// IRIS LocalAI — platform-independent llama.cpp chat engine.
#include "iris_engine.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <chrono>
#include <mutex>
#include <thread>

#include "ggml-backend.h"
#ifdef IRIS_HTP
#include <dlfcn.h>
#include <android/log.h>
#endif
#include "llama.h"
#ifdef IRIS_MTMD
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

#if defined(__linux__)
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#if defined(__ANDROID__)
#include <android/log.h>
#endif
#include <cerrno>
#include <cstdarg>
#include <cstdio>

namespace iris {

namespace {

using Clock = std::chrono::steady_clock;

int64_t us_since(Clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - t0).count();
}

void backend_init_once() {
    static std::once_flag flag;
    std::call_once(flag, [] {
        llama_backend_init();
#ifdef IRIS_HTP
        // 2.2.15: ggml-hexagon (upstream llama.cpp release build, same commit) is a separate shared
        // library loaded by the app right after this one (soname renamed to libiris-hexagon.so, its
        // libggml-base.so dependency pointed at us). Register its devices (HTP0 = type GPU).
        void *h = dlopen("libiris-hexagon.so", RTLD_NOW | RTLD_NOLOAD);
        if (!h) h = dlopen("libiris-hexagon.so", RTLD_NOW);
        auto reg_fn = h ? reinterpret_cast<ggml_backend_reg_t (*)()>(dlsym(h, "ggml_backend_hexagon_reg")) : nullptr;
        if (reg_fn) {
            ggml_backend_reg_t reg = reg_fn();
            if (reg) ggml_backend_register(reg);
        }
        __android_log_print(ANDROID_LOG_INFO, "IrisNative", "hexagon backend: %s (%zu devices)",
                            reg_fn ? "registered" : "not found", ggml_backend_dev_count());
#endif
    });
}

bool starts_with(const std::string &s, const std::string &prefix) {
    return !prefix.empty() && s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
}

bool ends_with(const std::string &s, const std::string &suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

int32_t auto_threads() {
    unsigned hc = std::thread::hardware_concurrency();
    if (hc == 0) return 4;
    // Leave headroom for the UI thread on 8-core phones.
    int32_t n = (int32_t) hc - (hc >= 8 ? 2 : 1);
    return std::clamp(n, 1, 8);
}

struct ProgressCtx {
    const ProgressCallback *cb;
};

bool progress_trampoline(float p, void *ud) {
    auto *pc = static_cast<ProgressCtx *>(ud);
    if (!pc || !pc->cb || !*pc->cb) return true;
    return (*pc->cb)(p);
}

LogSink g_log_sink = nullptr;
int g_log_min_level = 3;

// 2.2.14: OpenCL init/load progress for the Kotlin watchdog. ggml prints one "." (CONT) per
// compiled kernel group and "ggml_opencl: ..." lines; the counter moving = not hung.
std::atomic<int> g_gpu_ticks{0};
std::mutex g_gpu_log_mu;
std::string g_gpu_last;

void log_trampoline(enum ggml_log_level level, const char *text, void *) {
    if (text) {
        if ((int) level == GGML_LOG_LEVEL_CONT) {
            if (text[0] == '.') g_gpu_ticks.fetch_add(1, std::memory_order_relaxed);
        } else if (std::strncmp(text, "ggml_opencl", 11) == 0) {
            std::lock_guard<std::mutex> lk(g_gpu_log_mu);
            g_gpu_last.assign(text, strnlen(text, 240));
            while (!g_gpu_last.empty() && (g_gpu_last.back() == '\n' || g_gpu_last.back() == '\r')) g_gpu_last.pop_back();
            g_gpu_ticks.fetch_add(1, std::memory_order_relaxed);
        }
    }
    if (g_log_sink && (int) level >= g_log_min_level && (int) level != GGML_LOG_LEVEL_CONT) {
        g_log_sink((int) level, text);
    }
}

// 1.7.2: verifiable "the tier switch really reached the decoder" signal. On Android this goes to
// logcat under tag IrisResource (adb logcat -s IrisResource), on the host to stderr.
void resource_log(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
#if defined(__ANDROID__)
    __android_log_write(ANDROID_LOG_INFO, "IrisResource", buf);
#else
    std::fprintf(stderr, "[IrisResource] %s\n", buf);
#endif
}

#if defined(__linux__)
// "0-3,6" style list of the cores in `set`.
std::string cpuset_to_string(const cpu_set_t &set) {
    std::string out;
    int start = -1, prev = -2;
    auto flush = [&]() {
        if (start < 0) return;
        if (!out.empty()) out += ',';
        out += std::to_string(start);
        if (prev != start) out += "-" + std::to_string(prev);
    };
    for (int c = 0; c < CPU_SETSIZE; ++c) {
        if (!CPU_ISSET(c, &set)) continue;
        if (c != prev + 1) { flush(); start = c; }
        prev = c;
    }
    flush();
    return out.empty() ? "?" : out;
}
#endif

// 2.2.13: native renderers for templates llama_chat_apply_template (the non-jinja path) does not know.
bool is_gemma4_template(const std::string &t) {
    return t.find("<|turn>") != std::string::npos && t.find("<turn|>") != std::string::npos;
}
bool is_k2_template(const std::string &t) { return t.find("<|ifm|im_start|>") != std::string::npos; }

std::string trim_ws(const std::string &s) {
    const size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    const size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

// Gemma 4: <|turn>role\n…<turn|>\n, thinking switched on by <|think|> in the first system turn and
// off by an empty <|channel>thought block after the generation prompt (google-gemma-4 jinja).
std::string format_gemma4(const std::vector<ChatMessage> &messages, bool think) {
    std::string out;
    size_t i = 0;
    const bool sys = !messages.empty() && (messages[0].role == "system" || messages[0].role == "developer");
    if (think || sys) {
        out += "<|turn>system\n";
        if (think) out += "<|think|>\n";
        if (sys) { out += trim_ws(messages[0].content); i = 1; }
        out += "<turn|>\n";
    }
    for (; i < messages.size(); i++) {
        const std::string &r = messages[i].role;
        const std::string role = r == "assistant" ? "model" : (r == "system" || r == "user") ? r : "user";
        out += "<|turn>" + role + "\n" + trim_ws(messages[i].content) + "<turn|>\n";
    }
    out += "<|turn>model\n";
    if (!think) out += "<|channel>thought\n<channel|>";
    return out;
}

// K2 Horizon (IFM-K2-Horizon.jinja): <|ifm|im_start|>role\n…<|ifm|im_end|>, no separator; every assistant
// turn carries a (possibly empty) <ifm|think> block; the generation prompt opens one.
std::string format_k2(const std::vector<ChatMessage> &messages, bool think) {
    std::string out;
    for (const auto &m : messages) {
        if (m.role == "assistant") {
            out += "<|ifm|im_start|>assistant\n<ifm|think>\n</ifm|think>" + m.content + "<|ifm|im_end|>";
        } else {
            out += "<|ifm|im_start|>" + m.role + "\n" + m.content + "<|ifm|im_end|>";
        }
    }
    out += "<|ifm|im_start|>assistant\n<ifm|think>\n";
    if (!think) out += "</ifm|think>";
    return out;
}
}  // namespace

int gpu_init_ticks() { return g_gpu_ticks.load(std::memory_order_relaxed); }

std::string gpu_last_log() {
    std::lock_guard<std::mutex> lk(g_gpu_log_mu);
    return g_gpu_last;
}

void set_log_sink(LogSink sink, int min_level) {
    g_log_sink = sink;
    g_log_min_level = min_level;
    llama_log_set(log_trampoline, nullptr);
}

size_t utf8_complete_prefix(std::string_view s) {
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        const auto c = (unsigned char) s[i];
        size_t len;
        if (c < 0x80) len = 1;
        else if ((c >> 5) == 0x6) len = 2;
        else if ((c >> 4) == 0xE) len = 3;
        else if ((c >> 3) == 0x1E) len = 4;
        else len = 1;  // invalid lead / stray continuation byte: pass through, never block the stream
        if (i + len > n) break;  // incomplete trailing sequence: hold it back
        i += len;
    }
    return i;
}

size_t stop_holdback(std::string_view text, const std::vector<std::string> &stops) {
    size_t hold = 0;
    for (const auto &stop : stops) {
        if (stop.empty()) continue;
        const size_t max_k = std::min(stop.size() - 1, text.size());
        for (size_t k = max_k; k > hold; --k) {
            if (text.compare(text.size() - k, k, stop, 0, k) == 0) {
                hold = k;
                break;
            }
        }
    }
    return hold;
}

size_t find_first_stop(std::string_view text, size_t from, const std::vector<std::string> &stops) {
    size_t cut = std::string_view::npos;
    for (const auto &stop : stops) {
        if (stop.empty()) continue;
        const size_t pos = text.find(stop, from);
        if (pos < cut) cut = pos;
    }
    return cut;
}

Engine::~Engine() {
    std::lock_guard<std::mutex> lk(mu_);
    free_all();
}

void Engine::free_mmproj() {
#ifdef IRIS_MTMD
    if (mtmd_) mtmd_free(mtmd_);
#endif
    mtmd_ = nullptr;
    mtmd_gpu_ = false;
}

void Engine::free_all() {
    free_mmproj();
    mmproj_path_.clear();
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
    ctx_ = nullptr;
    model_ = nullptr;
    vocab_ = nullptr;
    template_.clear();
    kv_tokens_.clear();
    n_ctx_ = 0;
}

bool Engine::abort_cb(void *data) {
    auto *self = static_cast<Engine *>(data);
    return self->cancelled_.load(std::memory_order_relaxed);
}

bool Engine::load(const std::string &path, const LoadParams &params,
                  const ProgressCallback &progress, std::string &error) {
    std::lock_guard<std::mutex> lk(mu_);
    free_all();
    backend_init_once();
    cancelled_.store(false);
    pending_threads_.store(0);

    ProgressCtx pctx{&progress};
    llama_model_params mp = llama_model_default_params();
    gpu_name_.clear();
    // Explicit device list: the GPU when offloading, otherwise *no* accelerator at all. With the
    // default (all devices) llama.cpp would still initialise the OpenCL backend for a CPU-only
    // load (driver init + kernel setup, extra memory) even though nothing runs on it.
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    if (params.n_gpu_layers > 0) {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            const auto type = ggml_backend_dev_type(d);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                const char *desc = ggml_backend_dev_description(d);
                gpu_name_ = desc && *desc ? desc : ggml_backend_dev_name(d);
                devices[0] = d;
                break;
            }
        }
    }
    mp.devices = devices;
    mp.n_gpu_layers = devices[0] ? params.n_gpu_layers : 0;
    mp.load_mode = params.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    mp.progress_callback = progress_trampoline;
    mp.progress_callback_user_data = &pctx;

    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        gpu_name_.clear();
        error = "无法加载模型文件（文件损坏、格式不受支持或内存不足）";
        return false;
    }
    vocab_ = llama_model_get_vocab(model_);

    const int32_t n_train = llama_model_n_ctx_train(model_);
    int32_t n_ctx = params.n_ctx > 0 ? params.n_ctx : 4096;
    if (n_train > 0) n_ctx = std::min(n_ctx, n_train);
    n_ctx = std::max(n_ctx, 512);

    const int32_t threads = params.n_threads > 0 ? params.n_threads : auto_threads();
    threads_ = threads;
    n_batch_ = std::clamp(params.n_batch, 32, 2048);

    int32_t kv_type = params.kv_type >= 0 ? params.kv_type : (params.kv_q8 ? 1 : 0);
    if (!params.flash_attn) kv_type = 0;
    // A quantised V cache needs Flash Attention; when AUTO resolves to "off" for this model /
    // backend the context cannot be created. Retry once with the default F16 cache.
    if (!create_context(params, n_ctx, threads, kv_type) &&
        !(kv_type != 0 && create_context(params, n_ctx, threads, 0))) {
        llama_model_free(model_);
        model_ = nullptr;
        vocab_ = nullptr;
        gpu_name_.clear();
        error = "无法创建推理上下文（内存不足？可尝试减小上下文长度）";
        return false;
    }
    n_ctx_ = (int32_t) llama_n_ctx(ctx_);

    const char *tmpl = llama_model_chat_template(model_, nullptr);
    template_ = tmpl ? tmpl : "";

    // Verify that llama.cpp recognises the embedded template; otherwise use ChatML.
    use_chatml_fallback_ = template_.empty();
    if (!use_chatml_fallback_ && !is_gemma4_template(template_) && !is_k2_template(template_)) {
        llama_chat_message probe[] = {{"user", "hi"}};
        if (llama_chat_apply_template(template_.c_str(), probe, 1, true, nullptr, 0) < 0) {
            use_chatml_fallback_ = true;
        }
    }
    return true;
}

bool Engine::create_context(const LoadParams &params, int32_t n_ctx, int32_t threads, int32_t kv_type) {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = (uint32_t) n_batch_;
    // Physical batch: 512 is the sweet spot for the CPU (KleidiAI / llamafile kernels) and keeps
    // the compute buffer small; larger logical batches are still split by llama.cpp.
    cp.n_ubatch = (uint32_t) std::min(n_batch_, 512);
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.flash_attn_type = params.flash_attn ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    if (kv_type == 1) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
    } else if (kv_type == 2) {
        cp.type_k = GGML_TYPE_Q4_0;
        cp.type_v = GGML_TYPE_Q4_0;
    }
    cp.abort_callback = &Engine::abort_cb;
    cp.abort_callback_data = this;
    cp.no_perf = true;
    ctx_ = llama_init_from_model(model_, cp);
    return ctx_ != nullptr;
}

void Engine::unload() {
    cancel();
    std::lock_guard<std::mutex> lk(mu_);
    free_all();
}

void Engine::set_threads(int32_t n_threads) {
    if (n_threads <= 0) return;
    pending_threads_.store(n_threads, std::memory_order_release);
    // Apply right away when idle; a running generate() picks it up before its next decode.
    std::unique_lock<std::mutex> lk(mu_, std::try_to_lock);
    if (lk.owns_lock()) apply_pending_threads();
}

void Engine::apply_pending_threads() {
    const int32_t n = pending_threads_.exchange(0, std::memory_order_acq_rel);
    if (n > 0 && ctx_) llama_set_n_threads(ctx_, n, n);
}

void Engine::request_live_policy(int32_t n_threads, const std::vector<int32_t> &cpus, int32_t nice) {
    if (n_threads <= 0) return;
    {
        std::lock_guard<std::mutex> lk(policy_mu_);
        pending_policy_.threads = n_threads;
        pending_policy_.cpus = cpus;
        pending_policy_.nice = nice;
    }
    policy_pending_.store(true, std::memory_order_release);
    resource_log("live switch requested: threads=%d cpus=%zu nice=%d (applies before the next llama_decode)",
                 n_threads, cpus.size(), nice);
}

// Runs on the decoding thread, between two llama_decode calls (never while one is running), so
// llama_set_n_threads cannot race graph_compute(), which reads cparams.n_threads per graph.
bool Engine::apply_pending_policy(int32_t token_index) {
    if (!policy_pending_.load(std::memory_order_acquire)) return false;
    LivePolicy p;
    {
        std::lock_guard<std::mutex> lk(policy_mu_);
        if (!policy_pending_.exchange(false, std::memory_order_acq_rel)) return false;
        p = pending_policy_;
    }
    if (!ctx_ || p.threads <= 0) return false;
    pending_threads_.store(0, std::memory_order_release);  // superseded by this request
    llama_set_n_threads(ctx_, p.threads, p.threads);
    const int32_t n_now = llama_n_threads(ctx_);
#if defined(__linux__)
    cpu_set_t set;
    CPU_ZERO(&set);
    if (!p.cpus.empty()) {
        for (int32_t c : p.cpus) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
    } else {
        const long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        for (long c = 0; c < ncpu && c < CPU_SETSIZE; ++c) CPU_SET((int) c, &set);
    }
    const bool aff_ok = sched_setaffinity(0, sizeof(set), &set) == 0;
    const pid_t tid = (pid_t) syscall(SYS_gettid);
    const bool prio_ok = setpriority(PRIO_PROCESS, (id_t) tid, std::clamp<int32_t>(p.nice, -10, 19)) == 0;
    // Read back what the kernel actually holds for this thread: this is what the next graph's
    // freshly created ggml workers inherit.
    cpu_set_t now;
    CPU_ZERO(&now);
    const std::string cores = sched_getaffinity(0, sizeof(now), &now) == 0 ? cpuset_to_string(now) : "?";
    errno = 0;
    const int nice_now = getpriority(PRIO_PROCESS, (id_t) tid);
    resource_log("live switch APPLIED at token %d: tid=%d llama_n_threads=%d cpus=%s%s nice=%d%s",
                 token_index, (int) tid, n_now, cores.c_str(), aff_ok ? "" : " (setaffinity failed)",
                 nice_now, prio_ok ? "" : " (setpriority failed)");
#else
    resource_log("live switch APPLIED at token %d: llama_n_threads=%d (affinity/priority unsupported here)",
                 token_index, n_now);
#endif
    return true;
}

bool Engine::init_mmproj(bool use_gpu, std::string &error) {
#ifdef IRIS_MTMD
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu = use_gpu;
    mp.print_timings = false;
    mp.n_threads = threads_ > 0 ? threads_ : auto_threads();
    mp.warmup = false;
    mtmd_ = mtmd_init_from_file(mmproj_path_.c_str(), model_, mp);
    mtmd_gpu_ = mtmd_ != nullptr && use_gpu;
    if (mtmd_ && !mtmd_support_vision(mtmd_)) {
        free_mmproj();
        error = "该 mmproj 不含视觉编码器";
        return false;
    }
    if (!mtmd_) error = "无法加载 mmproj（与模型不匹配、文件损坏或内存不足）";
    return mtmd_ != nullptr;
#else
    (void) use_gpu;
    error = "此运行库不支持 mmproj";
    return false;
#endif
}

bool Engine::load_mmproj(const std::string &path, bool prefer_gpu, std::string &error) {
    std::lock_guard<std::mutex> lk(mu_);
    free_mmproj();
    mmproj_path_ = path;
    if (path.empty()) return true;
    if (!model_) { error = "模型未加载"; return false; }
    if (prefer_gpu && !gpu_name_.empty() && init_mmproj(true, error)) return true;
    error.clear();
    if (init_mmproj(false, error)) return true;
    mmproj_path_.clear();
    return false;
}

void Engine::reset() {
    std::lock_guard<std::mutex> lk(mu_);
    if (ctx_) llama_memory_clear(llama_get_memory(ctx_), true);
    kv_tokens_.clear();
}

ModelMeta Engine::meta() const {
    std::lock_guard<std::mutex> lk(mu_);
    ModelMeta m;
    if (!model_) return m;
    char buf[256];
    if (llama_model_desc(model_, buf, sizeof(buf)) > 0) m.description = buf;
    if (llama_model_meta_val_str(model_, "general.architecture", buf, sizeof(buf)) > 0) m.architecture = buf;
    m.n_params = llama_model_n_params(model_);
    m.size_bytes = llama_model_size(model_);
    m.n_ctx_train = llama_model_n_ctx_train(model_);
    m.n_ctx = n_ctx_;
    m.gpu = gpu_name_;
    m.has_chat_template = !use_chatml_fallback_;
    m.supports_thinking = template_.find("<think>") != std::string::npos ||
                          is_gemma4_template(template_) || is_k2_template(template_);
    if (m.supports_thinking) {
        const std::string probe = format_prompt({{"user", "hi"}}, false);
        m.thinking_prefilled = ends_with(probe, "<think>\n") || ends_with(probe, "<think>") ||
                               ends_with(probe, "<ifm|think>\n");
    }
    return m;
}

std::string Engine::format_prompt(const std::vector<ChatMessage> &messages, bool disable_thinking) const {
    if (is_gemma4_template(template_)) return format_gemma4(messages, !disable_thinking);
    if (is_k2_template(template_)) return format_k2(messages, !disable_thinking);
    std::vector<llama_chat_message> chat;
    chat.reserve(messages.size());
    for (const auto &m : messages) chat.push_back({m.role.c_str(), m.content.c_str()});

    const char *tmpl = use_chatml_fallback_ ? "chatml" : template_.c_str();
    int32_t need = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, nullptr, 0);
    std::string out;
    if (need > 0) {
        out.resize((size_t) need);
        int32_t wrote = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, out.data(), need);
        if (wrote < 0) out.clear();
        else out.resize((size_t) std::min(wrote, need));
    }
    if (out.empty()) {
        // Last resort: hand-written ChatML.
        for (const auto &m : messages) {
            out += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
        }
        out += "<|im_start|>assistant\n";
    }

    if (disable_thinking && template_.find("<think>") != std::string::npos) {
        // Same trick Qwen3 uses for enable_thinking=false: pre-fill an empty reasoning block.
        if (ends_with(out, "<think>\n")) out += "\n</think>\n\n";
        else out += "<think>\n\n</think>\n\n";
    }
    return out;
}

std::vector<int32_t> Engine::tokenize(const std::string &text) const {
    std::vector<int32_t> tokens;
    if (!vocab_) return tokens;
    bool add_bos = llama_vocab_get_add_bos(vocab_);
    if (add_bos) {
        const llama_token bos = llama_vocab_bos(vocab_);
        const char *bos_text = bos != LLAMA_TOKEN_NULL ? llama_vocab_get_text(vocab_, bos) : nullptr;
        // The chat template usually already starts with the BOS text; avoid a double BOS.
        if (bos_text && starts_with(text, bos_text)) add_bos = false;
    }
    int32_t n = -llama_tokenize(vocab_, text.c_str(), (int32_t) text.size(), nullptr, 0, add_bos, true);
    if (n <= 0) return tokens;
    tokens.resize((size_t) n);
    int32_t got = llama_tokenize(vocab_, text.c_str(), (int32_t) text.size(),
                                 tokens.data(), n, add_bos, true);
    if (got < 0) tokens.clear();
    else tokens.resize((size_t) got);
    return tokens;
}

void Engine::resync_after_failure() {
    // On abort llama_decode may leave already-processed ubatches in memory; drop everything
    // past what we track so kv_tokens_ stays the single source of truth.
    llama_memory_t mem = llama_get_memory(ctx_);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) kv_tokens_.size(), -1)) {
        llama_memory_clear(mem, true);
        kv_tokens_.clear();
    }
}

bool Engine::decode_tokens(const std::vector<int32_t> &tokens, size_t from, GenerateStats &st) {
    for (size_t i = from; i < tokens.size(); i += (size_t) n_batch_) {
        if (cancelled_.load(std::memory_order_relaxed)) {
            st.stop = StopReason::Cancelled;
            return false;
        }
        if (apply_pending_policy(-1)) st.live_switches++;
        const int32_t n = (int32_t) std::min<size_t>((size_t) n_batch_, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + i), n);
        const int32_t rc = llama_decode(ctx_, batch);
        if (rc != 0) {
            if (rc == 2 || cancelled_.load()) {
                st.stop = StopReason::Cancelled;
            } else {
                st.stop = StopReason::Error;
                st.error = "llama_decode 失败 (" + std::to_string(rc) + ")";
            }
            resync_after_failure();
            return false;
        }
        kv_tokens_.insert(kv_tokens_.end(), tokens.begin() + (long) i, tokens.begin() + (long) i + n);
    }
    return true;
}

bool Engine::shift_context(int32_t n_keep, GenerateStats &st) {
    // llama.cpp-style context shift: keep [0, n_keep) (attention sink + system prompt), drop half of
    // the rest and slide the remainder down. Needs a memory that supports position shifting
    // (not SWA-only / recurrent models); otherwise the caller stops with ContextFull.
    llama_memory_t mem = llama_get_memory(ctx_);
    if (!llama_memory_can_shift(mem)) return false;
    // 2.2.17: picture cells (-1) carry their own positions (M-RoPE etc.); never shift them.
    if (std::find(kv_tokens_.begin(), kv_tokens_.end(), -1) != kv_tokens_.end()) return false;
    const int32_t n_past = (int32_t) kv_tokens_.size();
    n_keep = std::clamp(n_keep, 0, n_past / 4);
    const int32_t n_left = n_past - n_keep;
    const int32_t n_discard = n_left / 2;
    if (n_discard <= 0) return false;
    if (!llama_memory_seq_rm(mem, 0, n_keep, n_keep + n_discard)) return false;
    llama_memory_seq_add(mem, 0, n_keep + n_discard, n_past, -n_discard);
    kv_tokens_.erase(kv_tokens_.begin() + n_keep, kv_tokens_.begin() + n_keep + n_discard);
    st.shifted_tokens += n_discard;
    return true;
}

bool Engine::reuse_prefix(std::vector<int32_t> &tokens, bool need_tail, GenerateStats &st) {
    llama_memory_t mem = llama_get_memory(ctx_);
    size_t n_common = 0;
    while (n_common < kv_tokens_.size() && n_common < tokens.size() &&
           kv_tokens_[n_common] == tokens[n_common]) {
        n_common++;
    }
    if (need_tail && n_common == tokens.size() && n_common > 0) n_common--;  // need at least one token to produce logits
    if (n_common < kv_tokens_.size()) {
        bool keep = n_common > 0;
        if (keep) {
            // Sliding-window attention only keeps the last n_swa positions: rolling back to
            // n_common is valid only if the window ending there is still materialised.
            const int32_t n_swa = llama_model_n_swa(model_);
            if (n_swa > 0) {
                const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
                if (pos_min > std::max<int32_t>(0, (int32_t) n_common - n_swa)) keep = false;
            }
        }
        if (!keep || !llama_memory_seq_rm(mem, 0, (llama_pos) n_common, -1)) {
            llama_memory_clear(mem, true);  // e.g. SWA / recurrent memory cannot be partially removed
            n_common = 0;
        }
        kv_tokens_.resize(n_common);
    }
    st.reused_tokens = (int32_t) n_common;

    return true;
}

bool Engine::prefill_media(const std::string &prompt, const std::vector<std::string> &images, GenerateStats &st) {
#ifdef IRIS_MTMD
    for (int attempt = 0; attempt < 2; ++attempt) {
        std::vector<mtmd_bitmap *> bitmaps;
        auto free_bitmaps = [&]() { for (auto *b : bitmaps) mtmd_bitmap_free(b); bitmaps.clear(); };
        for (const auto &path : images) {
            mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_file(mtmd_, path.c_str(), false, mtmd_helper_init_opt_default());
            if (w.video_ctx) mtmd_helper_video_free(w.video_ctx);
            if (!w.bitmap) {
                free_bitmaps();
                st.stop = StopReason::Error;
                st.error = "图片读取失败：" + path;
                return false;
            }
            bitmaps.push_back(w.bitmap);
        }
        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        bool add_bos = llama_vocab_get_add_bos(vocab_);
        if (add_bos) {
            const llama_token bos = llama_vocab_bos(vocab_);
            const char *bos_text = bos != LLAMA_TOKEN_NULL ? llama_vocab_get_text(vocab_, bos) : nullptr;
            if (bos_text && starts_with(prompt, bos_text)) add_bos = false;  // template already has BOS
        }
        mtmd_input_text txt{prompt.c_str(), prompt.size(), add_bos, true};
        const int32_t rc = mtmd_tokenize(mtmd_, chunks, &txt, (const mtmd_bitmap **) bitmaps.data(), bitmaps.size());
        free_bitmaps();
        if (rc != 0) {
            mtmd_input_chunks_free(chunks);
            st.stop = StopReason::Error;
            st.error = rc == 1 ? "图片数量与提示词中的占位符不一致" : "图片预处理失败 (" + std::to_string(rc) + ")";
            return false;
        }
        const size_t n_chunks = mtmd_input_chunks_size(chunks);
        st.prompt_tokens = (int32_t) mtmd_helper_get_n_tokens(chunks);
        // Leading text chunk: same KV prefix reuse as text-only turns (system prompt stays cached).
        size_t first = 0;
        if (n_chunks > 0 && mtmd_input_chunk_get_type(mtmd_input_chunks_get(chunks, 0)) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t nt = 0;
            const llama_token *tt = mtmd_input_chunk_get_tokens_text(mtmd_input_chunks_get(chunks, 0), &nt);
            std::vector<int32_t> head(tt, tt + nt);
            if (!reuse_prefix(head, n_chunks == 1, st) || !decode_tokens(head, (size_t) st.reused_tokens, st)) {
                mtmd_input_chunks_free(chunks);
                return false;
            }
            first = 1;
        } else {
            llama_memory_clear(llama_get_memory(ctx_), true);
            kv_tokens_.clear();
        }
        llama_pos n_past = (llama_pos) kv_tokens_.size();
        bool media_failed = false;
        for (size_t c = first; c < n_chunks; ++c) {
            if (cancelled_.load(std::memory_order_relaxed)) { st.stop = StopReason::Cancelled; break; }
            const mtmd_input_chunk *ch = mtmd_input_chunks_get(chunks, c);
            const bool is_text = mtmd_input_chunk_get_type(ch) == MTMD_INPUT_CHUNK_TYPE_TEXT;
            llama_pos np = n_past;
            const int32_t r = mtmd_helper_eval_chunk_single(mtmd_, ctx_, ch, n_past, 0, n_batch_, c + 1 == n_chunks, &np);
            if (r != 0) {
                if (cancelled_.load()) st.stop = StopReason::Cancelled;
                else { st.stop = StopReason::Error; st.error = "图片编码/解码失败 (" + std::to_string(r) + ")"; media_failed = !is_text; }
                break;
            }
            if (is_text) {
                size_t nt = 0;
                const llama_token *tt = mtmd_input_chunk_get_tokens_text(ch, &nt);
                kv_tokens_.insert(kv_tokens_.end(), tt, tt + nt);
            } else {
                // Media cells never match a text token, so a later turn reuses the cache only up to here.
                kv_tokens_.insert(kv_tokens_.end(), mtmd_input_chunk_get_n_tokens(ch), (int32_t) -1);
            }
            n_past = np;
        }
        mtmd_input_chunks_free(chunks);
        if (st.stop == StopReason::Cancelled || st.stop == StopReason::Error) {
            resync_after_failure();
            // GPU vision encoder failed: reload the projector on the CPU and try once more.
            if (media_failed && mtmd_gpu_ && attempt == 0) {
                std::string err;
                free_mmproj();
                if (init_mmproj(false, err)) {
                    __android_log_print(ANDROID_LOG_WARN, "IrisNative", "mtmd: GPU encode failed, retrying on CPU");
                    const int32_t dropped = st.dropped_messages;
                    st = GenerateStats{};
                    st.dropped_messages = dropped;
                    continue;
                }
            }
            return false;
        }
        st.stop = StopReason::EndOfGeneration;
        return true;
    }
    return false;
#else
    (void) prompt; (void) images;
    st.stop = StopReason::Error;
    st.error = "此运行库不支持图片";
    return false;
#endif
}

GenerateStats Engine::generate(const std::vector<ChatMessage> &input,
                               const SamplingParams &sp, const PieceCallback &on_piece) {
    std::lock_guard<std::mutex> lk(mu_);
    return generate_impl(input, sp, on_piece, nullptr, -1);
}

GenerateStats Engine::generate_vision(const std::vector<ChatMessage> &input, const SamplingParams &sp,
                                      const PieceCallback &on_piece, const std::vector<std::string> &images,
                                      int32_t image_msg) {
    std::lock_guard<std::mutex> lk(mu_);
    return generate_impl(input, sp, on_piece, images.empty() ? nullptr : &images, image_msg);
}

GenerateStats Engine::generate_impl(const std::vector<ChatMessage> &input, const SamplingParams &sp,
                                    const PieceCallback &on_piece, const std::vector<std::string> *images,
                                    int32_t image_msg) {
    GenerateStats st;
    if (!ctx_) {
        st.stop = StopReason::Error;
        st.error = "模型未加载";
        return st;
    }
    if (!sp.honor_prior_cancel) cancelled_.store(false, std::memory_order_release);
    apply_pending_threads();

    // ---- 1. Format & fit into the context window (drop oldest turns, keep system prompt) ----
    const int32_t reserve = std::clamp(sp.max_new_tokens, 64, n_ctx_ / 2);
    const int32_t budget = n_ctx_ - reserve;
    std::vector<ChatMessage> messages = input;
#ifdef IRIS_MTMD
    // 2.2.17: one media marker per picture in front of the user's text (mtmd splits on it).
    const bool vision = images && mtmd_ && image_msg >= 0 && image_msg < (int32_t) messages.size();
    if (vision) {
        std::string pre;
        for (size_t k = 0; k < images->size(); ++k) pre += std::string(mtmd_default_marker()) + "\n";
        messages[(size_t) image_msg].content = pre + messages[(size_t) image_msg].content;
    }
#else
    const bool vision = false;
    (void) images; (void) image_msg;
#endif
    std::string prompt;
    std::vector<int32_t> tokens;
    for (;;) {
        prompt = format_prompt(messages, sp.disable_thinking);
        tokens = tokenize(prompt);
        if ((int32_t) tokens.size() <= budget) break;
        // Find the oldest non-system message that is not the final user turn.
        auto it = std::find_if(messages.begin(), messages.end(),
                               [](const ChatMessage &m) { return m.role != "system"; });
        if (it == messages.end() || it + 1 >= messages.end()) {
            st.stop = StopReason::ContextFull;
            st.error = "输入内容超出上下文长度（" + std::to_string(tokens.size()) + " / " +
                       std::to_string(budget) + " tokens），请缩短内容或在设置中增大上下文";
            return st;
        }
        const bool was_user = it->role == "user";
        it = messages.erase(it);
        st.dropped_messages++;
        // Drop the reply that answered it as well: many templates (Gemma, Mistral, …) reject a
        // history that starts with an assistant turn or does not alternate, and then the whole
        // prompt would silently fall back to hand-written ChatML.
        while (was_user && it != messages.end() && it + 1 != messages.end() && it->role == "assistant") {
            it = messages.erase(it);
            st.dropped_messages++;
        }
    }
    if (tokens.empty()) {
        st.stop = StopReason::Error;
        st.error = "提示词分词失败";
        return st;
    }
    st.prompt_tokens = (int32_t) tokens.size();

    // ---- 2+3. KV-cache prefix reuse + prefill (2.2.17: pictures through mtmd) ----
    const auto t_prefill = Clock::now();
    if (vision) {
        if (!prefill_media(prompt, *images, st)) return st;
    } else {
        if (!reuse_prefix(tokens, true, st)) return st;
        if (!decode_tokens(tokens, (size_t) st.reused_tokens, st)) return st;
    }
    st.prefill_us = us_since(t_prefill);

    // ---- 4. Sampler chain (penalties → top-k → top-p → min-p → temperature → dist) ----
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sp.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (sp.repeat_penalty > 0.0f && sp.repeat_penalty != 1.0f && sp.repeat_last_n != 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                    llama_vocab_n_tokens(vocab_), sp.repeat_last_n, sp.repeat_penalty,
                    sp.frequency_penalty, sp.presence_penalty));
        } else if ((sp.frequency_penalty != 0.0f || sp.presence_penalty != 0.0f) && sp.repeat_last_n != 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                    llama_vocab_n_tokens(vocab_), sp.repeat_last_n, 1.0f,
                    sp.frequency_penalty, sp.presence_penalty));
        }
        if (sp.top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sp.top_k));
        if (sp.top_p > 0.0f && sp.top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sp.top_p, 1));
        if (sp.min_p > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sp.min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sp.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sp.seed));
    }

    // Text-level stop strings: needed when the end-of-turn marker is not an EOG token
    // (e.g. the ChatML fallback on a model that does not know ChatML).
    std::vector<std::string> stops;
    if (use_chatml_fallback_) stops = {"<|im_end|>", "<|im_start|>"};

    // ---- 5. Decode loop ----
    // With context shift the reply may run past the end of the window (old tokens are discarded).
    const int32_t max_new = sp.context_shift
            ? std::max(1, sp.max_new_tokens)
            : std::max(1, std::min(sp.max_new_tokens, n_ctx_ - (int32_t) kv_tokens_.size() - 1));
    // `pending` only holds text that has not been emitted yet (an incomplete UTF-8 sequence or a
    // possible stop-string prefix), so per-token work stays O(1) instead of growing with the reply.
    std::string pending;
    pending.reserve(64);
    std::vector<char> piece(128);

    auto emit = [&](size_t n) -> bool {
        if (n == 0) return true;
        const bool keep_going = on_piece ? on_piece(pending.data(), n) : true;
        pending.erase(0, n);
        return keep_going;
    };

    const auto t_gen = Clock::now();
    st.stop = StopReason::MaxTokens;
    for (int32_t i = 0; i < max_new; ++i) {
        if (cancelled_.load(std::memory_order_acquire)) { st.stop = StopReason::Cancelled; break; }

        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, tok)) { st.stop = StopReason::EndOfGeneration; break; }
        st.generated_tokens++;

        int32_t n = llama_token_to_piece(vocab_, tok, piece.data(), (int32_t) piece.size(), 0, false);
        if (n < 0) {
            piece.resize((size_t) -n);
            n = llama_token_to_piece(vocab_, tok, piece.data(), (int32_t) piece.size(), 0, false);
        }
        if (n > 0) pending.append(piece.data(), (size_t) n);

        // Stop strings: emitted text never ends in a stop prefix (held back below), so a match
        // can only start inside `pending`.
        if (!stops.empty()) {
            const size_t cut = find_first_stop(pending, 0, stops);
            if (cut != std::string::npos) {
                pending.resize(cut);
                st.stop = StopReason::EndOfGeneration;
                break;
            }
        }

        size_t safe = utf8_complete_prefix(pending);
        if (!stops.empty()) safe -= stop_holdback(std::string_view(pending).substr(0, safe), stops);
        if (!emit(safe)) { st.stop = StopReason::Cancelled; break; }

        if ((int32_t) kv_tokens_.size() >= n_ctx_ - 1) {
            if (!sp.context_shift || !shift_context(sp.n_keep, st)) { st.stop = StopReason::ContextFull; break; }
        }
        apply_pending_threads();
        if (apply_pending_policy(i + 1)) st.live_switches++;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        const int32_t rc = llama_decode(ctx_, batch);
        if (rc != 0) {
            if (rc == 2 || cancelled_.load()) st.stop = StopReason::Cancelled;
            else { st.stop = StopReason::Error; st.error = "llama_decode 失败 (" + std::to_string(rc) + ")"; }
            resync_after_failure();
            break;
        }
        kv_tokens_.push_back(tok);
    }
    // Flush whatever is left (drop a dangling, incomplete UTF-8 sequence).
    emit(utf8_complete_prefix(pending));
    st.generate_us = us_since(t_gen);
    st.kv_used = (int32_t) kv_tokens_.size();

    llama_sampler_free(smpl);
    return st;
}

}  // namespace iris

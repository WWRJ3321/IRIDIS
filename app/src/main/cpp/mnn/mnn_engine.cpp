#include "mnn_engine.h"
#include "../thread_policy.h"

#include <llm/llm.hpp>

#include <cstdarg>
#include <ctime>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ostream>
#include <sstream>
#include <streambuf>

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

// 1.7.4: MNN keeps its CPU worker pools in a process-global map keyed by core mask
// (source/backend/cpu/ThreadPool.cpp:17-33, MNN 3.6.1). A pool is created once, with the thread
// count and the creator's affinity/nice of the *first* load, and is never resized or destroyed by
// Llm::destroy. A reload with a new tier would silently reuse the old pool (ThreadPool::init
// returns min(requested, existing) threads). ThreadPool::destroy() is not in MNN's public headers,
// so it is bound by its mangled name; it is only called while no Llm exists in the process.
extern void iris_mnn_threadpool_destroy() __asm__("_ZN3MNN10ThreadPool7destroyEv");

namespace iris {

namespace {

std::atomic<int> gLoaded{0};

void resLog(const char* fmt, ...) {
    char buf[768];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_INFO, "IrisResource", buf);
#else
    std::fprintf(stderr, "IrisResource: %s\n", buf);
#endif
}

std::string cpusString(const std::vector<int>& cpus) {
    if (cpus.empty()) return "all";
    std::string o;
    for (int c : cpus) { if (!o.empty()) o += ','; o += std::to_string(c); }
    return o;
}

/** Value of a top-level key in MNN's dumped config JSON (number or string), "?" if absent. */
std::string jsonValue(const std::string& json, const std::string& key) {
    const std::string k = "\"" + key + "\"";
    size_t p = json.find(k);
    if (p == std::string::npos) return "?";
    p = json.find(':', p + k.size());
    if (p == std::string::npos) return "?";
    ++p;
    while (p < json.size() && (json[p] == ' ' || json[p] == '\t')) ++p;
    if (p < json.size() && json[p] == '"') {
        size_t e = json.find('"', p + 1);
        return e == std::string::npos ? "?" : json.substr(p + 1, e - p - 1);
    }
    size_t e = p;
    while (e < json.size() && json[e] != ',' && json[e] != '}' && json[e] != ' ') ++e;
    return json.substr(p, e - p);
}

/** Applies a tier policy to the calling thread and restores the previous one when it goes away. */
class ScopedPolicy {
public:
    ScopedPolicy(bool enabled, const std::vector<int>& cpus, int nice) : enabled_(enabled) {
        if (!enabled_) return;
        saved_ = tp::capture();
        mask_ = tp::apply(cpus, nice);
    }
    ~ScopedPolicy() {
        if (enabled_ && !tp::restore(saved_)) {
            resLog("MNN: could not fully restore tid=%d to %s (now %s)", tp::current_tid(), savedString().c_str(),
                   tp::describe(tp::current_tid()).c_str());
        }
    }
    int mask() const { return mask_; }
    std::string savedString() const {
        if (!enabled_) return "-";
        char b[160];
        snprintf(b, sizeof b, "cpus=%s nice=%d", saved_.haveAffinity ? tp::cpuset_string(saved_.affinity).c_str() : "?",
                 saved_.haveNice ? saved_.nice : 0);
        return b;
    }
private:
    bool enabled_;
    int mask_ = 0;
    tp::Snapshot saved_;
};

// 1.8.0 crash diagnostics: stage line → logcat + iris_mnn_load.log (append, fflush per line).
// The directory comes from MnnNative.nativeSetDiagDir (the app's external files dir, where the
// export button reads it back). Best-effort: without a directory it only logs to logcat.
std::mutex gDiagMu;
std::string gDiagPath;

void loadTrace(const std::string& line, bool /*flush*/) {
    resLog("%s", line.c_str());
    char stamp[24];
    timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    tm tmv{};
    localtime_r(&ts.tv_sec, &tmv);
    snprintf(stamp, sizeof stamp, "%02d:%02d:%02d.%03d ", tmv.tm_hour, tmv.tm_min, tmv.tm_sec,
             static_cast<int>(ts.tv_nsec / 1000000));
    std::lock_guard<std::mutex> lk(gDiagMu);
    if (gDiagPath.empty()) return;
    if (FILE* f = fopen(gDiagPath.c_str(), "a")) {
        fprintf(f, "%s%s\n", stamp, line.c_str());
        fflush(f);   // survives a SIGSEGV right after this line
        fclose(f);
    }
}

void resetPoolsIfIdle(const char* when) {
    if (gLoaded.load() != 0) return;
#ifdef IRIS_MNN_KEEP_POOLS   // host test only: reproduces the ≤1.7.3 behaviour
    (void) when;
    return;
#endif
    iris_mnn_threadpool_destroy();
    resLog("MNN thread pools destroyed (%s): the next load creates a fresh pool from the loading thread", when);
}

int64_t nowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string jsonEscape(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof b, "\\u%04x", c);
                    o += b;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o;
}

std::string fmtFloat(float v) {
    char b[32];
    snprintf(b, sizeof b, "%.4f", v);
    return b;
}

/** Collects everything MNN writes to its output stream; drained after every decode step. */
class CollectBuf : public std::streambuf {
public:
    std::string data;
protected:
    int_type overflow(int_type ch) override {
        if (ch != traits_type::eof()) data.push_back(static_cast<char>(ch));
        return ch;
    }
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        data.append(s, static_cast<size_t>(n));
        return n;
    }
};

bool endsWithTrimmed(const std::string& s, const std::string& suffix) {
    size_t end = s.size();
    while (end > 0 && (s[end - 1] == '\n' || s[end - 1] == ' ' || s[end - 1] == '\r')) end--;
    return end >= suffix.size() && s.compare(end - suffix.size(), suffix.size(), suffix) == 0;
}

} // namespace

std::string MnnStats::toJson() const {
    std::ostringstream o;
    o << "{\"promptTokens\":" << promptTokens << ",\"reusedTokens\":" << reusedTokens
      << ",\"generatedTokens\":" << generatedTokens << ",\"prefillUs\":" << prefillUs
      << ",\"generateUs\":" << generateUs << ",\"droppedMessages\":" << droppedMessages << ",\"visionUs\":" << visionUs
      << ",\"stop\":" << stop << ",\"error\":\"" << jsonEscape(error) << "\"}";
    return o.str();
}

MnnEngine::MnnEngine() = default;
MnnEngine::~MnnEngine() { unload(); }

size_t MnnEngine::utf8CompleteLen(const std::string& s) {
    size_t n = s.size();
    if (n == 0) return 0;
    // Walk back over at most 3 continuation bytes to the lead byte of the last code point.
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) break;
        i--;
        back++;
    }
    if (i == 0) return back >= 4 ? n : 0; // only continuation bytes: malformed, flush when long
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need = 1;
    if (lead >= 0xF0) need = 4;
    else if (lead >= 0xE0) need = 3;
    else if (lead >= 0xC0) need = 2;
    else return n; // ASCII lead: everything complete (stray continuation bytes are passed on)
    size_t have = n - (i - 1);
    return have >= need ? n : i - 1;
}

std::string MnnEngine::stripImageTags(const std::string& text, int* images) {
    std::string out;
    out.reserve(text.size());
    int n = 0;
    size_t pos = 0;
    while (true) {
        const size_t open = text.find("<img>", pos);
        if (open == std::string::npos) break;
        const size_t close = text.find("</img>", open + 5);
        if (close == std::string::npos) break;
        out.append(text, pos, open - pos);
        pos = close + 6;
        n++;
    }
    out.append(text, pos, std::string::npos);
    if (images != nullptr) *images = n;
    return out;
}

std::string MnnEngine::stripThink(const std::string& reply) {
    std::string r = reply;
    size_t close = r.rfind("</think>");
    if (close != std::string::npos) r = r.substr(close + 8);
    else if (r.find("<think>") != std::string::npos) r.clear(); // reasoning only, no answer yet
    size_t b = r.find_first_not_of(" \n\r\t");
    if (b == std::string::npos) return "";
    size_t e = r.find_last_not_of(" \n\r\t");
    return r.substr(b, e - b + 1);
}

int mnnLoadedCount() { return gLoaded.load(); }

// 1.8.0: called from JNI with the app's external files dir ("" = off). Lives in namespace iris so
// it matches mnn_engine.h; the actual state lives in the anonymous-namespace helpers above.
void setDiagDir(const std::string& dir) {
    std::lock_guard<std::mutex> lk(gDiagMu);
    gDiagPath = dir.empty() ? std::string() : dir + "/iris_mnn_load.log";
    if (!gDiagPath.empty()) {
        if (FILE* f = fopen(gDiagPath.c_str(), "a")) {
            fprintf(f, "---- diag logging on (%s) ----\n", gDiagPath.c_str());
            fclose(f);
        }
    }
}

// 1.8.0 crash-diag: strong overrides of libMNN's weak symbols. The JNI setter stores the app
// switch (禁用视觉模块); MNN's createLLM() probes irisNoVisual() before deciding to load visual.mnn.
static std::atomic<int> gIrisNoVisual{0};
extern "C" void irisSetNoVisual(int enabled) { gIrisNoVisual.store(enabled); }
extern "C" int irisNoVisual() { return gIrisNoVisual.load(); }

// 1.7.9 (IRIS MNN patch, OpenCLRuntime.cpp): Adreno perf hint of the last OpenCL context MNN created
// (0 low / 1 normal / 2 high / -1 none). Weak so the CPU-only host build links without it.
extern "C" __attribute__((weak)) int MNNIrisGetOpenCLPerfHint();

// 1.8.0 (IRIS MNN patch, llm.cpp): per-stage callback from inside Llm::load()/Omni::load()
// (tokenizer / llm.mnn graph / weights / visual.mnn …). The weak default in libMNN.a does nothing;
// libiris_mnn.so overrides it here so every stage lands in logcat and iris_mnn_load.log, flushed
// line by line — after a SIGSEGV the file shows exactly where loading died. libMNN.a also has a
// weak default, so the CPU-only host test build links fine without this override.
extern "C" void irisLlmStageHook(const char* stage) {
    resLog("MNN stage %s", stage);
    loadTrace(std::string("mnn ") + stage, true);
}

std::string MnnEngine::load(const MnnLoadParams& p) {
    unload();
    resetPoolsIfIdle("before load");
    params_ = p;
    poolTids_.clear();
    loggedGenPolicy_ = false;
    // 1.8.0: every stage below also goes to iris_mnn_load.log (append + fflush per line), so a
    // SIGSEGV during load leaves the progress on disk.
    loadTrace("===== load start: " + p.configPath + " backend=" + p.backend + " threads=" + std::to_string(p.threads) +
              " ctx=" + std::to_string(p.contextLength) + " mmap=" + (p.useMmap ? "1" : "0"), true);
    loadTrace("stage: create Llm (read config.json)", true);
    Llm* llm = Llm::createLLM(p.configPath);
    if (llm == nullptr) { loadTrace("FAILED: createLLM returned null", true); return "无法读取模型配置：" + p.configPath; }
    // 1.7.1: "cpu|high" — the resource tier's MNN power hint rides on the backend string.
    std::string backendType = p.backend, power = "normal";
    const size_t bar = backendType.find('|');
    if (bar != std::string::npos) { power = backendType.substr(bar + 1); backendType.resize(bar); }
    // 1.7.9: "opencl|normal|low" — third field = the tier's GPU perf hint (Adreno cl_qcom_perf_hint).
    // "cpu|normal|kleidi=0" — third field turns KleidiAI off (Oryon, or the previous nativeLoad died).
    std::string gpuHint;
    bool kleidi = backendType == "cpu";
    const size_t bar2 = power.find('|');
    if (bar2 != std::string::npos) {
        const std::string third = power.substr(bar2 + 1);
        power.resize(bar2);
        if (third.rfind("kleidi=", 0) == 0) kleidi = third == "kleidi=1";
        else gpuHint = third;
    }
    if (gpuHint != "low" && gpuHint != "normal" && gpuHint != "high") gpuHint.clear();
    if (power != "low" && power != "high") power = "normal";
    // 1.7.5: on the CPU, MNN's own core binding is switched off (power=normal) so that we alone decide
    // where the workers run. With power=high/low, CPURuntime::_validateCpuIds (CPUBackend.cpp:181-200)
    // fills mCpuIds, and CPURuntime::onConcurrencyBegin (391-411) → _bindCPUCore (81-113) re-pins every
    // worker to them whenever the *calling* thread id differs from the last one (mCurrentTID, reset to 0
    // by every _resetThreadPool, 115-125) — i.e. on the first run after each load and again whenever a
    // run comes from another thread, which would silently undo a live re-pin. power=normal leaves
    // mCpuIds empty, so _bindCPUCore returns at line 82 and never touches affinity. Power_Low has one
    // more effect (CPUBackend.cpp:462, no per-cluster work split), which pinning replaces.
    const std::string requestedPower = power;
    if (backendType == "cpu") power = "normal";
    params_.backend = backendType;
    power_ = power;
    std::ostringstream cfg;
    cfg << "{\"backend_type\":\"" << jsonEscape(backendType) << "\""
        << ",\"power\":\"" << power << "\""
        << ",\"thread_num\":" << std::max(1, p.threads)
        // 1.7.5 (IRIS MNN patch): with the threads pinned to one set of cores, split work evenly; MNN's
        // default (50) gives the first threads 2x work assuming they sit on the fastest cluster.
        << ",\"cpu_decrease_rate\":" << (backendType == "cpu" && !tp::cores_for(p.cpus, std::max(1, p.threads)).empty() ? 100 : 50)
        << ",\"precision\":\"low\",\"memory\":\"low\""
        // 2.0.3: Arm KleidiAI kernels (IRIS MNN patch hint; libMNN built with MNN_KLEIDIAI=ON).
        // Off on Oryon: KleidiAI::initKernelInfo executes SVE and SIGSEGVs inside this load.
        << ",\"enable_kleidiai\":" << (backendType == "cpu" && kleidi ? 1 : 0)
        << ",\"reuse_kv\":true,\"prompt_cache\":true"
        << ",\"max_all_tokens\":" << std::max(512, p.contextLength)
        << ",\"use_mmap\":" << (p.useMmap && !p.tmpDir.empty() ? "true" : "false");
    if (!p.tmpDir.empty()) cfg << ",\"tmp_path\":\"" << jsonEscape(p.tmpDir) << "\"";
    if (backendType == "opencl" && !gpuHint.empty()) cfg << ",\"iris_cl_perf_hint\":\"" << gpuHint << "\"";
    cfg << "}";
    llm->set_config(cfg.str());
    loadTrace(std::string("stage: set_config done → Llm::load() kleidi=") + (backendType == "cpu" && kleidi ? "1" : "0"), true);
    bool ok = false;
    {
        // 1.7.4: MNN's ThreadPool is created inside load() (Llm::initRuntime → createRuntimeManager →
        // CPURuntime::onReset → ThreadPool::init), so its workers inherit this thread's cores + nice.
        ScopedPolicy policy(p.applyPolicy, tp::cores_for(p.cpus, std::max(1, p.threads)), p.nice);
        const int tid = tp::current_tid();
        const std::string self = p.applyPolicy ? tp::describe(tid) : std::string("(policy not applied)");
        const std::vector<int> before = tp::task_ids();
        ok = llm->load();
        loadTrace(std::string("stage: Llm::load() returned ok=") + (ok ? "1" : "0"), true);
        poolTids_ = tp::new_tasks(before, tp::task_ids());
        {
            // 1.7.5: MNN's pool workers are std::threads started from this thread, so they carry its
            // name (Linux copies comm on clone). Anything else that appeared meanwhile is ignored.
            const std::string myName = tp::thread_comm(tid);
            std::lock_guard<std::mutex> lk(policyMu_);
            workerTids_.clear();
            for (int t : poolTids_) if (!myName.empty() && tp::thread_comm(t) == myName) workerTids_.push_back(t);
            threadsUsed_ = static_cast<int>(workerTids_.size()) + 1;
        }
        if (ok) {
            if (backendType != "opencl") gpuHint_ = "-";
            else {
                const int h = MNNIrisGetOpenCLPerfHint != nullptr ? MNNIrisGetOpenCLPerfHint() : -1;
                gpuHint_ = h == 0 ? "low" : h == 1 ? "normal" : h == 2 ? "high" : "none";
            }
            resLog("MNN load: gpu perf hint requested=%s effective=%s", gpuHint.empty() ? "default" : gpuHint.c_str(),
                   gpuHint_.c_str());
            const std::string dumped = llm->dump_config();
            resLog("MNN load: requested backend=%s thread_num=%d power=%s (tier hint %s%s) cpus=%s nice=%d | MNN config "
                   "read back: thread_num=%s power=%s backend_type=%s cpu_decrease_rate=%s",
                   backendType.c_str(), std::max(1, p.threads), power.c_str(), requestedPower.c_str(),
                   backendType == "cpu" ? ", MNN binding off: cores are ours" : "", cpusString(p.cpus).c_str(), p.nice,
                   jsonValue(dumped, "thread_num").c_str(), jsonValue(dumped, "power").c_str(),
                   jsonValue(dumped, "backend_type").c_str(), jsonValue(dumped, "cpu_decrease_rate").c_str());
            resLog("MNN load: loading tid=%d %s (policy mask=%d, was %s) | new native threads during load: %s | pool workers "
                   "tracked for live re-pinning: %d",
                   tid, self.c_str(), policy.mask(), policy.savedString().c_str(), tp::summarize(poolTids_).c_str(),
                   static_cast<int>(workerTids_.size()));
        }
    }
    if (p.applyPolicy) resLog("MNN load: loading tid=%d restored to %s", tp::current_tid(), tp::describe(tp::current_tid()).c_str());
    if (!ok) {
        loadTrace("FAILED: Llm::load() returned false", true);
        Llm::destroy(llm);
        poolTids_.clear();
        { std::lock_guard<std::mutex> lk(policyMu_); workerTids_.clear(); threadsUsed_ = 1; }
        resetPoolsIfIdle("after failed load");
        return "MNN 模型加载失败（" + backendType + "）：文件缺失/损坏、内存不足或该后端不支持此模型";
    }
    llm_ = llm;
    gLoaded.fetch_add(1);
    haveSampling_ = false;
    thinkingState_ = -1;

    // Probe the chat template: does it know a thinking switch, and does it open <think> itself?
    ChatMsgs probe{{"user", "hi"}};
    setThinking(true);
    std::string on = render(probe, true);
    setThinking(false);
    std::string off = render(probe, true);
    hasTemplate_ = !on.empty() && on != "hi";
    thinkingSwitch_ = on != off;
    supportsThinking_ = thinkingSwitch_ || on.find("<think>") != std::string::npos;
    thinkingPrefilled_ = supportsThinking_ && endsWithTrimmed(on, "<think>");
    return "";
}

void MnnEngine::unload() {
    if (llm_ != nullptr) {
        Llm::destroy(llm_);
        llm_ = nullptr;
        gLoaded.fetch_sub(1);
        poolTids_.clear();
        { std::lock_guard<std::mutex> lk(policyMu_); workerTids_.clear(); callerTid_ = 0; threadsUsed_ = 1; }
        resetPoolsIfIdle("after unload");
    }
}

void MnnEngine::reset() {
    if (llm_ != nullptr) llm_->reset();
}

std::string MnnEngine::meta() const {
    std::ostringstream o;
    o << "{\"supportsThinking\":" << (supportsThinking_ ? "true" : "false")
      << ",\"thinkingPrefilled\":" << (thinkingPrefilled_ ? "true" : "false")
      << ",\"hasChatTemplate\":" << (hasTemplate_ ? "true" : "false")
      << ",\"nCtx\":" << params_.contextLength
      << ",\"backend\":\"" << jsonEscape(params_.backend) << "\""
      << ",\"gpuHint\":\"" << gpuHint_ << "\"}";
    return o.str();
}

std::string MnnEngine::render(const ChatMsgs& msgs, bool addGen) const {
    if (llm_ == nullptr) return "";
    if (addGen) return llm_->apply_chat_template(msgs);
    // add_generation_prompt=false: render with an empty trailing assistant-free list.
    return llm_->apply_chat_template(msgs);
}

void MnnEngine::setThinking(bool enabled) {
    if (llm_ == nullptr || thinkingState_ == (enabled ? 1 : 0)) return;
    thinkingState_ = enabled ? 1 : 0;
    llm_->set_config(std::string("{\"jinja\":{\"context\":{\"enable_thinking\":") + (enabled ? "true" : "false") + "}}}");
}

void MnnEngine::applySampling(const MnnSampling& s) {
    if (haveSampling_ && s.temperature == lastSampling_.temperature && s.topP == lastSampling_.topP &&
        s.minP == lastSampling_.minP && s.topK == lastSampling_.topK && s.repeatPenalty == lastSampling_.repeatPenalty) {
        return;
    }
    std::ostringstream c;
    if (s.temperature <= 0.0f || s.topK == 1) {
        c << "{\"sampler_type\":\"greedy\"}";
    } else {
        c << "{\"sampler_type\":\"mixed\",\"mixed_samplers\":[\"penalty\",\"topK\",\"topP\",\"min_p\",\"temperature\"]"
          << ",\"temperature\":" << fmtFloat(s.temperature)
          << ",\"topK\":" << std::max(1, s.topK)
          << ",\"topP\":" << fmtFloat(std::min(1.0f, std::max(0.01f, s.topP)))
          << ",\"min_p\":" << fmtFloat(std::max(0.0f, s.minP))
          << ",\"penalty\":" << fmtFloat(std::max(1.0f, s.repeatPenalty)) << "}";
    }
    llm_->set_config(c.str());
    llm_->reset_sampler();
    lastSampling_ = s;
    haveSampling_ = true;
}

int MnnEngine::countTokens(const std::string& text) const {
    if (llm_ == nullptr) return 0;
    // 2.1.1: Omni::tokenizer_encode(string) runs the visual encoder for every <img> tag. Counting the
    // prompt that way encoded each picture once per count (2+ times per reply, more while dropping
    // old turns) before the real prefill, so a photo kept 「预填充」 busy for minutes. Count the text
    // with the plain tokenizer and budget a fixed allowance per picture instead.
    int images = 0;
    const std::string plain = stripImageTags(text, &images);
    return static_cast<int>(llm_->MNN::Transformer::Llm::tokenizer_encode(plain).size()) + images * kImageTokenAllowance;
}

MnnStats MnnEngine::generate(const ChatMsgs& input, const MnnSampling& s, const PieceFn& onPiece) {
    MnnStats st;
    if (llm_ == nullptr) { st.error = "模型未加载"; return st; }
    if (input.empty()) { st.error = "没有可发送的消息"; return st; }
    if (cancel_.load()) { st.stop = MNN_STOP_CANCEL; return st; }

    // 1.7.4: tier cores + nice on the generating thread for this call only (restored on return).
    // 1.7.5: never pin more busy threads than cores (spin-wait, see thread_policy.h cores_for).
    const std::vector<int> genCores = tp::cores_for(s.cpus, threadsUsed_);
    ScopedPolicy policy(s.applyPolicy, genCores, s.nice);
    // 1.7.5: register this thread for live re-pinning; the workers get this reply's policy too (they
    // normally already have it from load / an earlier live switch — this only repairs drift).
    struct CallerReg {
        MnnEngine* e;
        ~CallerReg() { std::lock_guard<std::mutex> lk(e->policyMu_); e->callerTid_ = 0; }
    } callerReg{this};
    {
        std::lock_guard<std::mutex> lk(policyMu_);
        callerTid_ = lastCallerTid_ = tp::current_tid();
        if (s.applyPolicy) for (int t : workerTids_) tp::apply_to(t, genCores, s.nice);
    }
    if (!loggedGenPolicy_) {
        loggedGenPolicy_ = true;
        const std::vector<int> all = tp::task_ids();
        std::vector<int> alive;
        for (int t : poolTids_) if (std::binary_search(all.begin(), all.end(), t)) alive.push_back(t);
        resLog("MNN generate: calling tid=%d %s (policy %s) | load-time threads still alive: %s",
               tp::current_tid(), tp::describe(tp::current_tid()).c_str(), s.applyPolicy ? "applied" : "off",
               tp::summarize(alive).c_str());
    }

    applySampling(s);
    if (thinkingSwitch_) setThinking(!s.disableThinking);

    // ---- Fit the conversation into the context window: drop the oldest turns first ----
    ChatMsgs msgs = input;
    const int ctx = std::max(512, params_.contextLength);
    const int reserve = std::min(std::max(64, s.maxNewTokens > 0 ? s.maxNewTokens : 512), ctx / 2);
    std::string prompt = render(msgs, true);
    int promptTokens = countTokens(prompt);
    while (promptTokens + reserve > ctx) {
        // first non-system message that is not the last one
        size_t idx = 0;
        while (idx < msgs.size() && msgs[idx].first == "system") idx++;
        if (idx >= msgs.size() - 1) break;
        msgs.erase(msgs.begin() + static_cast<long>(idx));
        st.droppedMessages++;
        // keep turns paired: an assistant message never leads the history
        while (idx < msgs.size() - 1 && msgs[idx].first == "assistant") {
            msgs.erase(msgs.begin() + static_cast<long>(idx));
            st.droppedMessages++;
        }
        prompt = render(msgs, true);
        promptTokens = countTokens(prompt);
    }
    if (promptTokens + 16 > ctx) {
        st.stop = MNN_STOP_CONTEXT;
        st.error = "消息太长，超出上下文长度（" + std::to_string(ctx) + " tokens）";
        return st;
    }
    st.promptTokens = promptTokens;

    // 2.1.1: a picture in the prompt → full prefill. MNN's prompt-cache delta path tokenizes the whole
    // prompt but prefills only the tail with mRoPE positions taken from the start of the prompt (wrong
    // positions for the vision tokens). reset() also zeroes vision_us so we can tell below whether the
    // picture was actually read.
    int imageTags = 0;
    stripImageTags(prompt, &imageTags);
    if (imageTags > 0) llm_->reset();

    CollectBuf buf;
    std::ostream os(&buf);
    const int64_t t0 = nowUs();
    // Prefill only (max_new_tokens = 0); decoding is stepped one token at a time below so that
    // cancellation, pacing and streaming happen between tokens. MNN's prompt cache reuses the KV
    // of the shared conversation prefix and only prefills the new part.
    llm_->response(msgs, &os, "", 0);
    const int64_t t1 = nowUs();
    st.prefillUs = t1 - t0;
    const auto* ctxInfo = llm_->getContext();
    if (ctxInfo == nullptr || ctxInfo->status == LlmStatus::INTERNAL_ERROR || ctxInfo->status == LlmStatus::NOT_LOADED) {
        st.error = "MNN 预填充失败";
        llm_->reset();
        return st;
    }
    st.visionUs = ctxInfo->vision_us;
    if (imageTags > 0) {
        resLog("MNN generate: %d picture(s), vision %lld ms, prefill %lld ms, prompt_len %d", imageTags,
               static_cast<long long>(ctxInfo->vision_us / 1000), static_cast<long long>(st.prefillUs / 1000), ctxInfo->prompt_len);
        if (ctxInfo->vision_us <= 0) {
            // Omni::visionProcess returns no tokens when the file cannot be decoded (or the model has no
            // vision tower): the reply would silently ignore the picture.
            st.error = "模型没有读到图片（MNN 视觉模块无法解码这张图片，或该模型不支持图片输入）";
            llm_->reset();
            return st;
        }
    }
    st.reusedTokens = std::max(0, promptTokens - ctxInfo->prompt_len);
    buf.data.clear();

    std::string pending;   // bytes waiting for the rest of a UTF-8 sequence
    std::string reply;
    bool stoppedByCallback = false;
    const int maxNew = s.maxNewTokens > 0 ? s.maxNewTokens : 1 << 30;
    st.stop = MNN_STOP_END;
    while (true) {
        if (cancel_.load()) { st.stop = MNN_STOP_CANCEL; break; }
        if (st.generatedTokens >= maxNew) { st.stop = MNN_STOP_MAX; break; }
        if (ctxInfo->all_seq_len + 1 >= ctx) { st.stop = MNN_STOP_CONTEXT; break; }
        // After the previous step MNN marks MAX_TOKENS_FINISHED (one-token budget); keep stepping.
        if (ctxInfo->status == LlmStatus::MAX_TOKENS_FINISHED || ctxInfo->status == LlmStatus::NORMAL_FINISHED) {
            const_cast<MNN::Transformer::LlmContext*>(ctxInfo)->status = LlmStatus::RUNNING;
        }
        llm_->generate(1);
        if (ctxInfo->status == LlmStatus::INTERNAL_ERROR) { st.stop = MNN_STOP_ERROR; st.error = "MNN 解码失败"; break; }
        const bool atStop = llm_->stoped();
        if (!atStop) st.generatedTokens++;
        if (!buf.data.empty()) {
            pending += buf.data;
            buf.data.clear();
            size_t ok = utf8CompleteLen(pending);
            if (ok > 0) {
                std::string piece = pending.substr(0, ok);
                pending.erase(0, ok);
                reply += piece;
                if (!onPiece(piece)) { stoppedByCallback = true; st.stop = MNN_STOP_CANCEL; break; }
            }
        }
        if (atStop) { st.stop = MNN_STOP_END; break; }
    }
    if (!pending.empty() && !stoppedByCallback && st.stop != MNN_STOP_CANCEL) {
        reply += pending;
        onPiece(pending);
    }
    st.generateUs = nowUs() - t1;

    // Keep MNN's prompt cache in step with what the app will send back next turn (the reply
    // without its reasoning), so the next request only prefills the new user message.
    ChatMsgs synced = msgs;
    synced.emplace_back("assistant", stripThink(reply));
    llm_->syncPromptCache(synced);
    return st;
}

std::string MnnLiveReport::toJson() const {
    std::ostringstream o;
    o << "{\"targets\":" << targets << ",\"affinityOk\":" << affinityOk << ",\"niceOk\":" << niceOk
      << ",\"gone\":" << gone << ",\"generating\":" << (generating ? "true" : "false")
      << ",\"coresWidened\":" << (coresWidened ? "true" : "false")
      << ",\"readback\":\"" << jsonEscape(readback) << "\"}";
    return o.str();
}

MnnLiveReport MnnEngine::applyLive(const std::vector<int>& cpus, int nice, bool allowSqueeze) {
    MnnLiveReport r;
    std::lock_guard<std::mutex> lk(policyMu_);
    const std::vector<int> cores = allowSqueeze ? cpus : tp::cores_for(cpus, threadsUsed_);
    r.coresWidened = cores.empty() && !cpus.empty();
    std::vector<int> targets = workerTids_;
    if (callerTid_ != 0) { targets.push_back(callerTid_); r.generating = true; }
    std::vector<int> alive, aliveWorkers;
    for (int t : targets) {
        r.targets++;
        const int m = tp::apply_to(t, cores, nice);
        if (m < 0) { r.gone++; continue; }
        if (m & 1) r.affinityOk++;
        if (m & 2) r.niceOk++;
        alive.push_back(t);
        if (t != callerTid_) aliveWorkers.push_back(t);
    }
    workerTids_ = aliveWorkers;   // drop exited / recycled-away threads
    r.readback = tp::summarize(alive);
    resLog("MNN live re-pin: cpus=%s nice=%d%s → %d/%d affinity, %d/%d nice, %d gone, generating=%s | read back: %s",
           cpusString(cpus).c_str(), nice, r.coresWidened ? " (fewer cores than threads → kept all cores)" : "",
           r.affinityOk, r.targets, r.niceOk, r.targets, r.gone, r.generating ? "yes" : "no", r.readback.c_str());
    return r;
}

std::vector<int> MnnEngine::threadIds() {
    std::lock_guard<std::mutex> lk(policyMu_);
    std::vector<int> out = workerTids_;
    if (lastCallerTid_ != 0) out.push_back(lastCallerTid_);
    return out;
}

} // namespace iris

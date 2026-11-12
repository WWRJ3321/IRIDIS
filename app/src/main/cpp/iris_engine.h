// IRIS LocalAI — platform-independent llama.cpp chat engine.
//
// This file has no JNI / Android dependency so it can be compiled and tested
// on any host (see native-test/). The JNI layer (jni_bridge.cpp) is a thin
// adapter on top of it.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_vocab;
struct mtmd_context;
struct common_speculative;

namespace iris {

struct ChatMessage {
    std::string role;     // "system" | "user" | "assistant"
    std::string content;
};

struct LoadParams {
    int32_t n_ctx = 4096;       // requested context; clamped to the model's training context
    int32_t n_threads = 0;      // 0 = auto
    int32_t n_batch = 512;
    bool use_mmap = true;
    bool flash_attn = true;     // AUTO when true, DISABLED when false
    int32_t n_gpu_layers = 0;   // >0 offloads that many layers to the GPU backend (OpenCL), if present
    bool kv_q8 = false;         // Q8_0 K/V cache (requires flash attention; falls back to F16)
    int32_t kv_type = -1;       // 1.7.1: 0 = F16, 1 = Q8_0, 2 = Q4_0 (-1 = follow kv_q8). Quantised needs FA.
    /// 2.5.0-beta9: >0 = MTP self-speculative decoding with up to this many draft tokens per step
    /// (models with nextn_predict_layers > 0; builds with IRIS_SPEC only). Any failure while setting
    /// it up leaves the model on normal decoding (see Engine::spec_status()).
    int32_t spec_draft = 0;
    /// 2.5.1: MoE 专家张量（ffn_*_exps）留在 CPU（mmap），GPU 只放其余部分；CPU-only 时专家本就 mmap。
    bool moe_cpu = false;
    /// 2.5.1: >0 覆盖激活专家数（<arch>.expert_used_count，clamp 到 [1, expert_count]）；0 = 模型默认；非 MoE 忽略。
    int32_t n_expert_used = 0;
    /// 2.5.1: true = 加载时不预读（无 MAP_POPULATE、整文件 MADV_RANDOM），专家按需读盘。moe_cpu 时强制 true。
    bool no_prefetch = false;
};

struct SamplingParams {
    float temperature = 0.7f;   // <= 0 → greedy
    int32_t top_k = 40;
    float top_p = 0.95f;
    float min_p = 0.05f;
    float repeat_penalty = 1.1f;
    int32_t repeat_last_n = 64;
    int32_t max_new_tokens = 2048;
    float frequency_penalty = 0.0f;  // OpenAI-style, applied over the last repeat_last_n tokens
    float presence_penalty = 0.0f;
    /// Context shift: when the KV cache fills up during generation, keep the first `n_keep`
    /// tokens (system prompt / attention sink) and discard half of the rest instead of stopping.
    bool context_shift = false;
    int32_t n_keep = 4;
    uint32_t seed = 0xFFFFFFFF; // LLAMA_DEFAULT_SEED (random)
    bool disable_thinking = false; // pre-fill an empty <think></think> block for reasoning models
    /// When true, a cancel() issued before generate() started is honoured instead of being
    /// cleared. The caller must then clear stale requests itself with clear_cancel() (the JNI
    /// layer does this so a "stop" tapped while the request is still queued is never lost).
    bool honor_prior_cancel = false;
};

enum class StopReason : int32_t {
    EndOfGeneration = 0,
    MaxTokens = 1,
    Cancelled = 2,
    ContextFull = 3,
    Error = 4,
};

struct GenerateStats {
    int32_t prompt_tokens = 0;     // tokens in the formatted prompt
    int32_t reused_tokens = 0;     // tokens served from the KV cache (prefix reuse)
    int64_t prefill_us = 0;
    int32_t generated_tokens = 0;
    int64_t generate_us = 0;
    int32_t dropped_messages = 0;  // history messages trimmed to fit the context window
    int32_t shifted_tokens = 0;    // tokens discarded by context shift during this generation
    int32_t kv_used = 0;           // tokens materialised in the KV cache when generation ended
    int32_t live_switches = 0;     // 1.7.2: resource-tier switches applied during this generation
    int32_t drafted_tokens = 0;    // 2.5.0-beta9: MTP draft tokens sent for verification
    int32_t accepted_tokens = 0;   // 2.5.0-beta9: of those, accepted by the target model
    StopReason stop = StopReason::EndOfGeneration;
    std::string error;
};

struct ModelMeta {
    std::string description;       // e.g. "qwen3 4B Q4_K - Medium"
    uint64_t n_params = 0;
    uint64_t size_bytes = 0;
    int32_t n_ctx_train = 0;
    int32_t n_ctx = 0;
    bool has_chat_template = false;
    bool supports_thinking = false;   // template mentions <think>
    bool thinking_prefilled = false;  // template already opens <think> (DeepSeek-R1 style)
    std::string architecture;
    std::string gpu;              // GPU device the model was offloaded to, empty when CPU-only
};

/// Receives UTF-8-safe text deltas. Return false to stop generation.
using PieceCallback = std::function<bool(const char *data, size_t len)>;
/// Receives load progress in [0,1]. Return false to abort loading.
using ProgressCallback = std::function<bool(float)>;

/// Routes llama.cpp / ggml logs. level: 2=info 3=warn 4=error (ggml_log_level). Messages below
/// `min_level` are dropped. Pass nullptr to silence everything.
using LogSink = void (*)(int level, const char *text);
void set_log_sink(LogSink sink, int min_level);
// 2.2.14: OpenCL progress ticks / last ggml_opencl log line (watchdog, hang report).
int gpu_init_ticks();
std::string gpu_last_log();

/// Returns the number of leading bytes of `s` that form complete UTF-8 sequences.
size_t utf8_complete_prefix(std::string_view s);

/// Longest suffix of `text` that is a proper prefix of any stop string.
size_t stop_holdback(std::string_view text, const std::vector<std::string> &stops);

/// Index of the earliest occurrence of any stop string in `text` at or after `from`, or npos.
size_t find_first_stop(std::string_view text, size_t from, const std::vector<std::string> &stops);

class Engine {
public:
    Engine() = default;
    ~Engine();
    Engine(const Engine &) = delete;
    Engine &operator=(const Engine &) = delete;

    /// Loads a GGUF model. Returns false and fills `error` on failure.
    bool load(const std::string &path, const LoadParams &params,
              const ProgressCallback &progress, std::string &error);
    void unload();
    bool loaded() const { return ctx_ != nullptr; }

    ModelMeta meta() const;

    /// Formats `messages` with the model's chat template, reuses the KV cache
    /// for the common prefix and streams the reply through `on_piece`.
    GenerateStats generate(const std::vector<ChatMessage> &messages,
                           const SamplingParams &sp, const PieceCallback &on_piece);

    /// 2.2.17: vision projector (mmproj) for the loaded model via llama.cpp mtmd. Only built into
    /// libiris_llama (IRIS_MTMD); elsewhere returns false with a message. GPU first when the model
    /// itself is on the GPU and `prefer_gpu`, then CPU. Empty path = drop the projector.
    bool load_mmproj(const std::string &path, bool prefer_gpu, std::string &error);
    bool has_vision() const { return mtmd_ != nullptr; }
    bool vision_on_gpu() const { return mtmd_gpu_; }
    /// Like generate(), with `images` (file paths) placed in front of message `image_msg`.
    GenerateStats generate_vision(const std::vector<ChatMessage> &messages, const SamplingParams &sp,
                                  const PieceCallback &on_piece, const std::vector<std::string> &images,
                                  int32_t image_msg);

    /// 2.5.0-beta9: "active n=<draft>" when MTP speculative decoding is on for the loaded model,
    /// "off" when not requested, otherwise "fallback: <reason>" (normal decoding is used).
    std::string spec_status() const;
    /// 2.5.0-beta9: MTP draft count the JNI layer passes to the next load() (0 = off).
    void set_spec_draft(int32_t n) { spec_cfg_.store(n < 0 ? 0 : n); }
    int32_t spec_draft_cfg() const { return spec_cfg_.load(); }
    /// 2.5.1: MoE 专家按需加载 / 激活专家数，JNI 在下次 load() 前设置（nativeSetMoe）。
    void set_moe(bool moe_cpu, int32_t n_expert_used) {
        moe_cpu_cfg_.store(moe_cpu);
        expert_used_cfg_.store(n_expert_used < 0 ? 0 : n_expert_used);
    }
    bool moe_cpu_cfg() const { return moe_cpu_cfg_.load(); }
    int32_t expert_used_cfg() const { return expert_used_cfg_.load(); }

    /// Thread-safe; may be called from any thread while generate() runs.
    void cancel() { cancelled_.store(true, std::memory_order_release); }
    /// Forgets a pending cancel() (see SamplingParams::honor_prior_cancel).
    void clear_cancel() { cancelled_.store(false, std::memory_order_release); }

    /// Changes the thread count used for subsequent decoding (e.g. thermal throttling).
    /// Never blocks: when a generation is running the change is applied at its next token.
    void set_threads(int32_t n_threads);

    /// 1.7.2: mid-generation resource-tier switch (GGUF on the CPU backend only — the caller
    /// decides). Never blocks and never touches llama state: it only records the request. The
    /// decoding thread applies it *between* two llama_decode calls (generate()/prefill loop):
    /// llama_set_n_threads + sched_setaffinity(0) + setpriority(gettid()) on itself. ggml (no
    /// OpenMP, no attached threadpool) creates a fresh worker pool from that thread for every
    /// graph, so the next token's workers use the new count, cores and nice value.
    /// `cpus` empty = all cores. Each applied switch is logged (tag IrisResource) with values
    /// read back from the kernel / llama.cpp.
    void request_live_policy(int32_t n_threads, const std::vector<int32_t> &cpus, int32_t nice);

    /// Drops the KV cache (e.g. when the user switches conversation).
    void reset();

    /// Exposed for tests.
    std::string format_prompt(const std::vector<ChatMessage> &messages, bool disable_thinking) const;
    std::vector<int32_t> tokenize(const std::string &text) const;

private:
    void free_all();
    void free_mmproj();
    bool init_mmproj(bool use_gpu, std::string &error);
    GenerateStats generate_impl(const std::vector<ChatMessage> &messages, const SamplingParams &sp,
                                const PieceCallback &on_piece, const std::vector<std::string> *images,
                                int32_t image_msg);
    bool reuse_prefix(std::vector<int32_t> &tokens, bool need_tail, GenerateStats &st);
    bool prefill_media(const std::string &prompt, const std::vector<std::string> &images, GenerateStats &st);
    bool decode_tokens(const std::vector<int32_t> &tokens, size_t from, GenerateStats &st);
    void resync_after_failure();
    void apply_pending_threads();
    bool apply_pending_policy(int32_t token_index);
    bool shift_context(int32_t n_keep, GenerateStats &st);
    bool create_context(const LoadParams &params, int32_t n_ctx, int32_t threads, int32_t kv_type);
    static bool abort_cb(void *data);
    void init_spec(const LoadParams &params);
    void free_spec();
    void spec_disable(const std::string &why);  // 2.5.0-beta10
    void spec_mem_rm(int32_t from);
    int32_t spec_decode(const std::vector<int32_t> &tokens, size_t from, size_t n, bool last_out);

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    const llama_vocab *vocab_ = nullptr;
    std::string template_;           // raw jinja template from GGUF metadata (may be empty)
    bool use_chatml_fallback_ = false;
    std::string gpu_name_;
    mtmd_context *mtmd_ = nullptr;   // 2.2.17 vision projector (IRIS_MTMD builds only)
    bool mtmd_gpu_ = false;
    std::string mmproj_path_;
    int32_t threads_ = 0;
    int32_t n_ctx_ = 0;
    int32_t n_batch_ = 512;
    std::vector<int32_t> kv_tokens_; // tokens currently materialised in the KV cache (seq 0)
    std::atomic<bool> cancelled_{false};
    std::atomic<int32_t> pending_threads_{0}; // set_threads() request not yet applied (0 = none)
    struct LivePolicy { int32_t threads = 0; std::vector<int32_t> cpus; int32_t nice = 0; };
    std::atomic<bool> policy_pending_{false};  // fast check on the decode path
    std::mutex policy_mu_;                     // guards pending_policy_ only (never held across decode)
    LivePolicy pending_policy_;
    // 2.5.0-beta9 MTP (IRIS_SPEC builds): draft context on the same model + common_speculative.
    llama_context *ctx_dft_ = nullptr;
    common_speculative *spec_ = nullptr;
    int32_t spec_n_ = 0;             // max draft tokens per step
    [[maybe_unused]] int32_t spec_req_ = 0; // LoadParams::spec_draft of the current load
    std::atomic<int32_t> spec_cfg_{0};
    std::atomic<bool> moe_cpu_cfg_{false};     // 2.5.1
    std::atomic<int32_t> expert_used_cfg_{0};  // 2.5.1
    bool spec_sync_ = false;         // draft context has seen every target decode since the last clear
    std::string spec_status_ = "off";
    mutable std::mutex mu_;          // serialises load/unload/generate/reset
};

}  // namespace iris

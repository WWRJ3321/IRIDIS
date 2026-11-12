// IRIS LocalAI — 2.5.1 知识库嵌入模型. See iris_embed.h.
#include "iris_embed.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <mutex>

#ifdef IRIS_VULKAN
#include <sys/system_properties.h>
#endif

#include "llama.h"

#define EMB_TAG "IrisEmbed"

namespace iris {

struct Embedder {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int n_embd = 0;
    int n_batch = 0;
    std::mutex mu;
};

namespace {

constexpr int kCtx = 2048;

void embed_backend_init() {
    static std::once_flag flag;
    std::call_once(flag, [] {
#ifdef IRIS_VULKAN
        // Same rule as iris_engine.cpp: keep Vulkan out on Qualcomm / crash-safe mode before the registry starts
        // (the embedder may be the first to touch llama.cpp in this process).
        if (!getenv("GGML_DISABLE_VULKAN")) {
            const char *force = getenv("IRIS_FORCE_VULKAN");
            const char *no_gpu = getenv("IRIS_NO_OPENCL");
            char soc[PROP_VALUE_MAX] = {0};
            __system_property_get("ro.soc.manufacturer", soc);
            std::string m(soc);
            std::transform(m.begin(), m.end(), m.begin(), [](unsigned char c) { return (char) std::tolower(c); });
            const bool qualcomm = m.find("qualcomm") != std::string::npos || m == "qti";
            if ((no_gpu && no_gpu[0] == '1') || (qualcomm && !(force && force[0] == '1'))) setenv("GGML_DISABLE_VULKAN", "1", 1);
        }
#endif
        llama_backend_init();
    });
}

void l2_normalize(const float *in, int n, std::vector<float> &out) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += (double) in[i] * in[i];
    const float inv = sum > 0.0 ? (float) (1.0 / std::sqrt(sum)) : 0.0f;
    out.resize(n);
    for (int i = 0; i < n; i++) out[i] = in[i] * inv;
}

} // namespace

Embedder *embed_load(const std::string &path, int threads, std::string &error) {
    embed_backend_init();
    llama_model_params mp = llama_model_default_params();
    // CPU only: an explicit empty device list, so no GPU backend is initialised for the embedder.
    static ggml_backend_dev_t no_devices[1] = {nullptr};
    mp.devices = no_devices;
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { error = "embedding model load failed"; return nullptr; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kCtx;
    cp.n_batch = kCtx;
    cp.n_ubatch = kCtx;
    cp.n_seq_max = 1;
    const int t = std::max(1, threads);
    cp.n_threads = t;
    cp.n_threads_batch = t;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED; // the model's own pooling (GGUF <arch>.pooling_type)
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); error = "embedding context init failed"; return nullptr; }
    if (llama_pooling_type(ctx) == LLAMA_POOLING_TYPE_RANK) {
        llama_free(ctx); llama_model_free(model); error = "not an embedding model (rank pooling)"; return nullptr;
    }
    auto *e = new Embedder();
    e->model = model;
    e->ctx = ctx;
    e->n_embd = llama_model_n_embd_out(model);
    e->n_batch = (int) std::min<uint32_t>(llama_n_ctx(ctx), (uint32_t) kCtx);
    __android_log_print(ANDROID_LOG_INFO, EMB_TAG, "loaded dim=%d pooling=%d threads=%d", e->n_embd, (int) llama_pooling_type(ctx), t);
    return e;
}

int embed_dim(const Embedder *e) { return e ? e->n_embd : 0; }

bool embed_text(Embedder *e, const std::string &text, std::vector<float> &out, std::string &error) {
    if (!e || !e->ctx) { error = "embedder not loaded"; return false; }
    std::lock_guard<std::mutex> lk(e->mu);
    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    const int len = (int) text.size();
    int n = -llama_tokenize(vocab, text.data(), len, nullptr, 0, true, false);
    if (n <= 0) n = len + 8;
    std::vector<llama_token> toks((size_t) n);
    n = llama_tokenize(vocab, text.data(), len, toks.data(), (int32_t) toks.size(), true, false);
    if (n <= 0) { error = "tokenize failed"; return false; }
    if (n > e->n_batch) {
        // Keep the head; LAST pooling reads the final token, so keep the EOS the tokenizer appended.
        const bool eos = llama_vocab_get_add_eos(vocab);
        const llama_token last = toks[(size_t) n - 1];
        n = e->n_batch;
        if (eos) toks[(size_t) n - 1] = last;
    }
    toks.resize((size_t) n);

    if (llama_memory_t mem = llama_get_memory(e->ctx)) llama_memory_clear(mem, true);
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = toks[(size_t) i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }
    batch.n_tokens = n;
    // llama_decode falls back to encode() for contexts without memory (non-causal embedding models).
    const int rc = llama_decode(e->ctx, batch);
    llama_batch_free(batch);
    if (rc < 0) { error = "decode failed"; return false; }

    const int d = e->n_embd;
    if (llama_pooling_type(e->ctx) == LLAMA_POOLING_TYPE_NONE) {
        // No pooling in the model: mean over the token embeddings.
        std::vector<double> acc((size_t) d, 0.0);
        int got = 0;
        for (int i = 0; i < n; i++) {
            const float *v = llama_get_embeddings_ith(e->ctx, i);
            if (!v) continue;
            for (int j = 0; j < d; j++) acc[(size_t) j] += v[j];
            got++;
        }
        if (got == 0) { error = "no token embeddings"; return false; }
        std::vector<float> mean((size_t) d);
        for (int j = 0; j < d; j++) mean[(size_t) j] = (float) (acc[(size_t) j] / got);
        l2_normalize(mean.data(), d, out);
    } else {
        const float *v = llama_get_embeddings_seq(e->ctx, 0);
        if (!v) { error = "no sequence embedding"; return false; }
        l2_normalize(v, d, out);
    }
    return true;
}

void embed_free(Embedder *e) {
    if (!e) return;
    {
        std::lock_guard<std::mutex> lk(e->mu);
        if (e->ctx) llama_free(e->ctx);
        if (e->model) llama_model_free(e->model);
        e->ctx = nullptr;
        e->model = nullptr;
    }
    delete e;
}

} // namespace iris

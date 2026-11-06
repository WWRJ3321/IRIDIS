// IRIDIS 2.4.0: JNI over upstream stable-diffusion.cpp (MIT, leejet/stable-diffusion.cpp) for 生图
// with FLUX.2 Klein / Z-Image style models (diffusion GGUF + Qwen3 text encoder GGUF + VAE).
// One context at a time; Kotlin (SdCppEngine) serialises calls. Only Java_* symbols are exported.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#include "stable-diffusion.h"

#define TAG "IrisSd"

// 2.4.3: the Qualcomm Hexagon build (IRIS_SDQ, libiris_sdq.so) binds to SdqNative so both libraries can
// live in one process without JNI symbol clashes.
#ifdef IRIS_SDQ
#define JNI_FN(n) Java_com_iris_localai_imagegen_SdqNative_##n
#else
#define JNI_FN(n) Java_com_iris_localai_imagegen_SdNative_##n
#endif

namespace {
std::mutex g_err_mu;
std::string g_last_error;

void set_error(const std::string &e) {
    std::lock_guard<std::mutex> lk(g_err_mu);
    g_last_error = e;
}

void log_cb(enum sd_log_level_t level, const char *text, void *) {
    if (!text) return;
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case SD_LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case SD_LOG_WARN: prio = ANDROID_LOG_WARN; break;
        case SD_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_write(prio, TAG, text);
    if (level == SD_LOG_ERROR) {
        std::string s(text);
        while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
        set_error(s);
    }
}

// Progress goes to the Kotlin listener of the generate call running on this thread.
struct ProgressSink {
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID on_step = nullptr;
};
thread_local ProgressSink t_sink;

void progress_cb(int step, int steps, float, void *) {
    ProgressSink &s = t_sink;
    if (!s.env || !s.listener || !s.on_step) return;
    s.env->CallVoidMethod(s.listener, s.on_step, (jint) step, (jint) steps);
    if (s.env->ExceptionCheck()) s.env->ExceptionClear();
}

std::once_flag g_init;
void init_once() {
    std::call_once(g_init, [] {
        sd_set_log_callback(log_cb, nullptr);
        sd_set_progress_callback(progress_cb, nullptr);
    });
}

struct JStr {
    JNIEnv *env; jstring js; const char *c;
    JStr(JNIEnv *e, jstring s) : env(e), js(s), c(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JStr() { if (c) env->ReleaseStringUTFChars(js, c); }
    const char *get() const { return (c && c[0]) ? c : nullptr; }
};
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
JNI_FN(nativeSystemInfo)(JNIEnv *env, jclass) {
    init_once();
    const char *s = sd_get_system_info();
    return env->NewStringUTF(s ? s : "");
}

JNIEXPORT jstring JNICALL
JNI_FN(nativeLastError)(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lk(g_err_mu);
    return env->NewStringUTF(g_last_error.c_str());
}

/** Loads diffusion model + LLM text encoder + VAE; returns the context handle or 0 (see nativeLastError). */
JNIEXPORT jlong JNICALL
JNI_FN(nativeLoad)(JNIEnv *env, jclass, jstring diffusion, jstring llm, jstring vae,
                                                   jstring backend, jint threads, jboolean flash_attn) {
    init_once();
    set_error("");
    JStr d(env, diffusion), l(env, llm), v(env, vae), b(env, backend);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.diffusion_model_path = d.get();
    p.llm_path = l.get();
    p.vae_path = v.get();
    p.backend = b.get();
#ifdef IRIS_SDQ
    // "backend|params_backend" (SdRules.HTP_LOWMEM): e.g. te=disk drops the encoder weights after conditioning.
    // static: kept alive in case the context holds on to the strings (one context at a time).
    static std::string be, pbe;
    be = b.get() ? b.get() : ""; pbe.clear();
    if (auto bar = be.find('|'); bar != std::string::npos) { pbe = be.substr(bar + 1); be.resize(bar); }
    p.backend = be.empty() ? nullptr : be.c_str();
    p.params_backend = pbe.empty() ? nullptr : pbe.c_str();
#endif
    p.n_threads = threads > 0 ? threads : sd_get_num_physical_cores();
    p.enable_mmap = true;
    p.flash_attn = flash_attn;
    p.diffusion_flash_attn = flash_attn;
#ifdef IRIS_SDQ
    // Hexagon path (PR #1970 README): direct-convolution VAE; the HTP kernels expect it.
    if (p.backend && std::strstr(p.backend, "HTP")) p.vae_conv_direct = true;
#endif
    sd_ctx_t *ctx = new_sd_ctx(&p);
    if (!ctx) {
        std::lock_guard<std::mutex> lk(g_err_mu);
        if (g_last_error.empty()) g_last_error = "new_sd_ctx failed";
    }
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
JNI_FN(nativeFree)(JNIEnv *, jclass, jlong h) {
    if (h) free_sd_ctx((sd_ctx_t *) (intptr_t) h);
}

JNIEXPORT void JNICALL
JNI_FN(nativeCancel)(JNIEnv *, jclass, jlong h) {
    if (h) sd_cancel_generation((sd_ctx_t *) (intptr_t) h, SD_CANCEL_ALL);
}

/**
 * Text-to-image. Returns width*height*3 RGB bytes (row-major), or null on failure / cancel.
 * [listener] gets onStep(step, steps) on this thread.
 */
JNIEXPORT jbyteArray JNICALL
JNI_FN(nativeGenerate)(JNIEnv *env, jclass, jlong h, jstring prompt, jstring negative,
                                                       jint width, jint height, jint steps, jfloat cfg, jlong seed,
                                                       jobject listener) {
    if (!h) return nullptr;
    auto *ctx = (sd_ctx_t *) (intptr_t) h;
    set_error("");
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    JStr pr(env, prompt), ng(env, negative);

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt = pr.c ? pr.c : "";
    g.negative_prompt = ng.c ? ng.c : "";
    g.width = width;
    g.height = height;
    g.seed = seed;
    g.batch_count = 1;
    g.sample_params.sample_steps = steps;
    g.sample_params.guidance.txt_cfg = cfg;
    g.sample_params.sample_method = sd_get_default_sample_method(ctx);
    g.sample_params.scheduler = sd_get_default_scheduler(ctx, g.sample_params.sample_method);
    // Phones: tile the VAE decode (FLUX.2 / Z-Image VAEs need ~1-2 GB untiled at 1024 px).
    g.vae_tiling_params.enabled = (width * height > 512 * 512);

    ProgressSink prev = t_sink;
    t_sink.env = env;
    t_sink.listener = listener;
    t_sink.on_step = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        t_sink.on_step = env->GetMethodID(cls, "onStep", "(II)V");
        env->DeleteLocalRef(cls);
        if (env->ExceptionCheck()) { env->ExceptionClear(); t_sink.on_step = nullptr; }
    }
#ifdef IRIS_SDQ
    sd_image_t *imgs = nullptr;
    int n_imgs = 0;
    bool ok = generate_image(ctx, &g, &imgs, &n_imgs);
    const sd_image_t *first = (ok && imgs && n_imgs > 0) ? &imgs[0] : nullptr;
#else
    sd_image_generation_result_t *res = nullptr;
    bool ok = generate_image(ctx, &g, &res);
    const sd_image_t *first = (ok && res && res->count > 0) ? &res->items[0].image : nullptr;
#endif
    t_sink = prev;

    jbyteArray out = nullptr;
    if (first && first->data) {
        const sd_image_t &im = *first;
        const size_t px = (size_t) im.width * im.height;
        out = env->NewByteArray((jsize) (px * 3));
        if (out) {
            if (im.channel == 3) {
                env->SetByteArrayRegion(out, 0, (jsize) (px * 3), (const jbyte *) im.data);
            } else {
                std::string rgb(px * 3, '\0');
                for (size_t i = 0; i < px; i++) {
                    for (int c = 0; c < 3; c++) rgb[i * 3 + c] = (char) im.data[i * im.channel + (c < (int) im.channel ? c : 0)];
                }
                env->SetByteArrayRegion(out, 0, (jsize) rgb.size(), (const jbyte *) rgb.data());
            }
        }
    } else {
        std::lock_guard<std::mutex> lk(g_err_mu);
        if (g_last_error.empty()) g_last_error = "generate_image failed";
    }
#ifdef IRIS_SDQ
    if (imgs) free_sd_images(imgs, n_imgs);
#else
    if (res) free_sd_image_generation_result(res);
#endif
    return out;
}

}  // extern "C"

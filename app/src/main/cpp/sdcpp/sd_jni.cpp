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
#include <vector>

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
    jmethodID on_preview = nullptr;  // 2.7.1: onPreview(int w, int h, byte[] rgb)
};
thread_local ProgressSink t_sink;

void progress_cb(int step, int steps, float, void *) {
    ProgressSink &s = t_sink;
    if (!s.env || !s.listener || !s.on_step) return;
    s.env->CallVoidMethod(s.listener, s.on_step, (jint) step, (jint) steps);
    if (s.env->ExceptionCheck()) s.env->ExceptionClear();
}

// 2.7.1: PREVIEW_PROJ frames (latent -> RGB linear map inside sd.cpp, no VAE) to the listener of this thread.
void preview_cb(int, int frame_count, sd_image_t *frames, bool, void *) {
    ProgressSink &s = t_sink;
    if (!s.env || !s.listener || !s.on_preview || frame_count < 1 || !frames || !frames[0].data) return;
    const sd_image_t &im = frames[0];
    const size_t px = (size_t) im.width * im.height;
    if (px == 0 || px > 4096u * 4096u) return;
    jbyteArray arr = s.env->NewByteArray((jsize) (px * 3));
    if (!arr) { if (s.env->ExceptionCheck()) s.env->ExceptionClear(); return; }
    if (im.channel == 3) {
        s.env->SetByteArrayRegion(arr, 0, (jsize) (px * 3), (const jbyte *) im.data);
    } else {
        std::string rgb(px * 3, '\0');
        for (size_t i = 0; i < px; i++)
            for (int c = 0; c < 3; c++) rgb[i * 3 + c] = (char) im.data[i * im.channel + (c < (int) im.channel ? c : 0)];
        s.env->SetByteArrayRegion(arr, 0, (jsize) rgb.size(), (const jbyte *) rgb.data());
    }
    s.env->CallVoidMethod(s.listener, s.on_preview, (jint) im.width, (jint) im.height, arr);
    if (s.env->ExceptionCheck()) s.env->ExceptionClear();
    s.env->DeleteLocalRef(arr);
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

#ifndef IRIS_SDQ
/**
 * 2.7.0: general import. Any path may be empty. [model] is a single-file checkpoint (SD1.x/SDXL, or SD3.5/FLUX.1
 * with separate encoders); [clipL]/[clipG]/[t5] are the CLIP/T5 encoders; [diffusion]/[llm]/[vae] as in nativeLoad.
 * sd.cpp detects the architecture from the tensor names.
 */
JNIEXPORT jlong JNICALL
JNI_FN(nativeLoadEx)(JNIEnv *env, jclass, jstring model, jstring clip_l, jstring clip_g, jstring t5,
                     jstring diffusion, jstring llm, jstring vae, jstring backend, jint threads, jboolean flash_attn) {
    init_once();
    set_error("");
    JStr m(env, model), cl(env, clip_l), cg(env, clip_g), t(env, t5), d(env, diffusion), l(env, llm), v(env, vae), b(env, backend);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = m.get();
    p.clip_l_path = cl.get();
    p.clip_g_path = cg.get();
    p.t5xxl_path = t.get();
    p.diffusion_model_path = d.get();
    p.llm_path = l.get();
    p.vae_path = v.get();
    p.backend = b.get();
    p.n_threads = threads > 0 ? threads : sd_get_num_physical_cores();
    p.enable_mmap = true;
    p.flash_attn = flash_attn;
    p.diffusion_flash_attn = flash_attn;
    sd_ctx_t *ctx = new_sd_ctx(&p);
    if (!ctx) {
        std::lock_guard<std::mutex> lk(g_err_mu);
        if (g_last_error.empty()) g_last_error = "new_sd_ctx failed";
    }
    return (jlong) (intptr_t) ctx;
}
#endif

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

#ifndef IRIS_SDQ
/** 2.7.1: capability level of this library (1 = nativeGenerate only; 2 = nativeGenerate2 with LoRA + preview). */
JNIEXPORT jint JNICALL
JNI_FN(nativeAbi)(JNIEnv *, jclass) { return 2; }

/**
 * 2.7.1: nativeGenerate plus user LoRAs ([loraPaths] .safetensors, [loraScales] multipliers; sd.cpp reads kohya,
 * diffusers/PEFT and LyCORIS LoKr/LoHa) and, when [preview], PREVIEW_PROJ frames of the denoised latent after
 * every step to listener.onPreview(w, h, rgb). Same result contract as nativeGenerate.
 */
JNIEXPORT jbyteArray JNICALL
JNI_FN(nativeGenerate2)(JNIEnv *env, jclass, jlong h, jstring prompt, jstring negative,
                        jint width, jint height, jint steps, jfloat cfg, jlong seed,
                        jobjectArray lora_paths, jfloatArray lora_scales, jboolean preview, jobject listener) {
    if (!h) return nullptr;
    auto *ctx = (sd_ctx_t *) (intptr_t) h;
    set_error("");
    sd_cancel_generation(ctx, SD_CANCEL_RESET);
    JStr pr(env, prompt), ng(env, negative);

    std::vector<std::string> paths;
    std::vector<sd_lora_t> loras;
    const jsize nl = lora_paths ? env->GetArrayLength(lora_paths) : 0;
    const jsize ns = lora_scales ? env->GetArrayLength(lora_scales) : 0;
    std::vector<float> scales((size_t) ns);
    if (ns > 0) env->GetFloatArrayRegion(lora_scales, 0, ns, scales.data());
    for (jsize i = 0; i < nl; i++) {
        auto js = (jstring) env->GetObjectArrayElement(lora_paths, i);
        if (!js) continue;
        const char *c = env->GetStringUTFChars(js, nullptr);
        if (c && c[0]) paths.emplace_back(c);
        else paths.emplace_back();
        if (c) env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    paths.resize((size_t) nl);
    for (jsize i = 0; i < nl; i++) {
        if (paths[(size_t) i].empty()) continue;
        sd_lora_t l{};
        l.is_high_noise = false;
        l.multiplier = i < ns ? scales[(size_t) i] : 1.0f;
        l.path = paths[(size_t) i].c_str();
        loras.push_back(l);
    }

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
    g.vae_tiling_params.enabled = (width * height > 512 * 512);
    g.loras = loras.empty() ? nullptr : loras.data();
    g.lora_count = (uint32_t) loras.size();

    ProgressSink prev = t_sink;
    t_sink.env = env;
    t_sink.listener = listener;
    t_sink.on_step = nullptr;
    t_sink.on_preview = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        t_sink.on_step = env->GetMethodID(cls, "onStep", "(II)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); t_sink.on_step = nullptr; }
        if (preview) {
            t_sink.on_preview = env->GetMethodID(cls, "onPreview", "(II[B)V");
            if (env->ExceptionCheck()) { env->ExceptionClear(); t_sink.on_preview = nullptr; }
        }
        env->DeleteLocalRef(cls);
    }
    if (t_sink.on_preview) sd_set_preview_callback(preview_cb, PREVIEW_PROJ, 1, true, false, nullptr);
    sd_image_generation_result_t *res = nullptr;
    bool ok = generate_image(ctx, &g, &res);
    if (t_sink.on_preview) sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, true, false, nullptr);
    const sd_image_t *first = (ok && res && res->count > 0) ? &res->items[0].image : nullptr;
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
                for (size_t i = 0; i < px; i++)
                    for (int c = 0; c < 3; c++) rgb[i * 3 + c] = (char) im.data[i * im.channel + (c < (int) im.channel ? c : 0)];
                env->SetByteArrayRegion(out, 0, (jsize) rgb.size(), (const jbyte *) rgb.data());
            }
        }
    } else {
        std::lock_guard<std::mutex> lk(g_err_mu);
        if (g_last_error.empty()) g_last_error = "generate_image failed";
    }
    if (res) free_sd_image_generation_result(res);
    return out;
}
#endif

}  // extern "C"

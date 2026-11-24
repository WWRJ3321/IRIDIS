// 3.0.0 Beta5: 生图后超分 — ESRGAN (RRDBNet, e.g. RealESRGAN_x4plus_anime_6B) via stable-diffusion.cpp's upscaler, CPU.
// Kotlin: com.iris.localai.imagegen.UpscaleNative. Input/output are packed RGB bytes.
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) 0
#define ANDROID_LOG_ERROR 0
#define ANDROID_LOG_DEBUG 0
#endif
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include "stable-diffusion.h"

#define TAG "iris_upscale"
static std::mutex g_mu;
static std::string g_err;
static std::atomic<int> g_progress{0};

static void on_log(sd_log_level_t level, const char* text, void*) {
    if (level >= SD_LOG_ERROR) { std::lock_guard<std::mutex> l(g_mu); g_err = text ? text : ""; }
    __android_log_print(level >= SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG, TAG, "%s", text ? text : "");
}
static void on_progress(int step, int steps, float, void*) {
    if (steps > 0) g_progress.store(step * 1000 / steps);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_iris_localai_imagegen_UpscaleNative_nativeLoad(JNIEnv* env, jclass, jstring jpath, jint threads, jint tile) {
    sd_set_log_callback(on_log, nullptr);
    sd_set_progress_callback(on_progress, nullptr);
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    { std::lock_guard<std::mutex> l(g_mu); g_err.clear(); }
    upscaler_ctx_t* ctx = new_upscaler_ctx(p, false, threads, tile, nullptr, nullptr);
    env->ReleaseStringUTFChars(jpath, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_iris_localai_imagegen_UpscaleNative_nativeScale(JNIEnv*, jclass, jlong h) {
    return h ? get_upscale_factor(reinterpret_cast<upscaler_ctx_t*>(h)) : 0;
}

/** rgb: width*height*3 bytes. Returns [w(4 LE) h(4 LE) rgb...] or null. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_iris_localai_imagegen_UpscaleNative_nativeUpscale(JNIEnv* env, jclass, jlong h, jbyteArray rgb, jint w, jint hgt) {
    if (!h) return nullptr;
    auto* ctx = reinterpret_cast<upscaler_ctx_t*>(h);
    jsize n = env->GetArrayLength(rgb);
    if (n != w * hgt * 3) return nullptr;
    auto* buf = new uint8_t[n];
    env->GetByteArrayRegion(rgb, 0, n, reinterpret_cast<jbyte*>(buf));
    sd_image_t in{(uint32_t)w, (uint32_t)hgt, 3, buf};
    g_progress.store(0);
    sd_image_generation_result_t* res = nullptr;
    bool ok = upscale(ctx, in, (uint32_t)get_upscale_factor(ctx), &res);
    delete[] buf;
    jbyteArray out = nullptr;
    if (ok && res && res->count > 0 && res->items[0].image.data) {
        sd_image_t o = res->items[0].image;
        size_t bytes = (size_t)o.width * o.height * o.channel;
        if (o.channel == 3) {
            out = env->NewByteArray((jsize)(bytes + 8));
            if (out) {
                uint8_t head[8];
                uint32_t ow = o.width, oh = o.height;
                memcpy(head, &ow, 4); memcpy(head + 4, &oh, 4);
                env->SetByteArrayRegion(out, 0, 8, reinterpret_cast<jbyte*>(head));
                env->SetByteArrayRegion(out, 8, (jsize)bytes, reinterpret_cast<jbyte*>(o.data));
            }
        } else { std::lock_guard<std::mutex> l(g_mu); g_err = "unexpected channels"; }
    }
    if (res) free_sd_image_generation_result(res);
    g_progress.store(1000);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_iris_localai_imagegen_UpscaleNative_nativeProgress(JNIEnv*, jclass) { return g_progress.load(); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_UpscaleNative_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_mu);
    return env->NewStringUTF(g_err.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_iris_localai_imagegen_UpscaleNative_nativeFree(JNIEnv*, jclass, jlong h) {
    if (h) free_upscaler_ctx(reinterpret_cast<upscaler_ctx_t*>(h));
}

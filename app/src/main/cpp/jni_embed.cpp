// IRIS LocalAI — 2.5.1 JNI for the 知识库 embedder (com.iris.localai.knowledge.KbEmbedNative).
// Lives in libiris_llama(.so / _compat.so) next to jni_bridge.cpp; text crosses as UTF-8 bytes.
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "iris_embed.h"

#define KB_JNI(n) Java_com_iris_localai_knowledge_KbEmbedNative_##n

namespace {
std::string bytes_to_string(JNIEnv *env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
KB_JNI(nativeEmbedLoad)(JNIEnv *env, jclass, jbyteArray path, jint threads) {
    std::string err;
    iris::Embedder *e = iris::embed_load(bytes_to_string(env, path), threads, err);
    if (!e) __android_log_print(ANDROID_LOG_WARN, "IrisEmbed", "load: %s", err.c_str());
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jfloatArray JNICALL
KB_JNI(nativeEmbed)(JNIEnv *env, jclass, jlong h, jbyteArray text) {
    auto *e = reinterpret_cast<iris::Embedder *>(h);
    if (!e) return nullptr;
    std::vector<float> v;
    std::string err;
    if (!iris::embed_text(e, bytes_to_string(env, text), v, err) || v.empty()) {
        __android_log_print(ANDROID_LOG_WARN, "IrisEmbed", "embed: %s", err.c_str());
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray((jsize) v.size());
    if (out) env->SetFloatArrayRegion(out, 0, (jsize) v.size(), v.data());
    return out;
}

JNIEXPORT jint JNICALL
KB_JNI(nativeEmbedDim)(JNIEnv *, jclass, jlong h) {
    return iris::embed_dim(reinterpret_cast<iris::Embedder *>(h));
}

JNIEXPORT void JNICALL
KB_JNI(nativeEmbedFree)(JNIEnv *, jclass, jlong h) {
    iris::embed_free(reinterpret_cast<iris::Embedder *>(h));
}

} // extern "C"

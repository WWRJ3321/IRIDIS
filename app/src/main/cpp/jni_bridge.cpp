// IRIS LocalAI — JNI adapter for iris::Engine.
//
// All text crosses the JNI boundary as UTF-8 byte arrays: JNI's "modified UTF-8"
// (GetStringUTFChars / NewStringUTF) mangles emoji and other supplementary characters.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>
#include <cstdio>
#include <string>
#include <vector>

#include "iris_engine.h"
#include "thread_policy.h"

#define LOG_TAG "IrisNative"

// 2.2.15: the same JNI surface is built twice: libiris_llama.so (CPU + OpenCL, LlamaNative) and
// libiris_lhtp.so (CPU + Hexagon NPU, LlamaHtpNative; -DIRIS_HTP).
#ifdef IRIS_HTP
#define IRIS_JNI(n) Java_com_iris_localai_engine_llama_LlamaHtpNative_##n
#else
#define IRIS_JNI(n) Java_com_iris_localai_engine_llama_LlamaNative_##n
#endif

namespace {

JavaVM *g_vm = nullptr;
jclass g_string_cls = nullptr;     // global ref, resolved once in JNI_OnLoad
jmethodID g_string_ctor = nullptr; // String(byte[], String charset)
jstring g_utf8 = nullptr;          // global ref to "UTF-8"

void log_sink(int level, const char *text) {
    int prio = level >= 4 ? ANDROID_LOG_ERROR : level == 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(prio, LOG_TAG, text);
}

std::string bytes_to_string(JNIEnv *env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jstring utf8_to_jstring(JNIEnv *env, const std::string &s) {
    // Build via new String(bytes, UTF_8) to stay correct for every code point.
    if (!g_string_cls || !g_string_ctor || !g_utf8) return env->NewStringUTF(s.c_str());
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (!arr) return nullptr;  // OutOfMemoryError pending; Java side sees it
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    auto out = (jstring) env->NewObject(g_string_cls, g_string_ctor, arr, g_utf8);
    env->DeleteLocalRef(arr);
    return out;
}

std::string json_escape(const std::string &s) {
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
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    o += buf;
                } else {
                    o += (char) c;
                }
        }
    }
    return o;
}

iris::Engine *engine(jlong h) { return reinterpret_cast<iris::Engine *>(h); }

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    if (jclass cls = env->FindClass("java/lang/String")) {
        g_string_cls = (jclass) env->NewGlobalRef(cls);
        g_string_ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    if (jstring cs = env->NewStringUTF("UTF-8")) {
        g_utf8 = (jstring) env->NewGlobalRef(cs);
        env->DeleteLocalRef(cs);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    iris::set_log_sink(log_sink, 3);
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
IRIS_JNI(nativeGpuTicks)(JNIEnv *, jclass) {
    return (jint) iris::gpu_init_ticks();
}

JNIEXPORT jstring JNICALL
IRIS_JNI(nativeGpuLastLog)(JNIEnv *env, jclass) {
    return env->NewStringUTF(iris::gpu_last_log().c_str());
}

JNIEXPORT jlong JNICALL
IRIS_JNI(nativeCreate)(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new iris::Engine());
}

JNIEXPORT void JNICALL
IRIS_JNI(nativeDestroy)(JNIEnv *, jobject, jlong h) {
    delete engine(h);
}

/** Returns null on success, an error message otherwise. */
JNIEXPORT jstring JNICALL
IRIS_JNI(nativeLoad)(
        JNIEnv *env, jobject, jlong h, jbyteArray jpath, jint n_ctx, jint n_threads,
        jint n_batch, jboolean flash_attn, jint n_gpu_layers, jint kv_type, jboolean use_mmap, jobject listener) {
    auto *e = engine(h);
    if (!e) return utf8_to_jstring(env, "engine handle is null");

    iris::LoadParams lp;
    lp.n_ctx = n_ctx;
    lp.n_threads = n_threads;
    lp.n_batch = n_batch;
    lp.flash_attn = flash_attn == JNI_TRUE;
    lp.n_gpu_layers = n_gpu_layers;
    lp.kv_type = std::clamp<jint>(kv_type, 0, 2);
    lp.kv_q8 = lp.kv_type == 1;
    lp.use_mmap = use_mmap == JNI_TRUE;
    lp.spec_draft = e->spec_draft_cfg();  // 2.5.0-beta9 (nativeSetMtp)
    lp.moe_cpu = e->moe_cpu_cfg();               // 2.5.1 (nativeSetMoe)
    lp.n_expert_used = e->expert_used_cfg();
    lp.no_prefetch = lp.moe_cpu;

    jmethodID on_progress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        on_progress = env->GetMethodID(cls, "onProgress", "(F)Z");
        if (!on_progress) env->ExceptionClear();  // NoSuchMethodError: load without progress
        env->DeleteLocalRef(cls);
    }
    iris::ProgressCallback progress = [&](float p) -> bool {
        if (!listener || !on_progress) return true;
        jboolean keep = env->CallBooleanMethod(listener, on_progress, (jfloat) p);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };

    std::string err;
    if (!e->load(bytes_to_string(env, jpath), lp, progress, err)) {
        return utf8_to_jstring(env, err.empty() ? "unknown error" : err);
    }
    return nullptr;
}

/** 2.5.0-beta9: MTP draft tokens per step for the next nativeLoad (0 = off). */
JNIEXPORT void JNICALL
IRIS_JNI(nativeSetMtp)(JNIEnv *, jobject, jlong h, jint n_draft) {
    if (auto *e = engine(h)) e->set_spec_draft(n_draft);
}

/** 2.5.1: MoE 专家留 CPU 按需加载 + 激活专家数（0 = 模型默认），作用于下次 nativeLoad。 */
JNIEXPORT void JNICALL
IRIS_JNI(nativeSetMoe)(JNIEnv *, jobject, jlong h, jboolean moe_cpu, jint n_expert_used) {
    if (auto *e = engine(h)) e->set_moe(moe_cpu == JNI_TRUE, n_expert_used);
}

/** 2.5.0-beta9: "off" | "active n=<draft>" | "fallback: <reason>" for the loaded model. */
JNIEXPORT jstring JNICALL
IRIS_JNI(nativeMtpStatus)(JNIEnv *env, jobject, jlong h) {
    auto *e = engine(h);
    return env->NewStringUTF(e ? e->spec_status().c_str() : "off");
}

JNIEXPORT void JNICALL
IRIS_JNI(nativeUnload)(JNIEnv *, jobject, jlong h) {
    if (auto *e = engine(h)) e->unload();
}

JNIEXPORT void JNICALL
IRIS_JNI(nativeCancel)(JNIEnv *, jobject, jlong h) {
    if (auto *e = engine(h)) e->cancel();
}

JNIEXPORT void JNICALL
IRIS_JNI(nativeClearCancel)(JNIEnv *, jobject, jlong h) {
    if (auto *e = engine(h)) e->clear_cancel();
}

JNIEXPORT void JNICALL
IRIS_JNI(nativeSetThreads)(JNIEnv *, jobject, jlong h, jint n) {
    if (auto *e = engine(h)) e->set_threads(n);
}

// 1.7.2: records a mid-generation tier switch; the decoding thread applies it between two decodes.
JNIEXPORT void JNICALL
IRIS_JNI(nativeRequestLivePolicy)(
        JNIEnv *env, jobject, jlong h, jint threads, jintArray jcpus, jint nice) {
    auto *e = engine(h);
    if (!e) return;
    std::vector<int32_t> cpus;
    const jsize n = jcpus ? env->GetArrayLength(jcpus) : 0;
    if (n > 0) {
        std::vector<jint> tmp((size_t) n);
        env->GetIntArrayRegion(jcpus, 0, n, tmp.data());
        cpus.assign(tmp.begin(), tmp.end());
    }
    e->request_live_policy(threads, cpus, nice);
}

JNIEXPORT void JNICALL
IRIS_JNI(nativeReset)(JNIEnv *, jobject, jlong h) {
    if (auto *e = engine(h)) e->reset();
}

JNIEXPORT jstring JNICALL
IRIS_JNI(nativeMeta)(JNIEnv *env, jobject, jlong h) {
    auto *e = engine(h);
    iris::ModelMeta m = e ? e->meta() : iris::ModelMeta{};
    std::string j = "{";
    j += "\"description\":\"" + json_escape(m.description) + "\",";
    j += "\"architecture\":\"" + json_escape(m.architecture) + "\",";
    j += "\"nParams\":" + std::to_string(m.n_params) + ",";
    j += "\"sizeBytes\":" + std::to_string(m.size_bytes) + ",";
    j += "\"nCtxTrain\":" + std::to_string(m.n_ctx_train) + ",";
    j += "\"nCtx\":" + std::to_string(m.n_ctx) + ",";
    j += std::string("\"hasChatTemplate\":") + (m.has_chat_template ? "true" : "false") + ",";
    j += std::string("\"supportsThinking\":") + (m.supports_thinking ? "true" : "false") + ",";
    j += std::string("\"thinkingPrefilled\":") + (m.thinking_prefilled ? "true" : "false") + ",";
    j += "\"gpu\":\"" + json_escape(m.gpu) + "\",";
    j += std::string("\"vision\":") + (e && e->has_vision() ? "true" : "false") + ",";
    j += std::string("\"visionGpu\":") + (e && e->vision_on_gpu() ? "true" : "false");
    j += "}";
    return utf8_to_jstring(env, j);
}

/**
 * roles/contents: UTF-8 byte arrays. f = [temperature, topP, minP, repeatPenalty],
 * i = [topK, repeatLastN, maxNewTokens, seed, disableThinking(, honorPriorCancel)].
 * Streams UTF-8 deltas to sink.onPiece(byte[]): Boolean and returns a JSON stats object.
 */
static jstring run_generate(JNIEnv *env, jlong h, jobjectArray roles, jobjectArray contents,
                            jfloatArray jf, jintArray ji, jobject sink,
                            const std::vector<std::string> *images, jint image_msg) {
    auto *e = engine(h);

    std::vector<iris::ChatMessage> msgs;
    const jsize n = roles ? env->GetArrayLength(roles) : 0;
    msgs.reserve((size_t) n);
    const jsize n_contents = contents ? env->GetArrayLength(contents) : 0;
    for (jsize k = 0; k < n && k < n_contents; ++k) {
        auto r = (jbyteArray) env->GetObjectArrayElement(roles, k);
        auto c = (jbyteArray) env->GetObjectArrayElement(contents, k);
        msgs.push_back({bytes_to_string(env, r), bytes_to_string(env, c)});
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    iris::SamplingParams sp;
    jfloat f[6] = {0.7f, 0.95f, 0.05f, 1.1f, 0.0f, 0.0f};
    jint i[8] = {40, 64, 2048, -1, 0, 0, 0, 4};
    if (jf) {
        const jsize nf = std::min<jsize>(env->GetArrayLength(jf), 6);
        if (nf >= 4) env->GetFloatArrayRegion(jf, 0, nf, f);
    }
    if (ji) {
        const jsize ni = std::min<jsize>(env->GetArrayLength(ji), 8);
        if (ni >= 5) env->GetIntArrayRegion(ji, 0, ni, i);
    }
    sp.temperature = f[0];
    sp.top_p = f[1];
    sp.min_p = f[2];
    sp.repeat_penalty = f[3];
    sp.top_k = i[0];
    sp.repeat_last_n = i[1];
    sp.max_new_tokens = i[2];
    sp.seed = (uint32_t) i[3];
    sp.disable_thinking = i[4] != 0;
    sp.honor_prior_cancel = i[5] != 0;
    sp.frequency_penalty = f[4];
    sp.presence_penalty = f[5];
    sp.context_shift = i[6] != 0;
    sp.n_keep = std::max<jint>(0, i[7]);

    jmethodID on_piece = nullptr;
    if (sink) {
        jclass cls = env->GetObjectClass(sink);
        on_piece = env->GetMethodID(cls, "onPiece", "([B)Z");
        if (!on_piece) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }

    iris::GenerateStats st;
    if (!e) {
        st.stop = iris::StopReason::Error;
        st.error = "engine handle is null";
    } else {
        iris::PieceCallback cb = [&](const char *data, size_t len) -> bool {
            if (!sink || !on_piece) return true;
            jbyteArray arr = env->NewByteArray((jsize) len);
            if (!arr) {  // OutOfMemoryError: stop instead of calling Java with a pending exception
                env->ExceptionClear();
                return false;
            }
            env->SetByteArrayRegion(arr, 0, (jsize) len, reinterpret_cast<const jbyte *>(data));
            jboolean keep = env->CallBooleanMethod(sink, on_piece, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            return keep == JNI_TRUE;
        };
        st = images ? e->generate_vision(msgs, sp, cb, *images, image_msg) : e->generate(msgs, sp, cb);
    }

    std::string j = "{";
    j += "\"promptTokens\":" + std::to_string(st.prompt_tokens) + ",";
    j += "\"reusedTokens\":" + std::to_string(st.reused_tokens) + ",";
    j += "\"prefillUs\":" + std::to_string(st.prefill_us) + ",";
    j += "\"generatedTokens\":" + std::to_string(st.generated_tokens) + ",";
    j += "\"generateUs\":" + std::to_string(st.generate_us) + ",";
    j += "\"droppedMessages\":" + std::to_string(st.dropped_messages) + ",";
    j += "\"shiftedTokens\":" + std::to_string(st.shifted_tokens) + ",";
    j += "\"kvUsed\":" + std::to_string(st.kv_used) + ",";
    j += "\"liveSwitches\":" + std::to_string(st.live_switches) + ",";
    j += "\"draftedTokens\":" + std::to_string(st.drafted_tokens) + ",";
    j += "\"acceptedTokens\":" + std::to_string(st.accepted_tokens) + ",";
    j += "\"stop\":" + std::to_string((int) st.stop) + ",";
    j += "\"error\":\"" + json_escape(st.error) + "\"";
    j += "}";
    return utf8_to_jstring(env, j);
}

JNIEXPORT jstring JNICALL
IRIS_JNI(nativeGenerate)(
        JNIEnv *env, jobject, jlong h, jobjectArray roles, jobjectArray contents,
        jfloatArray jf, jintArray ji, jobject sink) {
    return run_generate(env, h, roles, contents, jf, ji, sink, nullptr, -1);
}

#ifdef IRIS_MTMD
/** 2.2.17: loads (or, with an empty path, drops) the vision projector. Null on success, else the reason. */
JNIEXPORT jstring JNICALL
IRIS_JNI(nativeLoadMmproj)(JNIEnv *env, jobject, jlong h, jbyteArray jpath, jboolean prefer_gpu) {
    auto *e = engine(h);
    if (!e) return utf8_to_jstring(env, "engine handle is null");
    std::string err;
    if (!e->load_mmproj(bytes_to_string(env, jpath), prefer_gpu == JNI_TRUE, err))
        return utf8_to_jstring(env, err.empty() ? "unknown error" : err);
    return nullptr;
}

/** 2.2.17: nativeGenerate + picture files (UTF-8 paths) placed in front of message [imageMsg]. */
JNIEXPORT jstring JNICALL
IRIS_JNI(nativeGenerateVision)(
        JNIEnv *env, jobject, jlong h, jobjectArray roles, jobjectArray contents,
        jfloatArray jf, jintArray ji, jobjectArray jimages, jint image_msg, jobject sink) {
    std::vector<std::string> images;
    const jsize n = jimages ? env->GetArrayLength(jimages) : 0;
    for (jsize k = 0; k < n; ++k) {
        auto b = (jbyteArray) env->GetObjectArrayElement(jimages, k);
        images.push_back(bytes_to_string(env, b));
        env->DeleteLocalRef(b);
    }
    return run_generate(env, h, roles, contents, jf, ji, sink, &images, image_msg);
}
#endif

}  // extern "C"

// ---- 1.7.1: resource tiers ----------------------------------------------------------------------
// Pins the *calling* thread to `cpus` (empty = all cores) and sets its nice value. Worker threads
// that native libraries create afterwards (ggml's per-graph pool, XNNPACK, MNN) inherit both, so
// calling this on the engine's own thread before load / decode applies the tier to inference.
// Returns a bit mask: 1 = affinity applied, 2 = priority applied.
extern "C" JNIEXPORT jint JNICALL
IRIS_JNI(nativeSetThreadPolicy)(
        JNIEnv *env, jobject, jintArray jcpus, jint nice) {
    // 1.7.4: the body moved to thread_policy.h so libiris_mnn.so applies exactly the same policy.
    std::vector<int> cpus;
    const jsize n = jcpus ? env->GetArrayLength(jcpus) : 0;
    if (n > 0) {
        std::vector<jint> raw((size_t) n);
        env->GetIntArrayRegion(jcpus, 0, n, raw.data());
        cpus.assign(raw.begin(), raw.end());
    }
    return iris::tp::apply(cpus, nice);
}

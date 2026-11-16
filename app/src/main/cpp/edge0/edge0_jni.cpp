// IRIDIS 2.6.5: JNI bridge for edge0-infer (Edge0-35B, CPU, armv8.6-a+i8mm).
// Adapted from edge0-infer/examples/android/app/src/main/cpp/edge0_jni.cpp (Apache-2.0, Edge0 authors).
// Wraps open_runtime / load_model / create_session / generate (streamed answer, reasoning, tool calls).
#include <jni.h>
#include <android/log.h>
#include <edge0/assets.h>
#include <edge0/generation.h>
#include <nlohmann/json.hpp>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

using nlohmann::json;

namespace {
struct Engine {
    std::optional<edge0::RuntimeReference> runtime;
    edge0::ModelReference model;
    edge0::SessionReference session;
    std::mutex cancel_mutex;
    std::optional<edge0::CancelToken> cancel;
};
Engine& engine(jlong handle) { return *reinterpret_cast<Engine*>(handle); }
template<class T> T take(const edge0::Result<T>& value) {
    if (!value) throw std::runtime_error(value.error().message);
    return value.value();
}
std::string utf8(JNIEnv* env, jstring input) {
    if (!input) return {};
    const jchar* chars = env->GetStringChars(input, nullptr);
    const auto length = env->GetStringLength(input);
    std::string r;
    for (jsize i = 0; i < length; ++i) {
        unsigned c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && chars[i+1] >= 0xdc00 && chars[i+1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) r += static_cast<char>(c);
        else if (c < 0x800) { r += static_cast<char>(0xc0 | (c >> 6)); r += static_cast<char>(0x80 | (c & 63)); }
        else if (c < 0x10000) { r += static_cast<char>(0xe0 | (c >> 12)); r += static_cast<char>(0x80 | ((c >> 6) & 63)); r += static_cast<char>(0x80 | (c & 63)); }
        else { r += static_cast<char>(0xf0 | (c >> 18)); r += static_cast<char>(0x80 | ((c >> 12) & 63)); r += static_cast<char>(0x80 | ((c >> 6) & 63)); r += static_cast<char>(0x80 | (c & 63)); }
    }
    env->ReleaseStringChars(input, chars);
    return r;
}
jbyteArray bytes(JNIEnv* env, const std::string& v) {
    auto a = env->NewByteArray(static_cast<jsize>(v.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jbyte*>(v.data()));
    return a;
}
jstring jstr(JNIEnv* env, const std::string& v) {
    auto cls = env->FindClass("java/lang/String");
    auto ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto a = bytes(env, v);
    auto cs = env->NewStringUTF("UTF-8");
    auto r = static_cast<jstring>(env->NewObject(cls, ctor, a, cs));
    env->DeleteLocalRef(a); env->DeleteLocalRef(cs); env->DeleteLocalRef(cls);
    return r;
}
void fail(JNIEnv* env, const std::exception& e) {
    if (env->ExceptionCheck()) return;
    auto cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, e.what());
    env->DeleteLocalRef(cls);
}
void unload(Engine& v) {
    if (v.session) { take(edge0::release_session(v.session)); v.session.reset(); }
    if (v.model) { take(edge0::unload_model(v.model)); v.model.reset(); }
}
const char* stop_name(edge0::StopReason r) {
    switch (r) {
        case edge0::StopReason::end_of_generation: return "end";
        case edge0::StopReason::length: return "length";
        case edge0::StopReason::capacity: return "capacity";
        case edge0::StopReason::cancelled: return "cancelled";
        case edge0::StopReason::failed: return "failed";
        case edge0::StopReason::stop_string: return "stop";
        case edge0::StopReason::tool_calls: return "tool_calls";
    }
    return "failed";
}
// {"messages":[{"role","content","reasoning_content"?,"tool_call_id"?,"tool_calls"?:[{"id","name","arguments"}]}],
//  "tools":[<OpenAI tool object>...]}
edge0::ChatInput parse_input(const std::string& text) {
    auto j = json::parse(text);
    edge0::ChatInput in;
    for (const auto& m : j.at("messages")) {
        edge0::TextMessage t{m.value("role", "user"), m.value("content", "")};
        if (m.contains("reasoning_content") && m["reasoning_content"].is_string()) t.reasoning_content = m["reasoning_content"].get<std::string>();
        t.tool_call_id = m.value("tool_call_id", "");
        if (m.contains("tool_calls")) for (const auto& c : m["tool_calls"])
            t.tool_calls.push_back({c.value("id", ""), c.value("name", ""), c.value("arguments", "{}")});
        in.messages.push_back(std::move(t));
    }
    if (j.contains("tools")) for (const auto& t : j["tools"]) in.tools.push_back({t.dump()});
    return in;
}
}  // namespace

#define FN(name) Java_com_iris_localai_engine_edge0_Edge0Native_##name

extern "C" JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Engine());
}

// {"architecture","bytes","error"}
extern "C" JNIEXPORT jstring JNICALL FN(nativeInspect)(JNIEnv* env, jclass, jstring directory) {
    json o;
    try {
        auto r = edge0::inspect_assets({utf8(env, directory), false});
        if (r) {
            std::uint64_t size = 0;
            for (const auto& f : r.value().files) size += f.bytes;
            o["architecture"] = r.value().architecture; o["bytes"] = size;
        } else o["error"] = r.error().message;
    } catch (const std::exception& e) { o["error"] = e.what(); }
    return jstr(env, o.dump());
}

// open_runtime (CPU only) + load_model + create_session. Returns the model description as JSON.
extern "C" JNIEXPORT jstring JNICALL FN(nativeLoad)(JNIEnv* env, jclass, jlong handle, jstring directory, jlong context, jint threads) {
    try {
        auto& v = engine(handle);
        unload(v);
        if (!v.runtime) {
            edge0::RuntimeOptions opt;
            for (const auto& d : edge0::query_capabilities().devices)
                if (d.backend == "cpu") { opt.devices = {d.name}; break; }
            if (opt.devices.empty()) throw std::runtime_error("No CPU device");
            if (threads > 0) opt.cpu_threads = threads;
            opt.log_receiver = [](edge0::LogLevel, const std::string& m) { __android_log_write(ANDROID_LOG_INFO, "IrisEdge0", m.c_str()); };
            v.runtime = take(edge0::open_runtime(opt));
        }
        edge0::LoadOverrides ov; ov.device = v.runtime->available_devices().front();
        if (context > 0) ov.context = context;
        auto cfg = take(edge0::resolve_load_configuration(*v.runtime, utf8(env, directory), ov));
        v.model = take(edge0::load_model(*v.runtime, cfg));
        auto d = take(edge0::query_model(v.model));
        v.session = take(edge0::create_session(v.model));
        json o{{"context", d.context}, {"context_limit", d.context_limit}, {"thinking", d.thinking_enabled_supported},
               {"thinking_default", d.thinking_default}, {"tools", d.tools_supported}};
        return jstr(env, o.dump());
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL FN(nativeCancel)(JNIEnv*, jclass, jlong handle) {
    auto& v = engine(handle);
    std::lock_guard<std::mutex> lock(v.cancel_mutex);
    if (v.cancel) edge0::request_cancel(*v.cancel);
}

// Streams events to callback.onEvent(kind, index, utf8Bytes): 0 answer, 1 reasoning,
// 2 tool begin (name), 3 tool arguments fragment, 4 tool end ({"id","name","arguments"}), 5 rejected tool text.
// Returns {"stop","generated","input","reused","ttft","tps","error"}.
extern "C" JNIEXPORT jstring JNICALL FN(nativeGenerate)(JNIEnv* env, jclass, jlong handle, jstring input,
    jboolean thinking, jint max_tokens, jdouble temperature, jdouble top_p, jint top_k, jobject callback) {
    try {
        auto& v = engine(handle);
        if (!v.session) throw std::runtime_error("Model not loaded");
        auto token = take(edge0::create_cancel_token(*v.runtime));
        { std::lock_guard<std::mutex> lock(v.cancel_mutex); v.cancel = token; }
        auto chat = parse_input(utf8(env, input));
        edge0::GenerationOverrides ov;
        ov.thinking = thinking == JNI_TRUE;
        if (max_tokens > 0) ov.max_tokens = max_tokens;
        if (temperature >= 0) ov.temperature = temperature;
        if (top_p > 0) ov.top_p = top_p;
        if (top_k > 0) ov.top_k = top_k;
        auto cfg = take(edge0::resolve_generation_configuration(v.model, ov));
        auto cls = env->GetObjectClass(callback);
        auto method = env->GetMethodID(cls, "onEvent", "(II[B)V");
        auto send = [&](int kind, std::size_t index, const std::string& text) -> std::optional<edge0::Error> {
            auto data = bytes(env, text);
            env->CallVoidMethod(callback, method, kind, static_cast<jint>(index), data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return edge0::Error{edge0::ErrorCategory::invalid_argument, "callback failed"}; }
            return std::nullopt;
        };
        auto r = edge0::generate(v.session, chat, cfg, {}, [&](const edge0::OutputEvent& e) -> std::optional<edge0::Error> {
            switch (e.kind) {
                case edge0::OutputKind::answer: return send(0, e.index, e.text);
                case edge0::OutputKind::reasoning: return send(1, e.index, e.text);
                case edge0::OutputKind::tool_call_begin: return send(2, e.index, e.tool_call.name);
                case edge0::OutputKind::tool_call_arguments: return send(3, e.index, e.text);
                case edge0::OutputKind::tool_call_end:
                    return send(4, e.index, json{{"id", e.tool_call.id}, {"name", e.tool_call.name}, {"arguments", e.tool_call.arguments_json}}.dump());
                case edge0::OutputKind::tool_call_rejected: return send(5, e.index, e.rejected.text);
                default: return std::nullopt;
            }
        }, &token);
        env->DeleteLocalRef(cls);
        { std::lock_guard<std::mutex> lock(v.cancel_mutex); v.cancel.reset(); }
        json o;
        if (!r) { o["stop"] = "failed"; o["error"] = r.error().message; return jstr(env, o.dump()); }
        const auto& g = r.value();
        o["stop"] = stop_name(g.stop_reason);
        o["generated"] = g.generated_count; o["input"] = g.input.size(); o["reused"] = g.reused_tokens;
        o["ttft"] = g.request_summary.first_token_seconds.value_or(-1);
        o["tps"] = g.request_summary.decode_tokens_per_second.value_or(-1);
        if (g.error) o["error"] = g.error->message;
        // 2.6.7: 专家缓存命中 + 预取统计（确认没有无谓重复读取）
        const auto& wa = g.weight_activity;
        if (wa.applicable) {
            o["experts"] = {{"demand", wa.decode.demand}, {"hits", wa.decode.hits}, {"loads", wa.decode.loads}, {"bytes", wa.decode.bytes},
                            {"prefill_loads", wa.prefill.loads}, {"prefill_bytes", wa.prefill.bytes}};
            o["prefetch"] = {{"initiated", wa.prefetch.initiated}, {"late", wa.prefetch.late}, {"bytes", wa.prefetch.bytes}, {"discarded", wa.prefetch.discarded}};
            __android_log_print(ANDROID_LOG_INFO, "IrisEdge0",
                "experts decode demand=%zu hits=%zu (%.1f%%) loads=%zu read=%.1fMB | prefill loads=%zu read=%.1fMB | prefetch initiated=%zu late=%zu discarded=%zu read=%.1fMB",
                wa.decode.demand, wa.decode.hits, wa.decode.demand ? 100.0 * wa.decode.hits / wa.decode.demand : 0.0, wa.decode.loads, wa.decode.bytes / 1048576.0,
                wa.prefill.loads, wa.prefill.bytes / 1048576.0, wa.prefetch.initiated, wa.prefetch.late, wa.prefetch.discarded, wa.prefetch.bytes / 1048576.0);
        }
        return jstr(env, o.dump());
    } catch (const std::exception& e) {
        { std::lock_guard<std::mutex> lock(engine(handle).cancel_mutex); engine(handle).cancel.reset(); }
        fail(env, e); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL FN(nativeReset)(JNIEnv* env, jclass, jlong handle) {
    try { auto& v = engine(handle); if (v.session) take(edge0::reset_conversation(v.session)); }
    catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL FN(nativeUnload)(JNIEnv* env, jclass, jlong handle) {
    try { unload(engine(handle)); } catch (const std::exception& e) { fail(env, e); }
}
extern "C" JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv* env, jclass, jlong handle) {
    try {
        auto* v = &engine(handle);
        unload(*v);
        if (v->runtime) take(edge0::finish_runtime_use(*v->runtime));
        delete v;
    } catch (const std::exception& e) { fail(env, e); }
}

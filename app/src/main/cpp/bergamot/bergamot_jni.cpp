// 2.3.0 ③ 本地翻译: thin JNI wrapper around bergamot-translator (Mozilla / browsermt, MPL-2.0).
// Built separately (scripts/build-bergamot.sh) into libiris_bergamot.so; the Kotlin side is
// com.iris.localai.translate.BergamotNative. One handle = one loaded direction (model + shortlist
// + vocabs, given as a marian YAML config with absolute paths). translate() is blocking; the
// Kotlin side runs it on Dispatchers.IO, one paragraph at a time, and streams paragraphs.
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"

using namespace marian::bergamot;

namespace {
constexpr const char *TAG = "IrisBergamot";

struct Handle {
  std::unique_ptr<BlockingService> service;
  std::shared_ptr<TranslationModel> model;
  std::mutex lock;
};

std::string str(JNIEnv *env, jstring s) {
  if (!s) return {};
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string out(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

// GetStringUTFChars / NewStringUTF use modified UTF-8; build Java strings from real UTF-8 bytes.
jstring jstr(JNIEnv *env, const std::string &s) {
  jbyteArray bytes = env->NewByteArray((jsize)s.size());
  env->SetByteArrayRegion(bytes, 0, (jsize)s.size(), reinterpret_cast<const jbyte *>(s.data()));
  jclass cls = env->FindClass("java/lang/String");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
  jstring enc = env->NewStringUTF("UTF-8");
  auto out = (jstring)env->NewObject(cls, ctor, bytes, enc);
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(enc);
  env->DeleteLocalRef(cls);
  return out;
}

std::string utf8(JNIEnv *env, jbyteArray a) {
  if (!a) return {};
  jsize n = env->GetArrayLength(a);
  std::string out((size_t)n, '\0');
  env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&out[0]));
  return out;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_iris_localai_translate_BergamotNative_nativeLoad(JNIEnv *env, jclass, jstring config) {
  try {
    auto h = std::make_unique<Handle>();
    BlockingService::Config sc;
    sc.cacheSize = 0;
    h->service = std::make_unique<BlockingService>(sc);
    auto options = parseOptionsFromString(str(env, config), /*validate=*/false);
    h->model = std::make_shared<TranslationModel>(options, /*replicas=*/1);
    return reinterpret_cast<jlong>(h.release());
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "load failed: %s", e.what());
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "load failed");
  }
  return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_iris_localai_translate_BergamotNative_nativeTranslate(JNIEnv *env, jclass, jlong handle, jbyteArray text) {
  auto *h = reinterpret_cast<Handle *>(handle);
  if (!h) return nullptr;
  try {
    std::lock_guard<std::mutex> g(h->lock);
    std::vector<std::string> src{utf8(env, text)};
    std::vector<ResponseOptions> ro(1);
    auto r = h->service->translateMultiple(h->model, std::move(src), ro);
    return jstr(env, r.empty() ? std::string() : r[0].target.text);
  } catch (const std::exception &e) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "translate failed: %s", e.what());
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "translate failed");
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_iris_localai_translate_BergamotNative_nativeFree(JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<Handle *>(handle);
}

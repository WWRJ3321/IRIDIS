package com.iris.localai.imagegen

import androidx.annotation.Keep

/** 2.4.0: JNI surface of libiris_sd.so (upstream stable-diffusion.cpp, app/src/main/cpp/sdcpp/sd_jni.cpp). */
object SdNative {
    const val LIB = "iris_sd"

    /** Null when loaded, else why not (missing CPU features, or the library is not in this build). */
    val unavailableReason: String? by lazy {
        com.iris.localai.engine.llama.CpuFeatures.missingForLlama()?.let { return@lazy it }
        runCatching { System.loadLibrary(LIB); null }.getOrElse { it.message ?: it.toString() }
    }

    @Keep
    fun interface Progress { fun onStep(step: Int, steps: Int) }

    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeLoad(diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: Progress?,
    ): ByteArray?
}

package com.iris.localai.engine.llama

import androidx.annotation.Keep
import java.io.File

/** Called from native code — names/signatures are part of the JNI contract (kept in proguard-rules.pro). */
@Keep
fun interface PieceSink {
    /** UTF-8 bytes that always end on a code-point boundary. Return false to stop generation. */
    fun onPiece(utf8: ByteArray): Boolean
}

@Keep
fun interface LoadProgressListener {
    /** Progress in [0,1]. Return false to abort loading. */
    fun onProgress(progress: Float): Boolean
}

/** JNI surface of libiris_llama.so. */
@Keep
object LlamaNative {

    /** null when the library is usable, otherwise a user-facing reason. */
    val unavailableReason: String? by lazy { tryLoad() }

    private fun tryLoad(): String? {
        CpuFeatures.missingForLlama()?.let { return it }
        return try {
            System.loadLibrary("iris_llama")
            null
        } catch (t: Throwable) {
            "无法加载 llama.cpp 原生库：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 2.2.14: OpenCL init/compile progress ticks (changes = not hung) and the last ggml_opencl log line. */
    @JvmStatic external fun nativeGpuTicks(): Int
    @JvmStatic external fun nativeGpuLastLog(): String

    fun gpuTicks(): Int = if (unavailableReason == null) runCatching { nativeGpuTicks() }.getOrDefault(0) else 0
    fun gpuLastLog(): String = if (unavailableReason == null) runCatching { nativeGpuLastLog() }.getOrDefault("") else ""

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeLoad(
        handle: Long, pathUtf8: ByteArray, nCtx: Int, nThreads: Int, nBatch: Int,
        flashAttn: Boolean, nGpuLayers: Int, kvType: Int, useMmap: Boolean, listener: LoadProgressListener?,
    ): String?
    @JvmStatic external fun nativeUnload(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    /** Forgets a pending cancel; called right before a request whose own cancel flag is clear. */
    @JvmStatic external fun nativeClearCancel(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeSetThreads(handle: Long, threads: Int)
    /** 1.7.2: park a mid-reply tier switch; applied by the decoding thread before its next decode. */
    @JvmStatic external fun nativeRequestLivePolicy(handle: Long, threads: Int, cpus: IntArray, nice: Int)
    @JvmStatic external fun nativeMeta(handle: Long): String
    /** Pins the calling thread to [cpus] (empty = all) and sets its nice value; bit 1 = affinity ok, 2 = priority ok. */
    @JvmStatic external fun nativeSetThreadPolicy(cpus: IntArray, nice: Int): Int
    @JvmStatic external fun nativeGenerate(
        handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>,
        floats: FloatArray, ints: IntArray, sink: PieceSink?,
    ): String
}

/** The native library is compiled for armv8.2-a+dotprod+i8mm+fp16; refuse to load it elsewhere. */
object CpuFeatures {
    private val features: Set<String> by lazy {
        runCatching {
            // Every core repeats the same "Features" line; the first one is enough.
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')?.trim()?.split(' ', '\t')?.filter { it.isNotEmpty() }?.toSet()
            } ?: emptySet()
        }.getOrDefault(emptySet())
    }

    fun missingForLlama(): String? {
        if (features.isEmpty()) return null // cannot tell; let System.loadLibrary decide
        val required = mapOf("asimddp" to "dotprod", "i8mm" to "i8mm", "asimdhp" to "fp16")
        val missing = required.filterKeys { it !in features }.values
        return if (missing.isEmpty()) null
        else "此设备 CPU 缺少 ${missing.joinToString("、")} 指令集，无法运行 GGUF 引擎（需骁龙 8 Gen 1 及以上）"
    }

    val hasSve: Boolean get() = "sve" in features
}

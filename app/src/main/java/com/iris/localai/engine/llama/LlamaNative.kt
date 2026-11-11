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

    /** 2.5.0-beta8: which build was loaded ("iris_llama" or the no-i8mm "iris_llama_compat"), null before / on failure. */
    @Volatile var loadedLibrary: String? = null
        private set

    private fun tryLoad(): String? {
        CpuFeatures.missingForLlama()?.let { return it }
        val lib = CpuFeatures.llamaLibrary() ?: return CpuFeatures.missingForLlama()
        return try {
            System.loadLibrary(lib)
            loadedLibrary = lib
            android.util.Log.i("IrisLlama", "loaded lib$lib.so")
            null
        } catch (t: Throwable) {
            // 2.5.0: the regular APK ships without libiris_llama_compat.so; a no-i8mm CPU needs the legacy build.
            if (lib == CpuFeatures.LIB_COMPAT) {
                android.util.Log.w("IrisLlama", "lib$lib.so missing: ${t.message}")
                runCatching { com.iris.localai.I18n.s(com.iris.localai.R.string.v250_compat_needed) }
                    .getOrDefault("本机需安装老芯片兼容版")
            } else "无法加载 llama.cpp 原生库：${t.message ?: t.javaClass.simpleName}"
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
    /** 2.5.0-beta9: MTP draft tokens per step for the next [nativeLoad] (0 = off). */
    @JvmStatic external fun nativeSetMtp(handle: Long, nDraft: Int)
    /** 2.5.0-beta9: "off" | "active n=<draft>" | "fallback: <reason>". */
    @JvmStatic external fun nativeMtpStatus(handle: Long): String
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
    /** 2.2.17 (mtmd): loads the vision projector for the loaded model (empty path = drop it); null = ok, else the reason. */
    @JvmStatic external fun nativeLoadMmproj(handle: Long, pathUtf8: ByteArray, preferGpu: Boolean): String?
    /** 2.2.17: [nativeGenerate] with picture files placed in front of message [imageMsg]. */
    @JvmStatic external fun nativeGenerateVision(
        handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>,
        floats: FloatArray, ints: IntArray, images: Array<ByteArray>, imageMsg: Int, sink: PieceSink?,
    ): String
}

/**
 * CPU features from /proc/cpuinfo. 2.5.0-beta8: libiris_llama.so is built for armv8.2-a+dotprod+i8mm+fp16,
 * libiris_llama_compat.so for armv8.2-a+dotprod+fp16 (no i8mm, KleidiAI off; Snapdragon 855/865/870/888 …).
 * The other i8mm builds (libiris_sd / libiris_sdq / libiris_lhtp) keep the i8mm check ([missingForI8mm]).
 */
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

    const val LIB_FULL = "iris_llama"
    const val LIB_COMPAT = "iris_llama_compat"

    /** Library for [f] (empty = cannot tell → the full build, let System.loadLibrary decide); null = neither runs. */
    fun llamaLibraryFor(f: Set<String>): String? = when {
        f.isEmpty() -> LIB_FULL
        "asimddp" !in f || "asimdhp" !in f -> null
        "i8mm" in f -> LIB_FULL
        else -> LIB_COMPAT
    }

    fun llamaLibrary(): String? = llamaLibraryFor(features)

    /** GGUF engine: dotprod + fp16 are required (i8mm only picks the faster build). */
    fun missingForLlama(): String? {
        if (features.isEmpty()) return null // cannot tell; let System.loadLibrary decide
        val required = mapOf("asimddp" to "dotprod", "asimdhp" to "fp16")
        val missing = required.filterKeys { it !in features }.values
        return if (missing.isEmpty()) null
        else com.iris.localai.I18n.s(com.iris.localai.R.string.v250_cpu_missing, missing.joinToString(" / "))
    }

    /** The i8mm native builds (stable-diffusion.cpp CPU/NPU, llama.cpp Hexagon): the pre-beta8 check, unchanged. */
    fun missingForI8mm(): String? {
        if (features.isEmpty()) return null
        val required = mapOf("asimddp" to "dotprod", "i8mm" to "i8mm", "asimdhp" to "fp16")
        val missing = required.filterKeys { it !in features }.values
        return if (missing.isEmpty()) null
        else "此设备 CPU 缺少 ${missing.joinToString("、")} 指令集，无法运行 GGUF 引擎（需骁龙 8 Gen 1 及以上）"
    }

}

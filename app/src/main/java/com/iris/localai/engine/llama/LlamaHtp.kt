package com.iris.localai.engine.llama

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import com.iris.localai.engine.NpuIds
import java.io.File

/**
 * 2.2.15: the JNI calls [LlamaEngine] makes, so one engine can drive either native library:
 * libiris_llama.so (CPU + Adreno OpenCL, [LlamaNative]) or libiris_lhtp.so (CPU + Hexagon NPU, [LlamaHtpNative]).
 */
interface LlamaJni {
    fun create(): Long
    fun destroy(h: Long)
    fun load(h: Long, path: ByteArray, nCtx: Int, nThreads: Int, nBatch: Int, flashAttn: Boolean,
             nGpuLayers: Int, kvType: Int, useMmap: Boolean, listener: LoadProgressListener?): String?
    fun unload(h: Long)
    fun cancel(h: Long)
    fun clearCancel(h: Long)
    fun reset(h: Long)
    fun setThreads(h: Long, n: Int)
    fun requestLivePolicy(h: Long, threads: Int, cpus: IntArray, nice: Int)
    fun meta(h: Long): String
    fun generate(h: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, floats: FloatArray, ints: IntArray, sink: PieceSink?): String
    /** 2.2.17: vision projector (mtmd). Only libiris_llama has it; the NPU library stays text-only. */
    fun loadMmproj(h: Long, path: ByteArray, preferGpu: Boolean): String? = "内置 NPU 运行库不支持 mmproj 识图"
    fun generateVision(h: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, floats: FloatArray, ints: IntArray,
                       images: Array<ByteArray>, imageMsg: Int, sink: PieceSink?): String = generate(h, roles, contents, floats, ints, sink)
}

object LlamaJnis {
    val cpuGpu: LlamaJni = object : LlamaJni {
        override fun create() = LlamaNative.nativeCreate()
        override fun destroy(h: Long) = LlamaNative.nativeDestroy(h)
        override fun load(h: Long, path: ByteArray, nCtx: Int, nThreads: Int, nBatch: Int, flashAttn: Boolean, nGpuLayers: Int, kvType: Int, useMmap: Boolean, listener: LoadProgressListener?) =
            LlamaNative.nativeLoad(h, path, nCtx, nThreads, nBatch, flashAttn, nGpuLayers, kvType, useMmap, listener)
        override fun unload(h: Long) = LlamaNative.nativeUnload(h)
        override fun cancel(h: Long) = LlamaNative.nativeCancel(h)
        override fun clearCancel(h: Long) = LlamaNative.nativeClearCancel(h)
        override fun reset(h: Long) = LlamaNative.nativeReset(h)
        override fun setThreads(h: Long, n: Int) = LlamaNative.nativeSetThreads(h, n)
        override fun requestLivePolicy(h: Long, threads: Int, cpus: IntArray, nice: Int) = LlamaNative.nativeRequestLivePolicy(h, threads, cpus, nice)
        override fun meta(h: Long) = LlamaNative.nativeMeta(h)
        override fun generate(h: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, floats: FloatArray, ints: IntArray, sink: PieceSink?) =
            LlamaNative.nativeGenerate(h, roles, contents, floats, ints, sink)
        override fun loadMmproj(h: Long, path: ByteArray, preferGpu: Boolean) = LlamaNative.nativeLoadMmproj(h, path, preferGpu)
        override fun generateVision(h: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, floats: FloatArray, ints: IntArray,
                                    images: Array<ByteArray>, imageMsg: Int, sink: PieceSink?) =
            LlamaNative.nativeGenerateVision(h, roles, contents, floats, ints, images, imageMsg, sink)
    }
    val htp: LlamaJni = object : LlamaJni {
        override fun create() = LlamaHtpNative.nativeCreate()
        override fun destroy(h: Long) = LlamaHtpNative.nativeDestroy(h)
        override fun load(h: Long, path: ByteArray, nCtx: Int, nThreads: Int, nBatch: Int, flashAttn: Boolean, nGpuLayers: Int, kvType: Int, useMmap: Boolean, listener: LoadProgressListener?) =
            LlamaHtpNative.nativeLoad(h, path, nCtx, nThreads, nBatch, flashAttn, nGpuLayers, kvType, useMmap, listener)
        override fun unload(h: Long) = LlamaHtpNative.nativeUnload(h)
        override fun cancel(h: Long) = LlamaHtpNative.nativeCancel(h)
        override fun clearCancel(h: Long) = LlamaHtpNative.nativeClearCancel(h)
        override fun reset(h: Long) = LlamaHtpNative.nativeReset(h)
        override fun setThreads(h: Long, n: Int) = LlamaHtpNative.nativeSetThreads(h, n)
        override fun requestLivePolicy(h: Long, threads: Int, cpus: IntArray, nice: Int) = LlamaHtpNative.nativeRequestLivePolicy(h, threads, cpus, nice)
        override fun meta(h: Long) = LlamaHtpNative.nativeMeta(h)
        override fun generate(h: Long, roles: Array<ByteArray>, contents: Array<ByteArray>, floats: FloatArray, ints: IntArray, sink: PieceSink?) =
            LlamaHtpNative.nativeGenerate(h, roles, contents, floats, ints, sink)
    }
}

/** JNI surface of libiris_lhtp.so (same functions as [LlamaNative]; loaded by [LlamaHtpRuntime]). */
@Keep
object LlamaHtpNative {
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeLoad(
        handle: Long, pathUtf8: ByteArray, nCtx: Int, nThreads: Int, nBatch: Int,
        flashAttn: Boolean, nGpuLayers: Int, kvType: Int, useMmap: Boolean, listener: LoadProgressListener?,
    ): String?
    @JvmStatic external fun nativeUnload(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeClearCancel(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeSetThreads(handle: Long, threads: Int)
    @JvmStatic external fun nativeRequestLivePolicy(handle: Long, threads: Int, cpus: IntArray, nice: Int)
    @JvmStatic external fun nativeMeta(handle: Long): String
    @JvmStatic external fun nativeSetThreadPolicy(cpus: IntArray, nice: Int): Int
    @JvmStatic external fun nativeGenerate(
        handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>,
        floats: FloatArray, ints: IntArray, sink: PieceSink?,
    ): String
    @JvmStatic external fun nativeGpuTicks(): Int
    @JvmStatic external fun nativeGpuLastLog(): String
}

/** Pure rules for the built-in Hexagon runtime (unit-tested). */
object LlamaHtpRules {
    const val ASSET_DIR = "htp"
    const val JNI_LIB = "libiris_lhtp.so"
    const val HOST_LIB = "libiris-hexagon.so"
    /** HTP generations the upstream release ships skels for. */
    val ARCHS = setOf(73, 75, 79, 81, 85)
    fun skel(arch: Int) = "libiris-htp-v$arch.so"
    /** Files this phone needs from the assets. */
    fun files(arch: Int): List<String> = listOf(JNI_LIB, HOST_LIB, skel(arch))
    /** FastRPC splits ADSP_LIBRARY_PATH on ';'. Ours first, keep what was there (without duplicating ours). */
    fun adspPath(ours: String, current: String?): String =
        (listOf(ours) + current.orEmpty().split(';').filter { it.isNotBlank() && it != ours }).joinToString(";")
    const val NO_ARCH = "此芯片没有可用的 Hexagon NPU（需骁龙 8 Gen 2 及以上）"
}

/**
 * 2.2.15: the built-in GGUF NPU runtime — the same llama.cpp as the CPU/GPU engine (K2 Horizon, LFM3,
 * Gemma 4 …) plus upstream ggml-hexagon. Shipped as full-flavour assets (not jniLibs: the names would
 * clash with GenieX's packaging excludes), copied to app storage on first use, then System.load'ed;
 * ADSP_LIBRARY_PATH points FastRPC at the directory holding libiris-htp-vNN.so.
 */
object LlamaHtpRuntime {
    private const val TAG = "LlamaHtp"
    private val arch: Int? by lazy {
        NpuIds.hexagonArch(Build.SOC_MANUFACTURER, Build.SOC_MODEL)?.takeIf { it in LlamaHtpRules.ARCHS }
    }
    @Volatile private var loadedReason: String? = null
    @Volatile private var loaded = false

    /** Cheap check (no extraction): this build ships the runtime and this chip has a supported HTP. */
    fun available(context: Context): Boolean {
        val a = arch ?: return false
        if (CpuFeatures.missingForI8mm() != null) return false
        return runCatching {
            val names = context.assets.list(LlamaHtpRules.ASSET_DIR)?.toSet().orEmpty()
            LlamaHtpRules.files(a).all { it in names }
        }.getOrDefault(false)
    }

    /** Extracts (once per app version) and loads the libraries. Returns null when ready, else a reason. */
    @Synchronized
    fun ensureLoaded(context: Context): String? {
        if (loaded) return null
        loadedReason?.let { return it }
        val a = arch ?: return LlamaHtpRules.NO_ARCH
        LlamaNative.unavailableReason?.let { return it }
        return try {
            val ver = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
            val dir = File(context.filesDir, "llama-htp/v$ver-$a")
            val marker = File(dir, ".ok")
            if (!marker.isFile) {
                context.filesDir.resolve("llama-htp").listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
                dir.mkdirs()
                for (f in LlamaHtpRules.files(a)) {
                    context.assets.open("${LlamaHtpRules.ASSET_DIR}/$f").use { inp ->
                        val tmp = File(dir, "$f.tmp")
                        tmp.outputStream().use { inp.copyTo(it) }
                        if (!tmp.renameTo(File(dir, f))) throw java.io.IOException("rename $f")
                    }
                }
                marker.writeText("ok")
            }
            android.system.Os.setenv("ADSP_LIBRARY_PATH", LlamaHtpRules.adspPath(dir.absolutePath, System.getenv("ADSP_LIBRARY_PATH")), true)
            System.load(File(dir, LlamaHtpRules.JNI_LIB).absolutePath)
            System.load(File(dir, LlamaHtpRules.HOST_LIB).absolutePath)
            loaded = true
            Log.i(TAG, "loaded from $dir (HTP v$a)")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "load failed", t)
            ("无法加载内置 NPU 运行库：${t.message ?: t.javaClass.simpleName}").also { loadedReason = it }
        }
    }
}

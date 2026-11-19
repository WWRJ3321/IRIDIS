package com.iris.localai.imagegen

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.llama.CpuFeatures
import com.iris.localai.engine.llama.LlamaHtpRules
import android.content.Context
import android.util.Log
import java.io.File

/**
 * JNI surface of libiris_sdq.so — stable-diffusion.cpp's Qualcomm branch (Hexagon HTP v79+, FP8 DiT),
 * same sd_jni.cpp built with IRIS_SDQ (symbols bound to this class, so it can sit next to libiris_sd.so).
 * The library and its HTP skels come down with the NPU models ([ImageCatalog.SDQ_LIBS]); each is copied to
 * internal storage and checked against its SHA-256 before ADSP_LIBRARY_PATH is pointed at the folder and
 * the JNI library is loaded.
 */
object SdqNative {
    @Volatile var loaded = false
        private set

    /** Null when loaded, else why not (not cached: the files may arrive later). */
    @Synchronized
    fun ensureLoaded(context: Context, imagegenRoot: File?): String? {
        if (loaded) return null
        CpuFeatures.missingForI8mm()?.let { return it }
        val arch = runCatching { DeviceProbe.htpArch(DeviceProbe.socModel()) }.getOrNull()
        if (!ImageRules.sdqListed(arch)) return SDQ_NO_ARCH
        val noLib = I18n.s(R.string.imagegen_sd240_nolib)
        val root = imagegenRoot ?: return noLib
        return try {
            val dir = File(context.filesDir, "sdq/${ImageCatalog.SDQ_TAG}").apply { mkdirs() }
            context.filesDir.resolve("sdq").listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
            for (lib in ImageCatalog.SDQ_LIBS) {
                val src = File(root, lib.file.path)
                val dst = File(dir, lib.name)
                if (dst.isFile && dst.length() == lib.size && SdLibRules.sha256(dst) == lib.sha256) continue
                if (!(src.isFile && src.length() == lib.size)) return noLib
                val tmp = File(dir, lib.name + ".tmp")
                src.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
                if (SdLibRules.sha256(tmp) != lib.sha256) {
                    tmp.delete(); src.delete() // corrupt or tampered: force a fresh download
                    return noLib
                }
                if (!tmp.renameTo(dst)) throw java.io.IOException("rename ${dst.name}")
            }
            android.system.Os.setenv(
                "ADSP_LIBRARY_PATH",
                LlamaHtpRules.adspPath(dir.absolutePath, System.getenv("ADSP_LIBRARY_PATH")),
                true,
            )
            System.load(File(dir, "libiris_sdq.so").absolutePath)
            loaded = true
            Log.i("IrisSdq", "loaded from $dir (HTP v$arch)")
            null
        } catch (t: Throwable) {
            Log.w("IrisSdq", "load failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    const val SDQ_NO_ARCH = "NPU 生图需要骁龙 8 Elite 及以上（Hexagon HTP v79+）"

    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeLoad(diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: SdNative.Progress?,
    ): ByteArray?
}

/** the two native libraries behind one interface, so SdCppEngine runs either. */
interface SdApi {
    fun lastError(): String
    fun load(diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean): Long
    /** 带单文件模型与 CLIP / T5 的加载（只有上游库有；高通分支退回 [load]）。 */
    fun loadEx(model: String, clipL: String, clipG: String, t5: String, diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean): Long =
        load(diffusion, llm, vae, backend, threads, flashAttn)
    fun free(handle: Long)
    fun cancel(handle: Long)
    fun generate(handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: SdNative.Progress?): ByteArray?
    /** 2.7.1：LoRA + 预览（只有上游库 abi ≥ 2 有）。 */
    val supportsLoraPreview: Boolean get() = false
    fun generate2(handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
                  loras: List<LoraUse>, preview: Boolean, listener: SdNative.Progress?): ByteArray? =
        generate(handle, prompt, negative, width, height, steps, cfg, seed, listener)

    object Upstream : SdApi {
        override val supportsLoraPreview: Boolean get() = SdNative.abi >= 2
        override fun generate2(handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
                               loras: List<LoraUse>, preview: Boolean, listener: SdNative.Progress?): ByteArray? =
            if (!supportsLoraPreview) generate(handle, prompt, negative, width, height, steps, cfg, seed, listener)
            else SdNative.nativeGenerate2(handle, prompt, negative, width, height, steps, cfg, seed,
                loras.map { it.path }.toTypedArray(), FloatArray(loras.size) { loras[it].scale }, preview, listener)
        override fun lastError() = SdNative.nativeLastError()
        override fun load(diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean) = SdNative.nativeLoad(diffusion, llm, vae, backend, threads, flashAttn)
        override fun loadEx(model: String, clipL: String, clipG: String, t5: String, diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean) =
            SdNative.nativeLoadEx(model, clipL, clipG, t5, diffusion, llm, vae, backend, threads, flashAttn)
        override fun free(handle: Long) = SdNative.nativeFree(handle)
        override fun cancel(handle: Long) = SdNative.nativeCancel(handle)
        override fun generate(handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: SdNative.Progress?) =
            SdNative.nativeGenerate(handle, prompt, negative, width, height, steps, cfg, seed, listener)
    }

    object Qualcomm : SdApi {
        override fun lastError() = SdqNative.nativeLastError()
        override fun load(diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean) = SdqNative.nativeLoad(diffusion, llm, vae, backend, threads, flashAttn)
        override fun free(handle: Long) = SdqNative.nativeFree(handle)
        override fun cancel(handle: Long) = SdqNative.nativeCancel(handle)
        override fun generate(handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: SdNative.Progress?) =
            SdqNative.nativeGenerate(handle, prompt, negative, width, height, steps, cfg, seed, listener)
    }
}

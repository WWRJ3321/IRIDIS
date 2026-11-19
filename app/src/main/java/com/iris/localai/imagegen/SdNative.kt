package com.iris.localai.imagegen

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.llama.CpuFeatures
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.security.MessageDigest

/**
 * JNI surface of libiris_sd.so (upstream stable-diffusion.cpp, app/src/main/cpp/sdcpp/sd_jni.cpp).
 * The library is not in the APK: it comes down with the sd.cpp models ([ImageCatalog.SD_LIB]), is copied to
 * internal storage (external storage is mounted noexec) and loaded only when its SHA-256 matches.
 */
object SdNative {
    @Volatile var loaded = false
        private set

    /** Null when loaded (now or before), else why not. A failure is not cached: the file may arrive later. */
    @Synchronized
    fun ensureLoaded(context: Context, downloaded: File?): String? {
        if (loaded) return null
        CpuFeatures.missingForI8mm()?.let { return it }
        val noLib = I18n.s(R.string.imagegen_sd240_nolib)
        // 2.7.1：旧版库（3e92bf9-b 等）换成新库后删掉，免得占空间
        downloaded?.parentFile?.listFiles()?.filter { it.name.startsWith("libiris_sd-") && it.name.endsWith(".so") && it.name != downloaded.name }
            ?.forEach { runCatching { it.delete() } }
        val src = downloaded?.takeIf { it.isFile && it.length() == ImageCatalog.SD_LIB.size } ?: return noLib
        return try {
            val dir = File(context.filesDir, "sdcpp").apply { mkdirs() }
            val dst = File(dir, src.name)
            dir.listFiles()?.forEach { if (it.name != dst.name) it.delete() }
            if (!(dst.isFile && dst.length() == src.length() && SdLibRules.sha256(dst) == ImageCatalog.SD_LIB_SHA256)) {
                val tmp = File(dir, dst.name + ".tmp")
                src.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
                if (SdLibRules.sha256(tmp) != ImageCatalog.SD_LIB_SHA256) {
                    tmp.delete(); src.delete() // corrupt or tampered: force a fresh download
                    return noLib
                }
                if (!tmp.renameTo(dst)) throw java.io.IOException("rename ${dst.name}")
            }
            System.load(dst.absolutePath)
            loaded = true
            null
        } catch (t: Throwable) {
            Log.w("IrisSd", "load failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    @Keep
    fun interface Progress { fun onStep(step: Int, steps: Int) }

    /** 2.7.1：步进 + 预览帧（sd.cpp PREVIEW_PROJ，每步一帧 RGB）。JNI 按名字找 onStep / onPreview。 */
    @Keep
    class Listener2(private val step: (Int, Int) -> Unit, private val preview: (Int, Int, ByteArray) -> Unit) : Progress {
        @Keep override fun onStep(step: Int, steps: Int) = this.step(step, steps)
        @Keep fun onPreview(width: Int, height: Int, rgb: ByteArray) = preview(width, height, rgb)
    }

    /** 库的能力级别：1 = 只有 nativeGenerate；2 = 有 nativeGenerate2（LoRA + 预览）。 */
    val abi: Int by lazy { runCatching { nativeAbi() }.getOrDefault(1) }

    @JvmStatic external fun nativeAbi(): Int
    @JvmStatic external fun nativeGenerate2(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
        loraPaths: Array<String>?, loraScales: FloatArray?, preview: Boolean, listener: Progress?,
    ): ByteArray?

    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeLoad(diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean): Long
    @JvmStatic external fun nativeLoadEx(model: String, clipL: String, clipG: String, t5: String, diffusion: String, llm: String, vae: String, backend: String, threads: Int, flashAttn: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long, listener: Progress?,
    ): ByteArray?
}

/** Pure helpers (unit-tested). */
object SdLibRules {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

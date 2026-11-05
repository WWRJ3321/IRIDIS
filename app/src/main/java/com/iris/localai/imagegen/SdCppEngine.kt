package com.iris.localai.imagegen

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** 2.4.0: which files of a stable-diffusion.cpp model play which part (paths relative to the imagegen root). */
data class SdFiles(val diffusion: String, val llm: String, val vae: String)

/**
 * Pure rules of the sd.cpp engine (unit-tested): the backend to try and when a Vulkan crash forces CPU.
 * GPU = ggml Vulkan (Adreno, Mali, Immortalis, Xclipse); a driver crash during a GPU run leaves
 * [CRASH_MARKER] behind and the next runs use the CPU until the user retries the GPU.
 */
object SdRules {
    const val GPU = "vulkan0"
    const val CPU = "cpu"
    const val CRASH_MARKER = "sd-vulkan.running"

    fun backends(gpuCrashedBefore: Boolean, preferCpu: Boolean): List<String> =
        if (gpuCrashedBefore || preferCpu) listOf(CPU) else listOf(GPU, CPU)

    /** RGB (row-major, 3 bytes/px) → ARGB ints for Bitmap.setPixels. */
    fun argb(rgb: ByteArray, w: Int, h: Int): IntArray {
        require(rgb.size >= w * h * 3) { "short image: ${rgb.size} < ${w * h * 3}" }
        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}

/**
 * 2.4.0 生图 with upstream stable-diffusion.cpp (FLUX.2 Klein 4B, Z-Image Turbo). Keeps one model loaded
 * between generations (loading 5 GB of weights takes a while); [release] frees it.
 */
class SdCppEngine(
    private val rootOf: (ImageModel) -> File?,
    private val stateDir: File,
    private val preferCpu: () -> Boolean = { false },
) : ImageGenEngine {
    private val lock = Any()
    private var handle = 0L
    private var loadedKey: String? = null
    @Volatile private var running = 0L
    @Volatile private var cancelled = false

    private val marker get() = File(stateDir, SdRules.CRASH_MARKER)

    /** The GPU crashed the app last time (marker left by a dead process). */
    val gpuCrashedBefore: Boolean get() = marker.exists()

    /** User asked to try the GPU again. */
    fun clearGpuCrash() { marker.delete() }

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> {
        if (!SdNative.loaded) return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_nolib)))
        if (req.initImagePath != null) return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_noimg2img)))
        val files = model.sdFiles ?: return Result.failure(IllegalStateException("no sd.cpp files for ${model.id}"))
        val root = rootOf(model) ?: return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_28f942)))
        cancelled = false
        return try {
            synchronized(lock) {
                var lastErr: String? = null
                for (backend in SdRules.backends(gpuCrashedBefore, preferCpu())) {
                    val gpu = backend == SdRules.GPU
                    if (gpu) runCatching { stateDir.mkdirs(); marker.writeText(System.currentTimeMillis().toString()) }
                    try {
                        val h = ensureLoaded(root, files, backend) ?: run {
                            lastErr = SdNative.nativeLastError().ifBlank { "load failed ($backend)" }
                            Log.w(TAG, "load on $backend failed: $lastErr")
                            null
                        } ?: continue
                        if (cancelled) throw CancellationException()
                        running = h
                        val rgb = SdNative.nativeGenerate(h, req.prompt, req.effectiveNegative, req.width, req.height, req.steps, req.cfg, req.seed) { s, n ->
                            onProgress(s, n)
                        }
                        running = 0L
                        if (cancelled) throw CancellationException()
                        if (rgb == null) {
                            lastErr = SdNative.nativeLastError().ifBlank { "generate failed ($backend)" }
                            Log.w(TAG, "generate on $backend failed: $lastErr")
                            freeLocked()
                            continue
                        }
                        return Result.success(png(rgb, req.width, req.height))
                    } finally {
                        running = 0L
                        if (gpu) marker.delete()
                    }
                }
                Result.failure(IllegalStateException(lastErr ?: "generate failed"))
            }
        } catch (c: CancellationException) {
            Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_cancelled)))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun ensureLoaded(root: File, f: SdFiles, backend: String): Long? {
        val key = "$backend|${File(root, f.diffusion).path}"
        if (handle != 0L && loadedKey == key) return handle
        freeLocked()
        val h = SdNative.nativeLoad(File(root, f.diffusion).path, File(root, f.llm).path, File(root, f.vae).path, backend, 0, true)
        if (h == 0L) return null
        handle = h; loadedKey = key
        return h
    }

    private fun freeLocked() {
        if (handle != 0L) runCatching { SdNative.nativeFree(handle) }
        handle = 0L; loadedKey = null
    }

    override fun cancel() {
        cancelled = true
        running.takeIf { it != 0L }?.let { runCatching { SdNative.nativeCancel(it) } }
    }

    /** Frees the loaded weights (several GB). Waits for a running generation. */
    fun release() = synchronized(lock) { freeLocked() }

    val loaded: Boolean get() = handle != 0L

    private fun png(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(SdRules.argb(rgb, w, h), 0, w, 0, 0, w, h)
        return ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle(); out.toByteArray() }
    }

    private companion object { const val TAG = "IrisSd" }
}

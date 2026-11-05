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
    /**
     * 2.4.1: diffusion model + VAE on the GPU, the Qwen3-4B text encoder on the CPU (it runs once per image).
     * With a bare "vulkan0" sd.cpp's auto_fit placed everything — ~2.5 GB of encoder too — into Vulkan
     * buffers by the free VRAM the driver reports, which on a phone's unified memory is most of the RAM:
     * the system froze and the app was killed at step 2.
     */
    const val GPU = "diffusion=vulkan0,te=cpu,vae=vulkan0"
    const val CPU = "cpu"
    const val CRASH_MARKER = "sd-vulkan.running"
    /** 2.4.1: present = the user chose 「改用 CPU」 on the 生图 page. */
    const val PREFER_CPU_MARKER = "sd-prefer-cpu"
    private const val MB = 1024L * 1024L
    /** Room left for the app, the chat UI and the system while generating. */
    const val HEADROOM_MB = 400L

    /**
     * Estimated memory (MB) a generation takes beyond what is already used: on the GPU the diffusion model
     * and VAE are copied into Vulkan buffers (the text encoder stays mmapped on the CPU); compute buffers
     * grow with the pixel count (flash attention on; VAE decode dominates at high resolutions).
     */
    fun neededMb(gpu: Boolean, diffusionBytes: Long, vaeBytes: Long, width: Int, height: Int): Long {
        val megapixels = width.toLong() * height / (1024.0 * 1024.0)
        val compute = (350 + 900 * megapixels).toLong()
        val weights = if (gpu) (diffusionBytes.coerceAtLeast(0) + vaeBytes.coerceAtLeast(0)) / MB else 0L
        return weights + compute + HEADROOM_MB
    }

    /** Null when [availMb] covers [neededMb], else what to do (smaller size, or the CPU). */
    fun memoryProblem(availMb: Long, neededMb: Long, gpu: Boolean, width: Int, height: Int): String? {
        if (availMb >= neededMb) return null
        val have = "%.1f".format(java.util.Locale.ROOT, availMb / 1024.0)
        val need = "%.1f".format(java.util.Locale.ROOT, neededMb / 1024.0)
        return if (gpu) com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_lowmem_gpu, have, width, height, need)
        else com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_lowmem_cpu, have, width, height, need)
    }

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
    private val preferCpu: () -> Boolean = { File(stateDir, SdRules.PREFER_CPU_MARKER).exists() },
    /** 2.4.1: available RAM in MB (ActivityManager.MemoryInfo.availMem); MAX = unknown, no check. */
    private val availMb: () -> Long = { Long.MAX_VALUE },
) : ImageGenEngine {
    private val lock = Any()
    private var handle = 0L
    private var loadedKey: String? = null
    @Volatile private var running = 0L
    @Volatile private var cancelled = false

    private val marker get() = File(stateDir, SdRules.CRASH_MARKER)

    /** The GPU crashed the app last time (marker left by a dead process). */
    val gpuCrashedBefore: Boolean get() = marker.exists()

    /** User asked to try the GPU again (「重试 GPU」): forget the crash and the CPU choice. */
    fun clearGpuCrash() { marker.delete(); File(stateDir, SdRules.PREFER_CPU_MARKER).delete() }

    /** 2.4.1: 「改用 CPU」 / back to the GPU on the 生图 page. */
    fun setPreferCpu(on: Boolean) {
        val f = File(stateDir, SdRules.PREFER_CPU_MARKER)
        if (on) runCatching { stateDir.mkdirs(); f.writeText("1") } else f.delete()
    }

    /** True when the next generation runs on the CPU (crash marker or the user's choice). */
    val usesCpu: Boolean get() = SdRules.backends(gpuCrashedBefore, preferCpu()).first() == SdRules.CPU

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> {
        if (!SdNative.loaded) return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_nolib)))
        if (req.initImagePath != null) return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_noimg2img)))
        val files = model.sdFiles ?: return Result.failure(IllegalStateException("no sd.cpp files for ${model.id}"))
        val root = rootOf(model) ?: return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_28f942)))
        cancelled = false
        val order = SdRules.backends(gpuCrashedBefore, preferCpu())
        // 2.4.1: check free RAM before starting (a GPU run that does not fit froze the phone / got killed).
        run {
            val first = order.first()
            val gpu = first == SdRules.GPU
            val need = SdRules.neededMb(gpu, File(root, files.diffusion).length(), File(root, files.vae).length(), req.width, req.height)
            val loadedHere = handle != 0L && loadedKey?.startsWith("$first|") == true
            if (!loadedHere) SdRules.memoryProblem(availMb(), need, gpu, req.width, req.height)?.let { return Result.failure(IllegalStateException(it)) }
        }
        return try {
            synchronized(lock) {
                var lastErr: String? = null
                for (backend in order) {
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

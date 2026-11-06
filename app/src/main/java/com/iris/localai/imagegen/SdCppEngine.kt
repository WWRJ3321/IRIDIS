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
    /**
     * 2.4.3: NPU models (libiris_sdq.so): text encoder, DiT and VAE all on the Hexagon HTP (PR #1970 README).
     * After '|' comes sd.cpp's params_backend: below ~15 GB of RAM the text encoder's weights go back to disk
     * after conditioning (te=disk), so the 6 GB FP8 DiT and the 2.4 GB encoder are never resident together.
     */
    const val HTP = "diffusion=HTP0,te=HTP0,vae=HTP0"
    const val HTP_LOWMEM = "$HTP|te=disk"
    const val HTP_LOWMEM_BELOW_MB = 15L * 1024
    fun htpBackend(totalMb: Long): String = if (totalMb < HTP_LOWMEM_BELOW_MB) HTP_LOWMEM else HTP
    /** 2.4.3: left behind when the app died during an NPU run (the next run asks before trying again). */
    const val HTP_CRASH_MARKER = "sd-htp.running"
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

    /** 2.4.3: NPU — every resident weight (the encoder too unless te=disk) plus compute buffers. */
    fun neededMbHtp(diffusionBytes: Long, teBytes: Long, vaeBytes: Long, teOnDisk: Boolean, width: Int, height: Int): Long =
        neededMb(true, diffusionBytes + (if (teOnDisk) 0L else teBytes.coerceAtLeast(0)), vaeBytes, width, height)

    /** 2.4.3: a generation stopped by the memory check (the page offers 「仍然生成」). */
    class LowMemory(message: String) : IllegalStateException(message)

    /** 2.4.3: NPU — the weights dominate (FP8 DiT ≈ 4–6 GB), so a smaller size barely helps; say so. */
    fun memoryProblemHtp(availMb: Long, neededMb: Long, modelName: String, weightsMb: Long): String? {
        if (availMb >= neededMb) return null
        fun g(mb: Long) = "%.1f".format(java.util.Locale.ROOT, mb / 1024.0)
        return com.iris.localai.I18n.s(com.iris.localai.R.string.sd243_lowmem_npu, g(availMb), g(neededMb))
    }

    /** Null when [availMb] covers [neededMb], else what to do (smaller size, or the CPU). */
    fun memoryProblem(availMb: Long, neededMb: Long, gpu: Boolean, width: Int, height: Int): String? {
        if (availMb >= neededMb) return null
        val have = "%.1f".format(java.util.Locale.ROOT, availMb / 1024.0)
        val need = "%.1f".format(java.util.Locale.ROOT, neededMb / 1024.0)
        return if (gpu) com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_lowmem_gpu, have, width, height, need)
        else com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_lowmem_cpu, have, width, height, need)
    }

    /**
     * 2.4.2: CPU threads for sd.cpp. Left at 0 it used one thread per core including the little
     * cores, and every graph op then waits for the slowest one; use the performance cores only
     * (same rule as the chat engine's ThreadPolicy.performanceCores).
     */
    fun threads(t: com.iris.localai.engine.CpuTopology): Int {
        val n = t.count
        if (n <= 0) return 0
        val perf = if (t.clusters.size >= 3) n - t.clusters.first().size else n
        return perf.coerceIn(2, 8)
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
    /** 2.4.3: total RAM in MB (MAX = unknown). */
    private val totalMb: () -> Long = { Long.MAX_VALUE },
) : ImageGenEngine {
    private val lock = Any()
    private var handle = 0L
    private var loadedKey: String? = null
    @Volatile private var running = 0L
    @Volatile private var cancelled = false

    private val marker get() = File(stateDir, SdRules.CRASH_MARKER)
    private val htpMarker get() = File(stateDir, SdRules.HTP_CRASH_MARKER)
    /** 2.4.3: the native library the model runs on (set per generation, under [lock]). */
    @Volatile private var api: SdApi = SdApi.Upstream

    /** 2.4.3: the app died during an NPU run last time. */
    val htpCrashedBefore: Boolean get() = htpMarker.exists()

    /** The GPU crashed the app last time (marker left by a dead process). */
    val gpuCrashedBefore: Boolean get() = marker.exists()

    /** User asked to try the GPU again (「重试 GPU」): forget the crash and the CPU choice. */
    fun clearGpuCrash() { marker.delete(); htpMarker.delete(); File(stateDir, SdRules.PREFER_CPU_MARKER).delete() }

    /** 2.4.1: 「改用 CPU」 / back to the GPU on the 生图 page. */
    fun setPreferCpu(on: Boolean) {
        val f = File(stateDir, SdRules.PREFER_CPU_MARKER)
        if (on) runCatching { stateDir.mkdirs(); f.writeText("1") } else f.delete()
    }

    /** 2.4.3: set by 「仍然生成」 — the next generate() skips the memory check. */
    @Volatile var forceOnce = false

    /** True when the next generation runs on the CPU (crash marker or the user's choice). */
    val usesCpu: Boolean get() = SdRules.backends(gpuCrashedBefore, preferCpu()).first() == SdRules.CPU

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> {
        if (!(if (model.sdq) SdqNative.loaded else SdNative.loaded)) return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_nolib)))
        if (model.sdq && htpCrashedBefore) return Result.failure(IllegalStateException(SDQ_CRASHED))
        if (req.initImagePath != null) return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_sd240_noimg2img)))
        val files = model.sdFiles ?: return Result.failure(IllegalStateException("no sd.cpp files for ${model.id}"))
        val root = rootOf(model) ?: return Result.failure(IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_28f942)))
        cancelled = false
        val order = if (model.sdq) listOf(SdRules.htpBackend(totalMb())) else SdRules.backends(gpuCrashedBefore, preferCpu())
        // 2.4.1: check free RAM before starting (a GPU run that does not fit froze the phone / got killed).
        run {
            val first = order.first()
            val gpu = first == SdRules.GPU || model.sdq
            val need = if (model.sdq) SdRules.neededMbHtp(File(root, files.diffusion).length(), File(root, files.llm).length(),
                File(root, files.vae).length(), first == SdRules.HTP_LOWMEM, req.width, req.height)
            else SdRules.neededMb(gpu, File(root, files.diffusion).length(), File(root, files.vae).length(), req.width, req.height)
            val loadedHere = handle != 0L && loadedKey?.startsWith("$first|") == true
            // 2.4.3: 「仍然生成」 skips the check once (MemAvailable leaves out apps Android can still kill).
            val force = forceOnce.also { forceOnce = false }
            if (!loadedHere && !force) {
                val problem = if (model.sdq) SdRules.memoryProblemHtp(availMb(), need, model.name,
                    (File(root, files.diffusion).length() + File(root, files.vae).length()) / (1024L * 1024L))
                else SdRules.memoryProblem(availMb(), need, gpu, req.width, req.height)
                problem?.let { return Result.failure(SdRules.LowMemory(it)) }
            }
        }
        return try {
            synchronized(lock) {
                val want = if (model.sdq) SdApi.Qualcomm else SdApi.Upstream
                if (want !== api) { freeLocked(); api = want }
                var lastErr: String? = null
                for (backend in order) {
                    val gpu = backend == SdRules.GPU
                    val htp = model.sdq
                    val mark = if (htp) htpMarker else marker
                    if (gpu || htp) runCatching { stateDir.mkdirs(); mark.writeText(System.currentTimeMillis().toString()) }
                    try {
                        val h = ensureLoaded(root, files, backend) ?: run {
                            lastErr = api.lastError().ifBlank { "load failed ($backend)" }
                            Log.w(TAG, "load on $backend failed: $lastErr")
                            null
                        } ?: continue
                        if (cancelled) throw CancellationException()
                        running = h
                        val rgb = api.generate(h, req.prompt, req.effectiveNegative, req.width, req.height, req.steps, req.cfg, req.seed, SdNative.Progress { s, n ->
                            onProgress(s, n)
                        })
                        running = 0L
                        if (cancelled) throw CancellationException()
                        if (rgb == null) {
                            lastErr = api.lastError().ifBlank { "generate failed ($backend)" }
                            Log.w(TAG, "generate on $backend failed: $lastErr")
                            freeLocked()
                            continue
                        }
                        return Result.success(png(rgb, req.width, req.height))
                    } finally {
                        running = 0L
                        if (gpu || htp) mark.delete()
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
        val h = api.load(File(root, f.diffusion).path, File(root, f.llm).path, File(root, f.vae).path, backend, sdThreads, true)
        if (h == 0L) return null
        handle = h; loadedKey = key
        return h
    }

    private val sdThreads: Int by lazy { runCatching { SdRules.threads(com.iris.localai.engine.CpuTopology.read()) }.getOrDefault(0).also { Log.i(TAG, "sd.cpp threads = $it") } }

    private fun freeLocked() {
        if (handle != 0L) runCatching { api.free(handle) }
        handle = 0L; loadedKey = null
    }

    override fun cancel() {
        cancelled = true
        running.takeIf { it != 0L }?.let { runCatching { api.cancel(it) } }
    }

    /** Frees the loaded weights (several GB). Waits for a running generation. */
    fun release() = synchronized(lock) { freeLocked() }

    val loaded: Boolean get() = handle != 0L

    private fun png(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(SdRules.argb(rgb, w, h), 0, w, 0, 0, w, h)
        return ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle(); out.toByteArray() }
    }

    companion object {
        private const val TAG = "IrisSd"
        const val SDQ_CRASHED = "上次用 NPU 生图时应用退出了。点「重试 NPU」再试一次，或改用 GPU 版模型。"
    }
}

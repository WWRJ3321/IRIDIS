package com.iris.localai.imagegen

import com.iris.localai.I18n
import com.iris.localai.IrisApp
import com.iris.localai.R
import com.iris.localai.agent.tools.ChatModelYield
import com.iris.localai.agent.tools.ImageGenBackend
import com.iris.localai.agent.tools.ImageMemory
import com.iris.localai.data.ModelStorage
import com.iris.localai.engine.resources.ThermalCutoff
import android.content.Context
import com.iris.localai.data.ModelDirs
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure java.io rules of 生图 storage (unit-tested on temp folders):
 * weights in `Download/IRIDIS/models/imagegen/<id>/`, results in `<workspace>/images/IMG_yyyyMMdd_HHmmss.png`.
 */
object ImageGenFiles {
    const val MODELS_SUB = "${ModelDirs.SUB}/imagegen"
    const val IMAGES_SUB = "images"
    const val PREFIX = "IMG_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    fun outputName(time: LocalDateTime): String = PREFIX + STAMP.format(time) + ".png"

    /** A free `images/IMG_<stamp>.png` under [workspaceRoot] (`_2`, `_3` … when two land in one second). */
    fun outputFile(workspaceRoot: File, time: LocalDateTime): File {
        val dir = File(workspaceRoot, IMAGES_SUB)
        val base = outputName(time).removeSuffix(".png")
        var f = File(dir, "$base.png")
        var n = 2
        while (f.exists()) f = File(dir, "${base}_${n++}.png")
        return f
    }


    fun modelDir(imagegenRoot: File, model: ImageModel): File = File(imagegenRoot, model.id)

    /**
     * Downloaded = every listed file present with its size (an archive counts once its [RemoteFile.unpacked]
     * files are all in the model folder with their sizes — the zip itself is deleted); for a repo resolved at download time
     * ([ImageModel.files] empty, [ImageModel.hfRepo] set) a non-empty folder without `.part` leftovers.
     * Models waiting for the Hexagon runtime have no files yet, so never count as downloaded.
     */
    fun isDownloaded(imagegenRoot: File, model: ImageModel): Boolean {
        // 导入的 NPU 包 = 文件夹里该有的文件都在
        if (model.imported && model.animaQnn) return AnimaQnnFiles(modelDir(imagegenRoot, model)).missing().isEmpty()
        // 导入的模型 = 三个文件都在（大小不定）+ 引擎库已下载
        if (model.imported) return model.sdFiles?.let { f -> f.paths().all { File(imagegenRoot, it).isFile } } == true &&
            File(imagegenRoot, ImageCatalog.SD_LIB.path).let { it.isFile && it.length() == ImageCatalog.SD_LIB.size }
        if (model.files.isNotEmpty()) return model.files.all { rf ->
            if (rf.isArchive) ImageDownloads.unpacked(imagegenRoot, model, rf)
            else File(imagegenRoot, rf.path).let { f -> f.isFile && f.length() == rf.size }
        }
        if (model.hfRepo == null) return false
        val files = modelDir(imagegenRoot, model).walkTopDown().filter { it.isFile }.toList()
        return files.isNotEmpty() && files.none { it.name.endsWith(".part") }
    }

    /** Total download size, null when the file list is only known at download time. */
    fun sizeBytes(model: ImageModel): Long? = model.files.takeIf { it.isNotEmpty() }?.sumOf { it.size }

    /** A1111-style "parameters" text embedded in the PNG. */
    fun parametersText(req: ImageRequest, model: ImageModel): String = buildString {
        append(req.prompt.trim())
        if (req.effectiveNegative.isNotBlank()) append("\nNegative prompt: ").append(req.effectiveNegative.trim())
        append("\nSteps: ${req.steps}, Sampler: Euler, CFG scale: ${req.cfg}, Seed: ${req.seed}, Size: ${req.width}x${req.height}, Model: ${model.id}")
        if (req.initImagePath != null) append(", Denoising strength: ${req.strength}")
    }

    /** Generated images under [workspaceRoot]/images, newest first. */
    fun history(workspaceRoot: File): List<File> =
        File(workspaceRoot, IMAGES_SUB).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".png") }
            .sortedByDescending { it.name } // the stamp sorts by time; "IMG_x_2.png" > "IMG_x.png"

    /** what [parametersText] wrote, read back (null fields when a line is missing or foreign). */
    data class Params(
        val prompt: String, val negative: String = "",
        val steps: Int? = null, val cfg: Float? = null, val seed: Long? = null,
        val width: Int? = null, val height: Int? = null, val modelId: String? = null, val strength: Float? = null,
    )

    fun parseParameters(text: String?): Params? {
        if (text.isNullOrBlank()) return null
        val lines = text.trimEnd().lines()
        val metaIdx = lines.indexOfLast { it.startsWith("Steps:") }
        val body = if (metaIdx >= 0) lines.subList(0, metaIdx) else lines
        val negIdx = body.indexOfFirst { it.startsWith("Negative prompt:") }
        val prompt = (if (negIdx >= 0) body.subList(0, negIdx) else body).joinToString("\n").trim()
        val negative = if (negIdx >= 0) (listOf(body[negIdx].removePrefix("Negative prompt:")) + body.subList(negIdx + 1, body.size)).joinToString("\n").trim() else ""
        val kv = if (metaIdx < 0) emptyMap() else lines[metaIdx].split(", ").mapNotNull { part ->
            val i = part.indexOf(':'); if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        val size = kv["Size"]?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
        return Params(
            prompt = prompt, negative = negative,
            steps = kv["Steps"]?.toIntOrNull(), cfg = kv["CFG scale"]?.toFloatOrNull(), seed = kv["Seed"]?.toLongOrNull(),
            width = size?.get(0), height = size?.get(1), modelId = kv["Model"]?.takeIf { it.isNotEmpty() },
            strength = kv["Denoising strength"]?.toFloatOrNull(),
        )
    }

    /** only generated results (`images/IMG_*.png`) may be deleted from the history. */
    fun isHistoryFile(f: File): Boolean =
        f.name.startsWith(PREFIX) && f.name.endsWith(".png") && f.parentFile?.name == IMAGES_SUB

    /** Writes [png] with the parameters chunk into [file] (via `.part`, then rename). */
    fun write(file: File, png: ByteArray, parameters: String): File {
        file.parentFile?.mkdirs()
        val bytes = runCatching { PngParams.insert(png, parameters) }.getOrDefault(png)
        val part = File(file.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) { part.delete(); throw java.io.IOException(I18n.s(R.string.util_310b87, file.path)) }
        return file
    }
}

/** 生图 state on disk: catalog, which models are downloaded, results and their history. */
class ImageGenRepository(internal val context: Context) {
    val catalog: List<ImageModel> get() = ImageCatalog.all.let { a ->
        val roots = modelsLocation().listDirs
        val old = ImageCatalog.legacy.filter { m -> roots.any { ImageGenFiles.isDownloaded(it, m) } }
        if (old.isEmpty()) a else a + old
    }

    private val privateModels: File by lazy {
        File(context.getExternalFilesDir(ModelDirs.SUB) ?: File(context.filesDir, ModelDirs.SUB), "imagegen")
    }

    /** `Download/IRIDIS/models/imagegen` with 全部文件访问, else the app-specific folder. */
    fun modelsLocation(): ModelDirs.Location = ModelStorage.location(privateModels, ImageGenFiles.MODELS_SUB)

    /** Where a new download goes: the root that already holds a partial/complete copy, else the current write dir. */
    fun downloadRoot(model: ImageModel): File {
        val loc = modelsLocation()
        return loc.listDirs.firstOrNull { ImageGenFiles.modelDir(it, model).exists() } ?: loc.dir
    }

    /** Every root a model folder may live in (for 删除). */
    fun roots(): List<File> = modelsLocation().listDirs

    /** Ids of the models found complete in the shared or the private folder. */
    fun downloaded(): Set<String> {
        val dirs = modelsLocation().listDirs
        return catalog.filter { m -> dirs.any { ImageGenFiles.isDownloaded(it, m) } }.map { it.id }.toSet()
    }

    private val agent get() = (context.applicationContext as IrisApp).container.agent

    /** The agent workspace (Download/IRIDIS/workspace or the private one). */
    fun workspaceRoot(): File = agent.workspace.value.root

    /** Saves the result as `images/IMG_….png`; falls back to the private workspace when Download is not writable. */
    fun save(png: ByteArray, req: ImageRequest, model: ImageModel, time: LocalDateTime = LocalDateTime.now()): File {
        val params = ImageGenFiles.parametersText(req, model)
        return runCatching { ImageGenFiles.write(ImageGenFiles.outputFile(workspaceRoot(), time), png, params) }
            .getOrElse { ImageGenFiles.write(ImageGenFiles.outputFile(agent.privateRoot, time), png, params) }
    }

    /** Beta5 超分结果：存在原图同一个 images/ 里，参数沿用原图并注明放大倍数。 */
    fun saveUpscaled(src: File, png: ByteArray, factor: Int): File {
        val old = runCatching { PngParams.read(src.readBytes()) }.getOrNull()
        val root = src.parentFile?.parentFile ?: workspaceRoot()
        return ImageGenFiles.write(ImageGenFiles.outputFile(root, LocalDateTime.now()), png, UpscaleRules.parameters(old, factor))
    }

    /** The folder of [model] in whichever root holds a complete download, null when none does. */
    fun downloadedDir(model: ImageModel): File? =
        modelsLocation().listDirs.firstOrNull { ImageGenFiles.isDownloaded(it, model) }?.let { ImageGenFiles.modelDir(it, model) }

    fun history(): List<File> {
        val roots = listOf(workspaceRoot(), agent.privateRoot).distinctBy { it.absolutePath }
        return roots.flatMap { ImageGenFiles.history(it) }.distinctBy { it.absolutePath }
            .sortedByDescending { it.name }
    }

    /** deletes history images (results only; other paths are ignored). Returns the paths actually gone. */
    fun deleteHistory(paths: Collection<String>): Set<String> {
        val known = history().map { it.absolutePath }.toSet()
        return paths.filter { p ->
            val f = File(p)
            f.absolutePath in known && ImageGenFiles.isHistoryFile(f) && (f.delete() || !f.exists())
        }.toSet()
    }

    /** the parameters embedded in a result, null for an image without them. */
    fun params(path: String): ImageGenFiles.Params? =
        runCatching { ImageGenFiles.parseParameters(PngParams.read(File(path).readBytes())) }.getOrNull()
}

/**
 * generate_image's view of 生图 — downloaded models that can run (not waiting for the Hexagon
 * runtime), the engine, and saving into the workspace.
 */
class RepositoryImageGenBackend(
    private val repo: ImageGenRepository,
    private val engine: ImageGenEngine = ImageGenEngines.get(repo.context),
    /** the chat model's unload / reload (null = not wired, e.g. tests). */
    private val yielder: () -> ChatModelYield? = { null },
) : ImageGenBackend {
    override suspend fun makeRoom(model: ImageModel): (suspend () -> Unit)? {
        val y = yielder() ?: return null
        val bytes = ImageGenFiles.sizeBytes(model) ?: return null
        val avail = runCatching {
            val am = repo.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        }.getOrDefault(0L)
        if (!ImageMemory.mustYield(avail, bytes)) return null
        return y.yieldModel()
    }

    override suspend fun makeRoomAlways(): (suspend () -> Unit)? = yielder()?.yieldModel()

    override fun releaseNow() { (engine as? ImageGenEngines)?.release() }

    override fun runnableModels(): List<ImageModel> {
        val dl = runCatching { repo.downloaded() }.getOrDefault(emptySet())
        // catalog 已排除 MNN CPU；这里再保险一次
        return repo.catalog.filter { it.id in dl && ImageRules.listed(it) }
    }

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { engine.generate(req, model, onProgress) }

    override fun save(png: ByteArray, req: ImageRequest, model: ImageModel): File = repo.save(png, req, model)

    override fun cancel() = engine.cancel()
}

/** An engine that cannot run on this device: every request fails with [reason]. */
class UnavailableEngine(private val reason: String) : ImageGenEngine {
    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        Result.failure(IllegalStateException(reason))
    override fun cancel() = Unit
}

/**
 * The process-wide 生图 engine (shared by the 生图 page and generate_image, so the NPU graphs load once):
 * sd15-qnn → [QnnSdEngine] when libiris_imagegen's probe says the QNN HTP backend and device come up;
 * every other model → [StubEngine] for now. Runs in-process on the caller's dispatcher (Dispatchers.Default).
 */
class ImageGenEngines private constructor(private val context: Context) : ImageGenEngine {
    private val repo by lazy { ImageGenRepository(context) }

    init {
        // Anima 预览映射（16 通道，hexanima 系数）；读不出就不出预览
        if (PreviewHub.anima == null) PreviewHub.anima = runCatching {
            LatentRgbMap.parse(context.assets.open("imagegen/preview_anima.bin").use { it.readBytes() })
        }.getOrNull()
    }
    private val stub = StubEngine()
    @Volatile private var qnn: QnnSdEngine? = null
    @Volatile private var anima: AnimaQnnEngine? = null
    @Volatile private var active: ImageGenEngine? = null

    @Volatile private var sd: SdCppEngine? = null

    /** the stable-diffusion.cpp engine (FLUX.2 Klein / Z-Image), created on first use. */
    fun sdEngine(): SdCppEngine = sd ?: synchronized(this) {
        sd ?: SdCppEngine(rootOf = { m -> repo.downloadedDir(m)?.parentFile }, stateDir = context.filesDir, availMb = ::availMb, totalMb = ::totalMb).also { sd = it }
    }

    /** ActivityManager.MemoryInfo.availMem in MB (MAX when unknown). */
    private fun availMb(): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / (1024L * 1024L)
    }.getOrDefault(Long.MAX_VALUE)

    /** ActivityManager.MemoryInfo.totalMem in MB (MAX when unknown). */
    private fun totalMb(): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024L * 1024L)
    }.getOrDefault(Long.MAX_VALUE)

    fun engineFor(model: ImageModel): ImageGenEngine {
        if (model.sdq) {
            SdqNative.ensureLoaded(context, repo.downloadedDir(model)?.parentFile)?.let { return UnavailableEngine(it) }
            return sdEngine()
        }
        if (model.backend == Backend.SDCPP) {
            val root = repo.downloadedDir(model)?.parentFile
            SdNative.ensureLoaded(context, root?.let { File(it, ImageCatalog.SD_LIB.path) })?.let { return UnavailableEngine(it) }
            return sdEngine()
        }
        if (model.backend != Backend.QNN) return stub
        if (model.animaQnn) return animaEngine(model)
        anima?.release()
        qnn?.let { return it }
        val probe = DeviceProbe.nativeProbe(context)
        val dir = DeviceProbe.qnnRuntimeDir(context.filesDir, probe.htpArch ?: DeviceProbe.htpArch(DeviceProbe.socModel()))
        return when {
            !probe.libLoaded -> UnavailableEngine(I18n.s(R.string.imagegen_6b0e80, probe.error ?: '?'))
            dir == null -> UnavailableEngine(I18n.s(R.string.imagegen_f724e7))
            !(probe.backendOk && probe.deviceOk) -> UnavailableEngine(I18n.s(R.string.imagegen_a999cf, probe.error ?: '?'))
            else -> synchronized(this) {
                qnn ?: QnnSdEngine(modelDir = { repo.downloadedDir(it) }, loader = NativeQnnGraphLoader(dir), perf = NativeHtpPerf).also { qnn = it }
            }
        }
    }

    /** Frees the NPU graphs [IdleReleaser.DEFAULT_TIMEOUT_MS] after the last generation (page or generate_image). */
    private val idle = IdleReleaser { release() }

    /** in the app process, generations go to the `:imagegen` process ([RemoteImageGen]). */
    private val remote: RemoteImageGen? by lazy { if (REMOTE && !ImageGenIpc.isImageGenProcess()) RemoteImageGen.get(context) else null }

    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        generate(req, model, onProgress, null)

    /** 2.7.1：带实时预览（[onPreview] 每步一帧低清图）。 */
    suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit, onPreview: ((PreviewFrame) -> Unit)?): Result<ByteArray> {
        val r = remote ?: run {
            PreviewHub.sink = onPreview
            return try { generateLocal(req, model, onProgress) } finally { PreviewHub.sink = null }
        }
        // 终止温度: refuse while still over the cutoff, otherwise arm again for this image (app process state).
        ThermalCutoff.startOrRefuse()?.let { return Result.failure(IllegalStateException(it)) }
        val force = sd?.forceOnce == true
        sd?.forceOnce = false
        return r.generate(req, model, force, onProgress, onPreview ?: {})
    }

    /** Runs the native engine in this process (`:imagegen`, or the app process when [REMOTE] is off). */
    suspend fun generateLocal(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        idle.track {
            if (remote == null && !ImageGenIpc.isImageGenProcess()) {
                // 终止温度: refuse while still over the cutoff, otherwise arm again for this image.
                ThermalCutoff.startOrRefuse()?.let { return@track Result.failure(IllegalStateException(it)) }
            }
            val e = runCatching { engineFor(model) }.getOrElse { return@track Result.failure(it) }
            active = e
            try { e.generate(req, model, onProgress) } finally { if (active === e) active = null }
        }

    override fun cancel() { remote?.cancel(); active?.cancel() }

    /** 终止温度: a generation is running, or NPU graphs are held. */
    val busy: Boolean get() = active != null || remote?.busy == true

    /** Frees the NPU graphs (≈1.1 GB) now, until the next generation. */
    fun release() { remote?.release(); qnn?.release(); anima?.release(); sd?.release() }

    /**
     * Anima NPU 包：8 Elite 布局只认 HTP v79，[AnimaLdFiles] 布局 v75 起；包按 QAIRT 2.50.40 编译，优先用 GenieX 运行库（同版本），没有再用 QNN 运行库。
     * libiris_imagegen 的运行库全进程只有一份，切过来前先放掉 SD1.5 的图。
     */
    private fun animaEngine(model: ImageModel): ImageGenEngine {
        val arch = DeviceProbe.htpArch(DeviceProbe.socModel())
        // 另一种布局（QNN 2.28 / v75 编译）在 v75 及更新的芯片上试；8 Elite 布局只认 v79
        val ld = repo.downloadedDir(model)?.let { AnimaQnnFiles(it).ld } == true
        if (ld) { if (arch == null || arch < AnimaLdFiles.MIN_ARCH) return UnavailableEngine(I18n.s(R.string.ig273_npu_chip_ld)) }
        else if (arch != 79) return UnavailableEngine(I18n.s(R.string.ig270_npu_chip))
        anima?.let { return it }
        val gx = File(context.filesDir, "geniex").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("rt-") && it.name.endsWith("-v$arch") }
            .firstOrNull { d -> listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV${arch}Stub.so", "libQnnHtpV${arch}Skel.so").all { File(d, it).isFile } }
        val dir = gx ?: DeviceProbe.qnnRuntimeDir(context.filesDir, arch)
            ?: return UnavailableEngine(I18n.s(R.string.ig270_npu_runtime))
        QnnNative.loadError?.let { return UnavailableEngine(I18n.s(R.string.imagegen_6b0e80, it)) }
        return synchronized(this) {
            qnn?.release()
            anima ?: AnimaQnnEngine(modelDir = { repo.downloadedDir(it) }, loader = NativeQnnGraphLoader(dir), perf = NativeHtpPerf).also { anima = it }
        }
    }

    /** Leaving the 生图 page: free the NPU graphs now, or as soon as a running generation ends. */
    fun releaseWhenIdle() { remote?.releaseWhenIdle(); idle.releaseWhenIdle() }

    companion object {
        /** kill switch for the separate `:imagegen` process. */
        const val REMOTE = true
        @Volatile private var instance: ImageGenEngines? = null
        fun get(context: Context): ImageGenEngines =
            instance ?: synchronized(this) { instance ?: ImageGenEngines(context.applicationContext).also { instance = it } }
    }
}

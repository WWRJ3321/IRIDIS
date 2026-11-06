package com.iris.localai.ui.imagegen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.DeviceProbe
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageDl
import com.iris.localai.imagegen.ImageGenEngine
import com.iris.localai.imagegen.ImageGenRepository
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.imagegen.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Chinese copy of the 生图 page (tests read these). */
object ImageGenText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_19a9bc)
    val DOWNLOADED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_9719fe)
    val NOT_DOWNLOADED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_336189)
    val DOWNLOAD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f26ef9)
    val NOT_DOWNLOADED_ERROR: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_28b159)
    val EXTRACTING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_973e8a)
    val DELETE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad)
    val DELETE_TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_bb7a2e)
    val RETRY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b7da7)
    val PROMPT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_68a756)
    val NEGATIVE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_9576af)
    val REF_IMAGE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_477f3a)
    val STRENGTH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_a27088)
    val NO_IMG2IMG: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_b37ef6)
    val STEPS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_896303)
    const val CFG = "CFG"
    val SEED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_0e9bf6)
    val GENERATE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4dfe70)
    val CANCEL: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2)
    val CANCELLED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_2111cc)
    val SAVE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb)
    val SHARE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_c31f48)
    val TO_CHAT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_bf1781)
    val HISTORY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_c827d8)
    val SELF_CHECK: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_328b2c)
    val RUN: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_4c763b)
    val COPY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_79d3ab)
    val FIXED_SIZE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_e23c9e)
    /** 2.4.1: sd.cpp models — which processor the next image uses, and the switches. */
    val SD_ON_GPU: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_on_gpu)
    val SD_ON_CPU: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_on_cpu)
    val SD_GPU_CRASHED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_gpu_crashed)
    val RETRY_GPU: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_retry_gpu)
    val USE_CPU: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.sd241_use_cpu)
}

data class ImageGenUi(
    val modelId: String = ImageCatalog.SD15_QNN.id,
    val downloaded: Set<String> = emptySet(),
    val prompt: String = "",
    val negative: String = "",
    val initImage: String? = null,
    val strength: Float = 0.75f,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = ImageCatalog.SD15_QNN.defaultSteps,
    val cfg: Float = ImageCatalog.SD15_QNN.defaultCfg,
    val seed: String = "",
    val running: Boolean = false,
    val step: Int = 0,
    val total: Int = 0,
    /** 2.4.2: load / step / decode phase of the running generation, and when it started. */
    val progress: com.iris.localai.imagegen.SdProgress.State = com.iris.localai.imagegen.SdProgress.State(),
    val startedAt: Long = 0L,
    val error: String? = null,
    val result: String? = null,
    val history: List<String> = emptyList(),
    val report: String? = null,
    /** 2.4.1: sd.cpp — the GPU crashed last time (runs on the CPU until 「重试 GPU」). */
    val sdGpuCrashed: Boolean = false,
    /** 2.4.1: sd.cpp — the next image runs on the CPU (crash or 「改用 CPU」). */
    val sdOnCpu: Boolean = false,
    /** 2.4.3: the app died during an NPU (sdq) run. */
    val sdHtpCrashed: Boolean = false,
    /** Model id -> running / failed download (from [ImageGenDownloader.states]). */
    val downloads: Map<String, ImageDl> = emptyMap(),
) {
    val model: ImageModel get() = ImageCatalog.byId(modelId) ?: ImageCatalog.SD15_QNN
    /** Reference image + 重绘强度 work everywhere except the fixed-size QNN context binary (txt2img only). */
    val img2imgSupported: Boolean get() = model.backend != Backend.QNN
    val canGenerate: Boolean get() = !running && prompt.isNotBlank()

    fun request(): ImageRequest = ImageRequest(
        prompt = prompt, negative = negative, width = width, height = height, steps = steps, cfg = cfg,
        seed = seed.trim().toLongOrNull() ?: -1L,
        initImagePath = initImage?.takeIf { img2imgSupported }, strength = strength,
    ).normalize(model)
}

class ImageGenViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ImageGenRepository(app)
    private val engines = com.iris.localai.imagegen.ImageGenEngines.get(app)
    private val engine: ImageGenEngine = engines
    private val _ui = MutableStateFlow(ImageGenUi())
    val ui: StateFlow<ImageGenUi> = _ui.asStateFlow()
    private var job: Job? = null

    val catalog: List<ImageModel> get() = repo.catalog

    private val container = (app as com.iris.localai.IrisApp).container
    private val downloader = container.imageGenDownloader

    init {
        refresh()
        viewModelScope.launch {
            var before: Map<String, ImageDl> = emptyMap()
            downloader.states.collect { st ->
                _ui.update { it.copy(downloads = st) }
                // a download that just left the map finished (or was cancelled): re-scan the folders
                if (before.keys.any { it !in st }) refresh()
                before = st
            }
        }
    }

    /** Starts (or resumes) [m]'s download in the app scope with the 下载源 from 设置. */
    fun download(m: ImageModel) {
        if (m.files.isEmpty()) return
        // 2.2.16: after a failure the button is 「换源重试」 (the source that failed goes last; .part files resume)
        val failed = downloader.states.value[m.id] is com.iris.localai.imagegen.ImageDl.Failed
        downloader.clearError(m.id)
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { repo.downloadRoot(m) }
            val src = container.settings.current.downloadSource
            if (failed) downloader.retryOtherSource(m, root, src) { refresh() }
            else downloader.start(m, root, src) { refresh() }
        }
    }

    fun cancelDownload(m: ImageModel) { downloader.cancel(m.id); refresh() }

    /** Deletes the model folder (complete or partial) after the page's confirm dialog. */
    fun deleteModel(m: ImageModel) {
        viewModelScope.launch {
            val ok = downloader.delete(m, withContext(Dispatchers.IO) { repo.roots() })
            if (!ok) _ui.update { it.copy(error = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_acf066)) }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val (dl, hist) = withContext(Dispatchers.IO) {
                runCatching { repo.downloaded() }.getOrDefault(emptySet()) to runCatching { repo.history().map { it.path } }.getOrDefault(emptyList())
            }
            val sd = engines.sdEngine()
            _ui.update { it.copy(downloaded = dl, history = hist, sdGpuCrashed = sd.gpuCrashedBefore, sdOnCpu = sd.usesCpu, sdHtpCrashed = sd.htpCrashedBefore) }
        }
    }

    /** 2.4.1 「重试 GPU」: forget the last GPU crash (and a 「改用 CPU」 choice). */
    fun retryGpu() {
        engines.sdEngine().clearGpuCrash()
        _ui.update { it.copy(error = null) }
        refresh()
    }

    /** 2.4.1 「改用 CPU」: slower, but needs only the compute buffers (weights stay mmapped). */
    fun useCpu() {
        engines.sdEngine().setPreferCpu(true)
        _ui.update { it.copy(error = null) }
        refresh()
    }

    fun edit(f: (ImageGenUi) -> ImageGenUi) = _ui.update(f)

    fun selectModel(m: ImageModel) {
        _ui.update {
            val old = it.model
            // 2.2.12: a model's suggested prompt / negative fill fields that are empty or still the previous model's default
            fun pick(cur: String, oldDefault: String, newDefault: String) =
                if (cur.isBlank() || (oldDefault.isNotEmpty() && cur == oldDefault)) newDefault else cur
            it.copy(modelId = m.id, steps = m.defaultSteps, cfg = m.defaultCfg, width = m.fixedSize ?: it.width, height = m.fixedSize ?: it.height,
                prompt = pick(it.prompt, old.defaultPrompt, m.defaultPrompt), negative = pick(it.negative, old.defaultNegative, m.defaultNegative))
        }
    }

    /** Copies the picked reference image into the cache (the Uri grant does not outlive the picker). */
    fun pickReference(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()
                    val dir = File(ctx.cacheDir, "imagegen").apply { mkdirs() }
                    val out = File(dir, "ref-${System.currentTimeMillis()}")
                    ctx.contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } } ?: return@runCatching null
                    dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
                    out.path
                }.getOrNull()
            }
            _ui.update { it.copy(initImage = path, error = if (path == null) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_a7f86a) else null) }
        }
    }

    fun generate() {
        val s = _ui.value
        if (!s.canGenerate) return
        if (s.modelId !in s.downloaded) { _ui.update { it.copy(error = ImageGenText.NOT_DOWNLOADED_ERROR) }; return }
        val req = s.request()
        val model = s.model
        _ui.update { it.copy(running = true, step = 0, total = req.steps, error = null, seed = req.seed.toString(),
            progress = com.iris.localai.imagegen.SdProgress.State(), startedAt = System.currentTimeMillis()) }
        job = viewModelScope.launch {
            // The NPU pipeline holds ≈1.1 GB: free the chat model first (it reloads on the next chat message).
            if (model.backend == com.iris.localai.imagegen.Backend.QNN || model.backend == com.iris.localai.imagegen.Backend.SDCPP) runCatching { container.engine.unload(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_19a9bc)) }
            val r = withContext(Dispatchers.Default) {
                engine.generate(req, model) { step, total ->
                    _ui.update { it.copy(step = step, total = total, progress = com.iris.localai.imagegen.SdProgress.next(it.progress, step, total, req.steps)) }
                }
            }
            val saved = r.mapCatching { png -> withContext(Dispatchers.IO) { repo.save(png, req, model) } }
            saved.onSuccess { f -> _ui.update { it.copy(running = false, result = f.path, history = listOf(f.path) + it.history.filter { p -> p != f.path }) } }
                .onFailure { e -> _ui.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) } }
            if (model.backend == com.iris.localai.imagegen.Backend.SDCPP) refresh()
        }
    }

    fun cancel() {
        engine.cancel()
        job?.cancel()
        job = null
        _ui.update { it.copy(running = false, error = ImageGenText.CANCELLED) }
    }

    /** 2.4.2: recent sd.cpp log lines for a bug report (copied by the page). */
    suspend fun sdLog(): String = withContext(Dispatchers.IO) { com.iris.localai.imagegen.SdLog.recent() }

    fun showResult(path: String) = _ui.update { it.copy(result = path) }

    /** The 生图 page left the screen: free the NPU graphs (now, or when a running generation ends). */
    fun onLeave() = engines.releaseWhenIdle()

    override fun onCleared() {
        engines.releaseWhenIdle()
        super.onCleared()
    }

    fun runSelfCheck() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val dl = runCatching { repo.downloaded() }.getOrDefault(emptySet())
                val native = DeviceProbe.nativeProbe(getApplication(), refresh = true)
                DeviceProbe.report(DeviceProbe.probe(getApplication(), dl, native), native)
            }
            _ui.update { it.copy(report = report) }
        }
    }
}

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
    const val TITLE = "生图"
    const val DOWNLOADED = "已下载"
    const val NOT_DOWNLOADED = "未下载"
    const val DOWNLOAD = "下载"
    const val NOT_DOWNLOADED_ERROR = "模型未下载，请先点「下载」"
    const val EXTRACTING = "解压中"
    const val DELETE = "删除"
    const val DELETE_TITLE = "删除模型"
    const val RETRY = "重试"
    const val PROMPT = "正向提示词"
    const val NEGATIVE = "反向提示词"
    const val REF_IMAGE = "参考图"
    const val STRENGTH = "重绘强度"
    const val NO_IMG2IMG = "该模型为固定尺寸 NPU 模型，暂不支持参考图"
    const val STEPS = "步数"
    const val CFG = "CFG"
    const val SEED = "种子（留空随机）"
    const val GENERATE = "生成"
    const val CANCEL = "取消"
    const val CANCELLED = "已取消"
    const val SAVE = "保存"
    const val SHARE = "分享"
    const val TO_CHAT = "发到聊天"
    const val HISTORY = "历史"
    const val SELF_CHECK = "生图自检"
    const val RUN = "运行"
    const val COPY = "复制"
    const val FIXED_SIZE = "固定尺寸"
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
    val error: String? = null,
    val result: String? = null,
    val history: List<String> = emptyList(),
    val report: String? = null,
    /** Model id -> running / failed download (from [ImageGenDownloader.states]). */
    val downloads: Map<String, ImageDl> = emptyMap(),
) {
    val model: ImageModel get() = ImageCatalog.byId(modelId) ?: ImageCatalog.SD15_QNN
    /** Reference image + 重绘强度 work everywhere except the fixed-size QNN context binary (txt2img only). */
    val img2imgSupported: Boolean get() = model.backend != Backend.QNN
    val canGenerate: Boolean get() = !running && prompt.isNotBlank() && !model.needsHexagonRuntime

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
        if (m.needsHexagonRuntime || m.files.isEmpty()) return
        downloader.clearError(m.id)
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { repo.downloadRoot(m) }
            downloader.start(m, root, container.settings.current.downloadSource) { refresh() }
        }
    }

    fun cancelDownload(m: ImageModel) { downloader.cancel(m.id); refresh() }

    /** Deletes the model folder (complete or partial) after the page's confirm dialog. */
    fun deleteModel(m: ImageModel) {
        viewModelScope.launch {
            val ok = downloader.delete(m, withContext(Dispatchers.IO) { repo.roots() })
            if (!ok) _ui.update { it.copy(error = "删除失败") }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val (dl, hist) = withContext(Dispatchers.IO) {
                runCatching { repo.downloaded() }.getOrDefault(emptySet()) to runCatching { repo.history().map { it.path } }.getOrDefault(emptyList())
            }
            _ui.update { it.copy(downloaded = dl, history = hist) }
        }
    }

    fun edit(f: (ImageGenUi) -> ImageGenUi) = _ui.update(f)

    fun selectModel(m: ImageModel) {
        if (m.needsHexagonRuntime) return
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
            _ui.update { it.copy(initImage = path, error = if (path == null) "无法读取参考图" else null) }
        }
    }

    fun generate() {
        val s = _ui.value
        if (!s.canGenerate) return
        if (s.modelId !in s.downloaded) { _ui.update { it.copy(error = ImageGenText.NOT_DOWNLOADED_ERROR) }; return }
        val req = s.request()
        val model = s.model
        _ui.update { it.copy(running = true, step = 0, total = req.steps, error = null, seed = req.seed.toString()) }
        job = viewModelScope.launch {
            // The NPU pipeline holds ≈1.1 GB: free the chat model first (it reloads on the next chat message).
            if (model.backend == com.iris.localai.imagegen.Backend.QNN) runCatching { container.engine.unload("生图") }
            val r = withContext(Dispatchers.Default) {
                engine.generate(req, model) { step, total -> _ui.update { it.copy(step = step, total = total) } }
            }
            val saved = r.mapCatching { png -> withContext(Dispatchers.IO) { repo.save(png, req, model) } }
            saved.onSuccess { f -> _ui.update { it.copy(running = false, result = f.path, history = listOf(f.path) + it.history.filter { p -> p != f.path }) } }
                .onFailure { e -> _ui.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun cancel() {
        engine.cancel()
        job?.cancel()
        job = null
        _ui.update { it.copy(running = false, error = ImageGenText.CANCELLED) }
    }

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

package com.iris.localai.ui.imagegen

import com.iris.localai.I18n
import com.iris.localai.IrisApp
import com.iris.localai.R
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.Requester
import com.iris.localai.imagegen.AnimaMath
import com.iris.localai.imagegen.ImportErrors
import com.iris.localai.imagegen.AnimaQnnFiles
import com.iris.localai.imagegen.Defaults
import com.iris.localai.imagegen.Family
import com.iris.localai.imagegen.FileHead
import com.iris.localai.imagegen.FileRole
import com.iris.localai.imagegen.ImageGenEngines
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.ImageSeed
import com.iris.localai.imagegen.ImportArch
import com.iris.localai.imagegen.ImportDetect
import com.iris.localai.imagegen.ImportedImage
import com.iris.localai.imagegen.ImportedImages
import com.iris.localai.imagegen.PromptExpand
import com.iris.localai.imagegen.PromptTag
import com.iris.localai.imagegen.PromptTags
import com.iris.localai.imagegen.SdLog
import com.iris.localai.imagegen.SdProgress
import com.iris.localai.imagegen.SdRules
import com.iris.localai.imagegen.TagCat
import com.iris.localai.imagegen.TagLibrary
import com.iris.localai.imagegen.TagStore
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
import com.iris.localai.imagegen.FileRole as FR
import com.iris.localai.imagegen.LoraArch
import com.iris.localai.imagegen.ImageSizes
import com.iris.localai.imagegen.LoraCheck
import com.iris.localai.imagegen.LoraEntry
import com.iris.localai.imagegen.LoraInspect
import com.iris.localai.imagegen.LoraRules
import com.iris.localai.imagegen.LoraStore
import com.iris.localai.imagegen.PreviewFrame
import com.iris.localai.service.ImageGenJobService
import com.iris.localai.service.ImageGenMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Chinese copy of the 生图 page (tests read these). */
object ImageGenText {
    val TITLE: String get() = I18n.s(R.string.chat_19a9bc)
    val DOWNLOADED: String get() = I18n.s(R.string.imagegen_9719fe)
    val NOT_DOWNLOADED: String get() = I18n.s(R.string.imagegen_336189)
    val DOWNLOAD: String get() = I18n.s(R.string.settings_f26ef9)
    val NOT_DOWNLOADED_ERROR: String get() = I18n.s(R.string.imagegen_28b159)
    val EXTRACTING: String get() = I18n.s(R.string.imagegen_973e8a)
    val DELETE: String get() = I18n.s(R.string.chat_2f4aad)
    val DELETE_TITLE: String get() = I18n.s(R.string.imagegen_bb7a2e)
    val RETRY: String get() = I18n.s(R.string.models_3b7da7)
    val PROMPT: String get() = I18n.s(R.string.imagegen_68a756)
    val NEGATIVE: String get() = I18n.s(R.string.imagegen_9576af)
    val REF_IMAGE: String get() = I18n.s(R.string.imagegen_477f3a)
    val STRENGTH: String get() = I18n.s(R.string.imagegen_a27088)
    val NO_IMG2IMG: String get() = I18n.s(R.string.imagegen_b37ef6)
    val STEPS: String get() = I18n.s(R.string.imagegen_896303)
    const val CFG = "CFG"
    val SEED: String get() = I18n.s(R.string.imagegen_0e9bf6)
    val GENERATE: String get() = I18n.s(R.string.settings_4dfe70)
    val CANCEL: String get() = I18n.s(R.string.chat_625fb2)
    val CANCELLED: String get() = I18n.s(R.string.imagegen_2111cc)
    val SAVE: String get() = I18n.s(R.string.settings_be5fbb)
    val SHARE: String get() = I18n.s(R.string.files_c31f48)
    val TO_CHAT: String get() = I18n.s(R.string.imagegen_bf1781)
    val HISTORY: String get() = I18n.s(R.string.imagegen_c827d8)
    val SELF_CHECK: String get() = I18n.s(R.string.imagegen_328b2c)
    val RUN: String get() = I18n.s(R.string.imagegen_4c763b)
    val COPY: String get() = I18n.s(R.string.ui_79d3ab)
    val FIXED_SIZE: String get() = I18n.s(R.string.imagegen_e23c9e)
    /** sd.cpp models — which processor the next image uses, and the switches. */
    val SD_ON_GPU: String get() = I18n.s(R.string.sd241_on_gpu)
    val SD_ON_CPU: String get() = I18n.s(R.string.sd241_on_cpu)
    val SD_GPU_CRASHED: String get() = I18n.s(R.string.sd241_gpu_crashed)
    val RETRY_GPU: String get() = I18n.s(R.string.sd241_retry_gpu)
    val USE_CPU: String get() = I18n.s(R.string.sd241_use_cpu)
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
    /** the seed the last image actually used (shown with the result; the field is never overwritten). */
    val lastSeed: Long? = null,
    val running: Boolean = false,
    val step: Int = 0,
    val total: Int = 0,
    /** load / step / decode phase of the running generation, and when it started. */
    val progress: SdProgress.State = SdProgress.State(),
    val startedAt: Long = 0L,
    val error: String? = null,
    val result: String? = null,
    val history: List<String> = emptyList(),
    /** disk used by [history]. */
    val historyBytes: Long = 0L,
    /** Beta5 超分：进行中的那张图、进度 0..1（下载模型时为下载进度）、是否在下模型。 */
    val upscaling: String? = null,
    val upscaleProgress: Float = 0f,
    val upscaleDownloading: Boolean = false,
    val report: String? = null,
    /** sd.cpp — the GPU crashed last time (runs on the CPU until 「重试 GPU」). */
    val sdGpuCrashed: Boolean = false,
    /** sd.cpp — the next image runs on the CPU (crash or 「改用 CPU」). */
    val sdOnCpu: Boolean = false,
    /** the app died during an NPU (sdq) run. */
    val sdHtpCrashed: Boolean = false,
    /** the last error was the memory check (offer 「仍然生成」). */
    val lowMem: Boolean = false,
    /** Model id -> running / failed download (from [ImageGenDownloader.states]). */
    val downloads: Map<String, ImageDl> = emptyMap(),
    /** 低内存模式 / 提示词扩写（存在智能体设置里，与 generate_image 共用）。 */
    val lowMemMode: Boolean = false,
    val expand: Boolean = true,
    /** 标签库（内置 + 自定义）与当前分类。 */
    val tags: List<PromptTag> = PromptTags.BUILTIN,
    val tagCat: TagCat = TagCat.SUBJECT,
    /** 「AI 补全提示词」开着但没加载聊天模型（只补画质词）——界面给明确提示。 */
    val expandNoModel: Boolean = false,
    /** 正在扩写；上一张实际用的（扩写后）提示词。 */
    val expanding: Boolean = false,
    val usedPrompt: String? = null,
    /** 等待用户确认许可的模型（首次下载前）。 */
    val licenseAsk: String? = null,
    /** 导入进度（0..1，null = 没在导入）与正在复制的文件名。 */
    val importing: Float? = null,
    val importFile: String = "",
    /** 模型清单版本（导入 / 改名 / 删除后 +1，让页面重读 catalog）。 */
    val catalogRev: Int = 0,
    /** 2.7.1：导入的 LoRA、当前底模的结构（UNKNOWN = 不支持 LoRA）、导入结果一句话。 */
    val loras: List<LoraEntry> = emptyList(),
    val loraBase: LoraArch = LoraArch.UNKNOWN,
    val loraMsg: String? = null,
    val loraBusy: Boolean = false,
    /** 2.7.1：实时预览（当前 x0 估计的低清图）。 */
    val preview: PreviewFrame? = null,
    /** r5：Anima NPU 包里编好的尺寸（引擎加载后写在模型目录）。 */
    val packSizes: List<Pair<Int, Int>> = emptyList(),
) {
    /** r5：按模型类型给的尺寸选项（固定尺寸包只有那一档）。 */
    val sizeOptions: List<ImageSizes.Option> get() = ImageSizes.options(model, loraBase, packSizes)
    /** 当前尺寸不在选项里（换模型、导入模型识别出结构后）→ 用默认值。 */
    /** 读出底模结构：只有它改变了尺寸基准（如导入的其实是 SD1.5）才校正尺寸——「用这组参数」填的尺寸不被冲掉。 */
    fun withArch(a: LoraArch): ImageGenUi {
        val next = copy(loraBase = a)
        return if (ImageSizes.base(model, loraBase) != ImageSizes.base(model, a)) next.withValidSize() else next
    }
    fun withValidSize(): ImageGenUi {
        val o = sizeOptions
        if (o.any { it.width == width && it.height == height }) return this
        val d = ImageSizes.default(o)
        return copy(width = d.width, height = d.height)
    }
    /** 当前模型走 sd.cpp（GPU/CPU）才能挂 LoRA；NPU 模型权重编在上下文里。 */
    val loraSupported: Boolean get() = model.backend == Backend.SDCPP && !model.sdq
    val lorasHere: List<LoraEntry> get() = loras.filter { it.archOrUnknown.fits(loraBase) }
    val model: ImageModel get() = ImageCatalog.byId(modelId) ?: ImageCatalog.SD15_QNN
    /** Reference image + 重绘强度 work everywhere except the fixed-size QNN context binary (txt2img only). */
    val img2imgSupported: Boolean get() = model.backend != Backend.QNN
    val canGenerate: Boolean get() = !running && prompt.isNotBlank()

    fun request(): ImageRequest = ImageRequest(
        prompt = prompt, negative = negative, width = width, height = height, steps = steps, cfg = cfg,
        // empty field = a new random seed on every call (one call per generation); a typed seed stays fixed.
        seed = ImageSeed.pick(seed),
        initImagePath = initImage?.takeIf { img2imgSupported }, strength = strength, lowMem = lowMemMode,
    ).normalize(model)
}

class ImageGenViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ImageGenRepository(app)
    private val engines = ImageGenEngines.get(app)
    private val engine: ImageGenEngine = engines
    private val _ui = MutableStateFlow(ImageGenUi())
    val ui: StateFlow<ImageGenUi> = _ui.asStateFlow()
    private var job: Job? = null

    val catalog: List<ImageModel> get() = repo.catalog

    private val container = (app as IrisApp).container
    private val downloader = container.imageGenDownloader

    private val agentSettings = container.agent.settings
    private val prefs = app.getSharedPreferences("iris_imagegen", android.content.Context.MODE_PRIVATE)

    private val loraStore = LoraStore(app.filesDir)
    private val baseArchCache = HashMap<String, LoraArch>()
    /** 本 ViewModel 发起的那张图的序号（其余的是别的页面实例发起的，接上显示）。 */
    private var ownSerial = -1L

    init {
        refresh()
        loadLoras()
        viewModelScope.launch {
            // 离开过生图页 / 页面重建：接上仍在跑的那张图，或显示它的结果
            ImageGenMonitor.job.collect { j -> if (j != null && j.serial != ownSerial) adopt(j) }
        }
        viewModelScope.launch {
            agentSettings.settings.collect { a -> _ui.update { it.copy(lowMemMode = a.imageLowMemOn, expand = a.imagePromptExpand) } }
        }
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { TagLibrary.tags(app) }
            _ui.update { it.copy(tags = t) }
        }
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
        // 非商用许可的模型，首次下载前先确认
        if (m.licenseUrl.isNotEmpty() && !prefs.getBoolean("license:" + m.id, false)) { _ui.update { it.copy(licenseAsk = m.id) }; return }
        // after a failure the button is 「换源重试」 (the source that failed goes last; .part files resume)
        val failed = downloader.states.value[m.id] is ImageDl.Failed
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
            if (!ok) _ui.update { it.copy(error = I18n.s(R.string.agent_acf066)) }
            if (m.imported) _ui.update { it.copy(catalogRev = it.catalogRev + 1) }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val (dl, hist) = withContext(Dispatchers.IO) {
                runCatching { repo.downloaded() }.getOrDefault(emptySet()) to runCatching { repo.history() }.getOrDefault(emptyList())
            }
            val sd = engines.sdEngine()
            _ui.update { it.copy(downloaded = dl, history = hist.map { f -> f.path }, historyBytes = hist.sumOf { f -> f.length() }, sdGpuCrashed = sd.gpuCrashedBefore, sdOnCpu = sd.usesCpu, sdHtpCrashed = sd.htpCrashedBefore) }
            updateLoraBase()
        }
    }

    /** 「重试 GPU」: forget the last GPU crash (and a 「改用 CPU」 choice). */
    fun retryGpu() {
        engines.sdEngine().clearGpuCrash()
        _ui.update { it.copy(error = null) }
        refresh()
    }

    /** 「改用 CPU」: slower, but needs only the compute buffers (weights stay mmapped). */
    fun useCpu() {
        engines.sdEngine().setPreferCpu(true)
        _ui.update { it.copy(error = null) }
        refresh()
    }

    fun edit(f: (ImageGenUi) -> ImageGenUi) = _ui.update(f)

    /** 许可确认（同意后开始下载，之后不再问）。 */
    fun acceptLicense(accept: Boolean) {
        val id = _ui.value.licenseAsk ?: return
        _ui.update { it.copy(licenseAsk = null) }
        if (!accept) return
        prefs.edit().putBoolean("license:$id", true).apply()
        ImageCatalog.byId(id)?.let(::download)
    }

    fun setLowMem(on: Boolean) = agentSettings.update { it.copy(imageLowMem = on, imageLowMemSet = true) }
    fun setExpand(on: Boolean) = agentSettings.update { it.copy(imagePromptExpand = on) }

    /** 标签库：点标签追加到提示词（负面类追加到负面词）。 */
    /** r5：提示词辅助——整理顺序 / 补画质词 / 填负面词。 */
    fun arrangePrompt() = _ui.update { it.copy(prompt = com.iris.localai.imagegen.PromptOrder.arrange(it.prompt)) }
    fun addQuality() = _ui.update { it.copy(prompt = com.iris.localai.imagegen.PromptOrder.addAll(it.prompt, com.iris.localai.imagegen.PromptOrder.quality(it.model.tagStyle), it.model.tagStyle)) }
    fun addNegative() = _ui.update { it.copy(negative = com.iris.localai.imagegen.PromptOrder.addAll(it.negative, com.iris.localai.imagegen.PromptOrder.negative(it.model.tagStyle), false)) }

    fun useTag(t: PromptTag) = _ui.update {
        val text = t.text(it.model.tagStyle)
        if (t.cat == TagCat.NEGATIVE) it.copy(negative = PromptTags.append(it.negative, text))
        else it.copy(prompt = PromptTags.append(it.prompt, text))
    }

    fun addTag(cat: TagCat, zh: String, en: String) = changeTags { TagStore.add(it, cat, zh, en) }
    fun removeTag(t: PromptTag) = changeTags { TagStore.remove(it, t) }
    fun editTag(old: PromptTag, cat: TagCat, zh: String, en: String) = changeTags { TagStore.edit(it, old, cat, zh, en) }

    /** A chat model is loaded (AI 补全提示词 can use it). */
    val chatModelLoaded: kotlinx.coroutines.flow.Flow<Boolean> =
        container.engine.state.map { it is com.iris.localai.engine.EngineState.Ready }
    /** 恢复内置标签（清掉隐藏，保留自定义）。 */
    fun resetTags() = changeTags { it.copy(hidden = emptySet()) }

    private fun changeTags(f: (TagStore.Data) -> TagStore.Data) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) {
                val d = f(TagLibrary.load(app))
                TagLibrary.save(app, d)
                PromptTags.merged(d.custom, d.hidden)
            }
            _ui.update { it.copy(tags = t) }
        }
    }

    /**
     * 提示词扩写：有已加载的文本模型就让它改写（最多 45 秒、256 token、不思考），失败/超时/没加载用规则模板。
     */
    private suspend fun expandPrompt(prompt: String, model: ImageModel): String {
        val ex = PromptExpand
        if (!ex.needed(prompt)) return prompt
        val eng = container.engine
        if (eng.readyModel != null && eng.busy.value == null) {
            val out = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    val sb = StringBuilder()
                    val sampling = eng.samplingFor(true).copy(maxNewTokens = 256, contextShift = false)
                    eng.generate(listOf(PromptMessage(PromptMessage.SYSTEM, ex.system(model.tagStyle)),
                        PromptMessage(PromptMessage.USER, prompt)), sampling, Requester.CHAT).collect { ev ->
                        if (ev is GenEvent.Piece) sb.append(ev.text)
                    }
                    sb.toString()
                }
            }.getOrNull()
            out?.let { ex.clean(it, prompt) }?.let { return it }
        } else if (eng.readyModel == null) _ui.update { it.copy(expandNoModel = true) }
        return ex.rules(prompt, model.tagStyle)
    }

    fun selectModel(m: ImageModel) {
        _ui.update {
            val old = it.model
            // a model's suggested prompt / negative fill fields that are empty or still the previous model's default
            fun pick(cur: String, oldDefault: String, newDefault: String) =
                if (cur.isBlank() || (oldDefault.isNotEmpty() && cur == oldDefault)) newDefault else cur
            // r5：尺寸按新模型的类型给（SD1.5 512 基 / 1024 基 / NPU 包自己的几档）；同一类且当前尺寸可用就留着。
            val arch = baseArchCache[m.id] ?: LoraArch.UNKNOWN
            val pack = if (m.animaQnn) packFor(m) else emptyList()
            val (w, h) = ImageSizes.pick(it.width, it.height, ImageSizes.options(m, arch, pack),
                sameBase = !old.animaQnn && !m.animaQnn && old.fixedSize == null && m.fixedSize == null && ImageSizes.base(old, it.loraBase) == ImageSizes.base(m, arch))
            it.copy(modelId = m.id, steps = m.defaultSteps, cfg = m.defaultCfg, width = w, height = h, packSizes = pack,
                prompt = pick(it.prompt, old.defaultPrompt, m.defaultPrompt), negative = pick(it.negative, old.defaultNegative, m.defaultNegative))
        }
        updateLoraBase()
    }

    private fun packFor(m: ImageModel): List<Pair<Int, Int>> = runCatching { ImageSizes.readPack(repo.downloadedDir(m)) }.getOrDefault(emptyList())

    // ---- 2.7.1：LoRA ----

    private fun loadLoras() {
        viewModelScope.launch {
            val l = withContext(Dispatchers.IO) { loraStore.list() }
            _ui.update { it.copy(loras = l) }
            updateLoraBase()
        }
    }

    /** 当前模型（sd.cpp）主文件的结构，读文件头判断，按模型缓存。 */
    private fun updateLoraBase() {
        val m = _ui.value.model
        if (m.backend != Backend.SDCPP || m.sdq) { _ui.update { it.copy(loraBase = LoraArch.UNKNOWN) }; return }
        baseArchCache[m.id]?.let { a -> _ui.update { it.withArch(a) }; return }
        viewModelScope.launch {
            val a = withContext(Dispatchers.IO) {
                runCatching {
                    val f = m.sdFiles ?: return@runCatching LoraArch.UNKNOWN
                    val root = repo.downloadedDir(m)?.parentFile ?: return@runCatching LoraArch.UNKNOWN
                    val file = File(root, f.model.ifEmpty { f.diffusion })
                    val head = file.inputStream().buffered().use { ImportDetect.readHead(file.name, it) } ?: return@runCatching LoraArch.UNKNOWN
                    LoraInspect.baseArch(head.tensors, head.ggufArch)
                }.getOrDefault(LoraArch.UNKNOWN)
            }
            if (a != LoraArch.UNKNOWN) baseArchCache[m.id] = a
            // r5：导入的模型读出结构后（SD1.5 / SDXL…）尺寸选项可能变了，当前尺寸不在里面就换成默认。
            _ui.update { if (it.modelId == m.id) it.withArch(a) else it }
        }
    }

    /** 导入 LoRA：先读文件头校验，不合适直接说原因，合适再复制。 */
    fun importLora(uri: Uri) {
        if (_ui.value.loraBusy) return
        _ui.update { it.copy(loraBusy = true, loraMsg = null) }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = withContext(Dispatchers.IO) { nameOf(uri) }
            val check = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.buffered()?.use { LoraInspect.check(LoraInspect.readHeader(it)) } }
                    .getOrNull() ?: LoraCheck(com.iris.localai.imagegen.LoraProblem.NOT_SAFETENSORS)
            }
            if (!check.ok) { _ui.update { it.copy(loraBusy = false, loraMsg = LoraText.problem(check)) }; return@launch }
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = loraStore.target(name)
                    val tmp = File(dst.path + ".part")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } } ?: error("open")
                    // 复制后的文件再验一次（防止读到一半的流）
                    if (!LoraInspect.check(tmp).ok || !tmp.renameTo(dst)) { tmp.delete(); error("copy") }
                    val turbo = LoraInspect.isTurbo(name)
                    val e = LoraEntry(
                        id = "lora-" + System.currentTimeMillis().toString(36), name = name.substringBeforeLast('.'), file = dst.name,
                        arch = check.arch.name, format = check.format?.name.orEmpty(), rank = check.rank,
                        on = false, scale = 1f, steps = if (turbo) LoraInspect.turboSteps(name) else null,
                    )
                    loraStore.save(loraStore.list() + e)
                    e
                }.getOrNull()
            }
            _ui.update {
                if (entry == null) it.copy(loraBusy = false, loraMsg = I18n.s(R.string.ig271_lora_e_copy))
                else it.copy(loraBusy = false, loras = it.loras + entry, loraMsg = LoraText.ok(check, entry.archOrUnknown.fits(it.loraBase) || !it.loraSupported))
            }
        }
    }

    private fun changeLora(id: String, f: (LoraEntry) -> LoraEntry) {
        _ui.update { u -> u.copy(loras = u.loras.map { if (it.id == id) f(it) else it }) }
        val list = _ui.value.loras
        viewModelScope.launch(Dispatchers.IO) { runCatching { loraStore.save(list) } }
    }

    /** 开关一个 LoRA；开 Turbo 一类时把步数换成它的步数（之后可手改）。 */
    fun setLoraOn(id: String, on: Boolean) {
        changeLora(id) { it.copy(on = on) }
        val e = _ui.value.loras.firstOrNull { it.id == id } ?: return
        if (on) e.steps?.let { st -> _ui.update { it.copy(steps = st) } }
    }

    fun setLoraScale(id: String, v: Float) = changeLora(id) { it.copy(scale = LoraRules.clampScale(v)) }

    /** Turbo LoRA 的手填步数。 */
    fun setLoraSteps(id: String, text: String) {
        val n = text.filter(Char::isDigit).take(2).toIntOrNull()?.coerceIn(1, 50) ?: return
        changeLora(id) { it.copy(steps = n) }
        if (_ui.value.loras.firstOrNull { it.id == id }?.on == true) _ui.update { it.copy(steps = n) }
    }

    fun deleteLora(id: String) {
        _ui.update { u -> u.copy(loras = u.loras.filter { it.id != id }) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { loraStore.delete(id) } }
    }

    fun clearLoraMsg() = _ui.update { it.copy(loraMsg = null) }

    /** 另一个页面实例发起的生图：接上进度 / 预览，结束时显示结果。 */
    private fun adopt(j: ImageGenMonitor.Job) {
        if (!j.finished) {
            _ui.update { it.copy(running = true, step = j.step, total = j.total, startedAt = j.startedAt, preview = j.preview,
                progress = SdProgress.next(it.progress, j.step, j.total, it.steps)) }
        } else if (_ui.value.running || j.result != null && _ui.value.result != j.result) {
            _ui.update { it.copy(running = false, preview = null, result = j.result ?: it.result, lastSeed = j.seed ?: it.lastSeed,
                usedPrompt = j.usedPrompt, error = j.error, lowMem = j.lowMem) }
            refresh()
        }
    }

    /** Copies the picked reference image into the cache (the Uri grant does not outlive the picker). */
    // ---- 2.6.9: 导入生图模型（用户自备文件，App 不托管） ----

    /** 文件显示名（选完文件后在对话框里显示）。 */
    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) { nameOf(uri) }

    private fun nameOf(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model"

    private fun sizeOf(uri: Uri): Long = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    /** 选中的一批文件读头后的识别结果（对话框显示每个文件的用途，并填默认步数 / CFG）。 */
    data class ImportPlan(
        val files: List<Triple<Uri, String, FileRole>>,
        val npu: Boolean = false,
        val main: Uri? = null,
        val family: Family = Family.DIT,
        val defaults: Defaults = Defaults(20, 7f, false),
        /** 复用已下载的文本编码器 / VAE（相对路径，空 = 不复用）。 */
        val reuseLlm: String = "",
        val reuseVae: String = "",
        val error: String? = null,
    )

    suspend fun inspect(uris: List<Uri>): ImportPlan = withContext(Dispatchers.IO) {
        val det = ImportDetect
        val app = getApplication<Application>()
        val heads = HashMap<Uri, FileHead?>()
        val files = uris.distinct().map { u ->
            val n = nameOf(u)
            val h = if (det.isNpuFile(n)) null else runCatching { app.contentResolver.openInputStream(u)?.use { det.readHead(n, it.buffered(1 shl 16)) } }.getOrNull()
            heads[u] = h
            Triple(u, n, det.role(n, h))
        }
        if (det.isNpuPlan(files.map { it.second })) return@withContext ImportPlan(files, npu = true, defaults = Defaults(8, 1f, true))
        fun s(id: Int, vararg a: Any) = I18n.s(id, *a)
        files.firstOrNull { it.third == FileRole.UNKNOWN }?.let { return@withContext ImportPlan(files, error = s(R.string.ig27_unknown, it.second)) }
        val mains = files.filter { it.third == FileRole.CHECKPOINT || it.third == FileRole.DIFFUSION }
        if (mains.size != 1) return@withContext ImportPlan(files, error = s(if (mains.isEmpty()) R.string.ig27_no_main else R.string.ig27_two_main))
        val m = mains[0]
        val fam = det.family(m.third, heads[m.first])
        val root = repo.modelsLocation().dir
        val (rl, rv) = det.reusePaths(fam)
        val roles = files.map { it.third }.toSet()
        val enc = roles.any { it == FileRole.LLM || it == FileRole.T5 || it == FileRole.CLIP_L || it == FileRole.CLIP_G }
        val reuseLlm = if (!enc && rl.isNotEmpty() && File(root, rl).isFile) rl else ""
        val reuseVae = if (FileRole.VAE !in roles && rv.isNotEmpty() && File(root, rv).isFile) rv else ""
        var err: String? = null
        if (m.third == FileRole.DIFFUSION && ((!enc && reuseLlm.isEmpty()) || (FileRole.VAE !in roles && reuseVae.isEmpty())))
            err = s(R.string.ig269_need_parts)
        val total = files.sumOf { sizeOf(it.first).coerceAtLeast(0L) } + listOf(reuseLlm, reuseVae).filter { it.isNotEmpty() }.sumOf { File(root, it).length() }
        val mem = runCatching { android.app.ActivityManager.MemoryInfo().also { (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
        if (err == null && det.tooBig(total, mem)) err = s(R.string.ig27_too_big, String.format(java.util.Locale.US, "%.1f", total / 1e9))
        ImportPlan(files, main = m.first, family = fam, defaults = det.defaults(fam, m.second), reuseLlm = reuseLlm, reuseVae = reuseVae, error = err)
    }

    /** 按 [inspect] 的识别结果复制文件并登记（不按牌子分）。 */
    fun importAuto(plan: ImportPlan, name: String, steps: Int, cfg: Float) {
        if (plan.npu) { importNpuPack(name, plan.files.map { it.first }, steps); return }
        if (_ui.value.importing != null || plan.error != null || plan.main == null) return
        val imp = ImportedImages
        viewModelScope.launch {
            val id = imp.newId()
            val root = withContext(Dispatchers.IO) { repo.modelsLocation().dir }
            val dir = File(root, id)
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val total = plan.files.sumOf { sizeOf(it.first).coerceAtLeast(0L) }.coerceAtLeast(1L)
                    var done = 0L
                    fun copy(uri: Uri, fn0: String, idx: Int): String {
                        val fn = imp.safeFileName(fn0)
                        if (!imp.acceptable(fn)) throw IllegalArgumentException(I18n.s(R.string.ig269_bad_ext, fn))
                        dir.mkdirs()
                        val out = File(dir, fn).let { if (it.exists()) File(dir, "$idx-$fn") else it }
                        val part = File(dir, out.name + ".part")
                        _ui.update { it.copy(importFile = fn) }
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                            part.outputStream().use { os ->
                                val buf = ByteArray(1 shl 20)
                                var last = 0L
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, n); done += n
                                    val now = System.currentTimeMillis()
                                    if (now - last >= 200) { last = now; _ui.update { it.copy(importing = (done.toFloat() / total).coerceIn(0f, 1f)) } }
                                }
                            }
                        } ?: throw IllegalStateException(I18n.s(R.string.ig269_bad_file, fn))
                        if (!part.renameTo(out)) throw IllegalStateException(I18n.s(R.string.ig269_bad_file, fn))
                        return "$id/${out.name}"
                    }
                    _ui.update { it.copy(importing = 0f) }
                    val rel = HashMap<FileRole, String>()
                    plan.files.forEachIndexed { i, (u, n, r) -> rel[r] = copy(u, n, i) }
                    val mainRel = rel[FR.CHECKPOINT] ?: rel[FR.DIFFUSION] ?: error("缺少主模型文件")
                    val isCkpt = rel.containsKey(FR.CHECKPOINT)
                    imp.put(ImportedImage(
                        id = id, name = imp.cleanName(name, imp.nameFromFile(mainRel.substringAfterLast('/'))), arch = imp.AUTO,
                        diffusion = if (isCkpt) "" else mainRel, llm = rel[FR.LLM] ?: plan.reuseLlm, vae = rel[FR.VAE] ?: plan.reuseVae,
                        steps = steps.coerceIn(1, 100), cfg = cfg.coerceIn(0f, 30f),
                        model = if (isCkpt) mainRel else "", clipL = rel[FR.CLIP_L] ?: "", clipG = rel[FR.CLIP_G] ?: "", t5 = rel[FR.T5] ?: "",
                        tagStyle = plan.defaults.tagStyle,
                    ))
                    null
                }.exceptionOrNull()
            }
            if (err != null) withContext(Dispatchers.IO) { dir.deleteRecursively() }
            _ui.update { it.copy(importing = null, importFile = "", catalogRev = it.catalogRev + 1, error = err?.let { e -> ImportErrors.message(e) }) }
            refresh()
            if (err == null) imp.get(id)?.toModel()?.let { m ->
                selectModel(m)
                // 引擎库（libiris_sd.so）没下过就补下；权重本身不下载
                if (!withContext(Dispatchers.IO) { ImageGenFiles.isDownloaded(root, m) }) download(m)
            }
        }
    }

    /** 导入 Anima NPU 包：多选的文件里只收包内约定的文件名，复制进 `<id>/`。 */
    fun importNpuPack(name: String, uris: List<Uri>, steps: Int) {
        if (_ui.value.importing != null) return
        val imp = ImportedImages
        viewModelScope.launch {
            val id = imp.newId()
            val root = withContext(Dispatchers.IO) { repo.modelsLocation().dir }
            val dir = File(root, id)
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val named = uris.map { it to nameOf(it) }
                    val zips = named.filter { ImportDetect.isZip(it.second) }
                    val picked = named.filter { it.second in AnimaQnnFiles.NAMES }.distinctBy { it.second }
                    val total = (zips + picked).sumOf { sizeOf(it.first).coerceAtLeast(0L) }.coerceAtLeast(1L)
                    var done = 0L
                    var last = 0L
                    fun tick(n: Int) {
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last >= 200) { last = now; _ui.update { it.copy(importing = (done.toFloat() / total).coerceIn(0f, 1f)) } }
                    }
                    fun save(fn: String, ins: java.io.InputStream, count: Boolean) {
                        val part = File(dir, "$fn.part")
                        _ui.update { it.copy(importFile = fn) }
                        part.outputStream().use { os ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n); if (count) tick(n)
                            }
                        }
                        if (!part.renameTo(File(dir, fn))) throw IllegalStateException(I18n.s(R.string.ig269_bad_file, fn))
                    }
                    dir.mkdirs()
                    _ui.update { it.copy(importing = 0f) }
                    // 整包 zip：只解出包内认得的文件（按文件名，忽略 zip 里的目录层级）；进度按读过的压缩字节算
                    for ((uri, zn) in zips) {
                        val raw = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IllegalStateException(I18n.s(R.string.ig269_bad_file, zn))
                        val counted = object : java.io.FilterInputStream(raw.buffered(1 shl 20)) {
                            override fun read(): Int = super.read().also { if (it >= 0) tick(1) }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) tick(it) }
                        }
                        java.util.zip.ZipInputStream(counted).use { z ->
                            while (true) {
                                val e = z.nextEntry ?: break
                                val fn = e.name.substringAfterLast('/')
                                if (!e.isDirectory && fn in AnimaQnnFiles.NAMES && !File(dir, fn).exists()) save(fn, z, count = false)
                            }
                        }
                    }
                    for ((uri, fn) in picked) {
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins -> save(fn, ins, count = true) }
                            ?: throw IllegalStateException(I18n.s(R.string.ig269_bad_file, fn))
                    }
                    val files = AnimaQnnFiles(dir)
                    val miss = files.missing()
                    if (miss.isNotEmpty()) throw IllegalStateException(I18n.s(R.string.ig270_npu_missing, miss.joinToString()))
                    val a = ImportArch.ANIMA_NPU
                    imp.put(ImportedImage(id, imp.cleanName(name, I18n.s(R.string.ig27_npu)), a.name, id, id, id, steps.coerceIn(1, 50), 1f,
                        pack = if (files.ld) ImportedImage.PACK_LD else ""))
                    null
                }.exceptionOrNull()
            }
            if (err != null) withContext(Dispatchers.IO) { dir.deleteRecursively() }
            _ui.update { it.copy(importing = null, importFile = "", catalogRev = it.catalogRev + 1, error = err?.let { e -> ImportErrors.message(e) }) }
            refresh()
            if (err == null) imp.get(id)?.toModel()?.let { selectModel(it) }
        }
    }

    /** Anima NPU 模型的调度偏移（高级）：存进该导入模型，下次生成起用。 */
    fun setAnimaShift(m: ImageModel, v: Float) {
        val shift = AnimaMath.clampShift(v)
        if (!m.imported || !m.animaQnn || shift == m.animaShift) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ImportedImages.get(m.id)?.let { e -> if (e.shift != shift) ImportedImages.put(e.copy(shift = shift)) } }
            _ui.update { it.copy(catalogRev = it.catalogRev + 1) }
        }
    }

    fun renameImported(m: ImageModel, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ImportedImages.rename(m.id, name) }
            _ui.update { it.copy(catalogRev = it.catalogRev + 1) }
        }
    }

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
            _ui.update { it.copy(initImage = path, error = if (path == null) I18n.s(R.string.imagegen_a7f86a) else null) }
        }
    }

    fun generate() {
        val s = _ui.value
        if (!s.canGenerate || ImageGenMonitor.running) return
        if (s.modelId !in s.downloaded) { _ui.update { it.copy(error = ImageGenText.NOT_DOWNLOADED_ERROR) }; return }
        val model = s.model
        // LoRA：当前底模能用的、开着的；开了 Turbo 一类就用它的步数
        val loras = if (s.loraSupported) LoraRules.active(s.loras, s.loraBase, loraStore.dir) else emptyList()
        val turboSteps = if (s.loraSupported) s.lorasHere.firstOrNull { it.on && it.steps != null }?.steps else null
        val req0 = s.request().let { r -> r.copy(loras = loras, steps = turboSteps ?: r.steps) }.normalize(model)
        // 关了风险提示弹窗 = 跳过内存预检，直接生成
        if (!container.settings.current.warnPopups) engines.sdEngine().forceOnce = true
        _ui.update { it.copy(running = true, step = 0, total = req0.steps, error = null, lowMem = false, lastSeed = null, usedPrompt = null,
            progress = SdProgress.State(), startedAt = System.currentTimeMillis(), preview = null) }
        ownSerial = ImageGenMonitor.begin(model.name, req0.steps) { cancel() }
        // 前台服务：离开生图页、切到后台都继续生成，通知栏显示进度
        ImageGenJobService.start(getApplication())
        // 跑在应用级作用域：页面 / Activity 销毁也不打断
        job = container.scope.launch {
            // 扩写在卸载文本模型之前（要用它）。
            val req = if (s.expand && req0.initImagePath == null) {
                _ui.update { it.copy(expanding = true, expandNoModel = false) }
                // r5：标签模型扩写出来的提示词按标签约定整理顺序、去重（用户自己写的不动）。
                val p = expandPrompt(req0.prompt, model).let { e -> if (model.tagStyle && e != req0.prompt) com.iris.localai.imagegen.PromptOrder.arrange(e) else e }
                _ui.update { it.copy(expanding = false, usedPrompt = p.takeIf { x -> x != req0.prompt }) }
                req0.copy(prompt = p, negative = PromptExpand.negative(req0.negative, model.tagStyle))
            } else req0
            // The NPU pipeline holds ≈1.1 GB: free the chat model first (it reloads on the next chat message).
            if (model.backend == Backend.QNN || model.backend == Backend.SDCPP) runCatching { container.engine.unload(I18n.s(R.string.chat_19a9bc)) }
            val r = try {
                withContext(Dispatchers.Default) {
                    engines.generate(req, model, { step, total ->
                        ImageGenMonitor.progress(step, total)
                        _ui.update { it.copy(step = step, total = total, progress = SdProgress.next(it.progress, step, total, req.steps)) }
                    }, { f ->
                        ImageGenMonitor.preview(f)
                        _ui.update { it.copy(preview = f) }
                    })
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                ImageGenMonitor.finish(null, ImageGenText.CANCELLED, stopped = true)
                throw c
            }
            val saved = r.mapCatching { png -> withContext(Dispatchers.IO) { repo.save(png, req, model) } }
            saved.onSuccess { f ->
                ImageGenMonitor.finish(f.path, null, stopped = false, seed = req.seed, usedPrompt = _ui.value.usedPrompt)
                _ui.update { it.copy(running = false, preview = null, result = f.path, lastSeed = req.seed, history = listOf(f.path) + it.history.filter { p -> p != f.path }, historyBytes = it.historyBytes + f.length()) }
            }.onFailure { e ->
                val msg = e.message ?: e.javaClass.simpleName
                ImageGenMonitor.finish(null, msg, stopped = false, lowMem = e is SdRules.LowMemory)
                _ui.update { it.copy(running = false, preview = null, error = msg, lowMem = e is SdRules.LowMemory) }
            }
            if (model.backend == Backend.SDCPP) refresh()
            // r5：Anima NPU 包第一次加载后才知道它有哪几档尺寸。
            if (model.animaQnn) { val p = withContext(Dispatchers.IO) { packFor(model) }; _ui.update { if (it.modelId == model.id) it.copy(packSizes = p) else it } }
        }
    }

    /** 「仍然生成」: run once past the memory check. */
    fun generateAnyway() {
        engines.sdEngine().forceOnce = true
        generate()
    }

    fun cancel() {
        engine.cancel()
        val own = job
        if (own == null && ImageGenMonitor.running) {
            // 别的页面实例发起的那张：交给它停
            ImageGenMonitor.requestStop()
        } else {
            own?.cancel()
            job = null
            if (ImageGenMonitor.running) ImageGenMonitor.finish(null, ImageGenText.CANCELLED, stopped = true)
        }
        _ui.update { it.copy(running = false, expanding = false, preview = null, error = ImageGenText.CANCELLED) }
    }

    /** recent sd.cpp log lines for a bug report (copied by the page). */
    suspend fun sdLog(): String = withContext(Dispatchers.IO) { SdLog.recent() }

    /** a history image shown as the result: its seed is in its own parameters, not [ImageGenUi.lastSeed]. */
    fun showResult(path: String) = _ui.update { it.copy(result = path, lastSeed = if (path == it.result) it.lastSeed else null) }

    /** parameters embedded in a history image (prompt, seed, size …), null when it has none. */
    suspend fun params(path: String): ImageGenFiles.Params? = withContext(Dispatchers.IO) { repo.params(path) }

    /** Beta5：看图页「放大」——第一次先下模型（17.9 MB），放大结果作为新图存进历史，[onDone] 拿到新图路径。 */
    fun upscale(path: String, factor: Int, onDone: (String?) -> Unit) {
        if (_ui.value.upscaling != null) return
        val ctx = getApplication<Application>()
        // 3.0.0：先查库和尺寸，不合适就别白下 18 MB 模型
        if (!com.iris.localai.imagegen.UpscaleNative.available) {
            _ui.update { it.copy(error = I18n.s(R.string.b5_upscale_failed, "upscale library missing")) }; onDone(null); return
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(path, bounds) }
        if (bounds.outWidth > 0 && !com.iris.localai.imagegen.UpscaleRules.fits(bounds.outWidth, bounds.outHeight, factor)) {
            _ui.update { it.copy(error = I18n.s(R.string.b30_upscale_big, factor, com.iris.localai.imagegen.UpscaleRules.maxSide(factor))) }; onDone(null); return
        }
        _ui.update { it.copy(upscaling = path, upscaleProgress = 0f, upscaleDownloading = !com.iris.localai.imagegen.Upscaler.modelReady(ctx)) }
        viewModelScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    com.iris.localai.imagegen.Upscaler.ensureModel(ctx) { b ->
                        _ui.update { it.copy(upscaleProgress = (b.toFloat() / com.iris.localai.imagegen.Upscaler.MODEL_SIZE).coerceIn(0f, 1f)) }
                    }
                }
                _ui.update { it.copy(upscaleDownloading = false, upscaleProgress = 0f) }
                val src = java.io.File(path)
                val png = com.iris.localai.imagegen.Upscaler.upscale(ctx, src, factor) { p -> _ui.update { it.copy(upscaleProgress = p) } }
                withContext(Dispatchers.IO) { repo.saveUpscaled(src, png, factor) }
            }
            r.onSuccess { f ->
                _ui.update { it.copy(upscaling = null, upscaleDownloading = false, history = listOf(f.path) + it.history.filter { p -> p != f.path }, historyBytes = it.historyBytes + f.length()) }
                onDone(f.path)
            }.onFailure { e ->
                val msg = if (e is OutOfMemoryError) I18n.s(R.string.b30_upscale_oom) else I18n.s(R.string.b5_upscale_failed, e.message ?: e.javaClass.simpleName)
                _ui.update { it.copy(upscaling = null, upscaleDownloading = false, error = msg) }
                onDone(null)
            }
        }
    }

    /** 「删除」 in the history (one image or a multi-select), after the page's confirm dialog. */
    fun deleteHistory(paths: Collection<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val gone = withContext(Dispatchers.IO) { repo.deleteHistory(paths) }
            _ui.update { u ->
                u.copy(
                    history = u.history.filter { it !in gone },
                    result = u.result?.takeIf { it !in gone },
                    error = if (gone.size < paths.size) "有 ${paths.size - gone.size} 张图片删除失败" else u.error,
                )
            }
            refresh()
        }
    }

    /**
     * 「用这组参数」: fills prompt / negative / steps / CFG / seed / size from a history image.
     * Switches to its model when that model is still in the catalog; the size is kept for fixed-size models.
     */
    fun reuse(p: ImageGenFiles.Params) {
        val target = p.modelId?.let { id -> ImageCatalog.byId(id) }?.takeIf { m -> catalog.any { it.id == m.id } }
        if (target != null && target.id != _ui.value.modelId) selectModel(target)
        _ui.update { u ->
            val m = u.model
            u.copy(
                prompt = p.prompt, negative = p.negative,
                steps = p.steps ?: u.steps, cfg = p.cfg ?: u.cfg,
                seed = p.seed?.toString() ?: u.seed,
                width = m.fixedSize ?: p.width ?: u.width, height = m.fixedSize ?: p.height ?: u.height,
                strength = p.strength ?: u.strength, error = null,
            )
        }
    }

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

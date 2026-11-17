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
    /** 2.5.2: the seed the last image actually used (shown with the result; the field is never overwritten). */
    val lastSeed: Long? = null,
    val running: Boolean = false,
    val step: Int = 0,
    val total: Int = 0,
    /** 2.4.2: load / step / decode phase of the running generation, and when it started. */
    val progress: com.iris.localai.imagegen.SdProgress.State = com.iris.localai.imagegen.SdProgress.State(),
    val startedAt: Long = 0L,
    val error: String? = null,
    val result: String? = null,
    val history: List<String> = emptyList(),
    /** 2.4.3: disk used by [history]. */
    val historyBytes: Long = 0L,
    val report: String? = null,
    /** 2.4.1: sd.cpp — the GPU crashed last time (runs on the CPU until 「重试 GPU」). */
    val sdGpuCrashed: Boolean = false,
    /** 2.4.1: sd.cpp — the next image runs on the CPU (crash or 「改用 CPU」). */
    val sdOnCpu: Boolean = false,
    /** 2.4.3: the app died during an NPU (sdq) run. */
    val sdHtpCrashed: Boolean = false,
    /** 2.4.3: the last error was the memory check (offer 「仍然生成」). */
    val lowMem: Boolean = false,
    /** Model id -> running / failed download (from [ImageGenDownloader.states]). */
    val downloads: Map<String, ImageDl> = emptyMap(),
    /** 2.6.8: 低内存模式 / 提示词扩写（存在智能体设置里，与 generate_image 共用）。 */
    val lowMemMode: Boolean = false,
    val expand: Boolean = true,
    /** 2.6.8: 标签库（内置 + 自定义）与当前分类。 */
    val tags: List<com.iris.localai.imagegen.PromptTag> = com.iris.localai.imagegen.PromptTags.BUILTIN,
    val tagCat: com.iris.localai.imagegen.TagCat = com.iris.localai.imagegen.TagCat.SUBJECT,
    /** 2.6.8: 正在扩写；上一张实际用的（扩写后）提示词。 */
    val expanding: Boolean = false,
    val usedPrompt: String? = null,
    /** 2.6.8: 等待用户确认许可的模型（首次下载前）。 */
    val licenseAsk: String? = null,
    /** 2.6.9: 导入进度（0..1，null = 没在导入）与正在复制的文件名。 */
    val importing: Float? = null,
    val importFile: String = "",
    /** 2.6.9: 模型清单版本（导入 / 改名 / 删除后 +1，让页面重读 catalog）。 */
    val catalogRev: Int = 0,
) {
    val model: ImageModel get() = ImageCatalog.byId(modelId) ?: ImageCatalog.SD15_QNN
    /** Reference image + 重绘强度 work everywhere except the fixed-size QNN context binary (txt2img only). */
    val img2imgSupported: Boolean get() = model.backend != Backend.QNN
    val canGenerate: Boolean get() = !running && prompt.isNotBlank()

    fun request(): ImageRequest = ImageRequest(
        prompt = prompt, negative = negative, width = width, height = height, steps = steps, cfg = cfg,
        // 2.5.2: empty field = a new random seed on every call (one call per generation); a typed seed stays fixed.
        seed = com.iris.localai.imagegen.ImageSeed.pick(seed),
        initImagePath = initImage?.takeIf { img2imgSupported }, strength = strength, lowMem = lowMemMode,
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

    private val agentSettings = container.agent.settings
    private val prefs = app.getSharedPreferences("iris_imagegen", android.content.Context.MODE_PRIVATE)

    init {
        refresh()
        viewModelScope.launch {
            agentSettings.settings.collect { a -> _ui.update { it.copy(lowMemMode = a.imageLowMem, expand = a.imagePromptExpand) } }
        }
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { com.iris.localai.imagegen.TagLibrary.tags(app) }
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
        // 2.6.8: 非商用许可的模型，首次下载前先确认
        if (m.licenseUrl.isNotEmpty() && !prefs.getBoolean("license:" + m.id, false)) { _ui.update { it.copy(licenseAsk = m.id) }; return }
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

    /** 2.6.8: 许可确认（同意后开始下载，之后不再问）。 */
    fun acceptLicense(accept: Boolean) {
        val id = _ui.value.licenseAsk ?: return
        _ui.update { it.copy(licenseAsk = null) }
        if (!accept) return
        prefs.edit().putBoolean("license:$id", true).apply()
        ImageCatalog.byId(id)?.let(::download)
    }

    fun setLowMem(on: Boolean) = agentSettings.update { it.copy(imageLowMem = on) }
    fun setExpand(on: Boolean) = agentSettings.update { it.copy(imagePromptExpand = on) }

    /** 2.6.8 标签库：点标签追加到提示词（负面类追加到负面词）。 */
    fun useTag(t: com.iris.localai.imagegen.PromptTag) = _ui.update {
        val text = t.text(it.model.tagStyle)
        if (t.cat == com.iris.localai.imagegen.TagCat.NEGATIVE) it.copy(negative = com.iris.localai.imagegen.PromptTags.append(it.negative, text))
        else it.copy(prompt = com.iris.localai.imagegen.PromptTags.append(it.prompt, text))
    }

    fun addTag(cat: com.iris.localai.imagegen.TagCat, zh: String, en: String) = changeTags { com.iris.localai.imagegen.TagStore.add(it, cat, zh, en) }
    fun removeTag(t: com.iris.localai.imagegen.PromptTag) = changeTags { com.iris.localai.imagegen.TagStore.remove(it, t) }
    /** 恢复内置标签（清掉隐藏，保留自定义）。 */
    fun resetTags() = changeTags { it.copy(hidden = emptySet()) }

    private fun changeTags(f: (com.iris.localai.imagegen.TagStore.Data) -> com.iris.localai.imagegen.TagStore.Data) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) {
                val d = f(com.iris.localai.imagegen.TagLibrary.load(app))
                com.iris.localai.imagegen.TagLibrary.save(app, d)
                com.iris.localai.imagegen.PromptTags.merged(d.custom, d.hidden)
            }
            _ui.update { it.copy(tags = t) }
        }
    }

    /**
     * 2.6.8 提示词扩写：有已加载的文本模型就让它改写（最多 45 秒、256 token、不思考），失败/超时/没加载用规则模板。
     */
    private suspend fun expandPrompt(prompt: String, model: ImageModel): String {
        val ex = com.iris.localai.imagegen.PromptExpand
        if (!ex.needed(prompt)) return prompt
        val eng = container.engine
        if (eng.readyModel != null && eng.busy.value == null) {
            val out = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    val sb = StringBuilder()
                    val sampling = eng.samplingFor(true).copy(maxNewTokens = 256, contextShift = false)
                    eng.generate(listOf(com.iris.localai.engine.PromptMessage(com.iris.localai.engine.PromptMessage.SYSTEM, ex.system(model.tagStyle)),
                        com.iris.localai.engine.PromptMessage(com.iris.localai.engine.PromptMessage.USER, prompt)), sampling, com.iris.localai.engine.Requester.CHAT).collect { ev ->
                        if (ev is com.iris.localai.engine.GenEvent.Piece) sb.append(ev.text)
                    }
                    sb.toString()
                }
            }.getOrNull()
            out?.let { ex.clean(it, prompt) }?.let { return it }
        }
        return ex.rules(prompt, model.tagStyle)
    }

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
    // ---- 2.6.9: 导入生图模型（用户自备文件，App 不托管） ----

    /** 该架构的文本编码器 / VAE 是否已在写入根目录里（可复用）。 */
    suspend fun reusable(arch: com.iris.localai.imagegen.ImportArch): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val root = repo.modelsLocation().dir
        File(root, arch.llmPath).isFile to File(root, arch.vaePath).isFile
    }

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

    fun importModel(arch: com.iris.localai.imagegen.ImportArch, name: String, main: Uri, llm: Uri?, vae: Uri?, steps: Int, cfg: Float) {
        if (_ui.value.importing != null) return
        val imp = com.iris.localai.imagegen.ImportedImages
        viewModelScope.launch {
            val id = imp.newId()
            val root = withContext(Dispatchers.IO) { repo.modelsLocation().dir }
            val dir = File(root, id)
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val parts = listOfNotNull(main, llm, vae)
                    val total = parts.sumOf { sizeOf(it).coerceAtLeast(0L) }.coerceAtLeast(1L)
                    var done = 0L
                    fun copy(uri: Uri): String {
                        val fn = imp.safeFileName(nameOf(uri))
                        if (!imp.acceptable(fn)) throw IllegalArgumentException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_bad_ext, fn))
                        dir.mkdirs()
                        val out = File(dir, fn).let { if (it.exists()) File(dir, "${parts.indexOf(uri)}-$fn") else it }
                        val part = File(dir, out.name + ".part")
                        _ui.update { it.copy(importFile = fn) }
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                            part.outputStream().use { os ->
                                val buf = ByteArray(1 shl 20)
                                var first = true
                                var last = 0L
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    if (first) {
                                        if (!imp.headerOk(fn, buf.copyOf(n.coerceAtMost(16)))) throw IllegalArgumentException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_bad_file, fn))
                                        first = false
                                    }
                                    os.write(buf, 0, n); done += n
                                    val now = System.currentTimeMillis()
                                    if (now - last >= 200) { last = now; _ui.update { it.copy(importing = (done.toFloat() / total).coerceIn(0f, 1f)) } }
                                }
                            }
                        } ?: throw IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_bad_file, fn))
                        if (!part.renameTo(out)) throw IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_bad_file, fn))
                        return "$id/${out.name}"
                    }
                    _ui.update { it.copy(importing = 0f) }
                    val d = copy(main)
                    val l = llm?.let(::copy) ?: arch.llmPath
                    val v = vae?.let(::copy) ?: arch.vaePath
                    if (!File(root, l).isFile || !File(root, v).isFile) throw IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_need_parts))
                    imp.put(com.iris.localai.imagegen.ImportedImage(id, imp.cleanName(name, imp.nameFromFile(d.substringAfterLast('/'))), arch.name, d, l, v,
                        steps.coerceIn(1, 100), cfg.coerceIn(0f, 30f)))
                    null
                }.exceptionOrNull()
            }
            if (err != null) withContext(Dispatchers.IO) { dir.deleteRecursively() }
            _ui.update { it.copy(importing = null, importFile = "", catalogRev = it.catalogRev + 1, error = err?.let { e -> e.message ?: e.toString() }) }
            refresh()
            if (err == null) imp.get(id)?.toModel()?.let { m ->
                selectModel(m)
                // 引擎库（libiris_sd.so）没下过就补下；权重本身不下载
                if (!withContext(Dispatchers.IO) { com.iris.localai.imagegen.ImageGenFiles.isDownloaded(root, m) }) download(m)
            }
        }
    }

    /** 2.6.12: 导入 Anima NPU 包：多选的文件里只收包内约定的文件名，复制进 `<id>/`。 */
    fun importNpuPack(name: String, uris: List<Uri>, steps: Int) {
        if (_ui.value.importing != null) return
        val imp = com.iris.localai.imagegen.ImportedImages
        viewModelScope.launch {
            val id = imp.newId()
            val root = withContext(Dispatchers.IO) { repo.modelsLocation().dir }
            val dir = File(root, id)
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val picked = uris.map { it to nameOf(it) }.filter { it.second in com.iris.localai.imagegen.AnimaQnnFiles.NAMES }.distinctBy { it.second }
                    val total = picked.sumOf { sizeOf(it.first).coerceAtLeast(0L) }.coerceAtLeast(1L)
                    var done = 0L
                    dir.mkdirs()
                    _ui.update { it.copy(importing = 0f) }
                    for ((uri, fn) in picked) {
                        val part = File(dir, "$fn.part")
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
                        } ?: throw IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_bad_file, fn))
                        if (!part.renameTo(File(dir, fn))) throw IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig269_bad_file, fn))
                    }
                    val miss = com.iris.localai.imagegen.AnimaQnnFiles(dir).missing()
                    if (miss.isNotEmpty()) throw IllegalStateException(com.iris.localai.I18n.s(com.iris.localai.R.string.ig270_npu_missing, miss.joinToString()))
                    val a = com.iris.localai.imagegen.ImportArch.ANIMA_NPU
                    imp.put(com.iris.localai.imagegen.ImportedImage(id, imp.cleanName(name, a.label), a.name, id, id, id, steps.coerceIn(1, 50), 1f))
                    null
                }.exceptionOrNull()
            }
            if (err != null) withContext(Dispatchers.IO) { dir.deleteRecursively() }
            _ui.update { it.copy(importing = null, importFile = "", catalogRev = it.catalogRev + 1, error = err?.let { e -> e.message ?: e.toString() }) }
            refresh()
            if (err == null) imp.get(id)?.toModel()?.let { selectModel(it) }
        }
    }

    fun renameImported(m: ImageModel, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.iris.localai.imagegen.ImportedImages.rename(m.id, name) }
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
            _ui.update { it.copy(initImage = path, error = if (path == null) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_a7f86a) else null) }
        }
    }

    fun generate() {
        val s = _ui.value
        if (!s.canGenerate) return
        if (s.modelId !in s.downloaded) { _ui.update { it.copy(error = ImageGenText.NOT_DOWNLOADED_ERROR) }; return }
        val req0 = s.request()
        val model = s.model
        // 2.5.6: 关了风险提示弹窗 = 跳过内存预检，直接生成
        if (!container.settings.current.warnPopups) engines.sdEngine().forceOnce = true
        _ui.update { it.copy(running = true, step = 0, total = req0.steps, error = null, lowMem = false, lastSeed = null, usedPrompt = null,
            progress = com.iris.localai.imagegen.SdProgress.State(), startedAt = System.currentTimeMillis()) }
        job = viewModelScope.launch {
            // 2.6.8: 扩写在卸载文本模型之前（要用它）。
            val req = if (s.expand && req0.initImagePath == null) {
                _ui.update { it.copy(expanding = true) }
                val p = expandPrompt(req0.prompt, model)
                _ui.update { it.copy(expanding = false, usedPrompt = p.takeIf { x -> x != req0.prompt }) }
                req0.copy(prompt = p, negative = com.iris.localai.imagegen.PromptExpand.negative(req0.negative, model.tagStyle))
            } else req0
            // The NPU pipeline holds ≈1.1 GB: free the chat model first (it reloads on the next chat message).
            if (model.backend == com.iris.localai.imagegen.Backend.QNN || model.backend == com.iris.localai.imagegen.Backend.SDCPP) runCatching { container.engine.unload(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_19a9bc)) }
            val r = withContext(Dispatchers.Default) {
                engine.generate(req, model) { step, total ->
                    _ui.update { it.copy(step = step, total = total, progress = com.iris.localai.imagegen.SdProgress.next(it.progress, step, total, req.steps)) }
                }
            }
            val saved = r.mapCatching { png -> withContext(Dispatchers.IO) { repo.save(png, req, model) } }
            saved.onSuccess { f -> _ui.update { it.copy(running = false, result = f.path, lastSeed = req.seed, history = listOf(f.path) + it.history.filter { p -> p != f.path }, historyBytes = it.historyBytes + f.length()) } }
                .onFailure { e -> _ui.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName, lowMem = e is com.iris.localai.imagegen.SdRules.LowMemory) } }
            if (model.backend == com.iris.localai.imagegen.Backend.SDCPP) refresh()
        }
    }

    /** 2.4.3 「仍然生成」: run once past the memory check. */
    fun generateAnyway() {
        engines.sdEngine().forceOnce = true
        generate()
    }

    fun cancel() {
        engine.cancel()
        job?.cancel()
        job = null
        _ui.update { it.copy(running = false, expanding = false, error = ImageGenText.CANCELLED) }
    }

    /** 2.4.2: recent sd.cpp log lines for a bug report (copied by the page). */
    suspend fun sdLog(): String = withContext(Dispatchers.IO) { com.iris.localai.imagegen.SdLog.recent() }

    /** 2.5.2: a history image shown as the result: its seed is in its own parameters, not [ImageGenUi.lastSeed]. */
    fun showResult(path: String) = _ui.update { it.copy(result = path, lastSeed = if (path == it.result) it.lastSeed else null) }

    /** 2.4.3: parameters embedded in a history image (prompt, seed, size …), null when it has none. */
    suspend fun params(path: String): com.iris.localai.imagegen.ImageGenFiles.Params? = withContext(Dispatchers.IO) { repo.params(path) }

    /** 2.4.3 「删除」 in the history (one image or a multi-select), after the page's confirm dialog. */
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
     * 2.4.3 「用这组参数」: fills prompt / negative / steps / CFG / seed / size from a history image.
     * Switches to its model when that model is still in the catalog; the size is kept for fixed-size models.
     */
    fun reuse(p: com.iris.localai.imagegen.ImageGenFiles.Params) {
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

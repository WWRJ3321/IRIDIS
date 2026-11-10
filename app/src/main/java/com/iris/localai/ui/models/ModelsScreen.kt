package com.iris.localai.ui.models

import kotlinx.coroutines.flow.first
import com.iris.localai.ui.settings.ModelLoadSettings
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.outlined.DragHandle
import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.IrisApp
import com.iris.localai.data.Format
import com.iris.localai.data.LocalModel
import com.iris.localai.engine.EngineState
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.LargeTitle
import com.iris.localai.ui.components.EmptyState
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.TrackTopBarScroll
import com.iris.localai.ui.components.ValueRow
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.border

data class ImportState(val fileName: String = "", val progress: Float = 0f, val error: String? = null, val active: Boolean = false)

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IrisApp).container
    val models = c.models.models
    fun move(from: Int, to: Int) = c.models.move(from, to)
    val engine = c.engine.state
    val busy = c.engine.busy
    /** 2.2.0: "GenieX · NPU" for models GenieX runs (null = llama.cpp / the format's own engine). */
    fun engineTag(m: LocalModel): String? = runCatching { c.engine.geniexTag(m) }.getOrNull()
    val modelConfigs = c.modelConfigs.configs
    val settingsFlow = c.settings.settings
    /** 2.2.16 (merged 模型与加载 page): load telemetry, 资源档位, 改用 CPU — formerly the 档位与加载 sheet. */
    val loadStats = c.engine.loadStats.records
    fun preferredBackendLabel(m: LocalModel): String = runCatching { c.engine.preferredBackend(m).label }.getOrDefault("")
    fun setResourceTier(tier: Int) {
        val t = tier.coerceIn(0, com.iris.localai.engine.ResourceTier.entries.size - 1)
        if (t == c.settings.current.resourceTier) return
        c.settings.update { it.copy(resourceTier = t) }
        c.engine.applyTierLive(com.iris.localai.engine.ResourceTier.of(t))
    }
    /** 1.10.3 「改用 CPU」: the model's own backend becomes CPU; reloads now when it is (being) loaded elsewhere. */
    fun switchModelToCpu(m: LocalModel) {
        c.modelConfigs.update(m.id) { it.copy(backend = com.iris.localai.engine.Backend.CPU.name) }
        viewModelScope.launch {
            val end = c.engine.state.first { it !is EngineState.Loading }
            if (end is EngineState.Ready && end.model.path == m.path && end.details.activeBackend != com.iris.localai.engine.Backend.CPU) c.engine.reload()
            else if (end is EngineState.Failed && end.model?.path == m.path) c.engine.load(m)
        }
    }
    /** Current models folder (2.1.0: Download/IRIDIS/models with 全部文件访问) — re-read on resume. */
    data class Folder(val path: String, val shared: Boolean)
    private val _folder = MutableStateFlow<Folder?>(null)
    val folder = _folder.asStateFlow()

    fun refreshFolder() = viewModelScope.launch {
        _folder.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            c.models.location().let { Folder(it.dir.absolutePath, it.shared) }
        }
    }

    private val _import = MutableStateFlow(ImportState())
    val import = _import.asStateFlow()
    private var importJob: Job? = null

    // 2.3.0: init moved to end of class (crash: refreshImageModels touched _imageDownloaded before it was initialised)

    fun refresh() = viewModelScope.launch { refreshFolder(); c.models.refresh() }
    fun load(m: LocalModel) = viewModelScope.launch {
        // 2.5.0 Beta6: memory short → ask 「仍要加载 / 取消」 first (never blocks).
        val v = c.engine.preflight(m)
        if (v.level == com.iris.localai.engine.MemoryPreflight.Level.SHORT && v.message != null) _memWarn.value = m to v.message
        else c.engine.load(m)
    }
    private val _memWarn = MutableStateFlow<Pair<LocalModel, String>?>(null)
    val memWarn = _memWarn.asStateFlow()
    fun confirmMemLoad() { val p = _memWarn.value ?: return; _memWarn.value = null; viewModelScope.launch { c.engine.load(p.first) } }
    fun dismissMemWarn() { _memWarn.value = null }
    fun cancelLoad() { c.engine.cancelLoad() }
    fun unload() = viewModelScope.launch { c.engine.unload() }

    fun delete(m: LocalModel) = viewModelScope.launch {
        if ((engine.value as? EngineState.Ready)?.model?.path == m.path) c.engine.unload()
        c.models.delete(m)
    }

    fun import(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _import.value = ImportState(fileName = uri.lastPathSegment.orEmpty(), active = true)
            try {
                val m = c.models.import(uri) { p -> _import.value = _import.value.copy(progress = p) }
                _import.value = ImportState()
                // 2.3.5 (#8): a single GGUF without a paired projector → offer 「挂载视觉投影」.
                if (m.format == com.iris.localai.data.ModelFormat.GGUF && c.engine.mmprojFor(m, c.modelConfigs.get(m.id).mmproj) == null) _mmprojOffer.value = m
                if (engine.value !is EngineState.Ready) c.engine.load(m)
            } catch (t: Throwable) {
                _import.value = ImportState(error = if (t is kotlinx.coroutines.CancellationException) null else t.message)
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    // 2.3.5 (#8): 挂载视觉投影 (mmproj) after a single-GGUF import.
    private val _mmprojOffer = MutableStateFlow<LocalModel?>(null)
    val mmprojOffer = _mmprojOffer.asStateFlow()
    fun dismissMmprojOffer() { _mmprojOffer.value = null }
    fun attachMmproj(m: LocalModel, uri: Uri) = viewModelScope.launch {
        _mmprojOffer.value = null
        try {
            val name = c.models.importMmproj(m, uri)
            c.modelConfigs.update(m.id) { it.copy(mmproj = name) }
        } catch (t: Throwable) {
            _import.value = ImportState(error = t.message)
        }
    }

    // ---- MNN folders ----
    val mnnSupported: Boolean = com.iris.localai.data.ModelFormat.MNN in com.iris.localai.engine.EngineFactory.supportedFormats
    val hub = c.mnnHub.state

    fun importFolder(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _import.value = ImportState(fileName = uri.lastPathSegment?.substringAfterLast(':').orEmpty(), active = true)
            try {
                val m = c.models.importFolder(uri, onPair = { id, proj -> c.modelConfigs.update(id) { it.copy(mmproj = proj) } }) { p -> _import.value = _import.value.copy(progress = p) }
                _import.value = ImportState()
                if (engine.value !is EngineState.Ready) c.engine.load(m)
            } catch (t: Throwable) {
                _import.value = ImportState(error = if (t is kotlinx.coroutines.CancellationException) null else t.message)
            }
        }
    }

    fun downloadMnn(repoId: String, file: String? = null) {
        c.mnnHub.start(c.scope, repoId, c.settings.current.downloadSource, file = file) { m ->
            if (c.engine.state.value !is EngineState.Ready) c.engine.load(m)
        }
    }

    /** 2.2.12: a 生图 model from the recommended list → the 生图 downloader (2.2.17 C: progress on its card). */
    fun downloadImageModel(id: String, retry: Boolean = false) {
        val m = com.iris.localai.imagegen.ImageCatalog.byId(id) ?: return
        viewModelScope.launch {
            val root = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.imagegen.ImageGenRepository(getApplication()).downloadRoot(m) }
            val done: () -> Unit = { refreshImageModels() }
            if (retry) c.imageGenDownloader.retryOtherSource(m, root, c.settings.current.downloadSource, done)
            else c.imageGenDownloader.start(m, root, c.settings.current.downloadSource, onDone = done)
        }
    }
    fun cancelImageDownload(id: String) = c.imageGenDownloader.cancel(id)
    val imageStates = c.imageGenDownloader.states
    private val _imageDownloaded = MutableStateFlow<Set<String>>(emptySet())
    val imageDownloaded = _imageDownloaded.asStateFlow()
    fun refreshImageModels() = viewModelScope.launch {
        _imageDownloaded.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.iris.localai.imagegen.ImageGenRepository(getApplication()).downloaded() }.getOrDefault(emptySet())
        }
    }

    // ---- 2.3.0 ③: offline translation packs from the 推荐 › 翻译 section ----
    val translateStates = c.translator.packs.states
    private val translateJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    fun downloadTranslate(id: String) {
        val p = com.iris.localai.translate.TranslatePack.entries.firstOrNull { it.id == id } ?: return
        if (translateJobs[id]?.isActive == true) return
        translateJobs[id] = c.scope.launch { runCatching { c.translator.packs.download(p, c.settings.current.downloadSource) } }
    }
    fun cancelTranslate(id: String) { translateJobs.remove(id)?.cancel() }

    // ---- 2.2.17 C: voice packs from the 推荐 › 语音 section (same store as the 语音 page) ----
    val voiceStates = c.voiceStore.states
    private val voiceJobs = java.util.concurrent.ConcurrentHashMap<com.iris.localai.voice.VoicePack, Job>()
    fun voiceBytes(p: com.iris.localai.voice.VoicePack): Long = runCatching { c.voiceStore.totalBytes(p) }.getOrDefault(0L)
    fun downloadVoice(p: com.iris.localai.voice.VoicePack) {
        if (voiceJobs[p]?.isActive == true) return
        voiceJobs[p] = c.scope.launch { runCatching { c.voiceStore.download(p, c.settings.current.downloadSource) } }
    }
    fun cancelVoice(p: com.iris.localai.voice.VoicePack) { voiceJobs.remove(p)?.cancel() }
    fun useVoice(p: com.iris.localai.voice.VoicePack) = c.settings.update {
        if (p.kind == com.iris.localai.voice.VoiceKind.ASR) it.copy(asrPack = p.id) else it.copy(ttsPack = p.id)
    }
    fun voiceInUse(p: com.iris.localai.voice.VoicePack): Boolean = p.kind != com.iris.localai.voice.VoiceKind.CLONE &&
        c.voiceStore.active(p.kind, if (p.kind == com.iris.localai.voice.VoiceKind.ASR) c.settings.current.asrPack else c.settings.current.ttsPack) == p

    // ---- 2.2.17 C: recommended chat / 特色 models ----
    /** The local model this entry downloaded (null = not on disk). */
    fun localFor(item: com.iris.localai.data.RecommendedCatalog.Item, list: List<LocalModel>): LocalModel? {
        val repo = item.repoId ?: return null
        val k = item.kind
        if (k is com.iris.localai.data.RecommendedCatalog.Kind.Gguf) return list.firstOrNull { it.fileName == com.iris.localai.data.ModelFiles.sanitizeFileName(k.localFile) }
        // 2.4.4: a named file (the Dimensity NPU build) is installed only when that very file is on disk.
        if (k is com.iris.localai.data.RecommendedCatalog.Kind.Hub && k.file != null) return list.firstOrNull { it.fileName == com.iris.localai.data.ModelFiles.sanitizeFileName(k.file) }
        return list.firstOrNull { com.iris.localai.data.RecommendedCatalog.matchesLocal(repo, it.fileName) }
    }

    fun downloadCatalog(item: com.iris.localai.data.RecommendedCatalog.Item, withVision: Boolean = false) {
        when (val k = item.kind) {
            is com.iris.localai.data.RecommendedCatalog.Kind.Hub -> downloadMnn(k.repoId, k.file)
            is com.iris.localai.data.RecommendedCatalog.Kind.Gguf -> {
                val hit = com.iris.localai.data.GgufSearch.RepoHit(k.repoId, com.iris.localai.data.GgufSearch.Site.HF, license = item.licence)
                // 2.4.3: saved under k.localFile (a renamed file keeps the repo path for the URL)
                val mainLocal = com.iris.localai.data.ModelFiles.sanitizeFileName(k.localFile)
                val files = listOf(Triple(k.file, k.size, mainLocal)) + listOfNotNull(k.mmproj?.takeIf { withVision }?.let {
                    Triple(it, k.mmprojSize, com.iris.localai.data.GgufSearch.projectorLocalName(mainLocal, it))
                })
                c.mnnHub.startGguf(c.scope, hit, files, c.settings.current.downloadSource) { m ->
                    if (c.engine.state.value !is EngineState.Ready) c.engine.load(m)
                }
            }
            is com.iris.localai.data.RecommendedCatalog.Kind.Image -> downloadImageModel(k.modelId)
            is com.iris.localai.data.RecommendedCatalog.Kind.Voice -> com.iris.localai.voice.VoicePack.byId(k.packId)?.let(::downloadVoice)
            is com.iris.localai.data.RecommendedCatalog.Kind.Translate -> downloadTranslate(k.packId)
        }
    }

    fun pauseDownload() = c.mnnHub.pause()
    fun resumeDownload() = c.mnnHub.resume()

    /** 2.2.16 「换源重试」 for the failed model download. */
    fun retryOtherSource() = c.mnnHub.retryOtherSource(c.scope) { m ->
        if (c.engine.state.value !is EngineState.Ready) c.engine.load(m)
    }
    fun setDownloadSource(s: com.iris.localai.data.DownloadSource) = c.settings.update { it.copy(downloadSource = s) }

    // ---- 2.2.16 「在线搜索模型」 (GGUF on HF / 魔搭) ----
    data class SearchUi(
        val mode: com.iris.localai.data.GgufSearch.Browse = com.iris.localai.data.GgufSearch.Browse.HOT,
        val query: String = "",
        val loading: Boolean = false,
        val hits: List<com.iris.localai.data.GgufSearch.RepoHit> = emptyList(),
        val errors: List<String> = emptyList(),
        val searched: Boolean = false,
        /** Paging: HF cursor ("" first page, null no more), 魔搭 page (null no more). */
        val hfNext: String? = "",
        val msNext: Int? = 1,
        val repo: com.iris.localai.data.GgufSearch.RepoHit? = null,
        val files: com.iris.localai.data.GgufSearch.Files? = null,
        val filesLoading: Boolean = false,
        val filesError: String? = null,
    ) {
        val hasMore: Boolean get() = hfNext != null || msNext != null
    }
    private val _search = MutableStateFlow(SearchUi())
    val search = _search.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null



    /** First page of [mode] (keyword [q] optional). */
    fun browse(mode: com.iris.localai.data.GgufSearch.Browse, q: String = _search.value.query) {
        searchJob?.cancel()
        _search.value = SearchUi(mode = mode, query = q, loading = true)
        searchJob = viewModelScope.launch { fetchMore() }
    }

    /** Infinite scroll: the next page (适合本机 fetches up to 3 pages until a few fit). */
    fun loadMore() {
        val st = _search.value
        if (st.loading || !st.hasMore || st.repo != null) return
        _search.value = st.copy(loading = true)
        searchJob = viewModelScope.launch { fetchMore() }
    }

    private suspend fun fetchMore() {
        var st = _search.value
        val ram = totalRam()
        var added = 0
        var rounds = 0
        do {
            val p = com.iris.localai.data.GgufSearch.browse(st.mode, st.query, c.settings.current.downloadSource, st.hfNext, st.msNext, ram)
            val seen = st.hits.map { it.site to it.id }.toSet()
            val new = p.hits.filter { (it.site to it.id) !in seen }
            added += new.size
            st = st.copy(hits = st.hits + new, errors = p.errors, hfNext = p.hfNext, msNext = p.msNext)
            rounds++
        } while (st.mode == com.iris.localai.data.GgufSearch.Browse.FIT && added < 8 && st.hasMore && rounds < 3 && p.errors.isEmpty())
        _search.value = st.copy(loading = false, searched = true)
    }

    fun totalRam(): Long = runCatching {
        val am = getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    fun openRepo(hit: com.iris.localai.data.GgufSearch.RepoHit) {
        searchJob?.cancel()
        _search.value = _search.value.copy(repo = hit, files = null, filesLoading = true, filesError = null)
        searchJob = viewModelScope.launch {
            val r = runCatching { com.iris.localai.data.GgufSearch.files(hit, c.settings.current.downloadSource) }
            _search.value = _search.value.copy(
                filesLoading = false, files = r.getOrNull(),
                filesError = r.exceptionOrNull()?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.models_58665d) + com.iris.localai.data.DownloadSources.describe(it) },
            )
        }
    }

    fun closeRepo() { searchJob?.cancel(); _search.value = _search.value.copy(repo = null, files = null, filesLoading = false, filesError = null) }

    /** Free RAM now (for the 内存够用 / 偏紧 hint, same rule as the load preflight). */
    fun availRam(): Long = runCatching {
        val am = getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }.getOrDefault(0L)

    fun downloadGguf(choice: com.iris.localai.data.GgufSearch.Choice, projector: com.iris.localai.data.GgufSearch.GgufFile?) {
        startGguf(_search.value.repo ?: return, choice, projector)
    }

    private fun startGguf(hit: com.iris.localai.data.GgufSearch.RepoHit, choice: com.iris.localai.data.GgufSearch.Choice, projector: com.iris.localai.data.GgufSearch.GgufFile?) {
        val mainLocal = com.iris.localai.data.ModelFiles.sanitizeFileName(choice.main.name)
        val files = choice.parts.map { Triple(it.path, it.size, com.iris.localai.data.ModelFiles.sanitizeFileName(it.name)) } +
            listOfNotNull(projector?.let { Triple(it.path, it.size, com.iris.localai.data.GgufSearch.projectorLocalName(mainLocal, it.name)) })
        c.mnnHub.startGguf(c.scope, hit, files, c.settings.current.downloadSource) { m ->
            if (c.engine.state.value !is EngineState.Ready) c.engine.load(m)
        }
    }

    fun cancelDownload() = c.mnnHub.cancel()
    init { refresh(); refreshImageModels() }
}

@Composable
fun ModelsScreen(onBack: () -> Unit, settingsVm: com.iris.localai.ui.settings.SettingsViewModel? = null, onOpenDownload: () -> Unit = {}, vm: ModelsViewModel = viewModel()) {
    val c = Iris.colors
    val models by vm.models.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val modelConfigs by vm.modelConfigs.collectAsStateWithLifecycle()
    val appSettings by vm.settingsFlow.collectAsStateWithLifecycle()
    val import by vm.import.collectAsStateWithLifecycle()
    val loadRecords by vm.loadStats.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
    var configure by remember { mutableStateOf<LocalModel?>(null) }
    configure?.let { m -> ModelConfigSheet(m, onDismiss = { configure = null }) }
    val reduce = LocalReduceMotion.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importFolder) }
    val mmprojOffer by vm.mmprojOffer.collectAsStateWithLifecycle()
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> val m = mmprojOffer; if (uri != null && m != null) vm.attachMmproj(m, uri) else vm.dismissMmprojOffer() }
    mmprojOffer?.let { m ->
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { vm.dismissMmprojOffer() }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_attach),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_pick), onConfirm = { mmprojPicker.launch(arrayOf("*/*")) },
            dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_later), onDismissClick = { vm.dismissMmprojOffer() },
        ) { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_offer, m.displayName), style = IrisType.body, color = Iris.colors.textSecondary) }
    }
    val hub by vm.hub.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        // Back from the 全部文件访问 screen: the folder may have switched (and the shared models appear).
        var first = true
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (first) first = false else vm.refresh() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    SubpageScaffold(
        title = ModelsText.TITLE,
        onBack = onBack,
        actions = { PressableIcon(Icons.Outlined.Refresh, com.iris.localai.I18n.s(com.iris.localai.R.string.models_694fc5)) { vm.refresh() } },
    ) {
        val listState = rememberLazyListState()
        TrackTopBarScroll(listState)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Draw edge-to-edge behind the navigation bar; only the last item is inset.
            contentPadding = PaddingValues(bottom = IrisDimens.space32 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item(key = "large-title", contentType = "largeTitle") { LargeTitle() }
            // 2.3.5: 资源档位 removed from this page; the composer's speed chip sets the same value.
            item(key = "import", contentType = "import") {
                Column(Modifier.padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space8)) {
                    // 2.4.4: 下载模型 is the main way in (big button); importing a file / folder sits below.
                    if (vm.mnnSupported) {
                        IrisButton(
                            DownloadText.TITLE, onClick = onOpenDownload,
                            icon = Icons.Outlined.CloudDownload, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.padding(top = IrisDimens.space8), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                            IrisButton(
                                com.iris.localai.I18n.s(com.iris.localai.R.string.models_c60fdb), onClick = { picker.launch(arrayOf("*/*")) },
                                enabled = !import.active, style = IrisButtonStyle.Tonal, icon = Icons.Outlined.Add,
                                modifier = Modifier.weight(1f),
                            )
                            IrisButton(
                                com.iris.localai.I18n.s(com.iris.localai.R.string.models_238420), onClick = { folderPicker.launch(null) },
                                enabled = !import.active, style = IrisButtonStyle.Tonal, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        IrisButton(
                            com.iris.localai.I18n.s(com.iris.localai.R.string.models_c60fdb), onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !import.active, icon = Icons.Outlined.Add,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (vm.mnnSupported) {
                        // 2.2.17 C: one status line; progress / pause / 换源重试 live on the card in 下载模型
                        DownloadText.statusLine(hub)?.let { line ->
                            Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space8), verticalAlignment = Alignment.CenterVertically) {
                                Text(line, style = IrisType.caption, color = if (hub.error != null) c.danger else c.textSecondary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = IrisDimens.space4))
                                IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.models_607e7a), onOpenDownload, style = IrisButtonStyle.Text, compact = true)
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = import.active,
                        enter = fadeIn(IrisMotion.enter()) + expandVertically(IrisMotion.enter()),
                        exit = fadeOut(IrisMotion.standard()) + shrinkVertically(IrisMotion.standard()),
                    ) {
                        Column(Modifier.padding(top = IrisDimens.space16)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_99b61e, import.fileName.substringAfterLast('/').ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8000f1) }),
                                    style = IrisType.labelSmall, color = c.textSecondary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text("${(import.progress * 100).toInt()}%", style = IrisType.labelSmall, color = c.textSecondary)
                            }
                            Spacer(Modifier.height(IrisDimens.space8))
                            IrisProgressBar(progress = import.progress)
                            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2), vm::cancelImport, style = IrisButtonStyle.Text, compact = true, modifier = Modifier.padding(top = IrisDimens.space4))
                        }
                    }
                    import.error?.let { Text(it, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(top = IrisDimens.space8)) }
                }
            }

            (engine as? EngineState.Ready)?.let { ready ->
                item(key = "loaded-title", contentType = "title") { SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_486516)) }
                item(key = "loaded", contentType = "loaded") {
                    SettingsGroup {
                        val d = ready.details
                        SettingsRow(ready.model.displayName, subtitle = d.description.ifBlank { null })
                        Divider()
                        ValueRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_df1c1e), d.backend)
                        if (d.parameterCount > 0) {
                            Divider()
                            ValueRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_832236), Format.params(d.parameterCount))
                        }
                        Divider()
                        ValueRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b5663), if (d.trainContextLength > 0) "${d.contextLength} / ${d.trainContextLength}" else "${d.contextLength}")
                        Divider()
                        ValueRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_d86ff2), if (d.hasChatTemplate) com.iris.localai.I18n.s(com.iris.localai.R.string.models_807c9f) else com.iris.localai.I18n.s(com.iris.localai.R.string.models_ec88c5))
                        if (d.supportsThinking) {
                            Divider()
                            ValueRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_1d878d), com.iris.localai.I18n.s(com.iris.localai.R.string.models_262b0b))
                        }
                        // 2.2.17 D: the GPU→CPU / mmproj note stays readable on the loaded model.
                        d.fallbackNote?.let { note ->
                            Divider()
                            SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_0021bd), subtitle = note, icon = com.iris.localai.ui.icons.IrisIcons.Info)
                        }
                        Divider()
                        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_a1c951), titleColor = c.danger, enabled = busy == null, onClick = { vm.unload() })
                    }
                }
            }
            (engine as? EngineState.Failed)?.let { f ->
                item(key = "failed", contentType = "failed") {
                    Text(f.message, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space12))
                }
            }

            // 2.3.5: 「最近提示」 entry removed from this page (user request).
            item(key = "local-title", contentType = "title") { SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_c28cd6, models.size)) }
            if (models.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(
                        title = com.iris.localai.I18n.s(com.iris.localai.R.string.models_d38003),
                        message = if (vm.mnnSupported) com.iris.localai.I18n.s(com.iris.localai.R.string.models_22adbb)
                        else com.iris.localai.I18n.s(com.iris.localai.R.string.models_adf29d),
                        icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                    )
                }
            } else {
                // One row per model (keyed), each drawn as part of a continuous rounded group.
                itemsIndexed(models, key = { _, m -> m.path }, contentType = { _, _ -> "model" }) { i, m ->
                    val first = i == 0
                    val last = i == models.lastIndex
                    val r = IrisRadius.large
                    val shape = com.iris.localai.ui.theme.SmoothRoundedShape(
                        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
                        bottomEnd = if (last) r else 0.dp, bottomStart = if (last) r else 0.dp,
                    )
                    // 2.2.2: drag the ≡ handle to reorder; the home model menu follows this order.
                    val index by androidx.compose.runtime.rememberUpdatedState(i)
                    var dragDy by remember(m.path) { androidx.compose.runtime.mutableFloatStateOf(0f) }
                    var dragging by remember(m.path) { mutableStateOf(false) }
                    var rowH by remember(m.path) { androidx.compose.runtime.mutableIntStateOf(1) }
                    val handle = Modifier.pointerInput(m.path) {
                        detectDragGestures(
                            onDragStart = { dragging = true; dragDy = 0f },
                            onDragEnd = { dragging = false; dragDy = 0f },
                            onDragCancel = { dragging = false; dragDy = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragDy += amount.y
                            val h = rowH.toFloat().coerceAtLeast(1f)
                            if (dragDy > h / 2 && index < models.lastIndex) { vm.move(index, index + 1); dragDy -= h }
                            else if (dragDy < -h / 2 && index > 0) { vm.move(index, index - 1); dragDy += h }
                        }
                    }
                    Column(
                        (if (reduce || dragging) Modifier else Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard()))
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = dragDy; if (dragging) { scaleX = 1.02f; scaleY = 1.02f } }
                            .onSizeChanged { rowH = it.height }
                            .padding(horizontal = IrisDimens.groupGutter)
                            .fillMaxWidth()
                            .clip(shape)
                            .background(c.surfaceElevated),
                    ) {
                        if (!first) Divider()
                        // 2.5.0-beta9: 「MTP」 only when the GGUF header has MTP layers (read on IO).
                        val mtpTag by androidx.compose.runtime.produceState(false, m.path) {
                            value = m.format == com.iris.localai.data.ModelFormat.GGUF && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.iris.localai.engine.GgufQuant.of(java.io.File(m.path))?.mtp == true
                            }
                        }
                        val engineTag = remember(m.id, modelConfigs, appSettings.ggufEngine, appSettings.defaultBackends) { vm.engineTag(m) }
                        ModelRow(
                            m,
                            tag = com.iris.localai.engine.MtpTag.join(engineTag, m.format.label, mtpTag),
                            state = engine,
                            busy = busy != null,
                            onLoad = { vm.load(m) },
                            onCancelLoad = { vm.cancelLoad() },
                            onDelete = { confirmDelete = m },
                            onSettings = { configure = m },
                            dragHandle = handle,
                        )
                        // 1.10.2 / 1.10.3 (from the old 档位与加载 sheet): last load time + 「改用 CPU」 hint
                        com.iris.localai.engine.LoadTelemetry.latest(loadRecords.values, m.id)?.let(com.iris.localai.engine.LoadTelemetry::summary)?.let { t ->
                            Text(t, style = IrisType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 48.dp, end = IrisDimens.space16, bottom = IrisDimens.space6))
                        }
                        if (com.iris.localai.engine.CpuFasterHint.shouldShow(loadRecords.values, m.id, vm.preferredBackendLabel(m))) {
                            Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = IrisDimens.space8), verticalAlignment = Alignment.CenterVertically) {
                                Text(com.iris.localai.engine.CpuFasterHint.TEXT, style = IrisType.caption, color = c.accent, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IrisButton(com.iris.localai.engine.CpuFasterHint.ACTION, { vm.switchModelToCpu(m) }, style = IrisButtonStyle.Text, compact = true, enabled = busy == null)
                            }
                        }
                    }
                }
            }
            item(key = "footer", contentType = "footer") {
                folder?.let { f ->
                    GroupFootnote(f.path, monospace = true, modifier = Modifier.padding(top = IrisDimens.space4))
                    if (!f.shared) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = IrisDimens.groupGutter, end = IrisDimens.groupGutter, top = IrisDimens.space4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                com.iris.localai.I18n.s(com.iris.localai.R.string.models_5f4be2), style = IrisType.secondary, color = c.textTertiary,
                                modifier = Modifier.weight(1f).padding(start = IrisDimens.space16),
                            )
                            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.models_0b5542), { openAllFilesAccess(context) }, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                }
                GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.models_85c491))
            }
            // 2.2.16: the former 设置 › 模型与加载 (加载、下载源、默认后端、NPU、加载选项) on the same page
            if (settingsVm != null) item(key = "load-settings", contentType = "loadSettings") {
                Column { ModelLoadSettings(settingsVm) }
            }
        }
    }

    confirmDelete?.let { m ->
        ModelDeleteDialog(m, onConfirm = { vm.delete(m) }, onDismiss = { confirmDelete = null })
    }
    MemoryWarnHost(vm)
}

/** 2.5.0 Beta6: shows the 「内存可能不足」 question [ModelsViewModel.load] raised. */
@Composable
internal fun MemoryWarnHost(vm: ModelsViewModel) {
    val w by vm.memWarn.collectAsStateWithLifecycle()
    w?.let { (m, msg) -> MemoryLoadDialog(m, msg, onConfirm = vm::confirmMemLoad, onDismiss = vm::dismissMemWarn) }
}

/** 2.5.0 Beta6: 加载前内存预检 — free RAM looks short; the user may load anyway or cancel (never a hard block). */
@Composable
internal fun MemoryLoadDialog(m: LocalModel, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.v250_mem_title),
        message = m.displayName + "\n\n" + message,
        confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.v250_mem_load),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = false,
    )
}

/** 1.14.3: the one 「删除模型？」 confirmation (模型管理 and 设置 › 存储空间). */
@Composable
internal fun ModelDeleteDialog(m: LocalModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.models_ce618f),
        message = com.iris.localai.I18n.s(com.iris.localai.R.string.models_704d32, m.fileName, Format.bytes(m.sizeBytes)),
        confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}


/** "GGUF · Q4_K_M · 2.3 GB"; 2.2.0: 「GenieX · NPU · Q4_0 · 2.3 GB」 when GenieX runs it ([engineTag]). */
internal fun LocalModel.metaLine(engineTag: String? = null): String = listOfNotNull(engineTag ?: format.label, quantization, Format.bytes(sizeBytes)).joinToString(" · ")

@Composable
private fun ModelRow(m: LocalModel, tag: String? = null, state: EngineState, busy: Boolean, onLoad: () -> Unit, onDelete: () -> Unit, onSettings: () -> Unit, onCancelLoad: () -> Unit = {}, dragHandle: Modifier = Modifier) {
    val c = Iris.colors
    val loaded = (state as? EngineState.Ready)?.model?.path == m.path
    val loading = (state as? EngineState.Loading)?.model?.path == m.path
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = IrisDimens.space4, end = IrisDimens.space4, top = IrisDimens.space8, bottom = IrisDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            Icons.Outlined.DragHandle, com.iris.localai.I18n.s(com.iris.localai.R.string.models_138a26), tint = c.textTertiary,
            modifier = dragHandle.size(40.dp).padding(IrisDimens.space8),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrisDimens.space2)) {
            Text(m.displayName, style = IrisType.rowStrong, color = c.text)
            Row(verticalAlignment = Alignment.Top) {
                run {
                    // 2.3.5: 视觉 badge for every format, using the same test its engine uses when loading:
                    // GGUF = a linked/sibling mmproj; LiteRT-LM = multimodal file (Gemma 3n/4, VL…);
                    // MNN = visual.mnn in the folder; QAIRT = vision_encoder.bin in the bundle.
                    val ctr = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.iris.localai.IrisApp).container
                    val cfgs by ctr.modelConfigs.configs.collectAsStateWithLifecycle()
                    val pick = cfgs[m.id]?.mmproj
                    val vision = remember(m.path, m.format, pick) {
                        runCatching {
                            when (m.format) {
                                com.iris.localai.data.ModelFormat.GGUF -> ctr.engine.mmprojFor(m, pick) != null
                                com.iris.localai.data.ModelFormat.LITERT -> com.iris.localai.util.VisionModels.litertMayHaveVision(m.fileName)
                                com.iris.localai.data.ModelFormat.MNN -> java.io.File(m.path, "visual.mnn").isFile
                                com.iris.localai.data.ModelFormat.QAIRT -> com.iris.localai.engine.geniex.QairtBundle.isVision(java.io.File(m.path).list().orEmpty().toSet())
                            }
                        }.getOrDefault(false)
                    }
                    if (vision) Text(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_badge), style = IrisType.caption, color = c.accent, maxLines = 1,
                        modifier = Modifier.padding(end = IrisDimens.space4).border(IrisDimens.hairline, c.accent.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = com.iris.localai.ui.theme.IrisSpace.XS),
                    )
                }
                Text(m.metaLine(tag), style = IrisType.caption, color = c.textTertiary) // 2.4.4: never cut (size was hidden behind …)
            }
        }
        Spacer(Modifier.width(IrisDimens.space8))
        when {
            loaded -> Text(com.iris.localai.I18n.s(com.iris.localai.R.string.models_bec33d), style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.padding(end = IrisDimens.space8))
            loading && (state as? EngineState.Loading)?.applyingTier == null -> IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6e60d2), onCancelLoad, style = IrisButtonStyle.Outline, compact = true)
            loading -> Text(com.iris.localai.I18n.s(com.iris.localai.R.string.models_fb4ca1), style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.padding(end = IrisDimens.space8).pulse())
            else -> IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.models_4793ea), onLoad, style = IrisButtonStyle.Outline, compact = true, enabled = !busy && state !is EngineState.Loading)
        }
        PressableIcon(Icons.Outlined.Tune, com.iris.localai.I18n.s(com.iris.localai.R.string.models_fa1b10), iconSize = IrisDimens.iconMedium, tint = c.textSecondary, onClick = onSettings)
        PressableIcon(Icons.Outlined.DeleteOutline, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), iconSize = IrisDimens.iconMedium, tint = c.textSecondary, enabled = !loading, onClick = onDelete)
    }
}


/** System 全部文件访问 screen for this app (same flow as 智能体 › 全部文件访问). */
fun openAllFilesAccess(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName),
            ),
        )
    }.onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
}

/** 2.2.16 下载源 texts (unit-tested). */
object SourceText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_d98cb5)
    val RETRY_OTHER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b7da7)
    val IMPORT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_d9a670)
    val IMPORT_HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_aa27cc)
    val AUTO_SWITCH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_389994)
    val FOOTNOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_c0f2d7)
    fun hint(s: com.iris.localai.data.DownloadSource): String = when (s) {
        com.iris.localai.data.DownloadSource.AUTO -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_6acd72)
        com.iris.localai.data.DownloadSource.MODELSCOPE -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_35fcd5)
        com.iris.localai.data.DownloadSource.HF_MIRROR -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_5775bd)
        com.iris.localai.data.DownloadSource.HUGGING_FACE -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_4c60f8)
    }
}

/** 2.2.16 merged page texts (unit-tested). */
object ModelsText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_035516)
}

package com.iris.localai.ui.models
import androidx.compose.foundation.text.selection.SelectionContainer

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.DeviceRecommend
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelSort
import com.iris.localai.engine.GgufQuant
import com.iris.localai.engine.MtpTag
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.knowledge.KbEmbedModels
import com.iris.localai.translate.TranslatePack
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.LocalReduceMotion
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.data.DownloadSource
import com.iris.localai.engine.EngineState
import com.iris.localai.data.Format
import com.iris.localai.data.GgufSearch
import com.iris.localai.data.LocalModel
import com.iris.localai.data.MnnHub
import com.iris.localai.data.RecStatus
import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.data.RecommendedCatalog.Section
import com.iris.localai.imagegen.ImageDl
import com.iris.localai.ui.components.CheckRow
import com.iris.localai.ui.components.GlassTokens
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisCard
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.LargeTitle
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedControl
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.TrackTopBarScroll
import com.iris.localai.ui.components.ValueRow
import com.iris.localai.ui.components.pressScale
import com.iris.localai.ui.components.pressTint
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.voice.PackState
import com.iris.localai.voice.VoicePack

/** C 下载模型 page texts and small pure helpers (unit-tested). */
object DownloadText {
    val TITLE: String get() = I18n.s(R.string.models_329255)
    val TABS get() = listOf(I18n.s(R.string.models_3f9810), I18n.s(R.string.models_2b62ab), I18n.s(R.string.models_8818d4), I18n.s(R.string.models_726731))
    val ONE_AT_A_TIME: String get() = I18n.s(R.string.models_d7c432)
    val PAUSE: String get() = I18n.s(R.string.models_8d63ef)
    val CANCEL: String get() = I18n.s(R.string.chat_625fb2)
    /** paused → delete the downloaded part. */
    val DISCARD: String get() = I18n.s(R.string.dl263_discard)
    val VISION_TITLE: String get() = I18n.s(R.string.models_25cef9)
    val TRY: String get() = I18n.s(R.string.models_8c0dd3)
    val TRY_HINT: String get() = I18n.s(R.string.models_6ebc23)

    /** Tab index → online list (null = 推荐). */
    fun modeOf(tab: Int): GgufSearch.Browse? = when (tab) { 1 -> GgufSearch.Browse.HOT; 2 -> GgufSearch.Browse.NEW; 3 -> GgufSearch.Browse.FIT; else -> null }
    fun tabOf(mode: GgufSearch.Browse): Int = when (mode) { GgufSearch.Browse.HOT -> 1; GgufSearch.Browse.NEW -> 2; GgufSearch.Browse.FIT -> 3 }

    /** A search from any tab: 最新 / 推荐 search in 热门 order, 热门 / 适合本机 keep theirs. */
    fun searchMode(tab: Int): GgufSearch.Browse = modeOf(tab)?.takeIf { it != GgufSearch.Browse.NEW } ?: GgufSearch.Browse.HOT

    fun displayName(repoId: String): String = RecommendedCatalog.byRepo(repoId)?.name ?: repoId.substringAfter('/')

    /** One line for the 模型与加载 page (null = nothing downloading / paused / failed). */
    fun statusLine(hub: MnnHub.State): String? = when {
        hub.active -> I18n.s(R.string.models_51b52e, displayName(hub.repoId)) + (if (hub.totalBytes > 0) " · ${(hub.progress * 100).toInt()}%" else "")
        hub.paused -> I18n.s(R.string.models_87c0cf, displayName(hub.repoId))
        hub.error != null -> I18n.s(R.string.models_d2cb87) + RecommendedCatalog.shortReason(hub.error)
        else -> null
    }

    /** "1.2 GB / 2.5 GB · 魔搭 ModelScope". */
    fun progressLine(done: Long, total: Long, source: String = ""): String =
        listOfNotNull(if (total > 0) "${Format.bytes(done)} / ${Format.bytes(total)}" else I18n.s(R.string.models_dcfb68), source.ifBlank { null }).joinToString(" · ")

    /** What the detail sheet says the download fetches. */
    fun filesLine(item: RecommendedCatalog.Item): String = when (val k = item.kind) {
        is RecommendedCatalog.Kind.Gguf -> k.file
        is RecommendedCatalog.Kind.Hub -> when (item.engine) {
            RecommendedCatalog.Engine.MNN -> I18n.s(R.string.models_ddd4f5)
            RecommendedCatalog.Engine.LITERT -> I18n.s(R.string.models_b351da)
            RecommendedCatalog.Engine.EDGE0 -> I18n.s(R.string.e265_files)
            else -> I18n.s(R.string.models_89d8e3)
        }
        is RecommendedCatalog.Kind.Image -> I18n.s(R.string.models_aa40ac)
        is RecommendedCatalog.Kind.Voice -> I18n.s(R.string.models_73e385)
        is RecommendedCatalog.Kind.Translate -> I18n.s(R.string.models_947d0a)
    }
}

@Composable
fun DownloadModelsScreen(onBack: () -> Unit, onTryPrompt: (String) -> Unit, vm: ModelsViewModel = viewModel()) {
    val c = Iris.colors
    val hub by vm.hub.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val imageStates by vm.imageStates.collectAsStateWithLifecycle()
    val imageDone by vm.imageDownloaded.collectAsStateWithLifecycle()
    val voiceStates by vm.voiceStates.collectAsStateWithLifecycle()
    val translateStates by vm.translateStates.collectAsStateWithLifecycle()
    val ram = remember { vm.availRam() }
    // 按机型推荐 — total RAM + chip class → one chat model, shown first on 推荐.
    val total = remember { vm.totalRam() }
    val socMan = remember { if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { android.os.Build.SOC_MANUFACTURER }.getOrNull() else null }
    val socModel = remember { if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { android.os.Build.SOC_MODEL }.getOrNull() else android.os.Build.HARDWARE }
    val chip = remember { DeviceRecommend.chip(socMan, socModel) }
    val forDevice = remember { DeviceRecommend.pick(total, chip) }
    // 额外推荐（12 GB / 16 GB 机的 Gemma 4 19B REAP），排在原推荐之后
    val forDeviceExtra = remember { DeviceRecommend.extras(total, chip) }
    // downloads show their progress in the notification shade; ask once (Android 13+).
    run {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val prefs = ctx.getSharedPreferences("iris_dl244", android.content.Context.MODE_PRIVATE)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked", false) &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("asked", true).apply()
                runCatching { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(search.query) }
    var expanded by remember { mutableStateOf(emptySet<Section>()) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    // opened from elsewhere for one catalog card (知识库 › 嵌入模型 not downloaded).
    androidx.compose.runtime.LaunchedEffect(Unit) { DownloadNav.take()?.let { detailId = it } }

    // models from elsewhere: the existing import, its progress shows on 模型与加载
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it); onBack() } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.importFolder(it); onBack() } }
    val importFile = { picker.launch(arrayOf("*/*")) }

    LaunchedEffect(tab) {
        val mode = DownloadText.modeOf(tab) ?: return@LaunchedEffect
        if (search.mode != mode || (search.hits.isEmpty() && !search.loading && !search.searched)) vm.browse(mode, query.trim())
    }
    LaunchedEffect(imageStates.keys) { vm.refreshImageModels() }

    val loadedPath = (engine as? EngineState.Ready)?.model?.path
    // 下载卡顿——这些要读磁盘，之前每次进度刷新在主线程逐卡重算；改为 IO 线程、只在列表/下载状态变化时算。
    val embedDone by androidx.compose.runtime.produceState(emptySet<String>(), models, hub.active, hub.repoId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { KbEmbedModels.installed().map { it.repo }.toSet() }.getOrDefault(emptySet())
        }
    }
    val voiceSizes by androidx.compose.runtime.produceState(emptyMap<VoicePack, Long>(), voiceStates) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VoicePack.entries.associateWith(vm::voiceBytes) }
    }
    fun local(item: RecommendedCatalog.Item): LocalModel? = vm.localFor(item, models)
    /** the installed GGUF behind a card (its header decides the 「MTP」 badge). */
    fun ggufPath(item: RecommendedCatalog.Item): String? =
        if (item.kind is RecommendedCatalog.Kind.Gguf) local(item)?.takeIf { it.format == ModelFormat.GGUF }?.path else null
    fun status(item: RecommendedCatalog.Item): RecStatus = when (val k = item.kind) {
        is RecommendedCatalog.Kind.Hub, is RecommendedCatalog.Kind.Gguf -> {
            val l = local(item)
            RecStatus.forHub(item.repoId.orEmpty(), hub, l != null || item.repoId in embedDone, l != null && l.path == loadedPath)
        }
        is RecommendedCatalog.Kind.Image -> when (val s = imageStates[k.modelId]) {
            is ImageDl.Downloading -> RecStatus.Downloading(s.done, s.total, s.source, pausable = false)
            is ImageDl.Extracting -> RecStatus.Downloading(s.done, s.total, I18n.s(R.string.models_b8894f), pausable = false)
            is ImageDl.Failed -> RecStatus.Failed(RecommendedCatalog.shortReason(s.message))
            else -> if (k.modelId in imageDone) RecStatus.Installed() else RecStatus.Idle
        }
        is RecommendedCatalog.Kind.Voice -> {
            val p = VoicePack.byId(k.packId)
            when (val s = p?.let { voiceStates[it] }) {
                is PackState.Downloading -> RecStatus.Downloading(s.downloaded, s.total, pausable = false)
                PackState.Extracting -> RecStatus.Downloading(0, 0, I18n.s(R.string.models_b8894f), pausable = false)
                is PackState.Failed -> RecStatus.Failed(RecommendedCatalog.shortReason(s.message))
                PackState.Installed -> RecStatus.Installed(vm.voiceInUse(p))
                else -> RecStatus.Idle
            }
        }
        is RecommendedCatalog.Kind.Translate -> when (val s = TranslatePack.entries.firstOrNull { it.id == k.packId }?.let { translateStates[it] }) {
            is PackState.Downloading -> RecStatus.Downloading(s.downloaded, s.total, pausable = false)
            is PackState.Failed -> RecStatus.Failed(RecommendedCatalog.shortReason(s.message))
            PackState.Installed -> RecStatus.Installed()
            else -> RecStatus.Idle
        }
    }
    fun size(item: RecommendedCatalog.Item): Long = (item.kind as? RecommendedCatalog.Kind.Voice)?.let { k -> VoicePack.byId(k.packId)?.let { voiceSizes[it] }?.takeIf { it > 0 } } ?: item.sizeBytes
    fun ramHint(item: RecommendedCatalog.Item): String? =
        if (ram > 0 && item.section != Section.SPEECH && item.section != Section.TRANSLATE && item.sizeBytes > 0) GgufSearch.fitHint(RecommendedCatalog.runBytes(item), ram) else null // Edge0 按运行内存
    val actions = object : RecActions {
        override fun primary(item: RecommendedCatalog.Item, s: RecStatus, withVision: Boolean) {
            when (s) {
                RecStatus.Idle -> vm.downloadCatalog(item, withVision)
                is RecStatus.Paused -> vm.resumeDownload()
                is RecStatus.Installed -> when (val k = item.kind) {
                    is RecommendedCatalog.Kind.Voice -> VoicePack.byId(k.packId)?.let(vm::useVoice)
                    is RecommendedCatalog.Kind.Image, is RecommendedCatalog.Kind.Translate -> Unit
                    else -> if (!s.loaded) local(item)?.let(vm::load)
                }
                else -> detailId = item.id
            }
        }
        override fun pause() = vm.pauseDownload()
        override fun retry(item: RecommendedCatalog.Item) {
            when (val k = item.kind) {
                is RecommendedCatalog.Kind.Image -> vm.downloadImageModel(k.modelId, retry = true)
                is RecommendedCatalog.Kind.Voice -> VoicePack.byId(k.packId)?.let(vm::downloadVoice)
                is RecommendedCatalog.Kind.Translate -> vm.downloadTranslate(k.packId)
                else -> vm.retryOtherSource()
            }
        }
        override fun cancel(item: RecommendedCatalog.Item) {
            when (val k = item.kind) {
                is RecommendedCatalog.Kind.Image -> vm.cancelImageDownload(k.modelId)
                is RecommendedCatalog.Kind.Voice -> VoicePack.byId(k.packId)?.let(vm::cancelVoice)
                is RecommendedCatalog.Kind.Translate -> vm.cancelTranslate(k.packId)
                else -> vm.cancelDownload()
            }
        }
        override fun discard() { vm.discardDownload() }
        override fun import() = importFile()
    }

    SubpageScaffold(title = DownloadText.TITLE, onBack = onBack) {
        val list = rememberLazyListState()
        TrackTopBarScroll(list)
        val nearEnd by remember { derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 3
        } }
        LaunchedEffect(nearEnd, search.hits.size, tab) { if (tab > 0 && nearEnd && search.hits.isNotEmpty()) vm.loadMore() }
        var fmtPick by androidx.compose.runtime.remember { mutableStateOf(mapOf<String, String>()) }
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = IrisDimens.space32 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item(key = "large-title", contentType = "largeTitle") { LargeTitle() }
            item(key = "search", contentType = "search") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8),
                ) {
                    val submit = {
                        val mode = DownloadText.searchMode(tab)
                        vm.browse(mode, query.trim()); tab = DownloadText.tabOf(mode)
                    }
                    androidx.compose.material3.OutlinedTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = IrisType.body.copy(color = c.text),
                        placeholder = { Text(SearchText.HINT, style = IrisType.body, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.iconMedium)) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            PressableIcon(Icons.Outlined.Close, I18n.s(R.string.chat_288f0c)) { query = ""; if (tab > 0) vm.browse(DownloadText.modeOf(tab)!!, "") }
                        }) else null,
                        shape = IrisRadius.pillShape,
                        colors = com.iris.localai.ui.components.irisTextFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item(key = "tabs", contentType = "tabs") {
                SegmentedControl(
                    DownloadText.TABS, tab, { tab = it },
                    Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
                )
            }
            // a download started from the online lists (not a recommended card): its progress here
            if (hub.repoId.isNotEmpty() && RecommendedCatalog.byRepo(hub.repoId) == null && (hub.active || hub.paused || hub.error != null)) {
                item(key = "other-download", contentType = "download") {
                    val s = RecStatus.forHub(hub.repoId, hub, installed = false, loaded = false)
                    CardShell(onClick = null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hub.repoId.substringAfter('/'), style = IrisType.rowStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(hub.repoId.substringBefore('/'), style = IrisType.caption, color = c.textTertiary, maxLines = 1)
                            }
                            if (s is RecStatus.Paused) IrisButton(I18n.s(R.string.models_27ca56), vm::resumeDownload, compact = true)
                        }
                        DownloadBlock(s, onPause = vm::pauseDownload, onRetry = vm::retryOtherSource, onCancel = vm::cancelDownload, onImport = importFile, onDiscard = { vm.discardDownload() })
                    }
                }
            }
            if (tab == 0) {
                forDevice?.let { rec ->
                    item(key = "for-device-title", contentType = "title") { SectionTitle(I18n.s(R.string.v250_rec_title)) }
                    item(key = "for-device", contentType = "rec") {
                        RecCard(rec, status(rec), size(rec), ramHint(rec), actions, onOpen = { detailId = rec.id }, mtpPath = ggufPath(rec))
                    }
                    items(forDeviceExtra, key = { "for-device-" + it.id }, contentType = { "rec" }) { x ->
                        androidx.compose.foundation.layout.Box(if (LocalReduceMotion.current) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard()), propagateMinConstraints = true) { RecCard(x, status(x), size(x), ramHint(x), actions, onOpen = { detailId = x.id }, mtpPath = ggufPath(x)) }
                    }
                    item(key = "for-device-why", contentType = "hint") {
                        val dr = DeviceRecommend
                        val brand = when (dr.vendor(socMan, socModel)) {
                            DeviceRecommend.Vendor.QUALCOMM -> I18n.s(R.string.v250_soc_qcom)
                            DeviceRecommend.Vendor.MEDIATEK -> I18n.s(R.string.v250_soc_mtk)
                            else -> ""
                        }
                        val cls = when (chip) {
                            DeviceRecommend.Chip.FLAGSHIP -> I18n.s(R.string.v250_chip_flagship)
                            DeviceRecommend.Chip.MID -> I18n.s(R.string.v250_chip_mid)
                            DeviceRecommend.Chip.ENTRY -> I18n.s(R.string.v250_chip_entry)
                            DeviceRecommend.Chip.UNKNOWN -> ""
                        }
                        val chipText = listOf(brand, cls).filter { it.isNotEmpty() }.joinToString(" ")
                        GroupFootnote(I18n.s(R.string.v250_rec_why, dr.ramGb(total), chipText.ifEmpty { "—" }))
                    }
                }
                Section.entries.forEach { sec ->
                    val all = RecommendedCatalog.section(sec)
                    val open = sec in expanded
                    val shown = RecommendedCatalog.visible(sec, open)
                    item(key = "sec-" + sec.name, contentType = "title") { SectionTitle(sec.title) }
                    // sorted series → size; a run of ≥ 2 same-series cards gets a light series sub-title.
                    ModelSort.runs(shown) { it.name }.forEach { (series, run) ->
                    if (run.size >= 2 && sec in RecommendedCatalog.SERIES_TITLED) item(key = "ser-" + sec.name + "-" + run.first().id, contentType = "series") {
                        Text(series, style = IrisType.caption, color = c.textTertiary, modifier = Modifier.padding(start = IrisDimens.pageGutter + IrisDimens.space4, top = IrisDimens.space8, bottom = IrisDimens.space2))
                    }
                    items(com.iris.localai.data.FormatPairs.groups(run), key = { it.first().id }, contentType = { "rec" }) { group ->
                        val item = com.iris.localai.data.FormatPairs.pick(group, fmtPick[group.first().id]) { x -> status(x).let { st -> st is RecStatus.Installed || st is RecStatus.Downloading || st is RecStatus.Paused } }
                        val s = status(item)
                        // switching category / filter fades cards in and out instead of swapping at once.
                        androidx.compose.foundation.layout.Box(if (LocalReduceMotion.current) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard()), propagateMinConstraints = true) { RecCard(item, s, size(item), ramHint(item), actions, onOpen = { detailId = item.id }, mtpPath = ggufPath(item), alts = group, onSwitch = { x -> fmtPick = fmtPick + (group.first().id to x.id) }) }
                    }
                    }
                    if (shown.size < all.size || open && all.size > RecommendedCatalog.visible(sec, false).size) {
                        item(key = "more-" + sec.name, contentType = "more") {
                            IrisButton(
                                if (open) I18n.s(R.string.chat_def9e9) else I18n.s(R.string.models_2c77b8, all.size),
                                { expanded = if (open) expanded - sec else expanded + sec },
                                style = IrisButtonStyle.Text, compact = true,
                                icon = Icons.Outlined.ExpandMore,
                                modifier = Modifier.padding(horizontal = IrisDimens.space8),
                            )
                        }
                    }
                }
                item(key = "import", contentType = "footer") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                        Text(SourceText.IMPORT_HINT, style = IrisType.secondary, color = c.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                            IrisButton(SourceText.IMPORT, importFile, style = IrisButtonStyle.Tonal, compact = true, icon = Icons.Outlined.Add)
                            IrisButton(I18n.s(R.string.models_238420), { folderPicker.launch(null) }, style = IrisButtonStyle.Tonal, compact = true, icon = IrisIcons.Folder)
                        }
                        Text(SourceText.AUTO_SWITCH, style = IrisType.caption, color = c.textTertiary)
                    }
                }
            } else {
                item(key = "mode-hint", contentType = "hint") {
                    Column(Modifier.padding(horizontal = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
                        Text(SearchText.modeHint(search.mode, search.query), style = IrisType.caption, color = c.textTertiary)
                        search.errors.forEach { Text(RecommendedCatalog.shortReason(it), style = IrisType.caption, color = c.danger) }
                        if (search.searched && !search.loading && search.hits.isEmpty()) Text(SearchText.EMPTY, style = IrisType.caption, color = c.textTertiary)
                    }
                }
                items(search.hits, key = { "hit-" + it.site.name + it.id }, contentType = { "hit" }) { h -> androidx.compose.foundation.layout.Box(if (LocalReduceMotion.current) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard()), propagateMinConstraints = true) { OnlineCard(h, ram) { vm.openRepo(h) } } }
                item(key = "more-online", contentType = "hint") {
                    Text(
                        when { search.loading -> I18n.s(R.string.models_25878e); search.hasMore && search.hits.isNotEmpty() -> I18n.s(R.string.models_9248b2); search.hits.isNotEmpty() -> I18n.s(R.string.models_1fe900); else -> "" },
                        style = IrisType.caption, color = c.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
                    )
                }
            }
        }
    }

    detailId?.let(RecommendedCatalog::byId)?.let { item ->
        val l = local(item)
        RecDetailSheet(
            item, status(item), size(item), ramHint(item), actions, mtpPath = ggufPath(item),
            onTry = item.demoPrompt?.takeIf { l != null }?.let { p -> {
                if (l != null && l.path != loadedPath) vm.load(l)
                detailId = null
                onTryPrompt(p)
            } },
            onDismiss = { detailId = null },
        )
    }
    if (search.repo != null) GgufRepoSheet(vm, hub.active)
}

/**
 * open 下载模型 from another page with one catalog card's detail sheet shown.
 * MainActivity sets [opener] (push the route); [open] stores the card id the screen takes once.
 */
object DownloadNav {
    var opener: (() -> Unit)? = null
    @Volatile private var pending: String? = null
    fun open(itemId: String) { pending = itemId; opener?.invoke() }
    fun take(): String? = pending.also { pending = null }
}

private interface RecActions {
    fun primary(item: RecommendedCatalog.Item, s: RecStatus, withVision: Boolean = false)
    fun pause()
    fun retry(item: RecommendedCatalog.Item)
    fun cancel(item: RecommendedCatalog.Item)
    fun discard() {}
    fun import()
}

/** A card on the page (IrisCard), tint + 0.96 press scale, no ripple. */
@Composable
private fun CardShell(onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    IrisCard(
        Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space4)
            .then(
                if (onClick != null) Modifier.pressScale(interaction, GlassTokens.PRESS_SCALE).pressTint(interaction, IrisRadius.largeShape)
                    .clickable(interaction, null, onClick = onClick) else Modifier,
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8), content = content)
    }
}

@Composable
private fun Badge(text: String, tone: Color? = null) {
    val c = Iris.colors
    Text(
        text, style = IrisType.micro, color = tone ?: c.textSecondary, maxLines = 1,
        modifier = Modifier.clip(IrisRadius.pillShape).background(tone?.copy(alpha = 0.12f) ?: c.surfaceVariant)
            .padding(horizontal = IrisDimens.space6, vertical = IrisDimens.space2),
    )
}

@Composable
private fun ramTone(hint: String): Color = if (hint == I18n.s(R.string.models_60a35c)) Iris.colors.success else Iris.colors.warning

@Composable
private fun Badges(item: RecommendedCatalog.Item, size: Long, ramHint: String?, mtpPath: String? = null, alts: List<RecommendedCatalog.Item> = emptyList(), onSwitch: (RecommendedCatalog.Item) -> Unit = {}) {
    // 「MTP」 only when the downloaded GGUF's header has MTP layers (read on IO).
    val mtp by androidx.compose.runtime.produceState(false, mtpPath) {
        value = mtpPath != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            GgufQuant.of(java.io.File(mtpPath))?.mtp == true
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
        if (size > 0) Badge(I18n.s(R.string.chat_768d35) + Format.bytes(size))
        if (alts.size > 1) Row(Modifier.clip(IrisRadius.pillShape).background(Iris.colors.surfaceVariant)) {
            alts.forEach { a ->
                val on = a.id == item.id
                Text(
                    a.engine.label, style = IrisType.micro, color = com.iris.localai.ui.motion.selectTint(if (on) Iris.colors.onAccent else Iris.colors.textSecondary), maxLines = 1,
                    modifier = Modifier.clip(IrisRadius.pillShape).background(com.iris.localai.ui.motion.selectTint(if (on) Iris.colors.accent else Iris.colors.accent.copy(alpha = 0f)))
                        .clickable(enabled = !on) { onSwitch(a) }
                        .padding(horizontal = IrisDimens.space6, vertical = IrisDimens.space2),
                )
            }
        } else Badge(item.engine.label)
        if (mtp) Badge(MtpTag.LABEL)
        ramHint?.let { Badge(it, ramTone(it)) }
    }
}

@Composable
private fun PrimaryButton(item: RecommendedCatalog.Item, s: RecStatus, actions: RecActions, modifier: Modifier = Modifier, compact: Boolean = true, withVision: Boolean = false) {
    val label = RecStatus.primaryLabel(s, item.section)
    val style = when (s) {
        RecStatus.Idle, RecStatus.Blocked, is RecStatus.Paused -> IrisButtonStyle.Primary
        is RecStatus.Installed -> if (s.loaded || item.section == Section.IMAGE || item.section == Section.EMBED) IrisButtonStyle.Text else IrisButtonStyle.Tonal
        else -> IrisButtonStyle.Tonal
    }
    val enabled = s != RecStatus.Blocked && !(s is RecStatus.Installed && (s.loaded || item.section == Section.IMAGE || item.section == Section.EMBED))
    IrisButton(label, { actions.primary(item, s, withVision) }, modifier, style = style, compact = compact, enabled = enabled)
}

@Composable
private fun RecCard(item: RecommendedCatalog.Item, s: RecStatus, size: Long, ramHint: String?, actions: RecActions, onOpen: () -> Unit, mtpPath: String? = null, alts: List<RecommendedCatalog.Item> = emptyList(), onSwitch: (RecommendedCatalog.Item) -> Unit = {}) {
    val c = Iris.colors
    CardShell(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrisDimens.space2)) {
                Text(item.name, style = IrisType.rowStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.purpose, style = IrisType.secondary, color = c.textSecondary)
                Spacer(Modifier.height(IrisDimens.space4))
                Badges(item, size, ramHint, mtpPath, alts, onSwitch)
            }
            if (s !is RecStatus.Failed) {
                Spacer(Modifier.width(IrisDimens.space12))
                PrimaryButton(item, s, actions)
            }
        }
        DownloadBlock(s, onPause = actions::pause, onRetry = { actions.retry(item) }, onCancel = { actions.cancel(item) }, onImport = actions::import, onDiscard = actions::discard)
    }
}

/** Inline progress (暂停 / 取消), paused (换源重试 / 取消) or failure (one reason, 换源重试, 从文件导入). */
@Composable
private fun DownloadBlock(s: RecStatus, onPause: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onImport: () -> Unit, onDiscard: (() -> Unit)? = null) {
    val c = Iris.colors
    when (s) {
        is RecStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            IrisProgressBar(progress = s.fraction)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DownloadText.progressLine(s.done, s.total, s.source), style = IrisType.caption, color = c.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (s.pausable) IrisButton(DownloadText.PAUSE, onPause, style = IrisButtonStyle.Text, compact = true)
                IrisButton(DownloadText.CANCEL, onCancel, style = IrisButtonStyle.Text, compact = true)
            }
            s.notice?.let { Text(RecommendedCatalog.shortReason(it), style = IrisType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        is RecStatus.Paused -> Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            IrisProgressBar(progress = if (s.total > 0) (s.done.toDouble() / s.total).toFloat() else 0f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.s(R.string.models_80e506) + DownloadText.progressLine(s.done, s.total), style = IrisType.caption, color = c.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IrisButton(SourceText.RETRY_OTHER, onRetry, style = IrisButtonStyle.Text, compact = true)
                IrisButton(DownloadText.CANCEL, onCancel, style = IrisButtonStyle.Text, compact = true)
            }
            // 清除已下载 — confirm, then the .part files go and the card is back to 下载.
            if (onDiscard != null) {
                var asking by remember { mutableStateOf(false) }
                IrisButton(DownloadText.DISCARD, { asking = true }, style = IrisButtonStyle.Text, compact = true)
                if (asking) ConfirmDialog(
                    title = DownloadText.DISCARD,
                    message = I18n.s(R.string.dl263_discard_msg, Format.bytes(s.done)),
                    confirmText = I18n.s(R.string.chat_2f4aad),
                    onConfirm = { asking = false; onDiscard() },
                    onDismiss = { asking = false },
                )
            }
        }
        is RecStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            SelectionContainer { Text(s.reason, style = IrisType.caption, color = c.danger) }
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                IrisButton(SourceText.RETRY_OTHER, onRetry, style = IrisButtonStyle.Tonal, compact = true, icon = Icons.Outlined.Refresh)
                IrisButton(SourceText.IMPORT, onImport, style = IrisButtonStyle.Text, compact = true, icon = Icons.Outlined.Add)
            }
        }
        else -> Unit
    }
}

@Composable
private fun OnlineCard(h: GgufSearch.RepoHit, ram: Long, onOpen: () -> Unit) {
    val c = Iris.colors
    CardShell(onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space2)) {
            Text(h.id.substringAfter('/'), style = IrisType.rowStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(h.id.substringBefore('/'), SearchText.stats(h)).joinToString(" · "), style = IrisType.caption, color = c.textTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
            Badge(h.site.tag)
            h.sizeRange?.let { r -> Badge(I18n.s(R.string.chat_768d35) + Format.bytes(r.first) + I18n.s(R.string.models_d59693)) }
            if (ram > 0) h.sizeRange?.let { r -> GgufSearch.fitHint(r.first, ram).let { Badge(it, ramTone(it)) } }
            if (h.gated) Badge(I18n.s(R.string.models_dc6722), Iris.colors.warning)
        }
    }
}

/** The details behind a card: files, quant, licence, source; 特色模型 adds 看图 and a demo prompt. */
@Composable
private fun RecDetailSheet(
    item: RecommendedCatalog.Item, s: RecStatus, size: Long, ramHint: String?, actions: RecActions,
    onTry: (() -> Unit)?, onDismiss: () -> Unit, mtpPath: String? = null,
) {
    val c = Iris.colors
    var withVision by rememberSaveable(item.id) { mutableStateOf(false) }
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(item.name)
        Column(Modifier.padding(horizontal = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            Text(item.purpose, style = IrisType.body, color = c.textSecondary)
            Badges(item, size, ramHint, mtpPath)
        }
        Spacer(Modifier.height(IrisDimens.space8))
        ValueRow(I18n.s(R.string.chat_2a0c47), DownloadText.filesLine(item))
        item.quant?.let { ValueRow(I18n.s(R.string.models_03860b), it) }
        item.licence?.let { ValueRow(I18n.s(R.string.models_20a284), it) }
        ValueRow(I18n.s(R.string.chat_26ca20), item.repoId ?: when (val k = item.kind) {
            is RecommendedCatalog.Kind.Image -> ImageCatalog.byId(k.modelId)?.hfRepo ?: "Qualcomm AI Hub"
            is RecommendedCatalog.Kind.Translate -> I18n.s(R.string.models_3788b3, TranslatePack.REPO)
            else -> I18n.s(R.string.models_e88a00)
        })
        val g = item.kind as? RecommendedCatalog.Kind.Gguf
        if (g?.mmproj != null && s == RecStatus.Idle) {
            CheckRow(DownloadText.VISION_TITLE, withVision, subtitle = I18n.s(R.string.models_10ffff, g.mmproj, Format.bytes(g.mmprojSize))) { withVision = !withVision }
        }
        item.demoPrompt?.let { prompt ->
            Column(Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8), verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                Text(DownloadText.TRY, style = IrisType.labelSmall, color = c.textTertiary)
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (onTry != null) Modifier.pressScale(interaction, GlassTokens.PRESS_SCALE) else Modifier)
                        .clip(IrisRadius.mediumShape).background(c.surfaceVariant)
                        .then(if (onTry != null) Modifier.pressTint(interaction, IrisRadius.mediumShape).clickable(interaction, null, onClick = onTry) else Modifier)
                        .padding(IrisDimens.space12),
                ) {
                    Text(prompt, style = IrisType.secondary, color = if (onTry != null) c.text else c.textTertiary)
                }
                if (onTry == null) Text(DownloadText.TRY_HINT, style = IrisType.caption, color = c.textTertiary)
            }
        }
        if (item.section == RecommendedCatalog.Section.CHAT || item.section == RecommendedCatalog.Section.FEATURED) {
            // 科普页互链 — 看懂这张卡片上的术语（GGUF/量化/MoE）。
            Text(I18n.s(R.string.models264_guide_link), style = IrisType.caption, color = c.accent,
                modifier = Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space4)
                    .clickable { val key = ModelGuideNav.sectionFor(item); onDismiss(); ModelGuideNav.open(key) }.padding(vertical = IrisDimens.space4))
        }
        Column(Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            DownloadBlock(s, onPause = actions::pause, onRetry = { actions.retry(item) }, onCancel = { actions.cancel(item) }, onImport = actions::import, onDiscard = actions::discard)
            if (s == RecStatus.Blocked) Text(DownloadText.ONE_AT_A_TIME, style = IrisType.caption, color = c.warning)
            if (s !is RecStatus.Failed && s !is RecStatus.Downloading) {
                PrimaryButton(item, s, actions, Modifier.fillMaxWidth(), compact = false, withVision = withVision)
            }
        }
    }
}


package com.iris.localai.ui.models

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

/** 2.2.17 C 下载模型 page texts and small pure helpers (unit-tested). */
object DownloadText {
    const val TITLE = "下载模型"
    val TABS = listOf("推荐", "热门", "最新", "适合本机")
    const val ONE_AT_A_TIME = "一次下载一个，当前的下完再下这个"
    const val PAUSE = "暂停"
    const val CANCEL = "取消"
    const val VISION_TITLE = "同时下载看图组件"
    const val TRY = "试一试"
    const val TRY_HINT = "下载并加载后，点上面的示例发到对话"

    /** Tab index → online list (null = 推荐). */
    fun modeOf(tab: Int): GgufSearch.Browse? = when (tab) { 1 -> GgufSearch.Browse.HOT; 2 -> GgufSearch.Browse.NEW; 3 -> GgufSearch.Browse.FIT; else -> null }
    fun tabOf(mode: GgufSearch.Browse): Int = when (mode) { GgufSearch.Browse.HOT -> 1; GgufSearch.Browse.NEW -> 2; GgufSearch.Browse.FIT -> 3 }

    /** A search from any tab: 最新 / 推荐 search in 热门 order, 热门 / 适合本机 keep theirs. */
    fun searchMode(tab: Int): GgufSearch.Browse = modeOf(tab)?.takeIf { it != GgufSearch.Browse.NEW } ?: GgufSearch.Browse.HOT

    fun displayName(repoId: String): String = RecommendedCatalog.byRepo(repoId)?.name ?: repoId.substringAfter('/')

    /** One line for the 模型与加载 page (null = nothing downloading / paused / failed). */
    fun statusLine(hub: MnnHub.State): String? = when {
        hub.active -> "正在下载 ${displayName(hub.repoId)}" + (if (hub.totalBytes > 0) " · ${(hub.progress * 100).toInt()}%" else "")
        hub.paused -> "已暂停 ${displayName(hub.repoId)}"
        hub.error != null -> "下载失败：" + RecommendedCatalog.shortReason(hub.error)
        else -> null
    }

    /** "1.2 GB / 2.5 GB · 魔搭 ModelScope". */
    fun progressLine(done: Long, total: Long, source: String = ""): String =
        listOfNotNull(if (total > 0) "${Format.bytes(done)} / ${Format.bytes(total)}" else "准备中…", source.ifBlank { null }).joinToString(" · ")

    /** What the detail sheet says the download fetches. */
    fun filesLine(item: RecommendedCatalog.Item): String = when (val k = item.kind) {
        is RecommendedCatalog.Kind.Gguf -> k.file
        is RecommendedCatalog.Kind.Hub -> when (item.engine) {
            RecommendedCatalog.Engine.MNN -> "整个 MNN 模型文件夹"
            RecommendedCatalog.Engine.LITERT -> "通用版 .litertlm 文件"
            else -> "自动选 Q4_K_M 的 .gguf 文件"
        }
        is RecommendedCatalog.Kind.Image -> "模型文件包（下载后在 生图 页使用）"
        is RecommendedCatalog.Kind.Voice -> "语音模型文件包（在 设置 › 语音与翻译 里切换）"
        is RecommendedCatalog.Kind.Translate -> "离线翻译模型（模型、词表、词汇短表）"
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(search.query) }
    var expanded by remember { mutableStateOf(emptySet<Section>()) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }

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
    fun local(item: RecommendedCatalog.Item): LocalModel? = vm.localFor(item, models)
    fun status(item: RecommendedCatalog.Item): RecStatus = when (val k = item.kind) {
        is RecommendedCatalog.Kind.Hub, is RecommendedCatalog.Kind.Gguf -> {
            val l = local(item)
            RecStatus.forHub(item.repoId!!, hub, l != null, l != null && l.path == loadedPath)
        }
        is RecommendedCatalog.Kind.Image -> when (val s = imageStates[k.modelId]) {
            is ImageDl.Downloading -> RecStatus.Downloading(s.done, s.total, s.source, pausable = false)
            is ImageDl.Extracting -> RecStatus.Downloading(s.done, s.total, "正在解压", pausable = false)
            is ImageDl.Failed -> RecStatus.Failed(RecommendedCatalog.shortReason(s.message))
            else -> if (k.modelId in imageDone) RecStatus.Installed() else RecStatus.Idle
        }
        is RecommendedCatalog.Kind.Voice -> {
            val p = VoicePack.byId(k.packId)
            when (val s = p?.let { voiceStates[it] }) {
                is PackState.Downloading -> RecStatus.Downloading(s.downloaded, s.total, pausable = false)
                PackState.Extracting -> RecStatus.Downloading(0, 0, "正在解压", pausable = false)
                is PackState.Failed -> RecStatus.Failed(RecommendedCatalog.shortReason(s.message))
                PackState.Installed -> RecStatus.Installed(vm.voiceInUse(p))
                else -> RecStatus.Idle
            }
        }
        is RecommendedCatalog.Kind.Translate -> when (val s = com.iris.localai.translate.TranslatePack.entries.firstOrNull { it.id == k.packId }?.let { translateStates[it] }) {
            is PackState.Downloading -> RecStatus.Downloading(s.downloaded, s.total, pausable = false)
            is PackState.Failed -> RecStatus.Failed(RecommendedCatalog.shortReason(s.message))
            PackState.Installed -> RecStatus.Installed()
            else -> RecStatus.Idle
        }
    }
    fun size(item: RecommendedCatalog.Item): Long = (item.kind as? RecommendedCatalog.Kind.Voice)?.let { k -> VoicePack.byId(k.packId)?.let(vm::voiceBytes) } ?: item.sizeBytes
    fun ramHint(item: RecommendedCatalog.Item): String? =
        if (ram > 0 && item.section != Section.SPEECH && item.section != Section.TRANSLATE && item.sizeBytes > 0) GgufSearch.fitHint(item.sizeBytes, ram) else null
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
                            com.iris.localai.ui.components.PressableIcon(Icons.Outlined.Close, "清空") { query = ""; if (tab > 0) vm.browse(DownloadText.modeOf(tab)!!, "") }
                        }) else null,
                        shape = IrisRadius.pillShape,
                        colors = com.iris.localai.ui.components.irisTextFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                    SourceChip(settings.downloadSource, vm::setDownloadSource)
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
                                Text(hub.repoId.substringAfter('/'), style = IrisType.rowStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hub.repoId.substringBefore('/'), style = IrisType.caption, color = c.textTertiary, maxLines = 1)
                            }
                            if (s is RecStatus.Paused) IrisButton("继续", vm::resumeDownload, compact = true)
                        }
                        DownloadBlock(s, onPause = vm::pauseDownload, onRetry = vm::retryOtherSource, onCancel = vm::cancelDownload, onImport = importFile)
                    }
                }
            }
            if (tab == 0) {
                Section.entries.forEach { sec ->
                    val all = RecommendedCatalog.section(sec)
                    val open = sec in expanded
                    val shown = RecommendedCatalog.visible(sec, open)
                    item(key = "sec-" + sec.name, contentType = "title") { SectionTitle(sec.title) }
                    items(shown, key = { it.id }, contentType = { "rec" }) { item ->
                        val s = status(item)
                        RecCard(item, s, size(item), ramHint(item), actions, onOpen = { detailId = item.id })
                    }
                    if (shown.size < all.size || open && all.size > RecommendedCatalog.visible(sec, false).size) {
                        item(key = "more-" + sec.name, contentType = "more") {
                            IrisButton(
                                if (open) "收起" else "展开全部（${all.size}）",
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
                            IrisButton("导入文件夹", { folderPicker.launch(null) }, style = IrisButtonStyle.Tonal, compact = true, icon = com.iris.localai.ui.icons.IrisIcons.Folder)
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
                items(search.hits, key = { "hit-" + it.site.name + it.id }, contentType = { "hit" }) { h -> OnlineCard(h, ram) { vm.openRepo(h) } }
                item(key = "more-online", contentType = "hint") {
                    Text(
                        when { search.loading -> "正在加载 HuggingFace 和 魔搭…"; search.hasMore && search.hits.isNotEmpty() -> "上滑加载更多"; search.hits.isNotEmpty() -> "没有更多了"; else -> "" },
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
            item, status(item), size(item), ramHint(item), actions,
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

private interface RecActions {
    fun primary(item: RecommendedCatalog.Item, s: RecStatus, withVision: Boolean = false)
    fun pause()
    fun retry(item: RecommendedCatalog.Item)
    fun cancel(item: RecommendedCatalog.Item)
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
private fun ramTone(hint: String): Color = if (hint == "内存够用") Iris.colors.success else Iris.colors.warning

@Composable
private fun Badges(item: RecommendedCatalog.Item, size: Long, ramHint: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
        if (size > 0) Badge("约 " + Format.bytes(size))
        Badge(item.engine.label)
        ramHint?.let { Badge(it, ramTone(it)) }
    }
}

@Composable
private fun PrimaryButton(item: RecommendedCatalog.Item, s: RecStatus, actions: RecActions, modifier: Modifier = Modifier, compact: Boolean = true, withVision: Boolean = false) {
    val label = RecStatus.primaryLabel(s, item.section)
    val style = when (s) {
        RecStatus.Idle, RecStatus.Blocked, is RecStatus.Paused -> IrisButtonStyle.Primary
        is RecStatus.Installed -> if (s.loaded || item.section == Section.IMAGE) IrisButtonStyle.Text else IrisButtonStyle.Tonal
        else -> IrisButtonStyle.Tonal
    }
    val enabled = s != RecStatus.Blocked && !(s is RecStatus.Installed && (s.loaded || item.section == Section.IMAGE))
    IrisButton(label, { actions.primary(item, s, withVision) }, modifier, style = style, compact = compact, enabled = enabled)
}

@Composable
private fun RecCard(item: RecommendedCatalog.Item, s: RecStatus, size: Long, ramHint: String?, actions: RecActions, onOpen: () -> Unit) {
    val c = Iris.colors
    CardShell(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrisDimens.space2)) {
                Text(item.name, style = IrisType.rowStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.purpose, style = IrisType.secondary, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(IrisDimens.space4))
                Badges(item, size, ramHint)
            }
            if (s !is RecStatus.Failed) {
                Spacer(Modifier.width(IrisDimens.space12))
                PrimaryButton(item, s, actions)
            }
        }
        DownloadBlock(s, onPause = actions::pause, onRetry = { actions.retry(item) }, onCancel = { actions.cancel(item) }, onImport = actions::import)
    }
}

/** Inline progress (暂停 / 取消), paused (换源重试 / 取消) or failure (one reason, 换源重试, 从文件导入). */
@Composable
private fun DownloadBlock(s: RecStatus, onPause: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onImport: () -> Unit) {
    val c = Iris.colors
    when (s) {
        is RecStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            IrisProgressBar(progress = s.fraction)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DownloadText.progressLine(s.done, s.total, s.source), style = IrisType.caption, color = c.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (s.pausable) IrisButton(DownloadText.PAUSE, onPause, style = IrisButtonStyle.Text, compact = true)
                IrisButton(DownloadText.CANCEL, onCancel, style = IrisButtonStyle.Text, compact = true)
            }
            s.notice?.let { Text(RecommendedCatalog.shortReason(it), style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        is RecStatus.Paused -> Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            IrisProgressBar(progress = if (s.total > 0) (s.done.toDouble() / s.total).toFloat() else 0f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已暂停 · " + DownloadText.progressLine(s.done, s.total), style = IrisType.caption, color = c.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IrisButton(SourceText.RETRY_OTHER, onRetry, style = IrisButtonStyle.Text, compact = true)
                IrisButton(DownloadText.CANCEL, onCancel, style = IrisButtonStyle.Text, compact = true)
            }
        }
        is RecStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            Text(s.reason, style = IrisType.caption, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            Text(h.id.substringAfter('/'), style = IrisType.rowStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(h.id.substringBefore('/'), SearchText.stats(h)).joinToString(" · "), style = IrisType.caption, color = c.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
            Badge("GGUF")
            Badge(h.site.tag)
            h.sizeRange?.let { r -> Badge("约 " + Format.bytes(r.first) + " 起") }
            if (ram > 0) h.sizeRange?.let { r -> GgufSearch.fitHint(r.first, ram).let { Badge(it, ramTone(it)) } }
            if (h.gated) Badge("需申请", Iris.colors.warning)
        }
    }
}

/** The details behind a card: files, quant, licence, source; 特色模型 adds 看图 and a demo prompt. */
@Composable
private fun RecDetailSheet(
    item: RecommendedCatalog.Item, s: RecStatus, size: Long, ramHint: String?, actions: RecActions,
    onTry: (() -> Unit)?, onDismiss: () -> Unit,
) {
    val c = Iris.colors
    var withVision by rememberSaveable(item.id) { mutableStateOf(false) }
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(item.name)
        Column(Modifier.padding(horizontal = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            Text(item.purpose, style = IrisType.body, color = c.textSecondary)
            Badges(item, size, ramHint)
        }
        Spacer(Modifier.height(IrisDimens.space8))
        ValueRow("文件", DownloadText.filesLine(item))
        item.quant?.let { ValueRow("量化", it) }
        item.licence?.let { ValueRow("许可证", it) }
        ValueRow("来源", item.repoId ?: when (val k = item.kind) {
            is RecommendedCatalog.Kind.Image -> com.iris.localai.imagegen.ImageCatalog.byId(k.modelId)?.hfRepo ?: "Qualcomm AI Hub"
            is RecommendedCatalog.Kind.Translate -> "Mozilla Firefox Translations（${com.iris.localai.translate.TranslatePack.REPO}）"
            else -> "sherpa-onnx（k2-fsa）"
        })
        val g = item.kind as? RecommendedCatalog.Kind.Gguf
        if (g?.mmproj != null && s == RecStatus.Idle) {
            CheckRow(DownloadText.VISION_TITLE, withVision, subtitle = "${g.mmproj} · ${Format.bytes(g.mmprojSize)} · 加载后可以发图片给它") { withVision = !withVision }
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
        Column(Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            DownloadBlock(s, onPause = actions::pause, onRetry = { actions.retry(item) }, onCancel = { actions.cancel(item) }, onImport = actions::import)
            if (s == RecStatus.Blocked) Text(DownloadText.ONE_AT_A_TIME, style = IrisType.caption, color = c.warning)
            if (s !is RecStatus.Failed && s !is RecStatus.Downloading) {
                PrimaryButton(item, s, actions, Modifier.fillMaxWidth(), compact = false, withVision = withVision)
            }
        }
    }
}

/** Compact 下载源 chip (自动 / 魔搭 / hf-mirror / HF) with a menu. */
@Composable
private fun SourceChip(source: DownloadSource, onSource: (DownloadSource) -> Unit) {
    val c = Iris.colors
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box {
        Row(
            Modifier.height(IrisDimens.rowMinHeight)
                .pressScale(interaction, GlassTokens.PRESS_SCALE)
                .clip(IrisRadius.pillShape).background(c.surfaceVariant)
                .pressTint(interaction, IrisRadius.pillShape)
                .clickable(interaction, null, onClickLabel = SourceText.TITLE) { open = true }
                .padding(horizontal = IrisDimens.space12),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrisDimens.space4),
        ) {
            Column {
                Text(SourceText.TITLE, style = IrisType.micro, color = c.textTertiary)
                Text(source.short, style = IrisType.labelSmall, color = c.text, maxLines = 1)
            }
            Icon(Icons.Outlined.ExpandMore, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.iconSmall))
        }
        IrisMenu(open, { open = false }) {
            DownloadSource.entries.forEach { s ->
                IrisMenuItem(s.short, if (s == source) Icons.Outlined.Check else null) { onSource(s); open = false }
            }
            Text(SourceText.hint(source), style = IrisType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8).widthIn(max = IrisDimens.drawerWidth))
        }
    }
}

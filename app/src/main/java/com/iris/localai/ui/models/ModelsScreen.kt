package com.iris.localai.ui.models

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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    init { refresh() }

    fun refresh() = viewModelScope.launch { refreshFolder(); c.models.refresh() }
    fun load(m: LocalModel) = viewModelScope.launch { c.engine.load(m) }
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
                if (engine.value !is EngineState.Ready) c.engine.load(m)
            } catch (t: Throwable) {
                _import.value = ImportState(error = if (t is kotlinx.coroutines.CancellationException) null else t.message)
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    // ---- MNN folders ----
    val mnnSupported: Boolean = com.iris.localai.data.ModelFormat.MNN in com.iris.localai.engine.EngineFactory.supportedFormats
    val hub = c.mnnHub.state

    fun importFolder(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _import.value = ImportState(fileName = uri.lastPathSegment?.substringAfterLast(':').orEmpty(), active = true)
            try {
                val m = c.models.importFolder(uri) { p -> _import.value = _import.value.copy(progress = p) }
                _import.value = ImportState()
                if (engine.value !is EngineState.Ready) c.engine.load(m)
            } catch (t: Throwable) {
                _import.value = ImportState(error = if (t is kotlinx.coroutines.CancellationException) null else t.message)
            }
        }
    }

    fun downloadMnn(repoId: String) {
        c.mnnHub.start(c.scope, repoId, c.settings.current.downloadSource) { m ->
            if (c.engine.state.value !is EngineState.Ready) c.engine.load(m)
        }
    }

    /** 2.2.12: a 生图 model from the recommended list → the 生图 downloader (progress on the 生图 page). */
    fun downloadImageModel(id: String) {
        val m = com.iris.localai.imagegen.ImageCatalog.byId(id) ?: return
        viewModelScope.launch {
            val root = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.imagegen.ImageGenRepository(getApplication()).downloadRoot(m) }
            c.imageGenDownloader.start(m, root, c.settings.current.downloadSource)
            android.widget.Toast.makeText(getApplication(), "已开始下载「${m.name}」，进度见 生图 页", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelDownload() = c.mnnHub.cancel()
    fun clearDownloadError() = c.mnnHub.clearError()
}

@Composable
fun ModelsScreen(onBack: () -> Unit, vm: ModelsViewModel = viewModel()) {
    val c = Iris.colors
    val models by vm.models.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val modelConfigs by vm.modelConfigs.collectAsStateWithLifecycle()
    val appSettings by vm.settingsFlow.collectAsStateWithLifecycle()
    val import by vm.import.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
    var configure by remember { mutableStateOf<LocalModel?>(null) }
    configure?.let { m -> ModelConfigSheet(m, onDismiss = { configure = null }) }
    val reduce = LocalReduceMotion.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importFolder) }
    val hub by vm.hub.collectAsStateWithLifecycle()
    var showDownload by remember { mutableStateOf(false) }
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
        title = "模型管理",
        onBack = onBack,
        actions = { PressableIcon(Icons.Outlined.Refresh, "刷新") { vm.refresh() } },
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
            item(key = "import", contentType = "import") {
                Column(Modifier.padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space8)) {
                    IrisButton(
                        "导入模型文件", onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !import.active, icon = Icons.Outlined.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.mnnSupported) {
                        Row(Modifier.padding(top = IrisDimens.space8), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                            IrisButton(
                                "导入文件夹", onClick = { folderPicker.launch(null) },
                                enabled = !import.active, style = IrisButtonStyle.Tonal, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                                modifier = Modifier.weight(1f),
                            )
                            IrisButton(
                                "下载模型", onClick = { vm.clearDownloadError(); showDownload = true },
                                enabled = !hub.active, style = IrisButtonStyle.Tonal, icon = Icons.Outlined.CloudDownload,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (hub.active) {
                            Column(Modifier.padding(top = IrisDimens.space16)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "正在下载 ${hub.repoId} · ${hub.file}",
                                        style = IrisType.labelSmall, color = c.textSecondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (hub.totalBytes > 0) "${Format.bytes(hub.doneBytes)} / ${Format.bytes(hub.totalBytes)}" else "",
                                        style = IrisType.labelSmall, color = c.textSecondary,
                                    )
                                }
                                Spacer(Modifier.height(IrisDimens.space8))
                                IrisProgressBar(progress = hub.progress.takeIf { hub.totalBytes > 0 })
                                IrisButton("取消下载", vm::cancelDownload, style = IrisButtonStyle.Text, compact = true, modifier = Modifier.padding(top = IrisDimens.space4))
                            }
                        }
                        hub.error?.let { Text(it, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(top = IrisDimens.space8)) }
                    }
                    AnimatedVisibility(
                        visible = import.active,
                        enter = fadeIn(IrisMotion.enter()) + expandVertically(IrisMotion.enter()),
                        exit = fadeOut(IrisMotion.standard()) + shrinkVertically(IrisMotion.standard()),
                    ) {
                        Column(Modifier.padding(top = IrisDimens.space16)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "正在复制 ${import.fileName.substringAfterLast('/').ifBlank { "模型" }}",
                                    style = IrisType.labelSmall, color = c.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text("${(import.progress * 100).toInt()}%", style = IrisType.labelSmall, color = c.textSecondary)
                            }
                            Spacer(Modifier.height(IrisDimens.space8))
                            IrisProgressBar(progress = import.progress)
                            IrisButton("取消", vm::cancelImport, style = IrisButtonStyle.Text, compact = true, modifier = Modifier.padding(top = IrisDimens.space4))
                        }
                    }
                    import.error?.let { Text(it, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(top = IrisDimens.space8)) }
                }
            }

            (engine as? EngineState.Ready)?.let { ready ->
                item(key = "loaded-title", contentType = "title") { SectionTitle("当前已加载") }
                item(key = "loaded", contentType = "loaded") {
                    SettingsGroup {
                        val d = ready.details
                        SettingsRow(ready.model.displayName, subtitle = d.description.ifBlank { null })
                        Divider()
                        ValueRow("推理后端", d.backend)
                        if (d.parameterCount > 0) {
                            Divider()
                            ValueRow("参数量", Format.params(d.parameterCount))
                        }
                        Divider()
                        ValueRow("上下文长度", if (d.trainContextLength > 0) "${d.contextLength} / ${d.trainContextLength}" else "${d.contextLength}")
                        Divider()
                        ValueRow("聊天模板", if (d.hasChatTemplate) "模型内置" else "ChatML（兜底）")
                        if (d.supportsThinking) {
                            Divider()
                            ValueRow("深度思考", "支持")
                        }
                        Divider()
                        SettingsRow("卸载模型并释放内存", titleColor = c.danger, enabled = busy == null, onClick = { vm.unload() })
                    }
                }
            }
            (engine as? EngineState.Failed)?.let { f ->
                item(key = "failed", contentType = "failed") {
                    Text(f.message, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space12))
                }
            }

            item(key = "local-title", contentType = "title") { SectionTitle("本机模型（${models.size}）") }
            if (models.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(
                        title = "还没有模型",
                        message = if (vm.mnnSupported) "导入模型文件或文件夹，在线下载，或直接放到下方目录"
                        else "导入模型文件，或用数据线 / adb 直接放到下方目录",
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
                        ModelRow(
                            m,
                            tag = remember(m.id, modelConfigs, appSettings.ggufEngine, appSettings.defaultBackends) { vm.engineTag(m) },
                            state = engine,
                            busy = busy != null,
                            onLoad = { vm.load(m) },
                            onCancelLoad = { vm.cancelLoad() },
                            onDelete = { confirmDelete = m },
                            onSettings = { configure = m },
                            dragHandle = handle,
                        )
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
                                "卸载 App 时这些模型会一起被删除", style = IrisType.secondary, color = c.textTertiary,
                                modifier = Modifier.weight(1f).padding(start = IrisDimens.space16),
                            )
                            IrisButton("改存到下载目录", { openAllFilesAccess(context) }, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                }
                GroupFootnote("可在「下载模型」里获取，或把模型文件拷进上面的文件夹。")
            }
        }
    }

    if (showDownload) {
        MnnDownloadDialog(onDismiss = { showDownload = false }, onDownload = { vm.downloadMnn(it) }, onDownloadImage = { vm.downloadImageModel(it) })
    }

    confirmDelete?.let { m ->
        ModelDeleteDialog(m, onConfirm = { vm.delete(m) }, onDismiss = { confirmDelete = null })
    }
}

/** 1.14.3: the one 「删除模型？」 confirmation (模型管理 and 设置 › 存储空间). */
@Composable
internal fun ModelDeleteDialog(m: LocalModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "删除模型？",
        message = "将从手机中删除 ${m.fileName}（${Format.bytes(m.sizeBytes)}）。",
        confirmText = "删除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MnnDownloadDialog(onDismiss: () -> Unit, onDownload: (String) -> Unit, onDownloadImage: (String) -> Unit = {}) {
    val c = Iris.colors
    var text by remember { mutableStateOf(com.iris.localai.data.MnnHub.RECOMMENDED.first().first) }
    val valid = com.iris.localai.data.MnnModels.parseRepoId(text) != null
    com.iris.localai.ui.components.IrisDialog(
        onDismiss = onDismiss, title = "下载模型", confirmText = "下载",
        confirmEnabled = valid,
        onConfirm = { onDownload(text.trim()); onDismiss() },
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            Text("Hugging Face 仓库名，或点选推荐：", style = IrisType.secondary, color = c.textSecondary)
            androidx.compose.material3.OutlinedTextField(
                text, { text = it }, singleLine = true,
                textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text("作者/仓库名", style = IrisType.body, color = c.textTertiary) },
                shape = IrisRadius.mediumShape,
                colors = com.iris.localai.ui.components.irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            // 2.2.12: official list by category; tap = download now (生图 models go to the 生图 downloader)
            com.iris.localai.data.MnnHub.RECOMMENDED_GROUPS.forEach { g ->
                Text(g.title, style = IrisType.labelSmall, color = c.textTertiary, modifier = Modifier.padding(top = IrisDimens.space4))
                g.items.forEach { r ->
                    Column(
                        Modifier.fillMaxWidth().clip(IrisRadius.mediumShape)
                            .background(if (text.trim() == r.id) c.surfaceVariant else Color.Transparent)
                            .clickable {
                                if (r.imageModel != null) onDownloadImage(r.imageModel) else onDownload(r.id)
                                onDismiss()
                            }.padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6),
                    ) {
                        Text(r.title, style = IrisType.rowStrong, color = c.text)
                        Text(r.note, style = IrisType.caption, color = c.textTertiary)
                    }
                }
            }
            Text("镜像失败时自动换源。", style = IrisType.caption, color = c.textTertiary)
        }
    }
}

/** Picker line with the backend: the real one once loaded, otherwise the one a load would start with. */
internal fun LocalModel.pickerMeta(loadedBackend: String?, preferredBackend: String, engineTag: String? = null): String = when {
    loadedBackend != null -> listOfNotNull(loadedBackend, quantization, Format.bytes(sizeBytes)).joinToString(" · ")
    // 2.2.0: models run by GenieX show 「GenieX · NPU」 instead of the format label + backend.
    engineTag != null -> listOfNotNull(engineTag, quantization, Format.bytes(sizeBytes)).joinToString(" · ")
    else -> listOfNotNull(format.label, preferredBackend.ifBlank { null }, quantization, Format.bytes(sizeBytes)).joinToString(" · ")
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
            Icons.Outlined.DragHandle, "拖动排序", tint = c.textTertiary,
            modifier = dragHandle.size(40.dp).padding(IrisDimens.space8),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrisDimens.space2)) {
            Text(m.displayName, style = IrisType.rowStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.metaLine(tag), style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(IrisDimens.space8))
        when {
            loaded -> Text("已加载", style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.padding(end = IrisDimens.space8))
            loading && (state as? EngineState.Loading)?.applyingTier == null -> IrisButton("取消加载", onCancelLoad, style = IrisButtonStyle.Outline, compact = true)
            loading -> Text("加载中…", style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.padding(end = IrisDimens.space8).pulse())
            else -> IrisButton("加载", onLoad, style = IrisButtonStyle.Outline, compact = true, enabled = !busy && state !is EngineState.Loading)
        }
        PressableIcon(Icons.Outlined.Tune, "后端与参数", iconSize = IrisDimens.iconMedium, tint = c.textSecondary, onClick = onSettings)
        PressableIcon(Icons.Outlined.DeleteOutline, "删除", iconSize = IrisDimens.iconMedium, tint = c.textSecondary, enabled = !loading, onClick = onDelete)
    }
}

/** Selectable model row used by the model picker sheet. */
@Composable
fun ModelListItem(
    model: LocalModel, loaded: Boolean, loading: Boolean, enabled: Boolean, onClick: () -> Unit,
    meta: String = model.metaLine(), onSettings: (() -> Unit)? = null,
    /** 1.10.2: a second caption line, e.g. 「上次加载 12 秒 · 已用缓存」 (null = none). */
    extra: String? = null,
) {
    val c = Iris.colors
    val bg by animateColorAsState(if (loaded) c.surfaceVariant else Color.Transparent, IrisMotion.standard(), label = "modelBg")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space2)
            .heightIn(min = 60.dp)
            .clip(IrisRadius.mediumShape)
            .background(bg)
            .clickable(enabled = enabled && !loaded && !loading, onClick = onClick)
            .padding(horizontal = IrisDimens.space12, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrisDimens.space2)) {
            Text(model.displayName, style = IrisType.rowStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (extra != null) Text(extra, style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
            when {
                loaded -> Icon(Icons.Outlined.CheckCircle, "已加载", tint = c.accent, modifier = Modifier.size(IrisDimens.iconMedium))
                loading -> Box(Modifier.size(IrisDimens.space8).pulse(min = 0.3f).background(c.accent, androidx.compose.foundation.shape.CircleShape))
            }
        }
        if (onSettings != null) {
            com.iris.localai.ui.components.PressableIcon(
                Icons.Outlined.Tune, "后端与参数", iconSize = IrisDimens.iconMedium, tint = c.textSecondary, onClick = onSettings,
            )
        }
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

package com.iris.localai.ui.imagegen

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.data.Format
import com.iris.localai.imagegen.ImageDl
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.imagegen.SelfCheck
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.markdown.ChatImageLoader
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Size presets for models without a fixed size (width × height). */
internal val SIZE_PRESETS = listOf(512 to 512, 768 to 768, 1024 to 1024, 768 to 1024, 1024 to 768)

/** 2.1.7 生图: model tiers, prompts, parameters, result, history and 生图自检. [onSendToChat] gets the PNG path. */
@Composable
fun ImageGenScreen(onBack: () -> Unit, onSendToChat: (String) -> Unit, vm: ImageGenViewModel = viewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Leaving 生图 frees the NPU graphs (≈1.1 GB); generate_image keeps its own 60 s idle release.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.onLeave() } }
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    val logScope = androidx.compose.runtime.rememberCoroutineScope()
    // 2.4.2: copy the recent sd.cpp log (for a frozen / failed image) to the clipboard.
    fun copySdLog() = logScope.launch {
        val text = vm.sdLog()
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("IRIDIS sd log", text))
        toast("已复制生图日志，可直接发给开发者")
    }
    var deleting by remember { mutableStateOf<ImageModel?>(null) }
    deleting?.let { m ->
        ConfirmDialog(
            title = ImageGenText.DELETE_TITLE,
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_916ad2, m.name),
            confirmText = ImageGenText.DELETE,
            onConfirm = { vm.deleteModel(m) },
            onDismiss = { deleting = null },
        )
    }
    // 2.4.3: history manager state
    var viewing by remember { mutableStateOf<Int?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // drop selections of images that are gone
    androidx.compose.runtime.LaunchedEffect(s.history) {
        selected = selected.filter { it in s.history }.toSet()
        if (s.history.isEmpty()) { selecting = false; viewing = null }
    }
    viewing?.let { idx ->
        HistoryViewer(
            paths = s.history, index = idx, vm = vm,
            onIndex = { viewing = it }, onClose = { viewing = null }, onSendToChat = onSendToChat,
            onReused = { viewing = null; scope.launch { gridState.animateScrollToItem(0) } },
            onDelete = { p -> confirmDelete = listOf(p) },
        )
    }
    confirmDelete?.let { list ->
        ConfirmDialog(
            title = if (list.size == 1) HistoryText.DELETE_ONE_TITLE else HistoryText.deleteManyTitle(list.size),
            message = HistoryText.DELETE_ONE_MSG,
            confirmText = ImageGenText.DELETE,
            onConfirm = {
                val v = viewing
                // the viewer stays open on the neighbour (or closes after the last image)
                if (v != null && list.size == 1) viewing = if (s.history.size <= 1) null else v.coerceAtMost(s.history.size - 2)
                vm.deleteHistory(list)
                selected = selected - list.toSet()
                if (list.size > 1) selecting = false
            },
            onDismiss = { confirmDelete = null },
        )
    }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::pickReference) }

    SubpageScaffold(ImageGenText.TITLE, onBack, scrollUnder = false) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = IrisDimens.space32),
            horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
            verticalArrangement = Arrangement.spacedBy(IrisDimens.space6),
        ) {
            full { SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8000f1)) }
            full {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IrisDimens.groupGutter),
                    horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8),
                ) {
                    for (m in vm.catalog) ModelChip(m, selected = m.id == s.modelId, downloaded = m.id in s.downloaded,
                        dl = s.downloads[m.id],
                        onClick = { vm.selectModel(m) }, onDownload = { vm.download(m) },
                        onCancel = { vm.cancelDownload(m) }, onDelete = { deleting = m })
                }
            }

            full { SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_47b7af)) }
            full {
                SettingsGroup {
                    Field(s.prompt, ImageGenText.PROMPT, minLines = 3) { v -> vm.edit { it.copy(prompt = v) } }
                    Field(s.negative, ImageGenText.NEGATIVE, minLines = 2) { v -> vm.edit { it.copy(negative = v) } }
                }
            }

            full { SectionTitle(ImageGenText.REF_IMAGE) }
            full {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12), verticalAlignment = Alignment.CenterVertically) {
                        val ref = s.initImage
                        if (ref != null && s.img2imgSupported) Thumb(ref, Modifier.size(56.dp))
                        else Box(Modifier.size(56.dp).clip(IrisRadius.mediumShape).background(Iris.colors.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(IrisIcons.Image, null, tint = Iris.colors.textTertiary)
                        }
                        Spacer(Modifier.width(IrisDimens.space12))
                        IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_153fa6), onClick = { pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            style = IrisButtonStyle.Tonal, compact = true, enabled = s.img2imgSupported)
                        if (ref != null && s.img2imgSupported) {
                            Spacer(Modifier.width(IrisDimens.space8))
                            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_86048b), onClick = { vm.edit { it.copy(initImage = null) } }, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                    if (s.img2imgSupported) {
                        if (s.initImage != null) SliderRow(ImageGenText.STRENGTH, s.strength, 0f..1f, "%.2f".format(s.strength)) { v -> vm.edit { it.copy(strength = v) } }
                    }
                }
            }
            if (!s.img2imgSupported) full { GroupFootnote(ImageGenText.NO_IMG2IMG) }

            full { SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_3d0a2d)) }
            full {
                SettingsGroup {
                    val fixed = s.model.fixedSize
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
                        Text(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_c8339f), style = IrisType.row, color = Iris.colors.text)
                        Spacer(Modifier.height(IrisDimens.space6))
                        if (fixed != null) Text(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_a5cf72, fixed, fixed, ImageGenText.FIXED_SIZE), style = IrisType.secondary, color = Iris.colors.textSecondary)
                        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                            for ((w, h) in SIZE_PRESETS) Pill("$w×$h", selected = s.width == w && s.height == h) { vm.edit { it.copy(width = w, height = h) } }
                        }
                    }
                    SliderRow(ImageGenText.STEPS, s.steps.toFloat(), 1f..50f, s.steps.toString(), steps = 48) { v -> vm.edit { it.copy(steps = v.toInt()) } }
                    SliderRow(ImageGenText.CFG, s.cfg, 1f..20f, "%.1f".format(s.cfg), steps = 37) { v -> vm.edit { it.copy(cfg = (v * 2).toInt() / 2f) } }
                    Field(s.seed, ImageGenText.SEED, number = true) { v -> vm.edit { it.copy(seed = v.filter(Char::isDigit).take(18)) } }
                }
            }

            full {
                Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space12)) {
                    // 2.2.14: which model this generates with (and on what), always visible.
                    Text(
                        (if (s.running) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_b8656e) else com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_62227c)) + s.model.name + " · " + when (s.model.backend) {
                            com.iris.localai.imagegen.Backend.QNN -> "NPU"
                            com.iris.localai.imagegen.Backend.MNN_CPU -> "CPU"
                            com.iris.localai.imagegen.Backend.SDCPP -> if (s.model.sdq) "NPU" else if (s.sdOnCpu) "CPU" else "GPU"
                        } +
                            (if (s.modelId in s.downloaded) "" else com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_28f942)),
                        style = IrisType.caption, color = Iris.colors.accent,
                        modifier = Modifier.padding(bottom = IrisDimens.space6),
                    )
                    // 2.4.1: sd.cpp models — GPU or CPU for the next image, 「重试 GPU」 after a crash, 「改用 CPU」.
                    // 2.4.3: NPU models — only 「重试 NPU」 after the app died during an NPU run.
                    if (s.model.sdq && s.sdHtpCrashed && !s.running) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = IrisDimens.space6)) {
                            Text(com.iris.localai.imagegen.SdCppEngine.SDQ_CRASHED, style = IrisType.caption, color = Iris.colors.textSecondary, modifier = Modifier.weight(1f))
                            IrisButton("重试 NPU", onClick = vm::retryGpu, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                    if (s.model.backend == com.iris.localai.imagegen.Backend.SDCPP && !s.model.sdq && !s.running) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = IrisDimens.space6)) {
                            Text(
                                if (s.sdGpuCrashed) ImageGenText.SD_GPU_CRASHED else if (s.sdOnCpu) ImageGenText.SD_ON_CPU else ImageGenText.SD_ON_GPU,
                                style = IrisType.caption, color = Iris.colors.textSecondary, modifier = Modifier.weight(1f),
                            )
                            if (s.sdOnCpu) IrisButton(ImageGenText.RETRY_GPU, onClick = vm::retryGpu, style = IrisButtonStyle.Text, compact = true)
                            else IrisButton(ImageGenText.USE_CPU, onClick = vm::useCpu, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                    if (s.running) {
                        val sd = s.model.backend == com.iris.localai.imagegen.Backend.SDCPP
                        // 2.4.2: tick the elapsed time every second; sd.cpp phases (weights / step / decode) told apart.
                        val elapsed by androidx.compose.runtime.produceState(0L, s.startedAt) {
                            while (true) { value = ((System.currentTimeMillis() - s.startedAt) / 1000).coerceAtLeast(0); kotlinx.coroutines.delay(1000) }
                        }
                        if (sd) {
                            IrisProgressBar(com.iris.localai.imagegen.SdProgress.fraction(s.progress, s.steps))
                            Spacer(Modifier.height(IrisDimens.space6))
                            Text(com.iris.localai.imagegen.SdProgress.label(s.progress, s.steps, elapsed, !s.model.sdq && !s.sdOnCpu), style = IrisType.caption, color = Iris.colors.textSecondary)
                            if (elapsed >= com.iris.localai.imagegen.SdProgress.LOG_HINT_SEC) {
                                IrisButton("复制生图日志", onClick = { copySdLog() }, style = IrisButtonStyle.Text, compact = true)
                            }
                        } else {
                            IrisProgressBar(if (s.total > 0) s.step.toFloat() / s.total else null)
                            Spacer(Modifier.height(IrisDimens.space6))
                            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_1563a6, s.step, s.total), style = IrisType.caption, color = Iris.colors.textSecondary)
                        }
                        Spacer(Modifier.height(IrisDimens.space8))
                        IrisButton(ImageGenText.CANCEL, onClick = vm::cancel, modifier = Modifier.fillMaxWidth(), style = IrisButtonStyle.Tonal, icon = IrisIcons.Stop)
                    } else {
                        IrisButton(ImageGenText.GENERATE, onClick = vm::generate, modifier = Modifier.fillMaxWidth(), icon = IrisIcons.Spark, enabled = s.canGenerate)
                    }
                    s.error?.let {
                        Spacer(Modifier.height(IrisDimens.space8))
                        Text(it, style = IrisType.secondary, color = Iris.colors.danger)
                        if (s.model.backend == com.iris.localai.imagegen.Backend.SDCPP && it != ImageGenText.CANCELLED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (s.lowMem) IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.sd243_force), onClick = vm::generateAnyway, style = IrisButtonStyle.Text, compact = true)
                                IrisButton("复制生图日志", onClick = { copySdLog() }, style = IrisButtonStyle.Text, compact = true)
                            }
                        }
                    }
                }
            }

            s.result?.let { path ->
                full {
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter)) {
                        Thumb(path, Modifier.fillMaxWidth().aspectRatio(1f), maxSide = 1024, fit = true)
                        Spacer(Modifier.height(IrisDimens.space8))
                        Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                            IrisButton(ImageGenText.SAVE, icon = IrisIcons.Download, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                                val ok = runCatching { ChatImageLoader.saveToGallery(context, File(path).readBytes()) }.getOrDefault(false)
                                toast(if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_e8561b) else com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_6de920))
                            })
                            IrisButton(ImageGenText.SHARE, icon = IrisIcons.Share, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                                runCatching { ChatImageLoader.share(context, File(path).readBytes()) }
                            })
                            IrisButton(ImageGenText.TO_CHAT, icon = IrisIcons.Chat, style = IrisButtonStyle.Tonal, compact = true, onClick = { onSendToChat(path) })
                        }
                        Text(ImageGenFiles.IMAGES_SUB + "/" + File(path).name, style = IrisType.caption, color = Iris.colors.textTertiary, modifier = Modifier.padding(top = IrisDimens.space6))
                    }
                }
            }

            // 2.4.3: history manager — viewer with parameters, multi-select delete, 用这组参数.
            if (s.history.isNotEmpty()) {
                full {
                    HistoryHeader(
                        count = s.history.size, bytes = s.historyBytes, selecting = selecting, selectedCount = selected.size,
                        onManage = { selecting = true },
                        onToggleAll = { selected = if (selected.size == s.history.size) emptySet() else s.history.toSet() },
                        onDelete = { if (selected.isNotEmpty()) confirmDelete = selected.toList() },
                        onDone = { selecting = false; selected = emptySet() },
                    )
                }
                items(s.history, key = { it }) { p ->
                    HistoryTile(
                        p, selecting = selecting, selected = p in selected,
                        onClick = {
                            if (selecting) selected = if (p in selected) selected - p else selected + p
                            else viewing = s.history.indexOf(p)
                        },
                        onLongClick = { selecting = true; selected = selected + p },
                    )
                }
            }

            full { SectionTitle(ImageGenText.SELF_CHECK) }
            full { SelfCheckCard(s.report, onRun = vm::runSelfCheck) }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Field(value: String, label: String, minLines: Int = 1, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space6),
        label = { Text(label) }, minLines = minLines, singleLine = minLines == 1,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
    )
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Iris.colors
    Box(
        Modifier.clip(IrisRadius.pillShape)
            .background(if (selected) c.accentContainer else c.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
    ) { Text(text, style = IrisType.labelSmall, color = if (selected) c.onAccentContainer else c.textSecondary) }
}

/** Tier/model chip: name, size, 已下载/未下载 + 下载. */
@Composable
private fun ModelChip(
    m: ImageModel, selected: Boolean, downloaded: Boolean, dl: ImageDl?,
    onClick: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val c = Iris.colors
    Column(
        Modifier.widthIn(min = 150.dp, max = 190.dp)
            .clip(IrisRadius.largeShape)
            .background(if (selected) c.accentContainer else c.surfaceElevated)
            .border(1.dp, if (selected) c.onAccentContainer.copy(alpha = 0.25f) else c.outline, IrisRadius.largeShape)
            .clickable(onClick = onClick)
            .padding(IrisDimens.space12),
    ) {
        val fg = if (selected) c.onAccentContainer else c.text
        Text(tierLabel(m), style = IrisType.micro, color = c.textTertiary)
        Text(m.name, style = IrisType.labelSmall, color = fg, maxLines = 2)
        if (m.note.isNotEmpty()) Text(m.note, style = IrisType.micro, color = c.textTertiary, maxLines = 2)
        Spacer(Modifier.height(IrisDimens.space4))
        Text(ImageGenFiles.sizeBytes(m)?.let { Format.bytes(it) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_719560), style = IrisType.caption, color = c.textSecondary)
        Spacer(Modifier.height(IrisDimens.space4))
        when (dl) {
            is ImageDl.Downloading, is ImageDl.Extracting -> {
                val frac = if (dl is ImageDl.Downloading) dl.fraction else (dl as ImageDl.Extracting).fraction
                val label = if (dl is ImageDl.Downloading) "${Format.bytes(dl.done)} / ${Format.bytes(dl.total)}" + (if (dl.source.isNotEmpty()) " · ${dl.source}" else "")
                    else "${ImageGenText.EXTRACTING} ${(frac * 100).toInt()}%"
                IrisProgressBar(frac, Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = IrisType.caption, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                    IrisButton(ImageGenText.CANCEL, onClick = onCancel, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Close)
                }
            }
            else -> {
                if (dl is ImageDl.Failed) Text(dl.message, style = IrisType.caption, color = c.danger, maxLines = 5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (downloaded) ImageGenText.DOWNLOADED else ImageGenText.NOT_DOWNLOADED, style = IrisType.caption,
                        color = if (downloaded) c.success else c.textTertiary, modifier = Modifier.weight(1f))
                    if (!downloaded) IrisButton(if (dl is ImageDl.Failed) ImageGenText.RETRY else ImageGenText.DOWNLOAD, onClick = onDownload,
                        style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Download)
                }
                // 删除 also clears a partial download's leftovers
                if (downloaded || dl is ImageDl.Failed) IrisButton(ImageGenText.DELETE, onClick = onDelete, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Trash)
            }
        }
    }
}

private fun tierLabel(m: ImageModel): String = when (m.tier) {
    com.iris.localai.imagegen.Tier.LOW -> com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_1fa42a)
    com.iris.localai.imagegen.Tier.MID -> com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_fa11f8)
    com.iris.localai.imagegen.Tier.HIGH -> com.iris.localai.I18n.s(com.iris.localai.R.string.components_dfac15)
}

/** Decoded thumbnail of a local PNG/JPEG (off the main thread). */
@Composable
internal fun Thumb(path: String, modifier: Modifier, maxSide: Int = 320, fit: Boolean = false, plain: Boolean = false) {
    val c = Iris.colors
    val bmp by produceState<Bitmap?>(null, path, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { ChatImageLoader.decode(File(path).readBytes(), maxSide) }.getOrNull() }
    }
    // plain: the full-screen viewer (no tile background / corners)
    Box(if (plain) modifier else modifier.clip(IrisRadius.mediumShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
    }
}

@Composable
private fun SelfCheckCard(report: String?, onRun: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_82fc22), style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                IrisButton(ImageGenText.RUN, onClick = onRun, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Gauge)
                if (report != null) IrisButton(ImageGenText.COPY, compact = true, style = IrisButtonStyle.Text, icon = IrisIcons.Copy, onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_52e6ab), Toast.LENGTH_SHORT).show()
                })
            }
            if (report != null) {
                Spacer(Modifier.height(IrisDimens.space8))
                Text(report, style = IrisType.monoSmall, color = Iris.colors.text)
            }
        }
    }
}

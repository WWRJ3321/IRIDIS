package com.iris.localai.ui.imagegen

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageSeed
import com.iris.localai.imagegen.PromptTag
import com.iris.localai.imagegen.SdCppEngine
import com.iris.localai.imagegen.SdProgress
import com.iris.localai.imagegen.TagCat
import com.iris.localai.imagegen.Tier
import com.iris.localai.ui.components.AdvancedFold
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.SectionFold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.LocalReduceMotion
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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

/** 生图: model tiers, prompts, parameters, result, history and 生图自检. [onSendToChat] gets the PNG path. */
@Composable
fun ImageGenScreen(onBack: () -> Unit, onSendToChat: (String) -> Unit, vm: ImageGenViewModel = viewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Leaving 生图 frees the NPU graphs (≈1.1 GB); generate_image keeps its own 60 s idle release.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.onLeave() } }
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    val logScope = androidx.compose.runtime.rememberCoroutineScope()
    // copy the recent sd.cpp log (for a frozen / failed image) to the clipboard.
    fun copySdLog() = logScope.launch {
        val text = vm.sdLog()
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("IRIDIS sd log", text))
        toast("已复制生图日志，可直接发给开发者")
    }
    var deleting by remember { mutableStateOf<ImageModel?>(null) }
    // 导入 / 改名
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ImageModel?>(null) }
    if (importing) ImportDialog(vm, onDismiss = { importing = false })
    renaming?.let { m -> RenameDialog(m.name, onRename = { vm.renameImported(m, it) }, onDismiss = { renaming = null }) }
    deleting?.let { m ->
        ConfirmDialog(
            title = ImageGenText.DELETE_TITLE,
            message = I18n.s(R.string.imagegen_916ad2, m.name),
            confirmText = ImageGenText.DELETE,
            onConfirm = { vm.deleteModel(m) },
            onDismiss = { deleting = null },
        )
    }
    // history manager state
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
    // 非商用许可模型首次下载前确认
    s.licenseAsk?.let { id ->
        val m = ImageCatalog.byId(id)
        if (m != null) ConfirmDialog(
            title = I18n.s(R.string.ig268_license_title),
            message = I18n.s(R.string.ig268_license_msg, m.name, m.licenseUrl),
            confirmText = I18n.s(R.string.ig268_agree),
            onConfirm = { vm.acceptLicense(true) }, onDismiss = { vm.acceptLicense(false) }, destructive = false,
        )
    }
    // 标签库——添加 / 删除
    var addingTag by remember { mutableStateOf(false) }
    var removingTag by remember { mutableStateOf<PromptTag?>(null) }
    if (addingTag) TagAddDialog(s.tagCat, onAdd = { zh, en -> vm.addTag(s.tagCat, zh, en) }, onDismiss = { addingTag = false })
    removingTag?.let { t ->
        ConfirmDialog(
            title = I18n.s(R.string.ig268_del_tag, tagLabel(t)), message = t.text(s.model.tagStyle),
            confirmText = ImageGenText.DELETE, onConfirm = { vm.removeTag(t) }, onDismiss = { removingTag = null },
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
            full { SectionTitle(I18n.s(R.string.chat_8000f1)) }
            if (s.downloaded.isEmpty()) full { GroupFootnote(I18n.s(R.string.ig27_start)) }
            full {
                // 卡片等高（按最高的一张），状态行贴底
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IrisDimens.groupGutter)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8),
                ) {
                    val catalog = remember(s.catalogRev) { vm.catalog }
                    for (m in catalog) ModelChip(m, selected = m.id == s.modelId, downloaded = m.id in s.downloaded,
                        dl = s.downloads[m.id],
                        onClick = { vm.selectModel(m) }, onDownload = { vm.download(m) },
                        onCancel = { vm.cancelDownload(m) }, onDelete = { deleting = m }, onRename = { renaming = m })
                    ImportChip(s.importing, s.importFile, onClick = { importing = true })
                }
            }

            // 一屏讲清楚——模型 → 提示词 → 尺寸 → 生成；其余参数收进「高级」
            full { SectionTitle(I18n.s(R.string.imagegen_47b7af)) }
            full {
                SettingsGroup {
                    Field(s.prompt, ImageGenText.PROMPT, minLines = 3) { v -> vm.edit { it.copy(prompt = v) } }
                    // 标签库：分类 → 点标签追加，长按删除，「+」添加
                    TagRow(s, onCat = { c -> vm.edit { it.copy(tagCat = c) } }, onTag = vm::useTag, onLong = { removingTag = it },
                        onAdd = { addingTag = true }, onReset = vm::resetTags)
                    val fixed = s.model.fixedSize
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
                        Text(I18n.s(R.string.imagegen_c8339f), style = IrisType.row, color = Iris.colors.text)
                        Spacer(Modifier.height(IrisDimens.space6))
                        if (fixed != null) Text(I18n.s(R.string.imagegen_a5cf72, fixed, fixed, ImageGenText.FIXED_SIZE), style = IrisType.secondary, color = Iris.colors.textSecondary)
                        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                            for ((w, h) in SIZE_PRESETS) Pill("$w×$h", selected = s.width == w && s.height == h) { vm.edit { it.copy(width = w, height = h) } }
                        }
                    }
                    AdvancedFold("imagegen-adv") {
                        Field(s.negative, ImageGenText.NEGATIVE, minLines = 2) { v -> vm.edit { it.copy(negative = v) } }
                        SwitchRow(I18n.s(R.string.ig268_expand), s.expand,
                            subtitle = I18n.s(R.string.ig268_expand_sub)) { on -> vm.setExpand(on) }
                        SliderRow(ImageGenText.STEPS, s.steps.toFloat(), 1f..50f, s.steps.toString(), steps = 48) { v -> vm.edit { it.copy(steps = v.toInt()) } }
                        SliderRow(ImageGenText.CFG, s.cfg, 1f..20f, "%.1f".format(s.cfg), steps = 37) { v -> vm.edit { it.copy(cfg = (v * 2).toInt() / 2f) } }
                        // empty = a new random seed every time (hint in the field); 「随机」 clears a fixed seed.
                        Field(
                            s.seed, ImageGenText.SEED, number = true, placeholder = I18n.s(R.string.ig252_seed_hint),
                            trailingText = I18n.s(R.string.ig252_random),
                            onTrailing = if (ImageSeed.isRandom(s.seed)) null else ({ vm.edit { it.copy(seed = "") } }),
                        ) { v -> vm.edit { it.copy(seed = ImageSeed.sanitize(v)) } }
                        if (fixed == null) SwitchRow(I18n.s(R.string.ig268_lowmem), s.lowMemMode,
                            subtitle = I18n.s(R.string.ig268_lowmem_sub)) { on -> vm.setLowMem(on) }
                        // 参考图（图生图）：只在模型支持时出现
                        if (s.img2imgSupported) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12), verticalAlignment = Alignment.CenterVertically) {
                        val ref = s.initImage
                        if (ref != null && s.img2imgSupported) Thumb(ref, Modifier.size(56.dp))
                        else Box(Modifier.size(56.dp).clip(IrisRadius.mediumShape).background(Iris.colors.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(IrisIcons.Image, null, tint = Iris.colors.textTertiary)
                        }
                        Spacer(Modifier.width(IrisDimens.space12))
                        IrisButton(I18n.s(R.string.imagegen_153fa6), onClick = { pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            style = IrisButtonStyle.Tonal, compact = true, enabled = s.img2imgSupported)
                        if (ref != null && s.img2imgSupported) {
                            Spacer(Modifier.width(IrisDimens.space8))
                            IrisButton(I18n.s(R.string.imagegen_86048b), onClick = { vm.edit { it.copy(initImage = null) } }, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                    if (s.img2imgSupported) {
                        if (s.initImage != null) SliderRow(ImageGenText.STRENGTH, s.strength, 0f..1f, "%.2f".format(s.strength)) { v -> vm.edit { it.copy(strength = v) } }
                    }
                        }
                    }
                }
            }

            full {
                Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space12)) {
                    // which model this generates with (and on what), always visible.
                    Text(
                        (if (s.running) I18n.s(R.string.imagegen_b8656e) else I18n.s(R.string.imagegen_62227c)) + s.model.name + " · " + when (s.model.backend) {
                            Backend.QNN -> "NPU"
                            Backend.MNN_CPU -> "CPU"
                            Backend.SDCPP -> if (s.model.sdq) "NPU" else if (s.sdOnCpu) "CPU" else "GPU"
                        } +
                            (if (s.modelId in s.downloaded) "" else I18n.s(R.string.imagegen_28f942)),
                        style = IrisType.caption, color = Iris.colors.accent,
                        modifier = Modifier.padding(bottom = IrisDimens.space6),
                    )
                    if (s.expanding) Text(I18n.s(R.string.ig268_expanding), style = IrisType.caption, color = Iris.colors.textSecondary,
                        modifier = Modifier.padding(bottom = IrisDimens.space6))
                    // sd.cpp models — GPU or CPU for the next image, 「重试 GPU」 after a crash, 「改用 CPU」.
                    // NPU models — only 「重试 NPU」 after the app died during an NPU run.
                    if (s.model.sdq && s.sdHtpCrashed && !s.running) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = IrisDimens.space6)) {
                            Text(SdCppEngine.SDQ_CRASHED, style = IrisType.caption, color = Iris.colors.textSecondary, modifier = Modifier.weight(1f))
                            IrisButton("重试 NPU", onClick = vm::retryGpu, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                    if (s.model.backend == Backend.SDCPP && !s.model.sdq && !s.running) {
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
                        val sd = s.model.backend == Backend.SDCPP
                        // tick the elapsed time every second; sd.cpp phases (weights / step / decode) told apart.
                        val elapsed by androidx.compose.runtime.produceState(0L, s.startedAt) {
                            while (true) { value = ((System.currentTimeMillis() - s.startedAt) / 1000).coerceAtLeast(0); kotlinx.coroutines.delay(1000) }
                        }
                        if (sd) {
                            IrisProgressBar(SdProgress.fraction(s.progress, s.steps))
                            Spacer(Modifier.height(IrisDimens.space6))
                            Text(SdProgress.label(s.progress, s.steps, elapsed, !s.model.sdq && !s.sdOnCpu), style = IrisType.caption, color = Iris.colors.textSecondary)
                            if (elapsed >= SdProgress.LOG_HINT_SEC) {
                                IrisButton("复制生图日志", onClick = { copySdLog() }, style = IrisButtonStyle.Text, compact = true)
                            }
                        } else {
                            IrisProgressBar(if (s.total > 0) s.step.toFloat() / s.total else null)
                            Spacer(Modifier.height(IrisDimens.space6))
                            Text(I18n.s(R.string.imagegen_1563a6, s.step, s.total), style = IrisType.caption, color = Iris.colors.textSecondary)
                        }
                        Spacer(Modifier.height(IrisDimens.space8))
                        IrisButton(ImageGenText.CANCEL, onClick = vm::cancel, modifier = Modifier.fillMaxWidth(), style = IrisButtonStyle.Tonal, icon = IrisIcons.Stop)
                    } else {
                        IrisButton(ImageGenText.GENERATE, onClick = vm::generate, modifier = Modifier.fillMaxWidth(), icon = IrisIcons.Spark, enabled = s.canGenerate)
                    }
                    s.error?.let {
                        Spacer(Modifier.height(IrisDimens.space8))
                        Text(it, style = IrisType.secondary, color = Iris.colors.danger)
                        if (s.model.backend == Backend.SDCPP && it != ImageGenText.CANCELLED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (s.lowMem) IrisButton(I18n.s(R.string.sd243_force), onClick = vm::generateAnyway, style = IrisButtonStyle.Text, compact = true)
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
                                toast(if (ok) I18n.s(R.string.imagegen_e8561b) else I18n.s(R.string.imagegen_6de920))
                            })
                            IrisButton(ImageGenText.SHARE, icon = IrisIcons.Share, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                                runCatching { ChatImageLoader.share(context, File(path).readBytes()) }
                            })
                            IrisButton(ImageGenText.TO_CHAT, icon = IrisIcons.Chat, style = IrisButtonStyle.Tonal, compact = true, onClick = { onSendToChat(path) })
                        }
                        Text(ImageGenFiles.IMAGES_SUB + "/" + File(path).name, style = IrisType.caption, color = Iris.colors.textTertiary, modifier = Modifier.padding(top = IrisDimens.space6))
                        s.usedPrompt?.let { up -> Text(I18n.s(R.string.ig268_used, up), style = IrisType.caption, color = Iris.colors.textTertiary, maxLines = 4) }
                        // the seed this image used (the seed field keeps what the user typed); 「固定此种子」 copies it in.
                        s.lastSeed?.let { used ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(I18n.s(R.string.ig252_last_seed, used.toString()), style = IrisType.caption, color = Iris.colors.textTertiary, maxLines = 1, modifier = Modifier.weight(1f))
                                if (s.seed != used.toString()) IrisButton(I18n.s(R.string.ig252_fix_seed), onClick = { vm.edit { it.copy(seed = used.toString()) } }, style = IrisButtonStyle.Text, compact = true)
                            }
                        }
                    }
                }
            }

            // history manager — viewer with parameters, multi-select delete, 用这组参数.
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
                    // deleted tiles fade out, new ones fade in, the rest glide into place.
                    androidx.compose.foundation.layout.Box(if (LocalReduceMotion.current) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard()), propagateMinConstraints = true) {
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
            }

            full { SectionFold("imagegen-check", ImageGenText.SELF_CHECK) { SelfCheckCard(s.report, onRun = vm::runSelfCheck) } }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Field(
    value: String, label: String, minLines: Int = 1, number: Boolean = false,
    placeholder: String? = null, trailingText: String = "", onTrailing: (() -> Unit)? = null, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space6),
        label = { Text(label) }, minLines = minLines, singleLine = minLines == 1,
        placeholder = if (placeholder != null) { { Text(placeholder, maxLines = 1) } } else null,
        trailingIcon = if (onTrailing != null) { { IrisButton(trailingText, onClick = onTrailing, style = IrisButtonStyle.Text, compact = true) } } else null,
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
    onClick: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit = {},
) {
    val c = Iris.colors
    Column(
        Modifier.width(170.dp).fillMaxHeight()
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
        // 许可摘要 + 原文链接
        if (m.licenseNote.isNotEmpty()) {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Text(m.licenseNote + " · " + I18n.s(R.string.ig268_license_view), style = IrisType.micro, color = c.warning, maxLines = 2,
                modifier = Modifier.clickable { runCatching { uri.openUri(m.licenseUrl) } })
        }
        Spacer(Modifier.height(IrisDimens.space4))
        Spacer(Modifier.weight(1f))
        Text(if (m.imported) I18n.s(R.string.ig269_own_files)
            else ImageGenFiles.sizeBytes(m)?.let { Format.bytes(it) } ?: I18n.s(R.string.imagegen_719560), style = IrisType.caption, color = c.textSecondary)
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
                if (downloaded || dl is ImageDl.Failed || m.imported) Row {
                    if (m.imported) IrisButton(I18n.s(R.string.ig269_rename), onClick = onRename, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Edit)
                    IrisButton(ImageGenText.DELETE, onClick = onDelete, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Trash)
                }
            }
        }
    }
}

private fun tierLabel(m: ImageModel): String = if (m.imported) I18n.s(R.string.ig269_tier) else when (m.tier) {
    Tier.LOW -> I18n.s(R.string.imagegen_1fa42a)
    Tier.MID -> I18n.s(R.string.imagegen_fa11f8)
    Tier.HIGH -> I18n.s(R.string.components_dfac15)
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
            Text(I18n.s(R.string.imagegen_82fc22), style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                IrisButton(ImageGenText.RUN, onClick = onRun, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Gauge)
                if (report != null) IrisButton(ImageGenText.COPY, compact = true, style = IrisButtonStyle.Text, icon = IrisIcons.Copy, onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, I18n.s(R.string.chat_52e6ab), Toast.LENGTH_SHORT).show()
                })
            }
            if (report != null) {
                Spacer(Modifier.height(IrisDimens.space8))
                Text(report, style = IrisType.monoSmall, color = Iris.colors.text)
            }
        }
    }
}

/** 标签名（中文界面用中文名，其它语言用英文值）。 */
private fun tagLabel(t: PromptTag): String =
    if (I18n.uiLang().startsWith("zh")) t.zh else t.en

private fun catLabel(c: TagCat): String =
    if (I18n.uiLang().startsWith("zh")) c.zh else c.en

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TagRow(
    s: ImageGenUi, onCat: (TagCat) -> Unit, onTag: (PromptTag) -> Unit,
    onLong: (PromptTag) -> Unit, onAdd: () -> Unit, onReset: () -> Unit,
) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space6)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            for (cat in TagCat.entries) Pill(catLabel(cat), selected = cat == s.tagCat) { onCat(cat) }
        }
        Spacer(Modifier.height(IrisDimens.space6))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            for (t in s.tags.filter { it.cat == s.tagCat }) {
                Box(
                    Modifier.clip(IrisRadius.pillShape).border(1.dp, c.outline, IrisRadius.pillShape)
                        .combinedClickable(onClick = { onTag(t) }, onLongClick = { onLong(t) })
                        .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
                ) { Text(tagLabel(t), style = IrisType.labelSmall, color = if (t.custom) c.accent else c.textSecondary) }
            }
            Pill("+", selected = false, onClick = onAdd)
            Pill(I18n.s(R.string.ig268_reset_tags), selected = false, onClick = onReset)
        }
    }
}

@Composable
private fun TagAddDialog(cat: TagCat, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var zh by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    IrisDialog(
        onDismiss = onDismiss, title = I18n.s(R.string.ig268_add_tag) + " · " + catLabel(cat),
        confirmText = I18n.s(R.string.ig268_add_tag), confirmEnabled = en.isNotBlank(),
        onConfirm = { onAdd(zh, en); onDismiss() },
    ) {
        Column {
            OutlinedTextField(en, { en = it.take(80) }, label = { Text(I18n.s(R.string.ig268_tag_en)) }, singleLine = true,
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(zh, { zh = it.take(20) }, label = { Text(I18n.s(R.string.ig268_tag_zh)) }, singleLine = true,
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
        }
    }
}

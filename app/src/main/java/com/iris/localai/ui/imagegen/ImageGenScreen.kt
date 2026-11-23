package com.iris.localai.ui.imagegen
import androidx.compose.foundation.text.selection.SelectionContainer
import com.iris.localai.ui.components.InlineError
import com.iris.localai.ui.components.Fold
import com.iris.localai.ui.motion.RollingText

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.imagegen.AnimaMath
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageSeed
import com.iris.localai.imagegen.PromptTag
import androidx.compose.ui.text.style.TextOverflow
import com.iris.localai.ui.components.PressableIcon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.LazyColumn
import com.iris.localai.imagegen.PromptTags
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


/** 生图: model tiers, prompts, parameters, result, history and 生图自检. [onSendToChat] gets the PNG path. */
@Composable
fun ImageGenScreen(onBack: () -> Unit, onSendToChat: (String) -> Unit, vm: ImageGenViewModel = viewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Leaving 生图 frees the NPU graphs (≈1.1 GB); generate_image keeps its own 60 s idle release.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.iris.localai.service.ImageGenMonitor.pageVisible = true
        onDispose { com.iris.localai.service.ImageGenMonitor.pageVisible = false; vm.onLeave() }
    }
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
    var editingTag by remember { mutableStateOf<PromptTag?>(null) }
    var managingTags by remember { mutableStateOf(false) }
    val chatLoaded by vm.chatModelLoaded.collectAsStateWithLifecycle(initialValue = true)
    if (managingTags) TagManagerDialog(
        s.tags, start = s.tagCat, onEdit = { editingTag = it }, onDelete = { removingTag = it },
        onAdd = { addingTag = true }, onReset = vm::resetTags, onDismiss = { managingTags = false },
    )
    if (addingTag) TagEditDialog(null, s.tagCat, onSave = { cat, zh, en -> vm.addTag(cat, zh, en) }, onDismiss = { addingTag = false })
    editingTag?.let { t -> TagEditDialog(t, t.cat, onSave = { cat, zh, en -> vm.editTag(t, cat, zh, en) }, onDismiss = { editingTag = null }) }
    removingTag?.let { t ->
        ConfirmDialog(
            title = I18n.s(R.string.ig268_del_tag, tagLabel(t)), message = t.text(s.model.tagStyle),
            confirmText = ImageGenText.DELETE, onConfirm = { vm.removeTag(t) }, onDismiss = { removingTag = null },
        )
    }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::pickReference) }

    Box(Modifier.fillMaxSize()) {
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
                    // r5：提示词辅助——整理顺序（标签模型）、补画质词、填负面词
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = IrisDimens.rowPadding),
                        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                        if (s.model.tagStyle) Pill(I18n.s(R.string.r5_ig_arrange), selected = false, onClick = vm::arrangePrompt)
                        Pill(I18n.s(R.string.r5_ig_quality), selected = false, onClick = vm::addQuality)
                        Pill(I18n.s(R.string.r5_ig_negative), selected = s.negative.isNotBlank(), onClick = vm::addNegative)
                    }
                    // 标签库：分类 → 点标签追加，长按删除，「+」添加
                    TagRow(s, onCat = { c -> vm.edit { it.copy(tagCat = c) } }, onTag = vm::useTag, onLong = { removingTag = it },
                        onAdd = { addingTag = true }, onManage = { managingTags = true })
                    val fixed = s.model.fixedSize
                    // r5：尺寸按模型类型给（SD1.5 512 基、1024 基、NPU 包只列它支持的），比例 + 像素一枚胶囊，不再有说明小字
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
                        Text(I18n.s(R.string.imagegen_c8339f), style = IrisType.row, color = Iris.colors.text)
                        Spacer(Modifier.height(IrisDimens.space6))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                            for (o in s.sizeOptions) Pill(o.label, selected = s.width == o.width && s.height == o.height) { vm.edit { it.copy(width = o.width, height = o.height) } }
                        }
                    }
                    AdvancedFold("imagegen-adv") {
                        Field(s.negative, ImageGenText.NEGATIVE, minLines = 2) { v -> vm.edit { it.copy(negative = v) } }
                        // what it does, said right on the switch; without a loaded chat model it says so plainly.
                        SwitchRow(I18n.s(R.string.ig268_expand), s.expand,
                            subtitle = if (s.expand && !chatLoaded) I18n.s(R.string.ig27c_nomodel) else null) { on -> vm.setExpand(on) }
                        SliderRow(ImageGenText.STEPS, s.steps.toFloat(), 1f..50f, s.steps.toString(), steps = 48) { v -> vm.edit { it.copy(steps = v.toInt()) } }
                        SliderRow(ImageGenText.CFG, s.cfg, 1f..20f, "%.1f".format(s.cfg), steps = 37) { v -> vm.edit { it.copy(cfg = (v * 2).toInt() / 2f) } }
                        // Anima NPU：调度偏移，每个导入模型单独记
                        if (s.model.animaQnn && s.model.imported) SliderRow(
                            I18n.s(R.string.ig27e_shift), s.model.animaShift, AnimaMath.MIN_SHIFT..AnimaMath.MAX_SHIFT,
                            "%.1f".format(s.model.animaShift), steps = 9,
                        ) { v -> vm.setAnimaShift(s.model, v) }
                        // empty = a new random seed every time (hint in the field); 「随机」 clears a fixed seed.
                        Field(
                            s.seed, ImageGenText.SEED, number = true, placeholder = I18n.s(R.string.ig252_seed_hint),
                            trailingText = I18n.s(R.string.ig252_random),
                            onTrailing = if (ImageSeed.isRandom(s.seed)) null else ({ vm.edit { it.copy(seed = "") } }),
                        ) { v -> vm.edit { it.copy(seed = ImageSeed.sanitize(v)) } }
                        if (fixed == null) SwitchRow(I18n.s(R.string.ig268_lowmem), s.lowMemMode) { on -> vm.setLowMem(on) }
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

            // 2.7.1：LoRA（sd.cpp 模型；NPU 模型一句说明）
            full { SectionTitle(I18n.s(R.string.ig271_lora)) }
            full { LoraSection(s, vm) }

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
                    Fold(s.expanding) {
                        Text(I18n.s(R.string.ig268_expanding), style = IrisType.caption, color = Iris.colors.textSecondary,
                            modifier = Modifier.padding(bottom = IrisDimens.space6))
                    }
                    Fold(s.expandNoModel && !s.expanding) {
                        Text(I18n.s(R.string.ig27c_nomodel), style = IrisType.caption, color = Iris.colors.textSecondary,
                            modifier = Modifier.padding(bottom = IrisDimens.space6))
                    }
                    // sd.cpp models — GPU or CPU for the next image, 「重试 GPU」 after a crash, 「改用 CPU」.
                    // NPU models — only 「重试 NPU」 after the app died during an NPU run.
                    if (s.model.sdq && s.sdHtpCrashed && !s.running) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = IrisDimens.space6)) {
                            Text(SdCppEngine.SDQ_CRASHED, style = IrisType.caption, color = Iris.colors.textSecondary, modifier = Modifier.weight(1f))
                            IrisButton(I18n.s(R.string.b4r2_retry_npu), onClick = vm::retryGpu, style = IrisButtonStyle.Text, compact = true)
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
                        // 实时预览：每步的低清图（不跑 VAE）
                        s.preview?.let { f -> LivePreview(f); Spacer(Modifier.height(IrisDimens.space8)) }
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
                                IrisButton(I18n.s(R.string.v30_copy_sd_log), onClick = { copySdLog() }, style = IrisButtonStyle.Text, compact = true)
                            }
                        } else {
                            IrisProgressBar(if (s.total > 0) s.step.toFloat() / s.total else null)
                            Spacer(Modifier.height(IrisDimens.space6))
                            RollingText(I18n.s(R.string.imagegen_1563a6, s.step, s.total), style = IrisType.caption.copy(fontFeatureSettings = "tnum"), color = Iris.colors.textSecondary)
                        }
                        // 3.0 闲言碎语
                        com.iris.localai.ui.quips.IdleQuip(com.iris.localai.ui.quips.IdleQuips.Scene.IMAGE, active = true, modifier = Modifier.fillMaxWidth().padding(top = IrisDimens.space6))
                        Spacer(Modifier.height(IrisDimens.space8))
                        IrisButton(ImageGenText.CANCEL, onClick = vm::cancel, modifier = Modifier.fillMaxWidth(), style = IrisButtonStyle.Tonal, icon = IrisIcons.Stop)
                    } else {
                        IrisButton(ImageGenText.GENERATE, onClick = vm::generate, modifier = Modifier.fillMaxWidth(), icon = IrisIcons.Spark, enabled = s.canGenerate)
                    }
                    InlineError(s.error, Modifier.padding(top = IrisDimens.space8), style = IrisType.secondary)
                    Fold(s.error != null && s.model.backend == Backend.SDCPP && s.error != ImageGenText.CANCELLED) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.lowMem) IrisButton(I18n.s(R.string.sd243_force), onClick = vm::generateAnyway, style = IrisButtonStyle.Text, compact = true)
                            IrisButton(I18n.s(R.string.v30_copy_sd_log), onClick = { copySdLog() }, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                }
            }

            s.result?.let { path ->
                full(key = "result") {
                    Column(
                        (if (LocalReduceMotion.current) Modifier else Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard()))
                            .fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
                    ) {
                        // r5：点结果图直接进看图页（缩放 / 参数 / 保存 / 分享）
                        Thumb(path, Modifier.fillMaxWidth().aspectRatio(1f).clickable { s.history.indexOf(path).takeIf { it >= 0 }?.let { viewing = it } }, maxSide = 1024, fit = true)
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
                        s.usedPrompt?.let { up ->
                            SelectionContainer { Text(I18n.s(R.string.ig268_used, up), style = IrisType.caption, color = Iris.colors.textTertiary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                        }
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
                full(key = "historyHeader") {
                    Box(if (LocalReduceMotion.current) Modifier else Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard())) {
                        HistoryHeader(
                            count = s.history.size, bytes = s.historyBytes, selecting = selecting, selectedCount = selected.size,
                            onManage = { selecting = true },
                            onToggleAll = { selected = if (selected.size == s.history.size) emptySet() else s.history.toSet() },
                            onDelete = { if (selected.isNotEmpty()) confirmDelete = selected.toList() },
                            onDone = { selecting = false; selected = emptySet() },
                        )
                    }
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
    // Beta4 r3b：看图页改成本窗口内的全屏覆盖层（独立对话框窗口在 HyperOS 上顶部留白、底部被截）
    androidx.compose.animation.AnimatedVisibility(
        visible = viewing != null && s.history.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn(com.iris.localai.ui.motion.IrisMotion.Quick.float()),
        exit = androidx.compose.animation.fadeOut(com.iris.localai.ui.motion.IrisMotion.Quick.float()),
    ) {
        val last = remember { intArrayOf(0) }
        viewing?.let { last[0] = it }
        val idx = viewing ?: last[0]
        if (s.history.isNotEmpty())             HistoryViewer(
                paths = s.history, index = idx, vm = vm,
                onIndex = { viewing = it }, onClose = { viewing = null }, onSendToChat = onSendToChat,
                onReused = { viewing = null; scope.launch { gridState.animateScrollToItem(0) } },
                onDelete = { p -> confirmDelete = listOf(p) },
            )
    }
    }
}

private fun LazyGridScope.full(key: Any? = null, content: @Composable androidx.compose.foundation.lazy.grid.LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
internal fun Field(
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
            .background(com.iris.localai.ui.motion.selectTint(if (selected) c.accentContainer else c.surfaceVariant))
            .clickable(onClick = onClick)
            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
    ) { Text(text, style = IrisType.labelSmall, color = com.iris.localai.ui.motion.selectTint(if (selected) c.onAccentContainer else c.textSecondary)) }
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
            .background(com.iris.localai.ui.motion.selectTint(if (selected) c.accentContainer else c.surfaceElevated))
            .border(1.dp, com.iris.localai.ui.motion.selectTint(if (selected) c.onAccentContainer.copy(alpha = 0.25f) else c.outline), IrisRadius.largeShape)
            .clickable(onClick = onClick)
            .padding(IrisDimens.space12),
    ) {
        val fg = com.iris.localai.ui.motion.selectTint(if (selected) c.onAccentContainer else c.text)
        val busy = dl is ImageDl.Downloading || dl is ImageDl.Extracting
        // Beta3：导入的模型不再上下各写一遍「已导入」；下载中右上角一个 ✕ 取消
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (!m.imported) Text(tierLabel(m), style = IrisType.micro, color = c.textTertiary)
                Text(m.name, style = IrisType.labelSmall, color = fg, maxLines = 2)
            }
            if (busy) androidx.compose.material3.Icon(
                IrisIcons.Close, ImageGenText.CANCEL, tint = c.textSecondary,
                modifier = Modifier.size(40.dp).clip(IrisRadius.pillShape).clickable(onClick = onCancel).padding(11.dp),
            )
        }
        if (m.note.isNotEmpty()) Text(m.note, style = IrisType.micro, color = c.textTertiary, maxLines = 2)
        // 许可摘要 + 原文链接
        if (m.licenseNote.isNotEmpty()) {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Text(m.licenseNote + " · " + I18n.s(R.string.ig268_license_view), style = IrisType.micro, color = c.warning, maxLines = 2,
                modifier = Modifier.clickable { runCatching { uri.openUri(m.licenseUrl) } })
        }
        Spacer(Modifier.height(IrisDimens.space4))
        Spacer(Modifier.weight(1f))
        if (!busy) {
            val size = ImageGenFiles.sizeBytes(m)?.let { Format.bytes(it) }
            if (size != null || !m.imported) Text(size ?: I18n.s(R.string.imagegen_719560), style = IrisType.caption, color = c.textSecondary)
            Spacer(Modifier.height(IrisDimens.space4))
        }
        when (dl) {
            is ImageDl.Downloading, is ImageDl.Extracting -> {
                val frac = if (dl is ImageDl.Downloading) dl.fraction else (dl as ImageDl.Extracting).fraction
                val pct = "${(frac * 100).toInt()}%"
                val label = if (dl is ImageDl.Downloading) "$pct · ${DlSize.pair(dl.done, dl.total)}" else "${ImageGenText.EXTRACTING} $pct"
                IrisProgressBar(frac, Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4))
                RollingText(label, style = IrisType.caption.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
            }
            else -> {
                InlineError((dl as? ImageDl.Failed)?.message, maxLines = 5)
                if (!(m.imported && downloaded)) Row(verticalAlignment = Alignment.CenterVertically) {
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

/** Beta3「54 / 68 MB」：两个数用同一个单位，单位只写一次（纯函数，单测）。 */
internal object DlSize {
    fun pair(done: Long, total: Long): String {
        val t = total.coerceAtLeast(0L)
        val (div, unit) = when { t >= 1L shl 30 -> (1L shl 30).toDouble() to "GB"; t >= 1L shl 20 -> (1L shl 20).toDouble() to "MB"; else -> 1024.0 to "KB" }
        fun f(v: Long): String { val x = v / div; return if (unit == "GB") String.format(java.util.Locale.ROOT, "%.2f", x) else x.toLong().toString() }
        return "${f(done.coerceIn(0L, maxOf(t, done)))} / ${f(t)} $unit"
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
                SelectionContainer { Text(report, style = IrisType.monoSmall, color = Iris.colors.text) }
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
    onLong: (PromptTag) -> Unit, onAdd: () -> Unit, onManage: () -> Unit,
) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space6)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            for (cat in TagCat.entries) Pill(catLabel(cat), selected = cat == s.tagCat) { onCat(cat) }
        }
        Spacer(Modifier.height(IrisDimens.space6))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
            Pill("+", selected = false, onClick = onAdd)
            Pill(I18n.s(R.string.ig27c_manage), selected = false, onClick = onManage)
            for (t in s.tags.filter { it.cat == s.tagCat }) TagChip(t, onClick = { onTag(t) }, onLong = { onLong(t) })
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TagChip(t: PromptTag, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val c = Iris.colors
    Box(
        Modifier.clip(IrisRadius.pillShape).border(1.dp, c.outline, IrisRadius.pillShape)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
    ) { Text(tagLabel(t), style = IrisType.labelSmall, color = if (t.custom) c.accent else c.textSecondary, maxLines = 1) }
}

/** 词库管理：搜索 + 分类筛选，逐条编辑 / 删除，添加，恢复内置。 */
@Composable
private fun TagManagerDialog(
    tags: List<PromptTag>, start: TagCat, onEdit: (PromptTag) -> Unit, onDelete: (PromptTag) -> Unit,
    onAdd: () -> Unit, onReset: () -> Unit, onDismiss: () -> Unit,
) {
    val c = Iris.colors
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<TagCat?>(start) }
    val shown = remember(tags, cat, query) { PromptTags.filter(tags, cat, query) }
    IrisDialog(
        onDismiss = onDismiss, title = I18n.s(R.string.ig27c_manage),
        confirmText = I18n.s(R.string.agent_769d88), onConfirm = onDismiss, dismissText = null,
    ) {
        Column {
            OutlinedTextField(
                query, { query = it.take(40) }, singleLine = true,
                placeholder = { Text(I18n.s(R.string.ig27c_search), style = IrisType.secondary, color = c.textTertiary) },
                leadingIcon = { Icon(IrisIcons.Search, null, tint = c.textTertiary) },
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(IrisDimens.space8))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                Pill(I18n.s(R.string.ig27c_all), selected = cat == null) { cat = null }
                for (k in TagCat.entries) Pill(catLabel(k), selected = k == cat) { cat = k }
            }
            Spacer(Modifier.height(IrisDimens.space6))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.s(R.string.ig27c_count, shown.size), style = IrisType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                IrisButton(I18n.s(R.string.ig268_reset_tags), onClick = onReset, style = IrisButtonStyle.Text, compact = true)
                IrisButton(I18n.s(R.string.ig268_add_tag), onClick = onAdd, style = IrisButtonStyle.Tonal, compact = true)
            }
            if (shown.isEmpty()) Text(I18n.s(R.string.ig27c_none), style = IrisType.secondary, color = c.textTertiary,
                modifier = Modifier.padding(vertical = IrisDimens.space16))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                lazyItems(shown, key = { it.key }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space2), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tagLabel(t), style = IrisType.body, color = if (t.custom) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (cat == null) catLabel(t.cat) + " · " else "") + t.en, style = IrisType.caption, color = c.textTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PressableIcon(IrisIcons.Edit, I18n.s(R.string.ig27c_edit_tag), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { onEdit(t) }
                        PressableIcon(IrisIcons.Trash, I18n.s(R.string.chat_2f4aad), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { onDelete(t) }
                    }
                }
            }
        }
    }
}

/** 添加（[tag] == null）或编辑一个标签：分类 + 英文提示词 + 显示名。 */
@Composable
private fun TagEditDialog(tag: PromptTag?, cat0: TagCat, onSave: (TagCat, String, String) -> Unit, onDismiss: () -> Unit) {
    var zh by remember { mutableStateOf(if (tag == null || tag.zh == tag.en) "" else tag.zh) }
    var en by remember { mutableStateOf(tag?.en ?: "") }
    var cat by remember { mutableStateOf(cat0) }
    val c = Iris.colors
    IrisDialog(
        onDismiss = onDismiss, title = I18n.s(if (tag == null) R.string.ig268_add_tag else R.string.ig27c_edit_tag),
        confirmText = I18n.s(if (tag == null) R.string.ig268_add_tag else R.string.settings_be5fbb), confirmEnabled = en.isNotBlank(),
        onConfirm = { onSave(cat, zh, en); onDismiss() },
    ) {
        Column {
            Text(I18n.s(R.string.ig27c_cat), style = IrisType.caption, color = c.textSecondary)
            Spacer(Modifier.height(IrisDimens.space4))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                for (k in TagCat.entries) Pill(catLabel(k), selected = k == cat) { cat = k }
            }
            Spacer(Modifier.height(IrisDimens.space8))
            OutlinedTextField(en, { en = it.take(80) }, label = { Text(I18n.s(R.string.ig268_tag_en)) }, singleLine = true,
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(zh, { zh = it.take(20) }, label = { Text(I18n.s(R.string.ig268_tag_zh)) }, singleLine = true,
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
        }
    }
}

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
    var deleting by remember { mutableStateOf<ImageModel?>(null) }
    deleting?.let { m ->
        ConfirmDialog(
            title = ImageGenText.DELETE_TITLE,
            message = "删除「${m.name}」的全部模型文件（含未下载完的部分）？",
            confirmText = ImageGenText.DELETE,
            onConfirm = { vm.deleteModel(m) },
            onDismiss = { deleting = null },
        )
    }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::pickReference) }

    SubpageScaffold(ImageGenText.TITLE, onBack, scrollUnder = false) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = IrisDimens.space32),
            horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
            verticalArrangement = Arrangement.spacedBy(IrisDimens.space6),
        ) {
            full { SectionTitle("模型") }
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

            full { SectionTitle("提示词") }
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
                        IrisButton("选择", onClick = { pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            style = IrisButtonStyle.Tonal, compact = true, enabled = s.img2imgSupported)
                        if (ref != null && s.img2imgSupported) {
                            Spacer(Modifier.width(IrisDimens.space8))
                            IrisButton("移除", onClick = { vm.edit { it.copy(initImage = null) } }, style = IrisButtonStyle.Text, compact = true)
                        }
                    }
                    if (s.img2imgSupported) {
                        if (s.initImage != null) SliderRow(ImageGenText.STRENGTH, s.strength, 0f..1f, "%.2f".format(s.strength)) { v -> vm.edit { it.copy(strength = v) } }
                    }
                }
            }
            if (!s.img2imgSupported) full { GroupFootnote(ImageGenText.NO_IMG2IMG) }

            full { SectionTitle("参数") }
            full {
                SettingsGroup {
                    val fixed = s.model.fixedSize
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
                        Text("尺寸", style = IrisType.row, color = Iris.colors.text)
                        Spacer(Modifier.height(IrisDimens.space6))
                        if (fixed != null) Text("$fixed × $fixed（${ImageGenText.FIXED_SIZE}）", style = IrisType.secondary, color = Iris.colors.textSecondary)
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
                    if (s.running) {
                        IrisProgressBar(if (s.total > 0) s.step.toFloat() / s.total else null)
                        Spacer(Modifier.height(IrisDimens.space6))
                        Text("第 ${s.step}/${s.total} 步", style = IrisType.caption, color = Iris.colors.textSecondary)
                        Spacer(Modifier.height(IrisDimens.space8))
                        IrisButton(ImageGenText.CANCEL, onClick = vm::cancel, modifier = Modifier.fillMaxWidth(), style = IrisButtonStyle.Tonal, icon = IrisIcons.Stop)
                    } else {
                        IrisButton(ImageGenText.GENERATE, onClick = vm::generate, modifier = Modifier.fillMaxWidth(), icon = IrisIcons.Spark, enabled = s.canGenerate)
                    }
                    s.error?.let {
                        Spacer(Modifier.height(IrisDimens.space8))
                        Text(it, style = IrisType.secondary, color = Iris.colors.danger)
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
                                toast(if (ok) "已保存到 相册/Pictures/IRIDIS" else "保存失败")
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

            if (s.history.isNotEmpty()) {
                full { SectionTitle(ImageGenText.HISTORY) }
                items(s.history, key = { it }) { p ->
                    Thumb(p, Modifier.padding(horizontal = IrisDimens.space2).aspectRatio(1f).clickable { vm.showResult(p) })
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

/** Tier/model chip: name, size, 已下载/未下载 + 下载; Hexagon models disabled with [SelfCheck.HEXAGON_PENDING]. */
@Composable
private fun ModelChip(
    m: ImageModel, selected: Boolean, downloaded: Boolean, dl: ImageDl?,
    onClick: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val c = Iris.colors
    val enabled = !m.needsHexagonRuntime
    val alpha = if (enabled) 1f else 0.45f
    Column(
        Modifier.widthIn(min = 150.dp, max = 190.dp)
            .clip(IrisRadius.largeShape)
            .background(if (selected) c.accentContainer else c.surfaceElevated)
            .border(1.dp, if (selected) c.onAccentContainer.copy(alpha = 0.25f) else c.outline, IrisRadius.largeShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(IrisDimens.space12),
    ) {
        val fg = if (selected) c.onAccentContainer else c.text
        Text(tierLabel(m), style = IrisType.micro, color = c.textTertiary.copy(alpha = alpha))
        Text(m.name, style = IrisType.labelSmall, color = fg.copy(alpha = alpha), maxLines = 2)
        if (m.note.isNotEmpty()) Text(m.note, style = IrisType.micro, color = c.textTertiary.copy(alpha = alpha), maxLines = 2)
        Spacer(Modifier.height(IrisDimens.space4))
        Text(ImageGenFiles.sizeBytes(m)?.let { Format.bytes(it) } ?: "大小待定", style = IrisType.caption, color = c.textSecondary.copy(alpha = alpha))
        Spacer(Modifier.height(IrisDimens.space4))
        if (!enabled) {
            Text("「${SelfCheck.HEXAGON_PENDING}」", style = IrisType.caption, color = c.textTertiary)
        } else when (dl) {
            is ImageDl.Downloading, is ImageDl.Extracting -> {
                val frac = if (dl is ImageDl.Downloading) dl.fraction else (dl as ImageDl.Extracting).fraction
                val label = if (dl is ImageDl.Downloading) "${Format.bytes(dl.done)} / ${Format.bytes(dl.total)}"
                    else "${ImageGenText.EXTRACTING} ${(frac * 100).toInt()}%"
                IrisProgressBar(frac, Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = IrisType.caption, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                    IrisButton(ImageGenText.CANCEL, onClick = onCancel, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Close)
                }
            }
            else -> {
                if (dl is ImageDl.Failed) Text(dl.message, style = IrisType.caption, color = c.danger, maxLines = 3)
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
    com.iris.localai.imagegen.Tier.LOW -> "入门"
    com.iris.localai.imagegen.Tier.MID -> "进阶"
    com.iris.localai.imagegen.Tier.HIGH -> "高级"
}

/** Decoded thumbnail of a local PNG/JPEG (off the main thread). */
@Composable
private fun Thumb(path: String, modifier: Modifier, maxSide: Int = 320, fit: Boolean = false) {
    val c = Iris.colors
    val bmp by produceState<Bitmap?>(null, path, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { ChatImageLoader.decode(File(path).readBytes(), maxSide) }.getOrNull() }
    }
    Box(modifier.clip(IrisRadius.mediumShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
    }
}

@Composable
private fun SelfCheckCard(report: String?, onRun: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
            Text("检测芯片、NPU 运行库与内存，判断每个模型能否在本机运行。", style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                IrisButton(ImageGenText.RUN, onClick = onRun, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Gauge)
                if (report != null) IrisButton(ImageGenText.COPY, compact = true, style = IrisButtonStyle.Text, icon = IrisIcons.Copy, onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                })
            }
            if (report != null) {
                Spacer(Modifier.height(IrisDimens.space8))
                Text(report, style = IrisType.monoSmall, color = Iris.colors.text)
            }
        }
    }
}

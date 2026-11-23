package com.iris.localai.ui.imagegen
import com.iris.localai.ui.components.InlineError
import com.iris.localai.ui.motion.RollingText
import androidx.compose.ui.text.style.TextOverflow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.imagegen.FileRole
import com.iris.localai.imagegen.ImportedImages
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.components.irisTextFieldColors
import kotlinx.coroutines.launch

/** 模型行末尾的「导入模型」卡片（与 ModelChip 同尺寸同样式）；导入中显示进度。 */
@Composable
internal fun ImportChip(progress: Float?, file: String, onClick: () -> Unit) {
    val c = Iris.colors
    Column(
        Modifier.width(170.dp).fillMaxHeight()
            .clip(IrisRadius.largeShape)
            .background(c.surfaceElevated)
            .border(1.dp, c.outline, IrisRadius.largeShape)
            .clickable(enabled = progress == null, onClick = onClick)
            .padding(IrisDimens.space12),
    ) {
        Icon(IrisIcons.Plus, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(IrisDimens.space4))
        Text(I18n.s(R.string.ig269_import), style = IrisType.labelSmall, color = c.text, maxLines = 1)
        Text(I18n.s(R.string.ig269_import_note), style = IrisType.micro, color = c.textTertiary, maxLines = 3)
        Spacer(Modifier.weight(1f))
        if (progress != null) {
            IrisProgressBar(progress, Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RollingText("${(progress * 100).toInt()}%", style = IrisType.caption.copy(fontFeatureSettings = "tnum"), color = c.textSecondary)
                Text(" · $file", style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 选文件的一行：标题 + 当前选择（或「复用已下载」）+ 按钮。 */
@Composable
private fun PickRow(title: String, value: String, ok: Boolean, buttonText: String, onPick: () -> Unit, onClear: (() -> Unit)? = null, clearText: String = I18n.s(R.string.ig269_reuse)) {
    val c = Iris.colors
    Row(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.caption, color = c.textSecondary)
            Text(value, style = IrisType.labelSmall, color = if (ok) c.text else c.warning, maxLines = 2)
        }
        if (onClear != null) IrisButton(clearText, onClick = onClear, style = IrisButtonStyle.Text, compact = true)
        IrisButton(buttonText, onClick = onPick, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.File)
    }
}

@Composable
internal fun ImportDialog(vm: ImageGenViewModel, onDismiss: () -> Unit) {
    val c = Iris.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var plan by remember { mutableStateOf<ImageGenViewModel.ImportPlan?>(null) }
    var steps by remember { mutableStateOf("") }
    var cfg by remember { mutableStateOf("") }
    // 一次选全部文件（主模型、文本编码器、VAE，或 NPU 包），读文件头自动识别各自用途
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) scope.launch {
            uris = (uris + picked).distinct()
            val p = vm.inspect(uris)
            plan = p
            steps = p.defaults.steps.toString(); cfg = p.defaults.cfg.toString()
            if (name.isBlank()) p.files.firstOrNull { it.first == p.main }?.let { name = ImportedImages.nameFromFile(it.second) }
        }
    }
    val p = plan
    IrisDialog(
        onDismiss = onDismiss, title = I18n.s(R.string.ig269_import),
        confirmText = I18n.s(R.string.ig269_do_import), confirmEnabled = p != null && p.error == null && (p.npu || p.main != null),
        onConfirm = {
            p?.let { vm.importAuto(it, name, steps.toIntOrNull() ?: it.defaults.steps, cfg.toFloatOrNull() ?: it.defaults.cfg) }
            onDismiss()
        },
    ) {
        Column {
            Text(I18n.s(R.string.ig27_hint), style = IrisType.caption, color = c.textTertiary)
            Spacer(Modifier.height(IrisDimens.space8))
            PickRow(I18n.s(R.string.ig27_files), if (uris.isEmpty()) I18n.s(R.string.ig269_none) else I18n.s(R.string.ig270_files_val, uris.size),
                p != null && p.error == null, I18n.s(R.string.ig269_pick), onPick = { runCatching { pickMany.launch(arrayOf("*/*")) } },
                onClear = if (uris.isNotEmpty()) { { uris = emptyList(); plan = null } } else null, clearText = I18n.s(R.string.ig27_clear))
            if (p != null) {
                if (p.npu) Text(I18n.s(R.string.ig27_npu), style = IrisType.caption, color = c.textSecondary)
                else for ((_, n, r) in p.files) Text("${roleText(r)} · $n", style = IrisType.caption, color = c.textSecondary, maxLines = 1)
                if (p.reuseLlm.isNotEmpty()) Text("${I18n.s(R.string.ig269_te)} · ${I18n.s(R.string.ig269_downloaded, p.reuseLlm.substringAfterLast('/'))}", style = IrisType.caption, color = c.textSecondary, maxLines = 1)
                if (p.reuseVae.isNotEmpty()) Text("${I18n.s(R.string.ig269_vae)} · ${I18n.s(R.string.ig269_downloaded, p.reuseVae.substringAfterLast('/'))}", style = IrisType.caption, color = c.textSecondary, maxLines = 1)
                InlineError(p.error, color = c.warning)
                Spacer(Modifier.height(IrisDimens.space8))
            }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(I18n.s(R.string.ig269_name)) }, singleLine = true,
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
            if (p != null) Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                OutlinedTextField(steps, { steps = it.filter(Char::isDigit).take(3) }, label = { Text(ImageGenText.STEPS) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.weight(1f))
                if (!p.npu) OutlinedTextField(cfg, { cfg = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) }, label = { Text(ImageGenText.CFG) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun roleText(r: FileRole): String = when (r) {
    FileRole.CHECKPOINT, FileRole.DIFFUSION -> I18n.s(R.string.ig269_main)
    FileRole.LLM, FileRole.T5, FileRole.CLIP_L, FileRole.CLIP_G -> I18n.s(R.string.ig269_te)
    FileRole.VAE -> I18n.s(R.string.ig269_vae)
    FileRole.NPU -> I18n.s(R.string.ig27_npu)
    FileRole.UNKNOWN -> "?"
}

@Composable
internal fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    IrisDialog(onDismiss = onDismiss, title = I18n.s(R.string.ig269_rename), confirmText = I18n.s(R.string.ig269_rename),
        confirmEnabled = name.isNotBlank(), onConfirm = { onRename(name); onDismiss() }) {
        OutlinedTextField(name, { name = it.take(40) }, singleLine = true,
            shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
    }
}

package com.iris.localai.ui.imagegen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.iris.localai.imagegen.ImportArch
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

/** 2.6.9: 模型行末尾的「导入模型」卡片（与 ModelChip 同尺寸同样式）；导入中显示进度。 */
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
            Text("${(progress * 100).toInt()}% · $file", style = IrisType.caption, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** 选文件的一行：标题 + 当前选择（或「复用已下载」）+ 按钮。 */
@Composable
private fun PickRow(title: String, value: String, ok: Boolean, buttonText: String, onPick: () -> Unit, onClear: (() -> Unit)? = null) {
    val c = Iris.colors
    Row(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.caption, color = c.textSecondary)
            Text(value, style = IrisType.labelSmall, color = if (ok) c.text else c.warning, maxLines = 2)
        }
        if (onClear != null) IrisButton(I18n.s(R.string.ig269_reuse), onClick = onClear, style = IrisButtonStyle.Text, compact = true)
        IrisButton(buttonText, onClick = onPick, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.File)
    }
}

@Composable
internal fun ImportDialog(vm: ImageGenViewModel, onDismiss: () -> Unit) {
    val c = Iris.colors
    val scope = rememberCoroutineScope()
    var arch by remember { mutableStateOf(ImportArch.ANIMA) }
    var name by remember { mutableStateOf("") }
    var main by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var llm by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var vae by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var steps by remember { mutableStateOf(arch.steps.toString()) }
    var cfg by remember { mutableStateOf(arch.cfg.toString()) }
    var have by remember { mutableStateOf(false to false) }
    LaunchedEffect(arch) { have = vm.reusable(arch); steps = arch.steps.toString(); cfg = arch.cfg.toString() }
    var target by remember { mutableStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val n = vm.displayName(uri)
            when (target) {
                0 -> { main = uri to n; if (name.isBlank()) name = ImportedImages.nameFromFile(n) }
                1 -> llm = uri to n
                else -> vae = uri to n
            }
        }
    }
    fun open(t: Int) { target = t; runCatching { pick.launch(arrayOf("*/*")) } }
    val llmOk = llm != null || have.first
    val vaeOk = vae != null || have.second
    val mainOk = main?.second?.let(ImportedImages::acceptable) == true
    IrisDialog(
        onDismiss = onDismiss, title = I18n.s(R.string.ig269_import),
        confirmText = I18n.s(R.string.ig269_do_import), confirmEnabled = mainOk && llmOk && vaeOk,
        onConfirm = {
            main?.let { m -> vm.importModel(arch, name, m.first, llm?.first, vae?.first, steps.toIntOrNull() ?: arch.steps, cfg.toFloatOrNull() ?: arch.cfg) }
            onDismiss()
        },
    ) {
        Column {
            Text(I18n.s(R.string.ig269_hint), style = IrisType.caption, color = c.textTertiary)
            Spacer(Modifier.height(IrisDimens.space8))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                for (a in ImportArch.entries) ArchPill(a.label, a == arch) { arch = a; llm = null; vae = null }
            }
            Spacer(Modifier.height(IrisDimens.space8))
            PickRow(I18n.s(R.string.ig269_main), main?.second ?: I18n.s(R.string.ig269_none), mainOk, I18n.s(R.string.ig269_pick), onPick = { open(0) })
            PickRow(I18n.s(R.string.ig269_te), llm?.second ?: if (have.first) I18n.s(R.string.ig269_downloaded, arch.llmPath.substringAfterLast('/')) else I18n.s(R.string.ig269_none),
                llmOk, I18n.s(R.string.ig269_pick), onPick = { open(1) }, onClear = if (llm != null && have.first) { { llm = null } } else null)
            PickRow(I18n.s(R.string.ig269_vae), vae?.second ?: if (have.second) I18n.s(R.string.ig269_downloaded, arch.vaePath.substringAfterLast('/')) else I18n.s(R.string.ig269_none),
                vaeOk, I18n.s(R.string.ig269_pick), onPick = { open(2) }, onClear = if (vae != null && have.second) { { vae = null } } else null)
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(I18n.s(R.string.ig269_name)) }, singleLine = true,
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                OutlinedTextField(steps, { steps = it.filter(Char::isDigit).take(3) }, label = { Text(ImageGenText.STEPS) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.weight(1f))
                OutlinedTextField(cfg, { cfg = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) }, label = { Text(ImageGenText.CFG) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ArchPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Iris.colors
    Text(text, style = IrisType.labelSmall, color = if (selected) c.onAccentContainer else c.textSecondary,
        modifier = Modifier.clip(IrisRadius.pillShape).background(if (selected) c.accentContainer else c.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6))
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

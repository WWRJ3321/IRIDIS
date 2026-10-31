package com.iris.localai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedRow
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.voice.ClonedVoice
import com.iris.localai.voice.PackState
import com.iris.localai.voice.VoicePack
import java.util.Locale

/**
 * 设置 › 语音 › 克隆音色: ZipVoice model, saved voices (pick / 试听 / delete), a new voice from a
 * recording or an audio file, and the 快 / 像 trade-off.
 */
@Composable
internal fun CloneVoiceSection(vm: SettingsViewModel) {
    val c = Iris.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val packs by vm.voicePacks.collectAsStateWithLifecycle()
    val voices by vm.cloneVoices.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val previewTarget by vm.previewTarget.collectAsStateWithLifecycle()
    val pack = VoicePack.CLONE_ZIPVOICE
    val state = packs[pack] ?: PackState.NotInstalled
    val installed = state == PackState.Installed
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ClonedVoice?>(null) }

    SectionTitle("克隆音色")
    SettingsGroup {
        PackRow(
            pack, state, vm.packBytes(pack), selected = installed && s.cloneVoiceId.isNotEmpty(),
            onUse = {}, onDownload = { vm.download(pack) }, onCancel = { vm.cancelDownload(pack) }, onDelete = { vm.deletePack(pack) },
        )
        if (installed) {
            Divider()
            VoiceChoiceRow(
                title = "不用克隆音色", subtitle = "使用上面选中的朗读模型",
                selected = s.cloneVoiceId.isEmpty() || voices.none { it.id == s.cloneVoiceId },
                onSelect = { vm.useClone("") },
            )
            voices.forEach { v ->
                Divider()
                val playing = previewTarget == v.id && preview != SettingsViewModel.Preview.Idle && preview !is SettingsViewModel.Preview.Failed
                VoiceChoiceRow(
                    title = v.name,
                    subtitle = if (previewTarget == v.id && preview is SettingsViewModel.Preview.Failed)
                        (preview as SettingsViewModel.Preview.Failed).message
                    else String.format(Locale.US, "%.1f 秒 · ", v.durationMs / 1000f) + v.text,
                    selected = s.cloneVoiceId == v.id,
                    onSelect = { vm.useClone(v.id) },
                ) {
                    if (playing && preview == SettingsViewModel.Preview.Loading) TypingDots(Modifier.padding(horizontal = IrisDimens.space8))
                    else PressableIcon(
                        if (playing) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow, if (playing) "停止" else "试听",
                        iconSize = 22.dp, tint = c.text,
                    ) { vm.previewVoice(v) }
                    PressableIcon(Icons.Outlined.DeleteOutline, "删除", iconSize = IrisDimens.space20, tint = c.textSecondary) { deleting = v }
                }
            }
            Divider()
            SettingsRow("新建克隆音色", subtitle = "录 5–15 秒自己的声音，或选一个音频文件", titleColor = c.accent, onClick = { creating = true })
        }
    }

    if (installed) {
        SectionTitle("克隆音色：快 / 像")
        SettingsGroup {
            SegmentedRow(
                "生成方式",
                options = listOf("快", "像"),
                selectedIndex = s.cloneFidelity,
                onSelect = { i -> vm.update { it.copy(cloneFidelity = i) } },
                subtitle = if (s.cloneFidelity == 0) "快：步数少，开口快，音色略差一点"
                else "像：步数多，更像参考声音，但每句开口更慢（约为「快」的 2–3 倍）",
            )
            Divider()
            if (s.cloneFidelity == 0) {
                SliderRow("步数", s.cloneStepsFast.toFloat(), 2f..8f, "${s.cloneStepsFast}", subtitle = "默认 4", steps = 5) { v ->
                    vm.update { it.copy(cloneStepsFast = v.toInt()) }
                }
            } else {
                SliderRow("步数", s.cloneStepsFine.toFloat(), 6f..24f, "${s.cloneStepsFine}", subtitle = "默认 12", steps = 17) { v ->
                    vm.update { it.copy(cloneStepsFine = v.toInt()) }
                }
            }
        }
    }

    if (creating) CloneDialog(vm, onClose = { creating = false; vm.discardCloneDraft() }, onSaved = { creating = false })
    deleting?.let { v ->
        ConfirmDialog(
            title = "删除音色「${v.name}」？", message = "参考录音会一起删除。", confirmText = "删除",
            onConfirm = { vm.deleteClone(v.id) }, onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun VoiceChoiceRow(
    title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(start = IrisDimens.space4, end = IrisDimens.space4, top = IrisDimens.space6, bottom = IrisDimens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.iris.localai.ui.components.IrisRadio(selected)
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.rowStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = IrisType.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun CloneDialog(vm: SettingsViewModel, onClose: () -> Unit, onSaved: () -> Unit) {
    val c = Iris.colors
    val context = LocalContext.current
    val draft by vm.cloneDraft.collectAsStateWithLifecycle()
    val voices by vm.cloneVoices.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var textFor by remember { mutableStateOf<Any?>(null) }
    val ready = draft as? SettingsViewModel.CloneDraft.Ready
    // Fill the transcript once per new clip; the user's edits are kept afterwards.
    if (ready != null && textFor !== ready) { textFor = ready; text = ready.text }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startCloneRecording() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importCloneAudio) }

    IrisDialog(
        onDismiss = onClose,
        title = "新建克隆音色",
        confirmText = "保存并使用",
        confirmEnabled = ready != null && text.isNotBlank(),
        onConfirm = {
            vm.saveClone(name.ifBlank { "我的音色 ${voices.size + 1}" }, text)
            onSaved()
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "在安静的地方，用平常的语气连续说 5–15 秒（例如读一段文章）。声音会保存在本机，只用于本机合成。",
                style = IrisType.secondary, color = c.textSecondary,
            )
            when (val d = draft) {
                is SettingsViewModel.CloneDraft.Recording -> {
                    Text(String.format(Locale.US, "录音中 %.1f / 15 秒", d.seconds), style = IrisType.rowStrong, color = c.text)
                    IrisProgressBar(progress = (d.seconds / 15f).coerceIn(0f, 1f))
                    IrisButton("停止录音", vm::stopCloneRecording, icon = Icons.Outlined.StopCircle, modifier = Modifier.fillMaxWidth())
                }
                is SettingsViewModel.CloneDraft.Processing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypingDots(); Spacer(Modifier.width(IrisDimens.space8))
                        Text(d.message, style = IrisType.secondary, color = c.text)
                    }
                }
                else -> {
                    if (d is SettingsViewModel.CloneDraft.Failed) Text(d.message, style = IrisType.secondary, color = c.danger)
                    Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                        IrisButton(
                            if (ready != null) "重录" else "录音", {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startCloneRecording()
                                else mic.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            icon = Icons.Outlined.Mic, style = if (ready != null) IrisButtonStyle.Outline else IrisButtonStyle.Primary,
                            modifier = Modifier.weight(1f),
                        )
                        IrisButton(
                            "选音频文件", { pick.launch(arrayOf("audio/*")) }, icon = Icons.Outlined.AudioFile,
                            style = IrisButtonStyle.Outline, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (ready != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, "样本 %.1f 秒", ready.seconds), style = IrisType.rowStrong, color = c.text, modifier = Modifier.weight(1f))
                    IrisButton("听一下", vm::playDraft, icon = Icons.Outlined.PlayArrow, style = IrisButtonStyle.Text, compact = true)
                }
                ready.note?.let { Text(it, style = IrisType.caption, color = c.textTertiary) }
                OutlinedTextField(
                    text, { text = it }, label = { Text("参考文本（这段录音说了什么）") },
                    textStyle = IrisType.body.copy(color = c.text), shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("由语音识别自动填写，有错字请改正：越准，克隆越像", style = IrisType.caption) },
                )
                OutlinedTextField(
                    name, { name = it.take(20) }, label = { Text("音色名称") }, singleLine = true,
                    placeholder = { Text("我的音色 ${voices.size + 1}") },
                    textStyle = IrisType.body.copy(color = c.text), shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(IrisDimens.space2))
        }
    }
}

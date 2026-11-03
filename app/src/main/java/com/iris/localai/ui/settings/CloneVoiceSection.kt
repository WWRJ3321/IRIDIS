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

    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2c548f))
    SettingsGroup {
        PackRow(
            pack, state, vm.packBytes(pack), selected = installed && s.cloneVoiceId.isNotEmpty(),
            onUse = {}, onDownload = { vm.download(pack) }, onCancel = { vm.cancelDownload(pack) }, onDelete = { vm.deletePack(pack) },
        )
        if (installed) {
            Divider()
            VoiceChoiceRow(
                title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7bf998), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_41db47),
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
                    else String.format(Locale.US, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_56de7d), v.durationMs / 1000f) + v.text,
                    selected = s.cloneVoiceId == v.id,
                    onSelect = { vm.useClone(v.id) },
                ) {
                    if (playing && preview == SettingsViewModel.Preview.Loading) TypingDots(Modifier.padding(horizontal = IrisDimens.space8))
                    else PressableIcon(
                        if (playing) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow, if (playing) com.iris.localai.I18n.s(com.iris.localai.R.string.service_095e93) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f4a352),
                        iconSize = 22.dp, tint = c.text,
                    ) { vm.previewVoice(v) }
                    PressableIcon(Icons.Outlined.DeleteOutline, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), iconSize = IrisDimens.space20, tint = c.textSecondary) { deleting = v }
                }
            }
            Divider()
            SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fc271e), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4d9193), titleColor = c.accent, onClick = { creating = true })
        }
    }

    if (installed) {
        SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3cc941))
        SettingsGroup {
            SegmentedRow(
                com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ba3c48),
                options = listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8fcedb), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2774eb)),
                selectedIndex = s.cloneFidelity,
                onSelect = { i -> vm.update { it.copy(cloneFidelity = i) } },
                subtitle = if (s.cloneFidelity == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_39508e)
                else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_184e71),
            )
            Divider()
            if (s.cloneFidelity == 0) {
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_896303), s.cloneStepsFast.toFloat(), 2f..8f, "${s.cloneStepsFast}", subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_64acf2), steps = 5) { v ->
                    vm.update { it.copy(cloneStepsFast = v.toInt()) }
                }
            } else {
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_896303), s.cloneStepsFine.toFloat(), 6f..24f, "${s.cloneStepsFine}", subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cd53ff), steps = 17) { v ->
                    vm.update { it.copy(cloneStepsFine = v.toInt()) }
                }
            }
        }
    }

    if (creating) CloneDialog(vm, onClose = { creating = false; vm.discardCloneDraft() }, onSaved = { creating = false })
    deleting?.let { v ->
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_77062e, v.name), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_61763a), confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
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
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fc271e),
        confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e8ba81),
        confirmEnabled = ready != null && text.isNotBlank(),
        onConfirm = {
            vm.saveClone(name.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8c687c, voices.size + 1) }, text)
            onSaved()
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d2e526),
                style = IrisType.secondary, color = c.textSecondary,
            )
            when (val d = draft) {
                is SettingsViewModel.CloneDraft.Recording -> {
                    Text(String.format(Locale.US, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_479b73), d.seconds), style = IrisType.rowStrong, color = c.text)
                    IrisProgressBar(progress = (d.seconds / 15f).coerceIn(0f, 1f))
                    IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2d6864), vm::stopCloneRecording, icon = Icons.Outlined.StopCircle, modifier = Modifier.fillMaxWidth())
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
                            if (ready != null) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_385595) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_beacda), {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startCloneRecording()
                                else mic.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            icon = Icons.Outlined.Mic, style = if (ready != null) IrisButtonStyle.Outline else IrisButtonStyle.Primary,
                            modifier = Modifier.weight(1f),
                        )
                        IrisButton(
                            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_71f07b), { pick.launch(arrayOf("audio/*")) }, icon = Icons.Outlined.AudioFile,
                            style = IrisButtonStyle.Outline, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (ready != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d8a081), ready.seconds), style = IrisType.rowStrong, color = c.text, modifier = Modifier.weight(1f))
                    IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ab387f), vm::playDraft, icon = Icons.Outlined.PlayArrow, style = IrisButtonStyle.Text, compact = true)
                }
                ready.note?.let { Text(it, style = IrisType.caption, color = c.textTertiary) }
                OutlinedTextField(
                    text, { text = it }, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3e3f34)) },
                    textStyle = IrisType.body.copy(color = c.text), shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_05e288), style = IrisType.caption) },
                )
                OutlinedTextField(
                    name, { name = it.take(20) }, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2682ee)) }, singleLine = true,
                    placeholder = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8c687c, voices.size + 1)) },
                    textStyle = IrisType.body.copy(color = c.text), shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(IrisDimens.space2))
        }
    }
}

package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.Format
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.voice.InstalledTts
import com.iris.localai.voice.PackState

/**
 * 2.4.5: 设置 › 语音 › 导入的语音合成模型 — sherpa-onnx Kokoro / Matcha / VITS (Piper, MeloTTS…)
 * from a folder, a .tar.bz2 or a download link; each can be chosen as the read-aloud voice or deleted.
 */
@Composable
internal fun CustomTtsSection(vm: SettingsViewModel) {
    val c = Iris.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val models by vm.customTts.collectAsStateWithLifecycle()
    val state by vm.customImport.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshCustomTts() }
    val active = remember(models, s.ttsPack) { vm.activeCustomTts() }
    var askUrl by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InstalledTts?>(null) }
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importTtsFolder(uri)
    }
    val archivePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTtsArchive(uri)
    }

    SectionTitle(I18n.s(R.string.tts245_section))
    SettingsGroup {
        models.forEachIndexed { i, m ->
            if (i > 0) Divider()
            PackRow(
                m.model.name, I18n.s(R.string.tts245_blurb, m.model.spec.type.label), PackState.Installed, m.model.bytes,
                selected = active?.id == m.id && s.cloneVoiceId.isEmpty(),
                onUse = { vm.stopPreview(); vm.useCustomTts(m) }, onDownload = {}, onCancel = {}, onDelete = { deleting = m },
            )
        }
        if (models.isNotEmpty()) Divider()
        if (state.active) {
            val line = when (state.phase) {
                1 -> I18n.s(R.string.tts245_extracting)
                2 -> I18n.s(R.string.tts245_checking)
                else -> if (state.total > 0) I18n.s(R.string.tts245_copying, Format.bytes(state.done), Format.bytes(state.total)) else I18n.s(R.string.tts245_copying_unknown, Format.bytes(state.done))
            }
            SettingsRow(I18n.s(R.string.tts245_importing), subtitle = line, trailing = {
                IrisButton(I18n.s(R.string.models_8d63ef), { vm.cancelTtsImport() }, style = IrisButtonStyle.Text, compact = true)
            })
            IrisProgressBar(
                progress = if (state.phase == 0 && state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else null,
                modifier = Modifier.padding(start = com.iris.localai.ui.theme.IrisSpace.L, end = com.iris.localai.ui.theme.IrisSpace.L, bottom = com.iris.localai.ui.theme.IrisSpace.S),
            )
        } else {
            SettingsRow(I18n.s(R.string.tts245_pick_folder), subtitle = I18n.s(R.string.tts245_pick_folder_sub), onClick = { vm.clearTtsImportMessage(); folderPicker.launch(null) })
            Divider()
            SettingsRow(I18n.s(R.string.tts245_pick_archive), onClick = { vm.clearTtsImportMessage(); archivePicker.launch(arrayOf("*/*")) })
            Divider()
            SettingsRow(I18n.s(R.string.tts245_paste_url), subtitle = I18n.s(R.string.tts245_paste_url_sub), onClick = { vm.clearTtsImportMessage(); askUrl = true })
        }
        state.error?.let { err -> Text(err, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(horizontal = com.iris.localai.ui.theme.IrisSpace.L, vertical = com.iris.localai.ui.theme.IrisSpace.S)) }
        state.added?.let { name -> Text(I18n.s(R.string.tts245_added, name), style = IrisType.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = com.iris.localai.ui.theme.IrisSpace.L, vertical = com.iris.localai.ui.theme.IrisSpace.S)) }
    }
    GroupFootnote(I18n.s(R.string.tts245_footnote))

    if (askUrl) {
        com.iris.localai.ui.components.TextInputDialog(
            title = I18n.s(R.string.tts245_paste_url),
            initial = "",
            onConfirm = { url -> askUrl = false; vm.importTtsUrl(url.trim()) },
            onDismiss = { askUrl = false },
            confirmText = I18n.s(R.string.tts245_download),
            placeholder = "https://…/vits-piper-….tar.bz2",
        )
    }
    deleting?.let { m ->
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { deleting = null }, title = I18n.s(R.string.tts245_delete_title, m.model.name),
            confirmText = I18n.s(R.string.chat_2f4aad), onConfirm = { deleting = null; vm.deleteCustomTts(m) },
            destructive = true,
        ) {
            Text(I18n.s(R.string.tts245_delete_body, Format.bytes(m.model.bytes)), style = IrisType.secondary, color = c.textSecondary)
        }
    }
}

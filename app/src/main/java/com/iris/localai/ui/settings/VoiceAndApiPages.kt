package com.iris.localai.ui.settings

import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.Format
import com.iris.localai.ui.components.CheckRow
import com.iris.localai.ui.components.CodeSurface
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.PageDescription
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedControl
import com.iris.localai.ui.components.SegmentedRow
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.server.ApiAuth
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.voice.PackState
import com.iris.localai.voice.VoiceKind
import com.iris.localai.voice.VoicePack
import java.util.Locale

private val ASR_LANGUAGES = listOf("auto" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3aed2c), "zh" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2006be), "yue" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_319dbf), "en" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4145d4))  // 2.0.1: 日语 / 韩语 removed (user)

@Composable
fun VoicePage(vm: SettingsViewModel, onBack: () -> Unit) {
    val c = Iris.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val packs by vm.voicePacks.collectAsStateWithLifecycle()
    val customList by vm.customTts.collectAsStateWithLifecycle()
    // 2.5.0 Beta5: whether a cloned voice is really in force (saved voice exists + ZipVoice installed).
    val cloneList by vm.cloneVoices.collectAsStateWithLifecycle()
    val cloneOn = VoiceChoice.cloneActive(s.cloneVoiceId, cloneList.map { it.id }, packs[VoicePack.CLONE_ZIPVOICE] == PackState.Installed)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopPreview() } }

    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b9a753), onBack) {
        ScrollBody {
            PageDescription(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e3b3ef))
            // 2.0.x: everyday settings first (朗读, 识别), then the model packs; the rarely touched
            // ones (音色编号, 打断, 下载源) fold away.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f9ab1f))
            SettingsGroup(Modifier.tourTarget(TourId.VOICE_TTS)) {
                // 2.2.10: 朗读引擎 — 本机模型（含克隆音色）or Android 系统 TTS.
                val sys = s.ttsEngine == com.iris.localai.voice.TTS_ENGINE_SYSTEM
                SegmentedRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cf7e7f), listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_78587c), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f3dc4b)), if (sys) 1 else 0,
                    { i -> vm.stopPreview(); vm.update { it.copy(ttsEngine = if (i == 1) com.iris.localai.voice.TTS_ENGINE_SYSTEM else com.iris.localai.voice.TTS_ENGINE_LOCAL) } },
                    subtitle = if (sys) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be76a6) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_19ae43),
                )
                Divider()
                // 2.5.3: folds in / out with the engine choice instead of popping.
                com.iris.localai.ui.components.Fold(sys) { androidx.compose.foundation.layout.Column {
                    val hasEngine = remember(s.ttsEngine) { vm.systemTtsAvailable() }
                    SettingsRow(
                        if (hasEngine) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_486d41) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_da54a4),
                        subtitle = if (hasEngine) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0e304e) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6a57d5),
                        titleColor = if (hasEngine) c.text else c.danger,
                        onClick = vm::openSystemTtsSettings,
                        trailing = { Chevron() },
                    )
                    Divider()
                } }
                val ttsActive = remember(packs, s.ttsPack) { vm.activePack(VoiceKind.TTS) }
                val customVoice = remember(s.ttsPack, customList) { vm.activeCustomTts() }
                com.iris.localai.ui.components.Fold(!sys && customVoice == null && ttsActive?.singleSpeaker == false && !cloneOn) { androidx.compose.foundation.layout.Column {
                    val female = s.ttsSpeakerId in 3..57
                    val male = s.ttsSpeakerId >= 58
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d09e4a),
                        subtitle = when {
                            s.ttsSpeakerId < 3 -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_09f9a7, s.ttsSpeakerId)
                            female -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_77bca1, s.ttsSpeakerId - 2)
                            else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_aaac47, s.ttsSpeakerId - 57)
                        },
                        trailing = {
                            SegmentedControl(
                                listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2c0e77), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ed7a11)),
                                selectedIndex = when { female -> 0; male -> 1; else -> -1 },
                                onSelect = { i -> vm.update { it.copy(ttsSpeakerId = if (i == 0) 3 else 58) } },
                                modifier = Modifier.width(128.dp),
                            )
                        },
                    )
                    Divider()
                } }
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_595d51), s.ttsSpeed, 0.6f..1.6f, String.format(Locale.US, "%.1fx", s.ttsSpeed)) { v -> vm.update { it.copy(ttsSpeed = (v * 10).toInt() / 10f) } }
                Divider()
                val preview by vm.preview.collectAsStateWithLifecycle()
                SettingsRow(
                    when (preview) {
                        SettingsViewModel.Preview.Loading -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_661b77)
                        SettingsViewModel.Preview.Playing -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_498987)
                        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f4a352)
                    },
                    subtitle = (preview as? SettingsViewModel.Preview.Failed)?.message,
                    onClick = vm::previewVoice,
                    trailing = {
                        when (preview) {
                            SettingsViewModel.Preview.Loading -> com.iris.localai.ui.components.TypingDots()
                            SettingsViewModel.Preview.Playing -> Icon(Icons.Outlined.StopCircle, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d22e11), tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge))
                            else -> Icon(Icons.Outlined.PlayArrow, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f4a352), tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge))
                        }
                    },
                )
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_84b7cb), s.autoSpeak, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ccdd00)) { on -> vm.update { it.copy(autoSpeak = on) } }
            }
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_72eff8))
            SettingsGroup {
                SegmentedRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e57f3c),
                    options = ASR_LANGUAGES.map { it.second },
                    selectedIndex = ASR_LANGUAGES.indexOfFirst { it.first == s.asrLanguage },
                    onSelect = { i -> vm.update { it.copy(asrLanguage = ASR_LANGUAGES[i].first) } },
                )
            }

            for ((kind, label) in listOf(VoiceKind.ASR to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a6541c), VoiceKind.TTS to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_27eaa4))) {
                SectionTitle(label)
                val active = remember(packs, s.asrPack, s.ttsPack) { vm.activePack(kind) }
                // 2.4.5: an imported model chosen as the voice wins over the packs (CustomTtsSection).
                val customSel = remember(s.ttsPack, customList) { vm.activeCustomTts() }
                val customOn = kind == VoiceKind.TTS && customSel != null
                SettingsGroup {
                    VoicePack.entries.filter { it.kind == kind }.forEachIndexed { i, pack ->
                        if (i > 0) Divider()
                        PackRow(
                            pack, packs[pack] ?: PackState.NotInstalled, vm.packBytes(pack), selected = pack == active && !customOn && !(kind == VoiceKind.TTS && cloneOn),
                            onUse = { vm.usePack(pack) },
                            onDownload = { vm.download(pack) }, onCancel = { vm.cancelDownload(pack) }, onDelete = { vm.deletePack(pack) },
                        )
                    }
                }
            }
            CustomTtsSection(vm)
            CloneVoiceSection(vm)
            TranslateSection(vm, s)

            com.iris.localai.ui.components.SectionFold("voice-adv", com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9efdd4)) {
                val ttsActive = remember(packs, s.ttsPack) { vm.activePack(VoiceKind.TTS) }
                val customAdv = remember(s.ttsPack, customList) { vm.activeCustomTts() }
                // 2.4.5: an imported multi-voice model (Kokoro, multi-speaker VITS) uses the same 音色编号 (clamped to its voices).
                if (((customAdv == null && ttsActive?.singleSpeaker == false) || (customAdv != null && customAdv.model.spec.type != com.iris.localai.voice.CustomTtsType.MATCHA)) && !cloneOn) {
                    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_966b10))
                    SettingsGroup {
                        SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_966b10), s.ttsSpeakerId.toFloat(), 0f..102f, "#${s.ttsSpeakerId}") { v -> vm.update { it.copy(ttsSpeakerId = v.toInt()) } }
                    }
                }
                SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8d7696))
                SettingsGroup {
                    SegmentedRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3a1c52),
                        options = listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_19ac67), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_aed1df), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4296d7)),
                        selectedIndex = s.bargeInSensitivity,
                        onSelect = { i -> vm.update { it.copy(bargeInSensitivity = i) } },
                        subtitle = when (s.bargeInSensitivity) {
                            0 -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f51084)
                            2 -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_372984)
                            else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d68e5f)
                        } + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_906f6a),
                    )
                    Divider()
                    SwitchRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_959016), s.bargeInVoiceComm,
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_02fac3),
                    ) { on -> vm.update { it.copy(bargeInVoiceComm = on) } }
                }

                SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_d98cb5))
                SettingsGroup {
                    DownloadSource.entries.forEachIndexed { i, src ->
                        if (i > 0) Divider()
                        CheckRow(src.label, selected = s.downloadSource == src) { vm.update { it.copy(downloadSource = src) } }
                    }
                }

                GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4a492f))
            }
        }
    }
}

/** 1.14.7: model-license note for a voice pack (null = permissive). 2.5.3: no longer shown in the pack row subtitle. */
internal val VoicePack.licenseTag: String?
    get() = when (this) {
        VoicePack.CLONE_ZIPVOICE, VoicePack.TTS_MATCHA_BAKER -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_855f7b)
        VoicePack.TTS_PIPER_XIAOYA -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7e41cf)
        else -> null
    }

@Composable
internal fun PackRow(
    pack: VoicePack, state: PackState, bytes: Long, selected: Boolean,
    onUse: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) = PackRow(pack.title, pack.blurb, state, bytes, selected, onUse, onDownload, onCancel, onDelete) // 2.5.3: subtitle = 音色·特点 only; no license tag (see 关于 › 开源许可)

/** 2.3.0 ③: 翻译 — target language, engine, and the offline Bergamot packs (one per direction). */
@Composable
private fun TranslateSection(vm: SettingsViewModel, s: com.iris.localai.data.AppSettings) {
    val packs by vm.translatePacks.collectAsStateWithLifecycle()
    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8b3607))
    SettingsGroup {
        val targets = com.iris.localai.translate.TranslateSettings.TARGETS
        SegmentedRow(
            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_46ffe3), targets.map { it.second.substringBefore("（") }, targets.indexOfFirst { it.first == s.translateTarget }.coerceAtLeast(0),
            { i -> vm.update { it.copy(translateTarget = targets[i].first) } },
            subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_009e7a),
        )
        Divider()
        val engines = com.iris.localai.translate.TranslateSettings.ENGINES
        SegmentedRow(
            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_51fc23), engines.map { it.second }, engines.indexOfFirst { it.first == s.translateEngine }.coerceAtLeast(0),
            { i -> vm.update { it.copy(translateEngine = engines[i].first) } },
            subtitle = when (s.translateEngine) {
                com.iris.localai.translate.TranslateSettings.ENGINE_MODEL -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_24f86e)
                com.iris.localai.translate.TranslateSettings.ENGINE_OFFLINE -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_498e86)
                else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be176d)
            },
        )
    }
    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_113ce8))
    SettingsGroup {
        com.iris.localai.translate.TranslatePack.entries.forEachIndexed { i, pack ->
            if (i > 0) Divider()
            val st = packs[pack] ?: PackState.NotInstalled
            PackRow(
                pack.title, pack.blurb, st, pack.totalBytes, selected = st == PackState.Installed,
                onUse = {}, onDownload = { vm.downloadTranslate(pack) }, onCancel = { vm.cancelTranslate(pack) }, onDelete = { vm.deleteTranslate(pack) },
            )
        }
    }
    GroupFootnote(
        if (vm.offlineEngineAvailable) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_09d681)
        else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4f9ab2),
    )
}

@Composable
internal fun PackRow(
    title: String, blurb: String, state: PackState, bytes: Long, selected: Boolean,
    onUse: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val c = Iris.colors
    val installed = state == PackState.Installed
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (installed && !selected) Modifier.clickable(role = Role.RadioButton, onClick = onUse) else Modifier)
            .padding(start = IrisDimens.space4, end = IrisDimens.space8, top = IrisDimens.space8, bottom = IrisDimens.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.iris.localai.ui.components.IrisRadio(selected, Modifier.alpha(if (installed) 1f else 0.4f))
            Column(Modifier.weight(1f)) {
                Text(title, style = IrisType.rowStrong, color = c.text)
                Text(blurb, style = IrisType.caption, color = c.textSecondary)
                Text(
                    when (state) {
                        PackState.Installed -> if (selected) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_50c32a, Format.bytes(bytes)) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_20ea42)
                        is PackState.Downloading -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_90c647, Format.bytes(state.downloaded), Format.bytes(state.total))
                        PackState.Extracting -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c2ec7d)
                        is PackState.Failed -> state.message
                        PackState.NotInstalled -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a9abfc, Format.bytes(bytes))
                    },
                    style = IrisType.caption,
                    color = when {
                        state is PackState.Failed -> c.danger
                        installed && selected -> c.text
                        else -> c.textTertiary
                    },
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(IrisDimens.space8))
            when (state) {
                PackState.Installed -> IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), onDelete, style = IrisButtonStyle.Text, compact = true, destructive = true)
                is PackState.Downloading -> IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.models_8d63ef), onCancel, style = IrisButtonStyle.Text, compact = true)
                PackState.Extracting -> com.iris.localai.ui.components.TypingDots()
                else -> IrisButton(if (state is PackState.Failed) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_132c5c) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f26ef9), onDownload, style = IrisButtonStyle.Outline, compact = true)
            }
        }
        AnimatedVisibility(
            visible = state is PackState.Downloading || state == PackState.Extracting,
            enter = fadeIn(IrisMotion.enter()) + expandVertically(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()) + shrinkVertically(IrisMotion.standard()),
        ) {
            IrisProgressBar(
                progress = if (state == PackState.Extracting) null else (state as? PackState.Downloading)?.fraction ?: 1f,
                modifier = Modifier.padding(start = 48.dp, end = IrisDimens.space8, top = IrisDimens.space8, bottom = IrisDimens.space4),
            )
        }
    }
}

@Composable
fun ApiPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val c = Iris.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.apiStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var portText by rememberSaveable(s.apiPort) { mutableStateOf(s.apiPort.toString()) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setApiEnabled(true) }
    val portValid = portText.toIntOrNull()?.let { it in 1024..65535 } == true
    fun applyPort() { if (portValid) { portText.toIntOrNull()?.let(vm::setPort); focus.clearFocus() } }

    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2a59e7), onBack) {
        ScrollBody {
            PageDescription(
                com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c7b64c),
            )
            SettingsGroup {
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8668b0), s.apiEnabled,
                    subtitle = when {
                        status.error != null -> status.error
                        status.running -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d679ae)
                        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_9c5850)
                    },
                ) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.setApiEnabled(on)
                }
                Divider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = IrisDimens.rowMinHeight).padding(start = IrisDimens.rowPadding, end = IrisDimens.space8, top = IrisDimens.space8, bottom = IrisDimens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c76cfe), style = IrisType.row, color = c.text, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        portText, { v -> portText = v.filter(Char::isDigit).take(5) },
                        singleLine = true,
                        isError = portText.isNotEmpty() && !portValid,
                        textStyle = IrisType.body.copy(color = c.text),
                        shape = IrisRadius.mediumShape,
                        colors = irisTextFieldColors(),
                        modifier = Modifier.width(112.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { applyPort() }),
                    )
                    IrisButton(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb), ::applyPort, style = IrisButtonStyle.Text, compact = true,
                        enabled = portValid && portText != s.apiPort.toString(),
                    )
                }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_29f028), s.apiAllowLan, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cab136)) { vm.setLan(it) }
            }

            SectionTitle("API Key")
            // 2.1.7 API 可自定 Key: type your own (or 生成随机); set → clients send `Authorization: Bearer <key>` or `x-api-key`.
            var keyText by rememberSaveable(s.apiKey) { mutableStateOf(s.apiKey) }
            var keyVisible by rememberSaveable { mutableStateOf(false) }
            val keyDirty = ApiAuth.sanitizeKey(keyText) != s.apiKey
            fun applyKey() { vm.setApiKey(keyText); focus.clearFocus() }
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = IrisDimens.rowMinHeight).padding(start = IrisDimens.rowPadding, end = IrisDimens.space4, top = IrisDimens.space8, bottom = IrisDimens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        keyText, { v -> keyText = ApiAuth.sanitizeKey(v) },
                        singleLine = true,
                        placeholder = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4ee3ed), style = IrisType.secondary, color = c.textTertiary) },
                        textStyle = IrisType.mono.copy(color = c.text),
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = IrisRadius.mediumShape,
                        colors = irisTextFieldColors(),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { applyKey() }),
                    )
                    PressableIcon(
                        if (keyVisible) IrisIcons.EyeOff else IrisIcons.Eye, if (keyVisible) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dce537) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4d775d),
                        iconSize = 18.dp, tint = c.textSecondary,
                    ) { keyVisible = !keyVisible }
                    if (s.apiKey.isNotBlank()) {
                        PressableIcon(IrisIcons.Copy, com.iris.localai.I18n.s(com.iris.localai.R.string.ui_79d3ab), iconSize = 18.dp, tint = c.textSecondary, haptic = true) {
                            clipboard.setText(AnnotatedString(s.apiKey))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = IrisDimens.space8, end = IrisDimens.space8, bottom = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                    IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5fa42d), { vm.regenerateKey(); keyVisible = true }, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Reload)
                    Spacer(Modifier.weight(1f))
                    IrisButton(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb), ::applyKey, style = IrisButtonStyle.Text, compact = true,
                        enabled = keyDirty && (keyText.isNotBlank() || !s.apiAllowLan),
                    )
                }
                if (s.apiKey.isNotBlank() && !s.apiAllowLan) {
                    Divider()
                    SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_22355d), titleColor = c.danger, onClick = vm::clearKey)
                }
            }
            GroupFootnote(
                if (s.apiKey.isBlank()) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b058c9)
                else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f3744e),
            )

            AnimatedVisibility(
                visible = status.running && status.addresses.isNotEmpty(),
                enter = fadeIn(IrisMotion.enter()) + expandVertically(IrisMotion.enter()),
                exit = fadeOut(IrisMotion.standard()) + shrinkVertically(IrisMotion.standard()),
            ) {
                Column {
                    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d3ab61))
                    SettingsGroup {
                        status.addresses.forEachIndexed { i, a ->
                            if (i > 0) Divider()
                            SettingsRow(a, trailing = {
                                PressableIcon(Icons.Outlined.ContentCopy, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d4bf75), iconSize = 18.dp, tint = c.textSecondary, haptic = true) {
                                    clipboard.setText(AnnotatedString(a))
                                }
                            })
                        }
                    }
                    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1a63ac))
                    val base = status.addresses.firstOrNull()?.removeSuffix("/v1") ?: ""
                    val auth = if (s.apiKey.isNotBlank()) " \\\n  -H \"Authorization: Bearer ${s.apiKey}\"" else ""
                    val example = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e767f5, base, auth)
                    CodeSurface(example, Modifier.padding(horizontal = IrisDimens.groupGutter))
                    Row(Modifier.padding(start = IrisDimens.space8, top = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                        IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d8a8a4), { clipboard.setText(AnnotatedString(example)) }, style = IrisButtonStyle.Text, compact = true, icon = Icons.Outlined.ContentCopy)
                    }
                }
            }
        }
    }
}

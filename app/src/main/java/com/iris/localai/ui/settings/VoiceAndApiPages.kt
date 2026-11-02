package com.iris.localai.ui.settings

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

private val ASR_LANGUAGES = listOf("auto" to "自动", "zh" to "普通话", "yue" to "粤语", "en" to "英语")  // 2.0.1: 日语 / 韩语 removed (user)

@Composable
fun VoicePage(vm: SettingsViewModel, onBack: () -> Unit) {
    val c = Iris.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val packs by vm.voicePacks.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopPreview() } }

    SubpageScaffold("语音与翻译", onBack) {
        ScrollBody {
            PageDescription("语音识别、合成与翻译都在本机运行，首次使用需下载模型。")
            // 2.0.x: everyday settings first (朗读, 识别), then the model packs; the rarely touched
            // ones (音色编号, 打断, 下载源) fold away.
            SectionTitle("朗读")
            SettingsGroup {
                // 2.2.10: 朗读引擎 — 本机模型（含克隆音色）or Android 系统 TTS.
                val sys = s.ttsEngine == com.iris.localai.voice.TTS_ENGINE_SYSTEM
                SegmentedRow(
                    "朗读引擎", listOf("本机模型", "系统 TTS"), if (sys) 1 else 0,
                    { i -> vm.stopPreview(); vm.update { it.copy(ttsEngine = if (i == 1) com.iris.localai.voice.TTS_ENGINE_SYSTEM else com.iris.localai.voice.TTS_ENGINE_LOCAL) } },
                    subtitle = if (sys) "使用系统默认的语音引擎和音色，无需下载模型" else "使用下方下载的语音合成模型或克隆音色",
                )
                Divider()
                if (sys) {
                    val hasEngine = remember(s.ttsEngine) { vm.systemTtsAvailable() }
                    SettingsRow(
                        if (hasEngine) "系统 TTS 设置" else "未找到系统 TTS 引擎",
                        subtitle = if (hasEngine) "在系统设置中更换引擎、音色或下载中文语音包" else "请在系统设置中安装或启用文字转语音引擎（如 Google 语音服务、厂商自带引擎）",
                        titleColor = if (hasEngine) c.text else c.danger,
                        onClick = vm::openSystemTtsSettings,
                        trailing = { Chevron() },
                    )
                    Divider()
                }
                val ttsActive = remember(packs, s.ttsPack) { vm.activePack(VoiceKind.TTS) }
                if (!sys && ttsActive?.singleSpeaker == false && s.cloneVoiceId.isEmpty()) {
                    val female = s.ttsSpeakerId in 3..57
                    val male = s.ttsSpeakerId >= 58
                    SettingsRow(
                        "音色",
                        subtitle = when {
                            s.ttsSpeakerId < 3 -> "英文音色 #${s.ttsSpeakerId}"
                            female -> "中文女声 #${s.ttsSpeakerId - 2}"
                            else -> "中文男声 #${s.ttsSpeakerId - 57}"
                        },
                        trailing = {
                            SegmentedControl(
                                listOf("女声", "男声"),
                                selectedIndex = when { female -> 0; male -> 1; else -> -1 },
                                onSelect = { i -> vm.update { it.copy(ttsSpeakerId = if (i == 0) 3 else 58) } },
                                modifier = Modifier.width(128.dp),
                            )
                        },
                    )
                    Divider()
                }
                SliderRow("语速", s.ttsSpeed, 0.6f..1.6f, String.format(Locale.US, "%.1fx", s.ttsSpeed)) { v -> vm.update { it.copy(ttsSpeed = (v * 10).toInt() / 10f) } }
                Divider()
                val preview by vm.preview.collectAsStateWithLifecycle()
                SettingsRow(
                    when (preview) {
                        SettingsViewModel.Preview.Loading -> "正在加载音色…"
                        SettingsViewModel.Preview.Playing -> "播放中，点按停止"
                        else -> "试听"
                    },
                    subtitle = (preview as? SettingsViewModel.Preview.Failed)?.message,
                    onClick = vm::previewVoice,
                    trailing = {
                        when (preview) {
                            SettingsViewModel.Preview.Loading -> com.iris.localai.ui.components.TypingDots()
                            SettingsViewModel.Preview.Playing -> Icon(Icons.Outlined.StopCircle, "停止试听", tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge))
                            else -> Icon(Icons.Outlined.PlayArrow, "试听", tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge))
                        }
                    },
                )
                Divider()
                SwitchRow("文字对话也自动朗读", s.autoSpeak, subtitle = "语音对话总是朗读") { on -> vm.update { it.copy(autoSpeak = on) } }
            }
            SectionTitle("识别")
            SettingsGroup {
                SegmentedRow(
                    "识别语言",
                    options = ASR_LANGUAGES.map { it.second },
                    selectedIndex = ASR_LANGUAGES.indexOfFirst { it.first == s.asrLanguage },
                    onSelect = { i -> vm.update { it.copy(asrLanguage = ASR_LANGUAGES[i].first) } },
                )
            }

            for ((kind, label) in listOf(VoiceKind.ASR to "语音识别模型", VoiceKind.TTS to "语音合成模型")) {
                SectionTitle(label)
                val active = remember(packs, s.asrPack, s.ttsPack) { vm.activePack(kind) }
                SettingsGroup {
                    VoicePack.entries.filter { it.kind == kind }.forEachIndexed { i, pack ->
                        if (i > 0) Divider()
                        PackRow(
                            pack, packs[pack] ?: PackState.NotInstalled, vm.packBytes(pack), selected = pack == active,
                            onUse = { vm.usePack(pack) },
                            onDownload = { vm.download(pack) }, onCancel = { vm.cancelDownload(pack) }, onDelete = { vm.deletePack(pack) },
                        )
                    }
                }
            }
            CloneVoiceSection(vm)
            TranslateSection(vm, s)

            com.iris.localai.ui.components.SectionFold("voice-adv", "高级：音色编号、打断与下载源") {
                val ttsActive = remember(packs, s.ttsPack) { vm.activePack(VoiceKind.TTS) }
                if (ttsActive?.singleSpeaker == false && s.cloneVoiceId.isEmpty()) {
                    SectionTitle("音色编号")
                    SettingsGroup {
                        SliderRow("音色编号", s.ttsSpeakerId.toFloat(), 0f..102f, "#${s.ttsSpeakerId}") { v -> vm.update { it.copy(ttsSpeakerId = v.toInt()) } }
                    }
                }
                SectionTitle("语音对话打断")
                SettingsGroup {
                    SegmentedRow(
                        "说话打断回复的灵敏度",
                        options = listOf("低", "中", "高"),
                        selectedIndex = s.bargeInSensitivity,
                        onSelect = { i -> vm.update { it.copy(bargeInSensitivity = i) } },
                        subtitle = when (s.bargeInSensitivity) {
                            0 -> "低：要说得更大声、更久才打断，适合嘈杂环境"
                            2 -> "高：轻声也能打断，偶尔会被回复声误触"
                            else -> "中：正常音量说话即可打断"
                        } + "。外放时为防止自问自答，说话不打断，点光球打断；戴耳机或用听筒时生效",
                    )
                    Divider()
                    SwitchRow(
                        "更易打断（回复按通话播放）", s.bargeInVoiceComm,
                        subtitle = "打断更灵敏，但音量改由通话音量控制、音质略差",
                    ) { on -> vm.update { it.copy(bargeInVoiceComm = on) } }
                }

                SectionTitle("下载源")
                SettingsGroup {
                    DownloadSource.entries.forEachIndexed { i, src ->
                        if (i > 0) Divider()
                        CheckRow(src.label, selected = s.downloadSource == src) { vm.update { it.copy(downloadSource = src) } }
                    }
                }

                GroupFootnote("和「模型与加载」里的下载源是同一个设置。Matcha 声码器与 ZipVoice 从 GitHub 下载。")
            }
        }
    }
}

/** 1.14.7: model-license note shown in a voice pack's subtitle (null = permissive, no tag). */
internal val VoicePack.licenseTag: String?
    get() = when (this) {
        VoicePack.CLONE_ZIPVOICE, VoicePack.TTS_MATCHA_BAKER -> "仅限非商业"
        VoicePack.TTS_PIPER_XIAOYA -> "见模型许可"
        else -> null
    }

@Composable
internal fun PackRow(
    pack: VoicePack, state: PackState, bytes: Long, selected: Boolean,
    onUse: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) = PackRow(pack.title, pack.licenseTag?.let { "${pack.blurb} · $it" } ?: pack.blurb, state, bytes, selected, onUse, onDownload, onCancel, onDelete)

/** 2.3.0 ③: 翻译 — target language, engine, and the offline Bergamot packs (one per direction). */
@Composable
private fun TranslateSection(vm: SettingsViewModel, s: com.iris.localai.data.AppSettings) {
    val packs by vm.translatePacks.collectAsStateWithLifecycle()
    SectionTitle("翻译")
    SettingsGroup {
        val targets = com.iris.localai.translate.TranslateSettings.TARGETS
        SegmentedRow(
            "译成", targets.map { it.second.substringBefore("（") }, targets.indexOfFirst { it.first == s.translateTarget }.coerceAtLeast(0),
            { i -> vm.update { it.copy(translateTarget = targets[i].first) } },
            subtitle = "自动：中文译成英文，其他语言译成中文。长按消息或在输入框「+」里翻译",
        )
        Divider()
        val engines = com.iris.localai.translate.TranslateSettings.ENGINES
        SegmentedRow(
            "翻译引擎", engines.map { it.second }, engines.indexOfFirst { it.first == s.translateEngine }.coerceAtLeast(0),
            { i -> vm.update { it.copy(translateEngine = engines[i].first) } },
            subtitle = when (s.translateEngine) {
                com.iris.localai.translate.TranslateSettings.ENGINE_MODEL -> "只用已加载的模型翻译（不带工具、记忆和上下文，关闭思考）"
                com.iris.localai.translate.TranslateSettings.ENGINE_OFFLINE -> "只用下方的离线翻译包，不占用对话模型"
                else -> "已加载模型时用模型翻译，否则用离线翻译包"
            },
        )
    }
    SectionTitle("离线翻译包")
    SettingsGroup {
        com.iris.localai.translate.TranslatePack.entries.forEachIndexed { i, pack ->
            if (i > 0) Divider()
            val st = packs[pack] ?: PackState.NotInstalled
            PackRow(
                pack.title, "${pack.blurb} · MPL-2.0", st, pack.totalBytes, selected = st == PackState.Installed,
                onUse = {}, onDownload = { vm.downloadTranslate(pack) }, onCancel = { vm.cancelTranslate(pack) }, onDelete = { vm.deleteTranslate(pack) },
            )
        }
    }
    GroupFootnote(
        if (vm.offlineEngineAvailable) "离线引擎为 Mozilla Bergamot（Firefox 翻译同款），中英各一个方向，没加载模型时也能翻译。"
        else "本机未能加载离线翻译引擎，只能用已加载的模型翻译。",
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
                        PackState.Installed -> if (selected) "使用中 · ${Format.bytes(bytes)}" else "已安装 · 点按切换"
                        is PackState.Downloading -> "下载中 ${Format.bytes(state.downloaded)} / ${Format.bytes(state.total)}"
                        PackState.Extracting -> "正在校验并解压…"
                        is PackState.Failed -> state.message
                        PackState.NotInstalled -> "约 ${Format.bytes(bytes)}"
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
                PackState.Installed -> IrisButton("删除", onDelete, style = IrisButtonStyle.Text, compact = true, destructive = true)
                is PackState.Downloading -> IrisButton("暂停", onCancel, style = IrisButtonStyle.Text, compact = true)
                PackState.Extracting -> com.iris.localai.ui.components.TypingDots()
                else -> IrisButton(if (state is PackState.Failed) "重试" else "下载", onDownload, style = IrisButtonStyle.Outline, compact = true)
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

    SubpageScaffold("API 服务", onBack) {
        ScrollBody {
            PageDescription(
                "让 ChatBox、沉浸式翻译、Open WebUI 等 App 调用本机正在用的模型。支持 OpenAI 和 Ollama 接口。",
            )
            SettingsGroup {
                SwitchRow(
                    "启用 API 服务", s.apiEnabled,
                    subtitle = when {
                        status.error != null -> status.error
                        status.running -> "运行中"
                        else -> "已关闭"
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
                    Text("端口", style = IrisType.row, color = c.text, modifier = Modifier.weight(1f))
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
                        "保存", ::applyPort, style = IrisButtonStyle.Text, compact = true,
                        enabled = portValid && portText != s.apiPort.toString(),
                    )
                }
                Divider()
                SwitchRow("允许局域网访问", s.apiAllowLan, subtitle = "同一 Wi-Fi 下的电脑、平板也能连接，需要 API Key") { vm.setLan(it) }
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
                        placeholder = { Text("未设置 · 只在本机用可以不设", style = IrisType.secondary, color = c.textTertiary) },
                        textStyle = IrisType.mono.copy(color = c.text),
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = IrisRadius.mediumShape,
                        colors = irisTextFieldColors(),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { applyKey() }),
                    )
                    PressableIcon(
                        if (keyVisible) IrisIcons.EyeOff else IrisIcons.Eye, if (keyVisible) "隐藏" else "显示",
                        iconSize = 18.dp, tint = c.textSecondary,
                    ) { keyVisible = !keyVisible }
                    if (s.apiKey.isNotBlank()) {
                        PressableIcon(IrisIcons.Copy, "复制", iconSize = 18.dp, tint = c.textSecondary, haptic = true) {
                            clipboard.setText(AnnotatedString(s.apiKey))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = IrisDimens.space8, end = IrisDimens.space8, bottom = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                    IrisButton("生成随机", { vm.regenerateKey(); keyVisible = true }, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Reload)
                    Spacer(Modifier.weight(1f))
                    IrisButton(
                        "保存", ::applyKey, style = IrisButtonStyle.Text, compact = true,
                        enabled = keyDirty && (keyText.isNotBlank() || !s.apiAllowLan),
                    )
                }
                if (s.apiKey.isNotBlank() && !s.apiAllowLan) {
                    Divider()
                    SettingsRow("清除 API Key", titleColor = c.danger, onClick = vm::clearKey)
                }
            }
            GroupFootnote(
                if (s.apiKey.isBlank()) "不设 Key 时本机 App 可直接调用，浏览器网页会被拒绝。"
                else "调用时带上请求头 Authorization: Bearer <Key>（或 x-api-key: <Key>），否则返回 401。",
            )

            AnimatedVisibility(
                visible = status.running && status.addresses.isNotEmpty(),
                enter = fadeIn(IrisMotion.enter()) + expandVertically(IrisMotion.enter()),
                exit = fadeOut(IrisMotion.standard()) + shrinkVertically(IrisMotion.standard()),
            ) {
                Column {
                    SectionTitle("地址（Base URL）")
                    SettingsGroup {
                        status.addresses.forEachIndexed { i, a ->
                            if (i > 0) Divider()
                            SettingsRow(a, trailing = {
                                PressableIcon(Icons.Outlined.ContentCopy, "复制地址", iconSize = 18.dp, tint = c.textSecondary, haptic = true) {
                                    clipboard.setText(AnnotatedString(a))
                                }
                            })
                        }
                    }
                    SectionTitle("示例")
                    val base = status.addresses.firstOrNull()?.removeSuffix("/v1") ?: ""
                    val auth = if (s.apiKey.isNotBlank()) " \\\n  -H \"Authorization: Bearer ${s.apiKey}\"" else ""
                    val example = "curl $base/v1/chat/completions$auth \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"model\":\"default\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}'"
                    CodeSurface(example, Modifier.padding(horizontal = IrisDimens.groupGutter))
                    Row(Modifier.padding(start = IrisDimens.space8, top = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
                        IrisButton("复制示例", { clipboard.setText(AnnotatedString(example)) }, style = IrisButtonStyle.Text, compact = true, icon = Icons.Outlined.ContentCopy)
                    }
                }
            }
        }
    }
}

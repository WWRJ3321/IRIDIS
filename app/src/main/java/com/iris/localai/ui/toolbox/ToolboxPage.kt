package com.iris.localai.ui.toolbox
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.text.selection.SelectionContainer
import com.iris.localai.ui.components.Fold
import com.iris.localai.ui.components.rememberLast
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motion
import com.iris.localai.ui.theme.LocalReduceMotion

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.horizontalScroll

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.floating.FloatingService
import com.iris.localai.ui.chat.ChatViewModel
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisCard
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.feedback.FeedbackEvent
import com.iris.localai.ui.feedback.rememberIrisFeedback
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.launch
import java.io.File

/** 3.0 AI 工具箱：功能方块网格（每两格一行）。加新功能 = 在 tools 列表里加一项，需要独立页的再在 when(open) 里接上。 */
private data class Tool(val id: String, val icon: ImageVector, val title: String, val sub: String)

@Composable
fun ToolboxPage(chat: ChatViewModel, onBack: () -> Unit, onOpenImageGen: () -> Unit) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(open != null) { open = null }
    val floating by FloatingService.running.collectAsStateWithLifecycle()
    when (open) {
        "tts" -> SubpageScaffold(I18n.s(R.string.v30_tb_tts), { open = null }) { ScrollBody { TtsSection(chat) } }
        "stt" -> SubpageScaffold(I18n.s(R.string.v30_tb_stt), { open = null }) { ScrollBody { SttSection(chat) } }
        "find" -> SubpageScaffold(I18n.s(R.string.v30_tb_find), { open = null }) { ScrollBody { FindSection() } }
        "float" -> SubpageScaffold(I18n.s(R.string.v30_floating), { open = null }) { ScrollBody { FloatSection(chat, floating) } }
        else -> {
            val tools = listOf(
                Tool("image", IrisIcons.Image, I18n.s(R.string.v30_w_image), I18n.s(R.string.v30_tb_image_sub)),
                Tool("float", IrisIcons.Layers, I18n.s(R.string.v30_floating), if (floating) I18n.s(R.string.v30_tb_on) else I18n.s(R.string.v30_tb_float_sub2)),
                Tool("tts", IrisIcons.Sound, I18n.s(R.string.v30_tb_tts), I18n.s(R.string.v30_tb_tts_sub)),
                Tool("stt", IrisIcons.Chat, I18n.s(R.string.v30_tb_stt), I18n.s(R.string.v30_tb_stt_sub)),
                Tool("find", IrisIcons.Search, I18n.s(R.string.v30_tb_find), I18n.s(R.string.v30_tb_find_sub)),
            )
            fun tap(id: String) = when (id) {
                "image" -> onOpenImageGen()
                else -> { open = id }
            }
            SubpageScaffold(I18n.s(R.string.v30_toolbox), onBack) {
                ScrollBody {
                    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter), verticalArrangement = Arrangement.spacedBy(IrisDimens.space12)) {
                        tools.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space12)) {
                                row.forEach { t -> ToolTile(t.icon, t.title, t.sub, Modifier.weight(1f).fillMaxHeight()) { tap(t.id) } }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Beta3：悬浮窗设置页（工具箱里点进来，不再一点就开）。 */
@Composable
private fun FloatSection(chat: ChatViewModel, running: Boolean) {
    val ctx = LocalContext.current
    val st = chat.settings.collectAsStateWithLifecycle().value
    // 从系统授权页回来时重新读权限
    var tick by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val canDraw = remember(tick) { FloatingService.canDraw(ctx) }
    val a11y = remember(tick) { com.iris.localai.agent.gui.android.IrisAccessibilityService.connected }
    Spacer(Modifier.height(IrisDimens.space8))
    SettingsGroup {
        SwitchRow(I18n.s(R.string.v31_fl_set_on), running, subtitle = if (canDraw) null else I18n.s(R.string.v31_fl_perm), icon = IrisIcons.Layers) { on ->
            when {
                !on -> FloatingService.stop(ctx)
                !FloatingService.canDraw(ctx) -> runCatching { ctx.startActivity(FloatingService.permissionIntent(ctx)) }
                else -> FloatingService.start(ctx)
            }
        }
        SwitchRow(I18n.s(R.string.v31_fl_set_peek), st.floatPeek) { on -> chat.updateSettings { it.copy(floatPeek = on) } }
        SliderRow(I18n.s(R.string.v31_fl_set_size), st.floatSize.toFloat(), 40f..64f, "${st.floatSize} dp", steps = 5) { v -> chat.updateSettings { it.copy(floatSize = v.toInt()) } }
    }
    SectionTitle(I18n.s(R.string.v31_fl_set_menu))
    SettingsGroup {
        val names = mapOf(
            com.iris.localai.floating.FloatingLogic.Mode.TRANSLATE to I18n.s(R.string.v31_fl_tr_screen),
            com.iris.localai.floating.FloatingLogic.Mode.SCREEN to I18n.s(R.string.v30_fl_screen),
            com.iris.localai.floating.FloatingLogic.Mode.ASK to I18n.s(R.string.v30_fl_ask),
        )
        com.iris.localai.floating.FloatingLogic.MENU.forEach { m ->
            SwitchRow(names.getValue(m), m.name in st.floatItems) { on ->
                chat.updateSettings { it.copy(floatItems = if (on) it.floatItems + m.name else it.floatItems - m.name) }
            }
        }
    }
    if (!a11y) {
        Spacer(Modifier.height(IrisDimens.space12))
        SettingsGroup {
            SettingsRow(I18n.s(R.string.v31_fl_a11y), onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
    }
}

@Composable
private fun ToolTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Iris.colors
    IrisCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(IrisDimens.space16)) {
            Box(Modifier.size(40.dp).background(c.accent.copy(alpha = 0.12f), IrisRadius.mediumShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(IrisDimens.space12))
            Text(title, style = IrisType.label, color = c.text)
            Text(sub, style = IrisType.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TtsSection(chat: ChatViewModel) {
    val c = Iris.colors
    var text by rememberSaveable { mutableStateOf("") }
    val speaking by chat.speakingMessageId.collectAsStateWithLifecycle()
    SectionTitle(I18n.s(R.string.v30_tb_tts))
    SettingsGroup {
        Column(Modifier.padding(IrisDimens.space16)) {
            OutlinedTextField(
                text, { text = it }, colors = irisTextFieldColors(), textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text(I18n.s(R.string.v30_tb_tts_hint), color = c.textTertiary) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
            )
            Spacer(Modifier.height(IrisDimens.space12))
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                if (speaking == TB_SPEAK_ID) IrisButton(I18n.s(R.string.v30_island_stop), { chat.stopSpeaking() }, compact = true, style = IrisButtonStyle.Tonal)
                else IrisButton(I18n.s(R.string.v30_tb_speak), { chat.speakText(text, TB_SPEAK_ID) }, compact = true, enabled = text.isNotBlank(), icon = IrisIcons.Sound)
            }
        }
    }
}

private const val TB_SPEAK_ID = "toolbox-tts"

@Composable
private fun SttSection(chat: ChatViewModel) {
    val c = Iris.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val haptic = rememberIrisFeedback()
    var result by rememberSaveable { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val st = chat.settings.collectAsStateWithLifecycle().value
    val lang = st.asrLanguage
    // Beta2：可选识别模型——列出已装的语音识别包，点选即设为当前（与 模型 页 的选择同一设置）
    val packStates by chat.voiceStore.states.collectAsStateWithLifecycle()
    val asrPacks = remember(packStates) {
        com.iris.localai.voice.VoicePack.entries.filter { it.kind == com.iris.localai.voice.VoiceKind.ASR && packStates[it] == com.iris.localai.voice.PackState.Installed }
    }
    val activeAsr = remember(packStates, st.asrPack) { chat.voiceStore.active(com.iris.localai.voice.VoiceKind.ASR, st.asrPack) }
    SectionTitle(I18n.s(R.string.v30_tb_stt))
    SettingsGroup {
        Column(Modifier.padding(IrisDimens.space16)) {
            Text(I18n.s(R.string.v30b2_stt_model), style = IrisType.caption, color = c.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            if (asrPacks.isEmpty()) Text(I18n.s(R.string.v30b2_stt_none), style = IrisType.body, color = c.textTertiary)
            else Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                asrPacks.forEach { p ->
                    val sel = p == activeAsr
                    Box(
                        Modifier.clip(IrisRadius.pillShape)
                            .background(com.iris.localai.ui.motion.selectTint(if (sel) c.accentContainer else c.surfaceVariant))
                            .clickable(enabled = !holding && !busy) { chat.useAsrPack(p.id) }
                            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
                    ) { Text(p.title, style = IrisType.labelSmall, color = com.iris.localai.ui.motion.selectTint(if (sel) c.onAccentContainer else c.textSecondary)) }
                }
            }
            Spacer(Modifier.height(IrisDimens.space12))
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .background(com.iris.localai.ui.motion.selectTint(if (holding) c.accent else c.surfaceVariant, "pttBg"), IrisRadius.pillShape)
                    .pointerInput(lang) {
                        detectTapGestures(onPress = {
                            if (busy) return@detectTapGestures
                            holding = true; haptic.perform(FeedbackEvent.LISTEN_START)
                            chat.dictation.warmLanguage = lang
                            chat.dictation.start()
                            tryAwaitRelease()
                            holding = false; haptic.perform(FeedbackEvent.LISTEN_STOP)
                            busy = true
                            scope.launch {
                                val t = runCatching { chat.dictation.finish(lang) }.getOrDefault("")
                                result = com.iris.localai.voice.DictationText.join(result, t)
                                busy = false
                            }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val reduce = LocalReduceMotion.current
                val fg = com.iris.localai.ui.motion.selectTint(if (holding) c.onAccent else c.text, "pttFg")
                AnimatedContent(
                    when { holding -> I18n.s(R.string.v30_ptt_release); busy -> I18n.s(R.string.v30_tb_recognizing); else -> I18n.s(R.string.v30_ptt_hold) },
                    transitionSpec = { motion(reduce) { IrisMotion.crossfade() } }, label = "ptt",
                ) { t -> Text(t, style = IrisType.label, color = fg) }
            }
            // 清空时收起，退场期间仍显示最后一段
            val shownResult = rememberLast(result.ifBlank { null }).orEmpty()
            Fold(result.isNotBlank()) {
                Column {
                    Spacer(Modifier.height(IrisDimens.space12))
                    SelectionContainer { Text(shownResult, style = IrisType.body, color = c.text) }
                    Row(Modifier.padding(top = IrisDimens.space8), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                        IrisButton(I18n.s(R.string.v30_fl_copy), { clipboard.setText(AnnotatedString(shownResult)) }, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Copy)
                        IrisButton(I18n.s(R.string.v30_tb_to_chat), { chat.setDraft(shownResult) }, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Chat)
                        IrisButton(I18n.s(R.string.v30_tb_clear), { result = "" }, compact = true, style = IrisButtonStyle.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun FindSection() {
    val c = Iris.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<FileFinder.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) FileFinder.release() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); FileFinder.release() }
    }
    SectionTitle(I18n.s(R.string.v30_tb_find))
    SettingsGroup {
        Column(Modifier.padding(IrisDimens.space16)) {
            Text(
                if (FileFinder.semanticAvailable()) I18n.s(R.string.v30_tb_find_sem) else I18n.s(R.string.v30_tb_find_kw),
                style = IrisType.caption, color = c.textSecondary,
            )
            Spacer(Modifier.height(IrisDimens.space8))
            OutlinedTextField(
                q, { q = it }, singleLine = true, colors = irisTextFieldColors(), textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text(I18n.s(R.string.v30_tb_find_hint), color = c.textTertiary) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                if (searching) I18n.s(R.string.v30_tb_searching) else I18n.s(R.string.v30_fl_search),
                { searching = true; scope.launch { hits = runCatching { FileFinder.search(q) }.getOrDefault(emptyList()); searching = false; searched = true } },
                compact = true, enabled = q.isNotBlank() && !searching, icon = IrisIcons.Search,
            )
        }
        if (searched && hits.isEmpty()) SettingsRow(I18n.s(R.string.v30_fl_nothing))
        hits.take(20).forEach { h ->
            SettingsRow(
                h.file.name, subtitle = h.file.parent.orEmpty().substringAfter("/0/"), subtitleMaxLines = 1,
                icon = if (FindScore.kindWords(h.file.extension).startsWith("照片")) IrisIcons.Image else IrisIcons.File,
                onClick = { openFile(ctx, h.file) },
            )
        }
    }
}

private fun openFile(ctx: android.content.Context, f: File) {
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

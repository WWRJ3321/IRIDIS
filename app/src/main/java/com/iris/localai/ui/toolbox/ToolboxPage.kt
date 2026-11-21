package com.iris.localai.ui.toolbox

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

/** 3.0 AI 工具箱：生图、文本转语音、语音转文本、悬浮窗、找文件、冬天暖手宝，一页放齐。 */
@Composable
fun ToolboxPage(chat: ChatViewModel, onBack: () -> Unit, onOpenImageGen: () -> Unit) {
    val c = Iris.colors
    val ctx = LocalContext.current
    SubpageScaffold(I18n.s(R.string.v30_toolbox), onBack) {
        ScrollBody {
            // 顶部快捷格：生图 / 悬浮窗
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space12)) {
                ToolTile(IrisIcons.Image, I18n.s(R.string.v30_w_image), I18n.s(R.string.v30_tb_image_sub), Modifier.weight(1f), onOpenImageGen)
                val floating by FloatingService.running.collectAsStateWithLifecycle()
                ToolTile(IrisIcons.Layers, I18n.s(R.string.v30_floating), if (floating) I18n.s(R.string.v30_tb_on) else I18n.s(R.string.v30_tb_float_sub), Modifier.weight(1f)) {
                    when {
                        floating -> FloatingService.stop(ctx)
                        !FloatingService.canDraw(ctx) -> runCatching { ctx.startActivity(FloatingService.permissionIntent(ctx)) }
                        else -> FloatingService.start(ctx)
                    }
                }
            }
            TtsSection(chat)
            SttSection(chat)
            FindSection()
            WarmerSection()
        }
    }
}

@Composable
private fun ToolTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Iris.colors
    IrisCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(IrisDimens.space16)) {
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
    val lang = chat.settings.collectAsStateWithLifecycle().value.asrLanguage
    SectionTitle(I18n.s(R.string.v30_tb_stt))
    SettingsGroup {
        Column(Modifier.padding(IrisDimens.space16)) {
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .background(if (holding) c.accent else c.surfaceVariant, IrisRadius.pillShape)
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
                Text(
                    when { holding -> I18n.s(R.string.v30_ptt_release); busy -> I18n.s(R.string.v30_tb_recognizing); else -> I18n.s(R.string.v30_ptt_hold) },
                    style = IrisType.label, color = if (holding) c.onAccent else c.text,
                )
            }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(IrisDimens.space12))
                Text(result, style = IrisType.body, color = c.text)
                Row(Modifier.padding(top = IrisDimens.space8), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                    IrisButton(I18n.s(R.string.v30_fl_copy), { clipboard.setText(AnnotatedString(result)) }, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Copy)
                    IrisButton(I18n.s(R.string.v30_tb_to_chat), { chat.setDraft(result) }, compact = true, style = IrisButtonStyle.Tonal, icon = IrisIcons.Chat)
                    IrisButton(I18n.s(R.string.v30_tb_clear), { result = "" }, compact = true, style = IrisButtonStyle.Text)
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

@Composable
private fun WarmerSection() {
    val c = Iris.colors
    val ctx = LocalContext.current
    val st by HandWarmer.state.collectAsStateWithLifecycle()
    var minutes by rememberSaveable { mutableFloatStateOf(10f) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.running) { while (st.running) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    SectionTitle(I18n.s(R.string.v30_warmer))
    SettingsGroup {
        if (!st.running) SliderRow(
            I18n.s(R.string.v30_warmer_time), minutes, 5f..WarmerPolicy.MAX_MINUTES.toFloat(),
            I18n.s(R.string.v30_warmer_min, minutes.toInt()), steps = 4,
        ) { minutes = it }
        SwitchRow(
            I18n.s(R.string.v30_warmer),
            st.running,
            subtitle = when {
                st.running -> I18n.s(R.string.v30_warmer_running, ((st.endsAt - now).coerceAtLeast(0) / 60_000 + 1).toInt(), if (st.batteryC.isFinite()) "%.1f".format(st.batteryC) else "—")
                st.stoppedBy != null -> st.stoppedBy
                else -> I18n.s(R.string.v30_warmer_sub)
            },
            icon = IrisIcons.Bolt,
        ) { on -> if (on) HandWarmer.start(ctx, minutes.toInt()) else HandWarmer.stop(null) }
    }
}

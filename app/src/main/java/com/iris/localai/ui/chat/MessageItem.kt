package com.iris.localai.ui.chat

import androidx.compose.animation.core.snap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Format
import com.iris.localai.data.Role
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.components.floatingSurface
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.markdown.Markdown
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.util.ParagraphSplitter
import com.iris.localai.util.ThinkParseCache
import com.iris.localai.util.TypewriterPacer
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(
    message: ChatMessage,
    onEdit: () -> Unit,
    /** 2.0.x: 「记住这条」 (null = hidden). */
    onRemember: (() -> Unit)? = null,
    /** 2.0.x: ‹ › between the versions of this message (and the conversation that followed each). */
    onSwitchVersion: (Int) -> Unit = {},
) {
    val c = Iris.colors
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val haptics = com.iris.localai.ui.feedback.rememberIrisFeedback()
    Box(Modifier.fillMaxWidth().padding(start = 52.dp), contentAlignment = Alignment.CenterEnd) {
      Column(horizontalAlignment = Alignment.End) {
        // 1.10.12: attached pictures / files above the bubble.
        if (message.attachments.isNotEmpty()) {
            AttachmentStrip(message.attachments, modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) IrisDimens.space6 else 0.dp))
        }
        // 2.2 「技能」: the skills / tools this message pointed the agent at.
        if (message.refs.isNotEmpty()) {
            Text(
                message.refs.joinToString("  ") { it.token },
                style = IrisType.labelSmall, color = c.accent, maxLines = 2,
                modifier = Modifier.padding(end = IrisDimens.space4, bottom = IrisDimens.space4),
            )
        }
        if (message.content.isNotBlank()) Box(
            Modifier
                .clip(IrisRadius.bubbleShape)
                .background(c.surfaceVariant)
                .combinedClickable(onClick = {}, onLongClick = {
                    haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.LONG_PRESS); menu = true
                })
                .padding(horizontal = IrisDimens.space16, vertical = 10.dp),
        ) {
            Text(message.content, style = IrisType.message, color = c.text)
        }
        VersionSwitcher(message, onSwitchVersion)
      }
        com.iris.localai.ui.components.IrisMenu(menu, { menu = false }) {
            com.iris.localai.ui.components.IrisMenuItem("复制", Icons.Outlined.ContentCopy) { clipboard.setText(AnnotatedString(message.content)); menu = false }
            com.iris.localai.ui.components.IrisMenuItem("编辑后重新发送", Icons.Outlined.Edit) { menu = false; onEdit() }
            if (onRemember != null) com.iris.localai.ui.components.IrisMenuItem(VersionText.REMEMBER, com.iris.localai.ui.icons.IrisIcons.Memory) { menu = false; onRemember() }
        }
    }
}

/**
 * An assistant reply. While [streaming], its text comes from [streamText] (read only inside the
 * thinking / answer sub-scopes, so a new token never recomposes the whole message or the list),
 * is parsed incrementally ([ThinkParseCache]) and is revealed at an even, backlog-adaptive pace.
 */
@Composable
fun AssistantMessage(
    message: ChatMessage,
    streaming: Boolean,
    isLast: Boolean,
    expectThinking: Boolean,
    speaking: Boolean,
    onRegenerate: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    streamText: () -> String? = { null },
    /** 1.7.7: what the reply waits for before its first text (e.g. 「正在重载模型…」). */
    status: String? = null,
    /** 1.10.6: opens 「查看提示内容」 (null = hidden). */
    onShowPrompt: (() -> Unit)? = null,
    /** 2.0.x: edit this reply in place (null = hidden). */
    onEdit: (() -> Unit)? = null,
    /** 2.0.x: 「记住这条」 (null = hidden). */
    onRemember: (() -> Unit)? = null,
    /** 2.0.x: ‹ › between the versions of this reply. */
    onSwitchVersion: (Int) -> Unit = {},
    /** 2.2.9: 「＋ 记住？」 chip (shown when [ChatMessage.memorySuggestion] is set). */
    onAcceptMemory: (() -> Unit)? = null,
    onDismissMemory: (() -> Unit)? = null,
) {
    val c = Iris.colors
    val cache = remember(message.id) { ThinkParseCache() }
    val parts = remember(message.id, message.content, streaming, expectThinking) {
        derivedStateOf { if (streaming) cache.parseStreaming(streamText() ?: message.content, expectThinking) else cache.parse(message.content) }
    }
    // While a thinking reply starts, show the (empty) thinking block right away instead of typing
    // dots that are then swapped for the block: one layout from the first frame, no jump.
    // 1.13.3: while a tool runs (or waits for you) its card is the live part; no empty 思考中 below it.
    val stepActive = streaming && message.agentSteps.lastOrNull()?.status.let {
        it == com.iris.localai.agent.StepStatus.RUNNING || it == com.iris.localai.agent.StepStatus.ASKING
    }
    val hasThinking by remember(parts, streaming, expectThinking, stepActive) {
        derivedStateOf { parts.value.hasThinking || (streaming && !stepActive && expectThinking && parts.value.answer.isEmpty()) }
    }
    val hasAnswer by remember(parts) { derivedStateOf { parts.value.answer.isNotEmpty() } }
    // 2.2.17 D 引用链接: web_search sources of this reply (inline [n] links + 来源 row).
    val sources = remember(message.agentSteps, streaming) { if (streaming) emptyList() else com.iris.localai.agent.Citations.fromSteps(message.agentSteps) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
        // 1.9.0 智能体: tool steps of this reply, above the reasoning and the answer.
        com.iris.localai.ui.agent.AgentSteps(message.agentSteps, streaming) { st ->
            val lp = remember(st.lead) { ThinkParseCache().parse(st.lead) }
            Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
                if (lp.thinking.isNotBlank()) ThinkingBlock({ lp.thinking }, { false }, streaming = false, elapsedMs = 0L, initiallyExpanded = false)
                if (lp.answer.isNotBlank()) AnswerBody(text = { lp.answer }, streaming = false)
            }
        }
        if (hasThinking) {
            ThinkingBlock(
                text = { parts.value.thinking },
                inProgress = { streaming && (parts.value.thinkingOpen || (!parts.value.hasThinking && parts.value.answer.isEmpty())) },
                streaming = streaming,
                elapsedMs = message.thinkingMs,
            )
        }
        when {
            hasAnswer -> AnswerBody(text = { parts.value.answer }, streaming = streaming, sources = sources)
            stepActive -> {}
            streaming && !hasThinking && status != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8),
            ) {
                TypingDots()
                Text(status, style = IrisType.secondary, color = c.textSecondary)
            }
            streaming && !hasThinking -> TypingDots()
        }
        message.error?.let { err ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = c.danger, modifier = Modifier.padding(top = IrisDimens.space2).size(18.dp))
                Text(err, style = IrisType.secondary, color = c.danger)
            }
        }
        // Fades in once the reply is complete; history messages show it immediately.
        AnimatedVisibility(
            visible = !streaming && (hasAnswer || message.error != null),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            Column {
                VersionSwitcher(message, onSwitchVersion)
                ActionRow(message, parts.value.answer, isLast, speaking, onRegenerate, onSpeak, onStopSpeaking, onShowPrompt, onEdit, onRemember)
                if (message.kbSources.isNotEmpty()) KbSourceChips(message.kbSources)
                if (sources.isNotEmpty()) WebSourceChips(sources)
                val suggestion = message.memorySuggestion
                if (suggestion != null && onAcceptMemory != null) {
                    MemorySuggestionChip(suggestion, onAcceptMemory, onDismissMemory ?: {})
                }
            }
        }
    }
}

/** The visible answer: smooth reveal + incremental Markdown while streaming. */
@Composable
private fun AnswerBody(text: () -> String, streaming: Boolean, sources: List<com.iris.localai.agent.Citations.Source> = emptyList()) {
    val revealed by rememberRevealedText(text, animate = streaming)
    val shown = if (streaming || sources.isEmpty()) revealed else remember(revealed, sources) { com.iris.localai.agent.Citations.linkify(revealed, sources) }
    // 1.12.0: a finished reply is selectable — long-press, drag the handles, copy just a part.
    // (Not while streaming: the text changes under the selection.)
    if (streaming) Markdown(shown, streaming = true)
    else androidx.compose.foundation.text.selection.SelectionContainer { Markdown(shown, streaming = false) }
}

/**
 * Reveals [target] at the pace of a [TypewriterPacer] driven by the frame clock while [animate];
 * otherwise (finished message, or system animations off) shows it as is. Starts from what is
 * already there, so a reply scrolled back into view is not typed out again.
 */
@Composable
internal fun rememberRevealedText(target: () -> String, animate: Boolean): androidx.compose.runtime.State<String> {
    val reduce = LocalReduceMotion.current
    val live = animate && !reduce
    val pacer = remember { TypewriterPacer() }
    // A block that just appeared while streaming types from its first character; a reply scrolled
    // back into view keeps what it already showed.
    val shownLen = remember { androidx.compose.runtime.mutableIntStateOf(target().length.let { if (live && it <= FRESH_BLOCK_CHARS) 0 else it }) }
    val currentTarget by androidx.compose.runtime.rememberUpdatedState(target)
    if (live) {
        LaunchedEffect(Unit) {
            @Suppress("NAME_SHADOWING") val target = { currentTarget() }
            pacer.reset(shownLen.intValue)
            var last = 0L
            var origin = 0L
            while (true) {
                val len = target().length
                if (shownLen.intValue == len) {
                    // Caught up: sleep until more text arrives (no per-frame work while idle).
                    androidx.compose.runtime.snapshotFlow { target().length }.first { it != shownLen.intValue }
                    last = 0L
                    continue
                }
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 1f / 60f else (now - last) / 1e9f
                    last = now
                    // The clock lets the pacer measure the real arrival speed (see TypewriterPacer).
                    if (origin == 0L) origin = now
                    shownLen.intValue = pacer.step(target().length, dt, done = false, nowSec = (now - origin) / 1e9f)
                }
                // 1.13.0: ~40 reveals a second instead of one per vsync (120 Hz): each reveal re-lays
                // out the last Markdown block, and the eye cannot tell the difference.
                kotlinx.coroutines.delay(REVEAL_GAP_MS)
            }
        }
    }
    return remember(live, target) {
        derivedStateOf {
            val t = target()
            if (!live) t else {
                var n = shownLen.intValue.coerceIn(0, t.length)
                if (n in 1 until t.length && t[n - 1].isHighSurrogate()) n-- // never split an emoji
                t.substring(0, n)
            }
        }
    }
}

/** A streamed block shorter than this when it first appears is typed from its first character. */
private const val FRESH_BLOCK_CHARS = 48
private const val REVEAL_GAP_MS = 22L

/** 2.1: labels of the assistant action bar's overflow. */
internal object ActionRowText {
    const val MORE = "更多操作"
    const val SHOW_PROMPT = "查看提示内容"
}

@Composable
private fun ActionRow(
    message: ChatMessage,
    answer: String,
    isLast: Boolean,
    speaking: Boolean,
    onRegenerate: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onShowPrompt: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRemember: (() -> Unit)? = null,
) {
    val c = Iris.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val meta = remember(message.outputChars, message.outputMs, message.totalMs, message.interrupted, message.stats, message.edited, message.kbRefs) {
        buildList {
            if (message.edited) add(VersionText.EDITED)
            if (message.kbRefs > 0 && message.kbSources.isEmpty()) add(com.iris.localai.knowledge.KbPrompt.chip(message.kbRefs))
            if (message.interrupted) add("已停止")
            // Measured for this reply on the device: output speed (first char → end) and total time.
            com.iris.localai.util.ReplySpeed.format(message.outputChars, message.outputMs, message.totalMs,
                message.stats?.takeIf { it.generatedTokens >= 8 && it.generateMs >= 300 }?.tokensPerSecond ?: 0f)?.let { add(it) }
        }.joinToString(" · ")
    }
    // Icons sit flush with the text column: the first button's ripple starts at the text edge.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 0.dp).height(40.dp)) {
        val iconSize = 18.dp
        PressableIcon(
            if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "已复制" else "复制",
            size = 40.dp, iconSize = iconSize, tint = c.textSecondary, haptic = true,
            modifier = Modifier.padding(end = IrisDimens.space2),
        ) { clipboard.setText(AnnotatedString(answer)); copied = true }
        if (isLast) {
            PressableIcon(Icons.Outlined.Refresh, "重新生成", size = 40.dp, iconSize = iconSize, tint = c.textSecondary, onClick = onRegenerate)
        }
        PressableIcon(
            if (speaking) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp,
            if (speaking) "停止朗读" else "朗读",
            size = 40.dp, iconSize = iconSize, tint = if (speaking) c.text else c.textSecondary,
            modifier = Modifier.pulse(speaking, min = 0.55f),
            onClick = if (speaking) onStopSpeaking else onSpeak,
        )
        // 2.1: low-frequency actions live behind ⋯ so the bar stays short.
        if (onShowPrompt != null || onEdit != null || onRemember != null) {
            var overflow by remember { mutableStateOf(false) }
            Box {
                PressableIcon(com.iris.localai.ui.icons.IrisIcons.More, ActionRowText.MORE, size = 40.dp, iconSize = iconSize, tint = c.textSecondary) { overflow = true }
                com.iris.localai.ui.components.IrisMenu(overflow, { overflow = false }) {
                    if (onEdit != null) com.iris.localai.ui.components.IrisMenuItem(VersionText.EDIT_REPLY, com.iris.localai.ui.icons.IrisIcons.Edit) { overflow = false; onEdit() }
                    if (onRemember != null) com.iris.localai.ui.components.IrisMenuItem(VersionText.REMEMBER, com.iris.localai.ui.icons.IrisIcons.Memory) { overflow = false; onRemember() }
                    if (onShowPrompt != null) com.iris.localai.ui.components.IrisMenuItem(ActionRowText.SHOW_PROMPT, com.iris.localai.ui.icons.IrisIcons.Prompt) { overflow = false; onShowPrompt() }
                }
            }
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.width(IrisDimens.space6))
            Text(
                meta, style = IrisType.caption, color = c.textTertiary, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun ThinkingBlock(text: () -> String, inProgress: () -> Boolean, streaming: Boolean, elapsedMs: Long, initiallyExpanded: Boolean = true) {
    val c = Iris.colors
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val reduce = LocalReduceMotion.current
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, if (reduce) snap() else IrisMotion.standard(), label = "thinkArrow")
    val thinking by remember(inProgress) { derivedStateOf { inProgress() } }
    val hasText by remember(text) { derivedStateOf { text().isNotBlank() } }
    Column {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(IrisRadius.smallShape)
                .clickable { expanded = !expanded }
                .padding(horizontal = IrisDimens.space6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
        ) {
            Icon(com.iris.localai.ui.icons.IrisIcons.Think, null, tint = c.textSecondary, modifier = Modifier.size(IrisDimens.iconSmall + IrisDimens.space2))
            Text(
                when {
                    thinking -> "思考中…"
                    elapsedMs > 0 -> "已深度思考（用时 ${Format.seconds(elapsedMs)} 秒）"
                    else -> "已深度思考"
                },
                style = IrisType.label, color = c.textSecondary,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).pulse(thinking),
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown, if (expanded) "收起思考过程" else "展开思考过程",
                tint = c.textTertiary, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = arrow },
            )
        }
        // While thinking streams the body is already there (one line tall), so the first words do
        // not arrive together with an expand animation of a container that is still growing.
        AnimatedVisibility(
            visible = expanded && (hasText || (streaming && thinking)),
            enter = if (reduce) fadeIn(snap()) else fadeIn(IrisMotion.enter()) + expandVertically(IrisMotion.enter()),
            exit = if (reduce) fadeOut(snap()) else fadeOut(IrisMotion.standard()) + shrinkVertically(IrisMotion.standard()),
        ) {
            Row(Modifier.padding(start = IrisDimens.space6, top = IrisDimens.space4).height(IntrinsicSize.Min)) {
                Box(Modifier.width(IrisDimens.space2).fillMaxHeight().clip(IrisRadius.pillShape).background(c.outline))
                ThinkingParagraphs(text, animate = streaming && thinking, modifier = Modifier.padding(start = IrisDimens.space12))
            }
        }
    }
}

/**
 * Thinking text as one Text per paragraph: finished paragraphs keep the same String, so Compose
 * skips them and never lays them out again; only the last paragraph re-wraps as text streams in.
 */
@Composable
private fun ThinkingParagraphs(text: () -> String, animate: Boolean, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val shown by rememberRevealedText(text, animate)
    val splitter = remember { ParagraphSplitter() }
    val paragraphs = remember(shown) { splitter.split(shown) }
    val style = IrisType.secondary.copy(lineHeight = IrisType.message.lineHeight.times(0.88f))
    val lineMin = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }
    Column(modifier.heightIn(min = if (animate) lineMin else 0.dp), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
        paragraphs.forEachIndexed { i, p ->
            androidx.compose.runtime.key(i) { Text(p, style = style, color = c.textSecondary) }
        }
    }
}

/**
 * Wraps one chat message. Messages created just now rise and fade into place; messages loaded
 * from history (or recycled while scrolling) render immediately. graphicsLayer only.
 */
@Composable
fun MessageRow(message: ChatMessage, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val fresh = remember(message.id) { !reduce && System.currentTimeMillis() - message.createdAt < 1500 }
    val rise = remember(message.id) { Animatable(if (fresh) 0f else 1f) }
    if (fresh) LaunchedEffect(message.id) { rise.animateTo(1f, IrisMotion.Gentle.float()) }
    val user = message.role == Role.USER
    Box(
        modifier
            .fillMaxWidth()
            .widthIn(max = IrisDimens.messageMaxWidth)
            .padding(horizontal = 18.dp)
            .graphicsLayer {
                val v = rise.value
                alpha = v.coerceIn(0f, 1f)
                translationY = (1f - v) * 14.dp.toPx()
                if (user) {
                    val s = 0.94f + 0.06f * v
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(1f, 1f)
                }
            },
    ) { content() }
}

fun ChatMessage.isUser() = role == Role.USER

/** 2.0.x: labels of the message-version switcher and the new message actions. */
object VersionText {
    const val PREV = "上一个版本"
    const val NEXT = "下一个版本"
    const val EDIT_REPLY = "编辑回复"
    const val EDITED = "已编辑"
    const val REMEMBER = "记住这条"
    /** 0-based [active] of [count] → "2/3". */
    fun label(active: Int, count: Int): String = "${active + 1}/$count"
}

/** 2.0.x: compact ‹ 2/3 › under a message with more than one version; nothing otherwise. */
@Composable
fun VersionSwitcher(message: ChatMessage, onSwitch: (Int) -> Unit, modifier: Modifier = Modifier) {
    val n = com.iris.localai.data.MessageVersions.count(message)
    if (n < 2) return
    val i = com.iris.localai.data.MessageVersions.active(message)
    val c = Iris.colors
    Row(modifier.height(IrisDimens.space32), verticalAlignment = Alignment.CenterVertically) {
        PressableIcon(
            com.iris.localai.ui.icons.IrisIcons.ChevronLeft, VersionText.PREV, size = IrisDimens.space32, iconSize = IrisDimens.space16,
            tint = c.textSecondary, enabled = i > 0, haptic = true,
        ) { onSwitch(i - 1) }
        Text(VersionText.label(i, n), style = IrisType.caption, color = c.textSecondary, maxLines = 1)
        PressableIcon(
            com.iris.localai.ui.icons.IrisIcons.ChevronRight, VersionText.NEXT, size = IrisDimens.space32, iconSize = IrisDimens.space16,
            tint = c.textSecondary, enabled = i < n - 1, haptic = true,
        ) { onSwitch(i + 1) }
    }
}

/** 2.2.9 texts of the 「记住？」 chip. */
internal object MemoryChipText {
    const val DISMISS = "忽略"
    fun label(text: String): String = "＋ 记住？「${if (text.length > 24) text.take(23) + "…" else text}」"
}

/** 2.2.9: 「＋ 记住？「…」」 under a finished reply: tap saves, ✕ / long press ignores. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MemorySuggestionChip(text: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier
            .padding(top = IrisDimens.space4)
            .clip(IrisRadius.pillShape)
            .background(c.accentContainer)
            .combinedClickable(onClick = onAccept, onLongClick = onDismiss)
            .heightIn(min = 32.dp)
            .padding(start = IrisDimens.space12, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            MemoryChipText.label(text), style = IrisType.secondary, color = c.onAccentContainer, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        PressableIcon(Icons.Outlined.Close, MemoryChipText.DISMISS, size = 32.dp, iconSize = 16.dp, tint = c.onAccentContainer, onClick = onDismiss)
    }
}

/** 2.2.9 D4: 「出处：租房合同.md · 第 3 段」 chips; a tap shows the snippet. */
/** 2.2.17 D: 「来源」 under a web_search answer — number + title + domain, tap opens the page. */
@Composable
private fun WebSourceChips(sources: List<com.iris.localai.agent.Citations.Source>) {
    val c = Iris.colors
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().padding(top = IrisDimens.space4).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8),
    ) {
        Text("来源", style = IrisType.caption, color = c.textTertiary)
        sources.forEach { s ->
            Text(
                "${s.n} · ${s.title.take(18)}${if (s.title.length > 18) "…" else ""} · ${s.domain}",
                style = IrisType.caption, color = c.textSecondary, maxLines = 1,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(IrisRadius.pillShape)
                    .background(c.surfaceVariant)
                    .clickable { runCatching { uri.openUri(s.url) } }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space8),
            )
        }
    }
}

@Composable
private fun KbSourceChips(sources: List<String>) {
    val c = Iris.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(top = IrisDimens.space4),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(IrisDimens.space8),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(IrisDimens.space8),
    ) {
        sources.forEach { label ->
            Text(
                com.iris.localai.knowledge.KbSource.chip(label), style = IrisType.caption, color = c.textSecondary, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(IrisRadius.pillShape)
                    .background(c.surfaceVariant)
                    .clickable { open = label }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space8),
            )
        }
    }
    open?.let { label ->
        val text by androidx.compose.runtime.produceState<String?>(null, label) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val (file, idx) = com.iris.localai.knowledge.KbSource.parse(label)
                runCatching { (context.applicationContext as com.iris.localai.IrisApp).container.agent.knowledge.chunksOf(file).getOrNull(idx) }.getOrNull().orEmpty()
            }
        }
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { open = null }, title = label, confirmText = "关闭", onConfirm = { open = null }, dismissText = null,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(
                    when (val t = text) { null -> "正在读取…"; "" -> "原文已不在知识库里（文件可能已删除或更改）"; else -> t },
                    style = IrisType.body, color = c.text,
                )
            }
        }
    }
}

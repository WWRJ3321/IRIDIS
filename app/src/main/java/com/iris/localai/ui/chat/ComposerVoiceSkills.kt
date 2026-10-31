package com.iris.localai.ui.chat

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.data.TurnRef
import com.iris.localai.data.TurnRefKind
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion

// ------------------------------------------------------------------ 2.2 voice capsule

/** What a tap on the capsule does. */
enum class CapsuleTap { STOP, SEND, CALL }

/**
 * 2.2: the one long accent capsule bottom-right (replaces the 1.10.2 mic + round button).
 * Empty input: tap = 语音通话, long-press = 语音输入 (release to finish, slide up to cancel).
 * Text / attachments: 发送. Generating: 停止. Pure (unit-tested).
 */
object VoiceCapsule {
    const val WIDTH_DP = 88f
    const val HEIGHT_DP = 40f
    /** Slide this far up while holding → release cancels the dictation. */
    const val CANCEL_DRAG_DP = 56f
    /** Scale while dictating (the capsule swells a little). */
    const val HOLD_SCALE = 1.08f

    fun tap(action: ComposerAction): CapsuleTap = when (action) {
        ComposerAction.STOP -> CapsuleTap.STOP
        ComposerAction.SEND -> CapsuleTap.SEND
        ComposerAction.VOICE -> CapsuleTap.CALL
    }

    /** Long-press dictation only while the input is empty and nothing is generating. */
    fun canDictate(action: ComposerAction): Boolean = action == ComposerAction.VOICE

    fun label(action: ComposerAction): String = when (action) {
        ComposerAction.STOP -> ComposerText.STOP
        ComposerAction.SEND -> ComposerText.SEND
        ComposerAction.VOICE -> ComposerText.CALL_HOLD
    }

    /** [upPx] = how far the finger moved up from where it went down (px). */
    fun cancelArmed(upPx: Float, density: Float): Boolean = upPx >= CANCEL_DRAG_DP * density

    /**
     * 2.1.7: how far the finger slid up ON SCREEN (px) since the long-press began. Pointer positions
     * are local to the capsule, so when the layout moves under a still finger (the soft keyboard
     * hides and the composer drops 300 px) the local y changes although the finger did not move —
     * that used to read as an upward slide and cancel the dictation. Adding the capsule's own
     * position in the window ([startOriginY] at the press, [originY] now) turns both into window
     * coordinates, so a layout shift alone is 0.
     */
    fun slideUpPx(startFingerY: Float, startOriginY: Float, fingerY: Float, originY: Float): Float =
        (startOriginY + startFingerY) - (originY + fingerY)

    /** [slideUpPx] + [cancelArmed]: the decision the long-press loop makes on every pointer event. */
    fun cancelArmed(startFingerY: Float, startOriginY: Float, fingerY: Float, originY: Float, density: Float): Boolean =
        cancelArmed(slideUpPx(startFingerY, startOriginY, fingerY, originY), density)
}

/**
 * 2.2 voice capsule. One gesture detector for all three states, so a long-press can never also fire
 * a tap: VOICE waits for the long-press timeout (tap = call, hold = dictate), SEND / STOP are taps.
 */
@Composable
internal fun VoiceCapsuleButton(
    action: ComposerAction,
    holding: Boolean,
    micLevel: Float,
    sendEnabled: Boolean,
    cancelArmed: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCall: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onCancelArmed: (Boolean) -> Unit,
    /** 2.1.7: a reply is running — a long press on 发送 offers 「打断并发送」. */
    generating: Boolean = false,
    onInterruptSend: () -> Unit = {},
) {
    val c = Iris.colors
    var interruptMenu by remember { mutableStateOf(false) }
    val interruptNow by rememberUpdatedState(onInterruptSend)
    val canInterrupt = generating && action == ComposerAction.SEND
    val reduce = LocalReduceMotion.current
    val haptics = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val tapNow by rememberUpdatedState<() -> Unit> {
        when (VoiceCapsule.tap(action)) {
            CapsuleTap.STOP -> { haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.TICK); onStop() }
            CapsuleTap.SEND -> if (sendEnabled) onSend()
            CapsuleTap.CALL -> { haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.TICK); onCall() }
        }
    }
    val start by rememberUpdatedState(onHoldStart)
    val end by rememberUpdatedState(onHoldEnd)
    val cancel by rememberUpdatedState(onHoldCancel)
    val armed by rememberUpdatedState(onCancelArmed)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        when { holding -> VoiceCapsule.HOLD_SCALE; pressed -> IrisMotion.PRESS_SCALE_BUTTON; else -> 1f },
        IrisMotion.Press.float(), label = "capsuleScale",
    )
    val bg by animateColorAsState(
        when {
            cancelArmed -> c.danger
            action == ComposerAction.SEND && !sendEnabled -> c.accent.copy(alpha = 0.35f)
            else -> c.accent
        },
        IrisMotion.Color.spec(), label = "capsuleBg",
    )
    // Ripple ring while dictating: one Animatable looping 0 → 1 (IrisMotion.loop), off with reduce motion.
    val ring = remember { Animatable(0f) }
    LaunchedEffect(holding, reduce) {
        if (!holding || reduce) { ring.snapTo(0f); return@LaunchedEffect }
        while (true) { ring.snapTo(0f); ring.animateTo(1f, IrisMotion.loop(IrisMotion.PULSE_PERIOD_MS * 2)) }
    }
    val shape = IrisRadius.pillShape
    // 2.1.7: the capsule's top in window coordinates (a layout shift moves it; see VoiceCapsule.slideUpPx).
    val originY = remember { floatArrayOf(0f) }
    Box(
        Modifier
            .onGloballyPositioned { originY[0] = it.positionInWindow().y }
            .size(VoiceCapsule.WIDTH_DP.dp, VoiceCapsule.HEIGHT_DP.dp)
            .drawBehind {
                if (holding && ring.value > 0f) {
                    val grow = 10.dp.toPx() * ring.value
                    drawRoundRect(
                        bg.copy(alpha = 0.45f * (1f - ring.value)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = CornerRadius(size.height / 2 + grow),
                        style = Stroke(IrisDimens.space2.toPx()),
                    )
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .drawBehind { drawRect(bg) }
            .semantics {
                role = Role.Button
                contentDescription = VoiceCapsule.label(action)
                onClick(VoiceCapsule.label(action)) { tapNow(); true }
            }
            .pointerInput(action, canInterrupt) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (canInterrupt) {
                        // 2.1.7: tap = 发送 (补充 / 「停…」), hold = the 「打断并发送」 menu.
                        var up = false
                        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            up = waitForUpOrCancellation()?.also { it.consume() } != null
                        } == null
                        pressed = false
                        if (!held) { if (up) tapNow(); return@awaitEachGesture }
                        haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.TICK)
                        interruptMenu = true
                        waitForUpOrCancellation()?.consume()
                        return@awaitEachGesture
                    }
                    if (!VoiceCapsule.canDictate(action)) {
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) { up.consume(); tapNow() }
                        return@awaitEachGesture
                    }
                    var lifted = false
                    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        lifted = waitForUpOrCancellation()?.also { it.consume() } != null
                    } == null
                    if (!timedOut) { pressed = false; if (lifted) tapNow(); return@awaitEachGesture }
                    // Long-press → dictation until the finger lifts (1.14.1: lifting is the only end, a
                    // layout shift under the finger does not cut it off); slide up = cancel.
                    com.iris.localai.ui.feedback.FeedbackVoice.dictating = true
                    haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.LISTEN_START)
                    start()
                    val startOrigin = originY[0]
                    var isArmed = false
                    var done = false
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                            // 2.1.7: window coordinates — the keyboard closing (layout drops) is not a slide.
                            val now = VoiceCapsule.cancelArmed(down.position.y, startOrigin, ch.position.y, originY[0], density)
                            if (now != isArmed) { isArmed = now; armed(now) }
                            if (e.changes.none { it.pressed }) break
                        }
                    } finally {
                        if (!done) {
                            done = true
                            pressed = false
                            if (isArmed) { armed(false); cancel() } else end()
                            haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.LISTEN_STOP)
                            com.iris.localai.ui.feedback.FeedbackVoice.dictating = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        com.iris.localai.ui.components.IrisMenu(expanded = interruptMenu && canInterrupt, onDismiss = { interruptMenu = false }) {
            com.iris.localai.ui.components.IrisMenuItem(SendIntent.INTERRUPT_AND_SEND, IrisIcons.Stop) { interruptMenu = false; interruptNow() }
        }
        val face = if (action == ComposerAction.VOICE && holding) -1 else action.ordinal
        AnimatedContent(targetState = face, transitionSpec = { motion(reduce) { IrisMotion.pop() } }, label = "capsuleFace") { f ->
            when (f) {
                -1 -> LevelBars(micLevel, bars = 7, color = c.onAccent)
                ComposerAction.STOP.ordinal -> Icon(IrisIcons.Stop, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
                ComposerAction.SEND.ordinal -> Icon(IrisIcons.Send, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
                else -> Icon(IrisIcons.Voice, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 2.2 「技能」 picker

/** One row of the 「技能」 sheet. */
data class RefItem(val ref: TurnRef, val title: String, val subtitle: String)

/** 2.2: what the 「技能」 sheet lists. [skillToolOff] = the skill tool is disabled (no skills offered). */
data class RefCatalog(val skills: List<RefItem>, val tools: List<RefItem>, val skillToolOff: Boolean = false) {
    val isEmpty: Boolean get() = skills.isEmpty() && tools.isEmpty()
    val size: Int get() = skills.size + tools.size

    /** Case-insensitive match on name, title or description. */
    fun filter(query: String): RefCatalog {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return this
        fun RefItem.hit() = ref.name.lowercase().contains(q) || title.lowercase().contains(q) || subtitle.lowercase().contains(q)
        return copy(skills = skills.filter { it.hit() }, tools = tools.filter { it.hit() })
    }

    companion object {
        /** Show a search field once the list is longer than this. */
        const val SEARCH_FROM = 8
        val EMPTY = RefCatalog(emptyList(), emptyList())
    }
}

/** The picked references as removable chips above the text field. */
@Composable
internal fun RefChips(refs: List<TurnRef>, onRemove: (TurnRef) -> Unit, modifier: Modifier = Modifier) {
    val c = Iris.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        refs.forEach { r ->
            Row(
                Modifier
                    .height(26.dp)
                    .clip(IrisRadius.pillShape)
                    .background(c.accentContainer)
                    .padding(start = 9.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (r.kind == TurnRefKind.SKILL) IrisIcons.Skill else IrisIcons.Tool, null, tint = c.onAccentContainer, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(IrisDimens.space4))
                Text(r.token, style = IrisType.labelSmall, color = c.onAccentContainer, maxLines = 1)
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(IrisRadius.pillShape)
                        .semantics { contentDescription = ComposerText.removeRef(r.name) }
                        .clickable { onRemove(r) },
                    contentAlignment = Alignment.Center,
                ) { Icon(IrisIcons.Close, null, tint = c.onAccentContainer, modifier = Modifier.size(IrisDimens.space12)) }
            }
        }
    }
}

/** 2.2 「技能」 sheet: installed skills and agent tools; picking one adds a reference to the input. */
@Composable
internal fun SkillPickerSheet(catalog: RefCatalog, onPick: (TurnRef) -> Unit, onDismiss: () -> Unit) {
    val c = Iris.colors
    var query by remember { mutableStateOf("") }
    com.iris.localai.ui.components.IrisSheet(onDismiss) {
        com.iris.localai.ui.components.SheetHeader(ComposerText.SKILLS)
        if (catalog.size > RefCatalog.SEARCH_FROM) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space4)
                    .height(40.dp)
                    .clip(IrisRadius.pillShape)
                    .background(c.surfaceVariant)
                    .padding(horizontal = IrisDimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(IrisIcons.Search, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(IrisDimens.space8))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(ComposerText.SKILL_SEARCH, style = IrisType.body, color = c.textTertiary)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = IrisType.body.copy(color = c.text), cursorBrush = SolidColor(c.text), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val shown = catalog.filter(query)
        if (catalog.isEmpty) {
            Text(ComposerText.SKILLS_EMPTY, style = IrisType.body, color = c.textSecondary, modifier = Modifier.padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space24))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item { SectionLabel(ComposerText.SECTION_SKILLS) }
                if (shown.skills.isEmpty()) item {
                    EmptyLine(if (catalog.skillToolOff) ComposerText.SKILL_TOOL_OFF else if (query.isBlank()) ComposerText.NO_SKILLS else ComposerText.NO_MATCH)
                }
                items(shown.skills, key = { "s:" + it.ref.name }) { RefRow(it) { onPick(it.ref) } }
                item { SectionLabel(ComposerText.SECTION_TOOLS) }
                if (shown.tools.isEmpty()) item { EmptyLine(if (query.isBlank()) ComposerText.NO_TOOLS else ComposerText.NO_MATCH) }
                items(shown.tools, key = { "t:" + it.ref.name }) { RefRow(it) { onPick(it.ref) } }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = IrisType.labelSmall, color = Iris.colors.textTertiary, modifier = Modifier.padding(start = IrisDimens.pageGutter, top = IrisDimens.space12, bottom = IrisDimens.space4))

@Composable
private fun EmptyLine(text: String) =
    Text(text, style = IrisType.body, color = Iris.colors.textSecondary, modifier = Modifier.padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space8))

@Composable
private fun RefRow(item: RefItem, onClick: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = IrisDimens.pageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (item.ref.kind == TurnRefKind.SKILL) IrisIcons.Skill else IrisIcons.Tool, null, tint = c.textSecondary, modifier = Modifier.size(IrisDimens.space20))
        Spacer(Modifier.width(IrisDimens.space12))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = IrisType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = IrisType.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

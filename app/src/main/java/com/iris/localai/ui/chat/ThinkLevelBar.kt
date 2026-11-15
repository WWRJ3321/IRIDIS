package com.iris.localai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 2.6.0: pure helpers of the 思考档位 bar (tested in V260Test). */
object ThinkLevelBar {
    /** 2.6.1: 低 中 高 only — on / off stays the pill's tap (user: 点一下开、点一下关，长按只调档位). */
    const val SEGMENTS = 3
    const val SEGMENT_DP = 52
    const val HEIGHT_DP = 40
    const val GAP_DP = 8

    /** Level (1 低 .. 3 高) under the finger, [x] in px from the pill's left edge (the bar starts there too). */
    fun indexAt(x: Float, segmentPx: Float): Int =
        if (segmentPx <= 0f || x < 0f) 1 else (x / segmentPx).toInt().coerceIn(0, SEGMENTS - 1) + 1

    /** Level shown as current: the chosen one (中 when none), whether thinking is on or off. */
    @Suppress("UNUSED_PARAMETER")
    fun current(thinking: Boolean, level: Int): Int = if (level in 1..3) level else 2

    fun labels(): List<String> = listOf(
        com.iris.localai.I18n.s(com.iris.localai.R.string.think_lv_low),
        com.iris.localai.I18n.s(com.iris.localai.R.string.think_lv_mid),
        com.iris.localai.I18n.s(com.iris.localai.R.string.think_lv_high),
    )
}

private object Cancelled

/**
 * 思考 pill: tap = on / off (as before); long-press opens 低 中 高 above it, slide to choose,
 * release to apply ([onPick] gets 1..3, which also turns thinking on).
 */
@Composable
internal fun ThinkPill(
    text: String,
    thinking: Boolean,
    level: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onDisabledClick: () -> Unit,
    onPick: (Int) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val segPx = with(density) { ThinkLevelBar.SEGMENT_DP.dp.toPx() }
    val slopPx = with(density) { 8.dp.toPx() }
    var open by remember { mutableStateOf(false) }
    var sel by remember { mutableIntStateOf(0) }
    val cur by rememberUpdatedState(ThinkLevelBar.current(thinking, level))
    val en by rememberUpdatedState(enabled)
    val click by rememberUpdatedState(onClick)
    val disabledClick by rememberUpdatedState(onDisabledClick)
    val pick by rememberUpdatedState(onPick)

    Box(modifier) {
        ComposerPill(
            text, selected = thinking, enabled = enabled, onClick = onClick, icon = icon, customGesture = true,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() ?: Cancelled }
                    if (r === Cancelled) return@awaitEachGesture
                    if (r != null) {
                        (r as androidx.compose.ui.input.pointer.PointerInputChange).consume()
                        if (en) click() else disabledClick()
                        return@awaitEachGesture
                    }
                    if (!en) { disabledClick(); return@awaitEachGesture }
                    sel = cur
                    open = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!moved && abs(ch.position.x - down.position.x) > slopPx) moved = true
                        if (moved) {
                            val i = ThinkLevelBar.indexAt(ch.position.x, segPx)
                            if (i != sel) { sel = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                        ch.consume()
                        if (!ch.pressed) break
                    }
                    open = false
                    pick(sel)
                }
            },
        )
        // 2.6.1: same pop as IrisMenu (加模型 / 加角色 menus): fade + scale from the pill-side corner, Snappy in, Quick out.
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
        shown.targetState = open
        if (shown.currentState || shown.targetState) {
            val reduce = com.iris.localai.ui.theme.LocalReduceMotion.current
            val dy = with(density) { (ThinkLevelBar.HEIGHT_DP + ThinkLevelBar.GAP_DP).dp.roundToPx() }
            val origin = androidx.compose.ui.graphics.TransformOrigin(0.15f, 1f)
            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, -dy)) {
              androidx.compose.animation.AnimatedVisibility(
                visibleState = shown,
                enter = if (reduce) androidx.compose.animation.fadeIn(com.iris.localai.ui.motion.IrisMotion.Quick.float())
                else androidx.compose.animation.fadeIn(com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.FadeIn)) +
                    androidx.compose.animation.scaleIn(com.iris.localai.ui.motion.IrisMotion.Snappy.float(), initialScale = 0.86f, transformOrigin = origin),
                exit = if (reduce) androidx.compose.animation.fadeOut(com.iris.localai.ui.motion.IrisMotion.Quick.float())
                else androidx.compose.animation.fadeOut(com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.FadeOut)) +
                    androidx.compose.animation.scaleOut(com.iris.localai.ui.motion.IrisMotion.Quick.float(), targetScale = 0.92f, transformOrigin = origin),
              ) {
                val c = Iris.colors
                Row(
                    Modifier
                        .shadow(6.dp, IrisRadius.pillShape)
                        .clip(IrisRadius.pillShape)
                        .background(c.surface)
                        .height(ThinkLevelBar.HEIGHT_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThinkLevelBar.labels().forEachIndexed { i, t ->
                        val on = i + 1 == sel
                        Box(
                            Modifier.width(ThinkLevelBar.SEGMENT_DP.dp).fillMaxHeight().padding(3.dp)
                                .clip(IrisRadius.pillShape)
                                .background(if (on) c.accent else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t, style = IrisType.labelSmall.copy(fontSize = IrisType.label.fontSize), color = if (on) c.onAccent else c.text, maxLines = 1)
                        }
                    }
                }
              }
            }
        }
    }
}

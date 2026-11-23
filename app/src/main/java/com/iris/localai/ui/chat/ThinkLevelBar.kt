package com.iris.localai.ui.chat

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.LocalReduceMotion
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
import com.iris.localai.ui.components.glassSurface
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

/** pure helpers of the 思考档位 bar (tested in V260Test). */
object ThinkLevelBar {
    /** 低 中 高 only — on / off stays the pill's tap (user: 点一下开、点一下关，长按只调档位). */
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
        I18n.s(R.string.think_lv_low),
        I18n.s(R.string.think_lv_mid),
        I18n.s(R.string.think_lv_high),
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
    val pick by rememberUpdatedState(onPick)

    Box(modifier) {
        // Beta4 r3：点按交给 ComposerPill 自己的点击（和旁边「智能体」「高速」同一套，之前自定义手势在部分机型上
        // 点按被吞掉、思考关不掉）；这里只在长按弹出档位条后跟踪滑动，松手时应用。
        ComposerPill(
            text, selected = thinking, enabled = enabled, onClick = onClick, icon = icon,
            onDisabledClick = onDisabledClick,
            onLongClick = {
                sel = cur
                open = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (open) {
                            if (!moved && (abs(ch.position.x - down.position.x) > slopPx || abs(ch.position.y - down.position.y) > slopPx)) moved = true
                            if (moved) {
                                val i = ThinkLevelBar.indexAt(ch.position.x, segPx)
                                if (i != sel) { sel = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        }
                        if (!ch.pressed) break
                    }
                    if (open) {
                        open = false
                        // 没滑动就松手 = 只是看一眼，不改开关。
                        ThinkToggle.onRelease(moved, sel)?.let { pick(it) }
                    }
                }
            },
        )
        // same pop as IrisMenu (加模型 / 加角色 menus): fade + scale from the pill-side corner, Snappy in, Quick out.
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
        shown.targetState = open
        if (shown.currentState || shown.targetState) {
            val reduce = LocalReduceMotion.current
            // transparent margin around the bar (as IrisMenu): the popup window no longer clips the
            // soft shadow into a hard square band; the offset compensates so the bar stays put.
            val m = with(density) { com.iris.localai.ui.components.MenuGeometry.MARGIN.roundToPx() }
            val dy = with(density) { (ThinkLevelBar.HEIGHT_DP + ThinkLevelBar.GAP_DP).dp.roundToPx() }
            val origin = androidx.compose.ui.graphics.TransformOrigin(0.15f, 1f)
            Popup(alignment = Alignment.TopStart, offset = IntOffset(-m, -dy - m)) {
              androidx.compose.animation.AnimatedVisibility(
                visibleState = shown,
                enter = if (reduce) androidx.compose.animation.fadeIn(IrisMotion.Quick.float())
                else androidx.compose.animation.fadeIn(com.iris.localai.ui.motion.motionSpec(IrisMotion.FadeIn)) +
                    androidx.compose.animation.scaleIn(IrisMotion.Snappy.float(), initialScale = 0.86f, transformOrigin = origin),
                exit = if (reduce) androidx.compose.animation.fadeOut(IrisMotion.Quick.float())
                else androidx.compose.animation.fadeOut(com.iris.localai.ui.motion.motionSpec(IrisMotion.FadeOut)) +
                    androidx.compose.animation.scaleOut(IrisMotion.Quick.float(), targetScale = 0.92f, transformOrigin = origin),
              ) {
                val c = Iris.colors
                Box(Modifier.padding(com.iris.localai.ui.components.MenuGeometry.MARGIN)) {
                Row(
                    Modifier
                        .glassSurface(IrisRadius.full, com.iris.localai.ui.theme.IrisElevation.menu)
                        .height(ThinkLevelBar.HEIGHT_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThinkLevelBar.labels().forEachIndexed { i, t ->
                        val on = i + 1 == sel
                        Box(
                            Modifier.width(ThinkLevelBar.SEGMENT_DP.dp).fillMaxHeight().padding(3.dp)
                                .clip(IrisRadius.pillShape)
                                .background(com.iris.localai.ui.motion.selectTint(if (on) c.accent else c.accent.copy(alpha = 0f))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t, style = IrisType.labelSmall.copy(fontSize = IrisType.label.fontSize), color = com.iris.localai.ui.motion.selectTint(if (on) c.onAccent else c.text), maxLines = 1)
                        }
                    }
                }
                }
              }
            }
        }
    }
}

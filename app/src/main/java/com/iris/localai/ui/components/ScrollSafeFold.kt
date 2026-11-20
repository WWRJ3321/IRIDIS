package com.iris.localai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt

/**
 * Issue #10: true while the chat list is being dragged or flung. Provided by ChatScreen;
 * outside the chat list it stays false.
 */
val LocalListScrolling = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** when an expand / collapse animation inside a scrolling list stops. Pure. */
object FoldMotion {
    /** Jump straight to the end state: reduced motion, or the list is moving (a growing item under a fling drew garbled text). */
    fun snap(reduceMotion: Boolean, listScrolling: Boolean): Boolean = reduceMotion || listScrolling

    /**
     * 2.7.4：只有用户拖动/甩动列表才算「在滚动」。之前用 isScrollInProgress，生成时贴底自动滚动、
     * 展开思考块引起的跟随滚动也算，折叠/展开动画因此总是直接跳到终点（看起来动画没了）。
     * [prev] 上一状态；[dragged] 手指正拖着列表；[inProgress] 列表在滚（含甩动余势）。
     */
    fun userScrolling(prev: Boolean, dragged: Boolean, inProgress: Boolean): Boolean = when {
        dragged -> true
        !inProgress -> false
        else -> prev
    }

    /** Height shown for [fraction] of a [full]-pixel tall body (springs may overshoot; clamped). */
    fun height(full: Int, fraction: Float): Int = (full * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, full)

    /**
     * bodies taller than this never grow frame by frame — they take their full
     * height at once and only fade. A long thinking block growing inside the list while a fling
     * starts left the upper half of the screen drawing stale text; past one screen the growth is
     * not visible anyway.
     */
    const val TALL_PX = 2400

    /** Height for [fraction] with the tall-body rule: anything open shows its full height when [full] ≥ [TALL_PX]. */
    fun heightFor(full: Int, fraction: Float): Int = if (full >= TALL_PX && fraction > 0f) full else height(full, fraction)
}

/**
 * Expand / collapse for the thinking block and tool-call logs in the chat list. Same look as
 * [Fold] (grows from the top and fades), but the moment the list starts scrolling a running
 * animation jumps to its end: before 2.4.5 a quick swipe while the body was still growing left
 * the top half of the screen drawing text at stale positions.
 */
@Composable
fun ScrollSafeFold(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val scrolling by LocalListScrolling.current
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    val target = if (visible) 1f else 0f
    val snap = FoldMotion.snap(reduce, scrolling)
    LaunchedEffect(target, snap) {
        if (snap || progress.value == target) progress.snapTo(target)
        else progress.animateTo(target, if (visible) IrisMotion.Gentle.float() else IrisMotion.Smooth.float())
    }
    val present by remember(visible) { derivedStateOf { visible || progress.value > 0.001f } }
    if (!present) return
    Layout(
        content = content,
        // ModulateAlpha — the fade no longer renders the whole (possibly very tall) body into an
        // offscreen buffer, which is what drew garbage at the top of the screen mid-scroll.
        modifier = modifier.clipToBounds().graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val full = placeables.maxOfOrNull { it.height } ?: 0
        layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), FoldMotion.heightFor(full, progress.value).coerceIn(constraints.minHeight, constraints.maxHeight)) { placeables.forEach { it.place(0, 0) } }
    }
}

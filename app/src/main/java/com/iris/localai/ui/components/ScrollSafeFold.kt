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
 * 2.4.5 (Issue #10): true while the chat list is being dragged or flung. Provided by ChatScreen;
 * outside the chat list it stays false.
 */
val LocalListScrolling = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** 2.4.5 (#10): when an expand / collapse animation inside a scrolling list stops. Pure. */
object FoldMotion {
    /** Jump straight to the end state: reduced motion, or the list is moving (a growing item under a fling drew garbled text). */
    fun snap(reduceMotion: Boolean, listScrolling: Boolean): Boolean = reduceMotion || listScrolling

    /** Height shown for [fraction] of a [full]-pixel tall body (springs may overshoot; clamped). */
    fun height(full: Int, fraction: Float): Int = (full * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, full)
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
        modifier = modifier.clipToBounds().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val full = placeables.maxOfOrNull { it.height } ?: 0
        layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), FoldMotion.height(full, progress.value).coerceIn(constraints.minHeight, constraints.maxHeight)) { placeables.forEach { it.place(0, 0) } }
    }
}

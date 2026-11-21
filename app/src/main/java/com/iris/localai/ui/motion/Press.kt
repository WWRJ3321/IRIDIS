package com.iris.localai.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * Press feedback as a spring that can be interrupted at any point: finger down springs toward
 * [PressMotion.pressedScale]; finger up rebounds elastically from wherever it is, carrying the
 * current velocity. A tap that is released before the dip is visible still gets one: the
 * rebound starts with a downward kick, so it dips and springs back (iOS behaviour).
 */
@Stable
class PressMotion internal constructor(val pressedScale: Float) {
    internal val scale = Animatable(1f)
    /** 0 = released … 1 = fully pressed; read it in draw / graphicsLayer only. */
    val progress: Float get() = if (pressedScale == 1f) 0f else ((1f - scale.value) / (1f - pressedScale)).coerceIn(-0.5f, 1.5f)
    val value: Float get() = scale.value

    companion object {
        /** Initial velocity (scale units / s) given to a quick tap's rebound so the dip still shows. */
        fun tapKick(pressedScale: Float): Float = (pressedScale - 1f) * 9f
        /** A press shorter than this gets the kick. */
        const val QUICK_TAP_MS = 90L
        /** Near-square press targets (icon buttons) draw a round highlight. */
        fun isIconLike(w: Float, h: Float): Boolean {
            if (w <= 0f || h <= 0f) return false
            val r = if (w > h) w / h else h / w
            return r <= 1.35f
        }
    }
}

@Composable
fun rememberPressMotion(interaction: InteractionSource, pressedScale: Float): PressMotion {
    val reduce = LocalReduceMotion.current
    val state = remember(pressedScale) { PressMotion(pressedScale) }
    LaunchedEffect(interaction, reduce, pressedScale) {
        if (reduce) { state.scale.snapTo(1f); return@LaunchedEffect }
        var downAt = 0L
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    downAt = System.currentTimeMillis()
                    launch { state.scale.animateTo(pressedScale, IrisMotion.Press.float()) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val quick = System.currentTimeMillis() - downAt < PressMotion.QUICK_TAP_MS
                    val v = if (quick && i is PressInteraction.Release) PressMotion.tapKick(pressedScale) else state.scale.velocity
                    launch { state.scale.animateTo(1f, IrisMotion.Rebound.float(), initialVelocity = v) }
                }
            }
        }
    }
    return state
}

/** Scales the element with [press]; graphicsLayer only (no recomposition, no relayout per frame). */
fun Modifier.pressScale(press: PressMotion, origin: TransformOrigin = TransformOrigin.Center): Modifier =
    graphicsLayer {
        val s = press.value
        scaleX = s; scaleY = s
        transformOrigin = origin
    }

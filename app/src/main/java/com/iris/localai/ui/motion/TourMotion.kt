package com.iris.localai.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween

/**
 * 2.5.3 新手引导 motion tokens (kept here: animation specs live in ui/motion only).
 * The spotlight hole glides between targets in [HOLE_MS] with FastOutSlowIn; the scrim fades in once;
 * the accent ring around the hole pulses outward every [PULSE_MS].
 */
object TourMotion {
    const val HOLE_MS = 350
    const val APPEAR_MS = 220
    const val PULSE_MS = 1400

    fun <T> hole(): TweenSpec<T> = tween(HOLE_MS, easing = FastOutSlowInEasing)
    fun <T> appear(): TweenSpec<T> = tween(APPEAR_MS, easing = FastOutSlowInEasing)
    fun <T> pulse(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart)
}

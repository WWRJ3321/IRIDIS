package com.iris.localai.ui.motion

import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable

/**
 * 新手引导 motion tokens (kept here: animation specs live in ui/motion only).
 * The spotlight hole glides between targets in [HOLE_MS] with FastOutSlowIn; the scrim fades in once;
 * the accent ring around the hole pulses outward every [PULSE_MS].
 */
object TourMotion {
    const val HOLE_MS = 350
    const val APPEAR_MS = 220
    const val PULSE_MS = 1400

    // 统一用 IrisMotion 的曲线（不再各自写 tween）。
    fun <T> hole(): DurationBasedAnimationSpec<T> = IrisMotion.progress(HOLE_MS)
    fun <T> appear(): DurationBasedAnimationSpec<T> = IrisMotion.progress(APPEAR_MS)
    fun <T> pulse(): InfiniteRepeatableSpec<T> = infiniteRepeatable(IrisMotion.linearLoop(PULSE_MS), RepeatMode.Restart)
}

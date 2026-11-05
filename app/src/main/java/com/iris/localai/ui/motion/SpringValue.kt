package com.iris.localai.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A float that a finger can grab at any moment and a spring can release, used by the drawer,
 * sheets, the notice banner swipe and the droplet. Unlike `Animatable.snapTo` (suspending,
 * one frame late when launched from a gesture callback), [dragBy] updates the value
 * synchronously, so the surface sits exactly under the finger. Any running spring is cancelled
 * the instant a drag starts and a new spring starts from the current value *and* velocity, so
 * nothing ever jumps.
 *
 * Read [value] only in draw / graphicsLayer / offset lambdas to avoid recomposition per frame.
 */
@Stable
class SpringValue(initial: Float, private val scope: CoroutineScope) {
    var value by mutableFloatStateOf(initial)
        private set
    /** px/s (or units/s): last velocity of the spring or of the drag (set by [release]). */
    var velocity: Float = 0f
        private set
    var target: Float = initial
        private set
    /** Unconstrained position while dragging, so rubber banding is reversible. */
    private var raw: Float = initial
    private var job: Job? = null


    fun snapTo(v: Float) {
        job?.cancel()
        value = v; raw = v; target = v; velocity = 0f
    }

    /** Stops any spring where it is (finger down during a settle). */
    fun stop() {
        job?.cancel()
        raw = value
        target = value
    }

    /**
     * Moves by a finger delta. Inside [min]..[max] it tracks 1:1; beyond, it rubber-bands against
     * [dimension] (pass 0 to hard-clamp instead).
     */
    fun dragBy(delta: Float, min: Float, max: Float, dimension: Float = 0f) {
        if (job?.isActive == true) stop()
        raw += delta
        value = constrain(raw, min, max, dimension)
        target = value
    }

    /** Remembers the release velocity of a gesture so the following spring inherits it. */
    fun release(velocityPx: Float) { velocity = velocityPx }

    fun animateTo(
        targetValue: Float,
        spec: AnimationSpec<Float>,
        initialVelocity: Float = velocity,
        onEnd: (() -> Unit)? = null,
    ): Job {
        job?.cancel()
        target = targetValue
        val start = value
        val j = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animate(start, targetValue, initialVelocity, spec) { v, vel ->
                value = v; raw = v; velocity = vel
            }
            velocity = 0f
            onEnd?.invoke()
        }
        job = j
        return j
    }

    companion object {
        fun constrain(raw: Float, min: Float, max: Float, dimension: Float): Float = when {
            raw < min -> if (dimension > 0f) min + MotionMath.rubberBand(raw - min, dimension) else min
            raw > max -> if (dimension > 0f) max + MotionMath.rubberBand(raw - max, dimension) else max
            else -> raw
        }
    }
}

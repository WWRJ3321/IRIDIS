package com.iris.localai.ui.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure motion math (no Android / Compose types), so every curve the app uses is unit-tested.
 *
 * Springs are described the way iOS / SwiftUI does it: a **response** (seconds for one
 * undamped oscillation; smaller = snappier) and a **damping fraction** (1 = no overshoot,
 * < 1 = a little bounce). Compose wants stiffness + damping ratio for a unit mass:
 * stiffness = (2π / response)², dampingRatio = damping fraction.
 */
object MotionMath {

    fun stiffness(response: Float): Float {
        require(response > 0f) { "response must be > 0" }
        val w = 2.0 * PI / response
        return (w * w).toFloat()
    }

    /**
     * Normalised position (0 → 1) of a spring released from rest at 0 toward 1 after [t] seconds.
     * Closed-form solution of x'' + 2ζω x' + ω²(x − 1) = 0, used by the tests to check overshoot and
     * settle time of every token, and by [settleTime].
     */
    fun springValue(t: Float, response: Float, damping: Float, initialVelocity: Float = 0f): Float {
        if (t <= 0f) return 0f
        val w = 2.0 * PI / response
        val z = damping.toDouble()
        val v0 = initialVelocity.toDouble()
        val x0 = -1.0 // displacement from the target
        val tt = t.toDouble()
        val x = when {
            z < 1.0 -> {
                val wd = w * sqrt(1 - z * z)
                exp(-z * w * tt) * (x0 * cos(wd * tt) + ((v0 + z * w * x0) / wd) * sin(wd * tt))
            }
            z == 1.0 -> exp(-w * tt) * (x0 + (v0 + w * x0) * tt)
            else -> {
                val s = w * sqrt(z * z - 1)
                val r1 = -z * w + s
                val r2 = -z * w - s
                val c2 = (v0 - r1 * x0) / (r2 - r1)
                val c1 = x0 - c2
                c1 * exp(r1 * tt) + c2 * exp(r2 * tt)
            }
        }
        return (1.0 + x).toFloat()
    }

    /** Largest overshoot past the target as a fraction of the travel (0 for damping ≥ 1). */
    fun overshoot(damping: Float): Float {
        if (damping >= 1f) return 0f
        val z = damping.toDouble()
        return exp(-z * PI / sqrt(1 - z * z)).toFloat()
    }

    /** Seconds until the spring stays within [epsilon] of the target (sampled at 120 Hz). */
    fun settleTime(response: Float, damping: Float, epsilon: Float = 0.001f, initialVelocity: Float = 0f): Float {
        val dt = 1f / 120f
        var lastOutside = 0f
        var t = 0f
        while (t < 10f) {
            if (abs(1f - springValue(t, response, damping, initialVelocity)) > epsilon) lastOutside = t
            t += dt
        }
        return lastOutside + dt
    }

    /**
     * iOS-style rubber banding for dragging past an edge: the further you pull, the less it moves.
     * [overscroll] px beyond the limit, [dimension] = size of the dragged thing, [c] = 0.55 like UIScrollView.
     */
    fun rubberBand(overscroll: Float, dimension: Float, c: Float = 0.55f): Float {
        if (overscroll == 0f || dimension <= 0f) return 0f
        val x = abs(overscroll)
        val r = (1f - 1f / (x * c / dimension + 1f)) * dimension
        return if (overscroll < 0f) -r else r
    }

    /**
     * Where a fling would come to rest (UIScrollView "projection"): position + v·r / (1 − r)·(1/1000)
     * with deceleration rate r per millisecond (0.998 = normal). [velocity] in px/s.
     */
    fun project(position: Float, velocity: Float, decelerationRate: Float = 0.998f): Float =
        position + (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /**
     * Picks the anchor a released gesture should settle at: the anchor nearest to the projected
     * resting point, so a quick flick commits even from a short drag and a slow drag settles to
     * the nearer side.
     */
    fun settleAnchor(position: Float, velocity: Float, anchors: FloatArray, decelerationRate: Float = 0.99f): Float {
        require(anchors.isNotEmpty())
        val p = project(position, velocity, decelerationRate)
        return anchors.minBy { abs(it - p) }
    }

    /** Linear interpolation, and its clamped inverse. */
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun progress(value: Float, from: Float, to: Float): Float =
        if (from == to) 1f else ((value - from) / (to - from)).coerceIn(0f, 1f)


}

package com.iris.localai.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 2.2.10 性能 ›「终止温度」: unlike [ThermalGovernor] (which only slows tokens down), reaching this
 * temperature stops every generation and unloads every model; nothing reloads by itself.
 * Inputs: battery temperature (°C) plus the system's own signals.
 *
 * 2.4.2:
 *  - The system signal used to trip on a single THERMAL_STATUS_SEVERE or a single predicted
 *    headroom ≥ 1.0 — on HyperOS that happens for a moment under any big model, so the cutoff
 *    fired at a battery of 4x°C while the notice showed that battery temperature ("没到就停").
 *    Now: status ≥ CRITICAL, or headroom ≥ 1.0 held for [HEADROOM_HOLD_MS] over at least
 *    [HEADROOM_READINGS] readings. SEVERE / a single headroom spike are left to 温度限速.
 *  - The notice says which signal tripped ([Reason]).
 *  - The latch used to stay set until the phone cooled [REARM_C] below the setting, so a model
 *    reloaded meanwhile was never stopped again. Now every new load / generation / image calls
 *    [refusal] (refuses while still over) and then [rearm].
 */
object ThermalCutoff {
    const val MIN_C = 40
    const val MAX_C = 60
    const val DEFAULT_C = 48
    const val REARM_C = 2f
    /** PowerManager.THERMAL_STATUS_SEVERE (kept for the cool-down check). */
    const val STATUS_SEVERE = 3
    /** PowerManager.THERMAL_STATUS_CRITICAL: the system itself is about to shut things down. */
    const val STATUS_CRITICAL = 4
    const val HEADROOM_HOLD_MS = 20_000L
    const val HEADROOM_READINGS = 3

    enum class Reason { BATTERY, SYSTEM }

    @Volatile var cutoffC: Int = 0
        private set
    @Volatile private var latched = false
    @Volatile var lastTempC: Float = Float.NaN
        private set
    @Volatile var lastReason: Reason = Reason.BATTERY
        private set
    @Volatile private var headroomSince = 0L
    @Volatile private var headroomCount = 0

    /** Emits the temperature (°C, NaN if unknown) each time the cutoff trips. */
    private val _trips = MutableSharedFlow<Float>(extraBufferCapacity = 4)
    val trips: SharedFlow<Float> = _trips.asSharedFlow()

    /** 0 = off; anything else is clamped to [MIN_C]..[MAX_C]. */
    fun sanitize(c: Int): Int = if (c <= 0) 0 else c.coerceIn(MIN_C, MAX_C)

    fun setCutoff(c: Int) { cutoffC = sanitize(c); if (cutoffC == 0) { latched = false; resetHeadroom() } }

    /** Pure decision (unit-tested): the battery reached the cutoff, or the system reports CRITICAL. */
    fun over(tempC: Float, cutoffC: Int, thermalStatus: Int = 0): Boolean = reason(tempC, cutoffC, thermalStatus) != null

    fun reason(tempC: Float, cutoffC: Int, thermalStatus: Int = 0): Reason? = when {
        cutoffC <= 0 -> null
        !tempC.isNaN() && tempC >= cutoffC -> Reason.BATTERY
        thermalStatus >= STATUS_CRITICAL -> Reason.SYSTEM
        else -> null
    }

    /** Cooled down enough to arm again. */
    fun cooled(tempC: Float, cutoffC: Int, thermalStatus: Int = 0): Boolean =
        (tempC.isNaN() || tempC <= cutoffC - REARM_C) && thermalStatus < STATUS_SEVERE

    /** Headroom ≥ 1.0 sustained (not a single prediction spike). */
    private fun headroomHeld(headroom: Float, nowMs: Long): Boolean {
        if (headroom.isNaN() || headroom < 1f) { resetHeadroom(); return false }
        if (headroomCount == 0) headroomSince = nowMs
        headroomCount++
        return headroomCount >= HEADROOM_READINGS && nowMs - headroomSince >= HEADROOM_HOLD_MS
    }

    private fun resetHeadroom() { headroomCount = 0; headroomSince = 0L }

    /**
     * Feed a reading; [active] = a model is loaded or something is generating. Returns true (and
     * emits on [trips]) when the caller must stop and unload everything now.
     */
    fun check(tempC: Float, thermalStatus: Int, headroom: Float, active: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!tempC.isNaN()) lastTempC = tempC
        val c = cutoffC
        if (c <= 0) { resetHeadroom(); return false }
        if (latched && cooled(tempC, c, thermalStatus)) latched = false
        val held = headroomHeld(headroom, nowMs)
        val why = reason(tempC, c, thermalStatus) ?: if (held) Reason.SYSTEM else null
        if (latched || !active || why == null) return false
        latched = true
        lastReason = why
        resetHeadroom()
        _trips.tryEmit(tempC)
        return true
    }

    /** A new load / generation is starting: arm again so a later overheat stops it too. */
    fun rearm() { latched = false; resetHeadroom() }

    /** Non-null (the reason to show) when a new load / generation must not start: still over the cutoff. */
    fun refusal(tempC: Float = lastTempC, cutoff: Int = cutoffC): String? =
        if (cutoff > 0 && !tempC.isNaN() && tempC >= cutoff) "当前电池 %.1f°C，不低于终止温度 %d°C，请先让手机降温".format(tempC, cutoff) else null

    /** Refuses (returns the reason) or re-arms (returns null). */
    fun startOrRefuse(): String? = refusal() ?: run { rearm(); null }

    fun notice(tempC: Float, reason: Reason = lastReason): String =
        "温度过高，已停止并卸载模型（" + when {
            reason == Reason.SYSTEM -> "系统报告严重过热" + (if (tempC.isNaN()) "" else "，电池 %.1f°C".format(tempC))
            tempC.isNaN() -> "系统过热"
            else -> "电池 %.1f°C".format(tempC)
        } + "）"
}

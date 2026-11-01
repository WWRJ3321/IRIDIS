package com.iris.localai.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 2.2.10 性能 ›「终止温度」: unlike [ThermalGovernor] (which only slows tokens down), reaching this
 * temperature stops every generation and unloads every model; nothing reloads by itself. The trip
 * latches until the phone has cooled [REARM_C] below the setting, so one hot spell trips once.
 * Inputs: battery temperature (°C) plus, when the system reports it, PowerManager thermal status
 * (≥ SEVERE) or thermal headroom (≥ 1.0 = severe throttling imminent).
 */
object ThermalCutoff {
    const val MIN_C = 40
    const val MAX_C = 60
    const val DEFAULT_C = 48
    const val REARM_C = 2f
    /** PowerManager.THERMAL_STATUS_SEVERE. */
    const val STATUS_SEVERE = 3

    @Volatile var cutoffC: Int = 0
        private set
    @Volatile private var latched = false

    /** Emits the temperature (°C, NaN if unknown) each time the cutoff trips. */
    private val _trips = MutableSharedFlow<Float>(extraBufferCapacity = 4)
    val trips: SharedFlow<Float> = _trips.asSharedFlow()

    /** 0 = off; anything else is clamped to [MIN_C]..[MAX_C]. */
    fun sanitize(c: Int): Int = if (c <= 0) 0 else c.coerceIn(MIN_C, MAX_C)

    fun setCutoff(c: Int) { cutoffC = sanitize(c); if (cutoffC == 0) latched = false }

    /** Pure decision (unit-tested): over the cutoff by temperature or by the system's own signal. */
    fun over(tempC: Float, cutoffC: Int, thermalStatus: Int = 0, headroom: Float = Float.NaN): Boolean {
        if (cutoffC <= 0) return false
        return (!tempC.isNaN() && tempC >= cutoffC) || thermalStatus >= STATUS_SEVERE || (!headroom.isNaN() && headroom >= 1f)
    }

    /** Cooled down enough to arm again. */
    fun cooled(tempC: Float, cutoffC: Int, thermalStatus: Int = 0): Boolean =
        (tempC.isNaN() || tempC <= cutoffC - REARM_C) && thermalStatus < STATUS_SEVERE

    /**
     * Feed a reading; [active] = a model is loaded or something is generating. Returns true (and
     * emits on [trips]) when the caller must stop and unload everything now.
     */
    fun check(tempC: Float, thermalStatus: Int, headroom: Float, active: Boolean): Boolean {
        val c = cutoffC
        if (latched && cooled(tempC, c, thermalStatus)) latched = false
        if (latched || !active || !over(tempC, c, thermalStatus, headroom)) return false
        latched = true
        _trips.tryEmit(tempC)
        return true
    }

    fun notice(tempC: Float): String =
        "温度过高，已停止并卸载模型（" + (if (tempC.isNaN()) "系统过热" else "%.1f°C".format(tempC)) + "）"
}

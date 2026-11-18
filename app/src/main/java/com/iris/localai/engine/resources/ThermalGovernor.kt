package com.iris.localai.engine.resources

import com.iris.localai.engine.*
import com.iris.localai.engine.load.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 温度限速. When the phone (battery sensor, °C) reaches the user's limit (35–50, 0 = off),
 * generation is paced: the decoding thread sleeps between tokens, so the CPU / GPU really idles and
 * heats less (a delay in the collector would not help: the engines buffer without limit).
 * The further over the limit, the slower: 10 → 6 → 4 → 2 tok/s. Released 1 °C below the limit.
 */
object ThermalGovernor {
    data class Status(val tempC: Float = Float.NaN, val limitC: Int = 0, val throttling: Boolean = false, val capTps: Int = 0)

    const val MIN_C = 35
    const val MAX_C = 50
    const val HYSTERESIS_C = 1f

    @Volatile var limitC: Int = 0
        private set
    @Volatile var tempC: Float = Float.NaN
        private set
    @Volatile private var engaged = false
    @Volatile private var lastTokenNs = 0L

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 0 = off; anything else is clamped to [MIN_C]..[MAX_C]. */
    fun sanitize(c: Int): Int = if (c <= 0) 0 else c.coerceIn(MIN_C, MAX_C)

    /** Minimum milliseconds between two tokens (0 = no limit). Pure, unit-tested. */
    fun intervalMs(tempC: Float, limitC: Int, engaged: Boolean): Long {
        if (limitC <= 0 || tempC.isNaN()) return 0L
        val over = tempC - limitC
        if (over < (if (engaged) -HYSTERESIS_C else 0f)) return 0L
        return when {
            over < 1f -> 100L   // 10 tok/s
            over < 2f -> 167L   // 6 tok/s
            over < 3f -> 250L   // 4 tok/s
            else -> 500L        // 2 tok/s
        }
    }

    fun setLimit(c: Int) { limitC = sanitize(c); refresh() }

    fun onTemperature(c: Float) { tempC = c; refresh(); CoolDown.onTemperature(c) }

    private fun refresh() {
        val iv = intervalMs(tempC, limitC, engaged)
        engaged = iv > 0
        _status.value = Status(tempC, limitC, engaged, if (iv > 0) (1000 / iv).toInt() else 0)
    }

    /** Call when a reply starts. */
    fun beginReply() { lastTokenNs = 0L }

    /**
     * Call on the decoding thread for every streamed piece. Sleeps (in short slices, so a stop is
     * still prompt) until the paced interval since the last piece has passed.
     */
    fun pace(cancelled: () -> Boolean = { false }) {
        val iv = intervalMs(tempC, limitC, engaged)
        if ((iv > 0) != engaged) refresh()
        if (iv > 0 && lastTokenNs != 0L) {
            val until = lastTokenNs + iv * 1_000_000L
            while (!cancelled()) {
                val left = (until - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                try { Thread.sleep(left.coerceAtMost(50L)) } catch (_: InterruptedException) { break }
            }
        }
        lastTokenNs = System.nanoTime()
    }
}

package com.iris.localai.engine.resources

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.*
import com.iris.localai.engine.load.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 过热断续 + 少算（Edge0 与 llama 等所有引擎共用，入口在 EngineManager）。
 *
 * 用户实测：降线程/限速降温效果差——每个 token 的发热基本固定，降速只是把热摊开。所以这里不降速，
 * 只做两件真正减少总发热的事：
 *  1. 断续：电池温度到上限 [limitC] 后，当前回答照常说完（可选 [stopNow] 立即停），之后新的生成先
 *     「降温中」等待，降到 上限 − [HYST_C] 自动继续。
 *  2. 少算：接近上限（≥ 上限 − [HOT_MARGIN_C]）时，思考上限收紧到低档、单次最多输出 [HOT_MAX_NEW] token。
 */
object CoolDown {
    const val MIN_C = 38
    const val MAX_C = 55
    const val DEFAULT_C = 45
    const val HYST_C = 3f
    const val HOT_MARGIN_C = 2f
    const val HOT_MAX_NEW = 1024
    const val POLL_MS = 2_000L

    data class Status(val tempC: Float = Float.NaN, val limitC: Int = DEFAULT_C, val cooling: Boolean = false, val hot: Boolean = false)

    @Volatile var limitC: Int = DEFAULT_C; private set
    @Volatile var stopNow: Boolean = false
    @Volatile var cooling: Boolean = false; private set
    /** Fresh battery temperature (sticky broadcast) for the wait loop; set by IrisApp. */
    @Volatile var poll: () -> Float = { Float.NaN }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun sanitize(c: Int): Int = if (c <= 0) 0 else c.coerceIn(MIN_C, MAX_C)

    /** Hysteresis: start cooling at ≥ limit, resume at ≤ limit − 3 °C. Off / no reading → never cooling. Pure. */
    fun next(cooling: Boolean, tempC: Float, limitC: Int): Boolean = when {
        limitC <= 0 || tempC.isNaN() -> false
        !cooling -> tempC >= limitC
        else -> tempC > limitC - HYST_C
    }

    /** Close to the limit: compute less (thinking + output caps). Pure. */
    fun hot(tempC: Float, limitC: Int): Boolean = limitC > 0 && !tempC.isNaN() && tempC >= limitC - HOT_MARGIN_C

    /** Thinking budget when hot: at most the 低 tier (0 = no own cap → 低). Pure. */
    fun thinkBudget(budget: Int, hot: Boolean): Int = when {
        !hot -> budget
        budget <= 0 || budget > ThinkLevel.LOW_TOKENS -> ThinkLevel.LOW_TOKENS
        else -> budget
    }

    /** Reasoning effort for models with levels when hot: 低. 0 (model default) → 低 too. Pure. */
    fun effort(effort: Int, hasLevels: Boolean, hot: Boolean): Int = if (hot && hasLevels) ThinkLevel.LOW else effort

    /** One reply's output cap when hot. Pure. */
    fun maxNew(maxNew: Int, hot: Boolean): Int = if (!hot) maxNew else if (maxNew <= 0) HOT_MAX_NEW else minOf(maxNew, HOT_MAX_NEW)

    /** 立即停止: over the limit while a reply runs. Pure. */
    fun shouldStop(stopNow: Boolean, tempC: Float, limitC: Int): Boolean = stopNow && limitC > 0 && !tempC.isNaN() && tempC >= limitC

    fun setLimit(c: Int, stop: Boolean) { limitC = sanitize(c); stopNow = stop; refresh(ThermalGovernor.tempC) }

    fun onTemperature(c: Float) = refresh(c)

    private fun refresh(t: Float) {
        cooling = next(cooling, t, limitC)
        _status.value = Status(t, limitC, cooling, hot(t, limitC))
    }

    fun hotNow(): Boolean = hot(ThermalGovernor.tempC, limitC)
    fun stopNowHit(): Boolean = shouldStop(stopNow, ThermalGovernor.tempC, limitC)

    /** 「降温中 46.2°C，降到 42°C 自动继续」 */
    fun waitingText(tempC: Float = ThermalGovernor.tempC): String =
        I18n.s(R.string.cool267_wait, String.format(java.util.Locale.US, "%.1f", tempC), (limitC - HYST_C).toInt())

    /** Waits (cancellable) until cooled; [onWait] once when it has to wait. */
    suspend fun awaitCool(onWait: (String) -> Unit = {}) {
        if (!cooling) return
        onWait(waitingText())
        while (cooling) {
            delay(POLL_MS)
            poll().takeIf { !it.isNaN() }?.let { ThermalGovernor.onTemperature(it) } // → CoolDown.onTemperature
        }
    }
}

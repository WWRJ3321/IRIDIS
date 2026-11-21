package com.iris.localai.ui.toolbox

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.task.TaskProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 暖手宝的安全线（纯 JVM，单测）。 */
object WarmerPolicy {
    /** 电池温度到这里就停（°C）。 */
    const val MAX_BATTERY_C = 42f
    /** 到这个温度之前全速，之后只开一半线程（°C）。 */
    const val EASE_BATTERY_C = 39f
    const val MAX_MINUTES = 30

    enum class Verdict { RUN, EASE, STOP }

    /** [thermal] = PowerManager.THERMAL_STATUS_*；[batteryC] 未知 = NaN。 */
    fun verdict(batteryC: Float, thermal: Int, charging: Boolean): Verdict = when {
        thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Verdict.STOP
        batteryC.isFinite() && batteryC >= (if (charging) MAX_BATTERY_C - 2f else MAX_BATTERY_C) -> Verdict.STOP
        thermal >= PowerManager.THERMAL_STATUS_MODERATE -> Verdict.EASE
        batteryC.isFinite() && batteryC >= EASE_BATTERY_C -> Verdict.EASE
        else -> Verdict.RUN
    }

    fun threads(cores: Int, v: Verdict): Int = when (v) {
        Verdict.RUN -> (cores - 1).coerceAtLeast(1)
        Verdict.EASE -> (cores / 2).coerceAtLeast(1)
        Verdict.STOP -> 0
    }
}

/**
 * 3.0 AI 工具箱 · 冬天暖手宝：让 CPU 空转发热，到时间、电池过热或系统温控告警就自己停。
 * 进度挂 [TaskProgress]（灵动岛可见、可停）。
 */
object HandWarmer {
    data class State(val running: Boolean = false, val endsAt: Long = 0, val batteryC: Float = Float.NaN, val threads: Int = 0, val stoppedBy: String? = null)

    private const val ID = "handwarmer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var want = 0

    fun start(ctx: Context, minutes: Int) {
        stop(null)
        val app = ctx.applicationContext
        val mins = minutes.coerceIn(1, WarmerPolicy.MAX_MINUTES)
        val end = System.currentTimeMillis() + mins * 60_000L
        val cores = Runtime.getRuntime().availableProcessors()
        TaskProgress.begin(ID, TaskProgress.Kind.OTHER, I18n.s(R.string.v30_warmer), total = mins * 60) { stop(null) }
        job = scope.launch {
            val workers = ArrayList<Job>()
            repeat(cores) { i -> workers += launch(Dispatchers.Default) { burn(i) } }
            try {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now >= end) { finish(null); break }
                    val (temp, charging) = battery(app)
                    val thermal = app.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                    val v = WarmerPolicy.verdict(temp, thermal, charging)
                    if (v == WarmerPolicy.Verdict.STOP) { finish(I18n.s(R.string.v30_warmer_hot)); break }
                    want = WarmerPolicy.threads(cores, v)
                    _state.value = State(true, end, temp, want)
                    TaskProgress.progress(ID, ((mins * 60_000L - (end - now)) / 1000).toInt(), mins * 60)
                    delay(2000)
                }
            } finally {
                want = 0
                workers.forEach { it.cancel() }
            }
        }
    }

    fun stop(reason: String?) {
        job?.cancel(); job = null; want = 0
        if (TaskProgress.running(ID)) TaskProgress.finish(ID, TaskProgress.Phase.STOPPED)
        _state.value = _state.value.copy(running = false, threads = 0, stoppedBy = reason)
    }

    private fun finish(reason: String?) {
        want = 0
        TaskProgress.finish(ID, if (reason == null) TaskProgress.Phase.DONE else TaskProgress.Phase.STOPPED, error = reason)
        _state.value = _state.value.copy(running = false, threads = 0, stoppedBy = reason)
    }

    /** 一个核一个循环；编号 ≥ [want] 的就歇着。 */
    private suspend fun burn(index: Int) {
        var x = 1.0001
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (index >= want) { delay(300); continue }
            for (k in 0 until 200_000) x = x * 1.0000001 + 0.0000001 / x
            if (x > 1e9) x = 1.0001
        }
    }

    private fun battery(ctx: Context): Pair<Float, Boolean> {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (t == Int.MIN_VALUE) Float.NaN else t / 10f) to plugged
    }
}

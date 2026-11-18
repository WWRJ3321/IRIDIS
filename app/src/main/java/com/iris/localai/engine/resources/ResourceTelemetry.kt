package com.iris.localai.engine.resources

import com.iris.localai.engine.*
import com.iris.localai.engine.load.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * evidence on a real phone that a lower resource tier really draws less. Logged under tag
 * IrisResource; 1.7.6 also keeps the last reply's numbers for 设置 › 资源诊断 ([lastReport]).
 *
 * While a reply runs: battery current (BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) every 500 ms,
 * and every 250 ms which CPU each inference thread is running on (/proc/self/task/<tid>/stat field
 * 39, only while the thread is in state R). Samples are grouped in segments, one per tier: a tier
 * change during the reply closes the segment ("before") and opens the next ("after"). At the end
 * of the reply: per segment average mA, process CPU ms (Process.getElapsedCpuTime), CPU ms per
 * output piece, average busy cores, and the CPU histogram; for the reply CPU ms per token.
 */
object CurrentNorm {
    /**
     * Raw CURRENT_NOW → magnitude in mA, or null when the device does not report it. Android
     * documents µA, positive = charging; many phones (several Xiaomi models among them) report
     * discharge as positive and some report mA. Magnitude only; a value ≥ 20 000 is taken as µA
     * (a phone never draws 20 A, and never less than 20 mA with the screen on).
     */
    fun toMilliAmps(raw: Long?): Float? {
        if (raw == null || raw == 0L || raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong()) return null
        val abs = kotlin.math.abs(raw)
        return if (abs >= UA_THRESHOLD) abs / 1000f else abs.toFloat()
    }

    const val UA_THRESHOLD = 20_000L
}

/** One thread sample: state letter, CPU it runs / last ran on, utime+stime in clock ticks. */
data class TaskSample(val state: Char, val processor: Int, val cpuTicks: Long)

/** Parses /proc/<pid>/task/<tid>/stat (comm may contain spaces and parentheses). */
object ProcStat {
    fun parse(line: String): TaskSample? {
        val close = line.lastIndexOf(')')
        if (close < 0 || close + 2 >= line.length) return null
        val f = line.substring(close + 2).trim().split(' ')
        // f[0] = field 3 (state); field n is f[n - 3]
        if (f.size < 37) return null
        val utime = f[11].toLongOrNull() ?: return null
        val stime = f[12].toLongOrNull() ?: return null
        val cpu = f[36].toIntOrNull() ?: return null
        return TaskSample(f[0].firstOrNull() ?: '?', cpu, utime + stime)
    }
}

/** Accumulates one tier's share of a reply. Pure; unit-tested. */
class TelemetrySegment(val tier: String, val startCpuMs: Long, val startWallMs: Long) {
    var pieces = 0
    private var maSum = 0.0
    var maSamples = 0
        private set
    val coreHist = sortedMapOf<Int, Int>()
    var endCpuMs = startCpuMs
    var endWallMs = startWallMs
    /** wall time of the first / last output piece in this segment (-1 = none yet). */
    var firstPieceWallMs = -1L
    var lastPieceWallMs = -1L

    fun addPiece(wall: Long) {
        pieces++
        if (firstPieceWallMs < 0) firstPieceWallMs = wall
        lastPieceWallMs = wall
    }

    fun addCurrent(ma: Float?) { if (ma != null) { maSum += ma; maSamples++ } }
    fun addCores(samples: Collection<TaskSample>) {
        for (s in samples) if (s.state == 'R' && s.processor >= 0) coreHist[s.processor] = (coreHist[s.processor] ?: 0) + 1
    }
    fun close(cpuMs: Long, wallMs: Long) { endCpuMs = cpuMs; endWallMs = wallMs }

    val avgMa: Float? get() = if (maSamples == 0) null else (maSum / maSamples).toFloat()
    val cpuMs: Long get() = (endCpuMs - startCpuMs).coerceAtLeast(0)
    val wallMs: Long get() = (endWallMs - startWallMs).coerceAtLeast(0)
    val busyCores: Float get() = if (wallMs <= 0) 0f else cpuMs.toFloat() / wallMs

    fun coreText(): String {
        val total = coreHist.values.sum()
        if (total == 0) return "-"
        return coreHist.entries.joinToString(" ") { "cpu${it.key} ${it.value * 100 / total}%" }
    }

    fun describe(charging: Boolean): String {
        val ma = avgMa?.let { "%.0f mA (%d samples%s)".format(it, maSamples, if (charging) ", CHARGING: not consumption" else "") }
            ?: "n/a (no CURRENT_NOW)"
        val perPiece = if (pieces > 0) "%.1f".format(cpuMs.toFloat() / pieces) else "-"
        val busy = "%.2f".format(busyCores)
        return "[$tier] ${wallMs}ms, current avg $ma, process CPU ${cpuMs}ms (${perPiece} ms/piece, $pieces pieces, " +
            "$busy busy cores) | inference threads on: ${coreText()}"
    }
}

/**
 * Drives the segments of the running reply. Providers are injected so the logic is testable off
 * device; [ResourceTelemetry.android] wires the real ones.
 */
class ResourceTelemetry(
    private val scope: CoroutineScope,
    private val cpuMs: () -> Long,
    private val wallMs: () -> Long,
    private val currentRaw: () -> Long?,
    private val charging: () -> Boolean,
    private val readTasks: (IntArray) -> Map<Int, TaskSample>,
    private val log: (String) -> Unit,
    private val currentPeriodMs: Long = 500,
    private val corePeriodMs: Long = 250,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val _lastReport = kotlinx.coroutines.flow.MutableStateFlow<ReplyReport?>(null)
    /** the last reply, per tier segment, for 设置 › 资源诊断 (same numbers as the log). */
    val lastReport: kotlinx.coroutines.flow.StateFlow<ReplyReport?> = _lastReport
    private var segments = ArrayList<TelemetrySegment>()
    private var current: TelemetrySegment? = null
    private var tids: () -> IntArray = { IntArray(0) }
    private var startTicks: Map<Int, TaskSample> = emptyMap()
    private var job: Job? = null
    private var replyStartCpu = 0L

    val active: Boolean get() = synchronized(lock) { current != null }

    fun begin(tier: String, threadIds: () -> IntArray) {
        synchronized(lock) {
            tids = threadIds
            replyStartCpu = cpuMs()
            segments = ArrayList()
            current = TelemetrySegment(tier, replyStartCpu, wallMs()).also { segments += it }
            startTicks = readTasks(threadIds())
        }
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            var t = 0L
            while (isActive) {
                val ids = synchronized(lock) { tids }()
                val tasks = readTasks(ids)
                val ma = if (t % currentPeriodMs == 0L) CurrentNorm.toMilliAmps(currentRaw()) else null
                synchronized(lock) {
                    current?.addCores(tasks.values)
                    if (t % currentPeriodMs == 0L) current?.addCurrent(ma)
                }
                delay(corePeriodMs)
                t += corePeriodMs
            }
        }
    }

    fun piece() {
        val now = wallMs()
        synchronized(lock) { current?.addPiece(now) }
    }

    /** A tier change: during a reply it splits the segments; idle, it logs one reading now. */
    fun tierChanged(from: String, to: String, result: TierApplyResult) {
        val closed: TelemetrySegment?
        synchronized(lock) {
            val cur = current
            if (cur == null) {
                closed = null
            } else {
                cur.close(cpuMs(), wallMs())
                closed = cur
                current = TelemetrySegment(to, cpuMs(), wallMs()).also { segments += it }
            }
        }
        val ma = CurrentNorm.toMilliAmps(currentRaw())
        if (closed != null) {
            log("power before tier change $from→$to (${result.name}): ${closed.describe(charging())}")
        } else {
            log("power at idle tier change $from→$to (${result.name}): current now ${ma?.let { "%.0f mA".format(it) } ?: "n/a"}" +
                if (charging()) " (CHARGING: not consumption)" else "")
        }
    }

    /** End of the reply. [tokens] = generated tokens (null when unknown); [tokPerSec] = the engine's decode speed. */
    fun end(tokens: Int?, tokPerSec: Float? = null) {
        val segs: List<TelemetrySegment>
        val ids: IntArray
        val start: Map<Int, TaskSample>
        synchronized(lock) {
            val cur = current ?: return
            cur.close(cpuMs(), wallMs())
            current = null
            segs = segments
            ids = tids()
            start = startTicks
        }
        job?.cancel()
        job = null
        val total = segs.sumOf { it.cpuMs }
        val chg = charging()
        val perToken = if (tokens != null && tokens > 0) "%.1f ms/token".format(total.toFloat() / tokens) else "-"
        val end = readTasks(ids)
        val threads = if (end.isEmpty()) "threads n/a" else end.entries.groupBy { it.value.processor }.toSortedMap().entries.joinToString(" ") { (cpu, list) ->
            val ms = list.sumOf { (tid, s) -> (s.cpuTicks - (start[tid]?.cpuTicks ?: s.cpuTicks)).coerceAtLeast(0) } * TICK_MS
            "cpu$cpu:${list.size}thr/${ms}ms"
        }
        log("power reply end: tokens=${tokens ?: "?"} process CPU ${total}ms ($perToken, includes prefill), " +
            "${segs.size} tier segment(s) | inference threads by last CPU (count/CPU time this reply): $threads")
        segs.forEach { log("power segment ${it.describe(chg)}") }
        _lastReport.value = buildReport(segs, tokens, tokPerSec, total, chg, nowEpochMs())
    }

    companion object {
        /**
         * what 资源诊断 shows. Tokens per segment are the engine's token count split in
         * proportion to the streamed pieces of each segment. Decode speed of the first segment is
         * timed from its first piece (prefill excluded); a later segment from the tier change.
         */
        fun buildReport(
            segs: List<TelemetrySegment>, tokens: Int?, tokPerSec: Float?, totalCpuMs: Long, charging: Boolean, atEpochMs: Long,
        ): ReplyReport {
            val pieces = segs.sumOf { it.pieces }
            val scale = if (tokens != null && tokens > 0 && pieces > 0) tokens.toFloat() / pieces else 1f
            val out = segs.mapIndexed { i, sg ->
                val segTokens = kotlin.math.round(sg.pieces * scale).toInt()
                val from = if (i == 0) sg.firstPieceWallMs else sg.startWallMs
                val counted = if (i == 0) sg.pieces - 1 else sg.pieces
                val span = if (from >= 0 && sg.lastPieceWallMs >= 0) sg.lastPieceWallMs - from else 0L
                val rate = if (counted >= 2 && span >= 200) counted * scale * 1000f / span else null
                val total = sg.coreHist.values.sum()
                SegmentReport(
                    tier = sg.tier,
                    wallMs = sg.wallMs,
                    tokens = segTokens,
                    tokPerSec = rate,
                    cpuMs = sg.cpuMs,
                    cpuMsPerToken = if (segTokens > 0) sg.cpuMs.toFloat() / segTokens else null,
                    busyCores = sg.busyCores,
                    coreShare = if (total == 0) emptyMap() else sg.coreHist.mapValues { it.value * 100f / total },
                    avgMa = sg.avgMa,
                    maSamples = sg.maSamples,
                )
            }
            return ReplyReport(
                atEpochMs = atEpochMs, tokens = tokens, tokPerSec = tokPerSec?.takeIf { it > 0f }, cpuMs = totalCpuMs,
                cpuMsPerToken = if (tokens != null && tokens > 0) totalCpuMs.toFloat() / tokens else null,
                charging = charging, segments = out,
            )
        }

        /** Linux USER_HZ on Android. */
        const val TICK_MS = 10L

        fun android(context: android.content.Context, scope: CoroutineScope, log: (String) -> Unit): ResourceTelemetry {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            return ResourceTelemetry(
                scope = scope,
                cpuMs = { android.os.Process.getElapsedCpuTime() },
                wallMs = { android.os.SystemClock.elapsedRealtime() },
                currentRaw = { bm?.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) },
                charging = { bm?.isCharging == true },
                readTasks = ::readTasks,
                log = log,
            )
        }

        /** Stat of [tids] (all threads of the process when empty, then only running ones count). */
        fun readTasks(tids: IntArray): Map<Int, TaskSample> {
            val ids = if (tids.isNotEmpty()) tids.toList() else
                java.io.File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }.orEmpty()
            val out = HashMap<Int, TaskSample>(ids.size)
            for (t in ids) {
                val line = runCatching { java.io.File("/proc/self/task/$t/stat").readText() }.getOrNull() ?: continue
                ProcStat.parse(line)?.let { out[t] = it }
            }
            return out
        }
    }
}

/** one tier's share of the last reply, as shown in 资源诊断. */
data class SegmentReport(
    val tier: String,
    val wallMs: Long,
    val tokens: Int,
    /** Decode tokens per second in this segment; null when too short to time. */
    val tokPerSec: Float?,
    val cpuMs: Long,
    val cpuMsPerToken: Float?,
    val busyCores: Float,
    /** CPU id → % of running samples of the inference threads. */
    val coreShare: Map<Int, Float>,
    val avgMa: Float?,
    val maSamples: Int,
)

/** the last reply as a whole. */
data class ReplyReport(
    val atEpochMs: Long,
    val tokens: Int?,
    val tokPerSec: Float?,
    val cpuMs: Long,
    val cpuMsPerToken: Float?,
    /** Plugged in: the current reading is not consumption. */
    val charging: Boolean,
    val segments: List<SegmentReport>,
)

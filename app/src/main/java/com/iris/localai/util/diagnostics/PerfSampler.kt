package com.iris.localai.util.diagnostics

import com.iris.localai.util.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import java.io.File

/**
 * Readings for the in-chat performance overlay. Everything is read from this process's own
 * /proc files, which every app may read. GPU load is device-wide and only available where the
 * kernel exposes kgsl to apps; when it is not readable the value is null and the overlay hides it
 * (never a guess). NPU load is not readable at all and is not shown.
 */
class PerfSampler(
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    private val cpuTimeMs: () -> Long = { android.os.Process.getElapsedCpuTime() },
    private val readText: (String) -> String? = { p -> runCatching { File(p).readText() }.getOrNull() },
) {
    data class Reading(val cpuPercent: Float?, val rssBytes: Long?, val gpuPercent: Int?)

    private var lastCpu = -1L
    private var lastWall = 0L
    private var gpuReadable: Boolean? = null

    fun sample(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Reading {
        val cpu = cpuTimeMs()
        val pct = if (lastCpu >= 0 && nowMs > lastWall) cpuPercent(cpu - lastCpu, nowMs - lastWall, cores) else null
        lastCpu = cpu; lastWall = nowMs
        return Reading(pct, readText("/proc/self/status")?.let(::parseVmRss), gpu())
    }

    private fun gpu(): Int? {
        if (gpuReadable == false) return null
        val v = readText(GPU_BUSY_PERCENT)?.let(::parseGpuPercent) ?: readText(GPU_BUSY)?.let(::parseGpuBusy)
        if (gpuReadable == null) gpuReadable = v != null // checked once: permission does not change
        return v
    }

    companion object {
        const val GPU_BUSY_PERCENT = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
        const val GPU_BUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy"

        /** App CPU use as a share of the whole CPU (all cores = 100 %). */
        fun cpuPercent(cpuDeltaMs: Long, wallDeltaMs: Long, cores: Int): Float? {
            if (wallDeltaMs <= 0 || cores <= 0 || cpuDeltaMs < 0) return null
            return (cpuDeltaMs.toFloat() * 100f / (wallDeltaMs * cores)).coerceIn(0f, 100f)
        }

        /** "VmRSS:   123456 kB" → bytes. */
        fun parseVmRss(status: String): Long? = status.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.let { Regex("(\\d+)").find(it)?.value?.toLongOrNull() }
            ?.times(1024)

        /** "37 %" or "37" → 37. */
        fun parseGpuPercent(text: String): Int? =
            Regex("^\\s*(\\d{1,3})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

        /** kgsl gpubusy: "busy total" counters for the last window → percent. */
        fun parseGpuBusy(text: String): Int? {
            val n = text.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (n.size < 2 || n[1] <= 0) return null
            return ((n[0] * 100) / n[1]).toInt().coerceIn(0, 100)
        }
    }
}

/** Characters per second over a sliding window (overlay speed; unit-tested). */
class RateWindow(private val windowMs: Long = 2_000) {
    private val times = ArrayDeque<Long>()
    private val counts = ArrayDeque<Int>()

    fun add(nowMs: Long, totalChars: Int) {
        times.addLast(nowMs); counts.addLast(totalChars)
        while (times.size > 2 && nowMs - times.first() > windowMs) { times.removeFirst(); counts.removeFirst() }
    }

    fun perSecond(): Float? {
        if (times.size < 2) return null
        val dt = times.last() - times.first()
        if (dt < 300) return null
        return ((counts.last() - counts.first()).coerceAtLeast(0) * 1000f / dt)
    }

    fun clear() { times.clear(); counts.clear() }
}

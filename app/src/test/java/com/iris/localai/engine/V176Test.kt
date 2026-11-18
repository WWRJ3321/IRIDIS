package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.resources.CpuTopology
import com.iris.localai.engine.resources.LiveChange
import com.iris.localai.engine.resources.LiveResourcePolicy
import com.iris.localai.engine.resources.ResourcePlanner
import com.iris.localai.engine.resources.ResourceTelemetry
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.engine.resources.TaskSample
import com.iris.localai.engine.resources.TelemetrySegment
import com.iris.localai.engine.resources.TierApplyResult
import com.iris.localai.engine.resources.TierReloadPolicy
import com.iris.localai.ui.components.TierSliderMath
import com.iris.localai.ui.components.TierSliderStyle
import com.iris.localai.ui.settings.DiagnosticsText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.6: 8 Elite tiers retuned, 1.7.4 slider form back, 资源诊断 page data. */
@OptIn(ExperimentalCoroutinesApi::class)
class V176Test {

    private val elite = CpuTopology.fromFrequencies(List(6) { 3_532_800L } + List(2) { 4_320_000L })
    private val tri = CpuTopology(listOf(listOf(0, 1), listOf(2, 3, 4, 5, 6), listOf(7)))
    private fun plan(t: ResourceTier, userThreads: Int = 0) = ResourcePlanner.plan(t, elite, 0, userThreads)

    // ---------------------------------------------------------------- 8 Elite table

    @Test fun eliteTierTable() {
        val p = ResourceTier.entries.map { plan(it) }
        // 1.7.8: 「全部」 removed; the three CPU defaults are unchanged.
        assertEquals(listOf(1, 2, 6), p.map { it.threads }) // 2.0.3: 高 = 6 threads
        assertEquals(listOf(listOf(5), listOf(0, 1, 2, 3, 4, 5), listOf(2, 3, 4, 5, 6, 7)), p.map { it.cpus })
        assertEquals(listOf(10, 0, -4), p.map { it.nice })
        assertEquals(listOf("性能核 ×1", "性能核 ×6"), p.take(2).map { it.coreLabel })
    }

    @Test fun eliteTiersMonotonicInSpeedAndPower() {
        val p = ResourceTier.entries.map { plan(it) }
        val prime = setOf(6, 7)
        // "fastest core a thread may use": performance → performance → prime → prime
        fun topSpeed(cpus: List<Int>) = if (cpus.isEmpty() || cpus.any { it in prime }) 1 else 0
        for (i in 1 until p.size) {
            assertTrue("threads never fall", p[i].threads >= p[i - 1].threads)
            assertTrue("nice falls (higher priority)", p[i].nice < p[i - 1].nice)
            assertTrue("core class never drops", topSpeed(p[i].cpus) >= topSpeed(p[i - 1].cpus))
            assertNotEquals(Triple(p[i - 1].threads, p[i - 1].cpus, p[i - 1].nice), Triple(p[i].threads, p[i].cpus, p[i].nice))
        }
        // Each step up adds either threads or faster cores.
        assertTrue(p[1].threads > p[0].threads)                       // 省电 → 中: 1 → 2 threads
        assertTrue(p[2].cpus.containsAll(prime) && p[1].cpus.none { it in prime }) // 中 → 高: adds the prime cores
        // 省电 / 中 never touch the prime cores; one thread per pinned core at most.
        assertFalse(p[0].cpus.any { it in prime }); assertFalse(p[1].cpus.any { it in prime })
        for (x in p) if (x.cpus.isNotEmpty()) assertTrue(x.cpus.size >= x.threads)
    }

    @Test fun lowTierIsNamedPowerSaving() {
        assertEquals("省电", ResourceTier.LOW.label)
        assertEquals("中资源", ResourceTier.MEDIUM.label)
        assertEquals("高资源", ResourceTier.HIGH.label)
        assertEquals(3, ResourceTier.entries.size) // 1.7.8: 「全部资源」 removed
    }

    @Test fun threeClusterPlansUnchanged() {
        val low = ResourcePlanner.plan(ResourceTier.LOW, tri)
        val med = ResourcePlanner.plan(ResourceTier.MEDIUM, tri)
        val high = ResourcePlanner.plan(ResourceTier.HIGH, tri)
        assertEquals(listOf(0, 1), low.cpus); assertEquals(2, low.threads); assertEquals(10, low.nice)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), med.cpus); assertEquals(3, med.threads); assertEquals(0, med.nice)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), high.cpus); assertEquals(4, high.threads); assertEquals(-4, high.nice)
        assertEquals("能效核 ×2", low.coreLabel)
    }

    @Test fun thermalAndManualThreadsStillWork() {
        assertEquals(1, ResourcePlanner.plan(ResourceTier.LOW, elite, ResourcePlanner.THERMAL_SEVERE).threads)
        assertEquals(3, ResourcePlanner.plan(ResourceTier.HIGH, elite, ResourcePlanner.THERMAL_SEVERE).threads) // 2.0.3: 6 / 2
        // per-model 4 threads on 省电 / 高: more threads than pinned cores → widened to all cores
        assertEquals(emptyList<Int>(), plan(ResourceTier.LOW, userThreads = 4).cpus)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), plan(ResourceTier.HIGH, userThreads = 4).cpus) // 2.0.3: 4 threads fit 高's 6 cores
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plan(ResourceTier.MEDIUM, userThreads = 4).cpus)
    }

    // ---------------------------------------------------------------- live / reload with the new table

    @Test fun mediumToHighIsLiveOnlyNoReload() {
        // Same 2 threads: moved to the prime cores and nice -4 live, nothing to reload.
        val loaded = plan(ResourceTier.MEDIUM)
        val next = plan(ResourceTier.HIGH, userThreads = 2) // 2.0.3: default 高 is 6 threads; keep the 2-thread case
        val live = LiveChange(2, LiveResourcePolicy.coresFor(next.cpus, 2), next.nice)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), live.cpus) // 2.0.3
        assertFalse(TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live).reload)
    }

    @Test fun threadCountChangesStillReloadWhenIdle() {
        val loaded = plan(ResourceTier.HIGH, userThreads = 8)
        val next = plan(ResourceTier.LOW)
        val live = LiveChange(8, LiveResourcePolicy.coresFor(next, 8), next.nice)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), live.cpus)   // never released onto the prime cores
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live)
        assertTrue(d.reload); assertEquals(listOf("线程"), d.changed)
        val d2 = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, plan(ResourceTier.LOW), plan(ResourceTier.MEDIUM), Backend.CPU, Backend.CPU,
            LiveChange(1, LiveResourcePolicy.coresFor(listOf(0, 1, 2, 3, 4, 5), 1), 0))
        assertTrue(d2.reload); assertEquals(listOf("线程"), d2.changed)
    }

    // ---------------------------------------------------------------- live down-switch never reaches the prime cores

    @Test fun liveDownSwitchPinsToNeighbouringNonPrimeCores() {
        // thread count fixed until the idle reload: target cores + nearest performance cores
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plan(ResourceTier.LOW).nonPrimeCpus)
        assertEquals(listOf(4, 5), LiveResourcePolicy.coresFor(plan(ResourceTier.LOW), 2))          // 中/高 → 省电
        assertEquals(listOf(0, 1, 2, 3, 4, 5), LiveResourcePolicy.coresFor(plan(ResourceTier.LOW), 8))    // 全部 → 省电
        assertEquals(listOf(0, 1, 2, 3, 4, 5), LiveResourcePolicy.coresFor(plan(ResourceTier.MEDIUM), 8)) // 全部 → 中
        assertEquals(listOf(3, 4, 5), LiveResourcePolicy.coresFor(plan(ResourceTier.LOW), 3))
        for (from in listOf(2, 3, 4, 6, 8)) for (t in listOf(ResourceTier.LOW, ResourceTier.MEDIUM)) {
            val c = LiveResourcePolicy.coresFor(plan(t), from)
            assertTrue("$t with $from threads: pinned", c.isNotEmpty())
            assertFalse("$t with $from threads: no prime core", c.any { it >= 6 })
            assertTrue(c.containsAll(plan(t).cpus))
        }
        // 全部 → 高: the prime cores stay, only performance cores are added
        assertEquals(listOf(2, 3, 4, 5, 6, 7), LiveResourcePolicy.coresFor(plan(ResourceTier.HIGH), 6))
        // enough cores already / target = every core: unchanged
        assertEquals(listOf(2, 3, 4, 5, 6, 7), LiveResourcePolicy.coresFor(plan(ResourceTier.HIGH), 2)) // 2.0.3: 高 already pins 6 cores
        assertEquals(emptyList<Int>(), LiveResourcePolicy.coresFor(plan(ResourceTier.HIGH, userThreads = 8), 8))
    }

    @Test fun coresForPrefersNearestHigherNonPrimeCoreAndWorksOnThreeClusters() {
        // tie at distance 1 → the higher id (cpu0 takes most interrupts)
        assertEquals(listOf(2, 3), LiveResourcePolicy.coresFor(listOf(2), 2, listOf(0, 1, 2, 3, 4, 5)))
        // no non-prime list → the target cores are kept (shared), never released
        assertEquals(listOf(5), LiveResourcePolicy.coresFor(listOf(5), 4))
        // 3 clusters: prime cpu7 is never added
        val low = ResourcePlanner.plan(ResourceTier.LOW, tri)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), low.nonPrimeCpus)
        assertEquals(listOf(0, 1, 2, 3), LiveResourcePolicy.coresFor(low, 4))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), LiveResourcePolicy.coresFor(low, 8))
    }

    @Test fun idleReloadReturnsToTargetPlan() {
        // 中 loaded, 省电 chosen mid-reply: live cpu4-5 (2 threads), reload pending for the thread count.
        val loaded = plan(ResourceTier.MEDIUM)
        val next = plan(ResourceTier.LOW)
        val live = LiveChange(loaded.threads, LiveResourcePolicy.coresFor(next, loaded.threads), next.nice)
        assertEquals(listOf(4, 5), live.cpus)
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live)
        assertTrue(d.reload); assertEquals(listOf("线程"), d.changed)
        // after the idle reload the engine runs the target plan exactly: 1 thread on cpu5, nothing left to do
        val reloadedLive = LiveChange(next.threads, LiveResourcePolicy.coresFor(next, next.threads), next.nice)
        assertEquals(LiveChange(1, listOf(5), 10), reloadedLive)
        assertFalse(TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, next, next, Backend.CPU, Backend.CPU, reloadedLive).reload)
        // same for 全部 → 中: live cpu0-5 with 8 threads, reload to 2 threads on cpu0-5
        val all = plan(ResourceTier.HIGH, userThreads = 8); val med = plan(ResourceTier.MEDIUM)
        val l2 = LiveChange(8, LiveResourcePolicy.coresFor(med, 8), med.nice)
        assertEquals(listOf("线程"), TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, all, med, Backend.CPU, Backend.CPU, l2).changed)
        assertEquals(med.cpus, LiveResourcePolicy.coresFor(med, med.threads))
    }

    // ---------------------------------------------------------------- slider

    @Test fun sliderMathMatchesOriginalStepTrack() {
        val knob = 26f
        assertEquals(0, TierSliderMath.stopAt(0f, 300f, knob, 4))
        assertEquals(3, TierSliderMath.stopAt(300f, 300f, knob, 4))
        assertEquals(2, TierSliderMath.stopAt(13f + 274f * 0.6f, 300f, knob, 4))
        assertEquals(13f, TierSliderMath.knobX(0f, 300f, knob, 4), 0.001f)
        assertEquals(287f, TierSliderMath.knobX(3f, 300f, knob, 4), 0.001f)
        assertEquals(12f, TierSliderStyle.TRACK_DP); assertEquals(26f, TierSliderStyle.KNOB_DP)
    }

    // ---------------------------------------------------------------- diagnostics

    @Test fun reportSplitsTokensAndTimesDecodeWithoutPrefill() {
        val a = TelemetrySegment("高", startCpuMs = 0, startWallMs = 0)
        // prefill 1000 ms, then 21 pieces 50 ms apart → 20 intervals in 1000 ms = 20 /s
        for (i in 0..20) a.addPiece(1000L + i * 50)
        a.addCores(listOf(TaskSample('R', 6, 0), TaskSample('R', 7, 0)))
        a.addCurrent(800f)
        a.close(cpuMs = 2000, wallMs = 2000)
        val b = TelemetrySegment("省电", startCpuMs = 2000, startWallMs = 2000)
        for (i in 1..10) b.addPiece(2000L + i * 100)   // 10 pieces in 1000 ms = 10 /s
        b.addCores(listOf(TaskSample('R', 5, 0), TaskSample('S', 6, 0)))
        b.addCurrent(300f)
        b.close(cpuMs = 2600, wallMs = 3000)
        val r = ResourceTelemetry.buildReport(listOf(a, b), tokens = 31, tokPerSec = 15f, totalCpuMs = 2600, charging = false, atEpochMs = 1L)
        assertEquals(21, r.segments[0].tokens); assertEquals(10, r.segments[1].tokens)
        assertEquals(20f, r.segments[0].tokPerSec!!, 0.01f)
        assertEquals(10f, r.segments[1].tokPerSec!!, 0.01f)
        assertEquals(60f, r.segments[1].cpuMsPerToken!!, 0.01f)
        assertEquals(mapOf(5 to 100f), r.segments[1].coreShare)
        assertEquals(300f, r.segments[1].avgMa!!, 0.01f)
        assertEquals(2600f / 31, r.cpuMsPerToken!!, 0.01f)
        assertEquals(15f, r.tokPerSec!!, 0.01f)
    }

    @Test fun tooShortSegmentHasNoSpeed() {
        val a = TelemetrySegment("中", 0, 0)
        a.addPiece(100); a.close(10, 150)
        val r = ResourceTelemetry.buildReport(listOf(a), tokens = 1, tokPerSec = null, totalCpuMs = 10, charging = true, atEpochMs = 0)
        assertNull(r.segments[0].tokPerSec)
        assertNull(r.tokPerSec)
        assertTrue(r.charging)
    }

    @Test fun telemetryPublishesLastReport() = runTest {
        var wall = 0L
        val t = ResourceTelemetry(
            scope = backgroundScope, cpuMs = { wall / 2 }, wallMs = { wall }, currentRaw = { -350_000L }, charging = { false },
            readTasks = { emptyMap() }, log = {}, nowEpochMs = { 42L },
        )
        assertNull(t.lastReport.value)
        t.begin("省电") { IntArray(0) }
        repeat(5) { wall += 100; t.piece() }
        t.end(5, 9.5f)
        val r = t.lastReport.value
        assertNotNull(r)
        assertEquals(42L, r!!.atEpochMs)
        assertEquals("省电", r.segments.single().tier)
        assertEquals(5, r.segments.single().tokens)
    }

    @Test fun diagnosticsText() {
        assertEquals("1 线程 · cpu5 · nice 10", DiagnosticsText.plan(plan(ResourceTier.LOW)))
        assertEquals("2 线程 · cpu0-5 · nice 0", DiagnosticsText.plan(plan(ResourceTier.MEDIUM)))
        assertEquals("6 线程 · cpu2-7 · nice -4", DiagnosticsText.plan(plan(ResourceTier.HIGH)))
        assertEquals("8 线程 · 全部核心 · nice -4", DiagnosticsText.plan(plan(ResourceTier.HIGH, userThreads = 8)))
        assertEquals("cpu5 70%  cpu6 30%", DiagnosticsText.cores(mapOf(6 to 30f, 5 to 70f)))
        assertEquals("—", DiagnosticsText.cores(emptyMap()))
        assertEquals("读不到电流", DiagnosticsText.current(null, 0, false))
        assertTrue(DiagnosticsText.current(500f, 4, true).contains("充电中"))
        assertEquals("12.3 tok/s", DiagnosticsText.tokPerSec(12.34f))
        assertEquals(TierApplyResult.LIVE.name, "LIVE")
    }
}

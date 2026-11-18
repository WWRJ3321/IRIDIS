package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.resources.CpuTopology
import com.iris.localai.engine.resources.CurrentNorm
import com.iris.localai.engine.resources.LiveApply
import com.iris.localai.engine.resources.LiveChange
import com.iris.localai.engine.resources.LiveResourcePolicy
import com.iris.localai.engine.resources.ProcStat
import com.iris.localai.engine.resources.ResourcePlanner
import com.iris.localai.engine.resources.ResourceTelemetry
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.engine.resources.TaskSample
import com.iris.localai.engine.resources.TelemetrySegment
import com.iris.localai.engine.resources.TierApplyResult
import com.iris.localai.engine.resources.TierReloadPolicy
import com.iris.localai.engine.resources.TierReloadResult
import com.iris.localai.engine.resources.TierReloadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V175Test {

    // Snapdragon 8 Elite (SM8750): 6 performance cores 0-5 + 2 prime cores 6-7, two clusters.
    private val elite = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))
    private fun plan(t: ResourceTier, userThreads: Int = 0) = ResourcePlanner.plan(t, elite, 0, userThreads)
    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    // ---------------------------------------------------------------- tiers on 8 Elite

    @Test fun eliteTierTable() {
        val low = plan(ResourceTier.LOW); val med = plan(ResourceTier.MEDIUM)
        val high = plan(ResourceTier.HIGH); val all = plan(ResourceTier.HIGH, userThreads = 8)
        // 1.7.6 retune (see V176Test for the full table and monotonicity).
        assertEquals(listOf(5), low.cpus); assertEquals(1, low.threads); assertEquals(10, low.nice)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), med.cpus); assertEquals(2, med.threads); assertEquals(0, med.nice)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), high.cpus); assertEquals(6, high.threads); assertEquals(-4, high.nice) // 2.0.3
        assertEquals(emptyList<Int>(), all.cpus); assertEquals(8, all.threads); assertEquals(-4, all.nice) // 1.7.8: 8 threads = manual override on 高
    }

    @Test fun eliteTiersAreMonotonicAndAllDifferent() {
        val ps = ResourceTier.entries.map { plan(it) }
        // 1.7.6: 中 → 高 keeps 2 threads but moves to the prime cores (6 → 2 cores), so the order is
        // judged on (core class, nice, threads) together: none gets worse, at least one improves.
        val prime = setOf(6, 7)
        fun coreClass(cpus: List<Int>) = if (cpus.isEmpty() || cpus.any { it in prime }) 1 else 0
        for (i in 1 until ps.size) {
            val a = ps[i - 1]; val b = ps[i]
            assertTrue("core class never drops", coreClass(b.cpus) >= coreClass(a.cpus))
            assertTrue("nice falls (higher priority)", b.nice < a.nice)
            assertTrue("threads never fall", b.threads >= a.threads)
            assertTrue("each step adds faster cores or threads",
                coreClass(b.cpus) > coreClass(a.cpus) || b.threads > a.threads)
            assertNotEquals("adjacent tiers differ in what MNN really gets",
                Triple(ps[i - 1].threads, ps[i - 1].cpus, ps[i - 1].nice), Triple(ps[i].threads, ps[i].cpus, ps[i].nice))
        }
        // Low tier never touches the prime cores.
        assertFalse(plan(ResourceTier.LOW).cpus.any { it >= 6 })
    }

    // ---------------------------------------------------------------- live re-pin vs reload

    @Test fun coresForNeverReleasesPinning() {
        // 1.7.6: fewer cores than threads no longer means "every core" (that reached cpu6/7).
        assertEquals(listOf(0, 1, 2, 3, 4, 5), LiveResourcePolicy.coresFor(listOf(0, 1, 2, 3, 4, 5), 8))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), LiveResourcePolicy.coresFor(plan(ResourceTier.MEDIUM), 8))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), LiveResourcePolicy.coresFor(listOf(0, 1, 2, 3, 4, 5), 6))
        assertEquals(emptyList<Int>(), LiveResourcePolicy.coresFor(emptyList(), 2))   // target = every core
    }

    @Test fun mnnCpuOnlyCoresAndNiceChangedDoesNotReloadWhenAppliedLive() {
        // Same thread count (per-model 2 threads) → only cores / nice differ between 高 and 低.
        val loaded = plan(ResourceTier.HIGH, userThreads = 2)
        val next = plan(ResourceTier.LOW, userThreads = 2)
        assertEquals(loaded.threads, next.threads)
        assertNotEquals(loaded.cpus, next.cpus)
        val live = LiveChange(2, LiveResourcePolicy.coresFor(next, 2), next.nice)
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live)
        assertFalse(d.reload)
    }

    @Test fun mnnCpuCoresNiceNotAppliedLiveStillReloads() {
        val loaded = plan(ResourceTier.HIGH, userThreads = 2)
        val next = plan(ResourceTier.LOW, userThreads = 2)
        val stale = LiveChange(2, loaded.cpus, loaded.nice)   // the re-pin failed: threads still on the old policy
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, stale)
        assertTrue(d.reload)
        assertTrue(d.changed.containsAll(listOf("核心", "优先级")))
        assertFalse("线程" in d.changed)
    }

    @Test fun mnnCpuThreadCountChangeReloadsEvenAfterLiveRepin() {
        val loaded = plan(ResourceTier.MEDIUM)           // 2 threads, cpu0-5
        val next = plan(ResourceTier.LOW)                // 1 thread, cpu5, nice 10
        val live = LiveChange(2, LiveResourcePolicy.coresFor(next, 2), next.nice)
        assertEquals(listOf(4, 5), live.cpus)
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live)
        assertTrue(d.reload)
        assertEquals(listOf("线程"), d.changed)
    }

    @Test fun allToLowKeepsAllCoresLiveButReloadsForThreads() {
        // 1.7.6: the 8 threads stay off the prime cores live (cpu0-5), the idle reload brings 1 thread on cpu5.
        val loaded = plan(ResourceTier.HIGH, userThreads = 8)
        val next = plan(ResourceTier.LOW)
        val live = LiveChange(8, LiveResourcePolicy.coresFor(next, 8), next.nice)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), live.cpus)
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live)
        assertEquals(listOf("线程"), d.changed)
    }

    @Test fun schedulerDoesNotReloadForLiveOnlyChange() = runTest {
        val loaded = plan(ResourceTier.HIGH, userThreads = 4)
        val next = plan(ResourceTier.MEDIUM, userThreads = 4)
        val live = LiveChange(4, LiveResourcePolicy.coresFor(next, 4), next.nice)
        var calls = 0
        val s = TierReloadScheduler(
            scope = backgroundScope, debounceMs = 400, isBusy = { false },
            decide = { TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live) },
            reload = { calls++; TierReloadResult.APPLIED },
        )
        s.onTierChanged("中资源")
        settle()
        assertEquals(0, calls)
        assertFalse(s.pending)
    }

    @Test fun schedulerReloadsOnlyAfterReplyForThreadChange() = runTest {
        var busy = true
        var calls = 0
        val loaded = plan(ResourceTier.HIGH, userThreads = 8)
        val next = plan(ResourceTier.LOW)
        val live = LiveChange(8, LiveResourcePolicy.coresFor(next, 8), next.nice)
        val s = TierReloadScheduler(
            scope = backgroundScope, debounceMs = 400, isBusy = { busy },
            decide = { TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, loaded, next, Backend.CPU, Backend.CPU, live) },
            reload = { calls++; TierReloadResult.APPLIED },
        )
        s.onTierChanged("低资源")
        settle()
        assertEquals("never reloads mid-reply", 0, calls)
        assertTrue(s.pending)
        busy = false
        s.onGenerationFinished()
        settle()
        assertEquals(1, calls)
    }

    @Test fun mnnModeIsLivePolicyOnlyOnCpu() {
        assertEquals(LiveApply.LIVE_POLICY, LiveResourcePolicy.mode(ModelFormat.MNN, Backend.CPU))
        assertEquals(LiveApply.RELOAD, LiveResourcePolicy.mode(ModelFormat.MNN, Backend.GPU))
        assertEquals(LiveApply.RELOAD, LiveResourcePolicy.combine(LiveApply.LIVE_POLICY, LiveApply.RELOAD))
        assertEquals(LiveApply.LIVE_POLICY, LiveResourcePolicy.combine(LiveApply.LIVE_POLICY, LiveApply.LIVE_POLICY))
        // GGUF keeps 1.7.2; LiteRT keeps 1.7.4.
        assertEquals(LiveApply.IMMEDIATE, LiveResourcePolicy.mode(ModelFormat.GGUF, Backend.CPU))
        assertEquals(LiveApply.RELOAD, LiveResourcePolicy.mode(ModelFormat.LITERT, Backend.CPU))
    }

    // ---------------------------------------------------------------- status line

    @Test fun resultFollowsWhatReallyHappened() {
        assertEquals(TierApplyResult.LIVE, LiveResourcePolicy.result(LiveApply.LIVE_POLICY, true, true, false))
        assertEquals(TierApplyResult.LIVE_THREADS_ON_RELOAD, LiveResourcePolicy.result(LiveApply.LIVE_POLICY, false, true, true))
        assertEquals(TierApplyResult.RELOAD, LiveResourcePolicy.result(LiveApply.LIVE_POLICY, true, false, true))
        assertEquals(TierApplyResult.RELOAD, LiveResourcePolicy.result(LiveApply.LIVE_POLICY, true, null, false))
        assertEquals(TierApplyResult.LIVE, LiveResourcePolicy.result(LiveApply.IMMEDIATE, true, true, true))
        assertEquals(TierApplyResult.NEXT_REPLY, LiveResourcePolicy.result(LiveApply.IMMEDIATE, true, false, false))
        assertEquals(TierApplyResult.NEXT_REPLY, LiveResourcePolicy.result(LiveApply.IMMEDIATE, false, null, false))
        assertEquals(TierApplyResult.NEXT_REPLY, LiveResourcePolicy.result(LiveApply.NEXT_REPLY, true, null, false))
        assertEquals(TierApplyResult.RELOAD, LiveResourcePolicy.result(LiveApply.RELOAD, true, null, true))
    }

    @Test fun statusTextIsShortHonestAndHasNoSpeed() {
        assertNull(LiveResourcePolicy.statusText(null))
        assertEquals("实时生效", LiveResourcePolicy.statusText(TierApplyResult.LIVE))
        assertEquals("空闲时重载生效", LiveResourcePolicy.statusText(TierApplyResult.RELOAD))
        for (r in TierApplyResult.entries) {
            val t = LiveResourcePolicy.statusText(r)!!
            assertTrue("$t too long", t.length <= 16)
            for (bad in listOf("字/秒", "tok/s", "约", "估", "限速")) assertFalse(t.contains(bad))
            // "实时" only where the engine confirmed a live change.
            assertEquals(r == TierApplyResult.LIVE || r == TierApplyResult.LIVE_THREADS_ON_RELOAD, t.contains("实时"))
        }
    }

    // ---------------------------------------------------------------- telemetry

    @Test fun currentNormalisesMicroAndMilliAmpsAndSign() {
        assertEquals(350f, CurrentNorm.toMilliAmps(-350_000L)!!, 0.01f)
        assertEquals(350f, CurrentNorm.toMilliAmps(350_000L)!!, 0.01f)
        assertEquals(350f, CurrentNorm.toMilliAmps(-350L)!!, 0.01f)
        assertEquals(1500f, CurrentNorm.toMilliAmps(1_500_000L)!!, 0.01f)
        assertNull(CurrentNorm.toMilliAmps(0L))
        assertNull(CurrentNorm.toMilliAmps(null))
        assertNull(CurrentNorm.toMilliAmps(Long.MIN_VALUE))
        assertNull(CurrentNorm.toMilliAmps(Int.MIN_VALUE.toLong()))
    }

    @Test fun procStatParsesProcessorAndCpuTime() {
        val fields = MutableList(52) { "0" }
        fields[0] = "R"          // field 3
        fields[11] = "120"       // utime (field 14)
        fields[12] = "30"        // stime (field 15)
        fields[36] = "6"         // processor (field 39)
        val line = "4242 (iris (mnn) x) " + fields.joinToString(" ")
        val s = ProcStat.parse(line)!!
        assertEquals('R', s.state)
        assertEquals(6, s.processor)
        assertEquals(150L, s.cpuTicks)
        assertNull(ProcStat.parse("garbage"))
    }

    @Test fun segmentAveragesCurrentAndCountsRunningCores() {
        val seg = TelemetrySegment("低", startCpuMs = 1000, startWallMs = 0)
        seg.addCurrent(400f); seg.addCurrent(600f); seg.addCurrent(null)
        seg.addCores(listOf(TaskSample('R', 2, 0), TaskSample('R', 2, 0), TaskSample('S', 7, 0), TaskSample('R', 3, 0)))
        seg.pieces = 10
        seg.close(cpuMs = 3000, wallMs = 1000)
        assertEquals(500f, seg.avgMa!!, 0.01f)
        assertEquals(2000L, seg.cpuMs)
        assertEquals(2f, seg.busyCores, 0.001f)
        assertEquals("cpu2 66% cpu3 33%", seg.coreText())
        assertTrue(seg.describe(charging = false).contains("500 mA"))
        assertTrue(seg.describe(charging = true).contains("CHARGING"))
    }

    @Test fun telemetrySplitsSegmentsAtTierChange() = runTest {
        var cpu = 0L
        var wall = 0L
        val logs = ArrayList<String>()
        val t = ResourceTelemetry(
            scope = backgroundScope, cpuMs = { cpu }, wallMs = { wall }, currentRaw = { -420_000L }, charging = { false },
            readTasks = { emptyMap() }, log = { logs += it },
        )
        t.begin("高") { IntArray(0) }
        repeat(5) { t.piece() }
        cpu = 900; wall = 500
        t.tierChanged("高", "低", TierApplyResult.LIVE_THREADS_ON_RELOAD)
        repeat(5) { t.piece() }
        cpu = 1300; wall = 1000
        t.end(10)
        assertTrue(logs.any { it.startsWith("power before tier change 高→低 (LIVE_THREADS_ON_RELOAD): [高]") })
        assertTrue(logs.any { it.contains("power reply end: tokens=10 process CPU 1300ms (130.0 ms/token") })
        assertEquals(2, logs.count { it.startsWith("power segment [") })
        assertTrue(logs.any { it.startsWith("power segment [低] 500ms") && it.contains("process CPU 400ms") })
        assertFalse(t.active)
        // Idle change: one instant reading, no segments.
        t.tierChanged("低", "全部", TierApplyResult.RELOAD)
        assertTrue(logs.last().contains("idle tier change 低→全部") && logs.last().contains("420 mA"))
    }

    // 1.7.6: the TierBar maths / source checks moved to V176Test (TierSlider replaces TierBar).
}

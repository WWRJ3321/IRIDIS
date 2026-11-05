package com.iris.localai.v174

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.CpuTopology
import com.iris.localai.engine.LiveApply
import com.iris.localai.engine.LiveResourcePolicy
import com.iris.localai.engine.ResourcePlanner
import com.iris.localai.engine.ResourceTier
import com.iris.localai.engine.TierReloadDecision
import com.iris.localai.engine.TierReloadPolicy
import com.iris.localai.engine.TierReloadResult
import com.iris.localai.engine.TierReloadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.4: resource tier reaches MNN / LiteRT by reloading the same model. */
@OptIn(ExperimentalCoroutinesApi::class)
class V174Test {

    // Snapdragon 8 Elite: 6 performance (slower) + 2 prime Oryon cores.
    private val elite = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))
    private fun plan(t: ResourceTier, userThreads: Int = 0) = ResourcePlanner.plan(t, elite, 0, userThreads)

    // ---------------------------------------------------------------- plan difference

    @Test fun mnnReloadsWhenThreadsCoresNiceOrPowerDiffer() {
        // 1.7.5: on the CPU power is no longer compared (always "normal"); without live info cores/nice count.
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, plan(ResourceTier.HIGH), plan(ResourceTier.LOW))
        assertTrue(d.reload)
        assertTrue(d.changed.containsAll(listOf("线程", "核心", "优先级")))
        assertFalse("power" in d.changed)
        // 1.7.8: on OpenCL thread_num is MNN's GPU mode (fixed) and power is forced normal → only
        // cores / nice (host + driver threads) need the reload.
        val g = TierReloadPolicy.decide(ModelFormat.MNN, Backend.GPU, plan(ResourceTier.HIGH), plan(ResourceTier.LOW))
        assertTrue(g.changed.containsAll(listOf("核心", "优先级")))
        assertFalse("线程" in g.changed || "power" in g.changed)
    }

    @Test fun mnnHighToAllReloadsEvenWithSamePower() {
        // Both "high" power and no pinning on 8 Elite, but 6 → 8 threads and nice -4 → -8.
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, plan(ResourceTier.HIGH), plan(ResourceTier.HIGH, userThreads = 8))
        assertTrue(d.reload)
        assertTrue("线程" in d.changed)
        assertFalse("power" in d.changed)
    }

    @Test fun sameTierNeverReloads() {
        for (f in ModelFormat.entries) for (b in Backend.entries) for (t in ResourceTier.entries) {
            assertFalse(TierReloadPolicy.decide(f, b, plan(t), plan(t), Backend.CPU, Backend.CPU).reload)
        }
    }

    @Test fun batchOrGpuPermissionAloneDoesNotReloadMnn() {
        val a = plan(ResourceTier.HIGH)
        val b = a.copy(batch = 128, allowGpu = !a.allowGpu)
        assertFalse(TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, a, b).reload)
    }

    @Test fun liteRtIgnoresMnnPowerButReloadsOnThreads() {
        val a = plan(ResourceTier.HIGH)
        assertFalse(TierReloadPolicy.decide(ModelFormat.LITERT, Backend.CPU, a, a.copy(mnnPower = "low")).reload)
        // 1.7.6: 高 = 2 threads on the 8 Elite, so change it to something else
        assertTrue(TierReloadPolicy.decide(ModelFormat.LITERT, Backend.CPU, a, a.copy(threads = a.threads + 2)).reload)
    }

    @Test fun autoBackendChangeReloads() {
        val a = plan(ResourceTier.HIGH)
        val d = TierReloadPolicy.decide(ModelFormat.LITERT, Backend.GPU, a, a, Backend.GPU, Backend.CPU)
        assertTrue(d.reload)
        assertEquals(listOf("后端"), d.changed)
    }

    @Test fun ggufOnCpuNeverReloads() {
        for (from in ResourceTier.entries) for (to in ResourceTier.entries) {
            val d = TierReloadPolicy.decide(ModelFormat.GGUF, Backend.CPU, plan(from), plan(to), Backend.CPU, Backend.GPU)
            assertFalse("GGUF CPU $from→$to", d.reload)
        }
    }

    @Test fun ggufOnGpuReloadsOnlyWhenBackendChoiceChanges() {
        assertFalse(TierReloadPolicy.decide(ModelFormat.GGUF, Backend.GPU, plan(ResourceTier.HIGH), plan(ResourceTier.LOW), Backend.GPU, Backend.GPU).reload)
        assertTrue(TierReloadPolicy.decide(ModelFormat.GGUF, Backend.GPU, plan(ResourceTier.HIGH), plan(ResourceTier.LOW), Backend.GPU, Backend.CPU).reload)
    }

    @Test fun nothingLoadedNeverReloads() {
        assertFalse(TierReloadPolicy.decide(ModelFormat.MNN, Backend.CPU, null, plan(ResourceTier.LOW)).reload)
        assertFalse(TierReloadPolicy.decide(null, null, plan(ResourceTier.HIGH), plan(ResourceTier.LOW)).reload)
    }

    // ---------------------------------------------------------------- scheduler

    // backgroundScope work is not counted by advanceUntilIdle(); move virtual time past the debounce.
    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    private class Harness(scope: TestScope, var busy: Boolean = false, var decision: TierReloadDecision? = TierReloadDecision(true, listOf("线程"))) {
        var reloadCalls = 0
        val logs = ArrayList<String>()
        val s = TierReloadScheduler(
            scope = scope.backgroundScope,
            debounceMs = 400,
            isBusy = { busy },
            decide = { decision },
            reload = { reloadCalls++; if (busy) TierReloadResult.DEFERRED_BUSY else TierReloadResult.APPLIED },
            log = { logs += it },
        )
    }

    @Test fun rapidTapsDebounceToOneReload() = runTest {
        val h = Harness(this)
        h.s.onTierChanged("低资源"); advanceTimeBy(100)
        h.s.onTierChanged("中资源"); advanceTimeBy(100)
        h.s.onTierChanged("全部资源"); advanceTimeBy(399); runCurrent()
        assertEquals(0, h.reloadCalls)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, h.reloadCalls)
        settle()
        assertEquals(1, h.reloadCalls)
        assertEquals(3, h.logs.count { it.startsWith("tier reload requested") })
    }

    @Test fun idleReloadsRightAfterDebounce() = runTest {
        val h = Harness(this)
        h.s.onTierChanged("低资源")
        advanceTimeBy(401); runCurrent()
        assertEquals(1, h.reloadCalls)
        assertFalse(h.s.pending)
        assertEquals(1, h.s.reloads)
    }

    @Test fun generatingDefersUntilReplyEnds() = runTest {
        val h = Harness(this, busy = true)
        h.s.onTierChanged("低资源")
        settle()
        assertEquals(0, h.reloadCalls)
        assertTrue(h.s.pending)
        assertTrue(h.logs.any { it.startsWith("tier reload deferred") })
        h.busy = false
        h.s.onGenerationFinished()
        settle()
        assertEquals(1, h.reloadCalls)
        assertFalse(h.s.pending)
    }

    @Test fun switchingBackDuringReplyCancelsDeferredReload() = runTest {
        val h = Harness(this, busy = true)
        h.s.onTierChanged("低资源")
        settle()
        assertTrue(h.s.pending)
        h.decision = TierReloadDecision.NONE // back to the tier the model was loaded with
        h.busy = false
        h.s.onGenerationFinished()
        settle()
        assertEquals(0, h.reloadCalls)
        assertFalse(h.s.pending)
    }

    @Test fun replyStartingBeforeReloadIsRetriedAfterIt() = runTest {
        var busyAtReload = true
        var calls = 0
        val s = TierReloadScheduler(
            scope = backgroundScope, debounceMs = 400, isBusy = { false },
            decide = { TierReloadDecision(true, listOf("线程")) },
            reload = {
                calls++
                if (busyAtReload) { busyAtReload = false; TierReloadResult.DEFERRED_BUSY } else TierReloadResult.APPLIED
            },
        )
        s.onTierChanged("全部资源")
        settle()
        assertEquals(2, calls) // retried once the (already finished) reply is gone
        assertFalse(s.pending)
        assertEquals(1, s.reloads)
    }

    @Test fun ggufCpuTierChangeNeverCallsReload() = runTest {
        val loaded = plan(ResourceTier.HIGH)
        var current = plan(ResourceTier.LOW)
        var calls = 0
        val s = TierReloadScheduler(
            scope = backgroundScope, debounceMs = 400, isBusy = { false },
            decide = { TierReloadPolicy.decide(ModelFormat.GGUF, Backend.CPU, loaded, current, Backend.CPU, Backend.CPU) },
            reload = { calls++; TierReloadResult.APPLIED },
        )
        s.onTierChanged("低资源")
        current = plan(ResourceTier.HIGH, userThreads = 8)
        s.onTierChanged("全部资源")
        settle()
        assertEquals(0, calls)
        assertFalse(s.pending)
    }

    @Test fun nothingLoadedSkipsWithoutPending() = runTest {
        val h = Harness(this, decision = null)
        h.s.onTierChanged("低资源")
        settle()
        assertEquals(0, h.reloadCalls)
        assertFalse(h.s.pending)
    }

    // ---------------------------------------------------------------- card text

    @Test fun reloadNotesAreHonest() {
        val idle = LiveResourcePolicy.note(LiveApply.RELOAD, generating = false)
        val gen = LiveResourcePolicy.note(LiveApply.RELOAD, generating = true)
        assertTrue(idle.contains("空闲时自动重新加载模型生效（约几秒）"))
        assertTrue(gen.contains("本条结束后重新加载生效"))
    }
}

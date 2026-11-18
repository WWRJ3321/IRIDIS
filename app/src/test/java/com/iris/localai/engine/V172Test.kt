package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.resources.CpuTopology
import com.iris.localai.engine.resources.LiveApply
import com.iris.localai.engine.resources.LiveChange
import com.iris.localai.engine.resources.LiveResourcePolicy
import com.iris.localai.engine.resources.PendingLive
import com.iris.localai.engine.resources.ResourcePlanner
import com.iris.localai.engine.resources.ResourceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.2: mid-reply resource-tier switch — decision logic. */
class V172Test {

    // Snapdragon 8 Gen 3-like: 2 little, 5 mid, 1 prime.
    private val topo = CpuTopology(listOf(listOf(0, 1), listOf(2, 3, 4, 5, 6), listOf(7)))
    private fun plan(t: ResourceTier, userThreads: Int = 0) = ResourcePlanner.plan(t, topo, 0, userThreads)

    @Test fun ggufOnCpuIsImmediate() {
        assertEquals(LiveApply.IMMEDIATE, LiveResourcePolicy.mode(ModelFormat.GGUF, Backend.CPU))
    }

    @Test fun ggufOnGpuWaitsForNextReply() {
        assertEquals(LiveApply.NEXT_REPLY, LiveResourcePolicy.mode(ModelFormat.GGUF, Backend.GPU))
    }

    @Test fun liteRtAndMnnNeedReloadOnEveryBackend() {
        for (b in Backend.entries) {
            assertEquals(LiveApply.RELOAD, LiveResourcePolicy.mode(ModelFormat.LITERT, b))
            // 1.7.5: MNN on the CPU re-pins its existing threads live (thread count still reloads).
            assertEquals(if (b == Backend.CPU) LiveApply.LIVE_POLICY else LiveApply.RELOAD, LiveResourcePolicy.mode(ModelFormat.MNN, b))
        }
    }

    @Test fun noModelIsNeverImmediate() {
        assertEquals(LiveApply.NEXT_REPLY, LiveResourcePolicy.mode(null, null))
        assertEquals(LiveApply.NEXT_REPLY, LiveResourcePolicy.mode(ModelFormat.GGUF, null))
    }

    @Test fun combineNeedsBothRuleAndEngine() {
        assertEquals(LiveApply.IMMEDIATE, LiveResourcePolicy.combine(LiveApply.IMMEDIATE, LiveApply.IMMEDIATE))
        assertEquals(LiveApply.NEXT_REPLY, LiveResourcePolicy.combine(LiveApply.IMMEDIATE, LiveApply.NEXT_REPLY))
        assertEquals(LiveApply.RELOAD, LiveResourcePolicy.combine(LiveApply.IMMEDIATE, LiveApply.RELOAD))
        // An engine claiming IMMEDIATE cannot upgrade a GPU / LiteRT / MNN rule.
        assertEquals(LiveApply.NEXT_REPLY, LiveResourcePolicy.combine(LiveApply.NEXT_REPLY, LiveApply.IMMEDIATE))
        assertEquals(LiveApply.RELOAD, LiveResourcePolicy.combine(LiveApply.RELOAD, LiveApply.IMMEDIATE))
        assertEquals(LiveApply.IMMEDIATE, LiveResourcePolicy.combine(LiveApply.IMMEDIATE, null))
    }

    @Test fun noteSaysImmediateOnlyForCpuWhileGenerating() {
        val imm = LiveResourcePolicy.note(LiveApply.IMMEDIATE, generating = true)
        assertTrue(imm.contains("立即生效"))
        for (m in listOf(LiveApply.NEXT_REPLY, LiveApply.RELOAD)) {
            assertFalse(LiveResourcePolicy.note(m, true).contains("立即生效"))
        }
        assertTrue(LiveResourcePolicy.note(LiveApply.RELOAD, true).contains("重新加载"))
        assertTrue(LiveResourcePolicy.note(LiveApply.NEXT_REPLY, true).contains("下一条回复生效"))
        for (m in LiveApply.entries) {
            val idle = LiveResourcePolicy.note(m, generating = false)
            assertFalse(idle.contains("立即生效"))
            // 1.7.4: MNN / LiteRT (RELOAD) are reloaded when idle instead of "next reply".
            if (m == LiveApply.RELOAD || m == LiveApply.LIVE_POLICY) assertTrue(idle.contains("重新加载")) else assertTrue(idle.contains("下一条回复生效"))
        }
    }

    @Test fun noteNeverPromisesSpeed() {
        for (m in LiveApply.entries) for (g in listOf(true, false)) {
            val n = LiveResourcePolicy.note(m, g)
            assertFalse(n.contains("字/秒"))
            assertFalse(n.contains("tok/s"))
            // 1.7.4: "约几秒" (reload time) is allowed; no speed estimate such as "约 N 字".
            assertFalse(Regex("约\\s*\\d").containsMatchIn(n))
            assertFalse(n.contains("约") && !n.contains("约几秒"))
        }
    }

    @Test fun liveChangeCarriesThreadsCoresAndNice() {
        val high = plan(ResourceTier.HIGH)
        val low = plan(ResourceTier.LOW)
        val c = LiveResourcePolicy.liveChange(high, low)
        assertEquals(LiveChange(low.threads, low.cpus, low.nice), c)
        assertEquals(listOf(0, 1), c!!.cpus)
        assertEquals(10, c.nice)
        assertEquals(2, c.threads)
    }

    @Test fun liveChangeToAllUnpins() {
        val c = LiveResourcePolicy.liveChange(plan(ResourceTier.LOW), plan(ResourceTier.HIGH, userThreads = 8))
        assertNotNull(c)
        assertTrue("全部 = no pinning (all cores)", c!!.cpus.isEmpty())
        assertEquals(8, c.threads)
        assertEquals(-4, c.nice) // 1.7.8: 「全部」 removed; 8 threads = 高 + manual threads
    }

    @Test fun sameTierIsNoChange() {
        assertNull(LiveResourcePolicy.liveChange(plan(ResourceTier.HIGH), plan(ResourceTier.HIGH)))
    }

    @Test fun batchOnlyDifferenceIsNotAppliedMidReply() {
        val a = plan(ResourceTier.HIGH)
        val b = a.copy(batch = 128, allowGpu = false)
        assertNull(LiveResourcePolicy.liveChange(a, b))
        assertEquals(listOf("批量", "自动选 GPU"), LiveResourcePolicy.deferredParts(a, b, LiveApply.IMMEDIATE))
    }

    @Test fun userThreadsKeepCountButCoresAndNiceStillSwitch() {
        // Per-model fixed thread count: a tier switch still changes cores + priority.
        val c = LiveResourcePolicy.liveChange(plan(ResourceTier.HIGH, 4), plan(ResourceTier.LOW, 4))
        assertNotNull(c)
        assertEquals(4, c!!.threads)
        assertEquals(10, c.nice)
    }

    @Test fun unknownStartPlanAppliesEverything() {
        val low = plan(ResourceTier.LOW)
        assertEquals(LiveChange(low.threads, low.cpus, low.nice), LiveResourcePolicy.liveChange(null, low))
    }

    @Test fun deferredPartsForNonImmediateIsEverything() {
        val a = plan(ResourceTier.HIGH)
        assertEquals(listOf("全部"), LiveResourcePolicy.deferredParts(a, plan(ResourceTier.LOW), LiveApply.RELOAD))
        assertEquals(listOf("全部"), LiveResourcePolicy.deferredParts(a, plan(ResourceTier.LOW), LiveApply.NEXT_REPLY))
    }

    @Test fun pendingLiveIsLatestWinsAndConsumedOnce() {
        val p = PendingLive<Int>()
        assertNull(p.take())
        p.offer(10); p.offer(-4)
        assertTrue(p.isPending)
        assertEquals(-4, p.take())
        assertNull(p.take())
        p.offer(0); p.clear()
        assertFalse(p.isPending)
    }

    @Test fun pendingLiveSurvivesConcurrentOffers() {
        val p = PendingLive<Int>()
        val threads = (1..8).map { i -> Thread { repeat(1000) { p.offer(i) } } }
        threads.forEach { it.start() }
        var taken = 0
        while (threads.any { it.isAlive }) { if (p.take() != null) taken++ }
        threads.forEach { it.join() }
        val last = p.take()
        assertTrue(taken > 0 || last != null)
        assertNull(p.take())
    }
}

package com.iris.localai.engine

import com.iris.localai.engine.load.WarmUpGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 2.1.8: the bounded warm-up at the end of a model load. */
@OptIn(ExperimentalCoroutinesApi::class)
class WarmUpGuardTest {
    @Test fun quickWarmUp_isDone() = runTest {
        var timeouts = 0
        val r = WarmUpGuard.run(onTimeout = { timeouts++ }) { delay(2_000) }
        assertEquals(WarmUpGuard.Outcome.DONE, r)
        assertEquals(0, timeouts)
    }

    @Test fun failingWarmUp_isSwallowed_asFailed() = runTest {
        val r = WarmUpGuard.run(onTimeout = { fail("no timeout") }) { throw IllegalStateException("npu") }
        assertEquals(WarmUpGuard.Outcome.FAILED, r)
    }

    @Test fun hungWarmUp_timesOutAt60s_andCancelsTheEngine() = runTest {
        var timeouts = 0
        val start = testScheduler.currentTime
        val r = WarmUpGuard.run(onTimeout = { timeouts++ }) { awaitCancellation() }
        assertEquals(WarmUpGuard.Outcome.TIMED_OUT, r)
        assertEquals(1, timeouts)
        assertEquals(WarmUpGuard.TIMEOUT_MS, testScheduler.currentTime - start)
    }

    @Test fun throwingOnTimeout_stillReturnsTimedOut() = runTest {
        val r = WarmUpGuard.run(timeoutMs = 1_000, onTimeout = { error("engine gone") }) { awaitCancellation() }
        assertEquals(WarmUpGuard.Outcome.TIMED_OUT, r)
    }

    @Test fun loadCancelled_duringWarmUp_propagates() = runTest {
        var timeouts = 0
        var returned = false
        val load = async {
            WarmUpGuard.run(onTimeout = { timeouts++ }) { awaitCancellation() }
            returned = true
        }
        advanceTimeBy(5_000); runCurrent()
        load.cancel()
        runCurrent()
        assertTrue(load.isCancelled)
        assertFalse(returned)
        assertEquals(0, timeouts)
    }

    @Test fun cancellationThrownByTheBlock_isNotSwallowed() = runTest {
        try {
            WarmUpGuard.run(onTimeout = {}) { throw CancellationException("stop") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test fun timeoutNotice_namesModelAndLimit() {
        val n = WarmUpGuard.timeoutNotice("Qwen3-4B")
        assertTrue(n.startsWith("Qwen3-4B"))
        assertTrue(n.contains("60"))
    }
}

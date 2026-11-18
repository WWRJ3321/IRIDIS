package com.iris.localai.chat

import com.iris.localai.ui.chat.ReplyWatchdog
import com.iris.localai.ui.chat.ReplyWatchdog.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.8 「一发送就卡住」: the reply watchdog, on a virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendHangTest {
    private fun TestScope.dog() = ReplyWatchdog { testScheduler.currentTime }

    @Test fun promptWindow_is90s_plus30msPerToken_cappedAt600s() {
        assertEquals(90_000L, ReplyWatchdog.windowFor(0))
        assertEquals(90_000L, ReplyWatchdog.windowFor(-5))
        assertEquals(120_000L, ReplyWatchdog.windowFor(1_000))
        assertEquals(600_000L, ReplyWatchdog.windowFor(17_000))
        assertEquals(600_000L, ReplyWatchdog.windowFor(1_000_000))
    }

    @Test fun silentPrompt_firesAt90s_notBefore() = runTest {
        val w = dog()
        w.prompt(0)
        advanceTimeBy(89_999)
        assertNull(w.stalled())
        advanceTimeBy(1)
        val v = w.stalled()!!
        assertEquals(Phase.PROMPT, v.phase)
        assertEquals(90_000L, v.idleMs)
    }

    @Test fun longPrompt_getsMoreTime() = runTest {
        val w = dog()
        w.prompt(2_000) // 90 s + 60 s
        advanceTimeBy(149_000)
        assertNull(w.stalled())
        advanceTimeBy(1_000)
        assertEquals(Phase.PROMPT, w.stalled()?.phase)
    }

    @Test fun modelPhase_allows180s() = runTest {
        val w = dog()
        w.waitingForModel()
        assertEquals(ReplyWatchdog.LOAD_STALL_MS, w.limitMs())
        advanceTimeBy(179_000)
        assertNull(w.stalled())
        advanceTimeBy(1_000)
        assertEquals(Phase.MODEL, w.stalled()?.phase)
    }

    @Test fun loadProgress_touch_restartsTheClock() = runTest {
        val w = dog()
        w.waitingForModel()
        repeat(5) { advanceTimeBy(170_000); w.touch() }
        assertNull(w.stalled())
        advanceTimeBy(180_000)
        assertNotNull(w.stalled())
    }

    @Test fun modelReady_switchesToPromptWindow() = runTest {
        val w = dog()
        w.waitingForModel()
        advanceTimeBy(150_000)
        w.prompt(0) // model ready: the clock restarts with the prompt window
        assertEquals(Phase.PROMPT, w.phase)
        advanceTimeBy(89_000)
        assertNull(w.stalled())
        advanceTimeBy(1_000)
        assertEquals(Phase.PROMPT, w.stalled()?.phase)
    }

    @Test fun firstOutput_disarmsForGood() = runTest {
        val w = dog()
        w.prompt(0)
        advanceTimeBy(10_000)
        w.output()
        assertTrue(w.disarmed)
        advanceTimeBy(10_000_000)
        assertNull(w.stalled())
        w.prompt(500) // a later pass (agent step) does not re-arm it
        advanceTimeBy(10_000_000)
        assertNull(w.stalled())
    }

    @Test fun watch_firesOnAHungReply_andTheReplyCanBeCancelled() = runTest {
        val w = dog()
        w.prompt(0)
        val reply = launch { awaitCancellation() } // engine never answers
        val verdict = async { w.watch(reply) }
        advanceTimeBy(89_000); runCurrent()
        assertFalse(verdict.isCompleted)
        advanceTimeBy(2_000); runCurrent()
        val v = verdict.await()
        assertNotNull(v)
        assertEquals(Phase.PROMPT, v!!.phase)
        assertTrue(v.idleMs in 90_000L..91_000L)
        reply.cancel()
        runCurrent()
        assertTrue(reply.isCancelled)
    }

    @Test fun watch_hungModelLoad_firesAfter180s() = runTest {
        val w = dog()
        w.waitingForModel()
        val reply = launch { awaitCancellation() }
        val verdict = async { w.watch(reply) }
        advanceTimeBy(181_000); runCurrent()
        assertEquals(Phase.MODEL, verdict.await()?.phase)
        reply.cancel()
    }

    @Test fun watch_streamingReply_neverFires_returnsNullWhenItEnds() = runTest {
        val w = dog()
        w.prompt(0)
        val reply = launch {
            delay(30_000); w.output() // first token after 30 s
            delay(900_000)            // then a long, slow answer
        }
        val verdict = async { w.watch(reply) }
        advanceTimeBy(1_000_000); runCurrent()
        assertTrue(reply.isCompleted)
        assertNull(verdict.await())
    }

    @Test fun watch_replyEndingFast_returnsNull() = runTest {
        val w = dog()
        w.prompt(0)
        val reply = launch { delay(5_000) }
        val verdict = async { w.watch(reply) }
        advanceTimeBy(6_000); runCurrent()
        assertNull(verdict.await())
    }

    @Test fun summaryPieces_keepALongSummaryAlive() = runTest {
        val w = dog()
        w.prompt(0)
        val reply = launch {
            repeat(30) { delay(10_000); w.touch() } // summary text trickling in for 300 s
            w.prompt(0)
            delay(5_000); w.output()
        }
        val verdict = async { w.watch(reply) }
        advanceTimeBy(400_000); runCurrent()
        assertNull(verdict.await())
    }

    @Test fun messages_nameThePhase() {
        assertTrue(ReplyWatchdog.message(Phase.MODEL, 180_000).contains("180"))
        assertTrue(ReplyWatchdog.message(Phase.PROMPT, 90_000).contains("90"))
    }
}

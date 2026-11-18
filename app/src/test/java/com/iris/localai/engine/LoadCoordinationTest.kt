package com.iris.localai.engine

import com.iris.localai.engine.load.InFlightLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.1.7 #7: a message sent while a model loads waits for *that* load and answers with it (never
 * cancels it to reload the previously used model). The fake mirrors EngineManager.load /
 * ensureLoaded / cancelLoad around the real [InFlightLoad].
 */
class LoadCoordinationTest {
    private class FakeEngine(var last: String?) {
        val inFlight = InFlightLoad()
        private val lifecycle = Mutex()
        var ready: String? = null
        var loading: String? = null
        val loads = mutableListOf<String>()
        private var abandoned: String? = null

        suspend fun load(name: String, ms: Long = 1000): Boolean = inFlight.run(name) {
            lifecycle.withLock {
                if (ready == name) return@withLock true
                loads += name; ready = null; loading = name; abandoned = null
                delay(ms)
                if (abandoned == name) { loading = null; return@withLock false }
                loading = null; ready = name; last = name; true
            }
        }

        fun cancelLoad() {
            val l = loading ?: return
            abandoned = l; loading = null
            inFlight.current()?.let { inFlight.end(it, false) }
        }

        /** EngineManager.ensureLoaded(null). */
        suspend fun ensureLoaded(): Boolean {
            val awaited = inFlight.awaitInFlight()
            if (awaited != null) return awaited && ready != null
            if (ready != null) return true
            return load(last ?: return false)
        }

        /** ChatViewModel.startReply: ensureLoaded → generate with whatever is ready. */
        suspend fun send(): String = if (ensureLoaded()) "reply from $ready" else "ERROR:not loaded"
    }

    @Test fun sendDuringLoadAnswersWithTheLoadingModel() = runTest {
        val e = FakeEngine(last = "A")
        launch { e.load("B") }
        advanceTimeBy(100); runCurrent()
        val reply = async { e.send() }
        advanceTimeBy(2000); runCurrent()
        assertEquals("reply from B", reply.await())
        assertEquals(listOf("B"), e.loads) // A was never (re)loaded
    }

    @Test fun sendDuringLoadWhileAnotherModelIsReadyWaitsForTheNewOne() = runTest {
        val e = FakeEngine(last = "A")
        e.load("A", ms = 10)
        launch { e.load("B") }
        advanceTimeBy(100); runCurrent()
        val reply = async { e.send() }
        advanceTimeBy(2000); runCurrent()
        assertEquals("reply from B", reply.await())
        assertEquals(listOf("A", "B"), e.loads)
    }

    @Test fun followsANewerLoadStartedWhileWaiting() = runTest {
        val e = FakeEngine(last = "A")
        launch { e.load("B") }
        advanceTimeBy(100); runCurrent()
        val reply = async { e.send() }
        advanceTimeBy(100); runCurrent()
        launch { e.load("C") } // queued behind B
        advanceTimeBy(5000); runCurrent()
        assertEquals("reply from C", reply.await())
        assertEquals(listOf("B", "C"), e.loads)
    }

    @Test fun cancelledLoadReleasesTheWaiterWithoutFallingBack() = runTest {
        val e = FakeEngine(last = "A")
        launch { e.load("B") }
        advanceTimeBy(100); runCurrent()
        val reply = async { e.send() }
        advanceTimeBy(100); runCurrent()
        e.cancelLoad()
        runCurrent()
        assertEquals("ERROR:not loaded", reply.await()) // released at once, not after the native call
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf("B"), e.loads)
        assertNull(e.ready)
    }

    @Test fun nothingLoadingFallsBackToDefaultOrLast() = runTest {
        val e = FakeEngine(last = "A")
        val reply = async { e.send() }
        advanceTimeBy(2000); runCurrent()
        assertEquals("reply from A", reply.await())
        assertEquals(InFlightLoad.Plan.Ready, e.inFlight.plan(engineReady = true))
        assertEquals(InFlightLoad.Plan.LoadFallback, e.inFlight.plan(engineReady = false))
    }

    @Test fun inFlightLoadBasics() = runTest {
        val f = InFlightLoad()
        assertNull(f.current()); assertNull(f.awaitInFlight())
        val gate = CompletableDeferred<Boolean>()
        val job = async { f.run("x") { gate.await() } }
        runCurrent()
        assertEquals("x", f.current()?.key)
        assertEquals(InFlightLoad.Plan.AwaitInFlight, f.plan(engineReady = true))
        val w = async { f.awaitInFlight() }
        gate.complete(true); runCurrent()
        assertTrue(job.await()); assertEquals(true, w.await()); assertNull(f.current())
        // a throwing load counts as "not loaded" for waiters
        val boom = async { runCatching { f.run("y") { delay(10); error("oom") } } }
        runCurrent()
        val w2 = async { f.awaitInFlight() }
        advanceTimeBy(20); runCurrent()
        assertTrue(boom.await().isFailure); assertEquals(false, w2.await())
    }

}

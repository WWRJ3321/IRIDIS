package com.iris.localai.imagegen

import com.iris.localai.imagegen.DelayScheduler
import com.iris.localai.imagegen.IdleReleaser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 生图 NPU idle release: 60 s after the last generation, right away when leaving the page — on a fake clock. */
class IdleReleaserTest {

    /** Manual clock: [advance] runs every task whose time has come, in time order. */
    class FakeScheduler : DelayScheduler {
        var now = 0L; private set
        private class Task(val at: Long, val action: () -> Unit) { var cancelled = false; var ran = false }
        private val tasks = ArrayList<Task>()
        val live: Int get() = tasks.count { !it.cancelled && !it.ran }

        override fun schedule(delayMs: Long, action: () -> Unit): DelayScheduler.Cancellable {
            val t = Task(now + delayMs, action)
            tasks.add(t)
            return DelayScheduler.Cancellable { t.cancelled = true }
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && !it.ran && it.at <= until }.minByOrNull { it.at } ?: break
                now = next.at
                next.ran = true
                next.action()
            }
            now = until
        }
    }

    /** Stands in for the QNN engine: counts releases, knows whether its graphs are loaded. */
    class FakeEngine {
        var loaded = false
        var releases = 0
        fun generate() { loaded = true }
        fun release() { if (loaded) releases++; loaded = false }
    }

    private val clock = FakeScheduler()
    private val engine = FakeEngine()
    private val idle = IdleReleaser(timeoutMs = 60_000L, scheduler = clock) { engine.release() }

    private fun generateOnce() = runBlocking { idle.track { engine.generate() } }

    @Test fun defaultTimeoutIsSixtySeconds() = assertEquals(60_000L, IdleReleaser.DEFAULT_TIMEOUT_MS)

    @Test fun releasesSixtySecondsAfterTheLastGeneration() {
        generateOnce()
        assertTrue(idle.isPending)
        clock.advance(59_999L)
        assertTrue(engine.loaded)
        assertEquals(0, engine.releases)
        clock.advance(1L)
        assertFalse(engine.loaded)
        assertEquals(1, engine.releases)
        assertFalse(idle.isPending)
    }

    @Test fun aNewGenerationRestartsTheTimer() {
        generateOnce()
        clock.advance(50_000L)
        generateOnce()
        assertEquals(1, clock.live) // the first timer was cancelled
        clock.advance(50_000L)       // 100 s after the first, 50 s after the second
        assertTrue(engine.loaded)
        clock.advance(10_000L)
        assertFalse(engine.loaded)
        assertEquals(1, engine.releases)
    }

    @Test fun neverReleasesWhileAGenerationRuns() {
        idle.begin()
        engine.generate()
        clock.advance(10 * 60_000L)
        assertTrue(engine.loaded)
        assertFalse(idle.isPending)
        idle.end()
        clock.advance(60_000L)
        assertFalse(engine.loaded)
    }

    @Test fun overlappingGenerationsArmOnlyAfterTheLastEnds() {
        idle.begin(); idle.begin()   // 生图 page + generate_image
        engine.generate()
        idle.end()
        assertFalse(idle.isPending)
        assertEquals(1, idle.activeCount)
        clock.advance(120_000L)
        assertTrue(engine.loaded)
        idle.end()
        assertTrue(idle.isPending)
        clock.advance(60_000L)
        assertFalse(engine.loaded)
    }

    @Test fun failedOrCancelledGenerationStillArmsTheTimer() {
        try {
            runBlocking { idle.track<Unit> { engine.generate(); throw IllegalStateException("graphExecute failed") } }
            fail("expected the failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("graphExecute failed", e.message)
        }
        assertEquals(0, idle.activeCount)
        clock.advance(60_000L)
        assertFalse(engine.loaded)
    }

    @Test fun leavingThePageReleasesRightAwayOnTheSchedulerThread() {
        generateOnce()
        idle.releaseWhenIdle()
        assertTrue(engine.loaded)          // not on the caller's (main) thread
        clock.advance(0L)
        assertFalse(engine.loaded)
        assertEquals(1, clock.live + engine.releases) // the 60 s timer was replaced, not kept
        clock.advance(60_000L)
        assertEquals(1, engine.releases)
    }

    @Test fun leavingDuringAGenerationReleasesWhenItEnds() {
        idle.begin()
        engine.generate()
        idle.releaseWhenIdle()
        clock.advance(0L)
        assertTrue(engine.loaded)
        idle.end()                          // generation thread frees the graphs at once
        assertFalse(engine.loaded)
        assertFalse(idle.isPending)
        clock.advance(60_000L)
        assertEquals(1, engine.releases)
    }

    @Test fun generationStartingBeforeTheLeaveReleaseFiresKeepsTheGraphs() {
        generateOnce()
        idle.releaseWhenIdle()
        idle.begin()                         // generate_image starts before the scheduler thread ran
        clock.advance(0L)
        assertTrue(engine.loaded)
        idle.end()
        clock.advance(60_000L)
        assertFalse(engine.loaded)
    }

    @Test fun releaseFailureIsSwallowed() {
        val boom = IdleReleaser(timeoutMs = 1L, scheduler = clock) { throw IllegalStateException("contextFree") }
        runBlocking { boom.track { } }
        clock.advance(1L)                    // no throw
        runBlocking { boom.track { } }
        assertTrue(boom.isPending)
    }
}

package com.iris.localai.engine

import com.iris.localai.engine.load.ReloadGate
import com.iris.localai.engine.StopReason
import com.iris.localai.engine.resources.TierReloadDecision
import com.iris.localai.engine.resources.TierReloadResult
import com.iris.localai.engine.resources.TierReloadScheduler
import com.iris.localai.util.thinking.ReplyAccounting
import com.iris.localai.util.thinking.StreamingThinkSplitter
import com.iris.localai.util.thinking.ThinkOnlyPolicy
import com.iris.localai.util.thinking.ThinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.7.7: (1) first message after a resource-tier change failed; (2) replies that stop after the
 * reasoning with no answer.
 *
 * [FakeManager] reproduces EngineManager's locking exactly (lifecycle → generation mutexes,
 * `engine = null` while loading, generate → "模型未加载" when the engine is gone) with a fake engine,
 * because EngineManager itself needs an Android Context. `legacy = true` is the 1.7.6 behaviour,
 * `legacy = false` the 1.7.7 one (ReloadGate + recovery); the same timeline is run against both.
 */
class V177Test {

    private interface Sched { fun onTierChanged(label: String); fun onGenerationFinished() }

    /** TierReloadScheduler as shipped in 1.7.6 (verbatim logic): a new tier change cancels `job`, even mid-reload. */
    private class LegacyScheduler176(
        private val scope: CoroutineScope, private val debounceMs: Long, private val isBusy: () -> Boolean,
        private val decide: () -> TierReloadDecision?, private val reload: suspend () -> TierReloadResult,
    ) : Sched {
        private val lock = Any()
        private var job: Job? = null
        @Volatile var pending = false
        override fun onTierChanged(label: String) {
            synchronized(lock) { job?.cancel(); job = scope.launch { delay(debounceMs); evaluate() } }
        }
        override fun onGenerationFinished() {
            if (!pending) return
            synchronized(lock) { if (job?.isActive == true) return; job = scope.launch { evaluate() } }
        }
        private suspend fun evaluate() {
            val d = decide()
            if (d == null || !d.reload) { pending = false; return }
            if (isBusy()) { pending = true; if (!isBusy()) evaluate(); return }
            pending = false
            if (reload() == TierReloadResult.DEFERRED_BUSY) { pending = true; if (!isBusy()) evaluate() }
        }
    }

    private fun TestScope.settle() { advanceTimeBy(5_000); runCurrent() }

    /** Mirrors EngineManager: same locks, same order, same failure message. */
    private class FakeManager(
        val scope: TestScope,
        val legacy: Boolean,
        var loadMs: Long = 1_000,
        /** Loads that fail (1-based count of load attempts). */
        val failingLoads: Set<Int> = emptySet(),
    ) {
        val lifecycle = Mutex()
        val generation = Mutex()
        var engine: String? = "engine#0"
        var state = "Ready"
        var busy = false
        var loads = 0
        val gate = ReloadGate()
        var lostByReload = false
        var restoredWithPrevious = 0
        private val isBusyFn: () -> Boolean = { busy || (!legacy && gate.requests > 0) }
        private val decideFn: () -> TierReloadDecision? = { if (state == "Ready") TierReloadDecision(true, listOf("线程")) else null }
        val sched: Sched = if (legacy) LegacyScheduler176(scope.backgroundScope, 400, isBusyFn, decideFn, ::reloadForTier)
        else TierReloadScheduler(scope.backgroundScope, 400, isBusyFn, decideFn, ::reloadForTier).let { t ->
            object : Sched {
                override fun onTierChanged(label: String) = t.onTierChanged(label)
                override fun onGenerationFinished() = t.onGenerationFinished()
            }
        }
        init { gate.onIdle = { sched.onGenerationFinished() } }

        suspend fun loadLocked(): Boolean = generation.withLock {
            engine = null
            state = "Loading"
            loads++
            try {
                delay(loadMs)
            } catch (c: CancellationException) {
                state = "Idle"; throw c
            }
            if (loads in failingLoads) { state = "Failed"; false }
            else { engine = "engine#$loads"; state = "Ready"; lostByReload = false; true }
        }

        suspend fun reloadForTier(): TierReloadResult = lifecycle.withLock {
            if (state != "Ready") return@withLock TierReloadResult.SKIPPED
            if (busy) return@withLock TierReloadResult.DEFERRED_BUSY
            if (legacy) return@withLock if (loadLocked()) TierReloadResult.APPLIED else TierReloadResult.FAILED
            if (!gate.tryBeginReload()) return@withLock TierReloadResult.DEFERRED_BUSY
            try {
                if (loadLocked()) TierReloadResult.APPLIED
                else {
                    // restore with the previous tier (same model) – 1.7.7
                    if (loadLocked()) restoredWithPrevious++ else lostByReload = true
                    TierReloadResult.FAILED
                }
            } finally { gate.endReload() }
        }

        suspend fun ensureLoaded(): Boolean {
            if (state == "Ready") return true
            return lifecycle.withLock { if (state == "Ready") true else { loadLocked() } }
        }

        /** EngineManager.generate: returns the Done reason/message ("OK" = generated). */
        suspend fun generate(): String {
            suspend fun once(): String = generation.withLock {
                val e = engine ?: return@withLock "ERROR:模型未加载"
                busy = true
                try { delay(200); "OK:$e" } finally { busy = false; sched.onGenerationFinished() }
            }
            if (legacy) return once()
            return gate.request {
                if ((engine == null || state != "Ready") && lostByReload) lifecycle.withLock { loadLocked() }
                once()
            }
        }

        /** ChatViewModel.startReply: ensureLoaded → (planning / summary) → generate. */
        suspend fun send(planningMs: Long, onWaiting: (Boolean) -> Unit = {}): String {
            suspend fun body(): String {
                if (!ensureLoaded()) return "ERROR:not loaded"
                delay(planningMs) // planFor / summarize / dispatch to the generate flow
                return generate()
            }
            return if (legacy) body() else gate.request(onWaiting) { body() }
        }
    }

    // ------------------------------------------------------------------ bug 1: repro of the race

    /**
     * Timeline from the bug report: tier changed, the user sends just as the 400 ms debounce ends,
     * then changes the tier once more while the reload runs (a second slider step). 1.7.6: the
     * second change cancelled the running reload (model unloaded, state Idle), and the message that
     * had already passed ensureLoaded got "模型未加载".
     */
    private suspend fun TestScope.raceTimeline(m: FakeManager): String {
        m.sched.onTierChanged("全部资源")
        advanceTimeBy(390); runCurrent()
        val reply = async { m.send(planningMs = 50) } // passes ensureLoaded at t=390 (still Ready)
        advanceTimeBy(20); runCurrent()               // t=410: debounce fired
        advanceTimeBy(300); runCurrent()
        m.sched.onTierChanged("高资源")               // second step while reloading
        settle()
        return reply.await()
    }

    @Test fun legacy176_firstMessageAfterTierChangeFails() = runTest {
        val m = FakeManager(this, legacy = true)
        val r = raceTimeline(m)
        assertEquals("ERROR:模型未加载", r) // the reported bug
    }

    @Test fun v177_firstMessageAfterTierChangeIsGenerated() = runTest {
        val m = FakeManager(this, legacy = false)
        val r = raceTimeline(m)
        assertTrue(r, r.startsWith("OK:"))
        settle()
        assertEquals("Ready", m.state)       // the deferred reload ran after the reply
        assertTrue(m.loads >= 1)
        assertEquals(0, m.gate.requests)
    }

    @Test fun legacy176_sendDuringPlanningWindowLosesEngineWhenReloadFails() = runTest {
        // Reload starts in the gap between ensureLoaded and generate and fails (e.g. OOM).
        val m = FakeManager(this, legacy = true, failingLoads = setOf(1))
        m.sched.onTierChanged("全部资源")
        advanceTimeBy(390); runCurrent()
        val reply = async { m.send(planningMs = 100) }
        settle()
        assertEquals("ERROR:模型未加载", reply.await())
    }

    @Test fun v177_requestRegisteredBeforeReloadDefersTheReload() = runTest {
        val m = FakeManager(this, legacy = false, failingLoads = setOf(1))
        m.sched.onTierChanged("全部资源")
        advanceTimeBy(390); runCurrent()
        val reply = async { m.send(planningMs = 100) }
        advanceTimeBy(20); runCurrent()
        assertEquals("Ready", m.state) // reload did not start under the registered request
        assertEquals(0, m.loads)
        settle()
        assertTrue(reply.await().startsWith("OK:engine#0"))
        settle()
        // then the reload ran, failed, and the model was restored with the previous tier
        assertEquals(2, m.loads)
        assertEquals(1, m.restoredWithPrevious)
        assertEquals("Ready", m.state)
    }

    @Test fun v177_sendDuringReloadWaitsShowsStatusAndStartsByItself() = runTest {
        val m = FakeManager(this, legacy = false, loadMs = 3_000)
        m.sched.onTierChanged("全部资源")
        advanceTimeBy(500); runCurrent() // reload running
        assertTrue(m.gate.reloading.value)
        val statuses = ArrayList<Boolean>()
        val reply = async { m.send(planningMs = 10) { statuses += it } }
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(true), statuses)      // UI: 「正在重载模型…」
        assertEquals(1, m.gate.waiting.value)
        assertFalse(reply.isCompleted)
        settle()
        assertEquals("OK:engine#1", reply.await()) // generated on the reloaded engine
        assertEquals(listOf(true, false), statuses)
        assertEquals(0, m.gate.waiting.value)
    }

    @Test fun v177_reloadAndRestoreBothFailNextRequestLoadsAgain() = runTest {
        val m = FakeManager(this, legacy = false, failingLoads = setOf(1, 2))
        m.sched.onTierChanged("全部资源")
        advanceTimeBy(500); runCurrent()
        val reply = async { m.generate() } // e.g. an API request queued behind the reload
        settle()
        assertTrue(reply.await().startsWith("OK:engine#3")) // recovered, not "模型未加载"
        assertFalse(m.lostByReload)
    }

    @Test fun tierChangeDuringReloadDoesNotCancelIt() = runTest {
        var started = 0
        var finished = 0
        val s = TierReloadScheduler(
            scope = backgroundScope, debounceMs = 400, isBusy = { false },
            decide = { TierReloadDecision(true, listOf("线程")) },
            reload = { started++; delay(2_000); finished++; TierReloadResult.APPLIED },
        )
        s.onTierChanged("全部资源")
        advanceTimeBy(1_000); runCurrent()
        assertTrue(s.reloading)
        s.onTierChanged("高资源")
        settle(); settle()
        assertEquals(started, finished)  // nothing was cut off mid-load
        assertEquals(2, finished)        // and the newer tier was evaluated after the first reload
        assertFalse(s.reloading)
    }

    @Test fun gateDefersReloadWhileRequestRegisteredAndRunsItWhenIdle() = runTest {
        val g = ReloadGate()
        var idle = 0
        g.onIdle = { idle++ }
        val hold = CompletableDeferred<Unit>()
        val req = launch { g.request { hold.await() } }
        runCurrent()
        assertFalse(g.tryBeginReload())
        hold.complete(Unit)
        req.join()
        assertEquals(1, idle)
        assertTrue(g.tryBeginReload())
        assertFalse(g.tryBeginReload()) // only one reload at a time
        g.endReload()
    }

    @Test fun gateRequestCancelledWhileWaitingLeavesNoTrace() = runTest {
        val g = ReloadGate()
        assertTrue(g.tryBeginReload())
        val waits = ArrayList<Boolean>()
        val req = launch { g.request({ waits += it }) { "never" } }
        runCurrent()
        assertEquals(1, g.waiting.value)
        req.cancel(); runCurrent()
        assertEquals(0, g.waiting.value)
        assertEquals(0, g.requests)
        assertEquals(listOf(true, false), waits)
        g.endReload()
    }

    @Test fun nestedRequestsDoNotDeadlock() = runTest {
        val g = ReloadGate()
        val r = g.request { g.request { "inner" } }
        assertEquals("inner", r)
        assertEquals(0, g.requests)
    }

    // ------------------------------------------------------------------ bug 2: think-only replies

    private fun parts(raw: String) = ThinkParser.parse(raw)

    @Test fun eosRightAfterCloseTagContinuesOnce() {
        val v = ThinkOnlyPolicy.decide(parts("<think>想一想……</think>\n\n"), StopReason.END, null, false, 2048)
        assertEquals(ThinkOnlyPolicy.Action.CONTINUE, v.action)
    }

    @Test fun reasoningUsedTheWholeLimitContinuesOnceThenExplains() {
        val cut = parts("<think>很长的思考没有结束")
        assertTrue(cut.thinkingOpen)
        assertEquals(ThinkOnlyPolicy.Action.CONTINUE, ThinkOnlyPolicy.decide(cut, StopReason.MAX_TOKENS, null, false, 2048).action)
        val after = ThinkOnlyPolicy.decide(parts("<think>x</think>"), StopReason.MAX_TOKENS, null, true, 2048)
        assertEquals(ThinkOnlyPolicy.Action.NOTICE, after.action)
        assertTrue(after.message!!.startsWith(ThinkOnlyPolicy.THINK_ONLY))
        assertTrue(after.message!!.contains("2048"))
    }

    @Test fun stillEmptyAfterContinuationSaysOnlyThinking() {
        val v = ThinkOnlyPolicy.decide(parts("<think>x</think>\n\n"), StopReason.END, null, true, 2048)
        assertEquals(ThinkOnlyPolicy.Action.NOTICE, v.action)
        assertTrue(v.message!!.contains("模型只输出了思考"))
    }

    @Test fun answerPresentOrUserStoppedOrNoThinkingDoesNothing() {
        assertEquals(ThinkOnlyPolicy.Action.NONE, ThinkOnlyPolicy.decide(parts("<think>x</think>答案"), StopReason.END, null, false, 2048).action)
        assertEquals(ThinkOnlyPolicy.Action.NONE, ThinkOnlyPolicy.decide(parts("<think>x</think>"), StopReason.CANCELLED, null, false, 2048).action)
        assertEquals(ThinkOnlyPolicy.Action.NONE, ThinkOnlyPolicy.decide(parts("普通回答"), StopReason.END, null, false, 2048).action)
    }

    @Test fun contextFullAndErrorAreExplainedNotRetried() {
        val full = ThinkOnlyPolicy.decide(parts("<think>x"), StopReason.CONTEXT_FULL, null, false, 2048)
        assertEquals(ThinkOnlyPolicy.Action.NOTICE, full.action)
        assertTrue(full.message!!.contains("上下文已满"))
        val err = ThinkOnlyPolicy.decide(parts("<think>x</think>"), StopReason.ERROR, "MNN 解码失败", false, 2048)
        assertEquals(ThinkOnlyPolicy.Action.NOTICE, err.action)
        assertTrue(err.message!!.contains("MNN 解码失败"))
    }

    @Test fun bridgeClosesAnUnfinishedBlockSoTheContinuationIsTheAnswer() {
        val cut = parts("<think>还在想")
        val stored = "<think>还在想" + ThinkOnlyPolicy.bridge(cut) + "最终答案"
        val p = ThinkParser.parse(stored)
        assertEquals("还在想", p.thinking)
        assertEquals("最终答案", p.answer)
        val closed = "<think>想完了</think>" + ThinkOnlyPolicy.bridge(parts("<think>想完了</think>")) + "答案"
        assertEquals("答案", ThinkParser.parse(closed).answer)
    }

    @Test fun closeTagSplitAcrossPiecesIsRecognised() {
        // Suspect 2: "</think>" cut into several pieces must not swallow the answer.
        for (cut in 1 until "</think>".length) {
            val sp = StreamingThinkSplitter()
            val out = ArrayList<StreamingThinkSplitter.Piece>()
            listOf("<think>推理", "</think>".substring(0, cut), "</think>".substring(cut) + "\n\n答", "案").forEach { out += sp.push(it) }
            out += sp.flush()
            val answer = out.filter { !it.thinking }.joinToString("") { it.text }.trim()
            val thinking = out.filter { it.thinking }.joinToString("") { it.text }
            assertEquals("cut=$cut", "答案", answer)
            assertEquals("推理", thinking)
        }
    }

    @Test fun accountingSplitsTokensAndNamesTheEndReason() {
        val a = ReplyAccounting(startInThinking = false)
        listOf("<think>", "推", "理", "中", "</think>", "\n\n", "答", "案").forEach(a::onPiece)
        assertEquals(5, a.thinkPieces)  // <think> 推 理 中 </think>
        assertEquals(3, a.answerPieces)
        val (t, ans) = a.tokens(80)
        assertEquals(80, t + ans)
        assertEquals(50, t)
        val line = a.describe(StopReason.END, 80, 2048, null)
        assertTrue(line, line.contains("reason=EOS"))
        assertTrue(line, line.contains("thinking closed"))
        assertFalse(line, line.contains("ANSWER EMPTY"))
    }

    @Test fun accountingFlagsThinkOnlyAndLengthLimit() {
        val a = ReplyAccounting(startInThinking = true) // template pre-opened <think>
        listOf("想", "了", "很", "久").forEach(a::onPiece)
        assertEquals(4, a.thinkPieces)
        assertEquals(0, a.answerPieces)
        val line = a.describe(StopReason.MAX_TOKENS, 2048, 2048, null)
        assertTrue(line, line.contains("reason=LENGTH"))
        assertTrue(line, line.contains("thinking NOT closed"))
        assertTrue(line, line.contains("ANSWER EMPTY"))
        assertEquals(2048 to 0, a.tokens(2048))
        assertEquals("CANCELLED", ReplyAccounting.reasonName(StopReason.CANCELLED))
        assertEquals("ERROR", ReplyAccounting.reasonName(StopReason.ERROR))
    }

    @Test fun eosRightAfterCloseTagIsVisibleInTheLog() {
        val a = ReplyAccounting(startInThinking = false)
        listOf("<think>", "x", "</think>", "\n\n").forEach(a::onPiece)
        val line = a.describe(StopReason.END, 4, 2048, null)
        assertTrue(line, line.contains("thinking closed") && line.contains("ANSWER EMPTY"))
    }

}

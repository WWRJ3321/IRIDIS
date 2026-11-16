package com.iris.localai.v267

import com.iris.localai.agent.AgentPrompt
import com.iris.localai.agent.AgentSettings
import com.iris.localai.data.AppSettings
import com.iris.localai.data.DownloadSource.*
import com.iris.localai.data.DownloadSources
import com.iris.localai.data.ModelDirs
import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.CoolDown
import com.iris.localai.engine.ThinkLevel
import com.iris.localai.ui.agent.ImageGenLive
import com.iris.localai.ui.chat.PerfTone
import com.iris.localai.ui.chat.ThinkFold
import com.iris.localai.util.io.ProgressThrottle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 2.6.7: 下载卡顿/测速收敛、模型位置误判、生图混色、思考自动收起、先问清楚、过热暂停。 */
class V267Test {
    private fun file(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }
    private fun src(p: String) = file(p).readText()
    private fun root(p: String) = listOf(p, "../$p").map { File(it) }.first { it.exists() }

    @Test fun version() {
        val r = UpdateNotes.RELEASES.first { it.version == "2.6.7" } // 2.6.8: 已不是最新版
        assertTrue(r.of(UpdateNotes.Tag.FIX).size >= 4)
        assertTrue(root("CHANGELOG.md").readText().contains("## 2.6.7（"))
    }

    // ---- 1 下载卡顿
    @Test fun throttleAtMostEvery200ms() {
        var now = 1000L
        val t = ProgressThrottle(now = { now })
        assertTrue(t.ready())
        now += 50; assertFalse(t.ready())
        now += 149; assertFalse(t.ready())
        now += 1; assertTrue(t.ready())
        assertFalse(t.ready())
        assertEquals(200L, ProgressThrottle.MIN_MS)
    }

    @Test fun downloadsUseThrottleAndScreenReadsDiskOffMain() {
        assertTrue(src("src/main/java/com/iris/localai/util/io/HttpDownload.kt").contains("throttle.ready()"))
        assertTrue(src("src/main/java/com/iris/localai/util/io/ParallelDownload.kt").contains("throttle.ready()"))
        val s = src("src/main/java/com/iris/localai/ui/models/DownloadModelsScreen.kt")
        assertFalse(s.contains("KbEmbedModels.isDownloaded(item.repoId"))
        assertTrue(s.contains("item.repoId in embedDone"))
    }

    // ---- 2 测速收敛
    private val url = "https://huggingface.co/lmstudio-community/Qwen3-4B-GGUF/resolve/main/"

    @Test fun probeOncePerSourceAndRepoSharedInFlight() = runBlocking<Unit> {
        DownloadSources.clearProbes()
        val calls = AtomicInteger()
        val probe: (String) -> Long? = { u -> calls.incrementAndGet(); Thread.sleep(50); if ("huggingface.co" in u) null else if ("hf-mirror" in u) 300L else 100L }
        val order = listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE)
        // 5 files of one repo start together (like shards / mmproj)
        val r = (1..5).map { i -> async { DownloadSources.probeOrder(DownloadSources.candidates(url + "f$i.gguf", order), probe) } }.awaitAll()
        assertEquals(3, calls.get())
        assertEquals(3, DownloadSources.probesSent)
        r.forEach { assertEquals(listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE), it.map { c -> c.source }) }
        // later files: reachable ones reused, the unreachable one not re-probed within 10 min either
        DownloadSources.probeOrder(DownloadSources.candidates(url + "g.gguf", order), probe)
        assertEquals(3, calls.get())
        assertTrue(DownloadSources.lastSpeed!!.contains("100 ms"))
        assertTrue(DownloadSources.lastSpeed!!.contains("×"))
    }

    @Test fun failedProbeRetriedAfterTtlSuccessKeptForRun() = runBlocking<Unit> {
        DownloadSources.clearProbes()
        val c = DownloadSources.candidates(url + "x.gguf", listOf(HF_MIRROR)).single()
        var n = 0
        assertNull(DownloadSources.latency(c, { n++; null }, now = 0L))
        assertNull(DownloadSources.latency(c, { n++; null }, now = DownloadSources.FAIL_TTL - 1))
        assertEquals(1, n)
        assertEquals(200L, DownloadSources.latency(c, { n++; 200L }, now = DownloadSources.FAIL_TTL + 1))
        assertEquals(200L, DownloadSources.latency(c, { n++; 999L }, now = 100L * DownloadSources.FAIL_TTL))
        assertEquals(2, n)
    }

    @Test fun listingLatencyRememberedNoSecondProbe() = runBlocking<Unit> {
        DownloadSources.clearProbes()
        val repo = "lmstudio-community/Qwen3-4B-GGUF"
        DownloadSources.remember(MODELSCOPE, repo, 120L)
        DownloadSources.remember(HF_MIRROR, repo, 400L)
        DownloadSources.remember(HUGGING_FACE, repo, null)
        val r = DownloadSources.probeOrder(DownloadSources.candidates(url + "y.gguf", listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE))) { fail("no probe"); null }
        assertEquals(MODELSCOPE, r.first().source)
        assertEquals(0, DownloadSources.probesSent)
        val line = DownloadSources.speedLine(listOf(MODELSCOPE, HUGGING_FACE), mapOf(MODELSCOPE to 120L, HUGGING_FACE to null))
        assertTrue(line.endsWith("120 ms · HuggingFace ×"))
        assertEquals("测速", DownloadSources.SPEED); assertEquals("下载", DownloadSources.GET)
    }

    // ---- 3 模型位置误判
    @Test fun followsRequestedLocationNotTransientFallback() {
        val dl = File("/storage/emulated/0/Download")
        // workspace fell back to private at startup, but the setting is 下载 → still Download/IRIDIS
        assertTrue(ModelDirs.followsStorage(File("/data/user/0/com.iris.localai/files/iridis"), dl, requestedShared = true))
        assertFalse(ModelDirs.followsStorage(File("/data/user/0/com.iris.localai/files/iridis"), dl, requestedShared = false))
        assertFalse(ModelDirs.followsStorage(null, null, requestedShared = true))
        assertTrue(ModelDirs.followsStorage(File("/sdcard/Download/IRIDIS/"), dl))
        assertTrue(ModelDirs.followsStorage(File("/storage/self/primary/Download/IRIDIS"), dl))
    }

    @Test fun pathAliases() {
        assertTrue(ModelDirs.samePath(File("/sdcard/Download/IRIDIS/models"), File("/storage/emulated/0/Download/IRIDIS/models/")))
        assertTrue(ModelDirs.samePath(File("/storage/emulated/0/Download/./IRIDIS"), File("/mnt/user/0/primary/Download/IRIDIS")))
        assertFalse(ModelDirs.samePath(File("/sdcardx/Download"), File("/storage/emulated/0/Download")))
        assertTrue(ModelDirs.isShared(File("/sdcard/Download/IRIDIS/models"), File("/storage/emulated/0/Download")))
        assertFalse(ModelDirs.isShared(File("/storage/emulated/0/Android/data/x/files/models"), File("/storage/emulated/0/Download")))
    }

    @Test fun resolveUsesSharedWithoutAllFilesWhenRequested() {
        val tmp = kotlin.io.path.createTempDirectory("v267").toFile()
        val dl = File(tmp, "Download"); val priv = File(tmp, "priv")
        val loc = ModelDirs.resolve(false, "mounted", dl, priv, usable = { true },
            followStorage = ModelDirs.followsStorage(File(tmp, "files/iridis"), dl, requestedShared = true))
        assertTrue(loc.shared); assertEquals(File(File(dl, "IRIDIS"), "models"), loc.dir)
        tmp.deleteRecursively()
    }

    @Test fun probeUsesFreshFileName() {
        val tmp = kotlin.io.path.createTempDirectory("v267p").toFile()
        File(tmp, ".iris-probe").apply { writeText("x"); setReadOnly() } // left by an older install
        assertTrue(com.iris.localai.agent.WorkspaceResolver.probe(tmp))
        assertEquals(listOf(".iris-probe"), tmp.list()!!.toList())
        tmp.deleteRecursively()
    }

    // ---- 4 生图混色
    @Test fun cyclicColourIsContinuous() {
        val p = listOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        assertEquals(p[0], ImageGenLive.cyclicArgb(p, 0f))
        assertEquals(ImageGenLive.cyclicArgb(p, 0f), ImageGenLive.cyclicArgb(p, 1f))
        ImageGenLive.cyclicArgb(p, 1f / 3).let { c -> for (sh in listOf(0, 8, 16, 24)) assertTrue(kotlin.math.abs(((c ushr sh) and 0xFF) - ((p[1] ushr sh) and 0xFF)) <= 2) }
        // no jumps: neighbouring phases differ by at most a few levels per channel
        var prev = ImageGenLive.cyclicArgb(p, 0f)
        for (i in 1..3000) {
            val c = ImageGenLive.cyclicArgb(p, i / 3000f)
            for (sh in listOf(0, 8, 16, 24)) assertTrue(kotlin.math.abs(((c ushr sh) and 0xFF) - ((prev ushr sh) and 0xFF)) <= 3)
            prev = c
        }
        val s = src("src/main/java/com/iris/localai/ui/agent/ImageGenLive.kt")
        assertFalse(s.contains("(phase * 4).toInt()"))
        assertFalse(s.contains("Color.Transparent"))
    }

    // ---- 5 思考自动收起
    @Test fun autoFold() {
        assertFalse(AppSettings().autoFoldThinking)
        assertTrue(ThinkFold.shouldFold(true, true, true))
        assertFalse(ThinkFold.shouldFold(false, true, true))
        assertFalse(ThinkFold.shouldFold(true, true, false))
        assertFalse(ThinkFold.shouldFold(true, false, true))
        assertTrue(src("src/main/java/com/iris/localai/ui/chat/MessageItem.kt").contains("answerStarted = hasAnswer"))
    }

    // ---- 6 先问清楚
    @Test fun askFirst() {
        assertTrue(AgentSettings().askFirst)
        assertTrue(AgentPrompt.build(emptyList(), askFirst = true).contains(AgentPrompt.ASK_FIRST))
        assertFalse(AgentPrompt.build(emptyList(), askFirst = false).contains("Ask 1–2"))
        assertTrue(src("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt").contains("askFirst = ag.askFirst"))
    }

    // ---- 7 过热暂停 + 少算
    @Test fun coolDownHysteresis() {
        assertFalse(CoolDown.next(false, 44.9f, 45))
        assertTrue(CoolDown.next(false, 45f, 45))
        assertTrue(CoolDown.next(true, 43f, 45))
        assertFalse(CoolDown.next(true, 42f, 45))
        assertFalse(CoolDown.next(true, 60f, 0))
        assertFalse(CoolDown.next(false, Float.NaN, 45))
        assertEquals(0, CoolDown.sanitize(-1)); assertEquals(38, CoolDown.sanitize(20)); assertEquals(55, CoolDown.sanitize(90))
        assertEquals(45, AppSettings().coolPauseC); assertFalse(AppSettings().coolStopNow)
    }

    @Test fun computeLessWhenHot() {
        assertFalse(CoolDown.hot(42.9f, 45)); assertTrue(CoolDown.hot(43f, 45)); assertFalse(CoolDown.hot(50f, 0))
        assertEquals(ThinkLevel.LOW_TOKENS, CoolDown.thinkBudget(0, true))
        assertEquals(ThinkLevel.LOW_TOKENS, CoolDown.thinkBudget(4096, true))
        assertEquals(512, CoolDown.thinkBudget(512, true))
        assertEquals(4096, CoolDown.thinkBudget(4096, false))
        assertEquals(CoolDown.HOT_MAX_NEW, CoolDown.maxNew(8192, true))
        assertEquals(300, CoolDown.maxNew(300, true))
        assertEquals(8192, CoolDown.maxNew(8192, false))
        assertEquals(ThinkLevel.LOW, CoolDown.effort(3, hasLevels = true, hot = true))
        assertEquals(0, CoolDown.effort(0, hasLevels = false, hot = true))
        assertTrue(CoolDown.shouldStop(true, 45f, 45)); assertFalse(CoolDown.shouldStop(false, 50f, 45)); assertFalse(CoolDown.shouldStop(true, 44f, 45))
    }

    @Test fun noSlowDownAndWiredForAllEngines() {
        val e = src("src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(e.contains("CoolDown.awaitCool"))
        assertTrue(e.contains("CoolDown.stopNowHit()"))
        assertTrue(e.contains("CoolDown.maxNew("))
        val c = src("src/main/java/com/iris/localai/engine/resources/CoolDown.kt")
        assertFalse(c.contains("threads"))
        assertTrue(src("src/main/res/values/strings.xml").contains("降温靠暂停和少算，不靠降速"))
    }

    @Test fun awaitCoolReturnsAtOnceWhenNotCooling() = runBlocking<Unit> {
        CoolDown.setLimit(0, false)
        var waited = false
        CoolDown.awaitCool { waited = true }
        assertFalse(waited)
    }

    @Test fun perfTone() {
        assertEquals(0, PerfTone.of(40f, 45)); assertEquals(1, PerfTone.of(43.5f, 45)); assertEquals(2, PerfTone.of(45f, 45))
        assertEquals(2, PerfTone.of(42f, 0)); assertEquals(0, PerfTone.of(Float.NaN, 45))
    }

    @Test fun edge0LogsCacheStats() {
        val j = src("src/main/cpp/edge0/edge0_jni.cpp")
        assertTrue(j.contains("weight_activity") && j.contains("\"prefetch\""))
    }

    @Test fun stringsInSevenLanguages() {
        val keys = listOf("dl267_speed", "s267_fold", "s267_fold_sub", "s267_ask", "s267_ask_sub", "cool267_title", "cool267_off", "cool267_on",
            "cool267_limit", "cool267_note", "cool267_stop", "cool267_stop_sub", "cool267_wait", "cool267_short")
        listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi").forEach { d ->
            val x = src("src/main/res/$d/strings.xml")
            keys.forEach { k -> assertTrue("$d $k", x.contains("name=\"$k\"")) }
        }
    }
}

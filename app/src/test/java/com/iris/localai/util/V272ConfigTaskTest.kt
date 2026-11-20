package com.iris.localai.util

import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.data.AppSettings
import com.iris.localai.service.ImageGenMonitor
import com.iris.localai.task.TaskProgress
import com.iris.localai.util.io.ConfigTransfer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.iris.localai.util.diagnostics.CrashGate.Verdict as V

/** 2.7.2 A：设置导出 / 导入；C：统一任务进度 TaskProgress。 */
class V272ConfigTaskTest {
    private fun exportOf(s: AppSettings, withSecrets: Boolean, agent: AgentSettings = AgentSettings(workspaceTreeUri = "content://x", customWorkspace = "/sdcard/x")) =
        ConfigTransfer.export(
            "2.7.2", 277,
            mapOf(
                ConfigTransfer.SETTINGS to ConfigTransfer.section(AppSettings.serializer(), s, withSecrets),
                ConfigTransfer.AGENT to ConfigTransfer.section(AgentSettings.serializer(), agent, withSecrets),
            ),
            buildJsonObject { put("m1", buildJsonObject { put("temperature", 0.2) }) },
            listOf(ConfigTransfer.Card("alice", """{"spec":"chara_card_v3","data":{"name":"Alice"}}""")),
            withSecrets, now = 1000L,
        )

    @Test fun exportHasHeaderAndNoSecretsByDefault() {
        val text = exportOf(AppSettings(apiKey = "sekrit", temperature = 0.3f), withSecrets = false)
        val p = ConfigTransfer.parse(text)
        assertEquals(ConfigTransfer.SCHEMA_VERSION, p.schemaVersion)
        assertEquals("2.7.2", p.appVersion); assertEquals(277, p.versionCode); assertEquals(1000L, p.createdAt)
        assertFalse(text.contains("sekrit")); assertFalse(p.section(ConfigTransfer.SETTINGS)!!.containsKey("apiKey"))
        // device-only keys never leave the phone
        assertFalse(p.section(ConfigTransfer.AGENT)!!.containsKey("workspaceTreeUri"))
        assertFalse(p.section(ConfigTransfer.AGENT)!!.containsKey("customWorkspace"))
        assertEquals(listOf("alice"), p.cards.map { it.id })
        assertTrue(p.cards[0].json.contains("Alice"))
        assertTrue(exportOf(AppSettings(apiKey = "sekrit"), withSecrets = true).contains("sekrit"))
        assertTrue(ConfigTransfer.isSecret("hfToken")); assertFalse(ConfigTransfer.isSecret("temperature"))
    }

    @Test fun importMergesKnownFieldsAndMigrates() {
        val text = exportOf(AppSettings(temperature = 0.3f, systemPrompt = "你是助手"), withSecrets = false)
        val p = ConfigTransfer.parse(text)
        val cur = AppSettings(apiKey = "keep-me")
        val m = ConfigTransfer.merge(AppSettings.serializer(), cur, p.section(ConfigTransfer.SETTINGS))
        assertEquals(0.3f, m.value.temperature, 1e-6f)
        assertEquals("你是助手", m.value.systemPrompt)
        assertEquals("keep-me", m.value.apiKey) // no key in the file: the current one stays
        // unknown field and wrong type are skipped one by one, the rest still applies
        val odd = buildJsonObject { put("temperature", "hot"); put("fromTheFuture", 1); put("topK", 7) }
        val m2 = ConfigTransfer.merge(AppSettings.serializer(), cur, odd)
        assertEquals(7, m2.value.topK); assertEquals(cur.temperature, m2.value.temperature, 0f)
        assertEquals(1, m2.applied); assertEquals(2, m2.skipped)
        // a file without schemaVersion (0) is migrated; another format is refused
        val v0 = ConfigTransfer.parse("""{"format":"iridis-config","settings":{"topK":9},"unknownBlock":[1,2]}""")
        assertEquals(0, v0.schemaVersion)
        assertEquals(9, ConfigTransfer.merge(AppSettings.serializer(), cur, v0.section(ConfigTransfer.SETTINGS)).value.topK)
        assertTrue(runCatching { ConfigTransfer.parse("""{"format":"iridis-backup"}""") }.exceptionOrNull() is ConfigTransfer.BadFile)
        assertTrue(runCatching { ConfigTransfer.parse("not json") }.exceptionOrNull() is ConfigTransfer.BadFile)
        // newer schema still reads (unknown fields ignored)
        assertEquals(99, ConfigTransfer.parse("""{"format":"iridis-config","schemaVersion":99}""").schemaVersion)
    }

    @Test fun agentDeviceKeysAreNotOverwritten() {
        val cur = AgentSettings(customWorkspace = "/mine", maxSteps = 5)
        val inc = buildJsonObject { put("customWorkspace", "/theirs"); put("maxSteps", 9) }
        val m = ConfigTransfer.merge(AgentSettings.serializer(), cur, inc)
        assertEquals("/mine", m.value.customWorkspace); assertEquals(9, m.value.maxSteps)
        val e = ConfigTransfer.entries(kotlinx.serialization.json.JsonElement.serializer(), JsonObject(mapOf("a" to JsonPrimitive(1))))
        assertEquals(1, e.size)
    }

    @Test fun taskProgressIsTheOneSource() {
        TaskProgress.resetForTest()
        val seen = ArrayList<TaskProgress.Task>()
        TaskProgress.addSurface { seen += it }
        var stopped = false
        val s = TaskProgress.begin("t1", TaskProgress.Kind.OTHER, "任务", "副标题", total = 10) { stopped = true }
        assertTrue(TaskProgress.running("t1"))
        assertEquals(listOf("t1"), TaskProgress.active.value.map { it.id })
        TaskProgress.progress("t1", 5, 10)
        assertEquals(50, TaskProgress.current("t1")!!.percent)
        TaskProgress.requestStop("t1"); assertTrue(stopped)
        TaskProgress.finish("t1", TaskProgress.Phase.STOPPED, error = "已停止")
        val t = TaskProgress.slot("t1").value!!
        assertTrue(t.finished); assertTrue(t.stopped); assertEquals(s, t.serial)
        assertTrue(TaskProgress.active.value.isEmpty())
        assertEquals(listOf(0, 5, 5), seen.map { it.step })
        assertTrue(seen.last().finished)
        // progress after the end is ignored
        TaskProgress.progress("t1", 9, 10); assertEquals(5, TaskProgress.current("t1")!!.step)
        assertEquals(-1, TaskProgress.Task("x", TaskProgress.Kind.OTHER, "").percent)
        assertNull(TaskProgress.current("never"))
    }

    @Test fun imageGenMonitorReadsTaskProgress() {
        TaskProgress.resetForTest()
        var stop = 0
        val serial = ImageGenMonitor.begin("Anima", 20) { stop++ }
        assertTrue(ImageGenMonitor.running)
        ImageGenMonitor.progress(3, 20)
        val t = TaskProgress.current(ImageGenMonitor.ID)!!
        assertEquals(TaskProgress.Kind.IMAGE_GEN, t.kind); assertEquals("Anima", t.subtitle); assertEquals(3, t.step)
        ImageGenMonitor.requestStop(); assertEquals(1, stop)
        ImageGenMonitor.finish("/x.png", null, stopped = false, seed = 42L, usedPrompt = "p")
        val j = ImageGenMonitor.current!!
        assertTrue(j.finished); assertFalse(j.stopped); assertEquals("/x.png", j.result); assertEquals(42L, j.seed)
        assertEquals("p", j.usedPrompt); assertEquals(serial, j.serial); assertEquals("Anima", j.model)
        assertFalse(ImageGenMonitor.running)
        ImageGenMonitor.begin("B", 4) {}
        ImageGenMonitor.finish(null, "内存不足", stopped = false, lowMem = true)
        assertEquals(TaskProgress.Phase.FAILED, TaskProgress.current(ImageGenMonitor.ID)!!.phase)
        assertTrue(ImageGenMonitor.current!!.lowMem)
    }

    // ---------- E：意外退出弹窗 ----------
    @Test fun crashGateOnlyShowsRealProblems() {
        val G = com.iris.localai.util.diagnostics.CrashGate
        val A = android.app.ApplicationExitInfo::class.java
        val SIG = android.app.ApplicationExitInfo.REASON_SIGNALED
        val LOW = android.app.ApplicationExitInfo.REASON_LOW_MEMORY
        val FG = 100; val VIS = 200; val CACHED = 400
        // the group report: SIGNALED 9, PSS 0 / RSS 0 → logged, not shown (even when it was in front)
        assertEquals(V.LOG, G.decide(SIG, CACHED, 9, 0, 0, true))
        assertEquals(V.LOG, G.decide(SIG, FG, 9, 0, 0, true))
        // killed in the background / cached → log only; while visible with memory in use → shown
        assertEquals(V.LOG, G.decide(LOW, CACHED, 0, 900_000, 1_200_000, true))
        assertEquals(V.SHOW, G.decide(LOW, VIS, 0, 900_000, 1_200_000, true))
        assertEquals(V.SHOW, G.decide(SIG, FG, 9, 2_000_000, 2_500_000, true))
        assertEquals(V.LOG, G.decide(SIG, 0, 9, 2_000_000, 2_500_000, true)) // importance unknown
        // crashes / ANR always shown (when the setting is on)
        for (r in listOf(android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR))
            assertEquals(V.SHOW, G.decide(r, CACHED, 0, 0, 0, true))
        // user swiped / stopped it, normal exit → nothing
        assertEquals(V.IGNORE, G.decide(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, FG, 0, 1, 1, true))
        assertEquals(V.IGNORE, G.decide(android.app.ApplicationExitInfo.REASON_EXIT_SELF, FG, 0, 1, 1, true))
        // 「崩溃后显示报告」 off → never shown, still logged
        assertEquals(V.LOG, G.decide(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, FG, 0, 1, 1, false))
        assertTrue(AppSettings().crashReportPopup)
        assertTrue(A.name.isNotEmpty())
    }
}

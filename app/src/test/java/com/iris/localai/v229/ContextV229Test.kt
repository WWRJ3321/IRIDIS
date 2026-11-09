package com.iris.localai.v229

import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.ContextStrategy
import com.iris.localai.knowledge.KbStat
import com.iris.localai.knowledge.KbWeek
import com.iris.localai.memory.MemorySettings
import com.iris.localai.ui.chat.ContextText
import com.iris.localai.ui.chat.ContextUsage
import com.iris.localai.ui.settings.ContextMemoryText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** 2.2.9 E + F: one 「快满时自动整理」 switch, usage text, the new settings page. */
class ContextV229Test {
    private val sj = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    @Test fun migrationMergesTheTwoSwitches() {
        fun old(a: Boolean, c: Boolean) = sj.decodeFromString(MemorySettings.serializer(),
            """{"enabled":true,"injectAgent":true,"injectChat":true,"autoCompactAgent":$a,"autoCompactChat":$c,"epoch":0,"chatInjectDefaultApplied":true}""").migrated()
        assertTrue(old(true, true).autoCompact)
        assertTrue(old(true, false).autoCompact)
        assertTrue(old(false, true).autoCompact)
        assertFalse(old(false, false).autoCompact)
        assertTrue(old(false, false).autoCompactMerged)
        // New install: on.
        assertTrue(MemorySettings().migrated().autoCompact)
        // Once merged, the user's later choice sticks (legacy fields are not re-read).
        val off = old(true, true).copy(autoCompact = false)
        val back = sj.decodeFromString(MemorySettings.serializer(), sj.encodeToString(MemorySettings.serializer(), off)).migrated()
        assertFalse(back.autoCompact)
        // Idempotent.
        val m = old(true, false); assertEquals(m, m.migrated())
        // The switch keeps the legacy fields in step (downgrade keeps the choice).
        val w = MemorySettings().withAutoCompact(false)
        assertFalse(w.autoCompact); assertFalse(w.autoCompactAgent); assertFalse(w.autoCompactChat); assertTrue(w.autoCompactMerged)
        assertFalse(w.autoCompact(true)); assertFalse(w.autoCompact(false))
    }

    @Test fun autoAllowedIsOneSwitch() {
        for (agent in listOf(true, false)) for (st in ContextStrategy.entries) {
            assertFalse(ContextAssembly.autoAllowed(agent, st, autoCompact = false))
            assertEquals("$agent $st", agent || st == ContextStrategy.TOKEN_BUDGET, ContextAssembly.autoAllowed(agent, st, autoCompact = true))
        }
        assertFalse(ContextAssembly.devTakeover(ContextStrategy.TOKEN_BUDGET))
        for (st in ContextStrategy.entries.filter { it != ContextStrategy.TOKEN_BUDGET }) assertTrue(ContextAssembly.devTakeover(st))
        assertEquals("已由开发者选项接管", ContextAssembly.TAKEOVER)
    }

    @Test fun usageAndTidyTexts() {
        val u = ContextUsage(used = 3400, limit = 8192, visibleMessages = 26)
        assertEquals("这次对话用了 41% · 模型现在能看到最近 26 条", ContextText.usageLine(u))
        assertEquals("约 3.4k / 8k · 模型现在能看到最近 26 条", ContextText.usageDetail(u))
        assertEquals("这次对话还没开始", ContextText.usageTitle(ContextUsage()))
        assertEquals("较早的 5 条已整理成要点 · 原文仍在 · 撤销", ContextText.divider(5))
        assertEquals("已整理较早的 5 条消息，原文没删，需要时能找回", ContextText.compactNotice(5))
        assertEquals(" · 已整理", ContextText.meterSuffix(ContextUsage(compactedMessages = 2)))
        assertEquals("快满时自动整理", ContextText.AUTO)
    }

    @Test fun pageTextsAndStructure() {
        assertEquals("上下文、记忆和知识库", ContextMemoryText.NAV)
        assertEquals("记得你 12 件事", ContextMemoryText.memoryTitle(12))
        assertEquals("最近记住：回答尽量简洁 · 2 小时前", ContextMemoryText.memorySub("回答尽量简洁", ContextMemoryText.ago(0, 2 * 3_600_000L + 5), 0))
        assertEquals("3 个文件", ContextMemoryText.kbTitle(3)); assertEquals("本周回答时引用了 9 次", ContextMemoryText.kbSub(3, 9))
        val page = src("src/main/java/com/iris/localai/ui/settings/ContextMemoryPage.kt")
        val body = page.substringAfter("fun ContextMemoryPage(")
        for (t in listOf("PrivacyBanner()", "ContextText.AUTO", "MemoryListPage(", "KnowledgePage(", "ContextMemoryText.SUGGEST", "ContextMemoryText.KB_TOGGLE", "ContextMemoryText.FOOTNOTE", "contextHintShown"))
            assertTrue(t, page.contains(t))
        // No implementation words on the normal page.
        val visible = listOf(ContextMemoryText.TITLE, ContextMemoryText.PRIVACY, ContextMemoryText.HINT, ContextMemoryText.FOOTNOTE,
            ContextMemoryText.SUGGEST_SUB, ContextMemoryText.KB_SUB, ContextText.AUTO_SUB, ContextAssembly.TAKEOVER).joinToString() + body
        for (w in ContextMemoryText.FORBIDDEN) assertFalse(w, visible.contains(w))
        // Old controls are in 开发者选项 › 上下文与记忆.
        val dev = src("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        val devPage = dev.substringAfter("fun DevContextMemoryPage(").substringBefore("fun PerformancePage(")
        for (t in listOf("R.string.models_3b5663", "ContextStrategy.entries", "R.string.settings_dc8bfe", "R.string.settings_0e766b", "developerMode")) assertTrue(t, devPage.contains(t)) // 2.3.1: strings moved to res
        assertTrue(dev.substringAfter("fun DeveloperPage(").contains("SettingsPage.DEV_CONTEXT"))
        val mem = src("src/main/java/com/iris/localai/ui/agent/MemorySettingsPage.kt")
        for (t in listOf("R.string.agent_de49f7", "R.string.agent_fcd608", "R.string.agent_ec50c7", "R.string.agent_38d78c", "KnowledgeText.REBUILD", "R.string.agent_0a67bd", "MemoryText.CLEAR")) assertTrue(t, mem.contains(t)) // 2.3.1: strings moved to res
        val sheet = src("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertTrue(sheet.contains("showStrategy = settings.developerMode"))
    }

    @Test fun weeklyCites() {
        val z = TimeZone.getTimeZone("Asia/Hong_Kong")
        val mon = KbWeek.start(1_761_955_200_000L, z) // some time 2025-11-01 (Sat)
        val cal = java.util.Calendar.getInstance(z).apply { timeInMillis = mon }
        assertEquals(java.util.Calendar.MONDAY, cal.get(java.util.Calendar.DAY_OF_WEEK)); assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        var s: KbStat? = null
        s = KbWeek.bump(s, mon + 1000, z); s = KbWeek.bump(s, mon + 2000, z)
        assertEquals(2, s.count); assertEquals(2, s.week)
        assertEquals(2, KbWeek.thisWeek(listOf(s), mon + 5000, z))
        val next = mon + 7L * 86_400_000L + 1000
        assertEquals(0, KbWeek.thisWeek(listOf(s), next, z))
        s = KbWeek.bump(s, next, z)
        assertEquals(3, s.count); assertEquals(1, s.week)
        // Old stats (no week fields) decode and count as 0 this week.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(KbStat.serializer(), """{"count":4,"lastAt":1}""")
        assertEquals(0, KbWeek.thisWeek(listOf(old), next, z))
    }
}

package com.iris.localai.knowledge

import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.tools.KbSearchTool
import com.iris.localai.agent.tools.KnowledgeSearch
import com.iris.localai.agent.tools.MemoryTool
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.ContextStrategy
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemorySuggest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 上下文 · 记忆 · 知识库 审查（2.7.0-beta2）：智能体检索计入引用、提议记住不收请求句、记忆关闭时无记忆工具、接管文案。 */
class ContextMemoryReviewTest {
    private fun tmp() = Files.createTempDirectory("cmr").toFile()

    @Test fun agentKbSearchCountsCitations() {
        val cited = mutableListOf<String>()
        val kb = object : KnowledgeSearch {
            override fun search(query: String, limit: Int) = listOf(KbHit("合同.txt", "每月租金 3500 元", 3.0)).take(limit)
            override fun cite(files: Collection<String>) { cited += files }
        }
        val env = ToolEnv(WorkspaceSandbox(tmp()), UndoStore(tmp()), knowledge = kb)
        val r = KbSearchTool.run(buildJsonObject { put("query", "租金") }, env)
        assertTrue(r.ok)
        assertEquals(listOf("合同.txt"), cited)
        // a lambda fake (no cite override) still works
        val env2 = ToolEnv(WorkspaceSandbox(tmp()), UndoStore(tmp()), knowledge = KnowledgeSearch { _, _ -> listOf(KbHit("a.txt", "每月租金", 1.0)) })
        assertTrue(KbSearchTool.run(buildJsonObject { put("query", "租金") }, env2).ok)
    }

    @Test fun weekCountSumsBumps() {
        val now = 1_763_000_000_000L
        val s = KbWeek.bump(KbWeek.bump(null, now), now + 1000)
        assertEquals(2, KbWeek.thisWeek(listOf(s), now + 2000))
        assertEquals(0, KbWeek.thisWeek(listOf(s), now + 8L * 24 * 3600 * 1000))
    }

    @Test fun suggestSkipsRequestSentences() {
        assertTrue(MemorySuggest.detect("我喜欢猫，帮我画一只").isEmpty())
        assertEquals(MemoryItem.Kind.PREF, MemorySuggest.detect("我喜欢简洁的回答").single().kind)
        assertEquals(MemoryItem.Kind.PREF, MemorySuggest.detect("以后都帮我用中文回答").single().kind)
    }

    @Test fun memoryOffDropsTool() {
        val s = AgentSettings()
        assertFalse(AgentTools.enabled(s, true, memoryOn = false).any { it.name == MemoryTool.name })
    }

    @Test fun takeoverTextIsHonest() {
        // only 对话 mode is taken over; the agent still follows 快满时自动整理.
        assertTrue(ContextAssembly.devTakeover(ContextStrategy.KV_SHIFT))
        assertTrue(ContextAssembly.autoAllowed(true, ContextStrategy.KV_SHIFT, true))
        assertTrue(ContextAssembly.TAKEOVER.contains("智能体"))
    }
}

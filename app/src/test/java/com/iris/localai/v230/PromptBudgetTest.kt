package com.iris.localai.v230

import com.iris.localai.agent.AgentPrompt
import com.iris.localai.agent.AgentSettings
import com.iris.localai.agent.AgentTools
import com.iris.localai.agent.Plan
import com.iris.localai.agent.PlanStore
import com.iris.localai.agent.PlanTool
import com.iris.localai.agent.ReadFileTool
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ContextStrategy
import com.iris.localai.engine.PromptMessage
import com.iris.localai.knowledge.KbChat
import com.iris.localai.memory.MemoryPrompt
import com.iris.localai.util.ContextPlanner
import com.iris.localai.util.ContextRequest
import com.iris.localai.util.CtxMessage
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 2.3 预填充提速: prompt budgets (test-side estimator) and the KV-cache prefix layout —
 * stable system prompt first, volatile parts (plan, compaction summary, knowledge snippets)
 * outside it.
 */
class PromptBudgetTest {
    private fun t(s: String) = TokenEstimator.estimate(s)

    // ---------------------------------------------------------------- budgets

    @Test fun agentPromptWithEveryToolIsUnder600() {
        for (style in AgentPrompt.CallStyle.entries) {
            val p = AgentPrompt.build(AgentTools.all + AgentTools.onDemand, "简短", style)
            assertTrue("$style ~${t(p)}", t(p) <= 600)
        }
        val withSkills = AgentPrompt.build(AgentTools.all, "简短", AgentPrompt.CallStyle.QWEN, skills = PromptReportTest.SKILLS)
        assertTrue("skills ~${t(withSkills)}", t(withSkills) <= 680)
    }

    @Test fun plainChatCarriesNoAgentText() {
        val base = AppSettings().systemPrompt
        val sys = ContextAssembly.system(base, "")
        assertTrue("~${t(sys)}", t(sys) <= 150)
        listOf("tool", "Tool", "IRIS", "```").forEach { assertFalse(it, sys.contains(it)) }
        val mem = MemoryPrompt.block(PromptReportTest.MEMORY_SNAPSHOT, MemoryPrompt.budget(4096))
        assertTrue("memory header ~${t(MemoryPrompt.HEADER)}", t(MemoryPrompt.HEADER) <= 25)
        val withMemory = ContextAssembly.system(base, mem)
        assertTrue("~${t(withMemory)}", t(withMemory) <= 150)
    }

    @Test fun onlyEnabledToolsAreListed() {
        val s = AgentSettings(enabled = AgentTools.all.associate { it.name to (it.name == ReadFileTool.name) })
        val tools = AgentTools.enabled(s, historyAvailable = true, memoryOn = true)
        assertEquals(listOf(ReadFileTool), tools)
        val p = AgentPrompt.build(tools)
        assertTrue(p.contains("read_file("))
        AgentTools.all.filter { it != ReadFileTool }.forEach { assertFalse(it.name, p.contains(it.name + "(")) }
        assertTrue("~${t(p)}", t(p) <= 250)
    }

    // ---------------------------------------------------------------- stable prefix

    @Test fun systemPromptIsDeterministicAndLayeredStableFirst() {
        // fixed tool order whatever order the caller passes; same input → same bytes
        for (seed in 1..3) assertEquals(AgentPrompt.build(AgentTools.all), AgentPrompt.build(AgentTools.all.shuffled(java.util.Random(seed.toLong()))))
        // tools → user instructions → memory: each later layer only appends
        val tools = AgentPrompt.build(AgentTools.all)
        val instr = AgentPrompt.build(AgentTools.all, "简短")
        val mem = AgentPrompt.build(AgentTools.all, "简短", memory = "【长期记忆】x")
        assertTrue(instr.startsWith(tools)); assertTrue(mem.startsWith(instr)); assertTrue(mem.endsWith("【长期记忆】x"))
        // nothing that changes by itself: no clock, no plan
        assertFalse(Regex("20\\d\\d-\\d\\d-\\d\\d|\\d\\d:\\d\\d:\\d\\d").containsMatchIn(mem))
        assertFalse(mem.contains("# Current plan"))
    }

    @Test fun planRidesOnTheNewestUserTurn() {
        val tmp = Files.createTempDirectory("plan").toFile()
        val store = PlanStore()
        val env = ToolEnv(WorkspaceSandbox(tmp), UndoStore(File(tmp, ".u")), plans = store, conversationId = "c1")
        PlanTool.run(JsonObject(mapOf("action" to JsonPrimitive("set"), "steps" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))), env)
        val plan: Plan = store.get("c1")!!
        val msgs = listOf(
            PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, "早"),
            PromptMessage(PromptMessage.ASSISTANT, "好"), PromptMessage(PromptMessage.USER, "继续"),
        )
        val out = AgentPrompt.withPlanNote(msgs, plan)
        assertEquals(msgs.take(3), out.take(3))
        assertTrue(out[3].content.startsWith("继续\n\n# Current plan"))
        assertEquals(msgs, AgentPrompt.withPlanNote(msgs, null))
        assertNull(AgentPrompt.planNote(Plan()))
        tmp.deleteRecursively()
    }

    @Test fun summaryLeadsTheHistoryAndTheSystemPromptStaysTheSame() {
        val msgs = (0 until 6).flatMap { i -> listOf(CtxMessage("u$i", PromptMessage.USER, "问题$i"), CtxMessage("a$i", PromptMessage.ASSISTANT, "回答$i")) } +
            CtxMessage("u6", PromptMessage.USER, "新问题")
        fun req(summary: String, upTo: String?, lead: String = "") = ContextRequest(
            system = "SYS", messages = msgs, strategy = ContextStrategy.SUMMARY, contextTokens = 100_000, maxNewTokens = 512,
            summary = summary, summaryUpToId = upTo, lead = lead,
        )
        val plain = ContextPlanner.plan(req("", null))
        val folded = ContextPlanner.plan(req("早先聊了天气", "a2"))
        assertEquals(plain.messages.first(), folded.messages.first())
        assertEquals(PromptMessage.USER, folded.messages[1].role)
        assertTrue(folded.messages[1].content.startsWith(ContextPlanner.SUMMARY_HEADER))
        assertTrue(folded.messages[1].content.endsWith("问题3"))
        // a compaction lead in front of a history that starts with a reply becomes its own user turn
        val lead = ContextPlanner.withLead(listOf(PromptMessage(PromptMessage.ASSISTANT, "x")), "【已压缩的早期对话】")
        assertEquals(listOf(PromptMessage.USER, PromptMessage.ASSISTANT), lead.map { it.role })
        // the lead is counted in the estimate
        val withLead = ContextPlanner.plan(req("", null, lead = "摘要".repeat(50)))
        assertTrue(withLead.estimatedTokens >= plain.estimatedTokens + 100)
        assertEquals(plain.messages.first(), withLead.messages.first())
    }

    @Test fun knowledgeBlocksStayOnTheirMessages() {
        val blocks = LinkedHashMap<String, String>()
        KbChat.remember(blocks, "u1", "<knowledge>A</knowledge>")
        KbChat.remember(blocks, "u2", "<knowledge>B</knowledge>")
        val msgs = listOf(CtxMessage("u1", "user", "q1"), CtxMessage("a1", "assistant", "r1"), CtxMessage("u2", "user", "q2"))
        val out = KbChat.attachAll(msgs, blocks)
        assertEquals("<knowledge>A</knowledge>\n\nq1", out[0].content)
        assertEquals("r1", out[1].content)
        assertEquals("<knowledge>B</knowledge>\n\nq2", out[2].content)
        // the earlier turn is unchanged next time (same bytes → KV reuse)
        assertEquals(out[0], KbChat.attachAll(msgs.take(1), blocks)[0])
        repeat(KbChat.MAX_KEPT + 5) { KbChat.remember(blocks, "m$it", "b") }
        assertEquals(KbChat.MAX_KEPT, blocks.size)
        assertFalse(blocks.containsKey("u1"))
    }

    @Test fun chatViewModelUsesTheStableLayout() {
        // 2.4.1: ChatViewModel is split into ChatViewModel*.kt (same package).
        val vm = listOf("src/main/java/com/iris/localai/ui/chat", "app/src/main/java/com/iris/localai/ui/chat")
            .map { File(it) }.first { it.isDirectory }.listFiles { f -> f.name.startsWith("ChatViewModel") && f.name.endsWith(".kt") }!!
            .sortedBy { it.name }.joinToString("\n") { it.readText() }
        assertTrue(vm.contains("KbChat.attachAll(m, kbBlocks)"))
        assertTrue(vm.contains("AgentPrompt.withPlanNote(history, c.agent.plans.get(convId))"))
        assertTrue(vm.contains("lead = com.iris.localai.context.ContextAssembly.lead(compaction, recall = "))
        assertFalse(vm.contains("plan = c.agent.plans.get(convId), planFirst"))
        // 新对话 keeps llama.cpp's cached system prompt; the other engines still reset
        assertTrue(vm.contains("c.engine.resetForNewChat()"))
        val llama = listOf("src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt", "app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt")
            .map { File(it) }.first { it.exists() }.readText()
        assertTrue(llama.contains("override val reusesCommonPrefix: Boolean get() = true"))
    }
}

package com.iris.localai.agent

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.web.*
import com.iris.localai.agent.workspace.*
import com.iris.localai.agent.*
import com.iris.localai.ui.settings.RackInput
import com.iris.localai.ui.settings.RackRules
import com.iris.localai.ui.settings.RackState
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V1140Test {
    private fun env(store: PlanStore, tools: List<AgentTool> = AgentTools.all): ToolEnv {
        val dir = File(System.getProperty("java.io.tmpdir"), "v1140-" + System.nanoTime()).apply { mkdirs() }
        val sb = WorkspaceSandbox(dir)
        return ToolEnv(sb, UndoStore(File(dir, ".undo")), conversationId = "c1", plans = store, enabledTools = tools)
    }

    @Test fun planSetDoneAndPromptBlock() {
        val store = PlanStore()
        val e = env(store)
        val steps = JsonArray(listOf("搜索新闻", "整理要点", "写总结").map { JsonPrimitive(it) })
        val r = PlanTool.run(JsonObject(mapOf("action" to JsonPrimitive("set"), "steps" to steps, "title" to JsonPrimitive("新闻"))), e)
        assertTrue(r.output, r.output.contains("1. [~] 搜索新闻"))
        assertEquals("0/3 完成", r.summary)
        val d = PlanTool.run(buildJsonObject { put("action", "done"); put("step", 1) }, e)
        assertTrue(d.output, d.output.contains("1. [x] 搜索新闻") && d.output.contains("2. [~] 整理要点"))
        assertEquals(1, store.get("c1")!!.done)
        // 2.3: the plan rides on the newest user turn (re-added every run, so it survives compaction),
        // no longer in the system prompt (a plan update used to force a full re-prefill)
        val p = AgentPrompt.planNote(store.get("c1"))!!
        assertTrue(p.contains("# Current plan — 新闻") && p.contains("Continue with step 2"))
        assertFalse(AgentPrompt.build(listOf(PlanTool)).contains("# Current plan"))
        PlanTool.run(buildJsonObject { put("action", "done") }, e)
        PlanTool.run(buildJsonObject { put("action", "done") }, e)
        assertTrue(store.get("c1")!!.finished)
        assertNull(AgentPrompt.planNote(store.get("c1")))
    }

    @Test fun todoIsAnAliasOfPlan() {
        assertEquals("plan", ToolCallParser.canonicalizeName("todo", AgentTools.all.map { it.name }.toSet()))
        assertNull(AgentTools.byName("todo"))
    }

    @Test fun lazyGroupsAreListedByNameOnly() {
        val full = AgentPrompt.build(AgentTools.all)
        val lazy = AgentPrompt.build(AgentTools.all, lazy = true)
        assertTrue(full.contains("run_command("))
        assertFalse(lazy.contains("run_command("))
        assertTrue(lazy, lazy.contains("## More (call activate(group) first)") && lazy.contains("terminal: run_command"))
        assertFalse("activate is not listed without lazy groups", full.contains("activate("))
        assertTrue(TokenEstimator.estimate(lazy) < TokenEstimator.estimate(full))
        assertTrue("≈ ${TokenEstimator.estimate(lazy)}", TokenEstimator.estimate(lazy) <= 700)
    }

    @Test fun activateReturnsTheGroupsUsage() {
        val e = env(PlanStore())
        val r = ActivateTool.run(buildJsonObject { put("group", "terminal") }, e)
        assertTrue(r.ok); assertTrue(r.output, r.output.contains("run_command("))
        val bad = ActivateTool.run(buildJsonObject { put("group", "nope") }, e)
        assertFalse(bad.ok)
        // a tool name works too
        assertTrue(ActivateTool.run(buildJsonObject { put("group", "schedule") }, e).output.contains("schedule("))
    }

    @Test fun planFirstRule() {
        assertTrue(AgentPrompt.build(listOf(PlanTool), planFirst = true).contains("# Plan mode"))
        assertFalse(AgentPrompt.build(listOf(ReadFileTool), planFirst = true).contains("# Plan mode"))
    }

    @Test fun heartbeatRules() {
        assertEquals(5, HeartbeatRules.delayMinutes(0, 5, 60))
        assertEquals(60, HeartbeatRules.delayMinutes(3, 5, 60))
        assertEquals(HeartbeatRules.Verdict.Nothing, HeartbeatRules.check(null))
        val p = Plan(steps = listOf(PlanStep("a", PlanStatus.DONE), PlanStep("b", PlanStatus.DOING), PlanStep("c")))
        val v = HeartbeatRules.check(p) as HeartbeatRules.Verdict.Pending
        assertEquals(2, v.left); assertEquals("b", v.next)
        assertEquals(HeartbeatRules.Verdict.Nothing, HeartbeatRules.check(p.copy(steps = p.steps.map { it.copy(status = PlanStatus.DONE) })))
    }

    @Test fun powerGate() {
        assertTrue(PowerGate.allows(50, false, chargingOnly = false))
        assertFalse(PowerGate.allows(10, false, chargingOnly = false))
        assertTrue(PowerGate.allows(10, true, chargingOnly = false))
        assertFalse(PowerGate.allows(90, false, chargingOnly = true))
    }

    @Test fun scheduleAutoAndLimits() {
        val s = Schedule(title = "新闻", prompt = "整理新闻", nextAt = 0, autoRun = true, chargingOnly = true, maxMinutes = 5)
        assertEquals("自动执行 · 仅充电时 · 最长 5 分钟", ScheduleRules.limits(s))
        assertEquals("到点通知，点按执行", ScheduleRules.limits(s.copy(autoRun = false)))
    }

    @Test fun planStorePersists() {
        var saved: String? = null
        val a = PlanStore({ saved }, { saved = it })
        a.put("x", Plan(steps = listOf(PlanStep("one"))))
        val b = PlanStore({ saved }, { saved = it })
        assertEquals("one", b.get("x")!!.steps.single().text)
        b.clear("x"); assertNull(PlanStore({ saved }).get("x"))
    }

    @Test fun rackRules() {
        val m = RackRules.modules(RackInput(modelName = "Gemma", modelState = RackState.RUN, agentMode = true, agentRunning = true, tool = "联网搜索", toolGroup = "WEB", webOn = true))
        assertEquals(8, m.size)
        assertEquals(RackState.RUN, m[1].state)
        assertEquals(RackState.RUN, m[2].state)
        assertEquals(2, RackRules.autoSelect(m))
        val idle = RackRules.modules(RackInput())
        assertEquals(-1, RackRules.autoSelect(idle))
        assertEquals(RackState.OFF, idle[1].state)
    }

    @Test fun skillCalledAsToolBecomesSkill() {
        // covered in AgentLoop; the parser keeps the name so the loop can map it
        val r = ToolCallParser.parse("<|tool_call>call:daily-news{}<tool_call|>") as ParsedReply.Call
        assertEquals("daily-news", r.call.name)
    }
}

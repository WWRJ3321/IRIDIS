package com.iris.localai.agent

import com.iris.localai.agent.core.AgentCallbacks
import com.iris.localai.agent.core.AgentLoop
import com.iris.localai.agent.core.AgentModel
import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.AgentStep
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.core.ModelTurn
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.tools.Plan
import com.iris.localai.agent.tools.PlanStatus
import com.iris.localai.agent.tools.PlanStep
import com.iris.localai.agent.tools.PlanStore
import com.iris.localai.agent.tools.PlanTool
import com.iris.localai.agent.core.StepStatus
import com.iris.localai.agent.core.ToolCall
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.engine.PromptMessage
import com.iris.localai.ui.agent.StepText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2.1.0: the plan step card is built from structured plan state; the reply hides the call. */
class PlanStepCardTest {
    private val store = PlanStore()
    private val env = File(System.getProperty("java.io.tmpdir"), "v210plan-" + System.nanoTime()).apply { mkdirs() }.let { dir ->
        ToolEnv(WorkspaceSandbox(dir), UndoStore(File(dir, ".undo")), conversationId = "c1", plans = store, enabledTools = AgentTools.all)
    }

    private class Model(vararg r: String) : AgentModel {
        val q = ArrayDeque(r.toList())
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            val t = q.removeFirstOrNull() ?: "完成。"; onText(t); return ModelTurn(t)
        }
    }
    private class Rec : AgentCallbacks {
        var steps: List<AgentStep> = emptyList()
        val texts = ArrayList<String>()
        override suspend fun confirm(tool: AgentTool, call: ToolCall) = UserDecision.ALLOW_ONCE
        override fun onSteps(steps: List<AgentStep>) { this.steps = steps }
        override fun onText(text: String) { texts += text }
    }

    @Test fun planCallInJsonFenceBecomesChecklistCard() = runTest {
        val call = "我来拆一下任务。\n```json\n{\"tool\":\"plan\",\"args\":{\"action\":\"set\",\"steps\":[\"搜索\",\"阅读\",\"总结\",\"写入\",\"检查\"],\"title\":\"写报告\"}}\n```"
        val rec = Rec()
        val loop = AgentLoop(Model(call, "计划已列好。"), listOf(PlanTool), { Permission.ALLOW }, env, rec, 8, Int.MAX_VALUE)
        val o = loop.run(listOf(PromptMessage(PromptMessage.SYSTEM, "s"), PromptMessage(PromptMessage.USER, "写报告")))
        val st = rec.steps.single()
        assertEquals(StepStatus.OK, st.status)
        assertEquals("我来拆一下任务。", st.lead)
        assertFalse(rec.texts.any { "\"tool\"" in it })
        val p = st.plan!!
        assertEquals("写报告", p.title)
        assertEquals(5, p.steps.size)
        assertEquals(PlanStatus.DOING, p.steps[0].status)
        assertEquals("计划 · 写报告 · 0/5", StepText.header(st))
        assertTrue(StepText.isPlan(st))
        // model-facing text is unchanged and still in detail (for the model), never in the header
        assertTrue(st.detail.startsWith("# Current plan"))
        assertFalse("action=" in StepText.header(st))
        assertEquals("计划已列好。", o.finalText)
    }

    @Test fun planHeaderProgress() {
        val p = Plan("", listOf(PlanStep("a", PlanStatus.DONE), PlanStep("b", PlanStatus.DOING), PlanStep("c")))
        assertEquals("计划 · 1/3", StepText.header(AgentStep(tool = "plan", plan = p)))
        // legacy step without plan state: no key=value, no model text
        assertEquals("计划", StepText.header(AgentStep(tool = "plan", args = "action=set, steps=（209 字）")))
        assertEquals("搜索「x」", StepText.header(AgentStep(tool = "web_search", args = "query=x", title = "搜索「x」")))
    }

    @Test fun planToolResultCarriesStateAndPromptUnchanged() {
        val r = PlanTool.run(buildJsonObject { put("action", "set"); put("steps", "a\nb") }, env)
        assertEquals(2, r.plan!!.steps.size)
        assertTrue(r.output.contains("Continue with step 1. Mark it done with plan(action: done, step: 1)."))
        assertTrue(AgentPrompt.planNote(store.get("c1"))!!.contains("Continue with step 1"))
    }
}

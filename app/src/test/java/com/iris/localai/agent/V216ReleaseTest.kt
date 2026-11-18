package com.iris.localai.agent

import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.tools.Plan
import com.iris.localai.agent.tools.PlanStep
import com.iris.localai.data.TurnRef
import com.iris.localai.data.TurnRefKind
import com.iris.localai.data.TurnRefs
import com.iris.localai.engine.PromptMessage
import com.iris.localai.util.VisionPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.6: rich rendering + composer redesign + prompt restructure, merged. */
class V216ReleaseTest {

    private val plan = Plan("写周报", listOf(PlanStep("查资料"), PlanStep("写总结")))

    @Test fun skillLineAndPlanNoteShareTheNewestUserTurn() {
        val refs = listOf(TurnRef(TurnRefKind.SKILL, "周报"))
        val user = TurnRefs.userText("帮我写周报", "", refs)
        val msgs = listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, "早"), PromptMessage(PromptMessage.ASSISTANT, "早"), PromptMessage(PromptMessage.USER, user))
        val out = AgentPrompt.withPlanNote(msgs, plan)
        assertEquals("sys", out[0].content)
        assertEquals("早", out[1].content)
        val last = out[3].content
        assertTrue(last.startsWith("帮我写周报"))
        val ref = last.indexOf("[For this request: use skill \"周报\"")
        val note = last.indexOf(AgentPrompt.planNote(plan)!!)
        assertTrue(ref > 0 && note > ref)
    }

    @Test fun picturesStillFoundWhenPlanNoteFollowsTheUserWords() {
        val text = TurnRefs.userText("这是什么？", "\n\n[图片：IMG_1.jpg]", listOf(TurnRef(TurnRefKind.TOOL, "web_fetch")))
        val msgs = AgentPrompt.withPlanNote(listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, text)), plan)
        assertEquals(1, VisionPrompt.targetIndex(msgs, text))
    }
}

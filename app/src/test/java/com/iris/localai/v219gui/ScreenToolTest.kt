package com.iris.localai.v219gui

import com.iris.localai.agent.AgentPrompt
import com.iris.localai.agent.AgentSettings
import com.iris.localai.agent.AgentTools
import com.iris.localai.agent.GuiBlocker
import com.iris.localai.agent.GuiIntent
import com.iris.localai.agent.GuiTaskBackend
import com.iris.localai.agent.GuiTaskResult
import com.iris.localai.agent.GuiTaskStatus
import com.iris.localai.agent.Permission
import com.iris.localai.agent.ScreenTool
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.ToolGroup
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.agent.gui.GuiAction
import com.iris.localai.agent.gui.GuiOutcome
import com.iris.localai.agent.gui.GuiStep
import com.iris.localai.agent.gui.GuiStepStatus
import com.iris.localai.agent.gui.GuiStop
import com.iris.localai.agent.gui.GuiTaskMapping
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.1.9: 在聊天里直接说就操控手机 — the `screen` tool, its preconditions and the 对话-mode trigger. */
class ScreenToolTest {
    private fun tmp(): File = Files.createTempDirectory("gui").toFile()

    private class Fake(var block: GuiBlocker? = null, val result: GuiTaskResult = GuiTaskResult(GuiTaskStatus.DONE, "亮度已调到最高", 4)) : GuiTaskBackend {
        var ran: String? = null
        override fun blocker() = block
        override suspend fun run(task: String, progress: (String) -> Unit): GuiTaskResult { ran = task; progress("1. 打开 设置"); return result }
    }

    private fun env(gui: GuiTaskBackend?) = ToolEnv(WorkspaceSandbox(tmp()), UndoStore(tmp()), gui = gui)
    private val args = buildJsonObject { put("task", "打开设置把亮度调最高") }

    @Test fun registeredEnabledAllowedAndAlwaysListed() {
        assertTrue(AgentTools.all.contains(ScreenTool))
        assertEquals(ScreenTool, AgentTools.byName("screen"))
        val s = AgentSettings()
        assertTrue(s.isEnabled(ScreenTool))
        // the GUI loop asks before 支付/发送/删除 itself: the call is not asked again
        assertEquals(Permission.ALLOW, s.permission(ScreenTool))
        // not in a 按需载入 group: listed even with lazy tools on (the default)
        assertEquals(ToolGroup.OTHER, ScreenTool.group)
        val p = AgentPrompt.build(AgentTools.enabled(s, true, true), style = AgentPrompt.CallStyle.QWEN, lazy = s.lazyTools)
        assertTrue(p.contains("screen(task)"))
        // the 'phone' intents tool keeps its name
        assertEquals("phone", AgentTools.byName("phone")?.name)
    }

    @Test fun descriptionIsTinyAndPromptStaysInBudget() {
        assertTrue(ScreenTool.promptLine.length <= 60)
        for (style in AgentPrompt.CallStyle.entries) {
            val all = AgentPrompt.build(AgentTools.all + AgentTools.onDemand, "简短", style)
            assertTrue("~${TokenEstimator.estimate(all)}", TokenEstimator.estimate(all) <= 800)
        }
    }

    @Test fun runsTheGuiLoopAndReportsTheResult() = runBlocking {
        val f = Fake()
        val lines = ArrayList<String>()
        val r = ScreenTool.runSuspend(args, env(f)) { lines += it }
        assertEquals("打开设置把亮度调最高", f.ran)
        assertTrue(r.ok); assertTrue(r.output.startsWith("Done on screen")); assertTrue(r.summary.startsWith("完成"))
        assertEquals(listOf("1. 打开 设置"), lines)
        for ((st, head) in listOf(GuiTaskStatus.FAILED to "Not finished", GuiTaskStatus.TAKEN_OVER to "Handed to the user", GuiTaskStatus.STOPPED to "Stopped by the user")) {
            val o = ScreenTool.runSuspend(args, env(Fake(result = GuiTaskResult(st, "x")))) {}
            assertFalse(o.ok); assertTrue(o.output.startsWith(head)); assertTrue(o.summary.startsWith(st.label))
        }
    }

    @Test fun preconditionsGiveChineseGuidesAndDoNotRun() = runBlocking {
        val off = Fake(GuiBlocker.A11Y_OFF)
        val a = ScreenTool.runSuspend(args, env(off)) {}
        assertFalse(a.ok); assertTrue(a.output.contains("无障碍")); assertEquals(null, off.ran)
        val v = ScreenTool.runSuspend(args, env(Fake(GuiBlocker.NO_VISION))) {}
        assertFalse(v.ok); assertTrue(v.output.contains("识图模型")); assertTrue(v.output.contains("Qwen3-VL"))
        assertTrue(ScreenTool.runSuspend(args, env(Fake(GuiBlocker.NO_MODEL))) {}.output.contains("识图"))
        assertTrue(ScreenTool.runSuspend(args, env(Fake(GuiBlocker.BUSY))) {}.output.contains("停止"))
        val none = ScreenTool.runSuspend(args, env(null)) {}
        assertFalse(none.ok); assertEquals("不可用", none.summary)
        val missing = runCatching { ScreenTool.runSuspend(buildJsonObject { }, env(Fake())) {} }
        assertTrue(missing.isFailure)
    }

    @Test fun outcomeMapping() {
        fun o(stop: GuiStop, steps: List<GuiStep> = emptyList()) = GuiOutcome(stop, "m", steps)
        assertEquals(GuiTaskStatus.DONE, GuiTaskMapping.result(o(GuiStop.DONE), false).status)
        for (s in listOf(GuiStop.GAVE_UP, GuiStop.MAX_STEPS, GuiStop.ERROR)) assertEquals(GuiTaskStatus.FAILED, GuiTaskMapping.result(o(s), false).status)
        assertEquals(GuiTaskStatus.STOPPED, GuiTaskMapping.result(o(GuiStop.STOPPED), false).status)
        assertEquals(GuiTaskStatus.TAKEN_OVER, GuiTaskMapping.result(o(GuiStop.STOPPED), true).status)
        val asked = GuiTaskMapping.result(o(GuiStop.STOPPED, listOf(GuiStep(1, "", GuiAction.Ask("发给哪个张三？"), GuiStepStatus.OK))), true)
        assertEquals("模型提问：发给哪个张三？", asked.message)
        assertEquals(1, asked.steps)
    }

    @Test fun chatModeTriggerIsNarrow() {
        for (t in listOf("帮我打开设置把亮度调最高", "在手机上给妈妈发微信说我晚点到", "用微信给张三发消息说明天见", "给老王发条微信", "把亮度调到最高", "打开微信搜一下张三"))
            assertTrue(t, GuiIntent.looksLikePhoneTask(t))
        for (t in listOf("亮度对眼睛有什么影响", "设置一个好的作息怎么做", "微信是哪家公司做的", "明天天气怎么样", "写一首诗", "怎么打开设置"))
            assertFalse(t, GuiIntent.looksLikePhoneTask(t))
    }
}

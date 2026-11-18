package com.iris.localai.agent

import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.tools.SkillInfo
import com.iris.localai.memory.MemoryPrompt
import com.iris.localai.util.TokenEstimator
import org.junit.Test
import java.io.File

/**
 * 2.3 prompt audit: token counts (test-side estimator) per prompt section and mode, written to
 * build/prompt-report.txt. Report only; the budgets are locked in [PromptBudgetTest].
 */
class PromptReportTest {
    companion object {
        val SKILLS = listOf(
            SkillInfo("alpine-dev", "Install and use Python, Node, git or other Linux tools in the built-in Alpine terminal.", "builtin", { "" }),
            SkillInfo("daily-news", "Summarize today's news on a topic (or general headlines) in Chinese.", "builtin", { "" }),
            SkillInfo("note-taking", "Keep the user's notes and to-do lists as Markdown files in the workspace.", "builtin", { "" }),
        )
        /** A typical small memory: a few facts about the user and one project note. */
        val MEMORY_SNAPSHOT = MemoryPrompt.snapshot(
            "- 用户叫小王，在香港工作，使用 Redmi K90\n- 喜欢简短的中文回答\n- 对花生过敏",
            "- 项目 IRIDIS：安卓端本地大模型应用\n- 每周五提醒备份手机照片",
        )
    }

    @Test fun report() {
        fun t(s: String) = TokenEstimator.estimate(s)
        val all = AgentTools.all
        val defaults = all.filter { AgentSettings().isEnabled(it) }
        val mem = MemoryPrompt.block(MEMORY_SNAPSHOT, MemoryPrompt.budget(4096))
        val out = buildString {
            appendLine("plain chat system (default persona) = ${t("")}")
            appendLine("memory block = ${t(mem)}")
            appendLine("agent all tools FENCE = ${t(AgentPrompt.build(all))}")
            appendLine("agent all tools QWEN = ${t(AgentPrompt.build(all, style = AgentPrompt.CallStyle.QWEN))}")
            appendLine("agent default tools FENCE = ${t(AgentPrompt.build(defaults))}")
            appendLine("agent all tools + skills = ${t(AgentPrompt.build(all, skills = SKILLS))}")
            appendLine("agent all tools lazy = ${t(AgentPrompt.build(all, lazy = true))}")
            appendLine("agent no tools = ${t(AgentPrompt.build(emptyList()))}")
            val tools = all.joinToString("\n") { it.promptLine }
            appendLine("tool lines only (all) = ${t(tools)}")
            all.forEach { appendLine("  tool ${it.name} = ${t(it.promptLine)}") }
            appendLine("---- agent all FENCE text ----")
            appendLine(AgentPrompt.build(all, skills = SKILLS))
        }
        File("build").mkdirs()
        File("build/prompt-report.txt").writeText(out)
        println(out)
    }
}

package com.iris.localai.agent

import com.iris.localai.agent.core.AgentCallbacks
import com.iris.localai.agent.core.AgentLoop
import com.iris.localai.agent.core.AgentModel
import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.AgentStep
import com.iris.localai.agent.core.AgentStop
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.web.BrowserSearch
import com.iris.localai.agent.tools.FetchResult
import com.iris.localai.agent.core.ModelTurn
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.web.SearchHit
import com.iris.localai.agent.web.SearchText
import com.iris.localai.agent.core.StepStatus
import com.iris.localai.agent.core.ToolCall
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.web.WebSearchTool
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.engine.PromptMessage
import com.iris.localai.util.thinking.ThinkParser
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 2.1.7 「叫他找新闻，模型搜了但不告诉我」: after web_search the reply was empty (thinking only, a
 * stream cut at a "<tool_response>" mention inside thinking, a call buried in unfinished thinking,
 * the step limit) or generic. Covers the forced follow-up, the results fallback, the thinking-aware
 * stream cut, the restored prompt rule, and dates/snippets in the search output.
 */
class AgentAnswerTest {
    private lateinit var tmp: File
    private lateinit var env: ToolEnv

    private val rss = """<?xml version="1.0"?><rss><channel>
        <item><title>港股收涨 恒指升 1.2%</title><link>https://news.example.com/a</link>
        <description>恒生指数周二收涨，科技股领涨。</description><News:Source>示例财经</News:Source><pubDate>Tue, 20 Oct 2026 08:00:00 GMT</pubDate></item>
        <item><title>台风预警解除</title><link>https://news.example.com/b</link>
        <description>天文台上午取消八号风球。</description><pubDate>Tue, 20 Oct 2026 02:00:00 GMT</pubDate></item>
        </channel></rss>"""

    @Before fun setUp() {
        tmp = Files.createTempDirectory("iris217").toFile()
        env = ToolEnv(WorkspaceSandbox(File(tmp, "ws")), UndoStore(File(tmp, "undo")),
            fetcher = { url -> FetchResult(200, "application/rss+xml", rss, url) })
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private class FakeModel(vararg replies: String) : AgentModel {
        val queue = ArrayDeque(replies.toList())
        val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            seen += messages
            val r = queue.removeFirstOrNull() ?: "完成。"
            onText(r)
            return ModelTurn(r)
        }
    }

    private class Cb : AgentCallbacks {
        var steps: List<AgentStep> = emptyList()
        override suspend fun confirm(tool: AgentTool, call: ToolCall) = UserDecision.ALLOW_ONCE
        override fun onSteps(steps: List<AgentStep>) { this.steps = steps }
    }

    private fun call(q: String) = "<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"$q\"}}\n</tool_call>"
    /** A thinking-prefilled model's turn always starts inside <think> (the adapter adds the prefix). */
    private fun search(q: String) = "<think>搜一下</think>\n" + call(q)
    private val search = search("今日新闻")
    private val base = listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, "帮我找一下今天的新闻"))

    /** Thinking-prefilled model: the stream starts inside <think> (like the app's adapter). */
    private fun loop(m: AgentModel, maxSteps: Int = 8, budget: Int = Int.MAX_VALUE, thinking: Boolean = true) =
        AgentLoop(m, listOf(WebSearchTool), { Permission.ALLOW }, env, Cb(), maxSteps, budget,
            answerOf = { ThinkParser.parse(it, expectThinking = thinking).answer })

    // ---------------------------------------------------------------- empty answer → follow-up

    @Test fun thinkingOnlyAnswerAfterSearchGetsOneForcedFollowUp() = runTest {
        val m = FakeModel(search, "<think>结果里有港股和台风……（达到长度上限）", "今天的新闻：港股收涨，恒指升 1.2%；台风预警已解除。")
        val o = loop(m).run(base)
        assertEquals(AgentStop.DONE, o.stop)
        assertTrue(o.finalText, o.finalText.contains("港股收涨"))
        assertEquals(3, m.seen.size)
        val last = m.seen[2].last()
        assertEquals(PromptMessage.USER, last.role)
        assertTrue("nudge rides on the tool response", last.content.startsWith("<tool_response>") && last.content.endsWith(AgentPrompt.ANSWER_NOW))
        assertTrue("results still in view", last.content.contains("港股收涨 恒指升 1.2%"))
        // roles still alternate (no two user turns in a row)
        m.seen[2].zipWithNext().forEach { (a, b) -> assertFalse("${a.role}->${b.role}", a.role == PromptMessage.USER && b.role == PromptMessage.USER) }
    }

    @Test fun stillEmptyShowsTheSearchResultsInstead() = runTest {
        val m = FakeModel(search, "<think>想了很多", "<think>又想了很多")
        val o = loop(m).run(base)
        assertEquals(AgentStop.DONE, o.stop)
        val parts = ThinkParser.parse(o.finalText)
        assertEquals("又想了很多", parts.thinking)
        assertTrue(parts.answer, parts.answer.startsWith(AgentLoop.FALLBACK_HEAD))
        assertTrue(parts.answer.contains("港股收涨 恒指升 1.2%"))
        assertTrue("snippet", parts.answer.contains("恒生指数周二收涨"))
        assertTrue("date", parts.answer.contains("20 Oct 2026"))
        assertFalse(parts.answer.contains("Search results for"))
        assertTrue(o.transcript.last().content.startsWith(AgentLoop.FALLBACK_HEAD))
    }

    @Test fun plainAnswerIsNotNudged() = runTest {
        val m = FakeModel(search, "<think>好</think>港股收涨。")
        val o = loop(m).run(base)
        assertEquals(2, m.seen.size)
        assertEquals("港股收涨。", ThinkParser.parse(o.finalText).answer)
    }

    @Test fun emptyChatAnswerWithoutToolsIsNudgedButNotFilled() = runTest {
        val m = FakeModel("<think>嗯", "<think>嗯嗯")
        val o = loop(m).run(base)
        assertEquals(2, m.seen.size)
        assertFalse(o.finalText.contains(AgentLoop.FALLBACK_HEAD))
    }

    // ---------------------------------------------------------------- stream cut inside thinking

    @Test fun mentioningToolResponseWhileThinkingDoesNotEndTheTurn() {
        assertNull(ToolCallParser.callEnd("<think>看一下 <tool_response> 里的新闻，第一条是"))
        assertNull(ToolCallParser.callEnd("<think>先想</think>\n<think>看 <tool_response> 的内容"))
        // after the thinking closed it is still a hallucinated result → cut
        val s = "<think>好</think>答案<tool_response>假的"
        assertEquals(s.indexOf("<tool_response>"), ToolCallParser.callEnd(s))
        assertEquals("plain".length, ToolCallParser.callEnd("plain<tool_response>x"))
    }

    @Test fun callInsideUnfinishedThinkingIsRun() = runTest {
        // the adapter stops the stream right after the call, so the think block never closes
        val thinkCall = "<think>用户要新闻，我搜一下。\n" + call("今日新闻")
        assertNotNull(ToolCallParser.callEnd(thinkCall))
        assertEquals("web_search", ToolCallParser.callInOpenThink(thinkCall)?.let { (ToolCallParser.parse(it) as com.iris.localai.agent.core.ParsedReply.Call).call.name })
        assertNull(ToolCallParser.callInOpenThink("<think>a</think>" + call("x")))
        assertNull(ToolCallParser.callInOpenThink("<think>只是在想"))
        val m = FakeModel(thinkCall, "<think>好</think>港股收涨，台风预警解除。")
        val cb = Cb()
        val o = AgentLoop(m, listOf(WebSearchTool), { Permission.ALLOW }, env, cb, 8, Int.MAX_VALUE,
            answerOf = { ThinkParser.parse(it, expectThinking = true).answer }).run(base)
        assertEquals(listOf("web_search"), o.steps.map { it.tool })
        assertEquals(StepStatus.OK, o.steps[0].status)
        assertTrue(o.finalText.contains("港股收涨"))
    }

    // ---------------------------------------------------------------- step limit

    @Test fun stepLimitAsksForTheFindingsAndFallsBackToResults() = runTest {
        assertTrue(AgentPrompt.STEP_LIMIT.contains("tool results above"))
        val m = FakeModel(search, search, search)
        val o = loop(m, maxSteps = 1).run(base)
        assertEquals(AgentStop.MAX_STEPS, o.stop)
        assertEquals(AgentPrompt.STEP_LIMIT, m.seen.last().last().content)
        assertTrue(o.finalText, ThinkParser.parse(o.finalText).answer.startsWith(AgentLoop.FALLBACK_HEAD))
    }

    @Test fun stepLimitKeepsWhatTheModelSaid() = runTest {
        val m = FakeModel(search, search, "<think>好</think>港股收涨。")
        val o = loop(m, maxSteps = 1).run(base)
        assertEquals("港股收涨。", ThinkParser.parse(o.finalText).answer)
    }

    @Test fun contextTrimNeverStubsTheNewestToolOutput() = runTest {
        // tiny budget: older outputs are stubbed, the newest survives even when a note follows it
        val m = FakeModel(search("甲"), search("乙"), search("丙"), "好的")
        loop(m, maxSteps = 2, budget = 1).run(base)
        val last = m.seen.last()
        assertEquals(AgentPrompt.STEP_LIMIT, last.last().content)
        val outs = last.filter { it.content.startsWith("<tool_response>") }
        assertEquals(AgentPrompt.ELIDED, outs.first().content)
        assertTrue(outs.last().content.contains("Search results for \"乙\""))
    }

    // ---------------------------------------------------------------- prompt + search output

    @Test fun promptTellsTheModelToReportFindings() {
        for (style in AgentPrompt.CallStyle.entries) {
            val p = AgentPrompt.build(AgentTools.all + AgentTools.onDemand, "简短", style)
            assertTrue(p.contains("then report their findings"))
            assertTrue(com.iris.localai.util.TokenEstimator.estimate(p) <= 600)
        }
    }

    @Test fun searchOutputCarriesSnippetsDatesAndAnswerHint() {
        val text = SearchText.render("q", listOf(SearchHit("T", "https://e.com", "摘要", "源", "20 Oct 2026")))
        assertTrue(text.contains("answer from these"))
        assertTrue(text.contains("摘要") && text.contains("(源, 20 Oct 2026)"))
    }

    @Test fun googleNewsTrailingDateLineBecomesTheDate() {
        val (source, date, snippet) = BrowserSearch.describe("港股收涨", listOf("示例财经", "港股收涨", "恒生指数周二收涨，科技股领涨。", "3 小时前"))
        assertEquals("示例财经", source)
        assertEquals("3 小时前", date)
        assertEquals("恒生指数周二收涨，科技股领涨。", snippet)
        val (_, d2, s2) = BrowserSearch.describe("Title", listOf("Title", "Body text here.", "2 hours ago"))
        assertEquals("2 hours ago", d2); assertEquals("Body text here.", s2)
        // date at the head (Google web results) still works
        val (_, d3, s3) = BrowserSearch.describe("T", listOf("T", "2024年9月10日 — 正文"))
        assertEquals("2024年9月10日", d3); assertEquals("正文", s3)
    }

    @Test fun fallbackWithoutOutputNamesTheTools() {
        val s = AgentLoop.fallbackAnswer(listOf(AgentStep(tool = "web_search", title = "搜索「新闻」", status = StepStatus.ERROR)))
        assertTrue(s.contains("搜索「新闻」"))
        assertEquals("<think>a\n</think>\n\n", AgentLoop.closeThink("<think>a"))
        assertEquals("", AgentLoop.closeThink(""))
    }
}

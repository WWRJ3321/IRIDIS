package com.iris.localai.engine

import com.iris.localai.agent.core.AgentLoop
import com.iris.localai.engine.HistoryMatch
import com.iris.localai.engine.PromptMessage
import com.iris.localai.ui.chat.AttachmentOrder
import com.iris.localai.util.thinking.ThinkParser
import com.iris.localai.util.io.Attachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V1130ContextTest {
    @Test fun earlierToolOutputIsShortenedNotDropped() {
        val big = "<tool_response>\n" + "x".repeat(2000) + "\n</tool_response>"
        val s = AgentLoop.shortenToolOutput(big, AgentLoop.EARLIER_KEEP)
        assertTrue(s.startsWith("<tool_response>") && s.endsWith("</tool_response>"))
        assertTrue(s.length < 700)
        val small = "<tool_response>\nok\n</tool_response>"
        assertEquals(small, AgentLoop.shortenToolOutput(small, AgentLoop.EARLIER_KEEP))
    }

    @Test fun seedTurnsMergesSameRoleAndDropsEmptyModel() {
        val u = PromptMessage.USER; val a = PromptMessage.ASSISTANT
        val out = HistoryMatch.seedTurns(listOf(PromptMessage(u, "AI 新闻"), PromptMessage(a, " "), PromptMessage(u, "安特罗匹克"), PromptMessage(a, "好")))
        assertEquals(2, out.size)
        assertEquals("AI 新闻\n\n安特罗匹克", out[0].content)
        assertEquals(a, out[1].role)
    }

    @Test fun thinkOnlyReplyStaysInHistory() {
        val h = ThinkParser.stripForHistory("<think>" + "想".repeat(1000))
        assertTrue(h.startsWith("（未完成，仅有思考）"))
        assertTrue(h.length < 450)
        assertEquals("ok", ThinkParser.stripForHistory("<think>x</think>ok"))
    }

    @Test fun attachmentReorderAndLimits() {
        assertEquals(listOf("b", "c", "a"), AttachmentOrder.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), AttachmentOrder.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a"), AttachmentOrder.move(listOf("a"), 0, 3))
        assertEquals(20, Attachments.MAX_PER_MESSAGE)
        assertEquals(32_000, Attachments.MAX_TEXT_CHARS)
    }
}

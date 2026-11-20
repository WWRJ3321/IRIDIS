package com.iris.localai.agent

import com.iris.localai.agent.core.AgentCarry
import com.iris.localai.context.ToolRollup
import com.iris.localai.util.thinking.ThinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.4：智能体静默重试不再清掉已显示的正文。 */
class V274AgentCarryTest {
    @Test fun emptyCarryKeepsShown() {
        assertEquals("abc", AgentCarry.join("", "abc"))
        assertEquals("abc", AgentCarry.join("  ", "abc"))
        assertEquals("前文", AgentCarry.join("前文", ""))
    }

    @Test fun carryStaysAnswerBeforeThink() {
        val s = AgentCarry.join("已查到的内容", "<think>再想想")
        val p = ThinkParser.parse(s)
        assertEquals("已查到的内容", p.answer)
        assertTrue(p.thinkingOpen)
        val done = ThinkParser.parse(AgentCarry.join("已查到的内容", "<think>想</think>补充"))
        assertTrue(done.answer.startsWith("已查到的内容"))
        assertTrue(done.answer.endsWith("补充"))
    }

    @Test fun carryAfterUnopenedClose() {
        // 模板预填 <think> 时文本只有闭标签：前文不能落进思考里
        val p = ThinkParser.parse(AgentCarry.join("前文", "推理</think>答案"))
        assertEquals("推理", p.thinking)
        assertTrue(p.answer.startsWith("前文"))
        assertTrue(p.answer.endsWith("答案"))
    }

    @Test fun echoWithProseIsKeptNotRetried() {
        val answer = "根据搜索，今天北京晴。\n" + ToolRollup.HEADER + "\n- web_search(q=北京天气) ✓ 晴"
        assertTrue(ToolRollup.hasEcho(answer))
        // 去掉日志后还有正文 → 直接采用（AgentLoop 只在剩余为空时静默重试）
        assertFalse(ToolRollup.stripEcho(answer).isBlank())
        assertEquals("根据搜索，今天北京晴。", ToolRollup.stripEcho(answer))
        assertTrue(ToolRollup.stripEcho(ToolRollup.HEADER + "\n- web_search(q=x) ✓ y").isBlank())
    }
}

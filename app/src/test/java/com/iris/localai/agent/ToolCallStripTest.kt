package com.iris.localai.agent

import com.iris.localai.agent.core.ToolCallParser
import org.junit.Assert.*
import org.junit.Test

/** 2.1.0: the tool call never shows in the visible reply; prose around it stays. */
class ToolCallStripTest {
    private val call = "{\"tool\":\"plan\",\"args\":{\"action\":\"set\",\"steps\":[\"a\",\"b\"],\"title\":\"T\"}}"

    @Test fun fencedJsonCallRemovedProseKept() {
        val t = "好的，我先列个计划。\n```json\n$call\n```\n然后开始。"
        val out = ToolCallParser.stripCalls(t)
        assertEquals("好的，我先列个计划。\n\n然后开始。", out)
    }

    @Test fun toolFenceAndBareJsonAndQwenRemoved() {
        assertEquals("先看看。", ToolCallParser.stripCalls("先看看。\n```tool\n{\"tool\":\"list_dir\",\"args\":{}}\n```"))
        assertEquals("先看看。", ToolCallParser.stripCalls("先看看。\n$call"))
        assertEquals("读一下", ToolCallParser.stripCalls("读一下<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}</tool_call>"))
        assertEquals("嗯", ToolCallParser.stripCalls("嗯\n```\n$call\n```"))
    }

    @Test fun ordinaryCodeAndJsonDataStay() {
        val code = "示例：\n```json\n{\"a\": 1, \"b\": [2, 3]}\n```\n完"
        assertEquals(code, ToolCallParser.stripCalls(code))
        val kt = "```kotlin\nval x = mapOf(\"tool\" to 1)\n```"
        assertEquals(kt, ToolCallParser.stripCalls(kt))
        assertEquals("纯文本 {不是json", ToolCallParser.stripCalls("纯文本 {不是json"))
    }

    @Test fun roleJsonIsNotACall() {
        val t = "{\"name\": \"assistant\", \"content\": \"hi\"}"
        assertEquals(t, ToolCallParser.stripCalls(t))
    }

    @Test fun thinkBlockKept() {
        val out = ToolCallParser.stripCalls("<think>想一想</think>好的\n```json\n$call\n```")
        assertTrue(out.startsWith("<think>想一想</think>"))
        assertTrue(out.endsWith("好的"))
        assertFalse("tool" in out.substringAfter("</think>"))
    }

    @Test fun streamingHidesPartialCall() {
        assertEquals("好的", ToolCallParser.visibleText("好的\n```json\n{\"tool\":\"pl"))
        assertEquals("好的", ToolCallParser.visibleText("好的\n```json\n"))
        assertEquals("好的", ToolCallParser.visibleText("好的\n```js"))
        assertEquals("好的", ToolCallParser.visibleText("好的\n{\"tool\": \"plan\", \"args\": {\"st"))
        assertEquals("好的", ToolCallParser.visibleText("好的\n{\"to"))
        assertEquals("好的", ToolCallParser.visibleText("好的\n```json\n$call\n```"))
        // ordinary data while streaming is shown
        assertEquals("数据\n```json\n{\"a\": 1", ToolCallParser.visibleText("数据\n```json\n{\"a\": 1"))
        assertEquals("代码\n```python\nprint(1)", ToolCallParser.visibleText("代码\n```python\nprint(1)"))
    }

    @Test fun parseStillSeesTheCall() {
        // Stripping is display-only; the parser input is unchanged.
        val r = ToolCallParser.parse("好的\n```json\n$call\n```")
        assertTrue(r is com.iris.localai.agent.core.ParsedReply.Call)
    }
}

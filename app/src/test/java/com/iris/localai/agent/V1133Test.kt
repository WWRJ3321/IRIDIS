package com.iris.localai.agent

import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.core.ToolCallParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V1133Test {
    @Test fun gemmaNativeCallIsParsed() {
        val r = ToolCallParser.parse("Action: news\n<|tool_call>call:daily-news{query: \"今日头条\"}<tool_call|>")
        assertTrue(r is ParsedReply.Call)
        r as ParsedReply.Call
        assertEquals("daily-news", r.call.name)
        assertEquals("今日头条", r.call.args["query"]!!.jsonPrimitive.content)
    }

    @Test fun gemmaEscapedStringsAndColonsInValues() {
        val r = ToolCallParser.parse("<|tool_call>call:web_search{query:<|\"|>a, b: c<|\"|>,count:3}<tool_call|>") as ParsedReply.Call
        assertEquals("web_search", r.call.name)
        assertEquals("a, b: c", r.call.args["query"]!!.jsonPrimitive.content)
        assertEquals("3", r.call.args["count"]!!.jsonPrimitive.content)
    }

    @Test fun gemmaCallEndAndHidden() {
        val s = "ok <|tool_call>call:datetime{}<tool_call|> invented"
        assertEquals(s.indexOf(" invented"), ToolCallParser.callEnd(s))
        assertEquals("ok", ToolCallParser.visibleText("ok <|tool_call>call:dat"))
        assertEquals("ok", ToolCallParser.visibleText("ok <|tool"))
    }
}

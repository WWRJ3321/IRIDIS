package com.iris.localai.v228

import com.iris.localai.agent.ParsedReply
import com.iris.localai.agent.ToolCallParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V228TolerantToolCallTest {
    private fun call(text: String): ParsedReply.Call {
        val r = ToolCallParser.parse(text)
        assertTrue("expected a call, got $r", r is ParsedReply.Call)
        return r as ParsedReply.Call
    }

    @Test fun functionCallSyntaxInsideToolFence() {
        val c = call("好的，我来打开微信。\n```tool\nscreen(action=\"open_app\", app=\"微信\")\n```").call
        assertEquals("screen", c.name)
        assertEquals("open_app", c.args["action"]!!.jsonPrimitive.content)
        assertEquals("微信", c.args["app"]!!.jsonPrimitive.content)
    }

    @Test fun functionCallSyntaxInsideToolCallTag() {
        val r = call("<tool_call>\n调用 screen(action='open_app', app='微信')\n</tool_call>")
        assertEquals("screen", r.call.name)
        assertEquals("微信", r.call.args["app"]!!.jsonPrimitive.content)
    }

    @Test fun singleQuotedJsonWithPythonLiteralsAndTrailingCommas() {
        val c = call("```tool\n{'tool': 'web_search', 'args': {'query': '香港天气', 'fresh': True, 'site': None,},}\n```").call
        assertEquals("web_search", c.name)
        assertEquals("香港天气", c.args["query"]!!.jsonPrimitive.content)
        assertEquals("true", c.args["fresh"]!!.jsonPrimitive.content)
    }

    @Test fun singleQuotedJsonAfterProseInTag() {
        val c = call("<tool_call>先搜索一下 {'name': 'web_search', 'arguments': {'query': 'x'}}</tool_call>").call
        assertEquals("web_search", c.name)
        assertEquals("x", c.args["query"]!!.jsonPrimitive.content)
    }

    @Test fun toolNameFollowedByBraces() {
        val c = call("```tool\nscreen{\"action\": \"open_app\", \"app\": \"微信\"}\n```").call
        assertEquals("screen", c.name)
        assertEquals("微信", c.args["app"]!!.jsonPrimitive.content)
    }
}

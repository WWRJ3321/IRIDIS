package com.iris.localai.agent

import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.core.ToolCallParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V1108Test {
    private fun call(s: String) = (ToolCallParser.parse(s) as? ParsedReply.Call)?.call

    @Test fun semicolonAfterKeyIsRepaired() {
        val c = call("```tool\n{\"tool\"; \"list_dir\"}\n```")
        assertEquals("list_dir", c?.name)
        val c2 = call("```tool\n{\"tool\"; \"read_file\"; \"args\"; {\"path\"; \"a.md\"}}\n```")
        assertEquals("read_file", c2?.name)
        assertEquals("a.md", c2?.args?.get("path")?.jsonPrimitive?.content)
    }

    @Test fun otherSeparatorsAndCurlyQuotes() {
        assertEquals("datetime", call("```tool\n{\"tool\"= \"datetime\"}\n```")?.name)
        assertEquals("datetime", call("```tool\n{\"tool\"：\"datetime\"}\n```")?.name)
        val c = call("```tool\n{“tool”: “read_file”, “args”: {“path”: “x.txt”}}\n```")
        assertEquals("read_file", c?.name)
        assertEquals("x.txt", c?.args?.get("path")?.jsonPrimitive?.content)
        // members separated by ; or full-width comma
        val d = call("```tool\n{\"tool\": \"read_file\"; \"path\": \"b.txt\"}\n```")
        assertEquals("b.txt", d?.args?.get("path")?.jsonPrimitive?.content)
        // a semicolon inside a string stays
        val e = call("```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"a.js\", \"content\": \"x; y\"}}\n```")
        assertEquals("x; y", e?.args?.get("content")?.jsonPrimitive?.content)
    }

    @Test fun lfmRoleNameIsProse() {
        assertTrue(ToolCallParser.parse("tool_call:system{\"text\":\"hi\"}") is ParsedReply.Final)
        assertEquals("list_dir", call("tool_call:list_dir{\"path\":\".\"}")?.name)
    }
}

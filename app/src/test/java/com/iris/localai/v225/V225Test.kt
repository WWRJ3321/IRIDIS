package com.iris.localai.v225

import com.iris.localai.agent.Args
import com.iris.localai.agent.ParsedReply
import com.iris.localai.agent.ToolCallParser
import com.iris.localai.data.isProjectorFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.5: GLM-4.5 arg_key/arg_value calls (incl. malformed openings), tolerant known-tool fallback, markup never shown. */
class V225Test {
    private fun call(r: ParsedReply) = (r as ParsedReply.Call).call

    @Test fun sparkMalformedGlmOpening() {
        val raw = "<generate_image<arg_key>prompt</arg_key><arg_value>为我想象一个…人物特征。</arg_value><arg_key>image</arg_key><arg_value>workspace/imagen.png</arg_value></tool_call>"
        val c = call(ToolCallParser.parse(raw))
        assertEquals("generate_image", c.name)
        assertEquals("为我想象一个…人物特征。", Args.str(c.args, "prompt"))
        assertEquals("workspace/imagen.png", Args.str(c.args, "image"))
        assertEquals("", ToolCallParser.stripCalls(raw))
        assertEquals("", ToolCallParser.visibleText(raw))
        assertEquals(raw.length, ToolCallParser.callEnd(raw))
    }

    @Test fun glmStandardAndVariants() {
        val a = call(ToolCallParser.parse("<tool_call>web_search\n<arg_key>query</arg_key>\n<arg_value>香港 天气</arg_value>\n</tool_call>"))
        assertEquals("web_search", a.name); assertEquals("香港 天气", Args.str(a.args, "query"))
        val b = call(ToolCallParser.parse("<tool_call>read_file<arg_key>path</arg_key><arg_value>a.txt</arg_value>"))
        assertEquals("read_file", b.name); assertEquals("a.txt", Args.str(b.args, "path"))
        val c = ToolCallParser.parse("好的。<web_search><arg_key>query</arg_key><arg_value>新闻</arg_value>")
        assertEquals("web_search", call(c).name); assertEquals("好的。", (c as ParsedReply.Call).before)
        val d = call(ToolCallParser.parse("generate_image<arg_key>prompt</arg_key><arg_value>一只猫"))
        assertEquals("一只猫", Args.str(d.args, "prompt"))
    }

    @Test fun knownToolFallbackForms() {
        ToolCallParser.knownTools = setOf("web_search", "generate_image", "read_file")
        try {
            assertEquals("猫", Args.str(call(ToolCallParser.parse("generate_image prompt=\"猫\" width=512")).args, "prompt"))
            assertEquals("a.txt", Args.str(call(ToolCallParser.parse("read_file: {\"path\": \"a.txt\"}")).args, "path"))
            assertEquals("x", Args.str(call(ToolCallParser.parse("web_search <parameter=query>x</parameter>")).args, "query"))
            // Prose mentioning a tool is not a call.
            assertTrue(ToolCallParser.parse("我可以用 web_search 帮你查。") is ParsedReply.Final)
        } finally { ToolCallParser.knownTools = emptySet() }
    }

    @Test fun unreadableMarkupIsHidden() {
        val r = ToolCallParser.parse("稍等。<arg_key></arg_key></tool_call>")
        assertTrue(r is ParsedReply.Malformed)
        assertEquals("稍等。", (r as ParsedReply.Malformed).before)
        assertFalse(ToolCallParser.stripCalls("稍等。<arg_key></arg_key></tool_call>").contains("arg_key"))
    }

    @Test fun projectorFilesAreNotModels() {
        assertTrue(isProjectorFile("GUI-Owl-1.5-2B-Instruct.mmproj-Q8_0.gguf"))
        assertTrue(isProjectorFile("mmproj-model-f16.gguf"))
        assertFalse(isProjectorFile("Qwen3-1.7B-Q4_K_M.gguf"))
    }
}

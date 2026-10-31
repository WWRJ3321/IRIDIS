package com.iris.localai.v223

import com.iris.localai.agent.Args
import com.iris.localai.agent.ParsedReply
import com.iris.localai.agent.ToolCallParser
import com.iris.localai.agent.gui.OcrLine
import com.iris.localai.agent.gui.PxRect
import com.iris.localai.agent.gui.UiTreeSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.3: Python-style / XML tool calls, placeholder fill, GUI OCR fallback lines. */
class V223Test {
    private fun call(r: ParsedReply) = (r as ParsedReply.Call).call

    @Test fun signaturePlaceholderFilledFromUser() {
        val r = ToolCallParser.fillMainArg(ToolCallParser.parse("<tool_call>generate_image(prompt, image?)</tool_call>"), "画一只猫")
        assertEquals("generate_image", call(r).name)
        assertEquals("画一只猫", Args.str(call(r).args, "prompt"))
    }

    @Test fun pythonKeywordAndPositional() {
        val a = call(ToolCallParser.parse("<tool_call>web_search(query=\"天气 香港\", n=3)</tool_call>"))
        assertEquals("天气 香港", Args.str(a.args, "query")); assertEquals("3", Args.str(a.args, "n"))
        val b = call(ToolCallParser.parse("web_search(\"今日新闻\")"))
        assertEquals("今日新闻", Args.str(b.args, "query"))
        val c = ToolCallParser.fillMainArg(ToolCallParser.parse("web_search(\"weather\")"), "user words")
        assertEquals("weather", Args.str(call(c).args, "query"))
    }

    @Test fun qwenCoderXml() {
        val r = call(ToolCallParser.parse("<tool_call>\n<function=search_local>\n<parameter=query>\n发票\n</parameter>\n</function>\n</tool_call>"))
        assertEquals("search_local", r.name); assertEquals("发票", Args.str(r.args, "query"))
    }

    @Test fun jsonStillWorks() {
        val r = call(ToolCallParser.parse("<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"))
        assertEquals("a.txt", Args.str(r.args, "path"))
    }

    @Test fun ocrAddedWhenTreeEmpty() {
        assertTrue(UiTreeSerializer.needsOcr(emptyList()))
        val els = UiTreeSerializer.withOcr(emptyList(), listOf(OcrLine("开始游戏", PxRect(100, 200, 300, 260))), 1000, 2000)
        assertEquals("[0] OCR \"开始游戏\" (200,115)", els.single().line)
    }
}

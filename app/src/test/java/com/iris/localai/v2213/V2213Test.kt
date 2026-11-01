package com.iris.localai.v2213

import com.iris.localai.agent.ParsedReply
import com.iris.localai.agent.ToolCallParser
import com.iris.localai.engine.ChannelThink
import com.iris.localai.util.TombstoneText
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class V2213Test {
    private val k2Xml = "好的。<ifm|tool_calls>\n<ifm|tool_call>read_file\n<ifm|arg_key>path</ifm|arg_key>\n" +
        "<ifm|arg_value>a/b.md</ifm|arg_value>\n</ifm|tool_call>\n</ifm|tool_calls>"

    @Test fun k2XmlCall() {
        val r = ToolCallParser.parse(k2Xml)
        assertTrue(r.toString(), r is ParsedReply.Call)
        r as ParsedReply.Call
        assertEquals("read_file", r.call.name)
        assertEquals("a/b.md", r.call.args["path"]!!.jsonPrimitive.content)
        assertEquals("好的。", r.before.trim())
    }

    @Test fun k2TypedAndJsonCall() {
        val typed = "<ifm|tool_calls><ifm|tool_call>web_search\n<ifm|arg_key>query</ifm|arg_key>\n<ifm|arg_type>string</ifm|arg_type>\n" +
            "<ifm|arg_value>天气</ifm|arg_value>\n</ifm|tool_call></ifm|tool_calls>"
        val a = ToolCallParser.parse(typed) as ParsedReply.Call
        assertEquals("web_search", a.call.name)
        assertEquals("天气", a.call.args["query"]!!.jsonPrimitive.content)
        val json = "<ifm|tool_calls>\n<ifm|tool_call>{\"name\": \"memory\", \"arguments\": {\"action\": \"list\"}}</ifm|tool_call>\n</ifm|tool_calls>"
        val b = ToolCallParser.parse(json) as ParsedReply.Call
        assertEquals("memory", b.call.name)
    }

    @Test fun k2StreamingEndAndVisible() {
        val partial = "答案<ifm|tool_calls>\n<ifm|tool_call>read_file\n<ifm|arg_key>path"
        assertNull(ToolCallParser.callEnd(partial))
        assertEquals("答案", ToolCallParser.visibleText(partial).trim())
        val end = ToolCallParser.callEnd(k2Xml)!!
        assertTrue(k2Xml.substring(0, end).endsWith("</ifm|tool_call>"))
        assertEquals("答案", ToolCallParser.visibleText("答案<ifm|tool").trim())
        assertFalse(ToolCallParser.stripCalls(k2Xml).contains("ifm|"))
    }

    @Test fun k2ThinkTags() {
        val t = ChannelThink()
        val out = t.push("想一想</ifm|th") + t.push("ink>回答") + t.push("", final = true)
        assertEquals("想一想</think>回答", out)
        assertEquals("<think>x</think>y", ChannelThink.convert("<ifm|think_fast>x</ifm|think_fast>y"))
        assertEquals("<think>g</think>z", ChannelThink.convert("<|channel>thought\ng<channel|>z"))
    }

    // ---- tombstone protobuf → "#00 pc" text ----
    private fun ByteArrayOutputStream.varint(v0: Long) { var v = v0; while (true) { val b = (v and 0x7F).toInt(); v = v ushr 7; if (v == 0L) { write(b); return }; write(b or 0x80) } }
    private fun ByteArrayOutputStream.tagV(f: Int, v: Long) { varint((f shl 3).toLong()); varint(v) }
    private fun ByteArrayOutputStream.tagB(f: Int, b: ByteArray) { varint(((f shl 3) or 2).toLong()); varint(b.size.toLong()); write(b) }
    private fun msg(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()

    @Test fun tombstoneDecode() {
        fun frame(pc: Long, file: String, fn: String) = msg { tagV(1, pc); tagB(4, fn.toByteArray()); tagV(5, 16); tagB(6, file.toByteArray()) }
        fun thread(id: Int, name: String, vararg f: ByteArray) = msg { tagV(1, id.toLong()); tagB(2, name.toByteArray()); f.forEach { tagB(4, it) } }
        val idle = thread(11, "RenderThread", frame(0x10, "/system/lib64/libhwui.so", "wait"))
        val crash = thread(42, "DefaultDispatch", frame(0x1234, "/data/app/x/lib/arm64/libiris_llama.so", "ggml_cl_init"))
        val bytes = msg {
            tagV(1, 3); tagB(2, "OnePlus/PHK110".toByteArray()); tagV(5, 40); tagV(6, 42)
            tagB(10, msg { tagV(1, 11); tagB(2, "SIGSEGV".toByteArray()); tagB(4, "SEGV_MAPERR".toByteArray()); tagV(8, 1); tagV(9, 0x8) })
            tagB(16, msg { tagV(1, 11); tagB(2, idle) })
            tagB(16, msg { tagV(1, 42); tagB(2, crash) })
        }
        val text = TombstoneText.render(bytes)
        assertTrue(text, text.contains("signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x8"))
        val crashAt = text.indexOf("backtrace (crashing thread tid 42")
        assertTrue(text, crashAt >= 0 && crashAt < text.indexOf("tid 11"))
        assertTrue(text, text.contains("#00 pc 0000000000001234  /data/app/x/lib/arm64/libiris_llama.so (ggml_cl_init+16)"))
        assertEquals("*** *** text", TombstoneText.render("*** *** text".toByteArray()))
        assertNull(TombstoneText.decodeOrNull(byteArrayOf(0x0F, 0x01)))
    }
}

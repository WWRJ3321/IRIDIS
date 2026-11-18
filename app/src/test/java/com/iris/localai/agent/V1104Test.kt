package com.iris.localai.agent

import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.CallFormat
import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.core.ToolCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.10.4: role-name calls are prose, aliases fix misnames, LFM tool_call: form parses, hint texts. */
class V1104Test {

    private val avail = setOf("read_file", "write_file", "edit_file", "list_dir", "search_files", "delete_file", "calculator", "datetime", "web_fetch", "recall", "memory")

    // ---- 1. role names are never tool calls ----

    @Test fun roleSystemJsonIsFinal() {
        val r = ToolCallParser.parse("好的，这是示例：\n```json\n{\"name\": \"system\", \"content\": \"你好\"}\n```")
        assertTrue("expected Final, got $r", r is ParsedReply.Final)
    }

    @Test fun roleNameInToolFenceIsStillFinal() {
        // even a ```tool fence with a role name is dialogue JSON the model is illustrating — not a call.
        val r = ToolCallParser.parse("```tool\n{\"tool\": \"system\", \"args\": {}}\n```")
        assertTrue("expected Final, got $r", r is ParsedReply.Final)
    }

    @Test fun realToolStillParses() {
        val r = ToolCallParser.parse("```tool\n{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}\n```")
        assertTrue(r is ParsedReply.Call)
        assertEquals("read_file", (r as ParsedReply.Call).call.name)
    }

    // ---- 2. alias / canonicalize ----

    @Test fun aliasesMapToRealTools() {
        assertEquals("list_dir", ToolCallParser.canonicalizeName("ls", avail))
        assertEquals("read_file", ToolCallParser.canonicalizeName("cat", avail))
        assertEquals("read_file", ToolCallParser.canonicalizeName("Read-File", avail))
        assertEquals("read_file", ToolCallParser.canonicalizeName("readfile", avail))
        assertEquals("web_fetch", ToolCallParser.canonicalizeName("curl", avail))
        assertEquals("list_dir", ToolCallParser.canonicalizeName("LIST_DIR", avail))
    }

    @Test fun typoWithinTwoEditsFixes() {
        assertEquals("read_file", ToolCallParser.canonicalizeName("readflle", avail))
    }

    @Test fun unknownStaysUnknown() {
        assertNull(ToolCallParser.canonicalizeName("send_email", avail))
        assertNull(ToolCallParser.canonicalizeName("system", avail))
        assertNull(ToolCallParser.canonicalizeName("", avail))
    }

    // ---- 4. LFM2 native form ----

    @Test fun lfmToolCallColonForm() {
        val r = ToolCallParser.parse("我看一下。\ntool_call:read_file{\"path\": \"notes/a.md\"}")
        assertTrue("expected Call, got $r", r is ParsedReply.Call)
        val c = (r as ParsedReply.Call).call
        assertEquals("read_file", c.name)
        assertEquals("notes/a.md", c.args["path"]?.toString()?.trim('"'))
        assertEquals("我看一下。", c.let { (r).before })
    }

    @Test fun lfmFormDetectedInCallEnd() {
        val s = "前文\ntool_call:calculator{\"expression\": \"1+1\"}"
        assertNotNull(ToolCallParser.callEnd(s))
    }

    // ---- 5/3. prompt rules and hint text ----

    @Test fun promptTeachesRoleNameRule() {
        val p = AgentPrompt.build(emptyList())
        assertTrue(p.contains("\"system\""))
        assertTrue(p.contains("plain text with NO tool block"))
    }

    @Test fun unknownToolHintListsTools() {
        val h = AgentPrompt.unknownToolHint("system", avail)
        assertTrue(h.contains("no tool \"system\""))
        assertTrue(h.contains("read_file"))
    }

    // ---- 7. callEnd fast path keeps behavior ----

    @Test fun callEndFastPathMatchesSlowPath() {
        val plain = "这是一段普通的中文回答，没有工具调用，讲了很多内容。".repeat(3)
        assertNull(ToolCallParser.callEnd(plain))
        val fenced = "文```tool\n{\"tool\":\"read_file\",\"args\":{}}\n```后"
        val end = ToolCallParser.callEnd(fenced)
        assertNotNull(end)
        assertEquals(fenced.indexOf("```", fenced.indexOf("```tool") + 8) + 3, end)
    }

    @Test fun callFormatUnchanged() {
        val r = ToolCallParser.parse("<tool_call>{\"name\":\"datetime\",\"arguments\":{}}</tool_call>") as ParsedReply.Call
        assertEquals(CallFormat.QWEN_TAG, r.call.format)
    }
}

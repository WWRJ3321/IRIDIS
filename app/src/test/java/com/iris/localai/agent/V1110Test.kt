package com.iris.localai.agent

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.web.*
import com.iris.localai.agent.workspace.*
import com.iris.localai.agent.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V1110Test {
    @Test fun backendParse() {
        assertEquals(CommandBackend.BUILTIN, CommandBackend.parse(null))
        assertEquals(CommandBackend.TERMUX, CommandBackend.parse("termux"))
        assertEquals(CommandBackend.BUILTIN, AgentSettings(commandBackend = "x").sanitized().backend)
    }

    @Test fun termuxRefusal() {
        assertNull(TermuxResult.refusal("pkg install python -y"))
        assertNull(TermuxResult.refusal("python3 -c 'print(1)'"))
        assertNull(TermuxResult.refusal("rm -rf build"))
        assertNotNull(TermuxResult.refusal("su -c id"))
        assertNotNull(TermuxResult.refusal("rm -rf /"))
        assertNotNull(TermuxResult.refusal("rm -rf ~"))
        assertNotNull(TermuxResult.refusal("rm -rf /sdcard"))
        assertNotNull(TermuxResult.refusal(":(){ :|:& };:"))
    }

    @Test fun termuxMerge() {
        assertEquals("a\nb", TermuxResult.merge("a\n", "b", null))
        assertEquals("[Termux] x", TermuxResult.merge("", null, "x"))
    }

    @Test fun normalizeUrl() {
        assertEquals("https://example.com", BrowserText.normalizeUrl("example.com"))
        assertEquals("http://a.b/c", BrowserText.normalizeUrl("http://a.b/c"))
        assertTrue(BrowserText.normalizeUrl("天气 香港").startsWith("https://www.bing.com/search?q="))
    }

    @Test fun renderPages() {
        val lines = (1..400).map { "line $it with some words" }
        val s = PageSnapshot("T", "https://x", lines)
        val p1 = BrowserText.render(s, 1, 300)
        assertTrue(p1.startsWith("[T — https://x] part 1/"))
        assertTrue(p1.contains("page: 2"))
        val last = BrowserText.render(s, 99, 300)
        assertTrue(last.contains("line 400"))
        assertFalse(last.contains("…more"))
        assertTrue(BrowserText.render(PageSnapshot("", "u", emptyList()), 1, 300).contains("(empty page)"))
    }

    @Test fun parseSnapshot() {
        val inner = """{"title":"新闻","url":"https://a","lines":["Hello","[1] link \"更多\""]}"""
        val raw = JsonPrimitive(inner).toString()
        val s = BrowserScripts.parseSnapshot(raw)!!
        assertEquals("新闻", s.title)
        assertEquals(2, s.lines.size)
        assertNull(BrowserScripts.parseSnapshot("null"))
        assertTrue(BrowserScripts.type(3, "a\"b", true).contains("\"a\\\"b\""))
    }

    private class FakeBrowser : BrowserDriver {
        val log = mutableListOf<String>()
        private fun snap() = PageSnapshot("P", "https://p", listOf("[1] button \"Go\""))
        override fun open(url: String) = snap().also { log += "open $url" }
        override fun snapshot() = snap().also { log += "read" }
        override fun click(id: Int) = if (id == 1) snap().also { log += "click 1" } else null
        override fun type(id: Int, text: String, submit: Boolean) = if (id == 1) snap().also { log += "type $text $submit" } else null
        override fun back() = snap().also { log += "back" }
    }

    @Test fun browserTool() {
        val dir = kotlin.io.path.createTempDirectory("v1110").toFile()
        val b = FakeBrowser()
        val env = ToolEnv(WorkspaceSandbox(dir), UndoStore(File(dir, "u")), browser = b)
        assertTrue(BrowserTool.run(buildJsonObject { put("action", "open"); put("url", "bing.com") }, env).ok)
        assertEquals("open https://bing.com", b.log.last())
        assertFalse(BrowserTool.run(buildJsonObject { put("action", "click"); put("id", 5) }, env).ok)
        assertTrue(BrowserTool.run(buildJsonObject { put("action", "type"); put("id", 1); put("text", "hi"); put("submit", true) }, env).ok)
        assertEquals("type hi true", b.log.last())
        assertFalse(BrowserTool.run(buildJsonObject { put("action", "fly") }, env).ok)
        assertFalse(BrowserTool.defaultEnabled)
        assertEquals(ToolRisk.NETWORK, BrowserTool.risk)
        assertNotNull(AgentTools.byName("browser"))
    }

    @Test fun runCommandUsesTermuxRules() {
        val dir = kotlin.io.path.createTempDirectory("v1110").toFile()
        val shell = ShellRunner { c, _, _, _ -> ShellOutput(0, "ran $c", false, false) }
        val termux = ToolEnv(WorkspaceSandbox(dir), UndoStore(File(dir, "u")), shell = shell, shellKind = CommandBackend.TERMUX)
        assertTrue(RunCommandTool.run(buildJsonObject { put("command", "python3 -V") }, termux).ok)
        val builtin = ToolEnv(WorkspaceSandbox(dir), UndoStore(File(dir, "u")), shell = shell)
        assertFalse(RunCommandTool.run(buildJsonObject { put("command", "curl example.com") }, builtin).ok)
    }

    @Test fun texts() {
        assertTrue(com.iris.localai.ui.agent.ToolPageText.BACKEND_NOTE.contains("Termux"))
        assertTrue(TermuxBridge.SETUP.contains("allow-external-apps=true"))
    }
}

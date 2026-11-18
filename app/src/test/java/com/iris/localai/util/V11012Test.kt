package com.iris.localai.util

import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.data.Attachment
import com.iris.localai.data.AttachmentKind
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Role
import com.iris.localai.context.ContextAssembly
import com.iris.localai.ui.chat.ComposerAction
import com.iris.localai.ui.chat.ComposerActions
import com.iris.localai.util.io.Attachments
import com.iris.localai.util.TokenEstimator
import com.iris.localai.util.VisionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V11012Test {
    // ---- prompt ----
    @Test fun promptHasSections() {
        val p = AgentPrompt.build(AgentTools.all, "简短")
        listOf("# Rules", "# Tool calls", "# Tools", "# User instructions") // 2.3: Tone + Doing tasks merged.forEach { assertTrue(it, p.contains(it)) }
        assertTrue(p.endsWith("简短"))
        assertTrue("~${TokenEstimator.estimate(p)}", TokenEstimator.estimate(p) <= 800)
    }
    @Test fun qwenStyleUsesNativeTag() {
        val p = AgentPrompt.build(AgentTools.all, style = AgentPrompt.CallStyle.QWEN)
        assertTrue(p.contains("<tool_call>")); assertFalse(p.contains("```tool"))
        assertEquals(AgentPrompt.CallStyle.QWEN, AgentPrompt.styleFor("Agents-A1-4B_mixed_int4.litertlm"))
        assertEquals(AgentPrompt.CallStyle.QWEN, AgentPrompt.styleFor("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals(AgentPrompt.CallStyle.FENCE, AgentPrompt.styleFor("gemma-3n-E4B-it-int4.litertlm"))
        assertEquals(AgentPrompt.CallStyle.FENCE, AgentPrompt.styleFor(null))
    }
    @Test fun qwenExampleInPromptParses() {
        val r = ToolCallParser.parse("<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"notes/todo.md\"}}\n</tool_call>")
        assertTrue(r is ParsedReply.Call)
        assertEquals("read_file", (r as ParsedReply.Call).call.name)
    }
    @Test fun noToolsPromptSaysPlainText() {
        val p = AgentPrompt.build(emptyList())
        assertTrue(p.contains("No tools are enabled"))
    }
    @Test fun retryHintIsFormatNeutral() {
        val h = AgentPrompt.retryHint("bad json")
        assertTrue(h.contains("bad json")); assertFalse(h.contains("```tool"))
    }

    // ---- attachments ----
    @Test fun kinds() {
        assertEquals(AttachmentKind.IMAGE, Attachments.kindOf("a.JPG", null))
        assertEquals(AttachmentKind.IMAGE, Attachments.kindOf("x", "image/png"))
        assertEquals(AttachmentKind.TEXT, Attachments.kindOf("notes.md", "application/octet-stream"))
        assertEquals(AttachmentKind.TEXT, Attachments.kindOf("x", "text/plain"))
        assertEquals(AttachmentKind.FILE, Attachments.kindOf("doc.pdf", "application/pdf"))
    }
    @Test fun textDetection() {
        assertEquals("你好", Attachments.textOf("\uFEFF你好".toByteArray()))
        assertNull(Attachments.textOf(byteArrayOf(1, 0, 2, 3)))
        val long = "a".repeat(Attachments.MAX_TEXT_CHARS + 50)
        assertEquals(Attachments.MAX_TEXT_CHARS, Attachments.textOf(long.toByteArray())!!.length)
    }
    @Test fun promptBlocks() {
        val t = Attachment("todo.md", "/x/todo.md", kind = AttachmentKind.TEXT, text = "- 买菜")
        val i = Attachment("cat.jpg", "/x/cat.jpg", kind = AttachmentKind.IMAGE)
        val f = Attachment("a.pdf", "/x/a.pdf", sizeBytes = 2048, kind = AttachmentKind.FILE)
        val b = Attachments.promptBlock(listOf(t, i, f))
        assertTrue(b.contains("<attachment name=\"todo.md\">\n- 买菜\n</attachment>"))
        assertTrue(b.contains("看不到图片"))
        assertTrue(b.contains("a.pdf") && b.contains("2 KB"))
        assertTrue(Attachments.promptBlock(listOf(i), vision = true).contains("[图片：cat.jpg]"))
        assertEquals("", Attachments.promptBlock(emptyList()))
    }
    @Test fun attachmentOnlyMessageReachesPrompt() {
        val m = ChatMessage(role = Role.USER, content = "", attachments = listOf(Attachment("n.txt", "/n.txt", kind = AttachmentKind.TEXT, text = "hi")))
        val a = ContextAssembly.active(listOf(m), null, withTools = false)
        assertEquals(1, a.size); assertTrue(a[0].text.contains("hi"))
    }
    @Test fun visionNames() {
        assertTrue(VisionModels.litertMayHaveVision("gemma-3n-E4B-it-int4.litertlm"))
        assertFalse(VisionModels.litertMayHaveVision("Agents-A1-4B_mixed_int4.litertlm"))
    }
    @Test fun sendShowsWithOnlyAttachments() {
        assertEquals(ComposerAction.SEND, ComposerActions.of(false, false, hasAttachments = true))
        assertEquals(ComposerAction.VOICE, ComposerActions.of(false, false))
    }
}

class V11013Test {
    @Test fun stopGivesUpWithinTwoSeconds() {
        val total = com.iris.localai.util.streaming.StopPolicy.RETRIES * com.iris.localai.util.streaming.StopPolicy.RETRY_MS
        assertTrue(total in 900L..2000L)
    }
}

class V11013BgTest {
    @Test fun stallRule() {
        val g = com.iris.localai.util.io.BackgroundGuard
        assertTrue(g.stalled(30_000, 0, true))
        assertFalse(g.stalled(30_000, 0, false))
        assertFalse(g.stalled(5_000, 0, true))
        assertFalse(g.stalled(30_000, 200, true))
    }
    @Test fun attachMenuTexts() {
        assertEquals("照片", com.iris.localai.ui.chat.ComposerText.ATTACH_PHOTOS)
        assertEquals("文件", com.iris.localai.ui.chat.ComposerText.ATTACH_FILES)
    }
}

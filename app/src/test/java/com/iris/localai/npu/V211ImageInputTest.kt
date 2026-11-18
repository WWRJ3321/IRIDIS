package com.iris.localai.npu

import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PromptMessage
import com.iris.localai.knowledge.KbChat
import com.iris.localai.util.io.Attachments
import com.iris.localai.util.CtxMessage
import com.iris.localai.util.VisionPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V211ImageInputTest {
    private fun u(t: String) = PromptMessage(PromptMessage.USER, t)
    private fun a(t: String) = PromptMessage(PromptMessage.ASSISTANT, t)

    @Test fun picturesFoundOnExactUserTurn() {
        val text = "这是什么？\n\n[图片：IMG_1.jpg]"
        val msgs = listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), u("你好"), a("嗨"), u(text))
        assertEquals(3, VisionPrompt.targetIndex(msgs, text))
    }

    @Test fun picturesFoundWhenKnowledgeSnippetsArePrepended() {
        // 2.1.0 regression: KbChat.attach puts the snippets in front of the user's words.
        val text = "这是什么？\n\n[图片：IMG_1.jpg]"
        val ctx = KbChat.attach(listOf(CtxMessage("m1", PromptMessage.USER, text)), "m1", "<knowledge>片段</knowledge>")
        val msgs = listOf(PromptMessage(PromptMessage.SYSTEM, "sys")) + ctx.map { PromptMessage(it.role, it.content) }
        assertEquals(1, VisionPrompt.targetIndex(msgs, text))
    }

    @Test fun exactMatchPreferredAndAssistantIgnored() {
        val text = "[图片：a.png]"
        val msgs = listOf(u(text), a(text), u("前缀 $text"))
        assertEquals(0, VisionPrompt.targetIndex(msgs, text))
        assertEquals(-1, VisionPrompt.targetIndex(listOf(a(text)), text))
        assertEquals(-1, VisionPrompt.targetIndex(listOf(u("x")), "  "))
    }

    @Test fun mnnGetsSmallerPictures() {
        assertEquals(512, VisionPrompt.maxSide(0)) // 2.1.7: 1024 → 512
        assertEquals(512, VisionPrompt.maxSide(Attachments.MNN_VISION_MAX_SIDE))
        assertEquals(Pair(512, 384), Attachments.visionTarget(4000, 3000, 512))
        assertEquals(Pair(230, 512), Attachments.visionTarget(1080, 2400, 512))
        val d = ModelDetails("m", 0, 4096, 0, false, hasChatTemplate = true, backend = "CPU")
        assertEquals(0, d.visionMaxSide)
    }

    @Test fun cacheCopyKeyedBySize() {
        assertEquals("/a/b.png.vis.jpg", Attachments.visionPathFor("/a/b.png", 1024))
        assertEquals("/a/b.png.vis512.jpg", Attachments.visionPathFor("/a/b.png", 512))
        assertTrue(Attachments.isVisionCopyOf("b.png", "b.png.vis.jpg"))
        assertTrue(Attachments.isVisionCopyOf("b.png", "b.png.vis512.jpg"))
        assertFalse(Attachments.isVisionCopyOf("b.png", "b.png.vis512.jpg.tmp"))
        assertFalse(Attachments.isVisionCopyOf("b.png", "b.png.visual.jpg"))
        assertFalse(Attachments.isVisionCopyOf("b.png", "c.png.vis.jpg"))
    }

    @Test fun onlyJpegAndPngPassUnconverted() {
        assertTrue(Attachments.decodableEverywhere(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(Attachments.decodableEverywhere(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        // RIFF....WEBP
        assertFalse(Attachments.decodableEverywhere("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)))
        // ....ftypheic
        assertFalse(Attachments.decodableEverywhere(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)))
        assertFalse(Attachments.decodableEverywhere(ByteArray(0)))
    }

    @Test fun decodeSubsampleNeverBelowTarget() {
        assertEquals(4, Attachments.sampleSize(4000, 3000, 512, 384))
        assertEquals(1, Attachments.sampleSize(1000, 800, 512, 410))
        assertEquals(2, Attachments.sampleSize(1024, 768, 512, 384))
    }
}

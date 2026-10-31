package com.iris.localai.v228

import com.iris.localai.engine.ThinkSupport
import com.iris.localai.util.ThinkParseCache
import com.iris.localai.util.ThinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V228ThinkPrefillTest {
    @Test fun untaggedShortTextIsOpenThinkingWhenExpected() {
        val p = ThinkParseCache().parseStreaming("The user wants a poem about", expectThinking = true)
        assertTrue(p.thinkingOpen)
        assertEquals("", p.answer)
        assertEquals("The user wants a poem about", p.thinking)
    }

    @Test fun untaggedTextIsBodyWhenNotExpected() {
        val p = ThinkParseCache().parseStreaming("The user wants a poem about", expectThinking = false)
        assertFalse(p.hasThinking)
        assertEquals("The user wants a poem about", p.answer)
    }

    @Test fun longPlainUntaggedTextBecomesBody() {
        val text = "春".repeat(700)
        val p = ThinkParseCache().parseStreaming(text, expectThinking = true)
        assertFalse(p.thinkingOpen)
        assertEquals(text, p.answer)
    }

    @Test fun longReasoningLikeTextStaysThinking() {
        val text = "首先" + "想".repeat(700)
        assertTrue(ThinkParseCache().parseStreaming(text, expectThinking = true).thinkingOpen)
    }

    @Test fun closeTagMovesEverythingBeforeIntoThinking() {
        val c = ThinkParseCache()
        c.parseStreaming("Okay, let me think", expectThinking = true)
        c.parseStreaming("Okay, let me think</th", expectThinking = true).let { assertTrue(it.thinkingOpen) }
        val p = c.parseStreaming("Okay, let me think</think>\n\nHello!", expectThinking = true)
        assertFalse(p.thinkingOpen)
        assertEquals("Okay, let me think", p.thinking)
        assertEquals("Hello!", p.answer)
    }

    @Test fun strayTagsAreStripped() {
        assertEquals("Hi", ThinkParser.parse("a</think>Hi</think>").answer)
        assertEquals("Hi", ThinkParser.stripForHistory("<think>x</think>Hi</think>"))
    }

    @Test fun promptEndsWithOpenThink() {
        assertTrue(ThinkSupport.promptEndsWithOpenThink("<|im_start|>assistant\n<think>\n"))
        assertFalse(ThinkSupport.promptEndsWithOpenThink("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertFalse(ThinkSupport.promptEndsWithOpenThink("<|im_start|>assistant\n"))
    }
}

package com.iris.localai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkSwitchTest {
    private val qwen3 = "{%- if enable_thinking is defined and enable_thinking is false %}<think>\n\n</think>\n\n{%- endif %}<think>"
    private val r1 = "<｜Assistant｜><think>\n"
    private val smol = "{%- if '/no_think' in system_message %}{%- set reasoning_mode = '/no_think' %}<think>"
    private val nemo = "detailed thinking on"

    @Test fun switchableTemplatesAreNotForced() {
        assertFalse(ThinkSwitch.alwaysThinks(qwen3, supports = true, prefilled = false, effortKind = ""))
        // Qwen3.5 小模型：开思考时提示以 <think> 结尾，但有 enable_thinking 开关
        assertFalse(ThinkSwitch.alwaysThinks(qwen3, supports = true, prefilled = true, effortKind = ""))
    }

    @Test fun forcedTemplatesAreDetected() {
        assertTrue(ThinkSwitch.alwaysThinks(r1, supports = true, prefilled = true, effortKind = ""))
        assertTrue(ThinkSwitch.alwaysThinks(null, supports = true, prefilled = true, effortKind = ""))
        assertTrue(ThinkSwitch.alwaysThinks("<|channel|>", supports = true, prefilled = false, effortKind = "oss"))
        assertFalse(ThinkSwitch.alwaysThinks(r1, supports = false, prefilled = true, effortKind = ""))
        assertFalse(ThinkSwitch.alwaysThinks("<|channel|>", supports = true, prefilled = false, effortKind = "spark"))
    }

    @Test fun softSwitchFlags() {
        assertEquals("/no_think", ThinkSwitch.softSwitch(smol, false))
        assertEquals("/think", ThinkSwitch.softSwitch(smol, true))
        assertEquals("detailed thinking off", ThinkSwitch.softSwitch(nemo, false))
        assertEquals(null, ThinkSwitch.softSwitch(qwen3, false))
        assertEquals(null, ThinkSwitch.softSwitch(null, true))
        assertTrue(ThinkSwitch.supports(false, smol))
        assertFalse(ThinkSwitch.supports(false, "plain {{ messages }}"))
    }

    @Test fun applySoftAddsOrPrefixesSystem() {
        val h = listOf(PromptMessage("user", "hi"))
        val a = ThinkSwitch.applySoft(h, smol, on = false)
        assertEquals(2, a.size)
        assertEquals(PromptMessage(PromptMessage.SYSTEM, "/no_think"), a[0])
        val withSys = listOf(PromptMessage("system", "be brief"), PromptMessage("user", "hi"))
        val b = ThinkSwitch.applySoft(withSys, smol, on = true)
        assertEquals("/think\nbe brief", b[0].content)
        assertEquals(b, ThinkSwitch.applySoft(b, smol, on = true))
        assertEquals(h, ThinkSwitch.applySoft(h, qwen3, on = false))
    }

    @Test fun hideThinkingDropsReasoning() {
        assertEquals("answer", ThinkSwitch.hideThinking("<think>plan</think>answer"))
        assertEquals("answer", ThinkSwitch.hideThinking("plan steps</think>\n\nanswer"))
        assertEquals("", ThinkSwitch.hideThinking("<think>still thinking"))
        assertEquals("plain answer", ThinkSwitch.hideThinking("plain answer"))
    }
}

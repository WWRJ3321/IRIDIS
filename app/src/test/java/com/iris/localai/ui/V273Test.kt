package com.iris.localai.ui

import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.ThinkSwitch
import com.iris.localai.ui.chat.DictationOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.3：按住说话时识别文字可见；只带 enable_thinking 开关的模板思考按钮可用。 */
class V273Test {
    @Test fun dictationKeepsTextVisible() {
        assertEquals(DictationOverlay.Mode.NONE, DictationOverlay.of(false, "你好"))
        assertEquals(DictationOverlay.Mode.NONE, DictationOverlay.of(false, ""))
        assertEquals(DictationOverlay.Mode.FULL, DictationOverlay.of(true, ""))
        assertEquals(DictationOverlay.Mode.TEXT, DictationOverlay.of(true, "今天天气"))
        assertEquals(DictationOverlay.Mode.TEXT, DictationOverlay.of(true, " "))
    }

    private val qwen35moe = "{%- if enable_thinking is defined and enable_thinking is false %}{{- '<' + 'think>\\n\\n</' + 'think>\\n\\n' }}{%- endif %}"

    @Test fun enableThinkingTemplateSupportsThinking() {
        assertTrue(ThinkSwitch.supports(false, qwen35moe))
        assertTrue(ThinkSwitch.supports(true, qwen35moe))
        assertTrue(ThinkSwitch.supports(false, "detailed thinking on"))
        assertFalse(ThinkSwitch.supports(false, "{{ messages }}"))
        assertFalse(ThinkSwitch.supports(false, null))
        // 有开关：不算「关不掉」，关闭时只隐藏推理
        assertFalse(ThinkSwitch.alwaysThinks(qwen35moe, supports = true, prefilled = true, effortKind = ""))
        assertEquals("答案", ThinkSwitch.hideThinking("<think>想一想</think>答案").trim())
        // enable_thinking 模板不加系统旗标（开关走 disableThinking）
        assertEquals(null, ThinkSwitch.softSwitch(qwen35moe, true))
    }

    @Test fun releaseNotes273() {
        val r = UpdateNotes.RELEASES.first { it.version == "2.7.3" }
        assertEquals("2.7.3", r.version)
        assertEquals(3, r.of(UpdateNotes.Tag.FIX).size)
    }
}

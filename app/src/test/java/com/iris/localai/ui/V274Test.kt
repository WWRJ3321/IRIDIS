package com.iris.localai.ui

import com.iris.localai.data.ConversationOrder
import com.iris.localai.data.UpdateNotes
import com.iris.localai.ui.chat.ComposerAction
import com.iris.localai.ui.chat.ComposerActions
import com.iris.localai.ui.chat.DictationOverlay
import com.iris.localai.ui.chat.ThinkMotion
import com.iris.localai.ui.components.FoldMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.4：侧栏长按菜单/拖动、语音胶囊、聆听提示、思考块动画。 */
class V274Test {
    @Test fun longPressJitterIsNotDrag() {
        val slop = 18f
        assertFalse(ConversationOrder.isDrag(0f, 0f, slop))
        assertFalse(ConversationOrder.isDrag(3f, -5f, slop))
        assertFalse(ConversationOrder.isDrag(-18f, 18f, slop))
        assertTrue(ConversationOrder.isDrag(0f, 19f, slop))
        assertTrue(ConversationOrder.isDrag(0f, -40f, slop))
        assertTrue(ConversationOrder.isDrag(25f, 0f, slop))
    }

    @Test fun capsuleStaysVoiceWhileRecording() {
        assertEquals(ComposerAction.VOICE, ComposerActions.of(generating = false, hasText = true, recording = true))
        assertEquals(ComposerAction.VOICE, ComposerActions.of(generating = false, hasText = false, recording = true))
        // 松手定稿后按文字切换
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = false, hasText = true, recording = false))
        assertEquals(ComposerAction.VOICE, ComposerActions.of(generating = false, hasText = false))
        assertEquals(ComposerAction.STOP, ComposerActions.of(generating = true, hasText = false))
    }

    @Test fun listeningHintFadesWithText() {
        assertEquals(DictationOverlay.Mode.FULL, DictationOverlay.of(true, ""))
        assertEquals(DictationOverlay.Mode.TEXT, DictationOverlay.of(true, "你好"))
        assertEquals(DictationOverlay.Mode.NONE, DictationOverlay.of(false, "你好"))
        assertTrue(DictationOverlay.cancelOverlay(DictationOverlay.Mode.TEXT, cancelArmed = true))
        assertFalse(DictationOverlay.cancelOverlay(DictationOverlay.Mode.TEXT, cancelArmed = false))
        // 空输入框时由原来的整块提示显示「松开取消」
        assertFalse(DictationOverlay.cancelOverlay(DictationOverlay.Mode.FULL, cancelArmed = true))
    }

    @Test fun foldAnimatesUnderAutoScroll() {
        // 贴底自动滚动（没拖）不算用户滚动
        assertFalse(FoldMotion.userScrolling(prev = false, dragged = false, inProgress = true))
        // 拖动开始 → 算；松手后甩动余势仍算；停下 → 不算
        assertTrue(FoldMotion.userScrolling(prev = false, dragged = true, inProgress = true))
        assertTrue(FoldMotion.userScrolling(prev = true, dragged = false, inProgress = true))
        assertFalse(FoldMotion.userScrolling(prev = true, dragged = false, inProgress = false))
        assertFalse(FoldMotion.snap(reduceMotion = false, listScrolling = false))
    }

    @Test fun thinkTitleMotion() {
        assertTrue(ThinkMotion.PULSE_MIN in 0.5f..0.9f)
        assertTrue(ThinkMotion.shimmerX(0f) < 0f)
        assertTrue(ThinkMotion.shimmerX(1f) > 1f)
    }

    @Test fun releaseNotes274() {
        val r = UpdateNotes.RELEASES.first { it.version == "2.7.4" }
        assertEquals("2.7.4", r.version)
        assertEquals(4, r.of(UpdateNotes.Tag.FIX).size)
    }
}

package com.iris.localai.ui

import com.iris.localai.data.HistoryPress
import com.iris.localai.data.HistoryPress.Outcome
import com.iris.localai.data.UpdateNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.7.5：侧栏对话统一手势（短按 / 滚动 / 拖动位移）；2.7.6 起长按改进多选，见 V276Test。 */
class V275Test {
    private val slop = 18f

    @Test fun beforeLongPressTapOrScroll() {
        assertEquals(Outcome.TAP, HistoryPress.beforeLongPress(up = true, consumed = false, dx = 2f, dy = 3f, slop = slop))
        assertNull(HistoryPress.beforeLongPress(false, false, 5f, -6f, slop))
        // 列表上下滑动：超过 slop 放弃，交给列表
        assertEquals(Outcome.CANCEL, HistoryPress.beforeLongPress(false, false, 0f, 30f, slop))
        // 别人（滚动 / 抽屉）先消费了
        assertEquals(Outcome.CANCEL, HistoryPress.beforeLongPress(false, true, 0f, 1f, slop))
        assertEquals(Outcome.CANCEL, HistoryPress.beforeLongPress(true, true, 0f, 0f, slop))
    }

    @Test fun dragOffsetNoJump() {
        // 起拖瞬间为 0（不跳位）
        assertEquals(0f, HistoryPress.dragOffset(300, 0f, 300), 0f)
        // 跟手
        assertEquals(42f, HistoryPress.dragOffset(300, 42f, 300), 0f)
        // 分组变平铺 / 重排后布局位置变了，位移自动抵消，条目仍在手指下
        assertEquals(-58f, HistoryPress.dragOffset(300, 42f, 400), 0f)
        assertEquals(300f + 42f, 400 + HistoryPress.dragOffset(300, 42f, 400), 0f)
    }

    @Test fun releaseNotes275() {
        val r = UpdateNotes.RELEASES.first { it.version == "2.7.5" }
        assertEquals("2.7.5", r.version)
        assertEquals(1, r.of(UpdateNotes.Tag.FIX).size)
        assert(r.of(UpdateNotes.Tag.FIX).first().text.contains("长按"))
    }
}

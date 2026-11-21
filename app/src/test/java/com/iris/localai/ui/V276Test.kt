package com.iris.localai.ui

import com.iris.localai.data.HistoryPress
import com.iris.localai.data.HistoryPress.Outcome
import com.iris.localai.data.UpdateNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.6：侧栏长按不弹菜单——按住不动进多选；进多选后移动 = 退出多选并拖动；超时前移动 = 滚动。 */
class V276Test {
    private val slop = 18f

    @Test fun holdStillEntersSelectMode() {
        // 超时前没动、没抬起：一直等
        assertNull(HistoryPress.beforeLongPress(false, false, 3f, -5f, slop))
        // 到时：进多选并选中该条（按住时）
        assertEquals(Outcome.SELECT, HistoryPress.onLongPress())
        assertEquals(setOf("a"), HistoryPress.selection(HistoryPress.onLongPress(), emptySet(), "a"))
        // 仍按住、细微抖动：继续等，不拖动
        assertNull(HistoryPress.afterLongPress(up = false, dx = 0f, dy = 0f, slop = slop))
        assertNull(HistoryPress.afterLongPress(false, -4f, 7f, slop))
        assertNull(HistoryPress.afterLongPress(false, 18f, -18f, slop))
    }

    @Test fun releaseWithoutMoveStaysSelected() {
        assertEquals(Outcome.KEEP, HistoryPress.afterLongPress(true, 0f, 0f, slop))
        assertEquals(Outcome.KEEP, HistoryPress.afterLongPress(true, 10f, 10f, slop))
        assertEquals(setOf("a"), HistoryPress.selection(Outcome.KEEP, setOf("a"), "a"))
    }

    @Test fun moveAfterSelectCancelsSelectAndDrags() {
        assertEquals(Outcome.DRAG, HistoryPress.afterLongPress(false, 0f, 19f, slop))
        assertEquals(Outcome.DRAG, HistoryPress.afterLongPress(false, 0f, -40f, slop))
        assertEquals(Outcome.DRAG, HistoryPress.afterLongPress(false, 25f, 0f, slop))
        assertEquals(emptySet<String>(), HistoryPress.selection(Outcome.DRAG, setOf("a"), "a"))
    }

    @Test fun moveBeforeTimeoutIsScrollOnly() {
        assertEquals(Outcome.CANCEL, HistoryPress.beforeLongPress(false, false, 0f, 30f, slop))
        assertEquals(Outcome.CANCEL, HistoryPress.beforeLongPress(false, true, 0f, 1f, slop))
        // 放弃 / 短按都不碰多选
        assertEquals(emptySet<String>(), HistoryPress.selection(Outcome.CANCEL, emptySet(), "a"))
        assertEquals(emptySet<String>(), HistoryPress.selection(Outcome.TAP, emptySet(), "a"))
        assertEquals(Outcome.TAP, HistoryPress.beforeLongPress(true, false, 1f, 1f, slop))
    }

    @Test fun releaseNotes276() {
        val r = UpdateNotes.RELEASES.first { it.version == "2.7.6" }
        assertEquals("2.7.6", r.version)
        assertTrue(r.items.any { it.text.contains("多选") && it.text.contains("拖动") })
        assertTrue(r.items.none { it.text.contains("菜单（") })
    }
}

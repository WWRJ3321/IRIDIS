package com.iris.localai.ui

import com.iris.localai.data.HistoryPress
import com.iris.localai.data.HistoryPress.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 侧栏拖动排序（代码先行，待下个版本）：落点、邻行让位、贴边自动滚动、松手归位。 */
class SidebarDragTest {
    // 行高 100，下标 1..6 依次排在 top = 0,100,...,500
    private val slots = (1..6).map { Slot(it, (it - 1) * 100, 100) }

    @Test fun dropIndexFollowsCenter() {
        // 原位（拖动行 = 下标 3，top 200）：不动
        assertEquals(3, HistoryPress.dropIndex(3, slots, 200f, 100))
        // 往下一点点，中心（290）没过下一行中心（350）：不动
        assertEquals(3, HistoryPress.dropIndex(3, slots, 240f, 100))
        // 中心越过下标 4 的中心（350）：落到 4
        assertEquals(4, HistoryPress.dropIndex(3, slots, 310f, 100))
        // 越过两行
        assertEquals(5, HistoryPress.dropIndex(3, slots, 410f, 100))
        // 往上越过下标 2 的中心（150）
        assertEquals(2, HistoryPress.dropIndex(3, slots, 90f, 100))
        assertEquals(1, HistoryPress.dropIndex(3, slots, -10f, 100))
        // 拖出最后一行之外：停在最后
        assertEquals(6, HistoryPress.dropIndex(3, slots, 2000f, 100))
        // 候选里不含自己也一样
        assertEquals(4, HistoryPress.dropIndex(3, slots.filter { it.index != 3 }, 310f, 100))
    }

    @Test fun neighborsMakeRoomWithoutGap() {
        // 往下：3 → 5，下标 4、5 上移一个拖动行高，其余不动
        assertEquals(0, HistoryPress.neighborShift(2, 3, 5, 100))
        assertEquals(0, HistoryPress.neighborShift(3, 3, 5, 100))
        assertEquals(-100, HistoryPress.neighborShift(4, 3, 5, 100))
        assertEquals(-100, HistoryPress.neighborShift(5, 3, 5, 100))
        assertEquals(0, HistoryPress.neighborShift(6, 3, 5, 100))
        // 往上：3 → 1，下标 1、2 下移
        assertEquals(100, HistoryPress.neighborShift(1, 3, 1, 100))
        assertEquals(100, HistoryPress.neighborShift(2, 3, 1, 100))
        assertEquals(0, HistoryPress.neighborShift(4, 3, 1, 100))
        // 没在拖 / 回原位：都不动
        assertEquals(0, HistoryPress.neighborShift(4, -1, -1, 100))
        assertEquals(0, HistoryPress.neighborShift(4, 3, 3, 100))
        // 让位后：拖到 5 时，5 号行视觉 top = 400 − 100 = 300，原 3 号的位置被 4 号填上（不留空档）
        assertEquals(200, 300 + HistoryPress.neighborShift(4, 3, 5, 100))
    }

    @Test fun edgeAutoScrollSpeed() {
        val edge = 56f; val max = 20f
        // 中间：不滚
        assertEquals(0f, HistoryPress.autoScrollStep(300f, 50, 0, 1000, edge, max), 0f)
        // 刚碰到上边缘区：很慢地向上
        val s1 = HistoryPress.autoScrollStep(50f, 50, 0, 1000, edge, max)
        assertTrue(s1 < 0f && s1 > -max / 2)
        // 越深越快，顶到 / 超出边缘 = 最大
        val s2 = HistoryPress.autoScrollStep(10f, 50, 0, 1000, edge, max)
        assertTrue(s2 < s1)
        assertEquals(-max, HistoryPress.autoScrollStep(0f, 50, 0, 1000, edge, max), 1e-4f)
        assertEquals(-max, HistoryPress.autoScrollStep(-80f, 50, 0, 1000, edge, max), 1e-4f)
        // 下边缘：向下，线性
        assertEquals(max / 2, HistoryPress.autoScrollStep(1000f - 50 - 28, 50, 0, 1000, edge, max), 1e-3f)
        assertEquals(max, HistoryPress.autoScrollStep(980f, 50, 0, 1000, edge, max), 1e-4f)
        // 没视口 / 没边缘区：不滚
        assertEquals(0f, HistoryPress.autoScrollStep(0f, 50, 0, 0, edge, max), 0f)
        assertEquals(0f, HistoryPress.autoScrollStep(0f, 50, 0, 1000, 0f, max), 0f)
    }

    @Test fun settleStartsAtDropAndEndsInSlot() {
        // 松手时视觉 top 330，新布局 top 400：p=1 画在 330，p=0 在布局位置
        assertEquals(-70f, HistoryPress.settleOffset(1f, 330f, 400), 0f)
        assertEquals(-35f, HistoryPress.settleOffset(0.5f, 330f, 400), 0f)
        assertEquals(0f, HistoryPress.settleOffset(0f, 330f, 400), 0f)
        // 起拖那一刻不跳（沿用）
        assertEquals(0f, HistoryPress.dragOffset(300, 0f, 300), 0f)
    }

    // ---- 2.7.7 拖动行跟手（浮层，视口坐标） ----

    @Test fun overlayStaysUnderFingerWhileListScrolls() {
        // 手指在视口 y=330 抓住 top=300 的行：抓取偏移 30
        val grab = HistoryPress.grabOffset(330f, 300f)
        assertEquals(30f, grab, 0f)
        // 自动滚动 0 / 200 / -150：手指没动 → 浮层 top 不变（不跟着列表走，不被重复叠加滚动量）
        // （overlayTop 不接受滚动量：浮层位置只由手指决定）
        assertEquals(300f, HistoryPress.overlayTop(330f, grab, 100, 1000), 0f)
        // 手指往下 50：浮层也只往下 50
        assertEquals(350f, HistoryPress.overlayTop(380f, grab, 100, 1000), 0f)
    }

    @Test fun overlayClampedInsideViewport() {
        assertEquals(0f, HistoryPress.overlayTop(-200f, 30f, 100, 1000), 0f)
        assertEquals(900f, HistoryPress.overlayTop(5000f, 30f, 100, 1000), 0f)
        // 视口比行还矮：贴顶
        assertEquals(0f, HistoryPress.overlayTop(50f, 30f, 100, 60), 0f)
    }

    @Test fun viewportContentRoundTrip() {
        for (sc in listOf(0f, 123f, -40f)) {
            assertEquals(77f, HistoryPress.contentToViewport(HistoryPress.viewportToContent(77f, sc), sc), 0.001f)
        }
        assertEquals(350f, HistoryPress.viewportToContent(150f, 200f), 0f)
    }

    @Test fun dropIndexDuringScrollCountsScrollOnce() {
        // 内容里行 i 在 content top = (i-1)*100。拖 3（content 200）。
        val content = (1..10).map { Slot(it, (it - 1) * 100, 100) }
        fun visible(scrolled: Int) = content.map { Slot(it.index, it.top - scrolled, it.size) }   // 同帧视口 top
        // 手指不动、浮层停在视口 top=200；列表滚 0 → 原位
        assertEquals(3, HistoryPress.dropIndexContent(3, visible(0), 200f, 100, 0f))
        // 列表自动滚 250：浮层视口仍 200 = 内容 450 → 中心 500 越过 5 的中心（450）、没过 6（550）→ 落到 5
        assertEquals(5, HistoryPress.dropIndexContent(3, visible(250), 200f, 100, 250f))
        // 与纯视口同帧比较一致（平移不变）
        assertEquals(HistoryPress.dropIndex(3, visible(250), 200f, 100), HistoryPress.dropIndexContent(3, visible(250), 200f, 100, 250f))
        // 若把滚动量重复加到浮层（旧 bug）：会算成内容 700 → 落到 8，明显不同
        assertTrue(HistoryPress.dropIndex(3, content, 200f + 250f + 250f, 100) != 5)
        // 往上滚 -150（先滚到 300 后回到 150）：同理
        assertEquals(HistoryPress.dropIndex(3, visible(150), 200f, 100), HistoryPress.dropIndexContent(3, visible(150), 200f, 100, 150f))
    }
}

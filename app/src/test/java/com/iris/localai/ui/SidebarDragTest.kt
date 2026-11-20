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
}

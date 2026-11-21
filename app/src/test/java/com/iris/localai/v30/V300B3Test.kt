package com.iris.localai.v30

import com.iris.localai.floating.FloatingLogic
import com.iris.localai.ui.island.IslandGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V300B3Test {
    @Test fun islandSitsOnCutout() {
        assertEquals(32f, IslandGeom.pillHeight(38f), 0.01f)
        assertEquals(26f, IslandGeom.pillHeight(20f), 0.01f)
        // 挖孔 top 10 高 20 → 中心 20；胶囊 32 → 顶 4
        assertEquals(4f, IslandGeom.top(38f, 10f, 20f, 32f), 0.01f)
        assertEquals(3f, IslandGeom.top(38f, null, null, 32f), 0.01f)
        assertEquals(0f, IslandGeom.centerShift(400f, 200f), 0.01f)
        assertEquals(0f, IslandGeom.centerShift(400f, 30f), 0.01f)   // 角落挖孔不跟
        assertEquals(0f, IslandGeom.gap(null, 0f, false), 0.01f)
        assertEquals(22f, IslandGeom.gap(12f, 0f, true), 0.01f)
    }

    @Test fun menuBesideBall() {
        // 右侧小球（x=1000, 尺寸 140, 屏宽 1080）：菜单在左边
        val (x, y) = FloatingLogic.menuPos(1000, 800, 140, 400, 600, 1080, 2400, 20, left = false)
        assertEquals(1080 - 140 - 20 - 400, x)
        assertEquals(800 + 70 - 300, y)
        // 左侧：菜单在右边
        val (lx, _) = FloatingLogic.menuPos(0, 800, 140, 400, 600, 1080, 2400, 20, left = true)
        assertEquals(160, lx)
        // 贴顶时夹在屏幕内
        val (_, ty) = FloatingLogic.menuPos(1000, 0, 140, 400, 600, 1080, 2400, 20, left = false)
        assertTrue(ty >= 80)
    }

    @Test fun searchModeExists() { assertTrue(FloatingLogic.Mode.entries.any { it.name == "SEARCH" }) }
}

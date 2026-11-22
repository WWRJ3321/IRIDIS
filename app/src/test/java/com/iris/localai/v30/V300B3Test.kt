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
        assertEquals(800, y) // 卡片顶部和小球等高
        // 左侧：菜单在右边
        val (lx, _) = FloatingLogic.menuPos(0, 800, 140, 400, 600, 1080, 2400, 20, left = true)
        assertEquals(160, lx)
        // 贴顶时夹在屏幕内
        val (_, ty) = FloatingLogic.menuPos(1000, 0, 140, 400, 600, 1080, 2400, 20, left = false)
        assertTrue(ty >= 80)
        // 贴底时卡片往上收，小球跟着对齐卡片顶部
        val (_, by) = FloatingLogic.menuPos(1000, 2300, 140, 400, 600, 1080, 2400, 20, left = false)
        assertEquals(2400 - 600 - 80, by)
    }

    @Test fun searchModeExists() { assertTrue(FloatingLogic.Mode.entries.any { it.name == "SEARCH" }) }

    @Test fun screenTranslatePicksOcrForManga() {
        assertEquals("吹き出しのセリフがたくさんある", FloatingLogic.pickScreenText("戻る", "吹き出しのセリフがたくさんある"))
        assertEquals("一段很长的网页正文内容在这里", FloatingLogic.pickScreenText("一段很长的网页正文内容在这里", "一段很长的网页正文"))
        assertEquals("ocr", FloatingLogic.pickScreenText("", "ocr"))
    }

    @Test fun overlaySplitsTranslationPerBlock() {
        val a = com.iris.localai.agent.gui.OcrLine("Deploy", com.iris.localai.agent.gui.PxRect(0, 0, 100, 40))
        val b = com.iris.localai.agent.gui.OcrLine("Copy to\nbucket", com.iris.localai.agent.gui.PxRect(0, 50, 100, 90))
        assertEquals("Deploy\nCopy to bucket", FloatingLogic.joinBlocks(listOf(a, b)))
        assertEquals(listOf("部署"), FloatingLogic.splitTranslated("部署\n复制到", 2, done = false))
        assertEquals(listOf("部署", "复制到存储桶"), FloatingLogic.splitTranslated("1. 部署\n2. 复制到存储桶\n", 2, done = true))
        val root = com.iris.localai.agent.gui.UiNode(children = listOf(
            com.iris.localai.agent.gui.UiNode(text = "Like", bounds = com.iris.localai.agent.gui.PxRect(0, 10, 50, 30)),
            com.iris.localai.agent.gui.UiNode(text = "557", bounds = com.iris.localai.agent.gui.PxRect(60, 10, 90, 30)),
        ))
        assertEquals(listOf("Like"), FloatingLogic.treeBlocks(root).map { it.text })
    }

    @Test fun formatPairsCollapseGgufAndMnn() {
        fun mk(id: String, e: com.iris.localai.data.RecommendedCatalog.Engine, name: String = "Qwen3.5 2B") =
            com.iris.localai.data.RecommendedCatalog.Item(id, com.iris.localai.data.RecommendedCatalog.Section.entries.first(), name, "轻快", 1L, e, com.iris.localai.data.RecommendedCatalog.Kind.Hub(id))
        val a = mk("m", com.iris.localai.data.RecommendedCatalog.Engine.MNN); val b = mk("g", com.iris.localai.data.RecommendedCatalog.Engine.GGUF); val c = mk("x", com.iris.localai.data.RecommendedCatalog.Engine.GGUF, "Qwen3.5 4B")
        val g = com.iris.localai.data.FormatPairs.groups(listOf(a, b, c))
        assertEquals(2, g.size)
        assertEquals("g", com.iris.localai.data.FormatPairs.pick(g[0], null) { false }.id)
        assertEquals("m", com.iris.localai.data.FormatPairs.pick(g[0], null) { it.id == "m" }.id)
        assertEquals("m", com.iris.localai.data.FormatPairs.pick(g[0], "m") { false }.id)
    }
}

package com.iris.localai.data

import com.iris.localai.data.ModelSort
import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.data.RecommendedCatalog.Section
import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.edge0.Edge0Assets
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2.6.6: 推荐列表按 系列名 → 大小 排序（纯函数）+ Edge0 内存提示按运行内存。 */
class V266Test {
    private fun file(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }
    private fun src(p: String) = file(p).readText()
    private fun root(p: String) = listOf(p, "../$p").map { File(it) }.first { it.exists() }


    @Test fun seriesName() {
        assertEquals("Gemma 4", ModelSort.series("Gemma 4 19B REAP（IQ3_XXS）"))
        assertEquals("Gemma 4", ModelSort.series("Gemma 4 E2B"))
        assertEquals("LFM2.5", ModelSort.series("LFM2.5 24B-A2B 无审查（IQ2_XS）"))
        assertEquals("Edge0", ModelSort.series("Edge0-35B（实验）"))
        assertEquals("Nanbeige 4.2", ModelSort.series("Nanbeige 4.2 3B"))
        assertEquals("Qwen3", ModelSort.series("Qwen3 8B"))
        assertEquals("Qwen3.5", ModelSort.series("Qwen3.5 0.8B 推理版"))
        assertEquals("Spark X2.6", ModelSort.series("Spark X2.6 4B"))
        assertEquals("Gemma 3", ModelSort.series("Gemma 3 1B（NPU）"))
        assertEquals("Ternary Bonsai 2", ModelSort.series("Ternary Bonsai 2 9B（测试）"))
        assertEquals("Stable Diffusion 1.5", ModelSort.series("Stable Diffusion 1.5"))
    }

    @Test fun naturalOrder() {
        val n = ModelSort.natural
        assertTrue(n.compare("Qwen3", "Qwen3.5") < 0)
        assertTrue(n.compare("Qwen3.5", "Qwen3.6") < 0)
        assertTrue(n.compare("Gemma 4", "Gemma 12") < 0)
        assertTrue(n.compare("gemma 4", "Gemma 4") == 0)
        assertTrue(n.compare("Edge0", "Gemma 4") < 0)
        assertTrue(n.compare("LFM2.5", "Nanbeige 4.2") < 0)
    }

    private data class M(val name: String, val size: Long)

    @Test fun sortSeriesThenSize() {
        val shot = listOf( // 截图里的乱序
            M("Qwen3 8B", 5_027), M("Nanbeige 4.2 3B", 2_684), M("Gemma 4 19B REAP（IQ3_XXS）", 8_311),
            M("Gemma 4 19B REAP（Q4_0）", 10_647), M("LFM2.5 24B-A2B 无审查（IQ2_XS）", 8_516), M("Edge0-35B（实验）", 22_000),
            M("Qwen3.5 2B", 1_270), M("Gemma 4 E2B", 2_600), M("Qwen3 0.6B", 484),
        )
        val out = ModelSort.sort(shot, { it.name }, { it.size }).map { it.name }
        assertEquals(listOf(
            "Edge0-35B（实验）", "Gemma 4 E2B", "Gemma 4 19B REAP（IQ3_XXS）", "Gemma 4 19B REAP（Q4_0）",
            "LFM2.5 24B-A2B 无审查（IQ2_XS）", "Nanbeige 4.2 3B", "Qwen3 0.6B", "Qwen3 8B", "Qwen3.5 2B",
        ), out)
        // 同名两版（MNN / GGUF）按较小者排、保持原相对顺序
        val twins = listOf(M("Qwen3.5 4B", 2_900), M("Qwen3.5 4B", 2_707), M("Qwen3.5 2B", 1_400), M("Qwen3.5 2B", 1_270))
        assertEquals(listOf(1_400L, 1_270L, 2_900L, 2_707L), ModelSort.sort(twins, { it.name }, { it.size }).map { it.size })
        val runs = ModelSort.runs(out) { it }
        assertEquals(listOf("Edge0", "Gemma 4", "LFM2.5", "Nanbeige 4.2", "Qwen3", "Qwen3.5"), runs.map { it.first })
        assertEquals(3, runs[1].second.size)
    }

    @Test fun catalogSorted() {
        for (sec in listOf(Section.CHAT, Section.FEATURED, Section.IMAGE)) {
            val list = RecommendedCatalog.section(sec)
            assertEquals(list, ModelSort.sort(list, { it.name }, { it.sizeBytes }))
            // 同系列条目连续（视觉上归在一起）
            val series = ModelSort.runs(list) { it.name }.map { it.first }
            assertEquals(series.size, series.distinct().size)
        }
        val chat = RecommendedCatalog.section(Section.CHAT).map { it.id }
        assertFalse(chat.contains(RecommendedCatalog.REAP_IQ3)) // r4：REAP 移出推荐
        // 语音保持原顺序（与语音页一致）
        assertEquals(RecommendedCatalog.ITEMS.filter { it.section == Section.SPEECH }, RecommendedCatalog.section(Section.SPEECH))
        // 跳卡片详情仍按 id，不受排序影响
        assertNotNull(RecommendedCatalog.byId(Edge0Assets.REPO))
    }

}

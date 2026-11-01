package com.iris.localai.v2212

import com.iris.localai.data.MnnHub
import com.iris.localai.imagegen.ImageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedV2212Test {
    @Test fun groups() {
        val g = MnnHub.RECOMMENDED_GROUPS
        assertEquals(listOf("Qwen3（MNN）", "Qwen3.5（MNN）", "Qwen3.5 Opus 4.6 蒸馏（MNN）", "Gemma 4（LiteRT）", "Spark 2.6（GGUF Q4）", "生图（NPU）"), g.map { it.title })
        val all = g.flatMap { it.items }
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.any { it.id == "taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN" })
        assertFalse(all.any { it.id.contains("LFM", true) || it.id.contains("9B-Claude") })
        for (r in all) {
            assertTrue(r.note, r.note.contains(" · ") && r.note.contains("GB"))
            if (r.imageModel != null) assertTrue(ImageCatalog.byId(r.imageModel!!) != null) else assertTrue(r.id.count { it == '/' } == 1)
        }
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", MnnHub.RECOMMENDED.first().first)
    }

    @Test fun ggufPick() {
        val f = listOf("Spark-X2.6-4B.gguf" to 8L, "Spark-X2.6-4B-Q8_0.gguf" to 4L, "Spark-X2.6-4B-Q4_K_M.gguf" to 3L, "mmproj-Q4_K_M.gguf" to 1L)
        assertEquals("Spark-X2.6-4B-Q4_K_M.gguf", MnnHub.pickGgufFile(f)!!.first)
        assertEquals(null, MnnHub.pickGgufFile(listOf("a.mnn" to 1L)))
    }
}

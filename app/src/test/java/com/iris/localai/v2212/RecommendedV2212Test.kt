package com.iris.localai.v2212

import com.iris.localai.data.MnnHub
import com.iris.localai.imagegen.ImageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedV2212Test {
    /** 2.2.17 C: the list moved to RecommendedCatalog (对话 / 特色模型 / 生图 / 语音). */
    @Test fun groups() {
        val all = com.iris.localai.data.RecommendedCatalog.ITEMS
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.any { it.id == "taobao-mnn/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN" })
        assertFalse(all.any { it.id.contains("LFM", true) || it.id.contains("9B-Claude") })
        for (r in all) {
            val k = r.kind
            if (k is com.iris.localai.data.RecommendedCatalog.Kind.Image) assertTrue(ImageCatalog.byId(k.modelId) != null)
            r.repoId?.let { assertTrue(it.count { ch -> ch == '/' } == 1) }
        }
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", com.iris.localai.data.RecommendedCatalog.section(com.iris.localai.data.RecommendedCatalog.Section.CHAT).first().id)
    }

    @Test fun ggufPick() {
        val f = listOf("Spark-X2.6-4B.gguf" to 8L, "Spark-X2.6-4B-Q8_0.gguf" to 4L, "Spark-X2.6-4B-Q4_K_M.gguf" to 3L, "mmproj-Q4_K_M.gguf" to 1L)
        assertEquals("Spark-X2.6-4B-Q4_K_M.gguf", MnnHub.pickGgufFile(f)!!.first)
        assertEquals(null, MnnHub.pickGgufFile(listOf("a.mnn" to 1L)))
    }
}

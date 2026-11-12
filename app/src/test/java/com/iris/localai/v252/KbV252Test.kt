package com.iris.localai.v252

import com.iris.localai.agent.mcp.McpCap
import com.iris.localai.knowledge.KbEmbedModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.5.2 行为测试（需本版 main 类）：MCP 不限数量；Q4 嵌入模型的前缀、门槛与默认选择。 */
class KbV252Test {
    @Test fun mcpAllInjected() {
        val p = McpCap.select((1..60).toList())
        assertEquals(60, p.injected.size); assertEquals(0, p.dropped)
        assertEquals(Int.MAX_VALUE, McpCap.DEFAULT)
    }

    @Test fun q4Models() {
        val q = KbEmbedModels.QWEN3_Q4_MODEL; val g = KbEmbedModels.GEMMA_Q4_MODEL
        assertEquals(396_474_336L, q.size); assertEquals(175_673_856L, g.size)
        assertEquals(KbEmbedModels.QWEN3_MIN_COS, q.minCos, 0.0); assertEquals(KbEmbedModels.GEMMA_MIN_COS, g.minCos, 0.0)
        assertEquals(KbEmbedModels.QWEN3_MODEL.query("q"), q.query("q"))
        assertEquals(KbEmbedModels.QWEN3_MODEL.doc("x"), q.doc("x"))
        assertEquals(KbEmbedModels.GEMMA_MODEL.query("q"), g.query("q"))
        assertEquals(KbEmbedModels.GEMMA_MODEL.doc("x"), g.doc("x"))
        assertTrue(KbEmbedModels.isEmbedFile("Qwen3-Embedding-0.6B-Q4_K_M-imat.gguf"))
        assertTrue(KbEmbedModels.isEmbedFile("embeddinggemma-2-UD-Q4_K_XL.gguf"))
        assertEquals(4, KbEmbedModels.ALL.map { it.id }.toSet().size)
        assertEquals(4, KbEmbedModels.ALL.map { it.repo }.toSet().size)
    }

    @Test fun defaultPick() {
        val all = KbEmbedModels.ALL.map { it.id }
        assertEquals(KbEmbedModels.QWEN3, KbEmbedModels.pick(null, all)!!.id)
        assertEquals(KbEmbedModels.QWEN3_Q4, KbEmbedModels.pick(null, listOf(KbEmbedModels.GEMMA, KbEmbedModels.QWEN3_Q4))!!.id)
        assertEquals(KbEmbedModels.GEMMA_Q4, KbEmbedModels.pick(null, listOf(KbEmbedModels.GEMMA_Q4))!!.id)
        assertEquals(KbEmbedModels.GEMMA_Q4, KbEmbedModels.pick(KbEmbedModels.GEMMA_Q4, all)!!.id)
    }
}

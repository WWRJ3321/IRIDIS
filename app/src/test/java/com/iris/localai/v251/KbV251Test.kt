package com.iris.localai.v251

import com.iris.localai.knowledge.KbChat
import com.iris.localai.knowledge.KbChunk
import com.iris.localai.knowledge.KbCover
import com.iris.localai.knowledge.KbEmbedModels
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbPrompt
import com.iris.localai.knowledge.KbVecStore
import com.iris.localai.knowledge.KbVectors
import com.iris.localai.knowledge.KnowledgeBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.5.1 B: 知识库 tightened (≤ 2 snippets, 0.6 × best, no merge, no carried keywords), cite only injected, hybrid vectors. */
class KbV251Test {
    @Test fun tightenedConstants() {
        assertEquals(2, KbPrompt.INJECT_SNIPPETS)
        assertEquals(2, KbChat.MAX_SNIPPETS)
        assertEquals(0.6, KbChat.REL_SCORE, 1e-9)
        assertTrue(KbChat.MIN_NORM >= 0.25)
    }

    @Test fun injectedIsWhatTheBlockHolds() {
        val hits = (1..3).map { KbHit("f$it.md", "资料$it", 5.0, 0) }
        assertEquals(listOf("f1.md", "f2.md"), KbPrompt.injected(hits).map { it.file })
        val block = KbPrompt.inject(hits)
        assertTrue(block.contains("f2.md")); assertFalse(block.contains("f3.md"))
        // little room: one snippet only
        assertEquals(1, KbPrompt.injected(hits, KbPrompt.MIN_SNIPPET_CHARS).size)
        assertTrue(KbPrompt.injected(hits, 0).isEmpty())
    }

    @Test fun noMergeAndNoCarriedKeywords() {
        val root = Files.createTempDirectory("kb251").toFile()
        val kb = KnowledgeBase({ root }, File(root, "idx/kb.json"))
        val long = (1..12).joinToString("") { "第${it}条：押金规则与退还流程说明，内容编号$it。" }
        kb.add("合同.md", long.toByteArray())
        kb.add("菜谱.md", "番茄炒蛋：鸡蛋三个，番茄两个。".toByteArray())
        kb.refresh()
        val h = KbChat.hits(kb, "押金退还流程")
        assertTrue(h.size in 1..2)
        // each hit is one chunk as indexed (no neighbours joined in)
        for (x in h) assertEquals(kb.chunksOf(x.file)[x.chunkIndex], x.text)
        assertEquals(KbChat.hits(kb, "番茄呢").map { it.file }, KbChat.hits(kb, "番茄呢", previousUser = "押金退还流程").map { it.file })
        root.deleteRecursively()
    }

    @Test fun rrfAndHybridSelect() {
        val f = KbVectors.rrf(listOf(listOf(1, 2), listOf(2, 3)))
        assertEquals(1.0 / 61 + 1.0 / 62, f.getValue(2), 1e-12)
        val chunks = (0 until 4).map { KbChunk("d$it.md", "t$it", 0) }
        val cover = KbCover(2, 2, 1.0, 0.5, false, false)
        val bm = listOf(KbHit("d1.md", "t1", 3.0, 0, cover = cover), KbHit("d0.md", "t0", 2.0, 0, cover = cover))
        val cos = doubleArrayOf(0.30, 0.80, 0.70, Double.NaN)
        val out = KbVectors.select(chunks, bm, cos, KbEmbedModels.QWEN3_MIN_COS)
        assertTrue(out.size <= 2)
        assertEquals("d1.md", out.first().file)        // in both lists
        assertFalse(out.any { it.file == "d0.md" })     // cosine below the gate
        assertTrue(KbVectors.select(chunks, emptyList(), doubleArrayOf(0.1, 0.2, 0.3, 0.1), 0.45).isEmpty())
    }

    @Test fun vectorStoreRoundTripAndModelChange() {
        val dir = Files.createTempDirectory("kbv").toFile()
        val f = File(dir, KbVecStore.FILE)
        val s = KbVecStore(KbEmbedModels.QWEN3, 3)
        s.put(KbVectors.key("甲"), floatArrayOf(1f, 0f, 0f))
        s.put(42L, floatArrayOf(1f, 2f)) // wrong dimension: ignored
        s.write(f)
        val r = KbVecStore.read(f, KbEmbedModels.QWEN3)!!
        assertEquals(1, r.size)
        assertEquals(1f, r[KbVectors.key("甲")]!![0], 0f)
        assertNull(KbVecStore.read(f, KbEmbedModels.GEMMA))   // another model → rebuild
        assertNull(KbVecStore.read(f, KbEmbedModels.QWEN3, dim = 4))
        dir.deleteRecursively()
    }

    @Test fun embedModels() {
        assertTrue(KbEmbedModels.isEmbedFile("Qwen3-Embedding-0.6B-Q8_0.gguf"))
        assertTrue(KbEmbedModels.isEmbedFile("embeddinggemma-2-Q8_0.gguf"))
        assertFalse(KbEmbedModels.isEmbedFile("Qwen3-0.6B-Q4_K_M.gguf"))
        assertEquals(639_150_592L, KbEmbedModels.QWEN3_MODEL.size)
        assertEquals(KbEmbedModels.QWEN3, KbEmbedModels.pick(null, listOf(KbEmbedModels.GEMMA, KbEmbedModels.QWEN3))!!.id)
        assertEquals(KbEmbedModels.GEMMA, KbEmbedModels.pick(null, listOf(KbEmbedModels.GEMMA))!!.id)
        assertNull(KbEmbedModels.pick(KbEmbedModels.OFF, listOf(KbEmbedModels.QWEN3)))
        assertTrue(KbEmbedModels.QWEN3_MODEL.query("q").startsWith("Instruct: "))
        assertEquals("title: none | text: x", KbEmbedModels.GEMMA_MODEL.doc("x"))
        assertEquals("task: search result | query: q", KbEmbedModels.GEMMA_MODEL.query("q"))
        // 2.5.2: + Q4 of each (4 entries), Qwen3 Q8_0 still first / default
        assertEquals(4, KbEmbedModels.ALL.size)
        assertEquals(KbEmbedModels.QWEN3, KbEmbedModels.ALL.first().id)
    }
}

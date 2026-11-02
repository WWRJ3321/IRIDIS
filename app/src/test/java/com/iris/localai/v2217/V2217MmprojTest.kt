package com.iris.localai.v2217

import com.iris.localai.data.ModelOverrides
import com.iris.localai.engine.llama.MmprojPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.2.17 B: GGUF vision projector pairing (llama.cpp mtmd). */
class V2217MmprojTest {
    @Test fun downloadedWithModel() {
        val m = "gemma-3-4b-it-Q4_K_M.gguf"
        assertEquals("gemma-3-4b-it-Q4_K_M-mmproj-model-f16.gguf",
            MmprojPairing.pick(m, listOf(m, "gemma-3-4b-it-Q4_K_M-mmproj-model-f16.gguf", "other-mmproj-f16.gguf")))
    }

    @Test fun sameRepoPrefix() {
        val m = "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
        val files = listOf(m, "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf", "mmproj-SmolVLM-500M-f16.gguf", "Qwen3-4B-Q4_K_M.gguf")
        assertEquals("mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf", MmprojPairing.pick(m, files))
        // a text model in the same folder never gets someone else's projector
        assertNull(MmprojPairing.pick("Qwen3-4B-Q4_K_M.gguf", files))
    }

    @Test fun singleProjectorOnlyForVlmNames() {
        val files = listOf("SmolVLM2-2.2B-Q4_K_M.gguf", "mmproj-f16.gguf", "Llama-3.2-3B-Q4_K_M.gguf")
        assertEquals("mmproj-f16.gguf", MmprojPairing.pick("SmolVLM2-2.2B-Q4_K_M.gguf", files))
        assertNull(MmprojPairing.pick("Llama-3.2-3B-Q4_K_M.gguf", files))
    }

    @Test fun userPickWinsAndOffDisables() {
        val m = "Llama-3.2-3B-Q4_K_M.gguf"
        val files = listOf(m, "mmproj-a.gguf", "mmproj-b.gguf")
        assertEquals("mmproj-b.gguf", MmprojPairing.pick(m, files, "mmproj-b.gguf"))
        assertNull(MmprojPairing.pick(m, files, MmprojPairing.OFF))
        assertNull(MmprojPairing.pick(m, files, "gone-mmproj.gguf"))   // deleted file → text-only
        assertNull(MmprojPairing.pick(m, files, "Llama-3.2-3B-Q4_K_M.gguf")) // not a projector
    }

    @Test fun projectorOrNonGgufIsNeverAModel() {
        assertNull(MmprojPairing.pick("mmproj-f16.gguf", listOf("mmproj-f16.gguf", "mmproj-x.gguf")))
        assertNull(MmprojPairing.pick("qwen3-vl-mnn", listOf("mmproj-f16.gguf")))
    }

    @Test fun splitModelUsesBaseStem() {
        val m = "big-vl-Q4_K_M-00001-of-00002.gguf"
        assertEquals("big-vl-Q4_K_M-mmproj-f16.gguf",
            MmprojPairing.pick(m, listOf(m, "big-vl-Q4_K_M-00002-of-00002.gguf", "big-vl-Q4_K_M-mmproj-f16.gguf", "zz-mmproj.gguf")))
    }

    @Test fun candidatesAndImageMessage() {
        assertEquals(listOf("a-mmproj.gguf", "b-projector.gguf"),
            MmprojPairing.candidates("m.gguf", listOf("m.gguf", "b-projector.gguf", "a-mmproj.gguf", "x.gguf")))
        assertEquals(2, MmprojPairing.imageMessage(listOf(emptyList(), listOf("a.jpg"), listOf("b.jpg"), emptyList())))
        assertEquals(-1, MmprojPairing.imageMessage(listOf(emptyList(), emptyList())))
    }

    @Test fun overrideChangesLoadSignature() {
        assertNotEquals(ModelOverrides().loadSignature(), ModelOverrides(mmproj = "x-mmproj.gguf").loadSignature())
    }
}

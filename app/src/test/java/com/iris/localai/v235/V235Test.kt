package com.iris.localai.v235

import com.iris.localai.data.GgufFolder
import com.iris.localai.ui.components.MorphGeometry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.3.5: thin 角色 dropdown geometry (dp units, density 1). */
class V235Test {
    @Test fun widthWrapsWithinMinMax() {
        assertEquals(168f, MorphGeometry.dropWidth(120f, 400f, 16f, 168f, 220f), 0.01f)
        assertEquals(190f, MorphGeometry.dropWidth(190f, 400f, 16f, 168f, 220f), 0.01f)
        assertEquals(220f, MorphGeometry.dropWidth(500f, 400f, 16f, 168f, 220f), 0.01f)
        assertEquals(168f, MorphGeometry.dropWidth(500f, 200f, 16f, 168f, 220f), 0.01f)
    }
    @Test fun leftAlignsToPillShiftsOnlyAsNeeded() {
        assertEquals(150f, MorphGeometry.dropLeft(150f, 180f, 400f, 16f), 0.01f)
        assertEquals(204f, MorphGeometry.dropLeft(260f, 180f, 400f, 16f), 0.01f)
    }

    // ---- #8: GGUF folder classifier ----
    @Test fun mainPlusMmprojIsOnePair() {
        val p = GgufFolder.classify(listOf("LFM3-Omni-2B-Q4_K_M.gguf", "mmproj-LFM3-Omni-2B-F16.gguf", "README.md"))!!
        assertEquals(listOf("LFM3-Omni-2B-Q4_K_M.gguf"), p.mains)
        assertEquals(mapOf("LFM3-Omni-2B-Q4_K_M.gguf" to "mmproj-LFM3-Omni-2B-F16.gguf"), p.pairs)
        assertEquals(2, p.copy.size)
    }
    @Test fun headerDetectedProjectorIsRenamed() {
        val p = GgufFolder.classify(listOf("WeVisOne-VL-4B-Q4_0.gguf", "vision-f16.gguf"), setOf("vision-f16.gguf"))!!
        assertEquals("mmproj-vision-f16.gguf", p.copy["vision-f16.gguf"])
        assertEquals("mmproj-vision-f16.gguf", p.pairs["WeVisOne-VL-4B-Q4_0.gguf"])
        assertEquals(listOf("WeVisOne-VL-4B-Q4_0.gguf"), p.mains)
    }
    @Test fun multipleMainsEachImported() {
        val p = GgufFolder.classify(listOf("m-Q4_K_M.gguf", "m-Q8_0.gguf", "m-mmproj-f16.gguf"))!!
        assertEquals(2, p.mains.size)
        assertEquals(2, p.pairs.size)
    }
    @Test fun splitShardsAndNoMain() {
        val p = GgufFolder.classify(listOf("big-00001-of-00002.gguf", "big-00002-of-00002.gguf"))!!
        assertEquals(listOf("big-00001-of-00002.gguf"), p.mains)
        assertTrue(p.pairs.isEmpty())
        assertNull(GgufFolder.classify(listOf("mmproj-x.gguf", "config.json")))
    }
    @Test fun headerSniff() {
        assertTrue(GgufFolder.headerIsProjector("GGUF....clip.vision.image_size".toByteArray()))
        assertTrue(GgufFolder.headerIsProjector("GGUF..general.type....mmproj".toByteArray()))
        assertFalse(GgufFolder.headerIsProjector("GGUF..general.type....model llama.block_count".toByteArray()))
    }

    @Test fun folderNamesAndMains() {
        assertEquals("LFM3-Omni-2B-Q4_K_M", GgufFolder.folderFor("LFM3-Omni-2B-Q4_K_M.gguf"))
        assertEquals("big", GgufFolder.folderFor("big-00001-of-00003.gguf"))
        assertEquals(listOf("a-Q4.gguf"), GgufFolder.mainsIn(listOf("a-Q4.gguf", "mmproj-a-f16.gguf", "x.gguf.part")))
        assertTrue(GgufFolder.mainsIn(listOf("mmproj-a.gguf", "a.gguf.part")).isEmpty())
        assertTrue(GgufFolder.isSplitTail("big-00002-of-00003.gguf"))
        assertFalse(GgufFolder.isSplitTail("big-00001-of-00003.gguf"))
    }
}

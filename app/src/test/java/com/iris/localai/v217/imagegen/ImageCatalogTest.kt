package com.iris.localai.v217.imagegen

import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCatalogTest {
    @Test fun idsUniqueAndKnown() {
        val ids = ImageCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf("sd15-qnn", "sd15-mnn", "flux2-klein-4b", "z-image-turbo"), ids)
    }

    @Test fun invariants() {
        for (m in ImageCatalog.all) {
            assertTrue(m.defaultSteps in 1..50)
            assertTrue(m.defaultCfg in 1f..20f)
            m.fixedSize?.let { assertEquals(0, it % 16) }
            for (f in m.files) { assertTrue(f.url.startsWith("https://")); assertTrue(f.size > 0); assertTrue(f.path.isNotBlank()) }
            assertTrue(m.files.isNotEmpty() || m.hfRepo != null)
        }
    }

    @Test fun sd15Qnn() {
        val m = assertNotNullAnd(ImageCatalog.byId("sd15-qnn"))
        assertEquals(Tier.LOW, m.tier); assertEquals(Backend.QNN, m.backend)
        val zip = m.files.single { it.isArchive }
        assertEquals(709785222L, zip.size)
        assertTrue(zip.url.endsWith("qualcomm_snapdragon_8_elite_for_galaxy.zip"))
        assertTrue(zip.path.startsWith("sd15-qnn/.part/"))
        assertEquals(setOf("text_encoder.bin", "unet.bin", "vae.bin", "metadata.json"), zip.unpacked.map { it.path }.toSet())
        assertEquals(setOf("sd15-qnn/tokenizer/vocab.json", "sd15-qnn/tokenizer/merges.txt"), m.files.filter { !it.isArchive }.map { it.path }.toSet())
        assertEquals(512, m.fixedSize); assertEquals(20, m.defaultSteps); assertEquals(7.5f, m.defaultCfg, 0f)
        val mnn = assertNotNullAnd(ImageCatalog.byId("sd15-mnn"))
        assertEquals(Backend.MNN_CPU, mnn.backend); assertEquals(512, mnn.fixedSize)
        assertEquals("taobao-mnn/stable-diffusion-v1-5-mnn-general", mnn.hfRepo)
        assertEquals(Tier.MID, ImageCatalog.byId("flux2-klein-4b")!!.tier)
        assertEquals(Tier.HIGH, ImageCatalog.byId("z-image-turbo")!!.tier)
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}

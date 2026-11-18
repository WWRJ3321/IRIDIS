package com.iris.localai.imagegen

import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageRequestTest {
    private val free = ImageCatalog.FLUX2_KLEIN
    private val fixed = ImageCatalog.SD15_QNN

    @Test fun dimsRoundedAndClamped() {
        val r = ImageRequest("p", width = 777, height = 100, seed = 1).normalize(free)
        assertEquals(768, r.width); assertEquals(256, r.height)
        val big = ImageRequest("p", width = 5000, height = 1023, seed = 1).normalize(free)
        assertEquals(1024, big.width); assertEquals(1008, big.height)
    }

    @Test fun fixedSizeForced() {
        val r = ImageRequest("p", width = 768, height = 1024, seed = 1).normalize(fixed)
        assertEquals(512, r.width); assertEquals(512, r.height)
    }

    @Test fun stepsCfgStrengthClamped() {
        val r = ImageRequest("p", steps = 0, cfg = 50f, strength = 2f, seed = 3).normalize(free)
        assertEquals(1, r.steps); assertEquals(20f, r.cfg, 0f); assertEquals(1f, r.strength, 0f)
        val r2 = ImageRequest("p", steps = 99, cfg = 0f, strength = -1f, seed = 3).normalize(free)
        assertEquals(50, r2.steps); assertEquals(1f, r2.cfg, 0f); assertEquals(0f, r2.strength, 0f)
    }

    @Test fun negativeIgnoredWithoutCfg() {
        assertEquals("", ImageRequest("p", negative = "ugly", cfg = 1f).normalize(free).effectiveNegative)
        assertEquals("ugly", ImageRequest("p", negative = "ugly", cfg = 7f).normalize(free).effectiveNegative)
    }

    @Test fun seedRandomWhenNegative() {
        assertEquals(42L, ImageRequest("p", seed = 42).normalize(free).seed)
        val a = ImageRequest("p", seed = -1).normalize(free, Random(1)).seed
        val b = ImageRequest("p", seed = -1).normalize(free, Random(1)).seed
        assertTrue(a >= 0); assertEquals(a, b)
    }
}

package com.iris.localai.v30

import com.iris.localai.imagegen.UpscaleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V300B5Test {
    @Test fun rgbRoundTrip() {
        val px = intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFABCDEF.toInt())
        val rgb = UpscaleRules.argbToRgb(px)
        assertEquals(12, rgb.size)
        val head = ByteArray(8)
        assertTrue(UpscaleRules.rgbToArgb(head + rgb, 8, 4).contentEquals(px))
    }

    @Test fun header() {
        val w = 3; val h = 2
        val b = ByteArray(8 + w * h * 3)
        b[0] = 3; b[4] = 2
        assertEquals(3 to 2, UpscaleRules.header(b))
    }

    @Test(expected = IllegalArgumentException::class) fun headerBadSize() { UpscaleRules.header(ByteArray(10).also { it[0] = 3; it[4] = 2 }) }

    @Test fun limits() {
        UpscaleRules.check(1024, 1024)
        assertTrue(runCatching { UpscaleRules.check(2048, 512) }.isFailure)
        assertEquals(1, UpscaleRules.threads(1))
        assertEquals(6, UpscaleRules.threads(8))
    }

    @Test fun parameters() {
        assertEquals("a cat\nSteps: 8, Upscale: 4x RealESRGAN_x4plus_anime_6B", UpscaleRules.parameters("a cat\nSteps: 8\n", 4))
        assertEquals("Upscale: 2x RealESRGAN_x4plus_anime_6B", UpscaleRules.parameters(null, 2))
    }
}

package com.iris.localai.v2210

import com.iris.localai.engine.ThermalCutoff
import com.iris.localai.voice.WavPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTtsV2210Test {
    @Test fun cutoffSanitizeAndOver() {
        assertEquals(0, ThermalCutoff.sanitize(0))
        assertEquals(40, ThermalCutoff.sanitize(30))
        assertEquals(60, ThermalCutoff.sanitize(99))
        assertFalse(ThermalCutoff.over(70f, 0))             // off
        assertFalse(ThermalCutoff.over(47.9f, 48))
        assertTrue(ThermalCutoff.over(48f, 48))
        // 2.4.2: SEVERE alone no longer trips (left to 温度限速); CRITICAL does
        assertFalse(ThermalCutoff.over(Float.NaN, 48, thermalStatus = 3))
        assertTrue(ThermalCutoff.over(Float.NaN, 48, thermalStatus = 4))
        assertFalse(ThermalCutoff.over(Float.NaN, 48, thermalStatus = 2))
    }

    @Test fun tripLatchesUntilCooled() {
        ThermalCutoff.setCutoff(48)
        assertFalse(ThermalCutoff.check(50f, 0, Float.NaN, active = false)) // nothing loaded
        assertTrue(ThermalCutoff.check(48.5f, 0, Float.NaN, active = true))
        assertFalse(ThermalCutoff.check(49f, 0, Float.NaN, active = true))  // latched
        assertFalse(ThermalCutoff.check(46.5f, 0, Float.NaN, active = true)) // not cooled 2°C yet
        assertFalse(ThermalCutoff.check(45.9f, 0, Float.NaN, active = true)) // re-armed, below cutoff
        assertTrue(ThermalCutoff.check(48f, 0, Float.NaN, active = true))
        ThermalCutoff.setCutoff(0)
        assertTrue(ThermalCutoff.notice(48.04f).contains("48.0°C"))
    }

    private fun wav(rate: Int, samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test fun wavDecodeAndResample() {
        val pcm = WavPcm.decode(wav(16000, shortArrayOf(0, 16384, -32768, 32767)))
        assertNotNull(pcm)
        assertEquals(16000, pcm!!.rate)
        assertEquals(0.5f, pcm.samples[1], 1e-4f)
        assertEquals(-1f, pcm.samples[2], 1e-4f)
        val up = WavPcm.resample(FloatArray(16000) { 0.25f }, 16000, 24000)
        assertEquals(24000, up.size)
        assertEquals(0.25f, up[12345], 1e-6f)
        assertEquals(null, WavPcm.decode(ByteArray(10)))
    }
}

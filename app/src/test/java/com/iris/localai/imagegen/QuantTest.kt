package com.iris.localai.imagegen

import com.iris.localai.imagegen.Quant
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantTest {
    @Test fun roundTripWithinHalfStep() {
        val scale = 0.001f; val zp = 32768
        val xs = floatArrayOf(-3f, -0.5f, 0f, 0.1234f, 2.5f, 32.7f)
        val back = Quant.dequantize(Quant.quantize(xs, scale, zp), scale, zp)
        for (i in xs.indices) assertEquals(xs[i], back[i], scale / 2 + 1e-5f)
    }

    @Test fun clampsToUint16() {
        assertEquals(0, Quant.quantize(-1000f, 0.001f, 0))
        assertEquals(65535, Quant.quantize(1000f, 0.001f, 0))
        val q = Quant.quantize(floatArrayOf(1e9f), 1f, 0)
        assertEquals(65535, Quant.toUInt(q)[0])
        assertEquals(65535f, Quant.dequantize(q, 1f, 0)[0], 0f)
    }

    @Test fun requantizeMatchesDirect() {
        val xs = floatArrayOf(-1f, -0.25f, 0f, 0.75f, 1.5f)
        val q1 = Quant.quantize(xs, 0.0005f, 30000)
        val q2 = Quant.requantize(q1, 0.0005f, 30000, 0.0001f, 20000)
        val back = Quant.dequantize(q2, 0.0001f, 20000)
        for (i in xs.indices) assertEquals(xs[i], back[i], 0.0005f)
    }
}

package com.iris.localai.v2210

import com.iris.localai.ui.components.BlendPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BlendPaletteV2210Test {
    private val purple = 0xFF7C4DFF.toInt()

    private fun hueDist(a: Float, b: Float) = minOf(abs(a - b), 360f - abs(a - b))

    @Test fun hslRoundTrip() {
        for (c in intArrayOf(purple, 0xFF2196F3.toInt(), 0xFFE91E63.toInt(), 0xFF4CAF50.toInt())) {
            val h = BlendPalette.toHsl(c)
            val back = BlendPalette.fromHsl(h[0], h[1], h[2])
            for (sh in intArrayOf(0, 8, 16)) assertTrue(abs(((c shr sh) and 0xFF) - ((back shr sh) and 0xFF)) <= 1)
        }
    }

    @Test fun followsAccentWithAdjacentHues() {
        val accentHue = BlendPalette.toHsl(purple)[0]
        val cols = BlendPalette.derive(purple, dark = false)
        assertEquals(4, cols.size)
        cols.forEach { c ->
            val d = hueDist(BlendPalette.toHsl(c)[0], accentHue)
            assertTrue("hue $d", d <= 61f)
        }
        assertTrue(hueDist(BlendPalette.toHsl(cols[0])[0], accentHue) <= 2f)
        // A different accent gives different colours (nothing hard-coded).
        assertTrue(!BlendPalette.derive(0xFF2196F3.toInt(), false).contentEquals(cols))
    }

    @Test fun pastelInLightMutedInDark() {
        val light = BlendPalette.derive(purple, dark = false).map { BlendPalette.toHsl(it) }
        val dark = BlendPalette.derive(purple, dark = true).map { BlendPalette.toHsl(it) }
        light.forEach { assertTrue(it[2] in 0.75f..0.85f) }
        dark.forEach { assertTrue(it[2] <= 0.45f && it[1] <= 0.42f) }
        for (i in light.indices) assertTrue(dark[i][1] < light[i][1])
    }

    @Test fun greyAccentDoesNotCrash() {
        assertEquals(4, BlendPalette.derive(0xFF808080.toInt(), dark = true).size)
    }
}

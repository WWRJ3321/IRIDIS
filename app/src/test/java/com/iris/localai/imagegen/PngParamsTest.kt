package com.iris.localai.imagegen

import com.iris.localai.imagegen.PngParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PngParamsTest {
    /** Minimal valid 1x1 grayscale PNG. */
    private fun tinyPng(): ByteArray {
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0)
        val raw = byteArrayOf(0, 0x7F)
        val d = Deflater().apply { setInput(raw); finish() }
        val buf = ByteArray(64); val n = d.deflate(buf)
        return ByteArrayOutputStream().apply {
            write(PngParams.SIGNATURE)
            write(PngParams.chunk("IHDR", ihdr))
            write(PngParams.chunk("IDAT", buf.copyOf(n)))
            write(PngParams.chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    @Test fun insertsTextAfterIhdrWithValidCrc() {
        val src = tinyPng()
        val text = "a cat, masterpiece\nSteps: 20, CFG scale: 7.5, Seed: 42"
        val out = PngParams.insert(src, text)
        val chunks = PngParams.chunks(out)
        assertEquals(listOf("IHDR", "tEXt", "IDAT", "IEND"), chunks.map { it.type })
        assertTrue(chunks.all { it.crcOk })
        assertEquals(text, PngParams.read(out))
        // original chunks untouched
        assertArrayEquals(PngParams.chunks(src)[1].data, chunks[2].data)
    }

    @Test fun nonLatinFallsBackToItxt() {
        val out = PngParams.insert(tinyPng(), "一只猫")
        val chunks = PngParams.chunks(out)
        assertEquals("iTXt", chunks[1].type)
        assertTrue(chunks.all { it.crcOk })
        assertEquals("一只猫", PngParams.read(out))
    }
}

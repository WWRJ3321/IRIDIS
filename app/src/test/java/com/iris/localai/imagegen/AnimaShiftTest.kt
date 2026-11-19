package com.iris.localai.imagegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anima NPU 调度偏移与导入错误提示（2.7.0-beta3）。 */
class AnimaShiftTest {
    @Test fun defaultShiftUnchanged() {
        assertArrayEquals(AnimaMath.sigmas(8), AnimaMath.sigmas(8, 3f), 0f)
    }

    @Test fun shiftFormula() {
        val steps = 4
        for (sh in listOf(1f, 2f, 4.5f, 6f)) {
            val s = AnimaMath.sigmas(steps, sh)
            assertEquals(steps + 1, s.size); assertEquals(0f, s[steps])
            assertEquals(1f, s[0], 1e-6f)
            for (x in 1 until steps) {
                val t = (1000 - x * (1000 / steps)) / 1000.0
                assertEquals((sh * t / (1 + (sh - 1) * t)).toFloat(), s[x], 1e-6f)
            }
        }
        // shift 1 = linear; bigger shift keeps sigma higher in the middle
        assertEquals(0.5f, AnimaMath.sigmas(4, 1f)[2], 1e-6f)
        assertTrue(AnimaMath.sigmas(4, 6f)[2] > AnimaMath.sigmas(4, 3f)[2])
    }

    @Test fun clampShift() {
        assertEquals(1f, AnimaMath.clampShift(0.2f)); assertEquals(6f, AnimaMath.clampShift(9f))
        assertEquals(3.5f, AnimaMath.clampShift(3.4f)); assertEquals(3f, AnimaMath.clampShift(Float.NaN))
        assertArrayEquals(AnimaMath.sigmas(4, 6f), AnimaMath.sigmas(4, 20f), 0f)
    }

    @Test fun shiftPersistsPerImportedModel() {
        val e = ImportedImage("imp-0000abcd", "NPU", ImportArch.ANIMA_NPU.name, "imp-0000abcd", "imp-0000abcd", "imp-0000abcd", 8, 1f)
        assertEquals(3f, e.shift); assertEquals(3f, e.toModel().animaShift)
        val back = ImportedImages.decode(ImportedImages.encode(listOf(e.copy(shift = 4.5f)))).single()
        assertEquals(4.5f, back.shift); assertEquals(4.5f, back.toModel().animaShift)
        // older lists without the field keep the default
        val old = ImportedImages.encode(listOf(e)).replace(",\"shift\":3.0", "")
        assertFalse(old.contains("shift"))
        assertEquals(3f, ImportedImages.decode(old).single().shift)
    }

    @Test fun brokenArchiveDetected() {
        assertTrue(ImportErrors.isBroken(java.util.zip.ZipException("invalid block type")))
        assertTrue(ImportErrors.isBroken(java.io.EOFException("Unexpected end of ZLIB input stream")))
        assertTrue(ImportErrors.isBroken(IllegalStateException("x", java.util.zip.ZipException("invalid stored block lengths"))))
        assertTrue(ImportErrors.isBroken(java.io.IOException("Unexpected end of ZLIB input stream")))
        assertFalse(ImportErrors.isBroken(IllegalStateException("缺少主模型文件")))
        assertEquals("压缩包损坏或没下载完整，请重新下载", ImportErrors.message(java.util.zip.ZipException("invalid block type")))
        assertEquals("缺少主模型文件", ImportErrors.message(IllegalStateException("缺少主模型文件")))
    }
}

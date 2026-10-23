package com.iris.localai.files

import com.iris.localai.ui.motion.IrisMotion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 2.2 「档案模式」: pure deck geometry of [ArchiveMath]. */
class ArchiveMathTest {
    private val eps = 1e-4f

    @Test fun loopsOnlyAboveFive() {
        assertFalse(ArchiveMath.loops(0))
        assertFalse(ArchiveMath.loops(5))
        assertTrue(ArchiveMath.loops(6))
    }

    @Test fun wrapIsNegativeSafe() {
        assertEquals(0, ArchiveMath.wrap(0, 7))
        assertEquals(6, ArchiveMath.wrap(-1, 7))
        assertEquals(0, ArchiveMath.wrap(7, 7))
        assertEquals(3, ArchiveMath.wrap(-11, 7))
        assertEquals(0, ArchiveMath.wrap(5, 0))
    }

    @Test fun entryAtBoundedAndLooping() {
        assertEquals(-1, ArchiveMath.entryAt(-1, 4))
        assertEquals(-1, ArchiveMath.entryAt(4, 4))
        assertEquals(2, ArchiveMath.entryAt(2, 4))
        assertEquals(9, ArchiveMath.entryAt(-1, 10))
        assertEquals(0, ArchiveMath.entryAt(10, 10))
        assertEquals(-1, ArchiveMath.entryAt(0, 0))
    }

    @Test fun windowNeverShowsAnEntryTwice() {
        for (n in 0..30) {
            val half = ArchiveMath.halfWindow(n)
            assertTrue(half <= ArchiveMath.MAX_HALF_WINDOW)
            if (ArchiveMath.loops(n)) {
                val shown = (-half..half).map { ArchiveMath.entryAt(100 + it, n) }
                assertEquals("n=$n", shown.size, shown.toSet().size)
            }
        }
        assertEquals(0, ArchiveMath.halfWindow(1))
        assertEquals(2, ArchiveMath.halfWindow(6))   // 5 cards
        assertEquals(3, ArchiveMath.halfWindow(7))   // 7 cards
        assertEquals(3, ArchiveMath.halfWindow(500))
        assertEquals(3, ArchiveMath.halfWindow(4))   // bounded: missing slots are just empty
    }

    @Test fun focusIndex() {
        assertEquals(0, ArchiveMath.focusIndex(0.4f, 3))
        assertEquals(1, ArchiveMath.focusIndex(0.6f, 3))
        assertEquals(2, ArchiveMath.focusIndex(7f, 3))      // bounded: clamped
        assertEquals(9, ArchiveMath.focusIndex(-1f, 10))    // looping: wrapped
        assertEquals(0, ArchiveMath.focusIndex(3f, 0))
    }

    @Test fun dragRubberBandsOnlyWhenBounded() {
        assertEquals(1.5f, ArchiveMath.dragStep(1f, 0.5f, 4), eps)
        assertEquals(-0.35f, ArchiveMath.dragStep(0f, -1f, 4), eps)  // past the start resists
        assertEquals(3.35f, ArchiveMath.dragStep(3f, 1f, 4), eps)    // past the end resists
        assertEquals(-1f, ArchiveMath.dragStep(0f, -1f, 8), eps)     // loops: free
        assertEquals(0f, ArchiveMath.dragStep(2f, 1f, 0), eps)
    }

    @Test fun snapTarget() {
        assertEquals(1f, ArchiveMath.snapTarget(1.3f, 0f, 4), eps)
        assertEquals(3f, ArchiveMath.snapTarget(1f, 100f, 4), eps)   // clamped to the end
        assertEquals(0f, ArchiveMath.snapTarget(-0.3f, -50f, 4), eps)
        assertEquals(7f, ArchiveMath.snapTarget(1f, 1000f, 20), eps) // a fling moves at most 6 cards
        assertEquals(-5f, ArchiveMath.snapTarget(1f, -1000f, 20), eps)
        // projected position is rounded to a whole card
        val t = ArchiveMath.snapTarget(2f, 10f, 20)
        assertEquals(t, Math.round(t).toFloat(), eps)
        assertEquals(2f + Math.round(10f * ArchiveMath.FLING_PROJECTION), t, eps)
    }

    @Test fun clampPos() {
        assertEquals(2f, ArchiveMath.clampPos(5f, 3), eps)
        assertEquals(-4f, ArchiveMath.clampPos(-4f, 9), eps)
        assertEquals(0f, ArchiveMath.clampPos(5f, 0), eps)
    }

    @Test fun focusedCardIsFrontFacingAndLargest() {
        val l = ArchiveMath.layer(0f, tilt = true, out = ArchiveMath.Layer())
        assertEquals(0f, l.y, eps)
        assertEquals(ArchiveMath.FOCUS_SCALE, l.scale, eps)
        assertEquals(1f, l.alpha, eps)
        assertEquals(0f, l.rotationX, eps)
        assertEquals(0f, l.rotationY, eps)
        assertEquals(1f, l.elevation, eps)
    }

    @Test fun neighboursRecedeMonotonically() {
        val out = ArchiveMath.Layer()
        var prev = ArchiveMath.layer(0f, true, ArchiveMath.Layer())
        var d = 0.25f
        while (d <= ArchiveMath.MAX_DEPTH) {
            val l = ArchiveMath.layer(d, true, out)
            assertTrue("y grows at $d", l.y > prev.y)
            assertTrue("scale shrinks at $d", l.scale <= prev.scale)
            assertTrue("alpha fades at $d", l.alpha <= prev.alpha)
            assertTrue("elevation drops at $d", l.elevation <= prev.elevation)
            assertTrue("z drops at $d", l.z < prev.z)
            prev = ArchiveMath.Layer().also { it.y = l.y; it.scale = l.scale; it.alpha = l.alpha; it.elevation = l.elevation; it.z = l.z }
            d += 0.25f
        }
        val far = ArchiveMath.layer(10f, true, out)
        assertEquals(0f, far.elevation, eps)
        assertTrue(far.alpha in 0f..0.2f)
        assertTrue(far.scale >= 0.6f)
    }

    @Test fun layerIsSymmetric() {
        val a = ArchiveMath.layer(1.7f, true, ArchiveMath.Layer())
        val b = ArchiveMath.layer(-1.7f, true, ArchiveMath.Layer())
        assertEquals(a.y, -b.y, eps)
        assertEquals(a.scale, b.scale, eps)
        assertEquals(a.alpha, b.alpha, eps)
        assertEquals(a.rotationX, -b.rotationX, eps)
        assertEquals(a.rotationY, b.rotationY, eps)
        assertTrue(abs(a.rotationX) <= 34f && abs(a.rotationY) <= 12f)
    }

    @Test fun reducedMotionHasNoTilt() {
        for (d in listOf(-3f, -1f, 0.5f, 2f)) {
            val l = ArchiveMath.layer(d, tilt = false, out = ArchiveMath.Layer())
            assertEquals(0f, l.rotationX, eps)
            assertEquals(0f, l.rotationY, eps)
            assertEquals(ArchiveMath.layer(d, true, ArchiveMath.Layer()).scale, l.scale, eps)
        }
    }

    @Test fun waveStartsAtFocusAndRipplesOut() {
        assertEquals(0f, ArchiveMath.waveLift(0f, 0), eps)
        assertEquals(0f, ArchiveMath.waveLift(1f, 0), eps)
        // early on, only the focused card has lifted
        assertTrue(ArchiveMath.waveLift(0.1f, 0) < 0f)
        assertEquals(0f, ArchiveMath.waveLift(0.1f, 2), eps)
        // the far card peaks later and lower
        val peak0 = (1..99).minOf { ArchiveMath.waveLift(it / 100f, 0) }
        val peak3 = (1..99).minOf { ArchiveMath.waveLift(it / 100f, 3) }
        assertTrue(peak0 < peak3)
        assertEquals(-ArchiveMath.LIFT, peak0, 0.01f)
        assertEquals(1f, ArchiveMath.staggered(1f, ArchiveMath.MAX_HALF_WINDOW), eps)
        assertEquals(0f, ArchiveMath.staggered(ArchiveMath.WAVE_STAGGER, 1), eps)
    }

    @Test fun folderTransitFallsBackThenRises() {
        fun at(phase: Float, slot: Int) = ArchiveMath.transit(phase, slot, ArchiveMath.layer(slot.toFloat(), true, ArchiveMath.Layer()))
        val rest = ArchiveMath.layer(1f, true, ArchiveMath.Layer())
        val same = at(0f, 1)
        assertEquals(rest.y, same.y, eps); assertEquals(rest.alpha, same.alpha, eps)
        // fully fallen: invisible, smaller, lower
        val fallen = at(1f, 1)
        assertEquals(0f, fallen.alpha, eps)
        assertTrue(fallen.scale < rest.scale && fallen.y > rest.y)
        // rising: starts invisible below, nearer cards arrive first
        assertEquals(0f, at(-1f, 0).alpha, eps)
        assertTrue(at(-1f, 0).y > ArchiveMath.layer(0f, true, ArchiveMath.Layer()).y)
        assertTrue(at(-0.5f, 0).alpha > at(-0.5f, 3).alpha)
        // falling: the focused card leads
        assertTrue(at(0.3f, 0).alpha < 1f)
        assertEquals(ArchiveMath.layer(3f, true, ArchiveMath.Layer()).alpha, at(0.3f, 3).alpha, eps)
    }

    @Test fun sweepRevealsDiagonallyFromTopLeft() {
        val w = 300f; val h = 100f
        assertFalse(ArchiveMath.revealed(0f, 0f, 0f, w, h))
        assertTrue(ArchiveMath.revealed(0.5f, 10f, 10f, w, h))
        assertFalse(ArchiveMath.revealed(0.5f, 290f, 90f, w, h))
        assertTrue(ArchiveMath.revealed(1f, w - 1f, h - 1f, w, h))
        assertEquals(400f, ArchiveMath.sweepFront(1f, w, h), eps)
        assertEquals(0f, ArchiveMath.sweepFront(-1f, w, h), eps)
    }

    private fun area(pts: FloatArray, n: Int): Float {
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return abs(a) / 2f
    }

    @Test fun veilPolygonShrinksToNothing() {
        val w = 300f; val h = 100f
        val pts = FloatArray(12)
        val n0 = ArchiveMath.veilPolygon(0f, w, h, pts)
        assertEquals(4, n0)
        assertEquals(w * h, area(pts, n0), 0.5f)
        // half way: front = 200 → veil = rect minus the triangle/trapezoid x + y < 200
        val n1 = ArchiveMath.veilPolygon(0.5f, w, h, pts)
        assertTrue(n1 in 3..5)
        val revealed = 100f * 100f / 2f + 100f * 100f // trapezoid: x+y<200 inside 300×100
        assertEquals(w * h - revealed, area(pts, n1), 0.5f)
        for (i in 0 until n1) assertTrue(pts[i * 2] + pts[i * 2 + 1] >= 200f - 0.01f)
        assertEquals(0, ArchiveMath.veilPolygon(1f, w, h, pts))
        // monotonic
        var prev = Float.MAX_VALUE
        for (s in 0..20) {
            val n = ArchiveMath.veilPolygon(s / 20f, w, h, pts)
            val a = if (n == 0) 0f else area(pts, n)
            assertTrue(a <= prev + 0.01f); prev = a
        }
    }

    @Test fun blurStepsQuantised() {
        assertEquals(4, ArchiveMath.blurStep(0f, 5))
        assertEquals(0, ArchiveMath.blurStep(1f, 5))
        assertEquals(2, ArchiveMath.blurStep(0.5f, 5))
        assertEquals(0, ArchiveMath.blurStep(0.3f, 1))
        assertEquals(4, ArchiveMath.blurStep(-2f, 5))
    }

    @Test fun serialFormat() {
        assertEquals("ARC-0001", ArchiveMath.serial(0))
        assertEquals("ARC-0042", ArchiveMath.serial(41))
        assertEquals("ARC-9999", ArchiveMath.serial(9998))
        assertEquals("ARC-12345", ArchiveMath.serial(12344))
        assertEquals("ARC-0001", ArchiveMath.serial(-5))
    }

    @Test fun digitDecomposition() {
        assertArrayEquals(intArrayOf(0, 4, 2), ArchiveMath.digits(42, 3))
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), ArchiveMath.digits(12345, 2))
        assertArrayEquals(intArrayOf(0), ArchiveMath.digits(-3))
        // 9 → 10 keeps the ones digit in slot 0
        assertEquals(0, ArchiveMath.slotFromEnd(0, 1))
        assertEquals(0, ArchiveMath.slotFromEnd(1, 2))
        assertEquals(1, ArchiveMath.slotFromEnd(0, 2))
        assertTrue(ArchiveMath.rollsUp(3, 4))
        assertFalse(ArchiveMath.rollsUp(4, 3))
        assertTrue(ArchiveMath.rollsUp(9, 0))
        assertFalse(ArchiveMath.rollsUp(0, 9))
    }

    @Test fun kindsTagsAndPath() {
        assertEquals(ArchiveMath.Kind.SYSTEM, ArchiveMath.kind(true, true, ""))
        assertEquals(ArchiveMath.Kind.FOLDER, ArchiveMath.kind(true, false, ""))
        assertEquals(ArchiveMath.Kind.TEXT, ArchiveMath.kind(false, false, "MD"))
        assertEquals(ArchiveMath.Kind.MODEL, ArchiveMath.kind(false, false, "gguf"))
        assertEquals(ArchiveMath.Kind.MEDIA, ArchiveMath.kind(false, false, "png"))
        assertEquals(ArchiveMath.Kind.PACKAGE, ArchiveMath.kind(false, false, "zip"))
        assertEquals(ArchiveMath.Kind.OTHER, ArchiveMath.kind(false, false, "xyz"))
        assertEquals("SYS", ArchiveMath.typeTag(true, true, ""))
        assertEquals("DIR", ArchiveMath.typeTag(true, false, ""))
        assertEquals("FILE", ArchiveMath.typeTag(false, false, ""))
        assertEquals("GGUF", ArchiveMath.typeTag(false, false, "gguf"))
        assertEquals("SAFET", ArchiveMath.typeTag(false, false, "safetensors"))
        val e = FileEntry("a.md", "notes/a.md", isDir = false, size = 3, modified = 0)
        assertEquals("MD", ArchiveMath.typeTag(e))
        assertEquals("/IRIDIS", ArchiveMath.pathTag(""))
        assertEquals("/IRIDIS/models/qwen", ArchiveMath.pathTag("models/qwen/"))
    }

    @Test fun motionTokensLiveInIrisMotion() {
        assertTrue(IrisMotion.ARCHIVE_DECRYPT_MS in 300..1000)
        assertTrue(IrisMotion.ARCHIVE_FALL_MS < IrisMotion.ARCHIVE_RISE_MS)
        assertTrue(IrisMotion.ArchiveSnap.damping in 0.8f..1f)
    }
}

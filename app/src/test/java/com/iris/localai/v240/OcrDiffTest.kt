package com.iris.localai.v240

import com.iris.localai.agent.gui.OcrDiff
import com.iris.localai.agent.gui.OcrLine
import com.iris.localai.agent.gui.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrDiffTest {
    private val w = 240; private val h = 480
    private fun screen(fill: (Int, Int) -> Int = { _, _ -> 0xFFFFFFFF.toInt() }) = IntArray(w * h) { fill(it % w, it / w) }

    @Test fun firstAndResizedScreensAreFull() {
        val g = OcrDiff.grid(screen(), w, h)
        assertEquals(OcrDiff.Plan.Full, OcrDiff.plan(null, g))
        assertEquals(OcrDiff.Plan.Full, OcrDiff.plan(OcrDiff.grid(IntArray(100 * 100), 100, 100), g))
    }

    @Test fun sameScreenIsReused() {
        assertEquals(OcrDiff.Plan.Reuse, OcrDiff.plan(OcrDiff.grid(screen(), w, h), OcrDiff.grid(screen(), w, h)))
    }

    @Test fun smallChangeIsCroppedAroundIt() {
        val a = OcrDiff.grid(screen(), w, h)
        val b = OcrDiff.grid(screen { x, y -> if (x in 100..139 && y in 200..239) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, w, h)
        val p = OcrDiff.plan(a, b)
        assertTrue(p is OcrDiff.Plan.Crop)
        val r = (p as OcrDiff.Plan.Crop).rect
        assertTrue(r.left <= 100 && r.right >= 140 && r.top <= 200 && r.bottom >= 240)
        assertTrue(r.area < w.toLong() * h / 4)
    }

    @Test fun bigChangeIsFull() {
        val a = OcrDiff.grid(screen(), w, h)
        assertEquals(OcrDiff.Plan.Full, OcrDiff.plan(a, OcrDiff.grid(screen { _, _ -> 0xFF000000.toInt() }, w, h)))
    }

    @Test fun mergeKeepsLinesOutsideTheChange() {
        val keep = OcrLine("标题", PxRect(0, 0, 100, 40))
        val drop = OcrLine("旧", PxRect(110, 210, 130, 230))
        val fresh = OcrLine("新", PxRect(110, 210, 130, 230))
        assertEquals(listOf(keep, fresh), OcrDiff.merge(listOf(keep, drop), PxRect(100, 200, 140, 240), listOf(fresh)))
    }
}

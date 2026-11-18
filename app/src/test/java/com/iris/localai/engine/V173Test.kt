package com.iris.localai.engine

import com.iris.localai.engine.resources.CpuTopology
import com.iris.localai.engine.resources.ResourcePlanner
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.ui.chat.ResourcePopupMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.3: resource card placement and card text matching the real plan. */
class V173Test {
    private val gap = 21   // 8 dp @ 2.625
    private val margin = 32 // 12 dp
    private val w = 1080
    private val h = 2400

    @Test fun cardSitsAboveComposerAndInsideScreen() {
        // chip in the composer's bottom row, composer top at 2000 px, card 788 × 600 px
        val (x, y) = ResourcePopupMath.position(
            anchorLeft = 400, anchorTop = 2150, anchorBottom = 2230, avoidTop = 2000,
            windowW = w, windowH = h, popupW = 788, popupH = 600, gap = gap, margin = margin,
        )
        assertEquals(2000 - gap - 600, y)
        assertTrue(y + 600 <= 2000) // never covers the composer
        assertTrue(x >= margin)
        assertTrue(x + 788 <= w - margin) // never past the right edge
        assertEquals(w - 788 - margin, x)
    }

    @Test fun clampsLeftEdge() {
        val (x, _) = ResourcePopupMath.position(0, 2150, 2230, 2000, w, h, 788, 600, gap, margin)
        assertEquals(margin, x)
    }

    @Test fun goesBelowWhenItDoesNotFitAbove() {
        val (_, y) = ResourcePopupMath.position(100, 300, 380, Int.MAX_VALUE, w, h, 788, 600, gap, margin)
        assertEquals(380 + gap, y)
        // and is clamped to the bottom of the window
        val (_, y2) = ResourcePopupMath.position(100, 300, 2300, Int.MAX_VALUE, w, 2400, 788, 600, gap, margin)
        assertEquals(2400 - 600, y2)
    }

    @Test fun narrowScreenStillStartsAtMargin() {
        val (x, _) = ResourcePopupMath.position(200, 2150, 2230, 2000, 700, h, 700, 600, gap, margin)
        assertEquals(margin, x)
    }

    @Test fun highTierTextMatchesPlanOnTwoClusterChip() {
        // Snapdragon 8 Elite-like: 6 performance + 2 prime cores, no efficiency cores.
        // 1.7.6: 高 = 2 threads on the 2 prime cores; 省电 = 1 thread on one performance core.
        val topo = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))
        val high = ResourcePlanner.plan(ResourceTier.HIGH, topo)
        assertEquals(6, high.threads) // 2.0.3: prime + fastest performance cores
        assertTrue(high.cpus.containsAll(listOf(6, 7)))
        assertFalse(ResourceTier.HIGH.hint.startsWith("性能核"))
        assertTrue(ResourceTier.HIGH.hint.contains("较快的核心"))
        // the slower cluster on such a chip is not efficiency cores
        assertEquals("性能核 ×1", ResourcePlanner.plan(ResourceTier.LOW, topo).coreLabel)
        assertFalse(ResourcePlanner.plan(ResourceTier.LOW, topo).coreLabel.contains("能效核"))
    }

    @Test fun threeClusterLabelsUnchanged() {
        val topo = CpuTopology(listOf(listOf(0, 1), listOf(2, 3, 4, 5, 6), listOf(7)))
        assertEquals("能效核 ×2", ResourcePlanner.plan(ResourceTier.LOW, topo).coreLabel)
        assertEquals("性能核 ×6", ResourcePlanner.plan(ResourceTier.HIGH, topo).coreLabel)
    }
}

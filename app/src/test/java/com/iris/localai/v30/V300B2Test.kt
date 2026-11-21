package com.iris.localai.v30

import com.iris.localai.data.HistoryPress
import com.iris.localai.floating.FloatingLogic
import com.iris.localai.ui.motion.PressMotion
import com.iris.localai.ui.settings.AboutVersionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V300B2Test {
    @Test fun versionPartsDropFlavorAndSplitStage() {
        assertEquals("3.0.0" to "Beta 2", AboutVersionText.parts("3.0.0 Beta2-full"))
        assertEquals("3.0.0" to "Beta 1", AboutVersionText.parts("3.0.0 Beta1-lite"))
        val (n, st) = AboutVersionText.parts("2.7.6-full")
        assertEquals("2.7.6", n); assertNull(st)
    }

    @Test fun bubbleDocksAndPeeks() {
        assertTrue(FloatingLogic.dockLeft(100, 120, 1080))
        assertFalse(FloatingLogic.dockLeft(900, 120, 1080))
        assertEquals(-60, FloatingLogic.edgeX(true, 120, 1080, 20, peek = true))
        assertEquals(20, FloatingLogic.edgeX(true, 120, 1080, 20, peek = false))
        assertEquals(1020, FloatingLogic.edgeX(false, 120, 1080, 20, peek = true))
        assertEquals(940, FloatingLogic.edgeX(false, 120, 1080, 20, peek = false))
        assertTrue(FloatingLogic.inDismiss(540f, 2000f, 540f, 2030f, 64f))
        assertFalse(FloatingLogic.inDismiss(540f, 1800f, 540f, 2030f, 64f))
    }

    @Test fun iconTargetsGetRoundHighlight() {
        assertTrue(PressMotion.isIconLike(48f, 48f))
        assertTrue(PressMotion.isIconLike(56f, 44f))
        assertFalse(PressMotion.isIconLike(360f, 56f))
        assertFalse(PressMotion.isIconLike(0f, 10f))
    }

    @Test fun dragTiltIsBounded() {
        assertEquals(0f, HistoryPress.tiltFor(0f), 0f)
        assertEquals(2.5f, HistoryPress.tiltFor(400f), 0f)
        assertEquals(-2.5f, HistoryPress.tiltFor(-400f), 0f)
    }

    @Test fun manualPeekAndMemoryMargin() {
        assertTrue(FloatingLogic.pushedOut(-40, 120, 1080))
        assertFalse(FloatingLogic.pushedOut(-10, 120, 1080))
        assertTrue(FloatingLogic.pushedOut(1000, 120, 1080))
        val gb = 1024L * 1024 * 1024
        // 约需比可用多 14%（截图里 8.2 / 7.2）：不再提示
        assertTrue(com.iris.localai.engine.load.MemoryPreflight.check(72 * gb / 10, 1L, 4096, false, needOverride = 82 * gb / 10).level == com.iris.localai.engine.load.MemoryPreflight.Level.OK)
    }
}

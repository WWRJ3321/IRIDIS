package com.iris.localai.voice

import com.iris.localai.ui.chat.VoiceCapsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.7 #5: long-press dictation with the keyboard open — a layout shift is not a slide up. */
class VoiceCapsuleSlideTest {
    private val density = 3f

    @Test fun keyboardClosingUnderAStillFingerDoesNotCancel() {
        // Capsule top at y=1500 in the window, finger 20 px into it. The keyboard hides: the capsule
        // drops 300 px, the finger stays put on screen → its local y is now 20 - 300.
        assertEquals(0f, VoiceCapsule.slideUpPx(20f, 1500f, -280f, 1800f), 0.001f)
        assertFalse(VoiceCapsule.cancelArmed(20f, 1500f, -280f, 1800f, density))
        // The old local-only reading saw a 300 px slide up (≥ 56 dp at 3x) and cancelled.
        assertTrue(VoiceCapsule.cancelArmed(20f - (-280f), density))
    }

    @Test fun realSlideUpStillCancels() {
        val up = 120f * density
        // Capsule did not move; the finger went 120 dp up.
        assertTrue(VoiceCapsule.cancelArmed(20f, 1500f, 20f - up, 1500f, density))
        // Capsule moved down 300 px AND the finger went 120 dp up on screen.
        assertTrue(VoiceCapsule.cancelArmed(20f, 1500f, 20f - 300f - up, 1800f, density))
        assertEquals(up, VoiceCapsule.slideUpPx(20f, 1500f, 20f - 300f - up, 1800f), 0.001f)
    }

    @Test fun smallWobbleAndDownwardMovesDoNotCancel() {
        assertFalse(VoiceCapsule.cancelArmed(20f, 1500f, 20f - 10f * density, 1500f, density))
        assertFalse(VoiceCapsule.cancelArmed(20f, 1500f, 200f, 1500f, density))
        // Keyboard opening (capsule moves UP 300) with a still finger is not a slide either.
        assertFalse(VoiceCapsule.cancelArmed(20f, 1500f, 320f, 1200f, density))
    }

}

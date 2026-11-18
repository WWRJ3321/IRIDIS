package com.iris.localai.ui

import android.app.NotificationManager
import android.media.AudioManager
import com.iris.localai.data.AppSettings
import com.iris.localai.ui.feedback.FeedbackEnv
import com.iris.localai.ui.feedback.FeedbackEvent
import com.iris.localai.ui.feedback.FeedbackSound
import com.iris.localai.ui.feedback.HapticKind
import com.iris.localai.ui.feedback.IrisFeedbackPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V210FeedbackTest {
    private val all = FeedbackEnv()

    @Test fun mappingCoversEveryEvent() {
        val expected = mapOf(
            FeedbackEvent.SEND to (HapticKind.LIGHT to FeedbackSound.SEND),
            FeedbackEvent.REPLY_DONE to (HapticKind.CONFIRM to FeedbackSound.DONE),
            FeedbackEvent.LISTEN_START to (HapticKind.GESTURE_START to null),
            FeedbackEvent.LISTEN_STOP to (HapticKind.GESTURE_END to null),
            FeedbackEvent.BARGE_IN to (HapticKind.TICK to null),
            FeedbackEvent.STEP_DONE to (HapticKind.LIGHT to FeedbackSound.TICK),
            FeedbackEvent.ERROR to (HapticKind.REJECT to FeedbackSound.ERROR),
            FeedbackEvent.TOGGLE to (HapticKind.LIGHT to null),
            FeedbackEvent.TICK to (HapticKind.TICK to null),
            FeedbackEvent.LONG_PRESS to (HapticKind.LONG_PRESS to null),
        )
        assertEquals(FeedbackEvent.entries.toSet(), expected.keys)
        for ((e, hs) in expected) {
            val s = IrisFeedbackPolicy.spec(e)
            assertEquals("haptic of $e", hs.first, s.haptic)
            assertEquals("sound of $e", hs.second, s.sound)
        }
        // every sound clip is used by some event
        assertEquals(FeedbackSound.entries.toSet(), FeedbackEvent.entries.mapNotNull { IrisFeedbackPolicy.spec(it).sound }.toSet())
    }

    @Test fun everythingOnPlaysBoth() {
        val p = IrisFeedbackPolicy.plan(FeedbackEvent.SEND, all)
        assertEquals(HapticKind.LIGHT, p.haptic)
        assertEquals(FeedbackSound.SEND, p.sound)
    }

    @Test fun soundGates_leaveHapticOnly() {
        val off = listOf(
            all.copy(soundOn = false),
            all.copy(ringerNormal = false),
            all.copy(dndOff = false),
            all.copy(voiceActive = true),
        )
        for (env in off) {
            assertFalse(IrisFeedbackPolicy.soundAllowed(env))
            for (e in FeedbackEvent.entries) {
                val p = IrisFeedbackPolicy.plan(e, env)
                assertNull("$e sound with $env", p.sound)
                assertEquals(IrisFeedbackPolicy.spec(e).haptic, p.haptic)
            }
        }
    }

    @Test fun hapticsOff_keepsSound() {
        val p = IrisFeedbackPolicy.plan(FeedbackEvent.REPLY_DONE, all.copy(hapticsOn = false))
        assertNull(p.haptic)
        assertEquals(FeedbackSound.DONE, p.sound)
        assertTrue(IrisFeedbackPolicy.plan(FeedbackEvent.TOGGLE, all.copy(hapticsOn = false)).isEmpty)
        assertTrue(IrisFeedbackPolicy.plan(FeedbackEvent.SEND, all.copy(hapticsOn = false, soundOn = false)).isEmpty)
    }

    @Test fun systemStateMapping() {
        assertTrue(IrisFeedbackPolicy.ringerNormal(AudioManager.RINGER_MODE_NORMAL))
        assertFalse(IrisFeedbackPolicy.ringerNormal(AudioManager.RINGER_MODE_VIBRATE))
        assertFalse(IrisFeedbackPolicy.ringerNormal(AudioManager.RINGER_MODE_SILENT))
        assertTrue(IrisFeedbackPolicy.dndOff(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertTrue(IrisFeedbackPolicy.dndOff(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertFalse(IrisFeedbackPolicy.dndOff(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertFalse(IrisFeedbackPolicy.dndOff(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertFalse(IrisFeedbackPolicy.dndOff(NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    @Test fun throttleOnlySoundingEvents() {
        assertFalse(IrisFeedbackPolicy.throttled(FeedbackEvent.STEP_DONE, null, 1000))
        assertTrue(IrisFeedbackPolicy.throttled(FeedbackEvent.STEP_DONE, 900, 1000))
        assertFalse(IrisFeedbackPolicy.throttled(FeedbackEvent.STEP_DONE, 1000 - IrisFeedbackPolicy.THROTTLE_MS, 1000))
        assertFalse(IrisFeedbackPolicy.throttled(FeedbackEvent.TICK, 990, 1000))
    }

    @Test fun settingsDefaultsAndOldJson() {
        val d = AppSettings()
        assertTrue(d.haptics)
        assertTrue(d.sound)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"themeMode":"DARK"}""")
        assertTrue(old.haptics && old.sound)
    }

    @Test fun soundsAreTinyRawClips() {
        val raw = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
        for (n in listOf("iris_send", "iris_done", "iris_tick", "iris_error")) {
            val f = File(raw, "$n.ogg")
            assertTrue("$n exists", f.isFile)
            assertTrue("$n is small", f.length() in 1..16_000)
        }
    }

}

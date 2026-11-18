package com.iris.localai.voice

import android.media.AudioDeviceInfo
import com.iris.localai.voice.EchoTextGuard
import com.iris.localai.voice.HalfDuplexGate
import com.iris.localai.voice.MicAction
import com.iris.localai.voice.OutputRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfDuplexGateTest {
    private val spk = OutputRoute.LOUDSPEAKER
    private val priv = OutputRoute.PRIVATE

    @Test fun idleListens() {
        val g = HalfDuplexGate()
        assertEquals(MicAction.LISTEN, g.decide(0, replying = false, audible = false, route = spk))
    }

    @Test fun loudspeakerPlaybackMutesMic() {
        val g = HalfDuplexGate()
        for (t in 0L..3000L step 32) assertEquals(MicAction.MUTED, g.decide(t, replying = true, audible = true, route = spk))
    }

    @Test fun privateRouteKeepsBargeIn() {
        val g = HalfDuplexGate()
        assertEquals(MicAction.BARGE_IN_ONLY, g.decide(0, replying = true, audible = true, route = priv))
    }

    @Test fun thinkingWithoutAudioAllowsBargeIn() {
        val g = HalfDuplexGate()
        assertEquals(MicAction.BARGE_IN_ONLY, g.decide(0, replying = true, audible = false, route = spk))
    }

    @Test fun tailKeepsMicClosedAfterPlayback() {
        val g = HalfDuplexGate(tailMs = 450)
        g.decide(1000, replying = true, audible = true, route = spk)
        assertEquals(MicAction.MUTED, g.decide(1032, replying = false, audible = false, route = spk))
        assertEquals(MicAction.MUTED, g.decide(1400, replying = false, audible = false, route = spk))
        assertEquals(MicAction.LISTEN, g.decide(1483, replying = false, audible = false, route = spk))
    }

    @Test fun tailAppliesOnPrivateRouteToo() {
        val g = HalfDuplexGate(tailMs = 450)
        g.decide(0, replying = true, audible = true, route = priv)
        assertEquals(MicAction.MUTED, g.decide(100, replying = false, audible = false, route = priv))
        assertEquals(MicAction.LISTEN, g.decide(560, replying = false, audible = false, route = priv))
    }

    @Test fun residualAudioExtendsTailButIsCapped() {
        val g = HalfDuplexGate(tailMs = 400, maxResidualMs = 1500)
        g.decide(0, replying = true, audible = true, route = spk)
        assertEquals(MicAction.MUTED, g.decide(10, replying = false, audible = false, route = spk, residualMs = 600))
        assertEquals(MicAction.MUTED, g.decide(900, replying = false, audible = false, route = spk))
        assertEquals(MicAction.LISTEN, g.decide(1011, replying = false, audible = false, route = spk))

        val h = HalfDuplexGate(tailMs = 400, maxResidualMs = 1500)
        h.decide(0, replying = true, audible = true, route = spk)
        h.decide(0, replying = false, audible = false, route = spk, residualMs = 60_000) // stalled head
        assertEquals(MicAction.LISTEN, h.decide(1901, replying = false, audible = false, route = spk))
    }

    @Test fun residualOnlyCountsAtPlaybackEnd() {
        val g = HalfDuplexGate(tailMs = 400)
        g.decide(0, replying = true, audible = true, route = spk)
        g.decide(10, replying = false, audible = false, route = spk)
        // later residual readings (stale queue) must not re-close an opened gate
        assertEquals(MicAction.LISTEN, g.decide(500, replying = false, audible = false, route = spk, residualMs = 1000))
    }

    @Test fun newReplyRecloses() {
        val g = HalfDuplexGate()
        g.decide(0, replying = true, audible = true, route = spk)
        g.decide(100, replying = false, audible = false, route = spk)
        assertEquals(MicAction.LISTEN, g.decide(2000, replying = false, audible = false, route = spk))
        assertEquals(MicAction.MUTED, g.decide(3000, replying = true, audible = true, route = spk))
    }

    @Test fun routeSwitchMidReply() {
        val g = HalfDuplexGate()
        assertEquals(MicAction.BARGE_IN_ONLY, g.decide(0, replying = true, audible = true, route = priv))
        assertEquals(MicAction.MUTED, g.decide(32, replying = true, audible = true, route = spk)) // headset unplugged
    }

    @Test fun resetOpensImmediately() {
        val g = HalfDuplexGate()
        g.decide(0, replying = true, audible = true, route = spk)
        g.decide(10, replying = false, audible = false, route = spk)
        g.reset()
        assertEquals(MicAction.LISTEN, g.decide(20, replying = false, audible = false, route = spk))
    }

    @Test fun outputRouteClassification() {
        assertEquals(OutputRoute.LOUDSPEAKER, OutputRoute.of(null))
        assertEquals(OutputRoute.LOUDSPEAKER, OutputRoute.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(OutputRoute.LOUDSPEAKER, OutputRoute.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertEquals(OutputRoute.LOUDSPEAKER, OutputRoute.of(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputRoute.PRIVATE, OutputRoute.of(AudioDeviceInfo.TYPE_BLE_HEADSET))
    }
}

class EchoTextGuardTest {
    private val reply = "今天天气晴朗，最高气温二十五度。你想出去走走吗？"

    @Test fun normalizeDropsPunctuationAndCase() {
        assertEquals("hello世界123", EchoTextGuard.normalize(" Hello，世界！ 1２3 "))
        assertEquals("", EchoTextGuard.normalize("。。，？！ "))
    }

    @Test fun similarityExactSubstringIsOne() {
        assertEquals(1f, EchoTextGuard.similarity("最高气温", "今天天气晴朗最高气温二十五度"), 1e-6f)
    }

    @Test fun similarityToleratesAsrErrors() {
        // one substituted char out of 8
        val s = EchoTextGuard.similarity("最高汽温二十五度", "今天天气晴朗最高气温二十五度")
        assertEquals(0.875f, s, 1e-6f)
    }

    @Test fun similarityUnrelatedIsLow() {
        assertTrue(EchoTextGuard.similarity("帮我定个闹钟", "今天天气晴朗最高气温二十五度") < 0.4f)
        assertEquals(0f, EchoTextGuard.similarity("", "abc"), 0f)
        assertEquals(0f, EchoTextGuard.similarity("abc", ""), 0f)
    }

    @Test fun dropsEchoOfJustSpokenSentence() {
        val g = EchoTextGuard()
        g.remember(reply, 1_000)
        assertTrue(g.isEcho("今天天气晴朗最高气温25度", 2_000) || g.isEcho("今天天气晴朗，最高气温二十五度", 2_000))
        assertTrue(g.isEcho("你想出去走走吗", 2_000))
        assertTrue(g.isEcho("你想出去走走嘛？", 2_000)) // ASR mishears one char
    }

    @Test fun keepsGenuineUserReplies() {
        val g = EchoTextGuard()
        g.remember(reply, 1_000)
        assertFalse(g.isEcho("好啊我们去公园吧", 2_000))
        assertFalse(g.isEcho("明天会下雨吗", 2_000))
        assertFalse(g.isEcho("走走吧", 2_000))   // shorter than minChars: never dropped
        assertFalse(g.isEcho("好的", 2_000))
    }

    @Test fun windowExpires() {
        val g = EchoTextGuard(windowMs = 10_000)
        g.remember(reply, 1_000)
        assertTrue(g.isEcho("你想出去走走吗", 10_999))
        assertFalse(g.isEcho("你想出去走走吗", 11_001))
        g.touch(20_000) // playback activity refreshes it
        assertTrue(g.isEcho("你想出去走走吗", 25_000))
    }

    @Test fun emptyOrClearedGuardDropsNothing() {
        val g = EchoTextGuard()
        assertFalse(g.isEcho("你想出去走走吗", 0))
        g.remember(reply, 0)
        g.clear()
        assertFalse(g.isEcho("你想出去走走吗", 1))
    }

    @Test fun englishEcho() {
        val g = EchoTextGuard()
        g.remember("Sure! The meeting starts at three o'clock.", 0)
        assertTrue(g.isEcho("the meeting starts at three", 500))
        assertFalse(g.isEcho("cancel the meeting please", 500))
    }

    @Test fun keepsOnlyRecentText() {
        val g = EchoTextGuard()
        g.remember("最开始说过的一句话内容", 0)
        repeat(100) { g.remember("填充文本内容不相关的句子", 0) } // > MAX_CHARS
        assertFalse(g.isEcho("最开始说过的一句话", 1))
        assertTrue(g.isEcho("填充文本内容", 1))
    }
}

package com.iris.localai.v219

import com.iris.localai.voice.HalfDuplexGate
import com.iris.localai.voice.MicAction
import com.iris.localai.voice.OutputRoute
import com.iris.localai.voice.PreOpenBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.9 feat/voice-echo: shorter half-duplex tail + pre-open buffer replayed when the gate opens. */
class V219EchoGateTest {
    private val spk = OutputRoute.LOUDSPEAKER
    private fun f(v: Float) = floatArrayOf(v)

    @Test fun shorterTailConstants() {
        assertEquals(200L, HalfDuplexGate.TAIL_MS)          // was 450
        assertEquals(600L, HalfDuplexGate.MAX_RESIDUAL_MS)  // was 1500
        assertEquals(300L, HalfDuplexGate.PREOPEN_MS)
        assertEquals(10, HalfDuplexGate.PREOPEN_FRAMES)     // 10 × 32 ms ≥ 300 ms
        assertTrue(HalfDuplexGate.PREOPEN_SETTLE_MS in 1L until HalfDuplexGate.TAIL_MS)
    }

    @Test fun defaultTailOpensAfter200ms() {
        val g = HalfDuplexGate()
        g.decide(1000, replying = true, audible = true, route = spk)
        assertEquals(MicAction.MUTED, g.decide(1032, replying = false, audible = false, route = spk))
        assertEquals(MicAction.MUTED, g.decide(1200, replying = false, audible = false, route = spk))
        assertEquals(MicAction.LISTEN, g.decide(1233, replying = false, audible = false, route = spk))
    }

    @Test fun defaultResidualCappedAt600() {
        val g = HalfDuplexGate()
        g.decide(0, replying = true, audible = true, route = spk)
        g.decide(0, replying = false, audible = false, route = spk, residualMs = 60_000) // stalled head
        assertEquals(MicAction.MUTED, g.decide(799, replying = false, audible = false, route = spk))
        assertEquals(MicAction.LISTEN, g.decide(800, replying = false, audible = false, route = spk))
    }

    @Test fun echoFreeOnlyAfterPlaybackResidualAndSettle() {
        val g = HalfDuplexGate(tailMs = 200, maxResidualMs = 600, settleMs = 64)
        assertTrue(g.echoFree(0))                    // idle
        g.decide(1000, replying = true, audible = true, route = spk)
        assertFalse(g.echoFree(1000))                // playing
        g.decide(1010, replying = false, audible = false, route = spk, residualMs = 100)
        assertFalse(g.echoFree(1010))                // queued audio still coming out
        assertFalse(g.echoFree(1173))
        assertTrue(g.echoFree(1174))                 // 1010 + 100 + 64
        g.decide(2000, replying = true, audible = true, route = spk)
        assertFalse(g.echoFree(2000))                // new reply → not echo-free again
        g.reset()
        assertTrue(g.echoFree(2001))
    }

    @Test fun ringKeepsNewestFramesUpToCapacity() {
        val b = PreOpenBuffer(capacityFrames = 3)
        for (i in 1..5) b.onMutedFrame(f(i.toFloat()), echoFree = true)
        assertEquals(3, b.size)
        assertEquals(listOf(3f, 4f, 5f), b.drain().map { it[0] })
        assertEquals(0, b.size)                      // drained
        assertTrue(b.drain().isEmpty())
    }

    @Test fun echoFrameEmptiesRing() {
        val b = PreOpenBuffer(capacityFrames = 10)
        b.onMutedFrame(f(1f), echoFree = true)
        b.onMutedFrame(f(2f), echoFree = false)      // may hold our TTS: nothing before it survives
        assertEquals(0, b.size)
        b.onMutedFrame(f(3f), echoFree = true)
        assertEquals(listOf(3f), b.drain().map { it[0] })
    }

    /** Frame-by-frame simulation of the controller's use of gate + buffer. */
    @Test fun gateOpenReplaysOnlyPostPlaybackTailAudio() {
        val g = HalfDuplexGate()
        val b = PreOpenBuffer()
        val replayed = ArrayList<Float>()
        var prev = MicAction.LISTEN
        var t = 0L
        var opened = false
        // 1 s of TTS (frames tagged -1), then silence/user speech (frames tagged by time)
        while (t < 2000 && !opened) {
            val audible = t < 1000
            val a = g.decide(t, replying = audible, audible = audible, route = spk)
            val frame = f(if (audible) -1f else t.toFloat())
            if (a == MicAction.MUTED) {
                if (prev != MicAction.MUTED) b.clear()
                b.onMutedFrame(frame, echoFree = !audible && g.echoFree(t))
            } else if (prev == MicAction.MUTED) {
                b.drain().forEach { replayed.add(it[0]) }
                opened = true
            }
            prev = a
            t += 32
        }
        assertTrue(opened)
        assertTrue(replayed.isNotEmpty())                        // the tail audio is not thrown away
        assertTrue(replayed.none { it < 0f })                    // never TTS-time frames
        val end = 1024f                                          // first non-audible frame time
        assertTrue(replayed.all { it >= end + HalfDuplexGate.PREOPEN_SETTLE_MS }) // past settle
        assertTrue(replayed.size <= HalfDuplexGate.PREOPEN_FRAMES)
        assertEquals(replayed.sorted(), replayed)                // oldest first
    }

    @Test fun controllerReplaysBufferOnOpen() {
        val file = File("src/main/java/com/iris/localai/voice/VoiceController.kt").let { if (it.exists()) it else File("app/${it.path}") }
        val src = file.readText()
        assertTrue(src.contains("val preOpen = PreOpenBuffer()"))
        assertTrue(src.contains("preOpen.onMutedFrame(frame, echoFree = !audible && gate.echoFree(now))"))
        assertTrue(src.contains("for (f in preOpen.drain()) { vad.acceptWaveform(f); preroll.add(f) }"))
    }
}

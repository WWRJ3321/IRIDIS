package com.iris.localai.voice

import android.media.AudioDeviceInfo
import java.text.Normalizer

/**
 * 2.1.8 echo protection for voice call mode (pure logic, unit-tested).
 *
 * On the loudspeaker the microphone hears our own TTS. The echo canceller (VOICE_COMMUNICATION
 * source + AEC) removes only part of it, and on Xiaomi phones barely touches media playback, so
 * the barge-in detector could fire on the reply itself and the recognised echo came back as a
 * "user" turn: the assistant answered itself in a loop (自问自答).
 *
 * Two independent layers:
 *  1. [HalfDuplexGate]: while the reply is audible on the loudspeaker (plus a short tail), the
 *     microphone is not fed to VAD / ASR at all, and voice barge-in is off (tap still interrupts).
 *     On a private route (earpiece, wired / Bluetooth / USB headset) the speaker cannot reach the
 *     mic, so barge-in by voice keeps working as before.
 *     2.1.9: shorter tail, and the last ~300 ms heard after playback (inside the tail) is replayed
 *     into VAD when the gate opens ([PreOpenBuffer]), so the first syllable is not swallowed.
 *  2. [EchoTextGuard]: a recognised text that closely matches what TTS just said is dropped.
 */

/** Where the reply is playing. Anything not known to be private counts as the loudspeaker. */
enum class OutputRoute {
    LOUDSPEAKER, PRIVATE;

    companion object {
        private val PRIVATE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        /** [deviceType] is an AudioDeviceInfo.TYPE_*; null (not routed yet / unknown) → loudspeaker. */
        fun of(deviceType: Int?): OutputRoute =
            if (deviceType != null && deviceType in PRIVATE_TYPES) PRIVATE else LOUDSPEAKER
    }
}

/** What to do with one microphone frame. */
enum class MicAction {
    /** Normal listening: feed VAD → ASR. */
    LISTEN,
    /** The assistant is replying: frames only feed the barge-in detector (existing behaviour). */
    BARGE_IN_ONLY,
    /** Half-duplex: our own voice may be in the mic. Do not feed VAD / ASR / barge-in at all. */
    MUTED,
}

/**
 * Half-duplex gate. Call [decide] once per 32 ms mic frame.
 *
 * - [audible]: our TTS is (or may still be) coming out of the speaker — phase SPEAKING or a
 *   non-zero playback level.
 * - [replying]: the assistant owns the turn (THINKING or SPEAKING).
 * - [residualMs]: audio still queued in the track when playback ended; added to the tail (capped)
 *   so a drain that gave up early (stalled head, route change) cannot open the mic on its own voice.
 */
class HalfDuplexGate(
    val tailMs: Long = TAIL_MS,
    val maxResidualMs: Long = MAX_RESIDUAL_MS,
    val settleMs: Long = PREOPEN_SETTLE_MS,
) {
    private var wasAudible = false
    private var closedUntil = 0L
    /** 2.1.9: from here on the mic no longer hears our playback (queued residual played + settle). */
    private var echoFreeFrom = 0L

    fun decide(nowMs: Long, replying: Boolean, audible: Boolean, route: OutputRoute, residualMs: Long = 0L): MicAction {
        if (audible) {
            wasAudible = true
            return if (route == OutputRoute.PRIVATE) MicAction.BARGE_IN_ONLY else MicAction.MUTED
        }
        if (wasAudible) {
            wasAudible = false
            val residual = residualMs.coerceIn(0L, maxResidualMs)
            closedUntil = nowMs + tailMs + residual
            echoFreeFrom = nowMs + residual + settleMs
        }
        if (nowMs < closedUntil) return MicAction.MUTED
        return if (replying) MicAction.BARGE_IN_ONLY else MicAction.LISTEN
    }

    /**
     * 2.1.9: true when a frame captured at [nowMs] is past the end of our playback (still queued
     * audio + [settleMs] of output latency). Only such frames may go into the [PreOpenBuffer].
     */
    fun echoFree(nowMs: Long): Boolean = !wasAudible && nowMs >= echoFreeFrom

    fun reset() { wasAudible = false; closedUntil = 0L; echoFreeFrom = 0L }

    companion object {
        /**
         * Room echo / output latency after the last sample left the track.
         * 2.1.9: 450 → 200 ms (450 swallowed the first syllable of a quick answer).
         */
        const val TAIL_MS = 200L
        /** 2.1.9: 1500 → 600 ms cap on the still-queued audio added to the tail. */
        const val MAX_RESIDUAL_MS = 600L
        /** 2.1.9: output latency of the last played sample; frames before this are never buffered. */
        const val PREOPEN_SETTLE_MS = 64L
        /** 2.1.9: how much audio from just before the gate opens is replayed into VAD / ASR. */
        const val PREOPEN_MS = 300L
        /** [PREOPEN_MS] in whole 32 ms mic frames (VAD_WINDOW samples at 16 kHz), rounded up. */
        val PREOPEN_FRAMES: Int = ((PREOPEN_MS * ASR_SAMPLE_RATE / 1000 + VAD_WINDOW - 1) / VAD_WINDOW).toInt()
    }
}

/**
 * 2.1.9: short ring of mic frames heard while the half-duplex gate is still closed, so a user who
 * starts talking inside the tail does not lose the first syllable. When the gate opens the frames
 * are replayed into VAD (and on into ASR) ahead of the live audio, instead of the VAD starting
 * from nothing.
 *
 * Only echo-free frames are kept (after playback and its settle time, see [HalfDuplexGate.echoFree]):
 * a frame that may contain our own TTS empties the ring, so playback audio is never replayed.
 */
class PreOpenBuffer(val capacityFrames: Int = HalfDuplexGate.PREOPEN_FRAMES) {
    private val frames = ArrayDeque<FloatArray>(capacityFrames)

    val size: Int get() = frames.size

    /** A frame the gate muted. [echoFree] false → our voice may be in it: drop everything. */
    fun onMutedFrame(frame: FloatArray, echoFree: Boolean) {
        if (!echoFree) { frames.clear(); return }
        if (capacityFrames <= 0) return
        if (frames.size == capacityFrames) frames.removeFirst()
        frames.addLast(frame)
    }

    /** The buffered frames, oldest first; the buffer is empty afterwards. */
    fun drain(): List<FloatArray> = frames.toList().also { frames.clear() }

    fun clear() = frames.clear()
}

/**
 * Drops recognised text that is (a near copy of) something TTS said recently. Echo is usually a
 * fragment of the reply, so the score is approximate-substring similarity: 1 − (edit distance of
 * the heard text to its best-matching window in the spoken text) / heard length.
 */
class EchoTextGuard(
    val threshold: Float = THRESHOLD,
    val windowMs: Long = WINDOW_MS,
    val minChars: Int = MIN_CHARS,
    private val maxChars: Int = MAX_CHARS,
) {
    private val spoken = StringBuilder()
    private var lastActiveMs = Long.MIN_VALUE

    /** A sentence handed to TTS. */
    fun remember(text: String, nowMs: Long) {
        val n = normalize(text)
        if (n.isNotEmpty()) {
            spoken.append(n)
            if (spoken.length > maxChars) spoken.delete(0, spoken.length - maxChars)
        }
        lastActiveMs = nowMs
    }

    /** Playback still running / just ended: keeps the remembered text fresh. */
    fun touch(nowMs: Long) { lastActiveMs = nowMs }

    fun clear() { spoken.setLength(0); lastActiveMs = Long.MIN_VALUE }

    fun isEcho(heard: String, nowMs: Long): Boolean {
        if (spoken.isEmpty() || lastActiveMs == Long.MIN_VALUE || nowMs - lastActiveMs > windowMs) return false
        val h = normalize(heard)
        if (h.length < minChars) return false
        return similarity(h, spoken) >= threshold
    }

    companion object {
        const val THRESHOLD = 0.75f
        /** Remembered TTS text counts for this long after the last playback activity. */
        const val WINDOW_MS = 10_000L
        /** Shorter texts (好的 / 对 / 嗯) always pass: too ambiguous to call echo. */
        const val MIN_CHARS = 4
        const val MAX_CHARS = 800

        /** NFKC, lower case, letters and digits only (punctuation / spaces / emoji removed). */
        fun normalize(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
            val b = StringBuilder(n.length)
            var i = 0
            while (i < n.length) {
                val cp = n.codePointAt(i)
                if (Character.isLetterOrDigit(cp)) b.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            return b.toString()
        }

        /**
         * Approximate-substring similarity of [heard] inside [spoken] (both already normalised),
         * 0..1. Sellers' algorithm: free start and end in [spoken], O(|heard|·|spoken|).
         */
        fun similarity(heard: CharSequence, spoken: CharSequence): Float {
            val m = heard.length
            if (m == 0) return 0f
            if (spoken.isEmpty()) return 0f
            var prev = IntArray(m + 1) { it }   // column for spoken prefix of length 0
            var cur = IntArray(m + 1)
            var best = prev[m]
            for (j in 1..spoken.length) {
                cur[0] = 0 // match may start anywhere in spoken
                val c = spoken[j - 1]
                for (i in 1..m) {
                    val cost = if (heard[i - 1] == c) 0 else 1
                    cur[i] = minOf(prev[i - 1] + cost, prev[i] + 1, cur[i - 1] + 1)
                }
                if (cur[m] < best) best = cur[m]
                val t = prev; prev = cur; cur = t
            }
            return (1f - best.toFloat() / m).coerceIn(0f, 1f)
        }
    }
}

package com.iris.localai.voice

import android.util.Log
import com.iris.localai.data.SettingsRepository
import com.iris.localai.util.SentenceSplitter
import kotlinx.coroutines.CancellationException
import com.iris.localai.util.SpeechText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** What the voice UI shows. */
enum class VoicePhase { PREPARING, LISTENING, RECOGNIZING, THINKING, SPEAKING, ERROR }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.PREPARING,
    val micLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val lastHeard: String = "",
    val reply: String = "",
    val error: String? = null,
    /** Mid-answer gap: everything synthesised so far has played and the next sentence is still being synthesised. */
    val awaitingAudio: Boolean = false,
    /** 2.1.0: counts interruptions of the reply (talking over it or tapping), for the barge-in haptic. */
    val bargeIns: Int = 0,
) {
    /** The orb floats + breathes while the answer is being prepared: before the first audio and in gaps. */
    val waiting: Boolean get() = phase == VoicePhase.THINKING || (phase == VoicePhase.SPEAKING && awaitingAudio)
}

/** Fixed-size ring of the most recent microphone frames. */
internal class FrameRing(private val capacity: Int) {
    private val frames = ArrayDeque<FloatArray>(capacity)
    fun add(f: FloatArray) { if (frames.size == capacity) frames.removeFirst(); frames.addLast(f) }
    fun clear() = frames.clear()
    fun snapshot(): FloatArray {
        val out = FloatArray(frames.sumOf { it.size })
        var o = 0
        for (f in frames) { System.arraycopy(f, 0, out, o, f.size); o += f.size }
        return out
    }
}

/** The voice loop talks to the chat layer only through this. */
interface VoiceChatBridge {
    /** Adds [userText] to the current conversation and streams the visible answer text. */
    fun ask(userText: String): Flow<String>
    fun cancelReply()
}

/**
 * Hands-free conversation: listen → (VAD) → recognise → ask the LLM → speak sentence by
 * sentence while the model is still writing. Talking over the assistant interrupts it.
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val recognizer: SpeechRecognizer,
    private val synthesizer: SpeechSynthesizer,
    private val settings: SettingsRepository,
    private val bridge: VoiceChatBridge,
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    // Touched from the UI thread and from the microphone coroutine (barge-in).
    @Volatile private var session: Job? = null
    @Volatile private var replyJob: Job? = null
    @Volatile private var player: PcmPlayer? = null
    @Volatile private var muted = false
    /** 2.1.8: recent TTS text, to drop recognised echo of it. Touched from mic, ASR and TTS coroutines. */
    private val echoText = SyncEchoTextGuard()

    val isRunning: Boolean get() = session?.isActive == true

    fun start() {
        if (isRunning) return
        _state.value = VoiceUiState()
        echoText.clear()
        synthesizer.claim(this) { stop() }
        session = scope.launch {
            try {
                runSession()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "voice session failed", t)
                _state.update { it.copy(phase = VoicePhase.ERROR, error = t.message ?: "语音会话出错") }
            } finally {
                interruptReply()
                player?.release()
                player = null
                synthesizer.unclaim(this@VoiceController)
            }
        }
    }

    fun stop() {
        interruptReply()
        session?.cancel()
        session = null
    }

    /** Tap-to-interrupt from the UI. */
    fun interrupt() {
        interruptReply()
        _state.update { it.copy(phase = VoicePhase.LISTENING, awaitingAudio = false, bargeIns = it.bargeIns + 1) }
    }

    fun setMuted(value: Boolean) {
        muted = value
    }

    private suspend fun runSession() = coroutineScope {
        val s = settings.current
        // ASR and TTS live on separate worker threads: load them side by side.
        val asrReady = async { recognizer.prepare(s.asrLanguage) }
        synthesizer.prepare()
        asrReady.await()
        // Creating the VAD loads an ONNX session from assets: keep it off the (main) caller thread.
        val vad = withContext(Dispatchers.Default) { recognizer.newVad() }
        // A second Silero instance only gives the per-frame speech probability for barge-in
        // (compute() advances the model state, so it must not share the segmenting VAD).
        val probe = withContext(Dispatchers.Default) { runCatching { recognizer.newVad() }.getOrNull() }
        val out = try {
            PcmPlayer(synthesizer.sampleRate, voiceCommunication = s.bargeInVoiceComm).also { player = it }
        } catch (t: Throwable) {
            vad.release()
            probe?.release()
            throw t
        }
        val utterances = Channel<FloatArray>(capacity = 4)
        _state.update { it.copy(phase = VoicePhase.LISTENING) }

        // Microphone → VAD. Runs for the whole session; costs one small ONNX call per 32 ms — off the main thread.
        launch(Dispatchers.Default) {
            val detector = BargeInDetector(s.bargeInSensitivity)
            val preroll = FrameRing(PREROLL_FRAMES)  // the start of what the user says over the reply
            var prefix: FloatArray? = null            // pre-roll kept for the utterance after a barge-in
            var outPeak = 0f          // decaying peak of our own playback level
            val gate = HalfDuplexGate() // 2.1.8: mic closed while our voice is on the loudspeaker (+ tail)
            val preOpen = PreOpenBuffer() // 2.1.9: echo-free audio from the tail, replayed when the gate opens
            var lastAction = MicAction.LISTEN
            try {
                Microphone.frames().collect { frame ->
                    if (muted) {
                        _state.update { it.copy(micLevel = 0f) }
                        return@collect
                    }
                    val phase = _state.value.phase
                    val mic = Microphone.rms(frame)
                    val outLevel = out.level
                    outPeak = maxOf(outLevel, outPeak * 0.9f)
                    _state.update { it.copy(micLevel = mic, outputLevel = outLevel) }
                    val replying = phase == VoicePhase.SPEAKING || phase == VoicePhase.THINKING
                    val audible = phase == VoicePhase.SPEAKING || outLevel > 0f
                    val now = System.currentTimeMillis()
                    if (audible) echoText.touch(now)
                    val action = gate.decide(
                        nowMs = now, replying = replying, audible = audible,
                        route = if (audible) out.route() else OutputRoute.LOUDSPEAKER,
                        residualMs = if (audible) 0L else out.queuedMs(),
                    )
                    val prev = lastAction
                    lastAction = action
                    if (action == MicAction.MUTED) {
                        // Half-duplex: nothing of what the mic hears now reaches VAD / ASR / barge-in.
                        if (prev != MicAction.MUTED) { vad.clear(); detector.reset(); preroll.clear(); prefix = null; preOpen.clear() }
                        // 2.1.9: keep the last ~300 ms heard after playback ended (never TTS itself).
                        preOpen.onMutedFrame(frame, echoFree = !audible && gate.echoFree(now))
                        return@collect
                    }
                    if (prev == MicAction.MUTED) {
                        // 2.1.9: the gate opens. Instead of starting from nothing, replay what the
                        // user may already have said inside the tail, then the live frame.
                        vad.clear()
                        for (f in preOpen.drain()) { vad.acceptWaveform(f); preroll.add(f) }
                    }
                    vad.acceptWaveform(frame)
                    preroll.add(frame)
                    val talking = vad.isSpeechDetected()

                    if (action == MicAction.BARGE_IN_ONLY) {
                        val prob = probe?.let { runCatching { it.compute(frame) }.getOrNull() } ?: if (talking) 1f else 0f
                        if (detector.onFrame(mic, outPeak, prob)) {
                            Log.i(TAG, "barge-in (coupling=${detector.coupling})")
                            // Stop speaking and generating at once, then listen. Keep what was said
                            // so far: the VAD restarts its segment now, so the first ~0.6 s is
                            // carried over as a prefix instead of being lost.
                            interruptReply()
                            prefix = preroll.snapshot()
                            vad.clear()
                            _state.update { it.copy(phase = VoicePhase.LISTENING, awaitingAudio = false, bargeIns = it.bargeIns + 1) }
                        } else {
                            if (!talking) vad.clear() // drop finished (echo) segments, keep a live one
                            return@collect
                        }
                    } else {
                        detector.reset()
                    }
                    while (!vad.empty()) {
                        val seg = vad.front().samples
                        val p = prefix
                        prefix = null
                        utterances.trySend(if (p != null) p + seg else seg)
                        vad.pop()
                    }
                }
            } finally {
                vad.release()
                probe?.release()
                utterances.close()
            }
        }

        for (audio in utterances) {
            if (audio.size < ASR_SAMPLE_RATE / 4) continue // < 250 ms: noise
            _state.update { it.copy(phase = VoicePhase.RECOGNIZING) }
            // A failed recognition (native ASR error on an odd clip) skips that utterance; before
            // 1.5.1 it ended the whole voice session with an error screen.
            val text = try {
                recognizer.transcribe(audio)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "transcribe failed", t)
                ""
            }
            if (text.isBlank()) {
                _state.update { it.copy(phase = VoicePhase.LISTENING) }
                continue
            }
            // Second layer: the recogniser heard (a near copy of) what we just said → it is our echo.
            if (echoText.isEcho(text, System.currentTimeMillis())) {
                Log.i(TAG, "dropped echo of own reply: $text")
                _state.update { it.copy(phase = VoicePhase.LISTENING) }
                continue
            }
            interruptReply()
            _state.update { it.copy(lastHeard = text, reply = "", phase = VoicePhase.THINKING) }
            out.reset()
            replyJob = launch { reply(text, out) }
        }
    }

    /** A failing reply (engine error, TTS crash) ends that turn, never the whole voice session. */
    private suspend fun reply(text: String, out: PcmPlayer) {
        try {
            replyInner(text, out)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "reply failed", t)
            _state.update { it.copy(phase = VoicePhase.LISTENING, outputLevel = 0f, awaitingAudio = false) }
        }
    }

    private suspend fun replyInner(text: String, out: PcmPlayer) = coroutineScope {
        val s = settings.current
        val sentences = Channel<String>(Channel.UNLIMITED)
        val producer = launch {
            val splitter = SentenceSplitter()
            try {
                bridge.ask(text).collect { delta ->
                    _state.update { it.copy(reply = it.reply + delta) }
                    splitter.push(delta).forEach { sentences.send(it) }
                }
                splitter.flush().forEach { sentences.send(it) }
            } finally {
                sentences.close()
            }
        }
        // Synthesis and playback are decoupled: sentence N+1 is synthesised while N is playing,
        // so there is no silent gap waiting for the next sentence's audio.
        val pcm = Channel<FloatArray>(Channel.UNLIMITED)
        val playback = launch(Dispatchers.IO) {
            while (true) {
                var chunk = pcm.tryReceive().getOrNull()
                if (chunk == null) {
                    // Nothing queued: once the speaker has actually run dry (not just our queue),
                    // show the "waiting for the next sentence" state until the next chunk arrives.
                    val gap = launch {
                        while (out.queuedMs() > GAP_MS) kotlinx.coroutines.delay(40)
                        _state.update { if (it.phase == VoicePhase.SPEAKING) it.copy(awaitingAudio = true) else it }
                    }
                    chunk = pcm.receiveCatching().getOrNull()
                    gap.cancel()
                    if (chunk == null) break
                }
                _state.update {
                    when {
                        it.phase == VoicePhase.THINKING -> it.copy(phase = VoicePhase.SPEAKING, awaitingAudio = false)
                        it.awaitingAudio -> it.copy(awaitingAudio = false)
                        else -> it
                    }
                }
                if (!out.write(chunk)) break
            }
        }
        try {
            for (sentence in sentences) {
                val speakable = SpeechText.speakable(sentence)
                if (speakable.none { it.isLetterOrDigit() }) continue
                echoText.remember(speakable, System.currentTimeMillis())
                try {
                    synthesizer.speak(speakable, s.ttsSpeakerId, s.ttsSpeed) { chunk ->
                        pcm.trySend(chunk.copyOf())
                        playback.isActive
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(TAG, "tts failed for: $speakable", t)
                }
            }
        } finally {
            pcm.close()
        }
        playback.join()
        producer.join()
        // Stay in SPEAKING (barge-in armed, mic gated) until the last syllable has actually played.
        out.drain()
        echoText.touch(System.currentTimeMillis())
        _state.update { it.copy(phase = VoicePhase.LISTENING, outputLevel = 0f, awaitingAudio = false) }
    }

    private fun interruptReply() {
        player?.stop()
        if (replyJob?.isActive == true) bridge.cancelReply()
        replyJob?.cancel()
        replyJob = null
    }

    private companion object {
        const val TAG = "VoiceController"
        /** 20 × 32 ms ≈ 0.64 s of audio kept from before a barge-in was recognised. */
        const val PREROLL_FRAMES = 20
        /** Below this much audio left in the track, a gap is imminent. */
        const val GAP_MS = 60L
    }
}

/** Thread-safe wrapper: the guard is fed by the TTS coroutine and read by the ASR loop. */
internal class SyncEchoTextGuard(private val g: EchoTextGuard = EchoTextGuard()) {
    @Synchronized fun remember(text: String, nowMs: Long) = g.remember(text, nowMs)
    @Synchronized fun touch(nowMs: Long) = g.touch(nowMs)
    @Synchronized fun isEcho(heard: String, nowMs: Long): Boolean = g.isEcho(heard, nowMs)
    @Synchronized fun clear() = g.clear()
}

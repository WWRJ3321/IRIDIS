package com.iris.localai.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Hold to talk": records while the button is held, then transcribes the whole clip.
 * Silence is cut by the VAD first, so long pauses cost nothing and never confuse the model.
 *
 * 2.7.0 边说边出字：引擎能快速解码时（[SpeechRecognizer.supportsPartial]），录音期间每
 * [PARTIAL_MS] 用 VAD 切出已说完的句子定稿，正在说的那句反复解码作暂定结果，写进 [partial]；
 * 松手后仍按原方式整段识别一次作为定稿。不支持的引擎 [partial] 一直是 null（原行为）。
 */
class Dictation(private val scope: CoroutineScope, private val recognizer: SpeechRecognizer) {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var liveJob: Job? = null
    private val frames = ArrayList<FloatArray>()

    private val _partial = MutableStateFlow<String?>(null)
    /** 识别中的文字（已定稿句 + 正在说的暂定句）；null = 本次不实时。 */
    val partial: StateFlow<String?> = _partial.asStateFlow()

    /** Language the recogniser is being warmed for while the user is still talking. */
    var warmLanguage: String = "auto"

    fun start() {
        if (job != null) return
        scope.launch { runCatching { recognizer.prepare(warmLanguage) } }
        synchronized(frames) { frames.clear() }
        job = scope.launch {
            try {
                Microphone.frames().collect { f ->
                    _level.value = Microphone.rms(f)
                    synchronized(frames) {
                        if (frames.size < MAX_FRAMES) frames.add(f)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("Dictation", "microphone failed", t)
                _level.value = 0f
            }
        }
        if (recognizer.supportsPartial()) liveJob = scope.launch(Dispatchers.Default) { runLive() }
    }

    /** 录音期间的实时识别循环（取消即停；出错只停实时，不影响松手后的定稿）。 */
    private suspend fun runLive() {
        val vad = runCatching { recognizer.newVad(minSilenceSec = 0.4f) }.getOrNull() ?: return
        val live = LivePartial()
        val pending = ArrayList<Float>()
        var nextFrame = 0
        var fed = 0
        try {
            runCatching { recognizer.prepare(warmLanguage) }
            while (true) {
                kotlinx.coroutines.delay(PARTIAL_MS)
                val fresh = synchronized(frames) { frames.subList(nextFrame, frames.size).toList() }
                nextFrame += fresh.size
                if (fresh.isEmpty()) continue
                for (f in fresh) for (x in f) pending.add(x)
                val window = FloatArray(VAD_WINDOW)
                var i = 0
                while (i + VAD_WINDOW <= pending.size) {
                    for (k in 0 until VAD_WINDOW) window[k] = pending[i + k]
                    vad.acceptWaveform(window)
                    i += VAD_WINDOW
                }
                fed += i
                if (i > 0) pending.subList(0, i).clear()
                while (!vad.empty()) {
                    val seg = vad.front(); vad.pop()
                    live.finalize(recognizer.transcribe(seg.samples), seg.start + seg.samples.size)
                }
                if (vad.isSpeechDetected()) {
                    val from = maxOf(live.doneUpTo, fed - MAX_TENTATIVE)
                    val clip = synchronized(frames) { slice(frames, from, fed) }
                    if (clip.size >= ASR_SAMPLE_RATE / 4) live.tentative(recognizer.transcribe(clip))
                } else live.tentative("")
                _partial.value = live.text().ifBlank { null }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("Dictation", "live partial stopped", t)
        } finally {
            vad.release()
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        _partial.value = null
    }

    fun cancel() {
        stopLive()
        job?.cancel()
        job = null
        _level.value = 0f
        synchronized(frames) { frames.clear() }
    }

    /** Stops recording and returns the recognised text ("" if nothing was said). */
    suspend fun finish(language: String): String {
        liveJob?.let { it.cancel(); it.join() }
        stopLive()
        job?.cancel()
        job?.join()
        job = null
        _level.value = 0f
        val clip = synchronized(frames) {
            val all = FloatArray(frames.sumOf { it.size })
            var o = 0
            for (f in frames) { f.copyInto(all, o); o += f.size }
            frames.clear()
            all
        }
        if (clip.size < ASR_SAMPLE_RATE / 4) return ""
        recognizer.prepare(language)
        // Up to 90 s of audio through the VAD is a few thousand ONNX calls: never on the caller's
        // (usually main) thread.
        val segments = withContext(Dispatchers.Default) {
            val vad = recognizer.newVad(minSilenceSec = 0.4f)
            val found = ArrayList<FloatArray>()
            try {
                val window = FloatArray(VAD_WINDOW)
                var i = 0
                while (i + VAD_WINDOW <= clip.size) {
                    clip.copyInto(window, 0, i, i + VAD_WINDOW)
                    vad.acceptWaveform(window)
                    i += VAD_WINDOW
                    while (!vad.empty()) { found.add(vad.front().samples); vad.pop() }
                }
                vad.flush()
                while (!vad.empty()) { found.add(vad.front().samples); vad.pop() }
            } finally {
                vad.release()
            }
            found
        }
        if (segments.isEmpty()) return ""
        return segments.map { recognizer.transcribe(it) }.filter { it.isNotBlank() }.joinToString("")
    }

    private companion object {
        /** 90 s at 16 kHz / 512-sample frames. */
        const val MAX_FRAMES = 90 * ASR_SAMPLE_RATE / VAD_WINDOW
        /** 实时识别的刷新间隔。 */
        const val PARTIAL_MS = 700L
        /** 暂定句最多重解码最后 15 s。 */
        const val MAX_TENTATIVE = 15 * ASR_SAMPLE_RATE

        /** samples [from, to) of the recorded frames, as one array. */
        fun slice(frames: List<FloatArray>, from: Int, to: Int): FloatArray {
            val out = FloatArray((to - from).coerceAtLeast(0))
            var pos = 0
            for (f in frames) {
                val a = maxOf(from, pos); val b = minOf(to, pos + f.size)
                if (a < b) f.copyInto(out, a - from, a - pos, b - pos)
                pos += f.size
                if (pos >= to) break
            }
            return out
        }
    }
}

/** 实时识别的文字：已定稿句按顺序拼接，末尾是正在说的暂定句（每次刷新整句替换）。 */
class LivePartial {
    private val done = StringBuilder()
    private var pending = ""
    /** 已定稿到的样本位置（之后的音频属于暂定句）。 */
    var doneUpTo = 0
        private set

    fun finalize(text: String, endSample: Int) {
        done.append(text.trim())
        pending = ""
        doneUpTo = maxOf(doneUpTo, endSample)
    }

    fun tentative(text: String) { pending = text.trim() }

    fun text(): String = done.toString() + pending
}

/** 把听写文字接到输入框原有内容后面。 */
object DictationText {
    fun join(base: String, add: String): String = when {
        add.isBlank() -> base
        base.isBlank() -> add
        base.endsWith(" ") || base.endsWith("\n") -> base + add
        else -> "$base $add"
    }
}

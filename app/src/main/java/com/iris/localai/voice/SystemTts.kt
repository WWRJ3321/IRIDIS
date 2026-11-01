package com.iris.localai.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 2.2.10 语音 › 朗读引擎「系统 TTS」: Android's TextToSpeech with the system default engine and voice.
 * Each sentence is rendered with synthesizeToFile and handed to the normal PCM pipeline (resampled
 * to the player's rate), so stop / barge-in / echo guard work exactly as with the built-in models.
 */
class SystemTts(private val context: Context) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    /** Installed TTS engines (empty = none: show the hint + 打开系统 TTS 设置). */
    fun hasEngine(): Boolean = runCatching {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    }.getOrDefault(false)

    /** Initialises the default engine; false if there is none or it failed. */
    suspend fun prepare(): Boolean {
        if (ready && tts != null) return true
        if (!hasEngine()) return false
        val done = CompletableDeferred<Boolean>()
        val t = TextToSpeech(context.applicationContext) { status -> done.complete(status == TextToSpeech.SUCCESS) }
        val ok = withTimeoutOrNull(8_000) { done.await() } == true
        if (!ok) { runCatching { t.shutdown() }; return false }
        // Follow the app language (Chinese); keep the engine default if it has no Chinese voice.
        val r = runCatching { t.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (r == TextToSpeech.LANG_NOT_SUPPORTED) runCatching { t.setLanguage(Locale.getDefault()) }
        tts = t; ready = true
        return true
    }

    /**
     * Renders [text] and returns mono float PCM at [targetRate] (null on failure). Cancelling the
     * calling coroutine stops the engine.
     */
    suspend fun synthesize(text: String, speed: Float, targetRate: Int): FloatArray? {
        val t = tts ?: return null
        val file = File(context.cacheDir, "systts-${UUID.randomUUID()}.wav")
        try {
            val id = UUID.randomUUID().toString()
            val ok = suspendCancellableCoroutine { cont ->
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("") override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                t.setSpeechRate(speed.coerceIn(0.5f, 2f))
                val rc = t.synthesizeToFile(text, null, file, id)
                if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                cont.invokeOnCancellation { runCatching { t.stop() } }
            }
            if (!ok || !file.exists()) return null
            val wav = WavPcm.decode(file.readBytes()) ?: return null
            return WavPcm.resample(wav.samples, wav.rate, targetRate)
        } finally {
            file.delete()
        }
    }

    fun stop() { runCatching { tts?.stop() } }

    fun release() {
        ready = false
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    companion object {
        /** 系统 TTS 设置 (falls back to the general settings screen). */
        fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Minimal PCM16 / float32 WAV reader + linear resampler (pure; unit-tested). */
object WavPcm {
    class Pcm(val samples: FloatArray, val rate: Int)

    fun decode(b: ByteArray): Pcm? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        var p = 12
        var fmt = 1; var ch = 1; var rate = 0; var bits = 16
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII); val len = u32(p + 4)
            val body = p + 8
            if (id == "fmt ") { fmt = u16(body); ch = u16(body + 2).coerceAtLeast(1); rate = u32(body + 4); bits = u16(body + 14) }
            else if (id == "data") {
                val end = if (len <= 0 || body + len > b.size) b.size else body + len
                if (rate <= 0) return null
                val frameBytes = (bits / 8) * ch
                if (frameBytes <= 0) return null
                val n = (end - body) / frameBytes
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val o = body + i * frameBytes
                    out[i] = when {
                        fmt == 3 && bits == 32 -> java.lang.Float.intBitsToFloat(u32(o))
                        bits == 16 -> u16(o).toShort() / 32768f
                        bits == 8 -> ((b[o].toInt() and 0xFF) - 128) / 128f
                        else -> 0f
                    }
                }
                return Pcm(out, rate)
            }
            p = body + len + (len and 1)
        }
        return null
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty() || from <= 0 || to <= 0) return x
        val n = ((x.size.toLong() * to) / from).toInt().coerceAtLeast(1)
        val ratio = from.toDouble() / to
        return FloatArray(n) { i ->
            val pos = i * ratio
            val j = pos.toInt().coerceAtMost(x.size - 1)
            val k = (j + 1).coerceAtMost(x.size - 1)
            val f = (pos - j).toFloat()
            x[j] * (1 - f) + x[k] * f
        }
    }
}

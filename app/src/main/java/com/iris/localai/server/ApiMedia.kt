package com.iris.localai.server

import com.iris.localai.engine.PromptMessage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * what the multimodal API endpoints need from the app (生图 + 朗读), injected so [ApiRoutes]
 * stays testable. Null in [ApiRoutes] = those endpoints answer 501.
 */
interface ApiMedia {
    /** Model names /v1/images/generations can use (downloaded + runnable here). Empty = none. */
    fun imageModels(): List<String>
    /** One PNG. [model] = a name from [imageModels] or null for the one set in 设置 › 智能体 › 生图 (else the first). */
    suspend fun generateImage(p: ApiImageGen, model: String?): Result<ByteArray>
    /** True when a voice is ready for /v1/audio/speech. */
    fun speechAvailable(): Boolean
    /** Mono PCM in -1..1 and its sample rate. */
    suspend fun speech(text: String, speed: Float): Result<Pair<FloatArray, Int>>
}

/** Parsed /v1/images/generations body. */
data class ApiImageGen(
    val prompt: String,
    val n: Int = 1,
    val width: Int? = null,
    val height: Int? = null,
    val negative: String = "",
    val steps: Int? = null,
    val cfg: Float? = null,
    val seed: Long = -1,
    /** "b64_json" (default) or "url" (answered with a data: URI — nothing is hosted). */
    val format: String = "b64_json",
    val model: String? = null,
)

/** Parsed /v1/audio/speech body. */
data class ApiSpeech(val input: String, val speed: Float = 1f, val format: String = "wav")

/** pure helpers for images sent in chat (image_url) and the media endpoints. */
object ApiImages {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_IMAGES = 4
    const val MAX_SPEECH_CHARS = 4096
    const val MAX_N = 4

    fun isRemote(url: String): Boolean = url.startsWith("http://", true) || url.startsWith("https://", true)

    /**
     * `data:image/png;base64,…` or bare base64 (Ollama `images`) → bytes. Must decode to a known
     * picture type (PNG / JPEG / WebP / GIF / BMP) of at most [MAX_BYTES]. Throws [HttpError] 400.
     */
    fun decode(url: String): ByteArray {
        val s = url.trim()
        val payload = if (s.startsWith("data:", true)) {
            val comma = s.indexOf(',')
            if (comma < 0) throw HttpError(400, "malformed data URI")
            val meta = s.substring(5, comma).lowercase()
            if (!meta.endsWith(";base64")) throw HttpError(400, "data URI must be base64")
            val mime = meta.substringBefore(';')
            if (mime.isNotEmpty() && !mime.startsWith("image/")) throw HttpError(400, "unsupported image type '$mime'")
            s.substring(comma + 1)
        } else s
        if (payload.length > MAX_BYTES / 3 * 4 + 8) throw HttpError(413, "image larger than ${MAX_BYTES / (1024 * 1024)} MB")
        val bytes = try {
            Base64.getMimeDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, "image is not valid base64; send a data URI (data:image/png;base64,…) or an http(s) URL")
        }
        check(bytes)
        return bytes
    }

    /** Size and type check shared with downloaded images. */
    fun check(bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) throw HttpError(413, "image larger than ${MAX_BYTES / (1024 * 1024)} MB")
        if (sniff(bytes) == null) throw HttpError(400, "not a supported image (PNG, JPEG, WebP, GIF or BMP)")
    }

    /** File extension from the magic bytes, null = not a picture we take. */
    fun sniff(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "webp"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "gif"
            at(0) == 'B'.code && at(1) == 'M'.code && b.size > 26 -> "bmp"
            else -> null
        }
    }

    /**
     * The engine sees the pictures of one message (the newest that has any, as in the app's chat):
     * older messages keep their text, their pictures are dropped. Non-user messages never carry any.
     */
    fun keepNewest(messages: List<PromptMessage>): List<PromptMessage> {
        val i = messages.indexOfLast { it.role == PromptMessage.USER && it.images.isNotEmpty() }
        return messages.mapIndexed { j, m -> if (m.images.isEmpty() || j == i) m else m.copy(images = emptyList()) }
    }

    fun hasImages(messages: List<PromptMessage>): Boolean = messages.any { it.images.isNotEmpty() }

    // ---- /v1/images/generations ----

    /** `"512x768"` → (512, 768); "auto" / null → null; anything else → 400. Sides 64..2048. */
    fun size(s: String?): Pair<Int, Int>? {
        if (s == null || s.isBlank() || s.equals("auto", true)) return null
        val m = Regex("""^\s*(\d{2,4})\s*[xX×]\s*(\d{2,4})\s*$""").find(s) ?: throw HttpError(400, "'size' must look like 512x512")
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        if (w !in 64..2048 || h !in 64..2048) throw HttpError(400, "'size' sides must be 64–2048")
        return w to h
    }

    fun parseImageGen(root: JsonObject): ApiImageGen {
        fun str(k: String) = (root[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val prompt = str("prompt")?.trim()?.takeIf { it.isNotEmpty() } ?: throw HttpError(400, "'prompt' is required")
        val n = (root["n"] as? JsonPrimitive)?.intOrNull ?: 1
        if (n !in 1..MAX_N) throw HttpError(400, "'n' must be 1–$MAX_N")
        val fmt = str("response_format") ?: "b64_json"
        if (fmt != "b64_json" && fmt != "url") throw HttpError(400, "'response_format' must be b64_json or url")
        val wh = size(str("size"))
        return ApiImageGen(
            prompt = prompt, n = n, width = wh?.first, height = wh?.second,
            negative = str("negative_prompt")?.trim().orEmpty(),
            steps = (root["steps"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 100),
            cfg = ((root["cfg_scale"] ?: root["guidance_scale"]) as? JsonPrimitive)?.floatOrNull?.takeIf { !it.isNaN() }?.coerceIn(0f, 30f),
            seed = (root["seed"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: -1L,
            format = fmt,
            model = str("model")?.takeIf { it.isNotBlank() && it != "default" },
        )
    }

    // ---- /v1/audio/speech ----

    fun parseSpeech(root: JsonObject): ApiSpeech {
        fun str(k: String) = (root[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val input = str("input")?.trim()?.takeIf { it.isNotEmpty() } ?: throw HttpError(400, "'input' is required")
        if (input.length > MAX_SPEECH_CHARS) throw HttpError(400, "'input' is longer than $MAX_SPEECH_CHARS characters")
        val fmt = (str("response_format") ?: "wav").lowercase()
        if (fmt != "wav" && fmt != "pcm") throw HttpError(400, "only response_format wav or pcm is supported on device")
        val speed = (root["speed"] as? JsonPrimitive)?.floatOrNull?.takeIf { !it.isNaN() } ?: 1f
        if (speed !in 0.25f..4f) throw HttpError(400, "'speed' must be 0.25–4.0")
        return ApiSpeech(input, speed.coerceIn(0.5f, 2f), fmt)
    }

    /** Float PCM → 16-bit little-endian samples (clipped). */
    fun pcm16(pcm: FloatArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** A mono 16-bit WAV file. */
    fun wav(pcm: FloatArray, sampleRate: Int): ByteArray {
        val data = pcm16(pcm)
        val b = java.nio.ByteBuffer.allocate(44 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + data.size); b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(data.size); b.put(data)
        return b.array()
    }
}

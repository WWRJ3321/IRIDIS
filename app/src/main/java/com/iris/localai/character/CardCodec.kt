package com.iris.localai.character

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream

/**
 * 2.3.2 角色卡 import / export (pure Kotlin, no Android):
 *  - PNG: tEXt (also zTXt / iTXt) chunk `ccv3` (V3) or `chara` (V1/V2), base64 of UTF-8 JSON; `ccv3` wins.
 *  - JSON: the card itself.
 *  - CHARX: zip with `card.json` at the root and assets (`embeded://path` URIs) inside.
 * Export: PNG with both `ccv3` (V3) and `chara` (V2 view) chunks, old card chunks removed; or JSON.
 */
object CardCodec {
    class Imported(
        val card: CharacterCard,
        /** Avatar image bytes as found (PNG for PNG cards; any image type from CHARX / data URLs). */
        val avatar: ByteArray?,
        /** Other embedded CHARX files, zip path → bytes (kept beside the card, unchanged). */
        val assets: Map<String, ByteArray> = emptyMap(),
        val format: String,
    )

    class CardFormatException(val kind: Kind) : IllegalArgumentException(kind.name) {
        enum class Kind { NOT_A_CARD, PNG_NO_CARD, BAD_JSON, TOO_BIG }
    }

    const val MAX_BYTES = 40L * 1024 * 1024
    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val CARD_KEYS = setOf("chara", "ccv3")

    fun isPng(b: ByteArray) = b.size >= 8 && (0 until 8).all { b[it] == PNG_SIG[it] }
    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()

    /** Detects the format from the bytes (the file name is only a hint). */
    fun decode(bytes: ByteArray, fileName: String = ""): Imported {
        if (bytes.size > MAX_BYTES) throw CardFormatException(CardFormatException.Kind.TOO_BIG)
        return when {
            isPng(bytes) -> decodePng(bytes)
            isZip(bytes) || fileName.endsWith(".charx", true) -> decodeCharx(bytes)
            else -> {
                val card = runCatching { CardSpec.parse(String(bytes, Charsets.UTF_8)) }.getOrElse {
                    throw CardFormatException(if (it is IllegalArgumentException && it.message == "not a character card") CardFormatException.Kind.NOT_A_CARD else CardFormatException.Kind.BAD_JSON)
                }
                Imported(card, dataUrlIcon(card), format = "JSON")
            }
        }
    }

    // ---------------------------------------------------------------- PNG

    data class Chunk(val type: String, val data: ByteArray)

    fun chunks(png: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = 8
        while (p + 8 <= png.size) {
            val len = ((png[p].toInt() and 0xff) shl 24) or ((png[p + 1].toInt() and 0xff) shl 16) or ((png[p + 2].toInt() and 0xff) shl 8) or (png[p + 3].toInt() and 0xff)
            if (len < 0 || p + 12 + len > png.size) break
            val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
            out += Chunk(type, png.copyOfRange(p + 8, p + 8 + len))
            p += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    /** Text chunks → keyword to text (tEXt latin-1 value; zTXt inflated; iTXt UTF-8, maybe compressed). */
    fun textChunks(png: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (c in chunks(png)) {
            val z = c.data.indexOf(0)
            if (z <= 0) continue
            val key = String(c.data, 0, z, Charsets.ISO_8859_1)
            when (c.type) {
                "tEXt" -> m[key] = String(c.data, z + 1, c.data.size - z - 1, Charsets.ISO_8859_1)
                "zTXt" -> runCatching { m[key] = String(inflate(c.data.copyOfRange(z + 2, c.data.size)), Charsets.ISO_8859_1) }
                "iTXt" -> runCatching {
                    val compressed = c.data[z + 1].toInt() != 0
                    var q = z + 3
                    q = c.data.indexOf(0, q) + 1 // language tag
                    q = c.data.indexOf(0, q) + 1 // translated keyword
                    val body = c.data.copyOfRange(q, c.data.size)
                    m[key] = String(if (compressed) inflate(body) else body, Charsets.UTF_8)
                }
            }
        }
        return m
    }

    private fun ByteArray.indexOf(b: Int, from: Int = 0): Int { for (i in from until size) if (this[i].toInt() == b) return i; return -1 }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = java.util.zip.Inflater(); inf.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) break }
        inf.end(); return out.toByteArray()
    }

    private fun b64json(v: String): String = String(Base64.getMimeDecoder().decode(v.trim()), Charsets.UTF_8)

    fun decodePng(png: ByteArray): Imported {
        val t = textChunks(png)
        // V3 first; a broken ccv3 falls back to chara.
        val card = listOf("ccv3", "chara").firstNotNullOfOrNull { k -> t[k]?.let { v -> runCatching { CardSpec.parse(b64json(v)) }.getOrNull() } }
            ?: throw CardFormatException(CardFormatException.Kind.PNG_NO_CARD)
        return Imported(card, png, format = if (t.containsKey("ccv3")) "PNG V3" else "PNG V2")
    }

    /** [png] without its card chunks, plus `ccv3` and `chara` tEXt chunks before IEND. */
    fun encodePng(card: CharacterCard, png: ByteArray): ByteArray {
        require(isPng(png)) { "avatar is not a PNG" }
        val out = ByteArrayOutputStream()
        out.write(PNG_SIG)
        val v3 = Base64.getEncoder().encodeToString(card.toJson(pretty = false).toByteArray(Charsets.UTF_8))
        val v2 = Base64.getEncoder().encodeToString(CardSpec.json.encodeToString(JsonObject.serializer(), card.toV2()).toByteArray(Charsets.UTF_8))
        for (c in chunks(png)) {
            if (c.type == "IEND") {
                writeChunk(out, "tEXt", "chara".toByteArray(Charsets.ISO_8859_1) + 0 + v2.toByteArray(Charsets.ISO_8859_1))
                writeChunk(out, "tEXt", "ccv3".toByteArray(Charsets.ISO_8859_1) + 0 + v3.toByteArray(Charsets.ISO_8859_1))
            }
            if ((c.type == "tEXt" || c.type == "zTXt" || c.type == "iTXt") && keyOf(c) in CARD_KEYS) continue
            writeChunk(out, c.type, c.data)
        }
        return out.toByteArray()
    }

    private fun keyOf(c: Chunk): String { val z = c.data.indexOf(0); return if (z <= 0) "" else String(c.data, 0, z, Charsets.ISO_8859_1) }
    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        fun i32(v: Int) { out.write(v ushr 24 and 0xff); out.write(v ushr 16 and 0xff); out.write(v ushr 8 and 0xff); out.write(v and 0xff) }
        val t = type.toByteArray(Charsets.ISO_8859_1)
        i32(data.size); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data); i32(crc.value.toInt())
    }

    /** A plain [size]² PNG in one colour (export of a card without an avatar). */
    fun solidPng(size: Int = 256, rgb: Int = 0x8E7CC3): ByteArray {
        val raw = ByteArrayOutputStream()
        val row = ByteArray(1 + size * 3)
        for (x in 0 until size) { row[1 + x * 3] = (rgb shr 16).toByte(); row[2 + x * 3] = (rgb shr 8).toByte(); row[3 + x * 3] = rgb.toByte() }
        repeat(size) { raw.write(row) }
        val def = Deflater(9); def.setInput(raw.toByteArray()); def.finish()
        val z = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        def.end()
        val out = ByteArrayOutputStream(); out.write(PNG_SIG)
        val ihdr = ByteArrayOutputStream().apply {
            fun i32(v: Int) { write(v ushr 24 and 0xff); write(v ushr 16 and 0xff); write(v ushr 8 and 0xff); write(v and 0xff) }
            i32(size); i32(size); write(8); write(2); write(0); write(0); write(0)
        }.toByteArray()
        writeChunk(out, "IHDR", ihdr); writeChunk(out, "IDAT", z.toByteArray()); writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- CHARX

    fun decodeCharx(zip: ByteArray): Imported {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name.split('/').any { it == ".." }) continue
                val b = z.readBytes(); total += b.size
                if (total > MAX_BYTES * 4) throw CardFormatException(CardFormatException.Kind.TOO_BIG)
                files[name] = b
            }
        }
        val cardJson = files["card.json"] ?: throw CardFormatException(CardFormatException.Kind.NOT_A_CARD)
        val card = runCatching { CardSpec.parse(String(cardJson, Charsets.UTF_8)) }.getOrElse { throw CardFormatException(CardFormatException.Kind.BAD_JSON) }
        val icon = iconAsset(card)
        val avatar = icon?.let { uri -> resolveUri(uri, files) } ?: dataUrlIcon(card)
        val iconPath = icon?.removePrefix("embeded://")?.removePrefix("embedded://")
        val assets = files.filterKeys { it != "card.json" && it != iconPath }
        return Imported(card, avatar, assets, format = "CHARX")
    }

    /** The URI of the main icon asset (`type: icon`, name `main` first). */
    fun iconAsset(card: CharacterCard): String? {
        val list = (card.data["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val icons = list.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "icon" }
        val main = icons.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == "main" } ?: icons.firstOrNull() ?: return null
        return (main["uri"] as? JsonPrimitive)?.contentOrNull
    }

    private fun resolveUri(uri: String, files: Map<String, ByteArray>): ByteArray? = when {
        uri.startsWith("embeded://") || uri.startsWith("embedded://") -> files[uri.substringAfter("://")]
        uri.startsWith("data:") -> decodeDataUrl(uri)
        else -> null
    }

    private fun dataUrlIcon(card: CharacterCard): ByteArray? = iconAsset(card)?.takeIf { it.startsWith("data:") }?.let { decodeDataUrl(it) }

    private fun decodeDataUrl(uri: String): ByteArray? = runCatching {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) null else Base64.getMimeDecoder().decode(uri.substring(comma + 1))
    }.getOrNull()
}

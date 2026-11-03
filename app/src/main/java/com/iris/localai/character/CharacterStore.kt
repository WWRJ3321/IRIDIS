package com.iris.localai.character

import java.io.File

/**
 * 2.3.2 角色卡 storage: `Download/IRIDIS/characters/<id>/` (or `filesDir/iridis/characters` in
 * 应用私有目录 mode / when the public folder cannot be written), one folder per card:
 * `card.json` (full V3 JSON, unknown fields kept), `avatar.png` (or the imported image type),
 * `assets/…` (other CHARX files, unchanged). Pure java.io (unit-tested).
 */
class CharacterStore(private val root: () -> File, private val fallback: File) {
    data class Entry(val id: String, val card: CharacterCard, val avatar: File?, val modified: Long)

    private val cache = HashMap<String, Pair<Long, Entry>>()

    fun dir(): File {
        val d = File(root(), DIR)
        if ((d.isDirectory || d.mkdirs()) && d.canWrite()) return d
        return fallback.apply { mkdirs() }
    }

    fun list(): List<Entry> = synchronized(cache) {
        val out = dir().listFiles { f -> f.isDirectory && File(f, CARD).isFile }.orEmpty().mapNotNull { load(it) }
        out.sortedByDescending { it.modified }
    }

    fun get(id: String): Entry? = synchronized(cache) { safeId(id)?.let { File(dir(), it) }?.takeIf { File(it, CARD).isFile }?.let { load(it) } }

    private fun load(d: File): Entry? {
        val f = File(d, CARD)
        val mt = f.lastModified()
        cache[d.name]?.let { (t, e) -> if (t == mt) return e.copy(avatar = avatarIn(d)) }
        val card = runCatching { CardSpec.parse(f.readText()) }.getOrNull() ?: return null
        return Entry(d.name, card, avatarIn(d), mt).also { cache[d.name] = mt to it }
    }

    private fun avatarIn(d: File): File? = d.listFiles { f -> f.isFile && f.name.startsWith("avatar.") }?.maxByOrNull { it.lastModified() }

    /** Saves [card] (new id when [id] is null); [avatar] bytes replace the avatar when given. */
    fun save(card: CharacterCard, id: String? = null, avatar: ByteArray? = null, avatarExt: String = "png", assets: Map<String, ByteArray> = emptyMap()): String = synchronized(cache) {
        val useId = id?.let { safeId(it) } ?: newId(card.name)
        val d = File(dir(), useId).apply { mkdirs() }
        val tmp = File(d, "$CARD.tmp")
        tmp.writeText(card.toJson())
        if (!tmp.renameTo(File(d, CARD))) { File(d, CARD).writeText(card.toJson()); tmp.delete() }
        if (avatar != null) {
            d.listFiles { f -> f.name.startsWith("avatar.") }?.forEach { it.delete() }
            File(d, "avatar." + avatarExt.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "png" }).writeBytes(avatar)
        }
        for ((path, bytes) in assets) {
            val rel = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
            if (rel.isEmpty()) continue
            File(File(d, ASSETS), rel).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        cache.remove(useId)
        useId
    }

    fun delete(id: String): Boolean = synchronized(cache) {
        val d = safeId(id)?.let { File(dir(), it) } ?: return false
        cache.remove(id)
        d.deleteRecursively()
    }

    private fun newId(name: String): String {
        val base = slug(name).ifEmpty { "card" }
        var n = 0
        while (true) {
            val id = if (n == 0) base else "$base-$n"
            if (!File(dir(), id).exists()) return id
            n++
        }
    }

    companion object {
        const val DIR = "characters"
        const val CARD = "card.json"
        const val ASSETS = "assets"

        /** Folder name from a card name: letters / digits (any script), `-`; at most 40 chars. */
        fun slug(name: String): String = name.trim().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(40)

        fun safeId(id: String): String? = id.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != ".." }

        /** Image type from magic bytes ("png" / "jpg" / "webp" / "gif"; null = not an image). */
        fun imageExt(b: ByteArray): String? = when {
            CardCodec.isPng(b) -> "png"
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
            b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
            else -> null
        }
    }
}

package com.iris.localai.knowledge

import java.io.File

/**
 * 2.3.3: 知识库回收站 — `knowledge/.trash/<id>/` holds one deleted file or folder under its own name
 * plus `.origin` (original relative path, deletion time, folder flag). Restore puts it back (a free
 * name when the place is taken); items older than [KEEP_MS] are purged. `.trash` is never indexed.
 * Pure JVM, unit-tested.
 */
class KbTrash(private val kbDir: () -> File) {
    data class Item(val id: String, val path: String, val folder: Boolean, val deletedAt: Long, val bytes: Long)

    fun dir(): File = File(kbDir(), DIR)

    /** Moves `knowledge/<rel>` (file or folder) into the trash. False when it could not be moved. */
    fun put(rel: String, now: Long = System.currentTimeMillis()): Boolean {
        val src = File(kbDir(), rel)
        if (!src.exists() || rel.isBlank() || rel.split('/').any { it == DIR }) return false
        val folder = src.isDirectory
        var id = "$now"; var n = 1
        while (File(dir(), id).exists()) id = "$now-${n++}"
        val box = File(dir(), id)
        if (!box.mkdirs()) return false
        val ok = runCatching { src.renameTo(File(box, src.name)) }.getOrDefault(false)
        if (!ok) { box.deleteRecursively(); return false }
        File(box, ORIGIN).writeText("$rel\n$now\n${if (folder) 1 else 0}\n")
        return true
    }

    fun list(): List<Item> = (dir().listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { item(it) }.sortedByDescending { it.deletedAt }

    private fun item(box: File): Item? {
        val lines = runCatching { File(box, ORIGIN).readLines() }.getOrNull() ?: return null
        val path = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val at = lines.getOrNull(1)?.toLongOrNull() ?: box.lastModified()
        val payload = payload(box) ?: return null
        val size = if (payload.isDirectory) payload.walkTopDown().filter { it.isFile }.sumOf { it.length() } else payload.length()
        return Item(box.name, path, lines.getOrNull(2) == "1", at, size)
    }

    private fun payload(box: File): File? = box.listFiles()?.firstOrNull { it.name != ORIGIN }

    /** Puts item [id] back. Returns the restored relative path (renamed `name (2)` when taken), or null. */
    fun restore(id: String): String? {
        val box = File(dir(), id).takeIf { safe(id) && it.isDirectory } ?: return null
        val it = item(box) ?: return null
        val src = payload(box) ?: return null
        val parentRel = it.path.substringBeforeLast('/', "")
        val parent = if (parentRel.isEmpty()) kbDir() else File(kbDir(), parentRel)
        parent.mkdirs()
        val name = it.path.substringAfterLast('/')
        var target = File(parent, name); var i = 2
        while (target.exists()) {
            val base = if (it.folder) name else name.substringBeforeLast('.', name)
            val ext = if (it.folder) "" else name.substringAfterLast('.', "")
            target = File(parent, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++
        }
        if (!src.renameTo(target)) return null
        box.deleteRecursively()
        return if (parentRel.isEmpty()) target.name else "$parentRel/${target.name}"
    }

    /** Deletes item [id] for good. */
    fun remove(id: String): Boolean = safe(id) && File(dir(), id).let { it.isDirectory && it.deleteRecursively() }

    /** Empties the trash. Returns how many items went. */
    fun empty(): Int = list().count { remove(it.id) }.also { (dir().listFiles() ?: emptyArray()).forEach { f -> f.deleteRecursively() } }

    /** Removes items deleted more than [keepMs] ago. Returns how many. */
    fun purge(now: Long = System.currentTimeMillis(), keepMs: Long = KEEP_MS): Int =
        list().filter { now - it.deletedAt > keepMs }.count { remove(it.id) }

    private fun safe(id: String) = id.isNotBlank() && '/' !in id && '\\' !in id && id != "." && id != ".."

    companion object {
        const val DIR = ".trash"
        const val ORIGIN = ".origin"
        const val KEEP_MS = 30L * 24 * 3600 * 1000
    }
}

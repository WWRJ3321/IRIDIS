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

/**
 * 2.3.4: reference-only removal. A knowledge file IRIDIS cannot move into `.trash` (seen only through the
 * SAF grant / content:// — possibly the user's own file) is never deleted: its path is hidden from the
 * index instead, and restore simply un-hides it. Stored in app storage ([file]), one `state\tdeletedAt\tpath`
 * line each (state `t` = in the 回收站, `p` = removed for good, still hidden, file untouched). Pure JVM.
 */
class KbRefs(private val file: File) {
    private data class Row(val state: Char, val at: Long, val path: String)

    private fun rows(): List<Row> = runCatching {
        file.readLines().mapNotNull { l ->
            val p = l.split('\t', limit = 3)
            if (p.size == 3 && p[0].length == 1 && p[2].isNotBlank()) Row(p[0][0], p[1].toLongOrNull() ?: 0L, p[2]) else null
        }
    }.getOrDefault(emptyList())

    private fun save(r: List<Row>) = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(r.joinToString("") { "${it.state}\t${it.at}\t${it.path}\n" })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }.isSuccess

    /** Paths hidden from the knowledge base (in trash or removed for good). */
    fun hidden(): Set<String> = rows().map { it.path }.toSet()

    fun hide(path: String, now: Long = System.currentTimeMillis()): Boolean =
        save(rows().filterNot { it.path == path } + Row('t', now, path))

    /** Trash entries; ids are `ref-<index>` so they never clash with [KbTrash] ids. */
    fun items(): List<KbTrash.Item> = rows().withIndex().filter { it.value.state == 't' }
        .map { KbTrash.Item("$PREFIX${it.index}", it.value.path, false, it.value.at, 0L) }

    /** Un-hides [id]; returns the path (the reference is back, the file was never touched). */
    fun restore(id: String): String? {
        val i = id.removePrefix(PREFIX).toIntOrNull() ?: return null
        val r = rows(); val row = r.getOrNull(i)?.takeIf { it.state == 't' } ?: return null
        return if (save(r.filterIndexed { j, _ -> j != i })) row.path else null
    }

    /** Leaves the trash for good: stays hidden, the file is still not deleted. */
    fun remove(id: String): Boolean {
        val i = id.removePrefix(PREFIX).toIntOrNull() ?: return false
        val r = rows(); if (r.getOrNull(i)?.state != 't') return false
        return save(r.mapIndexed { j, x -> if (j == i) x.copy(state = 'p') else x })
    }

    fun empty(): Int { val r = rows(); val n = r.count { it.state == 't' }; save(r.map { it.copy(state = 'p') }); return n }

    fun purge(now: Long = System.currentTimeMillis(), keepMs: Long = KbTrash.KEEP_MS): Int {
        val r = rows(); val n = r.count { it.state == 't' && now - it.at > keepMs }
        if (n > 0) save(r.map { if (it.state == 't' && now - it.at > keepMs) it.copy(state = 'p') else it })
        return n
    }

    /** A file was added again at [path] (re-import): show it. */
    fun unhide(path: String) { val r = rows(); if (r.any { it.path == path }) save(r.filterNot { it.path == path }) }

    companion object { const val PREFIX = "ref-" }
}

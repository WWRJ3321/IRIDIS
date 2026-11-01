package com.iris.localai.knowledge

import com.iris.localai.agent.ExternalFiles
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.serialization.Serializable

/** 2.2.9: a file of the 知识库 page. [indexed] = the index holds its current version. */
data class KbFileInfo(val path: String, val size: Long, val chunks: Int, val indexed: Boolean, val cites: Int)

@Serializable
data class KbStat(
    val count: Int = 0,
    val lastAt: Long = 0,
    /** 2.2.9 F: citations in the week starting at [weekStart] (「本周引用 M 次」). */
    val weekStart: Long = 0,
    val week: Int = 0,
)

/** 2.2.9 F: calendar weeks (Monday 00:00, device zone) for 「本周引用 M 次」. */
object KbWeek {
    fun start(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            firstDayOfWeek = java.util.Calendar.MONDAY
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            val back = (get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
            add(java.util.Calendar.DAY_OF_MONTH, -back)
        }.timeInMillis

    /** One more citation of a file at [now]. */
    fun bump(s: KbStat?, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): KbStat {
        val ws = start(now, zone)
        val prev = s ?: KbStat()
        return KbStat(prev.count + 1, now, ws, if (prev.weekStart == ws) prev.week + 1 else 1)
    }

    fun thisWeek(stats: Collection<KbStat>, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val ws = start(now, zone)
        return stats.filter { it.weekStart == ws }.sumOf { it.week }
    }
}

@Serializable
data class KbStats(val files: Map<String, KbStat> = emptyMap())

/**
 * 2.1.0 知识库: `knowledge/` in the app's resource root (2.1.1: Download/IRIDIS/knowledge, beside — not inside —
 * the agent workspace, so only kb_search reads it; filesDir/iridis/knowledge in 应用私有目录 mode). The index is a
 * JSON file in app storage ([indexFile]); it is brought up to date on demand (before a search, or
 * from 重建索引) by comparing each file's size and mtime. Files only visible through the SAF grant
 * ([external]) are read that way (no mtime: changes are noticed by size, or by 重建索引).
 */
class KnowledgeBase(
    private val root: () -> File,
    private val indexFile: File,
    private val external: () -> ExternalFiles? = { null },
) {
    data class Status(val files: Int, val chunks: Int, val unsupported: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()
    private var data: KbIndexData? = null
    private var ranker: KbRanker? = null
    private var lastScan = 0L

    fun dir(): File = File(root(), FOLDER)

    private fun load(): KbIndexData = data ?: runCatching {
        json.decodeFromString(KbIndexData.serializer(), indexFile.readText())
    }.getOrNull().let { it ?: KbIndexData() }.also { data = it }

    /** Files in the folder (direct File access, plus the SAF view for files File cannot see). */
    fun scan(): Pair<List<KbSourceFile>, Int> {
        val out = LinkedHashMap<String, KbSourceFile>()
        var unsupported = 0
        val d = dir()
        d.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(d).path.replace(File.separatorChar, '/')
            if (KbFormats.supported(f.name) && f.length() <= MAX_BYTES) out[rel] = KbSourceFile(rel, f.length(), f.lastModified())
            else if (!f.name.startsWith(".")) unsupported++
        }
        external()?.let { ext ->
            fun walk(rel: String, depth: Int) {
                val entries = ext.list(if (rel.isEmpty()) FOLDER else "$FOLDER/$rel") ?: return
                for (e in entries) {
                    val p = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                    if (e.isDir) { if (depth < 4) walk(p, depth + 1) }
                    else if (p !in out) {
                        if (KbFormats.supported(e.name) && e.size <= MAX_BYTES) out[p] = KbSourceFile(p, e.size, 0L)
                        else if (!e.name.startsWith(".")) unsupported++
                    }
                }
            }
            walk("", 0)
        }
        return out.values.sortedBy { it.path.lowercase() } to unsupported
    }

    private fun read(rel: String): ByteArray? {
        val f = File(dir(), rel)
        if (f.isFile && f.canRead()) return runCatching { f.readBytes() }.getOrNull()
        return external()?.read("$FOLDER/$rel", MAX_BYTES)
    }

    /** Brings the index up to date. [force] = 重建索引 (reads every file again). Returns the status. */
    fun refresh(force: Boolean = false): Status = synchronized(lock) {
        val (files, unsupported) = scan()
        val old = load()
        val plan = KbChanges.plan(old.files, files, force)
        if (plan.changed || force) {
            val fresh = plan.reindex.map { f ->
                val text = read(f.path)?.let { KbFormats.text(f.path, it) }.orEmpty()
                KbFileEntry(f.path, f.size, f.mtime, KbChunker.chunk(text))
            }
            val next = KbIndexData(files = (plan.keep + fresh).sortedBy { it.path.lowercase() })
            data = next
            ranker = null
            runCatching {
                indexFile.parentFile?.mkdirs()
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(json.encodeToString(KbIndexData.serializer(), next))
                if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
            }
        }
        lastScan = System.currentTimeMillis()
        val d = load()
        nonEmpty = d.files.any { it.chunks.isNotEmpty() }
        Status(d.files.size, d.files.sumOf { it.chunks.size }, unsupported)
    }

    /** Whether the folder holds at least one indexable file (lists the folder; no reading). */
    fun hasFiles(): Boolean = runCatching { scan().first.isNotEmpty() }.getOrDefault(false).also { nonEmpty = it }

    @Volatile private var nonEmpty: Boolean? = null

    /**
     * Last known 「has files」 (an empty folder is listed again at most once a minute): decides whether
     * kb_search is listed and 引用知识库 runs, so the prompt does not flip on every turn.
     */
    fun nonEmptyCached(): Boolean {
        val known = nonEmpty
        if (known == true) return true
        // An empty folder is looked at again now and then (files dropped in from outside the app).
        if (known == false && System.currentTimeMillis() - lastEmptyCheck < 60_000L) return false
        lastEmptyCheck = System.currentTimeMillis()
        return hasFiles()
    }
    @Volatile private var lastEmptyCheck = 0L

    /** Top [limit] chunks for [query]; refreshes the index first (at most every few seconds). */
    fun search(query: String, limit: Int, boostFiles: Collection<String> = emptyList()): List<KbHit> = ranker().search(query, limit, boostFiles)

    /** 2.2.9: idf of a token over the current index (for KbQuery.build). */
    fun idf(token: String): Double = ranker().idf(token)

    /** 2.2.9: a file's chunks as indexed (empty when unknown). */
    fun chunksOf(path: String): List<String> = synchronized(lock) { load().files.firstOrNull { it.path == path }?.chunks.orEmpty() }

    private fun ranker(): KbRanker {
        return synchronized(lock) {
            if (System.currentTimeMillis() - lastScan > RESCAN_MS) refresh()
            ranker ?: KbRanker(load().files.flatMap { f -> f.chunks.mapIndexed { i, t -> KbChunk(f.path, t, i) } }).also { ranker = it }
        }
    }

    // ---- 2.2.9 D1: file list, delete, cite counts (kb_stats.json beside the index) ----

    private val statsFile: File get() = File(indexFile.parentFile ?: File("."), STATS)
    private var stats: MutableMap<String, KbStat>? = null

    private fun loadStats(): MutableMap<String, KbStat> = stats ?: runCatching {
        json.decodeFromString(KbStats.serializer(), statsFile.readText()).files.toMutableMap()
    }.getOrElse { HashMap() }.also { stats = it }

    private fun saveStats(m: Map<String, KbStat>) = runCatching {
        statsFile.parentFile?.mkdirs()
        val tmp = File(statsFile.path + ".tmp")
        tmp.writeText(json.encodeToString(KbStats.serializer(), KbStats(m.toMap())))
        if (!tmp.renameTo(statsFile)) { statsFile.delete(); tmp.renameTo(statsFile) }
    }

    /** Counts one citation for each distinct file of [files] (a reply was given its snippets). */
    fun cite(files: Collection<String>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (files.isEmpty()) return@synchronized
        val m = loadStats()
        files.distinct().forEach { f -> m[f] = KbWeek.bump(m[f], now) }
        saveStats(m)
    }

    /** 2.2.9 F: citations this week, files still in the folder only. */
    fun citesThisWeek(now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val present = scan().first.map { it.path }.toSet()
        KbWeek.thisWeek(loadStats().filterKeys { it in present }.values, now)
    }

    /** The files in the folder with their index state and cite counts (no reading; the index is not refreshed). */
    fun files(): List<KbFileInfo> = synchronized(lock) {
        val idx = load().files.associateBy { it.path }
        val st = loadStats()
        scan().first.map { f ->
            val e = idx[f.path]
            KbFileInfo(f.path, f.size, e?.chunks?.size ?: 0, e != null && e.size == f.size && (f.mtime == 0L || e.mtime == f.mtime), st[f.path]?.count ?: 0)
        }
    }

    /** Deletes [path] from knowledge/, drops it from the index and the stats. */
    fun delete(path: String): Boolean = synchronized(lock) {
        if (path.contains("..")) return@synchronized false
        val f = File(dir(), path)
        val gone = (f.isFile && f.delete()) || external()?.delete("$FOLDER/$path") == true || !f.exists()
        if (!gone) return@synchronized false
        val d = load()
        if (d.files.any { it.path == path }) {
            val next = d.copy(files = d.files.filterNot { it.path == path })
            data = next
            runCatching {
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(json.encodeToString(KbIndexData.serializer(), next))
                if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
            }
            nonEmpty = next.files.any { it.chunks.isNotEmpty() }
        }
        ranker = null
        loadStats().let { m -> if (m.remove(path) != null) saveStats(m) }
        true
    }

    /** Copies a picked file into knowledge/ (a free name when it exists). Returns its relative path. */
    fun add(name: String, bytes: ByteArray): String? = synchronized(lock) {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").trim().ifEmpty { "file.txt" }
        val d = dir().apply { mkdirs() }
        var target = File(d, clean)
        var i = 2
        while (target.exists()) {
            val base = clean.substringBeforeLast('.', clean); val ext = clean.substringAfterLast('.', "")
            target = File(d, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++
        }
        val ok = runCatching { target.writeBytes(bytes); true }.getOrDefault(false)
        val rel = if (ok) target.name else if (external()?.write("$FOLDER/${target.name}", bytes) == true) target.name else null
        lastScan = 0
        rel
    }

    companion object {
        const val FOLDER = "knowledge"
        const val MAX_BYTES = 4_000_000L
        const val STATS = "kb_stats.json"
        private const val RESCAN_MS = 5_000L
    }
}

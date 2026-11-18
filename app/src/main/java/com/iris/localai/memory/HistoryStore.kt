package com.iris.localai.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ln

/**
 * Minimal SQL access, so the history store runs on Android's own SQLite in the app and on
 * sqlite-jdbc in the JVM unit tests. Row values are Long / Double / String / ByteArray / null.
 */
interface SqlDriver {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<Array<Any?>>
    fun <T> transaction(block: () -> T): T
}

/** One raw history record: a chat message, or one tool step of an agent reply. */
data class HistoryRecord(
    /** Unique: message id, or "messageId#stepId" for a tool step. */
    val key: String,
    val conversationId: String,
    val messageId: String,
    /** Position in the conversation (message index × 100 + step index), for ordering. */
    val seq: Long,
    /** "user" | "assistant" | "tool". */
    val role: String,
    val tool: String? = null,
    /** Full text as stored (assistant: with reasoning; tool: args + result + output). */
    val content: String,
    /** Text that is indexed for search (assistant: reasoning removed). */
    val searchText: String = content,
    val createdAt: Long = 0,
)

data class HistoryHit(
    val conversationId: String,
    val messageId: String,
    val role: String,
    val tool: String?,
    val snippet: String,
    val createdAt: Long,
    /** BM25 score, higher = better (FTS5's bm25() sign flipped). */
    val score: Double,
)

/**
 * raw history: every message and tool step in SQLite with a full-text index, for the
 * agent's `recall` tool. FTS5 when the platform SQLite has it, FTS4 otherwise (BM25 is then
 * computed from matchinfo). Chinese has no spaces, so the index text puts every CJK character
 * on its own and a CJK query becomes a phrase: substring-exact, and BM25 still ranks by rarity.
 * Conversations themselves stay in their JSON files (the source of truth); this is an index
 * that can be rebuilt from them at any time.
 */
class HistoryStore(private val db: SqlDriver, allowFts5: Boolean = true) {
    /** "fts5" or "fts4". */
    val ftsVersion: String
    private val lock = Any()

    init {
        val version = (db.query("PRAGMA user_version").firstOrNull()?.get(0) as? Long ?: 0L).toInt()
        if (version < 1) {
            db.exec("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
            db.exec(
                "CREATE TABLE IF NOT EXISTS hist(" +
                    "rid INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL UNIQUE, conv_id TEXT NOT NULL, " +
                    "msg_id TEXT NOT NULL, seq INTEGER NOT NULL, role TEXT NOT NULL, tool TEXT, content TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, hash TEXT NOT NULL)",
            )
            db.exec("CREATE INDEX IF NOT EXISTS hist_conv ON hist(conv_id)")
            // Fresh index: existing conversations are indexed once from their JSON files.
            db.exec("INSERT OR REPLACE INTO meta(k, v) VALUES('backfill', '1')")
        }
        // Future schema changes: `if (version < 2) { … }` here, then bump SCHEMA_VERSION.
        val stored = meta("fts")
        ftsVersion = if (stored != null && tableExists("hist_fts")) stored else {
            val v = if (allowFts5 && tryExec("CREATE VIRTUAL TABLE IF NOT EXISTS hist_fts USING fts5(body)")) "fts5" else {
                db.exec("CREATE VIRTUAL TABLE IF NOT EXISTS hist_fts USING fts4(body, tokenize=simple)")
                "fts4"
            }
            setMeta("fts", v)
            if (stored != null) {
                // The index table was lost: forget the rows too and rebuild from the JSON files.
                db.exec("DELETE FROM hist")
                setMeta("backfill", "1")
            }
            v
        }
        if (version < SCHEMA_VERSION) db.exec("PRAGMA user_version = $SCHEMA_VERSION")
    }

    /** True until [markBackfilled]: the app then indexes every saved conversation once. */
    val needsBackfill: Boolean get() = meta("backfill") == "1"
    fun markBackfilled() = setMeta("backfill", "0")

    /**
     * Makes the index hold exactly [records] for [conversationId]: unchanged records are skipped
     * (content hash), changed ones re-indexed, and records no longer present removed.
     */
    fun syncConversation(conversationId: String, records: List<HistoryRecord>) = synchronized(lock) {
        db.transaction {
            val existing = HashMap<String, Pair<Long, String>>()
            db.query("SELECT key, rid, hash FROM hist WHERE conv_id = ?", listOf(conversationId)).forEach { r ->
                existing[r[0] as String] = (r[1] as Long) to (r[2] as String)
            }
            val keep = HashSet<String>()
            for (rec in records) {
                keep += rec.key
                val h = hash(rec)
                val old = existing[rec.key]
                if (old != null && old.second == h) continue
                if (old != null) {
                    db.exec("DELETE FROM hist_fts WHERE rowid = ?", listOf(old.first))
                    db.exec("DELETE FROM hist WHERE rid = ?", listOf(old.first))
                }
                db.exec(
                    "INSERT OR REPLACE INTO hist(key, conv_id, msg_id, seq, role, tool, content, created_at, hash) VALUES(?,?,?,?,?,?,?,?,?)",
                    listOf(rec.key, rec.conversationId, rec.messageId, rec.seq, rec.role, rec.tool, rec.content, rec.createdAt, h),
                )
                val rid = db.query("SELECT rid FROM hist WHERE key = ?", listOf(rec.key)).first()[0] as Long
                db.exec("INSERT INTO hist_fts(rowid, body) VALUES(?, ?)", listOf(rid, FtsText.index(rec.searchText)))
            }
            for ((k, v) in existing) if (k !in keep) {
                db.exec("DELETE FROM hist_fts WHERE rowid = ?", listOf(v.first))
                db.exec("DELETE FROM hist WHERE rid = ?", listOf(v.first))
            }
        }
    }

    fun deleteConversation(conversationId: String) = synchronized(lock) {
        db.transaction {
            db.exec("DELETE FROM hist_fts WHERE rowid IN (SELECT rid FROM hist WHERE conv_id = ?)", listOf(conversationId))
            db.exec("DELETE FROM hist WHERE conv_id = ?", listOf(conversationId))
        }
    }

    fun deleteAll() = synchronized(lock) {
        db.transaction {
            db.exec("DELETE FROM hist_fts")
            db.exec("DELETE FROM hist")
        }
    }

    fun count(conversationId: String? = null): Int = synchronized(lock) {
        val r = if (conversationId == null) db.query("SELECT COUNT(*) FROM hist")
        else db.query("SELECT COUNT(*) FROM hist WHERE conv_id = ?", listOf(conversationId))
        (r.firstOrNull()?.get(0) as? Long ?: 0L).toInt()
    }

    /**
     * BM25-ranked search. [conversationId] null = all conversations. Ties go to the newer record.
     * [exclude] = message ids left out (the request being answered right now).
     */
    fun search(query: String, conversationId: String?, limit: Int = 5, exclude: Set<String> = emptySet()): List<HistoryHit> = synchronized(lock) {
        val match = FtsText.query(query) ?: return emptyList()
        val where = if (conversationId != null) " AND h.conv_id = ?" else ""
        val args = listOf<Any?>(match) + listOfNotNull(conversationId)
        val cols = "h.conv_id, h.msg_id, h.role, h.tool, h.content, h.created_at, h.seq"
        val rows: List<Pair<Array<Any?>, Double>> = if (ftsVersion == "fts5") {
            db.query("SELECT $cols, bm25(hist_fts) FROM hist_fts JOIN hist h ON h.rid = hist_fts.rowid WHERE hist_fts MATCH ?$where", args)
                .map { it to -((it[7] as Number).toDouble()) }
        } else {
            db.query("SELECT $cols, matchinfo(hist_fts, 'pcnalx') FROM hist_fts JOIN hist h ON h.rid = hist_fts.rowid WHERE hist_fts MATCH ?$where", args)
                .map { it to Bm25.fromMatchinfo(it[7] as ByteArray) }
        }
        val terms = FtsText.terms(query)
        rows.asSequence()
            .filter { (it.first[1] as String) !in exclude }
            .sortedWith(compareByDescending<Pair<Array<Any?>, Double>> { it.second }.thenByDescending { it.first[5] as Long }.thenByDescending { it.first[6] as Long })
            .take(limit.coerceIn(1, 20))
            .map { (r, score) ->
                HistoryHit(
                    conversationId = r[0] as String, messageId = r[1] as String, role = r[2] as String, tool = r[3] as String?,
                    snippet = FtsText.snippet(r[4] as String, terms), createdAt = r[5] as Long, score = score,
                )
            }.toList()
    }

    private fun hash(r: HistoryRecord): String {
        val md = MessageDigest.getInstance("SHA-1")
        listOf(r.conversationId, r.messageId, r.seq.toString(), r.role, r.tool.orEmpty(), r.content, r.searchText, r.createdAt.toString())
            .forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun tryExec(sql: String): Boolean = try { db.exec(sql); true } catch (_: Throwable) { false }
    private fun tableExists(name: String) = db.query("SELECT name FROM sqlite_master WHERE name = ?", listOf(name)).isNotEmpty()
    private fun meta(k: String): String? = runCatching { db.query("SELECT v FROM meta WHERE k = ?", listOf(k)).firstOrNull()?.get(0) as String? }.getOrNull()
    private fun setMeta(k: String, v: String) = db.exec("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", listOf(k, v))

    companion object { const val SCHEMA_VERSION = 1 }
}

/** Text normalisation shared by indexing and querying (pure, unit-tested). */
object FtsText {
    private fun isCjk(c: Char): Boolean {
        val x = c.code
        return x in 0x3400..0x4DBF || x in 0x4E00..0x9FFF || x in 0xF900..0xFAFF || x in 0x3040..0x30FF || x in 0xAC00..0xD7AF
    }

    /** Lower-case words; every CJK character a word of its own; punctuation dropped. */
    fun index(text: String): String {
        val sb = StringBuilder(text.length * 2)
        var inWord = false
        for (ch in text) {
            when {
                isCjk(ch) -> { if (sb.isNotEmpty()) sb.append(' '); sb.append(ch); sb.append(' '); inWord = false }
                ch.isLetterOrDigit() || ch == '_' -> { sb.append(ch.lowercaseChar()); inWord = true }
                else -> { if (inWord) sb.append(' '); inWord = false }
            }
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    /** Search terms of a query: ASCII/Latin words and CJK runs (each run matched as a phrase). */
    fun terms(query: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var cjkRun = false
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        for (ch in query) {
            when {
                isCjk(ch) -> { if (!cjkRun) flush(); cjkRun = true; cur.append(ch) }
                ch.isLetterOrDigit() || ch == '_' -> { if (cjkRun) flush(); cjkRun = false; cur.append(ch.lowercaseChar()) }
                else -> { flush(); cjkRun = false }
            }
        }
        flush()
        return out.distinct().take(12)
    }

    /** FTS MATCH expression: every term quoted (no operator injection), joined with OR. null = nothing to search. */
    fun query(q: String): String? {
        val t = terms(q)
        if (t.isEmpty()) return null
        return t.joinToString(" OR ") { "\"" + index(it).replace("\"", "") + "\"" }
    }

    /** About 120 characters around the first hit, on one line. */
    fun snippet(content: String, terms: List<String>, width: Int = 120): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val lower = flat.lowercase()
        val at = terms.mapNotNull { t -> lower.indexOf(t.lowercase()).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - width / 3).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }
}

/** Okapi BM25 (k1 = 1.2, b = 0.75) from FTS4 `matchinfo(t, 'pcnalx')` (FTS5 has bm25() built in). */
object Bm25 {
    private const val K1 = 1.2
    private const val B = 0.75

    fun fromMatchinfo(blob: ByteArray): Double {
        val b = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder()).asIntBuffer()
        val ints = IntArray(b.remaining()).also { b.get(it) }
        val p = ints[0]
        val c = ints[1]
        val n = ints[2].toDouble()
        var score = 0.0
        for (col in 0 until c) {
            val avg = ints[3 + col].toDouble().coerceAtLeast(1.0)
            val len = ints[3 + c + col].toDouble()
            for (ph in 0 until p) {
                val x = 3 + 2 * c + 3 * (ph * c + col)
                val tf = ints[x].toDouble()
                val df = ints[x + 2].toDouble()
                if (tf <= 0) continue
                score += idf(n, df) * tf * (K1 + 1) / (tf + K1 * (1 - B + B * len / avg))
            }
        }
        return score
    }

    /** Always positive (the "+1" form), so very common terms still count a little. */
    fun idf(n: Double, df: Double): Double = ln((n - df + 0.5) / (df + 0.5) + 1.0)
}

package com.iris.localai.data

/**
 * 聊天历史全文搜索 — titles and message text. Matching / snippets are pure and unit-tested;
 * [MessageSearchIndex] keeps the plain text of each saved conversation in memory (re-read only when
 * its updatedAt changes) so typing does not decode every JSON file again.
 */
object HistorySearch {
    data class Doc(val id: String, val title: String, val updatedAt: Long, val messages: List<Msg>)
    data class Msg(val id: String, val user: Boolean, val text: String)

    /** One matching message: [snippet] with [ranges] to highlight (offsets inside the snippet). */
    data class Hit(
        val conversationId: String, val title: String, val updatedAt: Long,
        val messageId: String, val user: Boolean, val snippet: String, val ranges: List<IntRange>,
    )

    const val PER_CONVERSATION = 3
    const val MAX_HITS = 60
    const val CONTEXT = 24

    private val THINK = Regex("<think>[\\s\\S]*?(</think>|$)")

    /** The text a message shows (no <think> reasoning), whitespace collapsed. */
    fun plain(content: String): String = content.replace(THINK, " ").replace(Regex("\\s+"), " ").trim()

    /** Search words: split on spaces; every word must appear in the same message. */
    fun terms(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()

    /** All [term] occurrences in [text], case-insensitive. */
    fun occurrences(text: String, term: String): List<IntRange> {
        if (term.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = text.indexOf(term, ignoreCase = true)
        while (i >= 0) { out += i until i + term.length; i = text.indexOf(term, i + term.length, ignoreCase = true) }
        return out
    }

    /** A window of [text] around the first match, «…» where cut, and every term's ranges inside it. */
    fun snippet(text: String, terms: List<String>, context: Int = CONTEXT): Pair<String, List<IntRange>>? {
        val firsts = terms.map { occurrences(text, it).firstOrNull() ?: return null }
        val first = firsts.minOf { it.first }
        val start = (first - context).coerceAtLeast(0)
        val end = (firsts.maxOf { it.last } + 1 + context * 2).coerceAtMost(text.length).coerceAtLeast(first)
        val pre = if (start > 0) "…" else ""
        val post = if (end < text.length) "…" else ""
        val body = text.substring(start, end)
        val shift = pre.length
        val ranges = terms.flatMap { occurrences(body, it) }.map { (it.first + shift)..(it.last + shift) }.sortedBy { it.first }
        return (pre + body + post) to merge(ranges)
    }

    /** Overlapping / touching ranges merged. */
    fun merge(ranges: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in ranges.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    /** Message hits, newest conversation first, newest message first inside one. */
    fun search(docs: List<Doc>, query: String, perConversation: Int = PER_CONVERSATION, max: Int = MAX_HITS): List<Hit> {
        val t = terms(query)
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        for (d in docs.sortedByDescending { it.updatedAt }) {
            var n = 0
            for (m in d.messages.asReversed()) {
                if (n >= perConversation || out.size >= max) break
                val (snip, ranges) = snippet(m.text, t) ?: continue
                out += Hit(d.id, d.title, d.updatedAt, m.id, m.user, snip, ranges)
                n++
            }
            if (out.size >= max) break
        }
        return out
    }

    fun docOf(c: Conversation): Doc = Doc(c.id, c.title, c.updatedAt,
        c.messages.map { Msg(it.id, it.role == Role.USER, plain(it.content)) }.filter { it.text.isNotEmpty() })
}

/** In-memory plain text of saved conversations, refreshed per conversation when its updatedAt changes. */
class MessageSearchIndex(private val repo: ConversationRepository) {
    private val docs = java.util.concurrent.ConcurrentHashMap<String, HistorySearch.Doc>()

    suspend fun search(summaries: List<ConversationSummary>, query: String): List<HistorySearch.Hit> {
        val live = summaries.map { it.id }.toSet()
        docs.keys.retainAll(live)
        for (s in summaries) {
            val d = docs[s.id]
            if (d == null || d.updatedAt != s.updatedAt) repo.load(s.id)?.let { docs[s.id] = HistorySearch.docOf(it).copy(title = s.title, updatedAt = s.updatedAt) }
            else if (d.title != s.title) docs[s.id] = d.copy(title = s.title)
        }
        return HistorySearch.search(docs.values.toList(), query)
    }
}

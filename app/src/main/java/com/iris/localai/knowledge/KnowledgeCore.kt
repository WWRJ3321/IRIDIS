package com.iris.localai.knowledge

import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * 2.1.0 知识库: the pure part (JVM-tested). Files the user drops into the workspace's
 * `knowledge/` folder are cut into ~500-character chunks and ranked with BM25 over a tokenizer
 * that splits Latin text into words and CJK text into overlapping bigrams. No embeddings.
 */
object KbTokenizer {
    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    /** "IRIDIS 本地知识库v2" → [iridis, 本地, 地知, 知识, 识库, v2]. A lone CJK character stays a unigram. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        val run = StringBuilder()
        fun flushWord() { if (word.isNotEmpty()) { out += word.toString(); word.setLength(0) } }
        fun flushRun() {
            if (run.length == 1) out += run.toString()
            else for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
            run.setLength(0)
        }
        for (ch in text) {
            when {
                isCjk(ch) -> { flushWord(); run.append(ch) }
                ch.isLetterOrDigit() -> { flushRun(); word.append(ch.lowercaseChar()) }
                else -> { flushWord(); flushRun() }
            }
        }
        flushWord(); flushRun()
        return out
    }
}

object KbChunker {
    const val SIZE = 500
    const val OVERLAP = 80

    /**
     * Cuts [text] into pieces of at most [size] characters, preferring a paragraph, then a sentence
     * end, then a space; consecutive pieces share about [overlap] characters. Pure.
     */
    fun chunk(text: String, size: Int = SIZE, overlap: Int = OVERLAP): List<String> {
        val t = text.replace("\r\n", "\n").replace(Regex("[ \\t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= size) return listOf(t)
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            var end = (start + size).coerceAtMost(t.length)
            if (end < t.length) {
                val window = t.substring(start, end)
                val min = size / 2
                val cut = listOf(window.lastIndexOf("\n\n"), window.indexOfLast { it in "。！？.!?；;\n" }, window.lastIndexOf(' '))
                    .firstOrNull { it >= min }
                if (cut != null) end = start + cut + 1
            }
            out += t.substring(start, end).trim()
            if (end >= t.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out.filter { it.isNotEmpty() }
    }
}

@Serializable
data class KbChunk(val file: String, val text: String, val index: Int = 0)

/** One indexed file: its path in knowledge/, and size + mtime to notice changes. */
@Serializable
data class KbFileEntry(val path: String, val size: Long, val mtime: Long, val chunks: List<String> = emptyList())

@Serializable
data class KbIndexData(val version: Int = 1, val files: List<KbFileEntry> = emptyList())

/** A file as seen in the folder (mtime 0 when unknown, e.g. through the SAF grant). */
data class KbSourceFile(val path: String, val size: Long, val mtime: Long)

/** 2.2.9: [chunkIndex] = position of the chunk in its file (-1 unknown); 「第 chunkIndex+1 段」. */
data class KbHit(val file: String, val text: String, val score: Double, val chunkIndex: Int = -1, val covers: List<Int> = emptyList()) {
    /** 2.3.2: (file, chunk) pairs this hit's text holds (after [KbMerge.expand]: the merged neighbours too). */
    val keys: Set<Pair<String, Int>> get() = (covers.ifEmpty { listOf(chunkIndex) }).filter { it >= 0 }.map { file to it }.toSet()
}

object KbChanges {
    data class Plan(val keep: List<KbFileEntry>, val reindex: List<KbSourceFile>, val removed: List<String>) {
        val changed: Boolean get() = reindex.isNotEmpty() || removed.isNotEmpty()
    }

    /** Which files must be (re)read: new, or size / mtime differ. Pure. */
    fun plan(old: List<KbFileEntry>, now: List<KbSourceFile>, force: Boolean = false): Plan {
        val byPath = old.associateBy { it.path }
        val keep = ArrayList<KbFileEntry>()
        val re = ArrayList<KbSourceFile>()
        for (f in now) {
            val o = byPath[f.path]
            if (!force && o != null && o.size == f.size && o.mtime == f.mtime) keep += o else re += f
        }
        val present = now.map { it.path }.toSet()
        return Plan(keep, re, old.map { it.path }.filter { it !in present })
    }
}

/** BM25 over the chunks of an index. Build once per index version; [search] is pure. */
class KbRanker(chunks: List<KbChunk>, private val k1: Double = 1.2, private val b: Double = 0.75) {
    private val docs = chunks
    private val tf: List<Map<String, Int>> = chunks.map { c -> KbTokenizer.tokens(c.file.substringAfterLast('/') + " " + c.text).groupingBy { it }.eachCount() }
    private val len = tf.map { m -> m.values.sum() }
    private val avg = if (len.isEmpty()) 1.0 else len.average().coerceAtLeast(1.0)
    private val df: Map<String, Int> = HashMap<String, Int>().also { d -> tf.forEach { m -> m.keys.forEach { d[it] = (d[it] ?: 0) + 1 } } }

    val size: Int get() = docs.size

    /** 2.2.9: inverse document frequency of [token] over the chunks (0 when unseen). */
    fun idf(token: String): Double {
        val d = df[token] ?: return 0.0
        val n = docs.size.toDouble()
        return ln(1 + (n - d + 0.5) / (d + 0.5))
    }

    /** 2.2.9: [boostFiles] = files the previous turn hit (a follow-up likely continues there): score ×[BOOST]. */
    fun search(query: String, limit: Int, boostFiles: Collection<String> = emptyList()): List<KbHit> {
        val q = KbTokenizer.tokens(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        val scored = ArrayList<KbHit>()
        for (i in docs.indices) {
            var s = 0.0
            val m = tf[i]
            for (t in q) {
                val f = m[t] ?: continue
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len[i] / avg))
            }
            if (s > 0) scored += KbHit(docs[i].file, docs[i].text, if (docs[i].file in boostFiles) s * BOOST else s, docs[i].index)
        }
        // One file may fill the list only when nothing else matches.
        val sorted = scored.sortedByDescending { it.score }
        val out = ArrayList<KbHit>()
        val perFile = HashMap<String, Int>()
        for (h in sorted) {
            if ((perFile[h.file] ?: 0) >= 2) continue
            out += h; perFile[h.file] = (perFile[h.file] ?: 0) + 1
            if (out.size >= limit) return out
        }
        for (h in sorted) { if (out.size >= limit) break; if (h !in out) out += h }
        return out
    }
}

/** 2.2.9: score factor for files the previous turn cited. */
const val BOOST = 1.3

/** 2.2.9 D2: follow-up questions borrow the previous user question's keywords. Pure. */
object KbQuery {
    private val LEAD = Regex("^(?:那|这|它|第[0-9一二三四五六七八九十百]+|上面|刚才|还有)")
    private val TAIL = Regex("(?:呢|那个|这个)[？?。！!\\s]*$")
    /** Bigrams too common to say what a question is about. */
    val STOP = setOf("什么", "怎么", "么时", "时候", "一下", "是不", "不是", "可以", "这个", "那个", "我们", "你们", "一个", "有没", "没有",
        "我的", "你的", "的是", "是什", "怎样", "如何", "多少", "哪里", "请问", "还有", "上面", "刚才", "the", "a", "is", "of", "to", "and", "what")
    const val TOP_K = 4
    /** 2.3.2: acknowledgements / continuations that say nothing about the subject. */
    val ACK = setOf("好的", "好", "嗯", "嗯嗯", "继续", "谢谢", "多谢", "感谢", "收到", "明白", "了解", "知道", "道了", "行", "对", "是的",
        "没问", "问题", "可以", "ok", "okay", "thanks", "thank", "you", "yes", "go", "on", "continue", "next", "sure", "got", "it", "好吧", "然后", "接着", "再来")

    fun meaningful(text: String): List<String> = KbTokenizer.tokens(text).filter { it !in STOP }

    /** 2.3.2: terms that say what [current] is about; empty (好的 / 继续 / 谢谢) → no search at all. */
    fun effective(current: String): List<String> = meaningful(current).filter { it !in ACK }.distinct()

    fun isFollowUp(current: String): Boolean {
        val t = current.trim()
        return meaningful(t).distinct().size < 4 || LEAD.containsMatchIn(t) || TAIL.containsMatchIn(t)
    }

    /**
     * [current] alone, or — when it is short or starts / ends with a pointer (那/这/它/第N/上面/刚才/还有/呢) —
     * [current] + the top [TOP_K] keywords of [previousUser] (by [idf], highest first). [previousHitsFiles]
     * are not part of the text: pass them to the search as boostFiles.
     */
    @Suppress("UNUSED_PARAMETER")
    fun build(current: String, previousUser: String?, previousHitsFiles: List<String> = emptyList(), idf: (String) -> Double = { 1.0 }): String {
        val prev = previousUser?.trim().orEmpty()
        if (prev.isEmpty() || !isFollowUp(current)) return current
        val have = KbTokenizer.tokens(current).toSet()
        val words = meaningful(prev).distinct().filter { it !in have }
            .withIndex().sortedWith(compareByDescending<IndexedValue<String>> { idf(it.value) }.thenBy { it.index })
            .take(TOP_K).map { it.value }
        return if (words.isEmpty()) current else current.trim() + " " + words.joinToString(" ")
    }
}

/** 2.2.9 D3: a hit grows into its neighbouring chunks of the same file. Pure. */
object KbMerge {
    /** Joins two consecutive chunks, dropping the shared overlap (≈ [KbChunker.OVERLAP] characters). */
    fun join(a: String, b: String): String {
        val max = minOf(a.length, b.length, KbChunker.OVERLAP + 40)
        for (k in max downTo 8) if (a.endsWith(b.substring(0, k))) return a + b.substring(k)
        return a + "\n" + b
    }

    /**
     * Each hit takes chunk i-1 and/or i+1 of its file ([chunks] gives a file's chunks) while the text
     * stays within [maxChars]; a hit already covered by an earlier (better) one is dropped.
     */
    fun expand(hits: List<KbHit>, chunks: (String) -> List<String>, maxChars: Int, already: Set<Pair<String, Int>> = emptySet()): List<KbHit> {
        val covered = HashSet<Pair<String, Int>>(already)
        val out = ArrayList<KbHit>()
        for (h in hits) {
            if (h.chunkIndex >= 0 && (h.file to h.chunkIndex) in covered) continue
            val all = if (h.chunkIndex >= 0) chunks(h.file) else emptyList()
            if (h.chunkIndex !in all.indices || all[h.chunkIndex] != h.text) { out += h; continue }
            var text = h.text
            var lo = h.chunkIndex; var hi = h.chunkIndex
            covered += h.file to h.chunkIndex
            for (step in 0 until 2) {
                val next = hi + 1
                if (next in all.indices && (h.file to next) !in covered) {
                    val j = join(text, all[next]); if (j.length <= maxChars) { text = j; hi = next; covered += h.file to next }
                }
                val prev = lo - 1
                if (prev in all.indices && (h.file to prev) !in covered) {
                    val j = join(all[prev], text); if (j.length <= maxChars) { text = j; lo = prev; covered += h.file to prev }
                }
            }
            out += h.copy(text = text, covers = (lo..hi).toList())
        }
        return out
    }
}

/** 2.2.9 D4: 「租房合同.md · 第 3 段」 labels kept on a reply (ChatMessage.kbSources). Pure. */
object KbSource {
    private val RE = Regex("^(.+) · 第 (\\d+) 段$")
    fun label(h: KbHit): String = if (h.chunkIndex >= 0) "${h.file} · 第 ${h.chunkIndex + 1} 段" else h.file
    /** file + 0-based chunk index (-1 when the label has none). */
    fun parse(label: String): Pair<String, Int> = RE.find(label)?.let { it.groupValues[1] to it.groupValues[2].toInt() - 1 } ?: (label to -1)
    fun chip(label: String): String = "出处：$label"
    fun files(labels: List<String>): List<String> = labels.map { parse(it).first }.distinct()
}

/** What the model reads. Pure. */
object KbPrompt {
    const val INJECT_SNIPPETS = 3
    const val INJECT_CHARS = 1200
    const val TOOL_SNIPPETS = 4

    /** Block added to the user's turn in 对话 mode: at most [maxChars] characters of snippet text. */
    fun inject(hits: List<KbHit>, maxChars: Int = INJECT_CHARS): String {
        if (maxChars <= 0) return ""
        if (hits.isEmpty()) return ""
        val per = maxChars / hits.size
        return buildString {
            append("<knowledge>\n参考资料（来自用户的知识库，相关时引用并注明文件名，无关就忽略）：\n")
            hits.forEachIndexed { i, h -> append("[${i + 1}] ").append(h.file).append(": ").append(clip(h.text, per)).append('\n') }
            append("</knowledge>")
        }
    }

    fun toolOutput(query: String, hits: List<KbHit>, perChars: Int = 400): String =
        if (hits.isEmpty()) "No knowledge snippets match \"$query\"."
        else hits.mapIndexed { i, h -> "${i + 1}. [${h.file}] ${clip(h.text, perChars)}" }.joinToString("\n")

    /** Label under a reply that was given knowledge snippets. */
    fun chip(n: Int): String = "引用 $n 段知识库"

    fun clip(s: String, n: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= n) t else t.substring(0, (n - 1).coerceAtLeast(1)) + "…"
    }
}

/** Which files go into the index, and how their bytes become text. Pure. */
object KbFormats {
    val TEXT = setOf("txt", "md", "markdown", "csv", "json", "log", "srt")
    val HTML = setOf("html", "htm")
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun supported(name: String) = !name.startsWith(".") && ext(name).let { it in TEXT || it in HTML }

    fun text(name: String, bytes: ByteArray): String? {
        if (bytes.take(4096).any { it == 0.toByte() }) return null
        val s = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (s.isNotEmpty() && s.count { it == '\uFFFD' } > s.length / 20) return null
        return if (ext(name) in HTML) com.iris.localai.agent.HtmlText.toText(s) else s
    }
}

/** 2.1.0 引用知识库 in 对话 mode. */
object KbChat {
    /**
     * Snippets for [current] (2.3.2: no search when it has no effective term, e.g. 好的 / 继续); a follow-up
     * borrows [previousUser]'s keywords, and a hit that only matches through them needs [CARRIED_MIN_SCORE].
     * [exclude] = (file, chunk) already in a kept block: skipped, and not merged in as a neighbour.
     */
    fun hits(
        kb: KnowledgeBase, current: String, previousUser: String? = null, boostFiles: List<String> = emptyList(),
        maxChars: Int = KbPrompt.INJECT_CHARS, exclude: Set<Pair<String, Int>> = emptySet(),
    ): List<KbHit> = runCatching {
        if (KbQuery.effective(current).isEmpty() || maxChars <= 0) return emptyList()
        if (!kb.hasFiles()) return emptyList()
        val q = runCatching { KbQuery.build(current, previousUser, boostFiles, kb::idf) }.getOrDefault(current)
        val pool = KbPrompt.INJECT_SNIPPETS + exclude.size.coerceAtMost(6)
        var found = keep(kb.search(q, pool, boostFiles))
        if (q != current) {
            val direct = keep(kb.search(current, pool * 3)).flatMap { it.keys }.toSet()
            found = gateCarried(found, direct)
        }
        found = dedupe(found, exclude).take(KbPrompt.INJECT_SNIPPETS)
        KbMerge.expand(found, kb::chunksOf, maxChars / found.size.coerceAtLeast(1), exclude)
    }.getOrDefault(emptyList())

    /** 2.3.2: a hit the current words alone do not reach ([direct]) stays only with score ≥ [CARRIED_MIN_SCORE]. Pure. */
    fun gateCarried(hits: List<KbHit>, direct: Set<Pair<String, Int>>): List<KbHit> =
        hits.filter { h -> h.keys.any { it in direct } || h.score >= CARRIED_MIN_SCORE }

    /** 2.3.2: drops hits whose chunk is already injected in a kept block. Pure. */
    fun dedupe(hits: List<KbHit>, exclude: Set<Pair<String, Int>>): List<KbHit> =
        if (exclude.isEmpty()) hits else hits.filter { h -> h.chunkIndex < 0 || (h.file to h.chunkIndex) !in exclude }

    /** 2.3.2: carried-over keywords alone must score at least this. */
    const val CARRIED_MIN_SCORE = 1.5

    /** Below this BM25 score a snippet shares only a common word or two with the question. */
    const val MIN_SCORE = 0.6
    /** 2.2.9 D5: a hit is kept when it scores at least this share of the best one. */
    const val REL_SCORE = 0.35

    fun threshold(hits: List<KbHit>): Double = maxOf(MIN_SCORE, (hits.maxOfOrNull { it.score } ?: 0.0) * REL_SCORE)

    /** 2.2.9 D5: adaptive cut: max(0.6, top1 × 0.35). Pure. */
    fun keep(hits: List<KbHit>): List<KbHit> { val t = threshold(hits); return hits.filter { it.score >= t } }

    /** Puts [block] in front of the message [id]'s text (prompt only; the stored message is untouched). Pure. */
    fun attach(msgs: List<com.iris.localai.util.CtxMessage>, id: String, block: String): List<com.iris.localai.util.CtxMessage> =
        msgs.map { if (it.id == id) com.iris.localai.util.CtxMessage(it.id, it.role, block + "\n\n" + it.content) else it }

    /** 2.3: every message of [msgs] that has a block in [blocks] gets it in front (prompt only). Pure. */
    fun attachAll(msgs: List<com.iris.localai.util.CtxMessage>, blocks: Map<String, String>): List<com.iris.localai.util.CtxMessage> =
        if (blocks.isEmpty()) msgs
        else msgs.map { m -> blocks[m.id]?.let { com.iris.localai.util.CtxMessage(m.id, m.role, it + "\n\n" + m.content) } ?: m }

    /** 2.3: stores [block] for [id], keeping at most [MAX_KEPT] (oldest dropped first). */
    fun <T> remember(blocks: MutableMap<String, T>, id: String, block: T) {
        blocks.remove(id)
        blocks[id] = block
        val it = blocks.keys.iterator()
        while (blocks.size > MAX_KEPT && it.hasNext()) { it.next(); it.remove() }
    }

    /** Memory blocks kept per message (2.3.2: knowledge blocks are bounded by [KbBudget] instead). */
    const val MAX_KEPT = 64
}

/** 2.3.2: a knowledge block as injected, with the chunks it holds (for dedupe) and its size. */
data class KbBlock(val text: String, val keys: Set<Pair<String, Int>>) {
    val tokens: Int get() = com.iris.localai.util.TokenEstimator.estimate(text)
}

/**
 * 2.3.2 知识库预算: only the newest [KEEP_TURNS] user messages keep their block in the prompt, together at
 * most [SHARE] of the window; over budget the oldest goes first. Older kept blocks are never rewritten,
 * so the prompt prefix up to the newest message stays byte-stable (KV-cache reuse). Pure.
 */
object KbBudget {
    const val KEEP_TURNS = 2
    const val SHARE = 0.15
    /** Characters ≈ tokens for CJK; a conservative factor for the per-turn character cap. */
    fun limit(nCtx: Int): Int = (nCtx.coerceAtLeast(0) * SHARE).toInt()

    /**
     * Blocks that go into the prompt. [userIds] = the conversation's user message ids, oldest first.
     */
    fun select(userIds: List<String>, blocks: Map<String, KbBlock>, nCtx: Int): Map<String, KbBlock> {
        val ids = userIds.takeLast(KEEP_TURNS).filter { it in blocks }
        val kept = ids.toMutableList()
        val max = limit(nCtx)
        while (kept.isNotEmpty() && kept.sumOf { blocks.getValue(it).tokens } > max) kept.removeAt(0)
        return kept.associateWith { blocks.getValue(it) }
    }

    /** Below this many tokens of room the newest turn takes the whole budget and the older block goes. */
    const val MIN_ROOM = 300

    /**
     * For a new block on [newId]: its room in tokens, and the chunks of the older kept block(s) to skip.
     * The newest turn wins: when the older block leaves less than [MIN_ROOM], it is dropped (oldest first).
     */
    fun plan(userIds: List<String>, blocks: Map<String, KbBlock>, newId: String, nCtx: Int): Pair<Int, Set<Pair<String, Int>>> {
        val max = limit(nCtx)
        val others = select(userIds, blocks - newId, nCtx).toList().toMutableList()
        while (others.isNotEmpty() && max - others.sumOf { it.second.tokens } < minOf(MIN_ROOM, max)) others.removeAt(0)
        return (max - others.sumOf { it.second.tokens }).coerceAtLeast(0) to others.flatMap { it.second.keys }.toSet()
    }

    /** Block overhead (tags + instruction line), so the snippet characters fit into [tokens]. */
    fun charsFor(tokens: Int): Int = (tokens - 60).coerceAtLeast(0)
}

/** 2.3.2: paths inside knowledge/ — relative, '/'-separated, no ".." / absolute / hidden parts. Pure. */
object KbPaths {
    /** Folders a file may sit in: knowledge/a/b/c/file (the scan reaches depth 4). */
    const val MAX_FOLDER_DEPTH = 3
    const val MAX_NAME = 64
    private val BAD = Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]")
    val EDITABLE = setOf("txt", "md", "markdown")

    /** A single safe file / folder name, or null. */
    fun name(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t == "." || t == ".." || t.startsWith(".") || t.length > MAX_NAME || BAD.containsMatchIn(t)) return null
        return t
    }

    private fun parts(path: String): List<String>? {
        if (path.startsWith("/") || path.contains('\\')) return null
        val ps = path.split('/').filter { it.isNotEmpty() }
        return if (ps.all { name(it) == it }) ps else null
    }

    /** A folder path ("" = the root), or null when unsafe or deeper than [MAX_FOLDER_DEPTH]. */
    fun folder(path: String): String? = parts(path.trim().trimEnd('/'))?.takeIf { it.size <= MAX_FOLDER_DEPTH }?.joinToString("/")

    /** A file path (at least the name), or null when unsafe or too deep. */
    fun file(path: String): String? = parts(path.trim())?.takeIf { it.isNotEmpty() && it.size <= MAX_FOLDER_DEPTH + 1 }?.joinToString("/")

    fun join(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"
    fun depth(folder: String): Int = if (folder.isEmpty()) 0 else folder.count { it == '/' } + 1
    fun parent(path: String): String = path.substringBeforeLast('/', "")
    /** [path] lies inside folder [folder] (not the folder itself). */
    fun under(path: String, folder: String): Boolean = folder.isEmpty() || path.startsWith("$folder/")
    fun editable(path: String): Boolean = KbFormats.ext(path) in EDITABLE

    /** Breadcrumbs: ("", root label) then each folder of [folder] with its path. */
    fun crumbs(folder: String, root: String): List<Pair<String, String>> {
        val out = mutableListOf("" to root)
        var acc = ""
        for (p in folder.split('/').filter { it.isNotEmpty() }) { acc = join(acc, p); out += acc to p }
        return out
    }

    /** Direct sub-folders of [folder] among [all] folder paths. */
    fun subfolders(all: List<String>, folder: String): List<String> = all.filter { parent(it) == folder && it != folder }

    /** Files directly in [folder]. */
    fun filesIn(paths: List<String>, folder: String): List<String> = paths.filter { parent(it) == folder }
}

package com.iris.localai.util.streaming

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.thinking.*

/**
 * Turns streamed Markdown into speakable sentences.
 *
 * Text arrives token by token; we emit a sentence as soon as it is complete so speech can
 * start long before the model finishes. Very long clauses are split at commas to keep the
 * first audio fast.
 */
class SentenceSplitter(
    private val softLimit: Int = 48,
    /** The first clause is cut much earlier so the first audio starts within a few tokens. */
    private val firstSoftLimit: Int = 6,
    /** A run with no punctuation at all is still cut here (at a space or CJK char). */
    private val hardLimit: Int = 72,
) {
    private val buf = StringBuilder()
    private var emittedCount = 0
    private var inCodeBlock = false
    /** buf[0, scanned) is known to contain no cut point; only new text needs scanning. */
    private var scanned = 0

    /** Feed a delta; returns zero or more complete, cleaned sentences. */
    fun push(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = findCut() ?: break
            val sentence = buf.substring(0, cut)
            buf.delete(0, cut)
            scanned = 0
            speakable(sentence)?.let { out.add(it); emittedCount++ }
        }
        return out
    }

    /** Whatever is left at the end of the reply. */
    fun flush(): List<String> {
        val rest = buf.toString()
        buf.setLength(0)
        scanned = 0
        return listOfNotNull(speakable(rest))
    }

    private fun findCut(): Int? {
        // Re-check the last scanned char: a trailing '.' waits for the char after it.
        val from = (scanned - 1).coerceAtLeast(0)
        scanned = buf.length
        for (i in from until buf.length) {
            val c = buf[i]
            if (c in HARD_STOPS) {
                // Keep runs like "?!" or "……" together, and don't split "3.14" / "e.g."
                if (c == '.' && i + 1 < buf.length && !buf[i + 1].isWhitespace()) continue
                if (c == '.' && i + 1 >= buf.length) return null // may be a decimal point; wait
                var end = i + 1
                while (end < buf.length && (buf[end] in HARD_STOPS || buf[end] in CLOSERS)) end++
                return end
            }
            val limit = if (emittedCount == 0) firstSoftLimit else softLimit
            if (c in SOFT_STOPS && i >= limit) return i + 1
            if (i >= hardLimit && (c.isWhitespace() || c.code >= 0x2E80)) return i + 1
        }
        return null
    }

    private fun speakable(raw: String): String? {
        val lines = raw.split('\n').filter { line ->
            val fence = line.trimStart().startsWith("```")
            if (fence) inCodeBlock = !inCodeBlock
            !fence && !inCodeBlock
        }
        val t = clean(lines.joinToString(" "))
        return t.takeIf { s -> s.any { it.isLetterOrDigit() } }
    }

    companion object {
        private val HARD_STOPS = setOf('。', '！', '？', '!', '?', '；', ';', '\n', '.', '…')
        private val SOFT_STOPS = setOf('，', ',', '、', '：', ':')
        private val CLOSERS = setOf('"', '”', '’', ')', '）', '」', '』')

        private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
        private val INLINE_CODE = Regex("`([^`]*)`")
        private val EMPHASIS = Regex("(\\*\\*|__|\\*|_|~~)")
        private val HEADING = Regex("^\\s*#{1,6}\\s+")
        private val BULLET = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
        private val TABLE_PIPES = Regex("\\|")
        private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")
        private val SPACES = Regex("\\s+")
        private val RULE = Regex("^[-=]{3,}$")

        /** Strips Markdown syntax that should not be read aloud. */
        fun clean(s: String): String {
            var t = s
            t = LINK.replace(t) { it.groupValues[1] }
            t = INLINE_CODE.replace(t) { it.groupValues[1] }
            t = HTML_TAG.replace(t, "")
            t = t.lines().joinToString(" ") { BULLET.replace(HEADING.replace(it, ""), "") }
            t = EMPHASIS.replace(t, "")
            t = TABLE_PIPES.replace(t, " ")
            t = RULE.replace(t, "")
            return SPACES.replace(t, " ").trim()
        }
    }
}

/** Last-mile cleanup before text goes to the speech synthesizer. */
object SpeechText {
    private val MARKUP = Regex("[*#`>_~|]+")
    private val SHORT_TAG = Regex("<[^<>]{0,24}>")
    private val SPACES = Regex("\\s+")

    /**
     * Drops short tags (e.g. a leaked `<|im_end|>`) and leftover Markdown symbols. Tags go first:
     * the Markdown pass removes '>' and '|', after which a tag could no longer be recognised.
     */
    fun speakable(sentence: String): String =
        SPACES.replace(MARKUP.replace(SHORT_TAG.replace(sentence, " "), " "), " ").trim()
}

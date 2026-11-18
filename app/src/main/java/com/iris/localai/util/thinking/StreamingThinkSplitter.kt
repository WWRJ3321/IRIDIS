package com.iris.localai.util.thinking

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*

/**
 * Incremental version of [ThinkParser] for streaming APIs: routes each delta to either the
 * reasoning channel or the answer channel, even when a tag is split across deltas.
 */
class StreamingThinkSplitter(startInThinking: Boolean = false) {

    data class Piece(val thinking: Boolean, val text: String)

    private var thinking = startInThinking
    /** inside a reasoning block right now (after everything pushed so far). */
    val inThinking: Boolean get() = thinking
    private var seenAnyTag = false
    private val pending = StringBuilder()

    /**
     * a reasoning block has been closed. Like [ThinkParser] (which renders the stored
     * message), only the first block is reasoning: a later "<think>" is answer text (e.g. an answer
     * that talks about the tag). Before, the stream showed it as reasoning while the finished
     * message showed it as answer.
     */
    private var closedOnce = false

    fun push(delta: String): List<Piece> {
        pending.append(delta)
        val out = ArrayList<Piece>()
        if (closedOnce) {
            emit(out, pending.toString()); pending.setLength(0)
            return out
        }
        while (true) {
            if (closedOnce) { emit(out, pending.toString()); pending.setLength(0); break }
            val tag = if (thinking) CLOSE else OPEN
            var idx = pending.indexOf(tag)
            // before any tag, a bare </think> (template pre-opened the block) that comes
            // *earlier* than a later <think> must win; before, "reasoning</think>answer<think>…"
            // matched the later <think> and streamed the reasoning as answer text.
            if (idx >= 0 && !thinking && !seenAnyTag) {
                val c = pending.indexOf(CLOSE)
                if (c in 0 until idx) idx = -1
            }
            if (idx >= 0) {
                emit(out, pending.substring(0, idx))
                pending.delete(0, idx + tag.length)
                if (thinking) closedOnce = true
                thinking = !thinking
                seenAnyTag = true
                continue
            }
            // A bare </think> while not thinking means the template pre-opened the block.
            if (!thinking && !seenAnyTag) {
                val c = pending.indexOf(CLOSE)
                if (c >= 0) {
                    if (c > 0) out.add(Piece(true, pending.substring(0, c)))
                    pending.delete(0, c + CLOSE.length)
                    seenAnyTag = true
                    closedOnce = true
                    continue
                }
            }
            // Emit everything except a suffix that could be the start of a tag.
            val keep = partialTagSuffix(pending)
            emit(out, pending.substring(0, pending.length - keep))
            pending.delete(0, pending.length - keep)
            break
        }
        return out
    }

    fun flush(): List<Piece> {
        val out = ArrayList<Piece>()
        emit(out, pending.toString())
        pending.setLength(0)
        return out
    }

    private fun emit(out: MutableList<Piece>, text: String) {
        if (text.isEmpty()) return
        out.add(Piece(thinking, text))
    }

    private fun partialTagSuffix(s: CharSequence): Int {
        var best = 0
        for (tag in arrayOf(OPEN, CLOSE)) {
            for (k in minOf(tag.length - 1, s.length) downTo 1) {
                if (s.regionMatches(s.length - k, tag, 0, k)) { best = maxOf(best, k); break }
            }
        }
        return best
    }

    private fun CharSequence.regionMatches(offset: Int, other: String, otherOffset: Int, len: Int): Boolean {
        for (i in 0 until len) if (this[offset + i] != other[otherOffset + i]) return false
        return true
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

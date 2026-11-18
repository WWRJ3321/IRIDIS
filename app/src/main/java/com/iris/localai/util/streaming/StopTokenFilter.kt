package com.iris.localai.util.streaming

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.thinking.*

/**
 * Removes end-of-turn / special tokens that some converted models leak as plain text
 * (e.g. "<|im_end|>", "<end_of_turn>"). Everything after an end token is dropped and
 * [stopped] turns true so the caller can stop generation. Streaming-safe: a suffix that
 * could be the start of a token is held back until the next piece.
 */
class StopTokenFilter {
    var stopped = false
        private set
    private val pending = StringBuilder()

    fun push(delta: String): String {
        if (stopped) return ""
        pending.append(delta)
        val out = StringBuilder()
        while (true) {
            val (idx, tok) = firstToken(pending) ?: break
            out.append(pending, 0, idx)
            if (tok in END) { stopped = true; pending.setLength(0); return out.toString() }
            pending.delete(0, idx + tok.length)
        }
        val keep = partialSuffix(pending)
        out.append(pending, 0, pending.length - keep)
        pending.delete(0, pending.length - keep)
        return out.toString()
    }

    fun flush(): String = if (stopped) "" else pending.toString().also { pending.setLength(0) }

    // Searches the builder in place: no per-delta String copies on the streaming path.
    private fun firstToken(s: StringBuilder): Pair<Int, String>? {
        if (s.indexOf("<") < 0) return null // every token starts with an ASCII '<'
        var bestIdx = -1
        var bestTok = ""
        for (t in ALL) {
            val i = s.indexOf(t)
            if (i >= 0 && (bestIdx < 0 || i < bestIdx)) { bestIdx = i; bestTok = t }
        }
        return if (bestIdx >= 0) Pair(bestIdx, bestTok) else null
    }

    private fun partialSuffix(s: StringBuilder): Int {
        val lt = s.lastIndexOf("<")
        if (lt < 0) return 0
        val len = s.length - lt
        return if (ALL.any { it.length > len && it.regionMatches(0, s, lt, len) }) len else 0
    }

    private fun String.regionMatches(offset: Int, other: CharSequence, otherOffset: Int, len: Int): Boolean {
        for (k in 0 until len) if (this[offset + k] != other[otherOffset + k]) return false
        return true
    }

    companion object {
        private val END = listOf(
            "<|im_end|>", "<|endoftext|>", "<end_of_turn>", "<eos>", "</s>", "<|eot_id|>",
            "<|end_of_text|>", "<|end|>", "<|return|>", "<|EOT|>", "<｜end▁of▁sentence｜>", "<|im_start|>", "<start_of_turn>",
        )
        private val SKIP = listOf("<bos>", "<s>", "<|begin_of_text|>")
        private val ALL = END + SKIP
    }
}

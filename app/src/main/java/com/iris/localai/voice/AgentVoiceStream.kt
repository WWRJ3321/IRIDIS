package com.iris.localai.voice

/**
 * 2.1.9 边生成边开口: speaks a 智能体 voice reply while its answer turn is still being generated,
 * instead of waiting for the whole agent loop to end. Pure and unit-tested.
 *
 * Each model turn is fed in as its answer so far ([update], reasoning already removed). Text is
 * released (to [out], the call's [VoiceSpeech] delta sink) one sentence at a time, and only once
 * [LOOKAHEAD] visible characters follow the sentence, so a short lead-in that turns out to precede a
 * tool call (「我查一下。<tool_call>…」) is never spoken. As soon as anything that could start a call
 * (or a fake tool log) shows up, the turn stops releasing; whatever is left is handled at the end of
 * the run by [finish], which speaks the rest of the real final answer exactly once.
 */
class AgentVoiceStream(private val out: (String) -> Unit, private val lookahead: Int = LOOKAHEAD) {
    private var text = ""
    private var released = 0
    private var held = false

    /** A new model turn begins (its text so far is empty). */
    fun startTurn() {
        text = ""
        released = 0
        held = false
    }

    /** The current turn's answer so far (cumulative, think block removed). */
    fun update(answerSoFar: String) {
        val t = answerSoFar.trimStart()
        // A turn's text only grows; anything else (a rewrite) → stop releasing, [finish] reconciles.
        if (!t.startsWith(text.substring(0, released))) held = true
        text = t
        if (held) return
        if (mayBeCall(t)) { held = true; return }
        val end = releasable(t, released, lookahead)
        if (end > released) {
            out(t.substring(released, end))
            released = end
        }
    }

    /** The run ended; [finalAnswer] is what the bubble shows (think removed). Speaks what is still unsaid. */
    fun finish(finalAnswer: String) {
        val f = finalAnswer.trimStart()
        val said = text.substring(0, released)
        when {
            released == 0 -> if (f.isNotBlank()) out(f)
            f.startsWith(said) -> f.substring(said.length).takeIf { it.isNotEmpty() }?.let(out)
            // The final answer is not what was being spoken (rare): say nothing more rather than repeat.
            else -> {}
        }
        startTurn()
    }

    companion object {
        /** Visible characters that must follow a sentence before it is spoken (enough to see a call start). */
        const val LOOKAHEAD = 4
        private const val ENDS = "。！？!?；;…\n"
        private const val CLOSERS = "”’\"'」』）)】"
        /** Anything that can start a tool call in any supported format, or a copied tool log. */
        private val CALL_CHARS = charArrayOf('<', '`', '{', '〔')

        fun mayBeCall(t: String): Boolean = t.indexOfAny(CALL_CHARS) >= 0 || t.contains("tool_call", ignoreCase = true)

        /** Sentence ends in [t]: index just past each end (its punctuation run and closing quotes). */
        fun sentenceEnds(t: String): List<Int> {
            val ends = ArrayList<Int>()
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                val isEnd = ch in ENDS || (ch == '.' && (i + 1 >= t.length || t[i + 1].isWhitespace()) && i > 0 && !t[i - 1].isDigit())
                if (!isEnd) { i++; continue }
                var j = i + 1
                while (j < t.length && (t[j] in ENDS || t[j] in CLOSERS || (t[j] == '.' && ch == '.'))) j++
                ends += j
                i = j
            }
            return ends
        }

        /**
         * How far [t] may be spoken: the last sentence end after [from] that has at least [lookahead]
         * visible characters after it (and whose tail is not the start of an LFM `tool_call:`).
         */
        fun releasable(t: String, from: Int, lookahead: Int = LOOKAHEAD): Int {
            var best = from
            for (e in sentenceEnds(t)) {
                if (e <= from) continue
                val tail = t.substring(e).filterNot { it.isWhitespace() }
                if (tail.length < lookahead) break
                if ("tool_call".startsWith(tail.lowercase())) break
                best = e
            }
            return best
        }
    }
}

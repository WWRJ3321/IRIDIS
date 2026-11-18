package com.iris.localai.util.thinking

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*

/**
 * reasoning gets its own budget instead of eating the answer's maxNewTokens.
 *
 * Done in Kotlin, no native change: the engine is asked for `answerLimit + budget` tokens, and this
 * meter watches the stream. GGUF and MNN emit about one piece per token, so pieces are counted as
 * tokens (LiteRT pieces can be longer — there the engine cap still bounds the total).
 *  - reasoning passes [budget] while the block is still open → [Verdict.THINKING_EXHAUSTED]: the
 *    caller stops the engine and writes the answer with thinking off (the 1.7.7 continuation, which
 *    then has the full answer limit);
 *  - the answer reaches [answerLimit] → [Verdict.ANSWER_EXHAUSTED]: the caller stops and reports
 *    LENGTH, exactly as if the engine had hit maxNewTokens on the answer alone.
 * [budget] ≤ 0 = off (≤ 1.7.7 behaviour: one shared limit).
 */
class ThinkingBudget(val budget: Int, val answerLimit: Int, startInThinking: Boolean) {
    enum class Verdict { NONE, THINKING_EXHAUSTED, ANSWER_EXHAUSTED }

    private val splitter = StreamingThinkSplitter(startInThinking)
    var thinkingPieces = 0
        private set
    var answerPieces = 0
        private set
    private var fired = false

    val enabled: Boolean get() = budget > 0

    /** Engine limit for a pass with reasoning on. */
    fun engineLimit(): Int = if (enabled) answerLimit + budget else answerLimit

    fun onPiece(text: String): Verdict {
        val before = splitter.inThinking
        val parts = splitter.push(text)
        var t = 0
        var a = 0
        for (p in parts) if (p.thinking) t += p.text.length else a += p.text.length
        when {
            t == 0 && a == 0 -> if (before || splitter.inThinking) thinkingPieces++ else answerPieces++
            t >= a -> thinkingPieces++
            else -> answerPieces++
        }
        if (!enabled || fired) return Verdict.NONE
        return when {
            splitter.inThinking && thinkingPieces > budget -> { fired = true; Verdict.THINKING_EXHAUSTED }
            answerPieces >= answerLimit -> { fired = true; Verdict.ANSWER_EXHAUSTED }
            else -> Verdict.NONE
        }
    }
}

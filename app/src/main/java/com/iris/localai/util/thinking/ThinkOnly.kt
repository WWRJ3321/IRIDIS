package com.iris.localai.util.thinking

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*
import com.iris.localai.engine.StopReason

/**
 * per-reply accounting of reasoning vs. answer output, for the IrisGen end-of-reply log
 * (and the think-only decision). Engines only report one generated-token count, so the split is
 * by streamed pieces (GGUF / MNN emit about one piece per token; LiteRT pieces can be longer) and
 * the token numbers are that count divided in proportion. Pure, unit-tested.
 */
class ReplyAccounting(private val startInThinking: Boolean) {
    private val splitter = StreamingThinkSplitter(startInThinking)
    private val raw = StringBuilder()

    var thinkPieces = 0
        private set
    var answerPieces = 0
        private set
    var thinkChars = 0
        private set
    var answerChars = 0
        private set

    fun onPiece(text: String) {
        raw.append(text)
        val before = splitter.inThinking
        val parts = splitter.push(text)
        var t = 0
        var a = 0
        for (p in parts) if (p.thinking) t += p.text.length else a += p.text.length
        thinkChars += t
        answerChars += a
        // A piece that is only a tag ("</think>") counts for the channel it closes/opens.
        when {
            t == 0 && a == 0 -> if (before || splitter.inThinking) thinkPieces++ else answerPieces++
            t >= a -> thinkPieces++
            else -> answerPieces++
        }
    }

    /** Text as the chat would store it (a pre-opened block gets its "<think>" back). */
    fun text(): String = (if (startInThinking) "<think>" else "") + raw

    fun parts(): ThinkParser.Parts = ThinkParser.parse(text(), expectThinking = startInThinking)

    /** (thinking, answer) tokens: [generated] split by piece share; pieces when the count is unknown. */
    fun tokens(generated: Int?): Pair<Int, Int> {
        val total = thinkPieces + answerPieces
        if (generated == null || generated <= 0 || total == 0) return thinkPieces to answerPieces
        val think = Math.round(generated.toDouble() * thinkPieces / total).toInt()
        return think to (generated - think).coerceAtLeast(0)
    }

    /** One logcat line (tag IrisGen) that says how and why a reply ended. */
    fun describe(reason: StopReason, generated: Int?, maxNewTokens: Int, error: String?): String {
        val p = parts()
        val (tt, at) = tokens(generated)
        val shape = when {
            p.thinkingOpen -> "thinking NOT closed"
            p.hasThinking -> "thinking closed"
            else -> "no thinking"
        }
        return "reply end: reason=${reasonName(reason)} generated=${generated ?: "?"}/${maxNewTokens} tokens " +
            "(thinking≈$tt, answer≈$at; pieces $thinkPieces/$answerPieces; chars $thinkChars/$answerChars) $shape" +
            (if (p.hasThinking && p.answer.isBlank()) " ANSWER EMPTY" else "") +
            (error?.let { " error=$it" } ?: "")
    }

    companion object {
        fun reasonName(r: StopReason): String = when (r) {
            StopReason.END -> "EOS"
            StopReason.MAX_TOKENS -> "LENGTH"
            StopReason.CANCELLED -> "CANCELLED"
            StopReason.CONTEXT_FULL -> "CONTEXT_FULL"
            StopReason.ERROR -> "ERROR"
        }
    }
}

/**
 * a reply whose reasoning ended (or was cut off) with no answer text used to end silently:
 * no error line, no 重新生成 button (both need an answer or an error). Decides what to do instead.
 */
object ThinkOnlyPolicy {
    enum class Action {
        /** Nothing wrong (there is an answer, the user stopped it, or there was no reasoning). */
        NONE,
        /** Generate the answer once more with thinking off: the template closes an empty <think>. */
        CONTINUE,
        /** Say plainly that only reasoning came out (and why). */
        NOTICE,
    }

    data class Verdict(val action: Action, val message: String? = null)

    const val THINK_ONLY = "模型只输出了思考，没有给出正文"

    fun decide(
        parts: ThinkParser.Parts,
        reason: StopReason,
        error: String?,
        alreadyContinued: Boolean,
        maxNewTokens: Int,
    ): Verdict {
        if (parts.answer.isNotBlank()) return Verdict(Action.NONE)
        if (!parts.hasThinking) return Verdict(Action.NONE)
        return when (reason) {
            StopReason.CANCELLED -> Verdict(Action.NONE)
            StopReason.ERROR -> Verdict(Action.NOTICE, "$THINK_ONLY：推理出错（${error ?: "未知错误"}）")
            StopReason.CONTEXT_FULL -> Verdict(
                Action.NOTICE,
                "$THINK_ONLY：上下文已满，思考用完了剩余空间。可在输入框上方的上下文菜单里改用「自动摘要」或「从这里开始新上下文」",
            )
            StopReason.END, StopReason.MAX_TOKENS ->
                if (!alreadyContinued) Verdict(Action.CONTINUE)
                else Verdict(
                    Action.NOTICE,
                    THINK_ONLY + if (reason == StopReason.MAX_TOKENS)
                        "：补写正文也达到了最大回复长度（$maxNewTokens tokens），可在模型设置里调大"
                    else "：自动补写正文后模型仍直接结束",
                )
        }
    }

    /** What to append to the stored reply before the continued answer: closes an unfinished block. */
    fun bridge(parts: ThinkParser.Parts): String = if (parts.thinkingOpen) "\n</think>\n\n" else "\n\n"
}

/**
 * the same rule for the OpenAI / Ollama API (≤ 1.7.7 only logged it): a reply with reasoning
 * but no answer is continued once with thinking off; still empty (or context full / error) →
 * the client gets an explicit error instead of an empty `content`. A client that turned thinking
 * off itself, or a cancelled request, is left alone. Pure, unit-tested.
 */
object ApiThinkOnly {
    enum class Step { DONE, CONTINUE, FAIL }
    data class Decision(val step: Step, val message: String? = null)

    fun afterFirst(thinkingOff: Boolean, reasoning: String, answer: String, reason: StopReason, error: String?, maxNewTokens: Int): Decision {
        if (thinkingOff) return Decision(Step.DONE)
        val v = ThinkOnlyPolicy.decide(ThinkParser.Parts(reasoning, answer, false), reason, error, alreadyContinued = false, maxNewTokens = maxNewTokens)
        return when (v.action) {
            ThinkOnlyPolicy.Action.NONE -> Decision(Step.DONE)
            ThinkOnlyPolicy.Action.CONTINUE -> Decision(Step.CONTINUE)
            ThinkOnlyPolicy.Action.NOTICE -> Decision(Step.FAIL, v.message)
        }
    }

    /** After the continuation: [reasoning] is the first pass's, [answer] what the continuation wrote. */
    fun afterContinuation(reasoning: String, answer: String, reason: StopReason, error: String?, maxNewTokens: Int): Decision {
        if (reason == StopReason.CANCELLED) return Decision(Step.DONE)
        val v = ThinkOnlyPolicy.decide(ThinkParser.Parts(reasoning.ifBlank { "…" }, answer, false), reason, error, alreadyContinued = true, maxNewTokens = maxNewTokens)
        return if (v.action == ThinkOnlyPolicy.Action.NONE) Decision(Step.DONE) else Decision(Step.FAIL, v.message)
    }
}

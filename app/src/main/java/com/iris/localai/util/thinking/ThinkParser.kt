package com.iris.localai.util

/**
 * Splits a reasoning model's output into its <think> part and the visible answer.
 *
 * Handles all shapes seen in the wild:
 *  - "<think>reasoning</think>answer"           (Qwen3, QwQ)
 *  - "reasoning</think>answer"                   (DeepSeek-R1 distills: template pre-fills "<think>")
 *  - "<think>reasoning…" (still streaming)       → thinking in progress
 *  - plain text                                  → answer only
 */
object ThinkParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    data class Parts(
        val thinking: String,
        val answer: String,
        /** True while the closing tag has not arrived yet. */
        val thinkingOpen: Boolean,
    ) {
        val hasThinking: Boolean get() = thinking.isNotBlank() || thinkingOpen
    }

    fun parse(raw: String, expectThinking: Boolean = false): Parts =
        split(raw, raw.indexOf(OPEN), raw.indexOf(CLOSE), expectThinking)

    /** 2.2.8: untagged text shorter than this is shown as (open) thinking when thinking is expected. */
    const val UNTAGGED_THINK_MAX = 600
    private val REASONING_STARTS = listOf("The user", "Okay", "Let me", "Alright", "用户", "我需要", "首先")

    /** The reply opens like reasoning (the template pre-filled <think>, so no tag arrives). */
    fun looksLikeReasoning(raw: String): Boolean {
        val t = raw.trimStart()
        return REASONING_STARTS.any { t.startsWith(it) }
    }

    /** Removes stray <think> / </think> tags (a second close, an echoed open) from visible text. */
    fun stripTags(text: String): String = text.replace(OPEN, "").replace(CLOSE, "").trim()

    /** [parse] with the first-tag positions already known (used by the incremental [ThinkParseCache]). */
    fun split(raw: String, openAt: Int, closeAt: Int, expectThinking: Boolean = false): Parts {
        return when {
            // "reasoning</think>answer" — checked first so an answer that merely *mentions*
            // "<think>" after the real close tag is not mistaken for a new reasoning block.
            closeAt >= 0 && (openAt < 0 || openAt > closeAt) -> Parts(
                thinking = stripTags(raw.substring(0, closeAt)),
                answer = stripTags(raw.substring(closeAt + CLOSE.length)),
                thinkingOpen = false,
            )
            openAt >= 0 && closeAt > openAt -> Parts(
                thinking = stripTags(raw.substring(openAt + OPEN.length, closeAt)),
                answer = stripTags(raw.substring(0, openAt) + raw.substring(closeAt + CLOSE.length)),
                thinkingOpen = false,
            )
            openAt >= 0 -> Parts(
                thinking = stripTags(raw.substring(openAt + OPEN.length)),
                answer = stripTags(raw.substring(0, openAt)),
                thinkingOpen = true,
            )
            // 2.2.8: no tag yet. Thinking expected (template pre-filled <think>): short or
            // reasoning-like text is open thinking until </think> moves it; long plain text is body.
            expectThinking && (raw.length < UNTAGGED_THINK_MAX || looksLikeReasoning(raw)) ->
                Parts(thinking = raw.trim(), answer = "", thinkingOpen = true)
            else -> Parts(thinking = "", answer = raw.trim(), thinkingOpen = false)
        }
    }

    /** What gets sent back to the model as history: never the reasoning (saves context). */
    fun stripForHistory(raw: String): String {
        val p = parse(raw)
        if (p.answer.isNotBlank() || p.thinking.isBlank()) return p.answer
        // 1.13.0: the reply never left its reasoning (hit the length cap). Keep its tail so the
        // round stays in history instead of vanishing (which left two user turns in a row).
        val tail = p.thinking.trim().let { if (it.length > THINK_TAIL) "…" + it.takeLast(THINK_TAIL) else it }
        return "（未完成，仅有思考）$tail"
    }

    const val THINK_TAIL = 400
}

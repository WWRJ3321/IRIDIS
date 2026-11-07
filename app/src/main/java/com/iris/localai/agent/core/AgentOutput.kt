package com.iris.localai.agent

/**
 * 2.4.5 (Issue #11): write_file content was cut at different lengths depending on the model.
 *
 * Cause: every 智能体 turn was capped at min(最大生成长度, 1536) tokens — thinking included — and
 * [JsonRepair.extractObject] closes a string that the length limit cut off, so the call still
 * parsed and write_file saved the first part of the file and reported success. How much fitted
 * depended on the model's tokenizer (characters per token) and on how long it thought first.
 *
 * Now: the turn cap scales with the context window ([maxNew]), the engine's length stop is passed
 * on ([ModelTurn.truncated]), and a write cut off by it saves only the complete lines it received,
 * tells the model exactly where to continue with `append=true`, and says so on the step card
 * ([cutWrite]). Pure, unit-tested.
 */
object AgentOutput {
    /** 1.9.0's cap, still the floor (and the cap for a 4K window). */
    const val BASE = 1536
    const val MAX = 8192

    /**
     * Tokens one 智能体 turn may generate: 3/8 of the context window, between [BASE] and [MAX]. A
     * user limit below [BASE] (set on purpose for speed) is kept as before.
     */
    fun maxNew(userMax: Int, contextTokens: Int): Int =
        if (userMax in 1 until BASE) userMax else (contextTokens.toLong() * 3 / 8).toInt().coerceIn(BASE, MAX)

    /** Tools whose payload is long text the model writes itself. */
    val WRITE_TOOLS = setOf("write_file")

    /** What [cutWrite] keeps of [content] (complete lines only) and where the continuation starts. */
    data class Cut(val kept: String, val lines: Int, val lastLine: String)

    /**
     * The complete lines of a write the length limit cut off: everything up to the last line break
     * (the unfinished last line is dropped so `append` can continue on a fresh line). Null when not a
     * single complete line arrived.
     */
    fun cutWrite(content: String): Cut? {
        val nl = content.lastIndexOf('\n')
        if (nl < 0) return null
        val kept = content.substring(0, nl + 1)
        val lines = kept.count { it == '\n' }
        val last = kept.trimEnd('\n', '\r').substringAfterLast('\n')
        return Cut(kept, lines, last)
    }

    /** True when the call block of [answer] was never closed (the stream ended inside it). */
    fun callUnclosed(answer: String): Boolean = ToolCallParser.callEnd(answer) == null

    /** Model-facing note after a cut-off write that saved [cut]. */
    fun continueNote(path: String, cut: Cut, appended: Boolean): String =
        "Note: your reply hit the output length limit, so the content was cut off. " +
            (if (appended) "Appended" else "Saved") + " the first ${cut.lines} complete line(s) to $path; the last saved line is:\n" +
            cut.lastLine.take(200) + "\n" +
            "Continue with write_file(path=\"$path\", content=<the remaining lines, starting right after that line>, append=true). " +
            "Write long files in parts of about 40 lines, each part its own call with append=true."

    /** Model-facing note when nothing usable arrived. */
    fun nothingNote(path: String?): String =
        "Error: your reply hit the output length limit before the content was complete, so nothing was written" +
            (path?.let { " to $it" } ?: "") + ". Write the file in parts of about 40 lines: the first with write_file(path, content), " +
            "each next part with append=true."
}

package com.iris.localai.util.streaming

import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.io.*
import com.iris.localai.util.thinking.*
import java.util.Locale

/**
 * Times one assistant reply on the device, independent of the engine (GGUF / LiteRT / MNN):
 * characters actually produced, and the time from the first output to the end of generation —
 * also when the user stopped it. [clock] is injectable for tests.
 */
class ReplyTimer(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val startedAt = clock()
    private var firstAt = 0L
    private var lastAt = 0L
    private var endAt = 0L
    /** output time without long pauses (tool runs, app frozen in the background, prefill of a later agent step). */
    private var activeMs = 0L
    @Volatile var chars = 0
        private set

    fun onText(text: String) {
        if (endAt != 0L) return
        val n = ReplySpeed.countChars(text)
        if (n == 0) return
        val now = clock()
        if (firstAt == 0L) firstAt = now else activeMs += gap(now - lastAt)
        lastAt = now
        chars += n
    }

    fun finish() {
        if (endAt != 0L) return
        endAt = clock()
        if (lastAt != 0L) activeMs += gap(endAt - lastAt)
    }

    private fun gap(ms: Long): Long = ms.coerceIn(0L, GAP_CAP_MS)

    private fun end() = if (endAt != 0L) endAt else clock()
    /**
     * Time spent producing output: first character → end, but any pause longer than [GAP_CAP_MS]
     * between two outputs counts only [GAP_CAP_MS]. Before 1.13.1 the whole span counted, so a
     * reply with tool calls or a background freeze showed a speed far below the real decode rate.
     */
    fun outputMs(): Long = when {
        firstAt == 0L -> 0L
        endAt != 0L -> activeMs
        else -> activeMs + gap(clock() - lastAt)
    }
    /** Request → end, i.e. what the user waited in total. */
    fun totalMs(): Long = (end() - startedAt).coerceAtLeast(0L)

    companion object { const val GAP_CAP_MS = 2_500L }
}

object ReplySpeed {
    private val TAGS = Regex("</?think>")

    /** Visible characters (code points), not counting whitespace or the <think> markers. */
    fun countChars(text: String): Int {
        val s = if (text.indexOf('<') >= 0) TAGS.replace(text, "") else text
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isWhitespace(cp)) n++
            i += Character.charCount(cp)
        }
        return n
    }

    /** "23 字/秒 · 用时 8.4s"; speed is left out when too little was produced to say anything. */
    fun format(chars: Int, outputMs: Long, totalMs: Long, tokensPerSecond: Float = 0f): String? {
        if (totalMs <= 0L && outputMs <= 0L) return null
        val parts = ArrayList<String>(3)
        if (chars >= 2 && outputMs >= 200L) {
            val cps = chars * 1000.0 / outputMs
            parts += if (cps >= 10) "${Math.round(cps)} 字/秒" else String.format(Locale.US, "%.1f 字/秒", cps)
        }
        // the engine's own decode rate (tokens, not characters), when it measured one.
        if (tokensPerSecond > 0f && tokensPerSecond < 10_000f) parts += String.format(Locale.US, "%.1f tok/s", tokensPerSecond)
        val t = maxOf(totalMs, outputMs)
        if (t > 0) parts += if (t < 10_000) String.format(Locale.US, "用时 %.1fs", t / 1000.0) else "用时 ${Math.round(t / 1000.0)}s"
        return parts.joinToString(" · ").ifEmpty { null }
    }
}

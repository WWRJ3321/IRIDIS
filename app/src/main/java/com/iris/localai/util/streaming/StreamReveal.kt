package com.iris.localai.util

/**
 * Typewriter pacing for streamed text (unit-tested). Tokens arrive in bursts (a whole word or
 * several characters every 30–100 ms, sometimes a big chunk after a stall); revealing them as they
 * arrive makes text jump. The pacer reveals characters at a rate that tracks the backlog, so the
 * visible text trails the model by roughly [targetLagSec] and moves at an even pace:
 *  - the rate is backlog / targetLagSec, never below [minRate] (no crawling at the end of a burst)
 *    and never above [maxRate];
 *  - the rate is smoothed (it can rise fast, falls slower), so a single burst doesn't cause a lurch;
 *  - a backlog larger than [maxBacklog] is cut down at once (after a stall or when the app returns
 *    from the background nobody wants to watch 3 000 characters typed out);
 *  - when the stream is finished ([done]) everything is shown immediately.
 */
class TypewriterPacer(
    private val targetLagSec: Float = 0.30f,
    private val minRate: Float = 18f,
    private val maxRate: Float = 1_200f,
    private val maxBacklog: Int = 600,
) {
    /** Fractional characters shown (kept fractional so slow rates still advance smoothly). */
    var shown: Float = 0f
        private set
    var rate: Float = minRate
        private set

    /**
     * 1.7.1: measured arrival speed of the stream (chars/s), or 0 while unknown. Only fed when the
     * caller passes a clock ([step]'s nowSec), which the UI does.
     */
    var arrival: Float = 0f
        private set
    private var firstSec = -1f
    private var firstLen = 0
    private var sampleSec = -1f
    private var sampleLen = 0
    private var started = false

    fun reset(to: Int) {
        shown = to.toFloat(); rate = minRate
        arrival = 0f; firstSec = -1f; sampleSec = -1f; started = to > 0
    }

    /**
     * Advances by [dtSec]; returns the number of characters to show out of [target]. [nowSec] (a
     * monotonic clock) lets the pacer measure how fast text really arrives; with it:
     *  - the very first characters wait a moment ([PREBUFFER_SEC] or [PREBUFFER_CHARS]) so the
     *    reveal starts at a steady speed instead of popping 1 char and stalling;
     *  - the speed floor follows the arrival speed (never faster than ~90 % of it while the backlog
     *    is small), so a slow model is revealed continuously instead of catch-up / wait / catch-up,
     *    which is what made the first words of the thinking block twitch.
     */
    fun step(target: Int, dtSec: Float, done: Boolean, nowSec: Float = -1f): Int {
        if (target <= 0) { shown = 0f; return 0 }
        if (done || shown >= target) { shown = target.toFloat(); return target }
        if (target < shown) shown = target.toFloat() // text replaced (regenerate): never show past the end
        if (nowSec >= 0f) {
            measure(target, nowSec)
            if (!started) {
                if (target - shown < PREBUFFER_CHARS && nowSec - firstSec < PREBUFFER_SEC) return shown.toInt()
                started = true
                // Start at the speed the backlog asks for, not at the floor (no slow-then-lurch ramp).
                rate = ((target - shown) / targetLagSec).coerceIn(minRate, maxRate).let { r ->
                    if (arrival > 0f) maxOf(minOf(r, arrival), minRate * 0.5f) else r
                }
            }
        }
        val dt = dtSec.coerceIn(0f, 0.1f) // a dropped frame must not dump a huge chunk
        var backlog = target - shown
        if (backlog > maxBacklog) { shown = target - maxBacklog.toFloat(); backlog = maxBacklog.toFloat() }
        val floor = if (arrival > 0f) minOf(minRate, arrival * 0.9f).coerceAtLeast(2f) else minRate
        val wanted = (backlog / targetLagSec).coerceIn(floor, maxRate)
        // Asymmetric smoothing: catch up quickly, slow down gently.
        val k = if (wanted > rate) 0.35f else 0.08f
        rate += (wanted - rate) * k
        shown = (shown + rate * dt).coerceAtMost(target.toFloat())
        return shown.toInt()
    }

    private fun measure(target: Int, now: Float) {
        if (firstSec < 0f) { firstSec = now; firstLen = target; sampleSec = now; sampleLen = target; return }
        val since = now - firstSec
        if (since < 0.15f) return
        // Average since the first text for the first second, then a ~1 s sliding estimate.
        if (since < 1f) {
            arrival = (target - firstLen) / since
        } else if (now - sampleSec >= 0.25f) {
            val inst = (target - sampleLen) / (now - sampleSec)
            arrival += (inst - arrival) * 0.3f
            sampleSec = now; sampleLen = target
        }
    }

    private companion object {
        const val PREBUFFER_SEC = 0.12f
        const val PREBUFFER_CHARS = 4
    }
}

/**
 * Splits growing text into paragraphs ("\n\n"-separated) incrementally. Finished paragraphs keep
 * their String instances between calls, so Compose skips their Text and never lays them out
 * again; only the last (open) paragraph changes.
 */
class ParagraphSplitter {
    private val done = ArrayList<String>()
    private var doneEnd = 0      // index just after the last consumed separator
    private var source: String = ""

    fun split(text: String): List<String> {
        // Not a continuation of what we saw (new message / text replaced): start over.
        if (text.length < doneEnd || !text.regionMatches(0, source, 0, doneEnd)) { done.clear(); doneEnd = 0 }
        source = text
        while (true) {
            val sep = text.indexOf("\n\n", doneEnd)
            if (sep < 0) break
            val para = text.substring(doneEnd, sep).trim()
            if (para.isNotEmpty()) done += para
            doneEnd = sep + 2
        }
        val tail = text.substring(doneEnd).trim()
        return if (tail.isEmpty()) ArrayList(done) else ArrayList<String>(done.size + 1).apply { addAll(done); add(tail) }
    }
}

/**
 * [ThinkParser.parse] for append-only streams: remembers where the tags were, so each new frame
 * only scans the newly arrived tail instead of the whole (possibly 20 000-character) text.
 * Results are identical to ThinkParser.parse (tested); any non-append change falls back to it.
 */
class ThinkParseCache {
    private var last: String = ""
    private var lastParts = ThinkParser.Parts("", "", false)
    private var openAt = -1
    private var closeAt = -1
    private var scanned = 0
    private var lastExpect = false

    fun parse(raw: String): ThinkParser.Parts {
        if ((raw === last || raw == last) && !lastExpect) return lastParts
        return parseInternal(raw, false)
    }

    /**
     * While streaming: a tag that has only partly arrived ("<thi", "</th") is held back instead of
     * being shown as answer text for a frame and then disappearing (that made the first words jump),
     * and the open thinking block never starts with the newline the model writes after <think>.
     */
    fun parseStreaming(raw: String, expectThinking: Boolean = false): ThinkParser.Parts {
        val r = raw.substring(0, raw.length - partialTagSuffix(raw))
        if ((r === last || r == last) && expectThinking == lastExpect) return lastParts
        return parseInternal(r, expectThinking)
    }

    private fun parseInternal(raw: String, expectThinking: Boolean): ThinkParser.Parts {
        val appended = raw.length > last.length && raw.startsWith(last)
        if (!appended) { openAt = -1; closeAt = -1; scanned = 0 }
        // Re-scan a few characters before the old end: a tag may have been split across pieces.
        val from = (scanned - 8).coerceAtLeast(0)
        if (openAt < 0) { val i = raw.indexOf(OPEN, from); if (i >= 0) openAt = i }
        if (closeAt < 0) { val i = raw.indexOf(CLOSE, from); if (i >= 0) closeAt = i }
        scanned = raw.length
        last = raw
        lastExpect = expectThinking
        lastParts = ThinkParser.split(raw, openAt, closeAt, expectThinking)
        return lastParts
    }

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"

        /** Length of the longest suffix of [raw] that is a proper prefix of <think> or </think>. */
        fun partialTagSuffix(raw: String): Int {
            val lt = raw.lastIndexOf('<')
            if (lt < 0 || raw.length - lt >= CLOSE.length) return 0
            val tail = raw.substring(lt)
            return if (OPEN.startsWith(tail) && tail != OPEN || CLOSE.startsWith(tail) && tail != CLOSE) tail.length else 0
        }
    }
}

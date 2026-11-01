package com.iris.localai.engine

/**
 * 2.2.2: Gemma 4 streams its reasoning as `<|channel>thought\n…<channel|>`. This rewrites it, piece
 * by piece, into the `<think>…</think>` form the rest of the app folds; other models pass through
 * untouched. A tag split across pieces is held back until it is complete.
 */
class ChannelThink {
    private val buf = StringBuilder()

    /** Text ready to emit after [piece]; [final] flushes whatever is held back. */
    fun push(piece: String, final: Boolean = false): String {
        buf.append(piece)
        var s = buf.toString()
        val out = StringBuilder()
        while (true) {
            // Earliest known tag (Gemma 4 channel, or 2.2.13 K2 Horizon <ifm|think…> pairs).
            var at = -1
            var tag = ""
            for (t in TAGS) {
                val i = s.indexOf(t)
                if (i >= 0 && (at < 0 || i < at)) { at = i; tag = t }
            }
            if (at < 0) {
                var keep = 0
                if (!final) {
                    for (n in minOf(s.length, MAX_TAG) downTo 1) {
                        val tail = s.substring(s.length - n)
                        if (TAGS.any { it.startsWith(tail) }) { keep = n; break }
                    }
                }
                out.append(s, 0, s.length - keep)
                s = s.substring(s.length - keep)
                break
            }
            out.append(s, 0, at)
            if (tag != OPEN) {
                out.append(if (tag.startsWith("</") || tag == CLOSE) "</think>" else "<think>")
                s = s.substring(at + tag.length)
                continue
            }
            val rest = s.substring(at + OPEN.length)
            when {
                rest.startsWith("thought\n") -> { out.append("<think>"); s = rest.substring(8) }
                !final && "thought\n".startsWith(rest) -> { s = s.substring(at); break } // wait for more
                rest.startsWith("thought") -> { out.append("<think>"); s = rest.substring(7) }
                else -> { out.append("<think>"); s = rest }
            }
        }
        buf.setLength(0)
        buf.append(s)
        return out.toString()
    }

    companion object {
        const val OPEN = "<|channel>"
        const val CLOSE = "<channel|>"
        /** 2.2.13: K2 Horizon reasoning tags (high / medium / low effort). */
        val K2_TAGS = listOf("ifm|think", "ifm|think_fast", "ifm|think_faster").flatMap { listOf("<$it>", "</$it>") }
        val TAGS = listOf(OPEN, CLOSE) + K2_TAGS
        private val MAX_TAG = TAGS.maxOf { it.length }
        fun convert(text: String): String = ChannelThink().push(text, final = true)
    }
}

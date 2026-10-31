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
            val open = s.indexOf(OPEN)
            val close = s.indexOf(CLOSE)
            val at = listOf(open, close).filter { it >= 0 }.minOrNull()
            if (at == null) {
                var keep = 0
                if (!final) {
                    for (n in minOf(s.length, OPEN.length) downTo 1) {
                        val tail = s.substring(s.length - n)
                        if (OPEN.startsWith(tail) || CLOSE.startsWith(tail)) { keep = n; break }
                    }
                }
                out.append(s, 0, s.length - keep)
                s = s.substring(s.length - keep)
                break
            }
            out.append(s, 0, at)
            if (at == close) {
                out.append("</think>")
                s = s.substring(at + CLOSE.length)
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
        fun convert(text: String): String = ChannelThink().push(text, final = true)
    }
}

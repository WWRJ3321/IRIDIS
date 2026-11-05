package com.iris.localai.agent.gui

/**
 * 2.4.0: OCR only what changed between two screenshots (idea from Tsuki's behaviour; own code).
 * A screenshot is summarised as a [COLS]×rows grid of mean luminance; cells whose luminance moved
 * by more than [THRESHOLD] are "changed". Unchanged screen → reuse the last lines; a small changed
 * area → OCR only its bounding box (grown by one cell) and keep the old lines outside it; a big
 * one → full OCR. Pure and unit-tested; the Android provider feeds it pixels.
 */
object OcrDiff {
    const val COLS = 24
    const val THRESHOLD = 10
    /** Above this share of the screen, a crop saves little: OCR the whole screenshot. */
    const val FULL_SHARE = 0.55

    class Grid(val cols: Int, val rows: Int, val lum: IntArray, val w: Int, val h: Int)

    sealed interface Plan {
        data object Reuse : Plan
        data object Full : Plan
        /** Crop in screenshot pixels. */
        data class Crop(val rect: PxRect) : Plan
    }

    /** [argb] = w*h pixels (row-major). Rows keep the cells roughly square. */
    fun grid(argb: IntArray, w: Int, h: Int, cols: Int = COLS): Grid {
        val rows = maxOf(1, (cols.toLong() * h / maxOf(1, w)).toInt())
        val sum = LongArray(cols * rows); val n = IntArray(cols * rows)
        for (y in 0 until h) {
            val r = minOf(rows - 1, y * rows / h)
            for (x in 0 until w) {
                val c = minOf(cols - 1, x * cols / w)
                val p = argb[y * w + x]
                val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                sum[r * cols + c] += l.toLong(); n[r * cols + c]++
            }
        }
        return Grid(cols, rows, IntArray(cols * rows) { if (n[it] == 0) 0 else (sum[it] / n[it]).toInt() }, w, h)
    }

    fun plan(prev: Grid?, cur: Grid): Plan {
        if (prev == null || prev.cols != cur.cols || prev.rows != cur.rows || prev.w != cur.w || prev.h != cur.h) return Plan.Full
        var minC = Int.MAX_VALUE; var minR = Int.MAX_VALUE; var maxC = -1; var maxR = -1
        for (r in 0 until cur.rows) for (c in 0 until cur.cols) {
            val i = r * cur.cols + c
            if (kotlin.math.abs(cur.lum[i] - prev.lum[i]) > THRESHOLD) {
                if (c < minC) minC = c; if (c > maxC) maxC = c
                if (r < minR) minR = r; if (r > maxR) maxR = r
            }
        }
        if (maxC < 0) return Plan.Reuse
        minC = maxOf(0, minC - 1); minR = maxOf(0, minR - 1); maxC = minOf(cur.cols - 1, maxC + 1); maxR = minOf(cur.rows - 1, maxR + 1)
        val share = (maxC - minC + 1).toDouble() * (maxR - minR + 1) / (cur.cols * cur.rows)
        if (share > FULL_SHARE) return Plan.Full
        return Plan.Crop(PxRect(minC * cur.w / cur.cols, minR * cur.h / cur.rows, (maxC + 1) * cur.w / cur.cols, (maxR + 1) * cur.h / cur.rows))
    }

    /** Old lines (screen px) that do not touch [changed] (screen px), plus the fresh ones. */
    fun merge(old: List<OcrLine>, changed: PxRect, fresh: List<OcrLine>): List<OcrLine> =
        old.filter { o -> o.bounds.right <= changed.left || o.bounds.left >= changed.right || o.bounds.bottom <= changed.top || o.bounds.top >= changed.bottom } + fresh
}

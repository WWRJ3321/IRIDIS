package com.iris.localai.ui.components

/** 2.3.4: where a pill → panel morph ends up horizontally. Pure (px in, px out), unit-tested. */
object MorphGeometry {
    /**
     * Panel (width, left in screen px) for a pill at [pillLeft] with width [pillW] on a [screenW]-wide screen:
     * width = min(screen − 2·[margin], [maxW]) (never narrower than the pill unless the screen is);
     * a pill whose centre is right of the screen centre aligns the panel's right edge with its own,
     * otherwise its left edge; then clamped into [margin, screen − margin].
     */
    fun place(pillLeft: Float, pillW: Float, screenW: Float, margin: Float, maxW: Float): Pair<Float, Float> {
        val room = (screenW - 2 * margin).coerceAtLeast(0f)
        val w = minOf(maxOf(minOf(maxW, room), minOf(pillW, room)), room)
        val rightSide = pillLeft + pillW / 2f > screenW / 2f
        val want = if (rightSide) pillLeft + pillW - w else pillLeft
        val left = want.coerceIn(margin, (screenW - margin - w).coerceAtLeast(margin))
        return w to left
    }
}

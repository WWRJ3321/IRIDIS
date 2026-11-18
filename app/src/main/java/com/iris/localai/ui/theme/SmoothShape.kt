package com.iris.localai.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * "continuous" rounded corners (the iOS / HyperOS squircle look). A plain rounded corner
 * starts its curve abruptly where the straight edge ends; a smoothed corner eases into the curve
 * with two cubic Béziers on either side of a shorter circular arc (corner smoothing, 0 = plain
 * circle, ~0.6 = iOS). Pure geometry lives in [SmoothCorner] so it is unit-tested on the JVM.
 */

/** Geometry of one smoothed corner, in px. All lengths are measured from the corner along an edge. */
data class SmoothCorner(
    /** Radius of the circular part. */
    val radius: Float,
    /** Smoothing actually used after clamping (0..1). */
    val smoothing: Float,
    /** Distance from the corner where the curve begins on each edge, (1 + smoothing) × radius. */
    val extent: Float,
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    /** Angular size of the circular arc, degrees (90 when smoothing = 0). */
    val arcDegrees: Float,
) {
    companion object {
        /**
         * @param radius requested corner radius (px)
         * @param smoothing requested smoothing 0..1
         * @param maxExtent half of the shorter side: neither the curve nor the radius may exceed it
         */
        fun of(radius: Float, smoothing: Float, maxExtent: Float): SmoothCorner {
            val maxE = maxExtent.coerceAtLeast(0f)
            val r = radius.coerceIn(0f, maxE)
            var s = smoothing.coerceIn(0f, 1f)
            if (r <= 0f) return SmoothCorner(0f, 0f, 0f, 0f, 0f, 0f, 0f, 90f)
            // Not enough room for the eased part: smooth less (a pill ends up a plain semicircle).
            if ((1f + s) * r > maxE) s = (maxE / r - 1f).coerceIn(0f, s)
            val p = (1f + s) * r
            val arcMeasure = 90f * (1f - s)
            val arcSection = sin(rad(arcMeasure / 2f)) * r * sqrt(2f)
            val alpha = (90f - arcMeasure) / 2f
            val p3p4 = r * tan(rad(alpha / 2f))
            val beta = 45f * s
            val c = p3p4 * cos(rad(beta))
            val d = c * tan(rad(beta))
            val b = ((p - arcSection - c - d) / 3f).coerceAtLeast(0f)
            val a = 2f * b
            return SmoothCorner(r, s, p, a, b, c, d, arcMeasure)
        }

        private fun rad(deg: Float) = (deg * Math.PI / 180.0).toFloat()
    }
}

/** Rounded rectangle with continuous (smoothed) corners. Corners may differ (grouped list rows). */
@Immutable
class SmoothRoundedShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
    private val smoothing: Float = IrisRadius.SMOOTHING,
) : Shape {
    constructor(all: Dp, smoothing: Float = IrisRadius.SMOOTHING) : this(all, all, all, all, smoothing)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val (tl, tr) = if (ltr) topStart to topEnd else topEnd to topStart
        val (br, bl) = if (ltr) bottomEnd to bottomStart else bottomStart to bottomEnd
        val px = with(density) { listOf(tl.toPx(), tr.toPx(), br.toPx(), bl.toPx()) }
        if (px.all { it <= 0f }) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val half = minOf(size.width, size.height) / 2f
        return Outline.Generic(buildPath(size, px.map { SmoothCorner.of(it, smoothing, half) }))
    }

    private fun buildPath(size: Size, k: List<SmoothCorner>): Path {
        val w = size.width
        val h = size.height
        val (tl, tr, br, bl) = k
        return Path().apply {
            // Top edge → top-right corner.
            moveTo(tl.extent, 0f)
            lineTo(w - tr.extent, 0f)
            if (tr.radius > 0f) {
                cubicTo(w - tr.extent + tr.a, 0f, w - tr.extent + tr.a + tr.b, 0f, w - tr.extent + tr.a + tr.b + tr.c, tr.d)
                arcTo(Rect(w - 2 * tr.radius, 0f, w, 2 * tr.radius), -45f - tr.arcDegrees / 2f, tr.arcDegrees, false)
                cubicTo(w, tr.extent - tr.a - tr.b, w, tr.extent - tr.a, w, tr.extent)
            }
            lineTo(w, h - br.extent)
            if (br.radius > 0f) {
                cubicTo(w, h - br.extent + br.a, w, h - br.extent + br.a + br.b, w - br.d, h - br.extent + br.a + br.b + br.c)
                arcTo(Rect(w - 2 * br.radius, h - 2 * br.radius, w, h), 45f - br.arcDegrees / 2f, br.arcDegrees, false)
                cubicTo(w - br.extent + br.a + br.b, h, w - br.extent + br.a, h, w - br.extent, h)
            }
            lineTo(bl.extent, h)
            if (bl.radius > 0f) {
                cubicTo(bl.extent - bl.a, h, bl.extent - bl.a - bl.b, h, bl.extent - bl.a - bl.b - bl.c, h - bl.d)
                arcTo(Rect(0f, h - 2 * bl.radius, 2 * bl.radius, h), 135f - bl.arcDegrees / 2f, bl.arcDegrees, false)
                cubicTo(0f, h - bl.extent + bl.a + bl.b, 0f, h - bl.extent + bl.a, 0f, h - bl.extent)
            }
            lineTo(0f, tl.extent)
            if (tl.radius > 0f) {
                cubicTo(0f, tl.extent - tl.a, 0f, tl.extent - tl.a - tl.b, tl.d, tl.extent - tl.a - tl.b - tl.c)
                arcTo(Rect(0f, 0f, 2 * tl.radius, 2 * tl.radius), 225f - tl.arcDegrees / 2f, tl.arcDegrees, false)
                cubicTo(tl.extent - tl.a - tl.b, 0f, tl.extent - tl.a, 0f, tl.extent, 0f)
            }
            close()
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SmoothRoundedShape && other.topStart == topStart && other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd && other.bottomStart == bottomStart && other.smoothing == smoothing

    override fun hashCode(): Int =
        (((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()) * 31 + smoothing.hashCode()

    override fun toString(): String = "SmoothRoundedShape($topStart, $topEnd, $bottomEnd, $bottomStart, s=$smoothing)"
}

/** Continuous-corner shape whose radius is the same on every corner. */
fun smoothShape(radius: Dp, smoothing: Float = IrisRadius.SMOOTHING): Shape =
    if (radius <= 0.dp) androidx.compose.ui.graphics.RectangleShape else SmoothRoundedShape(radius, smoothing)

/**
 * clips what is drawn to [shape] but not hit-testing. Modifier.clip with an
 * [Outline.Generic] shape (our continuous corners) hit-tests each touch with Path.op against a
 * 0.01 px square; Skia's path ops lose that square to float precision ~8192 px from the top of
 * the clipped node, so rows far down a tall card stopped taking taps. Use this on tall containers.
 */
fun androidx.compose.ui.Modifier.clipDraw(shape: Shape): androidx.compose.ui.Modifier =
    this.drawWithCache {
        val path = when (val o = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Generic -> o.path
            is Outline.Rounded -> Path().apply { addRoundRect(o.roundRect) }
            is Outline.Rectangle -> Path().apply { addRect(o.rect) }
        }
        onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
    }

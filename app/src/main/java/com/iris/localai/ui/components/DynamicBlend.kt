package com.iris.localai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.withFrameNanos
import com.iris.localai.ui.motion.LocalDecorativeMotion
import com.iris.localai.ui.theme.Iris
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 2.2.10 外观 ›「动态混色背景」: pure colour maths for the HyperOS-style blend (JVM-testable, no
 * android.graphics). Blob colours come from the user's theme accent plus adjacent hues, at pastel
 * lightness in light mode and muted/darker in dark mode so text on top stays readable.
 */
object BlendPalette {
    /** Hue offsets (degrees) of the blobs relative to the accent: accent, ±40°, +60°. */
    val HUE_OFFSETS = floatArrayOf(0f, -40f, 40f, 60f)

    /** ARGB (opaque) blob colours for [accentArgb]. */
    fun derive(accentArgb: Int, dark: Boolean): IntArray {
        val hsl = toHsl(accentArgb)
        val sat = if (dark) (hsl[1] * 0.5f).coerceIn(0.22f, 0.40f) else (hsl[1] * 0.85f).coerceIn(0.45f, 0.75f)
        val light = if (dark) 0.40f else 0.80f
        return IntArray(HUE_OFFSETS.size) { i -> fromHsl(((hsl[0] + HUE_OFFSETS[i]) % 360f + 360f) % 360f, sat, light) }
    }

    /** [h 0–360, s 0–1, l 0–1] of an ARGB colour. */
    fun toHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val l = (max + min) / 2f
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/** Seconds per full drift loop (all blob paths are integer harmonics of it, so it loops seamlessly). */
private const val BLEND_PERIOD_S = 20f

/**
 * 2.2.10: slow drifting pastel colour blobs over the top ~half of the home/chat screen, fading out
 * vertically into the normal background. Drawn behind content (inside the glass source), never
 * receives input. Animates only while [LocalDecorativeMotion] is on (app resumed, not streaming,
 * no battery saver, system animations on, visual effects not 0); otherwise it holds a static frame.
 * Only the draw phase reads the clock, so frames do not recompose.
 */
@Composable
fun HomeDynamicBlend(modifier: Modifier = Modifier) {
    val c = Iris.colors
    val colors = remember(c.accent, c.isDark) { BlendPalette.derive(c.accent.toArgb(), c.isDark).map { Color(it) } }
    val alpha = if (c.isDark) 0.30f else 0.55f
    val animate = LocalDecorativeMotion.current
    val phase = remember { mutableFloatStateOf(0.13f) } // loop position 0–1, kept across pauses
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last < 0) last = now
                val dt = (now - last) / 1e9f
                // ~30 fps is plenty for a 20 s drift; skip alternate frames to halve the cost.
                if (dt >= 0.03f) {
                    last = now
                    phase.floatValue = (phase.floatValue + minOf(dt, 0.1f) / BLEND_PERIOD_S) % 1f
                }
            }
        }
    }
    Canvas(modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
        val t = phase.floatValue * 2f * PI.toFloat()
        val w = size.width; val h = size.height
        // (base x, base y, radius, x amp, y amp, x harmonic, y harmonic, phase) — all relative.
        val blobs = arrayOf(
            floatArrayOf(0.20f, 0.18f, 0.75f, 0.14f, 0.10f, 1f, 1f, 0f),
            floatArrayOf(0.85f, 0.25f, 0.70f, 0.12f, 0.12f, 1f, 2f, 1.7f),
            floatArrayOf(0.55f, 0.55f, 0.65f, 0.18f, 0.08f, 2f, 1f, 3.3f),
            floatArrayOf(0.10f, 0.62f, 0.55f, 0.10f, 0.10f, 1f, 1f, 4.6f),
        )
        drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint()) }
        blobs.forEachIndexed { i, b ->
            val center = Offset(
                w * (b[0] + b[3] * sin(t * b[5] + b[7])),
                h * (b[1] + b[4] * cos(t * b[6] + b[7] * 0.7f)),
            )
            val r = w * b[2]
            val col = colors[i % colors.size]
            drawCircle(
                Brush.radialGradient(
                    0f to col.copy(alpha = alpha), 0.45f to col.copy(alpha = alpha * 0.55f), 1f to Color.Transparent,
                    center = center, radius = r,
                ),
                radius = r, center = center,
            )
        }
        drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        drawIntoCanvas { it.restore() }
    }
}

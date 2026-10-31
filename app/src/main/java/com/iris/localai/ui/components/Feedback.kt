package com.iris.localai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.motion.LocalDecorativeMotion
import com.iris.localai.ui.motion.motionSpec

/**
 * Gentle breathing opacity for "in progress" text or dots. The animated value is read only in
 * the graphics layer, so the composable around it never recomposes per frame. Decorative: it
 * stops (and settles at full opacity) while a reply streams, when the app is in the background,
 * or when animations are off. [cycles] > 0 breathes that many times and then rests, for states
 * that can last indefinitely (e.g. "API 运行中").
 */
@Composable
fun Modifier.pulse(enabled: Boolean = true, min: Float = 0.4f, periodMs: Int = IrisMotion.PULSE_PERIOD_MS, cycles: Int = 0): Modifier {
    val run = enabled && LocalDecorativeMotion.current && !LocalReduceMotion.current
    val a = remember { Animatable(1f) }
    LaunchedEffect(run, min, periodMs, cycles) {
        if (!run) { if (a.value != 1f) a.animateTo(1f, IrisMotion.FadeIn.float()); return@LaunchedEffect }
        var n = 0
        while (cycles <= 0 || n < cycles) {
            a.animateTo(min, IrisMotion.loop(periodMs))
            a.animateTo(1f, IrisMotion.loop(periodMs))
            n++
        }
    }
    return this.graphicsLayer { alpha = a.value }
}

/** Three-dot typing indicator shown while waiting for the first token. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = Iris.colors.textSecondary) {
    val reduce = LocalReduceMotion.current
    // A progress indicator, not decoration: it runs while generation starts (when the decorative
    // flag is off by design). Shown only until the first token; the frame clock stops in background.
    val run = !reduce
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val v = if (!run) null else t.animateFloat(
                0f, 1f,
                infiniteRepeatable(
                    IrisMotion.loop(IrisMotion.DOTS_PERIOD_MS, i * IrisMotion.DOTS_STAGGER_MS),
                    RepeatMode.Reverse,
                ),
                label = "d$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        val p = v?.value ?: 1f
                        alpha = 0.3f + 0.7f * p
                        translationY = -3.dp.toPx() * p
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

/**
 * Thin progress bar drawn in a single draw call. [progress] = null shows an indeterminate sweep.
 * Determinate progress eases toward the new value instead of jumping.
 */
@Composable
fun IrisProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    color: Color = Iris.colors.accent,
    track: Color = Iris.colors.surfaceVariant,
) {
    val reduce = LocalReduceMotion.current
    if (progress != null) {
        // A spring retargets smoothly when a new progress value arrives mid-animation (downloads report often).
        val p = animateFloatAsState(progress.coerceIn(0f, 1f), motionSpec(IrisMotion.Smooth, 0.0005f), label = "progress")
        Box(
            modifier.fillMaxWidth().height(height).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val w = size.width * p.value
                if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
            },
        )
    } else {
        val t = rememberInfiniteTransition(label = "indeterminate")
        val x = if (reduce || !LocalDecorativeMotion.current) null
        else t.animateFloat(0f, 1f, infiniteRepeatable(IrisMotion.linearLoop(IrisMotion.SWEEP_PERIOD_MS)), label = "sweep")
        Box(
            modifier.fillMaxWidth().height(height).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val seg = size.width * 0.32f
                if (x == null) {
                    drawRoundRect(color.copy(alpha = 0.6f), cornerRadius = r)
                } else {
                    // Eased sweep: slow at the edges, quick through the middle.
                    val e = IrisMotion.LoopEasing.transform(x.value)
                    val start = -seg + (size.width + seg) * e
                    val l = start.coerceAtLeast(0f)
                    val rgt = (start + seg).coerceAtMost(size.width)
                    if (rgt > l) drawRoundRect(color, topLeft = Offset(l, 0f), size = Size(rgt - l, size.height), cornerRadius = r)
                }
            },
        )
    }
}

/** Small status dot; breathes a few times when it becomes [active], then rests (no endless loop). */
@Composable
fun StatusDot(color: Color, active: Boolean, modifier: Modifier = Modifier, size: Dp = IrisDimens.space8, cycles: Int = 3) {
    Box(modifier.size(size).pulse(active, min = 0.35f, cycles = cycles).drawBehind { drawCircle(color) })
}

/** Centered illustration + title + message, used for every empty list in the app. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    painter: Painter? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) appear.animateTo(1f, IrisMotion.Gentle.float()) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = IrisDimens.space32, vertical = IrisDimens.space24)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * IrisDimens.space16.toPx()
                val sc = 0.96f + 0.04f * appear.value
                scaleX = sc; scaleY = sc
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val p = painter ?: icon?.let { rememberVectorPainter(it) }
        if (p != null) {
            Box(
                Modifier.size(56.dp).background(c.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(p, null, tint = c.textSecondary, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.height(IrisDimens.space16))
        }
        Text(title, style = IrisType.rowStrong, color = c.text, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(IrisDimens.space6))
            Text(message, style = IrisType.secondary, color = c.textTertiary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(IrisDimens.space16))
            action()
        }
    }
}

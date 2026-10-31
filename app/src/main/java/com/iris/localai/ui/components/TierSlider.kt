package com.iris.localai.ui.components

import com.iris.localai.ui.theme.IrisDimens
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.glass.LocalGlassQuality
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion

/**
 * 1.7.6 resource tier slider, shared by the composer card and the settings page. It is the
 * 1.7.0–1.7.4 card slider brought back (12 dp rounded track, a small dot per tier, a 26 dp white
 * knob with a soft drop shadow and a hairline edge, knob springing stop to stop under the finger),
 * with the filled part in a muted light → deep blue instead of grey. No halo, no thick ring.
 *
 * Text is one compact line: bolt icon + tier name on the left, the optional short real status on
 * the right ("实时生效" / "空闲时重载生效" / "正在重载模型…").
 */
object TierSliderStyle {
    // Low-saturation (matte) blues. Light card: powder blue → slate/denim blue.
    val LightStart = Color(0xFFC3D3E6)
    val LightEnd = Color(0xFF4B6890)
    // Dark card: the deep end stays bright enough to separate from the dark surface.
    val DarkStart = Color(0xFF4A5B73)
    val DarkEnd = Color(0xFF7F9CC6)

    fun start(dark: Boolean) = if (dark) DarkStart else LightStart
    fun end(dark: Boolean) = if (dark) DarkEnd else LightEnd

    /** Geometry of the original StepTrack (dp). */
    const val TRACK_DP = 12f
    const val KNOB_DP = 26f
    const val DOT_DP = 1.8f
    const val HEIGHT_DP = 28f
}

/** Pure maths of the slider (unit-tested). */
object TierSliderMath {
    /** Stop under [x]; the knob centre travels between knob/2 and width − knob/2 (as in 1.7.4). */
    fun stopAt(x: Float, width: Float, knob: Float, count: Int): Int {
        val usable = (width - knob).coerceAtLeast(1f)
        return kotlin.math.round(((x - knob / 2) / usable) * (count - 1)).toInt().coerceIn(0, count - 1)
    }

    /** Knob centre x for an (animated, fractional) stop. */
    fun knobX(pos: Float, width: Float, knob: Float, count: Int): Float =
        knob / 2 + (width - knob).coerceAtLeast(0f) * (pos.coerceIn(0f, (count - 1).toFloat()) / (count - 1).coerceAtLeast(1))
}

@Composable
fun TierSlider(
    labels: List<String>,
    index: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** One short status, from what the engine really did; null = nothing to say. */
    status: String? = null,
) {
    val c = Iris.colors
    val dark = c.isDark
    val count = labels.size.coerceAtLeast(2)
    val feedback = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val reduce = LocalReduceMotion.current
    // 省电 visual level / battery saver / heat: a short critically damped move instead of the spring.
    val effects = LocalGlassQuality.current.blurs && !reduce
    var cur by remember { mutableIntStateOf(index.coerceIn(0, count - 1)) }
    LaunchedEffect(index) { cur = index.coerceIn(0, count - 1) }
    val latestChange by rememberUpdatedState(onChange)
    val latestIndex by rememberUpdatedState(index)
    val anim by animateFloatAsState(
        cur.toFloat(),
        when {
            reduce -> snap()
            effects -> IrisMotion.Detent.float()   // the 1.7.4 feel
            else -> IrisMotion.Quick.float()
        },
        label = "tierKnob",
    )

    fun move(i: Int) {
        if (i != cur) {
            cur = i
            feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.TICK)
        }
    }
    fun done() {
        if (cur != latestIndex) {
            feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.TOGGLE)
            latestChange(cur)
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(IrisDimens.space24), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bolt, null, tint = c.textSecondary, modifier = Modifier.size(IrisDimens.space16))
            Spacer(Modifier.width(IrisDimens.space6))
            AnimatedContent(
                targetState = cur,
                transitionSpec = { if (reduce) IrisMotion.none else IrisMotion.crossfade() },
                label = "tierName",
                modifier = Modifier.weight(1f),
            ) { i ->
                Text(
                    labels.getOrElse(i) { "" },
                    style = IrisType.row.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text, maxLines = 1,
                )
            }
            AnimatedContent(
                targetState = status.orEmpty(),
                transitionSpec = { if (reduce) IrisMotion.none else IrisMotion.crossfade() },
                label = "tierStatus",
            ) { s ->
                Text(s, style = IrisType.labelSmall, color = c.textTertiary, maxLines = 1)
            }
        }
        Spacer(Modifier.height(IrisDimens.space6))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TierSliderStyle.HEIGHT_DP.dp)
                .semantics {
                    contentDescription = "资源档位"
                    stateDescription = labels.getOrElse(cur) { "" }
                    setProgress { v ->
                        move(kotlin.math.round(v * (count - 1)).toInt().coerceIn(0, count - 1)); done(); true
                    }
                }
                .pointerInput(count) {
                    detectTapGestures { o ->
                        move(TierSliderMath.stopAt(o.x, size.width.toFloat(), TierSliderStyle.KNOB_DP.dp.toPx(), count)); done()
                    }
                }
                .pointerInput(count) {
                    detectHorizontalDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { ch, _ ->
                        move(TierSliderMath.stopAt(ch.position.x, size.width.toFloat(), TierSliderStyle.KNOB_DP.dp.toPx(), count))
                    }
                },
        ) {
            val knob = TierSliderStyle.KNOB_DP.dp.toPx()
            val trackH = TierSliderStyle.TRACK_DP.dp.toPx()
            val cy = size.height / 2
            val kx = TierSliderMath.knobX(anim, size.width, knob, count)
            val r = CornerRadius(trackH / 2)
            val top = cy - trackH / 2
            // Unselected: the original quiet light-grey track.
            drawRoundRect(c.outline.copy(alpha = 0.35f), Offset(0f, top), Size(size.width, trackH), r)
            // Selected: muted light → deep blue, anchored to the whole track (higher = deeper).
            val fillW = (kx + trackH / 2).coerceAtMost(size.width)
            if (fillW > 0f) {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(TierSliderStyle.start(dark), TierSliderStyle.end(dark)), startX = 0f, endX = size.width),
                    Offset(0f, top), Size(fillW, trackH), r,
                )
            }
            for (i in 0 until count) {
                val x = TierSliderMath.knobX(i.toFloat(), size.width, knob, count)
                val filled = x <= kx
                drawCircle(
                    if (filled) Color.White.copy(alpha = if (dark) 0.7f else 0.9f) else c.textTertiary.copy(alpha = 0.6f),
                    TierSliderStyle.DOT_DP.dp.toPx(), Offset(x, cy),
                )
            }
            // Knob: white, soft shadow, hairline edge — exactly the 1.7.4 knob.
            drawCircle(Color.Black.copy(alpha = if (dark) 0.32f else 0.18f), knob / 2 + 1.5.dp.toPx(), Offset(kx, cy + 1.dp.toPx()))
            drawCircle(Color.White, knob / 2, Offset(kx, cy))
            drawCircle(c.outline, knob / 2, Offset(kx, cy), style = Stroke(1.dp.toPx()))
        }
    }
}

package com.iris.localai.ui.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/**
 * 2.5.8: 智能体生图进行中：方形占位框里颜色不停流动 + 进度条（读进度文案里的 “步/总步”，没有就不定进度）。
 */
object ImageGenLive {
    private val STEP = Regex("(\\d+)\\s*/\\s*(\\d+)")

    /** "xx 生成中 3/20" → 0.15；没有数字（排队、卸载、装回）→ null。纯函数，单测覆盖。 */
    fun progressOf(line: String): Float? {
        val m = STEP.findAll(line).lastOrNull() ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return if (b <= 0) null else (a.toFloat() / b).coerceIn(0f, 1f)
    }
}

@Composable
fun ImageGenLiveCard(line: String, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val t = rememberInfiniteTransition(label = "imggen")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    // 柔和马卡龙色，跟随主题强调色
    val palette = remember(c.accent) {
        // 2.6.2: 加深饱和度（原马卡龙色太淡，混出来发灰）
        listOf(c.accent, Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF06B6D4), Color(0xFFF59E0B), c.accent)
    }
    val target = ImageGenLive.progressOf(line)
    val shown by animateFloatAsState(target ?: 0f, label = "p")
    Column(modifier.padding(start = IrisDimens.space24, top = IrisDimens.space6)) {
        Canvas(Modifier.size(168.dp).clip(IrisRadius.mediumShape)) {
            val w = size.width
            val h = size.height
            val angle = phase * 2f * Math.PI.toFloat()
            val cx = w / 2 + kotlin.math.cos(angle) * w * 0.6f
            val cy = h / 2 + kotlin.math.sin(angle) * h * 0.6f
            drawRect(Brush.linearGradient(palette, start = Offset(cx, cy), end = Offset(w - cx, h - cy)))
            // 第二层径向光斑，让颜色“混”起来
            val r = w * (0.45f + 0.2f * drift)
            drawRect(Brush.radialGradient(listOf(palette[(1 + (phase * 4).toInt()) % 5].copy(alpha = 0.7f), Color.Transparent),
                center = Offset(w * (0.25f + 0.5f * drift), h * (0.75f - 0.5f * drift)), radius = r))
            drawRect(Brush.radialGradient(listOf(palette[(3 + (phase * 4).toInt()) % 5].copy(alpha = 0.6f), Color.Transparent),
                center = Offset(w * (0.75f - 0.5f * drift), h * (0.25f + 0.5f * drift)), radius = w * (0.4f + 0.15f * (1f - drift))))
        }
        Spacer(Modifier.padding(top = IrisDimens.space6))
        IrisProgressBar(target, Modifier.width(168.dp), height = 4.dp, track = c.outline)
        Row(Modifier.width(168.dp).padding(top = IrisDimens.space4)) {
            Text(line, style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (target != null) Text("${(shown * 100).toInt()}%", style = IrisType.caption, color = c.textTertiary)
        }
    }
}

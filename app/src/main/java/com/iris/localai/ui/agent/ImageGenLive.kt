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
import androidx.compose.ui.graphics.toArgb
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

    /**
     * 2.6.7: 循环调色板上的连续取色（t∈[0,1)，首尾相接）。之前光斑颜色按 (phase*4).toInt() 整格跳，
     * 每 1 秒突变一次 = 闪烁；现在相邻颜色线性插值，t 走一圈回到原色。纯函数，单测覆盖。
     */
    fun cyclicArgb(palette: List<Int>, t: Float): Int {
        if (palette.isEmpty()) return 0
        if (palette.size == 1) return palette[0]
        val n = palette.size
        val x = ((t % 1f) + 1f) % 1f * n
        val i = x.toInt() % n
        val f = x - x.toInt()
        val a = palette[i]; val b = palette[(i + 1) % n]
        fun ch(c: Int, sh: Int) = (c ushr sh) and 0xFF
        fun mix(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * f).toInt().coerceIn(0, 255)
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

@Composable
fun ImageGenLiveCard(line: String, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val t = rememberInfiniteTransition(label = "imggen")
    // 2.6.7: 一个连续相位驱动全部（无 Reverse 折返、无整格换色），颜色平滑过渡、不闪
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val palette = remember(c.accent) {
        listOf(c.accent, Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF06B6D4), Color(0xFFF59E0B)).map { it.toArgb() }
    }
    val target = ImageGenLive.progressOf(line)
    val shown by animateFloatAsState(target ?: 0f, label = "p")
    Column(modifier.padding(start = IrisDimens.space24, top = IrisDimens.space6)) {
        Canvas(Modifier.size(168.dp).clip(IrisRadius.mediumShape)) {
            val w = size.width
            val h = size.height
            val tau = 2f * Math.PI.toFloat()
            fun col(off: Float) = Color(ImageGenLive.cyclicArgb(palette, phase + off))
            // 底色：整块填满（不留透明边，避免露出方框）
            drawRect(col(0f))
            // 三个大光斑沿圆周慢慢转，颜色连续变化；淡出到同色透明（不是黑色透明，边缘不发灰）
            for (k in 0 until 3) {
                val a = (phase + k / 3f) * tau
                val center = Offset(w / 2 + kotlin.math.cos(a) * w * 0.32f, h / 2 + kotlin.math.sin(a * 1f) * h * 0.32f)
                val cc = col(0.25f + k * 0.25f)
                drawCircle(Brush.radialGradient(listOf(cc.copy(alpha = 0.85f), cc.copy(alpha = 0f)), center = center, radius = w * 0.62f),
                    radius = w * 0.62f, center = center)
            }
        }
        Spacer(Modifier.padding(top = IrisDimens.space6))
        IrisProgressBar(target, Modifier.width(168.dp), height = 4.dp, track = c.outline)
        Row(Modifier.width(168.dp).padding(top = IrisDimens.space4)) {
            Text(line, style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (target != null) Text("${(shown * 100).toInt()}%", style = IrisType.caption, color = c.textTertiary)
        }
    }
}

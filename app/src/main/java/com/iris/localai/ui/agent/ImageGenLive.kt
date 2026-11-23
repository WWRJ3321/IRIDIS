package com.iris.localai.ui.agent
import com.iris.localai.ui.motion.RollingText

import com.iris.localai.ui.motion.IrisMotion
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import com.iris.localai.ui.theme.LocalReduceMotion

/**
 * 智能体生图进行中：方形占位框里颜色不停流动 + 进度条（读进度文案里的 “步/总步”，没有就不定进度）。
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
     * 循环调色板上的连续取色（t∈[0,1)，首尾相接）。之前光斑颜色按 (phase*4).toInt() 整格跳，
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
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "imggen")
    // 单色调：主题色淡淡铺底 + 一道柔光斜扫（原来多色光斑流动，混色难看）。
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(IrisMotion.linearLoop(IrisMotion.IMAGE_GEN_SHIMMER_MS), RepeatMode.Restart), label = "sweep")
    val target = ImageGenLive.progressOf(line)
    val shown by animateFloatAsState(target ?: 0f, IrisMotion.Gentle.float(), label = "p")
    Column(modifier.padding(start = IrisDimens.space24, top = IrisDimens.space6)) {
        Canvas(Modifier.size(168.dp).clip(IrisRadius.mediumShape)) {
            val w = size.width
            val h = size.height
            drawRect(c.surfaceVariant)
            drawRect(Brush.linearGradient(
                listOf(c.accent.copy(alpha = if (c.isDark) 0.10f else 0.06f), c.accent.copy(alpha = if (c.isDark) 0.22f else 0.16f)),
                start = Offset.Zero, end = Offset(w, h),
            ))
            if (!reduce) {
                // 光带从左上外侧扫到右下外侧；白色高光，深色主题更淡。
                val x = -w + sweep * 3f * w
                val hi = Color.White.copy(alpha = if (c.isDark) 0.10f else 0.45f)
                drawRect(Brush.linearGradient(
                    listOf(Color.Transparent, hi, Color.Transparent),
                    start = Offset(x - w * 0.45f, 0f), end = Offset(x + w * 0.45f, h),
                ))
            }
        }
        Spacer(Modifier.padding(top = IrisDimens.space6))
        IrisProgressBar(target, Modifier.width(168.dp), height = 4.dp, track = c.outline)
        Row(Modifier.width(168.dp).padding(top = IrisDimens.space4)) {
            Text(line, style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (target != null) RollingText("${(shown * 100).toInt()}%", style = IrisType.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary)
        }
    }
}

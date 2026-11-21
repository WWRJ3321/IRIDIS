package com.iris.localai.ui.quips

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.IrisApp
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/**
 * 3.0 生成界面闲言碎语：长等待时（生图、加载模型、长思考）在进度下面轮换一句小话。
 * 只是点缀：短、不卖萌过头、不打扰（设置 › 外观 可关，[com.iris.localai.data.AppSettings.idleQuips]）。纯 JVM（单测）。
 */
object IdleQuips {
    enum class Scene { IMAGE, LOAD, THINK }

    const val ROTATE_MS = 4200L

    private val ZH = mapOf(
        Scene.IMAGE to listOf(
            "正在从噪点里把画捞出来…", "颜料还没干，别催～", "先画轮廓，再上颜色", "每一步都在少一点模糊",
            "像素们正在排队就位", "这一笔有点难，稍等", "GPU 在认真加班", "构图已定，正在打光",
        ),
        Scene.LOAD to listOf(
            "把几十亿个参数搬进内存…", "模型正在伸懒腰", "第一次加载会慢一点，下次就快了", "正在给权重找座位",
            "内存腾地方中", "热身一下，马上开聊",
        ),
        Scene.THINK to listOf(
            "让它多想一会儿", "正在把思路理顺", "草稿纸写满了一页", "换个角度再想想", "快想通了",
        ),
    )
    private val TW = mapOf(
        Scene.IMAGE to listOf("正在從雜訊裡把畫撈出來…", "顏料還沒乾，別催～", "先畫輪廓，再上顏色", "像素們正在排隊就位", "GPU 在認真加班"),
        Scene.LOAD to listOf("把幾十億個參數搬進記憶體…", "模型正在伸懶腰", "第一次載入會慢一點", "熱身一下，馬上開聊"),
        Scene.THINK to listOf("讓它多想一會兒", "正在把思路理順", "快想通了"),
    )
    private val EN = mapOf(
        Scene.IMAGE to listOf("Pulling a picture out of the noise…", "Paint's still wet", "Outline first, colour next", "Pixels are lining up", "The GPU is working overtime"),
        Scene.LOAD to listOf("Moving billions of weights into memory…", "The model is stretching", "First load is slow, next time is quick", "Warming up"),
        Scene.THINK to listOf("Letting it think a little longer", "Untangling the idea", "Almost there"),
    )
    /** 深夜（0–5 点）偶尔插一句。 */
    private val NIGHT_ZH = listOf("这么晚还在折腾，注意休息", "夜深了，屏幕调暗一点吧")
    private val NIGHT_EN = listOf("It's late — remember to rest", "Late night? Dim the screen a bit")

    fun lines(scene: Scene, lang: String = I18n.uiLang(), hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): List<String> {
        val zh = lang.startsWith("zh")
        val base = when {
            lang == "zh-TW" -> TW
            zh -> ZH
            else -> EN
        }[scene].orEmpty()
        val night = if (hour in 0..4) (if (zh) NIGHT_ZH else NIGHT_EN) else emptyList()
        return base + night
    }

    /** 第 [tick] 次轮换显示哪一句（同一 [seed] 顺序固定，不连续重复）。 */
    fun at(lines: List<String>, tick: Int, seed: Int): String {
        if (lines.isEmpty()) return ""
        val n = lines.size
        val start = Math.floorMod(seed, n)
        val step = if (n > 2) 1 + Math.floorMod(seed / n, n - 1) else 1
        return lines[Math.floorMod(start + tick * step, n)]
    }
}

/** 等待中轮换的一行小字；[active] 为 false 或设置关掉时不显示。 */
@Composable
fun IdleQuip(scene: IdleQuips.Scene, active: Boolean, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { (ctx.applicationContext as IrisApp).container.settings.settings }
    val on = settings.collectAsStateWithLifecycle().value.idleQuips
    if (!active || !on) return
    val lines = remember(scene) { IdleQuips.lines(scene) }
    val seed = remember { (System.nanoTime() and 0x7FFF).toInt() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(scene) { while (true) { delay(IdleQuips.ROTATE_MS); tick++ } }
    val reduce = LocalReduceMotion.current
    AnimatedContent(IdleQuips.at(lines, tick, seed), transitionSpec = { if (reduce) androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None, androidx.compose.animation.ExitTransition.None) else IrisMotion.crossfade() }, label = "quip", modifier = modifier) { t ->
        Text(t, style = IrisType.caption, color = Iris.colors.textTertiary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

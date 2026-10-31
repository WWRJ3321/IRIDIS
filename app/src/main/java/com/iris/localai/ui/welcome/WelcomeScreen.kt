package com.iris.localai.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iris.localai.R
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 1.14.7: texts of the first-launch welcome (kept here so tests can check them). */
object WelcomeText {
    const val TAGLINE = "完全运行在你手机上的 AI"
    const val START = "开始使用"
    const val DISCLAIMER =
        "本应用按原样提供，不提供任何担保。AI 生成内容可能有误，请勿用于医疗、法律或金融决策；智能体操作的后果由你自行承担。"
    /** Title to one-line description, in display order. */
    val FEATURES = listOf(
        "本地推理" to "对话与语音都在本机完成，对话不会上传",
        "智能体" to "可读写工作区文件、运行命令、联网搜索（需你开启）",
        "开源" to "Apache-2.0 许可，第三方组件见 设置 › 关于",
    )
}

/**
 * 1.14.7: shown once before the chat (fresh installs and upgrades alike) until 「开始使用」 sets
 * `AppSettings.welcomeAccepted`. Staggered fade + lift entrance from IrisMotion springs;
 * everything is in place immediately when the system removes animations.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val c = Iris.colors
    val icons: List<ImageVector> = listOf(IrisIcons.Chip, IrisIcons.Agent, IrisIcons.Prompt)
    // mark, wordmark, tagline, 3 rows, disclaimer + button
    val enter = rememberStaggeredEntrance(count = 4 + WelcomeText.FEATURES.size)
    val last = 3 + WelcomeText.FEATURES.size

    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.chatBackground).safeDrawingPadding(),
    ) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(horizontal = IrisDimens.pageGutter + IrisDimens.space8, vertical = IrisDimens.space24),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painterResource(R.drawable.ic_iris_mark), null, tint = c.text,
                    modifier = Modifier.size(56.dp).entrance(enter[0]),
                )
                Spacer(Modifier.height(IrisDimens.space20))
                // D9 wordmark: letters follow the theme ink, the three I's keep the brand gradient.
                Box(Modifier.height(IrisDimens.space32).entrance(enter[1]), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.iridis_wordmark_ink), "IRIDIS", tint = c.text, modifier = Modifier.height(IrisDimens.space32).width(165.dp))
                    Image(painterResource(R.drawable.iridis_wordmark_accent), null, modifier = Modifier.height(IrisDimens.space32).width(165.dp))
                }
                Spacer(Modifier.height(IrisDimens.space12))
                Text(
                    WelcomeText.TAGLINE, style = IrisType.body, color = c.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.entrance(enter[2]),
                )
                Spacer(Modifier.height(IrisDimens.space32))
                Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space16)) {
                    WelcomeText.FEATURES.forEachIndexed { i, (title, desc) ->
                        FeatureRow(icons[i], title, desc, Modifier.entrance(enter[3 + i]))
                    }
                }
                Spacer(Modifier.height(IrisDimens.space32))
                Column(Modifier.entrance(enter[last]), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        WelcomeText.DISCLAIMER, style = IrisType.caption, color = c.textTertiary, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(IrisDimens.space16))
                    IrisButton(WelcomeText.START, onClick = onStart, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String, modifier: Modifier = Modifier) {
    val c = Iris.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(c.surfaceVariant, IrisRadius.smallShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge)) }
        Spacer(Modifier.width(IrisDimens.space12))
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.label, color = c.text)
            Spacer(Modifier.height(IrisDimens.space2))
            Text(desc, style = IrisType.secondary, color = c.textSecondary)
        }
    }
}

/** One progress (0 → 1) per item, started [IrisMotion.ENTRANCE_STAGGER_MS] apart; all 1 when motion is reduced. */
@Composable
private fun rememberStaggeredEntrance(count: Int): List<Animatable<Float, AnimationVector1D>> {
    val reduce = LocalReduceMotion.current
    val items = remember { List(count) { Animatable(if (reduce) 1f else 0f) } }
    LaunchedEffect(reduce) {
        if (reduce) {
            items.forEach { it.snapTo(1f) }
            return@LaunchedEffect
        }
        items.forEachIndexed { i, a ->
            launch {
                delay(i * IrisMotion.ENTRANCE_STAGGER_MS)
                a.animateTo(1f, IrisMotion.Gentle.float())
            }
        }
    }
    return items
}

/** Fade + lift driven by [p] (read in the draw layer only, so no recomposition per frame). */
private fun Modifier.entrance(p: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    val v = p.value
    alpha = v.coerceIn(0f, 1f)
    translationY = (1f - v) * IrisDimens.space16.toPx()
}

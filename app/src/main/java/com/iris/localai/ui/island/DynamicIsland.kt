package com.iris.localai.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.task.TaskProgress
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/** 3.0 应用内灵动岛：读 [TaskProgress]，挑要显示的一项（纯 JVM，单测）。 */
object IslandLogic {
    /** 结束后「已完成 / 已停止 / 失败」停留多久。 */
    const val LINGER_MS = 2600L

    /** 正在跑的任务里优先显示生图，其次下载，再按开始时间；[hidden] = 当前页面自己已显示进度的种类。 */
    fun pick(active: List<TaskProgress.Task>, hidden: Set<TaskProgress.Kind>): TaskProgress.Task? =
        active.filter { it.kind !in hidden && !it.finished }
            .sortedWith(compareBy<TaskProgress.Task> { rank(it.kind) }.thenBy { it.startedAt })
            .firstOrNull()

    private fun rank(k: TaskProgress.Kind) = when (k) {
        TaskProgress.Kind.IMAGE_GEN -> 0
        TaskProgress.Kind.DOWNLOAD -> 1
        TaskProgress.Kind.VOICE -> 2
        TaskProgress.Kind.CHAT -> 3
        TaskProgress.Kind.OTHER -> 4
    }

    /** 胶囊右侧的短文字：百分比、步数，或不定进度时的空串。 */
    fun trailing(t: TaskProgress.Task): String = when {
        t.phase == TaskProgress.Phase.DONE -> "✓"
        t.phase == TaskProgress.Phase.FAILED -> "!"
        t.phase == TaskProgress.Phase.STOPPED -> "■"
        t.kind == TaskProgress.Kind.IMAGE_GEN && t.total > 0 -> "${t.step}/${t.total}"
        t.percent >= 0 -> "${t.percent}%"
        else -> ""
    }
}

/**
 * 顶部居中的黑色胶囊，长任务进行中显示（图标 + 标题 + 进度环）；点一下展开（副标题 + 停止 + 打开）。
 * 任务结束后显示结果 [IslandLogic.LINGER_MS] 再收起。[hidden] 的任务种类不显示（所在页面已有进度）。
 */
@Composable
fun DynamicIsland(
    enabled: Boolean,
    hidden: Set<TaskProgress.Kind>,
    onOpen: (TaskProgress.Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active by TaskProgress.active.collectAsStateWithLifecycle()
    var lingering by remember { mutableStateOf<TaskProgress.Task?>(null) }
    var expanded by remember { mutableStateOf(false) }
    // 结束的那一份只经 surface 送来（active 里已经没有它）
    DisposableEffect(Unit) {
        val s = TaskProgress.TaskSurface { t -> if (t.finished && t.kind !in hidden) lingering = t }
        TaskProgress.addSurface(s)
        onDispose { TaskProgress.removeSurface(s) }
    }
    LaunchedEffect(lingering?.serial, lingering?.phase) { if (lingering != null) { delay(IslandLogic.LINGER_MS); lingering = null; expanded = false } }
    val running = IslandLogic.pick(active, hidden)
    val task = running ?: lingering?.takeIf { it.kind !in hidden }
    val reduce = LocalReduceMotion.current
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = enabled && task != null,
            enter = if (reduce) fadeIn() else fadeIn(IrisMotion.enter()) + scaleIn(IrisMotion.Bouncy.float(), initialScale = 0.6f),
            exit = if (reduce) fadeOut() else fadeOut(IrisMotion.exit()) + scaleOut(IrisMotion.exit(), targetScale = 0.6f),
        ) {
            val t = task ?: return@AnimatedVisibility
            IslandPill(t, expanded, onTap = { expanded = !expanded }, onOpen = { expanded = false; onOpen(t) })
        }
    }
}

@Composable
private fun IslandPill(t: TaskProgress.Task, expanded: Boolean, onTap: () -> Unit, onOpen: () -> Unit) {
    val c = Iris.colors
    val ink = Color(0xFF0B0B0D)
    val accent = when (t.phase) {
        TaskProgress.Phase.FAILED -> c.danger
        TaskProgress.Phase.DONE -> c.success
        else -> c.accent
    }
    Column(
        Modifier
            .widthIn(min = 132.dp, max = if (expanded) 360.dp else 260.dp)
            .background(ink, IrisRadius.pillShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .animateContentSize(IrisMotion.Snappy.size())
            .padding(horizontal = 14.dp, vertical = if (expanded) 12.dp else 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconOf(t.kind), null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                t.title, style = IrisType.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(10.dp))
            AnimatedContent(IslandLogic.trailing(t), label = "islandTrail") { s ->
                if (s.isNotEmpty()) Text(s, style = IrisType.labelSmall, color = Color.White.copy(alpha = 0.75f))
            }
            if (!t.finished) {
                Spacer(Modifier.size(8.dp))
                ProgressRing(t.percent, accent)
            }
        }
        if (expanded) {
            if (t.subtitle.isNotBlank()) {
                Text(t.subtitle, style = IrisType.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (!t.finished) IslandButton(I18n.s(R.string.v30_island_stop), c.danger) { TaskProgress.requestStop(t.id) }
                IslandButton(I18n.s(R.string.v30_island_open), Color.White.copy(alpha = 0.16f), onOpen)
            }
        }
    }
}

@Composable
private fun IslandButton(text: String, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.background(bg, IrisRadius.pillShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(text, style = IrisType.labelSmall, color = Color.White) }
}

@Composable
private fun ProgressRing(percent: Int, color: Color) {
    val reduce = LocalReduceMotion.current
    val spin = if (percent < 0 && !reduce) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "islandSpin")
        tr.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(IrisMotion.linearLoop(1100)), label = "spin")
    } else null
    Canvas(Modifier.size(14.dp)) {
        val w = 2.dp.toPx()
        val sz = Size(size.width - w, size.height - w)
        val o = Offset(w / 2, w / 2)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, o, sz, style = Stroke(w))
        if (percent >= 0) drawArc(color, -90f, 3.6f * percent, false, o, sz, style = Stroke(w, cap = StrokeCap.Round))
        else drawArc(color, (spin?.value ?: 0f) - 90f, 90f, false, o, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

private fun iconOf(k: TaskProgress.Kind) = when (k) {
    TaskProgress.Kind.IMAGE_GEN -> IrisIcons.Image
    TaskProgress.Kind.DOWNLOAD -> IrisIcons.Download
    TaskProgress.Kind.VOICE -> IrisIcons.Voice
    TaskProgress.Kind.CHAT -> IrisIcons.Chat
    TaskProgress.Kind.OTHER -> IrisIcons.Spark
}

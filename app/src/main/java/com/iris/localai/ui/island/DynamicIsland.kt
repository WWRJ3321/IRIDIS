package com.iris.localai.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.VisibilityThreshold
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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

    /** r5：岛的三种形态；内容在它们之间只淡入淡出，尺寸由外层一条弹簧动画。 */
    enum class Mode { PILL, EXPANDED, BIG }
    fun mode(expanded: Boolean, big: Boolean): Mode = if (big) Mode.BIG else if (expanded) Mode.EXPANDED else Mode.PILL
    /** 3.0.0：大图模式要显示的图；空 / null（任务已换）时返回 null。 */
    fun bigShot(result: String?): String? = result?.takeIf { it.isNotBlank() }
    const val OPEN_RADIUS_DP = 28f
    /** r5：进退场、尺寸、圆角共用的一条弹簧（getter，测试里不加载 Compose）。 */
    val SPRING get() = IrisMotion.Snappy

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

/** Beta3 灵动岛的位置：贴前摄挖孔（纯 JVM，单测）。单位都是 dp。 */
object IslandGeom {
    /** 收起时胶囊的高度：比状态栏矮一点，夹在 26–38 dp。 */
    fun pillHeight(statusBarDp: Float): Float = (statusBarDp - 6f).coerceIn(26f, 38f)

    /** 胶囊顶边：有挖孔时和挖孔垂直居中，否则在状态栏里垂直居中。 */
    fun top(statusBarDp: Float, cutoutTopDp: Float?, cutoutHeightDp: Float?, pillDp: Float): Float =
        if (cutoutTopDp != null && cutoutHeightDp != null && cutoutHeightDp > 0f) (cutoutTopDp + cutoutHeightDp / 2f - pillDp / 2f).coerceAtLeast(2f)
        else ((statusBarDp - pillDp) / 2f).coerceAtLeast(2f)

    /** 挖孔中心相对屏幕中线的偏移（挖孔不居中的机型，胶囊跟着挖孔走）；超出 ±120 dp 视为角落挖孔，不跟。 */
    fun centerShift(screenWidthDp: Float, cutoutCenterXDp: Float?): Float {
        val d = (cutoutCenterXDp ?: return 0f) - screenWidthDp / 2f
        return if (kotlin.math.abs(d) > 120f) 0f else d
    }

    /** 中间要给挖孔让出的宽度（左右内容分在两侧）；没有居中挖孔就是 0。 */
    fun gap(cutoutWidthDp: Float?, shiftDp: Float, hasCutout: Boolean): Float =
        if (!hasCutout || cutoutWidthDp == null || cutoutWidthDp > 160f) 0f else cutoutWidthDp + 10f
}

/**
 * Beta3 应用内灵动岛（按宣传片做）：贴前摄挖孔居中的小黑胶囊——左边图标/生图缩略图 + 标题，右边百分比或转圈，
 * 底边一条细进度线；点一下展开成圆角卡片（副标题 + 停止 + 打开），4 秒不碰自动收回；生图完成时展开成大图
 * 「已保存到本机」停 [IslandLogic.LINGER_MS]。[hidden] 的任务种类不显示（所在页面已有进度）。
 */
@Composable
fun DynamicIsland(
    enabled: Boolean,
    hidden: Set<TaskProgress.Kind>,
    onOpen: (TaskProgress.Task) -> Unit,
    modifier: Modifier = Modifier,
    bottomPad: Float = 16f,
) {
    val active by TaskProgress.active.collectAsStateWithLifecycle()
    var lingering by remember { mutableStateOf<TaskProgress.Task?>(null) }
    var expanded by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val s = TaskProgress.TaskSurface { t -> if (t.finished && t.kind !in hidden) lingering = t }
        TaskProgress.addSurface(s)
        onDispose { TaskProgress.removeSurface(s) }
    }
    LaunchedEffect(lingering?.serial, lingering?.phase) {
        if (lingering != null) { delay(if (bigDone(lingering)) IslandLogic.LINGER_MS + 1400 else IslandLogic.LINGER_MS); lingering = null; expanded = false }
    }
    LaunchedEffect(expanded) { if (expanded) { delay(4000); expanded = false } }
    val running = IslandLogic.pick(active, hidden)
    val task = running ?: lingering?.takeIf { it.kind !in hidden }
    val reduce = LocalReduceMotion.current

    // 3.0 Beta3：岛移到左下（输入栏上方），展开/生图大图向上弹出；不再贴挖孔
    val pillH = 34f
    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars
    Box(
        modifier.fillMaxSize()
            .windowInsetsPadding(navBottom)
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = bottomPad.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        // r5：退场时 task 已是 null，以前内容立刻消失、只剩空的退场动画（看起来是「啪」一下没了）——留住最后一个任务画完退场。
        var shown by remember { mutableStateOf<TaskProgress.Task?>(null) }
        if (task != null) shown = task
        // r5：进场 / 退场 / 尺寸 / 圆角同一条弹簧（IslandLogic.SPRING），不再一段 Bouncy、一段 Snappy、一段 Gentle。
        AnimatedVisibility(
            visible = enabled && task != null,
            enter = if (reduce) fadeIn() else fadeIn(IslandLogic.SPRING.float()) + slideInVertically(IslandLogic.SPRING.offset()) { it },
            exit = if (reduce) fadeOut() else fadeOut(IslandLogic.SPRING.float()) + slideOutVertically(IslandLogic.SPRING.offset()) { it },
        ) {
            val t = task ?: shown ?: return@AnimatedVisibility
            IslandPill(t, expanded && task != null, pillH, 0f, reduce, onTap = { if (bigDone(t)) onOpen(t) else expanded = !expanded }, onOpen = { expanded = false; onOpen(t) })
        }
    }
}

/** 生图完成且有图：岛展开成大图。 */
private fun bigDone(t: TaskProgress.Task?): Boolean =
    t != null && t.kind == TaskProgress.Kind.IMAGE_GEN && t.phase == TaskProgress.Phase.DONE && !t.result.isNullOrBlank()

@Composable
private fun IslandPill(t: TaskProgress.Task, expanded: Boolean, pillH: Float, gap: Float, reduce: Boolean, onTap: () -> Unit, onOpen: () -> Unit) {
    val c = Iris.colors
    val ink = Color(0xFF0E0F13)
    val accent = when (t.phase) {
        TaskProgress.Phase.FAILED -> c.danger
        TaskProgress.Phase.DONE -> c.success
        else -> Color(0xFF6EAAFF)
    }
    val big = bigDone(t)
    val open = expanded || big
    val mode = IslandLogic.mode(expanded, big)
    // r5：圆角跟尺寸用同一条弹簧一起变（以前胶囊→卡片圆角瞬间跳变，尺寸再慢慢长）。
    val radius by androidx.compose.animation.core.animateDpAsState(
        (if (open) IslandLogic.OPEN_RADIUS_DP else pillH / 2f).dp,
        if (reduce) androidx.compose.animation.core.snap() else IslandLogic.SPRING.spec(androidx.compose.ui.unit.Dp.VisibilityThreshold),
        label = "islandRadius",
    )
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
    Box(
        Modifier
            // 尺寸只由这一处动画驱动（外层量动画、里层夹宽度、再裁剪）；内容切换只做淡入淡出，不再自己跳尺寸。
            .animateContentSize(if (reduce) androidx.compose.animation.core.snap() else IslandLogic.SPRING.size())
            .widthIn(min = if (open) 300.dp else 0.dp, max = if (open) 360.dp else 260.dp)
            .clip(shape)
            .background(ink)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    ) {
      AnimatedContent(
        mode,
        transitionSpec = {
            if (reduce) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else (fadeIn(IslandLogic.SPRING.float()) togetherWith fadeOut(IrisMotion.Quick.float()))
                .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
        },
        contentAlignment = Alignment.BottomStart,
        label = "islandMode",
      ) { m ->
        when (m) {
            IslandLogic.Mode.BIG -> Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 退场动画里 t 可能已换成下一个任务（result 为 null）：没有图就留同尺寸空位，不再 !! 闪退
                val shot = IslandLogic.bigShot(t.result)
                if (shot != null) com.iris.localai.ui.imagegen.Thumb(shot, Modifier.size(276.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)), maxSide = 640)
                else Box(Modifier.size(276.dp))
                Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(IrisIcons.Check, null, tint = c.success, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(I18n.s(R.string.v31_is_saved), style = IrisType.labelSmall, color = Color.White)
                }
            }
            IslandLogic.Mode.EXPANDED -> Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lead(t, accent, 28.dp)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = IrisType.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.subtitle.isNotBlank()) Text(t.subtitle, style = IrisType.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Trail(t, accent)
                }
                if (t.percent >= 0 && !t.finished) Bar(t.percent, accent, Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (!t.finished) IslandButton(I18n.s(R.string.v30_island_stop), c.danger) { TaskProgress.requestStop(t.id) }
                    IslandButton(I18n.s(R.string.v30_island_open), Color.White.copy(alpha = 0.16f), onOpen)
                }
            }
            IslandLogic.Mode.PILL -> Box(Modifier.height(pillH.dp)) {
                Row(Modifier.fillMaxHeight().padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lead(t, accent, (pillH - 10f).dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        title(t), style = IrisType.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Spacer(Modifier.width(gap.coerceAtLeast(12f).dp))
                    Trail(t, accent)
                }
                // 底边细进度线（有确定进度时）
                if (t.percent >= 0 && !t.finished) Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) { Bar(t.percent, accent, Modifier.padding(start = pillH.dp, end = 14.dp, bottom = 3.dp), thin = true) } // r4：进度线不再把胶囊撑宽
            }
        }
      }
    }
}

private fun title(t: TaskProgress.Task): String =
    if (t.kind == TaskProgress.Kind.IMAGE_GEN && !t.finished) I18n.s(R.string.v31_is_gen) else t.title

/** 左侧：生图时是实时预览缩略图，其他任务是图标。 */
@Composable
private fun Lead(t: TaskProgress.Task, accent: Color, size: androidx.compose.ui.unit.Dp) {
    val frame = (t.payload as? com.iris.localai.service.ImageGenMonitor.Extra)?.preview
    if (t.kind == TaskProgress.Kind.IMAGE_GEN && frame != null) {
        val bmp = remember(frame) { runCatching { android.graphics.Bitmap.createBitmap(frame.argb, frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull() }
        if (bmp != null) {
            androidx.compose.foundation.Image(bmp, null, Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            return
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) { Icon(iconOf(t.kind), null, tint = accent, modifier = Modifier.size(16.dp)) }
}

/** 右侧：百分比 / 步数，或不定进度的转圈，或结果符号。 */
@Composable
private fun Trail(t: TaskProgress.Task, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // r5：百分比每跳一次不再整段淡出淡入（数字在闪、宽度在抖）；只有「进行中 ↔ 完成/失败」才过渡。
        val s = IslandLogic.trailing(t)
        val rm = LocalReduceMotion.current
        AnimatedContent(t.finished, transitionSpec = { if (rm) com.iris.localai.ui.motion.IrisMotion.none else com.iris.localai.ui.motion.IrisMotion.crossfade() }, label = "islandTrail") { _ ->
            if (s.isNotEmpty()) Text(s, style = IrisType.labelSmall, color = if (t.finished) accent else Color(0xFF9CC2FF))
        }
        if (!t.finished && t.percent < 0) { Spacer(Modifier.size(6.dp)); ProgressRing(t.percent, accent) }
    }
}

@Composable
private fun Bar(percent: Int, color: Color, modifier: Modifier, thin: Boolean = false) {
    val h = if (thin) 2.dp else 4.dp
    Box(modifier.fillMaxWidth().height(h).clip(IrisRadius.pillShape).background(Color.White.copy(alpha = 0.14f))) {
        Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).fillMaxHeight().clip(IrisRadius.pillShape).background(color))
    }
}

@Composable
private fun IslandButton(text: String, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(IrisRadius.pillShape).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
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

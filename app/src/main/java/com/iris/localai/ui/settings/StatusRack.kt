package com.iris.localai.ui.settings

import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.LocalDecorativeMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlin.math.cos
import kotlin.math.sin

/** 1.14.0: state of one rack slot. */
enum class RackState(val code: String, val label: String) {
    OFF("OFF", "关闭"), STANDBY("STANDBY", "待机"), ON("ON", "就绪"), RUN("RUN", "运行中"), LOAD("LOAD", "加载中"), DONE("DONE", "完成"), ERROR("ERR", "出错");
    val up: Boolean get() = this == ON || this == RUN || this == LOAD || this == DONE
}

data class RackModule(val code: String, val name: String, val state: RackState, val detail: String, val chips: List<String> = emptyList())

/** Inputs of the rack (all plain values, so [RackRules] stays pure and testable). */
data class RackInput(
    val modelName: String? = null,
    val modelState: RackState = RackState.STANDBY,
    val modelDetail: String = "",
    val modelChips: List<String> = emptyList(),
    val agentMode: Boolean = false,
    val agentRunning: Boolean = false,
    val tool: String? = null,
    val toolGroup: String? = null,
    val stepsDone: Int = 0,
    val webOn: Boolean = false,
    /** 2.2.2: the screen tool (GUI agent) is enabled. */
    val screenOn: Boolean = false,
    val voicePacks: Int = 0,
    val voiceActive: Boolean = false,
    val apiRunning: Boolean = false,
    val apiPort: Int = 0,
    val apiError: String? = null,
    val memoryOn: Boolean = false,
    val planLeft: Int = 0,
)

object RackRules {
    fun modules(i: RackInput): List<RackModule> {
        fun live(group: String) = i.agentRunning && i.toolGroup == group
        return listOf(
            RackModule("PWR", "模型", i.modelState, i.modelName?.let { it + (if (i.modelDetail.isNotBlank()) " · " + i.modelDetail else "") } ?: "未加载模型", i.modelChips),
            RackModule(
                "AGENT", "智能体",
                when { i.agentRunning -> RackState.RUN; !i.agentMode -> RackState.OFF; i.planLeft > 0 -> RackState.STANDBY; else -> RackState.ON },
                when {
                    i.agentRunning -> (i.tool?.let { "正在：$it" } ?: "思考中") + " · 已完成 ${i.stepsDone} 步"
                    !i.agentMode -> "对话模式（不调用工具）"
                    i.planLeft > 0 -> "计划还有 ${i.planLeft} 步"
                    else -> "智能体模式"
                },
                listOfNotNull(i.tool, if (i.planLeft > 0) "计划 ${i.planLeft}" else null, "${i.stepsDone} 步"),
            ),
            RackModule("NET", "联网", if (!i.webOn) RackState.OFF else if (live("WEB")) RackState.RUN else RackState.ON,
                if (i.webOn) "搜索与网页读取已开启" else "联网工具已关闭", listOf("搜索", "网页")),
            RackModule("SCREEN", "看屏", if (!i.screenOn) RackState.OFF else if (i.agentRunning && i.tool == com.iris.localai.agent.ScreenTool.label) RackState.RUN else RackState.STANDBY,
                if (i.screenOn) "可操作手机屏幕" else "看屏工具已关闭", emptyList()),
            RackModule("VOICE", "语音", when { i.voiceActive -> RackState.RUN; i.voicePacks > 0 -> RackState.ON; else -> RackState.OFF },
                if (i.voicePacks > 0) "已安装 ${i.voicePacks} 个语音包" else "未安装语音包", listOf("${i.voicePacks} 包")),
            RackModule("API", "API 服务", when { i.apiError != null -> RackState.ERROR; i.apiRunning -> RackState.ON; else -> RackState.OFF },
                i.apiError ?: if (i.apiRunning) "运行中 · 端口 ${i.apiPort}" else "未启动", if (i.apiRunning) listOf(":${i.apiPort}") else emptyList()),
            RackModule("MEM", "记忆", if (!i.memoryOn) RackState.OFF else if (live("MEMORY")) RackState.RUN else RackState.ON,
                if (i.memoryOn) "长期记忆已开启" else "长期记忆已关闭", emptyList()),
        )
    }

    /** The slot that pulls out by itself: whatever is running (tool first), else none. */
    fun autoSelect(mods: List<RackModule>): Int = mods.indexOfLast { it.state == RackState.RUN && it.code != "PWR" }
        .takeIf { it >= 0 } ?: mods.indexOfFirst { it.state == RackState.RUN || it.state == RackState.LOAD }
}

private val Orange = com.iris.localai.ui.theme.IrisChartColors.orange
private const val C30 = 0.8660254f
private const val S30 = 0.5f

/**
 * 1.14.0 运行状态: an isometric compute rack — one slot per part of IRIS with its state; the
 * running (or tapped) slot slides out in orange, the fans spin while a reply is written.
 * 1.14.1: every size is relative to the rack (text no longer spills into the next slot), the
 * rack is taller so slots are big enough to tap, and a plain list underneath names each part
 * and opens its settings.
 */
@Composable
fun StatusRack(
    modules: List<RackModule>, generating: Boolean, modelReady: Boolean,
    modifier: Modifier = Modifier, onOpen: ((Int) -> Unit)? = null,
) {
    val c = Iris.colors
    // 1.14.4: only the system "remove animations" switch snaps the rack (1.14.0: 减少动态效果时风扇静止).
    // It used to be OR'ed with LocalDecorativeMotion, which is false while a reply streams, in battery
    // saver and at 视觉效果 省电 — so the drawers, the boot sequence and the fans never moved, and the
    // "fans speed up while generating" state could never be seen at all.
    val reduce = LocalReduceMotion.current
    // Repeating loops (fans, LED pulse) run only while the rack is actually on screen; generating
    // counts as "in progress", the reason LocalDecorativeMotion exists, so it may loop then too.
    var onScreen by remember { mutableStateOf(false) }
    val loops = !reduce && onScreen && (LocalDecorativeMotion.current || generating)
    val measurer = rememberTextMeasurer()
    var picked by rememberSaveable { mutableStateOf(-2) } // -2 = follow what is running
    val auto = RackRules.autoSelect(modules)
    val sel = if (picked == -2) auto else picked
    val pulls = remember(modules.size) { List(modules.size) { Animatable(0f) } }
    modules.indices.forEach { i ->
        LaunchedEffect(sel == i, reduce) {
            val target = if (sel == i) 1f else 0f
            if (reduce) pulls[i].snapTo(target) else pulls[i].animateTo(target, IrisMotion.Gentle.float())
        }
    }
    // 1.14.1: power-on sequence — the slots slide in one after another and their lights come on.
    val boot = remember(modules.size) { List(modules.size) { Animatable(if (reduce) 1f else 0f) } }
    LaunchedEffect(modules.size) {
        boot.forEachIndexed { i, a ->
            launch {
                kotlinx.coroutines.delay(120L + i * 70L)
                if (reduce) a.snapTo(1f) else a.animateTo(1f, IrisMotion.Bouncy.float())
            }
        }
    }
    val upAnim by animateFloatAsState(modules.count { it.state.up }.toFloat(), IrisMotion.Gentle.float(), label = "upBars")
    val up = modules.count { it.state.up }
    // 1.14.4: the infinite transition only exists while [loops] (it ticks frames for as long as it is
    // composed, read or not). Its values are State objects read inside draw / graphicsLayer, so a
    // frame redraws the canvas instead of recomposing the whole rack and its list.
    val angleState: State<Float>
    val blinkState: State<Float>
    if (loops) {
        val spin = rememberInfiniteTransition(label = "fans")
        val period = if (generating) 900 else 4200
        angleState = spin.animateFloat(0f, 360f, infiniteRepeatable(IrisMotion.linearLoop(period), RepeatMode.Restart), label = "fanAngle")
        blinkState = spin.animateFloat(0f, 1f, infiniteRepeatable(IrisMotion.loop(IrisMotion.PULSE_PERIOD_MS), RepeatMode.Reverse), label = "blink")
    } else {
        angleState = remember { mutableFloatStateOf(0f) }
        blinkState = remember { mutableFloatStateOf(1f) }
    }
    // Fans idle gently whenever anything in the rack is up (they used to need a loaded model), fast while generating.
    val fansOn = generating || modelReady || up > 0

    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    val face = c.surface
    val front = lerp(c.surfaceVariant, c.surface, 0.35f)
    val side = c.surfaceVariant
    val line = lerp(c.outline, c.textTertiary, 0.45f)
    val ink = c.textSecondary
    val dark = lerp(c.text, c.background, 0.12f)
    fun ledOf(s: RackState, b: Float) = when (s) {
        RackState.RUN, RackState.LOAD -> Orange.copy(alpha = 0.45f + 0.55f * b)
        RackState.ON, RackState.DONE -> c.success
        RackState.ERROR -> c.danger
        RackState.STANDBY -> lerp(line, c.textTertiary, 0.5f)
        RackState.OFF -> line
    }

    val selNow by rememberUpdatedState(sel) // the tap handler outlives compositions; don't toggle against a stale sel
    val openNow by rememberUpdatedState(onOpen)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(RackGeom.ASPECT)
                .onGloballyPositioned { onScreen = it.isAttached && it.boundsInWindow().height > 0f }
                .pointerInput(modules.size) {
                detectTapGestures { tap ->
                    val geo = RackGeom(size.width.toFloat(), size.height.toFloat())
                    // 1.14.5: the pulled-out drawer is a real control — its readout (top) opens that
                    // part like the list row does; its front (handle) pushes it back in.
                    val open = selNow
                    val drawer = if (open in modules.indices) geo.drawerHit(tap, open, modules.size, pulls[open].value * geo.OPEN) else DrawerHit.NONE
                    when (drawer) {
                        DrawerHit.CONTENT -> { picked = open; openNow?.invoke(open) }
                        DrawerHit.HANDLE -> picked = -1
                        DrawerHit.NONE -> {
                            val hit = geo.slotAt(tap, modules.size)
                            picked = when { hit == null -> -1; hit == selNow -> -1; else -> hit }
                        }
                    }
                }
            },
        ) {
            val blink = blinkState.value
            val fanAngle = if (fansOn) angleState.value else 0f
            val g = RackGeom(size.width, size.height)
            with(g) {
                val n = modules.size
                // ---- base plate ----
                val bx0 = -0.10f; val bx1 = W + 0.10f; val by0 = -0.08f; val by1 = D + 0.10f; val bz = -0.03f
                poly(listOf(p(bx0, by0, 0f), p(bx1, by0, 0f), p(bx1, by1, 0f), p(bx0, by1, 0f)), face, line)
                poly(listOf(p(bx0, by0, bz), p(bx1, by0, bz), p(bx1, by0, 0f), p(bx0, by0, 0f)), front, line)
                poly(listOf(p(bx1, by0, bz), p(bx1, by1, bz), p(bx1, by1, 0f), p(bx1, by0, 0f)), side, line)
                // ---- box ----
                poly(listOf(p(0f, 0f, H), p(W, 0f, H), p(W, D, H), p(0f, D, H)), face, line)
                poly(listOf(p(0f, 0f, 0f), p(W, 0f, 0f), p(W, 0f, H), p(0f, 0f, H)), front, line)
                poly(listOf(p(W, 0f, 0f), p(W, D, 0f), p(W, D, H), p(W, 0f, H)), side, line)

                // ---- fans on the top, along the depth ----
                // 1.14.1: three fans side by side along the width, with a gap between them (they overlapped).
                val fr = 0.105f
                listOf(0.22f, 0.50f, 0.78f).forEach { f -> fan(W * f, D * 0.50f, fr, fanAngle, line, ink, face) }

                // ---- side: vents and four status screens ----
                for (k in 0 until 6) {
                    val z = H - 0.10f - k * 0.025f
                    drawLine(line, p(W, D * 0.70f, z), p(W, D * 0.92f, z), 1.dp.toPx())
                }
                val lit = (up * 4 + n - 1) / n.coerceAtLeast(1)
                for (j in 0 until 4) {
                    val z1 = H - 0.30f - j * 0.22f; val z0 = z1 - 0.18f
                    poly(listOf(p(W, D * 0.10f, z0), p(W, D * 0.60f, z0), p(W, D * 0.60f, z1), p(W, D * 0.10f, z1)), dark, line)
                    if (j < lit) drawCircle(if (generating) Orange.copy(alpha = 0.5f + 0.5f * blink) else c.success, 0.012f * k(), p(W, D * 0.55f, z1 - 0.03f))
                }

                // ---- header: power, UP n/N, load bar ----
                val hz1 = H - 0.03f; val hz0 = H - HEADER
                poly(listOf(p(0.04f, 0f, hz0), p(W - 0.04f, 0f, hz0), p(W - 0.04f, 0f, hz1), p(0.04f, 0f, hz1)), front, if (generating) Orange else line)
                val hm = (hz0 + hz1) / 2
                // 1.14.1: the power button lies on the front face (it was a flat screen circle).
                val pcx = 0.14f; val pr = 0.075f
                faceDisc(pcx, 0f, hm, pr, dark)
                val pw = if (modelReady) c.success else c.surface
                faceArc(pcx, 0f, hm, pr * 0.55f, 120f, 420f, pw, pr * 0.14f * k())
                faceLine(pcx, 0f, hm + pr * 0.10f, pcx, 0f, hm + pr * 0.68f, pw, pr * 0.14f * k())
                frontText("UP", 0.26f, hm + 0.035f, mono.copy(color = ink), 0.055f, measurer)
                frontText("$up/$n", 0.40f, hm + 0.035f, mono.copy(color = c.text), 0.075f, measurer)
                val bars = 8
                val booted = boot.count { it.value > 0.9f }
                val litBars = if (generating) ((blink * 0.6f + 0.4f) * bars).toInt() else (upAnim * bars / n.coerceAtLeast(1)).toInt().coerceAtMost(booted * bars / n.coerceAtLeast(1))
                for (k in 0 until bars) {
                    val x0 = 0.26f + k * 0.075f
                    val z = hz0 + 0.035f
                    poly(listOf(p(x0, 0f, z), p(x0 + 0.055f, 0f, z), p(x0 + 0.055f, 0f, z + 0.035f), p(x0, 0f, z + 0.035f)),
                        if (k < litBars) Orange.copy(alpha = 0.9f) else lerp(front, line, 0.35f), null)
                }

                // ---- slots: a drawn-out slot sits in front of everything, so it is drawn last ----
                val order = (n - 1 downTo 0).sortedBy { pulls[it].value > 0.01f || boot[it].value < 0.99f }
                for (i in order) {
                    val m = modules[i]
                    val (z0, z1) = slotZ(i, n)
                    val zm = (z0 + z1) / 2
                    val sh = z1 - z0
                    val b = boot[i].value
                    if (b < 0.01f) continue
                    val pull = pulls[i].value * OPEN
                    val active = pulls[i].value > 0.01f
                    val y = -pull - (1f - b) * 0.30f
                    val x0 = 0.04f; val x1 = W - 0.04f
                    if (active) {
                        poly(listOf(p(x0, y, z1), p(x1, y, z1), p(x1, 0f, z1), p(x0, 0f, z1)), lerp(face, Orange, 0.08f), Orange)
                        poly(listOf(p(x1, y, z0), p(x1, 0f, z0), p(x1, 0f, z1), p(x1, y, z1)), side, Orange)
                    }
                    poly(listOf(p(x0, y, z0), p(x1, y, z0), p(x1, y, z1), p(x0, y, z1)), if (active) lerp(front, Orange, 0.06f) else front, if (active) Orange else line)
                    faceDisc(x0 + 0.05f, y, zm, sh * 0.30f, dark.copy(alpha = 0.15f))
                    faceDisc(x0 + 0.05f, y, zm, sh * 0.20f, if (b > 0.9f) ledOf(m.state, blink) else line)
                    val txt = if (active) Orange else c.text
                    frontText("%02d %s".format(i + 1, m.code), x0 + 0.11f, zm, mono.copy(color = txt), sh * 0.50f, measurer, y)
                    frontText(m.state.code, x0 + 0.60f, zm, mono.copy(color = if (active) Orange else ink, fontWeight = FontWeight.Medium), sh * 0.40f, measurer, y)
                    faceRing(x1 - 0.045f, y, zm, sh * 0.16f, line, 1.dp.toPx())
                    // 1.14.5「抽屉里没东西」: the open drawer's top carries a readout of that part.
                    // Only the selected drawer, and only once it is mostly out (snaps in with reduce motion).
                    val reveal = ((pulls[i].value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    if (i == sel && reveal > 0f) {
                        drawerReadout(m, x0, x1, y, z1, reveal, measurer, mono,
                            bay = lerp(face, dark, 0.05f), text = c.text, sub = c.textSecondary, line = line,
                            stateColor = when (m.state) {
                                RackState.RUN, RackState.LOAD -> Orange; RackState.ERROR -> c.danger
                                RackState.ON, RackState.DONE -> c.success; else -> c.textTertiary
                            })
                    }
                }
            }
        }
        // 1.14.1: the same parts as a readable list — tap opens that part's settings.
        // 2.0.x: only the parts that are doing something (and the selected one) are listed; the
        // rest fold into one 「其余 N 项」 row. The rack above still shows every drawer.
        var showAll by rememberSaveable { mutableStateOf(false) }
        val listed = RackList.visible(modules.map { it.state }, sel)
        modules.forEachIndexed { i, m ->
            val b = boot.getOrNull(i)?.value ?: 1f
            val dot by animateColorAsState(ledOf(m.state, 1f), IrisMotion.Color.spec(), label = "rackDot")
            val bg by animateColorAsState(if (i == sel) Orange.copy(alpha = 0.10f) else Color.Transparent, IrisMotion.Color.spec(), label = "rackRow")
            val stateColor by animateColorAsState(when (m.state) {
                RackState.RUN, RackState.LOAD -> Orange; RackState.ERROR -> c.danger
                RackState.ON, RackState.DONE -> c.success; else -> c.textTertiary
            }, IrisMotion.Color.spec(), label = "rackState")
            val breathe = m.state == RackState.RUN
            com.iris.localai.ui.components.Fold(showAll || i in listed) {
            Row(
                Modifier.fillMaxWidth()
                    .graphicsLayer { alpha = b.coerceIn(0f, 1f); translationY = (1f - b) * 18.dp.toPx() }
                    .clip(com.iris.localai.ui.theme.IrisRadius.mediumShape)
                    .background(bg)
                    .clickable(onClickLabel = "打开${m.name}设置") { picked = i; onOpen?.invoke(i) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space6),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).graphicsLayer { val sc = if (breathe) 0.8f + 0.4f * blinkState.value else 1f; scaleX = sc; scaleY = sc }.clip(CircleShape).background(dot))
                Spacer(Modifier.width(IrisDimens.space12))
                androidx.compose.material3.Icon(RACK_ICONS.getOrElse(i) { com.iris.localai.ui.icons.IrisIcons.Chip }, null,
                    tint = if (m.state.up) c.text else c.textTertiary, modifier = Modifier.size(IrisDimens.space20))
                Spacer(Modifier.width(IrisDimens.space12))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = IrisType.row, color = c.text, maxLines = 1)
                    Text(m.detail, style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(IrisDimens.space8))
                androidx.compose.animation.AnimatedContent(m.state.label, transitionSpec = { IrisMotion.roll(true) }, label = "rackLabel") { t ->
                    Text(t, style = IrisType.caption, color = stateColor)
                }
            }
            }
        }
        val hidden = modules.size - listed.size
        if (hidden > 0) {
            Text(
                RackList.toggleLabel(hidden, showAll), style = IrisType.caption, color = c.textSecondary,
                modifier = Modifier.fillMaxWidth()
                    .clip(com.iris.localai.ui.theme.IrisRadius.mediumShape)
                    .clickable(onClickLabel = if (showAll) "收起" else "展开") { showAll = !showAll }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = IrisDimens.space16, vertical = 11.dp),
            )
        }
    }
}

/** 2.0.x: which rows of the rack's list are shown while it is folded (pure, unit-tested). */
object RackList {
    /** Indices of parts that are not off / on standby, plus the selected one. */
    fun visible(states: List<RackState>, selected: Int): Set<Int> =
        states.indices.filter { states[it] != RackState.OFF && states[it] != RackState.STANDBY || it == selected }.toSet()

    fun toggleLabel(hidden: Int, open: Boolean): String = if (open) "收起" else "其余 $hidden 项 ›"
}

/** Isometric projection of the rack (x along the front, y into depth, z up). */
private class RackGeom(val cw: Float, val ch: Float) {
    val W = 1f
    val D = 0.46f
    val H = 1.22f
    val PULL = 0.15f
    /** 1.14.5: how far the selected drawer slides out — deep enough to show its readout on top (fits the left margin). */
    val OPEN = 0.26f
    val HEADER = 0.30f

    private val k0 = cw / SPAN_X
    fun k() = k0
    private val ox = (PULL + MARGIN) * C30 * k0
    private val oy = (D * S30 + H + 0.06f) * k0

    fun p(x: Float, y: Float, z: Float) = Offset(ox + (x + y) * C30 * k0, oy + ((x - y) * S30 - z) * k0)

    fun slotZ(i: Int, n: Int): Pair<Float, Float> {
        val top = H - HEADER - 0.02f
        val bottom = 0.04f
        val step = (top - bottom) / n
        val z1 = top - i * step
        return (z1 - step + 0.01f) to (z1 - 0.01f)
    }

    /** Slot under a tap (front face, a little forgiving); null = none. */
    fun slotAt(t: Offset, n: Int): Int? {
        val x = (t.x - ox) / (C30 * k0)
        if (x < -0.25f || x > W + 0.05f) return null
        val z = x.coerceIn(0f, W) * S30 - (t.y - oy) / k0
        for (i in 0 until n) { val (z0, z1) = slotZ(i, n); if (z in (z0 - 0.01f)..(z1 + 0.01f)) return i }
        return null
    }

    fun DrawScope.poly(pts: List<Offset>, fill: Color, stroke: Color?) {
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, fill)
        if (stroke != null) drawPath(path, stroke, style = Stroke(1.dp.toPx()))
    }

    /** A circle lying on the front face (x–z plane at depth [y]): an ellipse on screen. */
    private fun faceRingPts(cx: Float, y: Float, cz: Float, r: Float, from: Float = 0f, to: Float = 360f, steps: Int = 36): List<Offset> =
        (0..steps).map { i ->
            val t = Math.toRadians((from + (to - from) * i / steps).toDouble())
            p(cx + r * cos(t).toFloat(), y, cz + r * sin(t).toFloat())
        }
    fun DrawScope.faceDisc(cx: Float, y: Float, cz: Float, r: Float, fill: Color) = poly(faceRingPts(cx, y, cz, r), fill, null)
    fun DrawScope.faceRing(cx: Float, y: Float, cz: Float, r: Float, color: Color, width: Float) =
        faceArc(cx, y, cz, r, 0f, 360f, color, width)
    fun DrawScope.faceArc(cx: Float, y: Float, cz: Float, r: Float, from: Float, to: Float, color: Color, width: Float) {
        val pts = faceRingPts(cx, y, cz, r, from, to)
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
    fun DrawScope.faceLine(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, color: Color, width: Float) =
        drawLine(color, p(x0, y0, z0), p(x1, y1, z1), width, StrokeCap.Round)

    fun DrawScope.fan(cx: Float, cy: Float, r: Float, angle: Float, line: Color, ink: Color, bg: Color) {
        fun pt(u: Float, v: Float) = p(cx + u, cy + v, H)
        fun ring(rr: Float): List<Offset> = (0 until 40).map { a -> val t = a / 40f * 2f * Math.PI.toFloat(); pt(rr * cos(t), rr * sin(t)) }
        poly(ring(r * 1.15f), bg, line)
        poly(ring(r), lerp(bg, ink, 0.12f), line)
        val a0 = Math.toRadians(angle.toDouble()).toFloat()
        for (b in 0 until 5) {
            val t = a0 + b * 2f * Math.PI.toFloat() / 5f
            poly(listOf(
                pt(r * 0.18f * cos(t), r * 0.18f * sin(t)),
                pt(r * 0.92f * cos(t - 0.15f), r * 0.92f * sin(t - 0.15f)),
                pt(r * 0.92f * cos(t + 0.55f), r * 0.92f * sin(t + 0.55f)),
                pt(r * 0.30f * cos(t + 0.75f), r * 0.30f * sin(t + 0.75f)),
            ), lerp(bg, ink, 0.55f), null)
        }
        poly(ring(r * 0.16f), bg, line)
    }

    /**
     * Text on the front face, reading along +x, vertically centred on [zMid]; [height] is the
     * cap height in rack units, so text always fits its slot.
     */
    fun DrawScope.frontText(s: String, x: Float, zMid: Float, style: TextStyle, height: Float, m: androidx.compose.ui.text.TextMeasurer, y: Float = 0f) {
        val px = height * k0
        val layout = m.measure(s, style.copy(fontSize = (px / 1.0f).toSp(), lineHeight = (px * 1.15f).toSp()))
        val o = p(x, y, zMid)
        val mtx = Matrix().apply {
            values[Matrix.ScaleX] = C30; values[Matrix.SkewY] = S30
            values[Matrix.TranslateX] = o.x; values[Matrix.TranslateY] = o.y
        }
        withTransform({ transform(mtx) }) { drawText(layout, topLeft = Offset(0f, -layout.size.height / 2f)) }
    }

    /** 1.14.5: map a screen point onto the plane z = [z] (rack units x, y). */
    private fun onTop(t: Offset, z: Float): Pair<Float, Float> {
        val a = (t.x - ox) / (C30 * k0)
        val b = ((t.y - oy) / k0 + z) / S30
        return (a + b) / 2 to (a - b) / 2
    }

    /** 1.14.5: what a tap hits on the drawer [i] pulled out by [pull]: its readout (top) or its handle (front / side). */
    fun drawerHit(t: Offset, i: Int, n: Int, pull: Float): DrawerHit {
        if (pull < 0.05f) return DrawerHit.NONE
        val (z0, z1) = slotZ(i, n)
        val x0 = 0.04f; val x1 = W - 0.04f
        val (tx, ty) = onTop(t, z1)
        if (tx in x0..x1 && ty in -pull..0f) return DrawerHit.CONTENT
        // front face at y = -pull
        val fx = (t.x - ox) / (C30 * k0) + pull
        val fz = (fx + pull) * S30 - (t.y - oy) / k0
        if (fx in (x0 - 0.02f)..(x1 + 0.02f) && fz in (z0 - 0.01f)..(z1 + 0.01f)) return DrawerHit.HANDLE
        // right side face at x = x1
        val sy = (t.x - ox) / (C30 * k0) - x1
        val sz = (x1 - sy) * S30 - (t.y - oy) / k0
        if (sy in -pull..0f && sz in z0..z1) return DrawerHit.HANDLE
        return DrawerHit.NONE
    }

    /**
     * 1.14.5: text lying on a top face (plane z = [z]), reading along +x with its top toward the back
     * (+y), vertically centred on depth [yMid]. Returns the drawn width in rack units.
     */
    fun DrawScope.topText(
        s: String, x: Float, yMid: Float, z: Float, style: TextStyle, height: Float, m: androidx.compose.ui.text.TextMeasurer,
        maxWidth: Float = 0f, rightAlign: Boolean = false,
    ): Float {
        if (s.isEmpty()) return 0f
        val px = height * k0
        val st = style.copy(fontSize = px.toSp(), lineHeight = (px * 1.15f).toSp())
        val layout = if (maxWidth > 0f)
            m.measure(s, st, overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (maxWidth * k0).toInt().coerceAtLeast(1)))
        else m.measure(s, st, maxLines = 1, softWrap = false)
        val w = layout.size.width / k0
        val o = p(if (rightAlign) x - w else x, yMid, z)
        val mtx = Matrix().apply {
            values[Matrix.ScaleX] = C30; values[Matrix.SkewY] = S30
            values[Matrix.SkewX] = -C30; values[Matrix.ScaleY] = S30
            values[Matrix.TranslateX] = o.x; values[Matrix.TranslateY] = o.y
        }
        withTransform({ transform(mtx) }) { drawText(layout, topLeft = Offset(0f, -layout.size.height / 2f)) }
        return w
    }

    /**
     * 1.14.5: the readout inside an open drawer (top face from depth [y] to 0 at height [z]):
     * name + state, the part's detail line, up to three chips, and an 打开 › hint — the same
     * data the list row shows. Drawn for the selected drawer only.
     */
    fun DrawScope.drawerReadout(
        mod: RackModule, x0: Float, x1: Float, y: Float, z: Float, reveal: Float,
        m: androidx.compose.ui.text.TextMeasurer, mono: TextStyle,
        bay: Color, text: Color, sub: Color, line: Color, stateColor: Color,
    ) {
        val a = reveal
        val depth = -y
        if (depth < 0.15f) return
        val ix0 = x0 + 0.025f; val ix1 = x1 - 0.025f
        val back = -0.012f; val frontY = y + 0.014f
        poly(listOf(p(ix0, frontY, z), p(ix1, frontY, z), p(ix1, back, z), p(ix0, back, z)), bay.copy(alpha = bay.alpha * a), line.copy(alpha = 0.6f * a))
        val tx = ix0 + 0.03f
        val base = TextStyle(fontWeight = FontWeight.SemiBold)
        // line 1: name · state            打开 ›
        val l1 = y + depth * 0.80f
        val hint = topText("打开 ›", ix1 - 0.03f, l1, z, base.copy(color = Orange.copy(alpha = a), fontWeight = FontWeight.Medium), 0.044f, m, rightAlign = true)
        val nw = topText(mod.name, tx, l1, z, base.copy(color = text.copy(alpha = a)), 0.056f, m, maxWidth = 0.40f)
        topText("· " + mod.state.label, tx + nw + 0.02f, l1, z, base.copy(color = stateColor.copy(alpha = a), fontWeight = FontWeight.Medium), 0.046f, m,
            maxWidth = (ix1 - 0.05f - hint - (tx + nw + 0.02f)).coerceAtLeast(0.05f))
        // line 2: detail
        val l2 = y + depth * 0.56f
        topText(mod.detail, tx, l2, z, TextStyle(color = sub.copy(alpha = a)), 0.044f, m, maxWidth = ix1 - 0.03f - tx)
        // line 3: chips
        val chips = mod.chips.filter { it.isNotBlank() }.take(3)
        if (chips.isEmpty()) return
        val l3 = y + depth * 0.27f
        val ch = 0.060f
        var cx = tx
        for (chip in chips) {
            val st = mono.copy(color = sub.copy(alpha = a), fontWeight = FontWeight.Medium)
            val w = m.measure(chip, st.copy(fontSize = (0.038f * k0).toSp()), maxLines = 1, softWrap = false).size.width / k0
            val bw = w + 0.04f
            if (cx + bw > ix1 - 0.03f) break
            poly(listOf(p(cx, l3 - ch / 2, z), p(cx + bw, l3 - ch / 2, z), p(cx + bw, l3 + ch / 2, z), p(cx, l3 + ch / 2, z)),
                Orange.copy(alpha = 0.08f * a), Orange.copy(alpha = 0.55f * a))
            topText(chip, cx + 0.02f, l3, z, st, 0.038f, m)
            cx += bw + 0.025f
        }
    }

    companion object {
        private const val MARGIN = 0.08f
        private const val SPAN_X = (0.15f + MARGIN + 1.1f + 0.56f + 0.04f) * C30
        private const val SPAN_Y = (1f + 0.46f + 0.15f) * S30 + 1.22f + 0.14f
        const val ASPECT = SPAN_X / SPAN_Y
    }
}

/** 1.14.5: what a tap on the open drawer lands on. */
private enum class DrawerHit { NONE, CONTENT, HANDLE }

/** 1.14.1: list icons, same order as the rack layers (模型 智能体 联网 看屏 语音 API 记忆). */
private val RACK_ICONS by lazy {
    with(com.iris.localai.ui.icons.IrisIcons) { listOf(Chip, Agent, Globe, Eye, Voice, Plug, Memory) }
}

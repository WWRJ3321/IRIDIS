package com.iris.localai.ui.agent

import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.agent.Plan
import com.iris.localai.agent.PlanStatus
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.glass.GlassBackdrop
import com.iris.localai.ui.glass.GlassStyle
import com.iris.localai.ui.glass.glass
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/**
 * 1.14.0 计划卡片: the conversation's plan above the composer — one line (计划 · 2/5 · 当前步骤)
 * that opens into a vertical flow: a node per step joined by a line, the current one breathing,
 * finished ones drawing their check. Finishing the whole plan gives a success haptic.
 */
@Composable
fun PlanCard(plan: Plan?, backdrop: GlassBackdrop, onClear: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(plan != null && plan.steps.isNotEmpty(), enter = IrisMotion.expand(), exit = IrisMotion.collapse(), modifier = modifier) {
        val p = plan ?: return@AnimatedVisibility
        val c = Iris.colors
        val haptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
        var open by rememberSaveable { mutableStateOf(false) }
        val turn by animateFloatAsState(if (open) 180f else 0f, IrisMotion.Snappy.float(), label = "planChevron")
        val progress by animateFloatAsState(if (p.steps.isEmpty()) 0f else p.done.toFloat() / p.steps.size, IrisMotion.Gentle.float(), label = "planProgress")
        var lastDone by remember { mutableStateOf(p.done) }
        LaunchedEffect(p.done, p.finished) {
            if (p.done > lastDone) haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.STEP_DONE)
            lastDone = p.done
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = IrisDimens.space6)
                .glass(backdrop, IrisRadius.mediumShape, GlassStyle(tint = c.glassTintOpaque))
                .clip(IrisRadius.mediumShape),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "收起计划" else "展开计划") { open = !open }
                    .padding(start = IrisDimens.space12, end = IrisDimens.space4, top = IrisDimens.space4, bottom = IrisDimens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(progress, done = p.finished, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(IrisDimens.space8))
                Text(
                    if (p.finished) "计划已完成 · ${p.steps.size} 步" else "计划 ${p.done}/${p.steps.size}",
                    style = IrisType.labelSmall, color = if (p.finished) c.success else c.text, maxLines = 1,
                )
                val cur = p.current
                Text(
                    if (!p.finished && cur >= 0) " · " + p.steps[cur].text else if (p.title.isNotBlank()) " · " + p.title else "",
                    style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = c.textTertiary, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
                PressableIcon(Icons.Outlined.Close, "清除计划", size = IrisDimens.space32, iconSize = IrisDimens.space16, tint = c.textTertiary, onClick = onClear)
            }
            AnimatedVisibility(open, enter = IrisMotion.expand(), exit = IrisMotion.collapse()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                        .padding(start = IrisDimens.space12, end = IrisDimens.space12, bottom = 10.dp),
                ) {
                    if (p.title.isNotBlank()) Text(p.title, style = IrisType.caption, color = c.textTertiary, modifier = Modifier.padding(bottom = IrisDimens.space6))
                    p.steps.forEachIndexed { i, st -> FlowNode(i + 1, st.text, st.status, first = i == 0, last = i == p.steps.lastIndex) }
                }
            }
        }
    }
}

@Composable
internal fun FlowNode(n: Int, text: String, status: PlanStatus, first: Boolean, last: Boolean) {
    val c = Iris.colors
    val color by animateColorAsState(
        when (status) { PlanStatus.DONE -> c.success; PlanStatus.DOING -> c.accent; PlanStatus.SKIPPED -> c.textTertiary; PlanStatus.TODO -> c.outline },
        IrisMotion.Color.spec(), label = "nodeColor",
    )
    val check by animateFloatAsState(if (status == PlanStatus.DONE) 1f else 0f, IrisMotion.Gentle.float(), label = "nodeCheck")
    val lineDone = c.success.copy(alpha = 0.6f)
    val lineTodo = c.outline
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(22.dp).fillMaxHeight()) {
            // The connecting line: above to the previous node, below to the next.
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val x = size.width / 2
                val cy = 11.dp.toPx()
                if (!first) drawLine(if (status == PlanStatus.TODO) lineTodo else lineDone, Offset(x, 0f), Offset(x, cy - 7.dp.toPx()), 1.5.dp.toPx())
                if (!last) drawLine(if (status == PlanStatus.DONE || status == PlanStatus.SKIPPED) lineDone else lineTodo, Offset(x, cy + 7.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx())
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = IrisDimens.space4).size(14.dp).pulse(status == PlanStatus.DOING, min = 0.45f)) {
                Canvas(Modifier.size(14.dp)) {
                    val r = size.minDimension / 2
                    if (status == PlanStatus.DONE) drawCircle(color, r)
                    else drawCircle(color, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    if (status == PlanStatus.DOING) drawCircle(color, r * 0.42f)
                    if (check > 0f) {
                        // The check draws itself: short stroke, then the long one.
                        val a = Offset(size.width * 0.28f, size.height * 0.52f)
                        val b = Offset(size.width * 0.44f, size.height * 0.68f)
                        val e = Offset(size.width * 0.74f, size.height * 0.36f)
                        val t1 = (check / 0.4f).coerceAtMost(1f)
                        val t2 = ((check - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        val w = 1.6.dp.toPx()
                        drawLine(Color.White, a, a + (b - a) * t1, w, StrokeCap.Round)
                        if (t2 > 0f) drawLine(Color.White, b, b + (e - b) * t2, w, StrokeCap.Round)
                    }
                }
            }
        }
        Spacer(Modifier.width(IrisDimens.space6))
        Text(
            "$n. $text", style = IrisType.caption,
            color = when (status) { PlanStatus.DOING -> c.text; PlanStatus.TODO -> c.textSecondary; else -> c.textTertiary },
            textDecoration = if (status == PlanStatus.SKIPPED) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).padding(top = IrisDimens.space2, bottom = IrisDimens.space8),
        )
    }
}

/** Progress ring that fills as steps finish; turns into a filled success dot at the end. */
@Composable
fun ProgressRing(progress: Float, done: Boolean, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val track = c.outline
    val fill = if (done) c.success else c.accent
    Canvas(modifier) {
        val w = IrisDimens.space2.toPx()
        drawCircle(track, size.minDimension / 2 - w / 2, style = Stroke(w))
        drawArc(fill, -90f, 360f * progress.coerceIn(0f, 1f), false, style = Stroke(w, cap = StrokeCap.Round),
            topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
    }
}

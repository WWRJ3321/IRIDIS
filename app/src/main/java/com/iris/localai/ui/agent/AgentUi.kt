package com.iris.localai.ui.agent

import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.AgentTools
import com.iris.localai.agent.StepStatus
import com.iris.localai.agent.UserDecision
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.components.floatingSurface
import com.iris.localai.ui.components.glassSurface
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/** 1.9.0: Chinese status words of a step card (unit-tested). */
object StepText {
    fun status(s: StepStatus): String = when (s) {
        StepStatus.RUNNING -> "执行中"
        StepStatus.ASKING -> "等待确认"
        StepStatus.OK -> "完成"
        StepStatus.ERROR -> "失败"
        StepStatus.DENIED -> "已拒绝"
    }

    fun label(tool: String): String = AgentTools.byName(tool)?.label ?: tool

    /** 2.1.0: a plan step drawn as a checklist (from [AgentStep.plan], never from its result text). */
    fun isPlan(step: AgentStep): Boolean = (step.tool == "plan" || step.tool == "todo") && step.plan?.steps?.isNotEmpty() == true

    /** 2.1.0: the card header — 计划 · 标题 · 1/5, 搜索「q」…; old saved steps keep label + args. */
    fun header(step: AgentStep): String {
        val p = step.plan
        if (isPlan(step) && p != null) return listOfNotNull("计划", p.title.takeIf { it.isNotBlank() }, "${p.done}/${p.steps.size}").joinToString(" · ")
        if (step.tool == "plan" || step.tool == "todo") return step.title.ifBlank { "计划" }
        if (step.title.isNotBlank()) return step.title
        return label(step.tool) + if (step.args.isNotBlank()) "  " + step.args else ""
    }

    const val PLAN_HINT = "智能体把任务拆成步骤，逐步完成"
}

/** The tool steps of one agent reply, above its answer. */
@Composable
fun AgentSteps(steps: List<AgentStep>, streaming: Boolean, lead: @Composable (AgentStep) -> Unit = {}) {
    if (steps.isEmpty()) return
    // 1.12.0: long runs (search → fetch → write …) no longer push the answer off screen: finished
    // earlier steps fold into one 「已执行 N 步」 row; the newest steps always stay visible.
    val keep = StepFold.visible(steps.size)
    var expanded by rememberSaveable(steps.first().id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
        val folded = if (expanded) 0 else steps.size - keep
        if (steps.size > keep) {
            val hidden = steps.take(steps.size - keep)
            StepFoldRow(StepFold.summary(hidden, expanded), expanded) { expanded = !expanded }
        }
        // 1.13.3: think → write → tool card → think … in the order it happened.
        steps.drop(folded).forEach { st ->
            androidx.compose.runtime.key(st.id) {
                if (st.lead.isNotBlank()) lead(st)
                AgentStepCard(st, streaming)
            }
        }
    }
}

/** 1.12.0: how many step cards stay unfolded, and the fold row's text. Pure (tested). */
object StepFold {
    const val KEEP = 2
    fun visible(total: Int): Int = if (total <= KEEP + 1) total else KEEP
    fun summary(hidden: List<AgentStep>, expanded: Boolean): String {
        if (expanded) return "收起前 ${hidden.size} 步"
        val failed = hidden.count { it.status == StepStatus.ERROR || it.status == StepStatus.DENIED }
        val names = hidden.map { StepText.label(it.tool) }.distinct().take(3).joinToString("、")
        return "已执行 ${hidden.size} 步 · $names" + if (failed > 0) " · $failed 步未成功" else ""
    }
}

@Composable
private fun StepFoldRow(text: String, expanded: Boolean, onClick: () -> Unit) {
    val c = Iris.colors
    val turn by animateFloatAsState(if (expanded) 180f else 0f, com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.Snappy), label = "foldChevron")
    Row(
        Modifier.fillMaxWidth().clip(IrisRadius.mediumShape).clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onClick)
            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Build, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(IrisDimens.space8))
        Text(text, style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.KeyboardArrowDown, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.space16).graphicsLayer { rotationZ = turn })
    }
}

/** Compact collapsible card: icon · tool · args summary · result summary · status; tap to see the output. */
@Composable
fun AgentStepCard(step: AgentStep, streaming: Boolean) {
    val c = Iris.colors
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.Snappy), label = "stepChevron")
    val live = streaming && (step.status == StepStatus.RUNNING || step.status == StepStatus.ASKING)
    val plan = step.plan?.takeIf { StepText.isPlan(step) }
    val planStep = step.tool == "plan" || step.tool == "todo"
    // A plan step never shows the model-facing text ("# Current plan …"); only its checklist.
    val expandable = if (planStep) plan != null else step.detail.isNotBlank()
    val (icon, tint) = when (step.status) {
        StepStatus.OK -> Icons.Outlined.CheckCircle to c.accent
        StepStatus.ERROR -> Icons.Outlined.ErrorOutline to c.danger
        StepStatus.DENIED -> Icons.Outlined.Block to c.textTertiary
        StepStatus.ASKING -> Icons.AutoMirrored.Outlined.HelpOutline to c.accent
        StepStatus.RUNNING -> Icons.Outlined.Build to c.textSecondary
    }
    // 1.14.0: the moment a step finishes it says so — the icon pops with a light tick, a failure
    // shakes with a heavier one (iOS-style "it worked / it didn't").
    val haptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    var prevStatus by androidx.compose.runtime.remember(step.id) { mutableStateOf(step.status) }
    val pop = androidx.compose.runtime.remember(step.id) { androidx.compose.animation.core.Animatable(1f) }
    val shake = androidx.compose.runtime.remember(step.id) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(step.status) {
        val was = prevStatus
        prevStatus = step.status
        if (was != StepStatus.RUNNING && was != StepStatus.ASKING) return@LaunchedEffect
        when (step.status) {
            StepStatus.OK -> {
                haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.STEP_DONE)
                pop.snapTo(0.5f); pop.animateTo(1f, com.iris.localai.ui.motion.IrisMotion.Rebound.float())
            }
            StepStatus.ERROR -> {
                haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.ERROR)
                for (x in listOf(5f, -4f, 3f, 0f)) shake.animateTo(x, com.iris.localai.ui.motion.IrisMotion.Press.float())
            }
            else -> {}
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value * density }
            .clip(IrisRadius.mediumShape)
            .background(c.surfaceVariant)
            .clickable(enabled = expandable, onClickLabel = if (open) "收起" else "展开") { open = !open }
            .padding(horizontal = IrisDimens.space12, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(IrisDimens.space16).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.pulse(live, min = 0.4f))
            Spacer(Modifier.width(IrisDimens.space8))
            Text(StepText.header(step), style = IrisType.labelSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(IrisDimens.space6))
            if (live) TypingDots()
            else Text(StepText.status(step.status), style = IrisType.caption, color = tint, maxLines = 1, softWrap = false)
            if (expandable) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown, null, tint = c.textTertiary,
                    modifier = Modifier.padding(start = IrisDimens.space2).size(IrisDimens.space16).graphicsLayer { rotationZ = turn },
                )
            }
        }
        if (step.result.isNotBlank() && plan == null) {
            Text(
                step.result, style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = IrisDimens.space24, top = IrisDimens.space2),
            )
        }
        // 2.1.7: pictures the step made (generate_image), tap → full-screen viewer.
        if (step.images.isNotEmpty()) {
            Row(Modifier.padding(start = IrisDimens.space24, top = IrisDimens.space6), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                step.images.take(4).forEach { com.iris.localai.ui.markdown.ImageThumbnail(it) }
            }
        }
        if (plan != null) com.iris.localai.ui.components.Fold(open) {
            Column(Modifier.padding(top = IrisDimens.space6).fillMaxWidth()) {
                if (plan.title.isNotBlank()) Text(plan.title, style = IrisType.labelSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(StepText.PLAN_HINT, style = IrisType.caption, color = c.textTertiary, modifier = Modifier.padding(top = IrisDimens.space2, bottom = IrisDimens.space8))
                plan.steps.forEachIndexed { i, ps -> FlowNode(i + 1, ps.text, ps.status, first = i == 0, last = i == plan.steps.lastIndex) }
            }
        } else if (!planStep) com.iris.localai.ui.components.Fold(open) {
            SelectionContainer {
                Text(
                    step.detail.take(4000), style = IrisType.monoSmall, color = c.text,
                    modifier = Modifier
                        .padding(top = IrisDimens.space8)
                        .fillMaxWidth()
                        .clip(IrisRadius.smallShape)
                        .background(c.codeBackground)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
    }
}

/** In-chat confirmation for a tool whose permission is 询问: 允许一次 / 总是允许 / 拒绝. */
@Composable
fun AgentPermissionDialog(toolLabel: String, toolName: String, preview: String, alwaysPrefix: String? = null, onAnswer: (UserDecision) -> Unit) {
    val c = Iris.colors
    AlertDialog(
        // Dismissing (back / outside tap) counts as 拒绝: the agent must never hang on a hidden dialog.
        onDismissRequest = { onAnswer(UserDecision.DENY) },
        modifier = Modifier.glassSurface(IrisRadius.dialogShape, com.iris.localai.ui.theme.IrisElevation.menu),
        shape = IrisRadius.dialogShape,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        title = {
            com.iris.localai.ui.glass.DialogWindowBlur(com.iris.localai.ui.glass.LocalGlassQuality.current)
            Text("允许智能体「$toolLabel」？", style = IrisType.title, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("工具 $toolName 想执行：", style = IrisType.secondary, color = c.textSecondary)
                Spacer(Modifier.size(IrisDimens.space8))
                Box(
                    Modifier.fillMaxWidth().clip(IrisRadius.smallShape).background(c.codeBackground)
                        .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    Text(preview, style = IrisType.monoSmall, color = c.text)
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                IrisButton("允许一次", { onAnswer(UserDecision.ALLOW_ONCE) }, modifier = Modifier.fillMaxWidth())
                IrisButton(alwaysPrefix?.let { "总是允许「${it.take(24)}」开头的命令" } ?: "总是允许", { onAnswer(UserDecision.ALWAYS_ALLOW) }, style = IrisButtonStyle.Tonal, modifier = Modifier.fillMaxWidth())
                IrisButton("拒绝", { onAnswer(UserDecision.DENY) }, style = IrisButtonStyle.Tonal, destructive = true, modifier = Modifier.fillMaxWidth())
            }
        },
        dismissButton = null,
    )
}

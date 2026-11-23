package com.iris.localai.ui.agent

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.agent.web.SearchText
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.ScrollSafeFold
import com.iris.localai.ui.feedback.FeedbackEvent
import com.iris.localai.ui.markdown.ImageThumbnail
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisElevation
import com.iris.localai.ui.theme.IrisSpace
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
import com.iris.localai.agent.core.AgentStep
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.core.StepStatus
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.components.floatingSurface
import com.iris.localai.ui.components.glassSurface
import com.iris.localai.ui.components.largeSurface
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/** Chinese status words of a step card (unit-tested). */
object StepText {
    fun status(s: StepStatus): String = when (s) {
        StepStatus.RUNNING -> I18n.s(R.string.agent_46e386)
        StepStatus.ASKING -> I18n.s(R.string.agent_70f361)
        StepStatus.OK -> I18n.s(R.string.agent_769d88)
        StepStatus.ERROR -> I18n.s(R.string.imagegen_acd5cb)
        StepStatus.DENIED -> I18n.s(R.string.agent_81233d)
    }

    fun label(tool: String): String = AgentTools.byName(tool)?.label ?: tool

    /** a plan step drawn as a checklist (from [AgentStep.plan], never from its result text). */
    fun isPlan(step: AgentStep): Boolean = (step.tool == "plan" || step.tool == "todo") && step.plan?.steps?.isNotEmpty() == true

    /** the card header — 计划 · 标题 · 1/5, 搜索「q」…; old saved steps keep label + args. */
    fun header(step: AgentStep): String {
        val p = step.plan
        if (isPlan(step) && p != null) return listOfNotNull(I18n.s(R.string.agent_0debf5), p.title.takeIf { it.isNotBlank() }, "${p.done}/${p.steps.size}").joinToString(" · ")
        if (step.tool == "plan" || step.tool == "todo") return step.title.ifBlank { I18n.s(R.string.agent_0debf5) }
        if (step.title.isNotBlank()) return step.title
        return label(step.tool) + if (step.args.isNotBlank()) "  " + step.args else ""
    }

    val PLAN_HINT: String get() = I18n.s(R.string.agent_9af3f4)
}

/** The tool steps of one agent reply, above its answer. */
@Composable
fun AgentSteps(steps: List<AgentStep>, streaming: Boolean, lead: @Composable (AgentStep) -> Unit = {}) {
    if (steps.isEmpty()) return
    // long runs (search → fetch → write …) no longer push the answer off screen: finished
    // earlier steps fold into one 「已执行 N 步」 row; the newest steps always stay visible.
    val keep = StepFold.visible(steps.size)
    var expanded by rememberSaveable(steps.first().id) { mutableStateOf(false) }
    // no animateContentSize on the whole column any more — the earlier steps open in a
    // ScrollSafeFold (snaps while the list moves, tall bodies do not grow frame by frame).
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
        val hiddenCount = steps.size - keep
        if (hiddenCount > 0) {
            val hidden = steps.take(hiddenCount)
            StepFoldRow(StepFold.summary(hidden, expanded), expanded) { expanded = !expanded }
            ScrollSafeFold(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                    hidden.forEach { st ->
                        androidx.compose.runtime.key(st.id) {
                            if (st.lead.isNotBlank()) lead(st)
                            AgentStepCard(st, streaming)
                        }
                    }
                }
            }
        }
        // think → write → tool card → think … in the order it happened.
        steps.drop(hiddenCount.coerceAtLeast(0)).forEach { st ->
            androidx.compose.runtime.key(st.id) {
                if (st.lead.isNotBlank()) lead(st)
                AgentStepCard(st, streaming)
            }
        }
    }
}

/** how many step cards stay unfolded, and the fold row's text. Pure (tested). */
object StepFold {
    const val KEEP = 2
    fun visible(total: Int): Int = if (total <= KEEP + 1) total else KEEP
    fun summary(hidden: List<AgentStep>, expanded: Boolean): String {
        if (expanded) return I18n.s(R.string.agent_63855c, hidden.size)
        val failed = hidden.count { it.status == StepStatus.ERROR || it.status == StepStatus.DENIED }
        val names = hidden.map { StepText.label(it.tool) }.distinct().take(3).joinToString(I18n.s(R.string.agent_b50566))
        return I18n.s(R.string.agent_64db29, hidden.size, names) + if (failed > 0) I18n.s(R.string.agent_bb83aa, failed) else ""
    }
}

@Composable
private fun StepFoldRow(text: String, expanded: Boolean, onClick: () -> Unit) {
    val c = Iris.colors
    val turn by animateFloatAsState(if (expanded) 180f else 0f, com.iris.localai.ui.motion.motionSpec(IrisMotion.Snappy), label = "foldChevron")
    Row(
        Modifier.fillMaxWidth().clip(IrisRadius.mediumShape).clickable(onClickLabel = if (expanded) I18n.s(R.string.chat_def9e9) else I18n.s(R.string.components_e2edde), onClick = onClick)
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
    val turn by animateFloatAsState(if (open) 180f else 0f, com.iris.localai.ui.motion.motionSpec(IrisMotion.Snappy), label = "stepChevron")
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
    // the moment a step finishes it says so — the icon pops with a light tick, a failure
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
                haptic.perform(FeedbackEvent.STEP_DONE)
                pop.snapTo(0.5f); pop.animateTo(1f, IrisMotion.Rebound.float())
            }
            StepStatus.ERROR -> {
                haptic.perform(FeedbackEvent.ERROR)
                for (x in listOf(5f, -4f, 3f, 0f)) shake.animateTo(x, IrisMotion.Press.float())
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
            .clickable(enabled = expandable, onClickLabel = if (open) I18n.s(R.string.chat_def9e9) else I18n.s(R.string.components_e2edde)) { open = !open }
            .padding(horizontal = IrisDimens.space12, vertical = IrisSpace.S),
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
        // 生图进行中 → 方框流动色 + 进度条
        val generating = step.tool == "generate_image" && step.status == StepStatus.RUNNING
        if (generating) ImageGenLiveCard(step.result.ifBlank { StepText.status(step.status) })
        if (step.result.isNotBlank() && plan == null && !generating) {
            Text(
                step.result, style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = IrisDimens.space24, top = IrisDimens.space2),
            )
        }
        // pictures the step made (generate_image), tap → full-screen viewer.
        if (step.images.isNotEmpty()) {
            Row(Modifier.padding(start = IrisDimens.space24, top = IrisDimens.space6), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                // 生图单张与等待方框同尺寸（168dp），不再缩成小框
                val big = step.tool == "generate_image" && step.images.size == 1
                step.images.take(4).forEach { if (big) ImageThumbnail(it, 168.dp) else ImageThumbnail(it) }
            }
        }
        if (plan != null) ScrollSafeFold(open) {
            Column(Modifier.padding(top = IrisDimens.space6).fillMaxWidth()) {
                if (plan.title.isNotBlank()) Text(plan.title, style = IrisType.labelSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(StepText.PLAN_HINT, style = IrisType.caption, color = c.textTertiary, modifier = Modifier.padding(top = IrisDimens.space2, bottom = IrisDimens.space8))
                plan.steps.forEachIndexed { i, ps -> FlowNode(i + 1, ps.text, ps.status, first = i == 0, last = i == plan.steps.lastIndex) }
            }
        } else if (!planStep) ScrollSafeFold(open) {
            SelectionContainer {
                Text(
                    SearchText.forPeople(step.tool, step.detail).take(4000), style = IrisType.monoSmall, color = c.text,
                    modifier = Modifier
                        .padding(top = IrisDimens.space8)
                        .fillMaxWidth()
                        .clip(IrisRadius.smallShape)
                        .background(c.codeBackground)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(IrisSpace.S),
                )
            }
        }
    }
}

/** Issue #9: what tapping outside / back does to the tool confirmation. Pure. */
object PermissionAskFold {
    enum class Action { COLLAPSE, DENY }
    /** With a pill to bring it back the dialog folds away; it never answers 拒绝 on the user's behalf. */
    fun onDismiss(collapsible: Boolean): Action = if (collapsible) Action.COLLAPSE else Action.DENY
}

/**
 * In-chat confirmation for a tool whose permission is 询问: 允许一次 / 总是允许 / 拒绝.
 * with [onCollapse], tapping outside or back folds it into [AgentPermissionPill] (the
 * agent keeps waiting) instead of answering 拒绝.
 */
@Composable
fun AgentPermissionDialog(toolLabel: String, toolName: String, preview: String, alwaysPrefix: String? = null, onCollapse: (() -> Unit)? = null, onAnswer: (UserDecision) -> Unit) {
    val c = Iris.colors
    IrisDialog(
        // Without a pill, dismissing counts as 拒绝: the agent must never hang on a hidden dialog.
        onDismiss = {
            when (PermissionAskFold.onDismiss(onCollapse != null)) {
                PermissionAskFold.Action.COLLAPSE -> onCollapse?.invoke()
                PermissionAskFold.Action.DENY -> onAnswer(UserDecision.DENY)
            }
        },
        title = I18n.s(R.string.agent_7bdbd6, toolLabel),
        actions = {
            IrisButton(I18n.s(R.string.agent_31a39e), { onAnswer(UserDecision.ALLOW_ONCE) }, modifier = Modifier.fillMaxWidth())
            IrisButton(alwaysPrefix?.let { I18n.s(R.string.agent_0d2227, it.take(24)) } ?: I18n.s(R.string.agent_60f7e7), { onAnswer(UserDecision.ALWAYS_ALLOW) }, style = IrisButtonStyle.Tonal, modifier = Modifier.fillMaxWidth())
            IrisButton(I18n.s(R.string.agent_7173f8), { onAnswer(UserDecision.DENY) }, style = IrisButtonStyle.Tonal, destructive = true, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(I18n.s(R.string.agent_dc6cc8, toolName), style = IrisType.secondary, color = c.textSecondary)
            Spacer(Modifier.size(IrisDimens.space8))
            Box(
                Modifier.fillMaxWidth().clip(IrisRadius.smallShape).background(c.codeBackground)
                    .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(IrisSpace.S),
            ) {
                SelectionContainer { Text(preview, style = IrisType.monoSmall, color = c.text) }
            }
        }
    }
}

/**
 * the folded tool confirmation — a small pill in the bottom-right corner above the
 * composer. Tapping it opens the dialog again; the agent waits until the user picks 允许 or 拒绝.
 */
@Composable
fun AgentPermissionPill(toolLabel: String, bottom: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val c = Iris.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val offset = with(density) { androidx.compose.ui.unit.IntOffset(-16.dp.roundToPx(), -bottom.roundToPx()) }
    androidx.compose.ui.window.Popup(alignment = Alignment.BottomEnd, offset = offset) {
        Row(
            Modifier
                .largeSurface(IrisRadius.pillShape, IrisElevation.menu)
                .clip(IrisRadius.pillShape)
                .background(c.accent)
                .clickable(onClickLabel = I18n.s(R.string.ask245_reopen), onClick = onClick)
                .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = c.onAccent, modifier = Modifier.size(IrisDimens.space16).pulse(true, min = 0.5f))
            Spacer(Modifier.width(IrisDimens.space6))
            Text(
                I18n.s(R.string.ask245_pill, toolLabel), style = IrisType.labelSmall, color = c.onAccent,
                maxLines = 2, modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}

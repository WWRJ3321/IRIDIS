@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.iris.localai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import com.iris.localai.ui.motion.RollingText
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/** Inset of section headers above a group (HyperOS: a little inside the card edge). */
internal val SectionInset = IrisDimens.groupGutter + 14.dp
/** Inset of footnotes under a group (HyperOS: aligned a touch further in than the header). */
internal val FootnoteInset = IrisDimens.groupGutter + 18.dp

/** Small grey header above a group (HyperOS 14 sp, regular weight). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = IrisType.sectionHeader,
        color = Iris.colors.textTertiary,
        modifier = modifier.padding(
            start = SectionInset,
            end = IrisDimens.pageGutter,
            top = IrisDimens.space24 + IrisDimens.space4,
            bottom = 10.dp,
        ),
    )
}

/** Explanatory paragraph at the top of a page or under a group. */
@Composable
fun PageDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = IrisType.secondary,
        color = Iris.colors.textSecondary,
        modifier = modifier.padding(start = SectionInset, end = IrisDimens.pageGutter, top = IrisDimens.space4, bottom = IrisDimens.space8),
    )
}

/** Footnote under a group. */
@Composable
fun GroupFootnote(text: String, modifier: Modifier = Modifier, monospace: Boolean = false) {
    Text(
        text,
        style = if (monospace) IrisType.monoSmall else IrisType.secondary,
        color = Iris.colors.textTertiary,
        modifier = modifier.padding(start = FootnoteInset, end = IrisDimens.groupGutter + IrisDimens.space16, top = 10.dp),
    )
}

/** Rounded card holding a list of rows (2.2.4: an [IrisCard] — 20 dp corners, soft shadow, no dividers). */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    IrisCard(modifier.padding(horizontal = IrisDimens.groupGutter), content = content)
}

/** Generic list row: optional leading icon, title + subtitle, optional trailing slot. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = Iris.colors.text,
    subtitleColor: Color = Iris.colors.textTertiary,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: (() -> Unit)? = null,
    interaction: MutableInteractionSource? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Iris.colors
    // Enabled ↔ disabled fades instead of snapping (e.g. options that depend on another switch).
    val a by animateFloatAsState(if (enabled) 1f else 0.45f, motionSpec(IrisMotion.FadeIn), label = "rowEnabled")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = IrisDimens.rowMinHeight)
            .then(
                if (onClick != null) Modifier.clickable(
                    enabled = enabled, role = role, onClick = onClick,
                    interactionSource = interaction, indication = LocalIndication.current,
                ) else Modifier,
            )
            .padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.textSecondary.copy(alpha = a), modifier = Modifier.size(IrisDimens.iconLarge))
            Spacer(Modifier.width(IrisDimens.space16))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.row, color = titleColor.copy(alpha = titleColor.alpha * a))
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle, style = IrisType.rowSummary, color = subtitleColor.copy(alpha = subtitleColor.alpha * a),
                    maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(IrisDimens.space12))
            trailing()
        }
    }
}

/** Read-only key/value row. */
@Composable
fun ValueRow(title: String, value: String, modifier: Modifier = Modifier) {
    SettingsRow(title, modifier, trailing = { ValueText(value) })
}

@Composable
fun ValueText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = IrisType.secondary, color = Iris.colors.textSecondary, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    // Pressing anywhere on the row stretches the knob, like touching an iOS switch.
    val interaction = remember { MutableInteractionSource() }
    val feedback = com.iris.localai.ui.feedback.rememberIrisFeedback()
    SettingsRow(
        title, subtitle = subtitle, icon = icon, enabled = enabled, role = Role.Switch,
        onClick = { feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.TOGGLE); onChange(!checked) }, interaction = interaction,
    ) {
        IrisSwitch(checked = checked, enabled = enabled, interaction = interaction)
    }
}

/** Row whose value is chosen from a few labelled options, shown as a segmented control under the title. */
@Composable
fun SegmentedRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space16)) {
        SettingsRow(title, subtitle = subtitle, icon = icon, modifier = Modifier.heightIn(min = 44.dp))
        SegmentedControl(
            options, selectedIndex, onSelect,
            Modifier
                .fillMaxWidth()
                .padding(start = if (icon != null) IrisDimens.rowPadding + IrisDimens.iconLarge + IrisDimens.space16 else IrisDimens.rowPadding, end = IrisDimens.rowPadding),
        )
    }
}

/** Single-choice row with an animated check mark. */
@Composable
fun CheckRow(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    SettingsRow(title, subtitle = subtitle, role = Role.RadioButton, onClick = onClick) {
        Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = selected,
                enter = if (LocalReduceMotion.current) EnterTransition.None else IrisMotion.popIn(0.4f),
                exit = if (LocalReduceMotion.current) ExitTransition.None else IrisMotion.popOut(0.4f),
            ) {
                Icon(Icons.Outlined.Check, "已选择", tint = Iris.colors.accent, modifier = Modifier.size(IrisDimens.iconMedium))
            }
        }
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    subtitle: String? = null,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, top = IrisDimens.rowVerticalPadding, bottom = IrisDimens.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = IrisType.row, color = c.text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(IrisDimens.space12))
            RollingText(display, style = IrisType.secondary.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = IrisType.rowSummary, color = c.textTertiary)
        }
        val interaction = remember { MutableInteractionSource() }
        val colors = SliderDefaults.colors(
            thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceVariant,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        )
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
            colors = colors,
            interactionSource = interaction,
            thumb = { SliderKnob(interaction) },
        )
    }
}

/**
 * Separator between rows of a group. 1.8.0 (HyperOS): grouped cards have no hairlines, rows are
 * separated by their own padding, so this draws nothing. Kept so call sites stay unchanged; use
 * [HairlineDivider] where a visible line is really wanted.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Divider(modifier: Modifier = Modifier, start: Dp = IrisDimens.rowPadding) = Unit

/**
 * Animated show / hide for a part of a page or group (expand from the top + fade, IrisMotion
 * tokens; instant when the system removes animations).
 */
@Composable
fun Fold(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    androidx.compose.animation.AnimatedVisibility(
        visible, modifier,
        enter = if (reduce) EnterTransition.None else IrisMotion.expand(),
        exit = if (reduce) ExitTransition.None else IrisMotion.collapse(),
    ) { content() }
}

/**
 * 「高级」: the folded, less-used part of a settings group. Closed by default; the open state
 * survives rotation ([key] must be unique per page). The chevron turns with the Snappy spring.
 */
@Composable
fun AdvancedFold(key: String, title: String = "高级", content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(key) { androidx.compose.runtime.mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, motionSpec(IrisMotion.Snappy), label = "advChevron")
    SettingsRow(
        title, onClick = { open = !open }, role = Role.Button,
        trailing = {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.KeyboardArrowDown, if (open) "收起" else "展开",
                tint = Iris.colors.textTertiary,
                modifier = Modifier.size(IrisDimens.iconMedium).graphicsLayer { rotationZ = turn },
            )
        },
    )
    Fold(open) { Column { content() } }
}

/**
 * 2.0.x: a whole run of sections behind one 「高级」-style row (its own small group). Closed by
 * default; [key] keeps the open state across rotation.
 */
@Composable
fun SectionFold(key: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(key) { androidx.compose.runtime.mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, motionSpec(IrisMotion.Snappy), label = "sectionChevron")
    Spacer(Modifier.height(IrisDimens.space24))
    SettingsGroup {
        SettingsRow(
            title, onClick = { open = !open }, role = Role.Button,
            trailing = {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.KeyboardArrowDown, if (open) "收起" else "展开",
                    tint = Iris.colors.textTertiary,
                    modifier = Modifier.size(IrisDimens.iconMedium).graphicsLayer { rotationZ = turn },
                )
            },
        )
    }
    Fold(open) { Column { content() } }
}

/** Visible hairline (the pre-1.8.0 [Divider]). */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier, start: Dp = IrisDimens.rowPadding) {
    Box(modifier.padding(start = start).fillMaxWidth().height(IrisDimens.hairline).background(Iris.colors.outline))
}

@Composable
fun Chevron() = Icon(
    Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
    tint = Iris.colors.textTertiary, modifier = Modifier.size(IrisDimens.iconMedium),
)

/** Full-width navigation row used in the drawer: icon + label, rounded highlight. */
@Composable
fun NavRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Iris.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = IrisDimens.space8, vertical = 1.dp)
            .heightIn(min = IrisDimens.touchTarget)
            .clip(IrisRadius.mediumShape)
            .clickable(onClick = onClick)
            .padding(horizontal = IrisDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space12),
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge))
        Text(
            text, style = if (emphasized) IrisType.rowStrong else IrisType.body, color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Slider knob that swells under the finger with a spring and rebounds on release. */
@Composable
fun SliderKnob(interaction: MutableInteractionSource) {
    val c = Iris.colors
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val reduce = LocalReduceMotion.current
    val s = remember { Animatable(1f) }
    val active = pressed || dragged
    LaunchedEffect(active, reduce) {
        if (reduce) s.snapTo(1f) else s.animateTo(if (active) 1.3f else 1f, if (active) IrisMotion.Press.float() else IrisMotion.Rebound.float())
    }
    Box(
        Modifier
            .size(IrisDimens.space24)
            .graphicsLayer {
                scaleX = s.value; scaleY = s.value
                shadowElevation = 3.dp.toPx(); shape = CircleShape; clip = true
            }
            // 1.8.0: plain white knob with a soft shadow (HyperOS / iOS), no accent ring.
            .drawBehind {
                drawCircle(Color.White)
                if (!c.isDark) drawCircle(Color.Black.copy(alpha = 0.06f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()))
            },
    )
}

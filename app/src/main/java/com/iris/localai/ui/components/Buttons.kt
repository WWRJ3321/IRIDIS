package com.iris.localai.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.MotionMath
import com.iris.localai.ui.motion.SpringValue
import com.iris.localai.ui.motion.motion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.motion.pressScale
import com.iris.localai.ui.motion.rememberPressMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Press feedback shared by every tappable surface: an interruptible spring scale with an elastic
 * rebound. Runs entirely in the graphics layer (no recomposition per frame, no relayout).
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = IrisMotion.PRESS_SCALE_BUTTON): Modifier {
    if (LocalReduceMotion.current) return this
    val press = rememberPressMotion(interaction, pressedScale)
    return this.pressScale(press)
}

/** Round icon button. [size] is the visual size; touch area is extended to 48 dp by Compose. */
/** 2.2.11: default [PressableIcon] size; [IrisTopBar] lowers it so action icons match the 44 dp back circle. */
val LocalPressableIconSize = androidx.compose.runtime.staticCompositionLocalOf { IrisDimens.touchTarget }

@Composable
fun PressableIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = LocalPressableIconSize.current,
    iconSize: Dp = IrisDimens.iconLarge,
    tint: Color = Iris.colors.text,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    haptic: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val reduce = LocalReduceMotion.current
    // Background / tint / enabled changes glide instead of snapping.
    val bg by animateColorAsState(background, motionSpec(IrisMotion.Color), label = "iconBg")
    val fg by animateColorAsState(if (enabled) tint else tint.copy(alpha = 0.35f), motionSpec(IrisMotion.Color), label = "iconFg")
    Box(
        modifier
            .size(size)
            .pressScale(interaction, IrisMotion.PRESS_SCALE_ICON)
            .clip(CircleShape)
            .drawBehind { if (bg.alpha > 0f) drawRect(bg) }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = {
                    if (haptic) haptics.perform(com.iris.localai.ui.feedback.FeedbackEvent.TOGGLE)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Icon swaps (copy → check, speak → stop, mic → mic off) morph with a springy pop.
        AnimatedContent(
            targetState = icon,
            transitionSpec = { motion(reduce) { IrisMotion.pop() } },
            label = "icon",
        ) { ic -> Icon(ic, contentDescription, tint = fg, modifier = Modifier.size(iconSize)) }
    }
}

enum class IrisButtonStyle { Primary, Tonal, Outline, Text }

/** The one button used across the app. [compact] = 36 dp for use inside rows; otherwise 48 dp. */
@Composable
fun IrisButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: IrisButtonStyle = IrisButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    destructive: Boolean = false,
) {
    val c = Iris.colors
    val interaction = remember { MutableInteractionSource() }
    val reduce = LocalReduceMotion.current
    val (bgTarget, fgTarget) = when (style) {
        IrisButtonStyle.Primary -> c.accent to c.onAccent
        IrisButtonStyle.Tonal -> c.surfaceVariant to (if (destructive) c.danger else c.text)
        IrisButtonStyle.Outline -> Color.Transparent to (if (destructive) c.danger else c.text)
        IrisButtonStyle.Text -> Color.Transparent to (if (destructive) c.danger else c.text)
    }
    val alpha = if (enabled) 1f else 0.38f
    val bg by animateColorAsState(if (bgTarget == Color.Transparent) bgTarget else bgTarget.copy(alpha = bgTarget.alpha * alpha), motionSpec(IrisMotion.Color), label = "btnBg")
    val fg by animateColorAsState(fgTarget.copy(alpha = alpha), motionSpec(IrisMotion.Color), label = "btnFg")
    val height = if (compact) 36.dp else IrisDimens.touchTarget
    val hPad = when {
        style == IrisButtonStyle.Text -> IrisDimens.space12
        compact -> 14.dp
        else -> IrisDimens.space20
    }
    Row(
        modifier
            .heightIn(min = height)
            .pressScale(interaction, IrisMotion.PRESS_SCALE_BUTTON)
            .clip(IrisRadius.pillShape)
            .drawBehind { if (bg.alpha > 0f) drawRect(bg) }
            .then(
                if (style == IrisButtonStyle.Outline) Modifier.border(1.dp, c.outline.copy(alpha = 0.9f), IrisRadius.pillShape)
                else Modifier,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        AnimatedContent(text, transitionSpec = { motion(reduce) { IrisMotion.crossfade() } }, label = "btnText") { t ->
            Text(t, style = IrisType.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Capsule toggle ("深度思考"). */
@Composable
fun ToggleChip(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Iris.colors
    val shape = IrisRadius.pillShape
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) c.accentContainer else Color.Transparent, motionSpec(IrisMotion.Color), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onAccentContainer else c.textSecondary, motionSpec(IrisMotion.Color), label = "chipFg")
    val border by animateColorAsState(if (selected) c.onAccentContainer.copy(alpha = 0.22f) else c.outline, motionSpec(IrisMotion.Color), label = "chipBorder")
    val a = if (enabled) 1f else 0.4f
    // Selecting gives the icon a little springy hop.
    val hop = remember { androidx.compose.animation.core.Animatable(1f) }
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) { first = false; return@LaunchedEffect }
        if (!reduce && selected) { hop.snapTo(0.7f); hop.animateTo(1f, IrisMotion.Bouncy.float()) }
    }
    Row(
        modifier
            .height(34.dp)
            .pressScale(interaction, 0.95f)
            .clip(shape)
            .drawBehind { if (bg.alpha > 0f) drawRect(bg) }
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Switch, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = IrisDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
    ) {
        Icon(icon, null, tint = fg.copy(alpha = a), modifier = Modifier.size(IrisDimens.iconSmall).graphicsLayer { scaleX = hop.value; scaleY = hop.value })
        AnimatedContent(text, transitionSpec = { motion(reduce) { IrisMotion.crossfade() } }, label = "chipText") { t ->
            Text(t, style = IrisType.labelSmall, color = fg.copy(alpha = a))
        }
    }
}

/**
 * Segmented control. 1.8.0: HyperOS look (rounded grey track, the selected segment a lighter pill
 * with blue medium text) with the iOS physics kept. The thumb springs between segments, can be grabbed and dragged
 * (it follows the finger, then settles on the nearest segment with the release velocity), and
 * squeezes slightly while pressed. Thumb motion is graphicsLayer-only.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Iris.colors
    val n = options.size.coerceAtLeast(1)
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val pos = remember { SpringValue(selectedIndex.coerceIn(0, n - 1).toFloat(), scope) }
    val haptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val squeeze = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(selectedIndex, reduce) {
        val t = selectedIndex.coerceIn(0, n - 1).toFloat()
        if (reduce) pos.snapTo(t) else if (pos.target != t) pos.animateTo(t, IrisMotion.Snappy.float())
    }
    Box(
        modifier
            .height(44.dp)
            .clip(IrisRadius.pillShape)
            .background(c.surfaceVariant)
            .padding(IrisDimens.space4)
            .pointerInput(n) {
                var lastIdx = -1
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        lastIdx = currentSelected
                        scope.launch { squeeze.animateTo(0.95f, IrisMotion.Press.float()) }
                    },
                    onDragEnd = {
                        val seg = size.width / n.toFloat()
                        val v = tracker.calculateVelocity().x
                        val target = MotionMath.settleAnchor(pos.value, v / seg, FloatArray(n) { it.toFloat() })
                        pos.animateTo(target, IrisMotion.Snappy.float(), initialVelocity = 0f)
                        scope.launch { squeeze.animateTo(1f, IrisMotion.Rebound.float()) }
                        val i = target.toInt()
                        if (i != currentSelected) currentSelect(i)
                    },
                    onDragCancel = {
                        pos.animateTo(currentSelected.toFloat(), IrisMotion.Snappy.float())
                        scope.launch { squeeze.animateTo(1f, IrisMotion.Rebound.float()) }
                    },
                ) { ch, dx ->
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    val seg = size.width / n.toFloat()
                    pos.dragBy(dx / seg, 0f, (n - 1).toFloat(), dimension = 1f)
                    val i = kotlin.math.round(pos.value).toInt().coerceIn(0, n - 1)
                    if (i != lastIdx) { lastIdx = i; haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.TICK) }
                    ch.consume()
                }
            },
    ) {
        if (selectedIndex in 0 until n) {
            Box(
                Modifier
                    .fillMaxWidth(1f / n)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = pos.value * size.width
                        scaleX = squeeze.value; scaleY = squeeze.value
                        shadowElevation = if (c.isDark) 0f else 1.5.dp.toPx()
                        shape = IrisRadius.pillShape
                        clip = true
                    }
                    .background(c.segmentThumb),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val color by animateColorAsState(if (selected) c.accent else c.textSecondary, motionSpec(IrisMotion.Color), label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(IrisRadius.pillShape)
                        .selectable(selected = selected, role = Role.RadioButton, interactionSource = null, indication = null, onClick = { if (!selected) onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = IrisType.segment,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = IrisDimens.space4),
                    )
                }
            }
        }
    }
}

/**
 * Switch. 1.8.0: HyperOS proportions and colours (blue pill track, white knob; grey track and,
 * in dark mode, a grey knob when off) with the iOS motion kept: the knob springs across with a slight overshoot and stretches while the
 * finger is down; the track colour glides. Drawn in one drawBehind (no layout per frame).
 */
@Composable
fun IrisSwitch(checked: Boolean, enabled: Boolean = true, interaction: MutableInteractionSource? = null, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val pos = remember { androidx.compose.animation.core.Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, reduce) {
        if (reduce) pos.snapTo(if (checked) 1f else 0f) else pos.animateTo(if (checked) 1f else 0f, IrisMotion.Bouncy.float())
    }
    val pressed by (interaction ?: remember { MutableInteractionSource() }).collectIsPressedAsState()
    val stretch = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pressed, reduce) {
        if (reduce) stretch.snapTo(0f)
        else stretch.animateTo(if (pressed) 1f else 0f, if (pressed) IrisMotion.Press.float() else IrisMotion.Rebound.float())
    }
    val onTrack = c.accent
    val offTrack = c.switchOffTrack
    val onKnob = Color.White
    val offKnob = c.switchOffThumb
    val a = if (enabled) 1f else 0.4f
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .semantics { contentDescription = if (checked) com.iris.localai.I18n.s(com.iris.localai.R.string.components_849320) else com.iris.localai.I18n.s(com.iris.localai.R.string.components_d58a55) }
            .drawBehind {
                val p = pos.value
                val t = p.coerceIn(0f, 1f)
                val track = androidx.compose.ui.graphics.lerp(offTrack, onTrack, t).copy(alpha = a)
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                val inset = 3.dp.toPx()
                val knob = androidx.compose.ui.graphics.lerp(offKnob, onKnob, t)
                val d = size.height - inset * 2
                val w = d + 7.dp.toPx() * stretch.value
                val travel = size.width - inset * 2 - w
                val x = inset + travel * p
                if (!c.isDark) drawRoundRect(Color.Black.copy(alpha = 0.10f * a), topLeft = Offset(x, inset + 1.dp.toPx()), size = Size(w, d), cornerRadius = CornerRadius(d / 2))
                drawRoundRect(knob.copy(alpha = if (enabled) 1f else 0.85f), topLeft = Offset(x, inset), size = Size(w, d), cornerRadius = CornerRadius(d / 2))
            },
    )
}

/** Radio dot whose inner disc pops in with a spring. */
@Composable
fun IrisRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val p = remember { androidx.compose.animation.core.Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, reduce) {
        if (reduce) p.snapTo(if (selected) 1f else 0f) else p.animateTo(if (selected) 1f else 0f, if (selected) IrisMotion.Bouncy.float() else IrisMotion.Snappy.float())
    }
    val ring by animateColorAsState(if (selected) c.accent else c.textTertiary, motionSpec(IrisMotion.Color), label = "radioRing")
    Box(
        modifier.size(48.dp).drawBehind {
            val r = 10.dp.toPx()
            drawCircle(ring, r, style = androidx.compose.ui.graphics.drawscope.Stroke(IrisDimens.space2.toPx()))
            val inner = 5.dp.toPx() * p.value
            if (inner > 0.1f) drawCircle(c.accent, inner)
        },
    )
}

/** Opaque blend of this colour toward [other] by [t] (0..1). */
internal fun Color.mix(other: Color, t: Float): Color =
    Color(
        red = red + (other.red - red) * t,
        green = green + (other.green - green) * t,
        blue = blue + (other.blue - blue) * t,
        alpha = 1f,
    )

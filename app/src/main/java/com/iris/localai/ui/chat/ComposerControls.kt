package com.iris.localai.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.iris.localai.engine.ResourceTier
import com.iris.localai.ui.components.pressScale
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/**
 * 1.10.1: the composer's speed choice. Display names only — each one IS an existing resource tier
 * (the `resourceTier` setting, the same one the model sheet's 资源档位 and 设置 › 性能与资源 edit),
 * so all three stay in sync. [ResourceTier]'s own label / short / hint are unchanged (logs,
 * telemetry and the 1.7.6 tests use them).
 */
enum class SpeedLevel(val label: String, val tier: ResourceTier) {
    LOW("低速", ResourceTier.LOW),       // 省电
    FAST("高速", ResourceTier.MEDIUM),   // 中
    MAX("极速", ResourceTier.HIGH);      // 高

    companion object {
        /** Tier ordinal (as stored in settings) → speed. Out-of-range ordinals follow [ResourceTier.of]. */
        fun ofTier(ordinal: Int): SpeedLevel = ofTier(ResourceTier.of(ordinal))
        fun ofTier(tier: ResourceTier): SpeedLevel = entries.first { it.tier == tier }
        /** Labels in tier order (index i = tier ordinal i), for TierSlider / SegmentedRow. */
        val labels: List<String> get() = ResourceTier.entries.map { ofTier(it).label }
    }

    /** The tier ordinal to store in settings. */
    val tierOrdinal: Int get() = tier.ordinal
}

/**
 * 1.10.1: pill toggles under the text field (HyperOS / iOS). On = HyperOS blue with white text,
 * off = grey; 36 dp high. Pure values so they can be unit-tested.
 */
object ComposerPillStyle {
    const val HEIGHT_DP = 36f
    const val MIN_WIDTH_DP = 52f
    const val H_PADDING_DP = 14f
    const val DISABLED_ALPHA = 0.4f
    /** 1.10.2: the small 「智能体」 pill in the card's top-left corner (same colours, smaller). */
    const val COMPACT_HEIGHT_DP = 28f
    const val COMPACT_MIN_WIDTH_DP = 0f
    const val COMPACT_H_PADDING_DP = 10f
    val ON_LIGHT = Color(0xFF0D84FF)
    val ON_DARK = Color(0xFF3482FF)
    val ON_TEXT = Color(0xFFFFFFFF)
    val OFF_LIGHT = Color(0xFFEBEBEF)
    val OFF_DARK = Color(0xFF2C2C2E)
    val OFF_TEXT_LIGHT = Color(0xFF3C3C43)
    val OFF_TEXT_DARK = Color(0xFFE5E5EA)

    fun background(on: Boolean, dark: Boolean): Color = when {
        on -> if (dark) ON_DARK else ON_LIGHT
        else -> if (dark) OFF_DARK else OFF_LIGHT
    }

    fun content(on: Boolean, dark: Boolean): Color = if (on) ON_TEXT else if (dark) OFF_TEXT_DARK else OFF_TEXT_LIGHT
}

/**
 * One composer pill. [selected] = on (blue). [enabled] = false greys it out but it stays tappable
 * when [onDisabledClick] is given, so a tap can explain why (e.g. the model has no thinking).
 */
@Composable
internal fun ComposerPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    toggle: Boolean = true,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onDisabledClick: (() -> Unit)? = null,
    /** 1.10.2: the small top-left variant (28 dp, 13 sp). */
    compact: Boolean = false,
) {
    val c = Iris.colors
    val dark = c.isDark
    val on = selected && enabled
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(ComposerPillStyle.background(on, dark), motionSpec(IrisMotion.Color), label = "pillBg")
    val fg by animateColorAsState(ComposerPillStyle.content(on, dark), motionSpec(IrisMotion.Color), label = "pillFg")
    val a = if (enabled) 1f else ComposerPillStyle.DISABLED_ALPHA
    val clickable = enabled || onDisabledClick != null
    Row(
        modifier
            .height((if (compact) ComposerPillStyle.COMPACT_HEIGHT_DP else ComposerPillStyle.HEIGHT_DP).dp)
            .widthIn(min = (if (compact) ComposerPillStyle.COMPACT_MIN_WIDTH_DP else ComposerPillStyle.MIN_WIDTH_DP).dp)
            .pressScale(interaction, 0.95f)
            .clip(IrisRadius.pillShape)
            .drawBehind { drawRect(bg.copy(alpha = bg.alpha * a)) }
            .semantics {
                if (toggle) {
                    role = Role.Switch
                    stateDescription = if (on) "已开启" else if (enabled) "已关闭" else "不可用"
                }
            }
            .clickable(
                enabled = clickable,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = { if (enabled) onClick() else onDisabledClick?.invoke() },
            )
            .padding(horizontal = (if (compact) ComposerPillStyle.COMPACT_H_PADDING_DP else ComposerPillStyle.H_PADDING_DP).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg.copy(alpha = a), modifier = Modifier.size(if (compact) 14.dp else 16.dp))
        Text(text, style = if (compact) IrisType.labelSmall else IrisType.labelSmall.copy(fontSize = IrisType.label.fontSize), color = fg.copy(alpha = a), maxLines = 1)
        if (trailingIcon != null) Icon(trailingIcon, null, tint = fg.copy(alpha = a * 0.7f), modifier = Modifier.size(16.dp))
    }
}

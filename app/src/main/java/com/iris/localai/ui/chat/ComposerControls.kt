package com.iris.localai.ui.chat

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.theme.IrisDarkColors
import com.iris.localai.ui.theme.IrisLightColors
import com.iris.localai.ui.theme.IrisSpace
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.ui.components.pressScale
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/**
 * the composer's speed choice. Display names only — each one IS an existing resource tier
 * (the `resourceTier` setting, the same one the model sheet's 资源档位 and 设置 › 性能与资源 edit),
 * so all three stay in sync. [ResourceTier]'s own label / short / hint are unchanged (logs,
 * telemetry and the 1.7.6 tests use them).
 */
enum class SpeedLevel(val label: String, val tier: ResourceTier) {
    LOW(I18n.s(R.string.chat_af7e96), ResourceTier.LOW),       // 省电
    FAST(I18n.s(R.string.chat_8f1208), ResourceTier.MEDIUM),   // 中
    MAX(I18n.s(R.string.chat_64fe2c), ResourceTier.HIGH);      // 高

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
 * pill toggles under the text field (HyperOS / iOS). On = HyperOS blue with white text,
 * off = grey; 36 dp high. Pure values so they can be unit-tested.
 */
object ComposerPillStyle {
    const val HEIGHT_DP = 36f
    const val MIN_WIDTH_DP = 52f
    const val H_PADDING_DP = 14f
    const val DISABLED_ALPHA = 0.4f
    /** the small 「智能体」 pill in the card's top-left corner (same colours, smaller). */
    const val COMPACT_HEIGHT_DP = 28f
    const val COMPACT_MIN_WIDTH_DP = 0f
    const val COMPACT_H_PADDING_DP = 10f
    // 取自主题令牌（不再写死颜色）；界面上由调用方传入当前主题的 accent / surfaceVariant / text。
    val ON_LIGHT = IrisLightColors.accent
    val ON_DARK = IrisDarkColors.accent
    val ON_TEXT = IrisLightColors.onAccent
    val OFF_LIGHT = IrisLightColors.surfaceVariant
    val OFF_DARK = IrisDarkColors.surfaceVariant
    val OFF_TEXT_LIGHT = IrisLightColors.text
    val OFF_TEXT_DARK = IrisDarkColors.text

    /** on = the theme's brand accent (was hard-coded blue while the send button followed the theme). */
    fun background(on: Boolean, dark: Boolean, accent: Color? = null, off: Color? = null): Color = when {
        on -> accent ?: if (dark) ON_DARK else ON_LIGHT
        else -> off ?: if (dark) OFF_DARK else OFF_LIGHT
    }

    fun content(on: Boolean, dark: Boolean, onAccent: Color? = null, offText: Color? = null): Color =
        if (on) (onAccent ?: ON_TEXT) else offText ?: if (dark) OFF_TEXT_DARK else OFF_TEXT_LIGHT
}

/**
 * One composer pill. [selected] = on (blue). [enabled] = false greys it out but it stays tappable
 * when [onDisabledClick] is given, so a tap can explain why (e.g. the model has no thinking).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    /** the small top-left variant (28 dp, 13 sp). */
    compact: Boolean = false,
    /** taps handled by the caller's own gesture (思考 long-press bar). */
    customGesture: Boolean = false,
    /** long-press (思考 opens its 低 中 高 bar); taps still go through the normal click. */
    onLongClick: (() -> Unit)? = null,
) {
    val c = Iris.colors
    val dark = c.isDark
    val on = selected && enabled
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(ComposerPillStyle.background(on, dark, Iris.colors.accent, Iris.colors.surfaceVariant), motionSpec(IrisMotion.Color), label = "pillBg")
    val fg by animateColorAsState(ComposerPillStyle.content(on, dark, Iris.colors.onAccent, Iris.colors.text), motionSpec(IrisMotion.Color), label = "pillFg")
    val a = if (enabled) 1f else ComposerPillStyle.DISABLED_ALPHA
    val clickable = (enabled || onDisabledClick != null) && !customGesture
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
                    stateDescription = if (on) I18n.s(R.string.chat_9db7a8) else if (enabled) I18n.s(R.string.chat_9c5850) else I18n.s(R.string.chat_d1e4a7)
                }
            }
            .then(
                if (onLongClick != null) Modifier.combinedClickable(
                    enabled = clickable,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onLongClick = { if (enabled) onLongClick() else onDisabledClick?.invoke() },
                    onClick = { if (enabled) onClick() else onDisabledClick?.invoke() },
                ) else Modifier.clickable(
                    enabled = clickable,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = { if (enabled) onClick() else onDisabledClick?.invoke() },
                )
            )
            .padding(horizontal = (if (compact) ComposerPillStyle.COMPACT_H_PADDING_DP else ComposerPillStyle.H_PADDING_DP).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) IrisSpace.XS else IrisSpace.XS, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg.copy(alpha = a), modifier = Modifier.size(if (compact) 14.dp else 16.dp))
        Text(text, style = if (compact) IrisType.labelSmall else IrisType.labelSmall.copy(fontSize = IrisType.label.fontSize), color = fg.copy(alpha = a), maxLines = 1)
        if (trailingIcon != null) Icon(trailingIcon, null, tint = fg.copy(alpha = a * 0.7f), modifier = Modifier.size(16.dp))
    }
}

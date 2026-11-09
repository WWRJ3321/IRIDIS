package com.iris.localai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisElevation
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.softShadow

/*
 * 2.2.4 design kit (Kimi-like): floating round buttons and pills with one soft shadow, 20 dp
 * cards, a top bar without a flat full-width strip. Screens compose these instead of styling
 * surfaces themselves.
 */

/** A round floating glass button holding one icon; presses scale to 0.96. */
@Composable
fun FloatingIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = IrisDimens.floatingButton,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Iris.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction, GlassTokens.PRESS_SCALE)
            .glassSurface(CircleShape)
            .pressTint(interaction, CircleShape)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription, tint = if (enabled) c.text else c.textTertiary,
            modifier = Modifier.size(IrisDimens.iconMedium),
        )
    }
}

/** A fully rounded floating glass pill; clickable (press scale 0.96) when [onClick] is set. */
@Composable
fun IrisPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    height: androidx.compose.ui.unit.Dp = IrisDimens.floatingButton,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(height)
            .then(if (onClick != null) Modifier.pressScale(interaction, GlassTokens.PRESS_SCALE) else Modifier)
            .glassSurface(IrisRadius.full)
            .then(if (onClick != null) Modifier.pressTint(interaction, IrisRadius.full).clickable(interaction, null, onClickLabel = onClickLabel, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 2.2.8: press feedback for rounded glass controls without a ripple (the default indication drew
 * a square highlight on the pill): a slight content-colour tint drawn in the control's own shape.
 */
@Composable
fun Modifier.pressTint(interaction: androidx.compose.foundation.interaction.InteractionSource, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val tintColor = Iris.colors.text
    val pressed by interaction.collectIsPressedAsState()
    val a by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.07f else 0f, androidx.compose.animation.core.tween(120), label = "pressTint")
    return this.drawWithContent {
        drawContent()
        if (a > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), tintColor.copy(alpha = a))
    }
}

/** A rounded card resting on the page: 20 dp continuous corners, soft shadow in light theme. */
@Composable
fun IrisCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Iris.colors
    // 2.5.0 Beta2 柔光: solid neutral card — opaque, no shadow, 0.5 dp low-contrast hairline.
    val soft = softStyle()
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(if (c.isDark || soft) IrisElevation.none else IrisElevation.card, IrisRadius.largeShape, c.isDark)
            .clip(IrisRadius.largeShape)
            .background(c.surfaceElevated)
            .then(if (soft) Modifier.border(MatteGlass.HAIRLINE_DP.dp, MatteGlass.hairline(c.isDark), IrisRadius.largeShape) else Modifier),
        content = content,
    )
}

/**
 * Secondary-page top bar: a floating round glass back button, a centred title ([titleModifier]
 * lets the scaffold fade it in as the large title scrolls away) and the page's actions grouped in
 * one glass pill (drawn only when there are actions; a lone icon is a 44 dp circle). The title
 * sits in a matching glass pill (2.2.11). A light background fade sits behind them (2.2.13).
 */
@Composable
fun IrisTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    stripAlpha: () -> Float = { 0f },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Iris.colors
    // 2.2.9: no background band behind the bar (no strip / scrim): only the floating glass back
    // button, the title and the glass action pill sit over the content. [stripAlpha] is ignored.
    // 2.2.11: the centred title sits in a frosted glass pill (same height / glass as the back circle)
    // so content scrolling underneath never collides with the title text. Its side inset follows
    // the wider of the back circle and the action pill, keeping the three groups symmetric.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var actionsWidth by remember { mutableStateOf(0.dp) }
    val sideInset = maxOf(IrisDimens.floatingButton, actionsWidth) + IrisDimens.space8
    Box(modifier.fillMaxWidth()) {
        // 2.2.13: light top fade (page background → transparent, status bar + ~56 dp) so content that
        // scrolls under the back button and title pill reads as fading out instead of colliding with them.
        // 2.2.14 纯色背景: a plain bar in the page colour instead of the fade.
        if (com.iris.localai.ui.theme.LocalSolidBackground.current) Box(Modifier.matchParentSize().background(c.background))
        else Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to c.background.copy(alpha = 0.92f), 0.55f to c.background.copy(alpha = 0.6f), 1f to c.background.copy(alpha = 0f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(IrisDimens.topBarHeight + IrisDimens.space8).padding(horizontal = IrisDimens.space16),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(horizontal = sideInset)
                    .then(titleModifier)
                    .height(IrisDimens.floatingButton)
                    .glassSurface(IrisRadius.full)
                    .padding(horizontal = IrisDimens.space16),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title, style = IrisType.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.AutoMirrored.Outlined.ArrowBack, com.iris.localai.I18n.s(com.iris.localai.R.string.components_5f4112), onClick = onBack)
                Spacer(Modifier.weight(1f))
                var hasActions by remember { mutableStateOf(false) }
                CompositionLocalProvider(LocalPressableIconSize provides IrisDimens.floatingButton) {
                    Row(
                        Modifier
                            .then(
                                if (hasActions) Modifier.height(IrisDimens.floatingButton).widthIn(min = IrisDimens.floatingButton)
                                    .glassSurface(IrisRadius.full) else Modifier,
                            )
                            .onSizeChanged {
                                hasActions = it.width > 0 && it.height > 0
                                actionsWidth = with(density) { it.width.toDp() }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        content = actions,
                    )
                }
            }
        }
    }
}

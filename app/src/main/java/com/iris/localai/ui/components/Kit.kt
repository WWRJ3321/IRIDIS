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
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(if (c.isDark) IrisElevation.none else IrisElevation.card, IrisRadius.largeShape, c.isDark)
            .clip(IrisRadius.largeShape)
            .background(c.surfaceElevated),
        content = content,
    )
}

/**
 * Secondary-page top bar: a floating round glass back button, a centred title ([titleModifier]
 * lets the scaffold fade it in as the large title scrolls away) and the page's actions grouped in
 * one glass pill (drawn only when there are actions). Behind them, a full-width glass strip
 * (status bar included) fades in with [stripAlpha] once content scrolls underneath.
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
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = stripAlpha() }
                .glassSurface(androidx.compose.ui.graphics.RectangleShape, IrisElevation.none),
        )
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(IrisDimens.topBarHeight + IrisDimens.space8).padding(horizontal = IrisDimens.space16),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, style = IrisType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = IrisDimens.floatingButton + IrisDimens.space24).then(titleModifier),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = onBack)
                Spacer(Modifier.weight(1f))
                var hasActions by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .then(if (hasActions) Modifier.glassSurface(IrisRadius.full).padding(horizontal = IrisDimens.space2) else Modifier)
                        .onSizeChanged { hasActions = it.width > 0 && it.height > 0 },
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

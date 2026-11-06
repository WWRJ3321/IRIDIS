package com.iris.localai.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisColors
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/** Surface colour for floating containers (dialogs, sheets): lifted a step in dark mode. */
val IrisColors.floatingSurface get() = if (isDark) surfaceElevated else surface

/**
 * One dialog look for the whole app. 1.8.0 (HyperOS): centred title, continuous 30 dp corners,
 * two equal-width pill buttons along the bottom (取消 grey, confirm blue; destructive = red text).
 */
@Composable
fun IrisDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2),
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    onDismissClick: () -> Unit = onDismiss,
    content: @Composable (() -> Unit)? = null,
) {
    val c = Iris.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.largeSurface(IrisRadius.dialogShape, com.iris.localai.ui.theme.IrisElevation.menu),
        shape = IrisRadius.dialogShape,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        titleContentColor = c.text,
        textContentColor = c.textSecondary,
        title = {
            Text(title, style = IrisType.title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = content?.let { body -> { Box(Modifier.fillMaxWidth()) { body() } } },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space12)) {
                if (dismissText != null) {
                    IrisButton(dismissText, onDismissClick, style = IrisButtonStyle.Tonal, modifier = Modifier.weight(1f))
                }
                IrisButton(
                    confirmText, onConfirm, enabled = confirmEnabled,
                    style = if (destructive) IrisButtonStyle.Tonal else IrisButtonStyle.Primary,
                    destructive = destructive,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        dismissButton = null,
    )
}

/** Yes/no confirmation; [destructive] paints the confirm action in the danger colour. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    IrisDialog(
        onDismiss = onDismiss, title = title, confirmText = confirmText,
        onConfirm = { onConfirm(); onDismiss() }, destructive = destructive,
    ) {
        Text(message, style = IrisType.secondary, color = Iris.colors.textSecondary)
    }
}

/** Dialog with a single text field, focused with the cursor at the end. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = com.iris.localai.I18n.s(com.iris.localai.R.string.components_38cf16),
    placeholder: String? = null,
    singleLine: Boolean = true,
    allowEmpty: Boolean = false,
) {
    val c = Iris.colors
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    IrisDialog(
        onDismiss = onDismiss, title = title, confirmText = confirmText,
        confirmEnabled = allowEmpty || value.text.isNotBlank(),
        onConfirm = { onConfirm(value.text); onDismiss() },
    ) {
        OutlinedTextField(
            value, { value = it },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            maxLines = if (singleLine) 1 else 10,
            textStyle = IrisType.body.copy(color = c.text),
            placeholder = placeholder?.let { p -> { Text(p, style = IrisType.body, color = c.textTertiary) } },
            shape = IrisRadius.mediumShape,
            colors = irisTextFieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/**
 * 1.10.10: one text-field look for the whole app (iOS / HyperOS): a filled grey field with
 * continuous corners and no outline; focus shows only the blue cursor + a faint accent rim,
 * an error a red rim. Every input (dialogs, memory, voice clone, API port) uses it.
 */
@Composable
fun irisTextFieldColors() = Iris.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent.copy(alpha = 0.55f),
        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        errorBorderColor = c.danger,
        cursorColor = c.accent,
        errorCursorColor = c.danger,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        focusedContainerColor = c.surfaceVariant,
        unfocusedContainerColor = c.surfaceVariant,
        disabledContainerColor = c.surfaceVariant.copy(alpha = 0.5f),
        errorContainerColor = c.surfaceVariant,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.textTertiary,
        focusedPlaceholderColor = c.textTertiary,
        unfocusedPlaceholderColor = c.textTertiary,
        focusedSupportingTextColor = c.textTertiary,
        unfocusedSupportingTextColor = c.textTertiary,
        selectionColors = androidx.compose.foundation.text.selection.TextSelectionColors(c.accent, c.accent.copy(alpha = 0.3f)),
    )
}

/**
 * 1.10.10: one popup-menu look (long-press menus on messages and history): continuous corners, lifted surface.
 * 1.13.0: own popup instead of Material DropdownMenu — its window clipped the shadow into a hard grey
 * band and the menu covered its anchor. The card now sits [MenuGeometry.GAP] beside the anchor (below,
 * or above when there is no room), inside a transparent margin wide enough for the soft shadow, and
 * scales/fades in from the anchor-side corner.
 */
@Composable
fun IrisMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Iris.colors
    val reduce = com.iris.localai.ui.theme.LocalReduceMotion.current
    val visible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    visible.targetState = expanded
    if (!visible.currentState && !visible.targetState) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val marginPx = with(density) { MenuGeometry.MARGIN.roundToPx() }
    val gapPx = with(density) { MenuGeometry.GAP.roundToPx() }
    var origin by remember { mutableStateOf(androidx.compose.ui.graphics.TransformOrigin(1f, 0f)) }
    val provider = remember(marginPx, gapPx) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val p = MenuGeometry.place(
                    anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom,
                    windowSize.width, windowSize.height, popupContentSize.width, popupContentSize.height, marginPx, gapPx,
                )
                origin = androidx.compose.ui.graphics.TransformOrigin(p.originX, p.originY)
                return androidx.compose.ui.unit.IntOffset(p.x, p.y)
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = visible,
            enter = if (reduce) androidx.compose.animation.fadeIn(com.iris.localai.ui.motion.IrisMotion.Quick.float())
            else androidx.compose.animation.fadeIn(com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.FadeIn)) +
                androidx.compose.animation.scaleIn(com.iris.localai.ui.motion.IrisMotion.Snappy.float(), initialScale = 0.86f, transformOrigin = origin),
            exit = if (reduce) androidx.compose.animation.fadeOut(com.iris.localai.ui.motion.IrisMotion.Quick.float())
            else androidx.compose.animation.fadeOut(com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.FadeOut)) +
                androidx.compose.animation.scaleOut(com.iris.localai.ui.motion.IrisMotion.Quick.float(), targetScale = 0.92f, transformOrigin = origin),
        ) {
            Box(Modifier.padding(MenuGeometry.MARGIN)) {
                Column(
                    Modifier
                        .glassSurface(IrisRadius.largeShape, com.iris.localai.ui.theme.IrisElevation.menu)
                        .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                        .widthIn(min = MenuGeometry.MIN_WIDTH)
                        .padding(vertical = IrisDimens.space4),
                    content = content,
                )
            }
        }
    }
}

/** 1.13.0: where an [IrisMenu] goes (pure, unit-tested). Sizes include the transparent shadow margin. */
object MenuGeometry {
    val MARGIN = IrisDimens.space20
    val GAP = IrisDimens.space6
    val MIN_WIDTH = 168.dp

    data class Placement(val x: Int, val y: Int, val originX: Float, val originY: Float, val below: Boolean)

    fun place(aL: Int, aT: Int, aR: Int, aB: Int, winW: Int, winH: Int, w: Int, h: Int, margin: Int, gap: Int): Placement {
        val cardW = w - 2 * margin
        val cardH = h - 2 * margin
        // Align the card's far edge with the anchor's: right-hand anchors open leftwards.
        val rightSide = (aL + aR) / 2 > winW / 2
        val cardX = (if (rightSide) aR - cardW else aL).coerceIn(0, (winW - cardW).coerceAtLeast(0))
        val below = aB + gap + cardH <= winH || aT - gap - cardH < 0
        val cardY = (if (below) aB + gap else aT - gap - cardH).coerceIn(0, (winH - cardH).coerceAtLeast(0))
        return Placement(cardX - margin, cardY - margin, if (rightSide) 1f else 0f, if (below) 0f else 1f, below)
    }
}

/** One menu row: 16 sp text, secondary-tinted 20 dp icon, destructive rows in red. */
@Composable
fun IrisMenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, destructive: Boolean = false, onClick: () -> Unit) {
    val c = Iris.colors
    val tint = if (destructive) c.danger else c.text
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text, style = IrisType.body.copy(fontSize = 16.sp), color = tint) },
        leadingIcon = icon?.let { i -> { androidx.compose.material3.Icon(i, null, Modifier.size(IrisDimens.iconMedium), tint = if (destructive) c.danger else c.textSecondary) } },
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = IrisDimens.space16),
        modifier = Modifier.padding(horizontal = 0.dp),
    )
}

/** Bottom sheet with the app's surface, radius, handle and scrim. */
@Composable
fun sheetSurfaceColor(): androidx.compose.ui.graphics.Color {
    val c = Iris.colors
    return (if (c.isDark) c.surfaceElevated else c.surface).copy(alpha = 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrisSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Iris.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 2.2.6 root-cause fix: the 2.2.5 sheet painted its surface through `modifier`
        // (largeSurface = clip + background) with a Transparent container. ModalBottomSheet applies
        // `modifier` OUTSIDE its anchoredDraggable offset, so that background/clip sat at the
        // un-offset (top) position: a white wash above the sheet, a see-through sheet body (the
        // real sheet had no colour) and the sheet clipped off before the bottom edge. Now the
        // colour goes into containerColor (fully opaque) and nothing is drawn via `modifier`.
        containerColor = sheetSurfaceColor(),
        contentColor = c.text,
        scrimColor = LARGE_SCRIM,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) },
        tonalElevation = 0.dp,
        shape = com.iris.localai.ui.theme.SmoothRoundedShape(24.dp, 24.dp, 0.dp, 0.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = IrisDimens.space6)
                    .size(width = 36.dp, height = IrisDimens.space4)
                    .background(c.textTertiary.copy(alpha = 0.4f), IrisRadius.pillShape),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = IrisDimens.space12), content = content)
    }
}

/** Title row of a sheet with an optional trailing action. */
@Composable
fun SheetHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = IrisDimens.space16, end = IrisDimens.space16, top = IrisDimens.space4, bottom = IrisDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = IrisType.title, color = Iris.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}


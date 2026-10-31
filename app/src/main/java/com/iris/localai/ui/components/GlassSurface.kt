package com.iris.localai.ui.components

import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.glass.LocalGlassQuality
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisElevation
import com.iris.localai.ui.theme.softShadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/*
 * 2.2.4 liquid glass. A screen marks the content that scrolls under its floating chrome with
 * [glassBackdrop] (one source per screen — the chat list is captured as one layer, never per item)
 * and provides its [HazeState] through [LocalGlassHaze]. Every floating surface (top-bar buttons,
 * pills, menus, drawer, sheets, dialogs) then draws [glassSurface]: a ~20 dp backdrop blur
 * (Android 12+, via Haze) under a translucent tint, a thin top-left highlight rim and the soft
 * shadow. Below Android 12, with 视觉效果 off / power saving, or with no backdrop it falls back to
 * an almost opaque tinted surface automatically, so text always stays readable.
 */

/** The backdrop the floating glass of the current screen blurs; null = tinted fallback only. */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

object GlassTokens {
    val blurRadius = IrisDimens.space20
    val rim = 0.75.dp
    /** Tint over the blur: light ≈ 65 % white, dark ≈ 55 % near-black. */
    val lightTint = Color.White.copy(alpha = 0.65f)
    val darkTint = Color(0xFF141416).copy(alpha = 0.55f)
    /** Without blur the tint must carry the contrast on its own. */
    val lightFallback = Color(0xFFFAFAFB).copy(alpha = 0.94f)
    val darkFallback = Color(0xFF1C1C1F).copy(alpha = 0.92f)
    const val PRESS_SCALE = 0.96f
}

/** Captures this content as the backdrop of [state]'s glass surfaces. */
fun Modifier.glassBackdrop(state: HazeState): Modifier = this.hazeSource(state)

/** Glass background for a floating surface of [shape]. Apply before the surface's padding/content. */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    elevation: Dp = IrisElevation.floating,
    state: HazeState? = LocalGlassHaze.current,
): Modifier {
    val c = Iris.colors
    val dark = c.isDark
    val blur = state != null && LocalGlassQuality.current.blurs
    val tint = if (dark) GlassTokens.darkTint else GlassTokens.lightTint
    val fallback = if (dark) GlassTokens.darkFallback else GlassTokens.lightFallback
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.22f else 0.70f),
        0.5f to Color.White.copy(alpha = if (dark) 0.06f else 0.18f),
        1f to Color.White.copy(alpha = if (dark) 0.03f else 0.10f),
        start = Offset.Zero, end = Offset.Infinite,
    )
    val base = this.softShadow(elevation, shape, dark).clip(shape)
    val filled = if (blur) base.hazeEffect(state!!) {
        backgroundColor = c.background
        blurRadius = GlassTokens.blurRadius
        tints = listOf(HazeTint(tint))
        fallbackTint = HazeTint(fallback)
        noiseFactor = 0f
    } else base.background(fallback)
    return filled.border(GlassTokens.rim, rim, shape)
}

/** A glass container of [shape] (see [glassSurface]). */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = IrisElevation.floating,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glassSurface(shape, elevation), content = content)
}

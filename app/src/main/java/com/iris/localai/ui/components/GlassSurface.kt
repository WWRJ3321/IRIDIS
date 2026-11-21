package com.iris.localai.ui.components

import com.iris.localai.ui.theme.IrisDarkColors
import com.iris.localai.ui.theme.IrisLightColors
import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
 * liquid glass. A screen marks the content that scrolls under its floating chrome with
 * [glassBackdrop] (one source per screen — the chat list is captured as one layer, never per item)
 * and provides its [HazeState] through [LocalGlassHaze]. Every floating surface (top-bar buttons,
 * pills, menus, drawer, sheets, dialogs) then draws [glassSurface]: a 24 dp (2.2.5) backdrop blur
 * (Android 12+, via Haze) under a translucent tint, a thin top-left highlight rim and the soft
 * shadow. Below Android 12, with 视觉效果 off / power saving, or with no backdrop it falls back to
 * an almost opaque tinted surface automatically, so text always stays readable.
 */

/** The backdrop the floating glass of the current screen blurs; null = tinted fallback only. */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

object GlassTokens {
    /** stronger frosted glass (user: 2.2.4 pill too flat, no glass feel). */
    val blurRadius = 24.dp
    val rim = 1.dp
    val elevation = 8.dp
    /** Tint over the blur: light ≈ 45 % white, dark ≈ 40 % near-black. */
    val lightTint = Color.White.copy(alpha = 0.45f)
    val darkTint = IrisDarkColors.glassTint.copy(alpha = 0.40f)
    /** stronger tint for glass that carries a list (the expanded model panel). */
    val lightTintStrong = Color.White.copy(alpha = 0.78f)
    val darkTintStrong = IrisDarkColors.glassTint.copy(alpha = 0.72f)
    const val NOISE = 0.15f
    /** Beta3 液态：填充乘这个系数、模糊更大、颗粒更细。 */
    const val LIQUID_TINT = 0.6f
    val liquidBlur = 30.dp
    const val LIQUID_NOISE = 0.06f
    /** Without blur the tint must carry the contrast on its own. */
    val lightFallback = IrisLightColors.surface.copy(alpha = 0.94f)
    val darkFallback = IrisDarkColors.surface.copy(alpha = 0.92f)
    const val PRESS_SCALE = 0.96f
}

/**
 * 柔光 solid surfaces (user: the matte glass still looked 花哨/廉价): fully opaque
 * surface colour, no blur, no sheen/rim, a 0.5 dp low-contrast hairline, and at most a faint
 * 2 dp shadow in light theme (none in dark). 经典 keeps the 2.2.5 glass. Pure constants (unit-tested).
 */
object MatteGlass {
    const val TINT_ALPHA = 1f
    const val TINT_ALPHA_STRONG = 1f
    const val BLUR_DP = 0f
    const val HAIRLINE_DP = 0.5f
    const val HAIRLINE_ALPHA_LIGHT = 0.08f
    const val HAIRLINE_ALPHA_DARK = 0.08f
    const val SHADOW_DP = 2f
    const val SHADOW_ALPHA_LIGHT = 0.05f
    const val SHADOW_ALPHA_DARK = 0f
    const val FALLBACK_ALPHA = 1f

    /** Hairline colour: black on light, white on dark, very low alpha. */
    fun hairline(dark: Boolean): Color = if (dark) Color.White.copy(alpha = HAIRLINE_ALPHA_DARK) else Color.Black.copy(alpha = HAIRLINE_ALPHA_LIGHT)

    /** Shadow for a floating surface: faint in light, none in dark. */
    fun shadowDp(dark: Boolean, elevated: Boolean): Float = if (!elevated || dark || SHADOW_ALPHA_LIGHT <= 0f) 0f else SHADOW_DP
}

/** solid surface for the 柔光 style (see [MatteGlass]); [state]/[strong] are ignored. */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun Modifier.matteGlassSurface(shape: Shape, elevation: Dp, state: HazeState?, strong: Boolean): Modifier {
    val c = Iris.colors
    val dark = c.isDark
    val sh = MatteGlass.shadowDp(dark, elevation.value > 0f)
    val shadowColor = Color.Black.copy(alpha = MatteGlass.SHADOW_ALPHA_LIGHT)
    return (if (sh > 0f) this.shadow(sh.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor) else this)
        .clip(shape)
        .background(c.surface.copy(alpha = 1f))
        .border(MatteGlass.HAIRLINE_DP.dp, MatteGlass.hairline(dark), shape)
}

/** Captures this content as the backdrop of [state]'s glass surfaces. */
fun Modifier.glassBackdrop(state: HazeState): Modifier = this.hazeSource(state)

/** Glass background for a floating surface of [shape]. Apply before the surface's padding/content. */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    elevation: Dp = GlassTokens.elevation,
    state: HazeState? = LocalGlassHaze.current,
    strong: Boolean = false,
): Modifier {
    if (softStyle()) return matteGlassSurface(shape, elevation, state, strong)
    val c = Iris.colors
    val dark = c.isDark
    val blur = state != null && LocalGlassQuality.current.blurs
    val liquid = com.iris.localai.ui.theme.LocalIrisStyle.current == com.iris.localai.ui.theme.IrisStyle.LIQUID
    val tint0 = if (strong) (if (dark) GlassTokens.darkTintStrong else GlassTokens.lightTintStrong)
        else if (dark) GlassTokens.darkTint else GlassTokens.lightTint
    // Beta3 液态去塑料感：填充更薄（约 6 成），边与高光只由 liquidEdge 画一层
    val tint = if (liquid) tint0.copy(alpha = tint0.alpha * GlassTokens.LIQUID_TINT) else tint0
    val fallback = if (dark) GlassTokens.darkFallback else GlassTokens.lightFallback
    // 1 dp rim, white 70 % → 15 % from top-left to bottom-right (dimmer in dark theme).
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.32f else 0.70f),
        1f to Color.White.copy(alpha = if (dark) 0.06f else 0.15f),
        start = Offset.Zero, end = Offset.Infinite,
    )
    // Subtle vertical sheen: the top of the glass a little brighter than the bottom.
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (liquid) 0f else if (dark) 0.07f else 0.18f),
        1f to Color.Transparent,
    )
    val highlight = Color.White.copy(alpha = if (dark) 0.22f else 0.85f)
    val shadowColor = Color.Black.copy(alpha = if (liquid) (if (dark) 0.16f else 0.06f) else if (dark) 0.30f else 0.12f)
    val base = (if (elevation.value > 0f) this.shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor) else this)
        .clip(shape)
    val filled = if (blur) base.hazeEffect(state!!) {
        backgroundColor = c.background
        blurRadius = if (liquid) GlassTokens.liquidBlur else GlassTokens.blurRadius
        tints = listOf(HazeTint(tint))
        fallbackTint = HazeTint(fallback)
        noiseFactor = if (liquid) GlassTokens.LIQUID_NOISE else GlassTokens.NOISE
    } else base.background(fallback)
    return filled
        .background(sheen)
        .drawWithContent {
            drawContent()
            if (liquid) return@drawWithContent
            // Thin inner top highlight: the shape's outline inset by the rim, fading out by ~40 % height.
            val inset = GlassTokens.rim.toPx() * 1.5f
            val w = size.width - inset * 2; val h = size.height - inset * 2
            if (w > 0f && h > 0f) {
                val outline = shape.createOutline(Size(w, h), layoutDirection, this)
                translate(inset, inset) {
                    drawOutline(
                        outline,
                        Brush.verticalGradient(0f to highlight, 0.4f to Color.Transparent, endY = h),
                        style = Stroke(width = 0.75.dp.toPx()),
                    )
                }
            }
        }
        .then(if (liquid) Modifier.liquidEdge(shape, dark) else Modifier.border(GlassTokens.rim, rim, shape))
}

/**
 * 3.0 液态玻璃的边缘（Beta3 去塑料感）：一圈 0.8 dp 发丝线，上亮下透明（不再是 3 dp 整圈白边）；
 * 顶边一条 1 dp 细亮线代替大面积镜面高光；底部内阴影去掉。全部画在面板自己的轮廓里。
 */
fun Modifier.liquidEdge(shape: Shape, dark: Boolean): Modifier = this.drawWithContent {
    val outline = shape.createOutline(size, layoutDirection, this)
    val hair = 0.8.dp.toPx()
    val top = Color.White.copy(alpha = if (dark) 0.22f else 0.55f)
    drawOutline(
        outline,
        Brush.verticalGradient(0f to top, 0.5f to Color.Transparent, 1f to Color.White.copy(alpha = if (dark) 0.04f else 0.12f), startY = 0f, endY = size.height.coerceAtLeast(1f)),
        style = Stroke(width = hair * 2f),
    )
    // 顶边细亮线（只占顶部 1 dp 高，左右淡出）
    val line = 1.dp.toPx()
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 0.3f to top.copy(alpha = top.alpha * 0.6f), 0.7f to top.copy(alpha = top.alpha * 0.6f), 1f to Color.Transparent),
        topLeft = Offset(0f, hair), size = androidx.compose.ui.geometry.Size(size.width, line),
    )
    drawContent()
}

/**
 * large surfaces (bottom sheets, dialogs, drawer) are NOT see-through glass: they live in
 * their own window (Haze cannot sample the screen behind it — that produced the milky wash) or
 * cover too much to stay readable. A near-opaque surface (95 % surface colour) with the same rim
 * and top highlight; the scrim behind is a plain ~32 % black ([LARGE_SCRIM]).
 */
@Composable
fun Modifier.largeSurface(shape: Shape, elevation: Dp = 0.dp): Modifier {
    val c = Iris.colors
    val dark = c.isDark
    if (softStyle()) {
        // 柔光: opaque neutral sheet, 0.5 dp hairline, faint shadow (light only).
        val sh = MatteGlass.shadowDp(dark, elevation.value > 0f)
        val sc = Color.Black.copy(alpha = MatteGlass.SHADOW_ALPHA_LIGHT)
        return (if (sh > 0f) this.shadow(sh.dp, shape, clip = false, ambientColor = sc, spotColor = sc) else this)
            .clip(shape)
            .background(c.surface.copy(alpha = 1f))
            .border(MatteGlass.HAIRLINE_DP.dp, MatteGlass.hairline(dark), shape)
    }
    val fill = (if (dark) c.surfaceElevated else c.surface).copy(alpha = 1f) // fully opaque
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.14f else 0.70f),
        1f to Color.White.copy(alpha = if (dark) 0.03f else 0.15f),
        start = Offset.Zero, end = Offset.Infinite,
    )
    val shadowColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.12f)
    return (if (elevation.value > 0f) this.shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor) else this)
        .clip(shape)
        .background(fill)
        .border(GlassTokens.rim, rim, shape)
}

/** the dark scrim behind sheets, dialogs and the drawer. */
val LARGE_SCRIM = Color.Black.copy(alpha = 0.32f)

/**
 * a very soft ambient glow behind the top of the empty home screen (brand blue + light
 * purple blobs fading out by ~35 % of the screen height) so the frosted top bar has something to
 * frost. Drawn inside the Haze source; the composer area and the empty-state content are untouched.
 */
@Composable
fun HomeAmbientGlow(modifier: Modifier = Modifier) {
    // no accent glow in 柔光 (restrained, no gradients); 经典 keeps it.
    if (softStyle()) return
    val c = Iris.colors
    val a = if (c.isDark) 0.08f else 0.14f
    // both blobs follow the theme accent (the second is the accent hue-shifted +40°).
    val blue = c.accent
    val purple = remember(c.accent) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.accent.toArgb(), hsv)
        hsv[0] = (hsv[0] + 40f) % 360f; hsv[1] = hsv[1] * 0.6f; hsv[2] = maxOf(hsv[2], 0.9f)
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().fillMaxHeight(0.35f)) {
        val fade = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent)
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawCircle(
            Brush.radialGradient(listOf(blue.copy(alpha = a), Color.Transparent), center = Offset(size.width * 0.18f, size.height * 0.12f), radius = size.width * 0.62f),
            radius = size.width * 0.62f, center = Offset(size.width * 0.18f, size.height * 0.12f),
        )
        drawCircle(
            Brush.radialGradient(listOf(purple.copy(alpha = a), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.28f), radius = size.width * 0.55f),
            radius = size.width * 0.55f, center = Offset(size.width * 0.85f, size.height * 0.28f),
        )
        drawRect(fade, blendMode = BlendMode.DstIn)
        drawIntoCanvas { it.restore() }
    }
}


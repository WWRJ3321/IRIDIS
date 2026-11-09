package com.iris.localai.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.theme.Iris
import kotlin.math.ceil

/*
 * IRIS glass — a from-scratch frosted-glass backdrop blur for Compose (no third-party code).
 *
 * 1.7.3: frosted glass only. The 1.7.1/1.7.2 AGSL liquid lens (refraction, dispersion, moving
 * highlights, press swelling, droplet merging) was removed: on the phone its outward samples hit
 * transparent pixels beyond the clip/screen edge and were forced opaque, which drew a thick black
 * rim, blue dispersion fringes and a square white block that ignored the rounded corners.
 *
 * Pipeline: Modifier.glassSource records the page (with its background, so the slice is opaque)
 * into a GraphicsLayer. Every glass panel records the slice of that layer under itself into its own
 * downsampled layer with a RenderEffect chain: blur → vibrancy. The blurred slice is drawn clipped
 * to the panel's own outline path (so it always follows the rounded corners), then tint, top sheen
 * and rim. Drawing a layer is by reference: when the list scrolls, HWUI re-renders the panel's
 * effect from the updated display list without re-running any Compose draw code. The panel's own
 * draw code only runs when the panel moves or resizes.
 */

@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(IntSize.Zero)
    /** Page colour, painted under every slice so content beyond the screen edge is never transparent. */
    internal var background by mutableStateOf(Color.Transparent)
}

@androidx.compose.runtime.Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Marks this content as what glass panels show through. [background] is painted into the
 * recording first so the slice behind a panel is never transparent.
 */
fun Modifier.glassSource(backdrop: GlassBackdrop, background: Color): Modifier = this
    .onGloballyPositioned {
        backdrop.origin = it.positionInRoot()
        backdrop.size = it.size
    }
    .drawWithContent {
        backdrop.background = background
        backdrop.layer.record {
            drawRect(background)
            this@drawWithContent.drawContent()
        }
        drawLayer(backdrop.layer)
    }

data class GlassStyle(
    /** Nominal blur radius (dp). */
    val blurRadius: Float = 24f,
    /** null = theme glass tint. */
    val tint: Color? = null,
    /** 1.13.1: 0 by default — flat look (user: 界面平化); the rim/sheen highlight is opt-in. */
    val rimAlpha: Float = 0f,
    /** 1.13.1: top white sheen, off by default (flat). */
    val sheen: Boolean = false,
    /** Large surfaces (drawer, full-screen) blur at a coarser resolution. */
    val large: Boolean = false,
    /** Saturation boost of what shows through (vibrancy). */
    val vibrancy: Float = 1.45f,
)

/**
 * Frosted glass behind this element, clipped to [shape]. Put it before background/padding modifiers.
 * In 省电 (or when downgraded) it is a translucent tint with no offscreen work.
 */
fun Modifier.glass(
    backdrop: GlassBackdrop?,
    shape: Shape,
    style: GlassStyle = GlassStyle(),
): Modifier = composed {
    val quality = LocalGlassQuality.current
    val source = if (quality.blurs) backdrop else null
    val colors = Iris.colors
    // 2.5.0 柔光 matte glass: ≥ 78 % opaque tint, gentler blur, no vibrancy boost, no sheen/rim.
    val matte = com.iris.localai.ui.theme.LocalIrisStyle.current == com.iris.localai.ui.theme.IrisStyle.SOFT
    val style = if (matte) style.copy(
        blurRadius = style.blurRadius.coerceAtMost(com.iris.localai.ui.components.MatteGlass.BLUR_DP),
        vibrancy = 1.05f, rimAlpha = 0f, sheen = false,
    ) else style
    val baseTint = style.tint ?: if (quality.blurs) colors.glassTint else colors.glassTintOpaque
    val tint = if (matte) baseTint.copy(alpha = baseTint.alpha.coerceAtLeast(com.iris.localai.ui.components.MatteGlass.TINT_ALPHA)) else baseTint
    val rim = colors.glassRim
    val solidBg = com.iris.localai.ui.theme.LocalSolidBackground.current
    val holder = remember { PanelState() }
    val panelLayer = rememberGraphicsLayer()

    this
        .then(if (source != null) Modifier.onGloballyPositioned { holder.position = it.positionInRoot() } else Modifier)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = outline.asPath()
            val rimBrush = Brush.linearGradient(
                0f to rim.copy(alpha = rim.alpha * style.rimAlpha),
                0.45f to rim.copy(alpha = rim.alpha * 0.12f * style.rimAlpha),
                1f to rim.copy(alpha = rim.alpha * 0.45f * style.rimAlpha),
                start = Offset.Zero,
                end = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f)),
            )
            // Top sheen: what makes even the translucent fallback read as glass.
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.22f),
                0.5f to Color.Transparent,
                startY = 0f, endY = size.height.coerceAtLeast(1f),
            )
            val rimStroke = Stroke(width = 1.dp.toPx())
            val drawRim = style.rimAlpha > 0f && rim.alpha > 0f
            val ok = source != null && size.minDimension > 0f

            // ---- FROSTED: downsampled blur + vibrancy, built once per size ----
            val ds = if (style.large) quality.downsampleLarge else quality.downsampleSmall
            val blurPx = style.blurRadius.dp.toPx() * quality.blurScale
            val fPad = ceil(blurPx * 1.5f).toInt()
            val fSize = IntSize(((size.width + fPad * 2) / ds).toInt().coerceAtLeast(1), ((size.height + fPad * 2) / ds).toInt().coerceAtLeast(1))
            val frostedEffect = if (ok) blurVibrancy(blurPx / ds, style.vibrancy).asComposeRenderEffect() else null

            onDrawBehind {
                if (ok && source != null && frostedEffect != null) {
                    // Reads backdrop.origin / position as state: redraw (not recompose) when they move.
                    val shift = source.origin - holder.position + Offset(fPad.toFloat(), fPad.toFloat())
                    panelLayer.renderEffect = frostedEffect
                    panelLayer.topLeft = IntOffset.Zero
                    panelLayer.record(fSize) {
                        drawRect(source.background)
                        scale(1f / ds, pivot = Offset.Zero) { translate(shift.x, shift.y) { drawLayer(source.layer) } }
                    }
                    clipPath(clip) {
                        translate(-fPad.toFloat(), -fPad.toFloat()) {
                            scale(ds.toFloat(), pivot = Offset.Zero) { drawLayer(panelLayer) }
                        }
                    }
                }
                // TRANSLUCENT draws only these: no offscreen work.
                drawOutline(outline, if (solidBg) tint.copy(alpha = 1f) else tint)
                if (style.sheen && !solidBg) drawOutline(outline, sheen)
                if (drawRim) drawOutline(outline, rimBrush, style = rimStroke, blendMode = BlendMode.SrcOver)
                if (matte) drawOutline(outline, com.iris.localai.ui.components.MatteGlass.hairline(colors.isDark), style = Stroke(width = com.iris.localai.ui.components.MatteGlass.HAIRLINE_DP.dp.toPx()))
            }
        }
}

@Stable
private class PanelState {
    var position by mutableStateOf(Offset.Zero)
}

/** Blur followed by a saturation boost: the "vibrancy" that makes colours behind glass glow through. */
internal fun blurVibrancy(blurPx: Float, saturation: Float): RenderEffect {
    val r = blurPx.coerceAtLeast(0.5f)
    val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
    if (saturation == 1f) return blur
    val cm = ColorMatrix().apply { setSaturation(saturation) }
    return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm), blur)
}

private fun Outline.asPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

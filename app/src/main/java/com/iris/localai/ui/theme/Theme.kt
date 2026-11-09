package com.iris.localai.ui.theme

import android.app.Activity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.remember
import com.iris.localai.ui.glass.GlassQuality
import com.iris.localai.ui.glass.LocalGlassQuality
import com.iris.localai.ui.motion.IrisIndication
import com.iris.localai.ui.motion.LocalDecorativeMotion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.iris.localai.data.ThemeMode

/**
 * Semantic colours. Components use these, never raw hex values.
 * 1.8.0 (HyperOS 75% / iOS 25%): dark is pure black with #1C1C1E cards, light is HyperOS
 * #F7F7F7 with white cards; the accent is the HyperOS blue used by switches, sliders, the
 * selected segment, primary buttons and the send button.
 */
@Immutable
data class IrisColors(
    val isDark: Boolean,
    /** Page background behind grouped cards (settings, models). */
    val background: Color,
    /** Chat page background (white / black: the chat is not a grouped page). */
    val chatBackground: Color,
    /** Sheets, dialogs, the composer field. */
    val surface: Color,
    /** User bubble, chips, input field, segmented-control track. */
    val surfaceVariant: Color,
    /** Grouped settings cards. */
    val surfaceElevated: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val danger: Color,
    /** 1.10.10: iOS system green / orange for status dots (running, listening, busy). */
    val success: Color,
    val warning: Color,
    val codeBackground: Color,
    /** Selected pill of a segmented control (lighter than its track). */
    val segmentThumb: Color,
    /** Switch track and knob when off (HyperOS: grey track; dark mode has a grey knob too). */
    val switchOffTrack: Color,
    val switchOffThumb: Color,
    /** Tint laid over the blurred backdrop of glass surfaces. */
    val glassTint: Color,
    /** Fallback when blur is off (省电 / battery saver / heat): a nearly opaque tint. */
    val glassTintOpaque: Color,
    val glassRim: Color,
    val scrim: Color,
    /** 2.5.0 柔光: top colour of the page gradient (Unspecified = plain [background], 经典). */
    val backgroundTop: Color = Color.Unspecified,
    /** 2.5.0 柔光: the tinted tile behind row icons (Unspecified = no tile, 经典). */
    val iconTile: Color = Color.Unspecified,
)

/** HyperOS blue (dark and light use the same hue; light is a touch deeper for contrast on white). */
internal val HyperBlueLight = Color(0xFF0D84FF)
internal val HyperBlueDark = Color(0xFF3482FF)

internal val IrisLightColors = IrisColors(
    isDark = false,
    background = Color(0xFFF7F7F7),
    chatBackground = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F2),
    surfaceElevated = Color(0xFFFFFFFF),
    text = Color(0xFF000000),
    textSecondary = Color(0xFF5E5E66),
    textTertiary = Color(0xFF8C8C93),
    outline = Color(0x14000000),
    accent = HyperBlueLight,
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFE5F0FF),
    onAccentContainer = Color(0xFF0B6FD9),
    danger = Color(0xFFF23B3B),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    codeBackground = Color(0xFFF4F4F6),
    segmentThumb = Color(0xFFFFFFFF),
    switchOffTrack = Color(0xFFE3E3E6),
    switchOffThumb = Color(0xFFFFFFFF),
    glassTint = Color(0x99FFFFFF),
    glassTintOpaque = Color(0xF0FFFFFF),
    glassRim = Color(0xE6FFFFFF),
    scrim = Color(0x4D000000),
)

internal val IrisDarkColors = IrisColors(
    isDark = true,
    background = Color(0xFF000000),
    chatBackground = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    surfaceElevated = Color(0xFF1C1C1E),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFADADB2),
    textTertiary = Color(0xFF8A8A8F),
    outline = Color(0x1FFFFFFF),
    accent = HyperBlueDark,
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFF13294D),
    onAccentContainer = Color(0xFF7DB3FF),
    danger = Color(0xFFFF5C5C),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    codeBackground = Color(0xFF1C1C1E),
    segmentThumb = Color(0xFF3A3A3C),
    switchOffTrack = Color(0xFF39393D),
    switchOffThumb = Color(0xFF75757A),
    glassTint = Color(0x8C141416),
    glassTintOpaque = Color(0xEB1C1C1E),
    glassRim = Color(0x40FFFFFF),
    scrim = Color(0x99000000),
)

val LocalIrisColors = staticCompositionLocalOf { IrisLightColors }

/** 2.5.0 界面风格: 柔光 (default; Beta2: solid neutral, one accent) or 经典 (≤ 2.4.6 look). */
enum class IrisStyle(val id: String) {
    SOFT("soft"), CLASSIC("classic");
    companion object {
        fun of(id: String?): IrisStyle = entries.firstOrNull { it.id == id } ?: SOFT
        fun isValid(id: String?): Boolean = entries.any { it.id == id }
    }
}

val LocalIrisStyle = staticCompositionLocalOf { IrisStyle.CLASSIC }

/**
 * 2.5.0 Beta2 柔光 = 克制 (restrained) look: solid neutral greys whatever the palette — no page
 * gradient, no blue-violet dark, no icon tiles. The palette (默认 / 樱花 / 薄荷 / 丁香 / 奶油) only
 * supplies the single accent (switches, selection, primary buttons, links). Values in [SoftNeutral]
 * (pure ARGB longs, unit-tested).
 */
object SoftNeutral {
    // light: iOS-settings grey page, white cards
    const val LIGHT_PAGE = 0xFFF5F5F7
    const val LIGHT_CHAT = 0xFFFFFFFF
    const val LIGHT_CARD = 0xFFFFFFFF
    const val LIGHT_VARIANT = 0xFFEDEDF0
    const val LIGHT_CODE = 0xFFF2F2F4
    const val LIGHT_THUMB = 0xFFFFFFFF
    const val LIGHT_TEXT = 0xFF111214
    const val LIGHT_TEXT2 = 0xFF6E6E73
    const val LIGHT_TEXT3 = 0xFF8E8E93
    const val LIGHT_OUTLINE = 0x14000000
    const val LIGHT_SWITCH_OFF = 0xFFE5E5EA
    // dark: neutral deep grey page, one step lighter cards
    const val DARK_PAGE = 0xFF111214
    const val DARK_CARD = 0xFF1C1D20
    const val DARK_VARIANT = 0xFF26272B
    const val DARK_CODE = 0xFF17181B
    const val DARK_THUMB = 0xFF34353A
    const val DARK_TEXT = 0xFFF2F2F3
    const val DARK_TEXT2 = 0xFFA1A1A6
    const val DARK_TEXT3 = 0xFF7C7C82
    const val DARK_OUTLINE = 0x1AFFFFFF
    const val DARK_SWITCH_OFF = 0xFF3A3A3E
    const val DARK_SWITCH_OFF_THUMB = 0xFF7A7A80

    /** True when [argb] has no hue cast (r == g == b ± 3): the neutral rule. */
    fun isNeutral(argb: Long): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 3
    }
}

object SoftLook {
    fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t, alpha = a.alpha + (b.alpha - a.alpha) * t,
    )

    fun apply(c: IrisColors): IrisColors = if (c.isDark) {
        val card = Color(SoftNeutral.DARK_CARD)
        c.copy(
            background = Color(SoftNeutral.DARK_PAGE),
            chatBackground = Color(SoftNeutral.DARK_PAGE),
            surface = card,
            surfaceElevated = card,
            surfaceVariant = Color(SoftNeutral.DARK_VARIANT),
            codeBackground = Color(SoftNeutral.DARK_CODE),
            segmentThumb = Color(SoftNeutral.DARK_THUMB),
            text = Color(SoftNeutral.DARK_TEXT),
            textSecondary = Color(SoftNeutral.DARK_TEXT2),
            textTertiary = Color(SoftNeutral.DARK_TEXT3),
            outline = Color(SoftNeutral.DARK_OUTLINE),
            switchOffTrack = Color(SoftNeutral.DARK_SWITCH_OFF),
            switchOffThumb = Color(SoftNeutral.DARK_SWITCH_OFF_THUMB),
            glassTint = card,
            glassTintOpaque = card,
            glassRim = Color.Transparent,
            backgroundTop = Color.Unspecified,
            iconTile = Color.Unspecified,
        )
    } else {
        val card = Color(SoftNeutral.LIGHT_CARD)
        c.copy(
            background = Color(SoftNeutral.LIGHT_PAGE),
            chatBackground = Color(SoftNeutral.LIGHT_CHAT),
            surface = card,
            surfaceElevated = card,
            surfaceVariant = Color(SoftNeutral.LIGHT_VARIANT),
            codeBackground = Color(SoftNeutral.LIGHT_CODE),
            segmentThumb = Color(SoftNeutral.LIGHT_THUMB),
            text = Color(SoftNeutral.LIGHT_TEXT),
            textSecondary = Color(SoftNeutral.LIGHT_TEXT2),
            textTertiary = Color(SoftNeutral.LIGHT_TEXT3),
            outline = Color(SoftNeutral.LIGHT_OUTLINE),
            switchOffTrack = Color(SoftNeutral.LIGHT_SWITCH_OFF),
            switchOffThumb = card,
            glassTint = card,
            glassTintOpaque = card,
            glassRim = Color.Transparent,
            backgroundTop = Color.Unspecified,
            iconTile = Color.Unspecified,
        )
    }
}

/** 2.2.14 纯色背景 is on: decorative gradients / glows / top fades draw nothing. */
val LocalSolidBackground = staticCompositionLocalOf { false }


object Iris {
    val colors: IrisColors @Composable get() = LocalIrisColors.current
}

/** Material slots mapped onto the IRIS type scale, so stock M3 widgets (dialogs, menus, buttons) match. */
private val IrisTypography = Typography(
    headlineSmall = IrisType.title.copy(fontSize = IrisFontSize.L, lineHeight = 26.sp),
    headlineMedium = IrisType.largeTitle,
    titleLarge = IrisType.display,
    titleMedium = IrisType.title,
    titleSmall = IrisType.titleSmall,
    bodyLarge = IrisType.message,
    bodyMedium = IrisType.secondary,
    bodySmall = IrisType.caption,
    labelLarge = IrisType.label,
    labelMedium = IrisType.labelSmall,
    labelSmall = IrisType.caption.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun IrisTheme(
    mode: ThemeMode,
    glassQuality: GlassQuality,
    reduceMotion: Boolean = false,
    decorativeMotion: Boolean = true,
    /** 2.1.4 配色 (默认 / 樱花粉 / 薄荷绿 / 丁香紫 / 奶油黄); the mode still picks light or dark. */
    palette: IrisPalette = IrisPalette.DEFAULT,
    /** 2.1.4: a custom chat background is set → cards and bubbles turn slightly see-through. */
    backdrop: Boolean = false,
    /** 2.2.14 纯色背景. */
    solid: Boolean = false,
    /** 2.5.0 界面风格 (柔光 / 经典). */
    style: IrisStyle = IrisStyle.CLASSIC,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = palette.colors(dark)
        .let { if (style == IrisStyle.SOFT) SoftLook.apply(it) else it }
        .let { if (solid) it.solid() else if (backdrop) it.overBackdrop() else it }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 2.1.5: no system contrast scrim behind the gesture pill (HyperOS draws one otherwise).
            window.isNavigationBarContrastEnforced = false
            // 2.4.6: the window background follows the app's own light/dark choice (not only the
            // system's), so the predictive-back preview no longer shows white behind a dark app.
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c.background.toArgb() or 0xFF000000.toInt()))
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surfaceElevated, surfaceContainerHigh = c.surfaceElevated, surfaceContainerLow = c.surface,
            secondaryContainer = c.accentContainer, onSecondaryContainer = c.onAccentContainer,
            outline = c.outline, outlineVariant = c.outline, error = c.danger,
            // 2.2.5: every Material accent slot maps to the brand tokens — no baseline-purple leaks.
            primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer, inversePrimary = c.accent,
            secondary = c.accent, onSecondary = c.onAccent,
            tertiary = c.accent, onTertiary = c.onAccent, tertiaryContainer = c.accentContainer, onTertiaryContainer = c.onAccentContainer,
            surfaceTint = c.accent, surfaceContainerHighest = c.surfaceElevated, surfaceContainerLowest = c.surface,
            surfaceBright = c.surface, surfaceDim = c.surface, onError = c.onAccent,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surfaceElevated, surfaceContainerHigh = c.surfaceElevated, surfaceContainerLow = c.surface,
            secondaryContainer = c.accentContainer, onSecondaryContainer = c.onAccentContainer,
            outline = c.outline, outlineVariant = c.outline, error = c.danger,
            // 2.2.5: every Material accent slot maps to the brand tokens — no baseline-purple leaks.
            primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer, inversePrimary = c.accent,
            secondary = c.accent, onSecondary = c.onAccent,
            tertiary = c.accent, onTertiary = c.onAccent, tertiaryContainer = c.accentContainer, onTertiaryContainer = c.onAccentContainer,
            surfaceTint = c.accent, surfaceContainerHighest = c.surfaceElevated, surfaceContainerLowest = c.surface,
            surfaceBright = c.surface, surfaceDim = c.surface, onError = c.onAccent,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = IrisTypography) {
        CompositionLocalProvider(
            LocalIrisColors provides c,
            LocalIrisStyle provides style,
            LocalSolidBackground provides solid,
            LocalGlassQuality provides glassQuality,
            LocalReduceMotion provides reduceMotion,
            // 2.1.4: text selection follows the theme accent.
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                androidx.compose.foundation.text.selection.TextSelectionColors(c.accent, c.accent.copy(alpha = 0.3f)),
            LocalDecorativeMotion provides (decorativeMotion && !reduceMotion),
            // One animated press highlight for every clickable in the app (replaces the M3 ripple).
            LocalIndication provides remember(c.isDark, reduceMotion) {
                IrisIndication(c.text.copy(alpha = if (c.isDark) 0.09f else 0.06f), reduceMotion)
            },
            content = content,
        )
    }
}

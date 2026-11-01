package com.iris.localai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 2.1.4 配色: the default IRIDIS look plus four tinted themes. Each has a light and a dark variant;
 * 浅色 / 深色 / 跟随系统 still decides which one is used. The whole UI is tinted (page and chat
 * background, cards, bubbles, accent, selection), and text keeps WCAG AA contrast on every
 * surface (checked in V214ThemesTest).
 */
enum class IrisPalette(val id: String, val label: String, val light: IrisColors, val dark: IrisColors) {
    DEFAULT("default", "默认", IrisLightColors, IrisDarkColors),
    SAKURA("sakura", "樱花粉", SakuraLight, SakuraDark),
    MINT("mint", "薄荷绿", MintLight, MintDark),
    LILAC("lilac", "丁香紫", LilacLight, LilacDark),
    CREAM("cream", "奶油黄", CreamLight, CreamDark);

    fun colors(dark: Boolean): IrisColors = if (dark) this.dark else light

    /** Colour of the round swatch in 外观 (the light accent). */
    val swatch: Color get() = light.accent

    companion object {
        const val DEFAULT_ID = "default"
        fun of(id: String?): IrisPalette = entries.firstOrNull { it.id == id } ?: DEFAULT
        fun isValid(id: String?): Boolean = entries.any { it.id == id }
    }
}

/**
 * 2.1.4 自定义背景: cards, bubbles and chips become slightly see-through so the picture shows
 * through; sheets, dialogs and the composer field ([IrisColors.surface]) stay opaque.
 */
/** 2.2.14 纯色背景: one page colour everywhere, every glass tint fully opaque (nothing mixes). */
fun IrisColors.solid(): IrisColors = copy(
    chatBackground = background,
    glassTint = surface.copy(alpha = 1f),
    glassTintOpaque = surface.copy(alpha = 1f),
    glassRim = Color.Transparent,
)

fun IrisColors.overBackdrop(): IrisColors = copy(
    surfaceVariant = surfaceVariant.copy(alpha = 0.84f),
    surfaceElevated = surfaceElevated.copy(alpha = 0.88f),
    codeBackground = codeBackground.copy(alpha = 0.88f),
    accentContainer = accentContainer.copy(alpha = 0.9f),
)

private fun tinted(
    base: IrisColors,
    background: Long, chatBackground: Long, surface: Long, surfaceVariant: Long,
    text: Long, textSecondary: Long, textTertiary: Long,
    accent: Long, accentContainer: Long, onAccentContainer: Long,
    codeBackground: Long, segmentThumb: Long, switchOffTrack: Long, switchOffThumb: Long,
    outline: Long, glassTint: Long, glassTintOpaque: Long,
): IrisColors = base.copy(
    background = Color(background), chatBackground = Color(chatBackground),
    surface = Color(surface), surfaceVariant = Color(surfaceVariant), surfaceElevated = Color(surface),
    text = Color(text), textSecondary = Color(textSecondary), textTertiary = Color(textTertiary),
    outline = Color(outline),
    accent = Color(accent), onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(accentContainer), onAccentContainer = Color(onAccentContainer),
    codeBackground = Color(codeBackground), segmentThumb = Color(segmentThumb),
    switchOffTrack = Color(switchOffTrack), switchOffThumb = Color(switchOffThumb),
    glassTint = Color(glassTint), glassTintOpaque = Color(glassTintOpaque),
)

// ---- 樱花粉 ----
internal val SakuraLight = tinted(
    IrisLightColors,
    background = 0xFFF9F1F4, chatBackground = 0xFFFFF9FB, surface = 0xFFFFFFFF, surfaceVariant = 0xFFF6E4EA,
    text = 0xFF2B1A20, textSecondary = 0xFF6B5560, textTertiary = 0xFF8E7680,
    accent = 0xFFC93D72, accentContainer = 0xFFFBE3EC, onAccentContainer = 0xFF9C2557,
    codeBackground = 0xFFF8EDF1, segmentThumb = 0xFFFFFFFF, switchOffTrack = 0xFFEBDCE2, switchOffThumb = 0xFFFFFFFF,
    outline = 0x1A5A1F38, glassTint = 0x99FFF7FA, glassTintOpaque = 0xF0FFF7FA,
)
internal val SakuraDark = tinted(
    IrisDarkColors,
    background = 0xFF120A0E, chatBackground = 0xFF120A0E, surface = 0xFF22181D, surfaceVariant = 0xFF33242B,
    text = 0xFFFFF4F8, textSecondary = 0xFFCBB3BD, textTertiary = 0xFF9E858F,
    accent = 0xFFD9568A, accentContainer = 0xFF4A1D31, onAccentContainer = 0xFFFFB0CD,
    codeBackground = 0xFF22181D, segmentThumb = 0xFF45323A, switchOffTrack = 0xFF3E2E35, switchOffThumb = 0xFF8C7680,
    outline = 0x24FFD6E4, glassTint = 0x8C1A1015, glassTintOpaque = 0xEB22181D,
)

// ---- 薄荷绿 ----
internal val MintLight = tinted(
    IrisLightColors,
    background = 0xFFF0F7F3, chatBackground = 0xFFF8FCFA, surface = 0xFFFFFFFF, surfaceVariant = 0xFFDEEFE6,
    text = 0xFF14261E, textSecondary = 0xFF4F665B, textTertiary = 0xFF71877C,
    accent = 0xFF0F8A63, accentContainer = 0xFFD3F0E3, onAccentContainer = 0xFF0A6247,
    codeBackground = 0xFFEAF5EF, segmentThumb = 0xFFFFFFFF, switchOffTrack = 0xFFD9E6DF, switchOffThumb = 0xFFFFFFFF,
    outline = 0x1A1F4A38, glassTint = 0x99F7FFFB, glassTintOpaque = 0xF0F7FFFB,
)
internal val MintDark = tinted(
    IrisDarkColors,
    background = 0xFF08110D, chatBackground = 0xFF08110D, surface = 0xFF15211C, surfaceVariant = 0xFF213129,
    text = 0xFFF0FAF5, textSecondary = 0xFFAEC6BA, textTertiary = 0xFF7F978B,
    accent = 0xFF1B9A70, accentContainer = 0xFF12392B, onAccentContainer = 0xFF86E0BC,
    codeBackground = 0xFF15211C, segmentThumb = 0xFF2E4238, switchOffTrack = 0xFF2B3B33, switchOffThumb = 0xFF73897E,
    outline = 0x24D6FFEC, glassTint = 0x8C0E1813, glassTintOpaque = 0xEB15211C,
)

// ---- 丁香紫 ----
internal val LilacLight = tinted(
    IrisLightColors,
    background = 0xFFF5F2FA, chatBackground = 0xFFFBFAFE, surface = 0xFFFFFFFF, surfaceVariant = 0xFFEAE4F6,
    text = 0xFF1F1A2B, textSecondary = 0xFF5F5873, textTertiary = 0xFF827A95,
    accent = 0xFF7656C6, accentContainer = 0xFFECE4FB, onAccentContainer = 0xFF5637A6,
    codeBackground = 0xFFF1EDF8, segmentThumb = 0xFFFFFFFF, switchOffTrack = 0xFFE2DDEC, switchOffThumb = 0xFFFFFFFF,
    outline = 0x1A3A2A66, glassTint = 0x99FBF9FF, glassTintOpaque = 0xF0FBF9FF,
)
internal val LilacDark = tinted(
    IrisDarkColors,
    background = 0xFF0D0A13, chatBackground = 0xFF0D0A13, surface = 0xFF1C1825, surfaceVariant = 0xFF2A2437,
    text = 0xFFF7F4FF, textSecondary = 0xFFBDB4D1, textTertiary = 0xFF8F86A3,
    accent = 0xFF8E6EE0, accentContainer = 0xFF2E2350, onAccentContainer = 0xFFC9B6FF,
    codeBackground = 0xFF1C1825, segmentThumb = 0xFF3A3249, switchOffTrack = 0xFF352E43, switchOffThumb = 0xFF7F7791,
    outline = 0x24E4DAFF, glassTint = 0x8C14101C, glassTintOpaque = 0xEB1C1825,
)

// ---- 奶油黄 ----
internal val CreamLight = tinted(
    IrisLightColors,
    background = 0xFFFAF6EA, chatBackground = 0xFFFFFDF5, surface = 0xFFFFFEFA, surfaceVariant = 0xFFF2EAD2,
    text = 0xFF2A2210, textSecondary = 0xFF675B40, textTertiary = 0xFF877B5F,
    accent = 0xFFA86C00, accentContainer = 0xFFFBEFC8, onAccentContainer = 0xFF7A4F00,
    codeBackground = 0xFFF7F0DC, segmentThumb = 0xFFFFFEFA, switchOffTrack = 0xFFE9E1CB, switchOffThumb = 0xFFFFFEFA,
    outline = 0x1A4A3A10, glassTint = 0x99FFFCF2, glassTintOpaque = 0xF0FFFCF2,
)
internal val CreamDark = tinted(
    IrisDarkColors,
    background = 0xFF110E07, chatBackground = 0xFF110E07, surface = 0xFF211C12, surfaceVariant = 0xFF2F291C,
    text = 0xFFFFF9EA, textSecondary = 0xFFCFC3A6, textTertiary = 0xFF9F9478,
    accent = 0xFFB07812, accentContainer = 0xFF3D2E0E, onAccentContainer = 0xFFF5CF7A,
    codeBackground = 0xFF211C12, segmentThumb = 0xFF3F3726, switchOffTrack = 0xFF3A3324, switchOffThumb = 0xFF8C8268,
    outline = 0x24FFF0C8, glassTint = 0x8C18140B, glassTintOpaque = 0xEB211C12,
)

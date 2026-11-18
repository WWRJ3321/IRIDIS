package com.iris.localai.ui

import androidx.compose.ui.graphics.Color
import com.iris.localai.data.AppSettings
import com.iris.localai.data.migrated
import com.iris.localai.data.sanitized
import com.iris.localai.ui.theme.BackgroundImage
import com.iris.localai.ui.theme.IrisColors
import com.iris.localai.ui.theme.IrisDarkColors
import com.iris.localai.ui.theme.IrisLightColors
import com.iris.localai.ui.theme.IrisPalette
import com.iris.localai.ui.theme.overBackdrop
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 2.1.4 外观: four colour themes (light + dark), 配色 swatches, custom chat background. */
class V214ThemesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun source(path: String): String =
        listOf(path, "app/$path").map { java.io.File(it) }.first { it.exists() }.readText()

    // ---------------------------------------------------------------- WCAG contrast

    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun backgrounds(c: IrisColors) = mapOf(
        "background" to c.background, "chatBackground" to c.chatBackground, "surface" to c.surface,
        "surfaceVariant" to c.surfaceVariant, "surfaceElevated" to c.surfaceElevated,
        "codeBackground" to c.codeBackground, "segmentThumb" to c.segmentThumb,
    )

    private val newPalettes = IrisPalette.entries - IrisPalette.DEFAULT

    @Test fun newPalettesKeepTextAtWcagAA() {
        for (p in newPalettes) for (dark in listOf(false, true)) {
            val c = p.colors(dark)
            val tag = "${p.label} ${if (dark) "深色" else "浅色"}"
            for ((name, bg) in backgrounds(c)) {
                assertEquals("$tag $name is opaque", 1f, bg.alpha)
                assertTrue("$tag text on $name: ${contrast(c.text, bg)}", contrast(c.text, bg) >= 7.0)
                assertTrue("$tag textSecondary on $name: ${contrast(c.textSecondary, bg)}", contrast(c.textSecondary, bg) >= 4.5)
                // hints / placeholders: AA for large text and UI components
                assertTrue("$tag textTertiary on $name: ${contrast(c.textTertiary, bg)}", contrast(c.textTertiary, bg) >= 3.0)
            }
            assertTrue("$tag onAccentContainer", contrast(c.onAccentContainer, c.accentContainer) >= 4.5)
            assertTrue("$tag onAccent on accent (buttons, send)", contrast(c.onAccent, c.accent) >= 3.0)
            assertTrue("$tag accent against the page (switches, sliders)", contrast(c.accent, c.background) >= 3.0)
            assertTrue("$tag accent against cards", contrast(c.accent, c.surfaceElevated) >= 3.0)
        }
    }

    @Test fun palettesHaveLightAndDarkVariants() {
        assertEquals(listOf("默认", "樱花粉", "薄荷绿", "丁香紫", "奶油黄", "烟雨", "云霄"), IrisPalette.entries.map { it.label })
        assertEquals(IrisPalette.entries.size, IrisPalette.entries.map { it.id }.toSet().size)
        for (p in IrisPalette.entries) {
            assertFalse(p.label, p.colors(false).isDark)
            assertTrue(p.label, p.colors(true).isDark)
            assertTrue(p.label, luminance(p.colors(false).background) > 0.8)
            assertTrue(p.label, luminance(p.colors(true).background) < 0.02)
        }
        // tinted everywhere, not just the accent
        for (p in newPalettes) for (dark in listOf(false, true)) {
            val base = IrisPalette.DEFAULT.colors(dark)
            val c = p.colors(dark)
            for (pick in listOf<(IrisColors) -> Color>({ it.background }, { it.chatBackground }, { it.surfaceVariant }, { it.accent }, { it.accentContainer }, { it.text }))
                assertTrue("${p.label} differs from 默认", pick(c) != pick(base))
        }
    }

    @Test fun defaultThemeUnchangedAndStillTheDefault() {
        assertSame(IrisLightColors, IrisPalette.DEFAULT.colors(false))
        assertSame(IrisDarkColors, IrisPalette.DEFAULT.colors(true))
        assertEquals(Color(0xFFF7F7F7), IrisLightColors.background)
        assertEquals(Color(0xFF000000), IrisDarkColors.background)
        assertEquals("default", AppSettings().themePalette)
        assertEquals(IrisPalette.DEFAULT, IrisPalette.of(AppSettings().themePalette))
        assertEquals(IrisPalette.DEFAULT, IrisPalette.of("nope"))
    }

    // ---------------------------------------------------------------- settings

    @Test fun newSettingsDefaults() {
        val d = AppSettings()
        assertEquals("default", d.themePalette)
        assertEquals("", d.backgroundImage)
        assertEquals(0, d.backgroundBlur)
        assertEquals(40, d.backgroundScrim)
        // settings saved by 2.1.3 (no new keys) load with the same defaults
        val old = json.decodeFromString(AppSettings.serializer(), """{"themeMode":"DARK"}""").migrated().sanitized()
        assertEquals("default", old.themePalette)
        assertEquals("", old.backgroundImage)
        assertEquals(0, old.backgroundBlur)
        assertEquals(40, old.backgroundScrim)
        // round trip
        val s = AppSettings(themePalette = "mint", backgroundImage = "bg-123.jpg", backgroundBlur = 12, backgroundScrim = 55)
        assertEquals(s.sanitized(), json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), s)).sanitized())
    }

    @Test fun newSettingsAreSanitized() {
        val s = AppSettings(themePalette = "neon", backgroundImage = "../../shared_prefs/x.xml", backgroundBlur = 99, backgroundScrim = -3).sanitized()
        assertEquals("default", s.themePalette)
        assertEquals("", s.backgroundImage)
        assertEquals(25, s.backgroundBlur)
        assertEquals(0, s.backgroundScrim)
        assertEquals(90, AppSettings(backgroundScrim = 100, backgroundBlur = -1).sanitized().backgroundScrim)
        assertEquals(0, AppSettings(backgroundBlur = -1).sanitized().backgroundBlur)
        for (p in IrisPalette.entries) assertEquals(p.id, AppSettings(themePalette = p.id).sanitized().themePalette)
        assertTrue(BackgroundImage.isValidName("bg-1729000000000.jpg"))
        assertFalse(BackgroundImage.isValidName("/sdcard/a.jpg"))
        assertFalse(BackgroundImage.isValidName("bg-1.jpg.tmp"))
        assertFalse(BackgroundImage.isValidName(""))
    }

    // ---------------------------------------------------------------- background picture

    @Test fun pictureIsScaledToCoverTheScreenWithoutUpscaling() {
        // 4000×3000 landscape photo on a 1080×2400 phone: scaled so the height covers, then cropped
        val (w, h) = BackgroundImage.decodeSize(4000, 3000, 1080, 2400)
        assertEquals(3200 to 2400, w to h)
        assertEquals(1080 to 2400, BackgroundImage.cropSize(w, h, 1080, 2400))
        // landscape screen size is treated as portrait
        assertEquals(BackgroundImage.decodeSize(4000, 3000, 1080, 2400), BackgroundImage.decodeSize(4000, 3000, 2400, 1080))
        // small pictures are never enlarged
        assertEquals(800 to 600, BackgroundImage.decodeSize(800, 600, 1080, 2400))
        assertEquals(800 to 600, BackgroundImage.cropSize(800, 600, 1080, 2400))
        // a tall screenshot: width covers
        val (tw, th) = BackgroundImage.decodeSize(2160, 9600, 1080, 2400)
        assertEquals(1080 to 4800, tw to th)
        assertEquals(1080 to 2400, BackgroundImage.cropSize(tw, th, 1080, 2400))
        assertEquals(1 to 1, BackgroundImage.decodeSize(0, 0, 1080, 2400))
    }

    @Test fun backdropMakesCardsSeeThroughButKeepsSheetsOpaque() {
        for (p in IrisPalette.entries) for (dark in listOf(false, true)) {
            val c = p.colors(dark).overBackdrop()
            assertEquals(1f, c.surface.alpha)
            assertEquals(1f, c.background.alpha)
            assertTrue(c.surfaceVariant.alpha in 0.75f..0.95f)
            assertTrue(c.surfaceElevated.alpha in 0.75f..0.95f)
        }
    }

}

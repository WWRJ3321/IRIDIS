package com.iris.localai.v180

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.ui.components.LargeTitleMath
import com.iris.localai.ui.settings.SettingsText
import com.iris.localai.ui.theme.IrisDarkColors
import com.iris.localai.ui.theme.IrisLightColors
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.SmoothCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 1.8.0: UI restyle toward HyperOS (75 %) / iOS (25 %). Pure maths, palette and source checks. */
class V180Test {

    // ---------------------------------------------------------------- continuous corners

    @Test fun plainCornerWhenSmoothingIsZero() {
        val k = SmoothCorner.of(radius = 40f, smoothing = 0f, maxExtent = 500f)
        assertEquals(40f, k.extent, 1e-4f)
        assertEquals(90f, k.arcDegrees, 1e-4f)
        assertEquals(0f, k.a + k.b + k.c + k.d, 1e-4f)
    }

    @Test fun smoothedCornerStartsFurtherOutAndItsCurveMeetsTheArc() {
        val r = 48f
        val k = SmoothCorner.of(r, IrisRadius.SMOOTHING, maxExtent = 500f)
        assertEquals((1f + IrisRadius.SMOOTHING) * r, k.extent, 1e-3f)
        assertTrue(k.arcDegrees < 90f)
        // Top-right corner of a w × h box, corner at (w, 0): the Bézier ends at
        // (w − extent + a + b + c, d), which must lie on the circle centred at (w − r, r).
        val dx = (-k.extent + k.a + k.b + k.c) - (-r)
        val dy = k.d - r
        assertEquals(r, hypot(dx, dy), 0.05f)
    }

    @Test fun smallBoxesSmoothLessAndPillsStayRound() {
        // Room for only 1.2 r: smoothing is cut to 0.2 so the curve still fits.
        val tight = SmoothCorner.of(radius = 50f, smoothing = 0.6f, maxExtent = 60f)
        assertEquals(0.2f, tight.smoothing, 1e-4f)
        assertTrue(tight.extent <= 60f + 1e-3f)
        // Radius larger than half the side: a plain semicircle of half the side.
        val pill = SmoothCorner.of(radius = 100f, smoothing = 0.6f, maxExtent = 30f)
        assertEquals(30f, pill.radius, 1e-4f)
        assertEquals(0f, pill.smoothing, 1e-4f)
        assertEquals(0f, SmoothCorner.of(0f, 0.6f, 30f).extent, 0f)
    }

    // ---------------------------------------------------------------- large title

    @Test fun largeTitleCollapsesOverItsOwnHeight() {
        assertEquals(0f, LargeTitleMath.collapse(0f, 160f), 0f)
        assertEquals(0.5f, LargeTitleMath.collapse(80f, 160f), 1e-6f)
        assertEquals(1f, LargeTitleMath.collapse(900f, 160f), 0f)
        assertEquals(1f, LargeTitleMath.collapse(0f, 0f), 0f)
        // The small bar title appears only near the end, so both titles are never fully shown.
        assertEquals(0f, LargeTitleMath.barTitleAlpha(0.5f), 0f)
        assertEquals(1f, LargeTitleMath.barTitleAlpha(1f), 0f)
    }

    // ---------------------------------------------------------------- palettes (黑白都要)

    @Test fun darkIsPureBlackWithGreyCardsAndLightIsHyperOsGrey() {
        assertEquals(Color(0xFF000000), IrisDarkColors.background)
        assertEquals(Color(0xFF000000), IrisDarkColors.chatBackground)
        assertEquals(Color(0xFF1C1C1E), IrisDarkColors.surfaceElevated)
        assertEquals(Color(0xFFF7F7F7), IrisLightColors.background)
        assertEquals(Color(0xFFFFFFFF), IrisLightColors.surfaceElevated)
        assertEquals(Color(0xFFFFFFFF), IrisLightColors.chatBackground)
        assertTrue(IrisDarkColors.isDark); assertFalse(IrisLightColors.isDark)
    }

    @Test fun accentIsHyperOsBlueInBothModes() {
        for (c in listOf(IrisLightColors.accent, IrisDarkColors.accent)) {
            assertTrue("blue dominates", c.blue > 0.9f && c.red < 0.25f && c.green > 0.45f && c.green < 0.6f)
        }
        assertEquals(Color.White, IrisLightColors.onAccent)
        assertEquals(Color.White, IrisDarkColors.onAccent)
    }

    @Test fun textContrastIsComfortableOnCardsAndPages() {
        for (c in listOf(IrisLightColors, IrisDarkColors)) {
            for (bg in listOf(c.background, c.surfaceElevated, c.surface)) {
                assertTrue("title on ${c.isDark}", contrast(c.text, bg) >= 12.0)
                assertTrue("summary on ${c.isDark}", contrast(c.textTertiary, bg) >= 3.0)
            }
            // White on the accent (switch knob, send button, primary button).
            assertTrue(contrast(c.onAccent, c.accent) >= 3.0)
            // Cards stand off the page; the selected segment stands off its track.
            assertTrue(contrast(c.surfaceElevated, c.background) > 1.04)
            assertTrue(contrast(c.segmentThumb, c.surfaceVariant) > 1.04)
            assertTrue(contrast(c.switchOffTrack, c.surfaceElevated) > 1.1)
        }
    }

    // ---------------------------------------------------------------- tokens

    @Test fun tokensFollowTheReference() {
        assertEquals(24.dp, IrisRadius.large)
        assertEquals(17.sp, IrisType.row.fontSize)
        assertEquals(14.sp, IrisType.rowSummary.fontSize)
        assertEquals(14.sp, IrisType.sectionHeader.fontSize)
        assertTrue(IrisType.largeTitle.fontSize.value >= 30f)
    }

    @Test fun settingsKeepTheirStructureAndGainAVersionFooter() {
        assertEquals("IRIDIS 1.8.0-full", SettingsText.versionLine("1.8.0-full"))
        val home = source("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
            .substringAfter("fun SettingsHome(").substringBefore("fun ModelBackendPage(")
        assertTrue(home.contains("VersionFooter("))
        val wipe = home.substringAfter("\"删除全部对话\"").substringBefore(")")
        assertTrue("destructive row is red", wipe.contains("danger"))
        assertFalse("destructive row has no icon", wipe.contains("icon ="))
    }

    @Test fun groupsHaveNoHairlinesAndVersionIsBumped() {
        val lists = source("src/main/java/com/iris/localai/ui/components/Lists.kt")
        assertTrue(lists.contains("fun Divider(modifier: Modifier = Modifier, start: Dp = IrisDimens.rowPadding) = Unit"))
        val gradle = source("build.gradle.kts")
        // 1.9.0: was an exact 138 / "1.8.0" check; now "bumped to at least 1.8.0" so later releases keep it green.
        val code = Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toInt()
        assertTrue(code >= 138)
        assertTrue(Regex("versionName = \"(1\\.(8|9|\\d\\d)|[2-9])\\.\\d+").containsMatchIn(gradle))
    }

    // ---------------------------------------------------------------- helpers

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private fun source(path: String): String =
        listOf(path, "app/$path").map { java.io.File(it) }.first { it.exists() }.readText()
}

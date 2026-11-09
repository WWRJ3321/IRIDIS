package com.iris.localai.v250

import com.iris.localai.service.GenerationText
import com.iris.localai.service.LiveUpdate
import com.iris.localai.ui.theme.IrisDesignScale
import com.iris.localai.ui.theme.IrisStyle
import com.iris.localai.ui.theme.SoftNeutral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V250Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()

    // ---- 1 design tokens ----
    @Test fun designScaleHasFiveFontsFiveSpacesThreeCorners() {
        assertEquals(listOf(28f, 20f, 16f, 14f, 12f), IrisDesignScale.FONT_SP)
        assertEquals(listOf(4f, 8f, 12f, 16f, 24f), IrisDesignScale.SPACE_DP)
        assertEquals(listOf(12f, 20f, 28f), IrisDesignScale.CORNER_DP)
        assertEquals(14f, IrisDesignScale.nearest(15f, IrisDesignScale.FONT_SP), 0f)
    }

    @Test fun nearestTiesGoToSmaller() {
        assertEquals(8f, IrisDesignScale.nearest(10f, IrisDesignScale.SPACE_DP), 0f)
        assertEquals(16f, IrisDesignScale.nearest(18f, IrisDesignScale.FONT_SP), 0f)
        assertEquals(20f, IrisDesignScale.nearest(22f, IrisDesignScale.CORNER_DP), 0f)
        assertEquals(28f, IrisDesignScale.nearest(30f, IrisDesignScale.CORNER_DP), 0f)
    }

    @Test fun typeScaleOnlyUsesTokenSizes() {
        val t = src("app/src/main/java/com/iris/localai/ui/theme/Tokens.kt")
        val body = t.substring(t.indexOf("object IrisType {"), t.indexOf("val LocalReduceMotion"))
        // segment 按 2.4.6 原样保留 15sp（beta4：分段控件复原 2.4.6）
        val rest = body.lines().filterNot { it.trimStart().startsWith("val segment ") }.joinToString("\n")
        assertFalse("raw sp font size in IrisType", Regex("""fontSize = \d""").containsMatchIn(rest))
    }

    @Test fun uiCodeHasNoRawFontSizesOrOffScaleRadii() {
        val dir = File(root(), "app/src/main/java/com/iris/localai/ui")
        val bad = mutableListOf<String>()
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "Tokens.kt" }.forEach { f ->
            Regex("""fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp""").findAll(f.readText()).forEach { m ->
                val v = m.groupValues[1].toFloat()
                if (v in 10f..32f && v !in IrisDesignScale.FONT_SP) bad += "${f.name}: ${m.value}"
            }
        }
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    // ---- 3 style ----
    @Test fun styleDefaultsToSoftAndClassicIsKept() {
        assertEquals(IrisStyle.SOFT, IrisStyle.of(null))
        assertEquals(IrisStyle.SOFT, IrisStyle.of("bogus"))
        assertEquals(IrisStyle.CLASSIC, IrisStyle.of("classic"))
        assertTrue(IrisStyle.isValid("soft")); assertFalse(IrisStyle.isValid(""))
        assertEquals("soft", com.iris.localai.data.AppSettings().uiStyle)
        // palettes untouched by the style switch (source check: IrisPalette needs Compose at class init)
        assertEquals(5, Regex("^    [A-Z]+\\(\"[a-z]+\", ", RegexOption.MULTILINE).findAll(src("app/src/main/java/com/iris/localai/ui/theme/Palettes.kt")).count())
    }

    // ---- 2 structure: Beta4 restores the 2.4.6 settings structure (only visual changes stay) ----
    @Test fun settingsStructureIsBackTo246() {
        val screens = src("app/src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        val home = screens.substringAfter("fun SettingsHome(").substringBefore("\n}\n")
        assertFalse(home.contains("HeroCard(")); assertFalse(home.contains("DeveloperCard("))
        assertTrue(home.contains("NavItem(")); assertTrue(home.contains("SettingsPage.STORAGE"))
        // 开发者 is reached from 关于 again
        assertTrue(screens.substringAfter("fun AboutPage(").substringBefore("internal val ABOUT_PRIVACY").contains("onDeveloper"))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/MainActivity.kt").contains("AboutPage(onBack = pop, onDeveloper = { push(Route.DEVELOPER) })"))
        // 「高级」 folds are back where 2.4.6 had them
        assertTrue(screens.contains("AdvancedFold("))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/agent/AgentSettingsPage.kt").contains("AdvancedFold("))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/agent/GuiAgentSection.kt").contains("AdvancedFold("))
        // Beta1–3 entries/pages are gone: no storage tabs, no load presets, no 回答风格 / 过热保护 presets
        assertFalse(File(root(), "app/src/main/java/com/iris/localai/ui/models/LoadPreset.kt").exists())
        assertFalse(src("app/src/main/java/com/iris/localai/ui/settings/StoragePage.kt").contains("StorageTabs"))
        for (t in listOf("object AnswerStyle", "object OverheatLevel", "SettingsHomeModel", "fun NpuPage(", "fun DevSamplingPage(")) assertFalse(t, screens.contains(t))
        // the one 2.5.0 item kept on a settings page: 外观 › 界面风格
        assertTrue(screens.substringAfter("fun AppearancePage(").contains("R.string.v250_style"))
    }

    @Test fun homeLayoutIsKept() {
        // 2.5.0 (user 23:33): the chat home keeps its 2.4.6 structure — menu, 模型 + 角色 pills, new chat.
        val s = src("app/src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertTrue(s.contains("CharacterTitle("))
        assertTrue(s.contains("ModelTitle("))
        assertFalse(s.contains("HeroCard("))
        assertTrue(s.contains("IrisPageSurface(com.iris.localai.ui.theme.PageKind.CHAT)"))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/welcome/WelcomeScreen.kt").contains("irisPageBackground("))
    }

    @Test fun softGlassIsMatte() {
        val g = src("app/src/main/java/com/iris/localai/ui/components/GlassSurface.kt")
        assertTrue(g.contains("MatteGlass"))
        // Beta2: solid — opaque, no blur, 0.5 dp hairline, no shadow in dark
        assertEquals(1f, com.iris.localai.ui.components.MatteGlass.TINT_ALPHA, 0f)
        assertEquals(1f, com.iris.localai.ui.components.MatteGlass.TINT_ALPHA_STRONG, 0f)
        assertEquals(0f, com.iris.localai.ui.components.MatteGlass.BLUR_DP, 0f)
        assertEquals(0.5f, com.iris.localai.ui.components.MatteGlass.HAIRLINE_DP, 0f)
        assertEquals(0f, com.iris.localai.ui.components.MatteGlass.shadowDp(dark = true, elevated = true), 0f)
        assertTrue(com.iris.localai.ui.components.MatteGlass.shadowDp(dark = false, elevated = true) <= 2f)
        assertFalse(g.substringAfter("fun Modifier.matteGlassSurface").substringBefore("\n}\n").contains("hazeEffect"))
        // IrisCard in 柔光: opaque, no alpha reduction
        val kit = src("app/src/main/java/com/iris/localai/ui/components/Kit.kt").substringAfter("fun IrisCard(").substringBefore("\n}\n")
        assertFalse(kit.contains("alpha *"))
    }

    /** No real hue cast: channels within 6 (the iOS-like greys lean ≤ 6 cool; the old Beta #141826 is +18). */
    private fun neutral(argb: Long): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 6
    }

    @Test fun softIsNeutralWithoutGradient() {
        // Beta2: 柔光 sets no page gradient and no icon tile; page/card colours are neutral grey.
        val t = src("app/src/main/java/com/iris/localai/ui/theme/Theme.kt").substringAfter("object SoftLook").substringBefore("/** 2.2.14")
        assertFalse(t.contains("backgroundTop = mix"))
        assertEquals(2, Regex("backgroundTop = Color.Unspecified").findAll(t).count())
        assertEquals(2, Regex("iconTile = Color.Unspecified").findAll(t).count())
        assertEquals(0xFFF5F5F7, SoftNeutral.LIGHT_PAGE); assertEquals(0xFFFFFFFF, SoftNeutral.LIGHT_CARD)
        assertEquals(0xFF111214, SoftNeutral.DARK_PAGE); assertEquals(0xFF1C1D20, SoftNeutral.DARK_CARD)
        listOf(SoftNeutral.LIGHT_PAGE, SoftNeutral.LIGHT_VARIANT, SoftNeutral.DARK_PAGE, SoftNeutral.DARK_CARD,
            SoftNeutral.DARK_VARIANT, SoftNeutral.DARK_THUMB, SoftNeutral.DARK_TEXT2, SoftNeutral.LIGHT_TEXT2)
            .forEach { assertTrue(java.lang.Long.toHexString(it), neutral(it)) }
        assertFalse(neutral(0xFF141826)) // the old Beta grey-blue
        val hero = src("app/src/main/java/com/iris/localai/ui/components/SoftKit.kt").substringAfter("fun HeroCard(").substringBefore("\n}\n")
        assertFalse(hero.contains("Brush")); assertFalse(hero.contains("Color.White"))
        val lists = src("app/src/main/java/com/iris/localai/ui/components/Lists.kt")
        assertFalse(lists.substringAfter("fun RowIcon(").substringBefore("\n}\n").contains("colors.accent"))
        assertFalse(lists.substringAfter("fun SectionTitle(").substringBefore("\n}\n").contains("colors.accent"))
    }

    // ---- 4 Live Updates ----
    @Test fun liveUpdateOnlyOnApi36() {
        assertFalse(LiveUpdate.supported(35)); assertTrue(LiveUpdate.supported(36)); assertTrue(LiveUpdate.supported(37))
        assertEquals("android.requestPromotedOngoing", LiveUpdate.EXTRA_REQUEST_PROMOTED_ONGOING)
        assertEquals("IRIDIS", LiveUpdate.title("  "))
        assertEquals("周报", LiveUpdate.title("周报"))
        assertEquals(40, LiveUpdate.title("长".repeat(80)).length)
    }

    @Test fun liveUpdateTexts() {
        assertEquals("正在思考…", GenerationText.liveText(0))
        assertEquals("生成中 · 12 字", GenerationText.liveText(12))
        assertEquals("思考中", GenerationText.liveChip(0))
        assertEquals("5 字", GenerationText.liveChip(5))
    }

    @Test fun liveUpdateNotificationFollowsPromotionRules() {
        val s = src("app/src/main/java/com/iris/localai/service/GenerationService.kt")
        assertTrue(s.contains("setOngoing(true)"))
        assertTrue(s.contains("setUsesChronometer(true)"))
        assertTrue(s.contains("setColorized(false)"))
        assertTrue(s.contains("LiveUpdate.EXTRA_REQUEST_PROMOTED_ONGOING, true"))
        assertFalse(s.contains("RemoteViews"))
        assertFalse(s.contains("setCustomContentView"))
        assertFalse(s.contains("IMPORTANCE_MIN"))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("miui.focus"))
        assertFalse(Regex("miui\\.focus").containsMatchIn(File(root(), "app/src/main/java").walkTopDown().filter { it.isFile }.joinToString("") { it.readText() }))
        assertTrue(src("app/src/main/AndroidManifest.xml").contains(LiveUpdate.PERMISSION))
    }

    @Test fun versionAndSdk() {
        val g = src("app/build.gradle.kts")
        assertTrue(g.contains("compileSdk = 36"))
        assertTrue(g.contains("versionCode = 242"))
        assertTrue(g.contains("versionName = \"2.5.0-beta4\""))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta2"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta3"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta4"))
    }

    @Test fun newStringsInEveryLanguage() {
        val res = File(root(), "app/src/main/res")
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").isFile }
        assertEquals(7, dirs.size)
        val keys = Regex("name=\"(v250_[a-z_]+)\"").findAll(File(res, "values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(keys.size >= 30)
        for (d in dirs) {
            val t = File(d, "strings.xml").readText()
            for (k in keys) assertTrue("${d.name} $k", t.contains("name=\"$k\""))
        }
    }

    @Test fun terminalAndSchedulesStayGone() {
        val m = src("app/src/main/AndroidManifest.xml")
        assertFalse(m.contains("proot", ignoreCase = true)); assertFalse(m.contains("com.iridis.terminal"))
        val java = File(root(), "app/src/main/java")
        assertFalse(File(java, "com/iris/localai/agent/shell").exists())
        assertFalse(java.walkTopDown().any { it.name == "RunCommand.kt" })
    }
}

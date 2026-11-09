package com.iris.localai.v250

import com.iris.localai.data.ModelOverrides
import com.iris.localai.engine.EngineFeatures
import com.iris.localai.engine.EngineState
import com.iris.localai.service.GenerationText
import com.iris.localai.service.LiveUpdate
import com.iris.localai.ui.components.HeroStatus
import com.iris.localai.ui.models.LoadPreset
import com.iris.localai.ui.settings.SettingsHomeModel
import com.iris.localai.ui.settings.SettingsText
import com.iris.localai.ui.settings.StorageTabs
import com.iris.localai.ui.theme.IrisDesignScale
import com.iris.localai.ui.theme.IrisStyle
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
        assertFalse("raw sp font size in IrisType", Regex("""fontSize = \d""").containsMatchIn(body))
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

    // ---- 2 slimming ----
    @Test fun settingsHomeShowsAtMostSevenEntriesAndFoldsTheRest() {
        val visible = SettingsText.HOME_GROUPS.sumOf { it.second.size }
        assertTrue("visible=$visible", visible <= 7)
        assertEquals(SettingsHomeModel.VISIBLE_ENTRIES, visible)
        assertEquals("模型与加载", SettingsText.HOME_GROUPS.first().second.first())
        // nothing removed: the former entries are all still reachable from home (visible or 高级)
        val all = SettingsText.HOME_GROUPS.flatMap { it.second } + SettingsText.HOME_ADVANCED
        for (t in listOf("性能与资源", "API 服务", "语言", "关于 IRIDIS", "删除全部对话", "外观", "存储空间")) assertTrue(t, t in all)
    }

    @Test fun heroCardModel() {
        assertEquals(HeroStatus.NONE, SettingsHomeModel.hero(EngineState.Idle).state)
        val f = SettingsHomeModel.hero(EngineState.Failed(null, "boom\nmore"))
        assertEquals(HeroStatus.FAILED, f.state); assertEquals("boom", f.detail)
    }

    @Test fun storageHasThreeTabs() {
        assertEquals(listOf("概览", "清理", "位置"), StorageTabs.labels())
    }

    @Test fun loadPresetsRoundTripAndOnlyTouchLoadFields() {
        val f = EngineFeatures(flashAttention = true, kvQuant = true, batchSize = true)
        val base = ModelOverrides(temperature = 0.5f, systemPrompt = "x")
        for (p in LoadPreset.entries) {
            val o = p.applyTo(base, f)
            assertEquals(p, LoadPreset.match(o, f))
            assertEquals(0.5f, o.temperature); assertEquals("x", o.systemPrompt)
        }
        assertEquals(LoadPreset.DEFAULT, LoadPreset.match(ModelOverrides(), f))
        assertNull(LoadPreset.match(ModelOverrides(contextLength = 3000), f)) // 自定义
        assertEquals(2048, LoadPreset.SAVER.applyTo(ModelOverrides(), f).contextLength)
        assertEquals(1, LoadPreset.SAVER.applyTo(ModelOverrides(), f).kvCacheType)
        // no KV quant without Flash Attention support
        assertNull(LoadPreset.LONG.applyTo(ModelOverrides(), EngineFeatures(kvQuant = true)).kvCacheType)
        // fixed-context models keep their context
        assertNull(LoadPreset.LONG.applyTo(ModelOverrides(), EngineFeatures(fixedContext = 4096)).contextLength)
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
        assertTrue(com.iris.localai.ui.components.MatteGlass.TINT_ALPHA in 0.70f..0.85f)
        assertTrue(com.iris.localai.ui.components.MatteGlass.HAIRLINE_DP in 0.5f..1f)
        assertTrue(com.iris.localai.ui.components.MatteGlass.BLUR_DP <= 16f)
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
        assertTrue(g.contains("versionCode = 239"))
        assertTrue(g.contains("versionName = \"2.5.0-beta\""))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta"))
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

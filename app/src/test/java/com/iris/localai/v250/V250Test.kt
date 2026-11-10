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
        assertTrue(src("app/src/main/java/com/iris/localai/ui/MainActivity.kt").contains("AboutPage(onBack = pop, onDeveloper = { push(Route.DEVELOPER) }"))
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
        // 2.5.0-beta9: shorter chip
        assertEquals("思考", GenerationText.liveChip(0))
        assertEquals("5字", GenerationText.liveChip(5))
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
        assertTrue(g.contains("versionCode = 247"))
        assertTrue(g.contains("versionName = \"2.5.0-beta9\""))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta2"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta3"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta4"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta5（2026-11-09，测试版，未在真机测试）"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta6（2026-11-10，测试版，未在真机测试）"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta7（2026-11-10，测试版，未在真机测试）"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta8（2026-11-10，测试版，未在真机测试）"))
        assertTrue(src("CHANGELOG.md").contains("## 2.5.0 Beta9（2026-11-11，测试版，未在真机测试）"))
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

    // ---- Beta5 ----
    @Test fun fileToolsAreOneRow() {
        val ui = com.iris.localai.ui.agent.AgentText
        val names = ui.listedTools().map { it.name }
        for (n in listOf("read_file", "write_file", "search_local", "memory", "activate")) assertFalse(n, n in names)
        assertEquals(listOf("read_file", "write_file", "search_local"), ui.FILE_TOOLS.map { it.name })
        val d = com.iris.localai.agent.AgentSettings()
        assertTrue(ui.filesOn(d))
        assertEquals(com.iris.localai.agent.Permission.ASK, ui.filesPermission(d)) // default: write asks
        val off = ui.withFilesOn(d, false)
        assertFalse(ui.filesOn(off)); ui.FILE_TOOLS.forEach { assertFalse(off.isEnabled(it)) }
        // 「本地搜索」 alone still on → the row stays on (never hides a tool that can run)
        assertTrue(ui.filesOn(off.copy(enabled = off.enabled + ("search_local" to true))))
        val deny = ui.withFilesPermission(d, com.iris.localai.agent.Permission.DENY)
        assertEquals(com.iris.localai.agent.Permission.DENY, ui.filesPermission(deny))
        val allow = ui.withFilesPermission(deny, com.iris.localai.agent.Permission.ALLOW)
        assertEquals(com.iris.localai.agent.Permission.ALLOW, ui.filesPermission(allow))
        val ask = ui.withFilesPermission(allow, com.iris.localai.agent.Permission.ASK)
        assertEquals(com.iris.localai.agent.Permission.ALLOW, ask.permission(com.iris.localai.agent.ReadFileTool))
        assertEquals(com.iris.localai.agent.Permission.ASK, ask.permission(com.iris.localai.agent.WriteFileTool))
        // per-tool keys stay (old backups import unchanged)
        assertEquals(setOf("read_file", "write_file", "search_local"), ask.permissions.keys)
        val page = src("app/src/main/java/com/iris/localai/ui/agent/AgentSettingsPage.kt")
        assertTrue(page.contains("AgentText.FILES_LABEL")); assertTrue(page.contains("AgentText.listedTools()"))
    }

    @Test fun memorySwitchIsOnContextMemoryPage() {
        val page = src("app/src/main/java/com/iris/localai/ui/settings/ContextMemoryPage.kt")
        assertTrue(page.contains("ContextMemoryText.MEMORY_SWITCH"))
        assertFalse(com.iris.localai.ui.agent.AgentText.listedTools().any { it.name == com.iris.localai.agent.MemoryTool.name })
        val t = com.iris.localai.ui.settings.ContextMemoryText
        val d = com.iris.localai.agent.AgentSettings()
        assertTrue(t.memoryOn(true, d)); assertFalse(t.memoryOn(false, d))
        val off = t.withMemoryTool(d, false)
        assertFalse(t.memoryOn(true, off)); assertTrue(t.memoryOn(true, t.withMemoryTool(off, true)))
    }

    @Test fun keepAliveIsRemoved() {
        val java = File(root(), "app/src/main/java/com/iris/localai")
        assertFalse(File(java, "ui/agent/A11yKeepAliveCard.kt").exists())
        assertFalse(File(java, "agent/gui/android/A11yKeepAlive.kt").exists())
        assertFalse(src("app/src/main/java/com/iris/localai/ui/agent/GuiAgentSection.kt").contains("A11yKeepAliveCard("))
        val m = src("app/src/main/AndroidManifest.xml")
        assertFalse(m.contains("A11yBootReceiver")); assertFalse(m.contains("ShizukuProvider")); assertFalse(m.contains("RECEIVE_BOOT_COMPLETED"))
        assertFalse(src("app/build.gradle.kts").contains("shizuku"))
        assertFalse(java.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.any { it.readText().contains("A11yKeepAlive") })
        // the 2.1.9 pre-task self-enable stays
        assertTrue(m.contains("WRITE_SECURE_SETTINGS"))
    }

    @Test fun memoryAndKnowledgeAreRoutes() {
        val main = src("app/src/main/java/com/iris/localai/ui/MainActivity.kt")
        assertTrue(main.contains("Route.MEMORY_LIST -> com.iris.localai.ui.agent.MemoryListPage(onBack = pop)"))
        assertTrue(main.contains("Route.KNOWLEDGE -> com.iris.localai.ui.agent.KnowledgePage(onBack = pop"))
        assertTrue(main.contains("onOpenList = { push(Route.MEMORY_LIST) }, onOpenKnowledge = { push(Route.KNOWLEDGE) }"))
        val list = src("app/src/main/java/com/iris/localai/ui/agent/MemoryListPage.kt")
        assertTrue(list.contains("padding(horizontal = IrisDimens.groupGutter)"))
    }

    @Test fun cloneVoiceChoiceIsExclusive() {
        // VoiceChoice 是文件内 internal，测试按源码检查
        val vc = src("app/src/main/java/com/iris/localai/ui/settings/CloneVoiceSection.kt") +
            src("app/src/main/java/com/iris/localai/ui/settings/CustomTtsSection.kt") +
            src("app/src/main/java/com/iris/localai/ui/settings/VoiceAndApiPages.kt") +
            src("app/src/main/java/com/iris/localai/ui/settings/SettingsViewModel.kt")
        assertTrue(vc.contains("object VoiceChoice"))
        assertTrue(vc.contains("fun cloneActive(")); assertTrue(vc.contains("fun pickPack(")); assertTrue(vc.contains("fun pickClone("))
        assertTrue(vc.contains("VoiceChoice.pickPack(it, pack.id)"))
    }

    @Test fun terminalAndSchedulesStayGone() {
        val m = src("app/src/main/AndroidManifest.xml")
        assertFalse(m.contains("proot", ignoreCase = true)); assertFalse(m.contains("com.iridis.terminal"))
        val java = File(root(), "app/src/main/java")
        assertFalse(File(java, "com/iris/localai/agent/shell").exists())
        assertFalse(java.walkTopDown().any { it.name == "RunCommand.kt" })
    }

    // ---- Beta6 ----
    @Test fun mcpAndKbEditorAreRoutes() {
        // KbEditorNav / Route 是 main 内对象，测试按源码检查
        val main = src("app/src/main/java/com/iris/localai/ui/MainActivity.kt")
        assertTrue(main.contains("MCP, KB_EDITOR,"))
        assertTrue(main.contains("onOpenMcp = { push(Route.MCP) }"))
        assertTrue(main.contains("Route.MCP -> com.iris.localai.ui.agent.McpPage(onBack = pop)"))
        assertTrue(main.contains("onOpenEditor = { push(Route.KB_EDITOR) }"))
        assertTrue(main.contains("Route.KB_EDITOR -> com.iris.localai.ui.agent.KbEditorRoute(onBack = pop)"))
        val kb = src("app/src/main/java/com/iris/localai/ui/agent/KnowledgePage.kt")
        assertTrue(kb.contains("object KbEditorNav"))
        assertTrue(kb.contains("BackHandler(enabled = handleBack, onBack = onClose)"))
        val mcp = src("app/src/main/java/com/iris/localai/ui/agent/McpPage.kt")
        assertTrue(mcp.contains("padding(horizontal = IrisDimens.groupGutter)"))
    }

    @Test fun licensesAreRoutes() {
        val main = src("app/src/main/java/com/iris/localai/ui/MainActivity.kt")
        assertTrue(main.contains("LICENSES, LICENSE_TEXT }"))
        assertTrue(main.contains("onOpenLicenses = { push(Route.LICENSES) }"))
        assertTrue(main.contains("LicensesPage(onBack = pop, onOpenFile = { push(Route.LICENSE_TEXT) })"))
        assertTrue(main.contains("LicenseTextRoute(onBack = pop)"))
        val lic = src("app/src/main/java/com/iris/localai/ui/settings/LicensesPage.kt")
        assertTrue(lic.contains("object LicenseNav"))
        assertTrue(lic.contains("if (onOpenFile == null) BackHandler(onBack = onBack)"))
        val about = src("app/src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        assertTrue(about.contains("if (showLicenses && onOpenLicenses == null)"))
    }

    private fun epub(entries: List<Pair<String, String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            for ((n, t) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test fun epubFollowsSpine() {
        val k = com.iris.localai.knowledge.KbFormats
        assertTrue(k.supported("书.epub"))
        assertTrue(k.maxBytes("a.epub", 4_000_000L) > 4_000_000L)
        assertEquals(4_000_000L, k.maxBytes("a.txt", 4_000_000L))
        val book = epub(listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
            "OEBPS/content.opf" to "<package><manifest><item id=\"c2\" href=\"Text/ch%202.xhtml\" media-type=\"application/xhtml+xml\"/><item href=\"Text/ch1.xhtml\" id=\"c1\"/></manifest><spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>",
            "OEBPS/Text/ch 2.xhtml" to "<html><body><p>第二章 乙</p></body></html>",
            "OEBPS/Text/ch1.xhtml" to "<?xml version=\"1.0\"?><html><head><style>p{}</style></head><body><h1>第一章</h1><p>甲</p></body></html>",
        ))
        val t = k.text("书.epub", book)!!
        assertTrue(t, t.contains("第一章") && t.contains("第二章"))
        assertTrue(t.indexOf("第一章") < t.indexOf("第二章"))
        assertFalse(t.contains("<p>"))
        assertEquals(null, k.text("坏.epub", "not a zip".toByteArray()))
        assertEquals(listOf("OEBPS/Text/a.xhtml"), com.iris.localai.knowledge.EpubText.spine("OEBPS/content.opf", "<item id='a' href='Text/a.xhtml'/><itemref idref='a'/>"))
        assertEquals("OEBPS/b.xhtml", com.iris.localai.knowledge.EpubText.resolve("OEBPS/Text", "../b.xhtml#x"))
    }

    @Test fun memoryPreflightAsksBeforeLoad() {
        val em = src("app/src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(em.contains("suspend fun preflight(model: LocalModel): MemoryPreflight.Verdict = withContext(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(em.contains("withContext(kotlinx.coroutines.Dispatchers.IO) { GgufQuant.of("))
        val ms = src("app/src/main/java/com/iris/localai/ui/models/ModelsScreen.kt")
        assertTrue(ms.contains("fun MemoryLoadDialog("))
        assertTrue(ms.contains("MemoryWarnHost(vm)"))
        val chat = src("app/src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertTrue(chat.contains("c.engine.preflight(model)"))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/chat/ChatScreen.kt").contains("MemoryLoadDialog("))
        assertTrue(src("app/src/main/res/values/strings.xml").contains("name=\"v250_mem_load\""))
    }

    @Test fun deviceRecommendation() {
        val d = com.iris.localai.data.DeviceRecommend
        val gib = 1024L * 1024 * 1024
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.FLAGSHIP, d.chip("QTI", "SM8750"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.MID, d.chip("QTI", "SM7675"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.ENTRY, d.chip("QTI", "SM6450"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.FLAGSHIP, d.chip("Mediatek", "MT6991"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.MID, d.chip("Mediatek", "MT6897"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Chip.ENTRY, d.chip("Mediatek", "MT6789"))
        assertEquals(com.iris.localai.data.DeviceRecommend.Vendor.MEDIATEK, d.vendor("Mediatek", "MT6991"))
        assertEquals(12, d.ramGb(11L * gib + gib / 5))
        assertEquals("taobao-mnn/Qwen3.5-9B-MNN", d.pickId(15L * gib, com.iris.localai.data.DeviceRecommend.Chip.FLAGSHIP))
        assertEquals("taobao-mnn/Qwen3.5-4B-MNN", d.pickId(11L * gib, com.iris.localai.data.DeviceRecommend.Chip.MID))
        assertEquals("taobao-mnn/Qwen3.5-2B-MNN", d.pickId(7L * gib + gib / 2, com.iris.localai.data.DeviceRecommend.Chip.MID))
        assertEquals("taobao-mnn/Qwen3.5-0.8B-MNN", d.pickId(4L * gib, com.iris.localai.data.DeviceRecommend.Chip.FLAGSHIP))
        assertEquals(null, d.pickId(0, com.iris.localai.data.DeviceRecommend.Chip.UNKNOWN))
        val dl = src("app/src/main/java/com/iris/localai/ui/models/DownloadModelsScreen.kt")
        assertTrue(dl.contains("item(key = \"for-device\""))
        val cat = src("app/src/main/java/com/iris/localai/data/RecommendedCatalog.kt")
        listOf("Qwen3.5-9B-MNN", "Qwen3.5-4B-MNN", "Qwen3.5-2B-MNN", "Qwen3.5-0.8B-MNN").forEach { assertTrue(it, cat.contains("\"$it\"")) }
    }

    // ---- Beta7 ----
    @Test fun phoneToolDeviceActions() {
        val p = src("app/src/main/java/com/iris/localai/agent/tools/Phone.kt")
        for (m in listOf("fun flashlight(mode: String): String", "fun volume(stream: String?, percent: Int?): String", "fun ringerMode(mode: String?): String",
            "fun brightness(percent: Int?): String", "fun openSettings(page: String, pkg: String?): String", "fun battery(): String")) assertTrue(m, p.contains(m))
        assertTrue(p.contains("flashlight|volume|ringer|brightness|settings|battery"))
        assertTrue(p.contains("cm.setTorchMode(id, target)"))
        assertTrue(p.contains("CameraManager.TorchCallback()"))
        assertTrue(p.contains("isNotificationPolicyAccessGranted"))
        assertTrue(p.contains("Settings.System.canWrite(ctx)"))
        assertTrue(p.contains("Settings.ACTION_MANAGE_WRITE_SETTINGS"))
        for (a in listOf("ACTION_WIFI_SETTINGS", "ACTION_BLUETOOTH_SETTINGS", "ACTION_LOCATION_SOURCE_SETTINGS", "ACTION_AIRPLANE_MODE_SETTINGS",
            "ACTION_DISPLAY_SETTINGS", "ACTION_POWER_USAGE_SUMMARY", "ACTION_APPLICATION_DETAILS_SETTINGS")) assertTrue(a, p.contains(a))
        assertTrue(p.contains("BatteryManager.BATTERY_PROPERTY_CAPACITY"))
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.WRITE_SETTINGS"))
        assertTrue(manifest.contains("android.permission.ACCESS_NOTIFICATION_POLICY"))
        assertFalse(manifest.contains("android.permission.CAMERA\""))
        // 纯函数（PhoneTool 为 public）
        assertEquals("on", com.iris.localai.agent.PhoneTool.torchMode("开"))
        assertEquals("toggle", com.iris.localai.agent.PhoneTool.torchMode("toggle"))
        assertEquals("ring", com.iris.localai.agent.PhoneTool.volumeStream("铃声"))
        assertEquals("silent", com.iris.localai.agent.PhoneTool.ringerMode("静音"))
        assertEquals("wifi", com.iris.localai.agent.PhoneTool.settingsPage("Wi-Fi"))
        assertEquals("airplane", com.iris.localai.agent.PhoneTool.settingsPage("飞行模式"))
        assertNull(com.iris.localai.agent.PhoneTool.settingsPage("nope"))
    }

    @Test fun fullLogExport() {
        val ui = src("app/src/main/java/com/iris/localai/ui/agent/AgentExtras.kt")
        assertTrue(ui.contains("R.string.v250_fulllog_title"))
        assertTrue(ui.contains("FullLogExport.writeZip("))
        assertTrue(ui.contains("withContext(Dispatchers.IO)"))
        assertTrue(ui.contains("R.string.agent_609e15")) // 原「导出日志」行仍在
        val ex = src("app/src/main/java/com/iris/localai/util/io/FullLogExport.kt")
        assertFalse(ex.contains("apiKey"))
        assertTrue(ex.contains("NOT_PERSISTED"))
        for (lang in listOf("values", "values-en", "values-de", "values-es", "values-hi", "values-ja", "values-zh-rTW")) {
            val x = src("app/src/main/res/$lang/strings.xml")
            for (k in listOf("v250_fulllog_title", "v250_fulllog_sub", "v250_fulllog_all", "v250_fulllog_all_sub", "v250_fulllog_desc", "v250_fulllog_done"))
                assertTrue("$lang/$k", x.contains("name=\"$k\""))
        }
        val e = com.iris.localai.util.FullLogExport
        assertFalse(e.redact("key sk-abcdefghijklmnopqrstuvwxyz end").contains("abcdefghijklmnop"))
        assertFalse(e.redact("Authorization: Bearer abcdefghijklmnopqrstuv").contains("abcdefghijklmnop"))
        val conv = com.iris.localai.data.Conversation(title = "t", messages = listOf(
            com.iris.localai.data.ChatMessage(role = com.iris.localai.data.Role.USER, content = "你好", createdAt = 1_000L),
            com.iris.localai.data.ChatMessage(role = com.iris.localai.data.Role.ASSISTANT, content = "<think>想一想</think>答案", createdAt = 2_000L, modelName = "m.gguf",
                agentSteps = listOf(com.iris.localai.agent.AgentStep(tool = "phone", args = "{\"action\":\"battery\"}", result = "80%", status = com.iris.localai.agent.StepStatus.OK)))))
        val out = e.formatConversation(conv, java.time.ZoneOffset.UTC)
        for (t in listOf("USER", "你好", "ASSISTANT", "model: m.gguf", "想一想", "答案", "tool call 1: phone", "battery", "80%", "1970-01-01 00:00:02")) assertTrue(t, out.contains(t))
        val zip = java.io.ByteArrayOutputStream()
        e.writeZip(zip, "h", "log", listOf(conv), java.time.ZoneOffset.UTC)
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip.toByteArray())).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertEquals(listOf("README.txt", "app-log.txt", "conversations/001-t.txt"), names)
    }

    // ---- Beta8 ----
    @Test fun elite5IsHtpV81() {
        val d = com.iris.localai.imagegen.DeviceProbe
        assertEquals(81, d.htpArch("SM8850"))
        assertEquals(81, d.htpArch("sm8850-ac"))
        assertEquals(79, d.htpArch("SM8750"))
        assertEquals(75, d.htpArch("SM8650P"))
        assertEquals(73, d.htpArch("SM8550"))
        assertNull(d.htpArch("SM8450"))
        assertTrue(com.iris.localai.imagegen.ImageRules.sdqListed(81))
        val c = com.iris.localai.imagegen.ImageCatalog
        val te81 = c.anythingV5(81).files.first { it.isZipSlice && it.path.endsWith("text_encoder.slice") }
        assertTrue(te81.url.endsWith("snapdragon_8_elite_gen5_for_galaxy.zip"))
        assertEquals(124_443_624L, te81.size)
        assertTrue(c.anythingV5(79).files.first { it.isZipSlice && it.path.endsWith("text_encoder.slice") }.url.endsWith("snapdragon_8_elite_for_galaxy.zip"))
        val sd81 = c.sd15Qnn(81).files.last(); val sd79 = c.sd15Qnn(79).files.last()
        assertTrue(sd81.url.endsWith("snapdragon_8_elite_gen5_for_galaxy.zip")); assertEquals(710_771_252L, sd81.size)
        assertTrue(sd79.url.endsWith("snapdragon_8_elite_for_galaxy.zip")); assertEquals(709_785_222L, sd79.size)
        assertEquals(c.sd15Qnn(null).files, c.sd15Qnn(79).files)
        assertTrue(src("app/src/main/java/com/iris/localai/imagegen/DeviceProbe.kt").contains("\"SM8850\" to 81"))
    }

    @Test fun llamaCompatLibraryChoice() {
        val f = com.iris.localai.engine.llama.CpuFeatures
        assertEquals("iris_llama", f.llamaLibraryFor(setOf("fp", "asimd", "asimddp", "asimdhp", "i8mm")))
        assertEquals("iris_llama_compat", f.llamaLibraryFor(setOf("fp", "asimd", "asimddp", "asimdhp")))
        assertNull(f.llamaLibraryFor(setOf("fp", "asimd", "asimdhp")))
        assertNull(f.llamaLibraryFor(setOf("fp", "asimd", "asimddp")))
        assertEquals("iris_llama", f.llamaLibraryFor(emptySet()))
        val ln = src("app/src/main/java/com/iris/localai/engine/llama/LlamaNative.kt")
        assertTrue(ln.contains("System.loadLibrary(lib)"))
        assertFalse(ln.substringAfter("fun missingForLlama()").substringBefore("fun missingForI8mm()").contains("\"i8mm\""))
        // the other i8mm builds keep the old check
        for (p in listOf("imagegen/SdNative.kt", "imagegen/SdqNative.kt", "engine/llama/LlamaHtp.kt")) {
            val t = src("app/src/main/java/com/iris/localai/$p")
            assertTrue(p, t.contains("CpuFeatures.missingForI8mm()")); assertFalse(p, t.contains("missingForLlama()"))
        }
        val cm = src("app/src/main/cpp/CMakeLists.txt")
        assertTrue(cm.contains("set(IRIS_CPU_ARM_ARCH \"armv8.2-a+dotprod+i8mm+fp16\" CACHE STRING"))
        assertTrue(cm.contains("set(GGML_CPU_ARM_ARCH \"\${IRIS_CPU_ARM_ARCH}\" CACHE STRING \"\" FORCE)"))
        // prebuilt-native/ is git-ignored: check only where the prebuilt libraries are present (local release build)
        if (File(root(), "prebuilt-native/arm64-v8a/libiris_llama.so").isFile)
            assertTrue(File(root(), "prebuilt-native/arm64-v8a/libiris_llama_compat.so").length() > 1_000_000L)
    }

    @Test fun downloadResumesWhileProgressing() = kotlinx.coroutines.runBlocking<Unit> {
        val ds = com.iris.localai.data.DownloadSources
        val dir = kotlin.io.path.createTempDirectory("v250b8").toFile(); val target = File(dir, "big.gguf")
        val a = com.iris.localai.data.SourceUrl(com.iris.localai.data.DownloadSource.MODELSCOPE, "https://modelscope.cn/x")
        val b = com.iris.localai.data.SourceUrl(com.iris.localai.data.DownloadSource.HF_MIRROR, "https://hf-mirror.com/x")
        var callsA = 0; var callsB = 0
        // every try writes 3 bytes then stalls: 10 tries on the same source finish 30 bytes
        ds.fetchAny(listOf(a, b), target, 30, {}, fetch = { u, t, _, _ ->
            val part = com.iris.localai.util.HttpDownload.partFile(t)
            if ("modelscope" in u) callsA++ else callsB++
            part.appendBytes(ByteArray(3))
            if (part.length() >= 30) part.renameTo(t) else throw java.net.SocketTimeoutException("Read timed out")
        }, retryDelayMs = 0)
        assertEquals(30L, target.length()); assertEquals(10, callsA); assertEquals(0, callsB)
        // no progress: two tries, then the next source (as before)
        target.delete(); callsA = 0; callsB = 0
        ds.fetchAny(listOf(a, b), target, 5, {}, fetch = { u, t, _, _ ->
            if ("modelscope" in u) { callsA++; throw java.net.SocketTimeoutException("Read timed out") }
            callsB++; t.writeBytes(ByteArray(5))
        }, retryDelayMs = 0)
        assertEquals(2, callsA); assertEquals(1, callsB)
        // progress every time but never done: the per-source cap stops it
        target.delete(); com.iris.localai.util.HttpDownload.partFile(target).delete(); callsA = 0
        try {
            ds.fetchAny(listOf(a), target, 1_000, {}, fetch = { _, t, _, _ ->
                callsA++; com.iris.localai.util.HttpDownload.partFile(t).appendBytes(ByteArray(1)); throw java.io.IOException("reset")
            }, retryDelayMs = 0)
            org.junit.Assert.fail()
        } catch (e: java.io.IOException) { assertEquals(ds.MAX_ATTEMPTS_PER_SOURCE, callsA) }
        assertEquals(1500L, ds.retryDelay(1500, 1)); assertEquals(30_000L, ds.retryDelay(1500, 25))
        dir.deleteRecursively()
    }
}

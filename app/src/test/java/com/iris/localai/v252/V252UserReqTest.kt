package com.iris.localai.v252

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.2 用户需求（源码断言）：① 新手引导（首次一次、设置可重播、视觉规则、7 语言）；② 网页抓取正文提取；
 * ③ 生图种子不回写；④ 模型导入与下载同一存储位置；CHANGELOG / 版本。纯源码读取。
 */
class V252UserReqTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val main = "app/src/main/java/com/iris/localai"
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")

    // ---- ① 新手引导 ----
    // 2.5.3: the five-page guide (OnboardingScreen, onboarding_done, ob252_*) was replaced by the spotlight
    // tour (ui/tour/Tour.kt, tour253_done, tour253_*); these assert the replacement keeps the 2.5.2 rules.
    @Test fun onboardingFlagAndGate() {
        val m = src("$main/data/Models.kt")
        assertTrue(m.contains("@kotlinx.serialization.SerialName(\"tour253_done\")"))
        assertTrue(m.contains("val tour253Done: Boolean = false,"))
        assertFalse(m.contains("onboardingDone"))
        val a = src("$main/ui/MainActivity.kt")
        assertTrue(a.contains("TourLogic.shouldShow(settings.welcomeAccepted, settings.tour253Done)"))
        assertTrue(a.contains("it.copy(tour253Done = true)"))
        // 欢迎页（免责声明）仍在前：引导只在 accepted 分支里
        assertTrue(a.indexOf("WelcomeScreen(") > a.indexOf("TourLogic.shouldShow("))
        assertFalse(File(root(), "$main/ui/welcome/OnboardingScreen.kt").exists())
    }

    @Test fun onboardingReplayRowInAboutGroup() {
        val s = src("$main/ui/settings/SettingsScreens.kt")
        val about = s.indexOf("{ onOpen(SettingsPage.ABOUT) }")
        val row = s.indexOf("R.string.tour253_replay)")
        assertTrue(about > 0 && row > about && row - about < 700)
        assertTrue(s.contains("vm.update { it.copy(tour253Done = false) }"))
        // 不新增分段控件
        assertFalse(s.substring(about, row).contains("Segmented"))
    }

    @Test fun onboardingVisualRules() {
        val o = src("$main/ui/tour/Tour.kt")
        for (bad in listOf("Brush", "Gradient", "blur(", "Segmented", "GlassSurface", "IrisCard(", "glass(")) assertFalse(bad, o.contains(bad))
        assertTrue(o.contains(".background(c.surface, IrisRadius.mediumShape)"))
        // 灰字（textSecondary）只有一处：步骤计数，单行
        assertEquals(1, Regex("c\\.textSecondary").findAll(o).count())
        assertTrue(Regex("color = c\\.textSecondary,\\s*maxLines = 1").containsMatchIn(o))
        assertFalse(o.contains("textTertiary"))
        // 单一强调色：只有脉冲环用 c.accent，按钮走 IrisButton
        assertEquals(1, Regex("c\\.accent\\b").findAll(o).count())
    }

    @Test fun onboardingStringsSevenLanguages() {
        val keys = listOf("skip", "next", "prev", "done", "counter", "replay", "replay_sub").map { "tour253_$it" } +
            listOf("ig252_seed_hint", "ig252_random", "ig252_last_seed", "ig252_fix_seed")
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/$k", x.contains("name=\"$k\""))
            assertFalse("$d still has ob252_*", x.contains("name=\"ob252_"))
            assertTrue("$d/ig252_last_seed", Regex("name=\"ig252_last_seed\">[^<]*%1\\\$s").containsMatchIn(x))
        }
    }

    // ---- ② 网页抓取 ----
    @Test fun webFetchUsesReadableAndCharset() {
        val t = src("$main/agent/tools/Tools.kt")
        assertTrue(t.contains("HtmlText.readable(r.body)"))
        assertTrue(t.contains("WebText.decode(r.body, type)"))
        assertFalse(t.contains("substringAfter(\"charset=\""))
        assertTrue(t.contains("lo = WebText.cutIndex(text, lo)"))
        assertTrue(t.contains("template|iframe|object|canvas|math|select"))
        val w = src("$main/agent/web/WebSearch.kt")
        assertTrue(w.contains("fun realUrl(u: String): String = WebText.unwrapRedirect(u)"))
        assertTrue(w.contains("fun ddgRealUrl(href: String): String = WebText.unwrapRedirect(href)"))
        assertFalse(w.contains(".take(SNIPPET_CHARS)"))
        assertFalse(w.contains("NUM_ENTITY"))
        assertFalse(src("$main/agent/web/BrowserSearch.kt").contains(".take(SearchText.SNIPPET_CHARS"))
    }

    // ---- ③ 生图种子 ----
    @Test fun seedNotWrittenBack() {
        val vm = src("$main/ui/imagegen/ImageGenViewModel.kt")
        assertFalse(vm.contains("seed = req.seed.toString()"))
        assertTrue(vm.contains("seed = com.iris.localai.imagegen.ImageSeed.pick(seed),"))
        assertTrue(vm.contains("lastSeed = req.seed,"))
        val sc = src("$main/ui/imagegen/ImageGenScreen.kt")
        assertTrue(sc.contains("R.string.ig252_last_seed, used.toString()"))
        assertTrue(sc.contains("ImageSeed.isRandom(s.seed)"))
        assertTrue(sc.contains("it.copy(seed = \"\")"))
    }

    // ---- ④ 模型导入存储位置 ----
    @Test fun importAndDownloadShareOneResolver() {
        val repo = src("$main/data/ModelRepository.kt")
        assertTrue(repo.contains("fun location(): ModelDirs.Location = ModelStorage.location(privateDir)"))
        assertTrue(repo.contains("val modelsDir: File get() = location().dir"))
        // 导入（单文件 / 文件夹）都写 modelsDir，与 MnnHub 下载相同
        val imp = repo.substring(repo.indexOf("suspend fun import(uri: Uri"), repo.indexOf("private fun sanitizeFileName"))
        assertTrue(imp.contains("val modelsDir = this@ModelRepository.modelsDir"))
        assertFalse(imp.contains("getExternalFilesDir") || imp.contains("filesDir"))
        val folder = repo.substring(repo.indexOf("suspend fun importFolder("))
        assertFalse(folder.substring(0, folder.indexOf("\n    }\n")).contains("getExternalFilesDir"))
        assertTrue(src("$main/data/MnnHub.kt").contains("repo.modelsDir"))
        // 全部模型类存储都只经 ModelStorage 解析
        val st = src("$main/data/ModelStorage.kt")
        assertTrue(st.contains("followStorage = ModelDirs.followsStorage("))
        for (f in listOf("imagegen/ImageGenRepository.kt", "voice/VoiceModelStore.kt", "translate/TranslatePacks.kt")) {
            val s = src("$main/$f")
            assertTrue(f, s.contains("ModelStorage.location("))
            assertFalse(f, s.contains("ModelDirs.resolve("))
        }
        assertTrue(src("$main/IrisApp.kt").contains("ModelStorage.storageRoot = { agent.appRoot }"))
    }

    // ---- 版本 / CHANGELOG ----
    @Test fun versionAndChangelog() {
        val g = src("app/build.gradle.kts")
        // 2.5.3: exact version asserted by V253Test; 2.5.2 only requires not lower.
        assertTrue(Regex("versionCode = (\\d+)").find(g)!!.groupValues[1].toInt() >= 251)
        val c = src("CHANGELOG.md")
        val sec = c.substring(c.indexOf("## 2.5.2"), c.indexOf("## 2.5.1（"))
        assertEquals("**本版未在真机测试。**", sec.lines()[1])
        for (w in listOf("新手引导", "Cookie", "charset", "DuckDuckGo", "种子", "下载/IRIDIS/models")) assertTrue(w, sec.contains(w))
    }

    @Test fun htmlPreviewFullscreenAndErrors() {
        val v = src("app/src/main/java/com/iris/localai/ui/markdown/RichViews.kt")
        assertTrue(v.contains("DialogWindowProvider"))
        assertTrue(v.contains("setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)"))
        val r = src("app/src/main/java/com/iris/localai/ui/markdown/RichContent.kt")
        assertTrue(r.contains("(importMap ?: \"\") + ERROR_OVERLAY"))
        assertTrue(r.contains("unhandledrejection") && r.contains("WebGL unavailable"))
    }
}

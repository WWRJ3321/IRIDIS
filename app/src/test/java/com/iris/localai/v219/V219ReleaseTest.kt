package com.iris.localai.v219

import com.iris.localai.data.AppSettings
import com.iris.localai.files.FileViewStyle
import com.iris.localai.ui.settings.LicenseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.9: feat/files — 「文件」页 + optional 「档案模式」 (design inspiration LBEILC/RhineLabUI, MIT). */
class V219ReleaseTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()
    private fun root(p: String) = listOf(File(p), File("../$p")).first { it.exists() }

    @Test fun version() {
        val gradle = listOf("app/build.gradle.kts", "build.gradle.kts").map { File(it) }.first { it.exists() }.readText()
        assertTrue(gradle.contains("versionCode = 187"))
        assertTrue(gradle.contains("versionName = \"2.1.9\""))
    }

    @Test fun filesPageAndArchiveModeWired() {
        assertEquals("LIST", AppSettings().fileViewStyle)                 // archive mode is opt-in
        assertEquals(FileViewStyle.LIST, FileViewStyle.parse(null))
        assertEquals(FileViewStyle.LIST, FileViewStyle.parse("archive"))   // 「档案」 shelved
        assertEquals(FileViewStyle.CARDS, FileViewStyle.parse("cards"))
        assertFalse(src("ui/files/FilesScreen.kt").contains("ArchiveFileView"))
        assertTrue(src("ui/files/FilesScreen.kt").contains("FileViewStyle.CARDS -> CardStackFileView(state, vm)"))
        assertTrue(src("ui/MainActivity.kt").contains("Route.APP_FILES -> com.iris.localai.ui.files.AppFilesPage("))
        // pure Compose: no web views, no new fonts / audio
        for (f in listOf("ui/files/CardStackFileView.kt")) {
            val view = src(f)
            for (bad in listOf("WebView", "MiSans", "Novecento", "R.raw", "R.font")) assertFalse(bad, view.contains(bad))
        }
    }

    @Test fun rhineLabUiCredited() {
        val screens = src("ui/settings/SettingsScreens.kt")
        val openSource = screens.substringAfter("internal val OPEN_SOURCE = listOf(").substringBefore("\n)\n")
        assertTrue(openSource.contains("\"RhineLabUI\" to \"MIT · Copyright (c) 2026 LBEILC · 设计灵感"))
        val index = LicenseIndex.parse(file("src/main/assets/licenses/index.json").readText())
        val entry = index.first { it.name.startsWith("RhineLabUI") }
        assertEquals("MIT", entry.license)
        assertEquals("https://github.com/LBEILC/RhineLabUI", entry.url)
        val text = file("src/main/assets/licenses/${entry.file}").readText()
        assertTrue(text.contains("Copyright (c) 2026 LBEILC"))
        assertTrue(text.contains("MIT License"))
        // every bundled license entry points at a file that exists
        for (e in index) assertTrue("missing ${e.file}", file("src/main/assets/licenses/${e.file}").isFile)
    }

    @Test fun docsExist() {
        val doc = root("docs/changelog/PROGRESS-2.1.9.md").readText()
        assertTrue(doc.contains("档案模式"))
        assertTrue(doc.contains("RhineLabUI"))
        val cl = root("CHANGELOG.md").readText()
        assertTrue(cl.contains("## 2.1.9"))
        assertTrue(cl.contains("新增「文件」页"))
        assertTrue(cl.contains("设计灵感 LBEILC/RhineLabUI，MIT"))
        assertTrue(cl.contains("视图菜单（列表 / 卡片）"))
        assertTrue(doc.contains("「档案」视图下线"))
        assertTrue(doc.contains("档案模式 重做"))
        assertTrue(cl.indexOf("## 2.1.9") < cl.indexOf("## 2.1.8"))
    }
}

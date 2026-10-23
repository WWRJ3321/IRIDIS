package com.iris.localai.files

import com.iris.localai.agent.StorageLayout
import com.iris.localai.data.AppSettings
import com.iris.localai.data.sanitized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 2.2 「文件」 page: sorting, paths / breadcrumbs, sizes, names, protection of the app's folders. */
class FilesLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun e(name: String, dir: Boolean = false, size: Long = 0, modified: Long = 0, children: Int = 0, system: SystemFolder? = null) =
        FileEntry(name, name, dir, size, modified, children, system)

    // ---------------------------------------------------------------- sorting

    @Test fun nameSortFoldersFirstCaseInsensitive() {
        val list = listOf(e("b.txt"), e("A.md"), e("zeta", dir = true), e("Alpha", dir = true), e("c.gguf"))
        assertEquals(listOf("Alpha", "zeta", "A.md", "b.txt", "c.gguf"), FilesSorting.sort(list, FileSort.NAME, true).map { it.name })
        assertEquals(listOf("zeta", "Alpha", "c.gguf", "b.txt", "A.md"), FilesSorting.sort(list, FileSort.NAME, false).map { it.name })
    }

    @Test fun timeAndSizeSorts() {
        val list = listOf(e("old", size = 10, modified = 1), e("new", size = 5, modified = 3), e("mid", size = 99, modified = 2))
        assertEquals(listOf("new", "mid", "old"), FilesSorting.sort(list, FileSort.TIME, false).map { it.name })
        assertEquals(listOf("old", "mid", "new"), FilesSorting.sort(list, FileSort.TIME, true).map { it.name })
        assertEquals(listOf("mid", "old", "new"), FilesSorting.sort(list, FileSort.SIZE, false).map { it.name })
        // equal keys fall back to the name
        val ties = listOf(e("b", size = 1), e("a", size = 1))
        assertEquals(listOf("a", "b"), FilesSorting.sort(ties, FileSort.SIZE, true).map { it.name })
    }

    @Test fun systemFoldersPinnedInFixedOrderWhateverTheSort() {
        val list = listOf(
            e("notes", dir = true, modified = 99), e("workspace", dir = true, system = SystemFolder.WORKSPACE),
            e("a.txt", size = 1_000_000), e("models", dir = true, system = SystemFolder.MODELS),
            e("config", dir = true, system = SystemFolder.CONFIG), e("voice", dir = true, system = SystemFolder.VOICE),
        )
        for (s in FileSort.entries) for (asc in listOf(true, false)) {
            val names = FilesSorting.sort(list, s, asc).map { it.name }
            assertEquals("$s $asc", listOf("models", "voice", "config", "workspace"), names.take(4))
            assertEquals(listOf("notes", "a.txt"), names.drop(4))
        }
    }

    @Test fun systemFolderOrderFollowsReservedList() {
        assertEquals(StorageLayout.RESERVED, SystemFolder.entries.map { it.dir })
        assertEquals(listOf("模型", "语音", "技能", "知识库", "配置", "工作区"), SystemFolder.entries.map { it.label })
        assertEquals(SystemFolder.MODELS, SystemFolder.of("Models"))
        assertNull(SystemFolder.of("modelsx"))
    }

    @Test fun tappingSortTogglesDirection() {
        assertEquals(FileSort.NAME to false, FilesSorting.next(FileSort.NAME, true, FileSort.NAME))
        assertEquals(FileSort.TIME to false, FilesSorting.next(FileSort.NAME, true, FileSort.TIME)) // newest first
        assertEquals(FileSort.SIZE to false, FilesSorting.next(FileSort.TIME, true, FileSort.SIZE)) // largest first
        assertEquals(FileSort.NAME to true, FilesSorting.next(FileSort.SIZE, false, FileSort.NAME))
    }

    // ---------------------------------------------------------------- paths / breadcrumbs

    @Test fun normalizeAndEscape() {
        assertEquals("a/b/c", FilesPaths.normalize("a//b/./c/"))
        assertEquals("a/b", FilesPaths.normalize("/a\\b"))
        assertEquals("", FilesPaths.normalize(""))
        assertNull(FilesPaths.normalize("../x"))
        assertNull(FilesPaths.normalize("a/../../b"))
        assertNull(FilesPaths.normalize("a/.."))
        assertEquals("a", FilesPaths.parent("a/b"))
        assertEquals("", FilesPaths.parent("a"))
        assertEquals("", FilesPaths.parent(""))
        assertEquals("a/b", FilesPaths.join("a", "b"))
        assertEquals("b", FilesPaths.join("", "b"))
        assertEquals(0, FilesPaths.depth(""))
        assertEquals(2, FilesPaths.depth("models/x.gguf"))
    }

    @Test fun crumbsUseSystemLabelsOnlyAtTheTop() {
        assertEquals(listOf(Crumb("IRIDIS", "")), FilesPaths.crumbs(""))
        assertEquals(
            listOf(Crumb("IRIDIS", ""), Crumb("模型", "models"), Crumb("qwen", "models/qwen")),
            FilesPaths.crumbs("models/qwen"),
        )
        // a folder called "voice" deeper down keeps its own name
        assertEquals(listOf("IRIDIS", "工作区", "voice"), FilesPaths.crumbs("workspace/voice").map { it.label })
        assertEquals(listOf("IRIDIS", "a", "b"), FilesPaths.crumbs("a//b/").map { it.label })
        assertEquals(listOf("IRIDIS"), FilesPaths.crumbs("../etc").map { it.label })
    }

    @Test fun resolveStaysInsideTheRoot() {
        val root = tmp.newFolder("IRIDIS")
        File(root, "models").mkdirs()
        val outside = tmp.newFolder("secret")
        assertEquals(File(root, "models").canonicalFile, FilesPaths.resolve(root, "models")!!.canonicalFile)
        assertEquals(root.canonicalFile, FilesPaths.resolve(root, "")!!.canonicalFile)
        assertNull(FilesPaths.resolve(root, "../secret"))
        assertNull(FilesPaths.resolve(root, "models/../../secret"))
        // a sibling whose name starts with the root's name is not inside
        val twin = tmp.newFolder("IRIDIS-twin")
        assertFalse(FilesPaths.isInside(root, twin))
        // a symbolic link that points out of the root does not count as inside
        val link = File(root, "link")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()); true }.getOrDefault(false)
        if (linked) {
            assertNull(FilesPaths.resolve(root, "link"))
            assertNull(FilesPaths.resolve(root, "link/x"))
        }
    }

    // ---------------------------------------------------------------- sizes

    @Test fun sizeFormatting() {
        assertEquals("0 B", FileSizes.format(0))
        assertEquals("0 B", FileSizes.format(-5))
        assertEquals("1023 B", FileSizes.format(1023))
        assertEquals("1.0 KB", FileSizes.format(1024))
        assertEquals("1.5 KB", FileSizes.format(1536))
        assertEquals("12.3 MB", FileSizes.format((12.3 * 1024 * 1024).toLong()))
        assertEquals("4.20 GB", FileSizes.format((4.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2.00 TB", FileSizes.format(2L * 1024 * 1024 * 1024 * 1024))
    }

    // ---------------------------------------------------------------- names

    @Test fun nameValidation() {
        val sib = listOf("a.txt", "Notes")
        assertNull(FileNames.validate("b.txt", "workspace", sib))
        assertNull(FileNames.validate("  新建文件夹  ", "workspace", sib))
        assertEquals(NameProblem.EMPTY, FileNames.validate("   ", "", sib))
        assertEquals(NameProblem.TOO_LONG, FileNames.validate("x".repeat(121), "", sib))
        assertNull(FileNames.validate("x".repeat(120), "w", sib))
        for (bad in listOf("a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\u0000b", "a\nb"))
            assertEquals(bad, NameProblem.ILLEGAL, FileNames.validate(bad, "", sib))
        assertEquals(NameProblem.DOTS, FileNames.validate(".", "", sib))
        assertEquals(NameProblem.DOTS, FileNames.validate("..", "", sib))
        assertEquals(NameProblem.DOTS, FileNames.validate(".hidden", "", sib))
        assertEquals(NameProblem.EXISTS, FileNames.validate("A.TXT", "w", sib)) // case-insensitive storage
        assertEquals(NameProblem.EXISTS, FileNames.validate("notes", "w", sib))
        // renaming to itself with another case is allowed
        assertNull(FileNames.validate("notes", "w", sib, current = "Notes"))
    }

    @Test fun systemNamesAreReservedOnlyAtTheRoot() {
        assertEquals(NameProblem.RESERVED, FileNames.validate("models", "", emptyList()))
        assertEquals(NameProblem.RESERVED, FileNames.validate("Workspace", "", emptyList()))
        assertNull(FileNames.validate("models", "workspace", emptyList()))
    }

    // ---------------------------------------------------------------- protection

    @Test fun rootAndSystemFoldersAreFixed() {
        for (rel in listOf("", "/", "models", "voice", "skills", "knowledge", "config", "workspace", "MODELS", "workspace/")) {
            assertTrue(rel, FileProtection.isFixed(rel))
            assertFalse(rel, FileProtection.canRename(rel))
            assertFalse(rel, FileProtection.canDelete(rel))
            assertFalse(rel, FileProtection.canMove(rel))
        }
        // escaping paths are never actionable
        assertFalse(FileProtection.canDelete("../x"))
        // user content, including a folder named like a system one deeper down, is not fixed
        for (rel in listOf("notes", "models/qwen.gguf", "workspace/models", "voice/kokoro", "a.txt")) {
            assertTrue(rel, FileProtection.canRename(rel))
            assertTrue(rel, FileProtection.canDelete(rel))
            assertTrue(rel, FileProtection.canMove(rel))
        }
    }

    @Test fun modelAndVoiceFilesWarn() {
        assertTrue(FileProtection.modelWarning("models/qwen.gguf"))
        assertTrue(FileProtection.modelWarning("models/qwen/config.json"))
        assertTrue(FileProtection.modelWarning("voice/kokoro"))
        assertTrue(FileProtection.modelWarning("Models/x"))
        assertFalse(FileProtection.modelWarning("models"))
        assertFalse(FileProtection.modelWarning("workspace/models/x"))
        assertFalse(FileProtection.modelWarning("skills/a/SKILL.md"))
        assertTrue(FileProtection.warningText("models/x")!!.contains("模型"))
        assertTrue(FileProtection.warningText("voice/x")!!.contains("语音"))
        assertNull(FileProtection.warningText("config/backup.zip"))
    }

    @Test fun hiddenEntries() {
        assertTrue(FileProtection.isHidden(".nomedia"))
        assertTrue(FileProtection.isHidden(StorageLayout.MARKER))
        assertTrue(FileProtection.isHidden(".iris-probe"))
        assertFalse(FileProtection.isHidden("notes.md"))
    }

    // ---------------------------------------------------------------- io on a temp root

    private fun sampleRoot(): File {
        val root = tmp.newFolder("IRIDIS")
        listOf("models", "voice", "workspace", "config").forEach { File(root, it).mkdirs() }
        File(root, "models/qwen.gguf").writeText("w")
        File(root, "models/.nomedia").writeText("")
        File(root, StorageLayout.MARKER).writeText("2")
        File(root, "readme.md").writeText("# hi")
        File(root, "workspace/models").mkdirs()
        return root
    }

    @Test fun listTagsSystemFoldersAtRootOnlyAndHidesBookkeeping() {
        val root = sampleRoot()
        val top = FilesIo.list(root, "")!!
        assertEquals(setOf("models", "voice", "workspace", "config", "readme.md"), top.map { it.name }.toSet())
        assertEquals(SystemFolder.MODELS, top.first { it.name == "models" }.system)
        assertNull(top.first { it.name == "readme.md" }.system)
        assertEquals(1, top.first { it.name == "models" }.childCount) // .nomedia hidden
        val ws = FilesIo.list(root, "workspace")!!
        assertNull(ws.single().system) // workspace/models is a plain folder
        assertEquals("workspace/models", ws.single().relPath)
        assertNull(FilesIo.list(root, "../"))
        assertNull(FilesIo.list(root, "missing"))
        val sorted = FilesSorting.sort(top, FileSort.NAME, true).map { it.name }
        assertEquals(listOf("models", "voice", "config", "workspace", "readme.md"), sorted)
    }

    @Test fun ioRefusesSystemFoldersAndRoot() {
        val root = sampleRoot()
        assertTrue(FilesIo.delete(root, "models") is FilesIo.Result.Failed)
        assertTrue(FilesIo.delete(root, "") is FilesIo.Result.Failed)
        assertTrue(FilesIo.delete(root, "Workspace") is FilesIo.Result.Failed)
        assertTrue(FilesIo.rename(root, "voice", "v2") is FilesIo.Result.Failed)
        assertTrue(File(root, "models/qwen.gguf").exists())
        assertTrue(File(root, "voice").isDirectory)
        // renaming a user file to a system name at the root is refused
        assertTrue(FilesIo.rename(root, "readme.md", "skills") is FilesIo.Result.Failed)
        assertTrue(FilesIo.mkdir(root, "", "knowledge").let { it is FilesIo.Result.Failed })
        assertTrue(FilesIo.delete(root, "../IRIDIS") is FilesIo.Result.Failed)
    }

    @Test fun ioRenameDeleteMkdirInsideTheRoot() {
        val root = sampleRoot()
        assertTrue(FilesIo.mkdir(root, "workspace", "新建") is FilesIo.Result.Ok)
        assertTrue(File(root, "workspace/新建").isDirectory)
        assertTrue(FilesIo.mkdir(root, "workspace", "新建") is FilesIo.Result.Failed) // exists
        assertTrue(FilesIo.mkdir(root, "workspace", "a/b") is FilesIo.Result.Failed)
        assertTrue(FilesIo.rename(root, "models/qwen.gguf", "qwen2.gguf") is FilesIo.Result.Ok)
        assertTrue(File(root, "models/qwen2.gguf").exists())
        assertTrue(FilesIo.rename(root, "readme.md", "README.md") is FilesIo.Result.Ok) // case-only
        assertEquals(listOf("README.md"), root.list()!!.filter { it.endsWith(".md", true) })
        assertTrue(FilesIo.rename(root, "README.md", "../out.md") is FilesIo.Result.Failed)
        assertTrue(FilesIo.delete(root, "workspace/新建") is FilesIo.Result.Ok)
        assertFalse(File(root, "workspace/新建").exists())
        assertTrue(FilesIo.delete(root, "models/qwen2.gguf") is FilesIo.Result.Ok)
        assertTrue(File(root, "models").isDirectory)
    }

    @Test fun deleteNeverFollowsSymlinksOut() {
        val root = sampleRoot()
        val outside = tmp.newFolder("keep").apply { File(this, "precious.txt").writeText("x") }
        val link = File(root, "workspace/link")
        val ok = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()); true }.getOrDefault(false)
        if (!ok) return
        val dir = File(root, "workspace/dir").apply { mkdirs() }
        Files.createSymbolicLink(File(dir, "inner").toPath(), outside.toPath())
        assertTrue(FilesIo.delete(root, "workspace/dir") is FilesIo.Result.Ok)
        assertTrue(FilesIo.delete(root, "workspace/link") is FilesIo.Result.Ok)
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(outside, "precious.txt").exists())
    }

    // ---------------------------------------------------------------- preview / settings

    @Test fun previewRules() {
        assertTrue(FilePreview.canPreview(e("a.md", size = 10)))
        assertTrue(FilePreview.isMarkdown("MD"))
        assertFalse(FilePreview.canPreview(e("a.md", size = FilePreview.MAX_BYTES + 1)))
        assertFalse(FilePreview.canPreview(e("m.gguf", size = 10)))
        assertFalse(FilePreview.canPreview(e("dir.md", dir = true)))
        assertTrue(FilePreview.looksText("你好".toByteArray()))
        assertFalse(FilePreview.looksText(byteArrayOf(1, 0, 2)))
        assertEquals("text/markdown", FilePreview.mimeFallback("md"))
        assertEquals("*/*", FilePreview.mimeFallback("gguf"))
        val f = tmp.newFile("big.txt").apply { writeBytes(ByteArray(300 * 1024) { 'a'.code.toByte() }) }
        assertEquals(FilePreview.MAX_BYTES.toInt(), FilePreview.readHead(f).size)
    }

    @Test fun fileViewStyleSetting() {
        assertEquals("LIST", AppSettings().fileViewStyle)
        assertEquals(FileViewStyle.LIST, FileViewStyle.parse(null))
        assertEquals(FileViewStyle.ARCHIVE, FileViewStyle.parse(" archive "))
        assertEquals(FileViewStyle.LIST, FileViewStyle.parse("cards"))
        assertEquals("LIST", AppSettings(fileViewStyle = "bogus").sanitized().fileViewStyle)
        assertEquals("ARCHIVE", AppSettings(fileViewStyle = "archive").sanitized().fileViewStyle)
    }

    // ---------------------------------------------------------------- wiring (source checks)

    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()

    @Test fun wiring() {
        val main = src("ui/MainActivity.kt")
        assertTrue(main.contains("Route.APP_FILES -> com.iris.localai.ui.files.AppFilesPage("))
        assertTrue(main.contains("onOpenFiles = { push(Route.APP_FILES) }"))
        val screen = src("ui/files/FilesScreen.kt")
        // part B: 「档案模式」 renders the archive deck with the same state / actions / dialogs
        assertTrue(screen.contains("FileViewStyle.ARCHIVE -> ArchiveFileView(state, vm)"))
        assertFalse(screen.contains("TODO(part B)"))
        val archive = src("ui/files/ArchiveFileView.kt")
        assertTrue(archive.contains("fun ArchiveFileView(state: FilesState, actions: FilesActions"))
        assertTrue(archive.contains("LocalReduceMotion.current"))      // reduced motion: no tilt / wave / decrypt
        assertTrue(archive.contains("actions.canRename(e)") && archive.contains("actions.canDelete(e)"))
        assertTrue(archive.contains("SystemBadge()"))
        assertTrue(archive.contains("FilePreview.canPreview(e)"))      // in-app preview reused
        assertTrue(archive.contains("ArchiveMath.halfWindow(n)"))      // only the visible window is composed
        assertTrue(archive.contains("Build.VERSION_CODES.S"))           // blur only on API 31+
        assertTrue(archive.contains("IrisIcons.") && !archive.contains("material.icons"))
        assertFalse(archive.contains("tween(") || archive.contains("durationMillis"))
        for (banned in listOf("WebView", "WebGL", "MiSans", "Novecento", "Arknights", "明日方舟", "Rhodes", "罗德岛", "MediaPlayer", "SoundPool"))
            assertFalse("archive view must not use $banned", archive.contains(banned))
        assertTrue(screen.contains("\"档案模式\""))
        assertFalse(screen.contains("material.icons"))
        assertFalse(src("ui/files/FilesViewModel.kt").contains("material.icons"))
        val paths = file("src/main/res/xml/shared_paths.xml").readText()
        assertTrue(paths.contains("path=\"Download/IRIDIS/\""))
        // no new permission: the manifest's permission list is unchanged
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertEquals(13, Regex("<uses-permission ").findAll(manifest).count())
        assertNotNull(Regex("MANAGE_EXTERNAL_STORAGE").find(manifest))
    }
}

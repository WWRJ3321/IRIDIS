package com.iris.localai.agent

import com.iris.localai.agent.workspace.LegacyWorkspace
import com.iris.localai.agent.workspace.LegacyWorkspace.Action
import com.iris.localai.agent.workspace.MigrationResult
import com.iris.localai.agent.workspace.WorkspaceMigration
import com.iris.localai.util.io.Backup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 2.1.0: package com.iridis, Download/IRIDIS migration, backup manifest. */
class V210RenameTest {
    private fun tmp(): File = Files.createTempDirectory("iridis").toFile()
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun entries(bytes: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return m
    }

    private val prefsWithKey =
        "<map><string name=\"settings\">{&quot;temperature&quot;:0.7,&quot;apiKey&quot;:&quot;sk-secret-123&quot;,&quot;port&quot;:8080}</string></map>"

    private fun dataRoot(): File = tmp().apply {
        File(this, "files/conversations").mkdirs()
        File(this, "files/conversations/a.json").writeText("{\"id\":\"a\"}")
        File(this, "shared_prefs").mkdirs()
        File(this, Backup.SETTINGS_PREFS).writeText(prefsWithKey)
    }

    // ---- applicationId ----


    // ---- backup manifest / zip ----

    @Test fun roundtripWithManifestAndSkills() {
        val root = dataRoot()
        val ws = tmp().apply { File(this, "skills/demo").mkdirs(); File(this, "skills/demo/SKILL.md").writeText("# demo") }
        val out = ByteArrayOutputStream()
        val n = Backup.export(root, out, "2.1.0", workspace = ws, now = 1_700_000_000_000)
        assertEquals(3, n)
        val bytes = out.toByteArray()
        val e = entries(bytes)
        assertTrue(Backup.MARKER in e)
        val m = Backup.parseManifest(e.getValue(Backup.MANIFEST))
        assertEquals(Backup.FORMAT, m.format)
        assertEquals(Backup.FORMAT_VERSION, m.formatVersion)
        assertEquals("2.1.0", m.appVersion)
        assertEquals(1_700_000_000_000, m.createdAt)
        assertFalse(m.includesApiKey)
        assertTrue(Backup.SKILLS_PREFIX + "demo/SKILL.md" in e)

        val info = Backup.inspect(bytes.inputStream())
        assertFalse(info.legacy)
        assertEquals(3, info.dataFiles)
        assertEquals("2.1.0", info.manifest.appVersion)

        val root2 = tmp(); val ws2 = tmp()
        assertEquals(3, Backup.restore(root2, bytes.inputStream(), workspace = ws2))
        assertEquals("{\"id\":\"a\"}", File(root2, "files/conversations/a.json").readText())
        assertEquals("# demo", File(ws2, "skills/demo/SKILL.md").readText())
    }

    @Test fun apiKeyStrippedByDefaultAndKeptOnRequest() {
        val root = dataRoot()
        val off = ByteArrayOutputStream().also { Backup.export(root, it, "2.1.0") }.toByteArray()
        val prefs = entries(off).getValue(Backup.SETTINGS_PREFS)
        assertFalse(prefs.contains("sk-secret-123"))
        assertTrue(prefs.contains("&quot;apiKey&quot;:&quot;&quot;"))
        assertTrue(prefs.contains("&quot;port&quot;:8080"))
        val on = ByteArrayOutputStream().also { Backup.export(root, it, "2.1.0", includeApiKey = true) }.toByteArray()
        assertTrue(entries(on).getValue(Backup.SETTINGS_PREFS).contains("sk-secret-123"))
        assertTrue(Backup.inspect(on.inputStream()).manifest.includesApiKey)
        assertEquals("{\"apiKey\":\"\"}", Backup.stripApiKey("{\"apiKey\":\"abc\"}"))
    }

    @Test fun oldMarkerOnlyBackupAccepted() {
        val bytes = zipOf(Backup.MARKER to "IRIS LocalAI backup\nversion=2.0.0\n", "files/conversations/b.json" to "{}")
        val info = Backup.inspect(bytes.inputStream())
        assertTrue(info.legacy)
        assertEquals(1, info.manifest.formatVersion)
        assertEquals(1, info.dataFiles)
        val root = tmp()
        assertEquals(1, Backup.restore(root, bytes.inputStream()))
        assertTrue(File(root, "files/conversations/b.json").isFile)
    }

    @Test fun tooNewFormatRejected() {
        val m = Backup.manifestJson(Backup.Manifest(formatVersion = Backup.FORMAT_VERSION + 1))
        val bytes = zipOf(Backup.MARKER to "x", Backup.MANIFEST to m, "files/conversations/c.json" to "{}")
        assertThrows(IllegalArgumentException::class.java) { Backup.inspect(bytes.inputStream()) }
        val root = tmp()
        assertThrows(IllegalArgumentException::class.java) { Backup.restore(root, bytes.inputStream()) }
        assertFalse(File(root, "files/conversations/c.json").exists())
        assertThrows(IllegalArgumentException::class.java) { Backup.parseManifest("{\"format\":\"other\",\"formatVersion\":1}") }
        assertThrows(IllegalArgumentException::class.java) { Backup.parseManifest("not json") }
    }

    @Test fun nonBackupZipRejected() {
        assertThrows(IllegalArgumentException::class.java) { Backup.inspect(zipOf("random.txt" to "x").inputStream()) }
    }

    // ---- LegacyWorkspace.decide ----

    @Test fun decideCases() {
        assertEquals(Action.NONE, LegacyWorkspace.decide(inDownloads = false, oldExists = true, oldVisibleFiles = 5, allFilesAccess = true, newHasFiles = false))
        assertEquals(Action.NONE, LegacyWorkspace.decide(inDownloads = true, oldExists = false, oldVisibleFiles = 0, allFilesAccess = false, newHasFiles = false))
        assertEquals(Action.HINT, LegacyWorkspace.decide(inDownloads = true, oldExists = true, oldVisibleFiles = 5, allFilesAccess = true, newHasFiles = true))
        assertEquals(Action.MIGRATE, LegacyWorkspace.decide(inDownloads = true, oldExists = true, oldVisibleFiles = 3, allFilesAccess = false, newHasFiles = false))
        assertEquals(Action.DELETE_OLD, LegacyWorkspace.decide(inDownloads = true, oldExists = true, oldVisibleFiles = 0, allFilesAccess = true, newHasFiles = false))
        assertEquals(Action.HINT, LegacyWorkspace.decide(inDownloads = true, oldExists = true, oldVisibleFiles = 0, allFilesAccess = false, newHasFiles = false))
        assertTrue(LegacyWorkspace.HINT.contains("IRISLocalAI") && LegacyWorkspace.HINT.contains("IRIDIS"))
    }

    @Test fun afterMigrateHint() {
        assertNull(LegacyWorkspace.afterMigrate(MigrationResult(2, 0, 0, 0), oldStillHasFiles = false))
        assertEquals(LegacyWorkspace.HINT, LegacyWorkspace.afterMigrate(MigrationResult(1, 0, 1, 0), oldStillHasFiles = true))
        assertEquals(LegacyWorkspace.HINT, LegacyWorkspace.afterMigrate(MigrationResult(2, 0, 0, 0), oldStillHasFiles = true))
    }

    @Test fun legacyFolderMovedIntoIridis() {
        val downloads = tmp()
        val old = LegacyWorkspace.legacyRoot(downloads).apply { File(this, "notes").mkdirs(); File(this, "notes/a.txt").writeText("hello") }
        val new = File(downloads, "IRIDIS").apply { mkdirs() }
        val r = WorkspaceMigration.migrate(old, new)
        assertTrue(r.ok)
        assertEquals("hello", File(new, "notes/a.txt").readText())
        assertFalse(old.walkTopDown().any { it.isFile })
        assertNull(LegacyWorkspace.afterMigrate(r, old.walkTopDown().any { it.isFile }))
    }
}

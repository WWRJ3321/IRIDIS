package com.iris.localai.agent

import com.iris.localai.agent.workspace.SafPaths
import com.iris.localai.agent.tools.SkillCatalog
import com.iris.localai.agent.tools.SkillTool
import com.iris.localai.agent.workspace.StorageLayout
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.workspace.WorkspaceLocation
import com.iris.localai.agent.workspace.WorkspaceResolver
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.agent.tools.listAll
import com.iris.localai.data.ModelDirs
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.util.io.StorageUsage
import com.iris.localai.voice.VoiceDirs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 2.1.1: Download/IRIDIS = app resources, Download/IRIDIS/workspace = the agent sandbox; one-time move. */
class V211StorageLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.text(s: String): File = apply { parentFile?.mkdirs(); writeText(s) }

    // ---------------------------------------------------------------- resolution

    @Test fun downloadsWorkspaceIsTheSubfolder() {
        val dl = tmp.newFolder("Download")
        val priv = File(tmp.root, "files/workspace")
        val c = WorkspaceResolver.resolve(WorkspaceLocation.DOWNLOADS, dl, "mounted", priv)
        assertEquals(WorkspaceLocation.DOWNLOADS, c.location)
        assertEquals(File(dl, "IRIDIS/workspace"), c.root)
        assertEquals(File(dl, "IRIDIS"), c.appRoot)
        assertTrue(c.root.isDirectory)
        // private mode: skills / knowledge beside (not inside) filesDir/workspace
        val p = WorkspaceResolver.resolve(WorkspaceLocation.PRIVATE, dl, "mounted", priv)
        assertEquals(priv, p.root)
        assertEquals(File(tmp.root, "files/iridis").absoluteFile, p.appRoot.absoluteFile)
        // fallback keeps a private resource root too
        val f = WorkspaceResolver.resolve(WorkspaceLocation.DOWNLOADS, dl, "unmounted", priv)
        assertEquals(priv, f.root)
        assertEquals(p.appRoot.absoluteFile, f.appRoot.absoluteFile)
        // the SAF grant is still the whole Download/IRIDIS tree
        assertTrue(WorkspaceResolver.isWorkspaceTree("primary:Download/IRIDIS"))
        assertFalse(WorkspaceResolver.isWorkspaceTree("primary:Download/IRIDIS/workspace"))
        assertEquals("下载/IRIDIS/workspace", WorkspaceLocation.DOWNLOADS.label)
    }

    @Test fun toolsCannotReachResourceFolders() {
        val dl = tmp.newFolder("Download")
        val c = WorkspaceResolver.resolve(WorkspaceLocation.DOWNLOADS, dl, "mounted", File(tmp.root, "priv"))
        val root = c.appRoot
        File(root, "models/m.gguf").text("w"); File(root, "skills/s/SKILL.md").text("x"); File(root, "config/b.zip").text("z")
        File(root, "voice/p/.complete").text("1"); File(root, "knowledge/k.md").text("k")
        val sb = WorkspaceSandbox(c.root)
        assertEquals(File(c.root, "a.txt").canonicalFile, sb.resolve("a.txt"))
        for (sub in StorageLayout.RESERVED.filter { it != StorageLayout.WORKSPACE }) {
            assertTrue(sub, runCatching { sb.resolve("../$sub/x") }.isFailure)
            assertTrue(sub, runCatching { sb.resolve(File(root, "$sub/x").path) }.isFailure)
        }
        assertTrue(runCatching { sb.resolve(File(root, "models/m.gguf").path) }.isFailure)
        assertFalse(sb.listAll().any { it.first.contains("models") || it.first.contains("SKILL") })
    }

    @Test fun safPathsStayInsideTheBase() {
        assertEquals(listOf("workspace", "a", "b.txt"), SafPaths.join(SafPaths.WORKSPACE, "a/b.txt"))
        assertEquals(listOf("workspace"), SafPaths.join(SafPaths.WORKSPACE, "."))
        assertEquals(listOf("workspace", "x"), SafPaths.join(SafPaths.WORKSPACE, "./x/"))
        assertNull(SafPaths.join(SafPaths.WORKSPACE, "../models/m.gguf"))
        assertNull(SafPaths.join(SafPaths.WORKSPACE, "a/../../config"))
        assertEquals(listOf("knowledge", "k.md"), SafPaths.join(SafPaths.ROOT, "knowledge/k.md"))
    }

    // ---------------------------------------------------------------- migration plan

    @Test fun planMovesEverythingButReservedNames() {
        val plan = StorageLayout.plan(
            listOf("models", "Voice", "skills", "knowledge", "config", "workspace", "notes", "a.txt", ".git", ".iridis-layout-v2", ".iris-probe", ".nomedia", "SKILLS"),
            emptyList(),
        )
        assertEquals(listOf(".git", "a.txt", "notes"), plan.moves.map { it.from })
        assertTrue(plan.moves.none { it.renamed })
        assertTrue(plan.kept.containsAll(listOf("models", "Voice", "workspace", ".nomedia", ".iridis-layout-v2", "SKILLS")))
    }

    @Test fun planNeverOverwritesWorkspaceEntries() {
        val plan = StorageLayout.plan(listOf("a.txt", "Notes", "x"), listOf("A.TXT", "notes", "a（迁移）.txt"))
        val byFrom = plan.moves.associate { it.from to it.to }
        assertEquals("a（迁移）2.txt", byFrom["a.txt"])
        assertEquals("Notes（迁移）", byFrom["Notes"])
        assertEquals("x", byFrom["x"])
        assertEquals(".env（迁移）", StorageLayout.alternative(".env", emptySet()))
        assertEquals("b.tar（迁移）.gz", StorageLayout.alternative("b.tar.gz", emptySet()))
    }

    @Test fun markerOnlyAfterAFullView() {
        val ok = StorageLayout.Result(3, 0, emptyList())
        assertTrue(StorageLayout.complete(ok, fullView = true))
        assertFalse(StorageLayout.complete(ok, fullView = false))
        assertFalse(StorageLayout.complete(StorageLayout.Result(2, 0, listOf("x")), fullView = true))
        // SAF pass re-plans what is left: its failures are final
        val direct = StorageLayout.Result(1, 0, listOf("hidden.txt"))
        assertEquals(StorageLayout.Result(2, 1, emptyList()), StorageLayout.combine(direct, StorageLayout.Result(1, 1, emptyList())))
        assertEquals(direct, StorageLayout.combine(direct, null))
        assertNull(StorageLayout.notice(StorageLayout.Result.NONE))
        assertTrue(StorageLayout.notice(StorageLayout.Result(2, 1, emptyList()))!!.contains("下载/IRIDIS/workspace"))
        assertTrue(StorageLayout.notice(StorageLayout.Result(0, 0, listOf("a")))!!.contains("未完成"))
    }

    // ---------------------------------------------------------------- migration on disk

    @Test fun migrateMovesUserFilesIntoWorkspace() {
        val root = tmp.newFolder("IRIDIS")
        File(root, "models/m.gguf").text("weights")
        File(root, "skills/s/SKILL.md").text("skill")
        File(root, "knowledge/k.md").text("kb")
        File(root, "notes/a.md").text("note")
        File(root, "todo.txt").text("todo")
        File(root, "workspace/todo.txt").text("already here")
        File(root, ".nomedia").text("")
        val r = StorageLayout.migrate(root)
        assertTrue(r.ok)
        assertEquals(2, r.moved); assertEquals(1, r.renamed)
        assertEquals("note", File(root, "workspace/notes/a.md").readText())
        assertEquals("already here", File(root, "workspace/todo.txt").readText())
        assertEquals("todo", File(root, "workspace/todo（迁移）.txt").readText())
        assertEquals("weights", File(root, "models/m.gguf").readText())
        assertEquals("skill", File(root, "skills/s/SKILL.md").readText())
        assertEquals("kb", File(root, "knowledge/k.md").readText())
        assertTrue(File(root, ".nomedia").exists())
        assertFalse(File(root, "notes").exists()); assertFalse(File(root, "todo.txt").exists())
        // marker: written once, a second run has nothing to do
        assertFalse(StorageLayout.markerExists(root))
        assertTrue(StorageLayout.writeMarker(root))
        assertTrue(StorageLayout.markerExists(root))
        assertEquals(StorageLayout.Result.NONE, StorageLayout.migrate(root))
        assertTrue(File(root, StorageLayout.MARKER).exists()) // the marker itself is never moved
    }

    @Test fun migrateCopiesWhenRenameFailsAndKeepsSourceOnFailure() {
        val root = tmp.newFolder("IRIDIS")
        File(root, "dir/x.bin").text("12345")
        File(root, "y.txt").text("y")
        // rename impossible (as across volumes): verified copy + delete
        val r = StorageLayout.migrate(root) { a, b -> StorageLayout.moveTree(NoRename(a), b) }
        assertTrue(r.ok); assertEquals(2, r.moved)
        assertEquals("12345", File(root, "workspace/dir/x.bin").readText())
        assertFalse(File(root, "dir").exists())

        val root2 = tmp.newFolder("IRIDIS2")
        File(root2, "keep.txt").text("k")
        val failed = StorageLayout.migrate(root2) { _, _ -> false }
        assertEquals(listOf("keep.txt"), failed.failed)
        assertEquals("k", File(root2, "keep.txt").readText())
        assertFalse(StorageLayout.complete(failed, fullView = true))
    }

    @Test fun workspaceFileInTheWayBlocksTheMove() {
        val root = tmp.newFolder("IRIDIS")
        File(root, "workspace").text("a file, not a folder")
        File(root, "a.txt").text("a")
        val r = StorageLayout.migrate(root)
        assertEquals(listOf("workspace"), r.failed)
        assertEquals("a", File(root, "a.txt").readText())
    }

    @Test fun privateModeExtractsSkillsAndKnowledge() {
        val ws = tmp.newFolder("files", "workspace")
        val appRoot = File(tmp.root, "files/iridis")
        File(ws, "skills/s/SKILL.md").text("s"); File(ws, "Knowledge/k.md").text("k"); File(ws, "notes.md").text("n")
        assertEquals(listOf("skills", "Knowledge"), StorageLayout.planExtract(ws.list()!!.toList(), emptyList()))
        assertEquals(listOf("Knowledge"), StorageLayout.planExtract(listOf("skills", "Knowledge"), listOf("SKILLS")))
        val r = StorageLayout.extract(ws, appRoot)
        assertTrue(r.ok); assertEquals(2, r.moved)
        assertEquals("s", File(appRoot, "skills/s/SKILL.md").readText())
        assertEquals("k", File(appRoot, "knowledge/k.md").readText())
        assertEquals("n", File(ws, "notes.md").readText())
        assertEquals(StorageLayout.Result.NONE, StorageLayout.extract(ws, appRoot))
    }

    // ---------------------------------------------------------------- consumers

    @Test fun skillsAndKnowledgeComeFromTheResourceRoot() {
        val dl = tmp.newFolder("Download")
        val c = WorkspaceResolver.resolve(WorkspaceLocation.DOWNLOADS, dl, "mounted", File(tmp.root, "priv"))
        File(c.appRoot, "skills/mine/SKILL.md").text("---\nname: mine\ndescription: d\n---\nbody")
        File(c.appRoot, "skills/mine/run.sh").text("echo hi")
        File(c.root, "skills/fake/SKILL.md").text("---\nname: fake\n---\nx") // in the sandbox: not a skill
        val cat = SkillCatalog({ emptyList() }, { c.appRoot })
        assertEquals(listOf("mine"), cat.all().map { it.name })
        val env = ToolEnv(WorkspaceSandbox(c.root), UndoStore(File(tmp.root, "undo")), skills = cat)
        val r = SkillTool.run(buildJsonObject { put("name", "mine") }, env)
        assertTrue(r.output.contains("run.sh") && r.output.contains("body"))
        val f = SkillTool.run(buildJsonObject { put("name", "mine"); put("file", "run.sh") }, env)
        assertTrue(f.ok); assertEquals("echo hi", f.output.trim())
        assertFalse(SkillTool.run(buildJsonObject { put("name", "mine"); put("file", "../../models/m.gguf") }, env).ok)
        assertNull(SkillCatalog.readIn(File(c.appRoot, "skills/mine"), "../../config/x"))

        assertEquals(File(c.appRoot, "knowledge"), KnowledgeBase({ c.appRoot }, File(tmp.root, "idx.json")).dir())
    }

    @Test fun voicePacksFollowTheModelsRule() {
        val dl = tmp.newFolder("Download")
        val priv = tmp.newFolder("voice-private")
        val on = ModelDirs.resolve(true, "mounted", dl, priv, sub = StorageLayout.VOICE)
        assertTrue(on.shared); assertEquals(File(dl, "IRIDIS/voice").absolutePath, on.dir.absolutePath)
        val off = ModelDirs.resolve(false, "mounted", dl, priv, sub = StorageLayout.VOICE)
        assertFalse(off.shared); assertEquals(priv, off.dir)
        // an installed private pack is still found after 全部文件访问 is granted; new ones go to the shared folder
        File(priv, "asr/.complete").text("1")
        assertEquals(File(priv, "asr"), VoiceDirs.packDir(on.listDirs, on.dir, "asr", ".complete"))
        assertEquals(File(on.dir, "tts"), VoiceDirs.packDir(on.listDirs, on.dir, "tts", ".complete"))
        File(on.dir, "asr/.complete").text("1")
        assertEquals(File(on.dir, "asr"), VoiceDirs.packDir(on.listDirs, on.dir, "asr", ".complete"))
        // models keep their folder name
        assertEquals(File(dl, "IRIDIS/models"), ModelDirs.sharedDir(dl))
    }

    @Test fun storageListsSkillsAndKnowledgeOutsideTheWorkspace() {
        val base = tmp.newFolder("app")
        val dl = tmp.newFolder("Download")
        val app = File(dl, "IRIDIS"); val ws = File(app, "workspace")
        File(ws, "a.txt").text("12345")
        File(app, "skills/s/SKILL.md").text("123")
        File(app, "knowledge/k.md").text("1234567")
        fun d(n: String) = File(base, n)
        val p = StorageUsage.Paths(
            roots = listOf(base), modelsDir = d("models"), litertCache = d("litert"), mnnCache = d("mnn"), openclCache = d("opencl"),
            npuRuntime = d("npu"), voiceRoot = d("voice"), voiceClones = d("clones"), workspace = ws, privateWorkspace = d("workspace"),
            undo = d("undo"), memory = d("memory"), alpine = d("alpine"), conversations = d("conversations"), attachments = d("attachments"),
            historyDb = emptyList(), cacheDir = d("cache"), externalCache = null, logFiles = emptyList(), restoreStage = d("restore"),
            skillsDir = File(app, "skills"), knowledgeDir = File(app, "knowledge"),
        )
        val g = StorageUsage.scan(p, emptyList(), emptyList(), 0).group(StorageUsage.Category.WORKSPACE)!!
        assertEquals(5L, g.entry(StorageUsage.Ids.WS_FILES)!!.bytes)
        assertEquals(3L, g.entry(StorageUsage.Ids.WS_SKILLS)!!.bytes)
        assertEquals(7L, g.entry(StorageUsage.Ids.WS_KNOWLEDGE)!!.bytes)
        assertTrue(g.entry(StorageUsage.Ids.WS_SKILLS)!!.external && g.entry(StorageUsage.Ids.WS_KNOWLEDGE)!!.external)
    }

    /** A File whose rename always fails (as across FUSE volumes). */
    private class NoRename(f: File) : File(f.path) {
        override fun renameTo(dest: File): Boolean = false
    }
}

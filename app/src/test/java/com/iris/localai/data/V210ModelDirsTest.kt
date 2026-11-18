package com.iris.localai.data

import com.iris.localai.data.ModelDirs
import com.iris.localai.util.io.StorageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 2.1.0: LLM models in Download/IRIDIS/models with 全部文件访问 (survive uninstall / applicationId change). */
class V210ModelDirsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.write(n: Int): File = apply { parentFile?.mkdirs(); writeBytes(ByteArray(n) { it.toByte() }) }

    @Test fun sharedOnlyWithAllFilesAccessAndMountedStorage() {
        val dl = tmp.newFolder("Download")
        val priv = tmp.newFolder("private")
        val on = ModelDirs.resolve(true, "mounted", dl, priv)
        assertTrue(on.shared)
        assertEquals(File(dl, "IRIDIS/models").absolutePath, on.dir.absolutePath)
        assertTrue(File(on.dir, ".nomedia").exists())
        assertEquals(listOf(on.dir, priv), on.listDirs)

        val off = ModelDirs.resolve(false, "mounted", dl, priv)
        assertFalse(off.shared)
        assertEquals(priv, off.dir)
        assertEquals(File(dl, "IRIDIS/models"), off.sharedDir) // still listed (shared wins on a clash)

        assertFalse(ModelDirs.resolve(true, "unmounted", dl, priv).shared)
        assertEquals(priv, ModelDirs.resolve(true, "mounted", null, priv).dir)
        assertEquals(priv, ModelDirs.resolve(true, "mounted", dl, priv, usable = { false }).dir)
    }

    @Test fun reEvaluatedEachCallSoGrantingLaterSwitches() {
        val dl = tmp.newFolder("Download"); val priv = tmp.newFolder("private")
        var granted = false
        fun loc() = ModelDirs.resolve(granted, "mounted", dl, priv)
        assertEquals(priv, loc().dir)
        granted = true
        assertEquals(ModelDirs.sharedDir(dl), loc().dir)
    }

    @Test fun mergeDedupesByNameSharedWins() {
        val shared = listOf("a.gguf" to "S", "b.litertlm" to "S")
        val priv = listOf("a.gguf" to "P", "c.gguf" to "P")
        val merged = ModelDirs.merge(listOf(shared, priv)) { it.first }
        assertEquals(listOf("a.gguf" to "S", "b.litertlm" to "S", "c.gguf" to "P"), merged)
    }

    @Test fun planMoveSkipsClashesInUsePartsAndHidden() {
        val priv = tmp.newFolder("private"); val shared = tmp.newFolder("shared")
        File(priv, "a.gguf").write(10)
        File(priv, "b.gguf").write(10); File(shared, "b.gguf").write(3)   // clash → skipped
        File(priv, "c.gguf").write(10)                                    // in use → skipped
        File(priv, "d.gguf.part").write(10)
        File(priv, ".nomedia").write(0)
        File(priv, "notes.txt").write(5)                                  // not a model
        File(priv, "Qwen-MNN/llm.mnn").write(20); File(priv, "Qwen-MNN/config.json").write(2)
        val plan = ModelDirs.planMove(priv, shared, { it.name.endsWith(".gguf") || it.isDirectory }, keep = setOf(File(priv, "c.gguf").absolutePath))
        assertEquals(listOf("Qwen-MNN", "a.gguf"), plan.move.map { it.name })
        assertEquals(listOf("b.gguf", "c.gguf"), plan.skipped.map { it.name }.sorted())
    }

    @Test fun moveEntryMovesFilesAndFoldersAndReportsBytes() {
        val priv = tmp.newFolder("private"); val shared = File(tmp.root, "dl/IRIDIS/models")
        val f = File(priv, "a.gguf").write(3000)
        var last = 0L
        val t = ModelDirs.moveEntry(f, shared) { last = it }
        assertFalse(f.exists()); assertEquals(3000, t.length()); assertEquals(3000, last)
        val d = File(priv, "Q"); File(d, "llm.mnn").write(500); File(d, "sub/w.bin").write(700)
        val td = ModelDirs.moveEntry(d, shared)
        assertFalse(d.exists()); assertEquals(1200, ModelDirs.totalBytes(td))
        assertTrue(File(td, "sub/w.bin").isFile)
        assertFalse(File(shared, "Q.part").exists())
    }

    @Test fun moveEntryRefusesExistingTargetAndKeepsSource() {
        val priv = tmp.newFolder("private"); val shared = tmp.newFolder("shared")
        val f = File(priv, "a.gguf").write(10)
        File(shared, "a.gguf").write(1)
        runCatching { ModelDirs.moveEntry(f, shared) }.let { assertTrue(it.isFailure) }
        assertTrue(f.exists()); assertEquals(1, File(shared, "a.gguf").length())
    }

    @Test fun copyPathVerifiesThenDeletesSource() {
        val priv = tmp.newFolder("private"); val shared = tmp.newFolder("shared")
        val d = File(priv, "Q"); File(d, "llm.mnn").write(5000); File(d, "config.json").write(30)
        var last = 0L
        val t = ModelDirs.moveEntry(d, shared, rename = { _, _ -> false }) { last = it }
        assertFalse(d.exists()); assertEquals(5030, ModelDirs.totalBytes(t)); assertEquals(5030, last)
        assertFalse(File(shared, "Q.part").exists())
        val f = File(priv, "a.gguf").write(2048)
        ModelDirs.moveEntry(f, shared, rename = { _, _ -> false })
        assertFalse(f.exists()); assertEquals(2048, File(shared, "a.gguf").length())
    }

    @Test fun cancelledCopyKeepsSourceAndLeavesNoPart() {
        val priv = tmp.newFolder("private"); val shared = tmp.newFolder("shared")
        val f = File(priv, "a.gguf").write(4096)
        val r = runCatching { ModelDirs.moveEntry(f, shared, checkCancel = { throw IllegalStateException("cancel") }, rename = { _, _ -> false }) }
        assertTrue(r.isFailure)
        assertTrue(f.exists()); assertEquals(4096, f.length())
        assertFalse(File(shared, "a.gguf").exists()); assertFalse(File(shared, "a.gguf.part").exists())
    }

    @Test fun storageScanCountsSharedModelsAsExternal() {
        val data = tmp.newFolder("data"); val ext = tmp.newFolder("ext"); val dl = tmp.newFolder("Download")
        val shared = ModelDirs.sharedDir(dl)
        val files = File(data, "files"); val cache = File(data, "cache")
        val p = StorageUsage.Paths(
            roots = listOf(data, ext), modelsDir = File(ext, "models"),
            litertCache = File(files, "litert-cache"), mnnCache = File(cache, "mnn"), openclCache = File(cache, "opencl"),
            npuRuntime = File(files, "npu"), voiceRoot = File(ext, "voice"), voiceClones = File(files, "voice-clones"),
            workspace = File(files, "workspace"), privateWorkspace = File(files, "workspace"), undo = File(files, "agent-undo"),
            memory = File(files, "memory"), alpine = File(files, "alpine"), conversations = File(files, "conversations"),
            attachments = File(files, "attachments"), historyDb = emptyList(), cacheDir = cache, externalCache = null,
            logFiles = emptyList(), restoreStage = File(files, ".restore"), sharedModelsDir = shared,
        )
        val a = StorageUsage.ModelRef("a.gguf", "A", "a.gguf", File(ext, "models/a.gguf").write(1000))
        val b = StorageUsage.ModelRef("b.gguf", "B", "b.gguf", File(shared, "b.gguf").write(4000))
        File(shared, "c.gguf.part").write(70)
        val r = StorageUsage.scan(p, listOf(a, b), emptyList())
        val g = r.group(StorageUsage.Category.MODELS)!!
        assertFalse(g.entry("model:a.gguf")!!.external)
        assertTrue(g.entry("model:b.gguf")!!.external)
        assertEquals(1000, g.appBytes)
        assertEquals(70, g.entry(StorageUsage.Ids.MODEL_LEFTOVER_SHARED)!!.bytes)
        assertEquals(null, g.entry(StorageUsage.Ids.MODEL_LEFTOVER))
    }
}

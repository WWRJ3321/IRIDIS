package com.iris.localai.util

import com.iris.localai.engine.load.ModelCache
import com.iris.localai.util.io.StorageUsage
import com.iris.localai.util.io.StorageUsage.Category
import com.iris.localai.util.io.StorageUsage.Ids
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

/** 1.14.3 设置 › 存储空间: the size scan, category attribution and the clear helpers (temp dirs only). */
class V1143StorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.write(n: Int): File = apply { parentFile?.mkdirs(); writeBytes(ByteArray(n)) }
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").readText()

    /** A fake app: data dir (files/, cache/, databases/) + external files dir; workspace optionally outside. */
    private inner class App(workspaceOutside: Boolean = false) {
        val data: File = tmp.newFolder("data")
        val files = File(data, "files")
        val cache = File(data, "cache")
        val ext: File = tmp.newFolder("ext")
        val outside: File = tmp.newFolder("Download")
        val workspace = if (workspaceOutside) File(outside, "IRISLocalAI") else File(files, "workspace")
        val paths = StorageUsage.Paths(
            roots = listOf(data, ext),
            modelsDir = File(ext, "models"),
            litertCache = File(files, ModelCache.LITERT_DIR),
            mnnCache = File(cache, "mnn"),
            openclCache = File(cache, "opencl"),
            npuRuntime = File(files, "npu"),
            voiceRoot = File(ext, "voice"),
            voiceClones = File(files, "voice-clones"),
            workspace = workspace,
            privateWorkspace = File(files, "workspace"),
            undo = File(files, "agent-undo"),
            memory = File(files, "memory"),
            alpine = File(files, "alpine"),
            conversations = File(files, "conversations"),
            attachments = File(files, "attachments"),
            historyDb = listOf(File(data, "databases/iris_history.db"), File(data, "databases/iris_history.db-wal")),
            cacheDir = cache,
            externalCache = null,
            logFiles = listOf(File(files, "last-crash.txt"), File(ext, "iris_mnn_load.log")),
            restoreStage = File(files, ".restore"),
        )
        val gguf = StorageUsage.ModelRef("a.gguf", "A", "a.gguf", File(ext, "models/a.gguf"))
        val lit = StorageUsage.ModelRef("b.litertlm", "B", "b.litertlm", File(ext, "models/b.litertlm"))
        val mnn = StorageUsage.ModelRef("Qwen-MNN", "Q", "Qwen-MNN", File(ext, "models/Qwen-MNN"))
        val models = listOf(gguf, lit, mnn)
        val voice = StorageUsage.VoiceRef("sense", "SenseVoice", File(ext, "voice/sense"))

        init {
            gguf.path.write(1000)
            lit.path.write(2000)
            File(mnn.path, "llm.mnn").write(3000); File(mnn.path, "config.json").write(10)
            File(ext, "models/c.gguf.part").write(50)                              // unfinished import
            File(files, "litert-cache/b.litertlm_1700000000_2000_mldrift_program_cache.bin").write(400)
            File(files, "litert-cache/gone.litertlm_1700000000_9_mldrift_weight_cache.bin").write(70) // deleted model
            File(files, "litert-cache/.migrated-from-cachedir").write(1)
            File(cache, "mnn/${ModelCache.mnnDirName("Qwen-MNN")}/kv.bin").write(300)
            File(cache, "mnn/deadbeef/kv.bin").write(30)                           // deleted model
            File(cache, "opencl/kernels.bin").write(200)
            File(ext, "voice/sense/model.onnx").write(500)
            File(files, "voice-clones/v1/ref.f32").write(40)
            File(workspace, "notes.md").write(20)
            File(workspace, "skills/pdf/SKILL.md").write(15)
            File(files, "agent-undo/1.bin").write(5)
            File(files, "memory/USER.md").write(7)
            File(files, "alpine/rootfs/bin/busybox").write(900)
            File(files, "conversations/x.json").write(60); File(files, "conversations/index.json").write(5)
            File(files, "attachments/ab-photo.jpg").write(80)
            File(data, "databases/iris_history.db").write(25)
            File(cache, "tmp/export.zip").write(90)
            File(cache, "WebView/Cache/blob").write(11)                             // browser's own: OTHER
            File(files, "last-crash.txt").write(3)
            File(ext, "iris_mnn_load.log").write(4)
            File(data, "shared_prefs/iris_settings_v2.xml").write(9)               // OTHER
        }

        fun scan() = StorageUsage.scan(paths, models, listOf(voice))
    }

    @Test fun usageCountsNestedFilesAndNeverFollowsLinks() {
        val d = tmp.newFolder("tree")
        File(d, "a/b/c.bin").write(10); File(d, "x.bin").write(5)
        val big = tmp.newFolder("elsewhere"); File(big, "huge.bin").write(10_000)
        Files.createSymbolicLink(File(d, "link").toPath(), big.toPath())               // like Alpine's absolute links
        val u = StorageUsage.usage(d)
        assertTrue("link target not counted", u.bytes < 10_000)
        assertEquals(15L, u.bytes - Files.readAttributes(File(d, "link").toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).size())
        assertEquals(3, u.files)
        assertEquals(StorageUsage.Usage.ZERO, StorageUsage.usage(File(d, "missing")))
        assertEquals(StorageUsage.Usage(5, 1), StorageUsage.usage(File(d, "x.bin")))
        // deleteTree removes the link, not what it points at
        StorageUsage.deleteTree(d)
        assertFalse(d.exists())
        assertTrue(File(big, "huge.bin").exists())
    }

    @Test fun scanAttributesEveryCategory() {
        val app = App()
        val r = app.scan()
        val models = r.group(Category.MODELS)!!
        assertEquals(3010L, models.entry(Ids.MODEL_PREFIX + "Qwen-MNN")!!.bytes)   // MNN = whole folder
        assertEquals(1000L, models.entry(Ids.MODEL_PREFIX + "a.gguf")!!.bytes)
        assertEquals(50L, models.entry(Ids.MODEL_LEFTOVER)!!.bytes)
        assertEquals(Ids.MODEL_PREFIX + "Qwen-MNN", models.entries.first().id)    // biggest first

        val cache = r.group(Category.MODEL_CACHE)!!
        assertEquals(400L, cache.entry(Ids.CACHE_PREFIX + "b.litertlm")!!.bytes)
        assertEquals(300L, cache.entry(Ids.CACHE_PREFIX + "Qwen-MNN")!!.bytes)
        assertNull("GGUF keeps no cache", cache.entry(Ids.CACHE_PREFIX + "a.gguf"))
        assertEquals(100L, cache.entry(Ids.CACHE_ORPHAN)!!.bytes)                   // 70 litert + 30 mnn, not the marker
        assertEquals(200L, cache.entry(Ids.CACHE_OPENCL)!!.bytes)

        val voice = r.group(Category.VOICE)!!
        assertEquals(500L, voice.entry(Ids.VOICE_PREFIX + "sense")!!.bytes)
        assertEquals(1, voice.entry(Ids.VOICE_CLONES)!!.count)

        val ws = r.group(Category.WORKSPACE)!!
        assertEquals(20L, ws.entry(Ids.WS_FILES)!!.bytes)
        assertEquals(15L, ws.entry(Ids.WS_SKILLS)!!.bytes)
        assertEquals(1, ws.entry(Ids.WS_SKILLS)!!.count)
        assertNull("same dir as the workspace", ws.entry(Ids.WS_PRIVATE))
        assertEquals(5L, ws.entry(Ids.WS_UNDO)!!.bytes)
        assertEquals(7L, ws.entry(Ids.WS_MEMORY)!!.bytes)

        assertEquals(900L, r.group(Category.TERMINAL)!!.bytes)

        val chats = r.group(Category.CHATS)!!
        assertEquals(1, chats.entry(Ids.CHATS)!!.count)                             // index.json is not a conversation
        assertEquals(65L, chats.entry(Ids.CHATS)!!.bytes)
        assertEquals(80L, chats.entry(Ids.ATTACHMENTS)!!.bytes)
        assertEquals(25L, chats.entry(Ids.HISTORY_INDEX)!!.bytes)

        val logs = r.group(Category.CACHE_LOGS)!!
        assertEquals("mnn / opencl / WebView are not 临时缓存", 90L, logs.entry(Ids.CACHE_FILES)!!.bytes)
        assertEquals(7L, logs.entry(Ids.LOG_FILES)!!.bytes)

        // Total = the roots; 其他 = what no category claimed (prefs, WebView, the LiteRT marker).
        val expectedTotal = StorageUsage.sizeOf(app.data) + StorageUsage.sizeOf(app.ext)
        assertEquals(expectedTotal, r.total)
        assertEquals(9L + 11L + 1L, r.group(Category.OTHER)!!.bytes)
        assertEquals(r.total, r.groups.sumOf { it.appBytes })
    }

    @Test fun conversationCountFromTheRepositoryWins() {
        val r = App().let { StorageUsage.scan(it.paths, it.models, listOf(it.voice), conversationCount = 12) }
        assertEquals(12, r.group(Category.CHATS)!!.entry(Ids.CHATS)!!.count)
    }

    @Test fun workspaceInDownloadIsListedButNotPartOfTheTotal() {
        val app = App(workspaceOutside = true)
        File(app.files, "workspace/old.txt").write(33)                               // pre-1.10 private workspace
        val r = app.scan()
        val ws = r.group(Category.WORKSPACE)!!
        assertTrue(ws.entry(Ids.WS_FILES)!!.external)
        assertTrue(ws.entry(Ids.WS_SKILLS)!!.external)
        assertEquals(33L, ws.entry(Ids.WS_PRIVATE)!!.bytes)
        assertEquals(StorageUsage.sizeOf(app.data) + StorageUsage.sizeOf(app.ext), r.total)
        assertEquals(r.total, r.groups.sumOf { it.appBytes })
        assertTrue(ws.bytes > ws.appBytes)
    }

    @Test fun segmentsAreSharesOfTheTotal() {
        val r = App().scan()
        val segs = StorageUsage.segments(r)
        assertEquals(Category.entries.toList(), segs.map { it.first })
        val sum = segs.sumOf { it.second.toDouble() }
        assertTrue(sum in 0.999..1.0001)
        assertTrue(segs.first { it.first == Category.MODELS }.second > segs.first { it.first == Category.CHATS }.second)
        val empty = StorageUsage.segments(StorageUsage.Report(0, emptyList()))
        assertTrue(empty.all { it.second == 0f })
    }

    @Test fun clearChildrenKeepsTheListedFoldersAndTheDir() {
        val app = App()
        val freed = StorageUsage.clearChildren(app.cache, StorageUsage.CACHE_KEEP)
        assertEquals(90L, freed)
        assertTrue(app.cache.isDirectory)
        assertTrue(File(app.cache, "mnn").exists()); assertTrue(File(app.cache, "opencl").exists()); assertTrue(File(app.cache, "WebView").exists())
        assertFalse(File(app.cache, "tmp").exists())
        assertEquals(0L, StorageUsage.clearChildren(null))
    }

    @Test fun orphanCachesSkipPresentModelsAndMarkers() {
        val app = App()
        File(app.files, "litert-cache/.loading-GPU-b.litertlm").write(1)          // present model's load marker
        File(app.files, "litert-cache/.loading-GPU-gone.litertlm").write(1)       // deleted model's marker
        val orphans = StorageUsage.orphanCaches(app.paths.litertCache, app.paths.mnnCache, app.models.map { it.fileName }).map { it.name }.toSet()
        assertEquals(setOf("gone.litertlm_1700000000_9_mldrift_weight_cache.bin", "deadbeef", ".loading-GPU-gone.litertlm"), orphans)
        // a model whose name merely starts the same is not the owner
        assertFalse(ModelCache.belongs("b.litertlm2_1_2_x", "b.litertlm"))
    }

    @Test fun attachmentCleanupRespectsReferencesAndAge() {
        val dir = tmp.newFolder("att")
        val now = 10_000_000_000L
        val kept = File(dir, "kept.jpg").write(1).apply { setLastModified(now - 2 * StorageUsage.ATTACHMENT_MIN_AGE_MS) }
        val old = File(dir, "old.jpg").write(1).apply { setLastModified(now - 2 * StorageUsage.ATTACHMENT_MIN_AGE_MS) }
        val fresh = File(dir, "fresh.jpg").write(1).apply { setLastModified(now - 1000) }  // maybe still in the composer
        val freshRef = File(dir, "freshRef.jpg").write(1).apply { setLastModified(now - 1000) }
        val refs = setOf(kept.absolutePath, freshRef.absolutePath)
        assertEquals(listOf(old), StorageUsage.orphanAttachments(dir, refs, now))
        assertEquals(setOf(kept, old, freshRef), StorageUsage.removableAttachments(dir, refs, now).toSet())
        assertFalse(StorageUsage.removableAttachments(dir, refs, now).contains(fresh))
    }

    @Test fun isUnderComparesWholeSegments() {
        val root = tmp.newFolder("files")
        assertTrue(StorageUsage.isUnder(File(root, "a/b"), root))
        assertTrue(StorageUsage.isUnder(root, root))
        assertFalse(StorageUsage.isUnder(File(root.path + "2/x"), root))
    }


    @Test fun strongConfirmWord() {
        assertEquals("删除", com.iris.localai.ui.settings.StorageText.WIPE_WORD)
    }
}

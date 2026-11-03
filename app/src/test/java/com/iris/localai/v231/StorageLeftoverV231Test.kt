package com.iris.localai.v231

import com.iris.localai.util.StorageUsage
import com.iris.localai.util.StorageUsage.LeftoverKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.3.1 设置 › 存储空间 › 未完成的导入与其他文件: classification of what sits beside the models. */
class StorageLeftoverV231Test {
    private fun dir(): File = Files.createTempDirectory("st231").toFile()
    private fun File.put(name: String, n: Int) = File(this, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(n)) }
    private fun ref(f: File) = StorageUsage.ModelRef(f.name, f.name, f.name, f)

    @Test fun partialNames() {
        listOf("a.gguf.part", "x.tmp", "Y.TEMP", "z.crdownload", "Qwen3-MNN.part", ".tmp-123").forEach { assertTrue(it, StorageUsage.isPartialName(it)) }
        listOf("a.gguf", "model-mmproj-f16.gguf", "notes.txt").forEach { assertFalse(it, StorageUsage.isPartialName(it)) }
    }

    @Test fun classifiesAndPairs() {
        val d = dir()
        val vl = d.put("Qwen2.5-VL-3B-Q4_K_M.gguf", 100)
        d.put("Qwen2.5-VL-3B-Q4_K_M-mmproj-f16.gguf", 40)        // paired with vl → not a leftover
        val txt = d.put("Llama-3.2-1B-Q4_K_M.gguf", 50)
        d.put("mmproj-other-thing-f16.gguf", 30)                   // no model claims it
        d.put("Big-Model-Q4.gguf.part", 20)
        d.put("Paused-Model.gguf.part", 10)
        d.put("readme.txt", 5)
        File(d, "Qwen3-1.7B-MNN.part").apply { mkdirs() }.put("llm.mnn", 7)
        val models = listOf(ref(vl), ref(txt))
        val list = StorageUsage.leftovers(d, models, protectedParts = setOf("Paused-Model.gguf.part"), shared = false)
        val by = list.associateBy { it.name }
        assertEquals(setOf("mmproj-other-thing-f16.gguf", "Big-Model-Q4.gguf.part", "Paused-Model.gguf.part", "readme.txt", "Qwen3-1.7B-MNN.part"), by.keys)
        assertEquals(LeftoverKind.PROJECTOR, by.getValue("mmproj-other-thing-f16.gguf").kind)
        assertEquals(LeftoverKind.UNKNOWN, by.getValue("readme.txt").kind)
        assertEquals(LeftoverKind.PARTIAL, by.getValue("Qwen3-1.7B-MNN.part").kind)
        assertEquals(7L, by.getValue("Qwen3-1.7B-MNN.part").bytes)
        assertTrue(by.getValue("Paused-Model.gguf.part").active)
        assertFalse(by.getValue("Big-Model-Q4.gguf.part").active)
        // 清理未完成的下载 skips the paused one
        assertEquals(setOf("Big-Model-Q4.gguf.part", "Qwen3-1.7B-MNN.part"), StorageUsage.clearablePartials(list).map { it.name }.toSet())
    }

    @Test fun scanAddsProjectorToModelEntry() {
        val root = dir(); val models = File(root, "models").apply { mkdirs() }
        val vl = models.put("gemma-3-4b-it-Q4_K_M.gguf", 100)
        models.put("gemma-3-4b-it-Q4_K_M-mmproj-F16.gguf", 40)
        models.put("half.gguf.part", 9)
        val p = StorageUsage.Paths(
            roots = listOf(root), modelsDir = models, litertCache = File(root, "l"), mnnCache = File(root, "m"),
            openclCache = File(root, "o"), npuRuntime = File(root, "n"), voiceRoot = File(root, "v"), voiceClones = File(root, "vc"),
            workspace = File(root, "w"), privateWorkspace = File(root, "w"), undo = File(root, "u"), memory = File(root, "me"),
            conversations = File(root, "c"), attachments = File(root, "a"), historyDb = emptyList(), cacheDir = File(root, "cache"),
            externalCache = null, logFiles = emptyList(), restoreStage = File(root, "r"),
        )
        val r = StorageUsage.scan(p, listOf(ref(vl)), emptyList(), 0)
        val g = r.group(StorageUsage.Category.MODELS)!!
        val e = g.entry(StorageUsage.Ids.MODEL_PREFIX + vl.name)!!
        assertEquals(140L, e.bytes); assertTrue(e.withProjector)
        assertEquals(9L, g.entry(StorageUsage.Ids.MODEL_LEFTOVER)!!.bytes)
        assertEquals(listOf("half.gguf.part"), r.leftovers.map { it.name })
    }

    @Test fun splitPartsBelongToModel() {
        val d = dir()
        val m = d.put("Big-Q4_K_M-00001-of-00002.gguf", 10)
        d.put("Big-Q4_K_M-00002-of-00002.gguf", 10)
        assertTrue(StorageUsage.leftovers(d, listOf(ref(m)), emptySet(), false).isEmpty())
    }
}

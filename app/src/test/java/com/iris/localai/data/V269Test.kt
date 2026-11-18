package com.iris.localai.data

import com.iris.localai.data.UpdateNotes
import com.iris.localai.imagegen.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2.6.9: 生图导入模型、GUI 智能体主线程等待修复、精简。 */
class V269Test {
    private fun file(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }
    private fun src(p: String) = file(p).readText()
    private fun root(p: String) = listOf(p, "../$p").map { File(it) }.first { it.exists() }
    private val main = "src/main/java/com/iris/localai/"


    private fun entry(id: String = "imp-0000abcd") = ImportedImage(id, "Anima Harem", ImportArch.ANIMA.name,
        "$id/harem.safetensors", ImportArch.ANIMA.llmPath, ImportArch.ANIMA.vaePath, 30, 4f)

    @Test fun roundTrip() {
        val l = listOf(entry(), entry("imp-00000002"))
        assertEquals(l, ImportedImages.decode(ImportedImages.encode(l)))
    }

    @Test fun decodeDropsUnsafe() {
        val bad = listOf(entry().copy(diffusion = "../x.gguf"), entry().copy(vae = "/sdcard/a.safetensors"), entry().copy(id = "flux2-klein-4b"), entry())
        assertEquals(1, ImportedImages.decode(ImportedImages.encode(bad)).size)
    }

    @Test fun toModel() {
        val m = entry().toModel()
        assertTrue(m.imported)
        assertEquals(Backend.SDCPP, m.backend)
        assertEquals(listOf(ImageCatalog.SD_LIB), m.files)
        assertEquals(SdFiles("imp-0000abcd/harem.safetensors", ImportArch.ANIMA.llmPath, ImportArch.ANIMA.vaePath), m.sdFiles)
        assertTrue(m.tagStyle)
        assertEquals(30, m.defaultSteps)
        assertFalse(ImportArch.Z_IMAGE.tagStyle)
    }

    @Test fun presetsPointAtBuiltInFiles() {
        // 预设复用的文件路径必须与内置模型的 sdFiles 一致（否则「用已下载」找不到）
        assertEquals("${ImageCatalog.SHARED_DIR}/Qwen3-0.6B-Base.Q8_0.gguf", ImportArch.ANIMA.llmPath)
        assertEquals("${ImageCatalog.ANIMA_OLD_ID}/qwen_image_vae.safetensors", ImportArch.ANIMA.vaePath)
        assertTrue(ImageCatalog.ANIMA_OLD_ID in com.iris.localai.util.io.StorageUsage.HIDDEN_IMAGE_DIRS)
        assertEquals(ImageCatalog.Z_IMAGE_TURBO.sdFiles!!.llm, ImportArch.Z_IMAGE.llmPath)
        assertEquals(ImageCatalog.Z_IMAGE_TURBO.sdFiles!!.vae, ImportArch.Z_IMAGE.vaePath)
        assertEquals(ImageCatalog.FLUX2_KLEIN.sdFiles!!.llm, ImportArch.FLUX2_4B.llmPath)
        assertEquals(ImageCatalog.FLUX2_KLEIN.sdFiles!!.vae, ImportArch.FLUX2_4B.vaePath)
    }

    @Test fun fileChecks() {
        assertTrue(ImportedImages.acceptable("a.GGUF"))
        assertTrue(ImportedImages.acceptable("b.safetensors"))
        assertFalse(ImportedImages.acceptable("c.ckpt"))
        assertTrue(ImportedImages.headerOk("a.gguf", "GGUF\u0003\u0000\u0000\u0000".toByteArray()))
        assertFalse(ImportedImages.headerOk("a.gguf", "PK\u0003\u0004".toByteArray()))
        val st = byteArrayOf(0x10, 0, 0, 0, 0, 0, 0, 0, '{'.code.toByte(), '"'.code.toByte())
        assertTrue(ImportedImages.headerOk("v.safetensors", st))
        assertFalse(ImportedImages.headerOk("v.safetensors", "GGUF0000{".toByteArray()))
        assertEquals("a_b", ImportedImages.safeFileName("x/y/a:b"))
        assertTrue(ImportedImages.safeRel("imp-1/a.gguf"))
        assertFalse(ImportedImages.safeRel("imp-1//a.gguf"))
    }

    @Test fun names() {
        assertEquals("anima harem v1", ImportedImages.nameFromFile("anima_harem_v1-Q4_0.gguf"))
        assertEquals("fallback", ImportedImages.cleanName("   ", "fallback"))
        assertEquals(40, ImportedImages.cleanName("x".repeat(60), "f").length)
        assertTrue(ImportedImages.newId(0xabcdL).matches(Regex("imp-[0-9a-f]{8}")))
    }

    @Test fun catalogIncludesImported() {
        // 没 init 时只有内置的；known/all 是 getter，导入后不必重启
        assertEquals(ImageCatalog.builtIn.size, ImageCatalog.known.size)
        val d = File(System.getProperty("java.io.tmpdir"), "v269-" + System.nanoTime()).apply { mkdirs() }
        try {
            ImportedImages.init(d)
            ImportedImages.put(entry())
            assertNotNull(ImageCatalog.byId("imp-0000abcd"))
            ImportedImages.rename("imp-0000abcd", "  新名字  ")
            assertEquals("新名字", ImportedImages.get("imp-0000abcd")!!.name)
            ImportedImages.remove("imp-0000abcd")
            assertNull(ImageCatalog.byId("imp-0000abcd"))
        } finally { d.deleteRecursively() }
    }

    @Test fun isDownloadedNeedsFilesAndLib() {
        val r = File(System.getProperty("java.io.tmpdir"), "v269r-" + System.nanoTime()).apply { mkdirs() }
        try {
            val m = entry().toModel()
            assertFalse(ImageGenFiles.isDownloaded(r, m))
            for (p in listOf(m.sdFiles!!.diffusion, m.sdFiles!!.llm, m.sdFiles!!.vae)) File(r, p).apply { parentFile!!.mkdirs(); writeText("x") }
            assertFalse(ImageGenFiles.isDownloaded(r, m)) // 引擎库还没下
        } finally { r.deleteRecursively() }
    }


    @Test fun importStrings() {
        for (loc in listOf("values", "values-zh-rTW", "values-en", "values-de", "values-es", "values-hi", "values-ja"))
            assertTrue(loc, src("src/main/res/$loc/strings.xml").contains("name=\"ig269_import\""))
    }
}

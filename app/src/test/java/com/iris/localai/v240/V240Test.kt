package com.iris.localai.v240

import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageDownloads
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.SdRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V240Test {
    private fun tmp(): File = Files.createTempDirectory("v240").toFile()

    @Test fun sdModelsHaveTheirFiles() {
        for (m in listOf(ImageCatalog.FLUX2_KLEIN, ImageCatalog.Z_IMAGE_TURBO)) {
            assertEquals(Backend.SDCPP, m.backend)
            assertFalse(m.needsHexagonRuntime)
            val sd = assertNotNull(m.sdFiles).let { m.sdFiles!! }
            val paths = m.files.map { it.path }.toSet()
            assertTrue(sd.diffusion in paths && sd.llm in paths && sd.vae in paths)
            assertEquals(listOf("${ImageCatalog.SHARED_DIR}/Qwen3-4B-Q4_K_M.gguf", ImageCatalog.SD_LIB.path), m.sharedPaths)
            assertTrue(m.files.filter { it !== ImageCatalog.SD_LIB }.all { it.url.startsWith("https://huggingface.co/") && "/resolve/" in it.url && it.size > 0 })
        }
    }

    @Test fun sharedEncoderSurvivesUntilLastUser() {
        val root = tmp()
        val k = ImageCatalog.FLUX2_KLEIN; val z = ImageCatalog.Z_IMAGE_TURBO
        for (m in listOf(k, z)) for (f in m.files) File(root, f.path).apply { parentFile!!.mkdirs(); writeText("x") }
        val shared = File(root, k.sharedPaths.first())
        assertTrue(ImageDownloads.delete(listOf(root), k))
        assertFalse(ImageGenFiles.modelDir(root, k).exists())
        assertTrue("z still uses it", shared.exists())
        assertTrue(ImageDownloads.delete(listOf(root), z))
        assertFalse(shared.exists())
    }

    @Test fun engineLibIsDownloadedAndPinned() {
        val lib = ImageCatalog.SD_LIB
        assertTrue(lib.url.startsWith("https://raw.githubusercontent.com/WWRJ3321/IRIDIS/sdlib-"))
        assertTrue(lib.path.endsWith(".so") && lib.size > 10_000_000L)
        assertTrue(Regex("[0-9a-f]{64}").matches(ImageCatalog.SD_LIB_SHA256))
        val f = File(tmp(), "x").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", com.iris.localai.imagegen.SdLibRules.sha256(f))
    }

    @Test fun gpuThenCpuUnlessCrashed() {
        assertEquals(listOf(SdRules.GPU, SdRules.CPU), SdRules.backends(gpuCrashedBefore = false, preferCpu = false))
        assertEquals(listOf(SdRules.CPU), SdRules.backends(gpuCrashedBefore = true, preferCpu = false))
        val px = SdRules.argb(byteArrayOf(-1, 0, 16), 1, 1)
        assertEquals(0xFFFF0010.toInt(), px[0])
    }
}

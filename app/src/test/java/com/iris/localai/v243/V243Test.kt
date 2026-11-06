package com.iris.localai.v243

import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageRules
import com.iris.localai.imagegen.SdRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.4.3: Hexagon NPU image generation (sd.cpp Qualcomm branch, libiris_sdq.so). */
class V243Test {
    @Test fun npuModelsOnlyOnV79Plus() {
        assertFalse(ImageRules.sdqListed(null))
        assertFalse(ImageRules.sdqListed(75))
        assertTrue(ImageRules.sdqListed(79))
        assertTrue(ImageRules.sdqListed(81))
        // JVM: no SoC → the NPU entries are not listed, but stay known by id (downloaded on another phone).
        assertFalse(ImageCatalog.all.any { it.sdq })
        assertEquals(ImageCatalog.Z_IMAGE_NPU, ImageCatalog.byId("z-image-turbo-npu"))
    }

    @Test fun npuModelsShipLibsAndQ40Encoder() {
        for (m in listOf(ImageCatalog.Z_IMAGE_NPU, ImageCatalog.FLUX2_KLEIN_NPU)) {
            assertTrue(m.sdq); assertEquals(Backend.SDCPP, m.backend); assertEquals(79, m.minHtpArch)
            val paths = m.files.map { it.path }
            for (lib in ImageCatalog.SDQ_LIBS) {
                assertTrue(lib.file.path in paths)
                assertTrue(lib.size > 0 && lib.sha256.length == 64)
                assertTrue(lib.file.url.contains("/${ImageCatalog.SDQ_TAG}/prebuilt-assets/sdq/${lib.name}"))
            }
            val sd = m.sdFiles!!
            assertTrue(sd.diffusion.endsWith("fp8.safetensors") || sd.diffusion.contains("fp8_scaled_e4m3fn"))
            assertEquals("_shared/Qwen3-4B-Q4_0.gguf", sd.llm)
            assertTrue(sd.diffusion in paths && sd.llm in paths && sd.vae in paths)
            assertTrue(m.sharedPaths.containsAll(ImageCatalog.SDQ_LIBS.map { it.file.path }))
        }
        assertFalse(ImageCatalog.Z_IMAGE_TURBO.sdq)
    }

    @Test fun htpBackendDropsEncoderBelow15GB() {
        assertEquals("diffusion=HTP0,te=HTP0,vae=HTP0|te=disk", SdRules.htpBackend(11_500))
        assertEquals("diffusion=HTP0,te=HTP0,vae=HTP0", SdRules.htpBackend(15_800))
        val gb = 1024L * 1024 * 1024
        val resident = SdRules.neededMbHtp(6 * gb, 2 * gb, gb / 4, teOnDisk = false, 1024, 1024)
        val disk = SdRules.neededMbHtp(6 * gb, 2 * gb, gb / 4, teOnDisk = true, 1024, 1024)
        assertEquals(2048L, resident - disk)
    }

    @Test fun sourcesWired() {
        val jni = java.io.File("app/src/main/cpp/sdcpp/sd_jni.cpp").readText()
        assertTrue(jni.contains("Java_com_iris_localai_imagegen_SdqNative_##n"))
        assertTrue(jni.contains("p.params_backend"))
        val repo = java.io.File("app/src/main/java/com/iris/localai/imagegen/ImageGenRepository.kt").readText()
        assertTrue(repo.contains("SdqNative.ensureLoaded"))
        val sh = java.io.File("scripts/build-sdq-native.sh").readText()
        assertTrue(sh.contains("854c9d1019775a3565c819362ac2dec29675911e") && sh.contains("libirsd-htp"))
    }
}

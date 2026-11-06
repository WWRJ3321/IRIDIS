package com.iris.localai.v243

import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageRules
import com.iris.localai.imagegen.SdRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.iris.localai.data.RecommendedCatalog as R
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

    // 2.4.3 生成历史管理: parameters read back from the PNG, only results deletable.
    @Test fun historyParametersRoundTrip() {
        val m = ImageCatalog.SD15_QNN
        val req = com.iris.localai.imagegen.ImageRequest(prompt = "一只猫\n在窗台上", negative = "模糊, 低质量", width = 768, height = 512, steps = 8, cfg = 1.5f, seed = 42)
        val text = com.iris.localai.imagegen.ImageGenFiles.parametersText(req, m)
        val p = com.iris.localai.imagegen.ImageGenFiles.parseParameters(text)!!
        assertEquals("一只猫\n在窗台上", p.prompt)
        assertEquals("模糊, 低质量", p.negative)
        assertEquals(8, p.steps); assertEquals(1.5f, p.cfg); assertEquals(42L, p.seed)
        assertEquals(768, p.width); assertEquals(512, p.height); assertEquals(m.id, p.modelId)
        assertEquals(null, p.strength)
        // survives the PNG text chunk (iTXt for Chinese)
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
        val back = com.iris.localai.imagegen.PngParams.read(com.iris.localai.imagegen.PngParams.insert(png, text))
        assertEquals(p, com.iris.localai.imagegen.ImageGenFiles.parseParameters(back))
        assertEquals(null, com.iris.localai.imagegen.ImageGenFiles.parseParameters(null))
        assertEquals("hello", com.iris.localai.imagegen.ImageGenFiles.parseParameters("hello")!!.prompt)
    }

    @Test fun historyDeletesOnlyResults() {
        val f = com.iris.localai.imagegen.ImageGenFiles
        assertTrue(f.isHistoryFile(java.io.File("/x/images/IMG_20261106_101010.png")))
        assertFalse(f.isHistoryFile(java.io.File("/x/images/photo.png")))
        assertFalse(f.isHistoryFile(java.io.File("/x/models/IMG_20261106_101010.png")))
        assertFalse(f.isHistoryFile(java.io.File("/x/images/IMG_20261106_101010.png.part")))
        val src = java.io.File("app/src/main/java/com/iris/localai/ui/imagegen/ImageHistory.kt").readText()
        assertFalse(Regex("\"[^\"\\n]*[\u4e00-\u9fff][^\"\\n]*\"").containsMatchIn(src))
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")) {
            val xml = java.io.File("app/src/main/res/$d/strings.xml").readText()
            for (k in listOf("ih243_reuse", "ih243_select_all", "ih243_header", "ih243_steps")) assertTrue("$d $k", "name=\"$k\"" in xml)
        }
    }

    // 2.4.3: every MNN chat entry has a GGUF twin (Q4_K_M) right after it; neither claims the other's files.
    @Test fun mnnEntriesHaveGgufTwins() {
        val chat = R.section(R.Section.CHAT)
        chat.forEachIndexed { i, it ->
            if (it.engine == R.Engine.MNN) {
                val twin = chat[i + 1]
                assertEquals(it.name, twin.name); assertEquals(R.Engine.GGUF, twin.engine); assertEquals("Q4_K_M", twin.quant)
                assertTrue(twin.kind is R.Kind.Gguf)
            }
        }
        assertFalse(R.matchesLocal("taobao-mnn/Qwen3.5-4B-MNN", "Qwen3.5-4B-Q4_K_M.gguf"))
        assertTrue(R.matchesLocal("taobao-mnn/Qwen3.5-4B-MNN", "Qwen3.5-4B-MNN"))
        val opus = R.byId("Jackrong/Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-GGUF")!!.kind as R.Kind.Gguf
        assertEquals("Qwen3.5-4B.Q4_K_M.gguf", opus.file)
        assertEquals("Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-Q4_K_M.gguf", opus.localFile)
        assertEquals(5_627_044_256L, R.byId("lmstudio-community/Qwen3.5-9B-GGUF")!!.sizeBytes)
    }

    // 2.4.3: NPU memory message names the model and the weights, never a "bigger" size; GPU text has no fixed 768 hint.
    @Test fun npuLowMemMessage() {
        assertNull(SdRules.memoryProblemHtp(8000, 7000, "Z-Image Turbo（NPU）", 6000))
        val m = SdRules.memoryProblemHtp(5734, 7168, "Z-Image Turbo（NPU）", 6025)!!
        assertTrue(m, m.contains("5.6") && m.contains("7.0") && m.contains("仍然生成") && !m.contains("GPU"))
        val g = SdRules.memoryProblem(3000, 5000, true, 512, 512)!!
        assertFalse(g, g.contains("768×768"))
        assertTrue(SdRules.LowMemory("x") is IllegalStateException)
    }

    @Test fun bonsai9bIsUpstreamQ2() {
        val k = R.byId("prism-ml/Ternary-Bonsai-2-9B-gguf")!!.kind as R.Kind.Gguf
        assertEquals("Ternary-Bonsai-2-9B-Q2_0.gguf", k.file); assertEquals(2_556_640_128L, k.size)
    }
}

package com.iris.localai.util

import com.iris.localai.data.AppSettings
import com.iris.localai.util.io.Attachments
import com.iris.localai.util.VisionPrompt
import com.iris.localai.util.VisionQuality
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.7: feat/ux-217 + feat/imagegen merged; 「识图清晰度」. */
class V217ReleaseTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()


    @Test fun visionDefaultIsStandard512() {
        assertEquals(512, AppSettings().visionSide)
        assertEquals(512, Attachments.VISION_MAX_SIDE)
        assertEquals(VisionQuality.STANDARD, VisionQuality.DEFAULT)
        // settings saved by 2.1.6 (no key) load as 标准
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"lastModelFile":"a.gguf"}""")
        assertEquals(512, old.visionSide)
    }

    @Test fun visionQualityOptions() {
        assertEquals(listOf(384, 512, 1024), VisionQuality.entries.map { it.side })
        assertEquals(listOf("快速", "标准", "清晰"), VisionQuality.entries.map { it.label })
        assertEquals(VisionQuality.STANDARD, VisionQuality.of(0))
        assertEquals(VisionQuality.FAST, VisionQuality.of(384))
        assertEquals(VisionQuality.STANDARD, VisionQuality.of(600))
        assertEquals(VisionQuality.SHARP, VisionQuality.of(1024))
        assertEquals(VisionQuality.SHARP, VisionQuality.of(4096))
    }

    @Test fun settingAppliesToEveryEngineAndMnnStaysCapped() {
        // llama / LiteRT / GenieX (no engine limit): the setting
        assertEquals(384, VisionPrompt.maxSide(0, 384))
        assertEquals(512, VisionPrompt.maxSide(0, 512))
        assertEquals(1024, VisionPrompt.maxSide(0, 1024))
        // MNN (512 cap): 清晰 still 512, 快速 goes smaller
        val mnn = Attachments.MNN_VISION_MAX_SIDE
        assertEquals(384, VisionPrompt.maxSide(mnn, 384))
        assertEquals(512, VisionPrompt.maxSide(mnn, 512))
        assertEquals(512, VisionPrompt.maxSide(mnn, 1024))
        // odd stored values snap to an option
        assertEquals(512, VisionPrompt.maxSide(0, 0))
    }

    @Test fun scaledCopiesKeepDistinctNames() {
        assertEquals("/a/b.jpg.vis.jpg", Attachments.visionPathFor("/a/b.jpg", 1024))
        assertEquals("/a/b.jpg.vis512.jpg", Attachments.visionPathFor("/a/b.jpg"))
        assertEquals("/a/b.jpg.vis384.jpg", Attachments.visionPathFor("/a/b.jpg", 384))
        assertTrue(Attachments.isVisionCopyOf("b.jpg", "b.jpg.vis384.jpg"))
        assertEquals(Pair(384, 288), Attachments.visionTarget(4000, 3000, 384))
    }



}

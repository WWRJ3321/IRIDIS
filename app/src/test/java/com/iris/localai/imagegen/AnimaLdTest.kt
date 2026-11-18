package com.iris.localai.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnimaLdTest {
    private val ldFiles = listOf("clip.bin", "unet_part1.bin", "unet_part2.bin", "vae_decoder.bin", "vae_encoder.bin",
        "token_emb.bin", "tokenizer.json", "tokenizer_t5.json", "config.json", "ANIMA")

    @Test fun detectsLdLayout() {
        val d = Files.createTempDirectory("ld").toFile()
        try {
            assertFalse(AnimaQnnFiles(d).ld)
            File(d, "ANIMA").writeText("")
            assertTrue(AnimaQnnFiles(d).ld)
            assertEquals(7, AnimaQnnFiles(d).missing().size)
            ldFiles.forEach { File(d, it).writeText("x") }
            File(d, "config.json").writeText("{\"default_scheduler\": \"euler\", \"default_steps\": 10, \"default_cfg\": 1}")
            assertTrue(AnimaQnnFiles(d).missing().isEmpty())
            assertEquals(10, AnimaLdFiles(d).defaultSteps())
            // 可选文件缺了也算完整
            File(d, "vae_encoder.bin").delete(); File(d, "config.json").delete(); File(d, "ANIMA").delete()
            assertTrue(AnimaQnnFiles(d).ld && AnimaQnnFiles(d).missing().isEmpty())
            assertNull(AnimaLdFiles(d).defaultSteps())
        } finally { d.deleteRecursively() }
    }

    @Test fun importRecognisesLdNamesAndZip() {
        assertTrue(ldFiles.all { it in AnimaQnnFiles.NAMES })
        assertEquals(FileRole.NPU, ImportDetect.role("unet_part1.bin", null))
        assertTrue(ImportDetect.isNpuFile("wai_anima_v1_turbo_qnn2.28_8gen3.zip"))
        assertTrue(ImportDetect.isNpuPlan(listOf("x.zip")))
        assertTrue(ImportDetect.isNpuPlan(listOf("clip.bin", "tokenizer.json")))
        // 只有通用文件名（config.json / tokenizer.json）不当 NPU 包
        assertFalse(ImportDetect.isNpuPlan(listOf("model.safetensors", "config.json", "tokenizer.json")))
        val m = ImportedImage("imp-00000002", "B", ImportArch.ANIMA_NPU.name, "imp-00000002", "imp-00000002", "imp-00000002", 10, 1f, pack = ImportedImage.PACK_LD).toModel()
        assertTrue(m.animaQnn); assertEquals(75, m.minHtpArch)
        val back = ImportedImages.decode(ImportedImages.encode(listOf(ImportedImage("imp-00000002", "B", ImportArch.ANIMA_NPU.name, "imp-00000002", "imp-00000002", "imp-00000002", 10, 1f, pack = "ld"))))
        assertEquals("ld", back.single().pack)
    }

    @Test fun textInputsPadAndMask() {
        val t = AnimaLdMath.textInputs(intArrayOf(5, 6), intArrayOf(7, 1), 4)
        assertEquals(listOf(5, 6, AnimaLdMath.QWEN_PAD, AnimaLdMath.QWEN_PAD), t.qwenRows.toList())
        assertEquals(listOf(1f, 1f, 0f, 0f), t.qwenMask.toList())
        assertEquals(listOf(7, 1, 0, 0), t.t5Ids.toList())
        assertEquals(listOf(1f, 1f, 0f, 0f), t.t5Mask.toList())
    }

    @Test fun latentAndPixels() {
        assertEquals(128 to 128, AnimaLdMath.latentHw(listOf(1, 16, 1, 128, 128)))
        val z = FloatArray(16 * 4) { 0f }
        val r = AnimaLdMath.denormalize(z)
        assertEquals(AnimaLdMath.LAT_MEAN[0], r[0], 0f); assertEquals(AnimaLdMath.LAT_MEAN[15], r[63], 0f)
        assertEquals(AnimaLdMath.LAT_STD[7] + AnimaLdMath.LAT_MEAN[7], AnimaLdMath.denormalize(FloatArray(64) { 1f })[7 * 4], 1e-6f)
        val px = AnimaLdMath.toArgb(floatArrayOf(-1f, 1f, 0f), 1, 1)
        assertEquals(0xFF00FF80.toInt(), px[0])
    }
}

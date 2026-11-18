package com.iris.localai.engine

import com.iris.localai.engine.Backend
import com.iris.localai.engine.GgufQuant
import com.iris.localai.engine.LiteRtMtp
import com.iris.localai.engine.MtpTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.5.0-beta10: MTP on the GPU backends (experimental, runtime fallback) + LiteRT-LM Gemma 4 drafter. */
class V2510MtpGpuTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val mtp = GgufQuant.Info(setOf(1), "qwen3next", 1)




    @Test fun gpuSubtitleNote() {
        assertTrue(MtpTag.gpuNote(Backend.GPU, null))
        assertTrue(MtpTag.gpuNote(null, "GPU · OpenCL"))
        assertFalse(MtpTag.gpuNote(null, "CPU"))
        assertFalse(MtpTag.gpuNote(Backend.CPU, "GPU"))
        assertFalse(MtpTag.gpuNote(Backend.NPU, null))
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")) {
            val x = src("app/src/main/res/$d/strings.xml")
            assertTrue(d, x.contains("name=\"v2510_mtp_gpu\"") && x.contains("name=\"v2510_litert_no_mtp\""))
            assertFalse(d, Regex("name=\"v259_mtp_sub\">[^<]*(仅 CPU|僅 CPU|CPU only|Nur CPU|Solo CPU|केवल CPU|CPU のみ)").containsMatchIn(x))
        }
    }

    @Test fun litertRules() {
        assertTrue(LiteRtMtp.enabled(true, false, true, Backend.CPU))
        assertTrue(LiteRtMtp.enabled(true, false, true, Backend.GPU))
        assertFalse(LiteRtMtp.enabled(true, false, true, Backend.NPU))
        assertFalse(LiteRtMtp.enabled(false, false, true, Backend.GPU))
        assertFalse(LiteRtMtp.enabled(true, false, false, Backend.GPU))
        assertEquals(listOf(true, false), LiteRtMtp.attempts(true))
        assertEquals(listOf(false), LiteRtMtp.attempts(false))
        assertTrue(LiteRtMtp.tag("gemma-4-E2B-it.litertlm"))
        assertTrue(LiteRtMtp.tag("gemma4_e4b_it_int4.litertlm"))
        assertFalse(LiteRtMtp.tag("gemma-4-E4B-it.task"))
        assertFalse(LiteRtMtp.tag("gemma-4-26B-A4B-it.litertlm"))
        assertFalse(LiteRtMtp.tag("Qwen3.5-0.8B.litertlm"))
        assertFalse(LiteRtMtp.tag("gemma-3n-E2B-it.litertlm"))
    }

}

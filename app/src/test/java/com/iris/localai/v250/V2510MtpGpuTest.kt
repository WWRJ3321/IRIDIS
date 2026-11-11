package com.iris.localai.v250

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

    @Test fun gpuAllowedNpuNot() {
        assertTrue(GgufQuant.mtpBackend(Backend.CPU))
        assertTrue(GgufQuant.mtpBackend(Backend.GPU))
        assertFalse(GgufQuant.mtpBackend(Backend.NPU))
        assertEquals(GgufQuant.MTP_DRAFT, GgufQuant.mtpDraft(true, mtp, Backend.GPU))
        assertEquals(0, GgufQuant.mtpDraft(true, mtp, Backend.NPU))
        assertEquals(0, GgufQuant.mtpDraft(false, mtp, Backend.GPU))
        // the HTP (NPU) llama path still never asks for MTP
        assertTrue(src("app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt").contains("jni.setMtp(h, if (npu) 0 else config.mtpDraft.coerceIn(0, 8))"))
    }

    @Test fun safeModeStillOff() {
        val em = src("app/src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(em.contains("mtpDraft = if (safeMode || model.format != ModelFormat.GGUF) 0 else GgufQuant.mtpDraft(cfg.mtp, quant, backend)"))
        assertTrue(em.contains("litertMtp = LiteRtMtp.enabled(cfg.mtp, safeMode, model.format == ModelFormat.LITERT, backend)"))
        assertFalse(LiteRtMtp.enabled(true, true, true, Backend.GPU))
    }

    @Test fun nativeRuntimeFallback() {
        val e = src("app/src/main/cpp/iris_engine.cpp")
        assertTrue(e.contains("void Engine::spec_disable(const std::string &why)"))
        for (r in listOf("spec_disable(\"draft failed\")", "spec_disable(\"verify decode failed (\"", "spec_disable(\"verify process failed\")",
                "spec_disable(\"rollback failed\")", "spec_disable(\"prompt process failed\")", "spec_disable(\"prompt decode failed (\""))
            assertTrue(r, e.contains(r))
        // a failed verification decodes the token alone instead of ending the reply
        assertTrue(e.contains("goto normal_decode;") && e.contains("normal_decode:"))
        // Kotlin notices the native switch-off after the reply
        assertTrue(src("app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt").contains("MTP turned off for this session"))
    }

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

    @Test fun litertSourceFlagEveryEngine() {
        val l = src("app/src/full/java/com/iris/localai/engine/litert/LiteRtEngine.kt")
        // set explicitly (true or false) right before every Engine(...) so the global never leaks to another model
        val idx = l.indexOf("ExperimentalFlags.enableSpeculativeDecoding = spec")
        assertTrue(idx > 0 && l.indexOf("e = Engine(", idx) > idx)
        assertEquals(1, Regex("Engine\\(\\s*\\n\\s*EngineConfig\\(").findAll(l).count())
        assertTrue(l.contains("LiteRtMtp.attempts(config.litertMtp)"))
        assertTrue(l.contains("R.string.v2510_litert_no_mtp"))
        assertTrue(l.contains("fallbackNote = mtpNote"))
        // the switch is offered for LiteRT-LM models; the tag needs no file probe
        assertTrue(src("app/src/main/java/com/iris/localai/ui/models/ModelConfigSheet.kt").contains("model.format == com.iris.localai.data.ModelFormat.LITERT ||"))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/models/ModelsScreen.kt").contains("LiteRtMtp.tag(m.fileName)"))
    }
}

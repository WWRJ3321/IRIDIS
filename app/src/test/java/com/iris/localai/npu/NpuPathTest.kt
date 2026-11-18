package com.iris.localai.npu

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.FirstReplyFallback
import com.iris.localai.engine.load.LiteRtLoadKey
import com.iris.localai.engine.NpuIds
import com.iris.localai.engine.NpuRuntimeFiles
import com.iris.localai.util.diagnostics.CrashDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.0 NPU path: SoC matching, file names, KV budget, first-reply fallback, runtime install check, logcat filter. */
class NpuPathTest {

    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    // ---- 1. SoC id normalisation + matching ----

    @Test fun baseSocIdStripsVariantSuffixes() {
        assertEquals("sm8750", NpuIds.baseSocId("SM8750"))
        assertEquals("sm8750", NpuIds.baseSocId("SM8750P"))
        assertEquals("sm8750", NpuIds.baseSocId("sm8750-ab"))
        assertEquals("sm8750", NpuIds.baseSocId(" SM8750-AC "))
        assertEquals("sm8650", NpuIds.baseSocId("SM8650"))
        assertEquals("qcs8550", NpuIds.baseSocId("QCS8550"))
        assertNull(NpuIds.baseSocId(""))
        assertNull(NpuIds.baseSocId(null))
        assertEquals("tensor", NpuIds.baseSocId("Tensor G5"))
    }

    @Test fun sm8750ModelRunsOnEverySm8750VariantButNotOtherFamilies() {
        for (dev in listOf("SM8750", "SM8750P", "sm8750-ab", "sm8750")) assertTrue(dev, NpuIds.socMatches("sm8750", dev))
        assertFalse(NpuIds.socMatches("sm8750", "SM8650"))
        assertFalse(NpuIds.socMatches("sm8650", "SM8750"))
        assertFalse(NpuIds.socMatches("sm8750", "SM8850"))
        assertFalse(NpuIds.socMatches("sm8750", null))
        // qcs targets keep the old rule (not compared with the phone's id)
        assertTrue(NpuIds.socMatches("qcs8275", "SM8750"))
    }

    @Test fun backendPickerUsesTheSameRule() {
        // env.npuSoc comes from BackendEnvironment.probe → NpuIds.baseSocId(Build.SOC_MODEL)
        val env = BackendEnvironment(hasOpenCl = true, npuSoc = NpuIds.baseSocId("SM8750P"), npuRuntimeInstalled = true, fullFlavor = true)
        val ok = BackendCapabilities.options(ModelFormat.LITERT, "gemma-4-E2B-it_qualcomm_sm8750.litertlm", env)
        assertTrue(ok.first { it.backend == Backend.NPU }.available)
        val wrong = BackendCapabilities.options(ModelFormat.LITERT, "gemma-4-E2B-it_qualcomm_sm8650.litertlm", env)
        assertTrue(wrong.none { it.available })
        // even a raw, un-normalised variant id in the environment still matches
        val raw = BackendCapabilities.options(ModelFormat.LITERT, "m_sm8750.litertlm", env.copy(npuSoc = "sm8750p"))
        assertTrue(raw.first { it.backend == Backend.NPU }.available)
    }


    // ---- hexagon arch ----

    @Test fun hexagonArchMapping() {
        assertEquals(81, NpuIds.hexagonArch("QTI", "SM8850"))
        assertEquals(79, NpuIds.hexagonArch("QTI", "SM8750"))
        assertEquals(79, NpuIds.hexagonArch("QTI", "SM8750P"))
        assertEquals(79, NpuIds.hexagonArch("Qualcomm", "sm8750-ab"))
        assertEquals(75, NpuIds.hexagonArch("QTI", "SM8650"))
        assertEquals(73, NpuIds.hexagonArch("QTI", "SM8550"))
        assertEquals(69, NpuIds.hexagonArch("QTI", "SM8450"))
        assertEquals(69, NpuIds.hexagonArch("QTI", "SM8475"))
        assertNull(NpuIds.hexagonArch("QTI", "SM7675"))
        assertNull(NpuIds.hexagonArch("Mediatek", "MT6989"))
        assertNull(NpuIds.hexagonArch("Google", "Tensor G5"))
        assertNull(NpuIds.hexagonArch(null, "SM8750"))
    }

    // ---- model file names ----

    @Test fun parsesNpuModelFileNames() {
        assertEquals("sm8750", NpuIds.targetSoc("gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
        assertEquals("sm8750", NpuIds.targetSoc("gemma-4-E2B-it.qualcomm.sm8750.litertlm"))
        assertEquals("sm8750", NpuIds.targetSoc("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertEquals("sm8750", NpuIds.targetSoc("model_SM8750.litertlm"))
        assertEquals("sm8650", NpuIds.targetSoc("gemma3-270m-it_q8_ekv1024_sm8650.litertlm"))
        assertEquals("qcs8550", NpuIds.targetSoc("model-qcs8550.litertlm"))
        assertEquals("sm8750", NpuIds.targetSoc("model_sm8750p.litertlm"))
        assertNull(NpuIds.targetSoc("gemma-4-E2B-it.litertlm"))
        assertNull(NpuIds.targetSoc("gemma-4-E2B-it_Google_Tensor_G5.litertlm"))
        assertNull(NpuIds.targetSoc("qwen3-sm87500.litertlm"))
        assertNull(NpuIds.targetSoc("prism8750.litertlm"))
        // the backend picker and the engine read names the same way
        assertEquals("sm8750", BackendCapabilities.npuTarget("gemma-4-E2B-it.qualcomm.sm8750.litertlm"))
    }

    @Test fun parsesEkv() {
        assertEquals(1280, NpuIds.kvCapacity("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertEquals(4096, NpuIds.kvCapacity("x.ekv4096.sm8750.litertlm"))
        assertEquals(1024, NpuIds.kvCapacity("gemma3-270m-it_q8_EKV1024_sm8650.litertlm"))
        assertNull(NpuIds.kvCapacity("gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
        assertNull(NpuIds.kvCapacity("gemma_ekv12_sm8750.litertlm"))
        assertEquals(1280, BackendCapabilities.npuKv("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
    }

    // ---- 4. history budget ----

    @Test fun historyBudgetUsesCompiledKvOrFamilyDefault() {
        assertEquals(1280, NpuIds.historyBudget("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertEquals(4096, NpuIds.historyBudget("gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
        assertEquals(4096, NpuIds.historyBudget("gemma-4-E2B-it.qualcomm.sm8750.litertlm"))
        assertEquals(1024, NpuIds.historyBudget("unknown_sm8750.litertlm"))
        assertEquals(4096, BackendCapabilities.features(ModelFormat.LITERT, "gemma-4-E2B-it_qualcomm_sm8750.litertlm").fixedContext)
        assertEquals(1280, BackendCapabilities.features(ModelFormat.LITERT, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm").fixedContext)
        assertNull(BackendCapabilities.features(ModelFormat.LITERT, "gemma-4-E2B-it.litertlm").fixedContext)
        // LiteRtEngine: never above the compiled size, never above the user's 上下文长度 when the name doesn't say
        val f = "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
        assertEquals(4096, LiteRtLoadKey.maxNumTokens(8192, npuFile = true, npuKv = null, npuDefault = NpuIds.defaultKv(f)))
        assertEquals(2048, LiteRtLoadKey.maxNumTokens(2048, npuFile = true, npuKv = null, npuDefault = NpuIds.defaultKv(f)))
        assertEquals(1280, LiteRtLoadKey.maxNumTokens(8192, npuFile = true, npuKv = 1280, npuDefault = NpuIds.defaultKv(f)))
    }

    // ---- 3. first-reply fallback ----

    @Test fun npuOnlyModelReportsTheNativeErrorInsteadOfACpuRetry() {
        val d = FirstReplyFallback.decide(true, Backend.NPU, failedBeforeOutput = true, npuOnlyModel = true, error = "推理失败：Failed to allocate tensors")
        assertEquals(FirstReplyFallback.Decision.Report("NPU 上首次生成失败：Failed to allocate tensors"), d)
        val empty = FirstReplyFallback.decide(true, Backend.NPU, true, true, null) as FirstReplyFallback.Decision.Report
        assertTrue(empty.message.startsWith("NPU 上首次生成失败："))
        assertTrue(empty.message.length > "NPU 上首次生成失败：".length)
    }

    @Test fun genericModelsKeepTheCpuRetry() {
        assertEquals(FirstReplyFallback.Decision.RetryOnCpu, FirstReplyFallback.decide(true, Backend.GPU, true, false, "x"))
        assertEquals(FirstReplyFallback.Decision.PassThrough, FirstReplyFallback.decide(false, Backend.GPU, true, false, "x"))
        assertEquals(FirstReplyFallback.Decision.PassThrough, FirstReplyFallback.decide(true, Backend.CPU, true, false, "x"))
        assertEquals(FirstReplyFallback.Decision.PassThrough, FirstReplyFallback.decide(true, null, true, false, "x"))
        assertEquals(FirstReplyFallback.Decision.PassThrough, FirstReplyFallback.decide(true, Backend.GPU, false, false, "x"))
        // NPU-only but output already streamed / not the first reply: nothing special
        assertEquals(FirstReplyFallback.Decision.PassThrough, FirstReplyFallback.decide(true, Backend.NPU, false, true, "x"))
        assertEquals(FirstReplyFallback.Decision.PassThrough, FirstReplyFallback.decide(false, Backend.NPU, true, true, "x"))
    }


    // ---- 5. runtime install validation ----

    private val arch = 79
    private val sizes = mapOf(
        "libLiteRtDispatch_Qualcomm.so" to 429_328L, "libQnnHtp.so" to 2_000_000L, "libQnnSystem.so" to 300_000L,
        "libQnnHtpV79Stub.so" to 400_000L, "libQnnHtpV79Skel.so" to 9_000_000L, "libQnnHtpPrepare.so" to 85_000_000L,
    )
    private val marker = NpuRuntimeFiles.markerText("litert=x qnn=2.50.0 arch=v79", sizes.map { (n, s) -> Triple(n, s, "ab") })

    @Test fun runtimeExpectsTheSixLibrariesOfThisArch() {
        assertEquals(sizes.keys.toList(), NpuRuntimeFiles.expected(arch))
        assertEquals(sizes, NpuRuntimeFiles.recordedSizes(marker))
        assertTrue(NpuRuntimeFiles.recordedSizes("litert=x qnn=2.50.0 arch=v79\n").isEmpty())
    }

    @Test fun runtimeCheck() {
        assertEquals(NpuRuntimeFiles.Check.Ok, NpuRuntimeFiles.check(arch, marker) { sizes[it] })
        assertEquals(NpuRuntimeFiles.Check.NotInstalled, NpuRuntimeFiles.check(arch, null) { sizes[it] })
        // marker alone is not enough
        val missing = NpuRuntimeFiles.check(arch, marker) { if (it == "libQnnHtpV79Skel.so") null else sizes[it] }
        assertTrue(missing is NpuRuntimeFiles.Check.Broken && missing.reason.contains("libQnnHtpV79Skel.so"))
        val empty = NpuRuntimeFiles.check(arch, marker) { if (it == "libQnnHtp.so") 0L else sizes[it] }
        assertTrue(empty is NpuRuntimeFiles.Check.Broken && empty.reason.contains("为空"))
        val truncated = NpuRuntimeFiles.check(arch, marker) { if (it == "libQnnSystem.so") 1L else sizes[it] }
        assertTrue(truncated is NpuRuntimeFiles.Check.Broken && truncated.reason.contains("大小不符"))
        // pre-2.0 marker (no sizes): present + non-empty is enough
        assertEquals(NpuRuntimeFiles.Check.Ok, NpuRuntimeFiles.check(arch, "litert=x qnn=2.50.0 arch=v79\n") { sizes[it] })
        // wrong-arch files do not count
        val v75 = NpuRuntimeFiles.check(75, "litert=x\n") { sizes[it] }
        assertTrue(v75 is NpuRuntimeFiles.Check.Broken)
    }


    // ---- 2. logcat filter ----

    @Test fun logcatKeepsNpuLines() {
        for (l in listOf(
            "10-18 20:00:01.000 1 2 E LiteRT  : Failed to allocate tensors",
            "10-18 20:00:01.000 1 2 E tflite  : Delegate failed",
            "10-18 20:00:01.000 1 2 E QnnDsp  : <E> graph prepare failed",
            "10-18 20:00:01.000 1 2 E adsprpc : remote_handle_open failed",
            "10-18 20:00:01.000 1 2 W FastRPC : session closed",
            "10-18 20:00:01.000 1 2 E CDSP    : subsystem restart",
            "10-18 20:00:01.000 1 2 I IrisLoad: load FAILED",
            "10-18 20:00:01.000 1 2 I IRISRESOURCE: per reply",
        )) assertTrue(l, CrashDump.keepLogcatLine(l))
        assertFalse(CrashDump.keepLogcatLine("10-18 20:00:01.000 1 2 I WifiService: scan"))
    }

    // ---- 6. version notes ----

}

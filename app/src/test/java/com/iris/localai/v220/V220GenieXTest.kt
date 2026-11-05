package com.iris.localai.v220

import com.iris.localai.data.AppSettings
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelOverrides
import com.iris.localai.data.sanitized
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.LiveApply
import com.iris.localai.engine.LiveResourcePolicy
import com.iris.localai.engine.NpuState
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.engine.StopReason
import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GenieXLibs
import com.iris.localai.engine.geniex.GenieXRules
import com.iris.localai.engine.geniex.GenieXRuntime
import com.iris.localai.engine.geniex.GenieXSampling
import com.iris.localai.engine.geniex.GenieXTurns
import com.iris.localai.engine.geniex.GenieXVision
import com.iris.localai.engine.geniex.QairtBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.2.0: Qualcomm GenieX as the fourth engine (full flavour), runtime downloaded on demand. */
class V220GenieXTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }

    // ---------------------------------------------------------------- engine choice

    @Test fun engineIdsParse() {
        assertEquals(GenieX.ENGINE_LLAMA, GenieX.parseEngine(" llama.cpp "))
        assertEquals(GenieX.ENGINE_GENIEX, GenieX.parseEngine("GenieX"))
        assertNull(GenieX.parseEngine("mnn"))
        assertNull(GenieX.parseEngine(null))
        assertEquals("GenieX · NPU", GenieX.tag(Backend.NPU))
        assertEquals("GenieX · GPU", GenieX.tag(Backend.GPU))
        assertEquals(listOf("cpu", "gpu", "npu"), Backend.entries.map { GenieX.computeUnit(it) })
    }

    @Test fun ggufEngineSettingDefaultsToLlamaAndSanitizes() {
        assertEquals("llama", AppSettings().ggufEngine)
        assertEquals("llama", AppSettings(ggufEngine = "nonsense").sanitized().ggufEngine)
        assertEquals("geniex", AppSettings(ggufEngine = "geniex").sanitized().ggufEngine)
    }

    @Test fun perModelEngineOverridesTheDefault() {
        val s = AppSettings()
        assertEquals(GenieX.ENGINE_LLAMA, EffectiveModelConfig.resolve(s, ModelFormat.GGUF, null).engine)
        assertEquals(GenieX.ENGINE_GENIEX, EffectiveModelConfig.resolve(s.copy(ggufEngine = "geniex"), ModelFormat.GGUF, null).engine)
        assertEquals(GenieX.ENGINE_LLAMA, EffectiveModelConfig.resolve(s.copy(ggufEngine = "geniex"), ModelFormat.GGUF, ModelOverrides(engine = "llama")).engine)
        assertEquals(GenieX.ENGINE_GENIEX, EffectiveModelConfig.resolve(s, ModelFormat.GGUF, ModelOverrides(engine = "geniex")).engine)
        assertNull(ModelOverrides(engine = "bogus").sanitized().engine)
        assertTrue(ModelOverrides(engine = "bogus").sanitized().isEmpty)
    }

    // ---------------------------------------------------------------- backends

    private val ready = BackendEnvironment(hasOpenCl = true, npuSoc = "sm8750", npuRuntimeInstalled = false, fullFlavor = true, geniexBlock = null, geniexQairt = true)

    @Test fun geniexGgufOffersNpuGpuCpuAndDefaultsToNpu() {
        val o = BackendCapabilities.options(ModelFormat.GGUF, "Qwen3-4B-Q4_0.gguf", ready, GenieX.ENGINE_GENIEX)
        assertTrue(o.all { it.available })
        assertEquals("最快，Q4_0 模型效果最好", o.first { it.backend == Backend.NPU }.detail) // 2.1.2: short, the sheet already names the engine
        // 2.1.3: GGUF 自动 stays on the CPU; NPU (= GenieX) is an explicit pick (old 「GenieX」 overrides migrate to backend NPU).
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.GGUF, "Qwen3-4B-Q4_0.gguf", o))
        // no OpenCL → GPU greyed out with the reason
        val noCl = BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", ready.copy(hasOpenCl = false), GenieX.ENGINE_GENIEX)
        assertFalse(noCl.first { it.backend == Backend.GPU }.available)
    }

    @Test fun llamaGgufBehaviourUnchanged() {
        // 2.1.3: with no engine given the backend decides (NPU = GenieX), so the explicit llama.cpp list is checked here.
        val before = BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", ready, GenieX.ENGINE_LLAMA)
        assertFalse(before.first { it.backend == Backend.NPU }.available)
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.GGUF, "a.gguf", before))
        assertTrue(BackendCapabilities.features(ModelFormat.GGUF, "a.gguf").kvQuant)
        assertFalse(BackendCapabilities.features(ModelFormat.GGUF, "a.gguf", engine = GenieX.ENGINE_GENIEX).kvQuant)
    }

    @Test fun geniexBlockedExplainsWhy() {
        val notInstalled = ready.copy(geniexBlock = GenieXRules.block(NpuState.NotInstalled))
        val o = BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", notInstalled, GenieX.ENGINE_GENIEX)
        assertTrue(o.none { it.available })
        assertTrue(o.all { it.reason == GenieX.NEED_RUNTIME })
        // the lite build / an old environment: GenieX is simply not there
        val lite = BackendEnvironment(hasOpenCl = true, npuSoc = null, npuRuntimeInstalled = false, fullFlavor = false)
        assertEquals(GenieX.NOT_IN_BUILD, BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", lite, GenieX.ENGINE_GENIEX).first().reason)
        // non-Qualcomm chip
        val mtk = GenieXLibs.unsupportedReason("Mediatek", "MT6989")!!
        assertTrue(mtk, "高通骁龙" in mtk && "MT6989" in mtk)
        assertEquals(mtk, GenieXRules.block(NpuState.Unsupported(mtk)))
        assertNull(GenieXRules.block(NpuState.Installed))
        assertTrue("修复" in GenieXRules.block(NpuState.NeedsRepair("缺少 x"))!!)
    }

    @Test fun qairtIsNpuOnly() {
        val o = BackendCapabilities.options(ModelFormat.QAIRT, "Qwen3-4B_genie_bundle", ready)
        assertEquals(listOf(Backend.NPU), o.filter { it.available }.map { it.backend })
        val noQairt = BackendCapabilities.options(ModelFormat.QAIRT, "x", ready.copy(geniexQairt = false))
        assertTrue(noQairt.none { it.available })
        assertTrue(noQairt.first { it.backend == Backend.NPU }.reason!!.contains("8 Elite"))
        assertEquals(LiveApply.NEXT_REPLY, LiveResourcePolicy.mode(ModelFormat.QAIRT, Backend.NPU))
        assertTrue(BackendCapabilities.contextShiftUnsupported(ModelFormat.QAIRT)!!.contains("QAIRT"))
    }

    // ---------------------------------------------------------------- runtime libraries

    @Test fun hexagonArchOfSnapdragons() {
        assertEquals(79, GenieXLibs.hexagonArch("QTI", "SM8750"))
        assertEquals(79, GenieXLibs.hexagonArch("QTI", "SM8750P"))
        assertEquals(81, GenieXLibs.hexagonArch("Qualcomm", "sm8850"))
        assertEquals(75, GenieXLibs.hexagonArch("QTI", "SM8650"))
        assertNull(GenieXLibs.hexagonArch("Mediatek", "SM8750"))
        assertNull(GenieXLibs.hexagonArch("QTI", "SM7675"))
        assertNull(GenieXLibs.unsupportedReason("QTI", "SM8750"))
        assertTrue(GenieXLibs.unsupportedReason("QTI", "SM7675")!!.contains("SM7675"))
    }

    @Test fun eliteGetsOnlyItsOwnLibraries() {
        val libs = GenieXLibs.forArch(79)
        val names = libs.map { it.entry.substringAfterLast('/') }
        assertEquals(libs.size, libs.map { it.path }.toSet().size)
        assertTrue("libggml-htp-v79.so" in names && "libQnnHtpV79Skel.so" in names && "libQnnHtpV79Stub.so" in names)
        for (bad in listOf("V81", "v81", "v75", "v73", "Prepare", "Hta", "hta_", "Saver", "Profiling", "PlatformValidator", "alculator", "NetRunDirect", "libomp", "QnnIr", "ModelDlc")) {
            assertTrue("$bad must not be installed", names.none { bad in it })
        }
        assertFalse("libQnnHtpV79.so" in names)
        // plugins go where geniex_init's registry scan finds them
        assertTrue(libs.any { it.path == "llama_cpp/libgeniex_plugin.so" && it.entry.endsWith("libgeniex_plugin_llama_cpp.so") })
        assertTrue(libs.any { it.path == "qairt/libgeniex_plugin.so" && it.entry.endsWith("libgeniex_plugin_qairt.so") })
        assertTrue(libs.all { it.entry.startsWith("jni/arm64-v8a/") && it.pin.sha256.length == 64 && it.pin.size > 0 })
        val mb = GenieXLibs.installedBytes(79) / (1 shl 20)
        assertTrue("installed ${mb} MB", mb in 40..60)
    }

    @Test fun olderSnapdragonsGetGgufOnly() {
        val names = GenieXLibs.forArch(75).map { it.path }
        assertTrue("libggml-htp-v75.so" in names)
        assertTrue(names.none { "Qnn" in it || "qairt" in it })
        assertTrue(GenieXLibs.qnnLoadOrder(75).isEmpty())
        assertEquals(setOf(79, 81), GenieXLibs.QAIRT_ARCHS)
    }

    @Test fun loadOrderPutsEveryDependencyFirst() {
        // NEEDED entries (llvm-readelf -d on the 0.8.0 AAR; unchanged in 0.10.0), restricted to GenieX's own libraries.
        val needed = mapOf(
            "libggml-cpu.so" to listOf("libggml-base.so"),
            "libggml-opencl.so" to listOf("libggml-base.so"),
            "libggml-hexagon.so" to listOf("libggml-base.so"),
            "libggml.so" to listOf("libggml-cpu.so", "libggml-opencl.so", "libggml-hexagon.so", "libggml-base.so"),
            "libllama.so" to listOf("libggml.so", "libggml-cpu.so", "libggml-opencl.so", "libggml-hexagon.so", "libggml-base.so"),
            "libllama-common.so" to listOf("libllama.so", "libggml.so"),
            "libmtmd.so" to listOf("libllama.so", "libggml.so"),
            "libgeniex-proc-vision.so" to listOf("libgeniex-proc.so"),
            "libgeniex_core.so" to listOf("libgeniex-proc.so"),
            "libgeniex_vlm.so" to listOf("libgeniex_core.so", "libgeniex-proc-vision.so", "libgeniex-proc.so"),
            "libnpu_jni.so" to listOf("libgeniex.so"),
            // plugins (registered by geniex_init from libnpu_jni's JNI_OnLoad)
            "libgeniex_plugin_llama_cpp.so" to listOf("libllama-common.so", "libgeniex.so", "libmtmd.so", "libllama.so", "libggml.so"),
            "libgeniex_plugin_qairt.so" to listOf("libgeniex.so", "libgeniex_vlm.so", "libgeniex_core.so", "libgeniex-proc-vision.so", "libgeniex-proc.so"),
        )
        val order = GenieXLibs.CORE_LOAD_ORDER + GenieXLibs.JNI
        for ((lib, deps) in needed) {
            val at = order.indexOf(lib).takeIf { it >= 0 } ?: order.size // plugins: after everything
            for (d in deps) assertTrue("$d before $lib", order.indexOf(d) in 0 until at)
        }
        assertEquals(GenieXLibs.JNI, order.last())
        // DSP-side libraries are never System.load()ed
        assertTrue(order.none { "htp-v" in it || "Skel" in it })
        assertEquals(listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpV79Stub.so", "libQnnHtpNetRunExtensions.so"), GenieXLibs.qnnLoadOrder(79))
    }

    @Test fun installMarkerCheck() {
        val libs = GenieXLibs.forArch(79)
        val marker = GenieXLibs.markerText(79, libs)
        val sizes = libs.associate { it.path to it.pin.size }
        assertEquals(GenieXLibs.Check.Ok, GenieXLibs.check(79, marker) { sizes[it] })
        assertEquals(GenieXLibs.Check.NotInstalled, GenieXLibs.check(79, null) { sizes[it] })
        val missing = GenieXLibs.check(79, marker) { if (it == "qairt/libgeniex_plugin.so") null else sizes[it] }
        assertTrue(missing is GenieXLibs.Check.Broken && "qairt/libgeniex_plugin.so" in missing.reason)
        val wrong = GenieXLibs.check(79, marker) { if (it == "libgeniex.so") 1L else sizes[it] }
        assertTrue(wrong is GenieXLibs.Check.Broken && "大小" in wrong.reason)
        assertTrue(GenieXLibs.check(81, marker) { sizes[it] } is GenieXLibs.Check.Broken) // another arch's runtime
    }

    @Test fun downloadSourcesArePinned() {
        assertEquals(2, GenieXLibs.AAR_URLS.size)
        assertTrue(GenieXLibs.AAR_URLS[0].startsWith("https://repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0/"))
        assertTrue(GenieXLibs.AAR_URLS[1].startsWith("https://maven.aliyun.com/"))
        assertEquals(64, GenieXLibs.AAR_SHA256.length)
        assertEquals(89_045_966L, GenieXLibs.AAR_BYTES)
    }

    @Test fun adspPathPutsOursFirstWithSemicolons() {
        assertEquals("/a", GenieXRuntime.adspPath("/a", null))
        assertEquals("/a;/vendor/dsp", GenieXRuntime.adspPath("/a", "/vendor/dsp"))
        assertEquals("/a;/b", GenieXRuntime.adspPath("/a", "/b;/a;"))
    }

    @Test fun apkCarriesNoGenieXNativeLibrary() {
        val gradle = file("build.gradle.kts").readText()
        assertTrue(gradle.contains("\"fullImplementation\"(libs.geniex.android)"))
        for (p in listOf("**/libnpu_jni.so", "**/libgeniex*.so", "**/libggml*.so", "**/libQnn*.so", "**/libllama.so", "**/libmtmd.so", "**/libomp.so")) {
            assertTrue("exclude $p", gradle.contains("\"$p\""))
        }
        // our own libraries keep their names: none of the patterns can match them
        val ours = listOf("libiris_llama.so", "libiris_mnn.so", "liblitertlm_jni.so", "libsherpa-onnx-jni.so", "libproot.so")
        val globs = listOf("libnpu_jni.so", "libgeniex*.so", "libllama.so", "libllama-common.so", "libmtmd.so", "libggml*.so", "libomp.so", "libQnn*.so")
            .map { Regex("^" + Regex.escape(it).replace("*", "\\E.*\\Q") + "$") }
        for (o in ours) assertTrue(o, globs.none { it.matches(o) })
        val rules = file("proguard-rules.pro").readText()
        assertTrue(rules.contains("-keep class com.geniex.sdk.** { *; }"))
        val toml = file("../gradle/libs.versions.toml").readText()
        assertTrue(toml.contains("geniex = \"0.10.0\""))
    }

    // ---------------------------------------------------------------- QAIRT bundles

    @Test fun qairtBundleFolders() {
        val bundle = setOf("genie_config.json", "tokenizer.json", "htp_backend_ext_config.json", "weight_sharing_model_1_of_2.serialized.bin", "weight_sharing_model_2_of_2.serialized.bin", "metadata.json")
        assertTrue(QairtBundle.isFileSet(bundle))
        assertNull(QairtBundle.missing(bundle))
        assertEquals("genie_config.json", QairtBundle.modelPathName(bundle))
        assertFalse(QairtBundle.isVision(bundle))
        assertTrue(QairtBundle.isVision(bundle + "vision_encoder.bin"))
        assertFalse(QairtBundle.isFileSet(setOf("config.json", "llm.mnn", "llm.mnn.weight")))
        assertFalse(QairtBundle.isFileSet(setOf("genie_config.json", "tokenizer.json")))
        assertEquals("缺少 tokenizer.json", QairtBundle.missing(setOf("genie_config.json", "a.bin")))
        assertTrue(QairtBundle.missing(setOf("metadata.json", "a.bin", "tokenizer.json"))!!.contains("genie_config.json"))
        assertEquals("Qwen3 4B Instruct 2507", QairtBundle.displayName("Qwen3-4B-Instruct-2507_genie_bundle"))
        assertEquals(4096, QairtBundle.contextSize("""{"dialog":{"context":{"version":1,"size":4096,"n-vocab":151936}}}"""))
        assertNull(QairtBundle.contextSize("{}"))
        val m = LocalModel("Qwen3-4B_genie_bundle", "/x", 1, ModelFormat.QAIRT)
        assertTrue(m.isFolder)
        assertNull(m.quantization)
        assertEquals("GenieX · QAIRT", ModelFormat.QAIRT.label)
        assertNull(ModelFormat.fromFileName("weights.bin"))
    }

    // ---------------------------------------------------------------- vision

    @Test fun mmprojNextToTheModel() {
        val dir = listOf("Qwen3-VL-2B-Instruct-Q4_0.gguf", "mmproj-Qwen3-VL-2B-Instruct-F16.gguf", "Llama-3.2-1B-Q4_0.gguf")
        assertEquals("mmproj-Qwen3-VL-2B-Instruct-F16.gguf", GenieXVision.findMmproj("Qwen3-VL-2B-Instruct-Q4_0.gguf", dir))
        assertNull(GenieXVision.findMmproj("Llama-3.2-1B-Q4_0.gguf", dir))
        assertNull(GenieXVision.findMmproj("mmproj-Qwen3-VL-2B-Instruct-F16.gguf", dir))
        // a single unnamed projector only for a VLM-looking model
        assertEquals("mmproj-model-f16.gguf", GenieXVision.findMmproj("gemma-3-4b-it-Q4_0.gguf", listOf("gemma-3-4b-it-Q4_0.gguf", "mmproj-model-f16.gguf")))
        assertNull(GenieXVision.findMmproj("Qwen3-4B-Q4_0.gguf", listOf("Qwen3-4B-Q4_0.gguf", "mmproj-model-f16.gguf")))
        assertTrue(GenieXVision.isProjector("mmproj-x.gguf"))
        assertFalse(GenieXVision.isProjector("x.gguf"))
    }

    // ---------------------------------------------------------------- multi-turn (QAIRT KV)

    @Test fun qairtContinuesOnlyAnUnchangedConversation() {
        val u1 = GenieXTurns.Msg("user", "hi")
        val a1 = GenieXTurns.Msg("assistant", "hello")
        val sys = GenieXTurns.Msg("system", "be brief")
        val cached = listOf(sys, u1, a1)
        assertTrue(GenieXTurns.canContinue(cached, cached + GenieXTurns.Msg("user", "more")))
        assertFalse("first turn", GenieXTurns.canContinue(emptyList(), cached))
        assertFalse("regenerate", GenieXTurns.canContinue(cached, listOf(sys, u1)))
        assertFalse("edited reply", GenieXTurns.canContinue(cached, listOf(sys, u1, a1.copy(content = "x"), GenieXTurns.Msg("user", "y"))))
        assertFalse("changed system prompt", GenieXTurns.canContinue(cached, listOf(sys.copy(content = "new"), u1, a1, GenieXTurns.Msg("user", "y"))))
        assertFalse("same history", GenieXTurns.canContinue(cached, cached))
        assertFalse("cache ends on a user turn", GenieXTurns.canContinue(listOf(sys, u1), listOf(sys, u1, GenieXTurns.Msg("user", "z"))))
    }

    // ---------------------------------------------------------------- sampling / results / errors

    private fun sampling(t: Float = 0.7f, seed: Int = -1) = SamplingConfig(
        temperature = t, topP = 0.95f, topK = 40, minP = 0.05f, repeatPenalty = 1.1f, maxNewTokens = 512,
        disableThinking = false, seed = seed,
    )

    @Test fun samplerZeroMeansDefaultSoGreedyIsTopK1() {
        val a = GenieXSampling.args(sampling())
        assertEquals(0.7f, a.temperature); assertEquals(40, a.topK); assertEquals(0, a.seed)
        val g = GenieXSampling.args(sampling(t = 0f, seed = 7))
        assertEquals(1, g.topK); assertEquals(7, g.seed)
    }

    @Test fun stopReasons() {
        assertEquals(StopReason.END, GenieXSampling.stopReason("eos", false, 10, 512))
        assertEquals(StopReason.END, GenieXSampling.stopReason("stop_sequence", false, 10, 512))
        assertEquals(StopReason.CANCELLED, GenieXSampling.stopReason("user", false, 10, 512))
        assertEquals(StopReason.CANCELLED, GenieXSampling.stopReason("eos", true, 10, 512))
        assertEquals(StopReason.MAX_TOKENS, GenieXSampling.stopReason("length", false, 512, 512))
        assertEquals(StopReason.CONTEXT_FULL, GenieXSampling.stopReason("length", false, 100, 512))
    }

    @Test fun thinkingModels() {
        assertTrue(GenieXSampling.thinkingModel("Qwen3-4B-Q4_0.gguf"))
        assertFalse(GenieXSampling.thinkingModel("Qwen3-4B-Instruct-2507_genie_bundle"))
        assertTrue(GenieXSampling.thinkingModel("Qwen3-4B-Thinking-2507-Q4_0.gguf"))
        assertTrue(GenieXSampling.thinkingModel("DeepSeek-R1-Distill-Qwen-1.5B-Q4_0.gguf"))
        assertFalse(GenieXSampling.thinkingModel("Llama-3.2-3B-Instruct-Q4_0.gguf"))
    }

    @Test fun errorsAreChineseAndKeepTheOriginal() {
        val e = GenieX.error("GenieX 在 NPU（llama.cpp）上加载失败", RuntimeException("Llm create failed: File not found or inaccessible"))
        assertTrue(e.startsWith("GenieX 在 NPU"))
        assertTrue(e.contains("RuntimeException: Llm create failed: File not found or inaccessible"))
        val nested = GenieX.error("x", IllegalStateException("outer", java.io.IOException("inner")))
        assertTrue(nested.contains("inner"))
        assertEquals("y", GenieX.error("y", null))
        assertTrue(GenieX.codeHint("Llm create failed: Parameter not supported by this plugin")!!.contains("QAIRT"))
        assertTrue(GenieX.codeHint("Llm create failed: File not found or inaccessible")!!.contains("tokenizer.json"))
        assertNull(GenieX.codeHint("something else"))
    }

    // ---------------------------------------------------------------- project rules

    @Test fun newSourcesFollowTheUiRules() {
        val files = listOf(
            "src/main/java/com/iris/localai/ui/settings/GenieXSettings.kt",
            "src/full/java/com/iris/localai/engine/geniex/GenieXEngine.kt",
            "src/full/java/com/iris/localai/engine/geniex/GenieXRuntime.kt",
            "src/main/java/com/iris/localai/engine/geniex/GenieX.kt",
        ).map { file(it).readText() }
        for (f in files) {
            assertFalse(f.contains("tween("))
            assertFalse(f.contains("Icons.Outlined") || f.contains("Icons.Rounded") || f.contains("Icons.Filled"))
        }
        // The SDK's GenieXSdk class hard-codes System.loadLibrary("npu_jni") from the APK: never touched.
        val engine = files[1] + files[2]
        assertFalse(engine.contains("import com.geniex.sdk.GenieXSdk") || engine.contains("GenieXSdk.getInstance"))
        assertFalse(engine.contains("import com.geniex.sdk.ModelManagerWrapper"))
        assertTrue(files[2].contains("GENIEX_PLUGIN_PATH") && files[2].contains("GENIEX_QAIRT_LIB"))
    }
}

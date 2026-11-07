package com.iris.localai.v245

import com.iris.localai.agent.AgentCallbacks
import com.iris.localai.agent.AgentLoop
import com.iris.localai.agent.AgentModel
import com.iris.localai.agent.AgentOutput
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.AgentTool
import com.iris.localai.agent.ModelTurn
import com.iris.localai.agent.Permission
import com.iris.localai.agent.StepStatus
import com.iris.localai.agent.ToolCall
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.UserDecision
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.agent.WriteFileTool
import com.iris.localai.data.companionOf
import com.iris.localai.data.mmprojTargetName
import com.iris.localai.engine.NpuIds
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.llama.MmprojPairing
import com.iris.localai.engine.llama.VisionGpu
import com.iris.localai.ui.agent.PermissionAskFold
import com.iris.localai.ui.components.FoldMotion
import com.iris.localai.ui.markdown.ZoomMath
import com.iris.localai.voice.CustomTts
import com.iris.localai.voice.CustomTtsType
import com.iris.localai.voice.TtsDetect
import com.iris.localai.voice.TtsDetectResult
import com.iris.localai.voice.TtsReject
import com.iris.localai.data.RecommendedCatalog as R
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V245Test {
    private lateinit var tmp: File

    @Before fun setUp() { tmp = Files.createTempDirectory("iris245").toFile() }
    @After fun tearDown() { tmp.deleteRecursively() }

    // ---- Issue #12: projector for a model imported before 2.3.5 (flat in models/) ----

    @Test fun mmprojNameForOldFlatModel() {
        val model = "gemma-3-4b-it-Q4_K_M.gguf"
        // generic upstream names would collide between models in the shared folder
        val n = mmprojTargetName(model, "mmproj-F16.gguf", flat = true)
        assertEquals("gemma-3-4b-it-Q4_K_M-mmproj-F16.gguf", n)
        assertTrue(companionOf(model, n)) // deleted with the model
        assertEquals(n, MmprojPairing.pick(model, listOf(model, n, "mmproj-F16.gguf", "other.gguf"))) // pairs automatically
        // a file without "mmproj" in its name still gets it
        assertEquals("gemma-3-4b-it-Q4_K_M-mmproj-vision.gguf", mmprojTargetName(model, "vision.gguf", flat = true))
        // already tied to the model: unchanged
        assertEquals("gemma-3-4b-it-Q4_K_M-mmproj-F16.gguf", mmprojTargetName(model, "gemma-3-4b-it-Q4_K_M-mmproj-F16.gguf", flat = true))
        // a model in its own folder (2.3.5+): name as before
        assertEquals("mmproj-F16.gguf", mmprojTargetName(model, "mmproj-F16.gguf", flat = false))
        assertEquals("mmproj-x.gguf", mmprojTargetName(model, "x.gguf", flat = false))
        // split model: stem without the part suffix
        assertEquals("Big-Q4-mmproj-f16.gguf", mmprojTargetName("Big-Q4-00001-of-00002.gguf", "mmproj-f16.gguf", flat = true))
        val long = "a".repeat(170) + ".gguf"
        val ln = mmprojTargetName(long, "mmproj-model-f16.gguf", flat = true)
        assertTrue(ln.length <= 180 && "mmproj" in ln && ln.endsWith(".gguf"))
    }

    // ---- Issue #11: write_file cut by the length limit ----

    @Test fun agentTurnBudgetScalesWithContext() {
        assertEquals(1536, AgentOutput.maxNew(2048, 4096))
        assertEquals(3072, AgentOutput.maxNew(2048, 8192))
        assertEquals(6144, AgentOutput.maxNew(2048, 16384))
        assertEquals(8192, AgentOutput.maxNew(2048, 65536))
        assertEquals(512, AgentOutput.maxNew(512, 32768)) // a lower user limit is kept
        assertEquals(1536, AgentOutput.maxNew(4096, 2048))
    }

    @Test fun cutWriteKeepsCompleteLines() {
        val c = AgentOutput.cutWrite("line1\nline2\nlin")!!
        assertEquals("line1\nline2\n", c.kept)
        assertEquals(2, c.lines)
        assertEquals("line2", c.lastLine)
        assertNull(AgentOutput.cutWrite("no newline yet"))
        val note = AgentOutput.continueNote("a.html", c, appended = false)
        assertTrue("append=true" in note && "a.html" in note && "line2" in note)
        assertTrue(AgentOutput.callUnclosed("```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"a\", \"content\": \"x\n"))
        assertFalse(AgentOutput.callUnclosed("```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"a\", \"content\": \"x\"}}\n```"))
    }

    private class Model(private val turns: List<ModelTurn>) : AgentModel {
        private var i = 0
        val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            seen += messages
            return turns.getOrElse(i++) { ModelTurn("完成。") }
        }
    }

    private class Cb : AgentCallbacks {
        var steps: List<AgentStep> = emptyList()
        override suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision = UserDecision.ALLOW_ONCE
        override fun onSteps(steps: List<AgentStep>) { this.steps = steps }
    }

    private fun env() = ToolEnv(WorkspaceSandbox(File(tmp, "ws")), UndoStore(File(tmp, "undo")))

    @Test fun truncatedWriteSavesCompleteLinesAndAsksForTheRest() = runBlocking {
        val cut = "```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"page.html\", \"content\": \"<html>\\n<body>\\n<p>hel"
        val more = "```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"page.html\", \"content\": \"<p>hello</p>\\n</body>\\n</html>\\n\", \"append\": true}}\n```"
        val m = Model(listOf(ModelTurn(cut, truncated = true), ModelTurn(more), ModelTurn("写好了。")))
        val e = env()
        val cb = Cb()
        AgentLoop(m, listOf(WriteFileTool), { Permission.ALLOW }, e, cb).run(listOf(PromptMessage(PromptMessage.USER, "写网页")))
        val f = File(tmp, "ws/page.html")
        assertEquals("<html>\n<body>\n<p>hello</p>\n</body>\n</html>\n", f.readText())
        val first = cb.steps.first()
        assertEquals(StepStatus.OK, first.status)
        assertTrue(first.result, "2" in first.result)
        // the model was told where to continue
        val toolMsg = m.seen[1].last().content
        assertTrue(toolMsg, "append=true" in toolMsg && "<body>" in toolMsg)
    }

    @Test fun truncatedWriteWithoutACompleteLineWritesNothing() = runBlocking {
        val cut = "```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"a.txt\", \"content\": \"only part of one li"
        val m = Model(listOf(ModelTurn(cut, truncated = true), ModelTurn("好的。")))
        val cb = Cb()
        AgentLoop(m, listOf(WriteFileTool), { Permission.ALLOW }, env(), cb).run(listOf(PromptMessage(PromptMessage.USER, "写")))
        assertFalse(File(tmp, "ws/a.txt").exists())
        assertEquals(StepStatus.ERROR, cb.steps.first().status)
    }

    @Test fun untruncatedWriteUnchanged() = runBlocking {
        val call = "```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"b.txt\", \"content\": \"x\\ny\"}}\n```"
        val m = Model(listOf(ModelTurn(call), ModelTurn("好。")))
        val cb = Cb()
        AgentLoop(m, listOf(WriteFileTool), { Permission.ALLOW }, env(), cb).run(listOf(PromptMessage(PromptMessage.USER, "写")))
        assertEquals("x\ny", File(tmp, "ws/b.txt").readText())
    }

    // ---- Issue #10 / #9 / #13 (pure parts of the UI fixes) ----

    @Test fun foldSnapsWhileTheListScrolls() {
        assertTrue(FoldMotion.snap(reduceMotion = false, listScrolling = true))
        assertTrue(FoldMotion.snap(reduceMotion = true, listScrolling = false))
        assertFalse(FoldMotion.snap(reduceMotion = false, listScrolling = false))
        assertEquals(50, FoldMotion.height(100, 0.5f))
        assertEquals(100, FoldMotion.height(100, 1.08f)) // spring overshoot clamped
        assertEquals(0, FoldMotion.height(100, -0.1f))
    }

    @Test fun outsideTapFoldsTheToolQuestion() {
        assertEquals(PermissionAskFold.Action.COLLAPSE, PermissionAskFold.onDismiss(collapsible = true))
        assertEquals(PermissionAskFold.Action.DENY, PermissionAskFold.onDismiss(collapsible = false))
    }

    @Test fun zoomStaysInsideThePicture() {
        assertEquals(6f, ZoomMath.scale(4f, 2f))
        assertEquals(1f, ZoomMath.scale(1f, 0.5f))
        assertEquals(500f, ZoomMath.axis(900f, 2f, 1000f))
        assertEquals(-900f, ZoomMath.axis(-900f, 2f, 2000f))
        assertEquals(-1000f, ZoomMath.axis(-1500f, 2f, 2000f))
        assertEquals(0f, ZoomMath.axis(30f, 1f, 1000f))
    }

    // ---- 识图用 GPU ----

    @Test fun visionGpuSwitch() {
        assertFalse(VisionGpu.useGpu(modelOnGpu = true, npu = false, gpuName = "Adreno (TM) 830", allowAdreno = false)) // 2.4.4 default
        assertTrue(VisionGpu.useGpu(modelOnGpu = true, npu = false, gpuName = "Adreno (TM) 830", allowAdreno = true))
        assertTrue(VisionGpu.useGpu(modelOnGpu = true, npu = false, gpuName = "Mali-G925", allowAdreno = false))
        assertFalse(VisionGpu.useGpu(modelOnGpu = false, npu = false, gpuName = "Mali-G925", allowAdreno = true))
        assertFalse(VisionGpu.useGpu(modelOnGpu = true, npu = true, gpuName = "Adreno (TM) 830", allowAdreno = true))
        assertFalse(com.iris.localai.data.AppSettings().visionGpu)
    }

    // ---- sherpa-onnx TTS import ----

    private fun ok(r: TtsDetectResult) = (r as TtsDetectResult.Ok).spec
    private fun rejected(r: TtsDetectResult) = (r as TtsDetectResult.Rejected).reason

    @Test fun detectsKokoro() {
        val files = listOf("model.onnx", "model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt",
            "date-zh.fst", "number-zh.fst", "phone-zh.fst", "espeak-ng-data/phontab", "espeak-ng-data/en_dict", "dict/jieba.dict.utf8", "README.md")
        val s = ok(TtsDetect.detect(files))
        assertEquals(CustomTtsType.KOKORO, s.type)
        assertEquals("model.int8.onnx", s.model)
        assertEquals("voices.bin", s.voices)
        assertEquals(listOf("lexicon-us-en.txt", "lexicon-zh.txt"), s.lexicons)
        assertEquals("espeak-ng-data", s.dataDir)
        assertEquals(3, s.ruleFsts.size)
        assertEquals(TtsReject.NO_TEXT_FRONTEND, rejected(TtsDetect.detect(listOf("model.onnx", "voices.bin", "tokens.txt"))))
    }

    @Test fun detectsMatcha() {
        val s = ok(TtsDetect.detect(listOf("model-steps-3.onnx", "vocos-22khz-univ.onnx", "lexicon.txt", "tokens.txt", "date.fst", "number.fst", "phone.fst")))
        assertEquals(CustomTtsType.MATCHA, s.type)
        assertEquals("model-steps-3.onnx", s.model)
        assertEquals("vocos-22khz-univ.onnx", s.vocoder)
        assertEquals(listOf("lexicon.txt"), s.lexicons)
        // English Matcha with espeak and a hifigan vocoder
        val en = ok(TtsDetect.detect(listOf("model-steps-3.onnx", "hifigan_v2.onnx", "tokens.txt", "espeak-ng-data/phontab")))
        assertEquals("hifigan_v2.onnx", en.vocoder)
        assertEquals(TtsReject.MATCHA_NO_VOCODER, rejected(TtsDetect.detect(listOf("model-steps-3.onnx", "lexicon.txt", "tokens.txt"))))
    }

    @Test fun detectsVitsPiperAndMelo() {
        val piper = ok(TtsDetect.detect(listOf("en_US-amy-low.onnx", "en_US-amy-low.onnx.json", "tokens.txt", "espeak-ng-data/phontab", "MODEL_CARD")))
        assertEquals(CustomTtsType.VITS, piper.type)
        assertEquals("en_US-amy-low.onnx", piper.model)
        assertEquals("espeak-ng-data", piper.dataDir)
        val melo = ok(TtsDetect.detect(listOf("model.onnx", "lexicon.txt", "tokens.txt", "date.fst", "number.fst")))
        assertEquals(CustomTtsType.VITS, melo.type)
        assertEquals(listOf("lexicon.txt"), melo.lexicons)
        // fp32 before int8 for VITS
        assertEquals("model.onnx", ok(TtsDetect.detect(listOf("model.int8.onnx", "model.onnx", "lexicon.txt", "tokens.txt"))).model)
        assertEquals(TtsReject.NO_TEXT_FRONTEND, rejected(TtsDetect.detect(listOf("model.onnx", "tokens.txt"))))
    }

    @Test fun rejectsWhatSherpaCannotRun() {
        assertEquals(TtsReject.PIPER_RAW, rejected(TtsDetect.detect(listOf("zh_CN-huayan-medium.onnx", "zh_CN-huayan-medium.onnx.json"))))
        assertEquals(TtsReject.GPT_SOVITS_RVC, rejected(TtsDetect.detect(listOf("s2G488k.pth", "s1bert25hz.ckpt", "added_IVF.index"))))
        assertEquals(TtsReject.NO_TOKENS, rejected(TtsDetect.detect(listOf("model.onnx", "lexicon.txt"))))
        assertEquals(TtsReject.EMPTY, rejected(TtsDetect.detect(emptyList())))
        assertEquals(TtsReject.NO_MODEL, rejected(TtsDetect.detect(listOf("readme.txt"))))
    }

    @Test fun archiveRootUrlAndIds() {
        assertEquals("vits-piper-en_US-amy-low/", TtsDetect.commonRoot(listOf("vits-piper-en_US-amy-low/tokens.txt", "vits-piper-en_US-amy-low/espeak-ng-data/phontab")))
        assertEquals("", TtsDetect.commonRoot(listOf("tokens.txt", "model.onnx")))
        assertEquals("", TtsDetect.commonRoot(listOf("a/x", "b/y")))
        assertNull(TtsDetect.checkUrl("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2"))
        assertNull(TtsDetect.checkUrl("https://example.com/x.tar.bz2?download=1"))
        assertEquals(TtsReject.BAD_URL, TtsDetect.checkUrl("http://example.com/x.tar.bz2"))
        assertEquals(TtsReject.BAD_URL, TtsDetect.checkUrl("https://example.com/model.onnx"))
        assertEquals("vits-piper-en_US-amy-low", CustomTts.slug("vits-piper-en_US-amy-low.tar.bz2"))
        assertEquals("tts", CustomTts.slug("中文模型"))
        assertEquals("a-2", CustomTts.unique("a", setOf("a")))
        assertEquals("a-3", CustomTts.unique("a", setOf("a", "a-2")))
        assertEquals("x", CustomTts.idOf("custom:x"))
        assertNull(CustomTts.idOf("kokoro-int8-multi-lang-v1_1"))
        assertEquals(0, CustomTts.speaker(5, 1))
        assertEquals(10, CustomTts.speaker(50, 11))
        assertEquals(3, CustomTts.speaker(3, 103))
    }

    // ---- Dimensity 9500: Qwen3-0.6B NPU ----

    @Test fun qwen3NpuForDimensity9500Only() {
        val file = "Qwen3-0.6B.mediatek.mt6993.litertlm"
        val item = R.byId("litert-community/Qwen3-0.6B#mt6993")
        assertNotNull(item)
        item!!
        assertEquals("mt6993", item.onlySoc)
        assertEquals(R.Engine.LITERT, item.engine)
        assertEquals(1_202_913_280L, item.sizeBytes)
        assertEquals("Apache-2.0", item.licence)
        assertEquals(R.Kind.Hub("litert-community/Qwen3-0.6B", file), item.kind)
        assertEquals("mt6993", NpuIds.targetSoc(file))
        assertNull(NpuIds.kvCapacity(file))
        assertEquals(4096, NpuIds.defaultKv(file))
        assertEquals(4096, NpuIds.historyBudget(file))
        assertEquals(1024, NpuIds.defaultKv("Unknown_mt6993.litertlm"))
        val before = R.deviceSoc
        try {
            R.deviceSoc = "mt6993"
            assertTrue(R.section(R.Section.CHAT).any { it.id == item.id })
            R.deviceSoc = "mt6991"
            assertFalse(R.section(R.Section.CHAT).any { it.id == item.id })
            R.deviceSoc = null
            assertFalse(R.section(R.Section.CHAT).any { it.id == item.id })
        } finally { R.deviceSoc = before }
        // the generic MNN / GGUF Qwen3 0.6B entries never claim the NPU file as theirs
        assertFalse(R.matchesLocal("taobao-mnn/Qwen3-0.6B-MNN", file))
    }

    @Test fun newStringsInAllLanguages() {
        val res = File("app/src/main/res")
        val keys = listOf("mm245_copying", "agent245_cut_saved", "ask245_pill", "vg245_title", "tts245_section", "tts245_piper_raw", "mtk245_qwen_purpose")
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-de", "values-es", "values-ja", "values-hi")) {
            val t = File(res, "$d/strings.xml").readText()
            for (k in keys) assertTrue("$d $k", "name=\"$k\"" in t)
        }
    }
}

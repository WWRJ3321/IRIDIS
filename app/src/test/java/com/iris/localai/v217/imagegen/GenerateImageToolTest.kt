package com.iris.localai.v217.imagegen

import com.iris.localai.agent.AgentPrompt
import com.iris.localai.agent.AgentSettings
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.AgentTools
import com.iris.localai.agent.GenerateImageTool
import com.iris.localai.agent.ImageGenBackend
import com.iris.localai.agent.Permission
import com.iris.localai.agent.SandboxException
import com.iris.localai.agent.StepImages
import com.iris.localai.agent.StepStatus
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.ToolGroup
import com.iris.localai.agent.ToolResult
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.data.ChatMessage
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.imagegen.ImageRequest
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class GenerateImageToolTest {
    private fun args(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) ->
        k to when (v) { is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    })

    private fun tmp(): File = Files.createTempDirectory("genimg").toFile()

    /** Records the request; returns fixed bytes; saves like the repository does. */
    private class FakeBackend(val root: File, val models: List<ImageModel>, val fail: String? = null) : ImageGenBackend {
        var last: ImageRequest? = null
        var lastModel: ImageModel? = null
        override fun runnableModels() = models
        override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (Int, Int) -> Unit): Result<ByteArray> {
            last = req; lastModel = model
            onProgress(1, req.steps)
            return if (fail != null) Result.failure(IllegalStateException(fail)) else Result.success(byteArrayOf(1, 2, 3))
        }
        override fun save(png: ByteArray, req: ImageRequest, model: ImageModel): File =
            ImageGenFiles.outputFile(root, LocalDateTime.of(2026, 10, 22, 2, 4, 5)).also { it.parentFile?.mkdirs(); it.writeBytes(png) }
    }

    private fun env(root: File, backend: ImageGenBackend?) = ToolEnv(WorkspaceSandbox(root), UndoStore(File(root, ".undo")), imageGen = backend)

    // ---------------------------------------------------------------- parsing

    @Test fun argsParseLenientlyWithDefaults() {
        val p = GenerateImageTool.parse(args("prompt" to "a cat"))
        assertEquals("a cat", p.prompt)
        assertEquals("", p.negative)
        assertNull(p.width); assertNull(p.height); assertNull(p.steps); assertNull(p.cfg); assertNull(p.image)
        assertEquals(-1L, p.seed)
        assertEquals(0.75f, p.strength)

        val q = GenerateImageTool.parse(args(
            "prompt" to " dog ", "negative_prompt" to "blur", "width" to "768", "height" to 640, "steps" to "12",
            "cfg" to "5.5", "seed" to "42", "image" to "in/ref.png", "strength" to "0.4",
        ))
        assertEquals("dog", q.prompt); assertEquals("blur", q.negative)
        assertEquals(768, q.width); assertEquals(640, q.height); assertEquals(12, q.steps)
        assertEquals(5.5f, q.cfg); assertEquals(42L, q.seed); assertEquals("in/ref.png", q.image); assertEquals(0.4f, q.strength)

        // blanks / junk = absent; negative seed = random
        val r = GenerateImageTool.parse(args("prompt" to "x", "seed" to "-5", "width" to "", "steps" to "abc", "image" to " "))
        assertEquals(-1L, r.seed); assertNull(r.width); assertNull(r.steps); assertNull(r.image)
    }

    @Test fun missingPromptIsAnArgumentError() {
        for (a in listOf(args(), args("prompt" to "  "), args("negative_prompt" to "x"))) {
            try { GenerateImageTool.parse(a); fail("expected error") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("prompt")) }
        }
    }

    @Test fun requestUsesModelDefaultsAndNormalizes() {
        val m = ImageCatalog.SD15_MNN
        val req = GenerateImageTool.request(GenerateImageTool.parse(args("prompt" to "x", "seed" to "7", "steps" to "999", "strength" to "3")), m, null)
        assertEquals(512, req.width); assertEquals(512, req.height) // fixed size
        assertEquals(50, req.steps); assertEquals(m.defaultCfg, req.cfg); assertEquals(7L, req.seed); assertEquals(1f, req.strength)
        val def = GenerateImageTool.request(GenerateImageTool.parse(args("prompt" to "x")), m, null)
        assertEquals(m.defaultSteps, def.steps)
        assertTrue(def.seed >= 0)
    }

    // ---------------------------------------------------------------- sandbox

    @Test fun referenceImageMustStayInTheWorkspace() {
        val root = tmp()
        val e = env(root, FakeBackend(root, listOf(ImageCatalog.SD15_MNN)))
        for (bad in listOf("../x.png", "a/../../x.png", "/etc/x", "/etc/passwd", "~/x.png")) {
            try {
                runBlocking { GenerateImageTool.runSuspend(args("prompt" to "p", "image" to bad), e) {} }
                fail("accepted $bad")
            } catch (_: SandboxException) {}
        }
        root.deleteRecursively()
    }

    @Test fun missingReferenceImageIsNotFound() {
        val root = tmp()
        val r = runBlocking { GenerateImageTool.runSuspend(args("prompt" to "p", "image" to "nope.png"), env(root, FakeBackend(root, listOf(ImageCatalog.SD15_MNN)))) {} }
        assertFalse(r.ok); assertTrue(r.output.contains("not found"))
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- running

    @Test fun noModelGivesTheChineseHint() {
        val root = tmp()
        for (backend in listOf(null, FakeBackend(root, emptyList()))) {
            val r = runBlocking { GenerateImageTool.runSuspend(args("prompt" to "p"), env(root, backend)) {} }
            assertFalse(r.ok)
            assertTrue(r.output, r.output.contains("没有可用的生图模型：请在侧栏「生图」下载并运行自检"))
            assertTrue(r.images.isEmpty())
        }
        root.deleteRecursively()
    }

    @Test fun successReturnsSavedPathAndImages() {
        val root = tmp()
        val b = FakeBackend(root, listOf(ImageCatalog.SD15_QNN, ImageCatalog.SD15_MNN))
        val progress = ArrayList<String>()
        val r = runBlocking { GenerateImageTool.runSuspend(args("prompt" to "a cat", "seed" to "3"), env(root, b)) { progress += it } }
        assertTrue(r.output, r.ok)
        assertTrue(r.output, r.output.startsWith("saved: images/IMG_20261022_020405.png"))
        assertEquals(listOf("images/IMG_20261022_020405.png"), r.images)
        assertEquals(ImageCatalog.SD15_QNN, b.lastModel) // first runnable model
        assertEquals(3L, b.last!!.seed)
        assertTrue(progress.isNotEmpty())
        root.deleteRecursively()
    }

    @Test fun editPicksAModelThatTakesAReferenceAndPassesTheSandboxedPath() {
        val root = tmp()
        File(root, "in").mkdirs(); File(root, "in/ref.png").writeBytes(byteArrayOf(9))
        val b = FakeBackend(root, listOf(ImageCatalog.SD15_QNN, ImageCatalog.SD15_MNN))
        val r = runBlocking { GenerateImageTool.runSuspend(args("prompt" to "p", "image" to "in/ref.png", "strength" to "0.5"), env(root, b)) {} }
        assertTrue(r.output, r.ok)
        assertEquals(ImageCatalog.SD15_MNN, b.lastModel)
        assertEquals(File(root, "in/ref.png").canonicalPath, b.last!!.initImagePath)
        assertEquals(0.5f, b.last!!.strength)
        // only the NPU model: it cannot edit
        val npu = runBlocking { GenerateImageTool.runSuspend(args("prompt" to "p", "image" to "in/ref.png"), env(root, FakeBackend(root, listOf(ImageCatalog.SD15_QNN)))) {} }
        assertFalse(npu.ok)
        root.deleteRecursively()
    }

    @Test fun engineFailureIsAnError() {
        val root = tmp()
        val r = runBlocking { GenerateImageTool.runSuspend(args("prompt" to "p"), env(root, FakeBackend(root, listOf(ImageCatalog.SD15_MNN), fail = "运行库未就绪"))) {} }
        assertFalse(r.ok); assertTrue(r.output.contains("运行库未就绪")); assertTrue(r.images.isEmpty())
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- registry + prompt

    @Test fun toolIsRegisteredInItsGroupAndOnByDefault() {
        assertTrue(AgentTools.all.contains(GenerateImageTool))
        assertEquals(GenerateImageTool, AgentTools.byName("generate_image"))
        assertEquals(ToolGroup.IMAGE, GenerateImageTool.group)
        assertEquals("生图", ToolGroup.IMAGE.label)
        assertTrue(GenerateImageTool.defaultEnabled)
        assertTrue(AgentSettings().isEnabled(GenerateImageTool))
        assertEquals(Permission.ALLOW, AgentSettings().permission(GenerateImageTool))
        // listed only while a runnable image model is downloaded
        assertTrue(AgentTools.enabled(AgentSettings(), historyAvailable = true, memoryOn = true, imageGenOn = true).contains(GenerateImageTool))
        assertFalse(AgentTools.enabled(AgentSettings(), historyAvailable = true, memoryOn = true).contains(GenerateImageTool))
        assertFalse(AgentTools.enabled(AgentSettings(enabled = mapOf("generate_image" to false)), true, true, imageGenOn = true).contains(GenerateImageTool))
        assertEquals("generate_image(prompt, image?): on-device picture; omit image for a new picture; image ONLY to edit an existing file the user attached or named", GenerateImageTool.promptLine)
        assertTrue(AgentPrompt.build(AgentTools.all).contains(GenerateImageTool.promptLine))
    }

    @Test fun promptWithEveryToolStaysUnder600() {
        for (style in AgentPrompt.CallStyle.entries) {
            val p = AgentPrompt.build(AgentTools.all + AgentTools.onDemand, "简短", style)
            println("PROMPT_TOKENS $style ${TokenEstimator.estimate(p)}")
            assertTrue("$style ~${TokenEstimator.estimate(p)}", TokenEstimator.estimate(p) <= 600)
        }
    }

    // ---------------------------------------------------------------- data compat

    @Test fun oldSavedJsonWithoutImagesStillDecodes() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val step = json.decodeFromString(AgentStep.serializer(), """{"id":"s1","tool":"read_file","args":"path=a","result":"3 行","status":"OK","detail":"x"}""")
        assertEquals(emptyList<String>(), step.images)
        assertEquals(StepStatus.OK, step.status)
        // strict decoder too (no ignoreUnknownKeys): a missing field with a default is fine
        assertEquals(emptyList<String>(), Json.decodeFromString(AgentStep.serializer(), """{"tool":"x"}""").images)
        val msg = json.decodeFromString(ChatMessage.serializer(),
            """{"id":"m1","role":"ASSISTANT","content":"好","agentSteps":[{"id":"s1","tool":"web_search","status":"OK"}]}""")
        assertEquals(emptyList<String>(), msg.agentSteps.single().images)
        // round trip keeps images; empty images are not written (encodeDefaults = false)
        val withImg = step.copy(images = listOf("images/IMG_1.png"))
        assertEquals(withImg, json.decodeFromString(AgentStep.serializer(), json.encodeToString(AgentStep.serializer(), withImg)))
        assertFalse(json.encodeToString(AgentStep.serializer(), step).contains("images"))
        // ToolResult: old call sites without images keep compiling and default to none
        assertEquals(emptyList<String>(), ToolResult(true, "o", "s").images)
    }

    @Test fun stepImagesJoinTheReplyOnce() {
        val steps = listOf(AgentStep(tool = "generate_image", images = listOf("images/IMG_a.png")), AgentStep(tool = "read_file"))
        assertEquals("好了\n\n![](images/IMG_a.png)", StepImages.appendTo("好了", steps))
        assertEquals("看 ![](images/IMG_a.png)", StepImages.appendTo("看 ![](images/IMG_a.png)", steps))
        assertEquals("![](images/IMG_a.png)", StepImages.appendTo("", steps))
        assertEquals("x", StepImages.appendTo("x", listOf(AgentStep(tool = "read_file"))))
    }

    @Test fun chatViewModelAttachesStepImages() {
        val vm = listOf("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt", "app/src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
            .map { File(it) }.first { it.exists() }.readText()
        assertTrue(vm.contains("StepImages.appendTo("))
    }
}

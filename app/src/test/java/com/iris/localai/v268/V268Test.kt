package com.iris.localai.v268

import com.iris.localai.agent.AgentSettings
import com.iris.localai.agent.GenerateImageTool
import com.iris.localai.data.UpdateNotes
import com.iris.localai.imagegen.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2.6.8: 生图低内存模式、提示词扩写、标签库、Anima Turbo（实验）。 */
class V268Test {
    private fun file(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }
    private fun src(p: String) = file(p).readText()
    private fun root(p: String) = listOf(p, "../$p").map { File(it) }.first { it.exists() }

    @Test fun version() {
        assertTrue(UpdateNotes.RELEASES.any { it.version == "2.6.8" && it.of(UpdateNotes.Tag.EXPERIMENTAL).isNotEmpty() })
        assertTrue(root("CHANGELOG.md").readText().contains("## 2.6.8（"))
    }

    // ---- 1 低内存模式
    @Test fun lowMemFit() {
        assertEquals(768 to 768, ImageLowMem.fit(1024, 1024))
        assertEquals(512 to 512, ImageLowMem.fit(512, 512))
        val (w, h) = ImageLowMem.fit(1024, 576)
        assertEquals(768, w); assertEquals(0, h % 16); assertTrue(h in 416..432)
        assertEquals(ImageLowMem.GPU, ImageLowMem.backend(SdRules.GPU, true))
        assertEquals(SdRules.GPU, ImageLowMem.backend(SdRules.GPU, false))
        assertEquals(SdRules.CPU, ImageLowMem.backend(SdRules.CPU, true))
        assertTrue(ImageLowMem.GPU.contains("vae=cpu") && ImageLowMem.GPU.contains("te=cpu"))
    }

    @Test fun lowMemRequest() {
        val m = ImageCatalog.ANIMA_TURBO
        val r = ImageRequest("x", width = 1024, height = 1024, lowMem = true).normalize(m)
        assertEquals(768, r.width); assertEquals(768, r.height)
        assertEquals(1024, ImageRequest("x", width = 1024, height = 1024).normalize(m).width)
        // 固定尺寸模型不受影响
        assertEquals(512, ImageRequest("x", width = 1024, height = 1024, lowMem = true).normalize(ImageCatalog.ANYTHING_V5).width)
        val t = GenerateImageTool.request(GenerateImageTool.Parsed("a cat", "", 1024, 1024, null, null, 1L, null, 0.75f), m, null, lowMem = true)
        assertTrue(t.lowMem); assertEquals(768, t.width)
    }

    @Test fun lowMemWiring() {
        assertFalse(AgentSettings().imageLowMem)
        assertTrue(AgentSettings().imagePromptExpand)
        assertTrue(src("src/main/java/com/iris/localai/imagegen/ImageGenProcess.kt").contains("\"lowMem\""))
        val e = src("src/main/java/com/iris/localai/imagegen/SdCppEngine.kt")
        assertTrue(e.contains("if (req.lowMem) freeLocked()"))
        assertTrue(src("src/main/java/com/iris/localai/server/AppApiMedia.kt").contains("makeRoomAlways"))
        // 不抄 Local Dream（CC BY-NC）
        assertFalse(src("src/main/java/com/iris/localai/imagegen/PromptKit.kt").contains("localdream", ignoreCase = true))
    }

    // ---- 2 扩写
    @Test fun expandRules() {
        assertTrue(PromptExpand.needed("一只猫"))
        assertFalse(PromptExpand.needed("masterpiece, best quality, 1girl"))
        assertFalse(PromptExpand.needed(""))
        assertFalse(PromptExpand.needed((1..50).joinToString(" ") { "word$it" }))
        assertTrue(PromptExpand.rules("1girl, smile", true).startsWith("masterpiece, best quality"))
        assertTrue(PromptExpand.rules("a cat.", false).startsWith("a cat,"))
        assertEquals("mine", PromptExpand.negative("mine", true))
        assertTrue(PromptExpand.negative("", true).contains("worst quality"))
        assertTrue(PromptExpand.system(true).contains("Danbooru"))
        assertFalse(PromptExpand.system(false).contains("Danbooru"))
    }

    @Test fun expandClean() {
        assertEquals("a fluffy cat on a sofa, warm light", PromptExpand.clean("<think>hmm</think>\nPrompt: \"a fluffy cat on a sofa, warm light\"", "cat"))
        assertNull(PromptExpand.clean("Sorry, I can't help with that.", "cat"))
        assertNull(PromptExpand.clean("ok", "cat"))
        assertNull(PromptExpand.clean("short", "a much longer original prompt than this"))
        assertTrue(PromptExpand.clean("x".repeat(2000), "cat")!!.length <= PromptExpand.MAX_LEN)
    }

    @Test fun toolExpand() {
        val p = GenerateImageTool.Parsed("a cat", "", null, null, null, null, -1L, null, 0.75f)
        val anima = ImageCatalog.ANIMA_TURBO
        assertTrue(GenerateImageTool.expand(p, anima, true).prompt.startsWith("masterpiece"))
        assertEquals("a cat", GenerateImageTool.expand(p, anima, false).prompt)
        assertTrue(GenerateImageTool.wantsTags(JsonObject(mapOf("list_tags" to JsonPrimitive("true")))))
        assertFalse(GenerateImageTool.wantsTags(JsonObject(mapOf("prompt" to JsonPrimitive("x")))))
        assertTrue(GenerateImageTool.promptLine.contains("list_tags"))
    }

    // ---- 3 标签库
    @Test fun tagsBuiltin() {
        for (c in TagCat.entries) assertTrue(c.name, PromptTags.BUILTIN.count { it.cat == c } >= 4)
        assertEquals(PromptTags.BUILTIN.size, PromptTags.BUILTIN.map { it.key }.toSet().size)
        val girl = PromptTags.BUILTIN.first { it.zh == "一个女孩" }
        assertEquals("1girl, solo", girl.text(true)); assertEquals("a girl", girl.text(false))
        // Danbooru 写法用空格不用下划线
        assertTrue(PromptTags.BUILTIN.all { !(it.danbooru ?: "").contains('_') })
        val list = PromptTags.forModel(PromptTags.BUILTIN, true)
        assertTrue(list.contains("Subject: ") && list.contains("1girl"))
    }

    @Test fun tagsAppend() {
        assertEquals("a cat", PromptTags.append("", "a cat"))
        assertEquals("a cat, night", PromptTags.append("a cat,", "night"))
        assertEquals("a cat, night", PromptTags.append("a cat, night", "Night"))
        assertEquals("x", PromptTags.append("x", " "))
    }

    @Test fun tagStore() {
        var d = TagStore.Data()
        d = TagStore.add(d, TagCat.STYLE, "吉卜力", "studio ghibli style")
        d = TagStore.add(d, TagCat.STYLE, "", "  ")
        assertEquals(1, d.custom.size)
        val builtin = PromptTags.BUILTIN.first { it.cat == TagCat.LIGHT }
        d = TagStore.remove(d, builtin)
        assertTrue(builtin.key in d.hidden)
        val back = TagStore.parse(TagStore.toJson(d))
        assertEquals(d, back)
        val merged = PromptTags.merged(back.custom, back.hidden)
        assertTrue(merged.any { it.en == "studio ghibli style" && it.custom })
        assertFalse(merged.any { it.key == builtin.key })
        d = TagStore.remove(back, back.custom.first())
        assertTrue(d.custom.isEmpty())
        assertEquals(TagStore.Data(), TagStore.parse("not json"))
        assertEquals(TagStore.Data(), TagStore.parse(null))
    }

    // ---- 4 Anima
    @Test fun animaRecipe() {
        val m = ImageCatalog.ANIMA_TURBO
        assertEquals(Backend.SDCPP, m.backend)
        assertTrue(m in ImageCatalog.known)
        assertTrue(m.tagStyle && ImageCatalog.ANYTHING_V5.tagStyle)
        assertEquals("仅限非商用，生成的图片可商用", m.licenseNote)
        assertTrue(m.licenseUrl.startsWith("https://huggingface.co/circlestone-labs/Anima/"))
        assertTrue(m.note.startsWith("实验"))
        assertEquals(8, m.defaultSteps); assertEquals(1f, m.defaultCfg)
        val paths = m.files.map { it.path }
        val sd = m.sdFiles!!
        assertTrue(sd.diffusion in paths && sd.llm in paths && sd.vae in paths)
        assertTrue(ImageCatalog.SD_LIB in m.files)
        // 全部指向原仓库，不自建镜像；不用坏转换
        val urls = m.files.map { it.url }
        assertTrue(urls.any { it.contains("vanes430/Anima-Turbo-V1.1-GGUF") && it.endsWith("Q4_0.gguf") })
        assertTrue(urls.any { it.contains("mradermacher/Qwen3-0.6B-Base-GGUF") })
        assertTrue(urls.any { it.contains("circlestone-labs/Anima") && it.endsWith("qwen_image_vae.safetensors") })
        assertFalse(urls.any { it.contains("Anima-2.9B-GGUF") || it.contains("WWRJ") && !it.contains("libiris_sd") })
        assertEquals(1_235_394_688L, m.files.first().size)
        assertTrue(src("src/main/java/com/iris/localai/ui/imagegen/ImageGenViewModel.kt").contains("licenseAsk"))
    }
}

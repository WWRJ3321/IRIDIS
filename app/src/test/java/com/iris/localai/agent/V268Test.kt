package com.iris.localai.agent

import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.GenerateImageTool
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
        val m = ImageCatalog.Z_IMAGE_TURBO
        val r = ImageRequest("x", width = 1024, height = 1024, lowMem = true).normalize(m)
        assertEquals(768, r.width); assertEquals(768, r.height)
        assertEquals(1024, ImageRequest("x", width = 1024, height = 1024).normalize(m).width)
        // 固定尺寸模型不受影响
        assertEquals(512, ImageRequest("x", width = 1024, height = 1024, lowMem = true).normalize(ImageCatalog.ANYTHING_V5).width)
        val t = GenerateImageTool.request(GenerateImageTool.Parsed("a cat", "", 1024, 1024, null, null, 1L, null, 0.75f), m, null, lowMem = true)
        assertTrue(t.lowMem); assertEquals(768, t.width)
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
        val anima = ImageCatalog.ANYTHING_V5
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

}

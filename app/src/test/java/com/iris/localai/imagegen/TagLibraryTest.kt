package com.iris.localai.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生图词库：扩充后的内置词、搜索 / 分类筛选、编辑内置与自定义标签。 */
class TagLibraryTest {
    @Test fun builtinIsLargeAndClean() {
        val b = PromptTags.BUILTIN
        assertTrue(b.size >= 300)
        assertEquals(b.size, b.map { it.key }.toSet().size)
        for (c in TagCat.entries) assertTrue(c.name, b.count { it.cat == c } >= 10)
        assertTrue(b.all { it.zh.isNotBlank() && it.en.isNotBlank() && !it.custom })
    }

    @Test fun filterByCategoryAndQuery() {
        val b = PromptTags.BUILTIN
        assertEquals(b.size, PromptTags.filter(b, null, "  ").size)
        assertTrue(PromptTags.filter(b, TagCat.SCENE, "").all { it.cat == TagCat.SCENE })
        val beach = PromptTags.filter(b, null, "海边")
        assertTrue(beach.any { it.en == "beach" })
        assertTrue(PromptTags.filter(b, null, "BEACH").any { it.zh == "海边" })
        assertTrue(PromptTags.filter(b, TagCat.NEGATIVE, "海边").isEmpty())
        assertTrue(PromptTags.filter(b, null, "twintails").any { it.zh == "双马尾" })
    }

    @Test fun editBuiltinReplacesIt() {
        val orig = PromptTags.BUILTIN.first { it.zh == "海边" }
        var d = TagStore.edit(TagStore.Data(), orig, TagCat.SCENE, "沙滩", "beach")
        var merged = PromptTags.merged(d.custom, d.hidden)
        val edited = merged.filter { it.key == orig.key }
        assertEquals(1, edited.size)
        assertEquals("沙滩", edited[0].zh)
        assertEquals(orig.danbooru, edited[0].danbooru)
        // round trip, then delete restores nothing (hidden)
        d = TagStore.parse(TagStore.toJson(d))
        d = TagStore.remove(d, edited[0])
        merged = PromptTags.merged(d.custom, d.hidden)
        assertFalse(merged.any { it.key == orig.key })
        // 恢复内置
        merged = PromptTags.merged(d.custom, emptySet())
        assertEquals("海边", merged.first { it.key == orig.key }.zh)
    }

    @Test fun editCustomMovesCategory() {
        var d = TagStore.add(TagStore.Data(), TagCat.STYLE, "我的", "my style")
        val mine = d.custom.first()
        d = TagStore.edit(d, mine, TagCat.COLOR, "我的色调", "my palette")
        assertEquals(1, d.custom.size)
        assertEquals(TagCat.COLOR, d.custom[0].cat)
        assertTrue(d.hidden.isEmpty())
        assertEquals(d, TagStore.edit(d, d.custom[0], TagCat.COLOR, "x", "   "))
    }
}

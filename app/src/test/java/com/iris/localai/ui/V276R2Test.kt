package com.iris.localai.ui

import com.iris.localai.data.DrawerGroups
import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.load.MemoryPreflight
import com.iris.localai.engine.load.PreloadPolicy
import com.iris.localai.ui.chat.StallKeep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.6 r2（vc282）：分组标题保留、卡住停止保留正文、低内存跳过预加载。 */
class V276R2Test {
    private data class C(val id: String, val g: String, val t: Long)

    @Test fun groupsKeepCustomOrderAndDateHeaders() {
        // 自定义顺序把一条「今天」排到「昨天」后面：仍只有一个「今天」标题，排在最前，组内保持自定义顺序
        val list = listOf(C("b", "today", 90), C("x", "yesterday", 50), C("a", "today", 100))
        val g = DrawerGroups.byDate(list, { it.g }, { it.t })
        assertEquals(listOf("today", "yesterday"), g.map { it.first })
        assertEquals(listOf("b", "a"), g[0].second.map { it.id })
    }

    @Test fun sectionOfHeaders() {
        val headers = setOf(0, 3)
        assertEquals(0, DrawerGroups.sectionOf(2) { it in headers })
        assertEquals(3, DrawerGroups.sectionOf(3) { it in headers })
        assertEquals(3, DrawerGroups.sectionOf(5) { it in headers })
        assertEquals(-1, DrawerGroups.sectionOf(1) { false })
    }

    @Test fun stallKeepsShownText() {
        assertEquals("已显示的正文", StallKeep.content("", "已显示的正文"))
        assertEquals("已存较长内容", StallKeep.content("已存较长内容", "短"))
        assertEquals("x", StallKeep.content("x", null))
        assertEquals("x", StallKeep.content("x", "  "))
    }

    @Test fun lowMemoryPreloadSkip() {
        assertTrue(PreloadPolicy.lowMemorySkip(true, MemoryPreflight.Level.OK, false))
        assertTrue(PreloadPolicy.lowMemorySkip(false, MemoryPreflight.Level.SHORT, false))
        assertTrue(PreloadPolicy.lowMemorySkip(false, MemoryPreflight.Level.TIGHT, true))
        assertFalse(PreloadPolicy.lowMemorySkip(false, MemoryPreflight.Level.TIGHT, false))
        assertFalse(PreloadPolicy.lowMemorySkip(false, MemoryPreflight.Level.OK, true))
        assertTrue(PreloadPolicy.lowMemoryNotice("1.2 GB", "3.4 GB").contains("3.4 GB"))
        assertFalse(PreloadPolicy.lowMemoryNotice("1.2 GB", null).contains("约需"))
    }

    @Test fun releaseNotes276r2() {
        val r = UpdateNotes.RELEASES.first { it.version == "2.7.6" }
        assertEquals("2.7.6", r.version)
        assertTrue(r.items.any { it.text.contains("预加载") })
        assertTrue(r.items.any { it.text.contains("正文") })
    }
}

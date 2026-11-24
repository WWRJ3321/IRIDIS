package com.iris.localai.v30

import com.iris.localai.data.HomeItem
import com.iris.localai.data.HomeLayout
import com.iris.localai.data.UpdateNotes
import com.iris.localai.imagegen.UpscaleRules
import com.iris.localai.prompts.PromptKey
import com.iris.localai.prompts.Prompts
import com.iris.localai.ui.island.IslandLogic
import com.iris.localai.ui.settings.LayoutDrag
import com.iris.localai.ui.toolbox.HandleLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0.0 正式版：审计修复（此次更新匹配、灵动岛、找文件释放、悬浮窗、放大、布局页、提示词语言、文案）。 */
class V300ReleaseTest {
    private fun src(p: String) = File("src/main/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    // ---- 此次更新 / 更新历史

    @Test fun notesFindRelease() {
        assertEquals("3.0.0", UpdateNotes.RELEASES.first().version)
        assertEquals("3.0.0", UpdateNotes.find("3.0.0-full")?.version)
        assertEquals("3.0.0", UpdateNotes.find("3.0.0")?.version)
        // beta 版本名退回到数字版本号匹配
        assertEquals("3.0.0", UpdateNotes.find("3.0.0 Beta6-full")?.version)
        assertNull(UpdateNotes.find("9.9.9"))
        assertEquals("3.0.0 Beta6", UpdateNotes.strip("3.0.0 Beta6-full"))
        assertEquals("2.5.5", UpdateNotes.strip("2.5.5-lite"))
    }

    @Test fun notesShowOnUpgrade() {
        assertTrue(UpdateNotes.shouldShow(true, true, "2.7.6-full", "3.0.0-full"))
        assertTrue(UpdateNotes.shouldShow(true, true, "3.0.0 Beta6-full", "3.0.0-full"))
        assertFalse(UpdateNotes.shouldShow(true, true, "3.0.0-full", "3.0.0-full"))
        assertFalse(UpdateNotes.shouldShow(true, true, "3.0.0", "3.0.0-full"))
    }

    @Test fun notes300Content() {
        assertTrue(UpdateNotes.RELEASES.none { it.version.startsWith("3.0.0 Beta") })
        val r = UpdateNotes.RELEASES.first()
        assertTrue(r.items.all { it.res != 0 })
        assertTrue(r.items.size <= 16)
        val all = r.items.joinToString("\n") { it.text }
        assertTrue(all.contains("高清放大"))
        assertTrue(all.contains("修复若干闪退与权限问题"))
        assertFalse(all.contains("未真机"))
        assertFalse(all.contains("顶部"))
        assertFalse(all.contains("不再显示闲言碎语"))
        assertEquals(2, r.of(UpdateNotes.Tag.FIX).size)
        val log = File("CHANGELOG.md").let { if (it.exists()) it else File("../CHANGELOG.md") }.readText()
        assertTrue(log.contains("## 3.0.0\n"))
        assertFalse(log.substringAfter("## 3.0.0").substringBefore("## 2.7.6").contains("未真机"))
    }

    // ---- 灵动岛大图

    @Test fun islandBigShotSafe() {
        assertNull(IslandLogic.bigShot(null))
        assertNull(IslandLogic.bigShot("  "))
        assertEquals("/a.png", IslandLogic.bigShot("/a.png"))
        assertFalse(src("java/com/iris/localai/ui/island/DynamicIsland.kt").contains("t.result!!"))
    }

    // ---- 找文件：句柄租借

    @Test fun leaseDefersReleaseUntilDone() {
        val freed = ArrayList<Long>()
        val l = HandleLease { freed += it }
        var loads = 0
        val h = l.acquire("m") { loads++; 7L }
        assertEquals(7L, h)
        l.release() // 搜索还在用：不释放
        assertTrue(freed.isEmpty())
        assertEquals(7L, l.held)
        l.done(h) // 搜索结束 → 释放
        assertEquals(listOf(7L), freed)
        assertEquals(0L, l.held)
        assertEquals(0, l.inUse)
        assertEquals(1, loads)
    }

    @Test fun leaseReuseAndIdleRelease() {
        val freed = ArrayList<Long>()
        val l = HandleLease { freed += it }
        var n = 0L
        val a = l.acquire("m") { ++n }
        l.done(a)
        val b = l.acquire("m") { ++n }
        assertEquals(a, b) // 同一模型复用
        assertEquals(1L, n)
        l.done(b)
        l.release() // 空闲时立即释放
        assertEquals(listOf(1L), freed)
        l.done(b) // 多余的 done 不出错、不重复释放
        assertEquals(1, freed.size)
    }

    @Test fun leaseConcurrentUsersAndModelSwitch() {
        val freed = ArrayList<Long>()
        val l = HandleLease { freed += it }
        val a = l.acquire("m") { 5L }
        val b = l.acquire("m") { 99L }
        assertEquals(5L, b)
        assertEquals(2, l.inUse)
        assertEquals(0L, l.acquire("other") { 6L }) // 别的模型正在用：不换
        l.release(); l.done(a)
        assertTrue(freed.isEmpty())
        l.done(b)
        assertEquals(listOf(5L), freed)
        // 再次 acquire 会重新加载；加载失败返回 0
        assertEquals(0L, l.acquire("m") { 0L })
        assertEquals(0, l.inUse)
        // 换模型时先放旧的
        val c = l.acquire("m") { 8L }; l.done(c)
        assertEquals(9L, l.acquire("other") { 9L })
        assertEquals(listOf(5L, 8L), freed)
    }

    @Test fun leaseReacquireCancelsPendingRelease() {
        val freed = ArrayList<Long>()
        val l = HandleLease { freed += it }
        val a = l.acquire("m") { 3L }
        l.release()
        val b = l.acquire("m") { 4L } // 回到页面又搜：继续用同一个
        l.done(a); l.done(b)
        assertTrue(freed.isEmpty())
        l.release()
        assertEquals(listOf(3L), freed)
    }

    @Test fun findHasPermissionHintAndMic() {
        val tb = src("java/com/iris/localai/ui/toolbox/ToolboxPage.kt")
        assertTrue(tb.contains("R.string.b30_find_perm"))
        assertTrue(tb.contains("openAllFilesAccess"))
        assertTrue(tb.contains("RECORD_AUDIO"))
        val ff = src("java/com/iris/localai/ui/toolbox/FileFinder.kt")
        assertTrue(ff.contains("lease.done(h)"))
    }

    // ---- 悬浮窗

    @Test fun floatingGuardsAndClipboard() {
        val s = src("java/com/iris/localai/floating/FloatingService.kt")
        assertFalse(s.contains("        wm.addView(v, bubbleLp)\n"))
        assertFalse(s.contains("        wm.addView(root, lp)\n"))
        assertTrue(s.contains("if (!canDraw(this)) { stopSelf(); return }"))
        assertTrue(s.contains("pasteWhenFocused(root)"))
        assertTrue(s.contains("addOnWindowFocusChangeListener"))
    }

    // ---- 高清放大

    @Test fun upscaleLimitsByFactor() {
        assertEquals(1024, UpscaleRules.maxSide(4))
        assertEquals(1536, UpscaleRules.maxSide(2))
        assertTrue(UpscaleRules.fits(1024, 1024, 4))
        assertFalse(UpscaleRules.fits(1536, 1024, 4))
        assertTrue(UpscaleRules.fits(1536, 1024, 2))
        assertFalse(UpscaleRules.fits(0, 10, 2))
        assertTrue(runCatching { UpscaleRules.check(1200, 800, 4) }.isFailure)
        UpscaleRules.check(1200, 800, 2)
        UpscaleRules.check(1024, 1024) // 旧调用默认按 2×
    }

    @Test fun upscaleRowMatchesWhole() {
        val px = intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFAB01EF.toInt(), 0xFF7F8081.toInt(), 0xFF010203.toInt())
        val buf = ByteArray(8) + UpscaleRules.argbToRgb(px)
        val whole = UpscaleRules.rgbToArgb(buf, 8, 6)
        val rows = IntArray(6)
        val row = IntArray(3)
        for (y in 0 until 2) { UpscaleRules.rgbRow(buf, 8 + y * 3 * 3, row); row.copyInto(rows, y * 3) }
        assertTrue(rows.contentEquals(whole))
        assertTrue(rows.contentEquals(px))
    }

    @Test fun upscaleChecksBeforeDownload() {
        val vm = src("java/com/iris/localai/ui/imagegen/ImageGenViewModel.kt")
        assertTrue(vm.contains("UpscaleNative.available"))
        assertTrue(vm.contains("R.string.b30_upscale_big"))
        assertTrue(vm.contains("OutOfMemoryError"))
        assertTrue(src("java/com/iris/localai/ui/imagegen/ImageHistory.kt").contains("UpscaleNative.available"))
    }

    // ---- 布局页

    @Test fun layoutPendingKeepsDrop() {
        val saved = HomeLayout.DEFAULT
        val moved = saved.move(HomeItem.AGENT, HomeLayout.Row.UPPER, false, 1)
        assertSame(moved, LayoutDrag.shown(null, moved, saved))
        assertSame(saved, LayoutDrag.shown(null, null, saved))
        assertSame(saved, LayoutDrag.shown(saved, moved, moved))
        assertFalse(LayoutDrag.settled(moved, saved))
        assertTrue(LayoutDrag.settled(moved, HomeLayout.parse(moved.encode())))
        val page = src("java/com/iris/localai/ui/settings/LayoutPage.kt")
        assertTrue(page.contains("minLines = 2"))
        assertTrue(page.contains("down.position + origin"))
        assertFalse(page.contains("translate(-origin)"))
    }

    // ---- 提示词跟随界面语言

    @Test fun floatPromptsFollowUiLanguage() {
        val keep = Prompts.localized
        val keepO = Prompts.overrides
        try {
            Prompts.overrides = emptyMap()
            Prompts.localized = { null }
            assertTrue(Prompts.default(PromptKey.FLOAT_SCREEN).startsWith("下面是手机屏幕上的文字"))
            Prompts.localized = { k -> "EN:" + k.id }
            assertEquals("EN:float.screen", Prompts.default(PromptKey.FLOAT_SCREEN))
            assertEquals("EN:float.ask", Prompts.get(PromptKey.FLOAT_ASK))
            // 其余提示词不跟随（与旧版逐字相同）
            assertEquals(Prompts.builtin(PromptKey.AGENT_RULES), Prompts.default(PromptKey.AGENT_RULES))
            Prompts.localized = { throw IllegalStateException("not ready") }
            assertEquals(Prompts.builtin(PromptKey.FLOAT_ASK), Prompts.default(PromptKey.FLOAT_ASK))
            Prompts.localized = { "  " }
            assertEquals(Prompts.builtin(PromptKey.FLOAT_ASK), Prompts.default(PromptKey.FLOAT_ASK))
        } finally { Prompts.localized = keep; Prompts.overrides = keepO }
    }

    // ---- 文案

    private val locales = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")
    private fun strings(l: String) = src("res/$l/strings.xml")
    private fun value(xml: String, key: String): String? = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    @Test fun stringsRenamedAndLocalized() {
        val zh = strings("values")
        assertEquals("下载模型 %1\$d%%", value(zh, "b5_upscale_dl"))
        assertEquals("高清放大", value(zh, "b5_upscale"))
        assertEquals("下載模型 %1\$d%%", value(strings("values-zh-rTW"), "b5_upscale_dl"))
        assertEquals(Prompts.builtin(PromptKey.FLOAT_SCREEN), value(zh, "b30_prompt_float_screen"))
        assertEquals(Prompts.builtin(PromptKey.FLOAT_ASK), value(zh, "b30_prompt_float_ask"))
        val keys = UpdateNotes.RELEASES.first().items.indices.map { "b30_n${it + 1}" } +
            listOf("b30_mic_denied", "b30_find_perm", "b30_find_perm_go", "b30_upscale_big", "b30_upscale_oom", "b30_prompt_float_screen", "b30_prompt_float_ask")
        for (l in locales) {
            val x = strings(l)
            for (k in keys) assertNotNull("$l/$k", value(x, k))
        }
        // 3.0.0 更新说明：资源里的中文与代码里的原文一致
        UpdateNotes.RELEASES.first().items.forEachIndexed { i, it -> assertEquals(it.text, value(zh, "b30_n${i + 1}")) }
    }

    @Test fun accessibilityNameUnified() {
        val bad = listOf("IRIDIS GUI 智能体", "IRIDIS GUI 智慧代理", "IRIDIS GUI agent", "IRIDIS GUI Agent", "IRIDIS GUI エージェント", "IRIDIS-GUI-Agent", "IRIDIS GUI-Agent", "Agente GUI de IRIDIS", "agente GUI de IRIDIS", "IRIDIS GUI एजेंट")
        for (l in locales) {
            val x = strings(l)
            assertEquals("IRIDIS GUI", value(x, "gui_a11y_label"))
            for (k in listOf("agent_2696c0", "agent_ca727f", "agent_13f110")) {
                val v = value(x, k)!!
                assertTrue("$l/$k", v.contains("IRIDIS GUI"))
                bad.forEach { b -> assertFalse("$l/$k has $b", v.contains(b)) }
            }
        }
        assertFalse(src("java/com/iris/localai/agent/tools/ScreenTool.kt").contains("IRIDIS GUI 智能体"))
        assertFalse(src("java/com/iris/localai/agent/gui/android/IrisAccessibilityService.kt").contains("IRIDIS GUI 智能体"))
    }
}

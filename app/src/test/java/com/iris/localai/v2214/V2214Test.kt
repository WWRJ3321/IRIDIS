package com.iris.localai.v2214

import com.iris.localai.agent.ToolCallParser
import com.iris.localai.agent.gui.android.A11yKeepAlive
import com.iris.localai.agent.gui.android.A11yList
import com.iris.localai.agent.gui.android.KeepAliveStatus
import com.iris.localai.data.TokenLimit
import com.iris.localai.data.TrashPolicy
import com.iris.localai.engine.GpuLoadWatchdog
import com.iris.localai.engine.MemoryPreflight
import com.iris.localai.util.title.TitleGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2214Test {
    @Test fun thinkClosedIsDropped() {
        val t = ToolCallParser.outsideThink("<think>我可以调用 <tool_call>{\"name\":\"x\"}</tool_call></think>你好")
        assertEquals("你好", t)
        assertFalse(t.contains("tool_call"))
    }

    @Test fun thinkUnclosedAndLoneClose() {
        assertEquals("答", ToolCallParser.outsideThink("答<think>也许 <tool_call>"))
        assertEquals("答案", ToolCallParser.outsideThink("预填思考 tool_call</think>答案"))
        assertEquals("plain", ToolCallParser.outsideThink("plain"))
    }

    @Test fun watchdogHangsWithoutProgress() {
        val w = GpuLoadWatchdog(timeoutMs = 25_000)
        val s = GpuLoadWatchdog.signature(0.05f, 3, 0)
        w.start(0, s)
        assertFalse(w.observe(10_000, s))
        assertTrue(w.observe(25_000, s))
        assertEquals(25_000, w.stalledFor(25_000))
    }

    @Test fun watchdogResetsOnAnyProgress() {
        val w = GpuLoadWatchdog(timeoutMs = 25_000)
        w.start(0, GpuLoadWatchdog.signature(0.05f, 3, 0))
        assertFalse(w.observe(20_000, GpuLoadWatchdog.signature(0.05f, 4, 0)))
        assertFalse(w.observe(40_000, GpuLoadWatchdog.signature(0.05f, 4, 0)))
        assertFalse(w.observe(44_000, GpuLoadWatchdog.signature(0.05f, 4, 1)))
        assertFalse(w.observe(60_000, GpuLoadWatchdog.signature(0.30f, 4, 1)))
        val sigs = setOf(GpuLoadWatchdog.signature(0.1f, 0, 0), GpuLoadWatchdog.signature(0f, 1, 0), GpuLoadWatchdog.signature(0f, 0, 1))
        assertEquals(3, sigs.size)
        assertTrue(GpuLoadWatchdog.hangDetail(7, "clBuildProgram", 25_000).contains("25s"))
    }

    @Test fun tokenLimitUnlimited() {
        assertTrue(TokenLimit.isUnlimited(TokenLimit.UNLIMITED))
        assertEquals(TokenLimit.UNLIMITED, TokenLimit.sanitize(5_000_000))
        assertEquals(16384, TokenLimit.sanitize(20_000))
        assertEquals(64, TokenLimit.sanitize(1))
        assertEquals("无限", TokenLimit.label(TokenLimit.UNLIMITED))
        assertEquals("512 tok", TokenLimit.label(512, " tok"))
        val v = TokenLimit.toSlider(TokenLimit.UNLIMITED, 8192, 256)
        assertEquals(TokenLimit.UNLIMITED, TokenLimit.fromSlider(v, 8192, 256))
        assertEquals(1024, TokenLimit.fromSlider(1024f, 8192, 256))
        assertEquals(TokenLimit.UNLIMITED, TokenLimit.resolve(TokenLimit.UNLIMITED, 4096, llama = true))
        assertEquals(4096, TokenLimit.resolve(TokenLimit.UNLIMITED, 4096, llama = false))
        assertEquals(800, TokenLimit.resolve(800, 4096, llama = false))
    }

    @Test fun titleGenClean() {
        assertEquals("周末去杭州", TitleGen.clean("标题：「周末去杭州」。\n其他"))
        assertNull(TitleGen.clean("   "))
        assertNull(TitleGen.clean("<think>"))
        val long = TitleGen.clean("这是一个非常非常非常非常非常长的标题文字内容")!!
        assertTrue(long.endsWith("…") && long.length == TitleGen.MAX_CHARS + 1)
        assertTrue(TitleGen.due(1, true, false))
        assertFalse(TitleGen.due(2, true, false))
        assertFalse(TitleGen.due(1, true, true))
        assertEquals(1, TitleGen.messages("u", "a").size)
    }

    @Test fun trashKeeps30Days() {
        val day = 86_400_000L
        assertEquals(30, TrashPolicy.KEEP_DAYS)
        assertFalse(TrashPolicy.expired(0, 29 * day))
        assertTrue(TrashPolicy.expired(0, 30 * day))
        assertEquals(30, TrashPolicy.daysLeft(0, 0))
        assertEquals(1, TrashPolicy.daysLeft(0, 29 * day))
        assertEquals(0, TrashPolicy.daysLeft(0, 40 * day))
    }

    @Test fun memoryPreflight() {
        val gb = 1024L * 1024 * 1024
        assertEquals(MemoryPreflight.Level.OK, MemoryPreflight.check(8 * gb, gb, 4096, gpu = false).level)
        val tight = MemoryPreflight.check(3 * gb, 2 * gb, 4096, gpu = true)
        assertTrue(tight.level != MemoryPreflight.Level.OK)
        assertTrue(tight.message!!.contains("改用 CPU"))
        assertEquals(MemoryPreflight.Level.SHORT, MemoryPreflight.check(gb, 4 * gb, 8192, gpu = false).level)
        assertTrue(MemoryPreflight.need(gb, 4096, true) > MemoryPreflight.need(gb, 4096, false))
        assertNull(MemoryPreflight.check(0, gb, 4096, false).message)
    }

    @Test fun keepAlive() {
        assertEquals(KeepAliveStatus.NOT_GRANTED, KeepAliveStatus.of(false, true, true))
        assertEquals(KeepAliveStatus.ACTIVE, KeepAliveStatus.of(true, true, false))
        assertEquals(KeepAliveStatus.GRANTED_SERVICE_OFF, KeepAliveStatus.of(true, false, false))
        assertEquals("已保活", KeepAliveStatus.ACTIVE.label)
        assertEquals("未授权", KeepAliveStatus.NOT_GRANTED.label)
        assertEquals("adb shell pm grant com.iridis android.permission.WRITE_SECURE_SETTINGS", A11yKeepAlive.adbCommand())
        val me = "com.iridis/com.iris.localai.agent.gui.android.IrisAccessibilityService"
        assertEquals("a/b", A11yList.remove("a/b:$me", me))
        assertEquals("a/b:$me", A11yList.add(A11yList.remove("a/b:$me", me), me))
        assertNotNull(A11yList.remove("", me))
    }
}

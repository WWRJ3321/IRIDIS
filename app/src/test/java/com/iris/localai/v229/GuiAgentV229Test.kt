package com.iris.localai.v229

import com.iris.localai.agent.AppMatcher
import com.iris.localai.agent.gui.ChangeReport
import com.iris.localai.agent.gui.GuiAction
import com.iris.localai.agent.gui.GuiSafety
import com.iris.localai.agent.gui.LoopGuard
import com.iris.localai.agent.gui.NPoint
import com.iris.localai.agent.gui.PxRect
import com.iris.localai.agent.gui.ScreenFingerprint
import com.iris.localai.agent.gui.ScreenState
import com.iris.localai.agent.gui.StableWait
import com.iris.localai.agent.gui.TypeCheck
import com.iris.localai.agent.gui.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiAgentV229Test {
    private fun tap(x: Int, y: Int) = GuiAction.Tap(NPoint(x / 1000f, y / 1000f))

    @Test fun loopGuardWarnsOnThirdSameTapWithoutChange() {
        val g = LoopGuard()
        assertEquals(LoopGuard.Verdict.OK, g.record(tap(500, 500), false))
        assertEquals(LoopGuard.Verdict.OK, g.record(tap(510, 495), false))
        assertEquals(LoopGuard.Verdict.WARN, g.record(tap(490, 515), false))
        assertEquals(3, g.count)
        assertEquals(LoopGuard.Verdict.WARN, g.record(tap(500, 500), null))
        assertEquals(LoopGuard.Verdict.WARN, g.record(tap(500, 500), false))
        assertEquals(LoopGuard.Verdict.STOP, g.record(tap(500, 500), false))
    }

    @Test fun loopGuardResetsOnChangeOrDifferentAction() {
        val g = LoopGuard()
        g.record(tap(500, 500), false); g.record(tap(500, 500), false)
        assertEquals(LoopGuard.Verdict.OK, g.record(tap(500, 500), true))
        g.record(tap(500, 500), false); g.record(tap(500, 500), false)
        assertEquals(LoopGuard.Verdict.OK, g.record(tap(560, 500), false)) // > 20/1000 away
        assertEquals(1, g.count)
        g.record(GuiAction.Back, false); g.record(GuiAction.Back, false)
        assertEquals(LoopGuard.Verdict.WARN, g.record(GuiAction.Back, false))
        assertEquals(LoopGuard.Verdict.OK, g.record(GuiAction.Wait(1000), false))
        assertTrue(LoopGuard.hint(3).contains("3"))
    }

    @Test fun loopGuardSameArgsForTypeAndSwipe() {
        val g = LoopGuard()
        repeat(2) { g.record(GuiAction.Type("你好"), false) }
        assertEquals(LoopGuard.Verdict.WARN, g.record(GuiAction.Type("你好"), false))
        val a = GuiAction.Swipe(NPoint(.5f, .8f), NPoint(.5f, .3f))
        val b = GuiAction.Swipe(NPoint(.51f, .79f), NPoint(.5f, .31f))
        assertTrue(g.same(a, b))
        assertFalse(g.same(a, GuiAction.Swipe(NPoint(.5f, .3f), NPoint(.5f, .8f))))
    }

    @Test fun sensitiveKeywordMatcher() {
        listOf("确认支付", "立即付款", "转账", "购买", "提交订单 下单", "发送", "删除", "Pay now", "Send", "DELETE").forEach {
            assertNotNull(it, GuiSafety.sensitiveWord(it))
        }
        assertNull(GuiSafety.sensitiveWord("设置"))
        assertNull(GuiSafety.sensitiveWord("display"))   // "pay" inside a word
        assertNull(GuiSafety.sensitiveWord("sender name")) // "send" inside a word
        assertEquals("确认支付", GuiSafety.intentWord("现在点击确认支付按钮"))
        assertEquals("send", GuiSafety.intentWord("Tap the Send button to send the message"))
        assertNull(GuiSafety.intentWord("open the settings and find brightness"))
        assertNull(GuiSafety.intentWord(null))
    }

    @Test fun reviewConfirmsByIntentAndLabel() {
        val btn = UiNode(text = "发送", bounds = PxRect(0, 0, 100, 100), clickable = true)
        val root = UiNode(bounds = PxRect(0, 0, 1000, 1000), children = listOf(btn))
        val els = com.iris.localai.agent.gui.UiTreeSerializer.elements(root, 1000, 1000)
        assertTrue(GuiSafety.review(tap(50, 50), els, 1000, 1000, "com.tencent.mm") is com.iris.localai.agent.gui.GuiVerdict.Confirm)
        assertTrue(GuiSafety.review(tap(800, 800), els, 1000, 1000, "com.x", "点击下单") is com.iris.localai.agent.gui.GuiVerdict.Confirm)
        assertTrue(GuiSafety.review(tap(800, 800), els, 1000, 1000, "com.x", "点击搜索框") is com.iris.localai.agent.gui.GuiVerdict.Allow)
        assertTrue(GuiSafety.review(GuiAction.Enter, els, 1000, 1000, "com.x", "按回车发送") is com.iris.localai.agent.gui.GuiVerdict.Confirm)
    }

    @Test fun appLabelFuzzyMatch() {
        val apps = listOf("微信" to "com.tencent.mm", "设置" to "com.android.settings", "网易云音乐" to "com.netease.cloudmusic",
            "Google Chrome" to "com.android.chrome", "小红书" to "com.xingin.xhs", "图库" to "com.miui.gallery")
        assertEquals("com.tencent.mm", AppMatcher.match("微信", apps)?.second)
        assertEquals("com.tencent.mm", AppMatcher.match("WeChat", apps)?.second)
        assertEquals("com.netease.cloudmusic", AppMatcher.match("网易云", apps)?.second)
        assertEquals("com.android.chrome", AppMatcher.match("chrome", apps)?.second)
        assertEquals("com.miui.gallery", AppMatcher.match("相册", apps)?.second)
        assertEquals("com.xingin.xhs", AppMatcher.match("com.xingin.xhs", apps)?.second)
        assertEquals("com.android.settings", AppMatcher.match("系统设置", apps)?.second)
        assertNull(AppMatcher.match("抖音", apps))
        assertTrue(AppMatcher.notFound("网易音乐", apps).contains("网易云音乐"))
    }

    @Test fun fingerprintAndChangeReport() {
        val a = UiNode(pkg = "com.a", bounds = PxRect(0, 0, 10, 10), children = listOf(UiNode(text = "x")))
        val b = a.copy(children = listOf(UiNode(text = "y")))
        val fa = ScreenFingerprint.of(a)!!; val fb = ScreenFingerprint.of(b)!!
        assertEquals(fa, ScreenFingerprint.of(a.copy()))
        assertFalse(fa.hash == fb.hash)
        assertEquals(ChangeReport.UNCHANGED, ChangeReport.text(fa, fa))
        assertEquals("画面已变化（前台：com.a）", ChangeReport.text(fa, fb))
        assertNull(ChangeReport.text(null, fb))
        assertEquals(true, ChangeReport.changed(fa, ScreenState(fa.hash, "com.b")))
    }

    @Test fun stableWaitStopsOnTwoMatchingFramesOrTimeout() = runBlocking {
        var t = 0L
        val seq = listOf(1, 2, 3, 3, 3).iterator()
        val r = StableWait.await({ ScreenState(seq.next(), null) }, now = { t.also { t += 150 } }, pollMs = 1)
        assertEquals(3, r?.hash)
        var n = 0; var t2 = 0L
        val r2 = StableWait.await({ ScreenState(n++, null) }, now = { t2.also { t2 += 150 } }, pollMs = 1)
        assertTrue(n in 15..19) // ~2.5 s of 150 ms samples, never stable
        assertNotNull(r2)
    }

    @Test fun typeCheck() {
        assertTrue(TypeCheck.took("你好 世界", "你好世界"))
        assertFalse(TypeCheck.took("搜索", "北京天气"))
        assertTrue(TypeCheck.took("北京天气", "北京天气"))
    }
}

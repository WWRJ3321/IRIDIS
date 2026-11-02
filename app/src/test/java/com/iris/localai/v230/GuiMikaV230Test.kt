package com.iris.localai.v230

import com.iris.localai.agent.gui.*
import org.junit.Assert.*
import org.junit.Test

class GuiMikaV230Test {
    private fun r(l: Int, t: Int, rr: Int, b: Int) = PxRect(l, t, rr, b)

    @Test fun parsesScrollBothFormats() {
        val a = GuiActionParser.parse("""<tool_call>{"name":"mobile_use","arguments":{"action":"scroll","direction":"down","until":"设置"}}</tool_call>""", GuiProfile.QWEN_VL).action
        assertEquals(GuiAction.Scroll(SwipeDir.DOWN, null, "设置"), a)
        val b = GuiActionParser.parse("""<answer>do(action="Scroll", direction="up", element=[500,500])</answer>""", GuiProfile.AUTOGLM).action as GuiAction.Scroll
        assertEquals(SwipeDir.UP, b.dir); assertNotNull(b.at); assertNull(b.until)
        assertNotNull(GuiActionParser.parse("""do(action="Scroll", direction="sideways")""", GuiProfile.AUTOGLM).error)
    }

    @Test fun longPressDuration() {
        val a = GuiActionParser.parse("""do(action="Long Press", element=[100,100], duration="2 seconds")""", GuiProfile.AUTOGLM).action as GuiAction.LongPress
        assertEquals(2000L, a.durationMs)
        val b = GuiActionParser.parse("""<tool_call>{"name":"mobile_use","arguments":{"action":"long_press","coordinate":[1,1]}}</tool_call>""", GuiProfile.QWEN_VL).action as GuiAction.LongPress
        assertEquals(GuiAction.DEFAULT_LONG_PRESS_MS, b.durationMs)
        assertEquals(1500L, (GuiActionParser.parse("""do(action="Long Press", element=[1,1], duration="1500ms")""", GuiProfile.AUTOGLM).action as GuiAction.LongPress).durationMs)
    }

    @Test fun scrollSwipeIsOppositeFinger() {
        val s = CoordMap.scrollSwipe(SwipeDir.DOWN)
        assertTrue(s.from.y > s.to.y) // content down = finger moves up
        assertTrue(GuiAction.Scroll(SwipeDir.DOWN, until = "x").label().contains("找「x」"))
    }

    @Test fun compactionDedupAndPriority() {
        val kids = (0 until 80).map { UiNode(cls = "TextView", text = "t$it", bounds = r(0, it * 10, 100, it * 10 + 9)) } +
            UiNode(cls = "Button", text = "OK", clickable = true, bounds = r(0, 900, 100, 950))
        val root = UiNode(cls = "Frame", bounds = r(0, 0, 1000, 1000), children = kids)
        val els = UiTreeSerializer.elements(root, 1000, 1000, maxElements = 10)
        assertEquals(10, els.size)
        assertTrue(els.any { it.label == "OK" })
        assertEquals((0 until 10).toList(), els.map { it.index })
        val dup = UiNode(bounds = r(0, 0, 1000, 1000), children = listOf(
            UiNode(text = "发送", bounds = r(10, 10, 60, 40)), UiNode(desc = "发送", bounds = r(11, 11, 61, 41))))
        assertEquals(1, UiTreeSerializer.elements(dup, 1000, 1000).size)
    }

    @Test fun hintAndFind() {
        val root = UiNode(bounds = r(0, 0, 1000, 1000), children = listOf(
            UiNode(cls = "EditText", editable = true, hint = "搜索", bounds = r(0, 0, 500, 50)),
            UiNode(text = "Wi-Fi 设置", bounds = r(0, 500, 500, 550))))
        val els = UiTreeSerializer.elements(root, 1000, 1000)
        assertTrue(els[0].line.contains("hint=\"搜索\""))
        assertEquals("Wi-Fi 设置", UiTreeSerializer.find(els, "wifi设置")?.label ?: UiTreeSerializer.find(els, "Wi-Fi设置")?.label)
        assertEquals(250 to 525, UiTreeSerializer.gridCentre(els[1], 1000, 1000))
    }

    @Test fun unlabeledControlsTriggerOcr() {
        val root = UiNode(bounds = r(0, 0, 1000, 1000), children = (0 until 5).map { UiNode(clickable = true, bounds = r(it * 100, 0, it * 100 + 90, 90)) } +
            (0 until 6).map { UiNode(text = "x$it", bounds = r(0, 200 + it * 50, 100, 240 + it * 50)) })
        assertTrue(UiTreeSerializer.unlabeledControls(root))
        assertTrue(UiTreeSerializer.needsOcr(UiTreeSerializer.elements(root, 1000, 1000), root))
    }

    private fun el(i: Int, label: String, cls: String = "TextView", click: Boolean = false, pw: Boolean = false, edit: Boolean = false) =
        UiElement(i, UiNode(cls = cls, text = label, clickable = click, password = pw, editable = edit, bounds = r(0, i * 50, 300, i * 50 + 40)), label, "")

    @Test fun diagnosis() {
        val anr = ScreenDiagnosis.classify(listOf(el(0, "微信 没有响应"), el(1, "关闭应用", "Button", true), el(2, "等待", "Button", true)), "android")
        assertEquals(ScreenKind.APP_NOT_RESPONDING, anr.kind); assertEquals("等待", anr.button?.label)
        val crash = ScreenDiagnosis.classify(listOf(el(0, "Foo keeps stopping"), el(1, "Close app", "Button", true)), "android")
        assertEquals(ScreenKind.APP_CRASHED, crash.kind); assertEquals("Close app", crash.button?.label)
        assertEquals(ScreenKind.NEEDS_HUMAN, ScreenDiagnosis.classify(listOf(el(0, "请完成验证")), "com.x").kind)
        assertEquals(ScreenKind.NEEDS_HUMAN, ScreenDiagnosis.classify(listOf(el(0, "", "EditText", edit = true, pw = true)), "com.x").kind)
        assertEquals(ScreenKind.BLANK, ScreenDiagnosis.classify(emptyList(), "com.x").kind)
        assertEquals(ScreenKind.NORMAL, ScreenDiagnosis.classify(listOf(el(0, "首页")), "com.x").kind)
        assertNull(ScreenDiagnosis.hint(ScreenCheck(ScreenKind.NORMAL)))
    }

    @Test fun stuckMemoryFinishJump() {
        val st = StuckTracker()
        val tap = GuiAction.Tap(NPoint(0.5f, 0.5f))
        assertNull(st.record(tap, "a", guardWarned = true, changed = false))
        assertEquals(StuckTracker.GIVE_UP, st.record(tap, "a", guardWarned = true, changed = false))
        val t = GuiAction.Type("咖啡")
        st.record(t, "a", false, true); st.record(t, "a", false, true)
        assertTrue(st.record(t, "a", false, true)!!.contains("3 次"))
        assertNull(StuckTracker().record(t, "b", false, true))

        val m = StepMemory()
        m.record(GuiAction.Launch("微信"), "com.tencent.mm", true, true)
        m.record(t, "com.tencent.mm", false, null)
        assertNull(m.summary(0))
        val s = m.summary(3)!!
        assertTrue(s.contains("前 3 步") && s.contains("「咖啡」") && s.contains("失败 1 次"))

        assertTrue(FinishCheck.needsCheck(false, null, 0)); assertTrue(FinishCheck.needsCheck(true, false, 0))
        assertFalse(FinishCheck.needsCheck(true, true, 0)); assertFalse(FinishCheck.needsCheck(false, false, 1))

        assertNotNull(AppJump.note(tap, "com.a", "com.b"))
        assertNull(AppJump.note(tap, "com.miui.home", "com.b"))
        assertNull(AppJump.note(GuiAction.Back, "com.a", "com.b"))
    }

    /** Scripted device: a list of screens; Scroll moves to the next one. */
    private class FakeDevice(val screens: List<UiNode>) : GuiDevice {
        var i = 0; val done = ArrayList<GuiAction>()
        override suspend fun observe() = GuiObservation(null, 1000, 1000, screens[i], "com.app")
        override suspend fun execute(action: GuiAction, obs: GuiObservation): ExecResult {
            done += action; if (action is GuiAction.Scroll && i < screens.size - 1) i++; return ExecResult(true)
        }
        override suspend fun snapshot() = ScreenFingerprint.of(screens[i])
        override suspend fun waitStable(fallbackMs: Long) = ScreenFingerprint.of(screens[i])
        override suspend fun tree() = screens[i]
    }

    private class Script(vararg replies: String) : GuiModel {
        val q = ArrayDeque(replies.toList()); val seen = ArrayList<String>()
        override suspend fun complete(messages: List<com.iris.localai.engine.PromptMessage>): String { seen += messages.last().content; return q.removeFirst() }
    }

    private val cb = object : GuiCallbacks {
        override suspend fun confirm(action: GuiAction, reason: String) = true
        override suspend fun takeOver(message: String) = true
        override suspend fun ask(question: String): String? = null
    }

    private fun page(vararg texts: String) = UiNode(pkg = "com.app", bounds = r(0, 0, 1000, 1000), scrollable = true,
        children = texts.mapIndexed { k, t -> UiNode(text = t, pkg = "com.app", bounds = r(0, k * 100, 1000, k * 100 + 90)) })

    @Test fun scrollUntilFoundInOneModelStep() = kotlinx.coroutines.runBlocking {
        val dev = FakeDevice(listOf(page("a", "b"), page("c", "d"), page("关于手机", "e")))
        val model = Script(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"scroll","direction":"down","until":"关于手机"}}</tool_call>""",
            """<tool_call>{"name":"mobile_use","arguments":{"action":"terminate","status":"success","text":"ok"}}</tool_call>""",
        )
        val out = GuiAgentLoop(model, dev, cb, GuiProfile.QWEN_VL, settleMs = 0, stop = { false }, userNotes = { null }).run("找关于手机")
        assertEquals(GuiStop.DONE, out.stop)
        assertEquals(2, dev.done.count { it is GuiAction.Scroll })
        assertTrue(model.seen[1], model.seen[1].contains("找到「关于手机」(500,45)"))
    }

    @Test fun scrollUntilEndReported() = kotlinx.coroutines.runBlocking {
        val dev = FakeDevice(listOf(page("a"), page("b")))
        val model = Script(
            """<tool_call>{"name":"mobile_use","arguments":{"action":"scroll","direction":"down","until":"zzz"}}</tool_call>""",
            """<tool_call>{"name":"mobile_use","arguments":{"action":"terminate","status":"failure","text":"没有"}}</tool_call>""",
        )
        val out = GuiAgentLoop(model, dev, cb, GuiProfile.QWEN_VL, settleMs = 0, stop = { false }, userNotes = { null }).run("x")
        assertEquals(GuiStop.GAVE_UP, out.stop)
        assertTrue(model.seen[1].contains("已到尽头"))
    }

    @Test fun anrDialogClearedWithoutModelStep() = kotlinx.coroutines.runBlocking {
        val anr = UiNode(pkg = "android", bounds = r(0, 0, 1000, 1000), children = listOf(
            UiNode(text = "某应用没有响应", pkg = "android", bounds = r(100, 300, 900, 360)),
            UiNode(cls = "android.widget.Button", text = "等待", clickable = true, pkg = "android", bounds = r(100, 500, 900, 560))))
        val dev = object : GuiDevice {
            var shown = true; val done = ArrayList<GuiAction>()
            override suspend fun observe() = GuiObservation(null, 1000, 1000, if (shown) anr else page("首页"), if (shown) "android" else "com.app")
            override suspend fun execute(action: GuiAction, obs: GuiObservation): ExecResult { done += action; shown = false; return ExecResult(true) }
        }
        val model = Script("""<tool_call>{"name":"mobile_use","arguments":{"action":"terminate","status":"success","text":"ok"}}</tool_call>""")
        val out = GuiAgentLoop(model, dev, cb, GuiProfile.QWEN_VL, settleMs = 0, stop = { false }, userNotes = { null }).run("x")
        assertEquals(GuiStop.DONE, out.stop)
        assertEquals(1, model.seen.size)
        val tap = dev.done.single() as GuiAction.Tap
        assertEquals(0.53f, tap.at.y, 0.01f)
        assertTrue(out.steps.first().note.contains("等待"))
    }

    @Test fun finishAfterNoEffectIsCheckedOnce() = kotlinx.coroutines.runBlocking {
        val dev = FakeDevice(listOf(page("a")))
        val tap = """<tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[500,50]}}</tool_call>"""
        val fin = """<tool_call>{"name":"mobile_use","arguments":{"action":"terminate","status":"success","text":"ok"}}</tool_call>"""
        val model = Script(tap, fin, fin)
        val out = GuiAgentLoop(model, dev, cb, GuiProfile.QWEN_VL, settleMs = 0, stop = { false }, userNotes = { null }).run("x")
        assertEquals(GuiStop.DONE, out.stop)
        assertEquals(3, model.seen.size)
        assertTrue(model.seen[2].contains("确认"))
    }

    @Test fun promptMentionsScrollAndSummary() {
        assertTrue(GuiPrompt.system(GuiProfile.QWEN_VL).contains("\"until\""))
        assertTrue(GuiPrompt.system(GuiProfile.AUTOGLM).contains("Scroll"))
        val hist = (1..12).map { "step $it" }
        val msg = GuiPrompt.messages(GuiProfile.QWEN_VL, "t", hist, GuiObservation(null, 1, 1, null), emptyList(), true, null, earlier = "（前 4 步已省略）")
        assertTrue(msg.last().content.contains("（前 4 步已省略）"))
    }

    @Test fun mikaLicenseListed() {
        val f = java.io.File("app/src/main/assets/licenses/index.json").readText()
        assertTrue(f.contains("wangzan101/mika"))
        assertTrue(java.io.File("app/src/main/assets/licenses/notice-mika.txt").readText().contains("Copyright 2026 wangzan101"))
    }
}

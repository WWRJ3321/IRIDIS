package com.iris.localai.v220gui

import com.iris.localai.agent.gui.CoordMap
import com.iris.localai.agent.gui.ExecResult
import com.iris.localai.agent.gui.GuiAction
import com.iris.localai.agent.gui.GuiActionParser
import com.iris.localai.agent.gui.GuiAgentLoop
import com.iris.localai.agent.gui.GuiCallbacks
import com.iris.localai.agent.gui.GuiDevice
import com.iris.localai.agent.gui.GuiModel
import com.iris.localai.agent.gui.GuiObservation
import com.iris.localai.agent.gui.GuiProfile
import com.iris.localai.agent.gui.GuiPrompt
import com.iris.localai.agent.gui.GuiSafety
import com.iris.localai.agent.gui.GuiStep
import com.iris.localai.agent.gui.GuiStepStatus
import com.iris.localai.agent.gui.GuiStop
import com.iris.localai.agent.gui.GuiVerdict
import com.iris.localai.agent.gui.NPoint
import com.iris.localai.agent.gui.PxRect
import com.iris.localai.agent.gui.UiNode
import com.iris.localai.agent.gui.UiTreeSerializer
import com.iris.localai.engine.PromptMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuiAgentTest {
    private fun near(a: Float, b: Float) = assertEquals(b, a, 0.002f)

    // ------------------------------------------------------------------ AutoGLM format

    @Test fun autoGlmTapUses1000Grid() {
        val p = GuiActionParser.parse("<think>点搜索框</think><answer>do(action=\"Tap\", element=[500,250])</answer>", GuiProfile.AUTOGLM)
        assertEquals("点搜索框", p.thought)
        val t = p.action as GuiAction.Tap
        near(t.at.x, 0.5f); near(t.at.y, 0.25f); assertNull(t.note)
    }

    @Test fun autoGlmSensitiveTapKeepsMessage() {
        val t = GuiActionParser.parse("<answer>do(action=\"Tap\", element=[10,20], message=\"重要操作\")</answer>", GuiProfile.AUTOGLM).action as GuiAction.Tap
        assertEquals("重要操作", t.note)
    }

    @Test fun autoGlmOtherActions() {
        fun a(s: String) = GuiActionParser.parse("<answer>$s</answer>", GuiProfile.AUTOGLM).action
        val sw = a("do(action=\"Swipe\", start=[500,800], end=[500,200])") as GuiAction.Swipe
        near(sw.from.y, 0.8f); near(sw.to.y, 0.2f)
        assertEquals(GuiAction.Type("他说\"你好\"\n"), a("do(action=\"Type\", text=\"他说\\\"你好\\\"\\n\")"))
        assertEquals(GuiAction.Type("张三"), a("do(action=\"Type_Name\", text=\"张三\")"))
        assertEquals(GuiAction.Launch("小红书"), a("do(action=\"Launch\", app=\"小红书\")"))
        assertTrue(a("do(action=\"Long Press\", element=[1,1])") is GuiAction.LongPress)
        assertTrue(a("do(action=\"Double Tap\", element=[1,1])") is GuiAction.DoubleTap)
        assertEquals(GuiAction.Back, a("do(action=\"Back\")"))
        assertEquals(GuiAction.Home, a("do(action=\"Home\")"))
        assertEquals(GuiAction.Wait(2000), a("do(action=\"Wait\", duration=\"2 seconds\")"))
        assertEquals(GuiAction.Wait(GuiAction.MAX_WAIT_MS), a("do(action=\"Wait\", duration=\"99 seconds\")"))
        assertEquals(GuiAction.TakeOver("请登录"), a("do(action=\"Take_over\", message=\"请登录\")"))
        assertTrue(a("do(action=\"Interact\")") is GuiAction.Ask)
        assertEquals(GuiAction.Finish("已完成(搜索)"), a("finish(message=\"已完成(搜索)\")"))
    }

    @Test fun autoGlmWithoutAnswerTagAndErrors() {
        assertTrue(GuiActionParser.parse("好的 do(action=\"Back\")", GuiProfile.AUTOGLM).action == GuiAction.Back)
        val bad = GuiActionParser.parse("<answer>do(action=\"Fly\")</answer>", GuiProfile.AUTOGLM)
        assertNull(bad.action); assertTrue(bad.error!!.contains("Fly"))
        assertNotNull(GuiActionParser.parse("<answer>do(action=\"Tap\")</answer>", GuiProfile.AUTOGLM).error)
        assertNotNull(GuiActionParser.parse("<answer>do(action=\"Tap\", element=[5000,10])</answer>", GuiProfile.AUTOGLM).error)
        assertNotNull(GuiActionParser.parse("我不知道", GuiProfile.AUTOGLM).error)
    }

    // ------------------------------------------------------------------ mobile_use (MAI-UI / Qwen3-VL)

    private fun mu(args: String, profile: GuiProfile = GuiProfile.MAI_UI) =
        GuiActionParser.parse("<thinking>想</thinking>\n<tool_call>\n{\"name\": \"mobile_use\", \"arguments\": $args}\n</tool_call>", profile)

    @Test fun maiUiClickUses999Grid() {
        val p = mu("{\"action\": \"click\", \"coordinate\": [999, 0]}")
        assertEquals("想", p.thought)
        val t = p.action as GuiAction.Tap
        near(t.at.x, 1f); near(t.at.y, 0f)
        val q = mu("{\"action\": \"click\", \"coordinate\": [500, 500]}", GuiProfile.QWEN_VL).action as GuiAction.Tap
        near(q.at.x, 0.5f)
    }

    @Test fun mobileUseBoxAndSwipes() {
        val box = mu("{\"action\": \"click\", \"coordinate\": [100, 100, 300, 300]}", GuiProfile.QWEN_VL).action as GuiAction.Tap
        near(box.at.x, 0.2f)
        val up = mu("{\"action\": \"swipe\", \"direction\": \"up\"}").action as GuiAction.Swipe
        assertTrue("finger moves up", up.to.y < up.from.y)
        val two = mu("{\"action\": \"swipe\", \"coordinate\": [500, 900], \"coordinate2\": [500, 100]}", GuiProfile.QWEN_VL).action as GuiAction.Swipe
        near(two.from.y, 0.9f); near(two.to.y, 0.1f)
        val drag = mu("{\"action\": \"drag\", \"start_coordinate\": [0, 0], \"end_coordinate\": [999, 999]}").action as GuiAction.Swipe
        near(drag.to.x, 1f)
        assertNotNull(mu("{\"action\": \"swipe\", \"direction\": \"sideways\"}").error)
    }

    @Test fun mobileUseOtherActions() {
        assertEquals(GuiAction.Back, mu("{\"action\": \"system_button\", \"button\": \"Back\"}").action)
        assertEquals(GuiAction.Home, mu("{\"action\": \"system_button\", \"button\": \"home\"}").action)
        assertEquals(GuiAction.Enter, mu("{\"action\": \"system_button\", \"button\": \"enter\"}").action)
        assertEquals(GuiAction.Type("你好"), mu("{\"action\": \"type\", \"text\": \"你好\"}").action)
        assertEquals(GuiAction.Launch("设置"), mu("{\"action\": \"open\", \"text\": \"设置\"}").action)
        assertEquals(GuiAction.Wait(3000), mu("{\"action\": \"wait\", \"time\": 3}").action)
        assertEquals(GuiAction.Finish("", success = false), mu("{\"action\": \"terminate\", \"status\": \"failure\"}").action)
        assertEquals(GuiAction.Finish("", success = true), mu("{\"action\": \"terminate\", \"status\": \"success\"}").action)
        assertEquals(GuiAction.Ask("哪个？"), mu("{\"action\": \"ask_user\", \"text\": \"哪个？\"}").action)
        assertEquals(GuiAction.Finish("42"), mu("{\"action\": \"answer\", \"text\": \"42\"}").action)
    }

    @Test fun mobileUseLenientAndErrors() {
        val bare = GuiActionParser.parse("{\"action\": \"click\", \"coordinate\": [10, 10]}", GuiProfile.QWEN_VL)
        assertTrue(bare.action is GuiAction.Tap)
        val unclosed = GuiActionParser.parse("<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"system_button\",\"button\":\"back\"}}", GuiProfile.QWEN_VL)
        assertEquals(GuiAction.Back, unclosed.action)
        assertNotNull(GuiActionParser.parse("<tool_call>{not json</tool_call>", GuiProfile.QWEN_VL).error)
        assertNotNull(mu("{\"coordinate\": [1, 1]}").error)
        assertNotNull(mu("{\"action\": \"click\"}").error)
        assertNotNull(mu("{\"action\": \"click\", \"coordinate\": [1]}").error)
    }

    @Test fun profileByModelName() {
        assertEquals(GuiProfile.AUTOGLM, GuiProfile.forModel("AutoGLM-Phone-9B-Q4_K_M.gguf"))
        assertEquals(GuiProfile.MAI_UI, GuiProfile.forModel("MAI-UI-2B.Q4_K_M.gguf"))
        assertEquals(GuiProfile.QWEN_VL, GuiProfile.forModel("Qwen3-VL-4B-Instruct-MNN"))
        assertEquals(GuiProfile.QWEN_VL, GuiProfile.forModel(null))
    }

    @Test fun coordMapClampsToScreen() {
        assertEquals(1079 to 2399, CoordMap.toPixels(NPoint(1f, 1f), 1080, 2400))
        assertEquals(540 to 1200, CoordMap.toPixels(NPoint(0.5f, 0.5f), 1080, 2400))
        near(CoordMap.fromGrid(1010.0, -10.0, 1000f).x, 1f) // small overshoot is clamped
    }

    // ------------------------------------------------------------------ node tree

    private val W = 1000
    private val H = 2000
    private fun r(l: Int, t: Int, rr: Int, b: Int) = PxRect(l, t, rr, b)

    private val tree = UiNode(
        cls = "android.widget.FrameLayout", bounds = r(0, 0, W, H), pkg = "com.example.chat",
        children = listOf(
            UiNode(cls = "android.widget.TextView", text = "聊天", bounds = r(0, 0, W, 100)),
            UiNode(
                cls = "android.widget.LinearLayout", clickable = true, bounds = r(0, 200, W, 400),
                children = listOf(UiNode(cls = "android.widget.TextView", text = "张三", bounds = r(100, 220, 500, 300)),
                    UiNode(cls = "android.widget.TextView", text = "明天见", bounds = r(100, 300, 500, 380))),
            ),
            UiNode(cls = "android.widget.TextView", text = "隐藏", bounds = r(0, 500, W, 600), visible = false),
            UiNode(cls = "android.widget.TextView", text = "屏外", bounds = r(0, 3000, W, 3100)),
            UiNode(cls = "android.widget.EditText", editable = true, focused = true, password = true, bounds = r(0, 1800, 800, 1900)),
            UiNode(cls = "android.widget.Button", text = "发送", clickable = true, bounds = r(800, 1800, W, 1900)),
        ),
    )

    @Test fun treeKeepsUsefulVisibleElements() {
        val els = UiTreeSerializer.elements(tree, W, H)
        val labels = els.map { it.label }
        assertEquals(listOf("聊天", "张三 明天见", "", "发送"), labels)
        assertTrue(els[1].line.startsWith("[1] LinearLayout \"张三 明天见\" (500,150) click"))
        assertTrue(els[2].line.contains("input(password) focused"))
        assertTrue(els[3].line.contains("(900,925)"))
        assertEquals("com.example.chat", UiTreeSerializer.packageOf(tree))
        assertNotNull(UiTreeSerializer.focusedField(tree))
        assertEquals("发送", UiTreeSerializer.hit(els, 900, 1850)?.label)
        assertEquals("张三 明天见", UiTreeSerializer.hit(els, 200, 250)?.label)
    }

    @Test fun treeRenderIsCapped() {
        val many = UiNode(bounds = r(0, 0, W, H), children = (0 until 200).map { UiNode(text = "项目 $it", clickable = true, bounds = r(0, it * 10, W, it * 10 + 10)) })
        val els = UiTreeSerializer.elements(many, W, H)
        assertEquals(UiTreeSerializer.MAX_ELEMENTS, els.size)
        val out = UiTreeSerializer.render(els, maxChars = 300)
        assertTrue(out.length <= 330); assertTrue(out.contains("more)"))
        assertTrue(UiTreeSerializer.elements(null, W, H).isEmpty())
    }

    // ------------------------------------------------------------------ safety

    @Test fun safetyRules() {
        val els = UiTreeSerializer.elements(tree, W, H)
        assertTrue(GuiSafety.review(GuiAction.Tap(NPoint(0.9f, 0.925f)), els, W, H, "com.example.chat") is GuiVerdict.Confirm)
        assertEquals(GuiVerdict.Allow, GuiSafety.review(GuiAction.Tap(NPoint(0.5f, 0.15f)), els, W, H, "com.example.chat"))
        assertTrue(GuiSafety.review(GuiAction.Tap(NPoint(0.5f, 0.15f), note = "重要操作"), els, W, H, null) is GuiVerdict.Confirm)
        assertTrue(GuiSafety.review(GuiAction.Type("123456"), els, W, H, null) is GuiVerdict.Block)
        assertTrue(GuiSafety.review(GuiAction.Tap(NPoint(0.5f, 0.15f)), els, W, H, "com.eg.android.AlipayGphone") is GuiVerdict.Block)
        assertTrue(GuiSafety.review(GuiAction.Launch("com.chinamworld.bank"), els, W, H, null) is GuiVerdict.Block)
        assertEquals(GuiVerdict.Allow, GuiSafety.review(GuiAction.Back, els, W, H, null))
        assertEquals("send", GuiSafety.sensitiveWord("Send"))
        assertNull(GuiSafety.sensitiveWord("Sender name"))
        assertEquals("支付", GuiSafety.sensitiveWord("立即支付 ¥12"))
    }

    // ------------------------------------------------------------------ loop

    private class FakeDevice(val root: UiNode? = null) : GuiDevice {
        val done = ArrayList<GuiAction>()
        var observed = 0
        override suspend fun observe(): GuiObservation { observed++; return GuiObservation("/tmp/shot.jpg", 1000, 2000, root, root?.pkg) }
        override suspend fun execute(action: GuiAction, obs: GuiObservation): ExecResult { done += action; return ExecResult(true) }
    }

    private class Script(vararg replies: String) : GuiModel {
        val queue = ArrayDeque(replies.toList())
        val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>): String { seen += messages; return queue.removeFirstOrNull() ?: "do(action=\"Back\")" }
    }

    private class Cb(val allow: Boolean = true, val takeOver: Boolean = true) : GuiCallbacks {
        val steps = ArrayList<GuiStep>()
        var confirms = 0
        override suspend fun confirm(action: GuiAction, reason: String): Boolean { confirms++; return allow }
        override suspend fun takeOver(message: String) = takeOver
        override suspend fun ask(question: String): String? = "第一个"
        override fun onStep(step: GuiStep) { steps += step }
    }

    private fun loop(model: GuiModel, dev: GuiDevice, cb: GuiCallbacks, profile: GuiProfile = GuiProfile.AUTOGLM, max: Int = 30, stop: () -> Boolean = { false }) =
        GuiAgentLoop(model, dev, cb, profile, maxSteps = max, settleMs = 0, stop = stop)

    @Test fun loopRunsUntilFinish() = runBlocking {
        val dev = FakeDevice(tree)
        val model = Script("<answer>do(action=\"Launch\", app=\"设置\")</answer>", "<answer>do(action=\"Tap\", element=[500,75])</answer>", "<answer>finish(message=\"好了\")</answer>")
        val out = loop(model, dev, Cb()).run("打开设置")
        assertEquals(GuiStop.DONE, out.stop); assertEquals("好了", out.message)
        assertEquals(2, dev.done.size)
        assertEquals(3, out.steps.size)
        // every step sends the newest screenshot and the history so far
        val last = model.seen.last()
        assertEquals(PromptMessage.SYSTEM, last[0].role)
        assertEquals(listOf("/tmp/shot.jpg"), last[1].images)
        assertTrue(last[1].content.contains("1. 打开 设置 → ok"))
        assertTrue(last[1].content.contains("当前应用：com.example.chat"))
        assertTrue(last[1].content.contains("\"发送\""))
    }

    @Test fun loopRetriesFormatErrorsThenGivesUp() = runBlocking {
        val ok = loop(Script("嗯", "<answer>finish(message=\"x\")</answer>"), FakeDevice(), Cb()).run("t")
        assertEquals(GuiStop.DONE, ok.stop)
        assertEquals(GuiStepStatus.FORMAT_ERROR, ok.steps.first().status)
        val bad = loop(Script("a", "b", "c", "d"), FakeDevice(), Cb()).run("t")
        assertEquals(GuiStop.ERROR, bad.stop)
    }

    @Test fun loopStepCapAndStop() = runBlocking {
        val dev = FakeDevice()
        val capped = loop(Script(*Array(10) { "<answer>do(action=\"Swipe\", start=[500,${800 - it}], end=[500,200])</answer>" }), dev, Cb(), max = 3).run("t")
        assertEquals(GuiStop.MAX_STEPS, capped.stop); assertEquals(3, dev.done.size)
        var n = 0
        val stopped = loop(Script(), FakeDevice(), Cb(), stop = { n++ > 0 }).run("t")
        assertEquals(GuiStop.STOPPED, stopped.stop)
    }

    @Test fun loopAsksBeforeSensitiveTapAndStopsWhenDenied() = runBlocking {
        val dev = FakeDevice(tree)
        val cb = Cb(allow = false)
        val out = loop(Script("<answer>do(action=\"Tap\", element=[900,925])</answer>"), dev, cb).run("发消息")
        assertEquals(GuiStop.STOPPED, out.stop)
        assertEquals(1, cb.confirms)
        assertTrue(dev.done.isEmpty())
        assertEquals(GuiStepStatus.DENIED, out.steps.single().status)
        // allowed → executed
        val dev2 = FakeDevice(tree)
        loop(Script("<answer>do(action=\"Tap\", element=[900,925])</answer>", "finish(message=\"ok\")"), dev2, Cb(allow = true)).run("发消息")
        assertEquals(1, dev2.done.size)
    }

    @Test fun loopHandsPasswordsToTheUser() = runBlocking {
        val dev = FakeDevice(tree)
        val out = loop(Script("<answer>do(action=\"Type\", text=\"pw\")</answer>", "finish(message=\"ok\")"), dev, Cb()).run("登录")
        assertTrue(dev.done.isEmpty())
        assertEquals(GuiStepStatus.BLOCKED, out.steps.first().status)
        assertEquals(GuiStop.DONE, out.stop)
        val refused = loop(Script("<answer>do(action=\"Type\", text=\"pw\")</answer>"), FakeDevice(tree), Cb(takeOver = false)).run("登录")
        assertEquals(GuiStop.STOPPED, refused.stop)
    }

    @Test fun loopStopsOnRepeatedNoOp() = runBlocking {
        val model = Script(*Array(20) { "<answer>do(action=\"Back\")</answer>" })
        val out = loop(model, FakeDevice(tree), Cb()).run("t")
        assertEquals(GuiStop.ERROR, out.stop)
        assertTrue(model.seen.any { it[1].content.contains("without effect") })
    }

    @Test fun loopAskAndTakeOverContinue() = runBlocking {
        val dev = FakeDevice()
        val out = loop(Script("<answer>do(action=\"Interact\")</answer>", "<answer>do(action=\"Take_over\", message=\"验证码\")</answer>", "finish(message=\"ok\")"), dev, Cb()).run("t")
        assertEquals(GuiStop.DONE, out.stop)
        assertTrue(dev.done.isEmpty())
    }

    // ------------------------------------------------------------------ prompts + manifest

    @Test fun promptsMatchEachFormat() {
        val a = GuiPrompt.system(GuiProfile.AUTOGLM, "2026-10-24")
        assertTrue(a.contains("do(action=\"Tap\", element=[x,y])") && a.contains("finish(message=") && a.contains("2026-10-24"))
        val m = GuiPrompt.system(GuiProfile.MAI_UI)
        assertTrue(m.contains("\"mobile_use\"") && m.contains("999x999"))
        assertTrue(GuiPrompt.system(GuiProfile.QWEN_VL).contains("1000x1000"))
        val noShot = GuiPrompt.messages(GuiProfile.QWEN_VL, "t", emptyList(), GuiObservation(null, 10, 10, null), emptyList(), true, null)
        assertTrue(noShot[1].images.isEmpty()); assertTrue(noShot[1].content.contains("No screenshot"))
    }

    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }

    @Test fun accessibilityServiceDeclared() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val svc = manifest.substringAfter(".agent.gui.android.IrisAccessibilityService").substringBefore("</service>")
        assertTrue(svc.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(svc.contains("android:exported=\"false\""))
        assertTrue(svc.contains("@xml/iris_accessibility_service"))
        val cfg = file("src/main/res/xml/iris_accessibility_service.xml").readText()
        for (k in listOf("canPerformGestures=\"true\"", "canRetrieveWindowContent=\"true\"", "canTakeScreenshot=\"true\"")) assertTrue(k, cfg.contains(k))
        // no new dangerous permissions for 形态 A
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
    }
}

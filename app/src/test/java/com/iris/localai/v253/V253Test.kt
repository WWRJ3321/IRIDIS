package com.iris.localai.v253

import com.iris.localai.ui.tour.TourLogic
import com.iris.localai.ui.tour.TourRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.3 源码断言 + 纯函数测试：① 聚光灯新手引导（步骤、触摸拦截、动画、触发 / 重看、7 语言）；
 * ② 动画顺滑度修补；③ 版本 / CHANGELOG。
 */
class V253Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val main = "app/src/main/java/com/iris/localai"
    private val tourSrc get() = src("$main/ui/tour/Tour.kt")
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")
    private val stepKeys = listOf(
        "menu", "model", "character", "newchat", "input", "agent", "skills", "attach", "think", "speed", "voice",
        "drawer", "download", "import", "models", "temp", "perf", "tts", "kb", "agentset", "mcp", "replay",
    )

    // ---------------------------------------------------------------- ① 步骤
    @Test fun stepsCoverHomeThenSettings() {
        val t = tourSrc
        val steps = Regex("TourStep\\(TourId\\.(\\w+), TourScreen\\.(\\w+),").findAll(t).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("15–22 steps, got ${steps.size}", steps.size in 15..22)
        // every control of the home (chat) screen first
        val home = listOf("MENU", "MODEL", "CHARACTER", "NEW_CHAT", "INPUT", "AGENT", "SKILLS", "ATTACH", "THINK", "SPEED", "VOICE")
        assertEquals(home, steps.take(home.size).map { it.first })
        assertTrue(steps.take(home.size).all { it.second == "CHAT" })
        // then the settings tour
        for (id in listOf("DOWNLOAD", "IMPORT", "MODEL_LIST", "TEMPERATURE", "PERF_TIER", "VOICE_TTS", "KB", "AGENT_MODE", "MCP", "REPLAY"))
            assertTrue(id, steps.any { it.first == id })
        assertEquals("REPLAY", steps.last().first)
        // every step's target is really marked in the UI
        val ui = File(root(), "$main/ui").walkTopDown().filter { it.extension == "kt" && it.name != "Tour.kt" }.joinToString("\n") { it.readText() }
        for ((id, _) in steps) assertTrue("tourTarget(TourId.$id)", ui.contains("tourTarget(TourId.$id)"))
    }

    @Test fun mainActivityDrivesStackAndDrawer() {
        val a = src("$main/ui/MainActivity.kt")
        assertTrue(a.contains("LocalTour provides tour"))
        assertTrue(a.contains("if (tour.active) com.iris.localai.ui.tour.TourOverlay(tour, navigate = ::tourNavigate)"))
        assertTrue(a.contains("TourScreen.CONTEXT -> listOf(Route.CHAT, Route.SETTINGS, Route.MEMORY)"))
        assertTrue(a.contains("tour.onFinish = { stack = listOf(Route.CHAT.name); onTourDone() }"))
        // normal animated transitions are reused (the stack is set, AnimatedContent animates it)
        assertTrue(a.contains("IrisMotion.push(forward = targetState.size >= initialState.size)"))
        val chat = src("$main/ui/chat/ChatScreen.kt")
        assertTrue(chat.contains("if (tourDrawer) drawer.open() else if (drawer.currentValue == DrawerValue.Open) drawer.close()"))
    }

    // ---------------------------------------------------------------- ① 触摸拦截（用户硬性要求）
    @Test fun onlyTheTargetReceivesTaps() {
        val t = tourSrc
        // consumeAllPointers: awaits every pointer event and consumes every change
        val fn = t.substring(t.indexOf("fun Modifier.consumeAllPointers()"), t.indexOf("private fun Rect.toTour()"))
        assertTrue(fn.contains("while (true)"))
        assertTrue(fn.contains("awaitPointerEvent(PointerEventPass.Main)"))
        assertTrue(fn.contains("event.changes.forEach { it.consume() }"))
        // the tooltip card's whole area consumes input (not just clickable buttons)
        val card = t.substring(t.indexOf("private fun TipCard("))
        assertTrue(card.contains(".consumeAllPointers()"))
        assertTrue(card.indexOf(".consumeAllPointers()") < card.indexOf(".background(c.surface, IrisRadius.mediumShape)"))
        // scrim pieces around a pass-through target consume everything
        val blocker = t.substring(t.indexOf("private fun Blocker("), t.indexOf("private fun TipCard("))
        assertTrue(blocker.contains(".consumeAllPointers()"))
        val passBranch = t.substring(t.indexOf("if (s != null && step?.passThrough == true)"), t.indexOf("// One full-screen blocker"))
        assertEquals(4, Regex("\\bBlocker\\(").findAll(passBranch).count())
        // full-screen blocker: consumes the down and every following change; a tap in the hole only advances
        val full = t.substring(t.indexOf("// One full-screen blocker"), t.indexOf("// ---- tooltip card ----"))
        assertTrue(full.contains("down.consume()"))
        assertTrue(full.contains("event.changes.forEach { it.consume() }"))
        assertTrue(full.contains("hole.contains(down.position) && hole.contains(lastPos)) tour.next()"))
        // while a step waits for its target nothing is pass-through (s == null → full blocker)
        assertTrue(t.contains("if (s != null && step?.passThrough == true) {"))
        // pass-through: the target observes the tap on the Initial pass and never consumes it
        val target = t.substring(t.indexOf("fun Modifier.tourTarget("), t.indexOf("/** Swallows every pointer event"))
        assertTrue(target.contains("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)"))
        assertTrue(target.contains("waitForUpOrCancellation(PointerEventPass.Initial)"))
        assertFalse(target.contains(".consume()"))
        // only toggles pass through; panels / pickers / voice / navigation are consumed and just advance
        val pass = Regex("TourStep\\(TourId\\.(\\w+)[^\\n]*passThrough = true").findAll(t).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("AGENT", "THINK"), pass)
    }

    // ---------------------------------------------------------------- ① 视觉 / 动画
    @Test fun spotlightVisualsAndMotion() {
        val t = tourSrc
        assertTrue(t.contains("private const val SCRIM_ALPHA = 0.65f"))
        assertTrue(t.contains("compositingStrategy = CompositingStrategy.Offscreen"))
        assertTrue(t.contains("blendMode = BlendMode.Clear"))
        val m = src("$main/ui/motion/TourMotion.kt")
        assertTrue(m.contains("const val HOLE_MS = 350"))
        assertTrue(m.contains("fun <T> hole(): TweenSpec<T> = tween(HOLE_MS, easing = FastOutSlowInEasing)"))
        assertTrue(t.contains("holeAnim.animateTo(hole, TourMotion.hole())"))
        assertFalse("animations only via ui/motion", t.contains("tween("))
        assertTrue(t.contains("Animatable(Rect.Zero, Rect.VectorConverter)"))
        assertTrue(t.contains("rememberInfiniteTransition(label = \"tourPulse\")"))
        assertTrue(t.contains("label = \"tourTip\""))
        // back = previous step (re-registered per step so it stays on top)
        assertTrue(t.contains("key(tour.index) { BackHandler(enabled = tour.active) { tour.back() } }"))
        // missing target → skipped, never stuck
        assertTrue(t.contains("val target = found ?: run { tour.skipMissing(); return@LaunchedEffect }"))
        // matte card, no blur / gradient / glass
        for (bad in listOf("Brush", "Gradient", "blur(", "glass", "Segmented")) assertFalse(bad, t.contains(bad))
    }

    @Test fun triggerAndReplay() {
        val m = src("$main/data/Models.kt")
        assertTrue(m.contains("@kotlinx.serialization.SerialName(\"tour253_done\")"))
        val s = src("$main/ui/settings/SettingsScreens.kt")
        assertTrue(s.contains("NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.tour253_replay), com.iris.localai.I18n.s(com.iris.localai.R.string.tour253_replay_sub)"))
        assertTrue(s.contains("{ vm.update { it.copy(tour253Done = false) } }"))
    }

    @Test fun stringsSevenLanguages() {
        val keys = listOf("skip", "prev", "next", "done", "counter", "replay", "replay_sub") + stepKeys.flatMap { listOf("${it}_t", "${it}_b") }
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/tour253_$k", x.contains("name=\"tour253_$k\""))
            assertTrue(d, x.contains("<string name=\"tour253_counter\">%1\$d/%2\$d</string>"))
            assertFalse(d, x.contains("name=\"ob252_"))
        }
        val zh = src("app/src/main/res/values/strings.xml")
        assertTrue(zh.contains("<string name=\"tour253_skip\">跳过</string>"))
        assertTrue(zh.contains("<string name=\"tour253_prev\">上一步</string>"))
        assertTrue(zh.contains("<string name=\"tour253_next\">下一步</string>"))
        assertTrue(zh.contains("<string name=\"tour253_done\">完成</string>"))
        assertTrue(zh.contains("<string name=\"tour253_replay\">新手引导</string>"))
    }

    // ---------------------------------------------------------------- ① 纯函数
    @Test fun gateAndPaging() {
        assertTrue(TourLogic.shouldShow(true, false))
        assertFalse(TourLogic.shouldShow(false, false))
        assertFalse(TourLogic.shouldShow(true, true))
        assertEquals(1, TourLogic.next(0, 22)); assertNull(TourLogic.next(21, 22))
        assertNull(TourLogic.prev(0)); assertEquals(2, TourLogic.prev(3))
        assertEquals(3 to 22, TourLogic.counter(2, 22))
        assertEquals(1 to 22, TourLogic.counter(-5, 22))
    }

    @Test fun usableAndHole() {
        assertTrue(TourLogic.usable(TourRect(10f, 10f, 110f, 60f), 400f, 800f))
        assertFalse(TourLogic.usable(TourRect(10f, 10f, 12f, 60f), 400f, 800f))          // too thin
        assertFalse(TourLogic.usable(TourRect(-300f, 10f, -10f, 60f), 400f, 800f))       // closed drawer, off screen
        assertFalse(TourLogic.usable(TourRect(0f, 780f, 100f, 900f), 400f, 800f))        // mostly below the view
        assertFalse(TourLogic.usable(TourRect(0f, 0f, 10f, 10f), 0f, 0f))                // overlay not measured yet
        val h = TourLogic.hole(TourRect(2f, 100f, 398f, 150f), 8f, 400f, 800f)
        assertEquals(TourRect(0f, 92f, 400f, 158f), h)                                    // clamped to the view
        assertTrue(TourLogic.same(TourRect(0f, 0f, 10f, 10f), TourRect(0.5f, 0f, 10f, 10.9f)))
        assertFalse(TourLogic.same(TourRect(0f, 0f, 10f, 10f), TourRect(3f, 0f, 10f, 10f)))
    }

    @Test fun tooltipPlacement() {
        // hole near the top → card below it
        assertEquals(212f, TourLogic.tipY(TourRect(0f, 100f, 100f, 200f), 150f, 40f, 760f, 12f), 0.01f)
        // hole near the bottom → card above it
        assertEquals(488f, TourLogic.tipY(TourRect(0f, 650f, 100f, 700f), 150f, 40f, 760f, 12f), 0.01f)
        // lower-half hole with room above → above
        assertEquals(238f, TourLogic.tipY(TourRect(0f, 400f, 100f, 700f), 150f, 40f, 760f, 12f), 0.01f)
        // no room either side → the roomier side (below here), clamped
        assertEquals(610f, TourLogic.tipY(TourRect(0f, 100f, 100f, 650f), 150f, 40f, 760f, 12f), 0.01f)
        // no room on either side → clamped inside the view, never off screen
        val y = TourLogic.tipY(TourRect(0f, 60f, 100f, 740f), 150f, 40f, 760f, 12f)
        assertTrue(y >= 40f && y + 150f <= 760f)
        // x: centred on the hole, clamped to the margins
        assertEquals(100f, TourLogic.tipX(250f, 300f, 600f, 16f), 0.01f)
        assertEquals(16f, TourLogic.tipX(20f, 300f, 400f, 16f), 0.01f)
        assertEquals(84f, TourLogic.tipX(390f, 300f, 400f, 16f), 0.01f)
        assertEquals(16f, TourLogic.tipX(200f, 500f, 400f, 16f), 0.01f)                 // wider than the view
    }

    // ---------------------------------------------------------------- ② 动画修补
    @Test fun animationFixes() {
        val s = src("$main/ui/settings/SettingsScreens.kt")
        for (c in listOf("Fold(s.backgroundGeneration)", "Fold(s.thermalLimitC > 0)", "Fold(s.thermalCutoffC > 0)", "Fold(hasBg)"))
            assertTrue(c, s.contains("com.iris.localai.ui.components.$c"))
        assertFalse(s.contains("if (s.thermalLimitC > 0) {"))
        val v = src("$main/ui/settings/VoiceAndApiPages.kt")
        assertTrue(v.contains("com.iris.localai.ui.components.Fold(sys)"))
        assertTrue(v.contains("com.iris.localai.ui.components.Fold(!sys && customVoice == null"))
        for (f in listOf("ui/chat/TrashSheet.kt", "ui/imagegen/ImageGenScreen.kt", "ui/models/DownloadModelsScreen.kt", "ui/chat/ComposerVoiceSkills.kt"))
            assertTrue(f, src("$main/$f").contains("Modifier.animateItem(fadeInSpec = com.iris.localai.ui.motion.IrisMotion.standard()"))
        assertEquals(3, Regex("Modifier\\.animateItem\\(").findAll(src("$main/ui/models/DownloadModelsScreen.kt")).count())
        val ch = src("$main/ui/character/CharactersPage.kt")
        assertTrue(ch.contains("label = \"characterEditor\""))
        assertTrue(ch.contains("com.iris.localai.ui.motion.IrisMotion.push(forward = targetState != null)"))
        assertFalse(ch.contains("        return\n    }\n\n    SubpageScaffold"))
        assertTrue(src("$main/ui/motion/EnterOnce.kt").contains("fun Modifier.enterOnce(): Modifier"))
        assertTrue(src("$main/ui/imagegen/ImageHistory.kt").contains("Modifier.fillMaxSize().enterOnce()"))
        assertEquals(2, Regex("Modifier\\.fillMaxSize\\(\\)\\.enterOnce\\(\\)").findAll(src("$main/ui/markdown/RichViews.kt")).count())
        // MCP / 知识库编辑 are real routes with the push transition (verified, unchanged)
        val a = src("$main/ui/MainActivity.kt")
        assertTrue(a.contains("Route.MCP -> com.iris.localai.ui.agent.McpPage(onBack = pop)"))
        assertTrue(a.contains("Route.KB_EDITOR -> com.iris.localai.ui.agent.KbEditorRoute(onBack = pop)"))
    }

    // ---------------------------------------------------------------- ③ 版本 / CHANGELOG
    @Test fun versionAndChangelog() {
        val g = src("app/build.gradle.kts")
        // 2.5.4: 版本号随新版前进，只查 2.5.3 段仍在 2.5.2 之前
        assertTrue(Regex("versionCode = (\\d+)").find(g)!!.groupValues[1].toInt() >= 252)
        val c = src("CHANGELOG.md")
        assertTrue(c.indexOf("## 2.5.3") in 0 until c.indexOf("## 2.5.2"))
        val sec = c.substring(c.indexOf("## 2.5.3"), c.indexOf("## 2.5.2"))
        assertEquals("**本版未在真机测试。**", sec.lines()[1])
        for (w in listOf("新手引导", "只有高亮的按钮可点", "动画")) assertTrue(w, sec.contains(w))
    }
}

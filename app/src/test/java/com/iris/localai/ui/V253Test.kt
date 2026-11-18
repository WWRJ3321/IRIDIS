package com.iris.localai.ui

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


}

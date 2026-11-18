package com.iris.localai.ui

import com.iris.localai.ui.settings.RackInput
import com.iris.localai.ui.settings.RackRules
import com.iris.localai.ui.settings.RackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.14.4 「机箱没动画」: the 运行状态 rack snapped every animation whenever LocalDecorativeMotion
 * was off (streaming, battery saver, 视觉效果 省电), so drawers / boot / fans never moved.
 * Source-level checks on StatusRack.kt plus the pure rules.
 */
class V1144RackTest {
    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    private val rack by lazy { source("src/main/java/com/iris/localai/ui/settings/StatusRack.kt") }
    private val body by lazy { rack.substringAfter("fun StatusRack(").substringBefore("private class RackGeom") }

    @Test fun onlySystemRemoveAnimationsSnapsTheRack() {
        assertTrue(body.contains("val reduce = LocalReduceMotion.current\n"))
        assertFalse("decorative flag must not snap drawers/boot", body.contains("LocalReduceMotion.current || !LocalDecorativeMotion.current"))
        // drawers still slide with a token spring, and snap only under reduce
        assertTrue(body.contains("pulls[i].animateTo(target, IrisMotion.Gentle.float())"))
        assertTrue(body.contains("if (reduce) pulls[i].snapTo(target)"))
    }

    @Test fun loopsRunOnlyWhileVisibleAndAllowed() {
        assertTrue(body.contains("onGloballyPositioned"))
        assertTrue(body.contains("boundsInWindow()"))
        assertTrue(body.contains("val loops = !reduce && onScreen && (LocalDecorativeMotion.current || generating)"))
        // the infinite transition is composed only inside `if (loops)` — never unconditionally
        val beforeIf = body.substringBefore("if (loops) {")
        assertFalse("infinite transition outside the loops gate", beforeIf.contains("rememberInfiniteTransition("))
        assertTrue(body.substringAfter("if (loops) {").substringBefore("} else {").contains("rememberInfiniteTransition("))
    }

    @Test fun animatedValuesAreReadInDrawNotComposition() {
        assertFalse("angle read via delegate recomposes the whole rack per frame", body.contains("val angle by spin"))
        assertFalse(body.contains("val blink by spin"))
        val canvas = body.substringAfter("Canvas(").substringBefore("// 1.14.1: the same parts as a readable list")
        assertTrue(canvas.contains("blinkState.value"))
        assertTrue(canvas.contains("angleState.value"))
        // fan + LEDs still get the animated values (face helpers keep the params)
        assertTrue(canvas.contains("fan(W * f, D * 0.50f, fr, fanAngle"))
        assertTrue(canvas.contains("ledOf(m.state, blink)"))
        // the list's breathing dot reads the pulse in its graphicsLayer
        assertTrue(body.contains("graphicsLayer { val sc = if (breathe) 0.8f + 0.4f * blinkState.value"))
    }

    @Test fun fansIdleWhenAnythingIsUpAndTapToggleIsNotStale() {
        assertTrue(body.contains("val fansOn = generating || modelReady || up > 0"))
        assertTrue(body.contains("rememberUpdatedState(sel)"))
        assertTrue(body.contains("hit == selNow -> -1"))
    }

    @Test fun noRawTweenInRack() {
        assertFalse(rack.contains("tween("))
        assertFalse(rack.contains("durationMillis"))
    }

    @Test fun rulesStillPickRunningSlot() {
        val idle = RackRules.modules(RackInput(webOn = true, memoryOn = true))
        assertEquals(-1, RackRules.autoSelect(idle))
        assertTrue(idle.count { it.state.up } > 0) // idle rack with parts up → fans idle-spin
        val busy = RackRules.modules(RackInput(modelName = "m", modelState = RackState.RUN, webOn = true, agentMode = true, agentRunning = true, toolGroup = "WEB"))
        assertEquals(2, RackRules.autoSelect(busy))
    }
}

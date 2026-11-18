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
 * 1.14.5「机箱抽屉出来里面没有东西看和点呀」: the pulled-out drawer of the 运行状态 rack shows a
 * readout of its part on its top face, and tapping that readout does what the list row does.
 * Source-level checks on StatusRack.kt plus the data the readout draws.
 */
class V1145DrawerTest {
    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    private val rack by lazy { source("src/main/java/com/iris/localai/ui/settings/StatusRack.kt") }
    private val body by lazy { rack.substringAfter("fun StatusRack(").substringBefore("private class RackGeom") }
    private val geom by lazy { rack.substringAfter("private class RackGeom") }
    private val canvas by lazy { body.substringAfter("Canvas(").substringBefore("// 1.14.1: the same parts as a readable list") }

    @Test fun drawerSlidesOutFarEnoughToShowContents() {
        assertTrue(geom.contains("val OPEN = 0.26f"))
        assertTrue(canvas.contains("val pull = pulls[i].value * OPEN"))
        // the left margin is still sized by PULL + MARGIN: the deeper drawer must fit inside it (x0 = 0.04)
        assertTrue(geom.contains("val PULL = 0.15f"))
        assertTrue(0.04f - 0.26f >= -(0.15f + 0.08f))
    }

    @Test fun readoutDrawnOnlyForTheSelectedDrawer() {
        assertTrue(canvas.contains("if (i == sel && reveal > 0f)"))
        assertTrue(canvas.contains("drawerReadout(m, x0, x1, y, z1, reveal"))
        val readout = geom.substringAfter("fun DrawScope.drawerReadout(").substringBefore("companion object")
        // name + state, detail, chips, and an open hint — all drawn on the top face
        assertTrue(readout.contains("mod.name"))
        assertTrue(readout.contains("mod.state.label"))
        assertTrue(readout.contains("mod.detail"))
        assertTrue(readout.contains("mod.chips"))
        assertTrue(readout.contains("打开 ›"))
        assertTrue(readout.contains("topText("))
        // crisp: real text layouts, ellipsized to the drawer width
        assertTrue(geom.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(geom.contains("drawText(layout"))
    }

    @Test fun openDrawerIsARealControl() {
        assertTrue(body.contains("val openNow by rememberUpdatedState(onOpen)"))
        assertTrue(body.contains("geo.drawerHit(tap, open, modules.size, pulls[open].value * geo.OPEN)"))
        assertTrue(body.contains("DrawerHit.CONTENT -> { picked = open; openNow?.invoke(open) }"))
        assertTrue(body.contains("DrawerHit.HANDLE -> picked = -1"))
        // closed drawers still pull out / toggle as before (1.14.4 stale-sel guard kept)
        assertTrue(body.contains("hit == selNow -> -1"))
        // the list row's action is the same callback
        assertTrue(body.contains("picked = i; onOpen?.invoke(i)"))
    }

    @Test fun motionRulesStillHold() {
        assertFalse(rack.contains("tween("))
        assertFalse(rack.contains("durationMillis"))
        assertTrue(body.contains("if (reduce) pulls[i].snapTo(target)"))
        // readout fades with the drawer's own (token-animated or snapped) pull, no extra animation
        assertTrue(canvas.contains("val reveal = ((pulls[i].value - 0.6f) / 0.4f).coerceIn(0f, 1f)"))
    }


    @Test fun readoutHasDataForEverySlot() {
        val mods = RackRules.modules(RackInput(
            modelName = "Qwen3", modelState = RackState.ON, modelDetail = "GPU", modelChips = listOf("GPU", "8K", "均衡"),
            apiRunning = true, apiPort = 8080, memoryOn = true, voicePacks = 2,
        ))
        assertEquals(8, mods.size)
        mods.forEach { m ->
            assertTrue(m.name.isNotBlank())
            assertTrue(m.detail.isNotBlank())
            assertTrue(m.state.label.isNotBlank())
        }
        assertEquals("Qwen3 · GPU", mods[0].detail)
        assertEquals(listOf("GPU", "8K", "均衡"), mods[0].chips)
        assertEquals(listOf(":8080"), mods[5].chips)
    }
}

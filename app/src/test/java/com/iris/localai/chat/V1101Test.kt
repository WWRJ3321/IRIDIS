package com.iris.localai.chat

import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.data.AppSettings
import com.iris.localai.data.migrated
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.ui.chat.ComposerPillStyle
import com.iris.localai.ui.chat.ComposerText
import com.iris.localai.ui.chat.SpeedLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.10.1: minimal composer — 智能体 / 思考 pills, 低速 / 高速 / 极速 speed, 「+」 menu. */
class V1101Test {

    // ---------------------------------------------------------------- speed ↔ tier

    @Test fun speedMapsToTier() {
        assertEquals(ResourceTier.LOW, SpeedLevel.LOW.tier)
        assertEquals(ResourceTier.MEDIUM, SpeedLevel.FAST.tier)
        assertEquals(ResourceTier.HIGH, SpeedLevel.MAX.tier)
        assertEquals(listOf(0, 1, 2), SpeedLevel.entries.map { it.tierOrdinal })
        assertEquals(listOf("省电", "中", "高"), SpeedLevel.entries.map { it.tier.short })
    }

    @Test fun tierMapsToSpeed() {
        assertEquals(SpeedLevel.LOW, SpeedLevel.ofTier(ResourceTier.LOW))
        assertEquals(SpeedLevel.FAST, SpeedLevel.ofTier(ResourceTier.MEDIUM))
        assertEquals(SpeedLevel.MAX, SpeedLevel.ofTier(ResourceTier.HIGH))
        assertEquals(SpeedLevel.LOW, SpeedLevel.ofTier(0))
        assertEquals(SpeedLevel.FAST, SpeedLevel.ofTier(1))
        assertEquals(SpeedLevel.MAX, SpeedLevel.ofTier(2))
        // Out of range (e.g. the removed 「全部」 ordinal 3, or -1) follows ResourceTier.of → DEFAULT = 高.
        assertEquals(SpeedLevel.MAX, SpeedLevel.ofTier(ResourceTier.REMOVED_ALL_ORDINAL))
        assertEquals(SpeedLevel.MAX, SpeedLevel.ofTier(-1))
        assertEquals(SpeedLevel.ofTier(ResourceTier.DEFAULT), SpeedLevel.ofTier(99))
    }

    @Test fun roundTripBothWays() {
        for (s in SpeedLevel.entries) assertEquals(s, SpeedLevel.ofTier(s.tierOrdinal))
        for (t in ResourceTier.entries) assertEquals(t, SpeedLevel.ofTier(t).tier)
        assertEquals(ResourceTier.entries.size, SpeedLevel.entries.size)
    }

    @Test fun speedLabelsInTierOrder() {
        assertEquals(listOf("低速", "高速", "极速"), SpeedLevel.labels)
        ResourceTier.entries.forEachIndexed { i, t -> assertEquals(SpeedLevel.labels[i], SpeedLevel.ofTier(t).label) }
    }

    @Test fun tierItselfUnchanged() {
        // Display-only rename: the tier's own names / hints, its count and default are untouched.
        assertEquals(listOf("省电", "中资源", "高资源"), ResourceTier.entries.map { it.label })
        assertEquals(ResourceTier.HIGH, ResourceTier.DEFAULT)
        assertTrue(ResourceTier.entries.all { it.hint.isNotBlank() })
    }

    // ---------------------------------------------------------------- settings stay the same keys

    @Test fun speedWritesTheResourceTierSetting() {
        val j = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        for (s in SpeedLevel.entries) {
            val st = AppSettings().copy(resourceTier = s.tierOrdinal).migrated()
            assertEquals(s.tierOrdinal, st.resourceTier)
            val back = j.decodeFromString(AppSettings.serializer(), j.encodeToString(AppSettings.serializer(), st))
            assertEquals(s, SpeedLevel.ofTier(back.resourceTier))
            assertTrue(j.encodeToString(AppSettings.serializer(), st).contains("\"resourceTier\":${s.tierOrdinal}"))
        }
    }

    @Test fun agentAndThinkingUseExistingKeys() {
        val j = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertFalse(AgentSettings().agentMode) // off = 对话 by default
        assertTrue(j.encodeToString(AgentSettings.serializer(), AgentSettings(agentMode = true)).contains("\"agentMode\":true"))
        assertTrue(AppSettings().deepThinking)
        assertTrue(j.encodeToString(AppSettings.serializer(), AppSettings(deepThinking = false)).contains("\"deepThinking\":false"))
    }



    // ---------------------------------------------------------------- labels + style

    @Test fun labels() {
        assertEquals("智能体", ComposerText.AGENT)
        assertEquals("思考", ComposerText.THINK)
        assertEquals("对话", ComposerText.CHAT)
        assertTrue(ComposerText.THINK_UNSUPPORTED.contains("不支持思考"))
        assertTrue(ComposerText.agentNotice(true).startsWith("已切换到智能体模式"))
        assertTrue(ComposerText.agentNotice(false).startsWith("已切换到对话模式"))
    }

    @Test fun pillStyle() {
        assertEquals(36f, ComposerPillStyle.HEIGHT_DP)
        assertEquals(0xFF0D84FF.toInt(), argb(ComposerPillStyle.background(on = true, dark = false)))
        assertEquals(0xFF3482FF.toInt(), argb(ComposerPillStyle.background(on = true, dark = true)))
        assertEquals(0xFFFFFFFF.toInt(), argb(ComposerPillStyle.content(on = true, dark = false)))
        assertEquals(0xFFFFFFFF.toInt(), argb(ComposerPillStyle.content(on = true, dark = true)))
        for (dark in listOf(false, true)) {
            val off = ComposerPillStyle.background(on = false, dark = dark)
            assertTrue("off is grey", sat(off) < 0.1f)
            assertTrue("on text contrast", contrast(ComposerPillStyle.ON_TEXT, ComposerPillStyle.background(true, dark)) >= 3.0)
            assertTrue("off text contrast", contrast(ComposerPillStyle.content(false, dark), off) >= 7.0)
        }
    }


    // ---------------------------------------------------------------- helpers

    private fun argb(c: androidx.compose.ui.graphics.Color): Int {
        fun ch(f: Float) = (f * 255f + 0.5f).toInt()
        return (ch(c.alpha) shl 24) or (ch(c.red) shl 16) or (ch(c.green) shl 8) or ch(c.blue)
    }

    private fun sat(c: androidx.compose.ui.graphics.Color): Float {
        val mx = maxOf(c.red, c.green, c.blue); val mn = minOf(c.red, c.green, c.blue)
        return if (mx == 0f) 0f else (mx - mn) / mx
    }

    private fun lum(c: androidx.compose.ui.graphics.Color): Double {
        fun l(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * l(c.red) + 0.7152 * l(c.green) + 0.0722 * l(c.blue)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val x = lum(a); val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun source(path: String): String =
        listOf(path, "app/$path").map { File(it) }.first { it.exists() }.readText()
}

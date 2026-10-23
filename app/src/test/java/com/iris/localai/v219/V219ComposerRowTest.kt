package com.iris.localai.v219

import com.iris.localai.ui.chat.ComposerInputRow
import com.iris.localai.ui.chat.ComposerPillStyle
import com.iris.localai.ui.chat.ComposerText
import com.iris.localai.ui.chat.VoiceCapsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.1.9 composer (user: one row less): top row 智能体 技能 思考 速度 … 「+」, then the text field and
 * the voice capsule on one row. Placeholder without the 「（可读写工作区文件）」 parenthetical.
 */
class V219ComposerRowTest {
    private fun source(path: String): String =
        listOf(path, "app/$path").map { File(it) }.first { it.exists() }.readText()
    private fun flat(s: String) = s.replace(Regex("\\s+"), " ")
    private val composer by lazy { source("src/main/java/com/iris/localai/ui/chat/Composer.kt") }
    private val body by lazy { flat(composer.substringAfter("fun Composer(").substringBefore("private fun ListeningIndicator")) }

    @Test fun twoRowsNotThree() {
        val field = body.indexOf("BasicTextField(")
        val chips = listOf("ComposerText.AGENT,", "ComposerText.SKILLS,", "ComposerText.THINK,", "SpeedChip(state.resourceTier", "IrisIcons.Plus, ComposerText.ATTACH")
            .map { body.indexOf(it) }
        assertTrue("all chips + 「+」 above the field, in order", chips.all { it in 1 until field } && chips == chips.sorted())
        // the field and the capsule share one bottom-aligned row; nothing comes after the capsule
        val row = body.lastIndexOf("Row(", field)
        assertTrue(body.substring(row, field).contains("verticalAlignment = Alignment.Bottom"))
        assertTrue(body.substring(row, field).contains("Box(Modifier.weight(1f))"))
        val capsule = body.indexOf("VoiceCapsuleButton(")
        assertTrue(capsule > field)
        assertFalse("no third row after the capsule", body.substring(capsule).contains("Row("))
    }

    @Test fun chipsAreCompactAndScrollWhilePlusStaysPinned() {
        val top = body.substring(body.indexOf("ComposerText.AGENT,") - 400, body.indexOf("BasicTextField("))
        assertTrue(top.contains("Modifier.weight(1f).horizontalScroll(rememberScrollState())"))
        // 「+」 sits after the scrolling row closes, so it never scrolls away
        assertTrue(top.indexOf("horizontalScroll") < top.indexOf("SpeedChip(") && top.indexOf("SpeedChip(") < top.indexOf("IrisIcons.Plus"))
        assertEquals(3, Regex("compact = true").findAll(top).count())
        val speed = flat(composer.substringAfter("private fun SpeedChip").substringBefore("if (open)"))
        assertTrue("speed chip compact too", speed.contains("compact = true"))
        // 4 compact chips + gaps + 「+」 fit a 390 dp phone (≈ 345 dp inside the card), estimated
        // with 13 sp CJK ≈ 13 dp per character.
        fun chip(chars: Int, trailing: Boolean) = 2 * ComposerPillStyle.COMPACT_H_PADDING_DP + 14f + 4f + chars * 13f + if (trailing) 4f + 14f else 0f
        val w = chip(3, false) + chip(2, false) + chip(2, false) + chip(2, true) + 3 * ComposerPillStyle.COMPACT_GAP_DP + ComposerPillStyle.COMPACT_HEIGHT_DP
        assertTrue("chips $w dp", w <= 345f)
    }

    @Test fun capsuleKeepsItsBehavioursAndSitsByTheLastLine() {
        assertTrue(body.contains("onHoldStart = onHoldToTalkStart") && body.contains("onHoldEnd = onHoldToTalkEnd"))
        assertTrue(body.contains("onCall = onVoiceMode") && body.contains("onSend = onSend") && body.contains("onStop = onStop"))
        assertTrue(body.contains("onHoldCancel = onHoldToTalkCancel"))
        assertEquals((ComposerInputRow.FIELD_MIN_HEIGHT_DP - VoiceCapsule.HEIGHT_DP) / 2f, ComposerInputRow.capsuleBottomPadDp())
        assertTrue(VoiceCapsule.HEIGHT_DP <= ComposerInputRow.FIELD_MIN_HEIGHT_DP)
        // f7d85db: slide distance stays in window coordinates (capsule origin), whatever its position
        val v = source("src/main/java/com/iris/localai/ui/chat/ComposerVoiceSkills.kt")
        assertTrue(v.contains("positionInWindow()"))
        assertEquals(0f, VoiceCapsule.slideUpPx(startFingerY = 20f, startOriginY = 1500f, fingerY = 320f, originY = 1200f))
    }

    @Test fun placeholderHasNoParenthetical() {
        assertEquals("交给 IRIDIS 智能体", ComposerText.AGENT_PLACEHOLDER)
        for (p in listOf(ComposerText.PLACEHOLDER, ComposerText.AGENT_PLACEHOLDER)) {
            assertFalse(p, p.contains("（") || p.contains("("))
        }
    }
}

package com.iris.localai.chat

import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Role
import com.iris.localai.data.TurnRef
import com.iris.localai.data.TurnRefKind
import com.iris.localai.data.TurnRefs
import com.iris.localai.engine.PromptMessage
import com.iris.localai.ui.chat.CapsuleTap
import com.iris.localai.ui.chat.ComposerAction
import com.iris.localai.ui.chat.ComposerActions
import com.iris.localai.ui.chat.ComposerText
import com.iris.localai.ui.chat.RefCatalog
import com.iris.localai.ui.chat.RefItem
import com.iris.localai.ui.chat.VoiceCapsule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.2 composer: one voice capsule (call / hold to dictate / send / stop) and the 「技能」 references. */
class V220ComposerTest {

    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    private val skill = TurnRef(TurnRefKind.SKILL, "pdf")
    private val tool = TurnRef(TurnRefKind.TOOL, "web_search")

    // ------------------------------------------------------------ capsule state logic

    @Test fun capsuleFaceFollowsInputAndGeneration() {
        assertEquals(ComposerAction.VOICE, ComposerActions.of(generating = false, hasText = false))
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = false, hasText = true))
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = false, hasText = false, hasAttachments = true))
        // generating wins over text: the capsule is 停止
        // 2.1.7: text while generating = 发送 (补充 / interrupt).
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = true, hasText = true))
        assertEquals(ComposerAction.STOP, ComposerActions.of(generating = true, hasText = false))
    }

    @Test fun tapAndLongPressPerFace() {
        assertEquals(CapsuleTap.CALL, VoiceCapsule.tap(ComposerAction.VOICE))
        assertEquals(CapsuleTap.SEND, VoiceCapsule.tap(ComposerAction.SEND))
        assertEquals(CapsuleTap.STOP, VoiceCapsule.tap(ComposerAction.STOP))
        // dictation only from the empty-input face
        assertTrue(VoiceCapsule.canDictate(ComposerAction.VOICE))
        assertFalse(VoiceCapsule.canDictate(ComposerAction.SEND))
        assertFalse(VoiceCapsule.canDictate(ComposerAction.STOP))
    }

    @Test fun accessibilityLabels() {
        assertEquals("通话，长按语音输入", VoiceCapsule.label(ComposerAction.VOICE))
        assertEquals("发送", VoiceCapsule.label(ComposerAction.SEND))
        assertEquals("停止生成", VoiceCapsule.label(ComposerAction.STOP))
    }

    @Test fun slideUpArmsCancel() {
        val d = 3f
        assertFalse(VoiceCapsule.cancelArmed(0f, d))
        assertFalse(VoiceCapsule.cancelArmed(-200f, d)) // sliding down never cancels
        assertFalse(VoiceCapsule.cancelArmed(VoiceCapsule.CANCEL_DRAG_DP * d - 1f, d))
        assertTrue(VoiceCapsule.cancelArmed(VoiceCapsule.CANCEL_DRAG_DP * d, d))
        assertTrue(ComposerText.LISTENING.contains("上滑取消"))
    }

    @Test fun capsuleIsAsWideAsTheOldPairAndAtLeastChipHeight() {
        assertTrue(VoiceCapsule.WIDTH_DP >= 40f + 6f + 40f)
        assertTrue(VoiceCapsule.HEIGHT_DP >= com.iris.localai.ui.chat.ComposerPillStyle.HEIGHT_DP)
    }


    // ------------------------------------------------------------ skill references

    @Test fun promptLineNamesSkillsAndTools() {
        assertEquals("", TurnRefs.promptLine(emptyList()))
        val line = TurnRefs.promptLine(listOf(skill, tool, skill))
        assertTrue(line.contains("\"pdf\"") && line.contains("skill tool") && line.contains("web_search"))
        assertEquals(1, Regex("pdf").findAll(line).count())
        assertEquals("@pdf", skill.token)
        assertEquals(listOf(skill), TurnRefs.add(TurnRefs.add(emptyList(), skill), skill))
        assertTrue(TurnRefs.needsAgent(listOf(skill), agentMode = false))
        assertFalse(TurnRefs.needsAgent(listOf(skill), agentMode = true))
        assertFalse(TurnRefs.needsAgent(emptyList(), agentMode = false))
    }

    @Test fun referenceGoesIntoThatUserTurnOnlyAndNeverTheSystemPrompt() {
        val msgs = listOf(
            ChatMessage(id = "1", role = Role.USER, content = "hi"),
            ChatMessage(id = "2", role = Role.ASSISTANT, content = "hello"),
            ChatMessage(id = "3", role = Role.USER, content = "summarise a.pdf", refs = listOf(skill, tool)),
        )
        val plain = msgs.map { it.copy(refs = emptyList()) }
        val system = ContextAssembly.system("BASE SYSTEM", "")
        fun prompt(m: List<ChatMessage>) = ContextAssembly.prompt(system, ContextAssembly.forPlanner(ContextAssembly.active(m, null, withTools = true), null, true))
        val withRefs = prompt(msgs)
        val without = prompt(plain)
        // system prompt identical (KV reuse) and free of the reference
        assertEquals(PromptMessage.SYSTEM, withRefs[0].role)
        assertEquals(without[0], withRefs[0])
        assertFalse(withRefs[0].content.contains("pdf"))
        // earlier turns untouched
        assertEquals(without.subList(0, 3), withRefs.subList(0, 3))
        // the picked turn carries the user's words + the one line
        assertEquals("summarise a.pdf\n\n" + TurnRefs.promptLine(listOf(skill, tool)), withRefs[3].content)
        assertEquals("summarise a.pdf", without[3].content)
    }

    @Test fun refsSurviveSaveAndOldChatsStillLoad() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val m = ChatMessage(id = "x", role = Role.USER, content = "q", refs = listOf(skill))
        assertEquals(listOf(skill), json.decodeFromString<ChatMessage>(json.encodeToString(m)).refs)
        val old = json.encodeToString(m.copy(refs = emptyList()))
        assertFalse(old.contains("refs"))
        assertEquals(emptyList<TurnRef>(), json.decodeFromString<ChatMessage>(old).refs)
    }

    @Test fun catalogSearchAndEmpty() {
        val cat = RefCatalog(
            skills = listOf(RefItem(skill, "pdf", "读写 PDF")),
            tools = listOf(RefItem(tool, "网页搜索", "搜索网页"), RefItem(TurnRef(TurnRefKind.TOOL, "calculator"), "计算器", "算数")),
        )
        assertEquals(3, cat.size)
        assertEquals(listOf("web_search"), cat.filter("搜索").tools.map { it.ref.name })
        assertTrue(cat.filter("搜索").skills.isEmpty())
        assertEquals(listOf("pdf"), cat.filter("PDF").skills.map { it.ref.name })
        assertEquals(cat, cat.filter("  "))
        assertTrue(RefCatalog.EMPTY.isEmpty)
        assertTrue(ComposerText.SKILLS_EMPTY.isNotBlank())
    }
}

package com.iris.localai.v200

import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.Attachment
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Conversation
import com.iris.localai.data.MessageVersions
import com.iris.localai.data.Role
import com.iris.localai.memory.HistoryRecords
import com.iris.localai.memory.MemoryCommand
import com.iris.localai.memory.MemoryDocs
import com.iris.localai.memory.MemoryEdit
import com.iris.localai.memory.MemoryOps
import com.iris.localai.memory.MemoryPrompt
import com.iris.localai.memory.MemorySettings
import com.iris.localai.memory.MemoryTarget
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 2.0.x: message versions (edit user / regenerate / edit reply, ‹ 2/3 › with the branch that
 * followed a user version) and long-term memory that is really used (对话模式 injection on by
 * default, 「记住：…」 and 「记住这条」 without the model, snapshot retaken until the first reply).
 */
class V200VersionsMemoryTest {
    private lateinit var tmp: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before fun setUp() { tmp = Files.createTempDirectory("iris200").toFile() }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun u(id: String, t: String) = ChatMessage(id = id, role = Role.USER, content = t, createdAt = 1)
    private fun a(id: String, t: String) = ChatMessage(id = id, role = Role.ASSISTANT, content = t, createdAt = 2, modelName = "M")
    private fun texts(l: List<ChatMessage>) = l.map { it.content }

    // ================================================================ persistence

    @Test fun oldSavedChatsLoadUnchanged() {
        // A 1.x / 2.0.0 conversation file: no versions / activeVersion / edited keys.
        val old = """{"id":"c1","title":"t","messages":[{"id":"m1","role":"USER","content":"你好"},{"id":"m2","role":"ASSISTANT","content":"嗨","modelName":"Q"}]}"""
        val c = json.decodeFromString(Conversation.serializer(), old)
        assertEquals(listOf("你好", "嗨"), texts(c.messages))
        for (m in c.messages) {
            assertTrue(m.versions.isEmpty()); assertEquals(0, m.activeVersion); assertFalse(m.edited)
            assertEquals(1, MessageVersions.count(m)); assertEquals(0, MessageVersions.active(m))
        }
        // …and a chat without versions is written exactly as before (no new keys).
        val back = json.encodeToString(Conversation.serializer(), c)
        assertFalse(back.contains("versions")); assertFalse(back.contains("activeVersion")); assertFalse(back.contains("edited"))
    }

    @Test fun nestedVersionsRoundTrip() {
        var msgs = listOf(u("u1", "问题一"), a("a1", "回答一"), u("u2", "追问"), a("a2", "回答二"))
        msgs = MessageVersions.editUser(msgs, "u1", "问题一（改）", now = 5)!! + a("a1b", "新回答")
        // The new branch's reply is regenerated too: versions inside a version's tail.
        msgs = msgs.dropLast(1) + MessageVersions.asNewVersionOf(msgs.last(), a("a1c", "再来一次"))
        // Fixed timestamps: a default of "now" is not written when it equals now (encodeDefaults = false).
        val conv = Conversation(id = "c", createdAt = 10, updatedAt = 11, messages = msgs)
        val back = json.decodeFromString(Conversation.serializer(), json.encodeToString(Conversation.serializer(), conv))
        assertEquals(conv, back)
        val restored = MessageVersions.switchTo(back.messages, "u1", 0)!!
        assertEquals(listOf("问题一", "回答一", "追问", "回答二"), texts(restored))
        val again = MessageVersions.switchTo(restored, "u1", 1)!!
        assertEquals(listOf("问题一（改）", "再来一次"), texts(again))
        assertEquals(2, MessageVersions.count(again.last()))
        assertEquals("新回答", MessageVersions.switchTo(again, again.last().id, 0)!!.last().content)
    }

    // ================================================================ edit a user message

    @Test fun editingAUserMessageKeepsTheOldVersionAndItsBranch() {
        val msgs = listOf(u("u1", "你好"), a("a1", "嗨"), u("u2", "讲个笑话"), a("a2", "笑话A"), u("u3", "再来"), a("a3", "笑话B"))
        val edited = MessageVersions.editUser(msgs, "u2", "  讲个冷笑话 ", now = 9)!!
        // The path ends at the edited message (the reply is generated next).
        assertEquals(listOf("你好", "嗨", "讲个冷笑话"), texts(edited))
        val m = edited.last()
        assertEquals("u2", m.id); assertEquals(2, MessageVersions.count(m)); assertEquals(1, MessageVersions.active(m))
        assertEquals(9L, m.createdAt)
        // The new reply arrives.
        val withReply = edited + a("a2b", "冷笑话")
        // ‹ : the old text and the whole conversation that followed come back.
        val back = MessageVersions.switchTo(withReply, "u2", 0)!!
        assertEquals(listOf("你好", "嗨", "讲个笑话", "笑话A", "再来", "笑话B"), texts(back))
        assertEquals(listOf("a2", "u3", "a3"), back.drop(3).map { it.id })
        assertEquals(0, MessageVersions.active(back[2]))
        // › : the new branch comes back.
        val fwd = MessageVersions.switchTo(back, "u2", 1)!!
        assertEquals(listOf("你好", "嗨", "讲个冷笑话", "冷笑话"), texts(fwd))
        // A third version keeps both earlier ones.
        val third = MessageVersions.editUser(fwd, "u2", "讲个长笑话")!!
        assertEquals(3, MessageVersions.count(third.last())); assertEquals(2, MessageVersions.active(third.last()))
        assertEquals("讲个笑话", MessageVersions.switchTo(third, "u2", 0)!![2].content)
        // Nothing to do / bad input.
        assertNull(MessageVersions.switchTo(fwd, "u2", 1))
        assertNull(MessageVersions.switchTo(fwd, "u2", 5))
        assertNull(MessageVersions.switchTo(msgs, "u1", 1))
        assertNull(MessageVersions.editUser(msgs, "a1", "x"))
        assertNull(MessageVersions.editUser(msgs, "nope", "x"))
    }

    @Test fun editedUserVersionKeepsItsAttachments() {
        val att = Attachment("a.png", "/files/attachments/a.png")
        val msgs = listOf(u("u1", "看图").copy(attachments = listOf(att)), a("a1", "一只猫"))
        val edited = MessageVersions.editUser(msgs, "u1", "这是什么动物")!!
        assertEquals(listOf(att), edited.last().attachments)
        assertTrue(MessageVersions.allAttachments(edited).contains(att))
    }

    // ================================================================ regenerate + edit a reply

    @Test fun regenerateKeepsThePreviousReplyAsAVersion() {
        val prev = a("a1", "第一版").copy(thinkingMs = 1200)
        val fresh = a("a1b", "")
        val bubble = MessageVersions.asNewVersionOf(prev, fresh)
        assertEquals("a1b", bubble.id); assertEquals(2, MessageVersions.count(bubble)); assertEquals(1, MessageVersions.active(bubble))
        val done = bubble.copy(content = "第二版")
        val msgs = listOf(u("u1", "问"), done)
        val v0 = MessageVersions.switchTo(msgs, "a1b", 0)!!
        assertEquals(listOf("问", "第一版"), texts(v0)); assertEquals(1200L, v0.last().thinkingMs)
        assertEquals("第二版", MessageVersions.switchTo(v0, "a1b", 1)!!.last().content)
        // A regenerated reply that never started gives the previous version back.
        val dropped = MessageVersions.dropActive(bubble)!!
        assertEquals("第一版", dropped.content); assertEquals(1, MessageVersions.count(dropped)); assertTrue(dropped.versions.isEmpty())
        assertNull(MessageVersions.dropActive(prev))
        val three = MessageVersions.asNewVersionOf(done, a("a1c", ""))
        val two = MessageVersions.dropActive(three)!!
        assertEquals("第二版", two.content); assertEquals(2, MessageVersions.count(two)); assertEquals(1, MessageVersions.active(two))
    }

    @Test fun editingAReplyInPlaceSavesAnEditedVersion() {
        val msgs = listOf(u("u1", "问"), a("a1", "<think>想</think>原回答").copy(outputChars = 3, totalMs = 900), u("u2", "继续"), a("a2", "后续"))
        val e = MessageVersions.editAssistant(msgs, "a1", " 改过的回答 ")!!
        // In place: nothing after it is dropped or regenerated.
        assertEquals(listOf("问", "改过的回答", "继续", "后续"), texts(e))
        val m = e[1]
        assertTrue(m.edited); assertEquals(0, m.outputChars); assertEquals(0L, m.totalMs)
        assertEquals("M", m.modelName); assertEquals(2, MessageVersions.count(m))
        val back = MessageVersions.switchTo(e, "a1", 0)!!
        // A reply's versions never swap the conversation after them.
        assertEquals(listOf("问", "<think>想</think>原回答", "继续", "后续"), texts(back))
        assertFalse(back[1].edited); assertEquals(900L, back[1].totalMs)
        assertNull(MessageVersions.editAssistant(msgs, "a1", "<think>想</think>原回答"))
        assertNull(MessageVersions.editAssistant(msgs, "a1", "   "))
        assertNull(MessageVersions.editAssistant(msgs, "u1", "x"))
    }

    // ================================================================ only the active path is read

    @Test fun modelAndHistoryIndexSeeOnlyTheActivePath() {
        val msgs = listOf(u("u1", "旧问题"), a("a1", "旧回答"))
        val edited = MessageVersions.editUser(msgs, "u1", "新问题")!! + a("a2", "新回答")
        val conv = Conversation(id = "c", messages = edited)
        val ctx = ContextAssembly.active(conv.messages, null, withTools = false).map { it.text }
        assertEquals(listOf("新问题", "新回答"), ctx)
        val rec = HistoryRecords.of(conv).map { it.content }
        assertEquals(listOf("新问题", "新回答"), rec)
        assertFalse(rec.any { it.contains("旧") })
        // Switched back: the old branch is what the model and the index read.
        val back = conv.copy(messages = MessageVersions.switchTo(conv.messages, "u1", 0)!!)
        assertEquals(listOf("旧问题", "旧回答"), ContextAssembly.active(back.messages, null, false).map { it.text })
        assertEquals(listOf("旧问题", "旧回答"), HistoryRecords.of(back).map { it.content })
    }

    @Test fun attachmentsOfStoredBranchesAreStillReferenced() {
        val att = Attachment("f.txt", "/files/attachments/f.txt")
        val msgs = listOf(u("u1", "问"), a("a1", "答"), u("u2", "看文件").copy(attachments = listOf(att)), a("a2", "好"))
        val edited = MessageVersions.editUser(msgs, "u1", "换个问法")!!
        assertTrue(edited.none { it.attachments.isNotEmpty() })
        assertEquals(listOf(att), MessageVersions.allAttachments(edited))
        assertEquals(listOf("u1", "u2", "a1", "a2").sorted(), MessageVersions.allMessages(edited).map { it.id }.distinct().sorted())
    }

    @Test fun uiWiring() {
        val screen = source("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("vm.switchVersion(m.id, v)"))
        assertTrue(screen.contains("vm.editAssistant(id, input)"))
        assertTrue(screen.contains("vm.editAndResend(id, input)"))
        assertTrue(screen.contains("RememberDialog("))
        val item = source("src/main/java/com/iris/localai/ui/chat/MessageItem.kt")
        assertTrue(item.contains("IrisIcons.ChevronLeft") && item.contains("IrisIcons.ChevronRight"))
        assertTrue(item.contains("VersionSwitcher(message, onSwitchVersion)"))
        assertFalse("animations only via IrisMotion", item.contains("tween("))
        assertFalse(screen.contains("tween("))
        assertEquals("2/3", com.iris.localai.ui.chat.VersionText.label(1, 3))
        assertNotNull(com.iris.localai.ui.icons.IrisIcons::class.java.getDeclaredMethod("getChevronLeft"))
        assertNotNull(com.iris.localai.ui.icons.IrisIcons::class.java.getDeclaredMethod("getChevronRight"))
        val vm = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("MessageVersions.asNewVersionOf(base, fresh)"))
        assertTrue(vm.contains("MessageVersions.dropActive(it)"))
        assertTrue(source("src/main/java/com/iris/localai/ui/settings/StoragePage.kt").contains("MessageVersions.allAttachments(conv.messages)"))
    }

    // ================================================================ memory: settings

    @Test fun chatInjectionIsOnByDefaultAndMigratedOnce() {
        val d = MemorySettings()
        assertTrue(d.injectChat); assertTrue(d.inject(agentMode = false))
        assertEquals(d.copy(chatInjectDefaultApplied = true, autoCompactMerged = true), d.migrated())
        val sj = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // Stored by 1.10–2.0.0 (encodeDefaults = true): injectChat false only because it was the default.
        val old = """{"enabled":true,"injectAgent":true,"injectChat":false,"autoCompactAgent":true,"autoCompactChat":true,"epoch":2}"""
        val m = sj.decodeFromString(MemorySettings.serializer(), old).migrated()
        assertTrue(m.injectChat); assertTrue(m.chatInjectDefaultApplied); assertEquals(2, m.epoch)
        // Turned off after the migration: stays off.
        val off = m.copy(injectChat = false)
        val again = sj.decodeFromString(MemorySettings.serializer(), sj.encodeToString(MemorySettings.serializer(), off)).migrated()
        assertFalse(again.injectChat)
        // The master switch still wins.
        assertFalse(d.copy(enabled = false).inject(agentMode = false))
        val repo = source("src/main/java/com/iris/localai/agent/core/AgentSettings.kt")
        assertTrue(repo.contains("val next = stored.migrated()"))
    }

    // ================================================================ memory: explicit saves

    @Test fun rememberCommandParsing() {
        assertEquals("我喜欢简短的回答", MemoryCommand.parse("记住：我喜欢简短的回答"))
        assertEquals("我喜欢简短的回答", MemoryCommand.parse("记住我喜欢简短的回答"))
        assertEquals("项目叫 IRIDIS", MemoryCommand.parse("请记住, 项目叫 IRIDIS"))
        assertEquals("项目叫 IRIDIS", MemoryCommand.parse("请帮我记住 项目叫 IRIDIS"))
        assertEquals("my cat is Mimi", MemoryCommand.parse("Remember: my cat is Mimi"))
        assertEquals("多行\n内容", MemoryCommand.parse("记住：多行\n内容"))
        for (t in listOf("记住了", "记住了吗", "记住", "记住：", "记住这条", "记住这个。", "你记住我说的话了吗？", "remember this", "我要你记住一件事", "remember me?", "Remember: what?"))
            assertNull(t, MemoryCommand.parse(t))
    }

    @Test fun rememberEntriesGoThroughMemoryOps() {
        assertEquals("a b c", MemoryCommand.entryFrom("  a\n\n b   c "))
        val long = MemoryCommand.entryFrom("长".repeat(500))
        assertEquals(300, long.length); assertTrue(long.endsWith("…"))
        assertEquals(MemoryTarget.USER, MemoryCommand.suggestTarget("我住在香港"))
        assertEquals(MemoryTarget.USER, MemoryCommand.suggestTarget("My name is W"))
        assertEquals(MemoryTarget.MEMORY, MemoryCommand.suggestTarget("项目用 Kotlin"))
        val docs = MemoryDocs(File(tmp, "memory"))
        val e = MemoryCommand.entryFrom("项目用 Kotlin")
        val r = docs.edit(MemoryTarget.MEMORY) { MemoryOps.add(it, e) }
        assertTrue(r is MemoryEdit.Ok); assertEquals("- 项目用 Kotlin", docs.read(MemoryTarget.MEMORY))
        assertTrue(MemoryCommand.userMessage(r, e, MemoryTarget.MEMORY).startsWith("已记住：项目用 Kotlin"))
        val dup = docs.edit(MemoryTarget.MEMORY) { MemoryOps.add(it, e) }
        assertTrue(MemoryCommand.userMessage(dup, e, MemoryTarget.MEMORY).startsWith("这条已经记过了"))
        // The cap applies: a full document refuses with a message the user can act on.
        val full = (0 until 60).joinToString("\n") { "- 事实$it" + "内".repeat(20) }.take(MemoryOps.CAP)
        val refused = MemoryOps.add(full, "新的事实" + "x".repeat(100))
        assertTrue(refused is MemoryEdit.Err)
        assertTrue(MemoryCommand.userMessage(refused, "x", MemoryTarget.USER).contains("已满"))
        // A long reply is shortened to one entry that fits the per-entry cap.
        assertTrue(MemoryOps.add("", long) is MemoryEdit.Ok)
        // Saved memory is what the next conversation's snapshot carries.
        assertTrue(docs.snapshot().contains("项目用 Kotlin"))
    }

    @Test fun snapshotIsRetakenUntilTheModelHasReplied() {
        val fresh = Conversation()
        assertTrue(MemoryPrompt.needsSnapshot(fresh, 0))
        val frozen = Conversation(messages = listOf(u("u", "hi")), memorySnapshot = "", memoryEpoch = 0)
        assertTrue("no reply yet → memory saved meanwhile is picked up", MemoryPrompt.needsSnapshot(frozen, 0))
        // The empty bubble of the reply being started does not count, nor a 「记住」 confirmation (no model).
        val bubble = frozen.copy(messages = frozen.messages + a("b", ""))
        assertTrue(MemoryPrompt.needsSnapshot(bubble, 0))
        val ack = frozen.copy(messages = frozen.messages + ChatMessage(role = Role.ASSISTANT, content = "已记住：x"))
        assertTrue(MemoryPrompt.needsSnapshot(ack, 0))
        // After a real reply the snapshot stays frozen (stable prefix)…
        val replied = frozen.copy(messages = frozen.messages + a("r", "好的"), memorySnapshot = "MEMORY.md:\n- x")
        assertFalse(MemoryPrompt.needsSnapshot(replied, 0))
        // …until 清空记忆 bumps the epoch.
        assertTrue(MemoryPrompt.needsSnapshot(replied, 1))
        val vm = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("MemoryPrompt.needsSnapshot(conv, ms.epoch)"))
        assertTrue(vm.contains("MemoryCommand.parse(trimmed)"))
        assertTrue(vm.contains("freezeMemory(agentMode = false)"))
    }

    private fun source(path: String): String =
        listOf(path, "app/$path").map { File(it) }.first { it.exists() }.readText()
}

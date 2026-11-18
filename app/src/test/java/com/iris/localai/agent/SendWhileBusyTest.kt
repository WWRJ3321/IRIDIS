package com.iris.localai.agent

import com.iris.localai.agent.core.AgentCallbacks
import com.iris.localai.agent.core.AgentLoop
import com.iris.localai.agent.core.AgentModel
import com.iris.localai.agent.core.AgentStep
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.core.ModelTurn
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.core.ToolCall
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.engine.PromptMessage
import com.iris.localai.ui.chat.ComposerAction
import com.iris.localai.ui.chat.ComposerActions
import com.iris.localai.ui.chat.QueuedMessage
import com.iris.localai.ui.chat.SendIntent
import com.iris.localai.ui.chat.SendQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.7 #2: a message sent while a reply / 智能体 run is going — interrupt or 补充. */
class SendWhileBusyTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    // ---------------------------------------------------------------- classifier

    @Test fun stopWordsInterrupt() {
        for (t in listOf("停", "停！", "停下", "停一下", "等等", "等等，我说错了", "等一下", "不对", "不对。", "不对，是明天", "不对啊",
            "算了", "算了吧", "取消", "别", "别！", "别说了", "别回答了", "别写了", "别继续", "stop", "Stop!", "  停。 ", "……等等")) {
            assertEquals(t, SendIntent.Kind.INTERRUPT, SendIntent.classify(t))
        }
    }

    @Test fun everythingElseIsASupplement() {
        for (t in listOf("别忘了带伞", "别人怎么看", "不对称加密是什么", "停车场在哪", "等等党永不为奴", "取消订阅要怎么弄",
            "还有，顺便查下上海", "另外记得用中文", "stopwatch 怎么用", "我觉得不对劲", "", "   ")) {
            assertEquals(t, SendIntent.Kind.SUPPLEMENT, SendIntent.classify(t))
        }
    }

    @Test fun agentNoteFormat() {
        assertEquals("用户补充：顺便查上海\n用户补充：用中文", SendIntent.agentNote(listOf(" 顺便查上海 ", "用中文")))
    }

    // ---------------------------------------------------------------- queue

    @Test fun queueIsFifoCancellableAndPerConversation() {
        val q = SendQueue()
        val a = QueuedMessage(convId = "c1", text = "一")
        val b = QueuedMessage(convId = "c1", text = "二")
        val other = QueuedMessage(convId = "c2", text = "别的对话")
        q.add(a); q.add(b); q.add(other)
        assertEquals(3, q.all.size)
        assertEquals(listOf(a, other), q.cancel(b.id))
        assertEquals(listOf(a), q.retainOnly("c1"))
        q.add(b)
        assertEquals(a, q.next("c1")); assertEquals(b, q.next("c1")); assertNull(q.next("c1"))
    }

    @Test fun agentNotesLeaveTheQueueOnceRead() {
        val q = SendQueue()
        q.add(QueuedMessage(convId = "c1", text = "补一句", forAgent = true))
        q.add(QueuedMessage(convId = "c1", text = "普通", forAgent = false))
        assertEquals(listOf("补一句"), q.takeAgentNotes("c1").map { it.text })
        assertTrue(q.takeAgentNotes("c1").isEmpty())
        // What the run never read is answered as its own turn afterwards.
        assertEquals("普通", q.next("c1")?.text)
        q.add(QueuedMessage(convId = "c1", text = "没来得及", forAgent = true))
        assertEquals("没来得及", q.next("c1")?.text)
        q.add(QueuedMessage(convId = "c1", text = "x")); assertTrue(q.clear().isEmpty()); assertTrue(q.all.isEmpty())
    }

    // ---------------------------------------------------------------- agent loop reads 补充 before its next step (fake model)

    private class FakeModel(vararg replies: String) : AgentModel {
        val queue = ArrayDeque(replies.toList())
        val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            seen += messages
            val r = queue.removeFirstOrNull() ?: "完成。"
            onText(r); return ModelTurn(r)
        }
    }

    private class Cb : AgentCallbacks {
        val before = ArrayList<String>()
        override suspend fun confirm(tool: AgentTool, call: ToolCall) = UserDecision.ALLOW_ONCE
        override fun onSteps(steps: List<AgentStep>) {}
        override suspend fun beforeTool(tool: AgentTool, call: ToolCall) { before += tool.name }
    }

    @Test fun supplementIsInjectedBeforeTheNextStep() = runTest {
        val dir = kotlin.io.path.createTempDirectory("sendq").toFile()
        val env = ToolEnv(WorkspaceSandbox(File(dir, "ws").apply { mkdirs() }), UndoStore(File(dir, "u")))
        val tool = AgentTools.all.first { it.name == "datetime" }
        val m = FakeModel("```tool\n{\"tool\": \"datetime\", \"args\": {}}\n```", "好，按上海来。")
        val q = SendQueue()
        val cb = Cb()
        var calls = 0
        val loop = AgentLoop(m, listOf(tool), { Permission.ALLOW }, env, cb, pendingNotes = {
            calls++
            // The user types a 补充 while the first step's tool runs.
            if (calls == 2) q.add(QueuedMessage(convId = "c", text = "顺便换成上海", forAgent = true))
            q.takeAgentNotes("c").takeIf { it.isNotEmpty() }?.let { n -> SendIntent.agentNote(n.map { it.text }) }
        })
        val o = loop.run(listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, "现在几点")))
        assertEquals("好，按上海来。", o.finalText)
        assertEquals(listOf("datetime"), cb.before)
        assertFalse(m.seen[0].any { it.content.contains("用户补充") })
        val second = m.seen[1]
        assertTrue(second.last().content.startsWith("<tool_response>"))
        assertTrue(second.last().content.endsWith("用户补充：顺便换成上海"))
        // Earlier messages (the cached prefix) are untouched.
        assertEquals(m.seen[0], second.subList(0, m.seen[0].size))
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- wiring

    @Test fun composerSendsWhileGeneratingWhenThereIsText() {
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = true, hasText = true))
        assertEquals(ComposerAction.STOP, ComposerActions.of(generating = true, hasText = false))
    }

}

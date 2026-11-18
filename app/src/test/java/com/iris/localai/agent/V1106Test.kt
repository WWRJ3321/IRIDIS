package com.iris.localai.agent

import com.iris.localai.agent.core.AgentCallbacks
import com.iris.localai.agent.core.AgentLoop
import com.iris.localai.agent.core.AgentModel
import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.core.AgentStep
import com.iris.localai.agent.core.AgentStop
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.EditFileTool
import com.iris.localai.agent.workspace.ExternalFiles
import com.iris.localai.agent.core.ModelTurn
import com.iris.localai.agent.core.PermissionPolicy
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.tools.ReadFileTool
import com.iris.localai.agent.RunCommandTool
import com.iris.localai.agent.ShellOutput
import com.iris.localai.agent.ShellRunner
import com.iris.localai.agent.core.ToolCall
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.tools.ToolRisk
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.engine.HistoryMatch
import com.iris.localai.engine.PromptMessage
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** 1.10.6: KV-cache-friendly agent history, SAF write fallback, run_command, access hint. */
class V1106Test {
    private lateinit var tmp: File
    private lateinit var sandbox: WorkspaceSandbox
    private lateinit var undo: UndoStore

    @Before fun setUp() {
        tmp = Files.createTempDirectory("iris1106").toFile()
        sandbox = WorkspaceSandbox(File(tmp, "ws"))
        undo = UndoStore(File(tmp, "undo"))
    }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun m(role: String, c: String) = PromptMessage(role, c)
    private fun obj(vararg p: Pair<String, String>) = buildJsonObject { p.forEach { put(it.first, JsonPrimitive(it.second)) } }

    // ---- history matching (engine KV reuse) ----

    @Test fun assistantPrefixAndThinkMatch() {
        assertTrue(HistoryMatch.assistantSame("<think>嗯</think>```tool\n{\"tool\":\"ls\"}\n```多余", "```tool\n{\"tool\":\"ls\"}\n```"))
        assertTrue(HistoryMatch.assistantSame("你好  世界", "你好 世界"))
        assertFalse(HistoryMatch.assistantSame("完全不同", "另一个回答"))
        assertFalse(HistoryMatch.assistantSame("", "x"))
    }

    @Test fun userAndSystemMustMatchExactly() {
        val a = listOf(m(PromptMessage.SYSTEM, "s"), m(PromptMessage.USER, "u"), m(PromptMessage.ASSISTANT, "回答全文"))
        assertTrue(HistoryMatch.same(a, listOf(m(PromptMessage.SYSTEM, "s"), m(PromptMessage.USER, "u"), m(PromptMessage.ASSISTANT, "回答"))))
        assertFalse(HistoryMatch.same(a, listOf(m(PromptMessage.SYSTEM, "s2"), m(PromptMessage.USER, "u"), m(PromptMessage.ASSISTANT, "回答全文"))))
        assertFalse(HistoryMatch.same(a, a.dropLast(1)))
        assertFalse(HistoryMatch.same(null, a))
    }

    // ---- agent loop keeps the model's own text and returns a transcript ----

    private class Fake(vararg r: String) : AgentModel {
        val q = ArrayDeque(r.toList()); val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            seen += messages; return ModelTurn(q.removeFirstOrNull() ?: "完成。")
        }
    }
    private object Cb : AgentCallbacks {
        override suspend fun confirm(tool: AgentTool, call: ToolCall) = UserDecision.ALLOW_ONCE
        override fun onSteps(steps: List<AgentStep>) {}
    }

    @Test fun eachStepExtendsThePreviousPromptAndTranscriptContinues() = runTest {
        File(sandbox.root, "a.txt").writeText("A")
        val callText = "我先看看。\n```tool\n{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}\n```"
        val f = Fake(callText, "内容是 A")
        val s = AgentSettings()
        val base = listOf(m(PromptMessage.SYSTEM, "sys"), m(PromptMessage.USER, "读 a"))
        val o = AgentLoop(f, AgentTools.all, { s.permission(it) }, ToolEnv(sandbox, undo), Cb).run(base)
        assertEquals(AgentStop.DONE, o.stop)
        // step 2's prompt = step 1's prompt + the model's own reply + the tool result
        val p1 = f.seen[0]; val p2 = f.seen[1]
        assertEquals(p1, p2.take(p1.size))
        assertEquals(callText.trim(), p2[p1.size].content)
        assertTrue(p2[p1.size + 1].content.startsWith("<tool_response>"))
        // transcript = last prompt + final answer
        assertEquals(p2 + m(PromptMessage.ASSISTANT, "内容是 A"), o.transcript)
    }

    @Test fun unknownToolHintIsClosed() {
        val h = AgentPrompt.unknownToolHint("system", setOf("read_file"))
        assertTrue(h.trimEnd().endsWith("</tool_response>"))
    }

    // ---- SAF write fallback ----

    private class Ext(val files: MutableMap<String, ByteArray>) : ExternalFiles {
        override fun list(relDir: String) = files.keys.map { ExternalFiles.Entry(it, false, files[it]!!.size.toLong()) }
        override fun read(relPath: String, maxBytes: Long) = files[relPath]
        override fun write(relPath: String, bytes: ByteArray): Boolean { files[relPath] = bytes; return true }
        override fun delete(relPath: String) = files.remove(relPath) != null
    }

    @Test fun editsAFileOnlyVisibleThroughTheGrant() {
        val ext = Ext(mutableMapOf("user.md" to "hello world".toByteArray()))
        val env = ToolEnv(sandbox, undo, external = ext)
        val r = EditFileTool.run(obj("path" to "user.md", "old" to "world", "new" to "IRIS"), env)
        assertTrue(r.output, r.ok)
        assertEquals("hello IRIS", String(ext.files["user.md"]!!))
        assertEquals(1, undo.count())
    }

    @Test fun notFoundCarriesTheAccessHint() {
        val env = ToolEnv(sandbox, undo, accessHint = "turn on 全部文件访问")
        val r = ReadFileTool.run(obj("path" to "missing.md"), env)
        assertFalse(r.ok)
        assertTrue(r.output.contains("全部文件访问"))
        assertEquals("文件不存在或无权访问", r.summary)
    }

    // ---- run_command ----

    @Test fun runCommandRefusesPathsOutsideTheWorkspace() {
        assertNull(RunCommandTool.refusal("ls -la"))
        assertNull(RunCommandTool.refusal("grep -n TODO notes/*.md | head -20"))
        assertNull(RunCommandTool.refusal("cat a.txt > /dev/null"))
        assertNotNull(RunCommandTool.refusal("cat ../secret"))
        assertNotNull(RunCommandTool.refusal("ls /data/data"))
        assertNotNull(RunCommandTool.refusal("cat ~/x"))
        assertNotNull(RunCommandTool.refusal("su -c id"))
        assertNotNull(RunCommandTool.refusal("  "))
    }

    @Test fun runCommandBlocksEveryWayOut() {
        for (ok in listOf("sed -i 's/a/b/' notes.md", "grep -rn content .", "find . -name '*.md' | wc -l", "mkdir -p a/b && mv x.txt a/b", "cp a.txt b.txt"))
            assertNull(ok, RunCommandTool.refusal(ok))
        for (bad in listOf("cd sub && ls", "ln -s x y", "echo \$HOME", "cat `echo a`", "sh -c ls", "find -L . -name x",
                "find . -exec cat {} +", "cp -s a b", "ls a/../..", "tar xf a.tar", "cat a\nls /", "/system/bin/ls", "xargs cat", "ls|sh"))
            assertNotNull(bad, RunCommandTool.refusal(bad))
    }

    @Test fun promptLogKeepsEveryCallAndFormats() {
        val log = com.iris.localai.util.diagnostics.PromptLog(maxReplies = 2)
        log.record("r1", listOf(m(PromptMessage.SYSTEM, "SYS"), m(PromptMessage.USER, "hi")))
        log.record("r1", listOf(m(PromptMessage.SYSTEM, "SYS"), m(PromptMessage.USER, "hi"), m(PromptMessage.ASSISTANT, "call"), m(PromptMessage.USER, "<tool_response>")))
        log.record("r2", listOf(m(PromptMessage.USER, "x")))
        log.record("r3", listOf(m(PromptMessage.USER, "y")))
        assertTrue(log.get("r1").isEmpty()) // oldest dropped
        val r3 = com.iris.localai.util.diagnostics.PromptLog.format(log.get("r3"))
        assertTrue(r3.contains("第 1 次调用") && r3.contains("用户 user") && r3.contains("y"))
        val two = com.iris.localai.util.diagnostics.PromptLog(); two.record("a", listOf(m(PromptMessage.SYSTEM, "SYS"))); two.record("a", listOf(m(PromptMessage.SYSTEM, "SYS2")))
        val f = com.iris.localai.util.diagnostics.PromptLog.format(two.get("a"))
        assertTrue(f.contains("共 2 次") && f.contains("SYS2") && f.contains("系统 system"))
        assertTrue(com.iris.localai.util.diagnostics.PromptLog.format(emptyList()).contains("没有记录"))
    }

    @Test fun runCommandUsesTheWorkspaceAsCwdAndReportsExit() {
        var cwd: File? = null
        val shell = ShellRunner { _, dir, _, _ -> cwd = dir; ShellOutput(1, "oops", false, false) }
        val r = RunCommandTool.run(obj("command" to "ls"), ToolEnv(sandbox, undo, shell = shell))
        assertEquals(sandbox.root, cwd)
        assertFalse(r.ok)
        assertTrue(r.output.contains("[exit 1]"))
    }

    @Test fun runCommandAsksByDefault() {
        assertEquals(ToolRisk.DELETE, RunCommandTool.risk)
        assertEquals(Permission.ASK, PermissionPolicy.defaultFor(RunCommandTool.risk))
        assertTrue(AgentTools.all.contains(RunCommandTool))
    }

    @Test fun promptWithEveryToolStaysCompact() {
        val p = AgentPrompt.build(AgentTools.all)
        assertTrue("prompt ~${TokenEstimator.estimate(p)} tokens", TokenEstimator.estimate(p) <= 800)
    }
}

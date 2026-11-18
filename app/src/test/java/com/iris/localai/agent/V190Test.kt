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
import com.iris.localai.agent.core.Args
import com.iris.localai.agent.CalcException
import com.iris.localai.agent.Calculator
import com.iris.localai.agent.core.CallFormat
import com.iris.localai.agent.DateTimeTool
import com.iris.localai.agent.DeleteFileTool
import com.iris.localai.agent.EditFileTool
import com.iris.localai.agent.EditPatch
import com.iris.localai.agent.tools.FetchResult
import com.iris.localai.agent.tools.HttpWebFetcher
import com.iris.localai.agent.ListDirTool
import com.iris.localai.agent.core.ModelTurn
import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.core.PermissionPolicy
import com.iris.localai.agent.tools.ReadFileTool
import com.iris.localai.agent.workspace.SandboxException
import com.iris.localai.agent.tools.SearchFilesTool
import com.iris.localai.agent.core.StepStatus
import com.iris.localai.agent.core.ToolCall
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.tools.ToolRisk
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.tools.WebFetchTool
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.agent.tools.WriteFileTool
import com.iris.localai.agent.tools.capTokens
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.Role
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendOption
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.load.SlowLoadHint
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/** 1.9.0: local Agent core (parser, sandbox, permissions, tools, loop) + the Agents-A1 CPU fix. */
class V190Test {

    private lateinit var tmp: File
    private lateinit var sandbox: WorkspaceSandbox
    private lateinit var undo: UndoStore
    private lateinit var env: ToolEnv

    @Before fun setUp() {
        tmp = Files.createTempDirectory("iris190").toFile()
        sandbox = WorkspaceSandbox(File(tmp, "workspace"))
        undo = UndoStore(File(tmp, "agent-undo"))
        env = ToolEnv(sandbox, undo, clock = { 1_760_528_280_000L }, zone = { ZoneId.of("Asia/Hong_Kong") })
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun call(text: String): ToolCall = (ToolCallParser.parse(text) as? ParsedReply.Call)?.call
        ?: throw AssertionError("not a call: ${ToolCallParser.parse(text)}")

    private fun obj(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

    // ================================================================ 1. bug fix: Agents-A1 → CPU

    private val liteRtOpts = listOf(
        BackendOption(Backend.CPU, true), BackendOption(Backend.GPU, true), BackendOption(Backend.NPU, false, "x"),
    )

    @Test fun agentsA1PrefersCpuInAutoMode() {
        for (n in listOf("Agents-A1-4B_mixed_int4.litertlm", "agents_a1-4b.litertlm", "AGENTS-A1-1.7B_q8.litertlm", "my-Agents_A1.litertlm")) {
            assertTrue(n, BackendCapabilities.prefersCpu(n))
            assertEquals(n, Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, n, liteRtOpts))
            assertEquals(n, Backend.CPU, BackendCapabilities.preferred(null, ModelFormat.LITERT, n, liteRtOpts))
        }
        // unchanged: Qwen3.5 → CPU, Gemma → GPU, explicit choice wins
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, "Qwen3.5-2B.litertlm", liteRtOpts))
        assertEquals(Backend.GPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, "gemma-4-E2B-it.litertlm", liteRtOpts))
        assertFalse(BackendCapabilities.prefersCpu("agents-b1.litertlm"))
        assertEquals(Backend.GPU, BackendCapabilities.preferred(Backend.GPU, ModelFormat.LITERT, "Agents-A1-4B.litertlm", liteRtOpts))
    }


    // ================================================================ 2. parser

    @Test fun parsesTheTaughtFencedFormat() {
        val r = ToolCallParser.parse("我先看看目录。\n```tool\n{\"tool\": \"list_dir\", \"args\": {\"path\": \"notes\"}}\n```")
        r as ParsedReply.Call
        assertEquals("list_dir", r.call.name)
        assertEquals("notes", r.call.args["path"]!!.jsonPrimitive.content)
        assertEquals(CallFormat.FENCED, r.call.format)
        assertEquals("我先看看目录。", r.before)
        // ```tool_call and ```json naming a tool are accepted too
        assertEquals("datetime", call("```tool_call\n{\"tool\":\"datetime\",\"args\":{}}\n```").name)
        assertEquals("calculator", call("```json\n{\"tool\":\"calculator\",\"args\":{\"expression\":\"1+1\"}}\n```").name)
    }

    @Test fun parsesQwenToolCallTags() {
        val c = call("<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.txt\", \"limit\": 5}}\n</tool_call>")
        assertEquals("read_file", c.name)
        assertEquals(CallFormat.QWEN_TAG, c.format)
        assertEquals(5, Args.int(c.args, "limit"))
        // arguments as a JSON string (OpenAI style) and an unclosed tag at the end of the reply
        val s = call("<tool_call>{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\": \\\"b.md\\\"}\"}")
        assertEquals("b.md", Args.str(s.args, "path"))
        val fn = call("<tool_call>{\"function\": {\"name\": \"datetime\", \"arguments\": {}}}</tool_call>")
        assertEquals("datetime", fn.name)
    }

    @Test fun parsesPlainJsonAndFlattenedArgs() {
        val c = call("{\"tool\": \"calculator\", \"args\": {\"expression\": \"2*3\"}}")
        assertEquals(CallFormat.PLAIN_JSON, c.format)
        val flat = call("{\"tool\": \"read_file\", \"path\": \"x.txt\"}")
        assertEquals("x.txt", Args.str(flat.args, "path"))
        assertFalse(flat.args.containsKey("tool"))
        val after = ToolCallParser.parse("好的。\n{\"name\": \"list_dir\", \"arguments\": {}}")
        assertTrue(after is ParsedReply.Call)
    }

    @Test fun repairsWhatSmallModelsActuallyWrite() {
        // raw newlines inside the content string
        val w = call("```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"a.md\", \"content\": \"# 标题\n第一行\n\t缩进\"}}\n```")
        assertEquals("# 标题\n第一行\n\t缩进", Args.str(w.args, "content"))
        // single quotes, trailing comma, Python booleans
        val p = call("```tool\n{'tool': 'edit_file', 'args': {'path': 'a.md', 'old': 'x', 'new': \"it's\", 'all': True,},}\n```")
        assertEquals("edit_file", p.name)
        assertEquals("it's", Args.str(p.args, "new"))
        assertEquals(true, Args.bool(p.args, "all"))
        // cut off by the token limit: no closing fence, braces or quote
        val cut = call("```tool\n{\"tool\": \"write_file\", \"args\": {\"path\": \"b.txt\", \"content\": \"hello")
        assertEquals("hello", Args.str(cut.args, "content"))
    }

    @Test fun malformedCallsAreReportedNotRun() {
        assertTrue(ToolCallParser.parse("```tool\nlist the files please\n```") is ParsedReply.Malformed)
        assertTrue(ToolCallParser.parse("<tool_call>read_file a.txt</tool_call>") is ParsedReply.Malformed)
        assertTrue(ToolCallParser.parse("```tool\n{\"args\": {\"path\": \"a\"}}\n```") is ParsedReply.Malformed) // no tool name
        assertTrue(ToolCallParser.parse("```tool\n{\"tool\": \"x\", \"args\": [1, 2]}\n```") is ParsedReply.Malformed)
        assertTrue(ToolCallParser.parse("{\"tool\": read_file(a.txt)") is ParsedReply.Malformed)
    }

    @Test fun ordinaryAnswersStayAnswers() {
        assertTrue(ToolCallParser.parse("你好！今天想做什么？") is ParsedReply.Final)
        assertTrue(ToolCallParser.parse("示例：\n```json\n{\"name\": \"张三\", \"age\": 3}\n```") is ParsedReply.Final)
        assertTrue(ToolCallParser.parse("```kotlin\nfun main() { println(\"{}\") }\n```\n完成") is ParsedReply.Final)
        assertTrue(ToolCallParser.parse("集合 {1, 2, 3} 的大小是 3") is ParsedReply.Final)
        // a call written inside the reasoning is not executed
        assertTrue(ToolCallParser.parse("<think>```tool\n{\"tool\":\"delete_file\",\"args\":{\"path\":\"a\"}}\n```</think>不需要工具。") is ParsedReply.Final)
        // a code block before the real call does not hide it
        val c = ToolCallParser.parse("```python\nprint(1)\n```\n```tool\n{\"tool\":\"datetime\",\"args\":{}}\n```")
        assertTrue(c is ParsedReply.Call)
    }

    @Test fun streamingStopsAtTheEndOfACallAndHidesTheBlock() {
        val head = "我来读取。\n```tool\n{\"tool\": \"read_file\", \"args\": {\"path\": \"a\"}}"
        assertNull(ToolCallParser.callEnd(head))
        val full = "$head\n```"
        assertEquals(full.length, ToolCallParser.callEnd("$full\n<tool_response>假的结果"))
        assertEquals(full.length, ToolCallParser.callEnd(full))
        val q = "<tool_call>{\"name\":\"x\",\"arguments\":{}}</tool_call>"
        assertEquals(q.length, ToolCallParser.callEnd(q + "more"))
        assertEquals(5, ToolCallParser.callEnd("回答完毕。<tool_response>"))
        assertNull(ToolCallParser.callEnd("普通回答 ```kotlin\nval a = 1\n```"))
        assertEquals("我来读取。", ToolCallParser.visibleText(head))
        assertEquals("好", ToolCallParser.visibleText("好\n```too"))
        assertEquals("好", ToolCallParser.visibleText("好<tool_c"))
        assertEquals("<think>想想</think>好", ToolCallParser.visibleText("<think>想想</think>好<tool_call>{\"name\""))
    }

    @Test fun canonicalFormRoundTrips() {
        val c = call("<tool_call>{\"name\":\"edit_file\",\"arguments\":{\"path\":\"a\",\"old\":\"x\\ny\",\"new\":\"z\"}}</tool_call>")
        val again = call(c.canonical())
        assertEquals(c.name, again.name)
        assertEquals(c.args, again.args)
        assertEquals(CallFormat.FENCED, again.format)
    }

    // ================================================================ sandbox

    @Test fun sandboxResolvesInsideTheWorkspace() {
        assertEquals(sandbox.root, sandbox.resolve("."))
        assertEquals(sandbox.root, sandbox.resolve(""))
        assertEquals(File(sandbox.root, "a/b.txt"), sandbox.resolve("a/b.txt"))
        assertEquals(File(sandbox.root, "a/b.txt"), sandbox.resolve("./a//b.txt"))
        assertEquals(File(sandbox.root, "x.md"), sandbox.resolve(File(sandbox.root, "x.md").path)) // absolute but inside
        assertEquals("a/b.txt", sandbox.relative(File(sandbox.root, "a/b.txt")))
    }

    @Test fun sandboxRejectsEscapes() {
        for (bad in listOf("../secret", "a/../../x", "a/../b", "..", "/etc/passwd", "/data/data/other/x", "~/x", "..\\x", "a\u0000b", tmp.path + "/agent-undo/1.bin")) {
            try { sandbox.resolve(bad); fail("accepted $bad") } catch (_: SandboxException) {}
        }
    }

    @Test fun sandboxRejectsSymlinkEscapes() {
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("s")
        Files.createSymbolicLink(File(sandbox.root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(sandbox.root, "file-link.txt").toPath(), File(outside, "secret.txt").toPath())
        for (bad in listOf("link", "link/secret.txt", "link/new.txt", "file-link.txt")) {
            try { sandbox.resolve(bad); fail("accepted $bad") } catch (_: SandboxException) {}
        }
        try { ReadFileTool.run(obj("path" to "link/secret.txt"), env); fail() } catch (_: SandboxException) {}
        try { WriteFileTool.run(obj("path" to "file-link.txt", "content" to "x"), env); fail() } catch (_: SandboxException) {}
        assertEquals("s", File(outside, "secret.txt").readText())
        // search never follows the link out of the workspace
        val r = SearchFilesTool.run(obj("query" to "s", "mode" to "content"), env)
        assertFalse(r.output.contains("secret.txt:"))
    }

    // ================================================================ undo

    @Test fun everyChangeCanBeUndone() {
        WriteFileTool.run(obj("path" to "n/a.txt", "content" to "v1"), env)
        WriteFileTool.run(obj("path" to "n/a.txt", "content" to "v2"), env)
        EditFileTool.run(obj("path" to "n/a.txt", "old" to "v2", "new" to "v3"), env)
        DeleteFileTool.run(obj("path" to "n/a.txt"), env)
        val f = File(sandbox.root, "n/a.txt")
        assertFalse(f.exists())
        assertEquals(4, undo.count())
        assertEquals("删除", undo.peek()!!.action)
        assertTrue(undo.undoLast(sandbox)!!.contains("恢复")); assertEquals("v3", f.readText())
        undo.undoLast(sandbox); assertEquals("v2", f.readText())
        undo.undoLast(sandbox); assertEquals("v1", f.readText())
        assertTrue(undo.undoLast(sandbox)!!.contains("删除新建")); assertFalse(f.exists())
        assertNull(undo.undoLast(sandbox))
    }

    @Test fun undoKeepsOnlyTheNewestSteps() {
        val small = UndoStore(File(tmp, "u2"), max = 3)
        val e = ToolEnv(sandbox, small)
        repeat(6) { i -> WriteFileTool.run(obj("path" to "c.txt", "content" to "$i"), e) }
        assertEquals(3, small.count())
        small.undoLast(sandbox)
        assertEquals("4", File(sandbox.root, "c.txt").readText())
    }

    // ================================================================ permissions

    @Test fun permissionDefaultsAndOverrides() {
        val s = AgentSettings()
        assertFalse("对话 stays the default", s.agentMode)
        assertEquals(8, s.maxSteps)
        for (t in AgentTools.all) {
            val want = t.defaultPermission ?: when (t.risk) { ToolRisk.SAFE, ToolRisk.READ -> Permission.ALLOW; else -> Permission.ASK }
            assertEquals(t.name, want, s.permission(t))
        }
        assertEquals(Permission.ALLOW, s.permission(ReadFileTool))
        assertEquals(Permission.ASK, s.permission(WriteFileTool))
        assertEquals(Permission.ASK, s.permission(EditFileTool))
        assertEquals(Permission.ASK, s.permission(DeleteFileTool))
        assertEquals(Permission.ASK, s.permission(WebFetchTool))
        assertTrue("1.12.0: web_fetch on by default", s.isEnabled(WebFetchTool))
        assertTrue(s.isEnabled(ReadFileTool))
        val o = s.copy(permissions = mapOf("read_file" to "DENY", "write_file" to "allow"))
        assertEquals(Permission.DENY, o.permission(ReadFileTool))
        assertEquals(Permission.ALLOW, o.permission(WriteFileTool))
    }

    @Test fun decisionsAndSanitising() {
        val s = AgentSettings()
        assertEquals(s, PermissionPolicy.afterDecision(s, WriteFileTool, UserDecision.ALLOW_ONCE))
        assertEquals(s, PermissionPolicy.afterDecision(s, WriteFileTool, UserDecision.DENY))
        assertEquals(Permission.ALLOW, PermissionPolicy.afterDecision(s, WriteFileTool, UserDecision.ALWAYS_ALLOW).permission(WriteFileTool))
        assertTrue(PermissionPolicy.mayRun(Permission.ALLOW, null))
        assertFalse(PermissionPolicy.mayRun(Permission.DENY, UserDecision.ALWAYS_ALLOW))
        assertFalse(PermissionPolicy.mayRun(Permission.ASK, null))
        assertFalse(PermissionPolicy.mayRun(Permission.ASK, UserDecision.DENY))
        assertTrue(PermissionPolicy.mayRun(Permission.ASK, UserDecision.ALLOW_ONCE))
        val bad = AgentSettings(maxSteps = 99, enabled = mapOf("rm_rf" to true), permissions = mapOf("read_file" to "maybe", "bash" to "ALLOW")).sanitized()
        assertEquals(AgentSettings.MAX_STEPS, bad.maxSteps)
        assertTrue(bad.enabled.isEmpty()); assertTrue(bad.permissions.isEmpty())
        assertEquals(1, AgentSettings(maxSteps = 0).sanitized().maxSteps)
        // stored apart from AppSettings, unknown keys tolerated
        val j = Json { ignoreUnknownKeys = true }
        assertEquals(AgentSettings(agentMode = true), j.decodeFromString(AgentSettings.serializer(), "{\"agentMode\":true,\"future\":1}"))
    }

    // ================================================================ edit_file patch

    @Test fun editPatchExactUniqueAndAll() {
        val t = "a = 1\nb = 2\na = 1\n"
        assertTrue((EditPatch.apply(t, "a = 1", "a = 9") as EditPatch.Result.Err).message.contains("2 次"))
        val all = EditPatch.apply(t, "a = 1", "a = 9", all = true) as EditPatch.Result.Ok
        assertEquals("a = 9\nb = 2\na = 9\n", all.text); assertEquals(2, all.replacements)
        val one = EditPatch.apply(t, "b = 2", "b = 3") as EditPatch.Result.Ok
        assertEquals("a = 1\nb = 3\na = 1\n", one.text); assertFalse(one.fuzzy)
        assertTrue(EditPatch.apply(t, "zzz", "y") is EditPatch.Result.Err)
        assertTrue(EditPatch.apply(t, "", "y") is EditPatch.Result.Err)
        assertTrue(EditPatch.apply(t, "b = 2", "b = 2") is EditPatch.Result.Err)
        assertEquals("a = 1\na = 1\n", (EditPatch.apply(t, "b = 2\n", "") as EditPatch.Result.Ok).text)
    }

    @Test fun editPatchToleratesLineEndingsAndIndentation() {
        val crlf = "one\r\ntwo\r\nthree\r\n"
        val r = EditPatch.apply(crlf, "one\ntwo", "1\n2") as EditPatch.Result.Ok
        assertEquals("1\r\n2\r\nthree\r\n", r.text)
        val code = "fun f() {\n    if (x) {\n        call()\n    }\n}\n"
        val fz = EditPatch.apply(code, "if (x) {\ncall()\n}", "if (y) {\n    other()\n}") as EditPatch.Result.Ok
        assertTrue(fz.fuzzy)
        assertEquals("fun f() {\n    if (y) {\n        other()\n    }\n}\n", fz.text)
    }

    @Test fun editFileToolUsesThePatchAndReportsErrors() {
        File(sandbox.root, "e.txt").writeText("hello world")
        val ok = EditFileTool.run(obj("path" to "e.txt", "old_string" to "world", "new_string" to "IRIS"), env)
        assertTrue(ok.ok); assertEquals("hello IRIS", File(sandbox.root, "e.txt").readText())
        val miss = EditFileTool.run(obj("path" to "e.txt", "old" to "nope", "new" to "x"), env)
        assertFalse(miss.ok); assertTrue(miss.output.contains("read_file"))
        assertEquals(1, undo.count()) // a failed edit takes no snapshot
    }

    // ================================================================ calculator

    @Test fun calculatorEvaluatesSafely() {
        fun v(s: String) = Calculator.evaluate(s)
        assertEquals(14.0, v("2 + 3 * 4"), 1e-12)
        assertEquals(20.0, v("(2 + 3) * 4"), 1e-12)
        assertEquals(512.0, v("2^3^2"), 1e-12)          // right-associative
        assertEquals(-4.0, v("-2^2"), 1e-12)
        assertEquals(0.5, v("2^-1"), 1e-12)
        assertEquals(8.0, v("2**3"), 1e-12)
        assertEquals(6.0, v("12 ÷ 4 × 2"), 1e-12)
        assertEquals(1.0, v("10 % 3"), 1e-12)
        assertEquals(3.0, v("sqrt(9)"), 1e-12)
        assertEquals(1.0, v("sin(pi/2)"), 1e-12)
        assertEquals(2.0, v("log(100)"), 1e-12)
        assertEquals(3.0, v("log(8, 2)"), 1e-12)
        assertEquals(1.0, v("ln(e)"), 1e-12)
        assertEquals(3.14, v("round(pi, 2)"), 1e-12)
        assertEquals(7.0, v("max(1, 7, 3)"), 1e-12)
        assertEquals(1500.0, v("1.5e3"), 1e-12)
        assertEquals(1000000.0, v("1_000_000"), 1e-12)
        for (bad in listOf("1/0", "sqrt(-1)", "foo(1)", "java.lang.System.exit(0)", "Runtime.getRuntime()", "2 +", "(1+2", "1 2", "", "\"a\"", "x = 1")) {
            try { v(bad); fail("accepted $bad") } catch (_: CalcException) {}
        }
        assertEquals("0.3", Calculator.format(v("0.1 + 0.2")))
        assertEquals("42", Calculator.format(42.0))
        assertEquals("= 8", com.iris.localai.agent.CalculatorTool.run(obj("expression" to "2^3"), env).summary)
        assertFalse(com.iris.localai.agent.CalculatorTool.run(obj("expression" to "1/0"), env).ok)
    }

    // ================================================================ other tools


    @Test fun webFetchIsOffByDefaultAndStripsHtml() {
        assertTrue(WebFetchTool.defaultEnabled)
        val fake = ToolEnv(sandbox, undo, fetcher = { url ->
            FetchResult(200, "text/html; charset=utf-8", "<html><head><title>t</title><script>evil()</script></head><body><h1>标题</h1><p>正文 &amp; 更多</p></body></html>", url)
        })
        val r = WebFetchTool.run(obj("url" to "https://example.com"), fake)
        assertTrue(r.ok); assertTrue(r.output.contains("标题")); assertTrue(r.output.contains("正文 & 更多")); assertFalse(r.output.contains("evil"))
        assertFalse(WebFetchTool.run(obj("url" to "https://x"), ToolEnv(sandbox, undo, fetcher = { FetchResult(404, null, "", it) })).ok)
        for (h in listOf("localhost", "127.0.0.1", "10.0.0.2", "192.168.1.1", "169.254.1.1", "::1", "0.0.0.0")) {
            try { HttpWebFetcher.checkPublicHost(h); fail("allowed $h") } catch (_: SecurityException) {}
        }
    }

    @Test fun toolOutputIsCappedForSmallContexts() {
        val long = "字".repeat(5000)
        val capped = capTokens(long, 900)
        assertTrue(TokenEstimator.estimate(capped) <= 960)
        assertTrue(capped.contains("TRUNCATED"))
        assertEquals("short", capTokens("short", 900))
    }

    // ================================================================ prompt

    @Test fun systemPromptIsCompactAndListsOnlyEnabledTools() {
        val all = AgentPrompt.build(AgentTools.all)
        assertTrue("prompt ≈ ${TokenEstimator.estimate(all)} tokens", TokenEstimator.estimate(all) <= 800)
        val defaults = AgentTools.all.filter { AgentSettings().isEnabled(it) }
        val p = AgentPrompt.build(defaults, "回答尽量简短")
        assertFalse(p.contains("browser("))
        assertTrue(p.contains("read_file(")); assertTrue(p.contains("```tool")); assertTrue(p.endsWith("回答尽量简短"))
        assertTrue(p.contains("Chinese"))
    }

    // ================================================================ agent loop (fake model)

    private class FakeModel(vararg replies: String) : AgentModel {
        val queue = ArrayDeque(replies.toList())
        val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            seen += messages
            val r = queue.removeFirstOrNull() ?: "完成。"
            onText(r)
            return ModelTurn(r)
        }
    }

    private open class Recorder(val decision: UserDecision = UserDecision.ALLOW_ONCE) : AgentCallbacks {
        var steps: List<AgentStep> = emptyList()
        val asked = ArrayList<String>()
        var always = 0
        var changed = 0
        override suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision { asked += tool.name; return decision }
        override fun onSteps(steps: List<AgentStep>) { this.steps = steps }
        override fun onAlwaysAllow(tool: AgentTool) { always++ }
        override fun onWorkspaceChanged() { changed++ }
    }

    private fun fence(tool: String, args: String) = "```tool\n{\"tool\": \"$tool\", \"args\": $args}\n```"
    private val base = listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, "整理笔记"))

    private fun loop(model: AgentModel, cb: AgentCallbacks, settings: AgentSettings = AgentSettings(), maxSteps: Int = 8, budget: Int = Int.MAX_VALUE) =
        AgentLoop(model, AgentTools.all.filter { settings.isEnabled(it) }, { settings.permission(it) }, env, cb, maxSteps, budget)

    @Test fun loopRunsToolsAndFeedsResultsBack() = runTest {
        File(sandbox.root, "a.txt").writeText("内容A")
        val m = FakeModel(fence("list_dir", "{}"), "<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>", "文件里写着：内容A")
        val cb = Recorder()
        val o = loop(m, cb).run(base)
        assertEquals(AgentStop.DONE, o.stop)
        assertEquals("文件里写着：内容A", o.finalText)
        assertEquals(listOf("list_dir", "read_file"), o.steps.map { it.tool })
        assertTrue(o.steps.all { it.status == StepStatus.OK })
        assertTrue(cb.asked.isEmpty()) // reads are allowed without asking
        val last = m.seen.last()
        assertEquals(base.size + 4, last.size)
        assertTrue(last[base.size + 1].content.startsWith("<tool_response>"))
        assertTrue(last.last().content.contains("内容A"))
        // 1.10.6: history carries the model's own call text (keeps the engine's KV cache valid)
        assertTrue(last[base.size + 2].content.startsWith("<tool_call>"))
    }

    @Test fun askPermissionAllowOnceAlwaysAndDeny() = runTest {
        val write = fence("write_file", "{\"path\": \"w.txt\", \"content\": \"x\"}")
        val denied = Recorder(UserDecision.DENY)
        val o1 = loop(FakeModel(write, "好的，没有写入。"), denied).run(base)
        assertEquals(listOf("write_file"), denied.asked)
        assertEquals(StepStatus.DENIED, o1.steps.single().status)
        assertFalse(File(sandbox.root, "w.txt").exists())

        val once = Recorder(UserDecision.ALLOW_ONCE)
        val o2 = loop(FakeModel(write, "已写入。"), once).run(base)
        assertEquals(StepStatus.OK, o2.steps.single().status)
        assertEquals("x", File(sandbox.root, "w.txt").readText())
        assertEquals(0, once.always); assertEquals(1, once.changed)

        val always = Recorder(UserDecision.ALWAYS_ALLOW)
        loop(FakeModel(write, "已写入。"), always).run(base)
        assertEquals(1, always.always)

        // DENY in the settings: never asks, never runs
        val cfg = AgentSettings(permissions = mapOf("write_file" to "DENY"))
        val cb = Recorder()
        val o3 = loop(FakeModel(fence("write_file", "{\"path\": \"z.txt\", \"content\": \"x\"}"), "无法写入。"), cb, cfg).run(base)
        assertTrue(cb.asked.isEmpty())
        assertEquals(StepStatus.DENIED, o3.steps.single().status)
        assertFalse(File(sandbox.root, "z.txt").exists())
    }

    @Test fun malformedCallIsRetriedOnceThenGivesUp() = runTest {
        val m = FakeModel("```tool\nread a.txt\n```", fence("datetime", "{}"), "现在是晚上。")
        val o = loop(m, Recorder()).run(base)
        assertEquals(AgentStop.DONE, o.stop)
        assertEquals(listOf("datetime"), o.steps.map { it.tool }) // 1.10.8: first retry is silent
        assertTrue(m.seen[1].last().content.contains("could not be read"))
        val twice = loop(FakeModel("```tool\nnope\n```", "```tool\nstill nope\n```"), Recorder()).run(base)
        assertEquals(AgentStop.ERROR, twice.stop)
        assertNotNull(twice.error)
    }

    @Test fun unknownDisabledAndFailingToolsAreErrorsNotCrashes() = runTest {
        val m = FakeModel(fence("bash", "{\"cmd\": \"rm -rf /\"}"), fence("web_fetch", "{\"url\": \"https://x\"}"), fence("read_file", "{\"path\": \"../etc/passwd\"}"), "结束")
        val o = loop(m, Recorder()).run(base)
        assertEquals(AgentStop.DONE, o.stop)
        // 1.10.4: the unknown "bash" call gets a silent retry hint instead of a step card.
        assertTrue(m.seen[1].last().content.contains("no tool \"bash\""))
        assertEquals(listOf("web_fetch", "read_file"), o.steps.map { it.tool })
        assertEquals(StepStatus.ERROR, o.steps[1].status)
        assertTrue(o.steps[1].detail.contains(".."))
    }

    @Test fun stepLimitStopsTheLoopWithASummaryTurn() = runTest {
        val calls = Array(4) { fence("datetime", "{}") }
        val m = FakeModel(*calls, "已完成 3 步，剩下的请稍后再试。")
        val o = loop(m, Recorder(), maxSteps = 3).run(base)
        assertEquals(AgentStop.MAX_STEPS, o.stop)
        assertEquals(3, o.steps.size)
        assertEquals(AgentPrompt.STEP_LIMIT, m.seen.last().last().content)
        assertTrue(o.finalText.contains("3 步"))
    }

    @Test fun cancellationStopsTheLoop() = runTest {
        val gate = CompletableDeferred<Unit>()
        val hanging = object : AgentModel {
            override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
                gate.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { loop(hanging, Recorder()).run(base) }
        gate.await(); job.cancel(); job.join()
        assertTrue(job.isCancelled)
        // a pending confirmation is cancelled with the run, and nothing is written
        val waiting = CompletableDeferred<Unit>()
        val cb = object : Recorder() {
            override suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision { waiting.complete(Unit); awaitCancellation() }
        }
        val job2 = launch { loop(FakeModel(fence("write_file", "{\"path\": \"c.txt\", \"content\": \"x\"}")), cb).run(base) }
        waiting.await(); job2.cancel(); job2.join()
        assertTrue(job2.isCancelled)
        assertFalse(File(sandbox.root, "c.txt").exists())
        assertEquals(StepStatus.ASKING, cb.steps.single().status)
    }

    @Test fun oldToolOutputIsElidedWhenTheContextFills() = runTest {
        File(sandbox.root, "big.txt").writeText("长".repeat(800))
        val read = fence("read_file", "{\"path\": \"big.txt\"}")
        val m = FakeModel(read, read, read, "完")
        loop(m, Recorder(), budget = 1500).run(base)
        val last = m.seen.last()
        assertTrue(last.sumOf { TokenEstimator.message(it.content) } <= 1500)
        assertTrue(last.any { it.content == AgentPrompt.ELIDED })
        assertTrue(last.last().content.contains("长长长")) // the newest result is kept
        assertEquals("整理笔记", last[1].content) // the request is never dropped
    }

    // ================================================================ persistence + UI texts

    @Test fun messagesWithStepsStayCompatible() {
        val j = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val old = j.decodeFromString(ChatMessage.serializer(), "{\"id\":\"1\",\"role\":\"ASSISTANT\",\"content\":\"hi\"}")
        assertTrue(old.agentSteps.isEmpty())
        val m = ChatMessage(role = Role.ASSISTANT, content = "ok", agentSteps = listOf(AgentStep(tool = "read_file", args = "path=a", status = StepStatus.OK)))
        val back = j.decodeFromString(ChatMessage.serializer(), j.encodeToString(ChatMessage.serializer(), m))
        assertEquals(m.agentSteps, back.agentSteps)
        assertFalse(j.encodeToString(ChatMessage.serializer(), old).contains("agentSteps"))
    }


    private fun source(path: String): String =
        listOf(path, "app/$path").map { java.io.File(it) }.first { it.exists() }.readText()
}

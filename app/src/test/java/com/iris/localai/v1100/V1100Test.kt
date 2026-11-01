package com.iris.localai.v1100

import com.iris.localai.agent.AgentCallbacks
import com.iris.localai.agent.AgentLoop
import com.iris.localai.agent.AgentModel
import com.iris.localai.agent.AgentPrompt
import com.iris.localai.agent.AgentSettings
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.AgentStop
import com.iris.localai.agent.AgentTool
import com.iris.localai.agent.AgentTools
import com.iris.localai.agent.HistorySearch
import com.iris.localai.agent.MemoryTool
import com.iris.localai.agent.ModelTurn
import com.iris.localai.agent.Permission
import com.iris.localai.agent.RecallHit
import com.iris.localai.agent.RecallTool
import com.iris.localai.agent.StepStatus
import com.iris.localai.agent.ToolCall
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.ToolRisk
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.UserDecision
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.agent.memoryPreview
import com.iris.localai.context.CompactMsg
import com.iris.localai.context.CompactPolicy
import com.iris.localai.context.CompactState
import com.iris.localai.context.Compactor
import com.iris.localai.context.ContextAssembly
import com.iris.localai.context.ToolRollup
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.Conversation
import com.iris.localai.data.Role
import com.iris.localai.engine.PromptMessage
import com.iris.localai.memory.Bm25
import com.iris.localai.memory.FtsText
import com.iris.localai.memory.HistoryRecord
import com.iris.localai.memory.HistoryRecords
import com.iris.localai.memory.HistorySearchAdapter
import com.iris.localai.memory.HistoryStore
import com.iris.localai.memory.MemoryDocs
import com.iris.localai.memory.MemoryEdit
import com.iris.localai.memory.MemoryOps
import com.iris.localai.memory.MemoryPrompt
import com.iris.localai.memory.MemorySettings
import com.iris.localai.memory.MemoryTarget
import com.iris.localai.memory.SqlDriver
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId

/** What a 1.9.0 reader keeps of a conversation (unknown keys ignored). */
@kotlinx.serialization.Serializable
data class OldConv(val id: String, val messages: List<JsonObject>)

/** sqlite-jdbc behind the app's [SqlDriver] (host SQLite with FTS5 and FTS4). */
class JdbcSqlDriver(val conn: Connection) : SqlDriver {
    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) =
        args.forEachIndexed { i, a -> if (a == null) st.setNull(i + 1, java.sql.Types.NULL) else st.setObject(i + 1, a) }

    override fun exec(sql: String, args: List<Any?>) { conn.prepareStatement(sql).use { bind(it, args); it.execute() } }

    override fun query(sql: String, args: List<Any?>): List<Array<Any?>> = conn.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            val out = ArrayList<Array<Any?>>()
            while (rs.next()) out += Array(n) { i ->
                when (val v = rs.getObject(i + 1)) { is Int -> v.toLong(); is Float -> v.toDouble(); else -> v }
            }
            out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val auto = conn.autoCommit
        conn.autoCommit = false
        try { val r = block(); conn.commit(); return r } catch (t: Throwable) { conn.rollback(); throw t } finally { conn.autoCommit = auto }
    }
}

/** 1.10.0: deterministic compaction, raw history + recall, long-term memory. */
class V1100Test {
    private lateinit var tmp: File
    private lateinit var sandbox: WorkspaceSandbox
    private lateinit var env: ToolEnv
    private val conns = ArrayList<Connection>()

    @Before fun setUp() {
        tmp = Files.createTempDirectory("iris1100").toFile()
        sandbox = WorkspaceSandbox(File(tmp, "workspace"))
        env = ToolEnv(sandbox, UndoStore(File(tmp, "agent-undo")), clock = { 1_760_528_280_000L }, zone = { ZoneId.of("Asia/Hong_Kong") })
    }

    @After fun tearDown() { conns.forEach { runCatching { it.close() } }; tmp.deleteRecursively() }

    private fun db(name: String = "h.db"): JdbcSqlDriver =
        JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:" + File(tmp, name).path).also { conns += it })

    // ================================================================ token estimator

    @Test fun tokenEstimatorCountsCjkAndLatin() {
        assertEquals(0, TokenEstimator.estimate(""))
        assertEquals(4, TokenEstimator.estimate("你好世界"))
        assertEquals(2, TokenEstimator.estimate("abcdefg"))       // ceil(7 / 3.5)
        assertEquals(3, TokenEstimator.estimate("abcdefgh"))      // ceil(8 / 3.5)
        assertEquals(2 + 1, TokenEstimator.estimate("你好abc"))
        assertEquals(TokenEstimator.estimate("x") + TokenEstimator.MESSAGE_OVERHEAD, TokenEstimator.message("x"))
        assertEquals(3, TokenEstimator.estimate("ＡＢＣ"))           // full-width forms count like CJK
        // CompactMsg counts what is really sent (rolled-up tools only in 智能体 mode).
        val m = CompactMsg("a", CompactMsg.ASSISTANT, "好", listOf(AgentStep(tool = "read_file", args = "path=a.txt", result = "3 行", status = StepStatus.OK)))
        assertEquals(TokenEstimator.message("好"), m.tokens(false))
        assertTrue(m.tokens(true) > m.tokens(false))
    }

    // ================================================================ compaction

    private fun user(id: String, t: String) = CompactMsg(id, CompactMsg.USER, t)
    private fun bot(id: String, t: String, steps: List<AgentStep> = emptyList()) = CompactMsg(id, CompactMsg.ASSISTANT, t, steps)

    /** A long conversation: [n] rounds of ~[size] CJK characters each. */
    private fun convo(n: Int, size: Int = 200): List<CompactMsg> = (0 until n).flatMap { i ->
        listOf(user("u$i", "第${i}个问题：" + "问".repeat(size / 2)), bot("a$i", "第${i}个回答。" + "答".repeat(size / 2)))
    }

    @Test fun policyScalesWithTheContextWindow() {
        val p = CompactPolicy(4096)
        assertEquals(1000, p.reserve); assertEquals(1200, p.keepRecent); assertEquals(3096, p.trigger)
        val big = CompactPolicy(8192)
        assertEquals(2000, big.reserve); assertEquals(2400, big.keepRecent)
        val small = CompactPolicy(2048)
        assertEquals(500, small.reserve); assertEquals(600, small.keepRecent)
        assertEquals(2048, CompactPolicy(4096, hardBudget = 2048).trigger) // the planner's budget is lower → it wins
    }

    @Test fun belowTheThresholdNothingIsCompacted() {
        val msgs = convo(4)
        val p = CompactPolicy(4096)
        assertTrue(Compactor.estimate(100, null, msgs, false) < p.trigger)
        assertNull(Compactor.maybeCompact(msgs, null, 100, p, withTools = false))
        // A single round is never compacted, not even manually.
        assertNull(Compactor.maybeCompact(convo(1), null, 0, p, withTools = false, manual = true))
    }

    @Test fun aboveTheThresholdOldTurnsFoldAndRecentStayVerbatim() {
        val msgs = convo(30)
        val p = CompactPolicy(4096)
        assertTrue(Compactor.estimate(200, null, msgs, false) > p.trigger)
        val s = Compactor.maybeCompact(msgs, null, 200, p, withTools = false)!!
        val rest = Compactor.remaining(s, msgs)
        assertTrue(rest.isNotEmpty())
        assertTrue("recent part ≤ keepRecent", rest.sumOf { it.tokens(false) } <= p.keepRecent)
        assertEquals("u29", rest[rest.size - 2].id) // the newest round is kept
        assertTrue(rest.first().isUser)            // cut on a round boundary
        assertEquals(msgs.size - rest.size, s.messages)
        assertEquals(msgs[msgs.size - rest.size - 1].id, s.upToId)
        assertTrue(Compactor.estimate(200, s, msgs, false) <= p.trigger)
        assertTrue(TokenEstimator.message(Compactor.render(s)) <= p.summaryCap)
        // Manual: everything but the newest round.
        val m = Compactor.maybeCompact(convo(3), null, 0, p, withTools = false, manual = true)!!
        assertEquals("a1", m.upToId); assertEquals(4, m.messages)
    }

    @Test fun compactionIsDeterministic() {
        fun build() = listOf(
            user("1", "帮我整理 notes/todo.md 里的待办，并决定用 Markdown 表格。"),
            bot("2", "好的，已读取 notes/todo.md（共 12 行）。", listOf(AgentStep(id = "s1", tool = "read_file", args = "path=notes/todo.md", result = "12 行 · 300 字节", status = StepStatus.OK))),
            user("3", "TODO：下周一前把 v1.2.3 发布说明写好？参考 https://example.com/release 。"),
            bot("4", "报错了：write_file 失败，权限不足。"),
            user("5", "最后一个问题"),
            bot("6", "好"),
        )
        val p = CompactPolicy(4096)
        val a = Compactor.maybeCompact(build(), null, 0, p, withTools = true, manual = true)
        val b = Compactor.maybeCompact(build(), null, 0, p, withTools = true, manual = true)
        assertEquals(a, b)
        assertEquals(Compactor.render(a!!), Compactor.render(b!!))
        repeat(3) { assertEquals(Compactor.render(a), Compactor.render(Compactor.maybeCompact(build(), null, 0, p, true, manual = true)!!)) }
        // The state survives the conversation JSON unchanged.
        val j = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        assertEquals(a, j.decodeFromString(CompactState.serializer(), j.encodeToString(CompactState.serializer(), a)))
    }

    @Test fun sectionsAreExtracted() {
        val fold = listOf(
            user("1", "帮我把 app/src/Main.kt 的崩溃修好。另外看看 README.md"),
            bot("2", "我决定改用协程。日志里报错 NullPointerException。耗时 350 ms，占用 42%。版本 v2.3.1。"),
            user("3", "接下来要写测试，别忘了更新 https://example.com/docs?id=7 的链接"),
            bot("4", "", listOf(AgentStep(id = "x", tool = "write_file", args = "path=tests/MainTest.kt, content=（800 字）", result = "用户已拒绝", status = StepStatus.DENIED),
                AgentStep(id = "y", tool = "read_file", args = "path=docs/a.md", result = "文件不存在", status = StepStatus.ERROR))),
            user("5", "数据库要不要换成 SQLite？"),
        )
        val s = Compactor.extract(fold, "5")
        assertEquals("帮我把 app/src/Main.kt 的崩溃修好。", s.goals.first())
        assertTrue(s.goals.any { it.startsWith("接下来要写测试") })
        assertTrue(s.todos.toString(), s.todos.any { it.contains("接下来要写测试") })
        assertTrue("unanswered question stays open", s.todos.any { it.contains("SQLite？") })
        for (r in listOf("app/src/Main.kt", "README.md", "350 ms", "42%", "v2.3.1", "https://example.com/docs?id=7", "tests/MainTest.kt", "docs/a.md"))
            assertTrue("$r in ${s.refs}", s.refs.contains(r))
        assertTrue(s.signals.toString(), s.signals.any { it.contains("决定改用协程") })
        assertTrue(s.signals.any { it.contains("NullPointerException") })
        assertTrue(s.signals.any { it.startsWith("工具失败：read_file") })
        assertEquals(listOf("write_file(path=tests/MainTest.kt, content=（800 字）) ⊘ 用户已拒绝", "read_file(path=docs/a.md) ✗ 文件不存在"), s.tools)
        val text = Compactor.render(s)
        for (h in listOf(Compactor.HEADER, "目标：", "未完成事项：", "引用：", "关键信号：", "工具记录：")) assertTrue(h, text.contains(h))
        // An answered question is not left open; text inside code blocks is not mined.
        val answered = Compactor.extract(listOf(user("1", "今天几号？"), bot("2", "```\nTODO in code\n```\n10 月 15 日")), "2")
        assertTrue(answered.todos.isEmpty())
        // Plain prose numbers like "3 个" stay, single digits alone and abbreviations do not.
        val refs = Compactor.references("我有 3 个苹果，e.g 不是路径，价格 1,299 元")
        assertTrue(refs.toString(), refs.contains("3 个")); assertTrue(refs.contains("1,299 元")); assertFalse(refs.any { it == "e.g" })
    }

    @Test fun repeatedCompactionsMergeAndDedupe() {
        val p = CompactPolicy(4096)
        val first = listOf(
            user("1", "目标：给 IRIS 写发布说明。"), bot("2", "已读取 docs/PROGRESS.md，决定先列要点。"),
            user("3", "继续"), bot("4", "好"),
        )
        val s1 = Compactor.maybeCompact(first, null, 0, p, false, manual = true)!!
        assertEquals("2", s1.upToId)
        val more = first + listOf(
            user("5", "再看一下 docs/PROGRESS.md，决定先列要点。"), bot("6", "已读取 docs/PROGRESS.md 和 docs/API.md"),
            user("7", "最后"), bot("8", "完"),
        )
        val s2 = Compactor.maybeCompact(more, s1, 0, p, false, manual = true)!!
        assertEquals("6", s2.upToId)
        assertEquals(2, s2.times); assertEquals(6, s2.messages)
        assertEquals("目标：给 IRIS 写发布说明。", s2.goals.first()) // the first goal always stays first
        assertEquals(1, s2.refs.count { it == "docs/PROGRESS.md" })
        assertTrue(s2.refs.contains("docs/API.md"))
        assertEquals(s2.refs.size, s2.refs.toSet().size)
        // An anchor that is gone (message edited / deleted) means the state no longer applies.
        assertNull(Compactor.applicable(s2, more.filterNot { it.id == "6" }))
        assertEquals(more.size, Compactor.remaining(s2, more.filterNot { it.id == "6" }).size + 1)
        // dedupe ignores case, spaces and punctuation
        assertEquals(listOf("Hello World!"), Compactor.dedupe(listOf("Hello World!", "hello  world", "HELLO, WORLD")))
    }

    @Test fun summaryIsCappedByDroppingOldestItems() {
        val many = (0 until 60).flatMap { i -> listOf(user("u$i", "决定第 $i 件事：修改 src/file$i.kt 并参考 https://e.com/p$i"), bot("a$i", "错误 #$i：TODO 处理 item$i.txt")) }
        val s = Compactor.extract(many, "a59")
        val capped = Compactor.fit(Compactor.merge(null, s), 150)
        assertTrue(TokenEstimator.message(Compactor.render(capped)) <= 150)
        assertEquals(s.goals.first(), capped.goals.first())
        assertTrue(capped.refs.isEmpty() || capped.refs.last() == Compactor.merge(null, s).refs.last()) // newest kept
    }

    @Test fun toolCallsRollUpToOneLineEach() {
        val st = AgentStep(tool = "edit_file", args = "path=a.md, old=x\ny, new=z", result = "已修改 a.md · 1 处", status = StepStatus.OK)
        assertEquals("edit_file(path=a.md, old=x y, new=z) ✓ 已修改 a.md · 1 处", ToolRollup.line(st))
        assertEquals("datetime() … ", ToolRollup.line(AgentStep(tool = "datetime", status = StepStatus.RUNNING)) + " ")
        val long = AgentStep(tool = "write_file", args = "path=" + "a".repeat(100), result = "x".repeat(100), status = StepStatus.OK)
        assertTrue(ToolRollup.line(long).length < 330)
        val msg = bot("1", "已完成。", listOf(st, st.copy(id = "2", tool = "read_file", args = "path=a.md", result = "1 行")))
        val text = msg.promptText(withTools = true)
        assertTrue(text.startsWith(ToolRollup.HEADER))
        assertEquals(4, text.lines().size) // header + 2 tools + answer
        assertEquals("已完成。", msg.promptText(withTools = false))
        assertEquals("问", user("u", "问").promptText(true))
    }

    // ================================================================ context assembly (both modes)

    private fun conv(vararg m: ChatMessage, start: String? = null) = Conversation(id = "c1", messages = m.toList(), contextStartAfterId = start)

    @Test fun assemblyCarriesToolHistoryOnlyInAgentMode() {
        val steps = listOf(AgentStep(tool = "list_dir", args = "", result = "2 项", status = StepStatus.OK))
        val c = conv(
            ChatMessage(id = "1", role = Role.USER, content = "列出文件"),
            ChatMessage(id = "2", role = Role.ASSISTANT, content = "<think>想</think>有两个文件", agentSteps = steps),
            ChatMessage(id = "3", role = Role.USER, content = "出错的", error = null),
            ChatMessage(id = "4", role = Role.ASSISTANT, content = "", error = "模型未加载"),
            ChatMessage(id = "5", role = Role.ASSISTANT, content = "", agentSteps = steps),
            ChatMessage(id = "6", role = Role.USER, content = "下一个"),
        )
        val chat = ContextAssembly.active(c.messages, null, withTools = false)
        assertEquals(listOf("1", "2", "3", "6"), chat.map { it.id })
        assertEquals("有两个文件", chat[1].text)
        val agent = ContextAssembly.active(c.messages, null, withTools = true)
        assertEquals(listOf("1", "2", "3", "5", "6"), agent.map { it.id })
        val forPlanner = ContextAssembly.forPlanner(agent, null, withTools = true)
        assertTrue(forPlanner[1].content.contains("list_dir() ✓ 2 项"))
        assertTrue(forPlanner[1].content.endsWith("有两个文件"))
        assertFalse(ContextAssembly.forPlanner(chat, null, false)[1].content.contains("list_dir"))
        // manual break
        assertEquals(listOf("3", "6"), ContextAssembly.active(c.messages, "2", false).map { it.id })
        // with a compaction, only the rest goes to the planner; 2.3: the summary leads the history
        // (the system prompt no longer changes when the history is folded)
        val s = Compactor.maybeCompact(agent, null, 0, CompactPolicy(4096), true, manual = true)!!
        assertEquals(listOf("6"), ContextAssembly.forPlanner(agent, s, true).map { it.id })
        val sys = ContextAssembly.system("自定义指令", "【长期记忆】x")
        assertEquals("自定义指令\n\n【长期记忆】x", sys)
        assertEquals("自定义指令", ContextAssembly.system("自定义指令", ""))
        val prompt = ContextAssembly.prompt(sys, ContextAssembly.forPlanner(agent, s, true), ContextAssembly.lead(s))
        assertEquals(PromptMessage.SYSTEM, prompt.first().role); assertEquals(sys, prompt.first().content)
        assertTrue(prompt.last().content.startsWith(Compactor.HEADER)); assertTrue(prompt.last().content.endsWith("下一个"))
    }

    @Test fun autoCompactionRulesPerMode() {
        // 2.2.9 E: one switch.
        assertTrue(ContextAssembly.autoAllowed(true, ContextStrategy.SUMMARY, autoCompact = true))
        assertFalse(ContextAssembly.autoAllowed(true, ContextStrategy.TOKEN_BUDGET, autoCompact = false))
        assertTrue(ContextAssembly.autoAllowed(false, ContextStrategy.TOKEN_BUDGET, autoCompact = true))
        for (st in listOf(ContextStrategy.SUMMARY, ContextStrategy.SLIDING_WINDOW, ContextStrategy.KV_SHIFT))
            assertFalse(st.name, ContextAssembly.autoAllowed(false, st, autoCompact = true))
        val d = MemorySettings()
        // 2.0.x: 对话模式注入 is on by default now (was off; see v200/V200MemoryTest).
        assertTrue(d.enabled); assertTrue(d.injectAgent); assertTrue(d.injectChat)
        assertTrue(d.inject(agentMode = true)); assertTrue(d.inject(agentMode = false))
        assertFalse(d.copy(injectChat = false).inject(agentMode = false))
        assertFalse(d.copy(enabled = false).inject(true))
        assertTrue(d.autoCompact(true)); assertTrue(d.autoCompact(false))
    }

    // ================================================================ memory injection

    @Test fun memorySnapshotIsInjectedFrozenAndCapped() {
        val snap = MemoryPrompt.snapshot("- 名字叫阿伟\n- 喜欢简短回答", "- 项目用 Kotlin")
        assertEquals("USER.md:\n- 名字叫阿伟\n- 喜欢简短回答\nMEMORY.md:\n- 项目用 Kotlin", snap)
        assertEquals("", MemoryPrompt.snapshot("", " "))
        assertEquals("MEMORY.md:\n- a", MemoryPrompt.snapshot("", "- a"))
        val c = Conversation(memorySnapshot = snap, memoryEpoch = 2)
        val block = ContextAssembly.memoryBlock(c, inject = true, contextTokens = 4096, epoch = 2)
        assertTrue(block.startsWith(MemoryPrompt.HEADER)); assertTrue(block.contains("喜欢简短回答"))
        assertEquals("", ContextAssembly.memoryBlock(c, inject = false, contextTokens = 4096, epoch = 2))
        assertEquals("cleared memory → old snapshot not injected", "", ContextAssembly.memoryBlock(c, true, 4096, epoch = 3))
        assertEquals("", ContextAssembly.memoryBlock(Conversation(), true, 4096))
        // Capped to a fifth of the window, whole lines dropped from the end.
        val huge = MemoryPrompt.snapshot((1..200).joinToString("\n") { "- 用户事实第 $it 条，内容比较长一些" }, "")
        val capped = MemoryPrompt.block(huge, MemoryPrompt.budget(2048))
        assertTrue(TokenEstimator.estimate(capped) <= MemoryPrompt.budget(2048))
        assertTrue(capped.endsWith(MemoryPrompt.TRUNCATED))
        assertEquals(819, MemoryPrompt.budget(4096))
        // The snapshot is stored with the conversation and old JSON still loads.
        val j = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val back = j.decodeFromString(Conversation.serializer(), j.encodeToString(Conversation.serializer(), c))
        assertEquals(snap, back.memorySnapshot); assertEquals(2, back.memoryEpoch)
    }

    // ================================================================ memory docs + ops

    @Test fun memoryOpsEnforceTheCap() {
        var doc = ""
        doc = (MemoryOps.add(doc, "喜欢简短回答") as MemoryEdit.Ok).text
        doc = (MemoryOps.add(doc, "- 住在香港") as MemoryEdit.Ok).text
        assertEquals("- 喜欢简短回答\n- 住在香港", doc)
        assertTrue(MemoryOps.add(doc, "住在香港。") is MemoryEdit.Err) // duplicate
        assertTrue(MemoryOps.add(doc, "  ") is MemoryEdit.Err)
        assertTrue(MemoryOps.add(doc, "长".repeat(301)) is MemoryEdit.Err)
        doc = (MemoryOps.replace(doc, "香港", "住在深圳") as MemoryEdit.Ok).text
        assertEquals("- 喜欢简短回答\n- 住在深圳", doc)
        assertTrue(MemoryOps.replace(doc, "不存在", "x") is MemoryEdit.Err)
        assertTrue((MemoryOps.replace(doc, "住", "x") as? MemoryEdit.Err) == null) // "住" matches one entry only
        val two = "- 喜欢猫\n- 喜欢狗"
        assertTrue((MemoryOps.remove(two, "喜欢") as MemoryEdit.Err).message.contains("2 entries"))
        assertEquals("- 喜欢狗", (MemoryOps.remove(two, "猫") as MemoryEdit.Ok).text)
        // Cap: fill to the limit, the next add is refused and nothing grows past it.
        var full = ""
        var i = 0
        while (true) {
            when (val r = MemoryOps.add(full, "事实编号 $i：" + "内容".repeat(20))) {
                is MemoryEdit.Ok -> { full = r.text; assertTrue(full.length <= MemoryOps.CAP) }
                is MemoryEdit.Err -> { assertTrue(r.message.contains("full")); break }
            }
            i++
        }
        assertTrue(full.length > MemoryOps.CAP - 60)
        assertTrue(MemoryOps.replace(full, "事实编号 0：", "x".repeat(299)) is MemoryEdit.Err)
        assertTrue(MemoryOps.capUserText("a\n".repeat(2000)).let { it.length <= MemoryOps.CAP && it.endsWith("a") })
        assertTrue(MemoryOps.capUserText("x".repeat(3000)).length <= MemoryOps.CAP)
        assertEquals(MemoryTarget.USER, MemoryTarget.parse("USER.md")); assertEquals(MemoryTarget.MEMORY, MemoryTarget.parse(null))
        assertEquals(MemoryTarget.USER, MemoryTarget.parse("profile")); assertNull(MemoryTarget.parse("workspace"))
    }

    @Test fun memoryDocsLiveOutsideTheWorkspace() {
        val docs = MemoryDocs(File(tmp, "memory"))
        assertTrue(docs.isEmpty())
        docs.write(MemoryTarget.USER, "- 称呼：WWD")
        assertEquals("- 称呼：WWD", MemoryDocs(File(tmp, "memory")).read(MemoryTarget.USER)) // persisted
        assertFalse(docs.isEmpty())
        // The file tools cannot reach it.
        for (p in listOf("../memory/USER.md", File(tmp, "memory/USER.md").path)) {
            assertTrue(p, runCatching { sandbox.resolve(p) }.isFailure)
        }
        val r = com.iris.localai.agent.ReadFileTool.let { t -> runCatching { t.run(obj("path" to "../memory/USER.md"), env) } }
        assertTrue(r.isFailure)
        docs.write(MemoryTarget.MEMORY, "x".repeat(5000))
        assertTrue(docs.read(MemoryTarget.MEMORY).length <= MemoryOps.CAP)
        docs.clear()
        assertTrue(docs.isEmpty()); assertEquals("", docs.snapshot())
    }

    private fun obj(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

    @Test fun memoryToolAddReplaceRemove() {
        val docs = MemoryDocs(File(tmp, "memory"))
        val e = ToolEnv(sandbox, UndoStore(File(tmp, "u")), memory = docs)
        assertTrue(MemoryTool.run(obj("action" to "add", "target" to "user", "content" to "喜欢简短回答"), e).ok)
        assertTrue(MemoryTool.run(obj("action" to "add", "target" to "memory", "content" to "项目叫 IRIS"), e).ok)
        val dup = MemoryTool.run(obj("action" to "add", "target" to "memory", "content" to "项目叫 IRIS"), e)
        assertFalse(dup.ok); assertTrue(dup.output.contains("already"))
        assertTrue(MemoryTool.run(obj("action" to "replace", "target" to "memory", "old" to "IRIS", "content" to "项目叫 IRIS LocalAI"), e).ok)
        assertEquals("- 项目叫 IRIS LocalAI", docs.read(MemoryTarget.MEMORY))
        assertTrue(MemoryTool.run(obj("action" to "remove", "target" to "user", "old" to "简短"), e).ok)
        assertEquals("", docs.read(MemoryTarget.USER))
        assertFalse(MemoryTool.run(obj("action" to "explode", "target" to "user"), e).ok)
        assertFalse(MemoryTool.run(obj("action" to "add", "target" to "disk", "content" to "x"), e).ok)
        assertTrue(runCatching { MemoryTool.run(obj("action" to "add", "target" to "user"), e) }.isFailure) // missing content
        val off = MemoryTool.run(obj("action" to "add", "content" to "x"), env) // memory switched off
        assertFalse(off.ok); assertTrue(off.summary.contains("关闭"))
        val preview = memoryPreview(ToolCall("memory", obj("action" to "replace", "target" to "user", "old" to "旧", "content" to "新"), com.iris.localai.agent.CallFormat.FENCED))
        assertTrue(preview.startsWith("修改 USER.md")); assertTrue(preview.contains("原条目：旧")); assertTrue(preview.contains("内容：新"))
    }

    @Test fun permissionsAndPromptWithTheNewTools() {
        val s = AgentSettings()
        assertEquals(ToolRisk.READ, RecallTool.risk); assertEquals(Permission.ALLOW, s.permission(RecallTool))
        assertEquals(ToolRisk.MEMORY, MemoryTool.risk); assertEquals(Permission.ASK, s.permission(MemoryTool))
        assertTrue(s.isEnabled(RecallTool)); assertTrue(s.isEnabled(MemoryTool))
        assertEquals(Permission.ALLOW, com.iris.localai.agent.PermissionPolicy.afterDecision(s, MemoryTool, UserDecision.ALWAYS_ALLOW).permission(MemoryTool))
        assertNotNull(AgentTools.byName("recall")); assertNotNull(AgentTools.byName("memory"))
        val all = AgentPrompt.build(AgentTools.all)
        assertTrue("prompt ≈ ${TokenEstimator.estimate(all)} tokens", TokenEstimator.estimate(all) <= 800)
        assertTrue(all.contains("recall(")); assertTrue(all.contains("memory(")); assertTrue(all.contains("use recall"))
        val without = AgentPrompt.build(AgentTools.all.filter { it.name != "memory" && it.name != "recall" })
        assertFalse(without.contains("memory(")); assertFalse(without.contains("use recall"))
        assertEquals("回忆", com.iris.localai.ui.agent.StepText.label("recall"))
        assertEquals("长期记忆", com.iris.localai.ui.agent.StepText.label("memory"))
    }

    // ================================================================ raw history + FTS

    @Test fun ftsTextNormalisesAndQuotes() {
        assertEquals("修 复 崩 溃 app_main kt v1 2", FtsText.index("修复崩溃：App_Main.kt v1.2"))
        assertEquals(listOf("修复崩溃", "app", "kt"), FtsText.terms("修复崩溃 app.kt"))
        assertEquals("\"修 复\" OR \"bug\"", FtsText.query("修复 BUG"))
        assertNull(FtsText.query("  ？！ "))
        // operators and quotes in the query are only words
        assertEquals("\"a\" OR \"or\" OR \"near\" OR \"b\"", FtsText.query("a\" OR NEAR(b"))
        val snip = FtsText.snippet("x".repeat(300) + " 关键 词 " + "y".repeat(300), listOf("关键"))
        assertTrue(snip.contains("关键")); assertTrue(snip.startsWith("…")); assertTrue(snip.length <= 122)
        assertTrue(Bm25.idf(10.0, 9.0) > 0); assertTrue(Bm25.idf(10.0, 1.0) > Bm25.idf(10.0, 5.0))
    }

    private fun rec(conv: String, id: String, text: String, seq: Long = 0, role: String = "user", at: Long = 1000) =
        HistoryRecord(id, conv, id, seq, role, null, text, text, at)

    private fun seeded(allowFts5: Boolean, name: String): HistoryStore {
        val st = HistoryStore(db(name), allowFts5)
        st.syncConversation("c1", listOf(
            rec("c1", "m1", "我们讨论了天气，今天天气不错。", 0),
            rec("c1", "m2", "数据库迁移方案：先备份，再迁移，迁移后校验。迁移迁移。", 100, "assistant"),
            rec("c1", "m3", "这是一段很长的无关文字，" + "其他内容".repeat(80) + "，最后顺便提到一次迁移。", 200),
            rec("c1", "m4", "API key 放在 local.properties 里，不要提交。", 300, "assistant"),
        ))
        st.syncConversation("c2", listOf(rec("c2", "n1", "另一个对话也说到数据库迁移。", 0)))
        return st
    }

    @Test fun ftsSearchRanksWithBm25OnFts5AndFts4() {
        val fts5 = seeded(true, "a.db")
        val fts4 = seeded(false, "b.db")
        assertEquals("fts5", fts5.ftsVersion)
        assertEquals("fts4", fts4.ftsVersion)
        for (st in listOf(fts5, fts4)) {
            val hits = st.search("迁移", "c1", limit = 5)
            assertEquals(st.ftsVersion, listOf("m2", "m3"), hits.map { it.messageId }) // dense short message first
            assertTrue(hits[0].score > hits[1].score)
            assertTrue(hits[0].snippet.contains("迁移"))
            // scope: all conversations
            assertEquals(setOf("c1", "c2"), st.search("数据库迁移", null, 10).map { it.conversationId }.toSet())
            assertEquals(listOf("n1"), st.search("数据库迁移", "c2", 10).map { it.messageId })
            // a rare term outranks a common one
            val mixed = st.search("天气 迁移", "c1", 5)
            assertEquals("m1", mixed.first().messageId)
            // ASCII words, case-insensitive; phrase semantics for CJK runs
            assertEquals(listOf("m4"), st.search("LOCAL.PROPERTIES", "c1", 5).map { it.messageId })
            assertTrue(st.search("迁天", "c1", 5).isEmpty())
            // exclusions + limit
            assertEquals(listOf("m3"), st.search("迁移", "c1", 5, exclude = setOf("m2")).map { it.messageId })
            assertEquals(1, st.search("迁移", null, 1).size)
            assertTrue(st.search("\"", null, 5).isEmpty())
        }
    }

    @Test fun historySyncIsIncrementalAndFollowsDeletes() {
        val st = HistoryStore(db())
        val c = Conversation(id = "c9", messages = listOf(
            ChatMessage(id = "1", role = Role.USER, content = "读一下 notes.md", createdAt = 5),
            ChatMessage(id = "2", role = Role.ASSISTANT, content = "<think>先列目录</think>里面写着买牛奶", createdAt = 6,
                agentSteps = listOf(AgentStep(id = "s", tool = "read_file", args = "path=notes.md", result = "1 行", status = StepStatus.OK, detail = "买牛奶和面包"))),
        ))
        val recs = HistoryRecords.of(c)
        assertEquals(listOf("1", "2", "2#s"), recs.map { it.key })
        assertEquals("里面写着买牛奶", recs[1].searchText) // reasoning is not indexed
        assertTrue(recs[1].content.contains("<think>"))  // but kept in the raw record
        assertEquals("tool", recs[2].role); assertEquals("read_file", recs[2].tool)
        st.syncConversation(c.id, recs)
        assertEquals(3, st.count("c9"))
        assertTrue(st.search("先列目录", "c9", 5).isEmpty())
        assertEquals("tool", st.search("面包", "c9", 5).single().role)
        // unchanged sync: same rows; edit: re-indexed; removal follows
        st.syncConversation(c.id, recs)
        assertEquals(3, st.count("c9"))
        val edited = c.copy(messages = listOf(c.messages[0].copy(content = "读一下 todo.md")))
        st.syncConversation(c.id, HistoryRecords.of(edited))
        assertEquals(1, st.count("c9"))
        assertTrue(st.search("notes", "c9", 5).isEmpty())
        assertEquals(1, st.search("todo", "c9", 5).size)
        st.deleteConversation("c9")
        assertEquals(0, st.count())
        st.syncConversation("a", listOf(rec("a", "x", "一")))
        st.deleteAll()
        assertEquals(0, st.count()); assertTrue(st.search("一", null, 5).isEmpty())
    }

    @Test fun historyDatabaseReopensAndMigrates() {
        val d = db("m.db")
        val first = HistoryStore(d)
        assertTrue("fresh index asks for a backfill of the saved conversations", first.needsBackfill)
        first.syncConversation("c", listOf(rec("c", "1", "持久化测试")))
        first.markBackfilled()
        val again = HistoryStore(d)
        assertFalse(again.needsBackfill)
        assertEquals(first.ftsVersion, again.ftsVersion)
        assertEquals(1, again.search("持久化", null, 5).size)
        assertEquals(HistoryStore.SCHEMA_VERSION.toLong(), d.query("PRAGMA user_version").first()[0])
        // A store first created as FTS4 stays FTS4 (no silent re-index on every start).
        val d4 = db("m4.db")
        HistoryStore(d4, allowFts5 = false).syncConversation("c", listOf(rec("c", "1", "四")))
        val reopened = HistoryStore(d4, allowFts5 = true)
        assertEquals("fts4", reopened.ftsVersion)
        assertEquals(1, reopened.search("四", null, 5).size)
        // Lost index table → rebuilt and a backfill is requested again.
        d4.exec("DROP TABLE hist_fts")
        HistoryStore(d4).markBackfilled()
        d4.exec("DROP TABLE hist_fts")
        val rebuilt = HistoryStore(d4)
        assertTrue(rebuilt.needsBackfill)
    }

    @Test fun oldConversationsStillLoad() = runTest {
        // A 1.9.0 conversation file (no compaction / memory fields) through the real repository.
        val dir = File(tmp, "conversations").apply { mkdirs() }
        File(dir, "old1.json").writeText(
            "{\"id\":\"old1\",\"title\":\"旧对话\",\"messages\":[{\"id\":\"a\",\"role\":\"USER\",\"content\":\"你好\"}," +
                "{\"id\":\"b\",\"role\":\"ASSISTANT\",\"content\":\"嗨\",\"agentSteps\":[{\"id\":\"s\",\"tool\":\"datetime\",\"status\":\"OK\"}]}],\"summary\":\"\"}",
        )
        val repo = com.iris.localai.data.ConversationRepository(dir, kotlinx.coroutines.Dispatchers.Unconfined)
        repo.init()
        val c = repo.load("old1")!!
        assertEquals(2, c.messages.size); assertNull(c.compaction); assertNull(c.memorySnapshot); assertEquals(0, c.memoryEpoch)
        // Saved again with the new fields; listener sees it; an older reader ignores the unknown keys.
        val seen = ArrayList<String>()
        repo.listener = object : com.iris.localai.data.ConversationRepository.Listener {
            override fun onSaved(conversation: Conversation) { seen += conversation.id }
            override fun onDeleted(id: String) { seen += "-$id" }
            override fun onDeletedAll() { seen += "*" }
        }
        val compacted = c.copy(compaction = CompactState(upToId = "a", goals = listOf("你好"), messages = 1, times = 1), memorySnapshot = "")
        repo.save(compacted)
        assertEquals(compacted.compaction, repo.load("old1")!!.compaction)
        val legacy = Json { ignoreUnknownKeys = true }
        assertEquals(2, legacy.decodeFromString(OldConv.serializer(), File(dir, "old1.json").readText()).messages.size)
        var listed = 0
        repo.forEachSaved { listed++ }
        assertEquals(1, listed)
        repo.delete("old1")
        assertEquals(listOf("old1", "-old1"), seen)
    }

    // ================================================================ recall tool

    @Test fun recallToolSearchesThisChatByDefault() {
        val st = seeded(true, "r.db")
        val e = ToolEnv(sandbox, UndoStore(File(tmp, "u")), zone = { ZoneId.of("Asia/Hong_Kong") },
            history = HistorySearchAdapter(st), conversationId = "c1", recallExclude = setOf("m4"))
        val r = RecallTool.run(obj("query" to "迁移"), e)
        assertTrue(r.ok); assertEquals("2 条结果", r.summary)
        assertTrue(r.output.contains("in this chat")); assertTrue(r.output.contains("1. [assistant"))
        assertFalse(r.output.contains("另一个对话"))
        val all = RecallTool.run(obj("query" to "数据库迁移", "scope" to "all"), e)
        assertTrue(all.output.contains("other chat")); assertTrue(all.output.contains("in all chats"))
        assertTrue(RecallTool.run(obj("query" to "local.properties"), e).output.startsWith("No earlier messages")) // excluded
        assertTrue(runCatching { RecallTool.run(obj("q" to " "), e) }.isFailure)
        assertFalse(RecallTool.run(obj("query" to "x"), env).ok) // no store
        val one = RecallTool.run(obj("query" to "迁移", "limit" to "1"), e)
        assertEquals("1 条结果", one.summary)
    }

    // ================================================================ agent loop with recall + memory (fake model)

    private class FakeModel(vararg replies: String) : AgentModel {
        val queue = ArrayDeque(replies.toList())
        val seen = ArrayList<List<PromptMessage>>()
        override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
            seen += messages
            val r = queue.removeFirstOrNull() ?: "完成。"
            onText(r); return ModelTurn(r)
        }
    }

    private class Recorder(val decision: UserDecision) : AgentCallbacks {
        var steps: List<AgentStep> = emptyList()
        val asked = ArrayList<String>()
        var memoryChanged = 0
        var always = 0
        override suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision { asked += tool.name; return decision }
        override fun onSteps(steps: List<AgentStep>) { this.steps = steps }
        override fun onMemoryChanged() { memoryChanged++ }
        override fun onAlwaysAllow(tool: AgentTool) { always++ }
    }

    private fun fence(tool: String, args: String) = "```tool\n{\"tool\": \"$tool\", \"args\": $args}\n```"

    @Test fun agentLoopUsesRecallThenMemory() = runTest {
        val st = seeded(true, "loop.db")
        val docs = MemoryDocs(File(tmp, "memory"))
        val e = ToolEnv(sandbox, UndoStore(File(tmp, "u")), history = HistorySearchAdapter(st), memory = docs, conversationId = "c1")
        val m = FakeModel(
            fence("recall", "{\"query\": \"数据库迁移\"}"),
            "<tool_call>{\"name\":\"memory\",\"arguments\":{\"action\":\"add\",\"target\":\"memory\",\"content\":\"迁移前先备份\"}}</tool_call>",
            "已记住：迁移前先备份。",
        )
        val settings = AgentSettings()
        val cb = Recorder(UserDecision.ALLOW_ONCE)
        val o = AgentLoop(m, AgentTools.all.filter { settings.isEnabled(it) }, { settings.permission(it) }, e, cb).run(
            listOf(PromptMessage(PromptMessage.SYSTEM, "sys"), PromptMessage(PromptMessage.USER, "之前说的迁移方案是什么？记下来")),
        )
        assertEquals(AgentStop.DONE, o.stop)
        assertEquals(listOf("recall", "memory"), o.steps.map { it.tool })
        assertTrue(o.steps.all { it.status == StepStatus.OK })
        assertEquals(listOf("memory"), cb.asked) // recall = allow, memory = ask
        assertEquals(1, cb.memoryChanged)
        assertEquals("- 迁移前先备份", docs.read(MemoryTarget.MEMORY))
        val recallResult = m.seen[1].last().content
        assertTrue(recallResult.startsWith("<tool_response>")); assertTrue(recallResult.contains("先备份"))
        assertTrue(m.seen[2].last().content.contains("MEMORY.md added"))
    }

    @Test fun deniedMemoryCallChangesNothing() = runTest {
        val docs = MemoryDocs(File(tmp, "memory"))
        val e = ToolEnv(sandbox, UndoStore(File(tmp, "u")), memory = docs)
        val m = FakeModel(fence("memory", "{\"action\": \"add\", \"target\": \"user\", \"content\": \"喜欢猫\"}"), "好的，不记了。")
        val settings = AgentSettings()
        val cb = Recorder(UserDecision.DENY)
        val o = AgentLoop(m, AgentTools.all.filter { settings.isEnabled(it) }, { settings.permission(it) }, e, cb)
            .run(listOf(PromptMessage(PromptMessage.USER, "记住我喜欢猫")))
        assertEquals(StepStatus.DENIED, o.steps.single().status)
        assertTrue(docs.isEmpty()); assertEquals(0, cb.memoryChanged)
        // 总是允许 = the tool's permission becomes allow (the app stores it).
        val always = Recorder(UserDecision.ALWAYS_ALLOW)
        AgentLoop(FakeModel(fence("memory", "{\"action\": \"add\", \"target\": \"user\", \"content\": \"喜欢猫\"}"), "记住了"),
            AgentTools.all, { settings.permission(it) }, e, always).run(listOf(PromptMessage(PromptMessage.USER, "记住我喜欢猫")))
        assertEquals(1, always.always); assertEquals("- 喜欢猫", docs.read(MemoryTarget.USER))
    }

    @Test fun recallFindsWhatCompactionFolded() = runTest {
        // End to end: a long conversation is compacted, the folded detail is gone from the prompt,
        // and recall finds it in the raw history.
        val msgs = (0 until 20).flatMap { i ->
            listOf(
                ChatMessage(id = "u$i", role = Role.USER, content = if (i == 1) "我的车牌号是 粤B12345，记一下" else "闲聊第 $i 轮：" + "嗯".repeat(120)),
                ChatMessage(id = "a$i", role = Role.ASSISTANT, content = "好的，第 $i 轮。" + "哦".repeat(120)),
            )
        }
        val c = Conversation(id = "cc", messages = msgs)
        val active = ContextAssembly.active(c.messages, null, true)
        val s = Compactor.maybeCompact(active, null, 400, CompactPolicy(4096, hardBudget = 4096 - 1536), true)!!
        val sent = ContextAssembly.forPlanner(active, s, true).joinToString { it.content }
        assertFalse(sent.contains("粤B12345"))
        val store = HistoryStore(db("e2e.db"))
        store.syncConversation(c.id, HistoryRecords.of(c))
        val e = ToolEnv(sandbox, UndoStore(File(tmp, "u")), history = HistorySearchAdapter(store), conversationId = "cc")
        val r = RecallTool.run(obj("query" to "车牌号"), e)
        assertTrue(r.output, r.output.contains("粤B12345"))
    }

    // ================================================================ workspace location (Download/IRIDIS)

    @Test fun workspaceResolvesToDownloadsAndFallsBack() {
        val downloads = File(tmp, "storage/emulated/0/Download")
        val priv = File(tmp, "files/workspace")
        val d = com.iris.localai.agent.WorkspaceResolver.resolve(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, downloads, "mounted", priv)
        assertEquals(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, d.location)
        assertEquals(File(downloads, "IRIDIS/workspace"), d.root) // 2.1.1: the sandbox is the workspace/ subfolder
        assertEquals(File(downloads, "IRIDIS"), d.appRoot)
        assertTrue(d.root.isDirectory); assertNull(d.fallbackReason)
        assertFalse("probe file removed", File(d.root, ".iris-probe").exists())
        assertTrue(com.iris.localai.agent.WorkspaceResolver.display(d).endsWith("Download/IRIDIS/workspace"))
        // 应用私有目录 chosen
        val p = com.iris.localai.agent.WorkspaceResolver.resolve(com.iris.localai.agent.WorkspaceLocation.PRIVATE, downloads, "mounted", priv)
        assertEquals(priv, p.root); assertNull(p.fallbackReason)
        // external storage unavailable → private + notice
        for (state in listOf("unmounted", "removed", "shared", null)) {
            val f = com.iris.localai.agent.WorkspaceResolver.resolve(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, downloads, state, priv)
            assertEquals(com.iris.localai.agent.WorkspaceLocation.PRIVATE, f.location); assertEquals(priv, f.root)
            assertEquals(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, f.requested)
            assertTrue(f.fallbackReason!!.contains("外部存储不可用")); assertTrue(f.fallbackReason!!.contains("应用私有目录"))
        }
        assertTrue(com.iris.localai.agent.WorkspaceResolver.resolve(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, null, "mounted", priv).fallbackReason!!.contains("下载"))
        // folder cannot be created (a plain file is in the way) → private
        val blocked = File(tmp, "blocked/Download").apply { mkdirs() }
        File(blocked, "IRIDIS").writeText("not a folder")
        val b = com.iris.localai.agent.WorkspaceResolver.resolve(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, blocked, "mounted", priv)
        assertEquals(priv, b.root); assertTrue(b.fallbackReason!!.contains("无法在「下载」里创建"))
        val denied = com.iris.localai.agent.WorkspaceResolver.resolve(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, downloads, "mounted", priv, usable = { false })
        assertEquals(com.iris.localai.agent.WorkspaceLocation.PRIVATE, denied.location)
        // settings: default + tolerant parsing, old JSON without the key
        assertEquals(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, AgentSettings().workspaceLocation)
        assertEquals(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, AgentSettings(workspace = "nonsense").sanitized().workspaceLocation)
        assertEquals("DOWNLOADS", AgentSettings(workspace = "nonsense").sanitized().workspace)
        assertEquals(com.iris.localai.agent.WorkspaceLocation.PRIVATE, AgentSettings(workspace = "private").workspaceLocation)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AgentSettings.serializer(), "{\"agentMode\":true,\"maxSteps\":5}")
        assertEquals(com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, old.workspaceLocation); assertNull(old.workspaceTreeUri)
        assertEquals(listOf("下载/IRIDIS/workspace", "应用私有目录"), com.iris.localai.agent.WorkspaceLocation.entries.map { it.label })
        // SAF grant must be exactly Download/IRIDIS
        assertTrue(com.iris.localai.agent.WorkspaceResolver.isWorkspaceTree("primary:Download/IRIDIS"))
        assertTrue(com.iris.localai.agent.WorkspaceResolver.isWorkspaceTree("primary:Download/IRIDIS/"))
        for (bad in listOf("primary:Download", "primary:Download/IRIDIS/sub", "primary:Documents/IRIDIS", "primary:", null))
            assertFalse("$bad", com.iris.localai.agent.WorkspaceResolver.isWorkspaceTree(bad))
    }

    @Test fun sandboxRulesHoldInTheDownloadsRoot() {
        val root = com.iris.localai.agent.WorkspaceResolver.resolve(
            com.iris.localai.agent.WorkspaceLocation.DOWNLOADS, File(tmp, "Download"), "mounted", File(tmp, "priv"),
        ).root
        val sb = WorkspaceSandbox(root)
        assertEquals(File(root, "notes/a.md").canonicalFile, sb.resolve("notes/a.md"))
        assertEquals(File(root, "a.txt").canonicalFile, sb.resolve(File(root, "a.txt").path)) // absolute inside: ok
        for (bad in listOf("../a.txt", "notes/../../x", "~/x", File(tmp, "Download/other.txt").path, File(tmp, "priv/x").path, "a\u0000b"))
            assertTrue(bad, runCatching { sb.resolve(bad) }.isFailure)
        // a symlink inside the folder pointing out of it is refused
        val outside = File(tmp, "Download/secret").apply { mkdirs() }
        runCatching { java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) }
        if (File(root, "link").exists()) assertTrue(runCatching { sb.resolve("link/x.txt") }.isFailure)
        // undo snapshots stay private, and never restore into another location
        val undo = UndoStore(File(tmp, "agent-undo"))
        val f = sb.resolve("u.txt").apply { writeText("v1") }
        undo.snapshot(sb, f, "修改"); f.writeText("v2")
        assertFalse(File(tmp, "agent-undo").canonicalPath.startsWith(root.canonicalPath))
        assertEquals(root.canonicalPath, undo.peek()!!.root)
        val other = WorkspaceSandbox(File(tmp, "priv"))
        assertTrue(undo.undoLast(other)!!.contains("另一个工作区位置"))
        assertEquals("v2", f.readText())
        // a 1.9.0 snapshot (no root) still restores
        val f2 = sb.resolve("w.txt").apply { writeText("a") }
        undo.snapshot(sb, f2, "修改")
        val meta = File(tmp, "agent-undo").listFiles()!!.first { it.name.endsWith(".meta") }
        meta.writeText(meta.readText().replace(Regex(",\"root\":\"[^\"]*\""), ""))
        f2.writeText("b")
        assertTrue(undo.undoLast(sb)!!.contains("已恢复")); assertEquals("a", f2.readText())
    }

    @Test fun privateWorkspaceIsMigratedSafely() {
        val from = File(tmp, "files/workspace")
        val to = File(tmp, "Download/IRISLocalAI")
        File(from, "notes").mkdirs()
        File(from, "notes/todo.md").writeText("买牛奶")
        File(from, "a.txt").writeText("A")
        File(from, "same.txt").writeText("同")
        File(from, "clash.txt").writeText("旧内容")
        to.mkdirs()
        File(to, "same.txt").writeText("同")
        File(to, "clash.txt").writeText("新内容")
        val r = com.iris.localai.agent.WorkspaceMigration.migrate(from, to)
        assertTrue(r.ok)
        assertEquals(3, r.copied); assertEquals(1, r.skipped); assertEquals(1, r.renamed)
        assertEquals("买牛奶", File(to, "notes/todo.md").readText())
        assertEquals("A", File(to, "a.txt").readText())
        assertEquals("新内容", File(to, "clash.txt").readText())
        assertEquals("旧内容", File(to, "clash（私有目录）.txt").readText())
        assertFalse("originals removed after verified copy", from.walkTopDown().any { it.isFile })
        // second run: nothing to do
        assertEquals(com.iris.localai.agent.MigrationResult(0, 0, 0, 0), com.iris.localai.agent.WorkspaceMigration.migrate(from, to))
        // a copy that fails keeps the original
        File(from, "deep").mkdirs(); File(from, "deep/keep.txt").writeText("留")
        File(to, "deep").writeText("a file where the folder should be")
        val r2 = com.iris.localai.agent.WorkspaceMigration.migrate(from, to)
        assertFalse(r2.ok); assertEquals(1, r2.failed)
        assertEquals("留", File(from, "deep/keep.txt").readText())
        // same folder / nested target: no-op
        assertEquals(0, com.iris.localai.agent.WorkspaceMigration.migrate(to, to).copied)
        assertEquals(0, com.iris.localai.agent.WorkspaceMigration.migrate(File(tmp, "missing"), to).copied)
    }

    @Test fun filesOnlyVisibleThroughTheSafGrantAreReadable() {
        val ext = object : com.iris.localai.agent.ExternalFiles {
            override fun list(relDir: String) = if (relDir == ".") listOf(
                com.iris.localai.agent.ExternalFiles.Entry("用户放的.txt", false, 9),
                com.iris.localai.agent.ExternalFiles.Entry("own.txt", false, 1),
            ) else null
            override fun read(relPath: String, maxBytes: Long) = if (relPath == "用户放的.txt") "你好世界".toByteArray() else null
        }
        val e = ToolEnv(sandbox, UndoStore(File(tmp, "u")), external = ext)
        File(sandbox.root, "own.txt").writeText("x")
        val list = com.iris.localai.agent.ListDirTool.run(obj(), e)
        assertTrue(list.output.contains("用户放的.txt")); assertEquals(1, Regex("own.txt").findAll(list.output).count())
        assertEquals("你好世界", com.iris.localai.agent.ReadFileTool.run(obj("path" to "用户放的.txt"), e).output)
        assertFalse(com.iris.localai.agent.ReadFileTool.run(obj("path" to "missing.txt"), e).ok)
        assertTrue(runCatching { com.iris.localai.agent.ReadFileTool.run(obj("path" to "../x.txt"), e) }.isFailure) // sandbox first
        assertFalse(com.iris.localai.agent.ReadFileTool.run(obj("path" to "用户放的.txt"), env).ok) // no grant
    }

    // ================================================================ UI texts + version

    @Test fun uiTextsAndVersion() {
        assertEquals("现在整理", com.iris.localai.ui.chat.ContextText.COMPACT)
        assertEquals("较早的 12 条已整理成要点 · 原文仍在 · 撤销", com.iris.localai.ui.chat.ContextText.divider(12))
        val u = com.iris.localai.ui.chat.ContextUsage(compactedMessages = 8, compactions = 2)
        assertEquals("已整理 8 条消息（2 次）", com.iris.localai.ui.chat.ContextText.compacted(u))
        assertEquals(" · 已整理", com.iris.localai.ui.chat.ContextText.meterSuffix(u))
        assertEquals("", com.iris.localai.ui.chat.ContextText.meterSuffix(com.iris.localai.ui.chat.ContextUsage()))
        assertEquals("120 / 1500 字", com.iris.localai.ui.agent.MemoryText.usage(120))
        assertTrue(com.iris.localai.ui.settings.SettingsPage.entries.any { it.name == "MEMORY" })
        val home = source("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        assertTrue(home.contains("onOpen(SettingsPage.MEMORY)"))
        val screen = source("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("CompactionDivider(")); assertTrue(screen.contains("onCompact = vm::compactNow"))
        val page = source("src/main/java/com/iris/localai/ui/agent/MemorySettingsPage.kt")
        for (t in listOf("\"长期记忆\"", "\"智能体模式注入\"", "\"对话模式注入\"", "MemoryText.CLEAR", "\"保存\"")) assertTrue(t, page.contains(t))
        val vm = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("compactIfNeeded(agentMode = true")); assertTrue(vm.contains("freezeMemory(agentMode = false)"))
        assertTrue(vm.contains("w.fallbackReason")) // fallback notice
        val agentPage = source("src/main/java/com/iris/localai/ui/agent/AgentSettingsPage.kt")
        assertTrue(agentPage.contains("OpenDocumentTree")); assertTrue(agentPage.contains("WorkspaceResolver.display(ws)"))
        // 1.10.6: 全部文件访问 is declared, but only requested from 设置›智能体 (opt-in).
        assertTrue(source("src/main/AndroidManifest.xml").contains("MANAGE_EXTERNAL_STORAGE"))
        assertTrue(agentPage.contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"))
        val gradle = source("build.gradle.kts")
        assertTrue(Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toInt() >= 140)
        assertTrue(Regex("versionName = \"(1\\.(1\\d|[2-9]\\d)|[2-9])\\.\\d+").containsMatchIn(gradle))
    }

    private fun source(path: String): String =
        listOf(path, "app/$path").map { File(it) }.first { it.exists() }.readText()
}

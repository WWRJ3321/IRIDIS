package com.iris.localai.v1120

import com.iris.localai.agent.*
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import androidx.compose.ui.unit.dp

class V1120Test {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private fun env(phone: PhoneActions? = null, schedules: ScheduleStore? = null, skills: SkillCatalog? = null, now: Long = 1_792_200_000_000L): ToolEnv {
        val d = File(System.getProperty("java.io.tmpdir"), "iris1120-" + System.nanoTime())
        return ToolEnv(WorkspaceSandbox(File(d, "ws")), UndoStore(File(d, "undo")), clock = { now }, zone = { zone }, phone = phone, schedules = schedules, skills = skills)
    }

    @Test fun commandRulesDefaults() {
        val none = emptyMap<String, Permission>()
        assertEquals(Permission.ALLOW, CommandRules.decide("ls -la", Permission.ASK, none))
        assertEquals(Permission.ALLOW, CommandRules.decide("cat a.txt | grep x | wc -l", Permission.ASK, none))
        assertEquals(Permission.ASK, CommandRules.decide("rm a.txt", Permission.ASK, none))
        assertEquals(Permission.ASK, CommandRules.decide("ls && rm a", Permission.ASK, none))
        assertEquals(Permission.ASK, CommandRules.decide("echo hi > a.txt", Permission.ASK, none))
        assertEquals(Permission.ASK, CommandRules.decide("echo $(rm a)", Permission.ASK, none))
        assertEquals(Permission.ASK, CommandRules.decide("find . -delete", Permission.ASK, none))
        assertEquals(Permission.DENY, CommandRules.decide("ls", Permission.DENY, none))
    }

    @Test fun commandRulesUser() {
        val u = mapOf("git status" to Permission.ALLOW, "git" to Permission.ASK, "rm" to Permission.DENY)
        assertEquals(Permission.ALLOW, CommandRules.decide("git status -s", Permission.ASK, u))
        assertEquals(Permission.ASK, CommandRules.decide("git push", Permission.ASK, u))
        assertEquals(Permission.DENY, CommandRules.decide("ls; rm -rf x", Permission.ALLOW, u))
        assertEquals(Permission.ASK, CommandRules.decide("rmdir x", Permission.ASK, u))
        assertEquals("git status", CommandRules.prefixOf("git status -s"))
        assertEquals("python3", CommandRules.prefixOf("python3 a.py && ls"))
        assertEquals("apk add", CommandRules.prefixOf("apk add python3"))
        assertEquals("make", CommandRules.prefixOf("CC=clang make -j4"))
        assertEquals(Permission.ALLOW, RunCommandTool.permissionFor(buildJsonObject { put("command", "ls") }, Permission.ASK, env()))
    }

    @Test fun headTailBuffer() {
        val b = HeadTailBuffer(10)
        val data = "0123456789abcdefghij".toByteArray()
        b.write(data, data.size)
        assertTrue(b.truncated)
        val t = b.text()
        assertTrue(t, t.startsWith("01234") && t.endsWith("fghij") && t.contains("省略"))
        val small = HeadTailBuffer(100).apply { write("hello".toByteArray(), 5) }
        assertEquals("hello", small.text())
    }

    @Test fun prootSessionWrap() {
        val w = ProotSession.wrap("cd src; ls")
        assertTrue(w.contains(". /root/.iris/env"))
        assertTrue(w.contains("{ cd src; ls\n}"))
        assertTrue(w.trimEnd().endsWith("exit \$__iris_rc"))
        assertEquals(CommandBackend.PROOT, CommandBackend.parse("proot"))
        RunCommandTool.kind = CommandBackend.PROOT
        assertTrue(RunCommandTool.promptLine.contains("Alpine"))
        RunCommandTool.kind = CommandBackend.BUILTIN
    }

    @Test fun groupedPromptFitsBudget() {
        val all = AgentPrompt.build(AgentTools.all, "简短", AgentPrompt.CallStyle.QWEN)
        assertTrue("~${TokenEstimator.estimate(all)}", TokenEstimator.estimate(all) <= 800)
        assertTrue(all.contains("## Files"))
        assertTrue(all.contains("## Web — you are online"))
        assertTrue(all.contains("phone(action"))
        assertTrue(all.contains("schedule(action"))
        val sk = listOf(SkillInfo("daily-news", "Summarize today's news", "内置", { "" }))
        val withSkills = AgentPrompt.build(AgentTools.all, style = AgentPrompt.CallStyle.QWEN, skills = sk)
        assertTrue(withSkills.contains("- daily-news: Summarize"))
        assertFalse(AgentPrompt.build(listOf(ReadFileTool), skills = sk).contains("daily-news"))
    }

    @Test fun ddgAndSearx() {
        val html = """<table><tr><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&amp;rut=x" class='result-link'>Example &amp; Co</a></td></tr>
<tr><td class='result-snippet'>Some <b>text</b> here</td></tr></table>"""
        val h = SearchText.parseDdgLite(html)
        assertEquals(1, h.size); assertEquals("https://example.com/a", h[0].url); assertEquals("Example & Co", h[0].title); assertEquals("Some text here", h[0].snippet)
        val j = """{"results":[{"url":"https://a.org","title":"A","content":"aaa","engine":"bing"}]}"""
        assertEquals("https://a.org", SearchText.parseSearx(j).single().url)
        assertEquals("https://s.org/search?format=json&q=a+b", SearchText.searxApi("https://s.org/", "a b"))
        assertNull(SearchText.searxApi("ftp://x", "a"))
    }

    @Test fun searchFallsBackToDdg() {
        val f = object : WebFetcher {
            override fun fetch(url: String) = fetch(url, null)
            override fun fetch(url: String, userAgent: String?): FetchResult =
                if (url.contains("duckduckgo")) FetchResult(200, "text/html", """<a href="https://x.com" class='result-link'>X</a><td class='result-snippet'>s</td>""", url)
                else FetchResult(500, null, "", url)
        }
        val (label, hits) = SearchText.search(f, "q", false, SearchProvider.AUTO, "")
        assertEquals("DuckDuckGo", label); assertEquals("https://x.com", hits.single().url)
    }

    private class FakePhone : PhoneActions {
        val log = mutableListOf<String>()
        override fun setAlarm(hour: Int, minute: Int, label: String?) = "alarm $hour:$minute $label".also { log += it }
        override fun startTimer(seconds: Int, label: String?) = "timer $seconds".also { log += it }
        override fun addEvent(title: String, startMs: Long, endMs: Long, location: String?, notes: String?) = "event $title $startMs $endMs".also { log += it }
        override fun apps() = listOf("微信" to "com.tencent.mm", "Chrome" to "com.android.chrome", "Calculator" to "com.miui.calculator")
        override fun openApp(pkg: String) = "open $pkg".also { log += it }
        override fun share(text: String) = "share".also { log += it }
        override fun copy(text: String) = "copy $text".also { log += it }
        override fun paste(): String? = "clip"
        override fun openUrl(url: String) = "url $url".also { log += it }
        override fun dial(number: String) = "dial $number".also { log += it }
        override fun flashlight(mode: String) = "torch $mode".also { log += it }
        override fun volume(stream: String?, percent: Int?) = "volume $stream $percent".also { log += it }
        override fun ringerMode(mode: String?) = "ringer $mode".also { log += it }
        override fun brightness(percent: Int?) = "brightness $percent".also { log += it }
        override fun openSettings(page: String, pkg: String?) = "settings $page $pkg".also { log += it }
        override fun battery() = "battery 80% charging".also { log += it }
    }

    @Test fun phoneTool() {
        val p = FakePhone(); val e = env(phone = p)
        assertTrue(PhoneTool.run(buildJsonObject { put("action", "alarm"); put("time", "7:05"); put("text", "起床") }, e).ok)
        assertEquals("alarm 7:5 起床", p.log.last())
        PhoneTool.run(buildJsonObject { put("action", "open_app"); put("name", "chrome") }, e)
        assertEquals("open com.android.chrome", p.log.last())
        assertFalse(PhoneTool.run(buildJsonObject { put("action", "open_app"); put("name", "nope") }, e).ok)
        PhoneTool.run(buildJsonObject { put("action", "timer"); put("minutes", 5) }, e)
        assertEquals("timer 300", p.log.last())
        PhoneTool.run(buildJsonObject { put("action", "open_url"); put("url", "example.com") }, e)
        assertEquals("url https://example.com", p.log.last())
        val r = PhoneTool.run(buildJsonObject { put("action", "calendar"); put("text", "开会"); put("time", "2026-10-20 15:00") }, e)
        assertTrue(r.ok)
        assertNull(PhoneTool.parseHourMinute("25:00"))
        assertEquals(1_792_479_600_000L, PhoneTool.parseDateTime("2026-10-20 15:00", e))
    }

    @Test fun schedules() {
        var saved: String? = null
        val store = ScheduleStore({ saved }, { saved = it })
        val now = PhoneTool.parseDateTime("2026-10-17 09:00", env())!!
        val e = env(schedules = store, now = now)
        val r = ScheduleTool.run(buildJsonObject { put("title", "整理新闻"); put("time", "08:00"); put("repeat", "daily"); put("prompt", "整理今天的新闻") }, e)
        assertTrue(r.output, r.ok)
        val s = store.items.value.single()
        assertEquals(PhoneTool.parseDateTime("2026-10-18 08:00", e), s.nextAt)
        val next = ScheduleRules.afterFire(s, s.nextAt + 1000, zone)!!
        assertEquals(PhoneTool.parseDateTime("2026-10-19 08:00", e), next.nextAt)
        // weekdays: Fri 2026-10-23 08:00 → Mon 10-26
        val fri = PhoneTool.parseDateTime("2026-10-23 08:30", e)!!
        assertEquals(PhoneTool.parseDateTime("2026-10-26 08:00", e), ScheduleRules.next(Repeat.WEEKDAYS, 8, 0, fri, zone))
        assertTrue(ScheduleTool.run(buildJsonObject { put("action", "list") }, e).output.contains("整理新闻"))
        assertTrue(ScheduleTool.run(buildJsonObject { put("action", "remove"); put("id", s.id) }, e).ok)
        assertTrue(store.items.value.isEmpty())
        assertNull(ScheduleRules.afterFire(s.copy(repeat = Repeat.NONE), now, zone))
        // reload from saved json
        ScheduleTool.run(buildJsonObject { put("title", "a"); put("time", "2026-10-17 10:00") }, e)
        assertEquals(1, ScheduleStore({ saved }, {}).items.value.size)
        assertThrows(IllegalArgumentException::class.java) { ScheduleTool.run(buildJsonObject { put("title", "past"); put("time", "2026-10-16 10:00") }, e) }
    }

    @Test fun skills() {
        val d = File(System.getProperty("java.io.tmpdir"), "iris-skills-" + System.nanoTime())
        File(d, "skills/my-skill").mkdirs()
        File(d, "skills/my-skill/SKILL.md").writeText("---\nname: my-skill\ndescription: \"Do X\"\n---\n# Steps\n1. go\n")
        File(d, "skills/my-skill/run.sh").writeText("echo hi")
        val builtIn = listOf(SkillCatalog.parse("daily-news", "---\nname: daily-news\ndescription: news\n---\nbody", "内置"),
            SkillCatalog.parse("my-skill", "---\nname: my-skill\ndescription: old\n---\nold", "内置"))
        val cat = SkillCatalog({ builtIn }, { d })
        val all = cat.all()
        assertEquals(listOf("daily-news", "my-skill"), all.map { it.name })
        assertEquals("Do X", all.last().description)
        val r = SkillTool.run(buildJsonObject { put("name", "my-skill") }, env(skills = cat))
        // 2.1.1: skill files are listed relative to the skill and read through the tool (skills are outside the workspace).
        assertTrue(r.output.contains("1. go") && r.output.contains("run.sh") && !r.output.contains("description:"))
        assertFalse(SkillTool.run(buildJsonObject { put("name", "zzz") }, env(skills = cat)).ok)
        assertEquals("x" to "y", SkillCatalog.frontMatter("---\nname: x\n---\ny").let { it.first["name"] to it.second.trim() })
    }

    @Test fun backupRoundTrip() {
        val root = File(System.getProperty("java.io.tmpdir"), "iris-bk-" + System.nanoTime())
        File(root, "files/conversations").mkdirs(); File(root, "files/conversations/a.json").writeText("A")
        File(root, "shared_prefs").mkdirs(); File(root, "shared_prefs/iris_agent.xml").writeText("<x/>")
        File(root, "files/secret.bin").writeText("no")
        val out = java.io.ByteArrayOutputStream()
        assertEquals(2, com.iris.localai.util.Backup.export(root, out, "1.12.0"))
        File(root, "files/conversations/a.json").writeText("changed"); File(root, "files/conversations/b.json").writeText("B")
        assertEquals(2, com.iris.localai.util.Backup.restore(root, out.toByteArray().inputStream()))
        assertEquals("A", File(root, "files/conversations/a.json").readText())
        assertFalse(File(root, "files/conversations/b.json").exists())
        val bad = java.io.ByteArrayOutputStream().also { java.util.zip.ZipOutputStream(it).use { z -> z.putNextEntry(java.util.zip.ZipEntry("files/conversations/x")); z.write(1) } }
        assertThrows(IllegalArgumentException::class.java) { com.iris.localai.util.Backup.restore(root, bad.toByteArray().inputStream()) }
    }

    @Test fun usageOnBadArgs() {
        val d = File(System.getProperty("java.io.tmpdir"), "iris-usage-" + System.nanoTime())
        val e = ToolEnv(WorkspaceSandbox(File(d, "ws")), UndoStore(File(d, "u")))
        val model = object : AgentModel {
            var n = 0
            override suspend fun complete(messages: List<com.iris.localai.engine.PromptMessage>, onText: (String) -> Unit): ModelTurn =
                if (n++ == 0) ModelTurn("```tool\n{\"tool\": \"read_file\", \"args\": {}}\n```") else ModelTurn("好")
        }
        val cb = object : AgentCallbacks {
            override suspend fun confirm(tool: AgentTool, call: ToolCall) = UserDecision.ALLOW_ONCE
            override fun onSteps(steps: List<AgentStep>) {}
        }
        val o = kotlinx.coroutines.runBlocking { AgentLoop(model, listOf(ReadFileTool), { Permission.ALLOW }, e, cb).run(emptyList()) }
        assertTrue(o.steps.single().detail, o.steps.single().detail.contains("Usage: read_file("))
    }

    @Test fun moveFileAndUndo() {
        val e = env()
        File(e.sandbox.root, "a.txt").apply { parentFile.mkdirs(); writeText("hi") }
        val r = MoveFileTool.run(buildJsonObject { put("from", "a.txt"); put("to", "notes/b.txt") }, e)
        assertTrue(r.output, r.ok)
        assertFalse(File(e.sandbox.root, "a.txt").exists()); assertEquals("hi", File(e.sandbox.root, "notes/b.txt").readText())
        assertFalse(MoveFileTool.run(buildJsonObject { put("from", "missing"); put("to", "x") }, e).ok)
        e.undo.undoLast(e.sandbox); e.undo.undoLast(e.sandbox)
        assertTrue(File(e.sandbox.root, "a.txt").exists()); assertFalse(File(e.sandbox.root, "notes/b.txt").exists())
    }

    @Test fun todoPlan() {
        val e = ToolEnv(env().sandbox, env().undo, conversationId = "c-todo")
        val items = kotlinx.serialization.json.JsonArray(listOf("搜索", "整理", "回答").map { kotlinx.serialization.json.JsonPrimitive(it) })
        val r = TodoTool.run(kotlinx.serialization.json.JsonObject(mapOf("items" to items)), e)
        assertTrue(r.output, r.output.contains("1. [ ] 搜索"))
        val d = TodoTool.run(buildJsonObject { put("done", "1,2") }, e)
        assertTrue(d.output.contains("2. [x] 整理")); assertEquals("2/3 完成", d.summary)
        assertEquals("全部完成", TodoTool.run(buildJsonObject { put("done", "3") }, e).summary)
    }

    @Test fun promptWithEveryToolStaysSmall() {
        val p = AgentPrompt.build(AgentTools.all)
        assertTrue("≈ ${TokenEstimator.estimate(p)}", TokenEstimator.estimate(p) <= 800)
        assertTrue(p.contains("move_file(") && p.contains("plan("))
    }

    @Test fun stepFoldAndAdaptive() {
        assertEquals(3, com.iris.localai.ui.agent.StepFold.visible(3))
        assertEquals(2, com.iris.localai.ui.agent.StepFold.visible(6))
        val hidden = listOf(AgentStep(tool = "web_search", status = StepStatus.OK), AgentStep(tool = "web_fetch", status = StepStatus.ERROR))
        val t = com.iris.localai.ui.agent.StepFold.summary(hidden, false)
        assertTrue(t, t.startsWith("已执行 2 步") && t.contains("1 步未成功"))
        val A = com.iris.localai.ui.components.Adaptive
        assertEquals(0f, A.sidePadding(400.dp, A.chatMaxWidth).value, 0f)
        assertEquals(170f, A.sidePadding(1100.dp, A.chatMaxWidth).value, 0.01f)
    }

    @Test fun readFileSaysHowToContinue() {
        val e = env()
        File(e.sandbox.root, "long.txt").apply { parentFile.mkdirs(); writeText((1..3000).joinToString("\n") { "第 $it 行，一些中文内容用来占位" }) }
        val r = ReadFileTool.run(buildJsonObject { put("path", "long.txt") }, e)
        assertTrue(r.output, r.output.startsWith("[lines 1-"))
        val next = Regex("offset=(\\d+)").find(r.output)!!.groupValues[1].toInt()
        assertTrue(r.output.contains("process_file") && !r.output.contains("已截断"))
        assertTrue(r.output.contains("第 ${next - 1} 行") && !r.output.contains("第 $next 行，"))
    }

    @Test fun writeFileAppends() {
        val e = env()
        WriteFileTool.run(buildJsonObject { put("path", "a.txt"); put("content", "1") }, e)
        WriteFileTool.run(buildJsonObject { put("path", "a.txt"); put("content", "2"); put("append", true) }, e)
        assertEquals("12", File(e.sandbox.root, "a.txt").readText())
    }

    @Test fun processFileWalksTheWholeFile() {
        val src = (1..900).joinToString("\n") { "第${it}句话。" }
        val parts = ProcessFileTool.split(src, 300)
        assertTrue(parts.size > 3)
        assertEquals(src.replace("\n", ""), parts.joinToString("") { it.replace("\n", "") })
        assertTrue(parts.all { TokenEstimator.estimate(it) <= 320 })
        val hard = ProcessFileTool.split("长".repeat(1000), 300)
        assertEquals(1000, hard.sumOf { it.length }); assertTrue(hard.size >= 4)
        assertEquals("docs/ATRI.translated.txt", ProcessFileTool.defaultOut("docs/ATRI.txt", "翻译成英文"))
        assertEquals("notes.out.md", ProcessFileTool.defaultOut("notes.md", "summarize"))
        assertEquals("hi", ProcessFileTool.clean("<think>x</think>```text\nhi\n```"))

        val calls = ArrayList<String>()
        val base = env()
        val e = ToolEnv(base.sandbox, base.undo, llm = { sys, user -> calls += user; assertTrue(sys.contains("Task: 翻译")); "EN(" + user.substringAfter("\n\n").length + ")" }, chunkTokens = 300)
        File(e.sandbox.root, "book.txt").apply { parentFile.mkdirs(); writeText(src) }
        val seen = ArrayList<String>()
        val r = kotlinx.coroutines.runBlocking { ProcessFileTool.runSuspend(buildJsonObject { put("path", "book.txt"); put("task", "翻译成英文") }, e) { seen += it } }
        assertTrue(r.output, r.ok)
        assertEquals(parts.size, calls.size)
        assertEquals("第 1/${parts.size} 段", seen.first())
        val out = File(e.sandbox.root, "book.translated.txt").readText()
        assertEquals(parts.size, Regex("EN\\(").findAll(out).count())
        assertTrue(r.output.contains("book.translated.txt"))
    }
}

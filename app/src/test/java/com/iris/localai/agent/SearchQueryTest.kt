package com.iris.localai.agent

import com.iris.localai.agent.core.Args
import com.iris.localai.agent.web.BrowserSearch
import com.iris.localai.agent.tools.FetchResult
import com.iris.localai.agent.core.JsonRepair
import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.web.SearchText
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.web.WebSearchTool
import com.iris.localai.agent.workspace.WorkspaceSandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files

/**
 * 2.1.8 「搜索词被弄乱」: tool calls whose query was single-quoted, unquoted, a list, or carried
 * inner quotes reached web_search mangled (`'诡秘之主'` with the quotes) or not at all.
 */
class SearchQueryTest {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private lateinit var tmp: File
    private val fetched = ArrayList<String>()
    private val rss = """<?xml version="1.0"?><rss><channel>
        <item><title>诡秘之主 - 百科</title><link>https://example.com/a</link><description>网络小说。</description></item>
        </channel></rss>"""

    @Before fun setUp() { tmp = Files.createTempDirectory("iris218sq").toFile() }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun env() = ToolEnv(WorkspaceSandbox(File(tmp, "ws")), UndoStore(File(tmp, "undo")),
        fetcher = { url -> fetched += url; FetchResult(200, "application/rss+xml", rss, url) })

    private fun call(reply: String): ParsedReply.Call {
        val p = ToolCallParser.parse(reply)
        assertTrue("expected a call, got $p", p is ParsedReply.Call)
        return p as ParsedReply.Call
    }

    private fun query(reply: String): String? = WebSearchTool.query(call(reply).call.args)

    private fun fenced(body: String) = "```tool\n$body\n```"

    private fun q(u: String): String {
        val raw = URI(u).rawQuery.split('&').first { it.startsWith("q=") }.removePrefix("q=")
        return URLDecoder.decode(raw, "UTF-8")
    }

    // ---------- 1) single quotes ----------

    @Test fun singleQuotedValueLosesItsQuotes() {
        val c = call(fenced("""{"tool": "web_search", "args": {"query": '诡秘之主'}}"""))
        assertEquals("web_search", c.call.name)
        assertEquals("诡秘之主", Args.str(c.call.args, "query"))
    }

    @Test fun singleQuotedObjectParses() {
        val c = call(fenced("""{'tool':'web_search','args':{'query':'三体'}}"""))
        assertEquals("web_search", c.call.name)
        assertEquals("三体", Args.str(c.call.args, "query"))
    }

    @Test fun apostropheInsideDoubleQuotesIsKept() {
        assertEquals("it's 三体", query(fenced("""{"tool": "web_search", "args": {"query": "it's 三体"}}""")))
        assertFalse(JsonRepair.singleQuoteOutside("""{"query": "it's"}"""))
        assertTrue(JsonRepair.singleQuoteOutside("""{"query": 'x'}"""))
        assertFalse(JsonRepair.singleQuoteOutside("""{"query": "a\"'b"}"""))
    }

    @Test fun singleQuotesInsideValueStayLiteral() {
        assertEquals("'诡秘之主' 小说", query(fenced("""{"tool": "web_search", "args": {"query": "'诡秘之主' 小说"}}""")))
    }

    // ---------- 2) bare values ----------

    @Test fun bareValueWithSpaceIsOneString() {
        assertEquals("诡秘之主 小说", query(fenced("""{"tool": "web_search", "args": {"query": 诡秘之主 小说}}""")))
    }

    @Test fun bareValueStopsAtCommaAndKeepsLiterals() {
        val a = call(fenced("""{"tool": "web_search", "args": {"query": 诡秘之主 小说, "news": true, "n": 3, "x": null}}""")).call.args
        assertEquals("诡秘之主 小说", Args.str(a, "query"))
        assertEquals(true, Args.bool(a, "news"))
        assertEquals(3, a["n"]!!.jsonPrimitive.int)
        assertFalse(a["n"]!!.jsonPrimitive.isString)
    }

    @Test fun repairQuotesBareValues() {
        val o = json.parseToJsonElement(JsonRepair.repair("""{"query": 三体: 黑暗森林 , "n": -1.5, "ok": True}""")).jsonObject
        assertEquals("三体: 黑暗森林", o["query"]!!.jsonPrimitive.content)
        assertEquals("-1.5", o["n"]!!.jsonPrimitive.content)
        assertFalse(o["n"]!!.jsonPrimitive.isString)
        assertEquals("true", o["ok"]!!.jsonPrimitive.content)
    }

    @Test fun bareValueHelper() {
        assertEquals("\"诡秘之主 小说\"" to 10, JsonRepair.bareValue("x: 诡秘之主 小说 }", 3))
        assertNull(JsonRepair.bareValue("42}", 0))
        assertNull(JsonRepair.bareValue("false,", 0))
        assertNull(JsonRepair.bareValue("\"a\"", 0))
        assertNull(JsonRepair.bareValue("{\"a\":1}", 0))
        assertNull(JsonRepair.bareValue("[1]", 0))
    }

    @Test fun gemmaBareValue() {
        assertEquals("诡秘之主 小说", query("<|tool_call>call:web_search{query: 诡秘之主 小说}<tool_call|>"))
        val a = call("<|tool_call>call:web_search{query:<|\"|>三体 小说<|\"|>, news: false}<tool_call|>").call.args
        assertEquals("三体 小说", Args.str(a, "query"))
        assertEquals(false, Args.bool(a, "news"))
        val g = json.parseToJsonElement(ToolCallParser.gemmaArgs("{query: 诡秘之主 小说, n: 2}")).jsonObject
        assertEquals("诡秘之主 小说", g["query"]!!.jsonPrimitive.content)
        assertEquals(2, g["n"]!!.jsonPrimitive.int)
    }

    // ---------- 3) list queries ----------

    @Test fun arrayQueryIsJoined() {
        assertEquals("诡秘之主 小说", query(fenced("""{"tool": "web_search", "args": {"query": ["诡秘之主", "小说"]}}""")))
        assertEquals("诡秘之主 小说", query(fenced("""{"tool": "web_search", "args": {"keywords": ["诡秘之主", " ", "小说"]}}""")))
        assertNull(WebSearchTool.query(JsonObject(mapOf("query" to kotlinx.serialization.json.JsonArray(emptyList())))))
        assertEquals("三体", WebSearchTool.query(JsonObject(mapOf("q" to JsonPrimitive("三体")))))
    }

    @Test fun arrayQueryRunsSearch() {
        val args = call(fenced("""{"tool": "web_search", "args": {"query": ["诡秘之主", "小说"]}}""")).call.args
        val r = WebSearchTool.run(args, env())
        assertTrue(r.output, r.ok)
        assertEquals("诡秘之主 小说", q(fetched.first()))
    }

    // ---------- 4) inner quotes ----------

    @Test fun unescapedInnerQuotesAreKept() {
        assertEquals("\"诡秘之主\" 小说", query(fenced("""{"tool": "web_search", "args": {"query": ""诡秘之主" 小说"}}""")))
        assertEquals("say \"hi\" 三体", query(fenced("""{"tool": "web_search", "args": {"query": "say \"hi\" 三体"}}""")))
    }

    // ---------- untouched forms ----------

    @Test fun normalFormsUnchanged() {
        assertEquals("诡秘之主 小说", query(fenced("""{"tool":"web_search","args":{"query":"诡秘之主 小说"}}""")))
        assertEquals("“诡秘之主”小说", query(fenced("""{"tool": "web_search", "args": {"query": "“诡秘之主”小说"}}""")))
        assertEquals("「诡秘之主」 小说", query(fenced("""{"tool": "web_search", "args": {"query": "「诡秘之主」 小说"}}""")))
        assertEquals("诡秘之主 小说", query("<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"诡秘之主 小说\"}}\n</tool_call>"))
        assertEquals("诡秘之主\n小说", query(fenced("""{"tool": "web_search", "args": {"query": "诡秘之主\n小说"}}""")))
    }

    // ---------- Chinese round-trip through every URL builder ----------

    @Test fun chineseQueriesRoundTripThroughUrls() {
        val queries = listOf("诡秘之主 小说", "三体", "「诡秘之主」 小说", "\"诡秘之主\" 小说", "C++ & 三体 #1 100%", "it's 三体?=")
        for (s in queries) {
            assertEquals(s, q(SearchText.rssUrl(s, false)))
            assertEquals(s, q(SearchText.rssUrl(s, true)))
            assertEquals(s, q(SearchText.htmlUrl(s)))
            assertEquals(s, q(SearchText.ddgUrl(s)))
            assertEquals(s, q(SearchText.searxApi("https://searx.example/", s)!!))
            assertEquals(s, q(BrowserSearch.googleUrl(s, "zh-CN")))
            assertEquals(s, q(BrowserSearch.googleUrl(s, "zh-TW", news = true)))
            assertEquals(s, q(BrowserSearch.bingUrl(s, "zh-CN")))
            assertFalse(SearchText.htmlUrl(s).any { it.code > 127 || it == ' ' })
        }
    }

    @Test fun parsedQueryRoundTripsToFetchedUrl() {
        val replies = mapOf(
            fenced("""{"tool": "web_search", "args": {"query": '诡秘之主'}}""") to "诡秘之主",
            fenced("""{'tool':'web_search','args':{'query':'三体'}}""") to "三体",
            fenced("""{"tool": "web_search", "args": {"query": 诡秘之主 小说}}""") to "诡秘之主 小说",
            "<|tool_call>call:web_search{query: 三体 黑暗森林}<tool_call|>" to "三体 黑暗森林",
        )
        for ((reply, expected) in replies) {
            fetched.clear()
            val r = WebSearchTool.run(call(reply).call.args, env())
            assertTrue(r.output, r.ok)
            assertEquals(expected, q(fetched.first()))
        }
    }
}

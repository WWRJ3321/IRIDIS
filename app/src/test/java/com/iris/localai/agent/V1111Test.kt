package com.iris.localai.agent

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.web.*
import com.iris.localai.agent.workspace.*
import com.iris.localai.agent.*
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V1111Test {
    private val rss = """<?xml version="1.0" encoding="utf-8" ?><rss version="2.0"><channel><title>x</title>
<item><title>长鑫科技市值重回3万亿</title><link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;aid=&amp;url=https%3a%2f%2fwww.msn.com%2fzh-cn%2fmoney%2far-AA2elX9P&amp;c=1</link><description>10月16日上午，A股上演了一出&#8220;王者归来&#8221;。</description><pubDate>Fri, 16 Oct 2026 00:51:55 GMT</pubDate><News:Source>BT财经 on MSN</News:Source></item>
<item><title>Today &amp; Tomorrow</title><link>https://www.today.com/</link><description>Find the latest news.</description></item>
</channel></rss>"""

    @Test fun parsesRss() {
        val h = SearchText.parseRss(rss)
        assertEquals(2, h.size)
        assertEquals("长鑫科技市值重回3万亿", h[0].title)
        assertEquals("https://www.msn.com/zh-cn/money/ar-AA2elX9P", h[0].url)
        assertEquals("BT财经 on MSN", h[0].source)
        assertEquals("16 Oct 2026", h[0].date)
        assertTrue(h[0].snippet, h[0].snippet.contains("“王者归来”"))
        assertEquals("Today & Tomorrow", h[1].title)
    }

    @Test fun parsesHtmlAndUnwrapsLinks() {
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.com/a?b=1".toByteArray())
        val html = """<ol><li class="b_algo" data-id><h2><a target="_blank" href="https://www.bing.com/ck/a?!&amp;&amp;p=1&amp;u=a1$b64&amp;ntb=1">Example <strong>page</strong></a></h2><div class="b_caption"><p>Apr 8, 2026&nbsp;&#0183;&#32;Some text</p></div></li></ol>"""
        val h = SearchText.parseHtml(html)
        assertEquals(1, h.size)
        assertEquals("Example page", h[0].title)
        assertEquals("https://example.com/a?b=1", h[0].url)
        assertEquals("Apr 8, 2026 · Some text", h[0].snippet)
    }

    @Test fun toolUsesRssThenFallsBack() {
        val asked = mutableListOf<String>()
        val f = object : WebFetcher {
            override fun fetch(url: String) = fetch(url, null)
            override fun fetch(url: String, userAgent: String?): FetchResult {
                asked += url
                assertNotNull(userAgent)
                return if (url.contains("news/search")) FetchResult(200, "text/xml", "<rss></rss>", url) else FetchResult(200, "text/xml", rss, url)
            }
        }
        val env = File(System.getProperty("java.io.tmpdir"), "iris1111").let { d -> ToolEnv(WorkspaceSandbox(File(d, "ws")), UndoStore(File(d, "undo")), fetcher = f) }
        val r = WebSearchTool.run(buildJsonObject { put("query", "今天的新闻") }, env)
        assertTrue(r.output, r.ok)
        assertTrue(asked[0].contains("news/search"))
        assertTrue(r.output.contains("1. 长鑫科技"))
        assertTrue(r.output.contains("https://www.msn.com/zh-cn/money/ar-AA2elX9P"))
    }

    @Test fun defaults() {
        val s = AgentSettings()
        assertTrue(s.isEnabled(WebSearchTool))
        assertEquals(Permission.ALLOW, s.permission(WebSearchTool))
        assertEquals(Permission.ASK, s.permission(WebFetchTool))
        assertEquals(Permission.ASK, s.copy(permissions = mapOf("web_search" to "ASK")).permission(WebSearchTool))
        assertNotNull(AgentTools.byName("web_search"))
    }

    @Test fun promptSaysOnline() {
        val p = AgentPrompt.build(AgentTools.all.filter { AgentSettings().isEnabled(it) })
        assertTrue(p.contains("web_search(query"))
        assertTrue(p.contains("never say you have no internet"))
        assertFalse(p.contains("fully offline"))
        val all = AgentPrompt.build(AgentTools.all, style = AgentPrompt.CallStyle.QWEN)
        assertTrue("prompt ~${TokenEstimator.estimate(all)}", TokenEstimator.estimate(all) <= 800)
        assertFalse(AgentPrompt.build(listOf(ReadFileTool)).contains("go online"))
    }

    @Test fun networkHint() {
        assertEquals(NetworkHint.CHAT_MODE, NetworkHint.forRequest("帮我找一下新闻", agentMode = false, online = false))
        assertEquals(NetworkHint.TOOLS_OFF, NetworkHint.forRequest("香港今天天气怎么样", agentMode = true, online = false))
        assertNull(NetworkHint.forRequest("帮我找一下新闻", agentMode = true, online = true))
        assertNull(NetworkHint.forRequest("写一首诗", agentMode = false, online = false))
        assertTrue(SearchText.looksLikeNews("今天有什么新闻"))
        assertFalse(SearchText.looksLikeNews("香港天气"))
    }
}

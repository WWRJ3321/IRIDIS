package com.iris.localai.agent

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.web.*
import com.iris.localai.agent.workspace.*
import com.iris.localai.agent.*
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

/** 2.0.0 浏览器搜索: Google / Bing in the shared in-app browser. */
class V200BrowserSearchTest {

    /** What the page script returns, wrapped like evaluateJavascript does (a JSON string literal). */
    private fun wrap(obj: String) = JsonPrimitive(obj).toString()

    private val googlePage = """
{"u":"https://www.google.com/search?q=iphone&hl=zh-CN&num=10","t":"iphone - Google 搜索","x":"…",
 "h":[
  {"h":"https://www.google.com/aclk?sa=l&ai=xyz","t":"买 iPhone 广告","l":["赞助商","买 iPhone 广告"]},
  {"h":"https://www.google.com/url?q=https://www.apple.com/cn/iphone/%3Fa%3D1&sa=U&ved=2a","t":"iPhone - Apple (中国大陆)","l":["Apple","https://www.apple.com › cn › iphone","iPhone - Apple (中国大陆)","探索 iPhone，全球最强大的个人设备。","翻译此页"]},
  {"h":"https://zh.wikipedia.org/wiki/IPhone","t":"iPhone - 维基百科，自由的百科全书","l":["维基百科","zh.wikipedia.org","iPhone - 维基百科，自由的百科全书","2024年9月10日 — iPhone是苹果公司设计的智能手机系列。"]},
  {"h":"https://zh.wikipedia.org/wiki/IPhone","t":"iPhone - 维基百科，自由的百科全书","l":[]},
  {"h":"https://www.google.com/search?q=iphone+16&tbm=isch","t":"图片","l":[]},
  {"h":"https://maps.google.com/maps?q=apple+store","t":"Apple Store","l":[]}
 ],
 "a":null}
"""

    @Test fun defaultsAndMigration() {
        assertEquals(SearchProvider.GOOGLE_BROWSER, AgentSettings().search)
        assertEquals(SearchProvider.GOOGLE_BROWSER, SearchProvider.parse(null))
        assertEquals(SearchProvider.BING_BROWSER, SearchProvider.parse("bing_browser"))
        // stored before 2.0 with the old default, never touched → new default
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AgentSettings.serializer(), """{"searchProvider":"AUTO"}""")
        assertEquals(SearchProvider.GOOGLE_BROWSER, old.migrated().search)
        // a deliberate choice stays
        assertEquals(SearchProvider.BING, json.decodeFromString(AgentSettings.serializer(), """{"searchProvider":"BING"}""").migrated().search)
        val chosen = AgentSettings(searchProvider = "AUTO", searchProviderChosen = true)
        assertEquals(SearchProvider.AUTO, chosen.migrated().search)
        // short picker labels
        assertEquals("谷歌", SearchProvider.GOOGLE_BROWSER.short)
        assertTrue(SearchProvider.entries.all { it.short.length <= 12 })
    }

    @Test fun urlsAndLocale() {
        assertEquals("https://www.google.com/search?q=%E5%A4%A9%E6%B0%94+%E9%A6%99%E6%B8%AF&hl=zh-CN&num=10",
            BrowserSearch.googleUrl("天气 香港", "zh-CN"))
        assertTrue(BrowserSearch.googleUrl("news", "en", news = true).endsWith("&num=10&tbm=nws"))
        assertEquals("https://www.bing.com/search?q=a+b&setlang=zh-CN", BrowserSearch.bingUrl("a b", "zh-CN"))
        assertEquals("zh-CN", BrowserSearch.hl(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-CN", BrowserSearch.hl(Locale("zh", "CN")))
        assertEquals("zh-TW", BrowserSearch.hl(Locale("zh", "HK")))
        assertEquals("zh-TW", BrowserSearch.hl(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-CN", BrowserSearch.hl(Locale.Builder().setLanguage("zh").setRegion("HK").setScript("Hans").build()))
        assertEquals("en", BrowserSearch.hl(Locale.US))
        assertEquals("ja", BrowserSearch.hl(Locale.JAPAN))
    }

    @Test fun unwrapsAndFiltersLinks() {
        assertEquals("https://example.com/a?b=1", BrowserSearch.unwrapGoogle("https://www.google.com/url?q=https://example.com/a%3Fb%3D1&sa=U"))
        assertEquals("https://example.com/", BrowserSearch.unwrapGoogle("/url?url=https%3A%2F%2Fexample.com%2F&sa=t"))
        assertEquals("https://www.google.com.hk/url?q=javascript:x", BrowserSearch.unwrapGoogle("https://www.google.com.hk/url?q=javascript:x"))
        assertEquals("https://example.com/url?q=https://x.org", BrowserSearch.unwrapGoogle("https://example.com/url?q=https://x.org"))
        assertTrue(BrowserSearch.isGoogleInternal("https://www.google.com/search?q=x"))
        assertTrue(BrowserSearch.isGoogleInternal("https://www.google.com.hk/search?q=x"))
        assertTrue(BrowserSearch.isGoogleInternal("https://maps.google.com/maps?q=x"))
        assertTrue(BrowserSearch.isGoogleInternal("https://www.googleadservices.com/pagead/aclk?x"))
        assertTrue(BrowserSearch.isGoogleInternal("https://accounts.google.com/ServiceLogin"))
        assertFalse(BrowserSearch.isGoogleInternal("https://support.google.com/mail"))
        assertFalse(BrowserSearch.isGoogleInternal("https://www.zhihu.com/question/1"))
    }

    @Test fun detectsConsentAndCaptcha() {
        val B = BrowserSearch.Block.CAPTCHA; val C = BrowserSearch.Block.CONSENT
        assertEquals(C, BrowserSearch.classify("https://consent.google.com/ml?continue=x", "", "", false))
        assertEquals(B, BrowserSearch.classify("https://www.google.com/sorry/index?continue=x", "", "", false))
        assertEquals(B, BrowserSearch.classify("https://www.google.com/search?q=x", "", "我们的系统检测到您的计算机网络中存在异常流量", false))
        assertEquals(B, BrowserSearch.classify("https://www.google.com/search?q=x", "", "Our systems have detected unusual traffic from your computer network", false))
        assertEquals(C, BrowserSearch.classify("https://www.google.com/", "Before you continue to Google", "", false))
        // a result quoting the words is not a captcha
        assertNull(BrowserSearch.classify("https://www.google.com/search?q=unusual+traffic", "", "unusual traffic", true))
        assertNull(BrowserSearch.classify("https://www.google.com/search?q=x", "x - Google", "results", false))
    }

    @Test fun parsesGoogleResults() {
        val page = BrowserSearch.parsePage(wrap(googlePage))!!
        val hits = BrowserSearch.toHits(page, SearchProvider.GOOGLE_BROWSER)
        assertEquals(2, hits.size)
        val apple = hits[0]
        assertEquals("iPhone - Apple (中国大陆)", apple.title)
        assertEquals("https://www.apple.com/cn/iphone/?a=1", apple.url)
        assertEquals("Apple", apple.source)
        assertEquals("探索 iPhone，全球最强大的个人设备。", apple.snippet)
        val wiki = hits[1]
        assertEquals("维基百科", wiki.source)
        assertEquals("2024年9月10日", wiki.date)
        assertEquals("iPhone是苹果公司设计的智能手机系列。", wiki.snippet)
        // also accepts the object itself and refuses junk
        assertNotNull(BrowserSearch.parsePage(googlePage))
        assertNull(BrowserSearch.parsePage("null"))
        assertNull(BrowserSearch.parsePage("\"not json\""))
    }

    @Test fun answerBoxComesFirst() {
        val withAnswer = googlePage.replace("\"a\":null", """"a":{"x":"iPhone 是苹果公司的智能手机。","h":"https://zh.wikipedia.org/wiki/IPhone","t":"iPhone - 维基百科，自由的百科全书"}""")
        val hits = BrowserSearch.toHits(BrowserSearch.parsePage(wrap(withAnswer))!!, SearchProvider.GOOGLE_BROWSER)
        assertEquals(2, hits.size)
        assertEquals("https://zh.wikipedia.org/wiki/IPhone", hits[0].url)
        assertEquals("iPhone 是苹果公司的智能手机。", hits[0].snippet)
        val loose = googlePage.replace("\"a\":null", """"a":{"x":"25°C 晴","h":"","t":""}""")
        val h2 = BrowserSearch.toHits(BrowserSearch.parsePage(wrap(loose))!!, SearchProvider.GOOGLE_BROWSER)
        assertEquals(3, h2.size)
        assertEquals("谷歌答案", h2[0].title); assertEquals("25°C 晴", h2[0].snippet)
    }

    @Test fun capsAtEight() {
        val many = (1..12).joinToString(",") { """{"h":"https://site$it.com/","t":"T$it","l":["T$it","s$it"]}""" }
        val hits = BrowserSearch.toHits(BrowserSearch.parsePage("""{"u":"","t":"","x":"","h":[$many]}""")!!, SearchProvider.GOOGLE_BROWSER)
        assertEquals(BrowserSearch.MAX_HITS, hits.size)
    }

    @Test fun parsesBingResults() {
        val raw = """{"u":"https://www.bing.com/search?q=x","t":"x - 搜索","x":"","h":[
 {"h":"https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS9wYWdl&ntb=1","t":"Example page","l":["Example","https://example.com › page","Example page","Some snippet text."]},
 {"h":"https://www.bing.com/search?q=related","t":"related","l":[]}]}"""
        val hits = BrowserSearch.toHits(BrowserSearch.parsePage(raw)!!, SearchProvider.BING_BROWSER)
        assertEquals(1, hits.size)
        assertEquals("https://example.com/page", hits[0].url)
        assertEquals("Some snippet text.", hits[0].snippet)
    }

    // ---------- orchestration with a fake browser ----------

    private class FakeBrowser(val pages: (String) -> String, val afterConsent: String? = null) : SearchBrowser {
        val log = mutableListOf<String>()
        var current = ""
        override fun <T> withSearchPage(block: (SearchPageOps) -> T): T = block(object : SearchPageOps {
            override fun load(url: String) { log += "load $url"; current = pages(url) }
            override fun eval(script: String): String? { log += "eval"; return JsonPrimitive(current).toString() }
            override fun evalAndSettle(script: String): String? { log += "click"; afterConsent?.let { current = it }; return "\"ok\"" }
            override fun pause(ms: Long) { log += "pause" }
        })
    }

    @Test fun searchReadsGoogle() {
        val b = FakeBrowser({ googlePage })
        val o = BrowserSearch.search(b, SearchProvider.GOOGLE_BROWSER, "iphone", false, Locale.SIMPLIFIED_CHINESE)
        assertEquals("谷歌（浏览器）", o.engine)
        assertEquals(2, o.hits.size); assertNull(o.block)
        assertEquals("load https://www.google.com/search?q=iphone&hl=zh-CN&num=10", b.log.first())
    }

    @Test fun consentIsAcceptedOnce() {
        val consent = """{"u":"https://consent.google.com/ml?continue=x","t":"Before you continue to Google","x":"","h":[]}"""
        val b = FakeBrowser({ consent }, afterConsent = googlePage)
        val o = BrowserSearch.search(b, SearchProvider.GOOGLE_BROWSER, "iphone", false, Locale.US)
        assertEquals(2, o.hits.size)
        assertEquals(1, b.log.count { it == "click" })
        // consent that never goes away → blocked with a notice, one click only
        val stuck = FakeBrowser({ consent }, afterConsent = consent)
        val s = BrowserSearch.search(stuck, SearchProvider.GOOGLE_BROWSER, "iphone", false, Locale.US)
        assertEquals(BrowserSearch.Block.CONSENT, s.block); assertTrue(s.hits.isEmpty())
        assertEquals(1, stuck.log.count { it == "click" })
    }

    @Test fun captchaGivesNotice() {
        val sorry = """{"u":"https://www.google.com/sorry/index?continue=x","t":"","x":"unusual traffic","h":[]}"""
        val o = BrowserSearch.search(FakeBrowser({ sorry }), SearchProvider.GOOGLE_BROWSER, "x", false)
        assertEquals(BrowserSearch.Block.CAPTCHA, o.block)
        assertEquals(BrowserSearch.GOOGLE_CAPTCHA, o.notice)
    }

    @Test fun newsFallsBackToWebResults() {
        val empty = """{"u":"https://www.google.com/search?q=x&tbm=nws","t":"","x":"","h":[]}"""
        val b = FakeBrowser({ u -> if (u.contains("tbm=nws")) empty else googlePage })
        val o = BrowserSearch.search(b, SearchProvider.GOOGLE_BROWSER, "x 新闻", true, Locale.US)
        assertEquals(2, o.hits.size)
        assertEquals(2, b.log.count { it.startsWith("load") })
    }

    @Test fun busyOrBrokenBrowserNeverThrows() {
        val busy = object : SearchBrowser { override fun <T> withSearchPage(block: (SearchPageOps) -> T): T = throw IllegalStateException("浏览器正忙") }
        assertTrue(BrowserSearch.search(busy, SearchProvider.GOOGLE_BROWSER, "x", false).hits.isEmpty())
    }

    // ---------- the tool ----------

    private fun env(browser: SearchBrowser?, fetcher: WebFetcher?, provider: SearchProvider = SearchProvider.GOOGLE_BROWSER): ToolEnv {
        val d = File(System.getProperty("java.io.tmpdir"), "iris200-" + System.nanoTime())
        return ToolEnv(WorkspaceSandbox(File(d, "ws")), UndoStore(File(d, "undo")), fetcher = fetcher, searchProvider = provider, searchBrowser = browser)
    }

    private val bingHtml = object : WebFetcher {
        val asked = mutableListOf<String>()
        override fun fetch(url: String) = fetch(url, null)
        override fun fetch(url: String, userAgent: String?): FetchResult {
            asked += url
            return if (url.contains("bing.com") && url.contains("format=rss"))
                FetchResult(200, "text/xml", "<rss><channel><item><title>Bing hit</title><link>https://b.example/</link><description>d</description></item></channel></rss>", url)
            else FetchResult(500, null, "", url)
        }
    }

    @Test fun toolUsesGoogleBrowser() {
        val r = WebSearchTool.run(buildJsonObject { put("query", "iphone") }, env(FakeBrowser({ googlePage }), bingHtml))
        assertTrue(r.output, r.ok)
        assertEquals("谷歌（浏览器） · 2 条结果", r.summary)
        assertTrue(r.output.contains("https://www.apple.com/cn/iphone/?a=1"))
        assertTrue(r.output.contains("(Apple)"))
    }

    @Test fun toolFallsBackToBingOnCaptcha() {
        val sorry = """{"u":"https://www.google.com/sorry/index","t":"","x":"","h":[]}"""
        val r = WebSearchTool.run(buildJsonObject { put("query", "iphone") }, env(FakeBrowser({ sorry }), bingHtml))
        assertTrue(r.output, r.ok)
        assertTrue(r.output.startsWith(BrowserSearch.GOOGLE_CAPTCHA))
        assertTrue(r.output.contains("https://b.example/"))
        assertEquals("必应 · 1 条结果 · 谷歌需人机验证", r.summary)
    }

    @Test fun toolWithoutBrowserUsesHttp() {
        val r = WebSearchTool.run(buildJsonObject { put("query", "iphone") }, env(null, bingHtml))
        assertTrue(r.ok); assertTrue(r.summary.startsWith("必应 · "))
    }

    @Test fun promptStaysSmall() {
        val all = AgentPrompt.build(AgentTools.all, style = AgentPrompt.CallStyle.QWEN)
        assertTrue("prompt ~${TokenEstimator.estimate(all)}", TokenEstimator.estimate(all) <= 800)
        assertTrue(WebSearchTool.description.length <= "用必应搜索网页或新闻，返回前几条结果的标题、摘要和链接（只发送搜索词）".length)
    }
}

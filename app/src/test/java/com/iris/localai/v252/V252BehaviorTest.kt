package com.iris.localai.v252

import com.iris.localai.agent.HtmlText
import com.iris.localai.agent.SearchText
import com.iris.localai.agent.WebText
import com.iris.localai.agent.capTokens
import com.iris.localai.data.ModelDirs
import com.iris.localai.imagegen.ImageSeed
import com.iris.localai.ui.welcome.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** 2.5.2 纯函数行为：网页正文 / 字符集 / 实体 / 跳转解包 / 截断；生图种子；新手引导翻页；模型存储位置。 */
class V252BehaviorTest {

    // ---------------------------------------------------------------- 网页正文
    private val page = """<html><head><title>示例 &amp; 新闻</title><style>.a{color:red}</style></head><body>
<header class="site-header"><a href="/">Home</a> <a href="/n">News</a></header>
<nav><ul><li><a href="/">首页</a><li><a href="/x">新闻</a></ul></nav>
<div id="cookie-banner">We use cookies <button>Accept all</button></div>
<main><article><h1>标题 &mdash; 新闻</h1><p>第一段<b>正文</b>内容，很长很长。</p><p>第二段 &#8220;引号&#8221; &amp;lt;b&amp;gt; 只解一层</p>
<div class="share-bar">分享到 微博</div><aside>相关阅读</aside><p hidden>隐藏段落</p><p style="display:none">也隐藏</p>
<p>${"正文填充".repeat(60)}</p><p>${"正文填充".repeat(60)}</p></article></main>
<div class="sidebar"><p>侧栏推荐</p></div><footer>© 2026 页脚</footer>
<template><p>模板文本</p></template><noscript>请开启 JS</noscript><svg><text>svg 字</text></svg>
<script>var evil = 1</script><script>unclosed evil"""

    @Test fun readablePrefersMainTextAndDropsBoilerplate() {
        val t = WebText.readable(page)
        assertTrue(t, t.startsWith("标题 — 新闻"))
        assertTrue(t.contains("第一段正文内容，很长很长。"))
        assertTrue(t.contains("第二段 “引号” &lt;b&gt; 只解一层"))
        for (bad in listOf("Home", "首页", "cookies", "Accept", "分享到", "相关阅读", "隐藏", "侧栏", "页脚", "模板文本", "请开启", "svg 字", "evil", "color:red")) {
            assertFalse(bad, t.contains(bad))
        }
        // repeated paragraph kept once
        assertEquals(1, t.lines().count { it.startsWith("正文填充") })
        assertEquals("示例 & 新闻", WebText.title(page))
    }

    @Test fun readableFallsBackToBodyWithoutMain() {
        val html = "<html><body><h1>标题</h1><p>正文 &amp; 更多</p><footer>页脚</footer></body></html>"
        val t = WebText.readable(html)
        assertTrue(t.contains("标题") && t.contains("正文 & 更多"))
        assertFalse(t.contains("页脚"))
        // a page that is all "nav" still gives its text instead of nothing
        val allNav = "<body><div class=\"nav\">" + "<p>只有这一段文字在导航容器里，但这是页面唯一的内容。</p>".repeat(4) + "</div></body>"
        assertTrue(WebText.readable(allNav).contains("唯一的内容"))
    }

    @Test fun contentWrapperNamedLikeBoilerplateIsKept() {
        // class mentions "share" / "nav" but the element holds the article: kept (3+ paragraphs)
        val html = "<body><div class=\"post share-enabled\"><p>第一段内容。</p><p>第二段内容。</p><p>第三段内容。</p>" +
            "<div class=\"share\">分享按钮</div></div><nav>导航</nav></body>"
        val t = WebText.readable(html)
        assertTrue(t, t.contains("第一段内容。") && t.contains("第三段内容。"))
        assertFalse(t.contains("分享按钮") || t.contains("导航"))
    }

    @Test fun toTextDropsCodeAndKeepsInlineWordsTogether() {
        assertEquals("北京天气 晴", HtmlText.toText("<p><b>北京</b>天气 <span>晴</span></p>"))
        assertEquals("a\nb", HtmlText.toText("<div>a</div><script>x()</script><div>b</div>"))
        assertEquals("ok", HtmlText.toText("<p>ok</p><script>never closed"))
        assertEquals("ok", HtmlText.toText("<p>ok</p><style>.x{}"))
        assertEquals("x y", HtmlText.toText("<p>x\u200B\u00A0 y</p>"))
    }

    @Test fun entitiesDecodedOnce() {
        assertEquals("— … ’ © € – ·", WebText.decodeEntities("&mdash; &hellip; &#8217; &copy; &#x20AC; &#150; &#183;"))
        assertEquals("&lt;", WebText.decodeEntities("&amp;lt;"))
        assertEquals("&unknown;", WebText.decodeEntities("&unknown;"))
        assertEquals("a b", WebText.decodeEntities("a&nbsp;b"))
        assertEquals("Example & Co", SearchText.clean("Example &amp; Co"))
        assertEquals("&#39;", SearchText.clean("&amp;#39;"))
    }

    // ---------------------------------------------------------------- 字符集
    @Test fun charsetFromHeaderMetaBom() {
        val zh = "中文网页"
        val gbk = "<html><head><meta charset=\"gb2312\"></head><body>$zh</body></html>".toByteArray(charset("GBK"))
        assertTrue(WebText.decode(gbk, "text/html").contains(zh))
        val gbk2 = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=GBK\"><p>$zh</p>".toByteArray(charset("GBK"))
        assertTrue(WebText.decode(gbk2, null).contains(zh))
        // header wins over meta; parameter name in any case
        assertTrue(WebText.decode("<p>$zh</p>".toByteArray(charset("GBK")), "text/html; Charset=\"GBK\"").contains(zh))
        // BOM wins and is removed
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<p>$zh</p>".toByteArray(Charsets.UTF_8)
        assertEquals("<p>$zh</p>", WebText.decode(bom, "text/html; charset=iso-8859-1"))
        // nothing declared → UTF-8; Latin-1 label → windows-1252
        assertEquals("<p>$zh</p>", WebText.decode("<p>$zh</p>".toByteArray(Charsets.UTF_8), "text/html"))
        assertEquals("windows-1252", WebText.charsetFor("ISO-8859-1")!!.name())
        assertEquals("GB18030", WebText.charsetFor("gb2312")!!.name())
        assertNull(WebText.charsetFor("no-such-charset"))
    }

    // ---------------------------------------------------------------- 跳转链接
    @Test fun unwrapsEngineRedirects() {
        assertEquals("https://example.com/a", WebText.unwrapRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&amp;rut=x"))
        assertEquals("https://example.com/a", WebText.unwrapRedirect("/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=x"))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.com/p?q=1".toByteArray())
        assertEquals("https://example.com/p?q=1", WebText.unwrapRedirect("https://www.bing.com/ck/a?!&&p=1&u=a1$b64&ntb=1"))
        assertEquals("https://www.msn.com/x", WebText.unwrapRedirect("http://www.bing.com/news/apiclick.aspx?ref=Rss&url=https%3a%2f%2fwww.msn.com%2fx&c=1"))
        assertEquals("https://example.org/", WebText.unwrapRedirect("https://www.google.com/url?q=https://example.org/&sa=U"))
        // a link that does not decode to http(s) stays as it was
        assertEquals("https://www.bing.com/ck/a?u=a1bad", WebText.unwrapRedirect("https://www.bing.com/ck/a?u=a1bad"))
        assertTrue(WebText.isEngineLink("https://duckduckgo.com/y.js?ad_domain=x.com&u3=y"))
        assertTrue(WebText.isEngineLink("https://www.bing.com/ck/a?u=a1bad"))
        assertFalse(WebText.isEngineLink("https://example.com/a|b"))
        assertFalse(WebText.isEngineLink("https://duckduckgo.com/about"))
    }

    @Test fun ddgAdsAreNotResults() {
        val html = """<a rel="nofollow" href="https://duckduckgo.com/y.js?ad_domain=ads.com&amp;u3=x" class='result-link'>Ad</a>
<td class='result-snippet'>ad</td><a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Freal.com%2F&amp;rut=x" class='result-link'>Real</a><td class='result-snippet'>ok</td>"""
        val h = SearchText.parseDdgLite(html)
        assertEquals(listOf("https://real.com/"), h.map { it.url })
    }

    // ---------------------------------------------------------------- 截断
    @Test fun cutsNeverSplitACharacter() {
        val emoji = "a".repeat(9) + "😀" + "b"
        assertEquals(9, WebText.cutIndex(emoji, 10)) // 10 would split the surrogate pair
        val s = "First sentence. Second sentence goes on and on"
        assertEquals("First sentence.…", WebText.clip(s, 30))
        val words = "alpha beta gamma delta"
        assertEquals("alpha beta…", WebText.clip(words, 14))
        assertEquals("短", WebText.clip("短", 10))
        val capped = capTokens(("这是一句话。").repeat(2000), 300)
        assertTrue(capped.substringBefore("\n[TRUNCATED").endsWith("。"))
    }

    // ---------------------------------------------------------------- 生图种子
    @Test fun seedEmptyIsRandomEveryTimeFixedStays() {
        val r = Random(42)
        val a = ImageSeed.pick("", r); val b = ImageSeed.pick("", r); val c = ImageSeed.pick("  ", r)
        assertTrue(a >= 0 && b >= 0 && c >= 0)
        assertTrue(a != b || b != c)
        assertEquals(1234L, ImageSeed.pick("1234", r)); assertEquals(1234L, ImageSeed.pick(" 1234 ", r))
        assertTrue(ImageSeed.isRandom("")); assertTrue(ImageSeed.isRandom("abc")); assertFalse(ImageSeed.isRandom("0"))
        assertEquals("123", ImageSeed.sanitize("1a2-3"))
        assertEquals(18, ImageSeed.sanitize("9".repeat(30)).length)
    }

    // ---------------------------------------------------------------- 新手引导
    @Test fun onboardingPaging() {
        assertTrue(Onboarding.shouldShow(welcomeAccepted = true, onboardingDone = false))
        assertFalse(Onboarding.shouldShow(welcomeAccepted = false, onboardingDone = false))
        assertFalse(Onboarding.shouldShow(welcomeAccepted = true, onboardingDone = true))
        assertEquals(1, Onboarding.next(0)); assertNull(Onboarding.next(Onboarding.PAGES - 1))
        assertNull(Onboarding.prev(0)); assertEquals(0, Onboarding.prev(1))
        assertTrue(Onboarding.isLast(4)); assertFalse(Onboarding.isLast(3))
    }

    // ---------------------------------------------------------------- 模型存储位置
    @Test fun modelsFollowTheStorageLocation() {
        val tmp = Files.createTempDirectory("v252dirs").toFile()
        val dl = File(tmp, "Download"); val priv = File(tmp, "Android/data/models")
        assertTrue(ModelDirs.followsStorage(File(dl, "IRIDIS"), dl))
        assertTrue(ModelDirs.followsStorage(File(dl, "IRIDIS/"), dl))
        assertFalse(ModelDirs.followsStorage(File(tmp, "files/iridis"), dl))
        assertFalse(ModelDirs.followsStorage(null, dl))
        // without 全部文件访问: the old rule keeps the private folder; following the storage location uses Download/IRIDIS/models
        assertEquals(priv, ModelDirs.resolve(false, "mounted", dl, priv).dir)
        val follow = ModelDirs.resolve(false, "mounted", dl, priv, followStorage = true)
        assertTrue(follow.shared); assertEquals(File(dl, "IRIDIS/models").absolutePath, follow.dir.absolutePath)
        // not writable / not mounted → private, so nothing is lost
        assertEquals(priv, ModelDirs.resolve(false, "mounted", dl, priv, usable = { false }, followStorage = true).dir)
        assertEquals(priv, ModelDirs.resolve(false, "unmounted", dl, priv, followStorage = true).dir)
        tmp.deleteRecursively()
    }
}

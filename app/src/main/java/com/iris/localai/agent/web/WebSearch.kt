package com.iris.localai.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * 1.12.0: which search service web_search uses (设置 › 智能体 › 联网搜索). 2.0.0: GOOGLE_BROWSER
 * (the default) and BING_BROWSER search in the in-app browser like on the phone; [short] is the
 * picker's label.
 */
enum class SearchProvider(val label: String, val short: String) {
    GOOGLE_BROWSER("谷歌（浏览器）：像在手机上搜索，验证失败时改用必应", "谷歌（浏览器）"),
    BING_BROWSER("必应（浏览器）：内置浏览器打开必应", "必应（浏览器）"),
    AUTO("自动（必应，失败时 DuckDuckGo）", "自动"),
    BING("必应", "必应"),
    DUCKDUCKGO("DuckDuckGo", "DuckDuckGo"),
    SEARXNG("自建 SearXNG", "自建 SearXNG");

    companion object {
        val DEFAULT = GOOGLE_BROWSER
        fun parse(s: String?): SearchProvider = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: DEFAULT
    }
}

/** 1.11.1: one search hit. */
data class SearchHit(val title: String, val url: String, val snippet: String, val source: String = "", val date: String = "")

/**
 * 1.11.1 `web_search`: the agent's way online for news, weather, prices and anything recent.
 * Reads Bing's RSS results (web, or news when the query asks for news), falling back to the
 * HTML result page. Read-only (only the query leaves the phone), on and allowed by default, so a
 * small model asked "找一下新闻" no longer answers that it has no internet.
 */
object WebSearchTool : AgentTool {
    override val group = ToolGroup.WEB
    override val name = "web_search"
    override val label = "联网搜索"
    override val description = "搜索网页或新闻，返回前几条结果的标题、摘要和链接"
    override val promptLine = "web_search(query, news?: bool) | web_search(url): read one page"
    override val risk = ToolRisk.NETWORK
    override val defaultPermission: Permission = Permission.ALLOW

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        // 2.2.2: one web tool — a url reads that page (the former web_fetch).
        if (Args.str(args, "url", "link", "href") != null && Args.str(args, "query", "q") == null) return com.iris.localai.agent.WebFetchTool.run(args, env)
        val q = query(args)?.trim()
            ?: throw IllegalArgumentException("缺少参数 query")
        if (q.isEmpty()) throw IllegalArgumentException("缺少参数 query")
        val news = Args.bool(args, "news") ?: SearchText.looksLikeNews(q)
        // 2.0.0: 谷歌/必应（浏览器） search in the shared in-app browser; a captcha (or no readable
        // results) falls back to Bing over HTTP for this query, with the reason in the output.
        var notice: String? = null
        var blocked: String? = null
        if (BrowserSearch.isBrowser(env.searchProvider) && env.searchBrowser != null) {
            val o = BrowserSearch.search(env.searchBrowser, env.searchProvider, q, news)
            if (o.hits.isNotEmpty()) return ok(q, o.engine, o.hits, null, env)
            notice = o.notice ?: "Note: ${o.engine} gave no readable results"
            blocked = when (o.block) {
                BrowserSearch.Block.CAPTCHA -> "需人机验证"
                BrowserSearch.Block.CONSENT -> "需手动同意"
                null -> "无结果"
            }.let { o.engine.substringBefore('（') + it }
        }
        val fetcher = env.fetcher ?: return ToolResult(false, "Error: network is not available" + (notice?.let { " ($it)" } ?: ""), blocked ?: "不可用")
        val http = if (BrowserSearch.isBrowser(env.searchProvider)) SearchProvider.AUTO else env.searchProvider
        val (engine, hits) = SearchText.search(fetcher, q, news, http, env.searxUrl)
        if (hits.isEmpty()) return ToolResult(false, "Error: no results for \"$q\" (try other keywords)" + (notice?.let { "\n$it" } ?: ""), blocked ?: "没有结果")
        return ok(q, engine, hits, notice?.let { if (isZh(it)) "$it（本次改用$engine）" else "$it; used $engine instead." }, env, blocked)
    }

    private fun isZh(s: String) = s.any { it.code > 0x2E80 }

    private val QUERY_KEYS = arrayOf("query", "q", "keywords", "keyword", "text", "search")

    /** 2.1.8: the query; a list of words (`"query": ["三体", "小说"]`) is joined with spaces. */
    internal fun query(args: JsonObject): String? = QUERY_KEYS.firstNotNullOfOrNull { k ->
        when (val v = args[k]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.trim() }
                .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
            is JsonPrimitive -> v.takeIf { it !is JsonNull }?.content
            else -> null
        }
    }

    private fun ok(q: String, engine: String, hits: List<SearchHit>, notice: String?, env: ToolEnv, blocked: String? = null): ToolResult {
        val text = (notice?.let { "$it\n" } ?: "") + SearchText.render(q, hits)
        val summary = "$engine · ${hits.size} 条结果" + (blocked?.let { " · $it" } ?: "")
        return ToolResult(true, com.iris.localai.agent.capTokens(text, env.outputTokens), summary)
    }
}

/** Pure helpers of web_search (unit-tested on saved pages). */
object SearchText {
    const val MAX_HITS = 5
    const val SNIPPET_CHARS = 160
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private val NEWS = Regex("(?i)新闻|新聞|头条|頭條|热点|熱點|最新消息|今日要闻|\\bnews\\b|headlines?")

    fun looksLikeNews(q: String): Boolean = NEWS.containsMatchIn(q)

    fun rssUrl(q: String, news: Boolean): String {
        val e = URLEncoder.encode(q, "UTF-8")
        return if (news) "https://www.bing.com/news/search?q=$e&format=rss" else "https://www.bing.com/search?format=rss&q=$e"
    }

    fun htmlUrl(q: String): String = "https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8")

    /** 1.11.1 behaviour: Bing only. */
    fun search(f: WebFetcher, q: String, news: Boolean): List<SearchHit> = search(f, q, news, SearchProvider.AUTO, "").second

    /**
     * 1.12.0: searches with [provider]; AUTO tries Bing, then DuckDuckGo. Returns the provider that
     * answered (label) and its hits.
     */
    fun search(f: WebFetcher, q: String, news: Boolean, provider: SearchProvider, searxUrl: String): Pair<String, List<SearchHit>> {
        fun get(u: String): String? = runCatching { f.fetch(u, UA) }.getOrNull()?.takeIf { it.status in 200..299 }?.body
        fun bing(): List<SearchHit> {
            var hits = get(rssUrl(q, news))?.let { parseRss(it) }.orEmpty()
            if (hits.isEmpty() && news) hits = get(rssUrl(q, false))?.let { parseRss(it) }.orEmpty()
            if (hits.isEmpty()) hits = get(htmlUrl(q))?.let { parseHtml(it) }.orEmpty()
            return hits
        }
        fun ddg(): List<SearchHit> = get(ddgUrl(q))?.let { parseDdgLite(it) }.orEmpty()
        fun searx(): List<SearchHit> = searxApi(searxUrl, q)?.let { u -> get(u)?.let { parseSearx(it) } }.orEmpty()
        val order: List<Pair<String, () -> List<SearchHit>>> = when (provider) {
            SearchProvider.BING -> listOf("必应" to ::bing)
            SearchProvider.DUCKDUCKGO -> listOf("DuckDuckGo" to ::ddg)
            SearchProvider.SEARXNG -> listOf("SearXNG" to ::searx, "必应" to ::bing)
            // the browser providers reach here only as the HTTP fallback (no browser / captcha)
            SearchProvider.AUTO, SearchProvider.GOOGLE_BROWSER, SearchProvider.BING_BROWSER -> listOf("必应" to ::bing, "DuckDuckGo" to ::ddg)
        }
        for ((label, run) in order) {
            val hits = runCatching { run() }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return label to hits.take(MAX_HITS)
        }
        return (order.firstOrNull()?.first ?: "") to emptyList()
    }

    fun ddgUrl(q: String): String = "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(q, "UTF-8")

    /** `https://searx.example` → its JSON API url, or null when not http(s). */
    fun searxApi(base: String, q: String): String? {
        val b = base.trim().trimEnd('/')
        if (!b.startsWith("http://") && !b.startsWith("https://")) return null
        return "$b/search?format=json&q=" + URLEncoder.encode(q, "UTF-8")
    }

    private val DDG_LINK = Regex("(?s)<a[^>]*href=[\"']([^\"']+)[\"'][^>]*class=[\"']result-link[\"'][^>]*>(.*?)</a>|<a[^>]*class=[\"']result-link[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
    private val DDG_SNIPPET = Regex("(?s)<td[^>]*class=[\"']result-snippet[\"'][^>]*>(.*?)</td>")

    fun parseDdgLite(html: String): List<SearchHit> {
        val links = DDG_LINK.findAll(html).toList()
        val snippets = DDG_SNIPPET.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            val href = decode(m.groupValues[1].ifEmpty { m.groupValues[3] })
            val title = clean(m.groupValues[2].ifEmpty { m.groupValues[4] })
            val url = ddgRealUrl(href)
            // 2.5.2: ads (duckduckgo.com/y.js) and links that did not unwrap are not results
            if (title.isEmpty() || !url.startsWith("http") || WebText.isEngineLink(url)) null
            else SearchHit(title, url, WebText.clip(snippets.getOrNull(i).orEmpty(), SNIPPET_CHARS))
        }.distinctBy { it.url }
    }

    /** `//duckduckgo.com/l/?uddg=<encoded>&rut=…` (also relative `/l/?uddg=`) → the real url. 2.5.2: [WebText.unwrapRedirect]. */
    fun ddgRealUrl(href: String): String = WebText.unwrapRedirect(href)

    fun parseSearx(json: String): List<SearchHit> = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val arr = root["results"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val url = str("url"); val title = clean(str("title"))
            if (url.isEmpty() || title.isEmpty()) null else SearchHit(title, url, WebText.clip(clean(str("content")), SNIPPET_CHARS), str("engine"))
        }.distinctBy { it.url }
    }.getOrDefault(emptyList())

    private val ITEM = Regex("(?s)<item>(.*?)</item>")
    private fun tag(s: String, name: String): String =
        Regex("(?s)<$name>(.*?)</$name>").find(s)?.groupValues?.get(1)?.let { cdata(it) }.orEmpty()
    private fun cdata(s: String) = s.trim().removePrefix("<![CDATA[").removeSuffix("]]>")

    fun parseRss(xml: String): List<SearchHit> = ITEM.findAll(xml).mapNotNull { m ->
        val it = m.groupValues[1]
        val title = clean(tag(it, "title"))
        val url = realUrl(decode(tag(it, "link")))
        if (title.isEmpty() || url.isEmpty() || WebText.isEngineLink(url)) return@mapNotNull null
        SearchHit(title, url, WebText.clip(clean(tag(it, "description")), SNIPPET_CHARS), clean(tag(it, "News:Source")), shortDate(tag(it, "pubDate")))
    }.distinctBy { it.url }.toList()

    private val ALGO = Regex("(?s)<li class=\"b_algo\"(.*?)</li>")
    private val H2A = Regex("(?s)<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
    private val PARA = Regex("(?s)<p[^>]*>(.*?)</p>")

    fun parseHtml(html: String): List<SearchHit> = ALGO.findAll(html).mapNotNull { m ->
        val b = m.groupValues[1]
        val a = H2A.find(b) ?: return@mapNotNull null
        val url = realUrl(decode(a.groupValues[1]))
        val title = clean(a.groupValues[2])
        if (title.isEmpty() || !url.startsWith("http") || WebText.isEngineLink(url)) return@mapNotNull null
        SearchHit(title, url, WebText.clip(clean(PARA.find(b)?.groupValues?.get(1).orEmpty()), SNIPPET_CHARS))
    }.distinctBy { it.url }.toList()

    /** Unwraps Bing's click-tracking links (`/ck/a?…&u=a1<base64url>`, `apiclick.aspx?…&url=`). */
    fun realUrl(u: String): String = WebText.unwrapRedirect(u) // 2.5.2: one unwrapper (checks the decoded target is http/https)

    private fun decode(s: String) = s.replace("&amp;", "&")

    /** 2.5.2: entities are decoded once inside [HtmlText.toText] (a second pass turned "&amp;#39;" into "'"). */
    fun clean(s: String): String = HtmlText.toText(cdata(s)).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    /** "Fri, 16 Oct 2026 00:51:55 GMT" → "16 Oct 2026". */
    fun shortDate(s: String): String = Regex("\\d{1,2} \\w{3} \\d{4}").find(s)?.value.orEmpty()

    fun render(q: String, hits: List<SearchHit>): String = buildString {
        // 2.1.7: tells the model to answer from the snippets (it used to read "read a page for
        // details" as the next step and kept fetching until the step limit).
        append("Search results for \"").append(q).append("\" (answer from these, citing them inline as [1], [2]; web_fetch a url only if more detail is needed):\n")
        hits.forEachIndexed { i, h ->
            append(i + 1).append(". ").append(h.title)
            val meta = listOf(h.source, h.date).filter { it.isNotBlank() }.joinToString(", ")
            if (meta.isNotEmpty()) append(" (").append(meta).append(')')
            append('\n')
            if (h.snippet.isNotBlank()) append("   ").append(h.snippet).append('\n')
            append("   ").append(h.url).append('\n')
        }
    }.trimEnd()

    /** 1.11.1: a request that needs the internet (news, weather, prices, "search …"). */
    private val NEEDS_WEB = Regex("(?i)新闻|新聞|头条|热搜|天气|天氣|气温|汇率|匯率|股价|股價|比分|最新|今天.*(发生|消息)|搜一下|搜索|搜搜|查一下.*(网|网上)|上网|联网|\\bnews\\b|weather|search the web|latest")

    fun needsWeb(text: String): Boolean = NEEDS_WEB.containsMatchIn(text)

    /** The tools that can reach the internet. */
    val NETWORK_TOOLS = setOf("web_search", "web_fetch", "browser")
}

/** 1.11.1: the notice shown when the user asks for something online that the current mode cannot reach. Pure. */
object NetworkHint {
    const val CHAT_MODE = "对话模式不能联网：点输入框左上角的「智能体」即可搜索新闻、天气等最新信息"
    const val TOOLS_OFF = "联网工具都已关闭，模型无法上网：可在 设置 › 智能体 › 工具与权限 里开启「联网搜索」"

    fun forRequest(text: String, agentMode: Boolean, online: Boolean): String? = when {
        !SearchText.needsWeb(text) -> null
        !agentMode -> CHAT_MODE
        !online -> TOOLS_OFF
        else -> null
    }
}

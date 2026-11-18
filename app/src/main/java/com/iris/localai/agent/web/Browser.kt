package com.iris.localai.agent.web

import com.iris.localai.util.TokenEstimator
import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.workspace.*
import kotlinx.serialization.json.JsonObject

/** one look at a page: what the model reads. */
data class PageSnapshot(val title: String, val url: String, val lines: List<String>)

/** The browser the `browser` tool drives; injected so the tool stays testable on the JVM. */
interface BrowserDriver {
    fun open(url: String): PageSnapshot
    fun snapshot(): PageSnapshot
    /** Clicks element [id] (the number shown as `[id]`); null = no such element. */
    fun click(id: Int): PageSnapshot?
    /** Types into element [id]; [submit] presses enter / submits its form. null = no such element. */
    fun type(id: Int, text: String, submit: Boolean): PageSnapshot?
    fun back(): PageSnapshot
}

/**
 * 1.11.0 `browser`: a real in-app browser (WebView: JavaScript, cookies, logins) for the agent.
 * Pages are shown as text with numbered elements (`[3] link "新闻"`, `[7] input:search "搜索"`), so
 * a 4B model with a 4096-token context can read and act on them. Long pages come in parts
 * (`page` 1, 2, …). Off by default; asks before each call by default (it can submit forms).
 */
object BrowserTool : AgentTool {
    override val group = ToolGroup.WEB
    override val name = "browser"
    override val label = "浏览器"
    override val description = "在内置浏览器里打开网页、点击、输入（支持 JavaScript 和登录；默认关闭，开启后每次询问）"
    override val promptLine = "browser(action: open|read|click|type|back, url?, id?, text?): login/click pages; url may be keywords"
    override val risk = ToolRisk.NETWORK
    override val defaultEnabled = false

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val b = env.browser ?: return ToolResult(false, "Error: browser not available", "不可用")
        val action = (Args.str(args, "action", "op", "do") ?: if (Args.str(args, "url") != null) "open" else "read").trim().lowercase()
        val page = (Args.int(args, "page", "part") ?: 1).coerceAtLeast(1)
        val id = Args.int(args, "id", "element", "index", "ref")
        val snap: PageSnapshot = when (action) {
            "open", "goto", "navigate", "visit" -> {
                val url = Args.str(args, "url", "link", "href") ?: throw IllegalArgumentException("缺少参数 url")
                val target = BrowserText.normalizeUrl(url)
                // local names / private IP literals are refused here without DNS; names are
                // resolved and checked by the real browser (WebViewBrowser) before loading.
                if (NetGuard.obviouslyLocal(target)) return ToolResult(false, "Error: ${NetGuard.REFUSED}", NetGuard.REFUSED)
                b.open(target)
            }
            "read", "snapshot", "look", "scroll", "next" -> b.snapshot()
            "click", "tap", "press" -> b.click(id ?: throw IllegalArgumentException("缺少参数 id"))
                ?: return ToolResult(false, "Error: no element [$id] on this page; read it again", "找不到元素 [$id]")
            "type", "fill", "input" -> {
                val text = Args.str(args, "text", "value", "content", "query") ?: throw IllegalArgumentException("缺少参数 text")
                val submit = Args.bool(args, "submit", "enter") ?: false
                b.type(id ?: throw IllegalArgumentException("缺少参数 id"), text, submit)
                    ?: return ToolResult(false, "Error: no element [$id] on this page; read it again", "找不到元素 [$id]")
            }
            "back" -> b.back()
            else -> return ToolResult(false, "Error: unknown action $action (open, read, click, type, back)", "未知操作")
        }
        val text = BrowserText.render(snap, page, env.outputTokens)
        return ToolResult(true, text, snap.title.ifBlank { snap.url }.take(40))
    }
}

/** Pure helpers of the browser tool (unit-tested). */
object BrowserText {
    fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        return when {
            u.startsWith("http://", true) || u.startsWith("https://", true) -> u
            u.contains('.') && !u.contains(' ') -> "https://$u"
            else -> "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(u, "UTF-8")
        }
    }

    /** Splits the page into parts of about [tokens] and renders part [page] with a header. */
    fun render(s: PageSnapshot, page: Int, tokens: Int): String {
        val budget = (tokens - 60).coerceAtLeast(120)
        val parts = mutableListOf<StringBuilder>()
        var cur = StringBuilder()
        for (line in s.lines) {
            val l = if (line.length > 400) line.take(400) + "…" else line
            if (cur.isNotEmpty() && TokenEstimator.estimate(cur.toString() + l) > budget) {
                parts += cur; cur = StringBuilder()
            }
            cur.append(l).append('\n')
        }
        if (cur.isNotEmpty() || parts.isEmpty()) parts += cur
        val p = page.coerceIn(1, parts.size)
        return buildString {
            append("[").append(s.title.ifBlank { "(no title)" }).append(" — ").append(s.url).append("]")
            if (parts.size > 1) append(" part $p/${parts.size}")
            append('\n')
            append(parts[p - 1].toString().trimEnd().ifEmpty { "(empty page)" })
            if (p < parts.size) append("\n…more: browser(action: read, page: ${p + 1})")
        }
    }
}

package com.iris.localai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * 2.0.0 浏览器搜索: the shared in-app browser ([WebViewBrowser]) as a search engine, so web_search
 * works like searching on the phone (same cookies as the 浏览器 page: a consent or captcha solved
 * there once stays solved). Injected so the search stays testable on the JVM.
 */
interface SearchBrowser {
    /** Runs [block] holding the shared browser exclusively (the `browser` tool waits meanwhile). */
    fun <T> withSearchPage(block: (SearchPageOps) -> T): T
}

/** What a search may do with the page while it holds the browser. Blocking; never on the main thread. */
interface SearchPageOps {
    /** Navigates (public hosts only) and waits for the page to settle. */
    fun load(url: String)
    /** Runs [script] on the current page; the raw evaluateJavascript result, null on timeout. */
    fun eval(script: String): String?
    /** Runs [script] (e.g. a click) and waits for any navigation it starts to settle. */
    fun evalAndSettle(script: String): String?
    fun pause(ms: Long)
}

/** Pure parts of the browser search (unit-tested) plus the small orchestration over [SearchPageOps]. */
object BrowserSearch {
    const val MAX_HITS = 8
    const val GOOGLE_CAPTCHA = "谷歌要求人机验证，请在 浏览器 页手动完成一次后重试"
    const val BING_CAPTCHA = "必应要求人机验证，请在 浏览器 页手动完成一次后重试"
    const val GOOGLE_CONSENT = "谷歌的同意页没能自动通过，请在 浏览器 页手动同意一次后重试"

    enum class Block { CONSENT, CAPTCHA }

    data class RawHit(val href: String, val title: String, val lines: List<String>)
    data class RawAnswer(val text: String, val href: String, val title: String)
    data class Page(val url: String, val title: String, val text: String, val hits: List<RawHit>, val answer: RawAnswer? = null)

    /** engine label (as in the result header), hits, and a notice for the model/user (captcha …). */
    data class Outcome(val engine: String, val hits: List<SearchHit>, val block: Block? = null, val notice: String? = null)

    fun isBrowser(p: SearchProvider) = p == SearchProvider.GOOGLE_BROWSER || p == SearchProvider.BING_BROWSER
    fun engineLabel(p: SearchProvider) = if (p == SearchProvider.BING_BROWSER) "必应（浏览器）" else "谷歌（浏览器）"

    // ---------- urls ----------

    /** Google's `hl` from the phone locale: zh-CN / zh-TW / en … */
    fun hl(locale: Locale): String {
        val lang = locale.language.lowercase().ifEmpty { "en" }
        if (lang != "zh") return lang
        val hant = locale.script.equals("Hant", true) || locale.country.uppercase() in setOf("TW", "HK", "MO")
        return if (hant && !locale.script.equals("Hans", true)) "zh-TW" else "zh-CN"
    }

    fun googleUrl(q: String, hl: String, news: Boolean = false): String =
        "https://www.google.com/search?q=" + enc(q) + "&hl=" + enc(hl) + "&num=10" + (if (news) "&tbm=nws" else "")

    fun bingUrl(q: String, hl: String): String = "https://www.bing.com/search?q=" + enc(q) + "&setlang=" + enc(hl)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- links ----------

    /** `/url?q=<target>&sa=…` (or `url=`) on any google host → the target; other links unchanged. */
    fun unwrapGoogle(href: String): String {
        val h = href.trim()
        val u = runCatching { URI(h) }.getOrNull() ?: return h
        val host = u.host?.lowercase().orEmpty()
        val path = u.rawPath.orEmpty()
        if ((host.isEmpty() || isGoogleHost(host)) && path == "/url") {
            (param(h, "q") ?: param(h, "url"))?.let { t ->
                val d = runCatching { URLDecoder.decode(t, "UTF-8") }.getOrDefault(t)
                if (d.startsWith("http://") || d.startsWith("https://")) return d
            }
        }
        return h
    }

    private val GOOGLE_HOST = Regex("^(www\\.|m\\.)?google\\.(com|[a-z]{2,3}|com?\\.[a-z]{2})$")
    private fun isGoogleHost(host: String) = GOOGLE_HOST.matches(host)

    /** Google's own pages (search, maps, sign-in, ads, cache): not results. */
    fun isGoogleInternal(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return true
        val host = u.host?.lowercase() ?: return true
        if (isGoogleHost(host)) return true
        if (host.startsWith("maps.google.") || host.startsWith("accounts.google.") || host.startsWith("policies.google.") ||
            host.startsWith("consent.google.") || host.startsWith("translate.google.") && u.rawPath.orEmpty().startsWith("/translate")) return true
        if (host.endsWith("googleadservices.com") || host == "webcache.googleusercontent.com") return true
        return u.rawPath.orEmpty().startsWith("/aclk")
    }

    private fun isBingInternal(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return true
        return host == "bing.com" || host.endsWith(".bing.com") || host.endsWith("msn.cn") && url.contains("/ck/")
    }

    private fun param(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    // ---------- blocks ----------

    private val CAPTCHA_TEXT = Regex("(?i)unusual traffic|异常流量|not a robot|不是自动程序|不是机器人|solve the challenge|人机身份验证")
    private val CONSENT_TEXT = Regex("(?i)before you continue to google|在继续访问\\s*google|继续使用\\s*google\\s*之前|繼續使用\\s*google\\s*之前")

    /**
     * Consent / captcha page? The url decides first (consent.google.com, google.com/sorry/, bing
     * /turing); page text only when the page gave no results (a result may quote the same words).
     */
    fun classify(url: String, title: String, text: String, hasHits: Boolean): Block? {
        val u = url.lowercase()
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.startsWith("consent.")) return Block.CONSENT
        if (u.contains("/sorry/") || u.contains("bing.com/turing") || u.contains("/challenge?")) return Block.CAPTCHA
        if (hasHits) return null
        val t = "$title\n$text"
        if (CAPTCHA_TEXT.containsMatchIn(t)) return Block.CAPTCHA
        if (CONSENT_TEXT.containsMatchIn(t)) return Block.CONSENT
        return null
    }

    // ---------- page JSON → hits ----------

    private val json = Json { ignoreUnknownKeys = true }

    /** evaluateJavascript's result: a JSON string literal holding our JSON (or the object itself). */
    fun parsePage(raw: String?): Page? = runCatching {
        if (raw.isNullOrBlank() || raw == "null") return null
        var e: JsonElement = json.parseToJsonElement(raw)
        if (e is JsonPrimitive && e.isString) e = json.parseToJsonElement(e.content)
        val o = e as? JsonObject ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val hits = (o["h"] as? JsonArray).orEmpty().mapNotNull { h ->
            val ho = h as? JsonObject ?: return@mapNotNull null
            RawHit(ho.s("h"), ho.s("t"), (ho["l"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })
        }
        val a = (o["a"] as? JsonObject)?.let { RawAnswer(it.s("x"), it.s("h"), it.s("t")) }?.takeIf { it.text.isNotBlank() }
        Page(o.s("u"), o.s("t"), o.s("x"), hits, a)
    }.getOrNull()

    private val BREADCRUMB = Regex("^(https?://\\S+|[\\w-]+(\\.[\\w-]+)+(/\\S*)?)$")
    private val JUNK = setOf("翻译此页", "翻譯此頁", "Translate this page", "网页快照", "Cached", "更多", "More", "广告", "Ad", "Ads", "Sponsored", "赞助商")
    private val DATE_HEAD = Regex("^(\\d{4}年\\d{1,2}月\\d{1,2}日|\\d+\\s*(天|小时|小時|分钟|分鐘|周|週)前|\\d+ (days?|hours?|minutes?) ago|[A-Z][a-z]{2} \\d{1,2}, \\d{4})\\s*[—–-]\\s*")

    private val DATE_LINE = Regex("^(\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}-\\d{1,2}-\\d{1,2}|\\d+\\s*(天|小时|小時|分钟|分鐘|周|週|个月|個月)前|昨天|前天|\\d+ (days?|hours?|mins?|minutes?|weeks?|months?) ago|[A-Z][a-z]{2} \\d{1,2}, \\d{4}|\\d{1,2} [A-Z][a-z]{2} \\d{4})$")

    private fun isCrumb(l: String) = l.contains('›') || BREADCRUMB.matches(l)

    /** Site name (the short line above the title), date and snippet text from a result's lines. */
    fun describe(title: String, lines: List<String>): Triple<String, String, String> {
        val ls = lines.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length > 1 && it !in JUNK }
        val ti = ls.indexOfFirst { it == title || it.startsWith(title) }
        val source = if (ti > 0) ls.subList(0, ti).firstOrNull { !isCrumb(it) && it.length <= 40 }.orEmpty() else ""
        val body = (if (ti >= 0) ls.drop(ti + 1) else ls)
            .filter { !isCrumb(it) && it != title && it != source }
        // 2.1.7: Google News cards put the date on its own line after the snippet ("3 小时前");
        // it used to be glued onto the snippet and was often cut off by the length cap.
        var date = body.lastOrNull { DATE_LINE.matches(it) }.orEmpty()
        var text = body.filter { it != date || date.isEmpty() }.joinToString(" ")
        DATE_HEAD.find(text)?.let { m -> if (date.isEmpty()) date = m.groupValues[1]; text = text.substring(m.range.last + 1) }
        return Triple(source, date, WebText.clip(SearchText.clean(text), SearchText.SNIPPET_CHARS))
    }

    /** The page's results as hits: real urls, no Google/Bing-internal links or ads, deduped, ≤ [MAX_HITS]. */
    fun toHits(page: Page, provider: SearchProvider): List<SearchHit> {
        val google = provider != SearchProvider.BING_BROWSER
        fun real(h: String) = if (google) unwrapGoogle(h) else SearchText.realUrl(h)
        fun internal(u: String) = !u.startsWith("http") || if (google) isGoogleInternal(u) else isBingInternal(u)
        val out = ArrayList<SearchHit>()
        for (r in page.hits) {
            val url = real(r.href)
            val title = SearchText.clean(r.title)
            if (title.isEmpty() || internal(url)) continue
            val (source, date, snippet) = describe(r.title.trim(), r.lines)
            out += SearchHit(title, url, snippet, source, date)
        }
        var hits = out.distinctBy { it.url }
        page.answer?.let { a ->
            val text = WebText.clip(SearchText.clean(a.text), SearchText.SNIPPET_CHARS * 2)
            val url = a.href.takeIf { it.isNotBlank() }?.let { real(it) }?.takeIf { !internal(it) }
            val same = url?.let { u -> hits.firstOrNull { it.url == u } }
            hits = if (same != null) listOf(same.copy(snippet = text)) + (hits - same)
            else listOf(SearchHit(a.title.ifBlank { if (google) "谷歌答案" else "必应答案" }, url ?: page.url, text, if (google) "Google" else "Bing")) + hits
        }
        return hits.take(MAX_HITS)
    }

    // ---------- orchestration ----------

    /**
     * Searches [q] in the shared browser with [provider] (GOOGLE_BROWSER / BING_BROWSER). A consent
     * page gets one auto-accept click; a captcha returns no hits and the notice to show. Never throws.
     */
    fun search(b: SearchBrowser, provider: SearchProvider, q: String, news: Boolean, locale: Locale = Locale.getDefault()): Outcome {
        val label = engineLabel(provider)
        val google = provider != SearchProvider.BING_BROWSER
        val captcha = if (google) GOOGLE_CAPTCHA else BING_CAPTCHA
        return runCatching {
            b.withSearchPage { ops ->
                val hl = hl(locale)
                val script = if (google) GOOGLE_EXTRACT else BING_EXTRACT
                fun read(): Page? = parsePage(ops.eval(script))
                fun once(url: String): Outcome {
                    ops.load(url)
                    var page = read() ?: return Outcome(label, emptyList())
                    var block = classify(page.url, page.title, page.text, page.hits.isNotEmpty())
                    if (block == Block.CONSENT) {
                        ops.evalAndSettle(CONSENT_ACCEPT)
                        page = read() ?: return Outcome(label, emptyList(), Block.CONSENT)
                        block = classify(page.url, page.title, page.text, page.hits.isNotEmpty())
                        // the accept may land back on the home page: search again once
                        if (block == null && page.hits.isEmpty()) {
                            ops.load(url); page = read() ?: page
                            block = classify(page.url, page.title, page.text, page.hits.isNotEmpty())
                        }
                    }
                    if (block == Block.CAPTCHA) return Outcome(label, emptyList(), Block.CAPTCHA, captcha)
                    if (block == Block.CONSENT) return Outcome(label, emptyList(), Block.CONSENT, if (google) GOOGLE_CONSENT else null)
                    var hits = toHits(page, provider)
                    if (hits.isEmpty()) { ops.pause(1200); read()?.let { hits = toHits(it, provider) } }
                    return Outcome(label, hits)
                }
                val first = once(if (google) googleUrl(q, hl, news) else bingUrl(q, hl))
                if (google && news && first.hits.isEmpty() && first.block == null) once(googleUrl(q, hl, false)) else first
            }
        }.getOrElse { Outcome(label, emptyList()) }
    }

    // ---------- page scripts ----------

    private const val PRELUDE = """
function T(e){return e?(e.innerText||e.textContent||'').replace(/[ \t\u00a0]+/g,' ').trim():'';}
function L(e){return T(e).split('\n').map(function(s){return s.trim();}).filter(function(s){return s;}).slice(0,14).map(function(s){return s.slice(0,300);});}
function vis(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);}
"""

    /** Google: anchors holding an h3 / role=heading; ads, "People also ask" and hidden ones skipped. */
    val GOOGLE_EXTRACT = """
(function(){$PRELUDE
var AD='#tads,#tadsb,#bottomads,[data-text-ad],[aria-label="Ads"],[aria-label="广告"],[aria-label="廣告"],[aria-label="Sponsored"]';
var PAA='.related-question-pair,[data-initq],[jsname="yEVEwb"],[data-q]';
var out=[],seen={};
document.querySelectorAll('a[href]').forEach(function(a){
 if(out.length>=16)return;
 var h=a.querySelector('h3,[role=heading]');if(!h||!vis(a))return;
 if(a.closest(AD)||a.closest(PAA))return;
 var t=T(h);if(!t||seen[a.href])return;seen[a.href]=1;
 var c=a;for(var i=0;i<7;i++){var p=c.parentElement;if(!p||p===document.body)break;if(p.querySelectorAll('h3,[role=heading]').length>1)break;c=p;}
 out.push({h:a.href,t:t,l:L(c)});});
var A=null,f=document.querySelector('.xpdopen .hgKElc,.IZ6rdc,.Z0LcW,[data-attrid="wa:/description"],.kno-rdesc span,[data-tts="answers"]');
if(f&&vis(f)){var box=f.closest('.xpdopen,[data-attrid],.kp-blk')||f.parentElement;var lh=box?box.querySelector('a[href] h3,a[href] [role=heading]'):null;var la=lh?lh.closest('a'):null;A={x:T(f).slice(0,400),h:la?la.href:'',t:lh?T(lh):''};}
return JSON.stringify({u:location.href,t:document.title||'',x:document.body?T(document.body).slice(0,600):'',h:out,a:A});
})()
""".trimIndent()

    /** Bing (mobile or desktop): `li.b_algo` blocks; ads are `li.b_ad` and are not read. */
    val BING_EXTRACT = """
(function(){$PRELUDE
var out=[],seen={};
document.querySelectorAll('li.b_algo').forEach(function(li){
 if(out.length>=16||!vis(li))return;
 var a=li.querySelector('h2 a[href],.b_title a[href],a.tilk[href]');if(!a)return;
 var h=li.querySelector('h2')||a;var t=T(h);if(!t||seen[a.href])return;seen[a.href]=1;
 out.push({h:a.href,t:t,l:L(li)});});
var A=null,f=document.querySelector('.b_ans .b_focusTextLarge,.b_ans .b_focusTextMedium,.b_ans .rwrl');
if(f&&vis(f)){A={x:T(f).slice(0,400),h:'',t:''};}
return JSON.stringify({u:location.href,t:document.title||'',x:document.body?T(document.body).slice(0,600):'',h:out,a:A});
})()
""".trimIndent()

    /** Clicks the consent page's "accept all" once. */
    val CONSENT_ACCEPT = """
(function(){var re=/^(accept all|全部接受|接受全部|全部同意|i agree|我同意|同意|alle akzeptieren|tout accepter|aceptar todo|accetta tutto|すべて受け入れる)$/i;
var els=document.querySelectorAll('button,input[type=submit],[role=button]');
for(var i=0;i<els.length;i++){var e=els[i];var t=((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').trim();if(re.test(t)){e.click();return 'ok';}}
return 'none';})()
""".trimIndent()
}

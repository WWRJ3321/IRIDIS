package com.iris.localai.agent

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 2.5.2: what web_search / web_fetch read off a page, cleaned. Pure (unit-tested in v252):
 *  - [decode]: bytes → text by BOM, Content-Type charset, `<meta charset>` / XML declaration, else UTF-8
 *    (GBK/GB2312 pages were read as UTF-8 and came out garbled);
 *  - [decodeEntities]: every numeric and the common named entities in one pass (&amp;lt; stays "&lt;");
 *  - [readable]: the page's main text: `<article>` / `<main>` when they hold the text, navigation /
 *    footer / sidebars / cookie banners / share bars / hidden blocks dropped, repeated lines removed;
 *  - [unwrapRedirect]: DuckDuckGo `uddg`, Bing `ck/a?u=a1…` / `apiclick?url=`, Google `/url?q=` links;
 *  - [cutIndex] / [clip]: cuts that never split a surrogate pair and prefer a line / sentence / word end.
 */
object WebText {
    // ------------------------------------------------------------------ charset

    private val HEADER_CHARSET = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._:\\-]+)")
    private val META_CHARSET = Regex("(?i)<meta\\b[^>]*?charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._:\\-]+)")
    private val XML_ENCODING = Regex("(?i)<\\?xml\\b[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._:\\-]+)")

    /** A charset label → a Charset this JVM has (GB2312/GBK → GB18030, Latin-1 → windows-1252), else null. */
    fun charsetFor(label: String?): Charset? {
        val l = label?.trim()?.trim('"', '\'')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val name = when (l) {
            "utf8", "utf-8", "unicode-1-1-utf-8" -> "UTF-8"
            "gb2312", "gbk", "x-gbk", "gb_2312-80", "chinese", "csgb2312", "iso-ir-58", "gb18030", "cp936", "windows-936" -> "GB18030"
            "big5", "big5-hkscs", "cn-big5", "x-x-big5", "csbig5" -> "Big5"
            "iso-8859-1", "latin1", "l1", "us-ascii", "ascii", "windows-1252", "cp1252", "iso8859-1" -> "windows-1252"
            "shift_jis", "shift-jis", "sjis", "x-sjis", "ms_kanji", "windows-31j", "cp932" -> "Shift_JIS"
            "euc-jp", "eucjp" -> "EUC-JP"
            "euc-kr", "euckr", "ks_c_5601-1987", "windows-949" -> "EUC-KR"
            else -> l
        }
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    /** The page's charset: BOM, then the Content-Type header, then meta / XML declaration in the first 4 KB, else UTF-8. */
    fun charsetOf(contentType: String?, bytes: ByteArray): Charset {
        bom(bytes)?.let { return it.first }
        contentType?.let { HEADER_CHARSET.find(it)?.groupValues?.get(1) }?.let { charsetFor(it) }?.let { return it }
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        (META_CHARSET.find(head) ?: XML_ENCODING.find(head))?.groupValues?.get(1)?.let { charsetFor(it) }
            // a page that is really UTF-16 cannot announce it in ASCII; such a meta is wrong
            ?.takeIf { !it.name().startsWith("UTF-16") }?.let { return it }
        return Charsets.UTF_8
    }

    private fun bom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        else -> null
    }

    /** Bytes of a response → text in the page's charset (BOM removed; bad bytes become U+FFFD). */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val cs = charsetOf(contentType, bytes)
        val skip = bom(bytes)?.takeIf { it.first == cs }?.second ?: 0
        val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        return dec.decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
    }

    // ------------------------------------------------------------------ entities

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,31});")

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "shy" to "", "zwj" to "", "zwnj" to "", "lrm" to "", "rlm" to "",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–", "minus" to "−",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "middot" to "·", "bull" to "•",
        "times" to "×", "divide" to "÷", "deg" to "°", "plusmn" to "±", "micro" to "µ", "para" to "¶", "sect" to "§",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤", "iexcl" to "¡", "iquest" to "¿",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "rArr" to "⇒", "hearts" to "♥",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup1" to "¹", "sup2" to "²", "sup3" to "³", "permil" to "‰",
        "prime" to "′", "Prime" to "″", "dagger" to "†", "Dagger" to "‡", "ordm" to "º", "ordf" to "ª", "not" to "¬",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "Aacute" to "Á", "Eacute" to "É",
        "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú", "agrave" to "à", "egrave" to "è", "igrave" to "ì", "ograve" to "ò",
        "ugrave" to "ù", "acirc" to "â", "ecirc" to "ê", "icirc" to "î", "ocirc" to "ô", "ucirc" to "û", "ntilde" to "ñ",
        "Ntilde" to "Ñ", "ccedil" to "ç", "Ccedil" to "Ç", "atilde" to "ã", "otilde" to "õ", "aring" to "å", "oslash" to "ø",
        "aelig" to "æ", "euml" to "ë", "iuml" to "ï", "yuml" to "ÿ",
    )

    /** windows-1252 for the C1 range numeric references real pages use (&#150; = –). */
    private val CP1252 = mapOf(128 to '€', 130 to '‚', 132 to '„', 133 to '…', 134 to '†', 135 to '‡', 137 to '‰',
        139 to '‹', 145 to '‘', 146 to '’', 147 to '“', 148 to '”', 149 to '•', 150 to '–', 151 to '—', 153 to '™', 155 to '›')

    /** One pass, so "&amp;lt;" becomes "&lt;" (not "<"). Unknown names are left as written. */
    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            if (v.startsWith("#")) {
                val cp = if (v.length > 1 && (v[1] == 'x' || v[1] == 'X')) v.substring(2).toIntOrNull(16) else v.substring(1).toIntOrNull()
                when {
                    cp == null -> " "
                    cp in CP1252 -> CP1252.getValue(cp).toString()
                    cp == 0 || cp in 0xD800..0xDFFF || !Character.isValidCodePoint(cp) -> "\uFFFD"
                    cp == 0xA0 -> " "
                    else -> String(Character.toChars(cp))
                }
            } else NAMED[v] ?: m.value
        }
    }

    // ------------------------------------------------------------------ main text

    private val OPEN_TAG = Regex("<([A-Za-z][A-Za-z0-9-]*)([^>]*)>")
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param")
    private val OPTIONAL_END = setOf("li", "p", "td", "th", "tr", "dt", "dd", "option")
    private val tagRe = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun pairRe(tag: String) = tagRe.getOrPut(tag) { Regex("(?i)<(/?)" + Regex.escape(tag) + "(?=[\\s/>])[^>]*>") }

    /** The closing tag that matches an element of [tag] opened just before [from] (nesting counted), or null. */
    private fun closeOf(html: String, tag: String, from: Int): IntRange? {
        var depth = 1
        var i = from
        val re = pairRe(tag)
        // li / p / td … may be left open in real HTML: their next end tag closes them (no nesting count).
        val optionalEnd = tag in OPTIONAL_END
        while (true) {
            val m = re.find(html, i) ?: return null
            if (m.groupValues[1] == "/") { if (--depth == 0) return m.range }
            else if (!optionalEnd && !m.value.endsWith("/>")) depth++
            i = m.range.last + 1
        }
    }

    /**
     * Walks the elements in document order. For an element [select] accepts, [visit] gets its opening
     * tag range and its closing tag range (null = never closed); when [visit] returns true the walk
     * skips the element's subtree, else it goes inside. Closing tags are looked up only for selected
     * elements, so a page is walked in about linear time.
     */
    private fun walk(html: String, select: (tag: String, attrs: String) -> Boolean, visit: (open: IntRange, close: IntRange?) -> Boolean) {
        var i = 0
        while (i < html.length) {
            val m = OPEN_TAG.find(html, i) ?: return
            val tag = m.groupValues[1].lowercase()
            val attrs = m.groupValues[2]
            if (tag in VOID || attrs.trimEnd().endsWith("/") || !select(tag, attrs)) { i = m.range.last + 1; continue }
            val close = closeOf(html, tag, m.range.last + 1)
            i = if (visit(m.range, close)) (close ?: m.range).last + 1 else m.range.last + 1
        }
    }

    private val PARA_OPEN = Regex("(?i)<p[\\s>]")

    /**
     * [html] without the elements [drop] selects (their whole subtree). An unclosed one loses only its
     * tag. With [keepLarge], an element holding 3+ paragraphs is kept and searched inside instead (a
     * content wrapper whose class merely mentions "share" or "nav").
     */
    fun removeElements(html: String, keepLarge: ((tag: String, attrs: String) -> Boolean)? = null, drop: (tag: String, attrs: String) -> Boolean): String {
        val sb = StringBuilder(html.length)
        var copied = 0
        var lastTag = ""
        var lastAttrs = ""
        walk(html, { t, a -> lastTag = t; lastAttrs = a; drop(t, a) }) { open, close ->
            if (keepLarge != null && close != null && keepLarge(lastTag, lastAttrs) &&
                PARA_OPEN.findAll(html.substring(open.last + 1, close.first)).take(3).count() >= 3) return@walk false
            sb.append(html, copied, open.first).append('\n')
            copied = (close ?: open).last + 1
            true
        }
        sb.append(html, copied.coerceAtMost(html.length), html.length)
        return sb.toString()
    }

    /** Inner HTML of the outermost closed elements [pick] selects. */
    fun innerOf(html: String, pick: (tag: String, attrs: String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        walk(html, pick) { open, close -> if (close != null) out += html.substring(open.last + 1, close.first); close != null }
        return out
    }

    private val attrRe = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun attr(attrs: String, name: String): String? =
        attrRe.getOrPut(name) { Regex("(?is)(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))") }.find(attrs)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
        }

    private val BOILER_TAGS = setOf("nav", "aside", "footer", "dialog", "button", "select", "menu")
    private val BOILER_ROLES = setOf("navigation", "banner", "contentinfo", "complementary", "search", "dialog", "alertdialog", "menu", "menubar")
    private val BOILER_TOKENS = setOf(
        "nav", "navbar", "navigation", "menu", "menubar", "breadcrumb", "breadcrumbs", "footer", "sidebar",
        "cookie", "cookies", "consent", "gdpr", "newsletter", "subscribe", "subscription", "share", "sharing", "social",
        "related", "recommend", "recommended", "comments", "advert", "advertisement", "ads", "ad", "sponsor", "sponsored",
        "promo", "popup", "modal", "toolbar", "skip", "pagination", "pager",
    )
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    private val HIDDEN_ATTR = Regex("(?i)(?:^|\\s)hidden(?:\\s|=|$)")
    private val HIDDEN_STYLE = Regex("(?i)display\\s*:\\s*none|visibility\\s*:\\s*hidden")

    /** Navigation, footers, sidebars, cookie banners, share bars, ads and hidden blocks. [header] also drops `<header>`. */
    fun isBoilerplate(tag: String, attrs: String, header: Boolean = true): Boolean {
        if (tag in BOILER_TAGS || (header && tag == "header")) return true
        if (attrs.isBlank()) return false
        attr(attrs, "role")?.lowercase()?.trim()?.let { if (it in BOILER_ROLES) return true }
        if (HIDDEN_ATTR.containsMatchIn(attrs)) return true
        if (attr(attrs, "aria-hidden")?.trim()?.equals("true", true) == true) return true
        attr(attrs, "style")?.let { if (HIDDEN_STYLE.containsMatchIn(it)) return true }
        return byName(attrs)
    }

    /** Boilerplate only by its class / id words (a guess, unlike nav / footer / role / hidden). */
    fun byName(attrs: String): Boolean {
        if (attrs.isBlank()) return false
        val names = (attr(attrs, "class").orEmpty() + " " + attr(attrs, "id").orEmpty()).lowercase()
        return names.split(TOKEN_SPLIT).any { it in BOILER_TOKENS }
    }

    /** Dropped by name only (not by tag, role or hidden): such an element is kept when it holds 3+ paragraphs. */
    private fun softOnly(tag: String, attrs: String, header: Boolean): Boolean =
        byName(attrs) && tag !in BOILER_TAGS && !(header && tag == "header") &&
            attr(attrs, "role").orEmpty().lowercase().trim() !in BOILER_ROLES && !HIDDEN_ATTR.containsMatchIn(attrs) &&
            attr(attrs, "aria-hidden")?.trim()?.equals("true", true) != true && attr(attrs, "style")?.let { HIDDEN_STYLE.containsMatchIn(it) } != true

    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val BODY = Regex("(?is)<body\\b[^>]*>")
    private val JUNK_LINE = Regex(
        "(?i)^(skip to (main )?content|跳到主要内容|跳转到主要内容|返回顶部|回到顶部|back to top|accept( all)?( cookies)?|reject all|cookie settings|" +
            "manage cookies|分享到?[:：]?|share( this)?|上一篇|下一篇|打印|print|menu|菜单|登录|注册|sign in|log in|subscribe)$",
    )

    /** The `<title>` text, or "". */
    fun title(html: String): String = TITLE.find(html)?.groupValues?.get(1)?.let { HtmlText.toText(it).replace('\n', ' ').trim() }.orEmpty()

    /**
     * The readable text of a page: the longest `<article>` / `<main>` / `role=main` when it holds most
     * of the text (≥ 200 chars, or ≥ half of the page), else the body; boilerplate removed inside it;
     * repeated lines and menu crumbs dropped. Falls back to the plain body text when cleaning left
     * almost nothing.
     */
    fun readable(html: String): String {
        val noise = HtmlText.stripNonContent(html)
        val body = BODY.find(noise)?.let { noise.substring(it.range.last + 1) } ?: noise
        val plain = HtmlText.toText(body)
        val mains = innerOf(body) { tag, attrs -> tag == "article" || tag == "main" || attr(attrs, "role")?.trim()?.equals("main", true) == true }
            .map { it to HtmlText.toText(removeElements(it, { t, a -> softOnly(t, a, false) }) { t, a -> isBoilerplate(t, a, header = false) }) }
        val best = mains.maxByOrNull { it.second.length }
        val text = if (best != null && (best.second.length >= 200 || best.second.length * 2 >= plain.length)) best.second
            else HtmlText.toText(removeElements(body, { t, a -> softOnly(t, a, true) }) { t, a -> isBoilerplate(t, a) })
        val chosen = if (text.length < 80 && plain.length > text.length * 3) plain else text
        return tidyLines(chosen)
    }

    /**
     * Drops consecutive duplicate lines, later repeats of a line ≥ 24 chars (menus and teasers that a
     * page repeats), and lone menu crumbs ("返回顶部", "Accept all cookies"). Pure.
     */
    fun tidyLines(text: String): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val key = l.lowercase()
            if (out.isNotEmpty() && out.last().lowercase() == key) continue
            if (l.length >= 24 && !seen.add(key)) continue
            if (l.length <= 24 && JUNK_LINE.matches(l)) continue
            out += l
        }
        return out.joinToString("\n")
    }

    // ------------------------------------------------------------------ links

    private fun param(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private val AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.-]*://(?:[^/?#@]*@)?(\\[[^\\]]*]|[^/?#:]*)")

    /** Host of an absolute url, lower case (no java.net.URI: it rejects `|`, spaces and other real-world chars); null when none. */
    fun hostOf(url: String): String? = AUTHORITY.find(url.trim())?.groupValues?.get(1)?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Path of an url ("" for none), without query and fragment. Relative urls (`/url?q=`) work too. */
    fun pathOf(url: String): String {
        val u = url.trim()
        var rest = AUTHORITY.find(u)?.let { u.substring(it.range.last + 1) } ?: u
        if (rest.startsWith(":")) rest = rest.drop(1).dropWhile { it.isDigit() }
        return rest.substringBefore('#').substringBefore('?')
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun web(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    /**
     * Search-engine click links → the real target: DuckDuckGo `/l/?uddg=` (absolute, `//` or relative),
     * Bing `/ck/a?…&u=a1<base64url>` and `apiclick.aspx?…&url=`, Google `/url?q=` / `url=`. Anything
     * else (or a link that does not decode to http/https) is returned unchanged.
     */
    fun unwrapRedirect(href: String): String {
        val h0 = href.trim().replace("&amp;", "&")
        val h = when {
            h0.startsWith("//") -> "https:$h0"
            h0.startsWith("/l/?") || h0.startsWith("/l?") -> "https://duckduckgo.com$h0"
            else -> h0
        }
        val host = hostOf(h).orEmpty()
        val path = pathOf(h)
        if (host.endsWith("duckduckgo.com") && (path == "/l/" || path == "/l")) {
            param(h, "uddg")?.let { dec(it) }?.takeIf(::web)?.let { return it }
        }
        if (host == "bing.com" || host.endsWith(".bing.com")) {
            param(h, "url")?.let { dec(it) }?.takeIf(::web)?.let { return it }
            param(h, "u")?.let { p ->
                val v = dec(p)
                if (v.startsWith("a1")) runCatching {
                    val raw = v.substring(2).replace('-', '+').replace('_', '/')
                    val pad = raw + "=".repeat((4 - raw.length % 4) % 4)
                    String(java.util.Base64.getDecoder().decode(pad), Charsets.UTF_8)
                }.getOrNull()?.takeIf(::web)?.let { return it }
            }
        }
        if ((host.isEmpty() || Regex("^(www\\.|m\\.)?google\\.[a-z.]{2,8}$").matches(host)) && path == "/url") {
            (param(h, "q") ?: param(h, "url"))?.let { dec(it) }?.takeIf(::web)?.let { return it }
        }
        return h
    }

    /** Ads and click links that did not unwrap: DuckDuckGo `/y.js` / `/l/`, Bing `/ck/a` / `/aclk` / `apiclick`, Google ads. */
    fun isEngineLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val path = pathOf(url).lowercase()
        if (host.endsWith("googleadservices.com") || path.startsWith("/aclk")) return true
        if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) return path == "/y.js" || path == "/l/" || path == "/l"
        if (host == "bing.com" || host.endsWith(".bing.com")) return path.startsWith("/ck/") || path.contains("apiclick")
        return false
    }

    // ------------------------------------------------------------------ cutting

    private const val BOUNDARY_WINDOW = 80

    /**
     * An index ≤ [max] to cut [text] at: never between a surrogate pair, and at the last line break,
     * sentence end (。！？.!?；;) or space within the last [BOUNDARY_WINDOW] chars when there is one.
     */
    fun cutIndex(text: String, max: Int): Int {
        if (max >= text.length) return text.length
        if (max <= 0) return 0
        var end = max
        if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        val floor = (end - BOUNDARY_WINDOW).coerceAtLeast(end / 2)
        for (k in end downTo floor + 1) {
            val c = text[k - 1]
            if (c == '\n') return k
        }
        for (k in end downTo floor + 1) {
            val c = text[k - 1]
            if (c in "。！？!?；;…" || (c == '.' && (k == text.length || text[k].isWhitespace()))) return k
        }
        for (k in end downTo floor + 1) if (text[k - 1].isWhitespace()) return k
        // CJK text without spaces: any char boundary is fine (not inside a pair)
        return end
    }

    /** [s] cut to at most [n] chars (with "…" when cut) at a boundary from [cutIndex]. */
    fun clip(s: String, n: Int): String {
        if (s.length <= n) return s
        if (n <= 1) return s.take(n.coerceAtLeast(0))
        return s.substring(0, cutIndex(s, n - 1)).trimEnd() + "…"
    }
}

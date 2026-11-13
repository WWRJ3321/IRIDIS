package com.iris.localai.ui.markdown

import com.iris.localai.agent.NetGuard
import java.io.File

/**
 * 2.2 rich rendering — the pure (JVM-tested) rules behind the HTML preview card and markdown
 * images. No Android types here; the views live in RichViews.kt.
 */
object HtmlBlocks {
    enum class Kind { HTML, SVG }

    private val HTML_LANGS = setOf("html", "htm", "xhtml")
    private val DOC_START = Regex("^(?:<!--.*?-->\\s*)*(?:<!doctype\\s+html[^>]*>|<html[\\s>])", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * The preview kind of a fenced block, or null when it stays a plain code block. Only a block
     * whose closing fence has arrived ([MdBlock.Code.closed]) is rendered: while streaming the
     * code shows as code.
     */
    fun kindOf(block: MdBlock.Code): Kind? {
        if (!block.closed || block.code.isBlank()) return null
        val lang = block.language.trim().lowercase()
        return when {
            lang in HTML_LANGS -> Kind.HTML
            lang == "svg" -> Kind.SVG
            lang == "xml" && block.code.trimStart().startsWith("<svg", ignoreCase = true) -> Kind.SVG
            else -> null
        }
    }

    /** A whole reply that is one HTML document (`<!DOCTYPE html>` / `<html>` … `</html>`), not markdown. */
    fun wholeDocument(text: String): Boolean {
        val t = text.trim()
        return t.length > 20 && DOC_START.containsMatchIn(t) && t.endsWith("</html>", ignoreCase = true)
    }
}

/** How a preview document is sandboxed (on top of the WebView settings in RichViews.kt). */
object HtmlSandbox {
    /**
     * Content-Security-Policy injected into every preview: inline script / style only, no fetch /
     * XHR / WebSocket (connect-src 'none'), no frames, no forms, no external scripts or fonts.
     * Network images only when [allowImages] (and they are fetched by the app, without cookies).
     */
    fun csp(allowImages: Boolean): String =
        "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'self' ${ThreeAssets.HOST} ${ThreeAssets.CDN_HOSTS.joinToString(" ")}; style-src 'unsafe-inline' data:; " +
            "img-src data: blob:" + (if (allowImages) " https:" else "") + "; font-src data:; media-src data: blob:; " +
            "connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"

    /**
     * 2.5.2: script errors used to fail silently (a blank 3D scene). This first script shows them in a
     * small bar at the bottom of the preview: uncaught errors, rejected promises, scripts that failed
     * to load, and a missing WebGL context.
     */
    const val ERROR_OVERLAY: String = "<script>(function(){var n=0;function show(m){if(n++>4)return;var add=function(){" +
        "var b=document.getElementById('__iris_err');if(!b){b=document.createElement('div');b.id='__iris_err';" +
        "b.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:2147483647;max-height:40%;overflow:auto;" +
        "padding:6px 10px;background:#1f1f1f;color:#f2b8b5;font:12px/1.4 monospace;white-space:pre-wrap;word-break:break-word';" +
        "document.body.appendChild(b);}b.textContent+=(b.textContent?'\\n':'')+'JS: '+m;};" +
        "if(document.body)add();else addEventListener('DOMContentLoaded',add);}" +
        "addEventListener('error',function(e){var t=e.target;if(t&&t!==window&&(t.src||t.href)){show('load failed '+(t.src||t.href));return;}" +
        "show((e.message||'error')+(e.lineno?' @'+e.lineno+':'+e.colno:''));},true);" +
        "addEventListener('unhandledrejection',function(e){var r=e.reason;show('promise: '+(r&&r.message?r.message:String(r)));});" +
        "var gc=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(k){var x=gc.apply(this,arguments);" +
        "if(!x&&/webgl/i.test(String(k)))show('WebGL unavailable ('+k+')');return x;};})();</script>"

    /** 2.5.4: sent after the preview changes size, so pages sized from innerWidth / innerHeight re-fit. */
    const val RESIZE_JS: String = "try{window.dispatchEvent(new Event('resize'))}catch(e){}"

    private val FILL_CSS = Regex("(?:html|body)[^{}]*\\{[^}]*height\\s*:\\s*100(?:%|vh)", RegexOption.IGNORE_CASE)
    private val FILL_JS = Regex("<canvas\\b|innerHeight|WebGLRenderer|getContext\\s*\\(\\s*['\"]webgl", RegexOption.IGNORE_CASE)

    /** 2.5.4: the page fills its viewport (3D / canvas / html,body height:100%) rather than having a content height. */
    fun fillsViewport(source: String): Boolean = FILL_CSS.containsMatchIn(source) || FILL_JS.containsMatchIn(source)

    private fun head(allowImages: Boolean, importMap: String? = null) =
        "<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"${csp(allowImages)}\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" + (importMap ?: "") + ERROR_OVERLAY

    private val HEAD_TAG = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<html(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    /** The document loaded into the WebView: the CSP goes first inside <head>, so it covers everything after it. */
    fun document(source0: String, kind: HtmlBlocks.Kind, allowImages: Boolean): String {
        if (kind == HtmlBlocks.Kind.SVG) {
            val h = head(allowImages)
            return "<!DOCTYPE html><html><head>$h<style>html,body{margin:0;padding:8px;background:#fff}" +
                "svg{max-width:100%;height:auto;display:block;margin:0 auto}</style></head><body>${source0.trim()}</body></html>"
        }
        // 2.2.17: CDN Three.js → bundled copy; import map entries for "three" / "three/addons/".
        val (source, importMap) = ThreeAssets.prepare(source0)
        val h = head(allowImages, importMap)
        HEAD_TAG.find(source)?.let { m -> return source.substring(0, m.range.last + 1) + h + source.substring(m.range.last + 1) }
        HTML_TAG.find(source)?.let { m -> return source.substring(0, m.range.last + 1) + "<head>$h</head>" + source.substring(m.range.last + 1) }
        // A fragment: wrap it (a leading doctype is kept first).
        val dt = DOCTYPE.find(source)
        val body = if (dt != null) source.substring(dt.range.last + 1) else source
        return "<!DOCTYPE html><html><head>$h</head><body>$body</body></html>"
    }

    /**
     * Whether the preview may load [url] at all. data: / blob: always; https only for images and only
     * when allowed and not a local / private address; everything else (scripts, fetch, http, file,
     * content, intent…) is blocked.
     */
    fun allowRequest(url: String, allowImages: Boolean, isImage: Boolean): Boolean {
        val u = url.trim().lowercase()
        return when {
            u.startsWith("data:") || u.startsWith("blob:") -> true
            u.startsWith("https://") -> allowImages && isImage && !NetGuard.obviouslyLocal(url.trim())
            else -> false
        }
    }

    private val IMAGE_EXT = Regex("\\.(png|jpe?g|gif|webp|bmp|ico|avif|heic)$", RegexOption.IGNORE_CASE)

    /** The WebView asks for an image (Accept header) or the path ends in an image extension. */
    fun looksLikeImage(url: String, accept: String?): Boolean {
        if (accept != null && accept.contains("image/") && !accept.startsWith("text/html")) return true
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: url.substringBefore('?')
        return IMAGE_EXT.containsMatchIn(path)
    }

    /** A tapped link leaves the preview only to the external browser, and only for http(s) / mailto / tel. */
    fun externalLink(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.startsWith(ThreeAssets.BASE) || u.startsWith("http://${ThreeAssets.HOST}")) return false // relative link inside the preview
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("mailto:") || u.startsWith("tel:")
    }
}

/** Where a markdown image comes from, after the allow rules. */
sealed interface ImageSource {
    data class Remote(val url: String) : ImageSource
    /** A file path: absolute, `Download/IRIDIS/workspace/…`, or relative to the agent workspace. */
    data class LocalFile(val path: String) : ImageSource
    data class Content(val uri: String) : ImageSource
    data class Data(val mime: String, val base64: String) : ImageSource
    data class Blocked(val reason: String) : ImageSource
}

object ImageRefs {
    val NETWORK_OFF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.markdown_c9a595)
    val HTTP_ONLY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.markdown_2d0a38)
    val UNSUPPORTED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.markdown_89b4ff)
    val LOCAL_ADDRESS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.markdown_78a4bf)
    /** ~6 MB of image bytes as base64. */
    const val MAX_DATA_CHARS = 8_000_000

    private val DATA = Regex("^data:(image/(?:png|jpe?g|gif|webp|bmp|x-icon|vnd\\.microsoft\\.icon|heic|avif));base64,([A-Za-z0-9+/=\\s]+)$", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun classify(url: String, allowNetwork: Boolean): ImageSource {
        val u = url.trim()
        val lower = u.lowercase()
        return when {
            u.isEmpty() -> ImageSource.Blocked(UNSUPPORTED)
            lower.startsWith("https://") -> when {
                NetGuard.obviouslyLocal(u) -> ImageSource.Blocked(LOCAL_ADDRESS)
                !allowNetwork -> ImageSource.Blocked(NETWORK_OFF)
                else -> ImageSource.Remote(u)
            }
            lower.startsWith("http://") -> ImageSource.Blocked(HTTP_ONLY)
            lower.startsWith("data:") -> {
                if (u.length > MAX_DATA_CHARS) return ImageSource.Blocked(UNSUPPORTED)
                val m = DATA.matchEntire(u) ?: return ImageSource.Blocked(UNSUPPORTED)
                ImageSource.Data(m.groupValues[1].lowercase(), m.groupValues[2])
            }
            lower.startsWith("content://") -> ImageSource.Content(u)
            lower.startsWith("file://") -> {
                val path = runCatching { java.net.URI(u).path }.getOrNull()
                if (path.isNullOrBlank()) ImageSource.Blocked(UNSUPPORTED) else ImageSource.LocalFile(path)
            }
            // No other scheme (javascript:, intent:, ftp:, …) is allowed.
            SCHEME.containsMatchIn(u) -> ImageSource.Blocked(UNSUPPORTED)
            else -> ImageSource.LocalFile(runCatching { java.net.URLDecoder.decode(u.replace("+", "%2B"), "UTF-8") }.getOrDefault(u))
        }
    }

    private val WORKSPACE_PREFIXES = listOf("Download/IRIDIS/workspace/", "IRIDIS/workspace/", "workspace/")
    private val STORAGE_PREFIX = Regex("^/(?:storage/emulated/\\d+|sdcard)/")

    /** The path inside the workspace that [path] names, for reading through a SAF grant; null = outside / absolute elsewhere. */
    fun workspaceRelative(path: String): String? {
        var p = path.trim().replace('\\', '/')
        p = STORAGE_PREFIX.replace(p, "")
        if (p.startsWith("/")) return null
        for (prefix in WORKSPACE_PREFIXES) if (p.startsWith(prefix)) return p.removePrefix(prefix).trimStart('/')
        return p.removePrefix("./")
    }

    /**
     * The readable file [path] names, only when it lies inside [workspace] or one of [extraRoots]
     * (after resolving `..` and symlinks); null otherwise. Relative paths are taken from the workspace.
     */
    fun resolveLocal(path: String, workspace: File, extraRoots: List<File> = emptyList()): File? {
        val roots = (listOf(workspace) + extraRoots).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        val p = path.trim().replace('\\', '/')
        val candidates = buildList {
            if (p.startsWith("/")) add(File(p))
            workspaceRelative(p)?.let { rel -> if (rel.isNotEmpty()) add(File(workspace, rel)) }
            if (!p.startsWith("/")) add(File(workspace, p))
        }
        for (c in candidates) {
            val f = runCatching { c.canonicalFile }.getOrNull() ?: continue
            if (roots.none { r -> f.path == r.path || f.path.startsWith(r.path + File.separator) }) continue
            if (f.isFile && f.canRead()) return f
        }
        return null
    }

    /** BitmapFactory inSampleSize (power of two) so the longer side is at most about [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var s = 1
        while (maxOf(width, height) / (s * 2) >= maxSide) s *= 2
        return s
    }

    /** MIME type from the first bytes (png / jpeg / gif / webp / bmp); null = unknown. */
    fun mimeOf(head: ByteArray): String? {
        fun b(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        return when {
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
            b(0) == 0xFF && b(1) == 0xD8 -> "image/jpeg"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> "image/gif"
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> "image/webp"
            b(0) == 0x42 && b(1) == 0x4D -> "image/bmp"
            else -> null
        }
    }

    fun extensionOf(mime: String?): String = when (mime) {
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/bmp" -> "bmp"
        else -> "jpg"
    }
}

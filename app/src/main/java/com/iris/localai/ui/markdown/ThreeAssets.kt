package com.iris.localai.ui.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 2.2.17: Three.js r170 (MIT) bundled in `assets/three/` so model-written 3D scenes run in the
 * offline HTML preview. Any CDN URL that points at the `three` package (jsDelivr, unpkg, cdnjs,
 * esm.sh, Skypack, JSPM), whatever version it asks for, is served from the app; every other
 * external script stays blocked ([HtmlSandbox]).
 *
 * Assets (paths inside the package):
 *  - build/three.module.js — ES module (r170 three.module.min.js)
 *  - build/three.min.js    — classic script that sets `window.THREE`; 2.4.1: not shipped, generated from the
 *                            module at first use ([classicFromModule], ~690 KB less in the APK)
 *  - examples/jsm/{controls/OrbitControls,loaders/GLTFLoader,utils/BufferGeometryUtils}.js — ES modules (import bare `three`)
 *  - examples/js/… the same three as classic scripts (THREE.OrbitControls, THREE.GLTFLoader, THREE.BufferGeometryUtils)
 *
 * The preview document is loaded with base [BASE] so module scripts and the import map have a
 * real https origin; the bundled files are addressed as `https://iridis.local/three/<path>`.
 */
object ThreeAssets {
    const val BASE = "https://iridis.local/"
    const val HOST = "iridis.local"
    const val LOCAL_ROOT = BASE + "three/"
    const val MODULE = "build/three.module.js"
    const val CLASSIC = "build/three.min.js"
    const val JSM = "examples/jsm/"
    const val VERSION = "r170"

    val BUNDLED: Set<String> = setOf(
        MODULE, CLASSIC,
        "examples/jsm/controls/OrbitControls.js", "examples/jsm/loaders/GLTFLoader.js", "examples/jsm/utils/BufferGeometryUtils.js",
        "examples/js/controls/OrbitControls.js", "examples/js/loaders/GLTFLoader.js", "examples/js/utils/BufferGeometryUtils.js",
    )

    /** Hosts allowed in the preview's script-src (only `three` files are ever served from them). */
    val CDN_HOSTS = listOf(
        "cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net", "testingcf.jsdelivr.net", "unpkg.com",
        "cdnjs.cloudflare.com", "esm.sh", "cdn.esm.sh", "cdn.skypack.dev", "ga.jspm.io", "jspm.dev",
    )

    private val PKG = Regex("^/three(?:@[^/]*)?(?:/(.*))?$")
    private val JSDELIVR = Regex("^/(?:npm/three(?:@[^/]*)?|gh/mrdoob/three\\.js(?:@[^/]*)?)(?:/(.*))?$")
    private val CDNJS = Regex("^/ajax/libs/three\\.js/[^/]+/(.*)$")
    private val JSPM = Regex("^/npm:three(?:@[^/]*)?(?:/(.*))?$")
    private val ESM_VER = Regex("^/v\\d+(/.*)$")
    private val ESM_BUILD = Regex("^(?:es20\\d\\d|esnext|denonext)/three(?:\\.development)?(?:\\.bundle)?\\.mjs$")

    /**
     * The canonical path inside the bundled package that [url] asks for ("build/three.module.js",
     * "examples/jsm/controls/OrbitControls.js", or a directory such as "examples/jsm/"), or null
     * when [url] is not a `three` file on a known CDN / the local root.
     */
    fun threePath(url: String): String? {
        val u = url.trim()
        val scheme = u.substringBefore("://", "").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val afterScheme = u.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore(':').lowercase()
        var path = "/" + afterScheme.substringAfter('/', "")
        path = path.substringBefore('#').substringBefore('?')
        val rest: String? = when {
            host == HOST -> if (path.startsWith("/three/")) path.removePrefix("/three/") else return null
            host.endsWith("jsdelivr.net") -> JSDELIVR.find(path)?.let { it.groupValues[1] }
            host == "cdnjs.cloudflare.com" -> CDNJS.find(path)?.let { it.groupValues[1] }
            host == "ga.jspm.io" -> JSPM.find(path)?.let { it.groupValues[1] }
            host == "esm.sh" || host == "cdn.esm.sh" -> {
                val p = ESM_VER.find(path)?.groupValues?.get(1) ?: path
                PKG.find(p)?.let { it.groupValues[1] }
            }
            host in CDN_HOSTS -> PKG.find(path)?.let { it.groupValues[1] }
            else -> null
        }
        return rest?.let { canonical(it) }
    }

    private fun canonical(rest0: String): String? {
        val rest = rest0.trimStart('/')
        if (rest.isEmpty() || rest == "+esm" || ESM_BUILD.matches(rest)) return MODULE
        val r = when {
            rest.startsWith("addons/") -> JSM + rest.removePrefix("addons/")
            else -> rest
        }.removeSuffix("/+esm")
        val file = r.substringAfterLast('/')
        return when {
            r.endsWith("/") -> if (r == JSM || r == "examples/js/" || r == "build/") r else null
            r.startsWith("examples/jsm/") || r.startsWith("examples/js/") ->
                r.removeSuffix(".min.js").let { if (it.endsWith(".js")) it else "$it.js" }
            file in MODULE_FILES && (r == file || r == "build/$file") -> MODULE
            file in CLASSIC_FILES && (r == file || r == "build/$file") -> CLASSIC
            r == "src/Three.js" -> MODULE
            else -> null
        }
    }

    private val MODULE_FILES = setOf("three.module.js", "three.module.min.js", "three.core.js", "three.core.min.js", "three.mjs")
    private val CLASSIC_FILES = setOf("three.js", "three.min.js", "three.cjs")

    /** 2.4.1: bundled paths that are not files in `assets/` but built at runtime ([open]). */
    val GENERATED: Set<String> = setOf(CLASSIC)

    /**
     * 2.4.1: the classic build from the ES module: the module body wrapped in an IIFE, its single trailing
     * `export{a as B,…}` turned into `THREE = Object.assign(THREE || {}, {B:a,…})` on `self`/`globalThis`.
     * Byte-identical to the three.min.js shipped up to 2.4.0. Null when [module] does not look like the module build.
     */
    fun classicFromModule(module: String): String? {
        val at = module.lastIndexOf("export{")
        val end = module.lastIndexOf('}')
        val hdrEnd = module.indexOf("*/")
        if (at < 0 || end < at || hdrEnd < 0 || hdrEnd > at) return null
        val header = module.substring(0, minOf(hdrEnd + 3, module.length))
        val pairs = module.substring(at + "export{".length, end).split(',').filter { it.isNotBlank() }.joinToString(",") { p ->
            val parts = p.split(" as ")
            if (parts.size == 2) parts[1].trim() + ":" + parts[0].trim() else p.trim() + ":" + p.trim()
        }
        val g = "(typeof self!=='undefined'?self:globalThis)"
        return header + "(function(){'use strict';\n" + module.substring(0, at) + "\n;" + g + ".THREE=Object.assign(" + g + ".THREE||{},{" + pairs + "});})();\n"
    }

    @Volatile private var classicCache: java.lang.ref.SoftReference<ByteArray>? = null

    /** Opens the bundled [asset] (`three/…`, from [assetFor]); generated files are built from their source with [readAsset]. */
    fun open(asset: String, readAsset: (String) -> java.io.InputStream): java.io.InputStream {
        if (asset.removePrefix("three/") !in GENERATED) return readAsset(asset)
        val bytes = classicCache?.get() ?: run {
            val module = readAsset("three/$MODULE").use { it.readBytes().toString(Charsets.UTF_8) }
            (classicFromModule(module) ?: throw java.io.IOException("three.module.js has no export list")).toByteArray(Charsets.UTF_8)
                .also { classicCache = java.lang.ref.SoftReference(it) }
        }
        return java.io.ByteArrayInputStream(bytes)
    }

    /** The asset file (under `assets/`) to serve for [url], or null when it is not a bundled `three` file. */
    fun assetFor(url: String): String? = threePath(url)?.takeIf { it in BUNDLED }?.let { "three/$it" }

    /** Response headers for a served file: modules are fetched in CORS mode. */
    val HEADERS: Map<String, String> = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
    const val MIME = "text/javascript"

    private val URL_IN_TEXT = Regex("https?://[A-Za-z0-9.-]+(?::\\d+)?/[^\\s\"'`<>()\\\\]*")

    /** Rewrites every `three` CDN URL in [html] to its local address (other URLs are untouched). */
    fun rewrite(html: String): String = URL_IN_TEXT.replace(html) { m ->
        val p = threePath(m.value)
        if (p == null || m.value.startsWith(LOCAL_ROOT)) m.value else LOCAL_ROOT + p
    }

    private val IMPORTMAP = Regex("(<script\\b[^>]*\\btype\\s*=\\s*[\"']?importmap[\"']?[^>]*>)(.*?)(</script\\s*>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val USES_THREE = Regex("(?:from|import)\\s*\\(?\\s*[\"']three(?:/|[\"'])|$LOCAL_ROOT".replace(".", "\\."))

    val DEFAULT_IMPORTS: Map<String, String> = linkedMapOf(
        "three" to LOCAL_ROOT + MODULE,
        "three/addons/" to LOCAL_ROOT + JSM,
        "three/examples/jsm/" to LOCAL_ROOT + JSM,
    )
    private val json = Json { prettyPrint = false }

    /** An import map (JSON) with the `three` entries added where [existing] lacks them; null when [existing] is not valid JSON. */
    fun mergeImportMap(existing: String): String? {
        val root = runCatching { json.parseToJsonElement(existing.trim().ifEmpty { "{}" }).jsonObject }.getOrNull() ?: return null
        val imports = runCatching { root["imports"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val merged = LinkedHashMap(imports)
        DEFAULT_IMPORTS.forEach { (k, v) -> if (k !in merged) merged[k] = JsonPrimitive(v) }
        val out = LinkedHashMap(root); out["imports"] = JsonObject(merged)
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    fun importMapTag(): String = "<script type=\"importmap\">" +
        json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("imports" to JsonObject(DEFAULT_IMPORTS.mapValues { JsonPrimitive(it.value) })))) +
        "</script>"

    /**
     * Prepares an HTML preview: CDN `three` URLs → local, and the page's import map gets the
     * `three` / `three/addons/` entries (merged into the first map, as older WebViews accept only one).
     * Returns the new source and the import map to inject first in <head> (null when none is needed).
     */
    fun prepare(html: String): Pair<String, String?> {
        val src = rewrite(html)
        val m = IMPORTMAP.find(src)
        if (m != null) {
            val merged = mergeImportMap(m.groupValues[2]) ?: return src to null
            return src.replaceRange(m.range, m.groupValues[1] + merged + m.groupValues[3]) to null
        }
        return src to (if (USES_THREE.containsMatchIn(src)) importMapTag() else null)
    }
}

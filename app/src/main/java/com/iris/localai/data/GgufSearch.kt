package com.iris.localai.data

import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 2.2.16 「在线搜索模型」 (PocketPal-style): GGUF repos on Hugging Face (api/models?filter=gguf, through
 * hf-mirror.com or huggingface.co per the download source) and ModelScope (dolphin model search with
 * the `libraries contains gguf` criterion, checked 2026-11-02). Parsing is pure and unit-tested.
 */
object GgufSearch {
    enum class Site(val tag: String) { HF("HF"), MS("魔搭") }

    data class RepoHit(
        val id: String,
        val site: Site,
        val downloads: Long = 0,
        val likes: Long = 0,
        /** "2026-09-29" (empty when unknown). */
        val updated: String = "",
        val license: String? = null,
        /** Needs a login / access request on the site: IRIDIS cannot download it. */
        val gated: Boolean = false,
        /** Parameter count (HF gguf metadata, else from the name like "4B"); 0 = unknown. */
        val params: Long = 0,
        val arch: String? = null,
    ) {
        /** Rough file sizes of the usual quants: Q4 ≈ 0.57 byte/param … Q8 ≈ 1.07. */
        val sizeRange: LongRange? get() = if (params > 0) (params * 57 / 100)..(params * 107 / 100) else null
    }

    /** 2.2.16 browse tabs (shown when the page opens; search uses 热门 order). */
    enum class Browse(val label: String) { HOT("热门"), NEW("最新"), FIT("适合本机") }

    /** One page of a browse / search; [hfNext] = HF cursor query ("" = first page, null = no more). */
    data class Page(val hits: List<RepoHit>, val hfNext: String?, val msNext: Int?, val errors: List<String>)

    data class GgufFile(val path: String, val size: Long) {
        val name: String get() = path.substringAfterLast('/')
        val quant: String? get() = ModelNames.quantization(name.replace(SPLIT, ".gguf"))
        val projector: Boolean get() = isProjectorFile(name)
        val split: Pair<Int, Int>? get() = SPLIT.find(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    /** One downloadable model: a single .gguf or all parts of a split one (first part = what llama.cpp opens). */
    data class Choice(val parts: List<GgufFile>) {
        val main: GgufFile get() = parts.first()
        val size: Long get() = parts.sumOf { it.size }
        val quant: String? get() = main.quant
        val title: String get() = main.name.replace(SPLIT, ".gguf") + if (parts.size > 1) "（${parts.size} 个分卷）" else ""
        val complete: Boolean get() = main.split?.let { (_, n) -> parts.size == n } ?: true
    }

    data class Files(val choices: List<Choice>, val projectors: List<GgufFile>, val license: String? = null, val gated: Boolean = false)

    val SPLIT = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- pure parsing

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.num(): Long = (this as? JsonPrimitive)?.longOrNull ?: (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L

    /** HF gated = false | "auto" | "manual". */
    private fun gatedOf(e: JsonElement?): Boolean = when (val p = e as? JsonPrimitive) {
        null -> false
        else -> p.booleanOrNull ?: (p.contentOrNull?.let { it.isNotBlank() && it != "false" } ?: false)
    }

    private fun licenseOf(o: JsonObject): String? {
        (o["cardData"] as? JsonObject)?.get("license")?.let { l ->
            (l as? JsonArray)?.firstOrNull()?.str()?.let { return it }
            l.str()?.let { return it }
        }
        return (o["tags"] as? JsonArray)?.mapNotNull { it.str() }?.firstOrNull { it.startsWith("license:") }?.removePrefix("license:")
    }

    fun parseHfSearch(body: String): List<RepoHit> = json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] ?: o["modelId"]).str() ?: return@mapNotNull null
        // strict: only repos tagged gguf (when tags are present) and not another format's repo
        val tags = (o["tags"] as? JsonArray)?.mapNotNull { it.str() }
        if (tags != null && "gguf" !in tags) return@mapNotNull null
        if (!looksGguf(id)) return@mapNotNull null
        val g = o["gguf"] as? JsonObject
        RepoHit(
            id, Site.HF, o["downloads"].num(), o["likes"].num(), date(o["lastModified"].str()), licenseOf(o), gatedOf(o["gated"]),
            g?.get("total").num().takeIf { it > 0 } ?: paramsFromName(id), g?.get("architecture").str(),
        )
    }

    /** Not an MNN / LiteRT / MLX / ONNX / GPTQ / AWQ repo (those can carry a stray gguf tag). Pure. */
    fun looksGguf(id: String): Boolean =
        !Regex("(?i)(-mnn$|litert|[-_]mlx|onnx|gptq|[-_]awq|safetensors|openvino|-qnn)").containsMatchIn(id)

    /** "Qwen3-4B-GGUF" → 4e9, "gemma-3-270m" → 2.7e8, "Qwen3-30B-A3B" → 3e10; 0 when absent. Pure. */
    fun paramsFromName(id: String): Long {
        val m = Regex("(?i)(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)([bm])(?![a-z0-9])").findAll(id.substringAfter('/').replace('_', '-')).firstOrNull() ?: return 0
        val v = m.groupValues[1].toDouble()
        return (if (m.groupValues[2].equals("b", true)) v * 1e9 else v * 1e6).toLong()
    }

    /** Estimated Q4 size fits in [ratio] of the phone's RAM. Unknown size → not listed under 适合本机. Pure. */
    fun fits(hit: RepoHit, totalRam: Long, ratio: Double = 0.6): Boolean =
        hit.sizeRange?.let { it.first <= totalRam * ratio } ?: false

    /** ModelScope dolphin search → hits (ApprovalMode ≠ 0 or IsAccessible = 0 → needs approval). */
    fun parseMsSearch(body: String): List<RepoHit> {
        val models = json.parseToJsonElement(body).jsonObject["Data"]?.jsonObject?.get("Model")?.jsonObject?.get("Models") as? JsonArray ?: return emptyList()
        return models.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val path = o["Path"].str() ?: return@mapNotNull null
            val name = o["Name"].str() ?: return@mapNotNull null
            val libs = (o["Libraries"] as? JsonArray)?.mapNotNull { it.str()?.lowercase() }
            if (libs != null && "gguf" !in libs) return@mapNotNull null
            if (!looksGguf("$path/$name")) return@mapNotNull null
            val t = o["LastUpdatedTime"].num()
            RepoHit(
                "$path/$name", Site.MS, o["Downloads"].num(), o["Stars"].num(),
                if (t > 0) java.time.Instant.ofEpochSecond(t).toString().take(10) else "",
                o["License"].str()?.takeIf { it.isNotBlank() },
                o["ApprovalMode"].num() != 0L || (o["IsAccessible"] != null && o["IsAccessible"].num() == 0L),
                paramsFromName(name),
            )
        }
    }

    /** HF repo detail (api/models/<id>) → (license, gated). */
    fun parseHfDetail(body: String): Pair<String?, Boolean> {
        val o = json.parseToJsonElement(body).jsonObject
        return licenseOf(o) to gatedOf(o["gated"])
    }

    fun parseHfTree(body: String): List<Pair<String, Long>> = json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        if (o["type"].str() != "file") return@mapNotNull null
        (o["path"].str() ?: return@mapNotNull null) to ((o["lfs"] as? JsonObject)?.get("size")?.num()?.takeIf { it > 0 } ?: o["size"].num())
    }

    fun parseMsTree(body: String): List<Pair<String, Long>> {
        val files = json.parseToJsonElement(body).jsonObject["Data"]?.jsonObject?.get("Files") as? JsonArray ?: return emptyList()
        return files.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o["Type"].str() != "blob") null else (o["Path"].str() ?: return@mapNotNull null) to o["Size"].num()
        }
    }

    /** "2026-09-29T10:28:00.000Z" → "2026-09-29"; anything else as given. */
    fun date(s: String?): String = when {
        s == null -> ""
        Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s) -> s.take(10)
        else -> s
    }

    /**
     * The .gguf files of a repo → downloadable choices (split parts grouped, sorted by size) and
     * projectors (mmproj, offered only as a vision companion). Pure.
     */
    fun group(files: List<Pair<String, Long>>): Files {
        val gguf = files.filter { it.first.endsWith(".gguf", ignoreCase = true) }.map { GgufFile(it.first, it.second) }
        val projectors = gguf.filter { it.projector }
        val models = gguf.filterNot { it.projector }
        val (split, single) = models.partition { it.split != null }
        val grouped = split.groupBy { it.path.replace(SPLIT, "") + "#" + it.split!!.second }
            .values.map { parts -> Choice(parts.sortedBy { it.split!!.first }) }
            .filter { it.main.split?.first == 1 }
        val choices = (single.map { Choice(listOf(it)) } + grouped).sortedBy { it.size }
        return Files(choices, projectors.sortedBy { it.size })
    }

    /** Local file name of a companion projector, tied to its model so it is found next to it (GenieXVision.findMmproj). */
    fun projectorLocalName(modelFile: String, projector: String): String =
        ModelFiles.sanitizeFileName(modelFile.replace(SPLIT, ".gguf").removeSuffix(".gguf") + "-" + projector.substringAfterLast('/').let {
            if ("mmproj" in it.lowercase()) it else "mmproj-$it"
        })

    /** RAM hint for a model of [bytes] against [availBytes] (4K context, CPU), reusing the load preflight. */
    fun fitHint(bytes: Long, availBytes: Long): String {
        val v = com.iris.localai.engine.MemoryPreflight.check(availBytes, bytes, 4096, gpu = false)
        return when (v.level) {
            com.iris.localai.engine.MemoryPreflight.Level.OK -> "内存够用"
            com.iris.localai.engine.MemoryPreflight.Level.TIGHT -> "内存偏紧"
            com.iris.localai.engine.MemoryPreflight.Level.SHORT -> "内存可能不够"
        }
    }

    fun hfSearchPath(q: String, limit: Int = 20, sort: String = "downloads") =
        "/api/models?" + (if (q.isNotBlank()) "search=${enc(q)}&" else "") + "filter=gguf&sort=$sort&direction=-1&limit=$limit" +
            "&expand%5B%5D=downloads&expand%5B%5D=likes&expand%5B%5D=lastModified&expand%5B%5D=gated&expand%5B%5D=cardData&expand%5B%5D=gguf&expand%5B%5D=tags"

    /** `<https://huggingface.co/api/models?…&cursor=…>; rel="next"` → "/api/models?…&cursor=…" (host-free, works on the mirror). Pure. */
    fun nextFromLink(link: String?): String? {
        val m = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?").find(link ?: return null) ?: return null
        val u = m.groupValues[1]
        return u.substring(u.indexOf("/api/").takeIf { it >= 0 } ?: return null)
    }

    fun msSort(mode: Browse) = if (mode == Browse.NEW) "GmtModified" else "DownloadsCount"
    fun hfSort(mode: Browse) = if (mode == Browse.NEW) "lastModified" else "downloads"

    fun msSearchBody(q: String, size: Int = 20, page: Int = 1, sort: String = "DownloadsCount"): String =
        """{"PageSize":$size,"PageNumber":$page,"SortBy":"$sort","Target":"","SingleCriterion":[],""" +
            """"Criterion":[{"category":"libraries","predicate":"contains","values":["gguf"]}],"Name":${Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), JsonPrimitive(q))}}"""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Hosts for HF api calls, in the user's source order (魔搭 has no HF api → hf-mirror / HF). */
    fun hfHosts(chosen: DownloadSource): List<String> =
        DownloadSources.order(chosen, false, DownloadSources.lastGood).map { it.baseUrl }

    // ---------------------------------------------------------------- network

    private class Cached(val at: Long, val page: Page)
    private val cache = HashMap<String, Cached>()
    private const val CACHE_MS = 5 * 60_000L

    /**
     * One page of 热门 / 最新 / 适合本机 (or a keyword search, [q] non-blank) from both sites in parallel.
     * [hfNext]/[msNext] from the previous page (""/1 = first). 适合本机 = 热门 filtered by [totalRam]. Cached 5 min.
     */
    suspend fun browse(mode: Browse, q: String, chosen: DownloadSource, hfNext: String?, msNext: Int?, totalRam: Long): Page = withContext(Dispatchers.IO) {
        val key = "$mode|$q|$hfNext|$msNext"
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return@withContext it.page } }
        coroutineScope {
            val hf = async {
                if (hfNext == null) null else runCatching {
                    val path = hfNext.ifEmpty { hfSearchPath(q, sort = hfSort(mode)) }
                    val (body, link) = firstOk(hfHosts(chosen)) { getWithLink(it + path) }
                    parseHfSearch(body) to nextFromLink(link)
                }
            }
            val ms = async {
                if (msNext == null) null else runCatching {
                    val body = put("${DownloadSources.MS}/api/v1/dolphin/models", msSearchBody(q, page = msNext, sort = msSort(mode)))
                    parseMsSearch(body) to (msRawCount(body) >= MS_PAGE)
                }
            }
            val h = hf.await(); val m = ms.await()
            val errors = listOfNotNull(
                h?.exceptionOrNull()?.let { "HuggingFace：${DownloadSources.describe(it)}" },
                m?.exceptionOrNull()?.let { "魔搭：${DownloadSources.describe(it)}" },
            )
            val msFirst = chosen == DownloadSource.MODELSCOPE || DownloadSources.lastGood == DownloadSource.MODELSCOPE
            var a = m?.getOrNull()?.first.orEmpty(); var b = h?.getOrNull()?.first.orEmpty()
            if (mode == Browse.FIT) { a = a.filter { fits(it, totalRam) }; b = b.filter { fits(it, totalRam) } }
            val page = Page(
                interleave(if (msFirst) a else b, if (msFirst) b else a),
                hfNext = h?.getOrNull()?.second,
                msNext = if (m?.getOrNull()?.second == true) msNext!! + 1 else null,
                errors = errors,
            )
            if (errors.isEmpty()) synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), page) }
            page
        }
    }

    const val MS_PAGE = 20

    /** Models on a ModelScope page before filtering (a full page = there may be more). Pure. */
    fun msRawCount(body: String): Int =
        (json.parseToJsonElement(body).jsonObject["Data"]?.jsonObject?.get("Model")?.jsonObject?.get("Models") as? JsonArray)?.size ?: 0

    /** a0 b0 a1 b1 … (both sites stay visible near the top). Pure. */
    fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(::add); b.getOrNull(i)?.let(::add) }
    }

    suspend fun files(hit: RepoHit, chosen: DownloadSource): Files = withContext(Dispatchers.IO) {
        when (hit.site) {
            Site.MS -> group(parseMsTree(get("${DownloadSources.MS}/api/v1/models/${hit.id}/repo/files?Recursive=true"))).copy(license = hit.license, gated = hit.gated)
            Site.HF -> {
                val hosts = hfHosts(chosen)
                val (lic, gated) = runCatching { parseHfDetail(firstOk(hosts) { get("$it/api/models/${hit.id}") }) }.getOrDefault(hit.license to hit.gated)
                group(parseHfTree(firstOk(hosts) { get("$it/api/models/${hit.id}/tree/main?recursive=true") }))
                    .copy(license = lic ?: hit.license, gated = gated || hit.gated)
            }
        }
    }

    /** Urls for one file of [hit] (HF: through the download sources incl. 魔搭 when mapped; 魔搭 hit: 魔搭 only). */
    suspend fun urls(hit: RepoHit, path: String, chosen: DownloadSource, demote: DownloadSource? = null): List<SourceUrl> {
        val p = MnnHub.encodePath(path)
        return when (hit.site) {
            Site.MS -> listOf(SourceUrl(DownloadSource.MODELSCOPE, "${DownloadSources.MS}/models/${hit.id}/resolve/master/$p"))
            Site.HF -> DownloadSources.resolve("${DownloadSources.HF}/${hit.id}/resolve/main/$p", chosen, demote)
        }
    }

    private inline fun <T> firstOk(hosts: List<String>, f: (String) -> T): T {
        var last: Exception? = null
        for (h in hosts) try { return f(h) } catch (e: Exception) { last = e }
        throw last ?: IOException("网络错误")
    }

    private fun get(url: String): String = getWithLink(url).first

    private fun getWithLink(url: String): Pair<String, String?> {
        val c = HttpDownload.open(url, 0, timeoutMs = 10_000)
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw IOException("需要登录才能访问（HTTP $code）")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() } to c.getHeaderField("Link")
        } finally { c.disconnect() }
    }

    private fun put(url: String, body: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "IRIS-LocalAI/1.0")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}

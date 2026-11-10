package com.iris.localai.data

import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 2.2.16: where model files come from. huggingface.co is mostly unreachable in mainland China, so
 * 自动 (default) probes the sources and picks the fastest reachable one: 魔搭 ModelScope first when the
 * repo has a verified copy there, then hf-mirror.com (a host swap of huggingface.co), then Hugging Face.
 * Every download falls through to the next source on failure or a 20 s stall, resuming the `.part`
 * file (the same bytes everywhere: the ModelScope copies below were checked file by file).
 */
@kotlinx.serialization.Serializable
enum class DownloadSource(val label: String, val baseUrl: String, val short: String) {
    AUTO(com.iris.localai.I18n.s(com.iris.localai.R.string.data_3e6ca1), "", com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3aed2c)),
    MODELSCOPE(com.iris.localai.I18n.s(com.iris.localai.R.string.data_01529c), "https://www.modelscope.cn", com.iris.localai.I18n.s(com.iris.localai.R.string.data_662005)),
    HF_MIRROR(com.iris.localai.I18n.s(com.iris.localai.R.string.data_50ae18), "https://hf-mirror.com", "hf-mirror"),
    HUGGING_FACE(com.iris.localai.I18n.s(com.iris.localai.R.string.data_941c0b), "https://huggingface.co", "HuggingFace");

    val concrete: Boolean get() = this != AUTO

    companion object {
        /** Concrete sources in the default preference order. */
        val CONCRETE: List<DownloadSource> = listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE)
        fun byName(n: String?): DownloadSource? = entries.firstOrNull { it.name == n }
    }
}

/** One place to fetch a file from. [source] = null: not a Hugging Face file (S3, GitHub…), fetched as is. */
data class SourceUrl(val source: DownloadSource?, val url: String) {
    val label: String get() = source?.label ?: (runCatching { java.net.URI(url).host }.getOrNull() ?: com.iris.localai.I18n.s(com.iris.localai.R.string.data_d911cf))
}

object DownloadSources {
    const val HF = "https://huggingface.co"
    const val MS = "https://www.modelscope.cn"
    /** A stream that delivers nothing for this long counts as stalled (→ next source). */
    const val STALL_MS = 20_000
    const val PROBE_MS = 3_000

    /** A ModelScope copy of a Hugging Face repo; [prefix] = sub-folder holding the same files. */
    data class MsRepo(val id: String, val prefix: String = "")

    /**
     * Hugging Face repo → ModelScope repo. Checked 2026-11-02 against both APIs
     * (HF api/models/<id>/tree, ModelScope api/v1/models/<id>/repo/files): every file present with the same
     * size and, for LFS files, the same SHA-256. MNN = the official Alibaba MNN org (taobao-mnn on HF).
     */
    val MODELSCOPE: Map<String, MsRepo> = buildMap {
        listOf(
            "Qwen3-0.6B-MNN", "Qwen3-1.7B-MNN", "Qwen3-4B-MNN", "Qwen3-8B-MNN",
            "Qwen3.5-0.8B-MNN", "Qwen3.5-2B-MNN", "Qwen3.5-4B-MNN", "Qwen3.5-9B-MNN",
            "Qwen3.5-0.8B-Claude-4.6-Opus-Reasoning-Distilled-MNN",
            "Qwen3.5-2B-Claude-4.6-Opus-Reasoning-Distilled-MNN",
            "Qwen3.5-4B-Claude-4.6-Opus-Reasoning-Distilled-MNN",
        ).forEach { put("taobao-mnn/$it", MsRepo("MNN/$it")) }
        listOf("E2B", "E4B", "12B").forEach { put("litert-community/gemma-4-$it-it-litert-lm", MsRepo("litert-community/gemma-4-$it-it-litert-lm")) }
        // 2.4.4: gated on Hugging Face (Gemma terms), open on ModelScope; the mt69xx files match HF by size (HF hides the SHA of gated files).
        put("litert-community/Gemma3-1B-IT", MsRepo("litert-community/Gemma3-1B-IT"))
        listOf("1.7B", "4B").forEach { put("XHToken/Spark-X2.6-$it-GGUF", MsRepo("XHToken/Spark-X2.6-$it-GGUF")) }
        // 2.4.3: GGUF twins of the MNN entries — same ids on ModelScope, sizes + SHA-256 checked against HF 2026-11-06.
        (listOf("Qwen3.5-0.8B", "Qwen3.5-2B", "Qwen3.5-4B", "Qwen3.5-9B", "Qwen3-0.6B", "Qwen3-1.7B", "Qwen3-4B", "Qwen3-8B").map { "lmstudio-community/$it-GGUF" } +
            listOf("0.8B", "2B", "4B").map { "Jackrong/Qwen3.5-$it-Claude-4.6-Opus-Reasoning-Distilled-GGUF" }).forEach { put(it, MsRepo(it)) }
        put("taobao-mnn/stable-diffusion-v1-5-mnn-general", MsRepo("MNN/stable-diffusion-v1-5-mnn", "general/"))
        put("csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03", MsRepo("csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03"))
    }

    /**
     * The ModelScope copy of [hfRepo]: the verified table, else other taobao-mnn repos under the official
     * MNN org (same team; the expected size still guards every file). Null = hf-mirror / HF only.
     */
    fun modelScopeFor(hfRepo: String): MsRepo? = MODELSCOPE[hfRepo]
        ?: hfRepo.takeIf { it.startsWith("taobao-mnn/") && it.endsWith("-MNN") }?.let { MsRepo("MNN/" + it.substringAfter('/')) }

    data class HfRef(val repo: String, val revision: String, val path: String)

    /** `https://huggingface.co/<owner>/<name>/resolve/<rev>/<path>[#bytes=a-b]` → parts; null for other urls. */
    fun parseHf(url: String): HfRef? {
        val m = Regex("^https://(?:huggingface\\.co|hf-mirror\\.com)/([^/]+/[^/]+)/resolve/([^/]+)/(.+)$").find(url.substringBefore('#')) ?: return null
        return HfRef(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** [hfUrl] on [source]; null when that source has no copy (ModelScope without a mapping). Pure. */
    fun urlOn(source: DownloadSource, hfUrl: String): String? {
        val ref = parseHf(hfUrl) ?: return null
        val frag = hfUrl.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        return when (source) {
            DownloadSource.AUTO -> null
            DownloadSource.HUGGING_FACE, DownloadSource.HF_MIRROR ->
                "${source.baseUrl}/${ref.repo}/resolve/${ref.revision}/${ref.path}$frag"
            // ModelScope has no HF commit hashes: its master holds the checked copy (sizes guard drift).
            DownloadSource.MODELSCOPE -> modelScopeFor(ref.repo)?.let { "$MS/models/${it.id}/resolve/master/${it.prefix}${ref.path}$frag" }
        }
    }

    /**
     * Sources to try, in order, before probing. A manual choice goes first and the others follow as
     * fallbacks; 自动 = ModelScope (when mapped) → hf-mirror → HF, with the last source that worked moved
     * to the front. [demote] (换源重试) goes last. Pure.
     */
    fun order(chosen: DownloadSource, hasModelScope: Boolean, lastGood: DownloadSource? = null, demote: DownloadSource? = null): List<DownloadSource> {
        val base = DownloadSource.CONCRETE.filter { it != DownloadSource.MODELSCOPE || hasModelScope }
        var list = when {
            chosen.concrete && chosen in base -> listOf(chosen) + (base - chosen)
            lastGood != null && lastGood in base -> listOf(lastGood) + (base - lastGood)
            else -> base
        }
        if (demote != null && demote in list && list.size > 1) list = (list - demote) + demote
        return list
    }

    /** Concrete urls for [url] in [order]; a non-HF url is used as it is. Pure. */
    fun candidates(url: String, order: List<DownloadSource>): List<SourceUrl> {
        if (parseHf(url) == null) return listOf(SourceUrl(null, url))
        return order.mapNotNull { s -> urlOn(s, url)?.let { SourceUrl(s, it) } }
    }

    /**
     * 自动 after probing: the preferred source wins when it is reachable and not much slower
     * (≤ fastest + [slackMs]); the other reachable ones follow fastest first; unreachable ones stay last
     * (in [order]) so a flaky probe never removes a source. [latency] null = unreachable. Pure.
     */
    fun rank(order: List<DownloadSource>, latency: Map<DownloadSource, Long?>, slackMs: Long = 800): List<DownloadSource> {
        val ok = order.filter { latency[it] != null }
        if (ok.isEmpty()) return order
        val fastest = ok.minOf { latency[it]!! }
        val lead = ok.first { latency[it]!! <= fastest + slackMs }
        val rest = (ok - lead).sortedBy { latency[it]!! }
        return listOf(lead) + rest + order.filter { latency[it] == null }
    }

    // ---- state shared by every downloader ----

    /** Last source that finished a file; persisted by IrisApp (AppSettings.lastGoodSource). */
    @Volatile var lastGood: DownloadSource? = null
    @Volatile var onLastGood: (DownloadSource) -> Unit = {}

    private class Probe(val at: Long, val ms: Long?)
    private val probes = HashMap<String, Probe>()
    private const val PROBE_TTL = 10 * 60_000L

    /** Plain-language reason for a failed attempt (shown with the source name). */
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> com.iris.localai.I18n.s(com.iris.localai.R.string.data_a749ad)
        is SocketTimeoutException -> if (e.message?.contains("connect", true) == true) com.iris.localai.I18n.s(com.iris.localai.R.string.data_5f4aa5) else com.iris.localai.I18n.s(com.iris.localai.R.string.data_253482, STALL_MS / 1000)
        is ConnectException -> com.iris.localai.I18n.s(com.iris.localai.R.string.data_757a73)
        is SSLException -> com.iris.localai.I18n.s(com.iris.localai.R.string.data_d93e0d)
        else -> e.message?.takeIf { it.isNotBlank() }?.substringBefore("：http")?.take(80) ?: com.iris.localai.I18n.s(com.iris.localai.R.string.data_0bffc6)
    }

    /** ~3 s Range 0-0 request; round-trip ms, or null when unreachable / not there. */
    fun probeOnce(url: String): Long? {
        val t0 = System.nanoTime()
        return try {
            val c = HttpDownload.open(url.substringBefore('#'), 0, 0, timeoutMs = PROBE_MS)
            try {
                val code = c.responseCode
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) (System.nanoTime() - t0) / 1_000_000 else null
            } finally { c.disconnect() }
        } catch (_: Exception) { null }
    }

    /** Orders [cands] for 自动 by probing them in parallel (cached 10 min per source + repo). */
    suspend fun probeOrder(cands: List<SourceUrl>, probe: (String) -> Long? = ::probeOnce): List<SourceUrl> {
        val hf = cands.filter { it.source != null }
        if (hf.size < 2) return cands
        val now = System.currentTimeMillis()
        val lat = coroutineScope {
            hf.map { c ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    val key = c.source!!.name + "|" + (parseHf(c.url)?.repo ?: c.url.substringBefore("/resolve/"))
                    val cached = synchronized(probes) { probes[key]?.takeIf { now - it.at < PROBE_TTL } }
                    val ms = cached?.ms ?: withTimeoutOrNull(PROBE_MS + 500L) { probe(c.url) }
                    if (cached == null) synchronized(probes) { probes[key] = Probe(now, ms) }
                    c.source to ms
                }
            }.awaitAll().toMap()
        }
        val ranked = rank(hf.map { it.source!! }, lat)
        return ranked.map { s -> hf.first { it.source == s } }
    }

    /**
     * Fetches one file trying [cands] in order. The `.part` file is kept between sources,
     * so the next one resumes (HTTP Range) — every source serves the same bytes; the expected size guards
     * that. [onSwitch] gets a Chinese line when moving on. Throws with every source's failure.
     * 2.5.0-beta8: a failed try that still grew `.part` ([HttpDownload.partFile]) resets that source's count,
     * so a big model on a flaky phone network keeps resuming on the same source instead of failing at 30–50 %
     * after two stalls. [attemptsPerSource] tries in a row without progress move on to the next source (as
     * before); [maxAttemptsPerSource] failed tries in total on one source stop it either way (no endless loop).
     * Back-off [retryDelayMs] × n, at most [MAX_RETRY_DELAY_MS]. SizeMismatch / NotFound skip the source at once.
     */
    suspend fun fetchAny(
        cands: List<SourceUrl>,
        target: File,
        expected: Long,
        onBytes: (Long) -> Unit,
        fetch: suspend (String, File, Long, (Long) -> Unit) -> Unit,
        onSource: (SourceUrl) -> Unit = {},
        onSwitch: (String) -> Unit = {},
        retryDelayMs: Long = 1500,
        attemptsPerSource: Int = 2,
        maxAttemptsPerSource: Int = MAX_ATTEMPTS_PER_SOURCE,
    ) {
        if (cands.isEmpty()) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_cde279))
        val errors = ArrayList<String>()
        val part = HttpDownload.partFile(target)
        for ((i, c) in cands.withIndex()) {
            onSource(c)
            var last: Throwable? = null
            var stuck = 0 // failed tries in a row without progress
            var total = 0 // failed tries on this source
            while (true) {
                val before = part.length()
                try {
                    fetch(c.url, target, expected, onBytes)
                    c.source?.let { if (lastGood != it) { lastGood = it; onLastGood(it) } }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    last = e
                    if (e is SizeMismatch || e is NotFound) break // another try on this source cannot help
                    total++
                    stuck = if (part.length() > before) 0 else stuck + 1
                    if (stuck >= attemptsPerSource || total >= maxAttemptsPerSource) break
                    delay(retryDelay(retryDelayMs, total))
                }
            }
            val why = describe(last ?: IOException())
            errors += com.iris.localai.I18n.s(com.iris.localai.R.string.chat_b9fef6, c.label, why)
            cands.getOrNull(i + 1)?.let { onSwitch(com.iris.localai.I18n.s(com.iris.localai.R.string.data_0c59da, c.label, why, it.label)) }
        }
        throw IOException(if (cands.size == 1) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ce1a3a, errors.single()) else com.iris.localai.I18n.s(com.iris.localai.R.string.data_300628) + errors.joinToString("\n"))
    }

    /** 2.5.0-beta8: failed tries in total on one source before giving it up, progress or not. */
    const val MAX_ATTEMPTS_PER_SOURCE = 20
    const val MAX_RETRY_DELAY_MS = 30_000L

    /** Back-off before try n+1 after [failures] failures: base × failures, capped at [MAX_RETRY_DELAY_MS]. */
    fun retryDelay(baseMs: Long, failures: Int): Long = (baseMs * failures).coerceIn(0L, MAX_RETRY_DELAY_MS)

    /** Thrown when a source serves a different-sized file (another version): skip it, keep nothing from it. */
    class SizeMismatch(msg: String) : IOException(msg)
    class NotFound(msg: String) : IOException(msg)

    /** For 自动: candidates of [url] ordered by probe; otherwise the plain order. */
    suspend fun resolve(url: String, chosen: DownloadSource, demote: DownloadSource? = null, probe: (String) -> Long? = ::probeOnce): List<SourceUrl> {
        val ref = parseHf(url)
        val ord = order(chosen, ref != null && modelScopeFor(ref.repo) != null, lastGood, demote)
        val c = candidates(url, ord)
        return if (chosen == DownloadSource.AUTO && demote == null) probeOrder(c, probe) else c
    }
}

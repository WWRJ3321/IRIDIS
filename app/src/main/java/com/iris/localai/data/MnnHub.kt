package com.iris.localai.data

import android.os.StatFs
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Downloads a whole MNN model repository (e.g. taobao-mnn/Qwen3-1.7B-MNN) from Hugging Face or its
 * mirror into `models/<repo name>/`. Files land in `<repo name>.part/` (each one resumable) and the
 * folder is renamed only after every file has its exact size, so a half download is never listed.
 */
class MnnHub(private val repo: ModelRepository) {

    data class State(
        val repoId: String = "",
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        val file: String = "",
        val active: Boolean = false,
        val error: String? = null,
        /** 2.2.16: source in use (「魔搭 ModelScope」…) and the last switch notice. */
        val source: String = "",
        val notice: String? = null,
        /** 2.2.17 C: stopped by 「暂停」; the .part files stay and 「继续」 resumes them. */
        val paused: Boolean = false,
    ) {
        val progress: Float get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Serializable
    private data class TreeEntry(val type: String = "", val path: String = "", val size: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class MsFiles(val Data: MsData? = null)
    @Serializable private data class MsData(val Files: List<MsFile> = emptyList())
    @Serializable private data class MsFile(val Path: String = "", val Size: Long = 0, val Type: String = "")

    /** Sources for this download, best first (set after listing); the first one is demoted by 换源重试. */
    @Volatile private var sources: List<DownloadSource> = emptyList()
    @Volatile private var lastRequest: Pair<String, DownloadSource>? = null

    suspend fun download(input: String, source: DownloadSource, demote: DownloadSource? = null): LocalModel = withContext(Dispatchers.IO) {
        val repoId = MnnModels.parseRepoId(input) ?: throw IOException("仓库名格式应为 作者/名称，例如 taobao-mnn/Qwen3-1.7B-MNN")
        val name = ModelFiles.sanitizeFileName(repoId.substringAfter('/'))
        val modelsDir = repo.modelsDir // resolved once: shared Download/IRIDIS/models when available
        val target = File(modelsDir, name)
        if (target.exists() || repo.find(name) != null) throw IOException("已存在同名模型：$name")
        val partName = name + ModelRepository.PART_SUFFIX
        if (!repo.claimPart(partName)) throw IOException("$repoId 正在下载中")
        _state.value = State(repoId = repoId, active = true, file = "读取文件列表…")
        try {
            // 2.2.16: list the repo on every candidate source (自动: in parallel, the listing doubles as the
            // speed probe); files then come from the ranked sources, each falling through to the next.
            val (order, all) = listAll(repoId, source, demote)
            sources = order
            // 1.12.1: a LiteRT-LM repo (litert-community/…) — download its one generic .litertlm file.
            pickLiteRtFile(all.filter { it.type == "file" }.map { it.path to it.size })?.let { (path, size) ->
                repo.releasePart(partName)
                return@withContext downloadSingle(order, repoId, path, size)
            }
            // 2.2.12: a GGUF repo (no MNN set) — download its one Q4 .gguf file.
            if (!MnnModels.isModelFileSet(all.filter { it.type == "file" && '/' !in it.path }.map { it.path }.toSet())) {
                pickGgufFile(all.filter { it.type == "file" }.map { it.path to it.size })?.let { (path, size) ->
                    repo.releasePart(partName)
                    return@withContext downloadSingle(order, repoId, path, size)
                }
            }
            val files = all.filter { it.type == "file" && MnnModels.shouldCopy(it.path) }
            if (!MnnModels.isModelFileSet(files.filter { '/' !in it.path }.map { it.path }.toSet())) {
                throw IOException("$repoId 不是 MNN 大模型仓库（缺少 config.json / llm.mnn）。请选 taobao-mnn 下以 -MNN 结尾的仓库")
            }
            val total = files.sumOf { it.size }
            val part = File(modelsDir, partName).apply { mkdirs() }
            val have = files.sumOf { f -> File(part, f.path).takeIf { it.exists() }?.length()?.coerceAtMost(f.size) ?: 0L }
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < total - have + 256L * 1024 * 1024) {
                throw IOException("存储空间不足：需要 ${Format.bytes(total - have)}，可用 ${Format.bytes(free)}")
            }
            var done = have
            _state.value = _state.value.copy(totalBytes = total, doneBytes = done)
            for (f in files) {
                coroutineContext.ensureActive()
                val out = File(part, f.path).apply { parentFile?.mkdirs() }
                if (out.exists() && out.length() == f.size) continue
                val before = done - (HttpDownload.partFile(out).takeIf { it.exists() }?.length() ?: 0L)
                _state.value = _state.value.copy(file = f.path)
                fetch(urlsFor(repoId, f.path, order), out, f.size) { got -> done = before + got; _state.value = _state.value.copy(doneBytes = done) }
                done = before + f.size
            }
            if (!part.renameTo(target)) throw IOException("无法写入模型目录")
            _state.value = State()
        } catch (t: Throwable) {
            _state.value = stopped(repoId, t)
            throw t
        } finally {
            repo.releasePart(partName)
        }
        repo.refresh()
        LocalModel(target.name, target.absolutePath, MnnModels.folderSize(target), ModelFormat.MNN)
    }

    /** One .litertlm file into models/ (resumable through its .part), listed only when complete. */
    private suspend fun downloadSingle(order: List<DownloadSource>, repoId: String, path: String, size: Long): LocalModel {
        val fileName = ModelFiles.sanitizeFileName(path.substringAfterLast('/'))
        val modelsDir = repo.modelsDir
        val target = File(modelsDir, fileName)
        if (target.exists() || repo.find(fileName) != null) throw IOException("已存在同名模型：$fileName")
        val partName = fileName + ModelRepository.PART_SUFFIX
        if (!repo.claimPart(partName)) throw IOException("$repoId 正在下载中")
        try {
            val have = HttpDownload.partFile(target).takeIf { it.exists() }?.length() ?: 0L
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < size - have + 256L * 1024 * 1024) throw IOException("存储空间不足：需要 ${Format.bytes(size - have)}，可用 ${Format.bytes(free)}")
            _state.value = _state.value.copy(totalBytes = size, doneBytes = have, file = fileName)
            // fetchOnce writes target.part and renames it when complete, so a half file is never listed.
            fetch(urlsFor(repoId, path, order), target, size) { got -> _state.value = _state.value.copy(doneBytes = got) }
            _state.value = State()
        } finally {
            repo.releasePart(partName)
        }
        repo.refresh()
        val format = if (fileName.endsWith(".gguf", ignoreCase = true)) ModelFormat.GGUF else ModelFormat.LITERT
        return LocalModel(target.name, target.absolutePath, target.length(), format)
    }

    private fun urlsFor(repoId: String, path: String, order: List<DownloadSource>): List<SourceUrl> =
        DownloadSources.candidates("${DownloadSources.HF}/$repoId/resolve/main/${encodePath(path)}", order)

    /**
     * File list of [repoId] plus the source order to download from. 自动: every source listed in parallel
     * (~6 s each), ranked by response time ([DownloadSources.rank]); a manual choice is tried first.
     */
    private suspend fun listAll(repoId: String, chosen: DownloadSource, demote: DownloadSource?): Pair<List<DownloadSource>, List<TreeEntry>> {
        val ms = DownloadSources.modelScopeFor(repoId)
        val order = DownloadSources.order(chosen, ms != null, DownloadSources.lastGood, demote)
        val results = kotlinx.coroutines.coroutineScope {
            order.map { src ->
                async(Dispatchers.IO) {
                    val t0 = System.nanoTime()
                    val r = try { Result.success(listOn(src, repoId, ms)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                    Triple(src, (System.nanoTime() - t0) / 1_000_000, r)
                }
            }.awaitAll()
        }
        val lat = results.associate { (s, ms0, r) -> s to (if (r.isSuccess && r.getOrThrow().isNotEmpty()) ms0 else null) }
        val ranked = if (chosen == DownloadSource.AUTO && demote == null) DownloadSources.rank(order, lat) else order
        val first = ranked.firstNotNullOfOrNull { s -> results.first { it.first == s }.third.getOrNull()?.takeIf { it.isNotEmpty() } }
            ?: throw IOException("无法获取 $repoId 的文件列表：\n" + results.joinToString("\n") { (s, _, r) ->
                "${s.label}：" + (r.exceptionOrNull()?.let { DownloadSources.describe(it) } ?: "没有文件")
            })
        return ranked to first
    }

    private fun listOn(src: DownloadSource, repoId: String, ms: DownloadSources.MsRepo?): List<TreeEntry> = when (src) {
        DownloadSource.MODELSCOPE -> if (ms == null) emptyList() else listModelScope(ms)
        else -> listFiles(src.baseUrl, repoId)
    }

    private fun listModelScope(ms: DownloadSources.MsRepo): List<TreeEntry> {
        val conn = HttpDownload.open("${DownloadSources.MS}/api/v1/models/${ms.id}/repo/files?Recursive=true", 0, timeoutMs = 6_000)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return modelScopeTree(json.decodeFromString<MsFiles>(body).Data?.Files.orEmpty().map { Triple(it.Path, it.Size, it.Type) }, ms.prefix)
                .map { (p, sz) -> TreeEntry("file", p, sz) }
        } finally {
            conn.disconnect()
        }
    }

    private fun listFiles(base: String, repoId: String): List<TreeEntry> {
        val conn = HttpDownload.open("$base/api/models/$repoId/tree/main?recursive=true", 0, timeoutMs = 6_000)
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == 401) throw IOException("找不到仓库 $repoId")
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString<List<TreeEntry>>(body)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun fetch(cands: List<SourceUrl>, target: File, expected: Long, onBytes: (Long) -> Unit) {
        DownloadSources.fetchAny(
            cands, target, expected, onBytes,
            fetch = { u, t, e, b -> HttpDownload.fetchOnce(u, t, e, b) },
            onSource = { c -> _state.value = _state.value.copy(source = c.label) },
            onSwitch = { msg -> _state.value = _state.value.copy(notice = msg) },
        )
    }

    @Volatile private var job: kotlinx.coroutines.Job? = null

    /** Runs in the app scope so the download survives leaving the models screen. */
    fun start(scope: kotlinx.coroutines.CoroutineScope, input: String, source: DownloadSource, demote: DownloadSource? = null, onDone: suspend (LocalModel) -> Unit) {
        if (job?.isActive == true) return
        lastRequest = input to source
        retry = { d -> start(scope, input, source, d, onDone) }
        job = scope.launch {
            try { onDone(download(input, source, demote)) } catch (_: Throwable) { /* reported through state */ }
        }
    }

    /** 2.2.16 「换源重试」: the failed download again, its first source moved to the end (resumes the .part files). */
    fun retryOtherSource(@Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope, @Suppress("UNUSED_PARAMETER") onDone: suspend (LocalModel) -> Unit) {
        val failed = DownloadSource.CONCRETE.firstOrNull { it.label == _state.value.source } ?: sources.firstOrNull()
        retry?.invoke(failed ?: DownloadSource.HF_MIRROR)
    }

    @Volatile private var retry: ((DownloadSource?) -> Unit)? = null

    /**
     * 2.2.16 「在线搜索模型」: one GGUF (all parts of a split one) plus an optional vision projector, from a
     * search hit, into models/ — same resumable, source-switching fetch; listed once every file is complete.
     * [files] = (remote path, size, local name); the first one is the model.
     */
    fun startGguf(
        scope: kotlinx.coroutines.CoroutineScope, hit: GgufSearch.RepoHit, files: List<Triple<String, Long, String>>,
        source: DownloadSource, demote: DownloadSource? = null, onDone: suspend (LocalModel) -> Unit,
    ) {
        if (job?.isActive == true) return
        retry = { d -> startGguf(scope, hit, files, source, d, onDone) }
        job = scope.launch {
            try { onDone(downloadGguf(hit, files, source, demote)) } catch (_: Throwable) { /* reported through state */ }
        }
    }

    private suspend fun downloadGguf(hit: GgufSearch.RepoHit, files: List<Triple<String, Long, String>>, source: DownloadSource, demote: DownloadSource?): LocalModel = withContext(Dispatchers.IO) {
        if (hit.gated) throw IOException(GATED)
        val modelsDir = repo.modelsDir
        val mainName = files.first().third
        if (File(modelsDir, mainName).exists() || repo.find(mainName) != null) throw IOException("已存在同名模型：$mainName")
        val claimed = files.map { it.third + ModelRepository.PART_SUFFIX }
        if (!claimed.all { repo.claimPart(it) }) { claimed.forEach { repo.releasePart(it) }; throw IOException("${hit.id} 正在下载中") }
        val total = files.sumOf { it.second }
        _state.value = State(repoId = hit.id, active = true, totalBytes = total, file = mainName)
        try {
            val have = files.sumOf { (_, size, name) ->
                val t = File(modelsDir, name)
                if (t.exists() && t.length() == size) size else HttpDownload.partFile(t).takeIf { it.exists() }?.length()?.coerceAtMost(size) ?: 0L
            }
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < total - have + 256L * 1024 * 1024) throw IOException("存储空间不足：需要 ${Format.bytes(total - have)}，可用 ${Format.bytes(free)}")
            var done = have
            _state.value = _state.value.copy(doneBytes = done)
            // the model's parts and the projector are written with .part first; the main file goes last so the
            // model is only listed once everything it needs is on disk
            for ((path, size, name) in files.drop(1) + files.first()) {
                coroutineContext.ensureActive()
                val out = File(modelsDir, name)
                if (out.exists() && out.length() == size) continue
                val before = done - (HttpDownload.partFile(out).takeIf { it.exists() }?.length() ?: 0L)
                _state.value = _state.value.copy(file = name)
                try {
                    fetch(GgufSearch.urls(hit, path, source, demote), out, size) { got -> done = before + got; _state.value = _state.value.copy(doneBytes = done) }
                } catch (e: DownloadSources.NotFound) {
                    throw IOException(GATED)
                }
                done = before + size
            }
            _state.value = State()
        } catch (t: Throwable) {
            _state.value = stopped(hit.id, t)
            throw t
        } finally {
            claimed.forEach { repo.releasePart(it) }
        }
        repo.refresh()
        val main = File(modelsDir, mainName)
        LocalModel(main.name, main.absolutePath, files.filterNot { isProjectorFile(it.third) }.sumOf { it.second }, ModelFormat.GGUF)
    }

    val canRetry: Boolean get() = retry != null

    /** User 「取消」: stop and clear the card (the .part files stay, a later download of the same model resumes). */
    fun cancel() { pausing = false; job?.cancel() }

    /** 2.2.17 C 「暂停」: stop, keep the progress; [resume] continues from the .part files on the same sources. */
    fun pause() { if (job?.isActive == true) { pausing = true; job?.cancel() } }
    fun resume() { if (job?.isActive != true) retry?.invoke(null) }

    @Volatile private var pausing = false

    /** State after a download ended with [t]: paused, cancelled (cleared) or failed. */
    private fun stopped(repoId: String, t: Throwable): State {
        val cur = _state.value
        return if (t is kotlinx.coroutines.CancellationException) {
            if (pausing) { pausing = false; State(repoId = repoId, doneBytes = cur.doneBytes, totalBytes = cur.totalBytes, source = cur.source, paused = true) } else State()
        } else State(repoId = repoId, error = t.message ?: "下载失败", source = cur.source)
    }

    fun clearError() { if (!_state.value.active) _state.value = State() }

    companion object {
        const val GATED = "这个仓库需要登录或申请访问权限，IRIDIS 暂不支持下载。请换一个仓库（例如 unsloth、bartowski、lmstudio-community 的同名模型）"

        /** 2.2.12: the Q4 .gguf of a GGUF repo (Q4_K_M, else Q4_0, else any Q4); no mmproj. Pure. */
        fun pickGgufFile(files: List<Pair<String, Long>>): Pair<String, Long>? {
            val g = files.filter { it.first.endsWith(".gguf", ignoreCase = true) && '/' !in it.first && !it.first.contains("mmproj", ignoreCase = true) }
            return g.firstOrNull { it.first.contains("Q4_K_M", true) } ?: g.firstOrNull { it.first.contains("Q4_0", true) }
                ?: g.firstOrNull { it.first.contains("Q4", true) }
        }

        /**
         * The generic .litertlm of a LiteRT-LM repo: not -web/-gpu/.task, not a chip-specific build
         * (`_qualcomm_…`, `_Google_Tensor_…`); null when the repo has none. Pure.
         */
        fun pickLiteRtFile(files: List<Pair<String, Long>>): Pair<String, Long>? {
            val lm = files.filter { it.first.endsWith(".litertlm", ignoreCase = true) && '/' !in it.first }
            if (lm.isEmpty()) return null
            val generic = lm.filter { (p, _) -> val stem = p.removeSuffix(".litertlm"); !stem.contains('_') && !Regex("(?i)-(web|gpu)$").containsMatchIn(stem) }
            return (generic.ifEmpty { lm }).minByOrNull { it.first.length }
        }

        /** ModelScope repo/files → (path without [prefix], size) of the files under [prefix]. Pure. */
        fun modelScopeTree(files: List<Triple<String, Long, String>>, prefix: String): List<Pair<String, Long>> =
            files.filter { it.third == "blob" && it.first.startsWith(prefix) }.map { it.first.removePrefix(prefix) to it.second }

        fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }
}

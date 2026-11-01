package com.iris.localai.data

import android.os.StatFs
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.Dispatchers
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
    ) {
        val progress: Float get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Serializable
    private data class TreeEntry(val type: String = "", val path: String = "", val size: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun download(input: String, source: DownloadSource): LocalModel = withContext(Dispatchers.IO) {
        val repoId = MnnModels.parseRepoId(input) ?: throw IOException("仓库名格式应为 作者/名称，例如 taobao-mnn/Qwen3-1.7B-MNN")
        val name = ModelFiles.sanitizeFileName(repoId.substringAfter('/'))
        val modelsDir = repo.modelsDir // resolved once: shared Download/IRIDIS/models when available
        val target = File(modelsDir, name)
        if (target.exists() || repo.find(name) != null) throw IOException("已存在同名模型：$name")
        val partName = name + ModelRepository.PART_SUFFIX
        if (!repo.claimPart(partName)) throw IOException("$repoId 正在下载中")
        _state.value = State(repoId = repoId, active = true, file = "读取文件列表…")
        try {
            // Mirror first when chosen; fall back to the other host (mirrors sometimes lag on new repos).
            val bases = listOf(source.baseUrl) + DownloadSource.entries.map { it.baseUrl }.filter { it != source.baseUrl }
            var listing: Pair<String, List<TreeEntry>>? = null
            var lastErr: Exception? = null
            for (b in bases) {
                try { listing = b to listFiles(b, repoId); break } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastErr = e
                }
            }
            val (base, all) = listing ?: throw IOException("无法获取 $repoId 的文件列表：${lastErr?.message ?: "网络错误"}")
            // 1.12.1: a LiteRT-LM repo (litert-community/…) — download its one generic .litertlm file.
            pickLiteRtFile(all.filter { it.type == "file" }.map { it.path to it.size })?.let { (path, size) ->
                repo.releasePart(partName)
                return@withContext downloadSingle(base, repoId, path, size)
            }
            // 2.2.12: a GGUF repo (no MNN set) — download its one Q4 .gguf file.
            if (!MnnModels.isModelFileSet(all.filter { it.type == "file" && '/' !in it.path }.map { it.path }.toSet())) {
                pickGgufFile(all.filter { it.type == "file" }.map { it.path to it.size })?.let { (path, size) ->
                    repo.releasePart(partName)
                    return@withContext downloadSingle(base, repoId, path, size)
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
                val url = "$base/$repoId/resolve/main/${encodePath(f.path)}"
                fetch(url, out, f.size) { got -> done = before + got; _state.value = _state.value.copy(doneBytes = done) }
                done = before + f.size
            }
            if (!part.renameTo(target)) throw IOException("无法写入模型目录")
            _state.value = State()
        } catch (t: Throwable) {
            _state.value = State(repoId = repoId, error = if (t is kotlinx.coroutines.CancellationException) null else (t.message ?: "下载失败"))
            throw t
        } finally {
            repo.releasePart(partName)
        }
        repo.refresh()
        LocalModel(target.name, target.absolutePath, MnnModels.folderSize(target), ModelFormat.MNN)
    }

    /** One .litertlm file into models/ (resumable through its .part), listed only when complete. */
    private suspend fun downloadSingle(base: String, repoId: String, path: String, size: Long): LocalModel {
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
            fetch("$base/$repoId/resolve/main/${encodePath(path)}", target, size) { got -> _state.value = _state.value.copy(doneBytes = got) }
            _state.value = State()
        } finally {
            repo.releasePart(partName)
        }
        repo.refresh()
        val format = if (fileName.endsWith(".gguf", ignoreCase = true)) ModelFormat.GGUF else ModelFormat.LITERT
        return LocalModel(target.name, target.absolutePath, target.length(), format)
    }

    private fun listFiles(base: String, repoId: String): List<TreeEntry> {
        val conn = HttpDownload.open("$base/api/models/$repoId/tree/main?recursive=true", 0)
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

    private suspend fun fetch(url: String, target: File, expected: Long, onBytes: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                HttpDownload.fetchOnce(url, target, expected, onBytes)
                return
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                delay(1500L * attempt)
            }
        }
    }

    @Volatile private var job: kotlinx.coroutines.Job? = null

    /** Runs in the app scope so the download survives leaving the models screen. */
    fun start(scope: kotlinx.coroutines.CoroutineScope, input: String, source: DownloadSource, onDone: suspend (LocalModel) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try { onDone(download(input, source)) } catch (_: Throwable) { /* reported through state */ }
        }
    }

    fun cancel() { job?.cancel() }

    fun clearError() { if (!_state.value.active) _state.value = State() }

    companion object {
        /**
         * 2.2.12: the official list in 下载模型, by category. Every repo id checked on the HF API 2026-11-01
         * (sizes = what the download fetches). [Rec.imageModel] = a 生图 model id (downloaded by ImageGenDownloader).
         * Left out: Qwen3.5-9B-Claude-4.6-Opus-Reasoning-Distilled-MNN (repo empty on 2026-11-01); LFM3 (needs a newer llama.cpp).
         */
        data class Rec(val id: String, val title: String, val note: String, val imageModel: String? = null)
        data class RecGroup(val title: String, val items: List<Rec>)

        private fun mnn(id: String, t: String, note: String) = Rec("taobao-mnn/$id", t, note)
        private const val OPUS = "-Claude-4.6-Opus-Reasoning-Distilled-MNN"
        val RECOMMENDED_GROUPS: List<RecGroup> = listOf(
            RecGroup("Qwen3（MNN）", listOf(
                mnn("Qwen3-0.6B-MNN", "Qwen3 0.6B", "MNN · 约 0.45 GB · 最快，适合低配"),
                mnn("Qwen3-1.7B-MNN", "Qwen3 1.7B", "MNN · 约 1.2 GB · 轻快日常"),
                mnn("Qwen3-4B-MNN", "Qwen3 4B", "MNN · 约 2.7 GB · 均衡"),
                mnn("Qwen3-8B-MNN", "Qwen3 8B", "MNN · 约 5.5 GB · 更强，需 12 GB 内存"),
            )),
            RecGroup("Qwen3.5（MNN）", listOf(
                mnn("Qwen3.5-0.8B-MNN", "Qwen3.5 0.8B", "MNN · 约 0.55 GB · 最快"),
                mnn("Qwen3.5-2B-MNN", "Qwen3.5 2B", "MNN · 约 1.4 GB · 轻快日常"),
                mnn("Qwen3.5-4B-MNN", "Qwen3.5 4B", "MNN · 约 2.9 GB · 推荐，均衡"),
                mnn("Qwen3.5-9B-MNN", "Qwen3.5 9B", "MNN · 约 7.3 GB · 最强，需大内存"),
            )),
            RecGroup("Qwen3.5 Opus 4.6 蒸馏（MNN）", listOf(
                mnn("Qwen3.5-0.8B$OPUS", "Opus 蒸馏 0.8B", "MNN · 约 0.54 GB · 小而会推理"),
                mnn("Qwen3.5-2B$OPUS", "Opus 蒸馏 2B", "MNN · 约 1.4 GB · 推理更稳"),
                mnn("Qwen3.5-4B$OPUS", "Opus 蒸馏 4B", "MNN · 约 2.8 GB · 推理强"),
            )),
            RecGroup("Gemma 4（LiteRT）", listOf(
                Rec("litert-community/gemma-4-E2B-it-litert-lm", "Gemma 4 E2B", "LiteRT · 约 2.6 GB · 推荐先试，GPU 快"),
                Rec("litert-community/gemma-4-E4B-it-litert-lm", "Gemma 4 E4B", "LiteRT · 约 3.7 GB · 更聪明"),
                Rec("litert-community/gemma-4-12B-it-litert-lm", "Gemma 4 12B", "LiteRT · 约 6.9 GB · 最强，需 16 GB 内存"),
            )),
            RecGroup("Spark 2.6（GGUF Q4）", listOf(
                Rec("XHToken/Spark-X2.6-1.7B-GGUF", "Spark X2.6 1.7B", "GGUF · Q4_K_M · 约 1.8 GB · 轻量中文"),
                Rec("XHToken/Spark-X2.6-4B-GGUF", "Spark X2.6 4B", "GGUF · Q4_K_M · 约 3.5 GB · 中文更强"),
            )),
            RecGroup("生图（NPU）", listOf(
                Rec("sd15-qnn", "Stable Diffusion 1.5", "NPU · 约 0.71 GB · 通用写实，512×512", imageModel = "sd15-qnn"),
                Rec("anything-v5-qnn", "Anything V5.0（动漫）", "NPU · 约 0.86 GB · 动漫风格，8 Gen 3 及以上", imageModel = "anything-v5-qnn"),
            )),
        )

        /** Flat list of the chat models (first = the dialog's preset). */
        val RECOMMENDED: List<Pair<String, String>> =
            RECOMMENDED_GROUPS.flatMap { g -> g.items.filter { it.imageModel == null }.map { it.id to it.note } }
                .sortedByDescending { it.first == "litert-community/gemma-4-E2B-it-litert-lm" }

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

        fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }
}

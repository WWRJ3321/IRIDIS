package com.iris.localai.ui.toolbox

import android.os.Environment
import com.iris.localai.knowledge.KbEmbedModels
import com.iris.localai.knowledge.KbEmbedNative
import com.iris.localai.knowledge.KbVectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** 找文件的打分（纯 JVM，单测）：文件名 / 路径里的关键词命中 + 可选的语义相似度。 */
object FindScore {
    private val SPLIT = Regex("[\\s,，、/_\\-.]+")

    /** 中文按单字、其余按词拆查询。 */
    fun terms(q: String): List<String> {
        val out = ArrayList<String>()
        for (w in q.lowercase().split(SPLIT)) {
            if (w.isEmpty()) continue
            if (w.any { it.code in 0x4E00..0x9FFF }) {
                if (w.length <= 4) out += w
                w.filter { it.code in 0x4E00..0x9FFF }.forEach { out += it.toString() }
            } else out += w
        }
        return out.distinct()
    }

    fun keyword(terms: List<String>, name: String, path: String): Double {
        if (terms.isEmpty()) return 0.0
        val n = name.lowercase(); val p = path.lowercase()
        var s = 0.0
        for (t in terms) s += when { n.contains(t) -> if (t.length > 1) 1.0 else 0.4; p.contains(t) -> 0.25; else -> 0.0 }
        return s / terms.size
    }

    /** 语义分只在有嵌入模型时加入；[cos] = NaN 表示没有。 */
    fun total(kw: Double, cos: Double): Double = if (cos.isNaN()) kw else kw * 0.6 + cos.coerceAtLeast(0.0) * 0.8

    /** 照片 / 文档等常见类型给中文别名，查「照片」「文档」也能命中。 */
    fun kindWords(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "gif" -> "照片 图片 photo image"
        "mp4", "mov", "mkv" -> "视频 video"
        "mp3", "m4a", "wav", "flac", "ogg" -> "音频 录音 audio"
        "pdf", "doc", "docx", "txt", "md", "epub", "xls", "xlsx", "ppt", "pptx" -> "文档 document"
        "apk" -> "安装包 app"
        "zip", "rar", "7z" -> "压缩包 archive"
        else -> ""
    }
}

/**
 * 3.0 AI 工具箱 · 找文件：在 下载 / 文档 / 相册 / 图片 里按描述找文件。装了知识库嵌入模型（EmbeddingGemma 等）时
 * 用它给「文件名 + 所在文件夹 + 类型」算语义相似度，没装就只按关键词。
 */
object FileFinder {
    data class Hit(val file: File, val score: Double)

    const val MAX_FILES = 4000
    private const val EMBED_TOP = 300
    private val cache = HashMap<String, FloatArray>()
    /** 嵌入模型句柄：搜索期间租用，离开页面时若还在搜索就等搜索结束再释放（避免 native use-after-free）。 */
    private val lease = HandleLease { KbEmbedNative.free(it) }

    fun roots(): List<File> = listOf(
        Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC,
    ).map { Environment.getExternalStoragePublicDirectory(it) }.filter { it.isDirectory }

    fun semanticAvailable(): Boolean = KbEmbedModels.installed().isNotEmpty() && KbEmbedNative.unavailableReason == null

    suspend fun search(query: String, limit: Int = 30): List<Hit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val files = ArrayList<File>()
        for (r in roots()) r.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.forEach { if (files.size < MAX_FILES) files += it }
        val terms = FindScore.terms(q)
        val kw = files.map { f -> f to FindScore.keyword(terms, f.name + " " + FindScore.kindWords(f.extension), f.parent.orEmpty()) }
        val model = KbEmbedModels.installed().firstOrNull()
        val kwOnly = { kw.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { Hit(it.first, it.second) } }
        if (model == null) return@withContext kwOnly()
        val h = lease.acquire(model.id) { KbEmbedModels.locate(model)?.let { KbEmbedNative.load(it.absolutePath, 2) } ?: 0L }
        if (h == 0L) return@withContext kwOnly()
        try {
            val qv = KbEmbedNative.embed(h, model.query(q)) ?: return@withContext kwOnly()
            // 语义只算关键词靠前的和最近改过的（嵌入很贵）
            val pool = (kw.sortedByDescending { it.second }.take(EMBED_TOP / 2) + kw.sortedByDescending { it.first.lastModified() }.take(EMBED_TOP / 2)).distinctBy { it.first.path }
            pool.map { (f, k) ->
                coroutineContext.ensureActive()
                val key = model.id + "|" + f.path
                val v = synchronized(cache) { cache[key] } ?: KbEmbedNative.embed(h, model.doc(describe(f)))?.also { synchronized(cache) { cache[key] = it } }
                Hit(f, FindScore.total(k, if (v == null) Double.NaN else KbVectors.cosine(qv, v)))
            }.filter { it.score > 0.15 }.sortedByDescending { it.score }.take(limit)
        } finally { lease.done(h) }
    }

    private fun describe(f: File): String = buildString {
        append(f.nameWithoutExtension.replace('_', ' ').replace('-', ' '))
        append(" · ").append(f.parentFile?.name.orEmpty())
        FindScore.kindWords(f.extension).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
    }

    /** 离开工具箱时放掉嵌入模型。 */
    fun release() = lease.release()

    /** 没开「全部文件访问」时分区存储下只能看到本应用的文件。 */
    fun hasAccess(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
}

/**
 * 原生句柄的租借（纯 JVM，单测）：[acquire] 期间不会被 [release] 释放；正在用时要求释放，就等最后一个 [done] 再放。
 */
class HandleLease(private val free: (Long) -> Unit) {
    private var handle = 0L
    private var key: String? = null
    private var users = 0
    private var pendingRelease = false

    /** 同一 [k] 复用已加载的句柄；返回 0 = 没拿到（加载失败，或别的模型正在用）。 */
    @Synchronized fun acquire(k: String, load: () -> Long): Long {
        if (handle != 0L && key == k) { users++; pendingRelease = false; return handle }
        if (users > 0) return 0L
        if (handle != 0L) { free(handle); handle = 0L; key = null }
        val h = load()
        if (h == 0L) return 0L
        handle = h; key = k; users = 1; pendingRelease = false
        return h
    }

    @Synchronized fun done(h: Long) {
        if (h == 0L || h != handle) return
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && pendingRelease) drop()
    }

    @Synchronized fun release() { if (users > 0) pendingRelease = true else drop() }

    @get:Synchronized val inUse: Int get() = users
    @get:Synchronized val held: Long get() = handle

    private fun drop() {
        if (handle != 0L) free(handle)
        handle = 0L; key = null; pendingRelease = false
    }
}

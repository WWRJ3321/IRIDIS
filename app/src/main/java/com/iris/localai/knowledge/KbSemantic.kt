package com.iris.localai.knowledge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 知识库向量检索 (runtime half; the pure half is [KbVectors]). Owns the embedder (loaded on first use —
 * building vectors or a search — and freed after [IDLE_MS] without use), the vector file beside index.json
 * ([KbVecStore.FILE]) and the background fill after documents change (progress in [progress], cancellable).
 * Anything failing here only means BM25 alone: [hits] returns null and the caller keeps its BM25 result.
 */
class KbSemantic(
    context: Context,
    private val kb: KnowledgeBase,
    private val vectorFile: File,
    private val scope: CoroutineScope,
) {
    /** [stale] = 2.7.2：存着的向量与当前嵌入模型不符（换了模型 / 文件 / 维度），正在（或需要）重建。 */
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val modelId: String? = null, val failed: Boolean = false,
        val stale: EmbedStamp.Mismatch? = null)

    private val prefs = context.applicationContext.getSharedPreferences("iris_kb_embed", Context.MODE_PRIVATE)
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** The user's choice (null = automatic: first downloaded in [KbEmbedModels.ALL] order — Qwen3 Q8_0, Qwen3 Q4, Gemma Q8_0, Gemma Q4; [KbEmbedModels.OFF] = none). */
    var chosen: String?
        get() = prefs.getString(KEY, null)
        set(v) { prefs.edit().putString(KEY, v).apply(); cancel(); indexChanged() }

    /** The embedding model in use right now (downloaded), or null. */
    fun model(): KbEmbedModels.Model? = KbEmbedModels.pick(chosen, KbEmbedModels.installed().map { it.id })

    // ---- embedder: lazy load, idle release ----
    private val emLock = Any()
    private var handle = 0L
    private var handleModel: String? = null
    @Volatile private var lastUse = 0L
    private var reaper: Job? = null

    private fun embed(m: KbEmbedModels.Model, text: String): FloatArray? = synchronized(emLock) {
        if (handle == 0L || handleModel != m.id) {
            KbEmbedNative.free(handle); handle = 0L; handleModel = null
            val f = KbEmbedModels.locate(m) ?: return null
            handle = KbEmbedNative.load(f.absolutePath, THREADS)
            if (handle == 0L) return null
            handleModel = m.id
            startReaper()
        }
        lastUse = System.currentTimeMillis()
        KbEmbedNative.embed(handle, text).also { lastUse = System.currentTimeMillis() }
    }

    private fun startReaper() {
        if (reaper?.isActive == true) return
        reaper = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(REAP_TICK_MS)
                synchronized(emLock) {
                    if (handle != 0L && System.currentTimeMillis() - lastUse >= IDLE_MS && _progress.value.running.not()) {
                        KbEmbedNative.free(handle); handle = 0L; handleModel = null
                    }
                    if (handle == 0L) return@launch
                }
            }
        }
    }

    // ---- vectors ----
    private val vLock = Any()
    private var store: KbVecStore? = null
    private var keysFor: List<KbChunk>? = null
    private var keys: LongArray = LongArray(0)

    /** fingerprint of the model file in use ("" when it cannot be read: then only id + dimension are compared). */
    private fun fingerprintOf(m: KbEmbedModels.Model): String = KbEmbedModels.locate(m)?.let { KbEmbedModels.fingerprint(it) }.orEmpty()

    /**
     * 2.7.2：the vectors for [m], or null. A vector file built with another model / model file / dimension is
     * dropped (never searched) and [stale] is set, so the fill rebuilds it.
     */
    private fun storeFor(m: KbEmbedModels.Model): KbVecStore? = synchronized(vLock) {
        val fp = fingerprintOf(m)
        store?.takeIf { EmbedStamp.compare(it.stamp, EmbedStamp(m.id, fp)) == null }?.let { return it }
        store = null
        val st = KbVecStore.stamp(vectorFile) ?: return null
        val bad = EmbedStamp.compare(st, EmbedStamp(m.id, fp))
        if (bad != null) {
            runCatching { vectorFile.delete() }
            stale = bad
            return null
        }
        KbVecStore.read(vectorFile, m.id, fingerprint = fp)?.also { store = it }
    }

    @Volatile private var stale: EmbedStamp.Mismatch? = null

    private fun keysOf(chunks: List<KbChunk>): LongArray = synchronized(vLock) {
        if (keysFor !== chunks) { keys = LongArray(chunks.size) { KbVectors.key(chunks[it].text) }; keysFor = chunks }
        keys
    }

    private var fill: Job? = null

    /** Documents changed (or the model did): fill the missing vectors in the background. Never blocks. */
    fun indexChanged() {
        if (fill?.isActive == true) return
        if (model() == null) return
        fill = scope.launch(Dispatchers.Default) { delay(FILL_DELAY_MS); runFill() }
    }

    fun cancel() { fill?.cancel(); fill = null; _progress.value = _progress.value.copy(running = false) }

    /** 重建向量: drops the stored vectors of the current model and fills them again. */
    fun rebuild() {
        cancel()
        synchronized(vLock) { store = null; runCatching { vectorFile.delete() } }
        indexChanged()
    }

    private fun save(m: KbEmbedModels.Model) = runCatching { synchronized(vLock) { store?.takeIf { it.modelId == m.id }?.write(vectorFile) } }

    private suspend fun runFill() {
        val m = model() ?: return
        val chunks = runCatching { kb.allChunks() }.getOrDefault(emptyList())
        val ks = keysOf(chunks)
        val existing = storeFor(m)
        val why = stale
        val missing = chunks.indices.filter { existing == null || ks[it] !in existing }.distinctBy { ks[it] }
        if (existing != null && synchronized(vLock) { existing.retain(ks.toHashSet()) }) save(m)
        if (missing.isEmpty()) { _progress.value = Progress(modelId = m.id, done = chunks.size, total = chunks.size); return }
        _progress.value = Progress(true, 0, missing.size, m.id, stale = why)
        val fp = fingerprintOf(m)
        var done = 0
        try {
            for (i in missing) {
                kotlinx.coroutines.yield() // cancellation point (取消 / model changed)
                val v = embed(m, m.doc(chunks[i].text))
                if (v == null) { _progress.value = Progress(false, done, missing.size, m.id, failed = true, stale = why); return }
                synchronized(vLock) {
                    val cur = store?.takeIf { it.modelId == m.id && it.dim == v.size } ?: KbVecStore(m.id, v.size, fp).also { store = it }
                    cur.put(ks[i], v)
                }
                done++
                _progress.value = Progress(true, done, missing.size, m.id, stale = why)
                if (done % SAVE_EVERY == 0) save(m)
            }
        } finally {
            save(m)
        }
        if (done == missing.size) stale = null
        _progress.value = Progress(false, done, missing.size, m.id)
    }

    /**
     * Hybrid hits for [query] (BM25 + cosine, RRF, cosine gate, ≤ [KbChat.MAX_SNIPPETS]), skipping [exclude];
     * null when there is no usable model / vectors / embedding (the caller falls back to BM25).
     */
    fun hits(query: String, boostFiles: Collection<String>, exclude: Set<Pair<String, Int>>): List<KbHit>? {
        val m = model() ?: return null
        val all = kb.allChunks()
        if (all.isEmpty()) return null
        val s = storeFor(m)
        if (s == null || s.size == 0) { indexChanged(); return null }
        val qv = embed(m, m.query(query)) ?: return null
        // 维度不符（同 id 换了模型文件且没读到指纹）：旧向量作废，这次只用关键词
        if (qv.size != s.dim) { stale = EmbedStamp.Mismatch.DIM; rebuild(); return null }
        val ks = keysOf(all)
        val idx = all.indices.filter { (all[it].file to all[it].index) !in exclude }
        val chunks = idx.map { all[it] }
        val cos = DoubleArray(idx.size) { j -> s[ks[idx[j]]]?.let { KbVectors.cosine(qv, it) } ?: Double.NaN }
        val bm = kb.search(query, KbVectors.TOP_EACH + exclude.size, boostFiles).filter { (it.file to it.chunkIndex) !in exclude }
            .sortedByDescending { it.score } // RRF 用纯分数名次（search 的结果是按文件分散过的顺序）
        if (cos.any { it.isNaN() }) indexChanged()
        return KbVectors.select(chunks, bm, cos, m.minCos)
    }

    companion object {
        private const val KEY = "model"
        /** the embedder is freed after this long without use. */
        const val IDLE_MS = 60_000L
        private const val REAP_TICK_MS = 10_000L
        private const val FILL_DELAY_MS = 1_500L
        private const val SAVE_EVERY = 32
        val THREADS: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}

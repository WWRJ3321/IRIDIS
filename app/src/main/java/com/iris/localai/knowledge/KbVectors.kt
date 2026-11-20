package com.iris.localai.knowledge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 2.7.2：一份向量索引是用哪个嵌入模型建的——模型 id、模型文件指纹（大小 + 头尾各 1 MiB 的 SHA-256，见
 * [KbEmbedModels.fingerprint]）、维度。知识库向量文件头里存这一份；以后记忆若加向量索引也用它。
 * 加载时与当前模型 [EmbedStamp.compare] 不符 = 旧向量作废、需重建，不再在旧向量上检索。
 */
data class EmbedStamp(val modelId: String, val fingerprint: String = "", val dim: Int = 0) {
    enum class Mismatch { MODEL, FILE, DIM }

    companion object {
        /**
         * [stored]（索引里记的）与 [current]（现在要用的）是否不符；null = 可用。旧格式没有指纹（""）时只比 id 与维度；
         * [current] 维度 0 = 还不知道（模型未加载）则不比维度。Pure.
         */
        fun compare(stored: EmbedStamp, current: EmbedStamp): Mismatch? = when {
            stored.modelId != current.modelId -> Mismatch.MODEL
            stored.fingerprint.isNotEmpty() && current.fingerprint.isNotEmpty() && stored.fingerprint != current.fingerprint -> Mismatch.FILE
            current.dim > 0 && stored.dim != current.dim -> Mismatch.DIM
            else -> null
        }
    }
}

/**
 * 向量检索 (pure, JVM-tested): one vector per chunk, keyed by a hash of the chunk text (a moved or renamed
 * file keeps its vectors), stored in a small binary file beside index.json whose header is the [EmbedStamp]
 * (model id, model file fingerprint, dimension — 2.7.2 format 2; format 1 had no fingerprint and still loads).
 */
class KbVecStore(val modelId: String, val dim: Int, val fingerprint: String = "") {
    private val map = HashMap<Long, FloatArray>()

    val stamp: EmbedStamp get() = EmbedStamp(modelId, fingerprint, dim)
    val size: Int get() = map.size
    operator fun get(key: Long): FloatArray? = map[key]
    fun put(key: Long, v: FloatArray) { if (v.size == dim) map[key] = v }
    operator fun contains(key: Long): Boolean = key in map
    /** Drops vectors of chunks no longer in the index. */
    fun retain(keys: Set<Long>): Boolean = map.keys.retainAll(keys)

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeUTF(modelId); o.writeUTF(fingerprint); o.writeInt(dim); o.writeInt(map.size)
            for ((k, v) in map) { o.writeLong(k); for (x in v) o.writeFloat(x) }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAGIC = 0x49524B56 // "IRKV"
        /** 2 = 2.7.2（头里加了模型文件指纹）；1 = 2.5.1–2.7.1。 */
        const val VERSION = 2
        const val FILE = "kb_vectors.bin"

        private fun DataInputStream.header(): Triple<EmbedStamp, Int, Int>? {
            if (readInt() != MAGIC) return null
            val v = readInt()
            if (v != 1 && v != VERSION) return null
            val id = readUTF(); val fp = if (v >= 2) readUTF() else ""; val d = readInt(); val n = readInt()
            if (d <= 0 || d > 8192 || n < 0) return null
            return Triple(EmbedStamp(id, fp, d), n, v)
        }

        /** Only the header (no vectors): what the file was built with; null when missing / unreadable. */
        fun stamp(file: File): EmbedStamp? = runCatching {
            if (!file.isFile) return null
            DataInputStream(BufferedInputStream(file.inputStream(), 4096)).use { it.header()?.first }
        }.getOrNull()

        /**
         * The stored vectors, or null when missing / unreadable / for another model, dimension or (when both are
         * known) another model file ([fingerprint]).
         */
        fun read(file: File, modelId: String, dim: Int = 0, fingerprint: String = ""): KbVecStore? = runCatching {
            if (!file.isFile) return null
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { i ->
                val (st, n, _) = i.header() ?: return null
                if (EmbedStamp.compare(st, EmbedStamp(modelId, fingerprint, dim)) != null) return null
                val s = KbVecStore(st.modelId, st.dim, st.fingerprint.ifEmpty { fingerprint })
                repeat(n) { val k = i.readLong(); s.put(k, FloatArray(st.dim) { i.readFloat() }) }
                s
            }
        }.getOrNull()
    }
}

object KbVectors {
    /** candidates taken from each list (BM25, cosine) before fusion. */
    const val TOP_EACH = 20
    /** Reciprocal-rank fusion constant. */
    const val RRF_K = 60

    /** FNV-1a 64 of the chunk text (UTF-8): the vector key. */
    fun key(text: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return h
    }

    /** Dot product = cosine for L2-normalised vectors. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** RRF over ranked lists of ids: Σ 1 / (k + rank), rank from 1. */
    fun <T> rrf(lists: List<List<T>>, k: Int = RRF_K): Map<T, Double> {
        val out = LinkedHashMap<T, Double>()
        for (l in lists) l.forEachIndexed { r, id -> out[id] = (out[id] ?: 0.0) + 1.0 / (k + r + 1) }
        return out
    }

    /**
     * Hybrid pick: BM25 top [TOP_EACH] ([bm25], best first) and cosine top [TOP_EACH] over [chunks] ([cos][i] =
     * cosine of chunk i, NaN when it has no vector yet) fused with RRF; a candidate must pass the cosine gate
     * [minCos] (without a vector: the BM25 coverage gate [KbChat.relevant]); then only fused scores ≥ the best ×
     * [KbChat.REL_SCORE], at most [limit]. Returned hits carry the fused score. Pure.
     */
    fun select(chunks: List<KbChunk>, bm25: List<KbHit>, cos: DoubleArray, minCos: Double, limit: Int = KbChat.MAX_SNIPPETS): List<KbHit> {
        if (chunks.isEmpty() || limit <= 0) return emptyList()
        val pos = HashMap<Pair<String, Int>, Int>(chunks.size * 2)
        chunks.forEachIndexed { i, c -> pos[c.file to c.index] = i }
        val bmHits = HashMap<Int, KbHit>()
        val bmList = ArrayList<Int>()
        for (h in bm25) {
            val i = pos[h.file to h.chunkIndex] ?: continue
            if (i in bmHits) continue
            bmHits[i] = h; bmList += i
            if (bmList.size >= TOP_EACH) break
        }
        val cosList = chunks.indices.filter { i -> i < cos.size && !cos[i].isNaN() }
            .sortedByDescending { cos[it] }.take(TOP_EACH)
        val fused = rrf(listOf(bmList, cosList))
        val gated = fused.filter { (i, _) ->
            val c = if (i < cos.size) cos[i] else Double.NaN
            // union — a chunk passes on meaning (cos ≥ minCos) OR on words (BM25 hit that passes KbChat.relevant).
            // Before, a chunk with a vector was judged on cosine alone, so a Q4 / short Chinese query below the gate
            // dropped every keyword hit too and nothing was cited.
            val byCos = !c.isNaN() && c >= minCos
            byCos || bmHits[i]?.let { KbChat.relevant(it) } == true
        }
        val top = gated.values.maxOrNull() ?: return emptyList()
        // 2.7.2：关键词强命中（BM25 第一且原样含问题整词，或覆盖度高）不被向量分数压掉：不受相对门槛限制，必进候选。
        val strong = bmList.take(STRONG_TOP).filter { i -> bmHits[i]?.let { strongKeyword(it) } == true }.toSet()
        val ranked = gated.entries.filter { it.value >= top * KbChat.REL_SCORE || it.key in strong }
            .sortedByDescending { it.value }
            .map { (i, s) -> val c = chunks[i]; KbHit(c.file, c.text, if (i in strong) maxOf(s, top) else s, c.index, cover = bmHits[i]?.cover) }
            .sortedByDescending { it.score }
        // 按片段排序；多个文件都命中时先各取各自最好的一段
        return KbRank.diversify(ranked, limit)
    }

    /** BM25 前几名里只看这么多个是否「关键词强命中」。 */
    const val STRONG_TOP = 2
    /** 覆盖度（BM25 / 上限）达到这个也算强命中。 */
    const val STRONG_NORM = 0.6

    /** 关键词强命中：通过覆盖度门槛，且原样含问题整词或覆盖度 ≥ [STRONG_NORM]。Pure. */
    fun strongKeyword(h: KbHit): Boolean {
        val c = h.cover ?: return false
        return KbChat.relevant(h) && (c.phraseHit || c.norm >= STRONG_NORM)
    }
}

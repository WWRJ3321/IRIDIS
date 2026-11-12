package com.iris.localai.knowledge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 2.5.1 向量检索 (pure, JVM-tested): one vector per chunk, keyed by a hash of the chunk text (a moved or renamed
 * file keeps its vectors), stored in a small binary file beside index.json that records the model id and the
 * dimension — another model (or dimension) means the vectors are rebuilt.
 */
class KbVecStore(val modelId: String, val dim: Int) {
    private val map = HashMap<Long, FloatArray>()

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
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeUTF(modelId); o.writeInt(dim); o.writeInt(map.size)
            for ((k, v) in map) { o.writeLong(k); for (x in v) o.writeFloat(x) }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAGIC = 0x49524B56 // "IRKV"
        const val VERSION = 1
        const val FILE = "kb_vectors.bin"

        /** The stored vectors, or null when missing / unreadable / for another model or dimension. */
        fun read(file: File, modelId: String, dim: Int = 0): KbVecStore? = runCatching {
            if (!file.isFile) return null
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                val id = i.readUTF(); val d = i.readInt(); val n = i.readInt()
                if (id != modelId || d <= 0 || d > 8192 || (dim > 0 && d != dim) || n < 0) return null
                val s = KbVecStore(id, d)
                repeat(n) { val k = i.readLong(); s.put(k, FloatArray(d) { i.readFloat() }) }
                s
            }
        }.getOrNull()
    }
}

object KbVectors {
    /** 2.5.1: candidates taken from each list (BM25, cosine) before fusion. */
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
            if (!c.isNaN()) c >= minCos else bmHits[i]?.let { KbChat.relevant(it) } == true
        }
        val top = gated.values.maxOrNull() ?: return emptyList()
        return gated.entries.filter { it.value >= top * KbChat.REL_SCORE }
            .sortedByDescending { it.value }.take(limit)
            .map { (i, s) -> val c = chunks[i]; KbHit(c.file, c.text, s, c.index, cover = bmHits[i]?.cover) }
    }
}

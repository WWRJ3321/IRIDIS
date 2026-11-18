package com.iris.localai.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Anima NPU 包的两个分词器（读 HF tokenizer.json），按通用的提示词权重写法切分。
 * - [QwenBpe]：Qwen2 字节级 BPE（NFC + 官方切分正则），不加起止符。
 * - [T5Unigram]：T5 SentencePiece Unigram（NFKC 近似 Precompiled 表，Metaspace），末尾由调用方加 </s>=1。
 * - [ComfyWeights]：(词:1.2) / (词) 权重语法；T5 每个 id 带权重，Qwen 只取去掉语法后的文本。
 */
class QwenBpe(private val vocab: Map<String, Int>, private val ranks: Map<String, Int>) {
    companion object {
        private val SPLIT: Pattern = Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
            // 不加 UNICODE_CHARACTER_CLASS——Android（ICU 正则）不支持这个标志，类初始化直接失败
            // （真机报错只显示混淆后的类名）；ICU 的 \p{L}、\s 本来就按 Unicode 处理。
        )

        /** GPT-2 bytes_to_unicode. */
        val BYTE_CHARS: CharArray = run {
            val bs = ArrayList<Int>()
            for (b in '!'.code..'~'.code) bs += b
            for (b in '¡'.code..'¬'.code) bs += b
            for (b in '®'.code..'ÿ'.code) bs += b
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            val out = CharArray(256)
            for (i in bs.indices) out[bs[i]] = cs[i].toChar()
            out
        }

        fun fromJson(text: String): QwenBpe {
            val m = (Json.parseToJsonElement(text).jsonObject["model"] ?: error("tokenizer.json 缺少 model")).jsonObject
            val vocab = HashMap<String, Int>(160_000)
            for ((k, v) in (m["vocab"] ?: error("tokenizer.json 缺少 vocab")).jsonObject) vocab[k] = v.jsonPrimitive.intOrNull ?: continue
            val ranks = HashMap<String, Int>(160_000)
            (m["merges"] ?: error("tokenizer.json 缺少 merges")).jsonArray.forEachIndexed { i, e ->
                val key = if (e is JsonArray) e[0].jsonPrimitive.content + " " + e[1].jsonPrimitive.content else e.jsonPrimitive.content
                ranks.putIfAbsent(key, i)
            }
            return QwenBpe(vocab, ranks)
        }
    }

    private val cache = HashMap<String, List<Int>>()

    fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = ArrayList<Int>()
        val m = SPLIT.matcher(s)
        while (m.find()) {
            val piece = m.group()
            if (piece.isEmpty()) continue
            val sb = StringBuilder()
            for (b in piece.toByteArray(Charsets.UTF_8)) sb.append(BYTE_CHARS[b.toInt() and 0xFF])
            out += cache.getOrPut(sb.toString()) { bpe(sb.toString()) }
        }
        return out
    }

    internal fun bpe(word: String): List<Int> {
        vocab[word]?.let { return listOf(it) }
        var parts = word.map { it.toString() }.toMutableList()
        while (parts.size > 1) {
            var best = -1; var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = ranks[parts[i] + " " + parts[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            val a = parts[best]; val b = parts[best + 1]
            val next = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) { next += a + b; i += 2 } else { next += parts[i]; i++ }
            }
            parts = next
        }
        return parts.mapNotNull { vocab[it] }
    }
}

class T5Unigram(private val pieces: Map<String, Pair<Int, Double>>, private val unkId: Int, private val maxLen: Int) {
    companion object {
        const val EOS = 1
        private val MULTI_SPACE = Regex(" {2,}")

        fun fromJson(text: String): T5Unigram {
            val m = (Json.parseToJsonElement(text).jsonObject["model"] ?: error("tokenizer.json 缺少 model")).jsonObject
            val map = HashMap<String, Pair<Int, Double>>(40_000)
            var maxLen = 1
            (m["vocab"] ?: error("tokenizer.json 缺少 vocab")).jsonArray.forEachIndexed { i, e ->
                val a = e.jsonArray
                val p = a[0].jsonPrimitive.content
                map.putIfAbsent(p, i to (a[1].jsonPrimitive.doubleOrNull ?: 0.0))
                maxLen = maxOf(maxLen, p.length)
            }
            return T5Unigram(map, m["unk_id"]?.jsonPrimitive?.intOrNull ?: 2, maxLen)
        }
    }

    private val unkScore = (pieces.values.minOfOrNull { it.second } ?: 0.0) - 10.0

    /** Ids without the end token. */
    fun encode(text: String): List<Int> {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC).trimEnd()
        if (s.isEmpty()) return emptyList()
        s = MULTI_SPACE.replace(s, "▁").replace(' ', '▁')
        if (!s.startsWith("▁")) s = "▁$s"
        val out = ArrayList<Int>()
        var start = 0
        for (i in 1..s.length) {
            if (i == s.length || s[i] == '▁') { out += viterbi(s.substring(start, i)); start = i }
        }
        return out
    }

    internal fun viterbi(w: String): List<Int> {
        val n = w.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1); val id = IntArray(n + 1)
        best[0] = 0.0
        for (j in 1..n) {
            for (i in maxOf(0, j - maxLen) until j) {
                if (best[i] == Double.NEGATIVE_INFINITY) continue
                val p = pieces[w.substring(i, j)] ?: continue
                val sc = best[i] + p.second
                if (sc > best[j]) { best[j] = sc; from[j] = i; id[j] = p.first }
            }
            if (best[j] == Double.NEGATIVE_INFINITY && best[j - 1] != Double.NEGATIVE_INFINITY) {
                best[j] = best[j - 1] + unkScore; from[j] = j - 1; id[j] = unkId
            }
        }
        val rev = ArrayList<Int>()
        var j = n
        while (j > 0) { rev += id[j]; j = from[j] }
        rev.reverse()
        // fuse_unk
        val out = ArrayList<Int>(rev.size)
        for (t in rev) if (!(t == unkId && out.lastOrNull() == unkId)) out += t
        return out
    }
}

/**
 * 提示词权重语法（社区通用写法）：`(词:1.2)` 设权重，`(词)` 乘 1.1，可嵌套；`\(` `\)` 是字面括号，
 * 找不到配对的括号也按字面处理。返回按权重切开的片段。
 */
object ComfyWeights {
    fun parse(text: String): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        walk(text, 0, text.length, 1f, out)
        // 合并相邻同权重片段
        val merged = ArrayList<Pair<String, Float>>()
        for (p in out) {
            val last = merged.lastOrNull()
            if (last != null && last.second == p.second) merged[merged.size - 1] = (last.first + p.first) to p.second
            else if (p.first.isNotEmpty()) merged += p
        }
        return merged
    }

    /** text[from, to) 里找与 open 处 '(' 配对的 ')'，没有返回 -1。 */
    private fun closing(t: String, open: Int, to: Int): Int {
        var depth = 0; var k = open
        while (k < to) {
            val ch = t[k]
            if (ch == '\\' && k + 1 < to) { k += 2; continue }
            if (ch == '(') depth++ else if (ch == ')') { depth--; if (depth == 0) return k }
            k++
        }
        return -1
    }

    private fun walk(t: String, from: Int, to: Int, w: Float, out: MutableList<Pair<String, Float>>) {
        val buf = StringBuilder()
        var k = from
        while (k < to) {
            val ch = t[k]
            if (ch == '\\' && k + 1 < to && (t[k + 1] == '(' || t[k + 1] == ')')) { buf.append(t[k + 1]); k += 2; continue }
            if (ch == '(') {
                val end = closing(t, k, to)
                if (end > 0) {
                    if (buf.isNotEmpty()) { out += buf.toString() to w; buf.setLength(0) }
                    var innerEnd = end
                    var gw = w * 1.1f
                    val colon = t.lastIndexOf(':', end - 1)
                    if (colon > k) t.substring(colon + 1, end).trim().toFloatOrNull()?.let { gw = it; innerEnd = colon }
                    walk(t, k + 1, innerEnd, gw, out)
                    k = end + 1
                    continue
                }
            }
            buf.append(ch); k++
        }
        if (buf.isNotEmpty()) out += buf.toString() to w
    }
}

/** 一条提示词 → Anima 文本编码器要的 Qwen id、T5 id（含 </s>）与 T5 每个 id 的权重。 */
data class AnimaTokens(val qwen: IntArray, val t5: IntArray, val t5Weights: FloatArray)

class AnimaTokenizer(private val qwen: QwenBpe, private val t5: T5Unigram) {
    fun encode(prompt: String): AnimaTokens {
        val q = ArrayList<Int>(); val t = ArrayList<Int>(); val w = ArrayList<Float>()
        for ((seg, wt) in ComfyWeights.parse(prompt)) {
            q += qwen.encode(seg)
            for (id in t5.encode(seg)) { t += id; w += wt }
        }
        t += T5Unigram.EOS; w += 1f
        return AnimaTokens(q.toIntArray(), t.toIntArray(), w.toFloatArray())
    }
}

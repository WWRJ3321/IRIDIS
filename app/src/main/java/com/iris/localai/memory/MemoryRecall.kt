package com.iris.localai.memory

import com.iris.localai.knowledge.KbQuery
import com.iris.localai.knowledge.KbTokenizer
import com.iris.localai.util.TokenEstimator
import kotlin.math.ln

/**
 * 2.2.9 C: memory by relevance. ABOUT + PREF always (newest first; over budget the oldest go),
 * frozen into the system prompt before the first answer; FACT chosen by BM25 against the current
 * question and attached to that user message (like knowledge snippets), so the prefix stays put. Pure.
 */
object MemoryRecall {
    const val ABOUT = "【关于用户】"
    const val PREF = "【用户偏好】"
    const val FACTS = "【相关记忆】"
    /** Facts attached to one turn at most. */
    const val MAX_FACTS = 5

    private fun line(i: MemoryItem) = "- " + i.text
    private fun cost(i: MemoryItem) = TokenEstimator.estimate(line(i)) + 1

    /** ABOUT + PREF, newest first, within [maxTokens] (the oldest are dropped). */
    fun fixed(items: List<MemoryItem>, maxTokens: Int): List<MemoryItem> {
        var left = maxTokens - TokenEstimator.estimate("$ABOUT\n$PREF\n${MemoryPrompt.NOTE}")
        val out = ArrayList<MemoryItem>()
        for (i in items.filter { it.kind != MemoryItem.Kind.FACT }.sortedByDescending { it.updatedAt }) {
            val c = cost(i)
            if (c > left) continue
            out += i; left -= c
        }
        return out
    }

    /** FACT items scored by BM25 against [query] (best first, score > 0 only). */
    fun scoreFacts(items: List<MemoryItem>, query: String): List<Pair<MemoryItem, Double>> {
        val facts = items.filter { it.kind == MemoryItem.Kind.FACT }
        val q = KbQuery.meaningful(query).distinct()
        if (q.isEmpty() || facts.isEmpty()) return emptyList()
        val tf = facts.map { f -> KbTokenizer.tokens(f.text).groupingBy { it }.eachCount() }
        val len = tf.map { it.values.sum() }
        val avg = len.average().coerceAtLeast(1.0)
        val df = HashMap<String, Int>().also { d -> tf.forEach { m -> m.keys.forEach { d[it] = (d[it] ?: 0) + 1 } } }
        val n = facts.size.toDouble()
        return facts.indices.mapNotNull { i ->
            var s = 0.0
            for (t in q) {
                val f = tf[i][t] ?: continue
                val d = df.getValue(t)
                s += ln(1 + (n - d + 0.5) / (d + 0.5)) * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * len[i] / avg))
            }
            if (s > 0) facts[i] to s else null
        }.sortedWith(compareByDescending<Pair<MemoryItem, Double>> { it.second }.thenByDescending { it.first.updatedAt })
    }

    /** Relevant FACT items for [query] within [maxTokens]. */
    fun facts(items: List<MemoryItem>, query: String, maxTokens: Int): List<MemoryItem> {
        var left = maxTokens - TokenEstimator.estimate(FACTS)
        val out = ArrayList<MemoryItem>()
        for ((i, _) in scoreFacts(items, query)) {
            if (out.size >= MAX_FACTS) break
            val c = cost(i)
            if (c > left) continue
            out += i; left -= c
        }
        return out
    }

    /** ABOUT + PREF always, then FACTs relevant to [query]; all within [maxTokens]. */
    fun select(items: List<MemoryItem>, query: String, maxTokens: Int): List<MemoryItem> {
        val fixed = fixed(items, maxTokens)
        val used = TokenEstimator.estimate(block(fixed))
        return fixed + facts(items, query, maxTokens - used)
    }

    /** "【关于用户】…\n【用户偏好】…\n【相关记忆】…" (sections in that order, items in the given order). */
    fun block(selected: List<MemoryItem>): String = listOf(
        ABOUT to MemoryItem.Kind.ABOUT, PREF to MemoryItem.Kind.PREF, FACTS to MemoryItem.Kind.FACT,
    ).mapNotNull { (label, k) ->
        selected.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.joinToString("\n", "$label\n") { line(it) }
    }.joinToString("\n")

    /** The block put in front of the current user message ("" when nothing is relevant). */
    fun turnBlock(items: List<MemoryItem>, query: String, maxTokens: Int): String {
        val f = facts(items, query, maxTokens)
        return if (f.isEmpty()) "" else block(f) + "\n" + MemoryPrompt.TURN_NOTE
    }
}

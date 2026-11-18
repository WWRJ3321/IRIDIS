package com.iris.localai.data

/**
 * 下载模型 › 推荐 — order inside a section: model series name first (natural order, digits by
 * value: Qwen3 < Qwen3.5 < Qwen3.6), then download size (small → big), then the full name. Pure, unit-tested.
 *
 * Series = the name before its first parameter-size token ("4B", "E2B", "0.8B", "24B-A2B", "-35B"),
 * with any （…）/(…) tag removed: "Gemma 4 19B REAP（Q4_0）" → "Gemma 4", "LFM2.5 24B-A2B 无审查" → "LFM2.5",
 * "Edge0-35B（实验）" → "Edge0", "Qwen3.5 4B 推理版" → "Qwen3.5". A name with no size token is its own series.
 */
object ModelSort {
    private val PAREN = Regex("[（(][^）)]*[）)]")
    private val SIZE = Regex("(?<![\\p{L}\\p{N}.])E?\\d+(?:\\.\\d+)?B(?:-A\\d+(?:\\.\\d+)?B)?(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun series(name: String): String {
        val plain = PAREN.replace(name, " ").replace(Regex("\\s+"), " ").trim()
        val m = SIZE.find(plain) ?: return plain
        return plain.substring(0, m.range.first).trimEnd(' ', '-', '_').ifEmpty { plain }
    }

    /** Natural, case-insensitive compare: digit runs by value ("Qwen3" < "Qwen3.5", "Gemma 3" < "Gemma 4" < "Gemma 12"). */
    val natural: Comparator<String> = Comparator { a, b ->
        val ta = tokens(a.lowercase()); val tb = tokens(b.lowercase())
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]; val y = tb[i]
            val nx = x.firstOrNull()?.isDigit() == true; val ny = y.firstOrNull()?.isDigit() == true
            val c = when {
                nx && ny -> x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                else -> x.compareTo(y)
            }
            if (c != 0) return@Comparator c
        }
        ta.size.compareTo(tb.size)
    }

    private fun tokens(s: String): List<String> = Regex("\\d+|\\D+").findAll(s).map { it.value }.toList()

    /**
     * Series → size → name. Entries sharing one name (an MNN build and its GGUF twin, "Qwen3.5 4B") are one
     * model: they sort by the smaller of their sizes and keep their catalog order next to each other.
     */
    fun <T> sort(list: List<T>, name: (T) -> String, size: (T) -> Long): List<T> {
        val modelSize = HashMap<String, Long>()
        list.forEach { x -> val n = name(x); modelSize[n] = minOf(modelSize[n] ?: Long.MAX_VALUE, size(x)) }
        return list.sortedWith(
            compareBy<T, String>(natural) { series(name(it)) }
                .thenBy { modelSize.getValue(name(it)) }
                .thenComparator { a, b -> natural.compare(name(a), name(b)) },
        )
    }

    /** Runs of consecutive same-series items, in order (for the light series sub-title over a run of ≥ 2). */
    fun <T> runs(list: List<T>, name: (T) -> String): List<Pair<String, List<T>>> {
        val out = ArrayList<Pair<String, MutableList<T>>>()
        for (x in list) {
            val s = series(name(x))
            if (out.isNotEmpty() && natural.compare(out.last().first, s) == 0) out.last().second += x
            else out += s to mutableListOf(x)
        }
        return out
    }
}

package com.iris.localai.agent

/**
 * 2.2.17 D 引用链接: the sources of a web_search answer, read back from the reply's own tool steps
 * (the step detail is the numbered result list the model saw, local or online model alike), so
 * the answer's inline [1] markers become links and a compact 来源 row sits under the message.
 */
object Citations {
    data class Source(val n: Int, val title: String, val url: String) {
        val domain: String get() = domainOf(url)
    }

    private val ITEM = Regex("^(\\d{1,2})\\. (.+)$")
    private val URL_LINE = Regex("^\\s+(https?://\\S+)\\s*$")
    /** [1] / [1,2] / [1][2] / 【1】, not a markdown link ([1](…)) and not inside a word. */
    private val MARK = Regex("[\\[【]\\s*(\\d{1,2}(?:\\s*[,，、]\\s*\\d{1,2})*)\\s*[\\]】](?!\\()")
    private val FENCE = Regex("(?s)```.*?(```|$)|`[^`\\n]*`")

    fun fromSteps(steps: List<AgentStep>): List<Source> {
        val out = ArrayList<Source>()
        val seen = HashSet<String>()
        for (s in steps) {
            if (s.tool != WebSearchTool.name || s.status != StepStatus.OK) continue
            var title: String? = null
            for (line in s.detail.lineSequence()) {
                ITEM.matchEntire(line)?.let { title = it.groupValues[2].trim(); return@let }
                val u = URL_LINE.matchEntire(line)?.groupValues?.get(1)
                if (u != null && title != null) {
                    if (seen.add(u)) out += Source(out.size + 1, title!!.substringBeforeLast(" (").ifBlank { title!! }, u)
                    title = null
                }
            }
        }
        return out
    }

    /** [text] with each [n] that names a source turned into a superscript markdown link (code left alone). */
    fun linkify(text: String, sources: List<Source>): String {
        if (sources.isEmpty() || (text.indexOf('[') < 0 && text.indexOf('【') < 0)) return text
        val byN = sources.associateBy { it.n }
        val code = FENCE.findAll(text).map { it.range }.toList()
        return MARK.replace(text) { m ->
            if (code.any { m.range.first in it }) return@replace m.value
            val ns = m.groupValues[1].split(',', '，', '、').mapNotNull { it.trim().toIntOrNull() }
            if (ns.isEmpty() || ns.any { it !in byN }) m.value
            else ns.joinToString("") { n -> "[" + superscript(n) + "](" + byN.getValue(n).url + ")" }
        }
    }

    fun superscript(n: Int): String = n.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")

    fun domainOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")
}

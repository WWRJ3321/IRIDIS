package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject

/**
 * 2.1.0: the header of a tool step card, written for people — 搜索「天气」, 读取 notes.md,
 * 打开网页 example.com — instead of the model-facing `key=value` list. One mapping; an unknown
 * tool falls back to its label alone. Pure (StepTitleTest).
 */
object StepTitle {
    private const val MAX = 40

    fun of(tool: String, args: JsonObject): String {
        fun s(vararg k: String) = Args.str(args, *k)?.let(::oneLine)?.takeIf { it.isNotEmpty() }
        val path = s("path", "file", "file_path", "filename")
        val q = s("query", "q", "keywords", "keyword", "search", "text")
        return when (tool) {
            "web_search" -> q?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0ac9ac, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7ad823)
            "search_files" -> q?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_903083, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6c84cd)
            "recall" -> q?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d78dcc, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_548431)
            "read_file" -> path?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1b8f12, name(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e34255)
            "write_file" -> path?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_999937, name(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_494a9a)
            "edit_file" -> path?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_14a469, name(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5c3b53)
            "delete_file" -> path?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_80ba6b, name(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_53518c)
            "process_file" -> path?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a6b984, name(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_890eeb)
            "list_dir" -> path?.takeIf { it != "." && it != "/" }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ae3538, name(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e976e3)
            "move_file" -> {
                val a = s("from", "src", "source", "path"); val b = s("to", "dest", "destination", "target")
                if (a != null && b != null) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_643a55, name(a), name(b)) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6ccecd)
            }
            "web_fetch" -> s("url", "link", "href")?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9a907c, site(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f0c808)
            "browser" -> when (s("action", "op")?.lowercase()) {
                "click" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8b80cb)
                "type" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7e9cc5)
                "back" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a9b5c6)
                "read" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_30a6b1)
                else -> s("url", "link", "href", "query")?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9a907c, site(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_912302)
            }
            "calculator" -> s("expression", "expr", "input")?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ce8a98, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0f1cc1)
            "datetime" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d5c875)
            "plan", "todo" -> s("title", "goal", "name")?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ad4811, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0debf5)
            "skill" -> s("name", "skill")?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2d6487, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_699143)
            "activate" -> s("group", "name", "tool", "toolset")?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2679a3, clip(it)) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c49c94)
            "memory" -> when (s("action", "op")?.lowercase()) {
                "add" -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f7d9d3)
                "replace" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_22d035)
                "remove" -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9cb88d)
                else -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7)
            }
            else -> label(tool)
        }
    }

    fun label(tool: String): String = AgentTools.byName(tool)?.label ?: tool

    private fun oneLine(v: String) = v.replace(Regex("\\s+"), " ").trim()
    private fun clip(v: String) = if (v.length > MAX) v.take(MAX - 1) + "…" else v
    /** File name of a path (a/b/c.txt → c.txt). */
    internal fun name(p: String) = clip(p.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { p })
    /** Domain of a URL (https://www.a.com/x → a.com); keywords stay as 「…」. */
    internal fun site(u: String): String {
        val t = u.trim()
        val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(t)
        if (!hasScheme && (' ' in t || '.' !in t)) return com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_c6c01f, clip(t))
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        return clip(host.removePrefix("www.").ifEmpty { t })
    }
}

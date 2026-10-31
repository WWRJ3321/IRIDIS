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
            "web_search" -> q?.let { "搜索「${clip(it)}」" } ?: "联网搜索"
            "search_files" -> q?.let { "搜索文件「${clip(it)}」" } ?: "搜索文件"
            "recall" -> q?.let { "回忆「${clip(it)}」" } ?: "回忆"
            "read_file" -> path?.let { "读取 ${name(it)}" } ?: "读取文件"
            "write_file" -> path?.let { "写入 ${name(it)}" } ?: "写入文件"
            "edit_file" -> path?.let { "修改 ${name(it)}" } ?: "修改文件"
            "delete_file" -> path?.let { "删除 ${name(it)}" } ?: "删除文件"
            "process_file" -> path?.let { "处理 ${name(it)}" } ?: "分段处理文件"
            "list_dir" -> path?.takeIf { it != "." && it != "/" }?.let { "列出 ${name(it)}" } ?: "列出目录"
            "move_file" -> {
                val a = s("from", "src", "source", "path"); val b = s("to", "dest", "destination", "target")
                if (a != null && b != null) "移动 ${name(a)} → ${name(b)}" else "移动/重命名"
            }
            "web_fetch" -> s("url", "link", "href")?.let { "打开网页 ${site(it)}" } ?: "打开网页"
            "browser" -> when (s("action", "op")?.lowercase()) {
                "click" -> "浏览器 · 点击"
                "type" -> "浏览器 · 输入"
                "back" -> "浏览器 · 返回"
                "read" -> "浏览器 · 读取页面"
                else -> s("url", "link", "href", "query")?.let { "打开网页 ${site(it)}" } ?: "浏览器"
            }
            "run_command" -> s("command", "cmd", "shell", "script")?.let { "运行命令 ${clip(it)}" } ?: "运行命令"
            "calculator" -> s("expression", "expr", "input")?.let { "计算 ${clip(it)}" } ?: "计算器"
            "datetime" -> "查看时间"
            "plan", "todo" -> s("title", "goal", "name")?.let { "计划 · ${clip(it)}" } ?: "计划"
            "skill" -> s("name", "skill")?.let { "技能 ${clip(it)}" } ?: "技能"
            "activate" -> s("group", "name", "tool", "toolset")?.let { "载入工具 ${clip(it)}" } ?: "载入工具"
            "memory" -> when (s("action", "op")?.lowercase()) {
                "add" -> "记住"
                "replace" -> "更新记忆"
                "remove" -> "删除记忆"
                else -> "长期记忆"
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
        if (!hasScheme && (' ' in t || '.' !in t)) return "「${clip(t)}」"
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        return clip(host.removePrefix("www.").ifEmpty { t })
    }
}

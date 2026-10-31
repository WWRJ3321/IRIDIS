package com.iris.localai.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 2.2.2 `search_local`: one local search over the workspace files, the knowledge folder and past
 * chats (the former search_files / kb_search / recall), so every model sees the same 9 tools.
 */
object SearchLocalTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "search_local"
    override val label = "本地搜索"
    override val description = "在工作区文件、知识库和聊天记录里搜索"
    override val promptLine = "search_local(query): find in your files, knowledge folder and past chats"
    override val risk = ToolRisk.READ

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val q = Args.str(args, "query", "q", "text", "keywords", "pattern")?.trim() ?: throw IllegalArgumentException("缺少参数 query")
        if (q.isEmpty()) throw IllegalArgumentException("query 不能为空")
        val a = buildJsonObject { put("query", JsonPrimitive(q)) }
        val parts = ArrayList<Pair<String, ToolResult>>()
        runCatching { SearchFilesTool.run(a, env) }.getOrNull()?.let { parts += "Files" to it }
        if (env.knowledge != null) runCatching { KbSearchTool.run(a, env) }.getOrNull()?.let { parts += "Knowledge folder" to it }
        if (env.history != null) runCatching { RecallTool.run(a, env) }.getOrNull()?.let { parts += "Past messages" to it }
        if (parts.isEmpty()) return ToolResult(false, "Error: nothing to search", "不可用")
        val out = parts.joinToString("\n\n") { (h, r) -> "## $h\n${r.output}" }
        val summary = parts.filter { it.second.ok && it.second.summary != "无结果" }.joinToString(" · ") { it.second.summary }.ifEmpty { "无结果" }
        return ToolResult(parts.any { it.second.ok }, capTokens(out, env.outputTokens), summary.take(60))
    }
}

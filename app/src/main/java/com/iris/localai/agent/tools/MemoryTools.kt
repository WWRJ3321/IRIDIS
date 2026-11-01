package com.iris.localai.agent

import com.iris.localai.memory.ItemsEdit
import com.iris.localai.memory.MemoryDocs
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryOps
import com.iris.localai.memory.MemoryTarget
import com.iris.localai.memory.kindFor
import com.iris.localai.memory.kinds
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class RecallHit(
    val conversationId: String,
    val role: String,
    val tool: String?,
    val snippet: String,
    val createdAt: Long,
    val score: Double,
)

/** Full-text search over the raw history (the SQLite store in the app, fakes in tests). */
fun interface HistorySearch {
    fun search(query: String, conversationId: String?, limit: Int, exclude: Set<String>): List<RecallHit>
}

/** 1.10.0: search earlier messages and tool steps (BM25-ranked snippets). */
object RecallTool : AgentTool {
    override val group = ToolGroup.MEMORY
    override val name = "recall"
    override val label = "回忆"
    override val description = "检索本对话（或全部对话）里较早的消息与工具记录"
    override val promptLine = "recall(query, scope?: chat|all): search past messages"
    override val risk = ToolRisk.READ
    private const val DEFAULT_LIMIT = 5

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val q = Args.str(args, "query", "q", "text", "keywords") ?: throw IllegalArgumentException("缺少参数 query")
        if (q.isBlank()) throw IllegalArgumentException("query 不能为空")
        val history = env.history ?: return ToolResult(false, "Error: history search is not available", "不可用")
        val all = (Args.str(args, "scope", "in", "where") ?: "").trim().lowercase() in setOf("all", "global", "everything", "全部", "all_chats")
        val limit = (Args.int(args, "limit", "n", "count") ?: DEFAULT_LIMIT).coerceIn(1, 10)
        val hits = history.search(q.trim(), if (all) null else env.conversationId, limit, env.recallExclude)
        val where = if (all) "all chats" else "this chat"
        if (hits.isEmpty()) return ToolResult(true, "No earlier messages in $where match \"${q.trim()}\".", "无结果")
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        val body = hits.mapIndexed { i, h ->
            val who = when (h.role) { "user" -> "user"; "tool" -> "tool ${h.tool ?: ""}".trim(); else -> "assistant" }
            val time = Instant.ofEpochMilli(h.createdAt).atZone(env.zone()).format(fmt)
            val other = if (all && h.conversationId != env.conversationId) ", other chat" else ""
            "${i + 1}. [$who · $time$other] ${h.snippet}"
        }.joinToString("\n")
        return ToolResult(true, capTokens("${hits.size} match(es) in $where:\n$body", env.outputTokens), "${hits.size} 条结果")
    }
}

/** 1.10.0: add / replace / remove entries of USER.md or MEMORY.md (bounded, outside the workspace). */
object MemoryTool : AgentTool {
    override val group = ToolGroup.MEMORY
    override val name = "memory"
    override val label = "长期记忆"
    override val description = "增删改长期记忆（关于你 / 记住的事，最多 200 条，每条 300 字）"
    override val promptLine = "memory(action: add|replace|remove, target: user|memory, content?, old?): lasting facts"
    override val risk = ToolRisk.MEMORY

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val docs = env.memory ?: return ToolResult(false, "Error: long-term memory is turned off in settings", "记忆已关闭")
        val action = (Args.str(args, "action", "op", "operation", "mode") ?: "add").trim().lowercase()
        val target = MemoryTarget.parse(Args.str(args, "target", "file", "doc", "where"))
            ?: return ToolResult(false, "Error: target must be user or memory", "目标无效")
        val content = Args.str(args, "content", "text", "new", "new_text", "entry", "value")
        val old = Args.str(args, "old", "old_text", "match", "find", "entry_to_replace")
        val r = when (action) {
            "add", "append", "save" -> {
                val c = content ?: throw IllegalArgumentException("缺少参数 content")
                docs.edit { MemoryOps.add(it, target.kindFor(c), c, MemoryItem.Source.AGENT) }
            }
            "replace", "update", "edit" -> {
                val o = old ?: throw IllegalArgumentException("缺少参数 old")
                val c = content ?: throw IllegalArgumentException("缺少参数 content")
                docs.edit { MemoryOps.replace(it, target.kinds, o, c) }
            }
            "remove", "delete", "forget" -> {
                val o = old ?: content ?: throw IllegalArgumentException("缺少参数 old")
                docs.edit { MemoryOps.remove(it, target.kinds, o) }
            }
            else -> return ToolResult(false, "Error: action must be add, replace or remove", "操作无效")
        }
        return when (r) {
            is ItemsEdit.Ok -> ToolResult(true, "OK: ${target.fileName} ${r.message}. Saved for future chats.", "${target.fileName} 已${verb(action)}")
            is ItemsEdit.Err -> ToolResult(false, "Error: ${r.message}", r.message.take(40))
        }
    }

    private fun verb(action: String) = when (action) {
        "replace", "update", "edit" -> "更新"
        "remove", "delete", "forget" -> "删除"
        else -> "添加"
    }
}

/** Confirmation preview of a memory call (shown in the 允许 / 拒绝 dialog). */
fun memoryPreview(call: ToolCall): String {
    val a = call.args
    val target = MemoryTarget.parse(Args.str(a, "target", "file", "doc", "where"))
    val action = (Args.str(a, "action", "op", "operation", "mode") ?: "add").lowercase()
    val verb = when (action) { "replace", "update", "edit" -> "修改"; "remove", "delete", "forget" -> "删除"; else -> "添加" }
    val old = Args.str(a, "old", "old_text", "match", "find", "entry_to_replace")
    val content = Args.str(a, "content", "text", "new", "new_text", "entry", "value")
    return buildString {
        append("$verb ${target?.fileName ?: "?"}（${target?.label ?: "目标无效"}）")
        if (old != null) append("\n原条目：").append(old.take(200))
        if (content != null) append("\n内容：").append(content.take(300))
    }
}

/** For the settings page / tests: saved entries and the cap. */
fun MemoryDocs.usage(): Pair<Int, Int> = store.items().size to cap


package com.iris.localai.agent.tools

import com.iris.localai.knowledge.KbChat
import com.iris.localai.agent.core.*
import com.iris.localai.agent.web.*
import com.iris.localai.agent.workspace.*
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbPrompt
import kotlinx.serialization.json.JsonObject

/** the user's knowledge folder for `kb_search` (the [com.iris.localai.knowledge.KnowledgeBase] in the app, fakes in tests). */
fun interface KnowledgeSearch {
    fun search(query: String, limit: Int): List<KbHit>
    /** Counts the files a reply actually used (设置 › 知识库「本周引用 N 次」); no-op for fakes. */
    fun cite(files: Collection<String>) {}
}

/** 知识库: top snippets (with file names) from the files in knowledge/. */
object KbSearchTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "kb_search"
    override val label = "知识库"
    override val description = "检索知识库文件夹里的资料"
    override val promptLine = "kb_search(query): search the user's knowledge folder"
    override val risk = ToolRisk.READ

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val q = Args.str(args, "query", "q", "text", "keywords")?.trim() ?: throw IllegalArgumentException("缺少参数 query")
        if (q.isEmpty()) throw IllegalArgumentException("query 不能为空")
        val kb = env.knowledge ?: return ToolResult(false, "Error: the knowledge base is empty", "知识库为空")
        // the same coverage gate as 引用知识库 (a query sharing one common word is "no match").
        val hits = KbChat.let { g -> g.reach(kb.search(q, KbPrompt.TOOL_SNIPPETS * 2).filter(g::relevant)) }.take(KbPrompt.TOOL_SNIPPETS)
        if (hits.isEmpty()) return ToolResult(true, KbPrompt.toolOutput(q, hits), "无结果")
        // 智能体模式的检索也计入「本周引用」（之前只有对话模式的引用知识库会计数）。
        runCatching { kb.cite(hits.map { it.file }) }
        return ToolResult(true, capTokens(KbPrompt.toolOutput(q, hits), env.outputTokens), "${hits.size} 段 · " + hits.map { it.file }.distinct().joinToString("、").take(40))
    }
}

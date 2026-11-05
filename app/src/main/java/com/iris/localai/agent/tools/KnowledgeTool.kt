package com.iris.localai.agent

import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbPrompt
import kotlinx.serialization.json.JsonObject

/** 2.1.0: the user's knowledge folder for `kb_search` (the [com.iris.localai.knowledge.KnowledgeBase] in the app, fakes in tests). */
fun interface KnowledgeSearch {
    fun search(query: String, limit: Int): List<KbHit>
}

/** 2.1.0 知识库: top snippets (with file names) from the files in knowledge/. */
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
        // 2.4.1: the same coverage gate as 引用知识库 (a query sharing one common word is "no match").
        val hits = com.iris.localai.knowledge.KbChat.let { g -> g.reach(kb.search(q, KbPrompt.TOOL_SNIPPETS * 2).filter(g::relevant)) }.take(KbPrompt.TOOL_SNIPPETS)
        if (hits.isEmpty()) return ToolResult(true, KbPrompt.toolOutput(q, hits), "无结果")
        return ToolResult(true, capTokens(KbPrompt.toolOutput(q, hits), env.outputTokens), "${hits.size} 段 · " + hits.map { it.file }.distinct().joinToString("、").take(40))
    }
}

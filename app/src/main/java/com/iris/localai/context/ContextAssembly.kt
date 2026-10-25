package com.iris.localai.context

import com.iris.localai.data.ChatMessage
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.Conversation
import com.iris.localai.data.Role
import com.iris.localai.engine.PromptMessage
import com.iris.localai.memory.MemoryPrompt
import com.iris.localai.util.CtxMessage
import com.iris.localai.util.ThinkParser

/**
 * 1.10.0: what goes in front of the context planner. Pure, unit-tested:
 *  - the active history (after a manual break, error / empty replies left out, thinking stripped);
 *  - in 智能体 mode an assistant message carries its tool steps rolled up, one line each, so the
 *    next turn knows what was already done (the 1.9.0 gap);
 *  - a compaction summary replaces the folded messages;
 *  - the frozen long-term-memory block joins the system prompt; 2.3: the summary leads the history.
 */
object ContextAssembly {

    fun active(messages: List<ChatMessage>, startAfterId: String?, withTools: Boolean, vision: Boolean = false): List<CompactMsg> {
        var list = messages
        if (startAfterId != null) {
            val i = list.indexOfFirst { it.id == startAfterId }
            if (i >= 0) list = list.drop(i + 1)
        }
        return list
            // 1.13.0: an assistant reply with a notice-type error but real text stays in history.
            .filter { (it.error == null || (it.role == Role.ASSISTANT && it.content.isNotBlank())) && (it.content.isNotBlank() || it.attachments.isNotEmpty() || it.refs.isNotEmpty() || (withTools && it.agentSteps.isNotEmpty())) }
            .map { m ->
                CompactMsg(
                    m.id,
                    if (m.role == Role.USER) CompactMsg.USER else CompactMsg.ASSISTANT,
                    // 1.10.12: attached text files ride along with the user's words.
                    if (m.role == Role.USER) com.iris.localai.data.TurnRefs.userText(m.content, com.iris.localai.util.Attachments.promptBlock(m.attachments, vision), m.refs) else ThinkParser.stripForHistory(m.content),
                    if (m.role == Role.ASSISTANT) m.agentSteps else emptyList(),
                )
            }
            .filterNot { !it.isUser && it.text.isBlank() && (!withTools || it.steps.isEmpty()) }
    }

    /** Messages the planner still sees (the compacted part left out). */
    fun forPlanner(active: List<CompactMsg>, state: CompactState?, withTools: Boolean): List<CtxMessage> =
        Compactor.remaining(state, active).map { CtxMessage(it.id, it.role, it.promptText(withTools)) }

    /**
     * Base system prompt + memory, blank parts left out. 2.3: the compaction summary is no longer
     * part of it (see [lead]): the system prompt stays byte-identical across a compaction, so the
     * engines keep its KV cache.
     */
    fun system(base: String, memoryBlock: String): String = listOf(
        base.trim(),
        memoryBlock.trim(),
    ).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** 2.3: the compaction summary, placed in front of the remaining history (ContextRequest.lead). */
    fun lead(state: CompactState?, recall: Boolean = false): String =
        state?.let { Compactor.render(it) + if (recall) "\n" + RECALL_HINT else "" }.orEmpty()

    /** 2.1.9 (pi-vcc): after a compaction the original messages stay searchable — say how. */
    const val RECALL_HINT = "需要早期原文细节时用 recall 查，别重做已完成的事。"

    /**
     * Whether automatic compaction runs for this reply. 智能体: its own switch. 对话: its switch, and
     * only under 按 token 预算 (自动摘要 / 滑动窗口 / KV 平移 keep managing the window as in 1.9.0).
     */
    fun autoAllowed(agentMode: Boolean, strategy: ContextStrategy, autoAgent: Boolean, autoChat: Boolean): Boolean =
        if (agentMode) autoAgent else autoChat && strategy == ContextStrategy.TOKEN_BUDGET

    /** The memory block for a conversation: its frozen snapshot, capped to the window. */
    fun memoryBlock(conv: Conversation, inject: Boolean, contextTokens: Int, epoch: Int = 0): String =
        if (!inject || conv.memoryEpoch != epoch) "" else MemoryPrompt.block(conv.memorySnapshot.orEmpty(), MemoryPrompt.budget(contextTokens))

    /** Prompt messages of the base system + planner output, for tests and the agent prompt. */
    fun prompt(system: String, history: List<CtxMessage>, lead: String = ""): List<PromptMessage> =
        (if (system.isBlank()) emptyList() else listOf(PromptMessage(PromptMessage.SYSTEM, system))) +
            com.iris.localai.util.ContextPlanner.withLead(history.map { PromptMessage(it.role, it.content) }, lead)
}

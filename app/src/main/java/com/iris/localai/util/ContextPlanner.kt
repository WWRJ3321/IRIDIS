package com.iris.localai.util

import com.iris.localai.data.ContextStrategy
import com.iris.localai.engine.PromptMessage
import kotlin.math.ceil

/**
 * Rough token count without a tokenizer (the planner runs before the engine sees the text). CJK
 * characters are about one token each in Qwen / Gemma / Llama-3 vocabularies; Latin text is about
 * one token per 3.5 characters. Deliberately slightly high so plans stay inside the window; the
 * native engines still trim as a last resort.
 */
object TokenEstimator {
    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x2E80..0x9FFF || c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + ceil(other / 3.5).toInt()
    }

    /** One chat message including the template's role markers. */
    fun message(text: String): Int = estimate(text) + MESSAGE_OVERHEAD
    const val MESSAGE_OVERHEAD = 5
}

/** One history message as the planner sees it. Thinking is already stripped. */
data class CtxMessage(val id: String, val role: String, val content: String) {
    val tokens: Int = TokenEstimator.message(content)
}

data class ContextRequest(
    val system: String,
    val messages: List<CtxMessage>,
    val strategy: ContextStrategy,
    /**
     * 2.3: text that leads the history instead of joining the system prompt (the 智能体 compaction
     * summary): it changes whenever the history is folded, and in the system prompt it forced the
     * whole cached prefix to be prefilled again. Placed in front of the first kept message.
     */
    val lead: String = "",
    /** The model's real context window (tokens). */
    val contextTokens: Int,
    val maxNewTokens: Int,
    val keepTurns: Int = 8,
    val summary: String = "",
    val summaryUpToId: String? = null,
    val summaryTriggerPercent: Int = 75,
    /** 「从这里开始新上下文」: only messages after this id count. */
    val startAfterId: String? = null,
    /** The engine supports KV context shift (llama.cpp). Otherwise KV_SHIFT behaves like TOKEN_BUDGET. */
    val engineCanShift: Boolean = true,
)

data class ContextPlan(
    val messages: List<PromptMessage>,
    /** History messages left out (manual break, window, budget, summary). */
    val droppedMessages: Int,
    val estimatedTokens: Int,
    /** Prompt budget = context − room reserved for the reply. */
    val budget: Int,
    /** Strategy really applied (KV_SHIFT on an engine without it → TOKEN_BUDGET). */
    val applied: ContextStrategy,
    /** Non-empty when the summary strategy wants these (oldest) messages folded into the summary first. */
    val toSummarize: List<CtxMessage> = emptyList(),
    val contextShift: Boolean = false,
    /** Tokens to keep at the start on a context shift (system prompt + summary + attention sink). */
    val keepTokens: Int = 4,
    /** 2.2.9 E: history messages that really go into the prompt (for 「模型现在能看到最近 N 条」). */
    val visibleMessages: Int = 0,
)

/**
 * Fits a conversation into the model's context window. Pure function: the same input always gives
 * the same plan, and cut points move in *chunks* (a quarter of the window / turns) rather than one
 * message per turn, so the prompt prefix stays identical for several turns and the engines' KV /
 * prompt-cache reuse keeps working (a cut that moved every turn would force a full re-prefill
 * on every reply). User/assistant pairs are always dropped together, and the latest user turn is
 * never dropped.
 */
object ContextPlanner {

    fun plan(r: ContextRequest): ContextPlan {
        val ctx = r.contextTokens.coerceAtLeast(256)
        val reserve = r.maxNewTokens.coerceIn(64, ctx / 2)
        val budget = ctx - reserve

        // 1. Manual break.
        var active = r.messages
        var dropped = 0
        r.startAfterId?.let { id ->
            val i = active.indexOfFirst { it.id == id }
            if (i >= 0) { dropped += i + 1; active = active.drop(i + 1) }
        }

        // 2. Summary (only when it covers part of the active range).
        val strategy = if (r.strategy == ContextStrategy.KV_SHIFT && !r.engineCanShift) ContextStrategy.TOKEN_BUDGET else r.strategy
        var summary = ""
        if (strategy == ContextStrategy.SUMMARY && r.summary.isNotBlank() && r.summaryUpToId != null) {
            val i = active.indexOfFirst { it.id == r.summaryUpToId }
            if (i >= 0) { summary = r.summary; dropped += i + 1; active = active.drop(i + 1) }
        }
        // 2.3: the system prompt stays byte-identical for the KV cache; the summary (and the
        // compaction lead) go in front of the history instead.
        val system = r.system.trim()
        val lead = leadText(r.lead, summary)
        val systemTokens = (if (system.isBlank()) 0 else TokenEstimator.message(system)) +
            (if (lead.isEmpty()) 0 else TokenEstimator.message(lead))

        var rounds = rounds(active)

        when (strategy) {
            ContextStrategy.SLIDING_WINDOW -> {
                val k = r.keepTurns.coerceAtLeast(1)
                if (rounds.size > k) {
                    // Move the window `step` rounds at a time so the prefix is stable in between.
                    val step = (k / 4).coerceAtLeast(1)
                    val over = rounds.size - k
                    val drop = (ceil(over / step.toDouble()).toInt() * step).coerceAtMost(rounds.size - 1)
                    dropped += rounds.take(drop).sumOf { it.size }
                    rounds = rounds.drop(drop)
                }
            }
            ContextStrategy.SUMMARY -> {
                val total = systemTokens + rounds.sumOf { rd -> rd.sumOf { it.tokens } }
                val trigger = budget * r.summaryTriggerPercent.coerceIn(40, 95) / 100
                if (total > trigger && rounds.size >= 3) {
                    // Fold the oldest rounds in until the rest is ≤ 40% of the budget; always keep the
                    // last two rounds verbatim.
                    val target = budget * 40 / 100
                    var rest = total
                    var n = 0
                    while (n < rounds.size - 2 && rest > target) { rest -= rounds[n].sumOf { it.tokens }; n++ }
                    if (n > 0) {
                        val fold = rounds.take(n).flatten()
                        // The caller summarises, saves, and plans again; meanwhile this plan is still valid.
                        val fallback = budgetTrim(rounds, systemTokens, budget)
                        return finish(system, lead, fallback.first, dropped + fallback.second, budget, strategy, systemTokens)
                            .copy(toSummarize = fold)
                    }
                }
            }
            else -> {}
        }

        // 3. Token budget: always applied (the main rule for TOKEN_BUDGET, a safety net for the others).
        val keepFirst = strategy == ContextStrategy.TOKEN_BUDGET
        val (kept, d) = budgetTrim(rounds, systemTokens, budget, keepFirst)
        dropped += d
        val plan = finish(system, lead, kept, dropped, budget, strategy, systemTokens)
        return if (strategy == ContextStrategy.KV_SHIFT) {
            plan.copy(contextShift = true, keepTokens = (systemTokens + 4).coerceAtMost(ctx / 4))
        } else plan
    }

    /** Groups messages into rounds: a user message plus the replies that follow it. */
    fun rounds(messages: List<CtxMessage>): List<List<CtxMessage>> {
        val out = ArrayList<MutableList<CtxMessage>>()
        for (m in messages) {
            if (m.role == PromptMessage.USER || out.isEmpty()) out += mutableListOf(m) else out.last() += m
        }
        return out
    }

    /**
     * Drops whole rounds (oldest first; with [keepFirst] the first round stays — it usually sets the
     * topic) until the history fits [budget]. The amount dropped is rounded up to a quarter of the
     * budget so the cut only moves every few turns.
     */
    fun budgetTrim(
        rounds: List<List<CtxMessage>>, systemTokens: Int, budget: Int, keepFirst: Boolean = false,
    ): Pair<List<List<CtxMessage>>, Int> {
        val total = systemTokens + rounds.sumOf { rd -> rd.sumOf { it.tokens } }
        val excess = total - budget
        if (excess <= 0 || rounds.size <= 1) return rounds to 0
        val chunk = (budget / 4).coerceAtLeast(1)
        val target = ceil(excess / chunk.toDouble()).toInt() * chunk
        val head = if (keepFirst && rounds.size > 2) 1 else 0
        val list = rounds.toMutableList()
        var removedTokens = 0
        var removedMsgs = 0
        while (removedTokens < target && list.size > head + 1) {
            val rd = list.removeAt(head)
            removedTokens += rd.sumOf { it.tokens }
            removedMsgs += rd.size
        }
        // Still too big with the first round kept: give it up too (never the last round).
        if (head == 1 && removedTokens < excess && list.size > 1) {
            val rd = list.removeAt(0)
            removedTokens += rd.sumOf { it.tokens }
            removedMsgs += rd.size
        }
        return list to removedMsgs
    }

    /** 2.3: compaction lead + 自动摘要 summary (with its header), blank parts left out. */
    fun leadText(lead: String, summary: String): String = listOf(
        lead.trim(),
        summary.trim().takeIf { it.isNotEmpty() }?.let { "$SUMMARY_HEADER\n$it" }.orEmpty(),
    ).filter { it.isNotEmpty() }.joinToString("\n\n")

    /**
     * 2.3: [lead] in front of the history — joined to the first message when it is the user's
     * (no extra turn, alternation kept for strict templates), else as a user turn of its own.
     */
    fun withLead(history: List<PromptMessage>, lead: String): List<PromptMessage> {
        if (lead.isBlank()) return history
        val first = history.firstOrNull()
        return if (first != null && first.role == PromptMessage.USER) listOf(first.copy(content = lead.trim() + "\n\n" + first.content)) + history.drop(1)
        else listOf(PromptMessage(PromptMessage.USER, lead.trim())) + history
    }

    private fun finish(
        system: String, lead: String, rounds: List<List<CtxMessage>>, dropped: Int, budget: Int,
        applied: ContextStrategy, systemTokens: Int,
    ): ContextPlan {
        val msgs = ArrayList<PromptMessage>()
        if (system.isNotBlank()) msgs += PromptMessage(PromptMessage.SYSTEM, system)
        msgs += withLead(rounds.flatten().map { PromptMessage(it.role, it.content) }, lead)
        return ContextPlan(
            messages = msgs,
            droppedMessages = dropped,
            estimatedTokens = systemTokens + rounds.sumOf { rd -> rd.sumOf { it.tokens } },
            budget = budget,
            applied = applied,
            visibleMessages = rounds.sumOf { it.size },
        )
    }

    /** Prompt that asks the local model to fold [messages] (and an older summary) into one paragraph. */
    fun summaryPrompt(previous: String, messages: List<CtxMessage>): List<PromptMessage> {
        val transcript = buildString {
            if (previous.isNotBlank()) append("【之前的摘要】\n").append(previous.trim()).append("\n\n")
            append("【对话】\n")
            messages.forEach { m ->
                append(if (m.role == PromptMessage.USER) "用户：" else "助手：")
                append(m.content.trim().take(4000)).append('\n')
            }
        }
        return listOf(
            PromptMessage(
                PromptMessage.SYSTEM,
                "你是对话摘要助手。把下面的对话压缩成一段简洁的中文摘要（不超过 300 字），保留：用户的目标与偏好、" +
                    "已确定的事实、数字、名称、结论和未完成的问题。不要寒暄，不要评价，只输出摘要正文。",
            ),
            PromptMessage(PromptMessage.USER, transcript),
        )
    }

    const val SUMMARY_HEADER = "【此前对话的摘要】"
}

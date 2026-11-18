package com.iris.localai.data

import kotlinx.serialization.Serializable

/** 2.2 composer 「技能」: what a user turn points the agent at. */
@Serializable
enum class TurnRefKind { SKILL, TOOL }

/** one skill / agent tool the user picked for this turn (shown as a chip, sent as one line). */
@Serializable
data class TurnRef(val kind: TurnRefKind, val name: String) {
    /** Text shown on the chip and in the bubble. */
    val token: String get() = "@$name"
}

/**
 * the picked skills / tools ride along with **that user turn only** — one short line appended
 * to the user text in the prompt. The system prompt never changes, so its KV cache stays reusable.
 * Pure (unit-tested).
 */
object TurnRefs {
    /** The line added to the user turn ("" when nothing was picked). */
    fun promptLine(refs: List<TurnRef>): String {
        val list = refs.distinct()
        if (list.isEmpty()) return ""
        val skills = list.filter { it.kind == TurnRefKind.SKILL }.map { it.name }
        val tools = list.filter { it.kind == TurnRefKind.TOOL }.map { it.name }
        val parts = buildList {
            if (skills.isNotEmpty()) add("use skill " + skills.joinToString(", ") { "\"$it\"" } + " (load it with the skill tool first)")
            if (tools.isNotEmpty()) add("use tool " + tools.joinToString(", "))
        }
        return "[For this request: " + parts.joinToString("; ") + ".]"
    }

    /** The prompt text of a user message: words + attachment block + the reference line. */
    fun userText(content: String, attachmentBlock: String, refs: List<TurnRef>): String {
        val base = (content + attachmentBlock).trim()
        val line = promptLine(refs)
        return if (line.isEmpty()) base else if (base.isEmpty()) line else "$base\n\n$line"
    }

    /** Adds [ref] once (picking the same one again does nothing). */
    fun add(list: List<TurnRef>, ref: TurnRef): List<TurnRef> = if (ref in list) list else list + ref

    /** Whether sending these refs needs 智能体 mode turned on first. */
    fun needsAgent(refs: List<TurnRef>, agentMode: Boolean): Boolean = refs.isNotEmpty() && !agentMode
}

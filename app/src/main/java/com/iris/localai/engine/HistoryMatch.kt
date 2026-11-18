package com.iris.localai.engine

import com.iris.localai.engine.load.*
import com.iris.localai.engine.resources.*

/**
 * whether a new request's history ([b], everything before the new user turn) still matches
 * what the engine's live conversation has already seen ([a]), so its KV cache can be reused instead
 * of re-prefilling the whole prompt (the cause of the slow first token in 智能体 mode).
 *
 * System and user turns must match exactly (trimmed). An assistant turn is the model's own output,
 * and the caller often sends it back slightly changed: reasoning removed, trimmed, or cut where a
 * tool call ended while the engine had already decoded a few more tokens. So assistant turns match
 * when, after removing think blocks and whitespace, either one is a prefix of the other.
 */
object HistoryMatch {
    private val THINK = Regex("(?s)<think>.*?(</think>|$)")

    fun same(a: List<PromptMessage>?, b: List<PromptMessage>): Boolean {
        if (a == null || a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.role != y.role) return false
            if (x.role == PromptMessage.ASSISTANT) {
                if (!assistantSame(x.content, y.content)) return false
            } else if (x.content.trim() != y.content.trim()) return false
        }
        return true
    }

    fun assistantSame(engine: String, sent: String): Boolean {
        val e = norm(engine)
        val s = norm(sent)
        if (e == s) return true
        if (e.isEmpty() || s.isEmpty()) return false
        return e.startsWith(s) || s.startsWith(e)
    }

    private fun norm(t: String): String = THINK.replace(t, "").replace(Regex("\\s+"), " ").trim()

    /**
     * turns safe to seed a fresh conversation with — empty assistant turns dropped and
     * consecutive same-role turns merged. Strict chat templates (Gemma) reject user,user or an
     * empty model turn, which used to switch the engine to a ChatML fallback for the whole session.
     */
    fun seedTurns(turns: List<PromptMessage>): List<PromptMessage> {
        val out = ArrayList<PromptMessage>(turns.size)
        for (t in turns) {
            if (t.role == PromptMessage.ASSISTANT && t.content.isBlank()) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.role == t.role) out[out.size - 1] = prev.copy(content = prev.content.trimEnd() + "\n\n" + t.content.trimStart(), images = prev.images + t.images)
            else out += t
        }
        return out
    }
}

package com.iris.localai.util

import com.iris.localai.agent.AgentStep
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Conversation
import com.iris.localai.data.Role

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 2.5.0-beta7 「导出完整日志」: the run log plus the full text of the chats — user messages, replies,
 * reasoning (<think>), every agent tool step (name / arguments / result / output as the model saw
 * it), timestamps, model. One zip: README.txt, app-log.txt, conversations/NN-title.txt.
 *
 * What is NOT on disk, so it can't be exported (written into README.txt):
 *  - the backend per message (only the model name is saved; the backend loaded *now* is in the header);
 *  - a timestamp per tool step (steps carry none; they belong to the reply's time);
 *  - tool output beyond the cap the agent stored ([AgentStep.detail] is already capped);
 *  - reasoning of models that never emit <think> in the saved text.
 * API keys are never part of a conversation; anything key-like in the text is masked anyway ([redact]).
 * Pure except [writeZip]'s stream; the caller runs it on Dispatchers.IO.
 */
object FullLogExport {
    const val README = "README.txt"
    const val APP_LOG = "app-log.txt"
    const val DIR = "conversations/"

    val NOT_PERSISTED = listOf(
        "backend per message (only the model name is stored; the backend loaded at export time is in the header)",
        "timestamp per tool call (tool steps store none; they share the reply's timestamp)",
        "full tool output beyond the cap the agent keeps (step output was already truncated when saved)",
        "reasoning of models that do not write <think> into the saved reply",
    )

    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun time(ms: Long, zone: ZoneId): String = if (ms <= 0) "?" else TS.withZone(zone).format(Instant.ofEpochMilli(ms))

    private val SECRETS = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{12,}"),
        Regex("\\b(?:sk|rk|pk)-[A-Za-z0-9_\\-]{16,}"),
        Regex("\\b(?:hf_|ghp_|gho_|github_pat_|AIza|xox[abp]-)[A-Za-z0-9_\\-]{12,}"),
        Regex("(?i)((?:api[_-]?key|apikey|access[_-]?token|secret|password|authorization)\"?\\s*[:=]\\s*\"?)[^\"\\s,}]{6,}"),
    )

    /** Masks anything that looks like a key or token. */
    fun redact(s: String): String = SECRETS.fold(s) { acc, r ->
        r.replace(acc) { m -> m.groupValues.getOrElse(1) { "" } + "***" }
    }

    fun header(appLine: String, engineLine: String, zone: ZoneId, now: Long, chats: Int, scope: String): String = buildString {
        append("IRIDIS full log export\n")
        append(appLine).append('\n')
        append("Exported: ").append(time(now, zone)).append(" (").append(zone.id).append(")\n")
        append("Model/backend loaded at export: ").append(engineLine).append('\n')
        append("Conversations: ").append(chats).append(" (").append(scope).append(")\n")
        append("Contains: run log (").append(APP_LOG).append("), and per conversation: user messages, assistant replies, reasoning, ")
        append("agent tool calls (name, arguments, result, output), timestamps, model name, generation stats.\n")
        append("Not stored by the app, so not included:\n")
        NOT_PERSISTED.forEach { append(" - ").append(it).append('\n') }
        append("API keys are not exported; key-like strings in the text are masked as ***.\n")
    }

    fun formatConversation(c: Conversation, zone: ZoneId): String = buildString {
        append("# ").append(c.title.ifBlank { "(untitled)" }).append('\n')
        append("id: ").append(c.id).append(" · created ").append(time(c.createdAt, zone)).append(" · updated ").append(time(c.updatedAt, zone)).append('\n')
        if (c.summary.isNotBlank()) append("\n[auto summary]\n").append(c.summary).append('\n')
        c.messages.forEachIndexed { i, m -> append('\n'); appendMessage(i + 1, m, zone) }
    }.let(::redact)

    private fun StringBuilder.appendMessage(n: Int, m: ChatMessage, zone: ZoneId) {
        val who = if (m.role == Role.USER) "USER" else "ASSISTANT"
        append("=== #").append(n).append(' ').append(who).append(" · ").append(time(m.createdAt, zone))
        m.modelName?.let { append(" · model: ").append(it) }
        if (m.edited) append(" · edited")
        if (m.interrupted) append(" · interrupted")
        append(" ===\n")
        m.attachments.forEach { append("[attachment] ").append(it.name).append(" (").append(it.kind.name.lowercase()).append(", ").append(it.sizeBytes).append(" B)\n") }
        if (m.refs.isNotEmpty()) append("[picked] ").append(m.refs.joinToString { "${it.kind.name.lowercase()}:${it.name}" }).append('\n')
        val parts = if (m.role == Role.ASSISTANT) ThinkParser.parse(m.content) else null
        if (parts != null && parts.thinking.isNotBlank()) {
            append("--- reasoning").append(if (m.thinkingMs > 0) " (${m.thinkingMs} ms)" else "").append(" ---\n").append(parts.thinking.trim()).append('\n')
        }
        m.agentSteps.forEachIndexed { i, s -> appendStep(i + 1, s) }
        if (parts != null) {
            if (parts.hasThinking || m.agentSteps.isNotEmpty()) append("--- reply ---\n")
            append(parts.answer.trim()).append('\n')
        } else append(m.content).append('\n')
        m.error?.let { append("[error] ").append(it).append('\n') }
        if (m.kbSources.isNotEmpty()) append("[knowledge] ").append(m.kbSources.joinToString(" | ")).append('\n')
        m.stats?.let { st ->
            append("[stats] prompt ").append(st.promptTokens).append(" tok, generated ").append(st.generatedTokens)
                .append(" tok, prefill ").append(st.prefillMs).append(" ms, generate ").append(st.generateMs).append(" ms")
            if (m.totalMs > 0) append(", total ").append(m.totalMs).append(" ms")
            append('\n')
        }
        if (m.versions.size > 1) append("[versions] ").append(m.versions.size).append(" (active ").append(m.activeVersion + 1).append("; other versions not shown)\n")
    }

    private fun StringBuilder.appendStep(n: Int, s: AgentStep) {
        append("--- tool call ").append(n).append(": ").append(s.tool).append(" [").append(s.status.name).append("] ---\n")
        if (s.lead.isNotBlank()) append("thought: ").append(s.lead.trim()).append('\n')
        append("args: ").append(s.args).append('\n')
        if (s.result.isNotBlank()) append("result: ").append(s.result).append('\n')
        if (s.detail.isNotBlank()) append("output:\n").append(s.detail.trimEnd()).append('\n')
        s.plan?.let { pl -> append("plan: ").append(pl.title).append('\n'); pl.steps.forEach { append("  [").append(it.status.name).append("] ").append(it.text).append('\n') } }
        if (s.images.isNotEmpty()) append("images: ").append(s.images.joinToString()).append('\n')
    }

    /** File name inside the zip: 01-title.txt (title cut and made safe). */
    fun entryName(i: Int, c: Conversation): String {
        val t = c.title.ifBlank { c.id }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        return DIR + "%03d-%s.txt".format(i + 1, t)
    }

    /** Writes the zip. [conversations] is called once and may be large; the caller is on Dispatchers.IO. */
    fun writeZip(out: OutputStream, header: String, appLog: String, conversations: List<Conversation>, zone: ZoneId) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put(README, header)
            put(APP_LOG, redact(appLog))
            conversations.forEachIndexed { i, c -> put(entryName(i, c), formatConversation(c, zone)) }
        }
    }
}

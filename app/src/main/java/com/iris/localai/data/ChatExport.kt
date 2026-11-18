package com.iris.localai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 多选会话导出 — Markdown / TXT / JSON. Pure (time zone passed in), unit-tested. */
object ChatExport {
    enum class Format(val ext: String, val mime: String) { MARKDOWN("md", "text/markdown"), TXT("txt", "text/plain"), JSON("json", "application/json") }

    @Serializable data class ExportMessage(val role: String, val content: String, val time: Long, val model: String? = null)
    @Serializable data class ExportConversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val messages: List<ExportMessage>)
    @Serializable data class ExportFile(val app: String = "IRIDIS", val version: String, val exportedAt: Long, val conversations: List<ExportConversation>)

    /** What the message shows: the reasoning (<think>…</think>) is left out. */
    fun text(m: ChatMessage): String = m.content.replace(THINK, "").trim()
    private val THINK = Regex("<think>[\\s\\S]*?(</think>|$)")

    private fun time(ms: Long, tz: TimeZone): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = tz }.format(Date(ms))

    fun title(c: Conversation, untitled: String): String = c.title.trim().ifEmpty { untitled }

    /** [user] / [assistant] are the role labels (localized by the caller). */
    fun markdown(list: List<Conversation>, user: String, assistant: String, untitled: String, tz: TimeZone = TimeZone.getDefault()): String =
        list.joinToString("\n\n---\n\n") { c ->
            buildString {
                append("# ").append(title(c, untitled)).append("\n\n")
                append("_").append(time(c.createdAt, tz)).append("_\n")
                c.messages.forEach { m ->
                    val who = if (m.role == Role.USER) user else (m.modelName?.let { "$assistant · $it" } ?: assistant)
                    append("\n**").append(who).append("** · ").append(time(m.createdAt, tz)).append("\n\n")
                    append(text(m)).append("\n")
                }
            }.trimEnd()
        } + "\n"

    fun txt(list: List<Conversation>, user: String, assistant: String, untitled: String, tz: TimeZone = TimeZone.getDefault()): String =
        list.joinToString("\n\n==========\n\n") { c ->
            buildString {
                append(title(c, untitled)).append("\n").append(time(c.createdAt, tz)).append("\n")
                c.messages.forEach { m ->
                    append("\n[").append(if (m.role == Role.USER) user else assistant).append(" ").append(time(m.createdAt, tz)).append("]\n")
                    append(text(m)).append("\n")
                }
            }.trimEnd()
        } + "\n"

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun json(list: List<Conversation>, version: String, now: Long): String = json.encodeToString(ExportFile.serializer(), ExportFile(
        version = version, exportedAt = now,
        conversations = list.map { c ->
            ExportConversation(c.id, c.title, c.createdAt, c.updatedAt, c.messages.map { m ->
                ExportMessage(if (m.role == Role.USER) "user" else "assistant", text(m), m.createdAt, m.modelName)
            })
        },
    ))

    /** File name: the title of a single conversation (safe characters), else IRIDIS-chats-<n>-<time>. */
    fun fileName(list: List<Conversation>, format: Format, now: Long, untitled: String, tz: TimeZone = TimeZone.getDefault()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).apply { timeZone = tz }.format(Date(now))
        val base = if (list.size == 1) title(list[0], untitled).replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").take(40).trim().ifEmpty { "IRIDIS-chat" } + "-" + stamp
        else "IRIDIS-chats-${list.size}-$stamp"
        return base + "." + format.ext
    }
}

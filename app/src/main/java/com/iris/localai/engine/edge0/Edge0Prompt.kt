package com.iris.localai.engine.edge0

import com.iris.localai.engine.StopReason
import com.iris.localai.engine.PromptMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * chat history → the JSON edge0_jni.cpp reads, and the engine's event stream → the text form the
 * rest of the app already folds: reasoning inside `<think>…</think>`, native tool calls as
 * `<tool_call>{"name":…,"arguments":…}</tool_call>` (ToolCallParser reads that form). Pure, unit-tested.
 */
object Edge0Prompt {
    private val THINK = Regex("(?s)<think>(.*?)(</think>|$)")

    fun inputJson(history: List<PromptMessage>, tools: List<JsonObject> = emptyList()): String = buildJsonObject {
        put("messages", buildJsonArray {
            history.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    if (m.role == PromptMessage.ASSISTANT) {
                        // earlier thinking is kept apart (the template decides whether to show it)
                        val think = THINK.find(m.content)?.groupValues?.get(1)?.trim()
                        put("content", m.content.replace(THINK, "").trim())
                        if (!think.isNullOrEmpty()) put("reasoning_content", think)
                    } else put("content", m.content)
                })
            }
        })
        if (tools.isNotEmpty()) put("tools", JsonArray(tools))
    }.toString()

    /** Turns stream events into text pieces; [finish] closes an open `<think>`. */
    class Stream {
        private var inThink = false
        private val args = StringBuilder()

        fun map(kind: Int, text: String): String = when (kind) {
            Edge0Native.REASONING -> (if (!inThink) { inThink = true; "<think>" } else "") + text
            Edge0Native.ANSWER -> closeThink() + text
            Edge0Native.TOOL_BEGIN -> { args.setLength(0); closeThink() }
            Edge0Native.TOOL_ARGS -> { args.append(text); "" }
            Edge0Native.TOOL_END -> closeThink() + toolText(text)
            Edge0Native.TOOL_REJECTED -> closeThink() + text
            else -> ""
        }

        fun finish(): String = closeThink()

        private fun closeThink(): String = if (inThink) { inThink = false; "</think>" } else ""

        private fun toolText(endJson: String): String {
            val o = runCatching { Json.parseToJsonElement(endJson).jsonObject }.getOrNull() ?: return ""
            val name = o["name"]?.jsonPrimitive?.content.orEmpty()
            val argText = o["arguments"]?.jsonPrimitive?.content ?: args.toString()
            val argEl = runCatching { Json.parseToJsonElement(argText.ifBlank { "{}" }) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(argText) }
            return "<tool_call>" + buildJsonObject { put("name", name); put("arguments", argEl) }.toString() + "</tool_call>"
        }
    }

    /** Native stop name → app reason. */
    fun stop(name: String?): StopReason = when (name) {
        "end", "stop", "tool_calls" -> StopReason.END
        "length" -> StopReason.MAX_TOKENS
        "capacity" -> StopReason.CONTEXT_FULL
        "cancelled" -> StopReason.CANCELLED
        else -> StopReason.ERROR
    }
}

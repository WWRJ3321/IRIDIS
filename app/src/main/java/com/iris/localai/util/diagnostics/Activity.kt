package com.iris.localai.util.diagnostics

import com.iris.localai.util.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*

/** what the model is doing right now (indicator above the composer). Pure parts unit-tested. */
data class Activity(val label: String, val since: Long = System.currentTimeMillis()) {
    companion object {
        /** A new phase must hold this long before it replaces the shown one (no flicker). */
        const val DWELL_MS = 350L
        private val CALL = Regex("(?im)```[ \\t]*(tool|tool_call|json)?[ \\t]*\\r?\\n?\\s*\\{|<tool_call>|^\\s*\\{\\s*\"(tool|name)\"|tool_call\\s*:")

        /** Phase of a streaming 智能体 reply: thinking, writing a tool call, or writing the answer. */
        fun phaseOf(streamed: String): String {
            val open = streamed.lastIndexOf("<think>")
            val close = streamed.lastIndexOf("</think>")
            if (open >= 0 && close < open) return "思考中"
            val answer = if (close >= 0) streamed.substring(close + 8) else streamed
            return if (CALL.containsMatchIn(answer)) "编写工具调用" else "生成回答"
        }

        /** "思考中 · 12 秒" (no seconds under 2 s). */
        fun line(label: String, seconds: Long): String = if (seconds >= 2) "$label · $seconds 秒" else label
    }
}

package com.iris.localai.util.diagnostics

import com.iris.localai.util.*
import com.iris.localai.util.io.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import com.iris.localai.engine.PromptMessage

/**
 * every prompt sent to the model for a reply (each 智能体 step is one call), kept in memory
 * for 「查看提示内容」. Newest [maxReplies] replies only; cleared when the app restarts. Pure, unit-tested.
 */
class PromptLog(private val maxReplies: Int = 30, private val maxCallsPerReply: Int = 16) {
    private val calls = LinkedHashMap<String, MutableList<List<PromptMessage>>>()

    @Synchronized fun record(replyId: String, messages: List<PromptMessage>) {
        val list = calls.remove(replyId) ?: ArrayList()
        list += messages.toList()
        while (list.size > maxCallsPerReply) list.removeAt(0)
        calls[replyId] = list
        while (calls.size > maxReplies) calls.remove(calls.keys.first())
    }

    @Synchronized fun get(replyId: String): List<List<PromptMessage>> = calls[replyId]?.toList().orEmpty()

    companion object {
        private fun roleName(r: String) = when (r) {
            PromptMessage.SYSTEM -> "系统 system"
            PromptMessage.USER -> "用户 user"
            PromptMessage.ASSISTANT -> "模型 assistant"
            else -> r
        }

        /** Readable dump: one section per call, each message with its role and estimated tokens. */
        fun format(calls: List<List<PromptMessage>>): String = buildString {
            if (calls.isEmpty()) { append("这条回复没有记录（只保留本次打开 App 后的最近 30 条回复）"); return@buildString }
            calls.forEachIndexed { i, msgs ->
                val tok = msgs.sumOf { TokenEstimator.message(it.content) }
                if (i > 0) append("\n\n")
                append("══════ 第 ${i + 1} 次调用（共 ${calls.size} 次）· ${msgs.size} 条消息 · 约 $tok token ══════\n")
                msgs.forEach { m ->
                    append("\n── ").append(roleName(m.role)).append(" · 约 ").append(TokenEstimator.message(m.content)).append(" token ──\n")
                    append(m.content).append('\n')
                }
            }
            append("\n（引擎会再套上模型自己的对话模板；最后一条是本次新发送的内容，前面相同的部分可复用 KV 缓存）")
        }
    }
}

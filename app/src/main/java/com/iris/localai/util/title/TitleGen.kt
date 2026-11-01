package com.iris.localai.util.title

import com.iris.localai.engine.PromptMessage

/**
 * 2.2.14: short conversation title written by the loaded model after the first reply.
 * The first user line stays as the fallback when the model gives nothing usable.
 */
object TitleGen {
    const val MAX_CHARS = 16
    const val MAX_TOKENS = 32

    fun messages(user: String, assistant: String): List<PromptMessage> = listOf(
        PromptMessage(
            PromptMessage.USER,
            "给下面这段对话起一个简短标题（不超过 12 个字），只输出标题本身，不要引号和标点。\n\n" +
                "用户：${user.trim().take(400)}\n助手：${assistant.trim().take(400)}",
        ),
    )

    private val PREFIX = Regex("^(标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val STRIP = "\"'“”‘’「」『』《》*#`。，,.!！?？:：;；"

    /** The model's answer as a title, or null (empty / too long to be a title / just echoes the prompt). */
    fun clean(raw: String): String? {
        var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = PREFIX.replace(t, "").trim().trim { it in STRIP || it.isWhitespace() }
        if (t.isEmpty() || t.startsWith("给下面") || t.contains("<")) return null
        return if (t.length <= MAX_CHARS) t else t.take(MAX_CHARS) + "…"
    }

    /** Only the first exchange of a conversation the user has not renamed gets one. */
    fun due(userMessages: Int, hasAnswer: Boolean, alreadyTitled: Boolean): Boolean =
        !alreadyTitled && userMessages == 1 && hasAnswer
}

package com.iris.localai.engine

import com.iris.localai.util.thinking.ThinkParser

/**
 * 输入框「思考」按钮 → 模型的完整开关链路（2.7.0）。
 *
 * - [alwaysThinks]：模板自己强制先思考（结尾直接开 `<think>`、又没有 enable_thinking 等开关；
 *   gpt-oss 只能调档不能关）。这类模型关不掉推理，关闭按钮时只隐藏思考过程并提示一次。
 * - [softSwitch]：模板用系统提示里的旗标切换思考（`/think`·`/no_think`、`detailed thinking on/off`），
 *   llama.cpp 的内置模板不认 enable_thinking，所以在系统消息前补上这个旗标。
 * - [hideThinking]：思考已关时回复里仍出现的推理（强制思考的模型、不守规矩的模型）不显示、不存。
 */
object ThinkSwitch {
    private val SWITCHES = listOf("enable_thinking", "/no_think", "detailed thinking", "<|think|>", "thinking_mode", "reasoning_effort")

    /** 模板里有真正的开关（jinja 变量或系统旗标）。 */
    fun hasSwitch(template: String?): Boolean = template != null && SWITCHES.any { it in template }

    /**
     * 关不掉思考：模板在回复开头强制打开推理且没有开关；或只能调档（gpt-oss）。
     * [prefilled] 来自引擎（以“开思考”渲染一句 hi 的提示结尾是否已是 `<think>`）。
     */
    fun alwaysThinks(template: String?, supports: Boolean, prefilled: Boolean, effortKind: String): Boolean =
        supports && (effortKind == "oss" || (prefilled && !hasSwitch(template)))

    /** 系统旗标；null = 该模板不用旗标。 */
    fun softSwitch(template: String?, on: Boolean): String? = when {
        template == null -> null
        "detailed thinking" in template -> if (on) "detailed thinking on" else "detailed thinking off"
        "/no_think" in template -> if (on) "/think" else "/no_think"
        else -> null
    }

    /**
     * 用系统旗标切换思考的模板，也算支持思考（之前没有 `<think>` 字面量时按钮是灰的）。
     * 2.7.3：只带 enable_thinking 等 jinja 开关的模板（如 Qwen3.5/3.6-35B-A3B）同样算支持，
     * 与设置页（[ThinkSupport.templateThinks]）一致；开/关仍经 disableThinking 传给引擎，关时 [hideThinking] 兜底。
     */
    fun supports(nativeSupports: Boolean, template: String?): Boolean =
        nativeSupports || softSwitch(template, true) != null || hasSwitch(template)

    /** 把旗标放到系统消息开头（没有系统消息就加一条）；已有同一旗标不重复。 */
    fun applySoft(history: List<PromptMessage>, template: String?, on: Boolean): List<PromptMessage> {
        val flag = softSwitch(template, on) ?: return history
        val first = history.firstOrNull()
        return if (first != null && first.role == PromptMessage.SYSTEM) {
            if (first.content.startsWith(flag)) history
            else listOf(first.copy(content = flag + "\n" + first.content)) + history.drop(1)
        } else listOf(PromptMessage(PromptMessage.SYSTEM, flag)) + history
    }

    /** 思考关时：去掉回复里的推理部分（完整块、只有 `</think>` 的前半段、还没闭合的推理）。 */
    fun hideThinking(text: String): String {
        if (!text.contains("<think>") && !text.contains("</think>")) return text
        val p = ThinkParser.parse(text)
        return if (p.hasThinking) p.answer else text
    }
}

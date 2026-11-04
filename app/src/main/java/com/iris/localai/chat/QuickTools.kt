package com.iris.localai.chat

import com.iris.localai.R

/**
 * 2.3.6: 快捷工具 on the empty chat. Each one only pre-fills the composer with an instruction line
 * (the user adds the text and sends it), so it works with every engine and model, adds no native code
 * and never sends anything by itself.
 */
object QuickTools {
    data class Tool(val id: String, val label: Int, val prompt: Int)

    val all: List<Tool> = listOf(
        Tool("write", R.string.qt236_write, R.string.qt236_write_p),
        Tool("translate", R.string.qt236_translate, R.string.qt236_translate_p),
        Tool("summary", R.string.qt236_summary, R.string.qt236_summary_p),
        Tool("polish", R.string.qt236_polish, R.string.qt236_polish_p),
        Tool("code", R.string.qt236_code, R.string.qt236_code_p),
        Tool("scam", R.string.qt236_scam, R.string.qt236_scam_p),
    )

    /** The composer text for [tool]: its instruction, then a blank line for the user's own text. */
    fun draft(prompt: String): String = prompt.trimEnd() + "\n\n"
}

package com.iris.localai.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 2.2.17 D 「最近提示」: transient notices (banner / toast: load errors, GPU→CPU fallback notes,
 * download failures, tool errors…) are also kept here, so they can be read again afterwards
 * (模型与加载 → 最近提示). In memory only, newest first, short: a repeat of the same text within
 * [MERGE_MS] only moves it to the top and counts it, so a looping notice cannot flood the list.
 */
object HintLog {
    data class Hint(val text: String, val source: String, val at: Long, val count: Int = 1)

    const val MAX = 30
    const val MERGE_MS = 10 * 60_000L
    const val MAX_CHARS = 2000

    object Source { const val CHAT = "对话"; const val MODEL = "模型加载"; const val DOWNLOAD = "下载"; const val TOOL = "工具"; const val SYSTEM = "系统" }

    private val _items = MutableStateFlow<List<Hint>>(emptyList())
    val items: StateFlow<List<Hint>> = _items

    fun add(text: String?, source: String = Source.CHAT, now: Long = System.currentTimeMillis()) {
        val t = text?.trim()?.take(MAX_CHARS).orEmpty()
        if (t.isEmpty()) return
        _items.update { list -> merge(list, t, source, now) }
    }

    /** Pure: [list] with [text] added on top (merged with an equal recent entry), capped to [MAX]. */
    fun merge(list: List<Hint>, text: String, source: String, now: Long): List<Hint> {
        val i = list.indexOfFirst { it.text == text && now - it.at <= MERGE_MS }
        val head = if (i >= 0) list[i].copy(at = now, count = list[i].count + 1, source = source) else Hint(text, source, now)
        val rest = if (i >= 0) list.filterIndexed { j, _ -> j != i } else list
        return (listOf(head) + rest).take(MAX)
    }

    fun clear() { _items.value = emptyList() }
}

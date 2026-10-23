package com.iris.localai.voice

import com.iris.localai.engine.PromptMessage
import com.iris.localai.util.SentenceSplitter

/**
 * 2.1.7 语音对话 — less "AI味", speakable output, tools in voice. All pure and unit-tested.
 *
 * 2.1.9: the voice instruction sits at the end of the system prompt ([withVoiceSystem]), a fixed
 * place: every request in a call has the same system prompt and the stored history is sent exactly
 * as it is kept, so the engine's KV prefix (LiteRT HistoryMatch, GenieX exact match, llama prefix)
 * still matches next turn. (2.1.7–2.1.8 put it on the newest user turn only; that turn was sent with
 * the note but kept without it, so the next voice turn never matched the cached history.)
 */
object VoiceStyle {
    /** Voice-call instruction (short), appended to the system prompt while in a call. */
    const val NOTE = "（语音聊天：像人当面说话那样回我，一两句短句，口语，不列清单，不客套，不复述，不总结。）"

    /** [messages] with [NOTE] at the end of the system prompt (one is added when there is none). */
    fun withVoiceSystem(messages: List<PromptMessage>): List<PromptMessage> {
        val i = messages.indexOfFirst { it.role == PromptMessage.SYSTEM }
        if (i < 0) return listOf(PromptMessage(PromptMessage.SYSTEM, NOTE)) + messages
        return messages.mapIndexed { idx, m -> if (idx == i) m.copy(content = m.content.trimEnd().let { c -> if (c.isEmpty()) NOTE else c + "\n\n" + NOTE }) else m }
    }

    /** Boilerplate openers (only at the very start of a reply). Repeated: 「好的！当然，…」. */
    private val OPENER = Regex(
        "^\\s*(好的|好嘞|当然可以|当然|没问题|好问题|这是一个好问题|这是个好问题|很高兴(为你|为您|能)?(解答|回答|帮助你|帮助您|帮到你)|" +
            "作为一个?(AI|人工智能)(语言模型|助手|模型)?)[，,！!。.~～：:]*\\s*",
        RegexOption.IGNORE_CASE,
    )
    /** A sentence that is pure closing boilerplate; it and everything after it are dropped. */
    private val CLOSER = Regex(
        "^\\s*(希望(这|以上|我的回答)?(些|个)?(内容|信息|回答)?(能|可以)?(对你|对您)?(有所|有)?帮助|" +
            "如果(你|您)?(还有|有)(其他|别的|任何)?(问题|疑问|需要)|" +
            "(有|还有)(任何|其他)?(问题|疑问).{0,6}(随时|欢迎)|" +
            "(欢迎)?随时(问我|告诉我|找我|提问|联系)|" +
            "还有什么(我)?(可以|能)?帮(你|您)|" +
            "祝你.{0,8}(愉快|顺利))",
    )

    /** First chunk: strips boilerplate openers; null = nothing left. */
    fun stripOpener(s: String): String? {
        var t = s
        repeat(3) { t = OPENER.replaceFirst(t, "") }
        return t.trim().takeIf { r -> r.any { it.isLetterOrDigit() } }
    }

    fun isCloser(sentence: String): Boolean = CLOSER.containsMatchIn(sentence)

    /** Whole-text boilerplate filter (the voice bubble): opener off the front, closing sentences off the end. */
    fun polish(text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return body
        val sentences = splitSentences(body)
        val out = ArrayList<String>()
        for ((i, s) in sentences.withIndex()) {
            val t = if (i == 0) stripOpener(s) ?: continue else s
            if (out.isNotEmpty() && isCloser(t)) break
            if (out.isEmpty() && isCloser(t) && i > 0) break
            out += t
        }
        val r = out.joinToString("").trim()
        return r.ifEmpty { body }
    }

    private val SENTENCE = Regex("[^。！？!?；;\\n]+[。！？!?；;…]*\\n*|[。！？!?；;…\\n]+")
    internal fun splitSentences(s: String): List<String> = SENTENCE.findAll(s).map { it.value }.filter { it.isNotBlank() }.toList()
}

/** Deterministic text → speech cleanup (markdown, code, links, emoji, tables), applied per sentence. */
object VoiceSpeakable {
    const val CODE_SAID = "代码已放在屏幕上。"
    const val MORE_SAID = "详细的我放在屏幕上了。"
    const val MAX_SENTENCES = 3

    private val URL = Regex("(https?://|www\\.)[^\\s，。、；！？）)\\]」>\\u4e00-\\u9fff]+", RegexOption.IGNORE_CASE)
    private val URL_START = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

    fun stripUrls(s: String): String = URL.replace(s, "")

    /**
     * How much of an unfinished line can go to the sentence splitter now: a link that may still be
     * growing (or a tail like "htt") waits, so it is removed whole instead of being cut at its ':'.
     */
    fun feedable(t: String): Int {
        val m = URL_START.findAll(t).lastOrNull()
        if (m != null && URL.find(t, m.range.first)?.range?.last == t.lastIndex) return m.range.first
        for (k in minOf(8, t.length) downTo 1) {
            val tail = t.takeLast(k).lowercase()
            if ("https://".startsWith(tail) || "www.".startsWith(tail)) return t.length - k
        }
        return t.length
    }
    private val MD_LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val SPACES = Regex("\\s+")

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F900..0x1F9FF || cp == 0xFE0F || cp == 0x200D ||
            cp in 0x1F1E6..0x1F1FF || cp in 0x2B00..0x2BFF || cp in 0xE0020..0xE007F

    fun stripEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** One sentence (no code inside) → what the synthesizer should say ("" = nothing). */
    fun sentence(s: String): String {
        var t = MD_LINK.replace(s) { it.groupValues[1] }
        t = URL.replace(t, "")
        t = if (TABLE_RULE.matches(t)) "" else t
        // Table rows read as a list: cells joined with a pause.
        if (t.count { it == '|' } >= 2) t = t.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("，")
        t = SentenceSplitter.clean(t)
        t = stripEmoji(t)
        t = com.iris.localai.util.SpeechText.speakable(t)
        t = SPACES.replace(t, " ").trim()
        // Leftover punctuation only (a stripped URL / emoji) → nothing.
        return if (t.any { it.isLetterOrDigit() }) t else ""
    }

    /** Whole reply → the spoken version (≤ [max] sentences; the bubble keeps everything). */
    fun clean(full: String, max: Int = MAX_SENTENCES): String {
        val v = VoiceSpeech(max)
        return (v.push(full) + v.flush()).joinToString("")
    }
}

/**
 * Streaming version of [VoiceSpeakable.clean] + [VoiceStyle]: answer deltas in, speakable
 * sentences out (each ends with punctuation, so the voice side's own splitter keeps them whole).
 * A fenced code block becomes 「代码已放在屏幕上。」 once; at most [max] sentences are spoken, then
 * 「详细的我放在屏幕上了。」. [cue] text (the short 「我查一下」 before a tool) passes straight through.
 */
class VoiceSpeech(private val max: Int = VoiceSpeakable.MAX_SENTENCES) {
    private val splitter = SentenceSplitter()
    private val line = StringBuilder()
    private var midLine = false
    private var inCode = false
    private var codeSaid = false
    private var spoken = 0
    private var chunks = 0
    private var closing = false
    private var truncated = false

    fun cue(text: String): List<String> = listOf(text)

    fun push(delta: String): List<String> {
        val out = ArrayList<String>()
        for (ch in delta) {
            if (ch == '\n') {
                val l = line.toString()
                line.setLength(0)
                val wasMid = midLine
                midLine = false
                if (!wasMid && l.trimStart().startsWith("```")) {
                    inCode = !inCode
                    if (inCode) { out += emitAll(splitter.flush()); if (!codeSaid) { codeSaid = true; out += emit(VoiceSpeakable.CODE_SAID, force = true) } }
                    continue
                }
                if (inCode) continue
                out += emitAll(splitter.push(VoiceSpeakable.stripUrls(l) + "\n"), lineEnd = true)
            } else {
                line.append(ch)
            }
        }
        // Feed the unfinished line now (low latency) unless it could still become a ``` fence.
        if (!inCode && line.isNotEmpty()) {
            val t = line.toString()
            if (midLine || !t.trimStart().let { it.isEmpty() || it.startsWith("`") }) {
                val n = VoiceSpeakable.feedable(t)
                if (n > 0) {
                    midLine = true
                    line.delete(0, n)
                    out += emitAll(splitter.push(VoiceSpeakable.stripUrls(t.substring(0, n))))
                }
            }
        }
        return out
    }

    fun flush(): List<String> {
        val out = ArrayList<String>()
        if (!inCode && line.isNotEmpty()) out += emitAll(splitter.push(VoiceSpeakable.stripUrls(line.toString())))
        line.setLength(0)
        out += emitAll(splitter.flush())
        if (truncated && !closing) out += VoiceSpeakable.MORE_SAID
        return out
    }

    /** [lineEnd]: the last chunk ends a line (a list item / heading becomes its own sentence). */
    private fun emitAll(list: List<String>, lineEnd: Boolean = false): List<String> =
        list.flatMapIndexed { i, r -> emit(r, lineEnd = lineEnd && i == list.lastIndex) }

    private fun emit(raw: String, force: Boolean = false, lineEnd: Boolean = false): List<String> {
        if (closing) return emptyList()
        var t = if (force) raw else VoiceSpeakable.sentence(raw)
        if (t.isEmpty()) return emptyList()
        if (lineEnd && t.last() !in LINE_PUNCT) t += "。"
        if (chunks == 0 && !force) t = VoiceStyle.stripOpener(t) ?: return emptyList()
        if (chunks > 0 && VoiceStyle.isCloser(t)) { closing = true; return emptyList() }
        if (spoken >= max) { truncated = true; return emptyList() }
        chunks++
        if (ends(t)) spoken++
        return listOf(t)
    }

    private fun ends(t: String): Boolean = t.isNotEmpty() && t.last() in HARD
    private companion object {
        val HARD = setOf('。', '！', '？', '!', '?', '；', ';', '…', '.')
        const val LINE_PUNCT = "。！？!?；;….，,、：:"
    }
}

/** 2.1.7: whether a spoken request goes through the 智能体 loop (tools) instead of a plain reply. */
object VoiceRouting {
    private val TASK = Regex(
        "^(请|麻烦)?(你)?(帮我|给我|替我)|^(查|搜|搜索|找|算|计算|翻译|提醒|定个|设(置|个|一个)|打开|打电话|打给|发给|发送|记一下|记住|整理|下载|导航)|" +
            "(帮我|给我|替我)(查|找|搜|算|设|定|发|打|记|看|写|翻译|整理|打开)|提醒我|闹钟|日程|几点了|今天几号|天气",
    )

    fun looksLikeTask(text: String): Boolean {
        val t = text.trim()
        return com.iris.localai.agent.SearchText.needsWeb(t) || TASK.containsMatchIn(t)
    }

    /** 智能体 on → always the agent; off → only a request that reads like a task. */
    fun useAgent(text: String, agentMode: Boolean): Boolean = agentMode || looksLikeTask(text)
}

/** 2.1.7: short spoken confirmations before a tool runs (no model call, no tokens). */
object VoiceCues {
    val POOL = listOf("我查一下。", "好，我来弄。", "稍等，我看看。")
    const val CONFIRM_ON_SCREEN = "这个要你在屏幕上确认，我先不动。"

    fun before(toolName: String, n: Int): String = when {
        toolName.contains("search", true) || toolName.contains("fetch", true) || toolName.contains("weather", true) -> POOL[0]
        else -> POOL[Math.floorMod(n, POOL.size)]
    }
}

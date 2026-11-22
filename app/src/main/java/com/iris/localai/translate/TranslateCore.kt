package com.iris.localai.translate

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.PromptMessage

/**
 * 本地翻译 — the pure parts (unit-tested, V230TranslateTest): language detection, the
 * dedicated translation prompt, cleaning model output (thinking stripped), engine selection and
 * the paragraph split the offline engine streams by.
 */
enum class TransLang(val code: String, val label: String, val english: String) {
    ZH("zh", I18n.s(R.string.translate_a7bac2), "Simplified Chinese"),
    EN("en", I18n.s(R.string.translate_f9fb6a), "English");

    companion object {
        fun byCode(code: String): TransLang? = entries.firstOrNull { it.code == code }
    }
}

/** Settings values (AppSettings.translateTarget / translateEngine). */
object TranslateSettings {
    const val TARGET_AUTO = "auto"
    const val ENGINE_AUTO = "auto"
    const val ENGINE_MODEL = "model"
    const val ENGINE_OFFLINE = "offline"
    val TARGETS = listOf(TARGET_AUTO to I18n.s(R.string.translate_a73116), "zh" to I18n.s(R.string.translate_a7bac2), "en" to I18n.s(R.string.translate_f9fb6a))
    val ENGINES = listOf(ENGINE_AUTO to I18n.s(R.string.settings_3aed2c), ENGINE_MODEL to I18n.s(R.string.translate_e18ae8), ENGINE_OFFLINE to I18n.s(R.string.translate_50d4a8))
}

object LangDetect {
    private fun isHan(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

    /**
     * 中 or 英 only. A Han character carries about a word, so a text counts as Chinese when its Han
     * characters are at least a quarter of its Latin letters (「用 Kotlin 写一个 Android app」 is
     * Chinese; an English sentence quoting one 中文 word is English). No letters at all = English.
     */
    fun detect(text: String): TransLang {
        var han = 0
        var latin = 0
        for (c in text) {
            if (isHan(c)) han++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++
        }
        return if (han > 0 && han * 4 >= latin) TransLang.ZH else TransLang.EN
    }

    /** [setting] "auto" = 中↔英 (Chinese → English, everything else → Chinese); "zh"/"en" fixed. */
    fun target(text: String, setting: String, ui: String = "zh"): TransLang =
        TransLang.byCode(setting) ?: if (ui == "en") {
            // English UI → auto translates into English, English text into Chinese.
            if (detect(text) == TransLang.EN) TransLang.ZH else TransLang.EN
        } else if (detect(text) == TransLang.ZH) TransLang.EN else TransLang.ZH

    /** Source language for a translation into [target]: the detected one, or the other of 中/英 when they match. */
    fun source(text: String, target: TransLang): TransLang =
        detect(text).let { if (it != target) it else if (target == TransLang.ZH) TransLang.EN else TransLang.ZH }
}

/** The short dedicated prompt for the loaded model: no tools, no memory, no conversation context. */
object TranslatePrompt {
    fun system(target: TransLang): String =
        com.iris.localai.prompts.Prompts.get(com.iris.localai.prompts.PromptKey.TRANSLATE_SYSTEM, mapOf("lang" to target.english))

    /** [modelName] 为腾讯混元翻译（[HyMt]）时用它的官方模板：没有 system prompt，指令和原文放在一条用户消息里。 */
    fun messages(text: String, target: TransLang, modelName: String? = null): List<PromptMessage> =
        if (HyMt.matches(modelName)) listOf(PromptMessage(PromptMessage.USER, HyMt.prompt(text, target)))
        else listOf(
            PromptMessage(PromptMessage.SYSTEM, system(target)),
            PromptMessage(PromptMessage.USER, text),
        )

    /** Output budget: about 3× the source length (tokens), at least 64, at most 2048. */
    fun maxTokens(text: String): Int = (text.length * 3 + 64).coerceIn(64, 2048)
}

/**
 * r5：腾讯混元翻译（Hy-MT2 / HY-MT1.5 / Hunyuan-MT）。官方卡片：没有默认 system prompt；
 * 中外互译模板「将以下文本翻译为{目标语言}，注意只需要输出翻译后的结果，不要额外解释：\n\n{原文}」；
 * 推荐 temperature 0.7、top_p 0.6、top_k 20、repetition_penalty 1.05。
 * 之前给它套通用的英文 system prompt，1.8B 会复述、加废话、译完再译一遍。
 */
object HyMt {
    private val NAME = Regex("(?i)(hy[-_ ]?mt|hunyuan[-_ ]?mt)")
    fun matches(modelName: String?): Boolean = !modelName.isNullOrBlank() && NAME.containsMatchIn(modelName)

    /** 官方要求用语言全称，中文指令里用中文名。 */
    fun langName(t: TransLang): String = when (t) { TransLang.ZH -> "中文"; TransLang.EN -> "英语" }

    /** 只有中↔英，总是中外互译模板。 */
    fun prompt(text: String, target: TransLang): String =
        "将以下文本翻译为${langName(target)}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"

    data class Sampling(val temperature: Float, val topP: Float, val topK: Int, val minP: Float, val repeatPenalty: Float)
    val OFFICIAL = Sampling(0.7f, 0.6f, 20, 0f, 1.05f)
    /** 其他模型翻译用的（原来的值）。 */
    val GENERIC = Sampling(0.2f, 0.9f, 40, 0f, 1.05f)
    fun sampling(modelName: String?): Sampling = if (matches(modelName)) OFFICIAL else GENERIC
}

/**
 * r5：模型翻译的「收尾」——遇到停止标记（漏出来的对话结束符、模型又开始复述指令）就截断并停止生成；
 * 整句复读、短语死循环只留第一次。纯逻辑，单测 V300B3R5Test。
 */
object TranslateStop {
    /** 模板结束符没被引擎当成 EOS 时会漏进文本：看到就停。 */
    private val MARKERS = listOf(
        "<|hy_", "<｜hy_", "<|im_end|>", "<|endoftext|>", "<|eot_id|>", "<|end|>", "</s>", "<｜end▁of▁sentence｜>",
        "<|im_start|>", "<|start_header_id|>",
        // 模型译完又复述指令、开始下一轮
        "将以下文本翻译为", "把下面的文本翻译成", "Translate the following",
    )

    data class Result(val text: String, val stop: Boolean)

    fun cutMarkers(raw: String): Result {
        var at = -1
        for (m in MARKERS) {
            val i = raw.indexOf(m, ignoreCase = m.first().isLetter())
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        return if (at < 0) Result(raw, false) else Result(raw.substring(0, at), true)
    }

    private val SENT = Regex("(?<=[。！？!?；;\\n])|(?<=[.] )")
    private fun norm(x: String) = x.trim().lowercase().replace(Regex("\\s+"), " ").trim('.', '。', ',', '，', ' ')
    private fun meaningful(x: String) = x.count { it.isLetterOrDigit() } >= 4

    /** 原文里本来就有重复的句子 / 行（比如列表里两行一样），这时不按「复读」截。 */
    fun sourceRepeats(source: String): Boolean {
        val seen = HashSet<String>()
        return source.split(SENT).map(::norm).filter { it.isNotEmpty() }.any { !seen.add(it) }
    }

    /** 整句复读：同一句第二次出现处截断。 */
    fun cutSentenceRepeat(out: String, source: String): Result {
        if (sourceRepeats(source)) return Result(out, false)
        val seen = HashSet<String>()
        var pos = 0
        for (part in out.split(SENT)) {
            val n = norm(part)
            if (meaningful(n) && !seen.add(n)) return Result(out.substring(0, pos).trimEnd(), true)
            pos += part.length
        }
        return Result(out, false)
    }

    /** 短语死循环：同一段（2–30 字，含字母或汉字）紧挨着重复 ≥ 4 次 → 只留第一次，后面全截掉。 */
    fun cutLoop(out: String, from: Int = 0): Result {
        val n = out.length
        var i = from.coerceIn(0, n)
        while (i < n) {
            for (len in 2..30) {
                if (i + len * 4 > n) break
                val unit = out.substring(i, i + len)
                if (unit.none { it.isLetter() }) continue
                var k = 1
                while (i + len * (k + 1) <= n && out.regionMatches(i + len * k, unit, 0, len)) k++
                if (k >= 4) return Result(out.substring(0, i + len).trimEnd(), true)
            }
            i++
        }
        return Result(out, false)
    }

    /** 全套：标记 → 清理（思考、标签）→ 死循环 → 整句复读。[stop] = 该停止生成了。 */
    fun finish(raw: String, source: String, tailOnly: Boolean = false): Result {
        val m = cutMarkers(raw)
        val cleaned = TranslateOutput.clean(m.text, source)
        // 边生成边查时只看最后一段（更早的死循环在它出现时就截过了）。
        val l = cutLoop(cleaned, if (tailOnly) cleaned.length - 400 else 0)
        val r = cutSentenceRepeat(l.text, source)
        return Result(r.text.trim(), m.stop || l.stop || r.stop)
    }
}

/** Cleans what the model streamed: thinking removed (closed, unclosed or prefilled), labels and wrapping quotes dropped. */
object TranslateOutput {
    private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val CHANNEL = Regex("<\\|channel\\|>analysis[\\s\\S]*?(<\\|end\\|>|<\\|channel\\|>final<\\|message\\|>)")
    private val LABEL = Regex("^(translation|translated text|译文|翻译|中文|英文|english|chinese)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<\\|[^|>]{1,32}\\|>")

    fun clean(raw: String, source: String = ""): String {
        var s = raw.replace(THINK_BLOCK, "").replace(CHANNEL, "")
        // A reply whose thinking was prefilled by the template starts mid-thought and only has the closing tag.
        val close = s.lastIndexOf("</think>", ignoreCase = true)
        if (close >= 0) s = s.substring(close + "</think>".length)
        // Still thinking (stream not past </think> yet): nothing to show.
        val open = s.indexOf("<think>", ignoreCase = true)
        if (open >= 0) s = s.substring(0, open)
        s = s.replace(TAGS, "").trim()
        s = s.replace(LABEL, "")
        if (s.length >= 2 && !source.trim().startsWith("\"") && !source.trim().startsWith("“")) {
            if ((s.first() == '"' && s.last() == '"') || (s.first() == '“' && s.last() == '”')) s = s.substring(1, s.length - 1)
        }
        return s.trim()
    }
}

enum class TranslateEngineKind { MODEL, OFFLINE }

sealed interface EngineChoice {
    data class Use(val kind: TranslateEngineKind) : EngineChoice
    data class Unavailable(val message: String) : EngineChoice
}

object EngineSelect {
    val NEED_MODEL: String get() = I18n.s(R.string.settings_99db9d)
    val NEED_EITHER: String get() = I18n.s(R.string.translate_7b5a14)
    fun needPack(source: TransLang, target: TransLang) =
        I18n.s(R.string.translate_85c9c7, source.label, target.label)
    val OFFLINE_UNSUPPORTED: String get() = I18n.s(R.string.translate_30da87)

    /**
     * [pref]: 自动 = the loaded model, else the offline pack; 当前模型 / 离线 = only that one.
     * [offlinePack] = the pack for this direction is installed; [offlineEngine] = the native engine loads.
     */
    fun choose(
        pref: String, modelReady: Boolean, offlinePack: Boolean, offlineEngine: Boolean,
        source: TransLang, target: TransLang,
    ): EngineChoice {
        val offline = offlinePack && offlineEngine
        fun offlineMissing() = if (!offlineEngine) OFFLINE_UNSUPPORTED else needPack(source, target)
        return when (pref) {
            TranslateSettings.ENGINE_MODEL ->
                if (modelReady) EngineChoice.Use(TranslateEngineKind.MODEL) else EngineChoice.Unavailable(NEED_MODEL)
            TranslateSettings.ENGINE_OFFLINE ->
                if (offline) EngineChoice.Use(TranslateEngineKind.OFFLINE) else EngineChoice.Unavailable(offlineMissing())
            else -> when {
                modelReady -> EngineChoice.Use(TranslateEngineKind.MODEL)
                offline -> EngineChoice.Use(TranslateEngineKind.OFFLINE)
                !offlineEngine -> EngineChoice.Unavailable(NEED_MODEL)
                else -> EngineChoice.Unavailable(NEED_EITHER)
            }
        }
    }
}

/** Paragraphs for the offline engine: translated one by one (streamed), blank lines kept as-is. */
object Paragraphs {
    fun split(text: String): List<String> = text.split('\n')

    /** Marian works on plain text: a paragraph that is only whitespace / Markdown rule / code fence is copied. */
    fun passThrough(p: String): Boolean = p.isBlank() || p.trim().let { it.startsWith("```") || it.all { c -> c in "-*_=#>| " } }
}

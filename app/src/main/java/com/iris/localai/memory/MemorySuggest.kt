package com.iris.localai.memory

/**
 * 2.2.9: rule-based 「记住？」 suggestions from what the user said (no model call). Pure.
 * At most one suggestion per turn; questions, hypotheticals and quotes of other people never match.
 */
object MemorySuggest {
    data class Suggestion(val text: String, val kind: MemoryItem.Kind)

    private const val MIN = 4
    private const val MAX = 120

    private val SENTENCE = Regex("[^。！!？?\\n；;]+[。！!？?；;]?")
    private val MY_X_IS = Regex("^我的(生日|名字|邮箱|手机|工作|公司)是.+")
    private val RULE = Regex("^(?:请|你)?(?:(?:以后|之后|今后)(?:都|请|你)?|(?:别|不要)再|每次都).{3,}")
    private val LIKE = Regex("^(?:其实|平时|一般)?我(?:比较|更|很|最|一直|也)?(喜欢|不喜欢|讨厌|习惯|偏好|更想).{1,}")
    private val ABOUT = Regex("^我(?:现在|目前)?(叫|是|在|住在|做|从事|用|有).{1,}")
    /** 我… openings that are about the moment, not lasting facts. */
    private val TRANSIENT = Regex("^我(?:是说|是不是|是想|在想|在看|在找|在问|有点|有些|有个问题|有一个问题|有问题|有空|在吗)")
    private val HYPOTHETICAL = Regex("如果|假如|比如|举例|例如|假设|要是")
    private val QUESTION_WORD = Regex("什么|怎么|为什么|哪|谁|多少|几点|是否|能不能|可不可以|可以吗")
    private val REQUEST = Regex("帮我|给我|请你|替我")
    private val QUOTE = Regex("(?:他|她|它|他们|她们|别人|有人|朋友|同事)(?:说|觉得|认为|讲)")

    fun detect(userText: String, items: List<MemoryItem> = emptyList(), ignored: Set<String> = emptySet()): List<Suggestion> {
        for (m in SENTENCE.findAll(userText)) {
            val s = check(m.value) ?: continue
            if (MemoryMerge.isDup(items, s.text)) continue
            if (MemoryMerge.key(s.text) in ignored) continue
            return listOf(s)
        }
        return emptyList()
    }

    /** Kind of a fact by the same rules (null = no rule matches). */
    fun kindOf(text: String): MemoryItem.Kind? = check(text)?.kind

    private fun check(raw: String): Suggestion? {
        val trimmed = raw.replace(Regex("\\s+"), " ").trim()
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) return null
        val s = trimmed.trimEnd('。', '.', '!', '！', ';', '；', '~', '～', ' ')
        if (s.endsWith("吗") || s.endsWith("呢") || s.endsWith("吧")) return null
        val compact = s.replace(" ", "")
        if (compact.length < MIN || s.length > MAX) return null
        if (HYPOTHETICAL.containsMatchIn(s) || QUOTE.containsMatchIn(s) || QUESTION_WORD.containsMatchIn(s)) return null
        val kind = when {
            MY_X_IS.containsMatchIn(compact) -> MemoryItem.Kind.ABOUT
            RULE.containsMatchIn(compact) -> MemoryItem.Kind.PREF
            LIKE.containsMatchIn(compact) -> MemoryItem.Kind.PREF
            REQUEST.containsMatchIn(s) || TRANSIENT.containsMatchIn(compact) -> return null
            ABOUT.containsMatchIn(compact) -> MemoryItem.Kind.ABOUT
            else -> return null
        }
        return Suggestion(MemoryCommand.entryFrom(s), kind)
    }
}

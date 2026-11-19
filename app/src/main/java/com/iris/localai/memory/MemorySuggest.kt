package com.iris.localai.memory

/**
 * rule-based 「记住？」 suggestions from what the user said (no model call). Pure.
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
    private val SPACES = Regex("\\s+")
    private val QUOTE = Regex("(?:他|她|它|他们|她们|别人|有人|朋友|同事)(?:说|觉得|认为|讲)")

    // English: the same rules for sentences written in English (lower-cased, spaces kept).
    private val EN_MY_X_IS = Regex("^my (birthday|name|email|e-mail|phone( number)?|job|company|pronouns|timezone|time zone) is .+")
    private val EN_RULE = Regex("^(?:please )?(?:from now on|in (?:the )?future|always|never(?! mind)|don'?t ever|do not ever) .{3,}")
    private val EN_LIKE = Regex("^(?:actually |usually |generally )?i (?:really |much |also |always |usually )?(like|love|prefer|hate|dislike|don'?t like|do not like|enjoy)\\b.+")
    private val EN_ABOUT = Regex("^i(?:'m| am) (?:a|an|from|based in|allergic to|vegetarian|vegan)\\b.+|^i (?:live|work|use|have|own|study)\\b.+|^my name'?s .+|^call me .+")
    private val EN_TRANSIENT = Regex("^i (?:have|had) (?:a|one|another|some) (?:quick |small )?(?:question|problem|issue|idea)|^i (?:live|work) (?:on|with) (?:this|that|it)\\b")
    private val EN_HYPOTHETICAL = Regex("\\b(if|suppose|supposing|for example|for instance|e\\.g\\.|imagine|let'?s say|what if)\\b")
    private val EN_QUESTION = Regex("^(what|how|why|where|who|which|when|can|could|would|should|is|are|do|does|did)\\b")
    private val EN_REQUEST = Regex("\\b(help me|can you|could you|would you|please (write|make|draw|give|tell|find|show|translate|explain))\\b")
    private val EN_QUOTE = Regex("\\b(he|she|they|someone|somebody|people|my (friend|colleague|coworker)|a friend) (said|says|thinks|think|told|believes)\\b")
    private val EN_SENTENCE_END = Regex("(?<=[a-z0-9)])\\.\\s+")
    private val LATIN = Regex("^[A-Za-z]")

    fun detect(userText: String, items: List<MemoryItem> = emptyList(), ignored: Set<String> = emptySet()): List<Suggestion> {
        for (m in SENTENCE.findAll(userText.replace(EN_SENTENCE_END, ".\n"))) {
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
        val trimmed = raw.replace(SPACES, " ").trim()
        if (LATIN.containsMatchIn(trimmed)) return checkEn(trimmed)
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) return null
        val s = trimmed.trimEnd('。', '.', '!', '！', ';', '；', '~', '～', ' ')
        if (s.endsWith("吗") || s.endsWith("呢") || s.endsWith("吧")) return null
        val compact = s.replace(" ", "")
        if (compact.length < MIN || s.length > MAX) return null
        if (HYPOTHETICAL.containsMatchIn(s) || QUOTE.containsMatchIn(s) || QUESTION_WORD.containsMatchIn(s)) return null
        val kind = when {
            MY_X_IS.containsMatchIn(compact) -> MemoryItem.Kind.ABOUT
            RULE.containsMatchIn(compact) -> MemoryItem.Kind.PREF
            // a request in the same sentence (「我喜欢猫，帮我画一只」) is a task, not a preference to keep.
            REQUEST.containsMatchIn(s) || TRANSIENT.containsMatchIn(compact) -> return null
            LIKE.containsMatchIn(compact) -> MemoryItem.Kind.PREF
            ABOUT.containsMatchIn(compact) -> MemoryItem.Kind.ABOUT
            else -> return null
        }
        return Suggestion(MemoryCommand.entryFrom(s), kind)
    }

    private fun checkEn(trimmed: String): Suggestion? {
        if (trimmed.endsWith("?")) return null
        val s = trimmed.trimEnd('.', '!', ';', '~', ' ')
        if (s.replace(" ", "").length < MIN || s.length > MAX) return null
        val l = s.lowercase().replace('’', '\'')
        if (EN_HYPOTHETICAL.containsMatchIn(l) || EN_QUOTE.containsMatchIn(l) || EN_QUESTION.containsMatchIn(l)) return null
        val kind = when {
            EN_MY_X_IS.containsMatchIn(l) -> MemoryItem.Kind.ABOUT
            EN_RULE.containsMatchIn(l) -> MemoryItem.Kind.PREF
            EN_REQUEST.containsMatchIn(l) || EN_TRANSIENT.containsMatchIn(l) -> return null
            EN_LIKE.containsMatchIn(l) -> MemoryItem.Kind.PREF
            EN_ABOUT.containsMatchIn(l) -> MemoryItem.Kind.ABOUT
            else -> return null
        }
        return Suggestion(MemoryCommand.entryFrom(s), kind)
    }
}

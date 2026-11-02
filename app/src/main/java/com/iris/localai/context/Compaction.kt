package com.iris.localai.context

import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.StepStatus
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.Serializable

/**
 * 1.10.0 deterministic context compaction (pi-vcc style): no model call, pure Kotlin, and the
 * same input always gives exactly the same summary. Older turns are folded into four sections
 * (目标 / 未完成事项 / 引用 / 关键信号) plus one line per tool call; the newest turns stay verbatim.
 * Repeated compactions merge into the previous state with de-duplication.
 */

/** One history message as compaction sees it (thinking already stripped). */
data class CompactMsg(
    val id: String,
    /** "user" or "assistant" (PromptMessage roles). */
    val role: String,
    val text: String,
    val steps: List<AgentStep> = emptyList(),
    /**
     * 2.2.17 D: tokens the planner adds to this message on top of [text] (its 【相关记忆】 /
     * knowledge block). Without it the estimate stayed below the trigger while the planner already
     * had to drop the oldest messages, so auto-compaction never ran near the limit.
     */
    val extraTokens: Int = 0,
) {
    val isUser: Boolean get() = role == USER

    /** What the model is sent for this message; in 智能体 mode earlier tool steps ride along rolled up. */
    fun promptText(withTools: Boolean): String =
        if (!withTools || steps.isEmpty() || isUser) text
        else ToolRollup.block(steps) + if (text.isBlank()) "" else "\n" + text

    fun tokens(withTools: Boolean): Int = TokenEstimator.message(promptText(withTools)) + extraTokens

    companion object { const val USER = "user"; const val ASSISTANT = "assistant" }
}

/** Stored on the conversation ([com.iris.localai.data.Conversation.compaction]). */
@Serializable
data class CompactState(
    /** Last message id folded into this summary; later messages are sent verbatim. */
    val upToId: String,
    val goals: List<String> = emptyList(),
    val todos: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val signals: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    /** Messages folded so far (all compactions together). */
    val messages: Int = 0,
    /** How many times this conversation was compacted. */
    val times: Int = 0,
)

/** One line per tool call: `read_file(path=a.txt) ✓ 3 行 · 20 字节`. */
object ToolRollup {
    fun line(s: AgentStep): String {
        val mark = when (s.status) {
            StepStatus.OK -> "✓"
            StepStatus.DENIED -> "⊘"
            StepStatus.ERROR -> "✗"
            StepStatus.RUNNING, StepStatus.ASKING -> "…"
        }
        val args = s.args.replace('\n', ' ').let { if (it.length > 60) it.take(59) + "…" else it }
        val res = s.result.replace('\n', ' ').trim().let { if (it.length > RESULT_CHARS) it.take(RESULT_CHARS - 1) + "…" else it }
        return "${s.tool}($args) $mark" + if (res.isEmpty()) "" else " $res"
    }

    /** Prefix of an assistant message in 智能体 mode: the tool steps of that reply, one line each. */
    fun block(steps: List<AgentStep>): String =
        steps.joinToString("\n", prefix = "$HEADER\n") { "- " + line(it) }

    const val HEADER = "〔本轮已执行的工具〕"
    /** 1.13.0: was 50 — too short to keep what a search or read found. */
    const val RESULT_CHARS = 240

    private val ECHO = Regex("〔本轮已执行的工具〕[^\\n]*(\\n[ \\t]*[-•*][^\\n]*)*\\n?")

    /**
     * 1.13.0: small models copy this history block into a new answer as if they had used a tool
     * (no tool ran — the "result" is invented). True when [text] contains such an echo.
     */
    fun hasEcho(text: String): Boolean = HEADER in text

    /** [text] without echoed tool-log blocks. */
    fun stripEcho(text: String): String = if (HEADER !in text) text else ECHO.replace(text, "").trim()
}

/** Thresholds, scaled from the 4096-token design point to the model's real window. */
data class CompactPolicy(val contextTokens: Int, val hardBudget: Int = Int.MAX_VALUE) {
    private val ctx = contextTokens.coerceAtLeast(512)
    /** Room kept free for the reply (≈1000 of 4096). */
    val reserve: Int get() = (ctx * 1000L / 4096).toInt().coerceAtLeast(128)
    /** Newest turns kept verbatim (≈1200 of 4096). */
    val keepRecent: Int get() = (ctx * 1200L / 4096).toInt().coerceAtLeast(160)
    /** Ceiling on the rendered summary (≈600 of 4096). */
    val summaryCap: Int get() = (ctx * 600L / 4096).toInt().coerceAtLeast(120)
    /**
     * Compact when the prompt estimate passes this. 2.2.17 D: at most 90% of the hard budget (the
     * planner's prompt budget): the estimate is approximate, and at exactly the budget the planner
     * had already started trimming the oldest rounds before compaction got a chance.
     */
    val trigger: Int get() = minOf(ctx - reserve, hardBudget.coerceAtLeast(ctx / 4) * 9 / 10)
}

object Compactor {
    const val HEADER = "【已压缩的早期对话】"
    private const val MAX_GOALS = 6
    private const val MAX_TODOS = 8
    private const val MAX_REFS = 16
    private const val MAX_SIGNALS = 10
    private const val MAX_TOOLS = 12
    private const val ITEM_CHARS = 140

    // ------------------------------------------------------------------ decision

    /** The compaction state that applies to [active] (null when its anchor is gone: edited, a break moved…). */
    fun applicable(state: CompactState?, active: List<CompactMsg>): CompactState? =
        state?.takeIf { s -> active.any { it.id == s.upToId } }

    /** Messages still sent verbatim under [state]. */
    fun remaining(state: CompactState?, active: List<CompactMsg>): List<CompactMsg> {
        val s = applicable(state, active) ?: return active
        val i = active.indexOfFirst { it.id == s.upToId }
        return active.drop(i + 1)
    }

    /** Estimated prompt size: [fixedTokens] (system, memory) + summary + verbatim messages. */
    fun estimate(fixedTokens: Int, state: CompactState?, active: List<CompactMsg>, withTools: Boolean): Int {
        val s = applicable(state, active)
        val summary = s?.let { TokenEstimator.message(render(it)) } ?: 0
        return fixedTokens + summary + remaining(s, active).sumOf { it.tokens(withTools) }
    }

    /**
     * Folds older turns when the estimate passes [CompactPolicy.trigger] (or always, [manual]).
     * Returns the new state, or null when nothing should change. Cuts on round boundaries (a user
     * message and the replies after it) and always keeps the newest round verbatim.
     */
    fun maybeCompact(
        active: List<CompactMsg>, state: CompactState?, fixedTokens: Int, policy: CompactPolicy,
        withTools: Boolean, manual: Boolean = false,
    ): CompactState? {
        val prev = applicable(state, active)
        val rest = remaining(prev, active)
        if (!manual && estimate(fixedTokens, prev, active, withTools) <= policy.trigger) return null
        val rounds = rounds(rest)
        if (rounds.size < 2) return null
        // Keep the newest rounds that fit keepRecent (manual: just the last round); at least one.
        var keep = 1
        if (!manual) {
            var used = rounds.last().sumOf { it.tokens(withTools) }
            while (keep < rounds.size - 1) {
                val t = rounds[rounds.size - 1 - keep].sumOf { it.tokens(withTools) }
                if (used + t > policy.keepRecent) break
                used += t; keep++
            }
        }
        val fold = rounds.dropLast(keep).flatten()
        if (fold.isEmpty()) return null
        val merged = merge(prev, extract(fold, fold.last().id))
        return fit(merged, policy.summaryCap)
    }

    fun rounds(messages: List<CompactMsg>): List<List<CompactMsg>> {
        val out = ArrayList<MutableList<CompactMsg>>()
        for (m in messages) if (m.isUser || out.isEmpty()) out += mutableListOf(m) else out.last() += m
        return out
    }

    // ------------------------------------------------------------------ extraction

    private val URL = Regex("https?://[^\\s<>\"'`）)\\]】，。]+")
    private val PATH = Regex("(?<![\\w/.-])(?:[\\w\\u3400-\\u9fff.-]+/)*[\\w\\u3400-\\u9fff-][\\w\\u3400-\\u9fff.-]*\\.[A-Za-z][A-Za-z0-9]{0,6}(?![A-Za-z0-9_/])")
    private val DIR_PATH = Regex("(?<![\\w/.-])(?:/|~/|\\./)?(?:[\\w.-]+/){1,}[\\w.-]+")
    private val VERSION = Regex("(?<![\\w.])v?\\d+\\.\\d+(?:\\.\\d+){0,3}(?:-[A-Za-z0-9]+)?(?![\\w.])")
    private val NUMBER = Regex("(?<![\\w.])\\d[\\d,]*(?:\\.\\d+)?\\s?(?:%|‰|元|块|美元|kb|mb|gb|KB|MB|GB|ms|秒|分钟|小时|天|周|个月|年|个|次|条|行|字|页|token|tokens|°C|℃|km|kg|米|公里|公斤)?")
    private val TODO = Regex("(?i)(todo|fixme|待办|未完成|还没|尚未|没做完|下一步|接下来|稍后|回头|以后再|之后再|待定|待确认|需要确认|要不要|别忘|记得|- \\[ \\])")
    private val SIGNAL = Regex(
        "(?i)(决定|确定了|定了|选择了|选用|采用|改用|就用|换成|不要|别再|必须|务必|一定要|只能|不能|不可以|禁止|限制|要求|偏好|喜欢|讨厌|" +
            "错误|报错|失败|异常|崩溃|bug|error|failed|failure|exception|crash|must|never|always|don't|do not|decided|prefer)",
    )
    private val QUESTION = Regex("[?？]\\s*$")
    private val FENCE = Regex("(?s)```.*?(```|$)")
    private val BULLET = Regex("^\\s*(?:[-*+•]|\\d+[.)、])\\s+")
    private val SENT_END = Regex("(?<=[。！？!?；;])")
    private val NUMBER_ONLY = Regex("^[\\d,.]+$")
    private val ABBREV = Regex("^[A-Za-z]\\.[A-Za-z]$")

    /** Sections of [fold] alone (first-seen order, de-duplicated). */
    fun extract(fold: List<CompactMsg>, upToId: String): CompactState {
        val goals = ArrayList<String>()
        val todos = ArrayList<String>()
        val refs = ArrayList<String>()
        val signals = ArrayList<String>()
        val tools = ArrayList<String>()
        fold.forEachIndexed { i, m ->
            val prose = FENCE.replace(m.text, " ")
            if (m.isUser) {
                firstSentence(prose)?.let { goals += it }
                // A question with no real answer after it stays open.
                val answered = fold.getOrNull(i + 1)?.let { !it.isUser && it.text.isNotBlank() } ?: false
                if (!answered) units(prose).filter { QUESTION.containsMatchIn(it) }.forEach { todos += it }
            }
            for (u in units(prose)) {
                if (TODO.containsMatchIn(u)) todos += u
                else if (SIGNAL.containsMatchIn(u)) signals += (if (m.isUser) "用户：" else "助手：") + u
            }
            refs += references(m.text)
            for (s in m.steps) {
                tools += ToolRollup.line(s)
                refs += references(s.args, pathsOnly = true)
                if (s.status == StepStatus.ERROR) signals += "工具失败：${s.tool} ${s.result}".trim().let(::clip)
            }
        }
        return CompactState(
            upToId = upToId,
            goals = dedupe(goals), todos = dedupe(todos), refs = dedupe(refs),
            signals = dedupe(signals), tools = dedupe(tools),
            messages = fold.size, times = 1,
        )
    }

    /** File paths, URLs, versions and meaningful numbers, in order of appearance. */
    fun references(text: String, pathsOnly: Boolean = false): List<String> {
        if (text.isBlank()) return emptyList()
        data class Hit(val at: Int, val v: String)
        val hits = ArrayList<Hit>()
        val taken = ArrayList<IntRange>()
        fun add(r: Regex, accept: (String) -> Boolean = { true }) {
            r.findAll(text).forEach { m ->
                val range = m.range
                if (taken.any { it.first <= range.last && range.first <= it.last }) return@forEach
                val v = m.value.trim().trimEnd('.', ',', ':', ';')
                if (v.isEmpty() || !accept(v)) return@forEach
                taken += range; hits += Hit(range.first, v)
            }
        }
        add(URL)
        if (!pathsOnly) add(VERSION)
        add(PATH) { !NUMBER_ONLY.matches(it) && !ABBREV.matches(it) }
        add(DIR_PATH) { it.contains('/') && !it.startsWith("//") && it.any { c -> c.code < 128 && (c.isLetterOrDigit() || c == '.') } }
        if (!pathsOnly) add(NUMBER) { v ->
            val digits = v.count { it.isDigit() }
            // Bare one-digit counts are noise; keep longer numbers, decimals and numbers with a unit.
            digits >= 3 || v.contains('.') || v.any { !it.isDigit() && it != ',' && !it.isWhitespace() }
        }
        return hits.sortedBy { it.at }.map { it.v.take(ITEM_CHARS) }
    }

    private fun units(text: String): List<String> =
        text.lines().flatMap { line -> SENT_END.split(line) }
            .map { BULLET.replace(it, "").trim() }
            .filter { it.length >= 2 }
            .map(::clip)

    private fun firstSentence(text: String): String? = units(text).firstOrNull()

    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length > ITEM_CHARS) one.take(ITEM_CHARS - 1) + "…" else one
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）]+"), "")

    /** Keeps the first occurrence of each item (normalised: case, spaces and punctuation ignored). */
    fun dedupe(items: List<String>): List<String> {
        val seen = HashSet<String>()
        return items.filter { it.isNotBlank() && seen.add(key(it)) }
    }

    /** Keeps the *latest* occurrence; used when merging so re-mentioned items move to the end. */
    private fun dedupeLatest(items: List<String>): List<String> {
        val seen = HashSet<String>()
        return items.asReversed().filter { it.isNotBlank() && seen.add(key(it)) }.asReversed()
    }

    // ------------------------------------------------------------------ merge + caps

    /** Folds [next] into [prev]: the very first goal stays, everything else keeps the newest items. */
    fun merge(prev: CompactState?, next: CompactState): CompactState {
        if (prev == null) return caps(next)
        val firstGoal = prev.goals.firstOrNull()
        val goalsRest = dedupeLatest(prev.goals.drop(1) + next.goals).filter { firstGoal == null || key(it) != key(firstGoal) }
        return caps(
            CompactState(
                upToId = next.upToId,
                goals = listOfNotNull(firstGoal) + goalsRest,
                todos = dedupeLatest(prev.todos + next.todos),
                refs = dedupeLatest(prev.refs + next.refs),
                signals = dedupeLatest(prev.signals + next.signals),
                tools = dedupeLatest(prev.tools + next.tools),
                messages = prev.messages + next.messages,
                times = prev.times + next.times,
            ),
        )
    }

    private fun caps(s: CompactState) = s.copy(
        goals = if (s.goals.size <= MAX_GOALS) s.goals else listOf(s.goals.first()) + s.goals.takeLast(MAX_GOALS - 1),
        todos = s.todos.takeLast(MAX_TODOS),
        refs = s.refs.takeLast(MAX_REFS),
        signals = s.signals.takeLast(MAX_SIGNALS),
        tools = s.tools.takeLast(MAX_TOOLS),
    )

    /** Drops the oldest items (tools → refs → todos → signals → later goals, in turn) until the summary fits [capTokens]. */
    fun fit(s0: CompactState, capTokens: Int): CompactState {
        var s = s0
        var turn = 0
        while (TokenEstimator.message(render(s)) > capTokens) {
            val before = s
            for (k in 0 until 5) {
                val next = when ((turn + k) % 5) {
                    0 -> if (s.tools.isNotEmpty()) s.copy(tools = s.tools.drop(1)) else null
                    1 -> if (s.refs.isNotEmpty()) s.copy(refs = s.refs.drop(1)) else null
                    2 -> if (s.todos.isNotEmpty()) s.copy(todos = s.todos.drop(1)) else null
                    3 -> if (s.signals.isNotEmpty()) s.copy(signals = s.signals.drop(1)) else null
                    else -> if (s.goals.size > 1) s.copy(goals = listOf(s.goals.first()) + s.goals.drop(2)) else null
                }
                if (next != null) { s = next; break }
            }
            turn++
            if (s == before) break // only the first goal is left
        }
        return s
    }

    // ------------------------------------------------------------------ render

    /** The summary as it goes into the system prompt (Chinese section titles, compact). */
    fun render(s: CompactState): String = buildString {
        append(HEADER).append("（较早的 ").append(s.messages).append(" 条消息已按规则压缩）")
        fun section(title: String, items: List<String>) {
            if (items.isEmpty()) return
            append('\n').append(title).append('：')
            items.forEach { append("\n- ").append(it) }
        }
        section("目标", s.goals)
        section("未完成事项", s.todos)
        if (s.refs.isNotEmpty()) append("\n引用：").append(s.refs.joinToString(" · "))
        section("关键信号", s.signals)
        section("工具记录", s.tools)
    }
}

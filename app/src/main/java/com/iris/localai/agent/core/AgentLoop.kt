package com.iris.localai.agent

import com.iris.localai.engine.PromptMessage
import com.iris.localai.util.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class StepStatus { RUNNING, ASKING, OK, ERROR, DENIED }

/** One tool step of an agent reply; stored on the assistant message and drawn as a card. */
@Serializable
data class AgentStep(
    val id: String = UUID.randomUUID().toString(),
    /** Tool name (or "格式错误" for a call that could not be read). */
    val tool: String,
    val args: String = "",
    val result: String = "",
    val status: StepStatus = StepStatus.RUNNING,
    /** Tool output as the model saw it (capped), for the expanded card. */
    val detail: String = "",
    /** 1.13.3: what the model thought/wrote in the turn that made this call (shown above the card). */
    val lead: String = "",
    /** 2.1.0: the card header for people (搜索「q」, 读取 a.txt); [args] stays the model-facing form. */
    val title: String = "",
    /** 2.1.0: the plan after a `plan` step, drawn as a checklist (never from [detail]). */
    val plan: Plan? = null,
    /** 2.1.7: workspace-relative pictures the step made (generate_image), drawn as thumbnails. */
    val images: List<String> = emptyList(),
)

/** 2.1.7: pictures of a reply's steps, appended to its text as markdown images. Pure. */
object StepImages {
    fun of(steps: List<AgentStep>): List<String> = steps.flatMap { it.images }.distinct()

    /** [text] plus `![](path)` for each step image the text does not mention yet. */
    fun appendTo(text: String, steps: List<AgentStep>): String {
        val missing = of(steps).filter { !text.contains(it) }
        if (missing.isEmpty()) return text
        val md = missing.joinToString("\n") { "![]($it)" }
        return if (text.isBlank()) md else "$text\n\n$md"
    }
}

/**
 * The system prompt for 智能体 mode: compact (2.3: ≤ 600 estimated tokens with every tool on),
 * and only the enabled tools are listed. English for token economy; the model answers in Chinese.
 */
object AgentPrompt {
    /**
     * 1.10.12: how the model is told to call tools. Qwen-family models (Qwen2.5/3, Agents-A1,
     * DeepSeek-R1-Distill-Qwen, Hermes) are trained on `<tool_call>{"name":…,"arguments":…}</tool_call>`;
     * everything else gets the neutral fenced block. The parser accepts both either way.
     */
    enum class CallStyle { QWEN, FENCE }

    fun styleFor(modelName: String?): CallStyle {
        val n = modelName.orEmpty().lowercase()
        return if (QWEN_HINTS.any { n.contains(it) }) CallStyle.QWEN else CallStyle.FENCE
    }
    private val QWEN_HINTS = listOf("qwen", "agents-a1", "agents_a1", "hermes", "qwq", "minicpm4", "internlm3")

    /**
     * 1.10.12: system prompt laid out after Claude Code's: who you are → rules → how to call tools
     * (one concrete example) → tools → environment. Short imperative lines, one rule each.
     *
     * 2.3 (预填充提速): ordered for KV-cache prefix reuse — everything that never changes within a
     * conversation comes first and is byte-identical between turns (persona, rules, call format,
     * the enabled tools in a fixed order), then the semi-stable parts (skills, plan-mode switch,
     * the user's own instructions, the frozen long-term memory). Nothing volatile lives here: the
     * conversation's plan rides on the newest user turn ([planNote]) and compaction summaries lead
     * the history (ContextAssembly / ContextPlanner), so a plan update or a compaction no longer
     * invalidates the cached system prompt. Wording trimmed (2.3: ~780 → ~570 estimated tokens with
     * every tool on). Every token here is prefilled on the phone.
     */
    fun build(
        tools: List<AgentTool>, userSystem: String = "", style: CallStyle = CallStyle.FENCE,
        /** 1.12.0: skills found in the workspace (listed only while the skill tool is on). */
        skills: List<SkillInfo> = emptyList(),
        /** 1.14.0 按需载入: [ActivateTool.LAZY] groups are listed by name only. */
        lazy: Boolean = false,
        /** 1.14.0 计划模式: plan first, wait for the user's OK, then do it. */
        planFirst: Boolean = false,
        /** 2.3: the frozen long-term-memory block (last: semi-stable, after the user's instructions). */
        memory: String = "",
    ): String = buildString {
        // 2.3: fixed tool order whatever the caller passes (byte-identical prefix for the KV cache).
        val tools = tools.distinct().sortedBy { t -> TOOL_ORDER.indexOf(t.name).let { if (it < 0) Int.MAX_VALUE else it } }
        append("You are IRIS, an AI agent on the user's Android phone.\n\n")

        append("# Rules\n")
        append("- Reply in Chinese, concise. Never claim actions you didn't take.\n")
        append("- Chat/simple questions: plain text with NO tool block (\"system\" is not a tool).\n")
        // 2.1.7: "then report their findings" was lost in the 2.1.6 trim ("When done, answer briefly
        // with what you did"): small models ended a news search with "已搜索" or nothing at all.
        append("- Use tools only if needed, then report their findings; fix a failed one once or say so.\n\n")

        append("# Tool calls\n")
        append("Call a tool with ONLY this, then stop and wait:\n")
        if (style == CallStyle.QWEN) {
            // 2.2.13: placeholder example — small models copied the old read_file("notes/todo.md") literally.
            append("<tool_call>\n{\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}\n</tool_call>\n")
        } else {
            append("```tool\n{\"tool\": \"工具名\", \"args\": {\"参数\": \"值\"}}\n```\n")
        }
        append("- 只在用户需要时调用工具，不要照抄示例。\n")
        append("- One call per reply, valid JSON. Results come in <tool_response>; never write one.\n")
        append("- Only the names below; asked about tools, reply in words.\n\n")

        if (tools.isNotEmpty()) {
            // 1.12.0: grouped, one shared hint per group. Group order and tool order are fixed
            // (enum order, then [AgentTools.all] order), so the text is identical turn after turn.
            // 2.3: the time format once in the header (phone + schedule; also for activate'd groups).
            append("# Tools (? = optional")
            if (tools.any { it.promptLine.contains("time?") }) append("; time = ").append(TIME_FORMAT).append(", local")
            append(")\n")
            val activatable = lazy && tools.any { it.name == ActivateTool.name }
            val folded = if (activatable) tools.filter { it.group in ActivateTool.LAZY } else emptyList()
            val shown = tools.filter { it !in folded && (it.name != ActivateTool.name || folded.isNotEmpty()) }
            // Groups with a shared hint get a header; 2.3: the rest are listed together under "Other",
            // one tool per line without bullets (fewer tokens, same information).
            val (hinted, plain) = shown.partition { it.group.hint.isNotEmpty() }
            hinted.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).forEach { (g, ts) ->
                append("## ").append(g.title).append(" — ").append(g.hint).append('\n')
                ts.forEach { append(it.promptLine).append('\n') }
            }
            if (plain.isNotEmpty()) {
                append("## ").append(ToolGroup.OTHER.title).append('\n')
                plain.sortedBy { it.group.ordinal }.forEach { append(it.promptLine).append('\n') }
            }
            if (folded.isNotEmpty()) {
                append("## More (call activate(group) first)\n")
                folded.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).forEach { (g, ts) ->
                    append("- ").append(ActivateTool.key(g)).append(": ").append(ts.joinToString(", ") { it.name }).append('\n')
                }
            }
            if (skills.isNotEmpty() && tools.any { it.name == SkillTool.name }) {
                append("\n# Skills (load with skill(name) when the task matches)\n")
                skills.take(MAX_SKILLS_LISTED).forEach { append("- ").append(it.name).append(": ").append(it.description.take(SKILL_DESC_MAX)).append('\n') }
            }
        } else {
            append("# Tools\nNo tools are enabled; answer in plain text.\n")
        }
        if (planFirst && tools.any { it.name == PlanTool.name }) {
            append("\n# Plan mode\n- For a task with several steps: first call plan(action: set, steps: [...]), then show the plan in one short reply and ask the user to confirm. Start only after the user agrees.\n")
        }
        if (userSystem.isNotBlank()) append("\n# User instructions\n").append(userSystem.trim()).append('\n')
        if (memory.isNotBlank()) append('\n').append(memory.trim()).append('\n')
    }.trimEnd()

    /**
     * 2.3: the conversation's unfinished plan, added to the newest user turn of an agent run (it
     * used to sit in the system prompt, where every plan update forced a full re-prefill). It is
     * re-added on every run, so compaction can still never drop it.
     */
    fun planNote(plan: Plan?): String? =
        plan?.takeIf { it.steps.isNotEmpty() && !it.finished }?.promptBlock()

    /** 2.3: [planNote] appended to the last user message of [messages] (prompt only). */
    fun withPlanNote(messages: List<PromptMessage>, plan: Plan?): List<PromptMessage> {
        val note = planNote(plan) ?: return messages
        val i = messages.indexOfLast { it.role == PromptMessage.USER }
        if (i < 0) return messages
        return messages.mapIndexed { idx, m -> if (idx == i) m.copy(content = m.content.trimEnd() + "\n\n" + note) else m }
    }

    const val MAX_SKILLS_LISTED = 12
    const val SKILL_DESC_MAX = 100
    /** 2.3: how phone / schedule take a time (stated once in the tools header). */
    const val TIME_FORMAT = "\"[yyyy-MM-dd ]HH:mm\""
    private val TOOL_ORDER: List<String> by lazy { (AgentTools.all + AgentTools.onDemand).map { it.name } }

    fun toolResponse(name: String, r: ToolResult): String =
        "<tool_response>\n{\"tool\": \"$name\", \"ok\": ${r.ok}}\n${r.output}\n</tool_response>"

fun retryHint(error: String): String =
        "<tool_response>\nError: your tool call could not be read ($error). Reply again with exactly one tool call " +
            "in the format from the system prompt (valid JSON, double quotes), or answer without any tool.\n</tool_response>"
    /** 1.13.0: the model wrote a 〔本轮已执行的工具〕 log itself — nothing ran. */
    const val FAKE_LOG_HINT =
        "<tool_response>\nError: no tool ran. You wrote a tool log yourself and its results are invented. " +
            "That block in history is written by the app, never by you. If you need a tool, reply with exactly one real tool call " +
            "in the format from the system prompt; otherwise answer the user without that block.\n</tool_response>"

    /** 1.10.4: silent retry hint after a call to a tool that does not exist. */
    fun unknownToolHint(name: String, available: Set<String>): String =
        "<tool_response>\nError: there is no tool \"$name\". Do not guess tool names. Use one of: ${available.joinToString()} — or answer the user in plain text without any tool.\n</tool_response>"

    const val STEP_LIMIT = "Step limit reached. Do not call any more tools. Answer the user in Chinese now with what the tool results above show, and what is left."
    /**
     * 2.1.7: added to the newest user turn when a reply came back empty (only thinking, a call
     * buried in unfinished thinking, or nothing): one more turn to give the actual answer.
     */
    /** 2.2.2: the user picked [tool] with @ but the reply did not call it. */
    fun mustCall(tool: String): String = if (tool == "screen")
        "(The user picked the screen tool: you CAN operate this phone through it. Call screen now with the task in the user's words. Do not refuse or explain.)"
    else "(The user picked the $tool tool for this request. Call $tool now.)"

    const val ANSWER_NOW = "(Your last reply was empty. Answer the user now in Chinese with what the tool results above show: key facts, dates, sources. No tool call; keep thinking short.)"
    const val ELIDED = "<tool_response>\n(earlier tool output removed to save context)\n</tool_response>"
}

/** [truncated] (2.4.5): the engine stopped at the output-length / context limit, not at the end of the reply. */
data class ModelTurn(val text: String, val error: String? = null, val truncated: Boolean = false)

/** One model completion; the app's adapter streams through [onText] (full text so far). */
interface AgentModel {
    suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn
}

interface AgentCallbacks {
    /** Asks the user (in-chat dialog). Suspends until answered; cancellation ends the wait. */
    suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision
    /** Steps changed (a new step, or a step's status). */
    fun onSteps(steps: List<AgentStep>)
    /** The model's text of the current turn so far (call block hidden). */
    fun onText(text: String) {}
    /** 总是允许 was chosen. */
    fun onAlwaysAllow(tool: AgentTool) {}
    /** 1.12.0: 总是允许 for one call (remembers the whole tool). */
    fun onAlwaysAllowCall(tool: AgentTool, call: ToolCall) = onAlwaysAllow(tool)
    /** A tool changed the workspace (write / edit / delete). */
    fun onWorkspaceChanged() {}
    /** 1.10.0: the memory tool changed USER.md / MEMORY.md. */
    fun onMemoryChanged() {}
    /** 2.1.7: a tool is about to run (permission granted) — voice speaks a short 「我查一下」. */
    suspend fun beforeTool(tool: AgentTool, call: ToolCall) {}
}

enum class AgentStop { DONE, MAX_STEPS, ERROR }

data class AgentOutcome(
    /** Raw text of the final turn (think tags kept; the UI parses them). */
    val finalText: String,
    val steps: List<AgentStep>,
    val stop: AgentStop,
    val error: String? = null,
    /**
     * 1.10.6: the whole prompt of this run as the engine saw it (system, history, every tool call
     * and result, and the final answer). The next 智能体 turn continues from it, so the engine's
     * KV cache is reused and the model keeps the tool results of earlier turns.
     */
    val transcript: List<PromptMessage> = emptyList(),
)

/**
 * 1.9.0 Pi-style minimal agent loop: model → parse → permission → execute → result appended →
 * again, until the model answers without a tool, [maxSteps] tool calls ran, or the coroutine is
 * cancelled (stop button; [CancellationException] propagates). A call that cannot be parsed is
 * retried once with an error hint. Older tool outputs are elided when the history outgrows
 * [contextBudget] (estimated tokens). Engine-agnostic and unit-tested with a fake model.
 */
class AgentLoop(
    private val model: AgentModel,
    private val tools: List<AgentTool>,
    private val permissionOf: (AgentTool) -> Permission,
    private val env: ToolEnv,
    private val callbacks: AgentCallbacks,
    private val maxSteps: Int = AgentSettings.DEFAULT_MAX_STEPS,
    private val contextBudget: Int = Int.MAX_VALUE,
    /** Text of a turn → the answer part (reasoning removed). */
    private val answerOf: (String) -> String = { it },
    /** Runs a tool off the caller's thread (Dispatchers.IO in the app). */
    private val runTool: suspend (() -> ToolResult) -> ToolResult = { it() },
    /**
     * 2.1.7: what the user added while the run was going (「用户补充：…」), read before every model
     * step; it rides on the newest user-side message (not yet prefilled, so the KV prefix stays).
     */
    private val pendingNotes: () -> String? = { null },
    /** 2.2.2: tools the user picked with @ for this turn; a reply that skips them is pushed once. */
    private val requiredTools: List<String> = emptyList(),
    /** 2.2.2: the user's own words, run as the `screen` task when the model still refuses. */
    private val userTask: String = "",
) {
    private val steps = ArrayList<AgentStep>()

    suspend fun run(base: List<PromptMessage>): AgentOutcome {
        val msgs = base.toMutableList()
        var fixed = base.size
        var calls = 0
        var retried = false
        var unknownRetried = false
        var echoRetried = false
        var nudged = false
        var toolPushed = false
        // 2.2.8: raw text of each unreadable call attempt (kept in the UI and logged), and the card of attempt 1.
        val failedRaw = ArrayList<String>()
        var retryStep: AgentStep? = null
        // 2.2.5: the tolerant parser fallback recognises exactly the tools of this run.
        ToolCallParser.knownTools = tools.map { it.name }.toSet()
        while (true) {
            currentCoroutineContext().ensureActive()
            pendingNotes()?.takeIf { it.isNotBlank() }?.let { injectNote(msgs, it) }
            fixed = fit(msgs, fixed)
            val tLlm0 = System.currentTimeMillis()
            val turn = model.complete(msgs.toList()) { callbacks.onText(ToolCallParser.visibleText(it)) }
            android.util.Log.i(AGENT_TAG, "step $calls: llm=${System.currentTimeMillis() - tLlm0}ms msgs=${msgs.size} prompt~${msgs.sumOf { it.content.length / 4 }}tok raw=\"${turn.text.take(500).replace('\n', ' ')}\"")
            if (turn.error != null) return AgentOutcome(turn.text, steps.toList(), AgentStop.ERROR, turn.error)
            // 2.1.7: a call written inside unfinished thinking (the stream is cut right after it, so
            // the think block never closes) used to leave an empty answer: run that call instead.
            // 2.2.14: a call that only appears inside thinking is not run (was callInOpenThink).
            val answer = answerOf(turn.text)
            var parsed = ToolCallParser.fillMainArg(ToolCallParser.parse(answer), userTask)
            retryStep?.let { if (parsed !is ParsedReply.Malformed) { update(it.copy(result = "已重试")); retryStep = null } }
            // 2.2.2: the user picked a tool (@screen) but the reply skips it — small local models
            // often refuse. Push once; if it still refuses, run `screen` with the user's own words.
            val missing = requiredTools.firstOrNull { r -> tools.any { it.name == r } && steps.none { it.tool == r } }
            if (parsed is ParsedReply.Final && missing != null && calls < maxSteps) {
                if (!toolPushed) {
                    toolPushed = true
                    android.util.Log.w(AGENT_TAG, "picked tool $missing not called (push once)")
                    msgs += PromptMessage(PromptMessage.ASSISTANT, answer.trim().ifEmpty { "…" })
                    msgs += PromptMessage(PromptMessage.USER, AgentPrompt.mustCall(missing))
                    continue
                }
                if (missing == ScreenTool.name && userTask.isNotBlank()) {
                    android.util.Log.w(AGENT_TAG, "picked tool screen refused twice: run it with the user's words")
                    parsed = ParsedReply.Call(ToolCall(ScreenTool.name, kotlinx.serialization.json.buildJsonObject {
                        put("task", kotlinx.serialization.json.JsonPrimitive(userTask))
                    }, CallFormat.FENCED), "")
                }
            }
            when (val p = parsed) {
                is ParsedReply.Final -> {
                    // 1.13.0: the model wrote a fake tool log (copied from history) instead of calling.
                    if (com.iris.localai.context.ToolRollup.hasEcho(answer) && !echoRetried) {
                        echoRetried = true
                        android.util.Log.w(AGENT_TAG, "fake tool log in answer (silent retry)")
                        msgs += PromptMessage(PromptMessage.ASSISTANT, answer.trim())
                        msgs += PromptMessage(PromptMessage.USER, AgentPrompt.FAKE_LOG_HINT)
                        continue
                    }
                    val clean = com.iris.localai.context.ToolRollup.stripEcho(answer.trim())
                    if (clean.isBlank()) {
                        // 2.1.7: an empty answer (thinking only / hit the length cap) showed the user
                        // nothing after a search. One forced follow-up, then the results themselves.
                        if (!nudged) {
                            nudged = true
                            android.util.Log.w(AGENT_TAG, "empty answer after ${steps.size} step(s) (forced follow-up)")
                            nudge(msgs, AgentPrompt.ANSWER_NOW)
                            continue
                        }
                        if (steps.any { it.tool != FORMAT_ERROR }) {
                            val text = closeThink(turn.text) + fallbackAnswer(steps)
                            return AgentOutcome(text, steps.toList(), AgentStop.DONE,
                                transcript = msgs + PromptMessage(PromptMessage.ASSISTANT, answerOf(text).trim()))
                        }
                    }
                    return AgentOutcome(ToolCallParser.stripCalls(com.iris.localai.context.ToolRollup.stripEcho(turn.text)), steps.toList(), AgentStop.DONE,
                        transcript = msgs + PromptMessage(PromptMessage.ASSISTANT, clean))
                }
                is ParsedReply.Malformed -> {
                    failedRaw += turn.text
                    logRaw(failedRaw.size, p.error, turn.text)
                    if (retried) {
                        // Second unreadable call in a row: stop and show what the model wrote.
                        // 2.2.5: the unreadable markup is hidden; only the prose before it stays.
                        // 2.2.8: the raw output of every failed attempt stays one tap away.
                        retryStep?.let { update(it.copy(result = "重试后仍无法识别")); retryStep = null }
                        steps += AgentStep(tool = FORMAT_ERROR, title = "查看原始输出", result = p.error, status = StepStatus.ERROR,
                            detail = failedRaw.mapIndexed { i, r -> "── 第 ${i + 1} 次输出 ──\n${r.trim()}" }.joinToString("\n\n"))
                        callbacks.onSteps(steps.toList())
                        return AgentOutcome(ToolCallParser.stripCalls(p.before), steps.toList(), AgentStop.ERROR, "模型的工具调用格式两次都无法识别：${p.error}")
                    }
                    retried = true
                    // 2.2.8: attempt 1 is kept (folded) instead of being replaced by attempt 2's stream.
                    android.util.Log.w(AGENT_TAG, "malformed call (retry): ${p.error}")
                    AgentStep(tool = FORMAT_ERROR, title = "第 1 次输出（格式无法识别）", result = "工具格式不对，正在重试…",
                        status = StepStatus.ERROR, detail = turn.text.trim()).let { retryStep = it; publish(it) }
                    msgs += PromptMessage(PromptMessage.ASSISTANT, answer.trim())
                    msgs += PromptMessage(PromptMessage.USER, AgentPrompt.retryHint(p.error))
                }
                is ParsedReply.Call -> {
                    retried = false
                    failedRaw.clear()
                    if (calls >= maxSteps) return finishAtLimit(msgs, fixed, p)
                    calls++
                    var call = p.call
                    // 1.10.4: a misnamed call (ls/cat/…) is fixed to a real tool silently.
                    val avail = tools.map { it.name }.toSet()
                    if (call.name !in avail) ToolCallParser.canonicalizeName(call.name, avail)?.let { fixed0 ->
                        android.util.Log.i(AGENT_TAG, "alias: ${call.name} -> $fixed0")
                        call = call.copy(name = fixed0)
                    }
                    // 1.13.3: a skill called like a tool (daily-news{…}) → skill(name=daily-news).
                    if (call.name !in avail && SkillTool.name in avail && env.skills?.find(call.name) != null) {
                        android.util.Log.i(AGENT_TAG, "skill as tool: ${call.name} -> skill")
                        call = call.copy(name = SkillTool.name, args = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(call.name)) })
                    }
                    val tool = tools.firstOrNull { it.name == call.name }
                    // 2.2.5: optional input files that do not exist (generate_image image="notes/x.png"
                    // invented by a small model) are dropped, so the call runs without them.
                    if (tool != null) call = call.copy(args = dropMissingInputs(tool.name, call.args, env))
                    var step = AgentStep(tool = call.name, args = Args.summary(call.args), lead = ToolCallParser.visibleText(turn.text).trim(),
                        title = StepTitle.of(call.name, call.args))
                    val stepStart = System.currentTimeMillis()
                    if (tool == null && !unknownRetried) {
                        // 1.10.4: first unknown tool — silent retry with a hint, no red card.
                        unknownRetried = true
                        android.util.Log.w(AGENT_TAG, "unknown tool \"${call.name}\" (silent retry); raw=${call.args.toString().take(200)}")
                        msgs += PromptMessage(PromptMessage.ASSISTANT, rawCall(answer, call))
                        msgs += PromptMessage(PromptMessage.USER, AgentPrompt.unknownToolHint(call.name, avail))
                        continue
                    }
                    // 2.4.5 (#11): a write the length limit cut off saves its complete lines only, then
                    // asks for the rest with append=true (it used to save the cut text and say OK).
                    var cut: AgentOutput.Cut? = null
                    var cutNothing = false
                    if (tool != null && turn.truncated && call.name in AgentOutput.WRITE_TOOLS && AgentOutput.callUnclosed(answer)) {
                        val content = Args.str(call.args, "content", "text", "data")
                        cut = content?.let { AgentOutput.cutWrite(it) }
                        if (cut == null) cutNothing = true
                        else call = call.copy(args = kotlinx.serialization.json.JsonObject(call.args.filterKeys { it != "text" && it != "data" } +
                            ("content" to kotlinx.serialization.json.JsonPrimitive(cut.kept))))
                        android.util.Log.w(AGENT_TAG, "${call.name} cut by the length limit: kept ${cut?.lines ?: 0} line(s)")
                    }
                    publish(step)
                    var result: ToolResult
                    val status: StepStatus
                    if (cutNothing) {
                        result = ToolResult(false, AgentOutput.nothingNote(Args.str(call.args, "path", "file", "file_path", "filename")),
                            com.iris.localai.I18n.s(com.iris.localai.R.string.agent245_cut_nothing))
                        status = StepStatus.ERROR
                    } else if (tool == null) {
                        // Still unknown after the retry: a gray note, not a red error card.
                        result = ToolResult(false, "Error: unknown tool \"${call.name}\". Available: ${avail.joinToString()}", "未知工具（已忽略）")
                        status = StepStatus.DENIED
                    } else {
                        val perm = tool.permissionFor(call.args, permissionOf(tool), env)
                        var decision: UserDecision? = null
                        if (perm == Permission.ASK) {
                            step = step.copy(status = StepStatus.ASKING); update(step)
                            decision = callbacks.confirm(tool, call)
                            if (decision == UserDecision.ALWAYS_ALLOW) callbacks.onAlwaysAllowCall(tool, call)
                            step = step.copy(status = StepStatus.RUNNING); update(step)
                        }
                        if (!PermissionPolicy.mayRun(perm, decision)) {
                            result = ToolResult(false, if (perm == Permission.DENY) "Error: this tool is denied in the user's settings." else "Error: the user declined this action.",
                                if (perm == Permission.DENY) "设置中已拒绝" else "用户已拒绝")
                            status = StepStatus.DENIED
                        } else {
                            callbacks.beforeTool(tool, call)
                            result = try {
                                if (tool is SuspendTool) {
                                    // 1.12.0: long-running tool (process_file) — progress goes into its card.
                                    val running = step
                                    tool.runSuspend(call.args, env) { p -> update(running.copy(result = p)) }
                                } else runTool { tool.run(call.args, env) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // 1.12.0: a wrong-argument error carries the tool's full signature.
                                val usage = if (t is IllegalArgumentException) "\nUsage: ${tool.promptLine}" else ""
                                ToolResult(false, "Error: ${t.message ?: t.javaClass.simpleName}$usage", (t.message ?: "执行失败").take(40))
                            }
                            status = if (result.ok) StepStatus.OK else StepStatus.ERROR
                            android.util.Log.i(AGENT_TAG, "step $calls: tool=${call.name} ok=${result.ok} ${System.currentTimeMillis() - stepStart}ms")
                            if (result.ok && tool.risk in CHANGES) callbacks.onWorkspaceChanged()
                            if (result.ok && tool.risk == ToolRisk.MEMORY) callbacks.onMemoryChanged()
                        }
                    }
                    cut?.let { k ->
                        if (result.ok) {
                            val path = Args.str(call.args, "path", "file", "file_path", "filename").orEmpty()
                            val appended = Args.bool(call.args, "append") == true
                            result = result.copy(
                                output = result.output + "\n" + AgentOutput.continueNote(path, k, appended),
                                summary = com.iris.localai.I18n.s(com.iris.localai.R.string.agent245_cut_saved, k.lines),
                            )
                        }
                    }
                    update(step.copy(result = result.summary, status = status, detail = result.output, plan = result.plan, images = result.images))
                    // 1.10.6: the model's own text (cut at the end of its call), not a rewritten
                    // canonical block: it matches what the engine decoded, so the KV cache is kept.
                    msgs += PromptMessage(PromptMessage.ASSISTANT, rawCall(answer, call))
                    msgs += PromptMessage(PromptMessage.USER, AgentPrompt.toolResponse(call.name, result))
                }
            }
        }
    }

    /** 2.1.7: appends [note] to the newest user-side message (a tool result or the request), or adds one. */
    private fun injectNote(msgs: MutableList<PromptMessage>, note: String) {
        val i = msgs.lastIndex
        if (i >= 0 && msgs[i].role == PromptMessage.USER) msgs[i] = msgs[i].copy(content = msgs[i].content.trimEnd() + "\n\n" + note.trim())
        else msgs += PromptMessage(PromptMessage.USER, note.trim())
        android.util.Log.i(AGENT_TAG, "user note injected (${note.length} chars)")
    }

    /** 2.2.5: optional reference-file args per tool (inputs only — never output paths). */
    private val OPTIONAL_INPUTS = mapOf(GenerateImageTool.name to listOf("image", "init_image", "image_path", "path"))

    private fun dropMissingInputs(tool: String, args: kotlinx.serialization.json.JsonObject, env: ToolEnv): kotlinx.serialization.json.JsonObject {
        val keys = OPTIONAL_INPUTS[tool] ?: return args
        val drop = keys.filter { k ->
            val v = Args.primitive(args, k)?.trim() ?: return@filter false
            v.isEmpty() || runCatching { !env.sandbox.resolve(v).isFile }.getOrDefault(true)
        }
        if (drop.isEmpty()) return args
        android.util.Log.i(AGENT_TAG, "$tool: dropped missing optional input(s) $drop")
        return kotlinx.serialization.json.JsonObject(args.filterKeys { it !in drop })
    }

    /** Step limit hit: one last turn without tools for a summary (a stray call in it is dropped). */
    private suspend fun finishAtLimit(msgs: MutableList<PromptMessage>, fixed: Int, p: ParsedReply.Call): AgentOutcome {
        msgs += PromptMessage(PromptMessage.ASSISTANT, p.call.canonical())
        msgs += PromptMessage(PromptMessage.USER, AgentPrompt.STEP_LIMIT)
        fit(msgs, fixed)
        val turn = model.complete(msgs.toList()) { callbacks.onText(ToolCallParser.visibleText(it)) }
        val r = ToolCallParser.parse(answerOf(turn.text))
        var text = when (r) {
            is ParsedReply.Final -> turn.text
            is ParsedReply.Call -> r.before
            is ParsedReply.Malformed -> r.before
        }
        // 2.1.7: nothing said at the limit → the newest tool results instead of an empty bubble.
        val said = if (r is ParsedReply.Final) r.text else text
        if (turn.error == null && said.isBlank()) text = closeThink(turn.text.takeIf { r is ParsedReply.Final }.orEmpty()) + fallbackAnswer(steps)
        return AgentOutcome(text, steps.toList(), AgentStop.MAX_STEPS, turn.error,
            transcript = msgs + PromptMessage(PromptMessage.ASSISTANT, answerOf(text).trim()))
    }

    /**
     * 2.1.7: [note] added to the newest user message (a tool response or the request) rather than as
     * a new message, so roles still alternate (Gemma-style templates reject two user turns).
     */
    private fun nudge(msgs: MutableList<PromptMessage>, note: String) {
        val i = msgs.indexOfLast { it.role == PromptMessage.USER }
        if (i == msgs.lastIndex && i >= 0) msgs[i] = msgs[i].copy(content = msgs[i].content.trimEnd() + "\n" + note)
        else msgs += PromptMessage(PromptMessage.USER, note)
    }

    /** The model's reply as written (already cut after its call); the canonical block if it is empty. */
    private fun rawCall(answer: String, call: ToolCall): String = answer.trim().ifEmpty { call.canonical() }

    /** 2.2.8: the raw output of an unreadable call attempt, in logcat-sized pieces. */
    private fun logRaw(attempt: Int, error: String, raw: String) {
        android.util.Log.w(AGENT_TAG, "unreadable call attempt $attempt ($error), raw ${raw.length} chars:")
        raw.chunked(3000).forEachIndexed { i, c -> android.util.Log.w(AGENT_TAG, "raw[$attempt.$i] $c") }
    }

    private fun publish(s: AgentStep) {
        steps += s; callbacks.onSteps(steps.toList())
        // 1.13.3: the turn's text now lives on the step (above its card); the live text starts over.
        callbacks.onText("")
    }

    private fun update(s: AgentStep) {
        val i = steps.indexOfFirst { it.id == s.id }
        if (i >= 0) steps[i] = s else steps += s
        callbacks.onSteps(steps.toList())
    }

    /**
     * Keeps the prompt under [contextBudget]. 1.13.0 order (the old one dropped whole earlier turns
     * while their big tool outputs survived, so the model "forgot" what was just asked):
     *  1. tool outputs of earlier turns are shortened to their head ([EARLIER_KEEP] chars);
     *  2. the oldest tool outputs of this run are replaced by a stub;
     *  3. tool outputs of earlier turns are replaced by a stub;
     *  4. the oldest earlier rounds are dropped whole (a round = a user request and everything up to
     *     the next one), so the history never starts with an assistant turn or a tool response.
     * System prompt and the current request always stay.
     */
    private fun fit(msgs: MutableList<PromptMessage>, fixedIn: Int): Int {
        var fixed = fixedIn
        fun total() = msgs.sumOf { TokenEstimator.message(it.content) }
        if (total() <= contextBudget) return fixed
        val start = if (msgs.firstOrNull()?.role == PromptMessage.SYSTEM) 1 else 0
        val before = msgs.size
        fun isToolOut(m: PromptMessage) = m.role == PromptMessage.USER && m.content.startsWith("<tool_response>") && m.content != AgentPrompt.ELIDED
        // 1. shorten earlier turns' tool outputs
        for (i in start until (fixed - 1).coerceAtLeast(start)) {
            if (total() <= contextBudget) return fixed
            val m = msgs[i]
            if (isToolOut(m)) msgs[i] = m.copy(content = shortenToolOutput(m.content, EARLIER_KEEP))
        }
        // 2. this run's oldest outputs (2.1.7: never the newest one — the answer turn after a step
        // limit or an empty reply ends on a note, so "all but the last message" could stub it out)
        val newest = msgs.indexOfLast { isToolOut(it) }
        for (i in fixed until msgs.size - 1) {
            if (total() <= contextBudget) return fixed
            if (i == newest) continue
            val m = msgs[i]
            if (isToolOut(m)) msgs[i] = m.copy(content = AgentPrompt.ELIDED)
        }
        // 3. earlier turns' outputs → stub
        for (i in start until (fixed - 1).coerceAtLeast(start)) {
            if (total() <= contextBudget) return fixed
            val m = msgs[i]
            if (isToolOut(m)) msgs[i] = m.copy(content = AgentPrompt.ELIDED)
        }
        // 4. drop whole rounds, oldest first
        while (total() > contextBudget && fixed - start > 1) {
            msgs.removeAt(start); fixed--
            while (fixed - start > 1 && !isRequest(msgs[start])) { msgs.removeAt(start); fixed-- }
        }
        if (msgs.size != before) android.util.Log.i(AGENT_TAG, "fit dropped ${before - msgs.size} msgs")
        return fixed
    }

    private fun isRequest(m: PromptMessage) = m.role == PromptMessage.USER && !m.content.startsWith("<tool_response>")

    companion object {
        const val FORMAT_ERROR = "格式错误"
        /** 1.10.4: step timing + raw-output log tag (adb logcat -s IrisAgent). */
        const val AGENT_TAG = "IrisAgent"
        /** Characters of an earlier turn's tool output kept before it is stubbed out. */
        const val EARLIER_KEEP = 600

        /** Head of a `<tool_response>` block, closed again, with a marker that text was cut. */
        fun shortenToolOutput(block: String, keep: Int): String {
            val body = block.removePrefix("<tool_response>").removeSuffix("</tool_response>").trim()
            if (body.length <= keep) return block
            return "<tool_response>\n" + body.take(keep).trimEnd() + "\n…(shortened to save context)\n</tool_response>"
        }
        private val CHANGES = setOf(ToolRisk.WRITE, ToolRisk.DELETE)

        const val FALLBACK_HEAD = "（模型没有给出回答，以下是工具找到的结果）"
        const val FALLBACK_MAX = 1500

        /** Raw turn text with an unclosed `<think>` closed, so text appended after it is the answer. */
        fun closeThink(raw: String): String {
            val t = raw.trimEnd()
            val open = t.lastIndexOf("<think>", ignoreCase = true)
            val close = t.lastIndexOf("</think>", ignoreCase = true)
            return when {
                open > close -> "$t\n</think>\n\n"
                t.isNotEmpty() -> "$t\n\n"
                else -> ""
            }
        }

        /**
         * 2.1.7: what the user sees when the model said nothing after its tools: the newest
         * successful tool output (search hits with snippets, page text), header and truncation
         * notes removed, capped; or a short note naming the tools that ran.
         */
        fun fallbackAnswer(steps: List<AgentStep>): String {
            val last = steps.lastOrNull { it.status == StepStatus.OK && it.detail.isNotBlank() }
                ?: return "（模型没有给出回答。已运行：" + steps.joinToString("、") { it.title.ifBlank { it.tool } } + "。可以重试或换个问法。）"
            val body = last.detail.lines()
                .filterNot { it.startsWith("Search results for ") || it.startsWith("[TRUNCATED by the tool") || it.startsWith("Note: ") }
                .joinToString("\n").trim()
            val capped = if (body.length > FALLBACK_MAX) body.take(FALLBACK_MAX).trimEnd() + "…" else body
            return FALLBACK_HEAD + "\n\n" + capped
        }
    }
}

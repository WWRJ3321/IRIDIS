package com.iris.localai.ui.chat


import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.agent.core.AgentCallbacks
import com.iris.localai.agent.core.AgentLoop
import com.iris.localai.agent.core.AgentModel
import com.iris.localai.agent.core.AgentOutcome
import com.iris.localai.agent.core.AgentOutput
import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.AgentStep
import com.iris.localai.agent.core.AgentStop
import com.iris.localai.agent.core.Args
import com.iris.localai.agent.core.ModelTurn
import com.iris.localai.agent.core.PermissionPolicy
import com.iris.localai.agent.core.StepStatus
import com.iris.localai.agent.core.ToolCall
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.gui.GuiNotes
import com.iris.localai.agent.mcp.McpTool
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.tools.ChunkLlm
import com.iris.localai.agent.tools.GenerateImageTool
import com.iris.localai.agent.tools.GuiIntent
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.TurnRefKind
import com.iris.localai.service.GenerationMonitor
import com.iris.localai.service.GenerationService
import com.iris.localai.util.ContextPlan
import com.iris.localai.util.TokenEstimator
import com.iris.localai.util.diagnostics.Activity
import com.iris.localai.util.diagnostics.SystemStatus
import com.iris.localai.util.streaming.ReplyTimer
import com.iris.localai.util.streaming.StopTokenFilter
import com.iris.localai.voice.AgentVoiceStream
import com.iris.localai.voice.VoiceCues
import com.iris.localai.voice.VoiceStyle
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.memory.kindFor
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Conversation
import com.iris.localai.data.LocalModel
import com.iris.localai.data.Role
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.Requester
import com.iris.localai.engine.StopReason
import com.iris.localai.util.thinking.StreamingThinkSplitter
import com.iris.localai.util.thinking.ThinkParser
import com.iris.localai.util.streaming.StopPolicy
import com.iris.localai.voice.Dictation
import com.iris.localai.voice.PcmPlayer
import com.iris.localai.voice.VoiceChatBridge
import com.iris.localai.voice.VoiceController
import com.iris.localai.util.streaming.SentenceSplitter
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.iris.localai.ui.chat.ChatViewModel.Companion.FRAME_MS
import com.iris.localai.ui.chat.ChatViewModel.Companion.GEN_TAG
import com.iris.localai.ui.chat.ChatViewModel.Companion.AGENT_MAX_NEW
import com.iris.localai.ui.chat.ChatViewModel.PermissionAsk
import com.iris.localai.ui.chat.ChatViewModel.AgentTranscript

// split out of ChatViewModel.kt unchanged (extensions on ChatViewModel) — 智能体 turns: the agent loop, tool previews, continued transcripts, queued notes.

internal fun ChatViewModel.agentPreview(tool: AgentTool, call: ToolCall): String {
    val a = call.args
    val path = Args.str(a, "path", "file", "file_path", "filename")
    return when (tool.name) {
        "write_file" -> "文件：$path\n内容（${com.iris.localai.agent.core.Args.str(a, "content", "text", "data")?.length ?: 0} 字）：\n" +
            (Args.str(a, "content", "text", "data") ?: "").take(400)
        "edit_file" -> I18n.s(R.string.chat_5691f8, path) + (Args.str(a, "old", "old_string", "old_text", "search", "find") ?: "").take(200) +
            I18n.s(R.string.chat_d2bc44) + (Args.str(a, "new", "new_string", "new_text", "replace", "replacement") ?: "").take(200)
        "delete_file" -> I18n.s(R.string.chat_469651, path)
        "web_fetch" -> I18n.s(R.string.chat_7caf26) + (Args.str(a, "url", "link", "href") ?: "")
        "memory" -> com.iris.localai.agent.tools.memoryPreview(call)
        else -> if (tool is McpTool) I18n.s(R.string.chat_e5c66d, tool.server.name, tool.server.url, tool.info.name) +
            Args.summary(a, 300) + I18n.s(R.string.chat_afc4c6) else Args.summary(a, 300)
    }
}

/**
 * 智能体 reply: [com.iris.localai.agent.core.AgentLoop] with the loaded engine as its model.
 * Each model turn streams into the bubble (the call block hidden); tool steps are stored on
 * the message ([ChatMessage.agentSteps]) and drawn as cards. Only the final answer goes back
 * into later turns' history (small context windows).
 */
internal suspend fun ChatViewModel.runAgentReply(reply: ChatMessage, voiceOut: ((String) -> Unit)? = null, only: Set<String>? = null) {
    GenerateImageTool.imageStyle = cardOf(conversation.value)?.bindImageStyle.orEmpty()
    val s = c.settings.current
    val ag = c.agent.settings.current
    val ready = c.engine.state.value as? EngineState.Ready
    val details = ready?.details
    val eff = c.engine.effective()
    // a spoken run answers without reasoning (as the plain voice reply always did).
    val disableThinking = !eff.thinking || voiceOut != null
    var toolCues = 0
    // 边生成边开口: the answer turn is spoken sentence by sentence while it streams.
    val voiceStream = voiceOut?.let { AgentVoiceStream(it) }
    val prefix = if (details?.thinkingPrefilled == true && !disableThinking) "<think>" else ""
    val history = c.agent.history
    val memoryOn = c.agent.memorySettings.current.enabled
    // r5：对话模式里的手机指令只带 [only] 里的工具（phone），其余工具说明不进提示。
    val tools = TurnAgent.tools(AgentTools.enabled(ag, history != null, memoryOn, c.agent.knowledge.nonEmptyCached(), c.agent.imageGenOnCached()), { it.name }, ag.agentMode, only)
    val ctxTokens = details?.contextLength?.takeIf { it > 0 } ?: eff.contextLength
    // the turn cap scales with the window (was min(最大生成长度, 1536) — long writes were cut).
    val maxNew = AgentOutput.maxNew(eff.maxNewTokens, ctxTokens)
    val sampling = c.engine.samplingFor(disableThinking).copy(maxNewTokens = maxNew, contextShift = false)
    streamingId.value = reply.id
    val timer = ReplyTimer().also { liveTimer = it }
    var steps: List<AgentStep> = emptyList()
    var agentCalls = 0
    var outcome: AgentOutcome? = null
    var lastStats: GenerationStats? = null
    var cancelled = false
    var failure: String? = null
    val background = s.backgroundGeneration
    val convId = conversation.value.id
    if (background) {
        GenerationMonitor.begin(convId, conversation.value.title, stop = { stop() })
        GenerationService.start(getApplication())
    }
    fun setSteps(list: List<AgentStep>) {
        steps = list
        conversation.update { conv -> conv.copy(messages = conv.messages.map { if (it.id == reply.id) it.copy(agentSteps = list) else it }) }
    }
    val model = object : AgentModel {
      override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): ModelTurn {
        val sb = StringBuilder()
        val stopFilter = StopTokenFilter()
        var cut = false
        var done: GenEvent.Done? = null
        var lastPub = 0L
        promptLog.record(reply.id, messages)
        agentCalls++
        voiceStream?.startTurn()
        val stepTag = if (agentCalls > 1) I18n.s(R.string.chat_e04ec0, agentCalls) else ""
        act(stepTag + I18n.s(R.string.chat_8a3813))
        replyWatchdog?.prompt(messages.sumOf { TokenEstimator.message(it.content) })
        c.engine.generate(withImages(messages), sampling, Requester.CHAT, engineOwner).collect { ev ->
            when (ev) {
                is GenEvent.Piece -> {
                    GenerationMonitor.tick()
                    replyWatchdog?.output()
                    if (cut) return@collect
                    val clean = stopFilter.push(ev.text)
                    if (stopFilter.stopped) { cut = true; c.engine.cancel(engineOwner) }
                    if (clean.isNotEmpty()) {
                        timer.onText(clean)
                        sb.append(clean)
                        // A complete call: stop here, the rest would be the model inventing the result.
                        // 只有新片段可能闭合调用（含 > ) } ] ` 或换行）时才扫描整段，避免每个 token 都 O(n) 重扫。
                        if (clean.any { it in CALL_END_CHARS }) ToolCallParser.callEnd(prefix + sb)?.let { end ->
                            sb.setLength((end - prefix.length).coerceIn(0, sb.length)); cut = true; c.engine.cancel(engineOwner)
                        }
                        val now = System.currentTimeMillis()
                        if (cut || now - lastPub >= FRAME_MS) {
                            // 思考解析、语音分句、阶段判断按帧节流。
                            val full = prefix + sb
                            lastPub = now; onText(full)
                            voiceStream?.update(ThinkParser.parse(full, expectThinking = prefix.isNotEmpty()).answer)
                            act(stepTag + Activity.phaseOf(full))
                        }
                    }
                }
                is GenEvent.Done -> {
                    if (!cut) sb.append(stopFilter.flush())
                    done = ev
                    lastStats = ev.stats
                }
            }
        }
        onText(prefix + sb)
        voiceStream?.update(ThinkParser.parse(prefix + sb, expectThinking = prefix.isNotEmpty()).answer)
        val d = done
        // first-token diagnostics — prefill is everything before the first token.
        android.util.Log.i("IrisAgent", "turn done: prefill=${lastStats?.prefillMs ?: -1}ms gen=${lastStats?.generateMs ?: -1}ms len=${sb.length}")
        if (d != null && d.reason == StopReason.CANCELLED && !cut) throw kotlinx.coroutines.CancellationException("stopped by user")
        return ModelTurn(
            prefix + sb.toString(),
            error = d?.takeIf { it.reason == StopReason.ERROR && sb.isBlank() }?.let { it.error ?: I18n.s(R.string.chat_7f7de8) },
            truncated = !cut && (d?.reason == StopReason.MAX_TOKENS || d?.reason == StopReason.CONTEXT_FULL),
        )
      }
    }
    val callbacks = object : AgentCallbacks {
        override suspend fun confirm(tool: AgentTool, call: ToolCall): UserDecision {
            // no dialog in a voice call — decline, and say it needs the screen.
            if (voiceOut != null) {
                voiceCue?.invoke(VoiceCues.CONFIRM_ON_SCREEN)
                notice.value = I18n.s(R.string.chat_ba2bd4, tool.label)
                return UserDecision.DENY
            }
            val d = kotlinx.coroutines.CompletableDeferred<UserDecision>()
            _stream.value = null
            _permissionAsk.value = PermissionAsk(tool.label, tool.name, agentPreview(tool, call), d, null)
            try { return d.await() } finally { if (_permissionAsk.value?.answer === d) _permissionAsk.value = null }
        }
        override fun onSteps(steps: List<AgentStep>) {
            setSteps(steps)
            val run = steps.lastOrNull()?.takeIf { it.status == StepStatus.RUNNING || it.status == StepStatus.ASKING }
            val t = run?.let { r -> AgentTools.byName(r.tool) }
            SystemStatus.update { it.copy(tool = t?.label ?: run?.tool, toolGroup = t?.group?.name, stepsDone = steps.count { st -> st.status == StepStatus.OK }) }
            steps.lastOrNull()?.let { st ->
                val label = AgentTools.all.firstOrNull { it.name == st.tool }?.label ?: st.tool
                when (st.status) {
                    StepStatus.ASKING -> act(I18n.s(R.string.chat_f2aede, label))
                    StepStatus.RUNNING -> act(I18n.s(R.string.chat_10df0b, label) + st.result.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    else -> {}
                }
            }
        }
        override fun onText(text: String) { _stream.value = StreamingText(reply.id, text) }
        override fun onAlwaysAllowCall(tool: AgentTool, call: ToolCall) {
            // 总是允许 on an MCP tool trusts its whole server.
            if (tool is McpTool) c.agent.mcp.allowAlways(tool.server.id) else onAlwaysAllow(tool)
        }
        override fun onAlwaysAllow(tool: AgentTool) {
            c.agent.settings.update { PermissionPolicy.afterDecision(it, tool, UserDecision.ALWAYS_ALLOW) }
        }
        override fun onWorkspaceChanged() = c.agent.changed()
        override fun onMemoryChanged() = c.agent.memoryChanged()
        override suspend fun beforeTool(tool: AgentTool, call: ToolCall) {
            // a fixed short phrase before the first tool (no tokens); tool blocks never reach TTS.
            if (voiceOut != null && toolCues++ == 0) voiceCue?.invoke(VoiceCues.before(tool.name, 0))
        }
    }
    // process_file: one fresh prompt per part (no history, no thinking), output up to
    // half the window; the part itself about 55% of that (translations grow).
    val chunkMax = (ctxTokens / 2).coerceIn(512, 4096)
    val chunkIn = minOf((chunkMax * 0.55).toInt(), ctxTokens - chunkMax - 400).coerceAtLeast(200)
    val chunkSampling = c.engine.samplingFor(true).copy(maxNewTokens = chunkMax, contextShift = false)
    val chunkLlm = ChunkLlm { system, user ->
        val sb = StringBuilder()
        val filter = StopTokenFilter()
        var stopped = false
        c.engine.generate(listOf(PromptMessage(PromptMessage.SYSTEM, system), PromptMessage(PromptMessage.USER, user)), chunkSampling, Requester.CHAT, engineOwner).collect { ev ->
            when (ev) {
                is GenEvent.Piece -> {
                    GenerationMonitor.tick()
                    if (stopped) return@collect
                    val clean = filter.push(ev.text)
                    if (filter.stopped) { stopped = true; c.engine.cancel(engineOwner) }
                    if (clean.isNotEmpty()) { timer.onText(clean); sb.append(clean) }
                }
                is GenEvent.Done -> { if (!stopped) sb.append(filter.flush()); lastStats = ev.stats }
            }
        }
        sb.toString()
    }
    agentRunning = true
    SystemStatus.update { it.copy(agentRunning = true, tool = null, toolGroup = null, stepsDone = 0) }
    var plan: ContextPlan? = null
    try {
        awaitingEngine = false
        freezeMemory(agentMode = true, ready = ready)
        prepareTurnMemory(agentMode = true, ready = ready)
        val folded = compactIfNeeded(agentMode = true, ready = ready, maxNew = maxNew, manual = false)
        if (folded > 0) notice.value = ContextText.compactNotice(folded)
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { planFor(conversation.value, ready, voice = false, agent = true, maxNewOverride = maxNew) }
        plan = p
        // card chat in 智能体 mode also gets the world book + post-history instructions on the newest
        // user turn (not saved to history; same lore budget as normal chat).
        val history = cardTurn(conversation.value, p.messages).filter { it.role != PromptMessage.SYSTEM }
        // stable system prompt (persona + tools + instructions + frozen memory); the plan goes on the new user turn.
        val base = listOf(PromptMessage(PromptMessage.SYSTEM, agentSystemFor(conversation.value, ready, tools))) +
            GuiIntent.withHint(AgentPrompt.withPlanNote(history, c.agent.plans.get(convId)), tools.any { it.name == "screen" })
                .let { if (voiceOut != null) VoiceStyle.withVoiceSystem(it) else it }
        val budget = (ctxTokens - maxNew - 32).coerceAtLeast(ctxTokens / 3)
        // continue from the previous 智能体 run's full prompt when nothing in between
        // changed, so the engine reuses its KV cache (fast first token) and the model still sees
        // the earlier tool calls and results.
        val runBase = continuedAgentBase(convId, reply.id, base, folded, budget) ?: base
        android.util.Log.i("IrisAgent", "base: ${if (runBase === base) "rebuilt" else "continued"} msgs=${runBase.size} prompt~${runBase.sumOf { com.iris.localai.util.TokenEstimator.message(it.content) }}tok")
        GuiNotes.source = { takeAgentNotes(convId)?.replace("用户补充：", "") }
        val loop = AgentLoop(
            model = model, tools = tools,
            permissionOf = { t -> c.agent.settings.current.permission(t) },
            env = c.agent.env(convId, setOfNotNull(conversation.value.messages.lastOrNull { it.role == Role.USER }?.id, reply.id),
                llm = chunkLlm, chunkTokens = chunkIn, enabledTools = tools),
            callbacks = callbacks, maxSteps = ag.maxSteps,
            contextBudget = budget,
            answerOf = { ThinkParser.parse(it, expectThinking = prefix.isNotEmpty()).answer },
            // 可取消：停止生成时中断工具线程（浏览器等待、网络读取会立即抛出），不再空等到 sleep 结束。
            runTool = { block -> kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { block() } },
            // 补充 typed during the run reach the model before its next step.
            pendingNotes = { takeAgentNotes(convId) },
            requiredTools = conversation.value.messages.lastOrNull { it.role == Role.USER }?.refs.orEmpty()
                .filter { it.kind == TurnRefKind.TOOL }.map { it.name },
            userTask = conversation.value.messages.lastOrNull { it.role == Role.USER }?.content.orEmpty().trim(),
        )
        agentTranscript = null
        val o = loop.run(runBase)
        outcome = o
        // only the final answer is spoken (no tool blocks, no reasoning). 2.1.9: most of it
        // already went out while it streamed; finish speaks what is still unsaid, once.
        voiceStream?.finish(ThinkParser.parse(o.finalText, expectThinking = prefix.isNotEmpty()).answer)
        android.util.Log.i(GEN_TAG, "agent: stop=${o.stop} steps=${o.steps.size} tools=${tools.size}" + (o.error?.let { " error=$it" } ?: ""))
        if (o.stop == AgentStop.MAX_STEPS) notice.value = I18n.s(R.string.chat_18bd6b, ag.maxSteps)
    } catch (e: kotlinx.coroutines.CancellationException) {
        cancelled = true
        throw e
    } catch (t: Throwable) {
        android.util.Log.w(GEN_TAG, "agent run failed", t)
        failure = t.message ?: t.javaClass.simpleName
    } finally {
        agentRunning = false
        SystemStatus.update { it.copy(agentRunning = false, tool = null, toolGroup = null) }
        _permissionAsk.value?.let { it.answer.cancel(); _permissionAsk.value = null }
        timer.finish()
        val o = outcome
        // 生图结果只在工具卡片里显示一次（不再追加到回复正文，避免同一张图出现两次）。
        val finalText = (o?.finalText ?: (_stream.value?.text ?: "")).let { if (disableThinking) com.iris.localai.engine.ThinkSwitch.hideThinking(it) else it }.trim()
        val finalSteps = steps.map { st ->
            if (st.status == StepStatus.RUNNING || st.status == StepStatus.ASKING)
                st.copy(status = StepStatus.ERROR, result = if (cancelled) I18n.s(R.string.chat_829778) else I18n.s(R.string.chat_cf0a3e))
            else st
        }
        val err = failure ?: o?.error
        conversation.update { conv ->
            conv.copy(messages = conv.messages.map {
                if (it.id != reply.id) it
                else it.copy(
                    content = finalText,
                    agentSteps = finalSteps,
                    stats = lastStats,
                    outputChars = timer.chars,
                    outputMs = timer.outputMs(),
                    totalMs = timer.totalMs(),
                    interrupted = cancelled,
                    error = err?.takeIf { finalText.isBlank() } ?: it.error.takeIf { _ -> reply.id in stalledIds },
                )
            })
        }
        if (err != null && finalText.isNotBlank()) notice.value = err
        agentTranscript = o?.transcript?.takeIf { it.isNotEmpty() && !cancelled && finalText.isNotBlank() }
            ?.let { AgentTranscript(convId, reply.id, finalText, it) }
        _stream.value = null
        if (streamingId.value == reply.id) streamingId.value = null
        if (background) GenerationMonitor.finish(convId, timer.chars, cancelled)
        if (!cancelled && voiceOut == null) _replyFinished.tryEmit(err == null)
        conversation.update { it.copy(updatedAt = System.currentTimeMillis()) }
        persist()
        if (!cancelled && voiceOut == null && err == null && finalText.isNotBlank()) suggestMemory(convId, reply.id)
        plan?.let { pl ->
            // the agent prompt (tool list + earlier tool results) is far bigger than the
            // chat plan; measure what was really sent so the meter does not undercount.
            val sent = o?.transcript?.sumOf { TokenEstimator.message(it.content) } ?: 0
            recordUsage(convId, pl, lastStats, finalText, ready, agentPromptTokens = sent)
        }
    }
}

/**
 * The previous run's transcript + the new user turn, when the new turn directly follows that
 * run's answer (same conversation, nothing edited or compacted, same system prompt) and it fits
 * [budget]; null = build the prompt from the chat history as usual.
 */
internal fun ChatViewModel.continuedAgentBase(convId: String, replyId: String, base: List<PromptMessage>, folded: Int, budget: Int): List<PromptMessage>? {
    val t = agentTranscript ?: return null
    if (t.convId != convId || folded > 0) return null
    val msgs = conversation.value.messages
    val i = msgs.indexOfFirst { it.id == replyId }
    if (i < 2) return null
    val user = msgs[i - 1].takeIf { it.role == Role.USER } ?: return null
    val prev = msgs[i - 2]
    if (prev.id != t.anchorId || prev.content.trim() != t.anchorText) return null
    val newUser = base.lastOrNull()?.takeIf { it.role == PromptMessage.USER } ?: return null
    if (user.content.isBlank()) return null
    if (t.messages.firstOrNull()?.content != base.firstOrNull()?.content) return null
    fun size(m: List<PromptMessage>) = m.sumOf { TokenEstimator.message(it.content) }
    val next = t.messages + newUser
    if (size(next) <= budget) return next
    // over budget → shorten the earlier tool outputs instead of rebuilding from the chat
    // history (which kept only ~50 chars of each result, so found facts were lost).
    val short = next.mapIndexed { idx, m ->
        if (idx < next.size - 1 && m.role == PromptMessage.USER && m.content.startsWith("<tool_response>"))
            m.copy(content = AgentLoop.shortenToolOutput(m.content, AgentLoop.EARLIER_KEEP))
        else m
    }
    return short.takeIf { size(it) <= budget }
}

/** The 补充 the running 智能体 has not read yet → one note for its next step (also kept on the request). */
internal fun ChatViewModel.takeAgentNotes(convId: String): String? {
    val notes = sendQueue.takeAgentNotes(convId)
    if (notes.isEmpty()) return null
    _queued.value = sendQueue.all
    val lastUser = conversation.value.messages.lastOrNull { it.role == Role.USER }
    if (lastUser != null && conversation.value.id == convId) {
        val add = notes.joinToString("\n") { "（补充）" + it.text.trim() }
        conversation.update { conv -> conv.copy(messages = conv.messages.map { if (it.id == lastUser.id) it.copy(content = it.content.trimEnd() + "\n\n" + add) else it }) }
        persist()
    }
    return SendIntent.agentNote(notes.map { it.text })
}

/** 能闭合一次工具调用的字符；流式片段不含这些时不必重扫整段。 */
private const val CALL_END_CHARS = ">)}]`\n"

package com.iris.localai.ui.chat


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
import com.iris.localai.util.StreamingThinkSplitter
import com.iris.localai.util.ThinkParser
import com.iris.localai.util.StopPolicy
import com.iris.localai.voice.Dictation
import com.iris.localai.voice.PcmPlayer
import com.iris.localai.voice.VoiceChatBridge
import com.iris.localai.voice.VoiceController
import com.iris.localai.util.SentenceSplitter
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
import com.iris.localai.ui.chat.ChatViewModel.Companion.RELOADING_STATUS
import com.iris.localai.ui.chat.ChatViewModel.Companion.LOADING_STATUS
import com.iris.localai.ui.chat.ChatViewModel.Companion.LOAD_FAILED
import com.iris.localai.ui.chat.ChatViewModel.Companion.STALL_ERROR
import com.iris.localai.ui.chat.ChatViewModel.Companion.PERSIST_MS

// 2.4.1: split out of ChatViewModel.kt unchanged (extensions on ChatViewModel) — reply pipeline: start / run a reply, its plan, summaries, the watchdog, titles, images.

/**
 * 1.10.12: the pictures of the newest user message go to the engine with that message, when the
 * model can see. Older pictures stay as a short text note.
 */
internal suspend fun ChatViewModel.withImages(messages: List<PromptMessage>): List<PromptMessage> {
    val ready = c.engine.state.value as? EngineState.Ready ?: return messages
    if (ready.details.supportsVision != true) return messages
    val lastUser = conversation.value.messages.lastOrNull { it.role == Role.USER } ?: return messages
    val originals = lastUser.attachments.filter { it.kind == com.iris.localai.data.AttachmentKind.IMAGE }.map { it.path }
    if (originals.isEmpty()) return messages
    // 2.0.2: hand the model a scaled copy (2.1.1: per engine; 2.1.7: 「识图清晰度」 384/512/1024, MNN capped at 512).
    val side = com.iris.localai.util.VisionPrompt.maxSide(ready.details.visionMaxSide, c.settings.current.visionSide)
    val imgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        originals.map { com.iris.localai.util.Attachments.visionCopy(it, side) }
    }
    val text = com.iris.localai.data.TurnRefs.userText(lastUser.content, com.iris.localai.util.Attachments.promptBlock(lastUser.attachments, true), lastUser.refs)
    val i = com.iris.localai.util.VisionPrompt.targetIndex(messages, text)
    if (i < 0) {
        android.util.Log.w(GEN_TAG, "withImages: newest user message not found in the prompt → ${imgs.size} picture(s) not sent")
        return messages
    }
    return messages.toMutableList().also { it[i] = it[i].copy(images = imgs) }
}

/**
 * 2.1.8 「一发送就卡住」: the reply [job] showed no sign of life past the watchdog's limit. The
 * bubble says so, the engine and the job are cancelled and the screen is free again; a job that
 * does not end within [ReplyWatchdog.DETACH_WAIT_MS] is detached (busy cleared anyway).
 */
internal suspend fun ChatViewModel.onReplyStalled(job: Job, verdict: ReplyWatchdog.Verdict, replyId: String?) {
    android.util.Log.w(GEN_TAG, "watchdog: ${verdict.phase} silent ${verdict.idleMs}ms → stopping the reply (${verdict.message})")
    if (replyId != null) {
        stalledIds.add(replyId)
        conversation.update { conv ->
            conv.copy(messages = conv.messages.map { if (it.id == replyId) it.copy(error = STALL_ERROR) else it })
        }
        persist()
    } else notice.value = STALL_ERROR
    stopAsked = true
    warmJob?.cancel()
    c.engine.cancel(engineOwner)
    job.cancel()
    if (replyId != null && streamingId.value == replyId) streamingId.value = null
    if (replyId != null && _stream.value?.id == replyId) _stream.value = null
    _replyStatus.value = null
    awaitingEngine = false
    act(null)
    val ended = kotlinx.coroutines.withTimeoutOrNull(ReplyWatchdog.DETACH_WAIT_MS) { job.join() } != null
    if (!ended && genJob === job) {
        android.util.Log.w(GEN_TAG, "watchdog: reply did not end ${ReplyWatchdog.DETACH_WAIT_MS}ms after cancel → detached")
        genJob = null
        agentRunning = false
        if (replyWatchdog != null) replyWatchdog = null
        adoptViewing()
        drainQueue()
    }
}

/** 2.2.14: after the first reply the loaded model writes a short title (first user line stays otherwise). */
internal suspend fun ChatViewModel.autoTitle() {
    val conv = conversation.value
    val users = conv.messages.filter { it.role == Role.USER }
    val answer = conv.messages.lastOrNull { it.role == Role.ASSISTANT && it.error == null && it.content.isNotBlank() }
    if (!com.iris.localai.util.title.TitleGen.due(users.size, answer != null, conv.titled)) return
    if (c.engine.state.value !is EngineState.Ready || viewingOther()) return
    conversation.update { it.copy(titled = true) }
    val out = StringBuilder()
    val ok = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(20_000) {
            c.engine.generate(
                com.iris.localai.util.title.TitleGen.messages(users.first().content, ThinkParser.parse(answer!!.content).answer),
                c.engine.samplingFor(disableThinking = true).copy(temperature = 0.2f, maxNewTokens = com.iris.localai.util.title.TitleGen.MAX_TOKENS, contextShift = false),
                Requester.CHAT, engineOwner,
            ).collect { ev -> if (ev is GenEvent.Piece) out.append(ev.text) }
            true
        } == true
    }.getOrDefault(false)
    val title = if (ok) com.iris.localai.util.title.TitleGen.clean(ThinkParser.parse(out.toString()).answer) else null
    if (conversation.value.id == conv.id) {
        if (title != null) conversation.update { it.copy(title = title) }
        persist()
    } else if (title != null) runCatching { c.conversations.rename(conv.id, title) }
}

/** Fits [conv] into the loaded model's window with the conversation's (or global) strategy. */
internal fun ChatViewModel.planFor(conv: Conversation, ready: EngineState.Ready?, voice: Boolean, thinkingBudget: Int = 0, agent: Boolean = false, maxNewOverride: Int? = null): com.iris.localai.util.ContextPlan {
    val s = c.settings.current
    val eff = c.engine.effective(ready?.model).let { e -> if (maxNewOverride != null) e.copy(maxNewTokens = maxNewOverride) else e }
    // 2.1.9: in a voice call the instruction is appended to the system prompt at send time
    // (VoiceStyle.withVoiceSystem), so the history stays as stored and the KV prefix holds.
    val baseSystem = withLang(cardSystem(conv, eff.systemPrompt, ready).trim())
    // 1.10.0: 智能体 history carries earlier tool steps (rolled up); a compaction summary replaces
    // the folded messages; the frozen long-term memory joins the system prompt.
    val active = com.iris.localai.context.ContextAssembly.active(conv.messages, conv.contextStartAfterId, withTools = agent, vision = ready?.details?.supportsVision == true)
    val compaction = com.iris.localai.context.Compactor.applicable(conv.compaction, active)
    val memory = com.iris.localai.context.ContextAssembly.memoryBlock(conv, memoryInjectFor(agent && !voice), contextTokensFor(ready), c.agent.memorySettings.current.epoch)
    val system = com.iris.localai.context.ContextAssembly.system(baseSystem, memory)
    // 2.3: every message keeps the knowledge block it was answered with, so the history the
    // engine sees next turn is the one it already holds (KV-cache reuse).
    val msgs = com.iris.localai.context.ContextAssembly.forPlanner(active, compaction, withTools = agent)
        // 2.2.9: plus each message's 【相关记忆】 (memory FACTs), both modes.
        .let { m -> com.iris.localai.knowledge.KbChat.attachAll(m, if (agent || !s.knowledgeInChat) memOnly() else turnBlocks(conv, ready?.details?.contextLength?.takeIf { it > 0 } ?: eff.contextLength)) }
    return com.iris.localai.util.ContextPlanner.plan(
        com.iris.localai.util.ContextRequest(
            system = system,
            messages = msgs,
            // 2.3: the compaction summary leads the history (system prompt stays cacheable).
            // 2.1.9: 智能体 with recall → the summary says the folded originals can be searched.
            lead = com.iris.localai.context.ContextAssembly.lead(compaction, recall = agent && c.agent.history != null && c.agent.settings.current.isEnabled(com.iris.localai.agent.RecallTool)),
            strategy = com.iris.localai.data.ContextStrategy.of(conv.contextStrategy ?: s.contextStrategy),
            contextTokens = ready?.details?.contextLength?.takeIf { it > 0 } ?: eff.contextLength,
            // 1.7.8: room for the reasoning budget too (the planner caps the reserve at half the window).
            maxNewTokens = eff.maxNewTokens + thinkingBudget.coerceAtLeast(0),
            keepTurns = s.contextKeepTurns,
            summary = conv.summary,
            summaryUpToId = conv.summaryUpToId,
            summaryTriggerPercent = s.summaryTriggerPercent,
            // Already applied by ContextAssembly.active (1.10.0).
            startAfterId = null,
            engineCanShift = com.iris.localai.engine.BackendCapabilities.contextShiftUnsupported(ready?.model?.format) == null,
        ),
    )
}

/**
 * 自动摘要: folds [plan.toSummarize] (plus any older summary) into one paragraph with the loaded
 * model and stores it on the conversation. Returns false when it failed (the reply then goes
 * ahead with plain token-budget trimming).
 */
internal suspend fun ChatViewModel.summarize(plan: com.iris.localai.util.ContextPlan): Boolean {
    if (plan.toSummarize.isEmpty()) return true
    summarizing.value = true
    act(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_42e4d5))
    try {
        val conv = conversation.value
        val cfg = c.engine.effective().let { it.copy(temperature = 0.3f, maxNewTokens = 512, repeatPenalty = 1.05f) }
        val out = StringBuilder()
        var ok = true
        val summaryPrompt = com.iris.localai.util.ContextPlanner.summaryPrompt(conv.summary, plan.toSummarize)
        replyWatchdog?.prompt(summaryPrompt.sumOf { com.iris.localai.util.TokenEstimator.message(it.content) })
        c.engine.generate(
            summaryPrompt,
            c.engine.samplingFor(disableThinking = true, cfg = cfg),
            Requester.CHAT,
            engineOwner,
        ).collect { ev ->
            when (ev) {
                is GenEvent.Piece -> { replyWatchdog?.touch(); out.append(ev.text) }
                is GenEvent.Done -> if (ev.reason == StopReason.ERROR || ev.reason == StopReason.CANCELLED) ok = false
            }
        }
        val text = ThinkParser.parse(out.toString()).answer.trim()
        if (!ok || text.isBlank()) return false
        conversation.update { it.copy(summary = text, summaryUpToId = plan.toSummarize.last().id) }
        persist()
        return true
    } finally {
        summarizing.value = false
    }
}

/**
 * Streams a reply into the conversation. UI updates are coalesced to ~30 fps: re-laying out
 * Markdown for every single token is the biggest avoidable battery cost of a chat app.
 */
internal fun ChatViewModel.startReply(requester: Requester, onAnswerDelta: ((String) -> Unit)? = null): Job {
    stopAsked = false
    // 2.0.x: regenerate — the new bubble carries the previous reply's versions.
    val base = nextReplyBase.also { nextReplyBase = null }
    // 2.1.8: a reply that shows no sign of life is ended with a reason (ReplyWatchdog).
    val watchdog = ReplyWatchdog()
    replyWatchdog = watchdog
    val bubbleId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val st0 = c.engine.state.value
    val modelPending = st0 !is EngineState.Ready
    if (modelPending) watchdog.waitingForModel()
    // 2.1.8: no model ready (loading, or loaded on demand): the bubble shows right away with
    // 「正在加载模型…」 so 停止 is there. Nothing to load at all keeps the old notice path.
    val showLoading = modelPending && (st0 is EngineState.Loading || c.engine.readyModel != null || c.engine.defaultModel() != null)
    val job = viewModelScope.launch {
        // 1.7.7: the reply bubble exists as soon as there is something to show: right away when a
        // tier reload makes the request wait (with 「正在重载模型…」), otherwise once the model is ready.
        var bubble: ChatMessage? = null
        fun bubble(): ChatMessage = bubble ?: ChatMessage(
            role = Role.ASSISTANT, content = "",
            modelName = c.engine.readyModel?.displayName ?: (c.engine.state.value as? EngineState.Loading)?.model?.displayName,
        ).let { fresh -> if (base != null) com.iris.localai.data.MessageVersions.asNewVersionOf(base, fresh) else fresh }.also { r ->
            bubble = r
            conversation.update { it.copy(messages = it.messages + r) }
            streamingId.value = r.id
            bubbleId.set(r.id)
        }
        awaitingEngine = true
        var started = false
        try {
            if (showLoading) { bubble(); _replyStatus.value = LOADING_STATUS }
            // One registered request from here to the end of the reply: a tier reload cannot slip
            // in between ensureLoaded and generate any more; one that is running is waited for.
            c.engine.request(onWaiting = { waiting ->
                if (waiting) { bubble(); watchdog.waitingForModel(); _replyStatus.value = RELOADING_STATUS } else _replyStatus.value = null
            }) {
                val loaded = c.engine.ensureLoaded()
                if (_replyStatus.value == LOADING_STATUS) _replyStatus.value = null
                if (!loaded) {
                    val failed = (c.engine.state.value as? EngineState.Failed)?.message
                    val b = bubble
                    if (b != null) {
                        // The user saw a bubble waiting: end it with the reason instead of leaving it.
                        conversation.update { conv ->
                            conv.copy(messages = conv.messages.map {
                                if (it.id != b.id) it
                                else if (modelPending) it.copy(error = failed?.let { m -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_b9fef6, LOAD_FAILED, m) } ?: LOAD_FAILED)
                                else it.copy(error = failed?.let { m -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_615c32, m) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5d0468))
                            })
                        }
                        if (streamingId.value == b.id) streamingId.value = null
                        persist()
                    } else {
                        notice.value = failed?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_fedfea, it) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1ae88d)
                    }
                    return@request
                }
                started = true
                watchdog.prompt()
                // 1.9.0: 智能体 mode. 2.1.7: voice uses tools too — 智能体 on, or a spoken task (VoiceRouting).
                val agentOn = c.agent.settings.current.agentMode
                val viaAgent = when (requester) {
                    // 2.1.9: 「帮我打开设置把亮度调最高」 in 对话 mode runs once through the 智能体 (`screen`).
                    Requester.CHAT -> agentOn || guiTurn(conversation.value.messages.lastOrNull { it.role == Role.USER }?.content.orEmpty())
                    Requester.VOICE -> com.iris.localai.voice.VoiceRouting.useAgent(
                        conversation.value.messages.lastOrNull { it.role == Role.USER }?.content.orEmpty(), agentOn)
                    else -> false
                }
                if (viaAgent) runAgentReply(bubble(), voiceOut = if (requester == Requester.VOICE) (onAnswerDelta ?: {}) else null)
                else runReply(requester, bubble(), onAnswerDelta)
            }
        } finally {
            awaitingEngine = false
            _replyStatus.value = null
            act(null)
            // Cancelled while waiting for the model: drop the empty bubble instead of leaving it.
            val b = bubble
            if (!started && b != null && streamingId.value == b.id) {
                // 2.0.x: a regenerated reply that never started gives the previous version back.
                conversation.update { conv ->
                    conv.copy(messages = conv.messages.mapNotNull {
                        if (it.id == b.id && it.content.isEmpty() && it.error == null) com.iris.localai.data.MessageVersions.dropActive(it) else it
                    })
                }
                streamingId.value = null
                persist()
            }
            // 2.0.x: no bubble at all (model failed to load / cancelled early): the old reply goes back.
            if (b == null && base != null && conversation.value.messages.none { it.id == base.id }) {
                conversation.update { conv -> conv.copy(messages = conv.messages + base) }
                persist()
            }
        }
    }
    genJob = job
    job.invokeOnCompletion {
        if (replyWatchdog === watchdog) replyWatchdog = null
        viewModelScope.launch {
            if (genJob === job || genJob?.isActive != true) adoptViewing()
            // 2.1.7: answer what was queued while this reply ran.
            if (genJob === job) drainQueue()
            if (genJob?.isActive != true) autoTitle()
        }
    }
    // 2.1.8: watch the reply until it ends; load progress counts as a sign of life.
    viewModelScope.launch {
        val loadTouch = launch { c.engine.state.collect { if (it is EngineState.Loading) watchdog.touch() } }
        val verdict = try { watchdog.watch(job) } finally { loadTouch.cancel() }
        if (verdict != null) onReplyStalled(job, verdict, bubbleId.get())
    }
    return job
}

/**
 * Streams a reply into [reply] (already in the conversation and marked streaming). UI updates
 * are coalesced to ~30 fps: re-laying out Markdown for every single token is the biggest
 * avoidable battery cost of a chat app.
 */
internal suspend fun ChatViewModel.runReply(requester: Requester, reply: ChatMessage, onAnswerDelta: ((String) -> Unit)?) {
        val s = c.settings.current
        val details = (c.engine.state.value as? EngineState.Ready)?.details
        val disableThinking = requester == Requester.VOICE || !c.engine.effective().thinking
        streamingId.value = reply.id

        val raw = StringBuilder()
        // Real timing of this reply, whatever the engine: first output → end (also when stopped).
        val timer = com.iris.localai.util.ReplyTimer().also { liveTimer = it }
        val splitter = StreamingThinkSplitter(startInThinking = details?.thinkingPrefilled == true && !disableThinking)
        val prefix = if (details?.thinkingPrefilled == true && !disableThinking) "<think>" else ""
        val stopFilter = com.iris.localai.util.StopTokenFilter()
        var stopRequested = false
        var lastPublish = 0L
        var thinkStart = 0L
        var thinkMs = 0L
        var result: GenEvent.Done? = null
        /** 1.7.7: shown under a reply that has reasoning but no answer (never silent). */
        var thinkOnlyNote: String? = null

        val background = requester == Requester.CHAT && s.backgroundGeneration
        val convId = conversation.value.id
        if (background) {
            com.iris.localai.service.GenerationMonitor.begin(convId, conversation.value.title, stop = { stop() })
            com.iris.localai.service.GenerationService.start(getApplication())
        }
        var lastPersist = System.currentTimeMillis()

        fun publish(done: Boolean) {
            val text = prefix + raw.toString()
            if (!done) {
                _stream.value = StreamingText(reply.id, text)
                if (background) com.iris.localai.service.GenerationMonitor.progress(timer.chars)
                // Saved every few seconds, so a reply survives the process being killed in the
                // background (it reloads as an interrupted reply with the text written so far).
                val now = System.currentTimeMillis()
                if (now - lastPersist >= PERSIST_MS) {
                    lastPersist = now
                    val snap = conversation.value.let { conv ->
                        conv.copy(messages = conv.messages.map { if (it.id == reply.id) it.copy(content = text, interrupted = true) else it })
                    }
                    save(snap)
                }
                return
            }
            conversation.update { conv ->
                conv.copy(messages = conv.messages.map {
                    if (it.id != reply.id) it
                    else it.copy(
                        // 2.1.7: a spoken reply loses its boilerplate opener / closer in the bubble too.
                        content = if (requester == Requester.VOICE && !text.contains("<think")) com.iris.localai.voice.VoiceStyle.polish(text) else text.trim(),
                        thinkingMs = thinkMs,
                        stats = result?.stats,
                        outputChars = timer.chars,
                        outputMs = if (done) timer.outputMs() else 0L,
                        totalMs = if (done) timer.totalMs() else 0L,
                        interrupted = result?.reason == StopReason.CANCELLED,
                        error = thinkOnlyNote ?: result?.takeIf { f -> f.reason == StopReason.ERROR || f.reason == StopReason.CONTEXT_FULL }?.error
                            ?.takeIf { raw.isBlank() }
                            ?: it.error.takeIf { _ -> reply.id in stalledIds },
                    )
                })
            }
            _stream.value = null
        }

        val ready = c.engine.state.value as? EngineState.Ready
        // 1.7.8: planned (and summarised) inside the try below. Before, a stop during 自动摘要
        // threw out of here past the finally: the reply stayed "streaming" for good (no more
        // sends possible) and the background-generation service + wakelock never stopped.
        var plan: com.iris.localai.util.ContextPlan? = null
        // 1.7.8: reasoning budget on top of the answer's limit (0 = shared limit, ≤ 1.7.7).
        val eff0 = c.engine.effective()
        val budget = if (!disableThinking && details?.supportsThinking == true) s.thinkingBudget else 0
        var thinkBudgetHit = false

        /**
         * One engine call into this reply. [answerOnly] (the 1.7.7 continuation): reasoning the
         * model may still produce goes nowhere, only answer text is appended.
         */
        suspend fun pass(noThinking: Boolean, answerOnly: Boolean) {
            val passSplitter = if (answerOnly) StreamingThinkSplitter(startInThinking = details?.thinkingPrefilled == true && !noThinking) else splitter
            val passStop = if (answerOnly) com.iris.localai.util.StopTokenFilter() else stopFilter
            var passStopRequested = false
            // 1.7.8: only the first pass (reasoning on) is metered; the continuation has thinking off.
            val meter = if (!answerOnly && !noThinking && budget > 0)
                com.iris.localai.util.ThinkingBudget(budget, eff0.maxNewTokens, details?.thinkingPrefilled == true) else null
            var meterStop: com.iris.localai.util.ThinkingBudget.Verdict? = null
            fun accept(clean: String) {
                if (clean.isEmpty() || stopAsked) return
                passSplitter.push(clean).forEach { p ->
                    act(if (p.thinking) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_dc269a) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2ee53b))
                    if (p.thinking) {
                        if (!answerOnly) { raw.append(p.text); if (thinkStart == 0L) thinkStart = System.currentTimeMillis() }
                    } else {
                        if (thinkStart != 0L && thinkMs == 0L) thinkMs = System.currentTimeMillis() - thinkStart
                        raw.append(p.text)
                        onAnswerDelta?.invoke(p.text)
                    }
                }
            }
            val p = plan!!
            val sampling = c.engine.samplingFor(noThinking, contextShift = p.contextShift, keepTokens = p.keepTokens)
            promptLog.record(reply.id, if (requester == Requester.VOICE) com.iris.localai.voice.VoiceStyle.withVoiceSystem(p.messages) else p.messages)
            replyWatchdog?.prompt(p.estimatedTokens)
            act(if (answerOnly) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_dd9f3c) else if (p.messages.lastOrNull { it.role == PromptMessage.USER }?.let { pickImages(it) } == true) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5ab804) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8a3813))
            c.engine.generate(
                withImages(cardTurn(conversation.value, if (requester == Requester.VOICE) com.iris.localai.voice.VoiceStyle.withVoiceSystem(p.messages) else p.messages)),
                if (meter != null) sampling.copy(maxNewTokens = meter.engineLimit()) else sampling,
                requester,
                engineOwner,
            ).collect { ev ->
                when (ev) {
                    is GenEvent.Piece -> {
                        com.iris.localai.service.GenerationMonitor.tick()
                        replyWatchdog?.output()
                        if (meter != null && meterStop == null) {
                            val v = meter.onPiece(ev.text)
                            if (v != com.iris.localai.util.ThinkingBudget.Verdict.NONE) {
                                meterStop = v
                                android.util.Log.i(GEN_TAG, "budget: $v (thinking≈${meter.thinkingPieces}/${meter.budget}, answer≈${meter.answerPieces}/${meter.answerLimit}) → stopping this pass")
                                c.engine.cancel(engineOwner)
                            }
                        }
                        val clean = passStop.push(ev.text)
                        if (passStop.stopped && !passStopRequested) { passStopRequested = true; c.engine.cancel(engineOwner) }
                        // 1.13.0: text arriving after 停止 is dropped here too.
                        if (clean.isEmpty() || stopAsked) return@collect
                        timer.onText(clean)
                        if (answerOnly) accept(clean) else {
                            // The stored text keeps the tags exactly as streamed (ThinkParser reads them).
                            raw.append(clean)
                            passSplitter.push(clean).forEach { p ->
                                act(if (p.thinking) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_dc269a) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2ee53b))
                                if (p.thinking) {
                                    if (thinkStart == 0L) thinkStart = System.currentTimeMillis()
                                } else {
                                    if (thinkStart != 0L && thinkMs == 0L) thinkMs = System.currentTimeMillis() - thinkStart
                                    onAnswerDelta?.invoke(p.text)
                                }
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= FRAME_MS) { lastPublish = now; publish(false) }
                    }
                    is GenEvent.Done -> {
                        passStop.flush().takeIf { it.isNotEmpty() }?.let { tail ->
                            timer.onText(tail)
                            if (answerOnly) accept(tail) else {
                                raw.append(tail)
                                passSplitter.push(tail).forEach { if (!it.thinking) onAnswerDelta?.invoke(it.text) }
                            }
                        }
                        passSplitter.flush().forEach { p ->
                            if (answerOnly) { if (!p.thinking) { raw.append(p.text); onAnswerDelta?.invoke(p.text) } }
                            else if (!p.thinking) onAnswerDelta?.invoke(p.text)
                        }
                        if (thinkStart != 0L && thinkMs == 0L) thinkMs = System.currentTimeMillis() - thinkStart
                        result = when {
                            passStopRequested && ev.reason == StopReason.CANCELLED -> ev.copy(reason = StopReason.END)
                            // 1.7.8: our own budget stop is a length limit, not a user stop.
                            meterStop != null && ev.reason == StopReason.CANCELLED -> {
                                if (meterStop == com.iris.localai.util.ThinkingBudget.Verdict.THINKING_EXHAUSTED) thinkBudgetHit = true
                                ev.copy(reason = StopReason.MAX_TOKENS)
                            }
                            else -> ev
                        }
                        // 1.7.8: a reply cut off by a full context says so (with what to do), instead
                        // of looking like an ordinary length limit. Shown as a notice: an error on the
                        // message would also drop the partial answer from the history.
                        if (ev.reason == StopReason.CONTEXT_FULL && raw.isNotBlank()) {
                            notice.value = ev.error ?: com.iris.localai.engine.LengthRule.contextFullMessage(ev.stats?.generatedTokens ?: 0, eff0.maxNewTokens)
                        }
                        if (ev.stats?.droppedMessages ?: 0 > 0) {
                            notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6c919b, ev.stats!!.droppedMessages)
                        }
                        recordUsage(convId, p, ev.stats, raw.toString(), ready)
                    }
                }
            }
        }

        try {
            plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { prepareKnowledge(requester, reply.id); if (requester != Requester.API) prepareTurnMemory(agentMode = false, ready = ready); planFor(conversation.value, ready, voice = requester == Requester.VOICE, thinkingBudget = budget) }
            // 1.10.0: frozen memory + deterministic compaction (typed chat only); planned again when either changed.
            if (requester == Requester.CHAT) {
                val hadSnapshot = conversation.value.memorySnapshot
                freezeMemory(agentMode = false, ready = ready)
                val n = compactIfNeeded(agentMode = false, ready = ready, maxNew = eff0.maxNewTokens + budget, manual = false)
                if (n > 0) notice.value = ContextText.compactNotice(n)
                if (n > 0 || hadSnapshot != conversation.value.memorySnapshot) plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { planFor(conversation.value, ready, voice = false, thinkingBudget = budget) }
            }
            if (plan.toSummarize.isNotEmpty() && requester == Requester.CHAT) {
                if (summarize(plan)) plan = planFor(conversation.value, ready, voice = false, thinkingBudget = budget)
                else notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_469284)
            }
            awaitingEngine = false
            pass(disableThinking, answerOnly = false)
            // 1.7.7: reasoning but no answer (EOS right after </think>, or the reasoning used up the
            // reply limit) → write the answer once more with thinking off; still nothing → say so.
            val maxNew = c.engine.effective().maxNewTokens
            val first = result
            if (!disableThinking && first != null) {
                val parts = ThinkParser.parse(prefix + raw.toString(), expectThinking = prefix.isNotEmpty())
                val v = com.iris.localai.util.ThinkOnlyPolicy.decide(parts, first.reason, first.error, alreadyContinued = false, maxNewTokens = maxNew)
                when (v.action) {
                    com.iris.localai.util.ThinkOnlyPolicy.Action.CONTINUE -> {
                        if (thinkBudgetHit) android.util.Log.i(GEN_TAG, "thinking budget ($budget) used up → answer written with thinking off")
                        android.util.Log.i(GEN_TAG, "think-only reply (reason=${com.iris.localai.util.ReplyAccounting.reasonName(first.reason)}) → continuing once with thinking off")
                        raw.append(com.iris.localai.util.ThinkOnlyPolicy.bridge(parts))
                        publish(false)
                        pass(noThinking = true, answerOnly = true)
                        val again = ThinkParser.parse(prefix + raw.toString(), expectThinking = prefix.isNotEmpty())
                        val r2 = result ?: first
                        val v2 = com.iris.localai.util.ThinkOnlyPolicy.decide(again, r2.reason, r2.error, alreadyContinued = true, maxNewTokens = maxNew)
                        thinkOnlyNote = v2.message
                        android.util.Log.i(GEN_TAG, "think-only continuation: reason=${com.iris.localai.util.ReplyAccounting.reasonName(r2.reason)} answerChars=${again.answer.length}" +
                            (v2.message?.let { " → notice: $it" } ?: ""))
                    }
                    com.iris.localai.util.ThinkOnlyPolicy.Action.NOTICE -> {
                        thinkOnlyNote = v.message
                        android.util.Log.i(GEN_TAG, "think-only reply → notice: ${v.message}")
                    }
                    com.iris.localai.util.ThinkOnlyPolicy.Action.NONE -> Unit
                }
            }
        } finally {
            if (result == null) result = GenEvent.Done(StopReason.CANCELLED, null)
            timer.finish()
            publish(true)
            if (streamingId.value == reply.id) streamingId.value = null
            if (background) com.iris.localai.service.GenerationMonitor.finish(convId, timer.chars, result?.reason == StopReason.CANCELLED)
            result?.takeIf { requester == Requester.CHAT && it.reason != StopReason.CANCELLED }
                ?.let { _replyFinished.tryEmit(it.reason != StopReason.ERROR && it.error == null) }
            conversation.update { it.copy(updatedAt = System.currentTimeMillis()) }
            persist()
            // 2.2.9: 「记住？」 after the reply is complete (never before the first token).
            if (requester == Requester.CHAT && raw.isNotBlank() && result?.reason != StopReason.CANCELLED && result?.reason != StopReason.ERROR) suggestMemory(convId, reply.id)
            if (requester == Requester.CHAT && c.settings.current.autoSpeak && raw.isNotBlank()) {
                speak(reply.id)
            }
        }
}

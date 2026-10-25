package com.iris.localai.ui.chat


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the chat screen renders, in one immutable snapshot. */
data class ChatUiState(
    val conversation: Conversation = Conversation(),
    /** id of the assistant message currently streaming, if any. */
    val streamingId: String? = null,
    val engine: EngineState = EngineState.Idle,
    val deepThinking: Boolean = true,
    val notice: String? = null,
    /** 1.7.1: context in use (tokens) and the loaded model's window; 0 = unknown. */
    val context: ContextUsage = ContextUsage(),
) {
    val isGenerating: Boolean get() = streamingId != null
    val readyModel: LocalModel? get() = (engine as? EngineState.Ready)?.model
    val supportsThinking: Boolean get() = (engine as? EngineState.Ready)?.details?.supportsThinking == true
}

/** Context meter for the current conversation. */
data class ContextUsage(
    val used: Int = 0,
    val limit: Int = 0,
    /** Measured by the engine (true) or estimated from the text (false). */
    val exact: Boolean = false,
    val strategy: com.iris.localai.data.ContextStrategy = com.iris.localai.data.ContextStrategy.TOKEN_BUDGET,
    /** The conversation overrides the global strategy. */
    val overridden: Boolean = false,
    val hasBreak: Boolean = false,
    val hasSummary: Boolean = false,
    val summarizing: Boolean = false,
    /** Why KV_SHIFT is not available for the loaded engine (null = available). */
    val shiftUnsupported: String? = null,
    /** 1.10.0: messages folded by deterministic compaction (0 = none), and how many times. */
    val compactedMessages: Int = 0,
    val compactions: Int = 0,
    /** 1.10.0: long-term memory is part of this conversation's prompt. */
    val memoryInjected: Boolean = false,
) {
    val fraction: Float get() = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val nearFull: Boolean get() = limit > 0 && used >= limit * NEAR_FULL
    companion object { const val NEAR_FULL = 0.85f }
}

/** Text of the reply being streamed, exposed apart from the conversation so only that message recomposes. */
data class StreamingText(val id: String, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app), VoiceChatBridge {

    private val c = (app as IrisApp).container
    /**
     * 1.14.3: owner of every generation this screen starts (replies, voice, agent turns, summaries,
     * process_file parts, warm-up). 停止 and the stop filters cancel only these, never an API
     * client's generation that happens to hold the engine.
     */
    private val engineOwner = com.iris.localai.engine.GenOwner("chat")
    val settings = c.settings.settings
    val summaries = c.conversations.summaries
    val models = c.models.models
    val engineBusy = c.engine.busy

    /** The conversation a reply is written into (also the one shown, unless [viewing] is set). */
    private val conversation = MutableStateFlow(Conversation())
    /**
     * 1.12.3: switching conversations no longer stops a running reply/agent run. It keeps writing
     * into [conversation] while the user looks at [viewing]; when it ends, [viewing] becomes the
     * conversation again. null = the user is looking at [conversation].
     */
    private val viewing = MutableStateFlow<Conversation?>(null)
    private val shown = combine(conversation, viewing) { w, v -> v ?: w }
    /** Id of the conversation running in the background while another is shown (null = none). */
    val backgroundRunId: StateFlow<String?> = combine(viewing, conversation) { v, w -> if (v != null) w.id else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private fun viewingOther(): Boolean {
        if (viewing.value == null) return false
        notice.value = "「" + conversation.value.title.ifBlank { "另一个对话" } + "」还在运行，完成或停止后才能在这里操作"
        return true
    }
    private val streamingId = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    fun showNotice(text: String) { notice.value = text }

    /** Last measured usage per conversation id (engine numbers after a reply). */
    private val measured = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val summarizing = MutableStateFlow(false)
    private val nearFullWarned = HashSet<String>()

    private val base = combine(shown, streamingId, c.engine.state, settings, notice) { conv, sid, eng, s, n ->
        // 1.12.3: the reply streaming in the background is not this screen's.
        ChatUiState(conv, sid?.takeIf { viewing.value == null }, eng, s.deepThinking, n)
    }

    // 1.10.0: the context readout also follows the mode switch and the memory settings.
    private val base2 = combine(base, c.agent.settings.settings, c.agent.memorySettings.settings, c.agent.memoryRevision) { st, _, _, _ -> st }

    val state: StateFlow<ChatUiState> = combine(base2, c.modelConfigs.configs, measured, summarizing) { st, cfgs, meas, summ ->
        val ready = st.engine as? EngineState.Ready
        val s = c.settings.current
        val eff = com.iris.localai.data.EffectiveModelConfig.resolve(s, ready?.model?.format, ready?.model?.id?.let { cfgs[it] })
        st.copy(deepThinking = eff.thinking, context = usageFor(st.conversation, ready, meas[st.conversation.id], summ))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /** 1.13.1: the running reply's timer — the speed overlay reads its visible-character count. */
    @Volatile var liveTimer: com.iris.localai.util.ReplyTimer? = null
        private set

    @Volatile private var usageMemo: Pair<List<Any?>, Int>? = null

    private fun usageFor(conv: Conversation, ready: EngineState.Ready?, measuredTokens: Int?, summ: Boolean): ContextUsage {
        val s = c.settings.current
        val strategy = com.iris.localai.data.ContextStrategy.of(conv.contextStrategy ?: s.contextStrategy)
        val limit = ready?.details?.contextLength ?: 0
        val agentMode = c.agent.settings.current.agentMode
        // 1.13.1: memoized — this planned the whole conversation on the main thread at every state
        // change (streamingId, engine busy, notices…), which made the start of a reply stutter.
        val used = measuredTokens ?: if (conv.messages.isEmpty()) 0 else {
            val key = listOf(conv, ready, agentMode, s, c.agent.memorySettings.current.epoch)
            usageMemo?.takeIf { it.first == key }?.second
                ?: planFor(conv, ready, voice = false, agent = agentMode).estimatedTokens.also { usageMemo = key to it }
        }
        val withTools = agentMode
        val compacted = com.iris.localai.context.Compactor.applicable(
            conv.compaction, com.iris.localai.context.ContextAssembly.active(conv.messages, conv.contextStartAfterId, withTools, ready?.details?.supportsVision == true),
        )
        return ContextUsage(
            used = used, limit = limit, exact = measuredTokens != null, strategy = strategy,
            overridden = conv.contextStrategy != null, hasBreak = conv.contextStartAfterId != null,
            hasSummary = conv.summary.isNotBlank(), summarizing = summ,
            shiftUnsupported = com.iris.localai.engine.BackendCapabilities.contextShiftUnsupported(ready?.model?.format),
            compactedMessages = compacted?.messages ?: 0,
            compactions = compacted?.times ?: 0,
            memoryInjected = memoryInjectFor(agentMode) && !conv.memorySnapshot.isNullOrBlank() && conv.memoryEpoch == c.agent.memorySettings.current.epoch,
        )
    }

    private var genJob: Job? = null

    /**
     * While a reply streams, its text lives here (not in [conversation]): updating the conversation
     * 30× a second recomposed the whole chat screen and every message, which is what made long
     * thinking output stutter. The conversation gets the text once, when the reply is finished.
     */
    private val _stream = MutableStateFlow<StreamingText?>(null)
    val stream: StateFlow<StreamingText?> = _stream.asStateFlow()

    /**
     * 1.7.7: status of the reply being prepared (shown in its bubble while it has no text yet), e.g.
     * 「正在重载模型…」 while the request waits for a resource-tier reload. null = none.
     */
    /** 1.10.9: what the model is doing right now, for the small indicator above the composer. */
    private val _activity = MutableStateFlow<com.iris.localai.util.Activity?>(null)
    val activity: StateFlow<com.iris.localai.util.Activity?> = _activity.asStateFlow()
    private fun act(label: String?) { if (_activity.value?.label != label) _activity.value = label?.let { com.iris.localai.util.Activity(it) } }

    /** 1.10.9: 「取消加载」. */
    /**
     * 2.1.7 弹窗可取消: abandon the running load (the engine releases it when the native call
     * returns) and cancel our own load coroutine, so a load queued behind it never starts either.
     */
    fun cancelLoad() {
        c.engine.cancelLoad()
        loadJob?.cancel(); loadJob = null
    }

    private val _replyStatus = MutableStateFlow<String?>(null)
    val replyStatus: StateFlow<String?> = _replyStatus.asStateFlow()

    /** 1.7.7: true until the reply's generate call is running (stop then cancels the job itself). */
    @Volatile private var awaitingEngine = false

    /** Bumped when a notification asks to show a conversation: navigation returns to the chat. */
    private val _showChat = MutableStateFlow(0)
    val showChat: StateFlow<Int> = _showChat.asStateFlow()
    fun openFromNotification(id: String) {
        _showChat.value++
        open(id)
    }

    // 1.10.13: did the system pause the reply while IRIS was in the background?
    private var awayAt = 0L
    private var awayPieces = 0L
    private val awayObserver = androidx.lifecycle.LifecycleEventObserver { _, e ->
        val mon = com.iris.localai.service.GenerationMonitor
        when (e) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> { awayAt = System.currentTimeMillis(); awayPieces = mon.pieces }
            androidx.lifecycle.Lifecycle.Event.ON_START -> if (awayAt > 0) {
                val away = System.currentTimeMillis() - awayAt
                val produced = mon.pieces - awayPieces
                val running = streamingId.value != null
                android.util.Log.i(GEN_TAG, "back from background after ${away}ms: pieces=$produced running=$running exempt=${com.iris.localai.util.BackgroundGuard.isExempt(getApplication())}")
                if (com.iris.localai.util.BackgroundGuard.stalled(away, produced, running)) {
                    notice.value = com.iris.localai.util.BackgroundGuard.STALL_NOTICE
                }
                awayAt = 0L
            }
            else -> Unit
        }
    }
    init { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(awayObserver) }

    // ---- voice ----
    val voice = VoiceController(viewModelScope, c.recognizer, c.synthesizer, c.settings, this)
    val dictation = Dictation(viewModelScope, c.recognizer)
    val voiceStore = c.voiceStore
    /**
     * 2.1.0: a chat reply finished (true) or failed (false); stopped replies emit nothing. Not
     * replayed: the UI collects it only while resumed, so a reply finishing in the background
     * gives no feedback.
     */
    private val _replyFinished = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val replyFinished: kotlinx.coroutines.flow.SharedFlow<Boolean> = _replyFinished
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()
    private var speakJob: Job? = null
    private var speakPlayer: PcmPlayer? = null
    private var speakToken: Any? = null

    init {
        // 1.7.7: reload failures / GPU→CPU fallbacks from the engine are shown, never swallowed.
        viewModelScope.launch { c.engine.notices.collect { notice.value = it } }
        // Held in a StateFlow: preload can decide to skip before this collector exists.
        viewModelScope.launch {
            c.engine.preloadSkipNotice.collect { text ->
                if (text != null) {
                    notice.value = text
                    c.engine.clearPreloadSkipNotice()
                }
            }
        }
        // 1.10.0: workspace fallback (Download unavailable) and the one-off migration result.
        viewModelScope.launch {
            c.agent.workspace.collect { w -> w.fallbackReason?.let { r -> if (c.agent.settings.current.agentMode) notice.value = r } }
        }
        viewModelScope.launch {
            c.agent.workspaceNote.collect { n -> if (n != null) { notice.value = n; c.agent.workspaceNote.value = null } }
        }
        // Load + warm the speech models in the background so the first voice turn is instant.
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            if (com.iris.localai.util.CrashReporter.diedInNativeWorkLastRun) return@launch
            val s = c.settings.current
            dictation.warmLanguage = s.asrLanguage
            if (c.recognizer.isAvailable) launch { runCatching { c.recognizer.prepare(s.asrLanguage) } }
            if (c.synthesizer.isAvailable) launch { runCatching { c.synthesizer.prepare() } }
        }
        // 1.10.10: after a model (re)load, prefill the open conversation in the background so the
        // next question only prefills its own words (MNN / llama.cpp keep the shared prefix in KV).
        viewModelScope.launch {
            c.engine.state.collect { st ->
                val r = st as? EngineState.Ready ?: return@collect
                val key = r.model.id + "|" + r.details.activeBackend
                if (key == warmedKey) return@collect
                warmedKey = key
                scheduleWarmUp()
            }
        }
        viewModelScope.launch {
            c.conversations.init()
            c.models.refresh()
            // 1.10.2: the last model is preloaded by IrisApp at process start (EngineManager.preloadAtStart),
            // no longer only once the chat screen exists.
        }
    }

    // ------------------------------------------------------------------ conversations

    /** 1.12.3: a reply/agent run is going on; switching conversations leaves it running. */
    private fun runningInBackground() = genJob?.isActive == true

    fun newChat() {
        if (runningInBackground()) { viewing.value = Conversation(); noteBackground(); return }
        viewing.value = null
        conversation.value = Conversation()
        // 2.3: llama.cpp keeps the cached system prompt for the new chat (common-prefix reuse).
        viewModelScope.launch { c.engine.resetForNewChat() }
    }

    fun open(id: String) {
        if (runningInBackground()) {
            if (id == conversation.value.id) { viewing.value = null; return }
            if (id == viewing.value?.id) return
            viewModelScope.launch {
                c.conversations.load(id)?.let { if (runningInBackground()) { viewing.value = it; noteBackground() } else { conversation.value = it; viewing.value = null; scheduleWarmUp() } }
            }
            return
        }
        if (id == conversation.value.id && viewing.value == null) return
        viewModelScope.launch {
            c.conversations.load(id)?.let { conversation.value = it; viewing.value = null; scheduleWarmUp() }
        }
    }

    private fun noteBackground() {
        notice.value = "「" + conversation.value.title.ifBlank { "上一个对话" } + "」在后台继续运行，可在侧边栏切回查看"
    }

    /** 1.13.0: a scheduled task tapped while busy runs once the reply ends. */
    private var pendingScheduled: String? = null

    /** 1.12.3: the background run ended — the conversation on screen becomes the working one. */
    private fun adoptViewing() {
        pendingScheduled?.let { p -> if (c.engine.state.value is EngineState.Ready) { pendingScheduled = null; viewModelScope.launch { kotlinx.coroutines.delay(300); runScheduled(p) } } }
        val v = viewing.value ?: return
        viewing.value = null
        conversation.value = v
        viewModelScope.launch { if (v.messages.isNotEmpty()) c.conversations.load(v.id)?.let { if (viewing.value == null && conversation.value.id == it.id) conversation.value = it } }
    }

    fun delete(id: String) = viewModelScope.launch {
        if (viewing.value?.id == id) viewing.value = Conversation()
        if (conversation.value.id == id) {
            // 1.13.0: wait for the reply to end, or its last save would bring the chat back.
            stop()
            genJob?.let { j -> kotlinx.coroutines.withTimeoutOrNull(3000) { j.join() } ?: j.cancelAndJoin() }
            deletedIds += id
        }
        // 1.10.12: the conversation's attachment copies go with it.
        // 2.0.x: including those of stored message versions / branches.
        runCatching { c.conversations.load(id)?.messages?.let { com.iris.localai.data.MessageVersions.allAttachments(it) } }.getOrNull()?.let { discardAttachments(it) }
        c.conversations.delete(id)
        c.agent.plans.clear(id)
        com.iris.localai.agent.Heartbeat.cancel(getApplication(), id)
        if (conversation.value.id == id) conversation.value = Conversation()
    }

    fun rename(id: String, title: String) = viewModelScope.launch {
        c.conversations.rename(id, title)
        if (conversation.value.id == id) conversation.update { it.copy(title = title) }
        if (viewing.value?.id == id) viewing.update { it?.copy(title = title) }
    }

    fun togglePin(id: String, pinned: Boolean) = viewModelScope.launch {
        c.conversations.setPinned(id, pinned)
        if (conversation.value.id == id) conversation.update { it.copy(pinned = pinned) }
        if (viewing.value?.id == id) viewing.update { it?.copy(pinned = pinned) }
    }

    fun dismissNotice() { notice.value = null }

    // ------------------------------------------------------------------ models

    /** 1.10.2: load telemetry for the model sheet. */
    val loadStats = c.engine.loadStats.records
    /** 2.1.7: the load started from this screen (picker / logo), cancellable by 「取消」. */
    private var loadJob: Job? = null
    fun loadModel(model: LocalModel): Job = viewModelScope.launch {
        if (genJob?.isActive == true) { notice.value = "正在回复，停止后再切换模型"; return@launch }
        c.engine.load(model)
    }.also { loadJob = it }
    /**
     * 2.1.7: tap on the chat logo → load 「默认模型」 (设置 › 模型与后端; none = the last used one).
     * Does nothing while a model is loaded or loading (or a reply runs).
     */
    fun loadDefaultModel() {
        val st = c.engine.state.value
        if (st is EngineState.Ready || st is EngineState.Loading || genJob?.isActive == true) return
        val m = c.engine.defaultModel()
        if (m != null) { loadModel(m); return }
        viewModelScope.launch {
            c.models.refresh()
            val again = c.engine.defaultModel()
            if (again != null) loadModel(again) else notice.value = "还没有可用的模型，点击顶部选择或导入"
        }
    }

    fun unloadModel() = viewModelScope.launch {
        if (genJob?.isActive == true) { notice.value = "正在回复，停止后再卸载模型"; return@launch }
        c.engine.unload()
    }
    /** Writes to the loaded model's own thinking switch when it has one, otherwise the global one. */
    fun setDeepThinking(on: Boolean) {
        val id = c.engine.readyModel?.id
        if (id != null && c.modelConfigs.get(id).thinking != null) c.modelConfigs.update(id) { it.copy(thinking = on) }
        else c.settings.update { it.copy(deepThinking = on) }
    }
    /**
     * 1.7.2: the setting always changes; while a reply runs on GGUF/CPU the decoder also picks up
     * threads / cores / priority before its next token (see EngineManager.applyTierLive).
     * 1.7.4: MNN / LiteRT are reloaded by EngineManager (it watches the setting, debounced).
     */
    fun setResourceTier(tier: Int) {
        val t = tier.coerceIn(0, com.iris.localai.engine.ResourceTier.entries.size - 1)
        if (t == c.settings.current.resourceTier) return
        c.settings.update { it.copy(resourceTier = t) }
        // 1.7.5: applied right away (GGUF/CPU mid-reply, MNN/CPU idle or mid-reply); the settings
        // watcher in EngineManager skips it then and only schedules the reload check.
        c.engine.applyTierLive(com.iris.localai.engine.ResourceTier.of(t))
    }

    /** 1.7.5: what the last tier change really did (EngineManager.applyTierLive). */
    val tierApply = c.engine.tierApply

    /** 1.7.5: the one short status line on the resource card; null = nothing to report. */
    fun resourceStatus(last: com.iris.localai.engine.TierApplyResult?, engine: EngineState): String? =
        if (engine is EngineState.Loading && engine.applyingTier != null)
            com.iris.localai.engine.LiveResourcePolicy.statusText(com.iris.localai.engine.TierApplyResult.RELOADING)
        else com.iris.localai.engine.LiveResourcePolicy.statusText(last)
            // 1.7.8: on the GPU/NPU the tier only places the host threads — say so when nothing else is shown.
            ?: com.iris.localai.engine.LiveResourcePolicy.backendNote((engine as? EngineState.Ready)?.details?.activeBackend)

    fun preferredBackendLabel(m: LocalModel): String = runCatching { c.engine.preferredBackend(m).label }.getOrDefault("")
    /** 2.2.0: 「GenieX · NPU」 when GenieX would run [m], else null. */
    fun engineTag(m: LocalModel): String? = runCatching { c.engine.geniexTag(m) }.getOrNull()

    // ------------------------------------------------------------------ context management

    /** null = follow the global default. */
    fun setConversationStrategy(strategy: Int?) {
        if (viewingOther()) return
        conversation.update { it.copy(contextStrategy = strategy) }
        persist()
    }

    /** 「从这里开始新上下文」: everything so far stays visible but is no longer sent to the model. */
    fun startNewContextHere() {
        if (viewingOther()) return
        val last = conversation.value.messages.lastOrNull() ?: return
        conversation.update { it.copy(contextStartAfterId = last.id) }
        measured.update { it - conversation.value.id }
        persist()
        notice.value = "已分隔：之后的回复不再参考上面的对话"
    }

    fun clearContextBreak() {
        if (viewingOther()) return
        conversation.update { it.copy(contextStartAfterId = null) }
        measured.update { it - conversation.value.id }
        persist()
    }

    fun clearSummary() {
        if (viewingOther()) return
        conversation.update { it.copy(summary = "", summaryUpToId = null) }
        measured.update { it - conversation.value.id }
        persist()
    }

    // ------------------------------------------------------------------ 1.10.0 compaction + memory

    private fun memoryInjectFor(agentMode: Boolean): Boolean =
        c.agent.memorySettings.current.inject(agentMode) && !c.agent.memoryDocs.isEmpty()

    private fun contextTokensFor(ready: EngineState.Ready?): Int =
        ready?.details?.contextLength?.takeIf { it > 0 } ?: c.engine.effective(ready?.model).contextLength

    /**
     * 2.1.0 引用知识库: user message id → snippet block added to it in the prompt (对话 mode).
     * 2.3: kept for every message answered with snippets (was: only the newest), so the next turn
     * sends the history the engine already holds and its KV cache is reused instead of the whole
     * conversation being prefilled again. Bounded ([KbChat.MAX_KEPT]); not persisted.
     */
    private val kbBlocks = LinkedHashMap<String, String>()

    /** 2.1.0: finds knowledge snippets for the newest user message (typed chat only) and marks the reply. */
    private suspend fun prepareKnowledge(requester: Requester, replyId: String) {
        if (requester != Requester.CHAT || !c.settings.current.knowledgeInChat) return
        val user = conversation.value.messages.lastOrNull { it.role == Role.USER && it.content.isNotBlank() } ?: return
        val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.knowledge.KbChat.hits(c.agent.knowledge, user.content) }
        synchronized(kbBlocks) {
            if (hits.isEmpty()) kbBlocks.remove(user.id)
            else com.iris.localai.knowledge.KbChat.remember(kbBlocks, user.id, com.iris.localai.knowledge.KbPrompt.inject(hits))
        }
        if (hits.isEmpty()) return
        conversation.update { conv -> conv.copy(messages = conv.messages.map { if (it.id == replyId) it.copy(kbRefs = hits.size) else it }) }
    }

    /** Freezes the long-term memory for this conversation the first time it is used (stable prompt prefix). */
    private fun freezeMemory(agentMode: Boolean) {
        val ms = c.agent.memorySettings.current
        val conv = conversation.value
        // 2.0.x: until the model has replied once, the snapshot is retaken (memory saved just now counts).
        if (!com.iris.localai.memory.MemoryPrompt.needsSnapshot(conv, ms.epoch)) return
        if (!ms.inject(agentMode)) return
        val snap = runCatching { c.agent.memoryDocs.snapshot() }.getOrDefault("")
        conversation.update { it.copy(memorySnapshot = snap, memoryEpoch = ms.epoch) }
    }

    /**
     * Deterministic compaction (no model call). [manual] = 「压缩上下文」: folds everything but the
     * newest round. Returns the number of newly folded messages (0 = nothing to do).
     */
    private fun compactIfNeeded(agentMode: Boolean, ready: EngineState.Ready?, maxNew: Int, manual: Boolean): Int {
        val conv = conversation.value
        val s = c.settings.current
        val strategy = com.iris.localai.data.ContextStrategy.of(conv.contextStrategy ?: s.contextStrategy)
        val ms = c.agent.memorySettings.current
        if (!manual && !com.iris.localai.context.ContextAssembly.autoAllowed(agentMode, strategy, ms.autoCompactAgent, ms.autoCompactChat)) return 0
        val active = com.iris.localai.context.ContextAssembly.active(conv.messages, conv.contextStartAfterId, agentMode, ready?.details?.supportsVision == true)
        val ctx = contextTokensFor(ready)
        val eff = c.engine.effective(ready?.model)
        val baseSystem = if (agentMode) {
            val ag = c.agent.settings.current
            com.iris.localai.agent.AgentTools.all.filter { ag.isEnabled(it) }.let { t ->
                agentSystem(t, eff.systemPrompt, ready?.model?.fileName)
            }
        } else eff.systemPrompt.trim()
        val memory = com.iris.localai.context.ContextAssembly.memoryBlock(conv, memoryInjectFor(agentMode), ctx, c.agent.memorySettings.current.epoch)
        val fixed = com.iris.localai.util.TokenEstimator.message(listOf(baseSystem, memory).filter { it.isNotBlank() }.joinToString("\n\n"))
        val reserve = maxNew.coerceIn(64, ctx / 2)
        val policy = com.iris.localai.context.CompactPolicy(ctx, hardBudget = ctx - reserve)
        val prev = com.iris.localai.context.Compactor.applicable(conv.compaction, active)
        val next = com.iris.localai.context.Compactor.maybeCompact(active, prev, fixed, policy, withTools = agentMode, manual = manual) ?: return 0
        val folded = next.messages - (prev?.messages ?: 0)
        conversation.update { it.copy(compaction = next) }
        measured.update { it - conv.id }
        persist()
        android.util.Log.i(GEN_TAG, "compaction: folded $folded messages (total ${next.messages}, #${next.times}, manual=$manual)")
        return folded
    }

    /** 「压缩上下文」 in the context sheet. */
    fun compactNow() {
        if (viewingOther()) return
        if (busy()) { notice.value = "正在回复，稍后再压缩"; return }
        val agentMode = c.agent.settings.current.agentMode
        val ready = c.engine.state.value as? EngineState.Ready
        val maxNew = if (agentMode) minOf(c.engine.effective().maxNewTokens, AGENT_MAX_NEW) else c.engine.effective().maxNewTokens
        val n = compactIfNeeded(agentMode, ready, maxNew, manual = true)
        notice.value = if (n > 0) "已压缩较早的 $n 条消息（不调用模型；原文仍在，智能体可用「回忆」检索）" else "没有可压缩的内容：只有最近一轮对话"
    }

    /** Puts the compacted messages back into the context (they were never deleted). */
    fun clearCompaction() {
        if (viewingOther()) return
        conversation.update { it.copy(compaction = null) }
        measured.update { it - conversation.value.id }
        persist()
        notice.value = "已撤销压缩：较早的对话重新交给模型（受上下文长度限制）"
    }
    fun onUserActivity() = c.engine.touch()
    fun markNotificationAsked() = c.settings.update { it.copy(askedNotificationPermission = true) }
    fun markBatteryAsked() = c.settings.update { it.copy(askedBatteryExemption = true) }

    // ------------------------------------------------------------------ messaging

    /** 2.1.9: the guide to show after `screen` was blocked (无障碍 off / no vision model); null = none. */
    val guiSetup: StateFlow<com.iris.localai.agent.GuiBlocker?> get() = c.gui.setup
    fun dismissGuiSetup() = c.gui.dismissSetup()

    /** 2.1.9: a 对话-mode message that asks to operate the phone, while `screen` is enabled. */
    private fun guiTurn(text: String): Boolean {
        val ag = c.agent.settings.current
        if (!ag.isEnabled(com.iris.localai.agent.ScreenTool) || !com.iris.localai.agent.GuiIntent.looksLikePhoneTask(text)) return false
        notice.value = com.iris.localai.agent.GuiIntent.CHAT_NOTICE
        return true
    }

    fun send(
        text: String,
        attachments: List<com.iris.localai.data.Attachment> = emptyList(),
        refs: List<com.iris.localai.data.TurnRef> = emptyList(),
        /** 2.1.7: 「打断并发送」 — stop the running reply and answer this instead. */
        interrupt: Boolean = false,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (viewingOther()) return
        // 2.1.7: sending while a reply runs used to be dropped silently — now 「停/等等/不对…」 (or
        // 「打断并发送」) stops it and starts a new turn; anything else is a queued 补充.
        if (busy()) { sendWhileBusy(trimmed, attachments, refs, interrupt); return }
        // 2.2 「技能」: a picked skill / tool needs the agent — turn 智能体 on for it.
        if (com.iris.localai.data.TurnRefs.needsAgent(refs, c.agent.settings.current.agentMode)) { setAgentMode(true); notice.value = ComposerText.AGENT_FOR_REF }
        // 2.0.x: 「记住：…」 / "remember: …" is saved to MEMORY.md directly (both modes, no model call).
        if (attachments.isEmpty() && refs.isEmpty()) com.iris.localai.memory.MemoryCommand.parse(trimmed)?.let { fact ->
            rememberCommand(trimmed, fact)
            return
        }
        // 2.1.9: 「打开微信」 only → the phone tool's open_app right away (no model, no screen loop).
        if (attachments.isEmpty() && refs.isEmpty()) com.iris.localai.agent.LaunchIntent.appName(trimmed)?.let { app ->
            if (launchCommand(trimmed, app)) return
        }
        val ready = c.engine.state.value as? EngineState.Ready
        if (attachments.any { it.kind == com.iris.localai.data.AttachmentKind.IMAGE } && ready != null && ready.details.supportsVision != true)
            notice.value = ATTACH_NO_VISION
        // 1.11.1: a request that needs the internet while no tool can reach it.
        if (notice.value == null) {
            val ag = c.agent.settings.current
            val online = ag.agentMode && com.iris.localai.agent.AgentTools.all.any { ag.isEnabled(it) && it.name in com.iris.localai.agent.SearchText.NETWORK_TOOLS }
            com.iris.localai.agent.NetworkHint.forRequest(trimmed, ag.agentMode, online)?.let { notice.value = it }
        }
        append(ChatMessage(role = Role.USER, content = trimmed, attachments = attachments, refs = refs.distinct()))
        startReply(Requester.CHAT)
    }

    // ------------------------------------------------------------------ 2.1.7 send while busy

    private val sendQueue = SendQueue()
    private val _queued = MutableStateFlow<List<QueuedMessage>>(emptyList())
    /** Messages sent while a reply ran: answered right after it (chat) or read before the next step (智能体). */
    val queued: StateFlow<List<QueuedMessage>> = _queued.asStateFlow()

    fun cancelQueued(id: String) { _queued.value = sendQueue.cancel(id) }

    private fun sendWhileBusy(text: String, attachments: List<com.iris.localai.data.Attachment>, refs: List<com.iris.localai.data.TurnRef>, force: Boolean) {
        val convId = conversation.value.id
        // Files / skills cannot wait in the text queue: they interrupt like 「打断并发送」.
        if (force || attachments.isNotEmpty() || refs.isNotEmpty() || SendIntent.isInterrupt(text)) {
            _queued.value = sendQueue.clear()
            val job = genJob
            stop()
            viewModelScope.launch {
                job?.join()
                if (conversation.value.id != convId || viewingOther()) return@launch
                // A stop that has not landed yet: wait a moment for the reply to wind down.
                repeat(40) { if (busy()) kotlinx.coroutines.delay(50) }
                if (busy()) { notice.value = "上一条回复还没停下，请稍后再发"; return@launch }
                send(text, attachments, refs)
            }
            return
        }
        val forAgent = agentRunning
        _queued.value = sendQueue.add(QueuedMessage(convId = convId, text = text, forAgent = forAgent))
        notice.value = if (forAgent) "已记下补充：智能体下一步会看到（点按可取消）" else SendIntent.QUEUED_HINT
    }

    /** The 补充 the running 智能体 has not read yet → one note for its next step (also kept on the request). */
    private fun takeAgentNotes(convId: String): String? {
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

    /** After a reply: the next queued message is answered as its own turn. */
    private fun drainQueue() {
        if (busy() || viewingOther()) return
        val convId = conversation.value.id
        _queued.value = sendQueue.retainOnly(convId)
        val next = sendQueue.next(convId) ?: return
        _queued.value = sendQueue.all
        send(next.text)
    }

    /** 1.10.12: copies picked files into app storage (off the main thread). */
    suspend fun importAttachments(uris: List<android.net.Uri>): List<com.iris.localai.data.Attachment> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            uris.take(com.iris.localai.util.Attachments.MAX_PER_MESSAGE).mapNotNull { u ->
                runCatching { com.iris.localai.util.Attachments.import(getApplication(), u) }
                    .onFailure { notice.value = it.message ?: "附件读取失败" }.getOrNull()
            }
        }

    /** 2.1.7 生图 「发到聊天」: a PNG path waiting to join the composer's attachments (ChatScreen consumes it). */
    private val _incomingImage = MutableStateFlow<String?>(null)
    val incomingImage: StateFlow<String?> = _incomingImage.asStateFlow()
    fun attachGeneratedImage(path: String) { _incomingImage.value = path }
    fun consumeIncomingImage() { _incomingImage.value = null }

    /** Copies a local image (a 生图 result) into app attachments, like a picked photo. */
    suspend fun importLocalImage(path: String): com.iris.localai.data.Attachment? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val src = java.io.File(path)
            val out = java.io.File(com.iris.localai.util.Attachments.dir(getApplication()), java.util.UUID.randomUUID().toString().take(8) + "-" + src.name)
            src.copyTo(out, overwrite = true)
            com.iris.localai.data.Attachment(name = src.name, path = out.path, mime = "image/png", sizeBytes = out.length(), kind = com.iris.localai.data.AttachmentKind.IMAGE)
        }.onFailure { notice.value = "无法附加图片：${it.message}" }.getOrNull()
    }

    fun discardAttachments(list: List<com.iris.localai.data.Attachment>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.util.Attachments.delete(list) }
    }

    /**
     * 1.10.12: the pictures of the newest user message go to the engine with that message, when the
     * model can see. Older pictures stay as a short text note.
     */
    private suspend fun withImages(messages: List<PromptMessage>): List<PromptMessage> {
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

    /** 2.1.1: whether this prompt turn is the newest user message and it has pictures the model will read. */
    private fun pickImages(m: PromptMessage): Boolean {
        val ready = c.engine.state.value as? EngineState.Ready ?: return false
        if (ready.details.supportsVision != true) return false
        val lastUser = conversation.value.messages.lastOrNull { it.role == Role.USER } ?: return false
        return lastUser.attachments.any { it.kind == com.iris.localai.data.AttachmentKind.IMAGE } &&
            m.content.contains(lastUser.attachments.first { it.kind == com.iris.localai.data.AttachmentKind.IMAGE }.name)
    }

    /**
     * 2.0.x: the next reply bubble becomes a new version of this message (regenerate). Read and
     * cleared by [startReply].
     */
    private var nextReplyBase: ChatMessage? = null

    /** Regenerates the last assistant reply; 2.0.x: the previous reply is kept as another version. */
    fun regenerate() {
        if (viewingOther()) return
        if (busy()) return
        val msgs = conversation.value.messages
        val lastUser = msgs.indexOfLast { it.role == Role.USER }
        if (lastUser < 0) return
        val prev = msgs.drop(lastUser + 1).lastOrNull { it.role == Role.ASSISTANT }
        // An empty reply (stopped before any text) is not worth keeping as a version of its own.
        nextReplyBase = prev?.let { p ->
            if (p.content.isBlank() && p.agentSteps.isEmpty()) com.iris.localai.data.MessageVersions.dropActive(p) else p
        }
        conversation.update { it.copy(messages = msgs.subList(0, lastUser + 1)) }
        startReply(Requester.CHAT)
    }

    /**
     * 2.0.x: editing a user message makes a new version of it (the old text and the conversation
     * that followed stay as the previous version, reachable with ‹ ›), then answers the new text.
     */
    fun editAndResend(messageId: String, newText: String) {
        if (viewingOther()) return
        if (busy() || newText.isBlank()) return
        val msgs = conversation.value.messages
        val next = com.iris.localai.data.MessageVersions.editUser(msgs, messageId, newText) ?: return
        conversation.update { it.copy(messages = next, updatedAt = System.currentTimeMillis()) }
        measured.update { it - conversation.value.id }
        startReply(Requester.CHAT)
    }

    /** 2.0.x: edits an assistant reply in place (no regeneration); the old text stays as a version. */
    fun editAssistant(messageId: String, newText: String) {
        if (viewingOther()) return
        if (busy() || newText.isBlank()) return
        val next = com.iris.localai.data.MessageVersions.editAssistant(conversation.value.messages, messageId, newText) ?: return
        conversation.update { it.copy(messages = next, updatedAt = System.currentTimeMillis()) }
        measured.update { it - conversation.value.id }
        persist()
    }

    /** 2.0.x: ‹ › under a message with several versions; a user message brings its own branch back. */
    fun switchVersion(messageId: String, target: Int) {
        if (viewingOther()) return
        if (busy()) { notice.value = "正在回复，稍后再切换版本"; return }
        val next = com.iris.localai.data.MessageVersions.switchTo(conversation.value.messages, messageId, target) ?: return
        conversation.update { it.copy(messages = next) }
        measured.update { it - conversation.value.id }
        persist()
    }

    // ------------------------------------------------------------------ 2.0.x explicit memory

    /** 「记住这条」 on a message: one line appended to USER.md / MEMORY.md (MemoryOps caps apply). */
    fun rememberText(text: String, target: com.iris.localai.memory.MemoryTarget) {
        val msg = saveMemory(text, target)
        notice.value = msg
    }

    /** The default target suggested in the 「记住这条」 dialog. */
    fun suggestMemoryTarget(text: String): com.iris.localai.memory.MemoryTarget = com.iris.localai.memory.MemoryCommand.suggestTarget(text)

    /** One-line entry for a message (reasoning removed, whitespace collapsed, capped). */
    fun memoryEntryFor(message: ChatMessage): String =
        com.iris.localai.memory.MemoryCommand.entryFrom(if (message.role == Role.USER) message.content else ThinkParser.parse(message.content).answer)

    /** Writes through [com.iris.localai.memory.MemoryDocs.edit] and returns the line to show the user. */
    private fun saveMemory(text: String, target: com.iris.localai.memory.MemoryTarget): String {
        if (!c.agent.memorySettings.current.enabled) return com.iris.localai.memory.MemoryCommand.DISABLED
        val entry = com.iris.localai.memory.MemoryCommand.entryFrom(text)
        val r = runCatching { c.agent.memoryDocs.edit(target) { doc -> com.iris.localai.memory.MemoryOps.add(doc, entry) } }
            .getOrElse { com.iris.localai.memory.MemoryEdit.Err(it.message ?: "write failed") }
        if (r is com.iris.localai.memory.MemoryEdit.Ok) {
            c.agent.memoryChanged()
            // A conversation the model has not answered yet picks the new memory up right away.
            if (com.iris.localai.memory.MemoryPrompt.needsSnapshot(conversation.value, c.agent.memorySettings.current.epoch)) {
                conversation.update { it.copy(memorySnapshot = null) }
            }
        }
        return com.iris.localai.memory.MemoryCommand.userMessage(r, entry, target)
    }

    /** 2.1.9: launches an installed app for a pure 「打开X」; false = not handled (no such app / phone tool off). */
    private fun launchCommand(userText: String, app: String): Boolean {
        val ag = c.agent.settings.current
        val tool = com.iris.localai.agent.PhoneTool
        if (!ag.isEnabled(tool) || ag.permission(tool) == com.iris.localai.agent.Permission.DENY) return false
        val apps = runCatching { c.agent.phone.apps() }.getOrDefault(emptyList())
        val hit = com.iris.localai.agent.AppMatcher.match(app, apps) ?: return false
        val reply = runCatching { c.agent.phone.openApp(hit.second); "已打开「${hit.first}」。" }.getOrElse { "打开「${hit.first}」失败：${it.message}" }
        append(ChatMessage(role = Role.USER, content = userText))
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
        return true
    }

    /** 「记住：…」: the user's line and a short confirmation are added to the chat; the model is not called. */
    private fun rememberCommand(userText: String, fact: String) {
        append(ChatMessage(role = Role.USER, content = userText))
        val reply = saveMemory(fact, com.iris.localai.memory.MemoryTarget.MEMORY)
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
    }

    /**
     * A reply is streaming, or a send is still waiting for the model to finish loading.
     * 2.1.8: read from the raw sources ([ReplyBusy]), not the derived `state` (it lags a dispatch,
     * so the queue drain right after a reply saw "generating" and a 「待回复」 waited forever).
     */
    private fun busy() = ReplyBusy.of(streamingId.value, genJob?.isActive == true)

    /** 2.1.8: the running reply's [ReplyWatchdog] (null when none runs). */
    @Volatile private var replyWatchdog: ReplyWatchdog? = null
    /** 2.1.8: replies ended by the watchdog: their error survives the reply's own cleanup. */
    private val stalledIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 2.1.8 「一发送就卡住」: the reply [job] showed no sign of life past the watchdog's limit. The
     * bubble says so, the engine and the job are cancelled and the screen is free again; a job that
     * does not end within [ReplyWatchdog.DETACH_WAIT_MS] is detached (busy cleared anyway).
     */
    private suspend fun onReplyStalled(job: Job, verdict: ReplyWatchdog.Verdict, replyId: String?) {
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

    /** 1.10.13: set by 停止 for the running reply; text arriving after it is dropped. */
    @Volatile private var stopAsked = false

    fun stop() {
        val job = genJob ?: return
        if (!job.isActive) return
        stopAsked = true
        // 1.10.13: a warm-up prefill still on the engine would take the cancel meant for this reply
        // (the reply then started right after it, so 停止 looked dead).
        warmJob?.cancel()
        // 1.9.0: an agent run is stopped as a whole (model turn, pending confirmation or tool).
        if (agentRunning) { c.engine.cancel(engineOwner); job.cancel(); return }
        // Still waiting on a model (re)load: no engine work to stop yet, cancel the request itself.
        if (streamingId.value != null && !awaitingEngine) {
            c.engine.cancel(engineOwner)
            // 1.10.13: a cancel that lands before the native call starts (prefill setup) is lost —
            // MNN clears the flag at the start, LiteRT has no live conversation yet. Repeat it, and
            // if the reply still runs, end the request itself.
            viewModelScope.launch {
                repeat(StopPolicy.RETRIES) {
                    kotlinx.coroutines.delay(StopPolicy.RETRY_MS)
                    if (!job.isActive) return@launch
                    c.engine.cancel(engineOwner)
                }
                if (job.isActive) {
                    android.util.Log.w(GEN_TAG, "stop: engine did not end the reply in ${StopPolicy.RETRIES * StopPolicy.RETRY_MS}ms → cancelling the request")
                    job.cancel()
                }
            }
        } else job.cancel()
    }

    private fun append(m: ChatMessage) {
        conversation.update { conv ->
            val title = conv.title.ifBlank { if (m.role == Role.USER) titleFrom(m.content.ifBlank { m.attachments.firstOrNull()?.name.orEmpty() }) else "" }
            conv.copy(title = title, messages = conv.messages + m, updatedAt = System.currentTimeMillis())
        }
        persist()
    }

    /** 1.13.0: conversations deleted while a reply was still writing (never saved again). */
    private val deletedIds = java.util.Collections.synchronizedSet(HashSet<String>())
    /** 1.13.0: saves go out in order (separate launches could land a stale partial after the final one). */
    private val saveQueue = Channel<Conversation>(Channel.UNLIMITED).also { q ->
        viewModelScope.launch { for (conv in q) if (conv.id !in deletedIds) runCatching { c.conversations.save(conv) } }
    }
    private fun save(conv: Conversation) { if (conv.messages.isNotEmpty()) saveQueue.trySend(conv) }

    private fun persist() = save(conversation.value)

    private fun titleFrom(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (line.length <= 24) line else line.take(24) + "…"
    }

    /** Fits [conv] into the loaded model's window with the conversation's (or global) strategy. */
    private fun planFor(conv: Conversation, ready: EngineState.Ready?, voice: Boolean, thinkingBudget: Int = 0, agent: Boolean = false, maxNewOverride: Int? = null): com.iris.localai.util.ContextPlan {
        val s = c.settings.current
        val eff = c.engine.effective(ready?.model).let { e -> if (maxNewOverride != null) e.copy(maxNewTokens = maxNewOverride) else e }
        // 2.1.9: in a voice call the instruction is appended to the system prompt at send time
        // (VoiceStyle.withVoiceSystem), so the history stays as stored and the KV prefix holds.
        val baseSystem = eff.systemPrompt.trim()
        // 1.10.0: 智能体 history carries earlier tool steps (rolled up); a compaction summary replaces
        // the folded messages; the frozen long-term memory joins the system prompt.
        val active = com.iris.localai.context.ContextAssembly.active(conv.messages, conv.contextStartAfterId, withTools = agent, vision = ready?.details?.supportsVision == true)
        val compaction = com.iris.localai.context.Compactor.applicable(conv.compaction, active)
        val memory = com.iris.localai.context.ContextAssembly.memoryBlock(conv, memoryInjectFor(agent && !voice), contextTokensFor(ready), c.agent.memorySettings.current.epoch)
        val system = com.iris.localai.context.ContextAssembly.system(baseSystem, memory)
        // 2.3: every message keeps the knowledge block it was answered with, so the history the
        // engine sees next turn is the one it already holds (KV-cache reuse).
        val msgs = com.iris.localai.context.ContextAssembly.forPlanner(active, compaction, withTools = agent)
            .let { m -> if (agent || !s.knowledgeInChat) m else synchronized(kbBlocks) { com.iris.localai.knowledge.KbChat.attachAll(m, kbBlocks) } }
        return com.iris.localai.util.ContextPlanner.plan(
            com.iris.localai.util.ContextRequest(
                system = system,
                messages = msgs,
                // 2.3: the compaction summary leads the history (system prompt stays cacheable).
                lead = com.iris.localai.context.ContextAssembly.lead(compaction),
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
    private suspend fun summarize(plan: com.iris.localai.util.ContextPlan): Boolean {
        if (plan.toSummarize.isEmpty()) return true
        summarizing.value = true
        act("压缩上下文")
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
    private fun startReply(requester: Requester, onAnswerDelta: ((String) -> Unit)? = null): Job {
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
                                    else if (modelPending) it.copy(error = failed?.let { m -> "$LOAD_FAILED：$m" } ?: LOAD_FAILED)
                                    else it.copy(error = failed?.let { m -> "模型重新加载失败：$m" } ?: "模型未加载")
                                })
                            }
                            if (streamingId.value == b.id) streamingId.value = null
                            persist()
                        } else {
                            notice.value = failed?.let { "模型加载失败：$it" } ?: "请先在顶部选择并加载一个模型"
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
    private suspend fun runReply(requester: Requester, reply: ChatMessage, onAnswerDelta: ((String) -> Unit)?) {
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
                        act(if (p.thinking) "思考中" else "生成回答")
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
                act(if (answerOnly) "继续生成回答" else if (p.messages.lastOrNull { it.role == PromptMessage.USER }?.let { pickImages(it) } == true) "读取图片与提示（视觉编码 + 预填充）" else "读取提示（预填充）")
                c.engine.generate(
                    withImages(if (requester == Requester.VOICE) com.iris.localai.voice.VoiceStyle.withVoiceSystem(p.messages) else p.messages),
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
                                    act(if (p.thinking) "思考中" else "生成回答")
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
                                notice.value = "对话较长，已自动省略最早的 ${ev.stats!!.droppedMessages} 条消息以适配上下文"
                            }
                            recordUsage(convId, p, ev.stats, raw.toString(), ready)
                        }
                    }
                }
            }

            try {
                plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { prepareKnowledge(requester, reply.id); planFor(conversation.value, ready, voice = requester == Requester.VOICE, thinkingBudget = budget) }
                // 1.10.0: frozen memory + deterministic compaction (typed chat only); planned again when either changed.
                if (requester == Requester.CHAT) {
                    val hadSnapshot = conversation.value.memorySnapshot
                    freezeMemory(agentMode = false)
                    val n = compactIfNeeded(agentMode = false, ready = ready, maxNew = eff0.maxNewTokens + budget, manual = false)
                    if (n > 0) notice.value = "对话较长，已压缩较早的 $n 条消息（不调用模型）"
                    if (n > 0 || hadSnapshot != conversation.value.memorySnapshot) plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { planFor(conversation.value, ready, voice = false, thinkingBudget = budget) }
                }
                if (plan.toSummarize.isNotEmpty() && requester == Requester.CHAT) {
                    if (summarize(plan)) plan = planFor(conversation.value, ready, voice = false, thinkingBudget = budget)
                    else notice.value = "早期对话摘要失败，本次按 token 预算截断"
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
                if (requester == Requester.CHAT && c.settings.current.autoSpeak && raw.isNotBlank()) {
                    speak(reply.id)
                }
            }
    }

    // ------------------------------------------------------------------ 1.9.0 智能体

    val agentSettings = c.agent.settings.settings
    fun setAgentMode(on: Boolean) = c.agent.settings.update { it.copy(agentMode = on) }

    /** 2.2 「技能」 sheet: installed skills (only while the skill tool is on) and the enabled agent tools. */
    fun refCatalog(): RefCatalog {
        val ag = c.agent.settings.current
        val tools = com.iris.localai.agent.AgentTools.all.filter { ag.isEnabled(it) && it.name != com.iris.localai.agent.SkillTool.name && it.name != com.iris.localai.agent.ActivateTool.name }
        val skillOn = com.iris.localai.agent.AgentTools.all.any { it.name == com.iris.localai.agent.SkillTool.name && ag.isEnabled(it) }
        val skills = if (skillOn) runCatching { c.agent.skills.all() }.getOrDefault(emptyList()) else emptyList()
        return RefCatalog(
            skills = skills.map { RefItem(com.iris.localai.data.TurnRef(com.iris.localai.data.TurnRefKind.SKILL, it.name), it.name, it.description) },
            tools = tools.map { RefItem(com.iris.localai.data.TurnRef(com.iris.localai.data.TurnRefKind.TOOL, it.name), it.label, it.description) },
            skillToolOff = !skillOn,
        )
    }

    /** 1.12.0: skills listed in the prompt (only while the skill tool is on). */
    private fun skillsFor(tools: List<com.iris.localai.agent.AgentTool>): List<com.iris.localai.agent.SkillInfo> =
        if (tools.any { it.name == com.iris.localai.agent.SkillTool.name }) runCatching { c.agent.skills.all() }.getOrDefault(emptyList()) else emptyList()

    /** 1.14.0: the agent system prompt (按需载入, the conversation's plan, 计划模式). */
    private fun agentSystem(tools: List<com.iris.localai.agent.AgentTool>, userSystem: String, modelFile: String?, memory: String = ""): String {
        val ag = c.agent.settings.current
        // 2.3: no plan here any more (it rides on the newest user turn, see AgentPrompt.withPlanNote).
        return com.iris.localai.agent.AgentPrompt.build(
            tools, userSystem, com.iris.localai.agent.AgentPrompt.styleFor(modelFile), skillsFor(tools),
            lazy = ag.lazyTools, planFirst = ag.planFirst, memory = memory,
        )
    }

    /** 2.3: the 智能体 system prompt for [conv]: the user's instructions and the frozen memory as their own sections. */
    private fun agentSystemFor(conv: Conversation, ready: EngineState.Ready?, tools: List<com.iris.localai.agent.AgentTool>): String {
        val memory = com.iris.localai.context.ContextAssembly.memoryBlock(conv, memoryInjectFor(true), contextTokensFor(ready), c.agent.memorySettings.current.epoch)
        return agentSystem(tools, c.engine.effective(ready?.model).systemPrompt, ready?.model?.fileName, memory)
    }

    /** 1.14.0: the open conversation's plan (计划卡片). */
    val plan: StateFlow<com.iris.localai.agent.Plan?> = kotlinx.coroutines.flow.combine(c.agent.plans.plans, conversation) { m, conv -> m[conv.id] }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    fun clearPlan() { c.agent.plans.clear(conversation.value.id); com.iris.localai.agent.Heartbeat.cancel(getApplication(), conversation.value.id) }

    /** 1.14.0: heartbeat check number the next agent run counts as (0 = a run the user started). */
    private var nextHeartbeatAttempt = 0
    private var runLimitJob: Job? = null

    /** Stops the run started for a background job after [minutes] (功耗限制). */
    private fun limitRun(minutes: Int) {
        runLimitJob?.cancel()
        if (minutes <= 0) return
        runLimitJob = viewModelScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            if (busy()) { android.util.Log.i(GEN_TAG, "background job hit its ${minutes}min limit"); stop(); notice.value = "后台任务达到 $minutes 分钟上限，已停止" }
        }
    }

    /** 1.14.0: work from the heartbeat / an unattended schedule. False = not now (caller notifies). */
    private fun acceptJob(job: com.iris.localai.agent.AgentBus.Job): Boolean {
        if (busy() || c.engine.state.value !is EngineState.Ready) return false
        viewModelScope.launch {
            when (job.kind) {
                com.iris.localai.agent.AgentBus.Kind.RESUME -> {
                    val id = job.conversationId ?: return@launch
                    if (conversation.value.id != id) { open(id); kotlinx.coroutines.delay(400) }
                    if (conversation.value.id != id) return@launch
                    if (!c.agent.settings.current.agentMode) setAgentMode(true)
                    nextHeartbeatAttempt = job.attempt + 1
                    send(job.prompt)
                }
                com.iris.localai.agent.AgentBus.Kind.SCHEDULED -> runScheduled(job.prompt)
            }
            limitRun(job.maxMinutes)
        }
        return true
    }

    /** 1.14.0: the 「计划还有 N 步」 notification was tapped. */
    fun resumePlan(convId: String) {
        _showChat.value++
        if (busy()) { notice.value = "正在回复，稍后再继续计划"; return }
        open(convId)
        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            if (conversation.value.id != convId) return@launch
            if (!c.agent.settings.current.agentMode) setAgentMode(true)
            if (c.engine.state.value !is EngineState.Ready) { notice.value = "需要先加载模型才能继续计划"; return@launch }
            send(com.iris.localai.agent.HeartbeatRules.RESUME_PROMPT)
        }
    }

    init {
        com.iris.localai.agent.AgentBus.runner = ::acceptJob
        // 1.14.0: live state for the 运行状态 rack in 设置.
        viewModelScope.launch { streamingId.collect { id -> com.iris.localai.util.SystemStatus.update { it.copy(generating = id != null) } } }
        viewModelScope.launch { _speakingMessageId.collect { id -> com.iris.localai.util.SystemStatus.update { it.copy(voiceActive = id != null) } } }
    }

    /**
     * 1.12.0: a scheduled task's notification was tapped: a new 智能体 conversation runs its prompt
     * (once a model is ready; otherwise the prompt waits in the composer's place as a notice).
     */
    fun runScheduled(prompt: String) {
        _showChat.value++
        if (busy()) { pendingScheduled = prompt; notice.value = "正在回复，完成后自动执行定时任务"; return }
        newChat()
        if (!c.agent.settings.current.agentMode) setAgentMode(true)
        if (c.engine.state.value !is EngineState.Ready) { notice.value = "定时任务需要先加载模型：$prompt"; return }
        send(prompt)
    }

    /** A tool call waiting for 允许一次 / 总是允许 / 拒绝 in the chat. */
    data class PermissionAsk(
        val toolLabel: String,
        val toolName: String,
        val preview: String,
        internal val answer: kotlinx.coroutines.CompletableDeferred<com.iris.localai.agent.UserDecision>,
        /** 1.12.0: what 总是允许 remembers for run_command (a command prefix); null = the whole tool. */
        val alwaysPrefix: String? = null,
    )

    private val _permissionAsk = MutableStateFlow<PermissionAsk?>(null)
    val permissionAsk: StateFlow<PermissionAsk?> = _permissionAsk.asStateFlow()

    fun answerPermission(d: com.iris.localai.agent.UserDecision) {
        val a = _permissionAsk.value ?: return
        _permissionAsk.value = null
        a.answer.complete(d)
    }

    @Volatile private var agentRunning = false

    /**
     * 1.9.0: GPU first load taking long (see [com.iris.localai.engine.SlowLoadHint]): the model's
     * own backend becomes CPU and, once the running load returns, it is reloaded on the CPU.
     */
    fun switchLoadingModelToCpu() {
        val st = c.engine.state.value as? EngineState.Loading ?: return
        val m = st.model
        c.modelConfigs.update(m.id) { it.copy(backend = com.iris.localai.engine.Backend.CPU.name) }
        notice.value = com.iris.localai.engine.SlowLoadHint.SWITCHED
        viewModelScope.launch {
            val end = c.engine.state.first { it !is EngineState.Loading }
            if (end is EngineState.Ready && end.model.path == m.path && end.details.activeBackend != com.iris.localai.engine.Backend.CPU) c.engine.reload()
            else if (end is EngineState.Failed && end.model?.path == m.path) c.engine.load(m)
        }
    }

    /**
     * 1.10.3: 「改用 CPU」 on the model sheet's [com.iris.localai.engine.CpuFasterHint]: the model's own
     * backend becomes CPU (a real user choice). Loading it now → the same flow as the slow-load card;
     * loaded on another backend → reload on the CPU; not loaded → takes effect on the next load.
     */
    fun switchModelToCpu(m: LocalModel) {
        val st = c.engine.state.value
        if (st is EngineState.Loading && st.model.path == m.path) { switchLoadingModelToCpu(); return }
        c.modelConfigs.update(m.id) { it.copy(backend = com.iris.localai.engine.Backend.CPU.name) }
        if (st is EngineState.Ready && st.model.path == m.path && st.details.activeBackend != com.iris.localai.engine.Backend.CPU) {
            viewModelScope.launch { c.engine.reload() }
        }
    }

    private fun agentPreview(tool: com.iris.localai.agent.AgentTool, call: com.iris.localai.agent.ToolCall): String {
        val a = call.args
        val path = com.iris.localai.agent.Args.str(a, "path", "file", "file_path", "filename")
        return when (tool.name) {
            "write_file" -> "文件：$path\n内容（${com.iris.localai.agent.Args.str(a, "content", "text", "data")?.length ?: 0} 字）：\n" +
                (com.iris.localai.agent.Args.str(a, "content", "text", "data") ?: "").take(400)
            "edit_file" -> "文件：$path\n查找：\n" + (com.iris.localai.agent.Args.str(a, "old", "old_string", "old_text", "search", "find") ?: "").take(200) +
                "\n替换为：\n" + (com.iris.localai.agent.Args.str(a, "new", "new_string", "new_text", "replace", "replacement") ?: "").take(200)
            "delete_file" -> "删除：$path"
            "run_command" -> "命令（在工作区运行，不可撤销）：\n" + (com.iris.localai.agent.Args.str(a, "command", "cmd", "shell", "script") ?: "").take(400)
            "web_fetch" -> "网址：" + (com.iris.localai.agent.Args.str(a, "url", "link", "href") ?: "")
            "memory" -> com.iris.localai.agent.memoryPreview(call)
            else -> com.iris.localai.agent.Args.summary(a, 300)
        }
    }

    /**
     * 1.9.0 智能体 reply: [com.iris.localai.agent.AgentLoop] with the loaded engine as its model.
     * Each model turn streams into the bubble (the call block hidden); tool steps are stored on
     * the message ([ChatMessage.agentSteps]) and drawn as cards. Only the final answer goes back
     * into later turns' history (small context windows).
     */
    private suspend fun runAgentReply(reply: ChatMessage, voiceOut: ((String) -> Unit)? = null) {
        val s = c.settings.current
        val ag = c.agent.settings.current
        val ready = c.engine.state.value as? EngineState.Ready
        val details = ready?.details
        val eff = c.engine.effective()
        // 2.1.7: a spoken run answers without reasoning (as the plain voice reply always did).
        val disableThinking = !eff.thinking || voiceOut != null
        var toolCues = 0
        // 2.1.9 边生成边开口: the answer turn is spoken sentence by sentence while it streams.
        val voiceStream = voiceOut?.let { com.iris.localai.voice.AgentVoiceStream(it) }
        val prefix = if (details?.thinkingPrefilled == true && !disableThinking) "<think>" else ""
        val history = c.agent.history
        val memoryOn = c.agent.memorySettings.current.enabled
        val tools = com.iris.localai.agent.AgentTools.enabled(ag, history != null, memoryOn, c.agent.knowledge.nonEmptyCached(), c.agent.imageGenOnCached())
        val ctxTokens = details?.contextLength?.takeIf { it > 0 } ?: eff.contextLength
        val maxNew = minOf(eff.maxNewTokens, AGENT_MAX_NEW)
        val sampling = c.engine.samplingFor(disableThinking).copy(maxNewTokens = maxNew, contextShift = false)
        streamingId.value = reply.id
        val timer = com.iris.localai.util.ReplyTimer().also { liveTimer = it }
        val hbAttempt = nextHeartbeatAttempt.also { nextHeartbeatAttempt = 0 }
        var steps: List<com.iris.localai.agent.AgentStep> = emptyList()
        var agentCalls = 0
        var outcome: com.iris.localai.agent.AgentOutcome? = null
        var lastStats: com.iris.localai.data.GenerationStats? = null
        var cancelled = false
        var failure: String? = null
        val background = s.backgroundGeneration
        val convId = conversation.value.id
        if (background) {
            com.iris.localai.service.GenerationMonitor.begin(convId, conversation.value.title, stop = { stop() })
            com.iris.localai.service.GenerationService.start(getApplication())
        }
        fun setSteps(list: List<com.iris.localai.agent.AgentStep>) {
            steps = list
            conversation.update { conv -> conv.copy(messages = conv.messages.map { if (it.id == reply.id) it.copy(agentSteps = list) else it }) }
        }
        val model = object : com.iris.localai.agent.AgentModel {
          override suspend fun complete(messages: List<PromptMessage>, onText: (String) -> Unit): com.iris.localai.agent.ModelTurn {
            val sb = StringBuilder()
            val stopFilter = com.iris.localai.util.StopTokenFilter()
            var cut = false
            var done: GenEvent.Done? = null
            var lastPub = 0L
            promptLog.record(reply.id, messages)
            agentCalls++
            voiceStream?.startTurn()
            val stepTag = if (agentCalls > 1) "第 $agentCalls 轮 · " else ""
            act(stepTag + "读取提示（预填充）")
            replyWatchdog?.prompt(messages.sumOf { com.iris.localai.util.TokenEstimator.message(it.content) })
            c.engine.generate(withImages(messages), sampling, Requester.CHAT, engineOwner).collect { ev ->
                when (ev) {
                    is GenEvent.Piece -> {
                        com.iris.localai.service.GenerationMonitor.tick()
                        replyWatchdog?.output()
                        if (cut) return@collect
                        val clean = stopFilter.push(ev.text)
                        if (stopFilter.stopped) { cut = true; c.engine.cancel(engineOwner) }
                        if (clean.isNotEmpty()) {
                            timer.onText(clean)
                            sb.append(clean)
                            // A complete call: stop here, the rest would be the model inventing the result.
                            com.iris.localai.agent.ToolCallParser.callEnd(prefix + sb)?.let { end ->
                                sb.setLength((end - prefix.length).coerceIn(0, sb.length)); cut = true; c.engine.cancel(engineOwner)
                            }
                            voiceStream?.update(ThinkParser.parse(prefix + sb, expectThinking = prefix.isNotEmpty()).answer)
                            val now = System.currentTimeMillis()
                            if (now - lastPub >= FRAME_MS) {
                                lastPub = now; onText(prefix + sb)
                                act(stepTag + com.iris.localai.util.Activity.phaseOf(prefix + sb))
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
            val d = done
            // 1.10.5: first-token diagnostics — prefill is everything before the first token.
            android.util.Log.i("IrisAgent", "turn done: prefill=${lastStats?.prefillMs ?: -1}ms gen=${lastStats?.generateMs ?: -1}ms len=${sb.length}")
            if (d != null && d.reason == StopReason.CANCELLED && !cut) throw kotlinx.coroutines.CancellationException("stopped by user")
            return com.iris.localai.agent.ModelTurn(
                prefix + sb.toString(),
                error = d?.takeIf { it.reason == StopReason.ERROR && sb.isBlank() }?.let { it.error ?: "生成失败" },
            )
          }
        }
        val callbacks = object : com.iris.localai.agent.AgentCallbacks {
            override suspend fun confirm(tool: com.iris.localai.agent.AgentTool, call: com.iris.localai.agent.ToolCall): com.iris.localai.agent.UserDecision {
                // 2.1.7: no dialog in a voice call — decline, and say it needs the screen.
                if (voiceOut != null) {
                    voiceCue?.invoke(com.iris.localai.voice.VoiceCues.CONFIRM_ON_SCREEN)
                    notice.value = "语音对话中不执行需要确认的操作（${tool.label}），请在屏幕上发送后确认"
                    return com.iris.localai.agent.UserDecision.DENY
                }
                val d = kotlinx.coroutines.CompletableDeferred<com.iris.localai.agent.UserDecision>()
                _stream.value = null
                val prefix = if (tool.name == com.iris.localai.agent.RunCommandTool.name)
                    com.iris.localai.agent.Args.str(call.args, "command", "cmd")?.let { com.iris.localai.agent.CommandRules.prefixOf(it) }?.takeIf { it.isNotBlank() } else null
                _permissionAsk.value = PermissionAsk(tool.label, tool.name, agentPreview(tool, call), d, prefix)
                try { return d.await() } finally { if (_permissionAsk.value?.answer === d) _permissionAsk.value = null }
            }
            override fun onSteps(steps: List<com.iris.localai.agent.AgentStep>) {
                setSteps(steps)
                val run = steps.lastOrNull()?.takeIf { it.status == com.iris.localai.agent.StepStatus.RUNNING || it.status == com.iris.localai.agent.StepStatus.ASKING }
                val t = run?.let { r -> com.iris.localai.agent.AgentTools.byName(r.tool) }
                com.iris.localai.util.SystemStatus.update { it.copy(tool = t?.label ?: run?.tool, toolGroup = t?.group?.name, stepsDone = steps.count { st -> st.status == com.iris.localai.agent.StepStatus.OK }) }
                steps.lastOrNull()?.let { st ->
                    val label = com.iris.localai.agent.AgentTools.all.firstOrNull { it.name == st.tool }?.label ?: st.tool
                    when (st.status) {
                        com.iris.localai.agent.StepStatus.ASKING -> act("等待你确认：$label")
                        com.iris.localai.agent.StepStatus.RUNNING -> act("运行工具：$label" + st.result.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                        else -> {}
                    }
                }
            }
            override fun onText(text: String) { _stream.value = StreamingText(reply.id, text) }
            override fun onAlwaysAllowCall(tool: com.iris.localai.agent.AgentTool, call: com.iris.localai.agent.ToolCall) {
                // 1.12.0: 总是允许 on run_command remembers this command's prefix ("git status"), not every command.
                if (tool.name == com.iris.localai.agent.RunCommandTool.name) {
                    val cmd = com.iris.localai.agent.Args.str(call.args, "command", "cmd", "shell", "script").orEmpty()
                    val prefix = com.iris.localai.agent.CommandRules.prefixOf(cmd)
                    if (prefix.isNotEmpty()) {
                        c.agent.settings.update { it.copy(commandRules = it.commandRules + (prefix to com.iris.localai.agent.Permission.ALLOW.name)) }
                        notice.value = "以后「$prefix」开头的命令不再询问（可在 设置 › 智能体 › 命令规则 修改）"
                        return
                    }
                }
                onAlwaysAllow(tool)
            }
            override fun onAlwaysAllow(tool: com.iris.localai.agent.AgentTool) {
                c.agent.settings.update { com.iris.localai.agent.PermissionPolicy.afterDecision(it, tool, com.iris.localai.agent.UserDecision.ALWAYS_ALLOW) }
            }
            override fun onWorkspaceChanged() = c.agent.changed()
            override fun onMemoryChanged() = c.agent.memoryChanged()
            override suspend fun beforeTool(tool: com.iris.localai.agent.AgentTool, call: com.iris.localai.agent.ToolCall) {
                // 2.1.7: a fixed short phrase before the first tool (no tokens); tool blocks never reach TTS.
                if (voiceOut != null && toolCues++ == 0) voiceCue?.invoke(com.iris.localai.voice.VoiceCues.before(tool.name, 0))
            }
        }
        // 1.12.0 process_file: one fresh prompt per part (no history, no thinking), output up to
        // half the window; the part itself about 55% of that (translations grow).
        val chunkMax = (ctxTokens / 2).coerceIn(512, 4096)
        val chunkIn = minOf((chunkMax * 0.55).toInt(), ctxTokens - chunkMax - 400).coerceAtLeast(200)
        val chunkSampling = c.engine.samplingFor(true).copy(maxNewTokens = chunkMax, contextShift = false)
        val chunkLlm = com.iris.localai.agent.ChunkLlm { system, user ->
            val sb = StringBuilder()
            val filter = com.iris.localai.util.StopTokenFilter()
            var stopped = false
            c.engine.generate(listOf(PromptMessage(PromptMessage.SYSTEM, system), PromptMessage(PromptMessage.USER, user)), chunkSampling, Requester.CHAT, engineOwner).collect { ev ->
                when (ev) {
                    is GenEvent.Piece -> {
                        com.iris.localai.service.GenerationMonitor.tick()
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
        com.iris.localai.util.SystemStatus.update { it.copy(agentRunning = true, tool = null, toolGroup = null, stepsDone = 0) }
        var plan: com.iris.localai.util.ContextPlan? = null
        try {
            awaitingEngine = false
            freezeMemory(agentMode = true)
            val folded = compactIfNeeded(agentMode = true, ready = ready, maxNew = maxNew, manual = false)
            if (folded > 0) notice.value = "对话较长，已压缩较早的 $folded 条消息（不调用模型）"
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { planFor(conversation.value, ready, voice = false, agent = true, maxNewOverride = maxNew) }
            plan = p
            val history = p.messages.filter { it.role != PromptMessage.SYSTEM }
            // 2.3: stable system prompt (persona + tools + instructions + frozen memory); the plan goes on the new user turn.
            val base = listOf(PromptMessage(PromptMessage.SYSTEM, agentSystemFor(conversation.value, ready, tools))) +
                com.iris.localai.agent.GuiIntent.withHint(com.iris.localai.agent.AgentPrompt.withPlanNote(history, c.agent.plans.get(convId)), tools.any { it.name == "screen" })
                    .let { if (voiceOut != null) com.iris.localai.voice.VoiceStyle.withVoiceSystem(it) else it }
            val budget = (ctxTokens - maxNew - 32).coerceAtLeast(ctxTokens / 3)
            // 1.10.6: continue from the previous 智能体 run's full prompt when nothing in between
            // changed, so the engine reuses its KV cache (fast first token) and the model still sees
            // the earlier tool calls and results.
            val runBase = continuedAgentBase(convId, reply.id, base, folded, budget) ?: base
            android.util.Log.i("IrisAgent", "base: ${if (runBase === base) "rebuilt" else "continued"} msgs=${runBase.size} prompt~${runBase.sumOf { com.iris.localai.util.TokenEstimator.message(it.content) }}tok")
            val loop = com.iris.localai.agent.AgentLoop(
                model = model, tools = tools,
                permissionOf = { t -> c.agent.settings.current.permission(t) },
                env = c.agent.env(convId, setOfNotNull(conversation.value.messages.lastOrNull { it.role == Role.USER }?.id, reply.id),
                    llm = chunkLlm, chunkTokens = chunkIn, enabledTools = tools),
                callbacks = callbacks, maxSteps = ag.maxSteps,
                contextBudget = budget,
                answerOf = { ThinkParser.parse(it, expectThinking = prefix.isNotEmpty()).answer },
                runTool = { block -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() } },
                // 2.1.7: 补充 typed during the run reach the model before its next step.
                pendingNotes = { takeAgentNotes(convId) },
            )
            agentTranscript = null
            val o = loop.run(runBase)
            outcome = o
            // 2.1.7: only the final answer is spoken (no tool blocks, no reasoning). 2.1.9: most of it
            // already went out while it streamed; finish speaks what is still unsaid, once.
            voiceStream?.finish(ThinkParser.parse(o.finalText, expectThinking = prefix.isNotEmpty()).answer)
            android.util.Log.i(GEN_TAG, "agent: stop=${o.stop} steps=${o.steps.size} tools=${tools.size}" + (o.error?.let { " error=$it" } ?: ""))
            if (o.stop == com.iris.localai.agent.AgentStop.MAX_STEPS) notice.value = "已达到最大步数（${ag.maxSteps} 步），智能体已停止调用工具"
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw e
        } catch (t: Throwable) {
            android.util.Log.w(GEN_TAG, "agent run failed", t)
            failure = t.message ?: t.javaClass.simpleName
        } finally {
            agentRunning = false
            com.iris.localai.util.SystemStatus.update { it.copy(agentRunning = false, tool = null, toolGroup = null) }
            _permissionAsk.value?.let { it.answer.cancel(); _permissionAsk.value = null }
            timer.finish()
            val o = outcome
            // 2.1.7: pictures made by generate_image join the reply as ![](images/IMG_….png) (unless already shown).
            val finalText = com.iris.localai.agent.StepImages.appendTo((o?.finalText ?: (_stream.value?.text ?: "")).trim(), steps)
            val finalSteps = steps.map { st ->
                if (st.status == com.iris.localai.agent.StepStatus.RUNNING || st.status == com.iris.localai.agent.StepStatus.ASKING)
                    st.copy(status = com.iris.localai.agent.StepStatus.ERROR, result = if (cancelled) "已停止" else "未完成")
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
            if (background) com.iris.localai.service.GenerationMonitor.finish(convId, timer.chars, cancelled)
            if (!cancelled && voiceOut == null) _replyFinished.tryEmit(err == null)
            conversation.update { it.copy(updatedAt = System.currentTimeMillis()) }
            persist()
            runLimitJob?.cancel()
            // 1.14.0 心跳: a plan with steps left is checked again later (done or stopped).
            runCatching {
                val pl = c.agent.plans.get(convId)
                if (pl != null && !pl.finished) com.iris.localai.agent.Heartbeat.arm(getApplication(), convId, hbAttempt, c.agent.settings.current)
                else com.iris.localai.agent.Heartbeat.cancel(getApplication(), convId)
            }
            plan?.let { pl ->
                // 1.13.0: the agent prompt (tool list + earlier tool results) is far bigger than the
                // chat plan; measure what was really sent so the meter does not undercount.
                val sent = o?.transcript?.sumOf { com.iris.localai.util.TokenEstimator.message(it.content) } ?: 0
                recordUsage(convId, pl, lastStats, finalText, ready, agentPromptTokens = sent)
            }
        }
    }

    private var warmedKey: String? = null
    private var warmJob: Job? = null
    private val _warming = MutableStateFlow<com.iris.localai.util.Activity?>(null)
    /** 1.10.10: 「预热上下文」 while the open conversation is prefilled after a model switch. */
    val warming: StateFlow<com.iris.localai.util.Activity?> = _warming.asStateFlow()

    private fun scheduleWarmUp() {
        if (warmJob?.isActive == true) return
        warmJob = viewModelScope.launch {
            kotlinx.coroutines.delay(WARM_DELAY_MS)
            val ready = c.engine.state.value as? EngineState.Ready ?: return@launch
            if (!WarmUp.supported(ready.model.format)) return@launch
            if (busy() || streamingId.value != null) return@launch
            val conv = conversation.value
            if (!WarmUp.worthIt(conv.messages.size, conv.messages.lastOrNull()?.role == Role.ASSISTANT)) return@launch
            val agent = c.agent.settings.current.agentMode
            val prompt = runCatching { warmPrompt(conv, ready, agent) }.getOrNull() ?: return@launch
            val disableThinking = !c.engine.effective().thinking
            val sampling = c.engine.samplingFor(disableThinking).copy(maxNewTokens = 1, contextShift = false)
            _warming.value = com.iris.localai.util.Activity("预热上下文（换模型后首次预填充）")
            val t0 = System.currentTimeMillis()
            try {
                c.engine.generate(prompt, sampling, Requester.CHAT, engineOwner).collect { ev -> if (ev is GenEvent.Piece) c.engine.cancel(engineOwner) }
                android.util.Log.i("IrisAgent", "warm-up done in ${System.currentTimeMillis() - t0}ms msgs=${prompt.size} agent=$agent")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("IrisAgent", "warm-up failed", t)
            } finally {
                _warming.value = null
            }
        }
    }

    /** The prompt the next request will start with, minus its new user message. */
    private fun warmPrompt(conv: Conversation, ready: EngineState.Ready, agent: Boolean): List<PromptMessage>? {
        if (!agent) return planFor(conv, ready, voice = false).messages.takeIf { it.isNotEmpty() }
        val t = agentTranscript
        if (t != null && t.convId == conv.id && conv.messages.lastOrNull()?.id == t.anchorId) return t.messages
        val ag = c.agent.settings.current
        val memoryOn = c.agent.memorySettings.current.enabled
        val tools = com.iris.localai.agent.AgentTools.enabled(ag, c.agent.history != null, memoryOn, c.agent.knowledge.nonEmptyCached(), c.agent.imageGenOnCached())
        val eff = c.engine.effective()
        val maxNew = minOf(eff.maxNewTokens, AGENT_MAX_NEW)
        freezeMemory(agentMode = true)
        val p = planFor(conversation.value, ready, voice = false, agent = true, maxNewOverride = maxNew)
        return listOf(PromptMessage(PromptMessage.SYSTEM, agentSystemFor(conversation.value, ready, tools))) +
            p.messages.filter { it.role != PromptMessage.SYSTEM }
    }

    /** 1.10.6: every prompt sent for a reply, for 「查看提示内容」. */
    private val promptLog = com.iris.localai.util.PromptLog()

    /** 1.10.6: readable dump of everything sent to the model for [replyId]. */
    fun promptText(replyId: String): String = com.iris.localai.util.PromptLog.format(promptLog.get(replyId))

    /** 1.10.6: the previous 智能体 run's prompt, continued by the next turn (see [continuedAgentBase]). */
    private data class AgentTranscript(val convId: String, val anchorId: String, val anchorText: String, val messages: List<PromptMessage>)
    private var agentTranscript: AgentTranscript? = null

    /**
     * The previous run's transcript + the new user turn, when the new turn directly follows that
     * run's answer (same conversation, nothing edited or compacted, same system prompt) and it fits
     * [budget]; null = build the prompt from the chat history as usual.
     */
    private fun continuedAgentBase(convId: String, replyId: String, base: List<PromptMessage>, folded: Int, budget: Int): List<PromptMessage>? {
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
        fun size(m: List<PromptMessage>) = m.sumOf { com.iris.localai.util.TokenEstimator.message(it.content) }
        val next = t.messages + newUser
        if (size(next) <= budget) return next
        // 1.13.0: over budget → shorten the earlier tool outputs instead of rebuilding from the chat
        // history (which kept only ~50 chars of each result, so found facts were lost).
        val short = next.mapIndexed { idx, m ->
            if (idx < next.size - 1 && m.role == PromptMessage.USER && m.content.startsWith("<tool_response>"))
                m.copy(content = com.iris.localai.agent.AgentLoop.shortenToolOutput(m.content, com.iris.localai.agent.AgentLoop.EARLIER_KEEP))
            else m
        }
        return short.takeIf { size(it) <= budget }
    }

    /** Engine-measured tokens in the window after a reply, or the plan's estimate plus the reply. */
    private fun recordUsage(
        convId: String, plan: com.iris.localai.util.ContextPlan, stats: com.iris.localai.data.GenerationStats?,
        reply: String, ready: EngineState.Ready?, agentPromptTokens: Int = 0,
    ) {
        val used = if (agentPromptTokens > 0 && (stats == null || stats.kvUsed <= 0)) agentPromptTokens else when {
            stats == null -> plan.estimatedTokens + com.iris.localai.util.TokenEstimator.estimate(reply)
            stats.kvUsed > 0 -> stats.kvUsed
            stats.promptTokens > 0 -> stats.promptTokens + stats.generatedTokens
            else -> plan.estimatedTokens + stats.generatedTokens.coerceAtLeast(com.iris.localai.util.TokenEstimator.estimate(reply))
        }
        measured.update { it + (convId to used) }
        val limit = ready?.details?.contextLength ?: return
        if (limit > 0 && used >= limit * ContextUsage.NEAR_FULL && nearFullWarned.add(convId)) {
            notice.value = "上下文已用 ${used * 100 / limit}%：可在输入框上方的上下文菜单里改用「自动摘要」，或「从这里开始新上下文」"
        }
    }

    // ------------------------------------------------------------------ voice bridge

    override fun ask(userText: String): Flow<String> = channelFlow {
        // 1.13.0: never a second reply into a chat that is still running in the background.
        if (viewing.value != null || genJob?.isActive == true) {
            notice.value = "另一个对话还在运行，完成或停止后再用语音"
            return@channelFlow
        }
        append(ChatMessage(role = Role.USER, content = userText))
        val deltas = Channel<String>(Channel.UNLIMITED)
        // 2.1.7: only speakable text reaches TTS (no markdown / code / links / emoji / boilerplate,
        // ≤ 3 sentences; the bubble keeps the full reply). Tool cues pass straight through.
        val speech = com.iris.localai.voice.VoiceSpeech()
        voiceCue = { cue -> speech.cue(cue).forEach { deltas.trySend(it) } }
        val job = startReply(Requester.VOICE) { d -> speech.push(d).forEach { deltas.trySend(it) } }
        job.invokeOnCompletion { speech.flush().forEach { deltas.trySend(it) }; voiceCue = null; deltas.close() }
        for (d in deltas) send(d)
    }.buffer(Channel.UNLIMITED)

    /** 2.1.7: speaks a fixed short phrase in the running voice call (「我查一下」); null = not in a call. */
    @Volatile private var voiceCue: ((String) -> Unit)? = null

    override fun cancelReply() = stop()

    // ------------------------------------------------------------------ read aloud

    fun speak(messageId: String) {
        stopSpeaking()
        val msg = (viewing.value ?: conversation.value).messages.firstOrNull { it.id == messageId } ?: return
        if (!c.synthesizer.isAvailable) {
            notice.value = "朗读需要先在 设置 › 语音 中下载语音合成模型"
            return
        }
        val text = ThinkParser.parse(msg.content).answer
        _speakingMessageId.value = messageId
        val token = Any().also { speakToken = it }
        c.synthesizer.claim(token) { stopSpeaking() }
        speakJob = viewModelScope.launch {
            var player: PcmPlayer? = null
            try {
                c.synthesizer.prepare()
                val out = PcmPlayer(c.synthesizer.sampleRate).also { speakPlayer = it }
                player = out
                val s = c.settings.current
                val splitter = SentenceSplitter()
                for (sentence in splitter.push(text) + splitter.flush()) {
                    // Same cleanup as the voice conversation (links, code, emoji, list markers…).
                    val speakable = com.iris.localai.util.SpeechText.speakable(sentence)
                    if (speakable.none { it.isLetterOrDigit() }) continue
                    c.synthesizer.speak(speakable, s.ttsSpeakerId, s.ttsSpeed) { out.write(it) }
                }
                out.drain() // the last ~0.5 s is still queued when the final write returns
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("ChatViewModel", "read aloud failed", t)
                notice.value = "朗读出错：${t.message ?: t.javaClass.simpleName}"
            } finally {
                // Only clean up our own state: when another message's 朗读 replaced this one, the
                // old finally used to run after the new start and wipe the new speaking indicator.
                player?.release()
                if (speakPlayer === player) speakPlayer = null
                if (speakToken === token) { _speakingMessageId.value = null; speakToken = null }
                c.synthesizer.unclaim(token)
            }
        }
    }

    fun stopSpeaking() {
        speakPlayer?.stop()
        speakJob?.cancel()
        speakJob = null
        speakToken = null
        _speakingMessageId.value = null
    }

    override fun onCleared() {
        com.iris.localai.agent.AgentBus.runner = null
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(awayObserver)
        voice.stop()
        dictation.cancel()
        stopSpeaking()
        super.onCleared()
    }

    private companion object {
        const val FRAME_MS = 33L
        const val GEN_TAG = "IrisGen"
        /** 1.7.7: shown in the reply bubble while the request waits for a tier reload. */
        const val RELOADING_STATUS = "正在重载模型…"
        /** 2.1.8: the reply waits for a model load (bubble shown right away, 停止 works). */
        const val LOADING_STATUS = "正在加载模型…"
        const val LOAD_FAILED = "模型加载失败"
        /** 2.1.8: shown on a reply the watchdog ended. */
        const val STALL_ERROR = "长时间没有输出，已停止。可重试或换模型"
        const val PERSIST_MS = 3_000L
        /** 1.9.0: reply cap per agent turn (tool calls are short; small windows need the room for results). */
        const val AGENT_MAX_NEW = 1536
        const val WARM_DELAY_MS = 600L
        const val ATTACH_NO_VISION = "当前模型看不到图片，只会收到图片的文件名。换一个支持视觉的模型（如 Gemma 3n、Qwen-VL）后可识图"
    }
}

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
    /** 2.2.9 E: history messages the model sees in the next prompt (planFor). */
    val visibleMessages: Int = 0,
    /** 2.3.2: tokens of knowledge snippets in the next prompt (part of [used]). */
    val knowledgeTokens: Int = 0,
) {
    val fraction: Float get() = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val nearFull: Boolean get() = limit > 0 && used >= limit * NEAR_FULL
    companion object { const val NEAR_FULL = 0.85f }
}

/** Text of the reply being streamed, exposed apart from the conversation so only that message recomposes. */
data class StreamingText(val id: String, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app), VoiceChatBridge {

    internal val c = (app as IrisApp).container
    // 2.3.5 fix: declared first — [state] starts Eagerly in the constructor and reads these through
    // usageFor → keptKb / turnBlocks; declared further down they were still null there and a restored
    // conversation with messages (对话 mode, 知识库 in chat) crashed at launch (synchronized on null).
    internal val kbBlocks = LinkedHashMap<String, com.iris.localai.knowledge.KbBlock>()
    internal val memBlocks = LinkedHashMap<String, String>()
    /**
     * 1.14.3: owner of every generation this screen starts (replies, voice, agent turns, summaries,
     * process_file parts, warm-up). 停止 and the stop filters cancel only these, never an API
     * client's generation that happens to hold the engine.
     */
    internal val engineOwner = com.iris.localai.engine.GenOwner("chat")
    val settings = c.settings.settings
    val summaries = c.conversations.summaries
    val models = c.models.models
    val engineBusy = c.engine.busy

    /** The conversation a reply is written into (also the one shown, unless [viewing] is set). */
    internal val conversation = MutableStateFlow(Conversation())
    /**
     * 1.12.3: switching conversations no longer stops a running reply/agent run. It keeps writing
     * into [conversation] while the user looks at [viewing]; when it ends, [viewing] becomes the
     * conversation again. null = the user is looking at [conversation].
     */
    internal val viewing = MutableStateFlow<Conversation?>(null)
    internal val shown = combine(conversation, viewing) { w, v -> v ?: w }
    /** Id of the conversation running in the background while another is shown (null = none). */
    val backgroundRunId: StateFlow<String?> = combine(viewing, conversation) { v, w -> if (v != null) w.id else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    internal fun viewingOther(): Boolean {
        if (viewing.value == null) return false
        notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d9eb6f) + conversation.value.title.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_83aa8a) } + com.iris.localai.I18n.s(com.iris.localai.R.string.chat_9edf20)
        return true
    }
    internal val streamingId = MutableStateFlow<String?>(null)
    /** 2.3.4: model loaded before a card chat swapped in its bound model (path); offered back once on leaving. */
    internal var preCardModelPath: String? = null
    internal val _modelRestore = MutableStateFlow<LocalModel?>(null)
    /** One-time 「已切回普通对话，是否换回原模型 X？」 hint; null = none. */
    val modelRestore: StateFlow<LocalModel?> = _modelRestore
    internal val notice = MutableStateFlow<String?>(null)
    fun showNotice(text: String) { notice.value = text }

    /** Last measured usage per conversation id (engine numbers after a reply). */
    internal val measured = MutableStateFlow<Map<String, Int>>(emptyMap())
    internal val summarizing = MutableStateFlow(false)
    internal val nearFullWarned = HashSet<String>()

    internal val base = combine(shown, streamingId, c.engine.state, settings, notice) { conv, sid, eng, s, n ->
        // 1.12.3: the reply streaming in the background is not this screen's.
        ChatUiState(conv, sid?.takeIf { viewing.value == null }, eng, s.deepThinking, n)
    }

    // 1.10.0: the context readout also follows the mode switch and the memory settings.
    internal val base2 = combine(base, c.agent.settings.settings, c.agent.memorySettings.settings, c.agent.memoryRevision) { st, _, _, _ -> st }

    val state: StateFlow<ChatUiState> = combine(base2, c.modelConfigs.configs, measured, summarizing) { st, cfgs, meas, summ ->
        val ready = st.engine as? EngineState.Ready
        val s = c.settings.current
        val eff = com.iris.localai.data.EffectiveModelConfig.resolve(s, ready?.model?.format, ready?.model?.id?.let { cfgs[it] })
        st.copy(deepThinking = eff.thinking, context = usageFor(st.conversation, ready, meas[st.conversation.id], summ))
    }
        // 2.3.5 fix: first value after the constructor has finished (Eagerly + Main.immediate ran
        // usageFor inside <init>, before later fields were initialised).
        .onStart { kotlinx.coroutines.yield() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /** 1.13.1: the running reply's timer — the speed overlay reads its visible-character count. */
    @Volatile var liveTimer: com.iris.localai.util.ReplyTimer? = null
        internal set

    @Volatile internal var usageMemo: Pair<List<Any?>, Pair<Int, Int>>? = null

    internal fun usageFor(conv: Conversation, ready: EngineState.Ready?, measuredTokens: Int?, summ: Boolean): ContextUsage {
        val s = c.settings.current
        val strategy = com.iris.localai.data.ContextStrategy.of(conv.contextStrategy ?: s.contextStrategy)
        val limit = ready?.details?.contextLength ?: 0
        val agentMode = c.agent.settings.current.agentMode
        // 1.13.1: memoized — this planned the whole conversation on the main thread at every state
        // change (streamingId, engine busy, notices…), which made the start of a reply stutter.
        val planned = if (conv.messages.isEmpty()) 0 to 0 else {
            val key = listOf(conv, ready, agentMode, s, c.agent.memorySettings.current.epoch)
            usageMemo?.takeIf { it.first == key }?.second
                ?: planFor(conv, ready, voice = false, agent = agentMode).let { it.estimatedTokens to it.visibleMessages }.also { usageMemo = key to it }
        }
        val used = measuredTokens ?: planned.first
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
            visibleMessages = planned.second,
            memoryInjected = memoryInjectFor(agentMode) && !conv.memorySnapshot.isNullOrBlank() && conv.memoryEpoch == c.agent.memorySettings.current.epoch,
            knowledgeTokens = if (agentMode || !s.knowledgeInChat) 0 else keptKb(conv, contextTokensFor(ready)).values.sumOf { it.tokens },
        )
    }

    internal var genJob: Job? = null

    /**
     * While a reply streams, its text lives here (not in [conversation]): updating the conversation
     * 30× a second recomposed the whole chat screen and every message, which is what made long
     * thinking output stutter. The conversation gets the text once, when the reply is finished.
     */
    internal val _stream = MutableStateFlow<StreamingText?>(null)
    val stream: StateFlow<StreamingText?> = _stream.asStateFlow()

    /**
     * 1.7.7: status of the reply being prepared (shown in its bubble while it has no text yet), e.g.
     * 「正在重载模型…」 while the request waits for a resource-tier reload. null = none.
     */
    /** 1.10.9: what the model is doing right now, for the small indicator above the composer. */
    internal val _activity = MutableStateFlow<com.iris.localai.util.Activity?>(null)
    val activity: StateFlow<com.iris.localai.util.Activity?> = _activity.asStateFlow()
    internal fun act(label: String?) { if (_activity.value?.label != label) _activity.value = label?.let { com.iris.localai.util.Activity(it) } }

    /** 1.10.9: 「取消加载」. */
    /**
     * 2.1.7 弹窗可取消: abandon the running load (the engine releases it when the native call
     * returns) and cancel our own load coroutine, so a load queued behind it never starts either.
     */
    fun cancelLoad() {
        c.engine.cancelLoad()
        loadJob?.cancel(); loadJob = null
    }

    internal val _replyStatus = MutableStateFlow<String?>(null)
    val replyStatus: StateFlow<String?> = _replyStatus.asStateFlow()

    /** 1.7.7: true until the reply's generate call is running (stop then cancels the job itself). */
    @Volatile internal var awaitingEngine = false

    /** Bumped when a notification asks to show a conversation: navigation returns to the chat. */
    internal val _showChat = MutableStateFlow(0)
    val showChat: StateFlow<Int> = _showChat.asStateFlow()
    fun openFromNotification(id: String) {
        _showChat.value++
        open(id)
    }

    // 1.10.13: did the system pause the reply while IRIS was in the background?
    internal var awayAt = 0L
    internal var awayPieces = 0L
    internal val awayObserver = androidx.lifecycle.LifecycleEventObserver { _, e ->
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
    // 2.2.10 终止温度: stop the reply / agent run (online ones too) and read-aloud; IrisApp unloads models.
    init {
        viewModelScope.launch {
            com.iris.localai.engine.ThermalCutoff.trips.collect { t ->
                runCatching { stop() }
                runCatching { stopSpeaking() }
                notice.value = com.iris.localai.engine.ThermalCutoff.notice(t)
            }
        }
    }

    // ---- voice ----
    val voice = VoiceController(viewModelScope, c.recognizer, c.synthesizer, c.settings, this)
    val dictation = Dictation(viewModelScope, c.recognizer)
    val voiceStore = c.voiceStore
    /** 2.2.17 C: text to put into the chat input (下载模型 › 「试一试」); the screen takes it and clears it. */
    internal val _draft = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val draft = _draft.asStateFlow()
    fun setDraft(text: String?) { _draft.value = text }
    /**
     * 2.1.0: a chat reply finished (true) or failed (false); stopped replies emit nothing. Not
     * replayed: the UI collects it only while resumed, so a reply finishing in the background
     * gives no feedback.
     */
    internal val _replyFinished = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val replyFinished: kotlinx.coroutines.flow.SharedFlow<Boolean> = _replyFinished
    internal val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()
    internal var speakJob: Job? = null
    internal var speakPlayer: PcmPlayer? = null
    internal var speakToken: Any? = null

    init {
        // 1.7.7: reload failures / GPU→CPU fallbacks from the engine are shown, never swallowed.
        viewModelScope.launch { c.engine.notices.collect { notice.value = it } }
        // 2.3.4: leaving a card chat → one-time hint to switch back to the pre-card model (never automatic).
        viewModelScope.launch {
            var lastCard: String? = null
            shown.map { it.characterId }.distinctUntilChanged().collect { card ->
                val prev = preCardModelPath
                val path = com.iris.localai.character.ModelRestore.offer(lastCard, card, prev, c.engine.readyModel?.path)
                if (lastCard != null && card == null) {
                    preCardModelPath = null
                    path?.let { p -> c.models.models.value.firstOrNull { it.path == p }?.let { _modelRestore.value = it } }
                }
                if (card != null) _modelRestore.value = null
                lastCard = card
            }
        }
        // 2.2.17 D 「最近提示」: every banner, plus load failures / fallback notes, can be read again later.
        viewModelScope.launch { notice.collect { n -> com.iris.localai.util.HintLog.add(n, com.iris.localai.util.HintLog.Source.CHAT) } }
        viewModelScope.launch {
            c.engine.state.collect { st ->
                when (st) {
                    is EngineState.Failed -> com.iris.localai.util.HintLog.add(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c4fa9b) + st.message, com.iris.localai.util.HintLog.Source.MODEL)
                    is EngineState.Ready -> st.details.fallbackNote?.let { com.iris.localai.util.HintLog.add(st.model.displayName + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dec892) + it, com.iris.localai.util.HintLog.Source.MODEL) }
                    else -> {}
                }
            }
        }
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
        c.conversations.purgeHook = { conv -> discardAttachments(com.iris.localai.data.MessageVersions.allAttachments(conv.messages)) }
        viewModelScope.launch {
            c.conversations.init()
            c.models.refresh()
            // 1.10.2: the last model is preloaded by IrisApp at process start (EngineManager.preloadAtStart),
            // no longer only once the chat screen exists.
        }
    }

    // ------------------------------------------------------------------ conversations

    /** 1.12.3: a reply/agent run is going on; switching conversations leaves it running. */
    internal fun runningInBackground() = genJob?.isActive == true

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

    internal fun noteBackground() {
        notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d9eb6f) + conversation.value.title.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_73aba3) } + com.iris.localai.I18n.s(com.iris.localai.R.string.chat_51bf65)
    }

    /** 1.12.3: the background run ended — the conversation on screen becomes the working one. */
    internal fun adoptViewing() {
        val v = viewing.value ?: return
        viewing.value = null
        conversation.value = v
        viewModelScope.launch { if (v.messages.isNotEmpty()) c.conversations.load(v.id)?.let { if (viewing.value == null && conversation.value.id == it.id) conversation.value = it } }
    }

    // ---- 2.2.14: 回收站 ----
    val trash = c.conversations.trash
    fun restoreChat(id: String) = viewModelScope.launch { deletedIds -= id; c.conversations.restore(id) }
    fun purgeChat(id: String) = viewModelScope.launch { c.conversations.purge(id) }
    fun emptyTrash() = viewModelScope.launch { c.conversations.emptyTrash() }

    fun delete(id: String) = viewModelScope.launch {
        if (viewing.value?.id == id) viewing.value = Conversation()
        if (conversation.value.id == id) {
            // 1.13.0: wait for the reply to end, or its last save would bring the chat back.
            stop()
            genJob?.let { j -> kotlinx.coroutines.withTimeoutOrNull(3000) { j.join() } ?: j.cancelAndJoin() }
            deletedIds += id
        }
        // 2.2.14: to 回收站; attachment copies are discarded only when it is purged (purgeHook).
        c.conversations.delete(id)
        c.agent.plans.clear(id)
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

    fun dismissModelRestore() { _modelRestore.value = null }
    fun acceptModelRestore() { _modelRestore.value?.let { loadModel(it) }; _modelRestore.value = null }

    // ------------------------------------------------------------------ models

    /** 2.1.7: the load started from this screen (picker / logo), cancellable by 「取消」. */
    internal var loadJob: Job? = null
    fun loadModel(model: LocalModel): Job = viewModelScope.launch {
        if (genJob?.isActive == true) { notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7a0481); return@launch }
        warmJob?.cancel() // 2.4.0: a pending warm-up of the old model never delays the switch
        c.engine.load(model)
    }.also { loadJob = it }
    /** 2.4.0: a tap on another model while a reply / background run goes used to do nothing at all. */
    fun noteSwitchBlocked() {
        notice.value = if (viewing.value != null) "另一个对话正在后台生成，结束或停止后才能切换模型"
            else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7a0481)
    }
    fun unloadModel() = viewModelScope.launch {
        if (genJob?.isActive == true) { notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4a1a73); return@launch }
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
        notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_adc46a)
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

    internal fun memoryInjectFor(agentMode: Boolean): Boolean =
        c.agent.memorySettings.current.inject(agentMode) && !c.agent.memoryDocs.isEmpty()

    internal fun contextTokensFor(ready: EngineState.Ready?): Int =
        ready?.details?.contextLength?.takeIf { it > 0 } ?: c.engine.effective(ready?.model).contextLength

    /**
     * 2.1.0 引用知识库: user message id → snippet block added to it in the prompt (对话 mode).
     * 2.3: kept for every message answered with snippets (was: only the newest), so the next turn
     * sends the history the engine already holds and its KV cache is reused instead of the whole
     * conversation being prefilled again. Bounded ([KbChat.MAX_KEPT]); not persisted.
     */
    // (kbBlocks: declared at the top of the class — see 2.3.5 fix)

    /**
     * 2.2.9 C: user message id → its 【相关记忆】 block (FACT items relevant to it), put in front of
     * that message like the knowledge block; kept per message so the history stays byte-stable.
     */
    // (memBlocks: declared at the top of the class — see 2.3.5 fix)

    /** kbBlocks + memBlocks, memory first, for [KbChat.attachAll]. */
    internal fun turnBlocks(conv: Conversation, nCtx: Int): Map<String, String> {
        val kb = keptKb(conv, nCtx).mapValues { it.value.text }
        val mem = memOnly()
        if (mem.isEmpty()) return kb
        return (mem.keys + kb.keys).associateWith { listOfNotNull(mem[it], kb[it]).joinToString("\n\n") }
    }

    /** Non-empty memory blocks ("" = looked, nothing relevant: kept so the message stays as answered). */
    internal fun memOnly(): Map<String, String> = synchronized(memBlocks) { memBlocks.filterValues { it.isNotEmpty() } }

    /** 「压缩上下文」 in the context sheet. */
    fun compactNow() {
        if (viewingOther()) return
        if (busy()) { notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_94d50d); return }
        val agentMode = c.agent.settings.current.agentMode
        val ready = c.engine.state.value as? EngineState.Ready
        val maxNew = if (agentMode) minOf(c.engine.effective().maxNewTokens, AGENT_MAX_NEW) else c.engine.effective().maxNewTokens
        val n = compactIfNeeded(agentMode, ready, maxNew, manual = true)
        notice.value = if (n > 0) ContextText.compactNotice(n) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5aa0db)
    }

    /** Puts the compacted messages back into the context (they were never deleted). */
    fun clearCompaction() {
        if (viewingOther()) return
        conversation.update { it.copy(compaction = null) }
        measured.update { it - conversation.value.id }
        persist()
        notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d4d648)
    }
    fun onUserActivity() = c.engine.touch()
    fun markNotificationAsked() = c.settings.update { it.copy(askedNotificationPermission = true) }
    fun markBatteryAsked() = c.settings.update { it.copy(askedBatteryExemption = true) }

    // ------------------------------------------------------------------ messaging

    /** 2.1.9: the guide to show after `screen` was blocked (无障碍 off / no vision model); null = none. */
    // 2.2.14: the overlay's 停止 also stops this chat's agent turn.
    internal val guiStopHook: () -> Unit = { viewModelScope.launch { stop() } }
    init { c.gui.onUserStop = guiStopHook }
    val guiSetup: StateFlow<com.iris.localai.agent.GuiBlocker?> get() = c.gui.setup
    fun dismissGuiSetup() = c.gui.dismissSetup()

    /** 2.1.9: a 对话-mode message that asks to operate the phone, while `screen` is enabled. */
    internal fun guiTurn(text: String): Boolean {
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
            notice.value = if (ready.model.format == com.iris.localai.data.ModelFormat.GGUF) com.iris.localai.engine.llama.MmprojPairing.NO_MMPROJ_HINT else ATTACH_NO_VISION
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

    internal val sendQueue = SendQueue()
    internal val _queued = MutableStateFlow<List<QueuedMessage>>(emptyList())
    /** Messages sent while a reply ran: answered right after it (chat) or read before the next step (智能体). */
    val queued: StateFlow<List<QueuedMessage>> = _queued.asStateFlow()

    fun cancelQueued(id: String) { _queued.value = sendQueue.cancel(id) }

    internal fun sendWhileBusy(text: String, attachments: List<com.iris.localai.data.Attachment>, refs: List<com.iris.localai.data.TurnRef>, force: Boolean) {
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
                if (busy()) { notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_ac970f); return@launch }
                send(text, attachments, refs)
            }
            return
        }
        val forAgent = agentRunning
        _queued.value = sendQueue.add(QueuedMessage(convId = convId, text = text, forAgent = forAgent))
        notice.value = if (forAgent) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_25760a) else SendIntent.QUEUED_HINT
    }

    /** After a reply: the next queued message is answered as its own turn. */
    internal fun drainQueue() {
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
                    .onFailure { notice.value = it.message ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d85dac) }.getOrNull()
            }
        }

    /** 2.1.7 生图 「发到聊天」: a PNG path waiting to join the composer's attachments (ChatScreen consumes it). */
    internal val _incomingImage = MutableStateFlow<String?>(null)
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
        }.onFailure { notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_88889b, it.message) }.getOrNull()
    }

    fun discardAttachments(list: List<com.iris.localai.data.Attachment>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.util.Attachments.delete(list) }
    }

    /** 2.1.1: whether this prompt turn is the newest user message and it has pictures the model will read. */
    internal fun pickImages(m: PromptMessage): Boolean {
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
    internal var nextReplyBase: ChatMessage? = null

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
        if (busy()) { notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_308cc5); return }
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
    internal fun saveMemory(text: String, target: com.iris.localai.memory.MemoryTarget): String {
        val entry = com.iris.localai.memory.MemoryCommand.entryFrom(text)
        return saveMemoryItem(entry, target.kindFor(entry), target)
    }

    // ------------------------------------------------------------------ 2.2.9 「记住？」 suggestions

    /** Conversation id → keys the user dismissed (✕ / long press) in that conversation. */
    internal val ignoredSuggestions = HashMap<String, MutableSet<String>>()

    internal fun clearSuggestion(messageId: String): String? {
        val text = conversation.value.messages.firstOrNull { it.id == messageId }?.memorySuggestion ?: return null
        conversation.update { cv -> cv.copy(messages = cv.messages.map { if (it.id == messageId) it.copy(memorySuggestion = null) else it }) }
        persist()
        return text
    }

    /** 「＋ 记住？」 tapped: saved as said by the user (and removed from 「等你确认」). */
    fun acceptMemorySuggestion(messageId: String) {
        val text = clearSuggestion(messageId) ?: return
        val kind = com.iris.localai.memory.MemorySuggest.kindOf(text) ?: com.iris.localai.memory.MemoryItem.Kind.ABOUT
        val target = if (kind == com.iris.localai.memory.MemoryItem.Kind.FACT) com.iris.localai.memory.MemoryTarget.MEMORY else com.iris.localai.memory.MemoryTarget.USER
        notice.value = saveMemoryItem(text, kind, target)
    }

    /** ✕ / long press: not suggested again in this conversation, and dropped from 「等你确认」. */
    fun dismissMemorySuggestion(messageId: String) {
        val text = clearSuggestion(messageId) ?: return
        val convId = conversation.value.id
        synchronized(ignoredSuggestions) { ignoredSuggestions.getOrPut(convId) { HashSet() }.add(com.iris.localai.memory.MemoryMerge.key(text)) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.agent.memoryDocs.store.dismissPendingText(text) }; c.agent.memoryChanged() }
    }

    /** 2.1.9: launches an installed app for a pure 「打开X」; false = not handled (no such app / phone tool off). */
    internal fun launchCommand(userText: String, app: String): Boolean {
        val ag = c.agent.settings.current
        val tool = com.iris.localai.agent.PhoneTool
        if (!ag.isEnabled(tool) || ag.permission(tool) == com.iris.localai.agent.Permission.DENY) return false
        val apps = runCatching { c.agent.phone.apps() }.getOrDefault(emptyList())
        val hit = com.iris.localai.agent.AppMatcher.match(app, apps) ?: return false
        val reply = runCatching { c.agent.phone.openApp(hit.second); com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7c8416, hit.first) }.getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5d6224, hit.first, it.message) }
        append(ChatMessage(role = Role.USER, content = userText))
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
        return true
    }

    /** 「记住：…」: the user's line and a short confirmation are added to the chat; the model is not called. */
    internal fun rememberCommand(userText: String, fact: String) {
        append(ChatMessage(role = Role.USER, content = userText))
        val reply = saveMemory(fact, com.iris.localai.memory.MemoryTarget.MEMORY)
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
    }

    /**
     * A reply is streaming, or a send is still waiting for the model to finish loading.
     * 2.1.8: read from the raw sources ([ReplyBusy]), not the derived `state` (it lags a dispatch,
     * so the queue drain right after a reply saw "generating" and a 「待回复」 waited forever).
     */
    internal fun busy() = ReplyBusy.of(streamingId.value, genJob?.isActive == true)

    /** 2.1.8: the running reply's [ReplyWatchdog] (null when none runs). */
    @Volatile internal var replyWatchdog: ReplyWatchdog? = null
    /** 2.1.8: replies ended by the watchdog: their error survives the reply's own cleanup. */
    internal val stalledIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 1.10.13: set by 停止 for the running reply; text arriving after it is dropped. */
    @Volatile internal var stopAsked = false

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

    internal fun append(m: ChatMessage) {
        conversation.update { conv ->
            val title = conv.title.ifBlank { if (m.role == Role.USER) titleFrom(m.content.ifBlank { m.attachments.firstOrNull()?.name.orEmpty() }) else "" }
            conv.copy(title = title, messages = conv.messages + m, updatedAt = System.currentTimeMillis())
        }
        persist()
    }

    /** 1.13.0: conversations deleted while a reply was still writing (never saved again). */
    internal val deletedIds = java.util.Collections.synchronizedSet(HashSet<String>())
    /** 1.13.0: saves go out in order (separate launches could land a stale partial after the final one). */
    internal val saveQueue = Channel<Conversation>(Channel.UNLIMITED).also { q ->
        viewModelScope.launch { for (conv in q) if (conv.id !in deletedIds) runCatching { c.conversations.save(conv) } }
    }
    internal fun save(conv: Conversation) { if (conv.messages.isNotEmpty()) saveQueue.trySend(conv) }

    internal fun persist() = save(conversation.value)

    internal fun titleFrom(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (line.length <= 24) line else line.take(24) + "…"
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
    internal fun skillsFor(tools: List<com.iris.localai.agent.AgentTool>): List<com.iris.localai.agent.SkillInfo> =
        if (tools.any { it.name == com.iris.localai.agent.SkillTool.name }) runCatching { c.agent.skills.all() }.getOrDefault(emptyList()) else emptyList()

    /** 1.14.0: the agent system prompt (按需载入, the conversation's plan, 计划模式). */
    internal fun agentSystem(tools: List<com.iris.localai.agent.AgentTool>, userSystem: String, modelFile: String?, memory: String = ""): String {
        val ag = c.agent.settings.current
        // 2.3: no plan here any more (it rides on the newest user turn, see AgentPrompt.withPlanNote).
        return com.iris.localai.agent.AgentPrompt.build(
            tools, withLang(userSystem), com.iris.localai.agent.AgentPrompt.styleFor(modelFile), skillsFor(tools),
            lazy = ag.lazyTools, planFirst = ag.planFirst, memory = memory,
        )
    }

    /** 2.3.1: non-Chinese UI → one line asking for replies in that language by default. */
    internal fun withLang(system: String) = com.iris.localai.I18nPure.withReplyLine(system, com.iris.localai.I18n.uiLang())

    // ------------------------------------------------------------------ 2.3.2 角色卡

    internal fun cardOf(conv: Conversation): com.iris.localai.character.CharacterCard? =
        conv.characterId?.let { id -> runCatching { c.characters.get(id)?.card }.getOrNull() }

    internal fun cardUser(): String = com.iris.localai.I18n.s(com.iris.localai.R.string.ch232_user_default)

    /** The card's assembled system prompt for a card chat, else [own] (the user's system prompt). */
    internal fun cardSystem(conv: Conversation, own: String, ready: EngineState.Ready?): String {
        val card = cardOf(conv) ?: return own
        val ctx = contextTokensFor(ready)
        return com.iris.localai.character.CardPrompt.system(card, cardUser(), own.trim(), conv.id.hashCode(),
            maxExampleTokens = (ctx * 0.15).toInt().coerceIn(200, 1500))
    }

    /** Card chat: lorebook entries + post-history instructions go on the newest user turn (not stored). */
    internal fun cardTurn(conv: Conversation, msgs: List<PromptMessage>): List<PromptMessage> {
        val card = cardOf(conv) ?: return msgs
        val i = msgs.indexOfLast { it.role == PromptMessage.USER }
        if (i < 0) return msgs
        val recent = msgs.filter { it.role != PromptMessage.SYSTEM }.map { it.content }
        val budget = com.iris.localai.character.CardPrompt.loreBudget(card, contextTokensFor(c.engine.state.value as? EngineState.Ready))
        val suffix = com.iris.localai.character.CardPrompt.turnSuffix(card, cardUser(), recent, budget, conv.id.hashCode())
        if (suffix.isEmpty()) return msgs
        return msgs.toMutableList().also { it[i] = it[i].copy(content = it[i].content + "\n\n" + suffix) }
    }

    /** Card of the conversation on screen (avatar + name in the top bar and on replies). */
    val character: StateFlow<com.iris.localai.character.CharacterStore.Entry?> = shown
        .map { it.characterId }.distinctUntilChanged()
        .map { id -> id?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.characters.get(it) }.getOrNull() } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // 2.3.3: card chat on screen → its bound voice as an override; any other chat → the global voice.
        viewModelScope.launch {
            character.collect { e ->
                val card = e?.card
                c.synthesizer.override = card?.let {
                    val pack = com.iris.localai.voice.VoicePack.entries.firstOrNull { p -> p.id == it.bindTtsPack }
                    com.iris.localai.voice.VoiceBinding.forCard(it.bindTtsPack, it.bindTtsSpeaker, it.bindCloneVoice,
                        packInstalled = pack != null && c.voiceStore.isInstalled(pack),
                        cloneExists = it.bindCloneVoice.isNotBlank() && c.cloneVoices.get(it.bindCloneVoice) != null)
                }
            }
        }
    }

    /**
     * Starts a new chat with card [id]: the card is bound to it, the greeting(s) become the opening
     * assistant message (alternates as its versions, switched with ‹ ›), and the card's bound model /
     * voice are applied when they exist on this device (otherwise the current ones stay).
     */
    fun startCharacterChat(id: String, reuse: Conversation? = null) = viewModelScope.launch {
        val entry = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.characters.get(id) }.getOrNull() } ?: return@launch
        val card = entry.card
        val conv0 = reuse?.copy(characterId = entry.id, title = card.name.ifBlank { entry.id }, titled = true, messages = emptyList())
            ?: Conversation(characterId = entry.id, title = card.name.ifBlank { entry.id }, titled = true)
        val greets = com.iris.localai.character.CardPrompt.greetings(card, cardUser(), conv0.id.hashCode())
        val opening = greets.firstOrNull()?.let { g ->
            ChatMessage(role = Role.ASSISTANT, content = g, modelName = card.name,
                versions = if (greets.size > 1) listOf(com.iris.localai.data.MessageVersion()) + greets.drop(1).map { com.iris.localai.data.MessageVersion(content = it) } else emptyList(),
                activeVersion = 0)
        }
        val conv = conv0.copy(messages = listOfNotNull(opening))
        save(conv)
        if (runningInBackground()) { viewing.value = conv; noteBackground() } else { viewing.value = null; conversation.value = conv }
        // Bindings (only to what exists; missing → keep the current setting).
        card.bindModel.takeIf { it.isNotBlank() }?.let { file ->
            val m = c.models.models.value.firstOrNull { it.fileName == file }
            if (m != null && c.engine.readyModel?.path != m.path && genJob?.isActive != true) {
                // 2.3.4: remember what was loaded before the first card swap (kept across card → card).
                if (preCardModelPath == null) preCardModelPath = c.engine.readyModel?.path
                loadModel(m)
            }
        }
        // 2.3.3: the bound voice is applied per chat by [character] (VoiceBinding), never saved to settings.
    }

    /** 2.3.3: cards for the top-bar 角色 dropdown. */
    fun characterEntries(): List<com.iris.localai.character.CharacterStore.Entry> =
        runCatching { c.characters.list() }.getOrDefault(emptyList())

    /**
     * 2.3.3: top-bar 角色 pick. [id] null = 「无角色」. A chat without user messages (new, or only the
     * card's opening) takes the card in place; otherwise a new chat starts with it (the old one stays).
     */
    fun pickCharacter(id: String?) {
        val cur = viewing.value ?: conversation.value
        when (com.iris.localai.character.CharacterPick.decide(cur.characterId, id, cur.messages.any { it.role == Role.USER })) {
            com.iris.localai.character.CharacterPick.Action.NONE -> Unit
            com.iris.localai.character.CharacterPick.Action.NEW_PLAIN -> newChat()
            com.iris.localai.character.CharacterPick.Action.APPLY_IN_PLACE -> startCharacterChat(id!!, reuse = cur)
            com.iris.localai.character.CharacterPick.Action.NEW_WITH_CARD -> startCharacterChat(id!!)
        }
    }

    /** 2.3: the 智能体 system prompt for [conv]: the user's instructions and the frozen memory as their own sections. */
    internal fun agentSystemFor(conv: Conversation, ready: EngineState.Ready?, tools: List<com.iris.localai.agent.AgentTool>): String {
        val memory = com.iris.localai.context.ContextAssembly.memoryBlock(conv, memoryInjectFor(true), contextTokensFor(ready), c.agent.memorySettings.current.epoch)
        return agentSystem(tools, cardSystem(conv, c.engine.effective(ready?.model).systemPrompt, ready), ready?.model?.fileName, memory)
    }

    /** 1.14.0: the open conversation's plan (计划卡片). */
    val plan: StateFlow<com.iris.localai.agent.Plan?> = kotlinx.coroutines.flow.combine(c.agent.plans.plans, conversation) { m, conv -> m[conv.id] }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    fun clearPlan() { c.agent.plans.clear(conversation.value.id) }

    init {
        // 1.14.0: live state for the 运行状态 rack in 设置.
        viewModelScope.launch { streamingId.collect { id -> com.iris.localai.util.SystemStatus.update { it.copy(generating = id != null) } } }
        viewModelScope.launch { _speakingMessageId.collect { id -> com.iris.localai.util.SystemStatus.update { it.copy(voiceActive = id != null) } } }
    }

    /** A tool call waiting for 允许一次 / 总是允许 / 拒绝 in the chat. */
    data class PermissionAsk(
        val toolLabel: String,
        val toolName: String,
        val preview: String,
        internal val answer: kotlinx.coroutines.CompletableDeferred<com.iris.localai.agent.UserDecision>,
        /** Unused since 2.2.2 (was a command prefix); null = the whole tool. */
        val alwaysPrefix: String? = null,
    )

    internal val _permissionAsk = MutableStateFlow<PermissionAsk?>(null)
    val permissionAsk: StateFlow<PermissionAsk?> = _permissionAsk.asStateFlow()

    fun answerPermission(d: com.iris.localai.agent.UserDecision) {
        val a = _permissionAsk.value ?: return
        _permissionAsk.value = null
        a.answer.complete(d)
    }

    @Volatile internal var agentRunning = false

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


    internal var warmedKey: String? = null
    internal var warmJob: Job? = null
    internal val _warming = MutableStateFlow<com.iris.localai.util.Activity?>(null)
    /** 1.10.10: 「预热上下文」 while the open conversation is prefilled after a model switch. */
    val warming: StateFlow<com.iris.localai.util.Activity?> = _warming.asStateFlow()

    internal fun scheduleWarmUp() {
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
            _warming.value = com.iris.localai.util.Activity(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f0ffdf))
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
    internal fun warmPrompt(conv: Conversation, ready: EngineState.Ready, agent: Boolean): List<PromptMessage>? {
        if (!agent) return planFor(conv, ready, voice = false).messages.takeIf { it.isNotEmpty() }
        val t = agentTranscript
        if (t != null && t.convId == conv.id && conv.messages.lastOrNull()?.id == t.anchorId) return t.messages
        val ag = c.agent.settings.current
        val memoryOn = c.agent.memorySettings.current.enabled
        val tools = com.iris.localai.agent.AgentTools.enabled(ag, c.agent.history != null, memoryOn, c.agent.knowledge.nonEmptyCached(), c.agent.imageGenOnCached())
        val eff = c.engine.effective()
        val maxNew = minOf(eff.maxNewTokens, AGENT_MAX_NEW)
        freezeMemory(agentMode = true, ready = ready)
        val p = planFor(conversation.value, ready, voice = false, agent = true, maxNewOverride = maxNew)
        return listOf(PromptMessage(PromptMessage.SYSTEM, agentSystemFor(conversation.value, ready, tools))) +
            cardTurn(conversation.value, p.messages).filter { it.role != PromptMessage.SYSTEM }
    }

    /** 1.10.6: every prompt sent for a reply, for 「查看提示内容」. */
    internal val promptLog = com.iris.localai.util.PromptLog()

    /** 1.10.6: readable dump of everything sent to the model for [replyId]. */
    fun promptText(replyId: String): String = com.iris.localai.util.PromptLog.format(promptLog.get(replyId))

    /** 1.10.6: the previous 智能体 run's prompt, continued by the next turn (see [continuedAgentBase]). */
    internal data class AgentTranscript(val convId: String, val anchorId: String, val anchorText: String, val messages: List<PromptMessage>)
    internal var agentTranscript: AgentTranscript? = null

    /** Engine-measured tokens in the window after a reply, or the plan's estimate plus the reply. */
    internal fun recordUsage(
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
            notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4e6f51, used * 100 / limit)
        }
    }

    // ------------------------------------------------------------------ voice bridge

    override fun ask(userText: String): Flow<String> = channelFlow {
        // 1.13.0: never a second reply into a chat that is still running in the background.
        if (viewing.value != null || genJob?.isActive == true) {
            notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_aa1ed6)
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
    @Volatile internal var voiceCue: ((String) -> Unit)? = null

    override fun cancelReply() = stop()

    // ------------------------------------------------------------------ read aloud

    fun speak(messageId: String) {
        stopSpeaking()
        val msg = (viewing.value ?: conversation.value).messages.firstOrNull { it.id == messageId } ?: return
        if (!c.synthesizer.isAvailable) {
            notice.value = if (c.synthesizer.systemSelected()) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5b9c6c) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_120479)
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
                    c.synthesizer.speak(speakable, c.synthesizer.speaker(s.ttsSpeakerId), s.ttsSpeed) { out.write(it) }
                }
                out.drain() // the last ~0.5 s is still queued when the final write returns
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("ChatViewModel", "read aloud failed", t)
                notice.value = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_21ec6b, t.message ?: t.javaClass.simpleName)
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
        if (c.gui.onUserStop === guiStopHook) c.gui.onUserStop = null
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(awayObserver)
        voice.stop()
        dictation.cancel()
        stopSpeaking()
        super.onCleared()
    }

    internal companion object {
        const val FRAME_MS = 33L
        const val GEN_TAG = "IrisGen"
        /** 1.7.7: shown in the reply bubble while the request waits for a tier reload. */
        val RELOADING_STATUS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_54e6e3)
        /** 2.1.8: the reply waits for a model load (bubble shown right away, 停止 works). */
        val LOADING_STATUS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_69b99c)
        val LOAD_FAILED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5eb967)
        /** 2.1.8: shown on a reply the watchdog ended. */
        val STALL_ERROR: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_24e46f)
        const val PERSIST_MS = 3_000L
        /** 1.9.0: reply cap per agent turn (tool calls are short; small windows need the room for results). */
        const val AGENT_MAX_NEW = 1536
        const val WARM_DELAY_MS = 600L
        val ATTACH_NO_VISION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5e0b54)
    }
}

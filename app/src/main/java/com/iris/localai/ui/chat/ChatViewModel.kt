package com.iris.localai.ui.chat


import com.iris.localai.BuildConfig
import com.iris.localai.I18n
import com.iris.localai.I18nPure
import com.iris.localai.R
import com.iris.localai.agent.core.AgentOutput
import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.core.UserDecision
import com.iris.localai.agent.tools.ActivateTool
import com.iris.localai.agent.tools.MemoryGate
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.tools.AppMatcher
import com.iris.localai.agent.tools.GuiBlocker
import com.iris.localai.agent.tools.GuiIntent
import com.iris.localai.agent.tools.LaunchIntent
import com.iris.localai.agent.tools.PhoneTool
import com.iris.localai.agent.tools.Plan
import com.iris.localai.agent.tools.ScreenTool
import com.iris.localai.agent.tools.SkillInfo
import com.iris.localai.agent.tools.SkillTool
import com.iris.localai.agent.web.NetworkHint
import com.iris.localai.agent.web.SearchText
import com.iris.localai.character.CardPrompt
import com.iris.localai.character.CharacterCard
import com.iris.localai.character.CharacterPick
import com.iris.localai.character.CharacterStore
import com.iris.localai.character.ModelRestore
import com.iris.localai.context.Compactor
import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.Attachment
import com.iris.localai.data.AttachmentKind
import com.iris.localai.data.ChatExport
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.HistorySearch
import com.iris.localai.data.MessageSearchIndex
import com.iris.localai.data.MessageVersion
import com.iris.localai.data.MessageVersions
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.TurnRef
import com.iris.localai.data.TurnRefKind
import com.iris.localai.data.TurnRefs
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.GenOwner
import com.iris.localai.engine.llama.MmprojPairing
import com.iris.localai.engine.load.MemoryPreflight
import com.iris.localai.engine.load.SlowLoadHint
import com.iris.localai.engine.resources.LiveResourcePolicy
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.engine.resources.ThermalCutoff
import com.iris.localai.engine.resources.TierApplyResult
import com.iris.localai.knowledge.KbBlock
import com.iris.localai.memory.MemoryCommand
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryMerge
import com.iris.localai.memory.MemorySuggest
import com.iris.localai.memory.MemoryTarget
import com.iris.localai.service.GenerationMonitor
import com.iris.localai.util.ContextPlan
import com.iris.localai.util.HintLog
import com.iris.localai.util.TokenEstimator
import com.iris.localai.util.diagnostics.Activity
import com.iris.localai.util.diagnostics.CrashReporter
import com.iris.localai.util.diagnostics.PromptLog
import com.iris.localai.util.diagnostics.SystemStatus
import com.iris.localai.util.io.Attachments
import com.iris.localai.util.io.BackgroundGuard
import com.iris.localai.util.streaming.ReplyTimer
import com.iris.localai.util.streaming.SpeechText
import com.iris.localai.voice.VoiceBinding
import com.iris.localai.voice.VoicePack
import com.iris.localai.voice.VoiceSpeech
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

/** Everything the chat screen renders, in one immutable snapshot. */
data class ChatUiState(
    val conversation: Conversation = Conversation(),
    /** id of the assistant message currently streaming, if any. */
    val streamingId: String? = null,
    val engine: EngineState = EngineState.Idle,
    val deepThinking: Boolean = true,
    val notice: String? = null,
    /** context in use (tokens) and the loaded model's window; 0 = unknown. */
    val context: ContextUsage = ContextUsage(),
) {
    val isGenerating: Boolean get() = streamingId != null
    val readyModel: LocalModel? get() = (engine as? EngineState.Ready)?.model
    /** r4：没加载模型（如崩溃后不预加载）时也能点，先记住开关；加载了且模型不支持才置灰。 */
    val supportsThinking: Boolean get() = (engine as? EngineState.Ready).let { r -> ThinkToggle.enabled(r != null, r?.details?.supportsThinking, r?.details?.thinkAlways == true) }
}

/** Context meter for the current conversation. */
data class ContextUsage(
    val used: Int = 0,
    val limit: Int = 0,
    /** Measured by the engine (true) or estimated from the text (false). */
    val exact: Boolean = false,
    val strategy: ContextStrategy = ContextStrategy.TOKEN_BUDGET,
    /** The conversation overrides the global strategy. */
    val overridden: Boolean = false,
    val hasBreak: Boolean = false,
    val hasSummary: Boolean = false,
    val summarizing: Boolean = false,
    /** Why KV_SHIFT is not available for the loaded engine (null = available). */
    val shiftUnsupported: String? = null,
    /** messages folded by deterministic compaction (0 = none), and how many times. */
    val compactedMessages: Int = 0,
    val compactions: Int = 0,
    /** long-term memory is part of this conversation's prompt. */
    val memoryInjected: Boolean = false,
    /** E: history messages the model sees in the next prompt (planFor). */
    val visibleMessages: Int = 0,
    /** tokens of knowledge snippets in the next prompt (part of [used]). */
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
    // fix: declared first — [state] starts Eagerly in the constructor and reads these through
    // usageFor → keptKb / turnBlocks; declared further down they were still null there and a restored
    // conversation with messages (对话 mode, 知识库 in chat) crashed at launch (synchronized on null).
    internal val kbBlocks = LinkedHashMap<String, KbBlock>()
    internal val memBlocks = LinkedHashMap<String, String>()
    /**
     * owner of every generation this screen starts (replies, voice, agent turns, summaries,
     * process_file parts, warm-up). 停止 and the stop filters cancel only these, never an API
     * client's generation that happens to hold the engine.
     */
    internal val engineOwner = GenOwner("chat")
    val settings = c.settings.settings
    val summaries = c.conversations.summaries
    val models = c.models.models
    val engineBusy = c.engine.busy

    /** The conversation a reply is written into (also the one shown, unless [viewing] is set). */
    internal val conversation = MutableStateFlow(Conversation())
    /**
     * switching conversations no longer stops a running reply/agent run. It keeps writing
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
        notice.value = I18n.s(R.string.chat_d9eb6f) + conversation.value.title.ifBlank { I18n.s(R.string.chat_83aa8a) } + I18n.s(R.string.chat_9edf20)
        return true
    }
    internal val streamingId = MutableStateFlow<String?>(null)
    /** model loaded before a card chat swapped in its bound model (path); offered back once on leaving. */
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
        // the reply streaming in the background is not this screen's.
        ChatUiState(conv, sid?.takeIf { viewing.value == null }, eng, s.deepThinking, n)
    }

    // the context readout also follows the mode switch and the memory settings.
    internal val base2 = combine(base, c.agent.settings.settings, c.agent.memorySettings.settings, c.agent.memoryRevision) { st, _, _, _ -> st }

    val state: StateFlow<ChatUiState> = combine(base2, c.modelConfigs.configs, measured, summarizing) { st, cfgs, meas, summ ->
        val ready = st.engine as? EngineState.Ready
        val s = c.settings.current
        val eff = EffectiveModelConfig.resolve(s, ready?.model?.format, ready?.model?.id?.let { cfgs[it] })
        // r5：没就绪时显示「将要用的模型」的开关（与 setDeepThinking 写的同一处），加载完不再跳回「开」。
        val shown = if (ready != null) eff.thinking else ThinkToggle.shown(s.deepThinking, thinkTargetId()?.let { cfgs[it]?.thinking })
        st.copy(deepThinking = shown, context = usageFor(st.conversation, ready, meas[st.conversation.id], summ))
    }
        // fix: first value after the constructor has finished (Eagerly + Main.immediate ran
        // usageFor inside <init>, before later fields were initialised).
        .onStart { kotlinx.coroutines.yield() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /** the running reply's timer — the speed overlay reads its visible-character count. */
    @Volatile var liveTimer: ReplyTimer? = null
        internal set

    @Volatile internal var usageMemo: Pair<List<Any?>, Pair<Int, Int>>? = null

    internal fun usageFor(conv: Conversation, ready: EngineState.Ready?, measuredTokens: Int?, summ: Boolean): ContextUsage {
        val s = c.settings.current
        val strategy = ContextStrategy.of(conv.contextStrategy ?: s.contextStrategy)
        val limit = ready?.details?.contextLength ?: 0
        val agentMode = c.agent.settings.current.agentMode
        // memoized — this planned the whole conversation on the main thread at every state
        // change (streamingId, engine busy, notices…), which made the start of a reply stutter.
        val planned = if (conv.messages.isEmpty()) 0 to 0 else {
            val key = listOf(conv, ready, agentMode, s, c.agent.memorySettings.current.epoch)
            usageMemo?.takeIf { it.first == key }?.second
                ?: planFor(conv, ready, voice = false, agent = agentMode).let { it.estimatedTokens to it.visibleMessages }.also { usageMemo = key to it }
        }
        val used = measuredTokens ?: planned.first
        val withTools = agentMode
        val compacted = Compactor.applicable(
            conv.compaction, ContextAssembly.active(conv.messages, conv.contextStartAfterId, withTools, ready?.details?.supportsVision == true),
        )
        return ContextUsage(
            used = used, limit = limit, exact = measuredTokens != null, strategy = strategy,
            overridden = conv.contextStrategy != null, hasBreak = conv.contextStartAfterId != null,
            hasSummary = conv.summary.isNotBlank(), summarizing = summ,
            shiftUnsupported = BackendCapabilities.contextShiftUnsupported(ready?.model?.format),
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
     * status of the reply being prepared (shown in its bubble while it has no text yet), e.g.
     * 「正在重载模型…」 while the request waits for a resource-tier reload. null = none.
     */
    /** what the model is doing right now, for the small indicator above the composer. */
    internal val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()
    internal fun act(label: String?) { if (_activity.value?.label != label) _activity.value = label?.let { Activity(it) } }

    /** 「取消加载」. */
    /**
     * 弹窗可取消: abandon the running load (the engine releases it when the native call
     * returns) and cancel our own load coroutine, so a load queued behind it never starts either.
     */
    fun cancelLoad() {
        c.engine.cancelLoad()
        loadJob?.cancel(); loadJob = null
    }

    internal val _replyStatus = MutableStateFlow<String?>(null)
    val replyStatus: StateFlow<String?> = _replyStatus.asStateFlow()

    /** true until the reply's generate call is running (stop then cancels the job itself). */
    @Volatile internal var awaitingEngine = false

    /** Bumped when a notification asks to show a conversation: navigation returns to the chat. */
    internal val _showChat = MutableStateFlow(0)
    val showChat: StateFlow<Int> = _showChat.asStateFlow()
    fun openFromNotification(id: String) {
        _showChat.value++
        open(id)
    }

    // did the system pause the reply while IRIS was in the background?
    internal var awayAt = 0L
    internal var awayPieces = 0L
    internal val awayObserver = androidx.lifecycle.LifecycleEventObserver { _, e ->
        val mon = GenerationMonitor
        when (e) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> { awayAt = System.currentTimeMillis(); awayPieces = mon.pieces }
            androidx.lifecycle.Lifecycle.Event.ON_START -> if (awayAt > 0) {
                val away = System.currentTimeMillis() - awayAt
                val produced = mon.pieces - awayPieces
                val running = streamingId.value != null
                android.util.Log.i(GEN_TAG, "back from background after ${away}ms: pieces=$produced running=$running exempt=${com.iris.localai.util.io.BackgroundGuard.isExempt(getApplication())}")
                if (BackgroundGuard.stalled(away, produced, running)) {
                    notice.value = BackgroundGuard.STALL_NOTICE
                }
                awayAt = 0L
            }
            else -> Unit
        }
    }
    init { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(awayObserver) }
    // 终止温度: stop the reply / agent run (online ones too) and read-aloud; IrisApp unloads models.
    init {
        viewModelScope.launch {
            ThermalCutoff.trips.collect { t ->
                runCatching { stop() }
                runCatching { stopSpeaking() }
                notice.value = ThermalCutoff.notice(t)
            }
        }
    }

    // ---- voice ----
    val voice = VoiceController(viewModelScope, c.recognizer, c.synthesizer, c.settings, this)
    val dictation = Dictation(viewModelScope, c.recognizer)
    val voiceStore = c.voiceStore
    /** C: text to put into the chat input (下载模型 › 「试一试」); the screen takes it and clears it. */
    internal val _draft = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val draft = _draft.asStateFlow()
    fun setDraft(text: String?) { _draft.value = text }
    /** Beta2：工具箱 语音转文本 选识别模型。 */
    fun useAsrPack(id: String) = c.settings.update { it.copy(asrPack = id) }
    /** Beta3：工具箱里的悬浮窗设置。 */
    fun updateSettings(block: (com.iris.localai.data.AppSettings) -> com.iris.localai.data.AppSettings) = c.settings.update(block)
    /**
     * a chat reply finished (true) or failed (false); stopped replies emit nothing. Not
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
        // reload failures / GPU→CPU fallbacks from the engine are shown, never swallowed.
        viewModelScope.launch { c.engine.notices.collect { notice.value = it } }
        // leaving a card chat → one-time hint to switch back to the pre-card model (never automatic).
        viewModelScope.launch {
            var lastCard: String? = null
            shown.map { it.characterId }.distinctUntilChanged().collect { card ->
                val prev = preCardModelPath
                val path = ModelRestore.offer(lastCard, card, prev, c.engine.readyModel?.path)
                if (lastCard != null && card == null) {
                    preCardModelPath = null
                    path?.let { p -> c.models.models.value.firstOrNull { it.path == p }?.let { _modelRestore.value = it } }
                }
                if (card != null) _modelRestore.value = null
                lastCard = card
            }
        }
        // D 「最近提示」: every banner, plus load failures / fallback notes, can be read again later.
        viewModelScope.launch { notice.collect { n -> HintLog.add(n, HintLog.Source.CHAT) } }
        viewModelScope.launch {
            c.engine.state.collect { st ->
                when (st) {
                    is EngineState.Failed -> HintLog.add(I18n.s(R.string.chat_c4fa9b) + st.message, HintLog.Source.MODEL)
                    is EngineState.Ready -> st.details.fallbackNote?.let { HintLog.add(st.model.displayName + I18n.s(R.string.settings_dec892) + it, HintLog.Source.MODEL) }
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
        // workspace fallback (Download unavailable) and the one-off migration result.
        viewModelScope.launch {
            c.agent.workspace.collect { w -> w.fallbackReason?.let { r -> if (c.agent.settings.current.agentMode) notice.value = r } }
        }
        viewModelScope.launch {
            c.agent.workspaceNote.collect { n -> if (n != null) { notice.value = n; c.agent.workspaceNote.value = null } }
        }
        // Load + warm the speech models in the background so the first voice turn is instant.
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            if (CrashReporter.diedInNativeWorkLastRun) return@launch
            val s = c.settings.current
            dictation.warmLanguage = s.asrLanguage
            if (c.recognizer.isAvailable) launch { runCatching { c.recognizer.prepare(s.asrLanguage) } }
            if (c.synthesizer.isAvailable) launch { runCatching { c.synthesizer.prepare() } }
        }
        // after a model (re)load, prefill the open conversation in the background so the
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
        c.conversations.purgeHook = { conv -> discardAttachments(MessageVersions.allAttachments(conv.messages)) }
        viewModelScope.launch {
            c.conversations.init()
            c.models.refresh()
            // the last model is preloaded by IrisApp at process start (EngineManager.preloadAtStart),
            // no longer only once the chat screen exists.
        }
    }

    // ------------------------------------------------------------------ conversations

    /** a reply/agent run is going on; switching conversations leaves it running. */
    internal fun runningInBackground() = genJob?.isActive == true

    fun newChat() {
        if (runningInBackground()) { viewing.value = Conversation(); noteBackground(); return }
        viewing.value = null
        conversation.value = Conversation()
        // llama.cpp keeps the cached system prompt for the new chat (common-prefix reuse).
        viewModelScope.launch { c.engine.resetForNewChat() }
    }

    // ------------------------------------------------------------------ 2.6.3 历史搜索 / 导出
    private val searchIndex by lazy { MessageSearchIndex(c.conversations) }
    /** Message hits for [query] in every saved conversation (titles are filtered by the drawer itself). */
    suspend fun searchMessages(query: String): List<HistorySearch.Hit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { searchIndex.search(summaries.value, query) }

    /** A message the chat list should scroll to once its conversation is shown (search hit). */
    val jumpTo = MutableStateFlow<String?>(null)
    fun openAt(conversationId: String, messageId: String) { jumpTo.value = messageId; open(conversationId) }
    fun jumpDone() { jumpTo.value = null }


    /** Writes the chosen conversations as one file and opens the share sheet. */
    fun exportChats(ids: Collection<String>, format: ChatExport.Format) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val order = summaries.value.map { it.id }
                    val list = ids.sortedBy { order.indexOf(it) }.mapNotNull { c.conversations.load(it) }
                    if (list.isEmpty()) return@runCatching false
                    val user = I18n.s(R.string.ex263_user)
                    val bot = I18n.s(R.string.ex263_assistant)
                    val untitled = I18n.s(R.string.chat_1ac07a)
                    val now = System.currentTimeMillis()
                    val E = ChatExport
                    val body = when (format) {
                        ChatExport.Format.MARKDOWN -> E.markdown(list, user, bot, untitled)
                        ChatExport.Format.TXT -> E.txt(list, user, bot, untitled)
                        ChatExport.Format.JSON -> E.json(list, BuildConfig.VERSION_NAME, now)
                    }
                    val ctx: android.content.Context = getApplication()
                    val dir = java.io.File(ctx.cacheDir, "shared/export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val f = java.io.File(dir, E.fileName(list, format, now, untitled)).apply { writeText(body) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = format.mime
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        ctx.startActivity(android.content.Intent.createChooser(send, I18n.s(R.string.ex263_share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    true
                }.getOrDefault(false)
            }
            if (!ok) android.widget.Toast.makeText(getApplication(), I18n.s(R.string.ex263_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
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
        notice.value = I18n.s(R.string.chat_d9eb6f) + conversation.value.title.ifBlank { I18n.s(R.string.chat_73aba3) } + I18n.s(R.string.chat_51bf65)
    }

    /** the background run ended — the conversation on screen becomes the working one. */
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
            // wait for the reply to end, or its last save would bring the chat back.
            stop()
            genJob?.let { j -> kotlinx.coroutines.withTimeoutOrNull(3000) { j.join() } ?: j.cancelAndJoin() }
            deletedIds += id
        }
        // to 回收站; attachment copies are discarded only when it is purged (purgeHook).
        c.conversations.delete(id)
        c.agent.plans.clear(id)
        if (conversation.value.id == id) conversation.value = Conversation()
    }

    fun rename(id: String, title: String) = viewModelScope.launch {
        c.conversations.rename(id, title)
        if (conversation.value.id == id) conversation.update { it.copy(title = title) }
        if (viewing.value?.id == id) viewing.update { it?.copy(title = title) }
    }

    /** 侧栏长按拖动后的新顺序（显示顺序的全部 id）。 */
    val customOrder = c.conversations.customOrder
    fun reorderChats(ids: List<String>) = viewModelScope.launch { c.conversations.reorder(ids) }

    fun togglePin(id: String, pinned: Boolean) = viewModelScope.launch {
        c.conversations.setPinned(id, pinned)
        if (conversation.value.id == id) conversation.update { it.copy(pinned = pinned) }
        if (viewing.value?.id == id) viewing.update { it?.copy(pinned = pinned) }
    }

    fun dismissNotice() { notice.value = null }

    fun dismissModelRestore() { _modelRestore.value = null }
    fun acceptModelRestore() { _modelRestore.value?.let { loadModel(it) }; _modelRestore.value = null }

    // ------------------------------------------------------------------ models

    /** the load started from this screen (picker / logo), cancellable by 「取消」. */
    internal var loadJob: Job? = null
    fun loadModel(model: LocalModel): Job = viewModelScope.launch {
        if (genJob?.isActive == true) { notice.value = I18n.s(R.string.chat_7a0481); return@launch }
        warmJob?.cancel() // a pending warm-up of the old model never delays the switch
        // memory short → ask first (仍要加载 / 取消), never a hard block.
        val v = c.engine.preflight(model)
        if (c.settings.current.warnPopups && v.level == MemoryPreflight.Level.SHORT && v.message != null) { memWarn.value = model to v.message; return@launch }
        c.engine.load(model)
    }.also { loadJob = it }
    internal val memWarn = MutableStateFlow<Pair<LocalModel, String>?>(null)
    fun confirmMemLoad(): Job? { val p = memWarn.value ?: return null; memWarn.value = null; return viewModelScope.launch { c.engine.load(p.first) }.also { loadJob = it } }
    fun dismissMemWarn() { memWarn.value = null }
    /** a tap on another model while a reply / background run goes used to do nothing at all. */
    fun noteSwitchBlocked() {
        notice.value = if (viewing.value != null) "另一个对话正在后台生成，结束或停止后才能切换模型"
            else I18n.s(R.string.chat_7a0481)
    }
    fun unloadModel() = viewModelScope.launch {
        if (genJob?.isActive == true) { notice.value = I18n.s(R.string.chat_4a1a73); return@launch }
        c.engine.unload()
    }
    /** 思考档位 from the long-press bar: 0 = thinking off, 1..3 = 低 / 中 / 高 (and thinking on). */
    fun setThinkLevel(level: Int) {
        if (level <= 0) { setDeepThinking(false); return }
        c.settings.update { it.copy(thinkLevel = level.coerceIn(1, 3)) }
        setDeepThinking(true)
    }
    /**
     * 输入框「思考」按钮。2.7.0：模板强制思考的模型关不掉推理，关闭后只隐藏思考过程，并提示一句。
     */
    fun toggleThinking(on: Boolean) {
        setDeepThinking(on)
        val d = (c.engine.state.value as? EngineState.Ready)?.details
        if (!on && d?.thinkAlways == true) notice.value = I18n.s(R.string.think27_always)
    }
    /** Writes to the loaded model's own thinking switch when it has one, otherwise the global one. */
    fun setDeepThinking(on: Boolean) {
        // r5：总写全局；目标模型（就绪 / 加载中 / 上次用的）有自己的思考设置时也写它——不再被模型设置盖回「开」。
        val id = thinkTargetId()
        val w = ThinkToggle.write(on, id?.let { c.modelConfigs.get(it).thinking })
        c.settings.update { it.copy(deepThinking = w.global) }
        if (id != null && w.model != null) c.modelConfigs.update(id) { it.copy(thinking = w.model) }
    }
    internal fun thinkTargetId(): String? = when (val e = c.engine.state.value) {
        is EngineState.Ready -> e.model.id
        is EngineState.Loading -> e.model.id
        is EngineState.Failed -> ThinkToggle.targetId(null, null, e.model?.id, c.settings.current.lastModelFile)
        else -> ThinkToggle.targetId(null, null, null, c.settings.current.lastModelFile)
    }
    /**
     * the setting always changes; while a reply runs on GGUF/CPU the decoder also picks up
     * threads / cores / priority before its next token (see EngineManager.applyTierLive).
     * MNN / LiteRT are reloaded by EngineManager (it watches the setting, debounced).
     */
    fun setResourceTier(tier: Int) {
        val t = tier.coerceIn(0, ResourceTier.entries.size - 1)
        if (t == c.settings.current.resourceTier) return
        c.settings.update { it.copy(resourceTier = t) }
        // applied right away (GGUF/CPU mid-reply, MNN/CPU idle or mid-reply); the settings
        // watcher in EngineManager skips it then and only schedules the reload check.
        c.engine.applyTierLive(ResourceTier.of(t))
    }

    /** what the last tier change really did (EngineManager.applyTierLive). */
    val tierApply = c.engine.tierApply

    /** the one short status line on the resource card; null = nothing to report. */
    fun resourceStatus(last: TierApplyResult?, engine: EngineState): String? =
        if (engine is EngineState.Loading && engine.applyingTier != null)
            LiveResourcePolicy.statusText(TierApplyResult.RELOADING)
        else LiveResourcePolicy.statusText(last)
            // on the GPU/NPU the tier only places the host threads — say so when nothing else is shown.
            ?: LiveResourcePolicy.backendNote((engine as? EngineState.Ready)?.details?.activeBackend)

    fun preferredBackendLabel(m: LocalModel): String = runCatching { c.engine.preferredBackend(m).label }.getOrDefault("")
    /** 「GenieX · NPU」 when GenieX would run [m], else null. */
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
        notice.value = I18n.s(R.string.chat_adc46a)
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
     * 引用知识库: user message id → snippet block added to it in the prompt (对话 mode).
     * kept for every message answered with snippets (was: only the newest), so the next turn
     * sends the history the engine already holds and its KV cache is reused instead of the whole
     * conversation being prefilled again. Bounded ([KbChat.MAX_KEPT]); not persisted.
     */
    // (kbBlocks: declared at the top of the class — see 2.3.5 fix)

    /**
     * C: user message id → its 【相关记忆】 block (FACT items relevant to it), put in front of
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
        if (busy()) { notice.value = I18n.s(R.string.chat_94d50d); return }
        val agentMode = c.agent.settings.current.agentMode
        val ready = c.engine.state.value as? EngineState.Ready
        val maxNew = if (agentMode) AgentOutput.maxNew(c.engine.effective().maxNewTokens, ready?.details?.contextLength?.takeIf { it > 0 } ?: c.engine.effective().contextLength) else c.engine.effective().maxNewTokens
        val n = compactIfNeeded(agentMode, ready, maxNew, manual = true)
        notice.value = if (n > 0) ContextText.compactNotice(n) else I18n.s(R.string.chat_5aa0db)
    }

    /** Puts the compacted messages back into the context (they were never deleted). */
    fun clearCompaction() {
        if (viewingOther()) return
        conversation.update { it.copy(compaction = null) }
        measured.update { it - conversation.value.id }
        persist()
        notice.value = I18n.s(R.string.chat_d4d648)
    }
    fun onUserActivity() = c.engine.touch()
    fun markNotificationAsked() = c.settings.update { it.copy(askedNotificationPermission = true) }
    /** 3.0 语音聊天模式（免提 / 按住说话），记住上次的选择。 */
    fun setVoicePushToTalk(on: Boolean) { if (c.settings.current.voicePushToTalk != on) c.settings.update { it.copy(voicePushToTalk = on) } }
    fun markBatteryAsked() = c.settings.update { it.copy(askedBatteryExemption = true) }

    // ------------------------------------------------------------------ messaging

    /** the guide to show after `screen` was blocked (无障碍 off / no vision model); null = none. */
    // the overlay's 停止 also stops this chat's agent turn.
    internal val guiStopHook: () -> Unit = { viewModelScope.launch { stop() } }
    init { c.gui.onUserStop = guiStopHook }
    val guiSetup: StateFlow<GuiBlocker?> get() = c.gui.setup
    fun dismissGuiSetup() = c.gui.dismissSetup()

    /** a 对话-mode message that asks to operate the phone, while `screen` is enabled. */
    internal fun guiTurn(text: String): Boolean {
        val ag = c.agent.settings.current
        if (!ag.isEnabled(ScreenTool) || !GuiIntent.looksLikePhoneTask(text)) return false
        notice.value = GuiIntent.CHAT_NOTICE
        return true
    }

    fun send(
        text: String,
        attachments: List<Attachment> = emptyList(),
        refs: List<TurnRef> = emptyList(),
        /** 「打断并发送」 — stop the running reply and answer this instead. */
        interrupt: Boolean = false,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (viewingOther()) return
        // sending while a reply runs used to be dropped silently — now 「停/等等/不对…」 (or
        // 「打断并发送」) stops it and starts a new turn; anything else is a queued 补充.
        if (busy()) { sendWhileBusy(trimmed, attachments, refs, interrupt); return }
        // 2.2 「技能」: a picked skill / tool needs the agent — turn 智能体 on for it.
        if (TurnRefs.needsAgent(refs, c.agent.settings.current.agentMode)) { setAgentMode(true); notice.value = ComposerText.AGENT_FOR_REF }
        // 2.0.x: 「记住：…」 / "remember: …" is saved to MEMORY.md directly (both modes, no model call).
        if (attachments.isEmpty() && refs.isEmpty()) MemoryCommand.parse(trimmed)?.let { fact ->
            rememberCommand(trimmed, fact)
            return
        }
        // 「打开微信」 only → the phone tool's open_app right away (no model, no screen loop).
        if (attachments.isEmpty() && refs.isEmpty()) LaunchIntent.appName(trimmed)?.let { app ->
            if (launchCommand(trimmed, app)) return
        }
        // Beta3「打开手电筒 / 亮度调到最高 / 静音」→ 系统接口直接做（不叫模型、不走无障碍）。
        // r4：手电筒/亮度/音量交给模型调用 phone 工具。r5：只在这一轮带 phone 工具走一次智能体，不再永久打开智能体模式。
        run {
            val ag = c.agent.settings.current
            turnTools = TurnAgent.oneShotTools(ag.agentMode, com.iris.localai.agent.tools.PhoneQuick.parse(trimmed) != null,
                ag.isEnabled(PhoneTool) && ag.permission(PhoneTool) != Permission.DENY, attachments.isNotEmpty() || refs.isNotEmpty())
        }
        val ready = c.engine.state.value as? EngineState.Ready
        if (attachments.any { it.kind == AttachmentKind.IMAGE } && ready != null && ready.details.supportsVision != true)
            notice.value = if (ready.model.format == ModelFormat.GGUF) MmprojPairing.NO_MMPROJ_HINT else ATTACH_NO_VISION
        // a request that needs the internet while no tool can reach it.
        if (notice.value == null) {
            val ag = c.agent.settings.current
            val online = ag.agentMode && AgentTools.all.any { ag.isEnabled(it) && it.name in SearchText.NETWORK_TOOLS }
            NetworkHint.forRequest(trimmed, ag.agentMode, online)?.let { notice.value = it }
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

    internal fun sendWhileBusy(text: String, attachments: List<Attachment>, refs: List<TurnRef>, force: Boolean) {
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
                if (busy()) { notice.value = I18n.s(R.string.chat_ac970f); return@launch }
                send(text, attachments, refs)
            }
            return
        }
        val forAgent = agentRunning
        _queued.value = sendQueue.add(QueuedMessage(convId = convId, text = text, forAgent = forAgent))
        notice.value = if (forAgent) I18n.s(R.string.chat_25760a) else SendIntent.QUEUED_HINT
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

    /** copies picked files into app storage (off the main thread). */
    suspend fun importAttachments(uris: List<android.net.Uri>): List<Attachment> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            uris.take(Attachments.MAX_PER_MESSAGE).mapNotNull { u ->
                runCatching { Attachments.import(getApplication(), u) }
                    .onFailure { notice.value = it.message ?: I18n.s(R.string.chat_d85dac) }.getOrNull()
            }
        }

    /** 生图 「发到聊天」: a PNG path waiting to join the composer's attachments (ChatScreen consumes it). */
    internal val _incomingImage = MutableStateFlow<String?>(null)
    val incomingImage: StateFlow<String?> = _incomingImage.asStateFlow()
    fun attachGeneratedImage(path: String) { _incomingImage.value = path }
    fun consumeIncomingImage() { _incomingImage.value = null }

    /** Copies a local image (a 生图 result) into app attachments, like a picked photo. */
    suspend fun importLocalImage(path: String): Attachment? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val src = java.io.File(path)
            val out = java.io.File(Attachments.dir(getApplication()), java.util.UUID.randomUUID().toString().take(8) + "-" + src.name)
            src.copyTo(out, overwrite = true)
            Attachment(name = src.name, path = out.path, mime = "image/png", sizeBytes = out.length(), kind = AttachmentKind.IMAGE)
        }.onFailure { notice.value = I18n.s(R.string.chat_88889b, it.message) }.getOrNull()
    }

    fun discardAttachments(list: List<Attachment>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { Attachments.delete(list) }
    }

    /** whether this prompt turn is the newest user message and it has pictures the model will read. */
    internal fun pickImages(m: PromptMessage): Boolean {
        val ready = c.engine.state.value as? EngineState.Ready ?: return false
        if (ready.details.supportsVision != true) return false
        val lastUser = conversation.value.messages.lastOrNull { it.role == Role.USER } ?: return false
        return lastUser.attachments.any { it.kind == AttachmentKind.IMAGE } &&
            m.content.contains(lastUser.attachments.first { it.kind == AttachmentKind.IMAGE }.name)
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
            if (p.content.isBlank() && p.agentSteps.isEmpty()) MessageVersions.dropActive(p) else p
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
        val next = MessageVersions.editUser(msgs, messageId, newText) ?: return
        conversation.update { it.copy(messages = next, updatedAt = System.currentTimeMillis()) }
        measured.update { it - conversation.value.id }
        startReply(Requester.CHAT)
    }

    /** 2.0.x: edits an assistant reply in place (no regeneration); the old text stays as a version. */
    fun editAssistant(messageId: String, newText: String) {
        if (viewingOther()) return
        if (busy() || newText.isBlank()) return
        val next = MessageVersions.editAssistant(conversation.value.messages, messageId, newText) ?: return
        conversation.update { it.copy(messages = next, updatedAt = System.currentTimeMillis()) }
        measured.update { it - conversation.value.id }
        persist()
    }

    /** long-press / ⋯ 「删除」 on any message, user or assistant. Refused while a reply is generating. */
    fun deleteMessage(messageId: String) {
        if (viewingOther()) return
        if (busy()) { notice.value = I18n.s(R.string.del244_busy); return }
        val old = conversation.value
        val next = MessageVersions.delete(old.messages, messageId) ?: return
        val start = MessageVersions.markerAfterDelete(old.messages, old.contextStartAfterId, messageId)
        val upTo = old.compaction?.let { MessageVersions.markerAfterDelete(old.messages, it.upToId, messageId) }
        conversation.update {
            it.copy(
                messages = next, updatedAt = System.currentTimeMillis(), contextStartAfterId = start,
                compaction = it.compaction?.let { c -> if (upTo == null) null else c.copy(upToId = upTo) },
            )
        }
        measured.update { it - conversation.value.id }
        persist()
        notice.value = I18n.s(R.string.del244_done)
    }

    /** 2.0.x: ‹ › under a message with several versions; a user message brings its own branch back. */
    fun switchVersion(messageId: String, target: Int) {
        if (viewingOther()) return
        if (busy()) { notice.value = I18n.s(R.string.chat_308cc5); return }
        val next = MessageVersions.switchTo(conversation.value.messages, messageId, target) ?: return
        conversation.update { it.copy(messages = next) }
        measured.update { it - conversation.value.id }
        persist()
    }

    // ------------------------------------------------------------------ 2.0.x explicit memory

    /** 「记住这条」 on a message: one line appended to USER.md / MEMORY.md (MemoryOps caps apply). */
    fun rememberText(text: String, target: MemoryTarget) {
        val msg = saveMemory(text, target)
        notice.value = msg
    }

    /** The default target suggested in the 「记住这条」 dialog. */
    fun suggestMemoryTarget(text: String): MemoryTarget = MemoryCommand.suggestTarget(text)

    /** One-line entry for a message (reasoning removed, whitespace collapsed, capped). */
    fun memoryEntryFor(message: ChatMessage): String =
        MemoryCommand.entryFrom(if (message.role == Role.USER) message.content else ThinkParser.parse(message.content).answer)

    /** Writes through [com.iris.localai.memory.MemoryDocs.edit] and returns the line to show the user. */
    internal fun saveMemory(text: String, target: MemoryTarget): String {
        val entry = MemoryCommand.entryFrom(text)
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
        val kind = MemorySuggest.kindOf(text) ?: MemoryItem.Kind.ABOUT
        val target = if (kind == MemoryItem.Kind.FACT) MemoryTarget.MEMORY else MemoryTarget.USER
        notice.value = saveMemoryItem(text, kind, target)
    }

    /** ✕ / long press: not suggested again in this conversation, and dropped from 「等你确认」. */
    fun dismissMemorySuggestion(messageId: String) {
        val text = clearSuggestion(messageId) ?: return
        val convId = conversation.value.id
        synchronized(ignoredSuggestions) { ignoredSuggestions.getOrPut(convId) { HashSet() }.add(MemoryMerge.key(text)) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.agent.memoryDocs.store.dismissPendingText(text) }; c.agent.memoryChanged() }
    }

    /** launches an installed app for a pure 「打开X」; false = not handled (no such app / phone tool off). */
    internal fun launchCommand(userText: String, app: String): Boolean {
        val ag = c.agent.settings.current
        val tool = PhoneTool
        if (!ag.isEnabled(tool) || ag.permission(tool) == Permission.DENY) return false
        val apps = runCatching { c.agent.phone.apps() }.getOrDefault(emptyList())
        val hit = AppMatcher.match(app, apps) ?: return false
        val reply = runCatching { c.agent.phone.openApp(hit.second); I18n.s(R.string.chat_7c8416, hit.first) }.getOrElse { I18n.s(R.string.chat_5d6224, hit.first, it.message) }
        append(ChatMessage(role = Role.USER, content = userText))
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
        return true
    }

    /** Beta3 一句话手机操作；false = phone 工具被关掉 / 禁止（交给模型）。 */
    internal fun quickPhoneCommand(userText: String, act: com.iris.localai.agent.tools.PhoneQuick.Act): Boolean {
        val ag = c.agent.settings.current
        if (!ag.isEnabled(PhoneTool) || ag.permission(PhoneTool) == Permission.DENY) return false
        val Q = com.iris.localai.agent.tools.PhoneQuick
        val reply = runCatching {
            val raw = Q.run(act, c.agent.phone)
            if (!Q.ok(raw)) I18n.s(R.string.v31_pq_perm) else when (act) {
                is com.iris.localai.agent.tools.PhoneQuick.Act.Torch -> I18n.s(if (raw.endsWith("on")) R.string.v31_pq_torch_on else R.string.v31_pq_torch_off)
                is com.iris.localai.agent.tools.PhoneQuick.Act.Brightness -> I18n.s(R.string.v31_pq_bright, act.level)
                is com.iris.localai.agent.tools.PhoneQuick.Act.Volume -> I18n.s(R.string.v31_pq_volume, act.level)
                is com.iris.localai.agent.tools.PhoneQuick.Act.Ringer -> I18n.s(when (act.mode) { "silent" -> R.string.v31_pq_silent; "vibrate" -> R.string.v31_pq_vibrate; else -> R.string.v31_pq_normal })
            }
        }.getOrElse { I18n.s(R.string.v31_pq_fail, it.message ?: "") }
        append(ChatMessage(role = Role.USER, content = userText))
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
        return true
    }

    /** 「记住：…」: the user's line and a short confirmation are added to the chat; the model is not called. */
    internal fun rememberCommand(userText: String, fact: String) {
        append(ChatMessage(role = Role.USER, content = userText))
        val reply = saveMemory(fact, MemoryTarget.MEMORY)
        append(ChatMessage(role = Role.ASSISTANT, content = reply))
    }

    /**
     * A reply is streaming, or a send is still waiting for the model to finish loading.
     * read from the raw sources ([ReplyBusy]), not the derived `state` (it lags a dispatch,
     * so the queue drain right after a reply saw "generating" and a 「待回复」 waited forever).
     */
    internal fun busy() = ReplyBusy.of(streamingId.value, genJob?.isActive == true)

    /** the running reply's [ReplyWatchdog] (null when none runs). */
    @Volatile internal var replyWatchdog: ReplyWatchdog? = null
    /** replies ended by the watchdog: their error survives the reply's own cleanup. */
    internal val stalledIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /** set by 停止 for the running reply; text arriving after it is dropped. */
    @Volatile internal var stopAsked = false

    fun stop() {
        val job = genJob ?: return
        if (!job.isActive) return
        stopAsked = true
        // a warm-up prefill still on the engine would take the cancel meant for this reply
        // (the reply then started right after it, so 停止 looked dead).
        warmJob?.cancel()
        // an agent run is stopped as a whole (model turn, pending confirmation or tool).
        if (agentRunning) { c.engine.cancel(engineOwner); job.cancel(); return }
        // Still waiting on a model (re)load: no engine work to stop yet, cancel the request itself.
        if (streamingId.value != null && !awaitingEngine) {
            c.engine.cancel(engineOwner)
            // a cancel that lands before the native call starts (prefill setup) is lost —
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

    /** conversations deleted while a reply was still writing (never saved again). */
    internal val deletedIds = java.util.Collections.synchronizedSet(HashSet<String>())
    /** saves go out in order (separate launches could land a stale partial after the final one). */
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

    /** r5：这一轮临时给模型的工具（手机指令 = phone）；startReply 取走即清空。 */
    @Volatile internal var turnTools: Set<String>? = null

    init {
        // r5：r4 命中手机指令时把智能体模式永久打开了——升级后关一次（之后用户自己开的不再动）。
        if (!c.settings.current.agentReset292) {
            TurnAgent.migrateAgentMode(c.agent.settings.current.agentMode, migrated = false)?.let { setAgentMode(it) }
            c.settings.update { it.copy(agentReset292 = true) }
        }
    }

    /** 2.2 「技能」 sheet: installed skills (only while the skill tool is on) and the enabled agent tools. */
    fun refCatalog(): RefCatalog {
        val ag = c.agent.settings.current
        val memoryOn = c.agent.memorySettings.current.enabled
        // 长期记忆关闭时智能体拿不到记忆工具，@ 菜单也不再列出它。
        val tools = AgentTools.all.filter { MemoryGate.listed(it, ag, memoryOn) && it.name != SkillTool.name && it.name != ActivateTool.name }
        val skillOn = AgentTools.all.any { it.name == SkillTool.name && ag.isEnabled(it) }
        val skills = if (skillOn) runCatching { c.agent.skills.all() }.getOrDefault(emptyList()) else emptyList()
        return RefCatalog(
            skills = skills.map { RefItem(TurnRef(TurnRefKind.SKILL, it.name), it.name, it.description) },
            tools = tools.map { RefItem(TurnRef(TurnRefKind.TOOL, it.name), it.label, it.description) },
            skillToolOff = !skillOn,
        )
    }

    /** skills listed in the prompt (only while the skill tool is on). */
    internal fun skillsFor(tools: List<AgentTool>): List<SkillInfo> =
        if (tools.any { it.name == SkillTool.name }) runCatching { c.agent.skills.all() }.getOrDefault(emptyList()) else emptyList()

    /** the agent system prompt (按需载入, the conversation's plan, 计划模式). */
    internal fun agentSystem(tools: List<AgentTool>, userSystem: String, modelFile: String?, memory: String = ""): String {
        val ag = c.agent.settings.current
        // no plan here any more (it rides on the newest user turn, see AgentPrompt.withPlanNote).
        return AgentPrompt.build(
            tools, withLang(userSystem), AgentPrompt.styleFor(modelFile), skillsFor(tools),
            lazy = ag.lazyTools, planFirst = ag.planFirst, memory = memory, askFirst = ag.askFirst,
        )
    }

    /** non-Chinese UI → one line asking for replies in that language by default. */
    internal fun withLang(system: String) = I18nPure.withReplyLine(system, I18n.uiLang())

    // ------------------------------------------------------------------ 2.3.2 角色卡

    internal fun cardOf(conv: Conversation): CharacterCard? =
        conv.characterId?.let { id -> runCatching { c.characters.get(id)?.card }.getOrNull() }

    internal fun cardUser(): String = I18n.s(R.string.ch232_user_default)

    /** The card's assembled system prompt for a card chat, else [own] (the user's system prompt). */
    internal fun cardSystem(conv: Conversation, own: String, ready: EngineState.Ready?): String {
        val card = cardOf(conv) ?: return own
        val ctx = contextTokensFor(ready)
        return CardPrompt.system(card, cardUser(), own.trim(), conv.id.hashCode(),
            maxExampleTokens = (ctx * 0.15).toInt().coerceIn(200, 1500))
    }

    /** Card chat: lorebook entries + post-history instructions go on the newest user turn (not stored). */
    internal fun cardTurn(conv: Conversation, msgs: List<PromptMessage>): List<PromptMessage> {
        val card = cardOf(conv) ?: return msgs
        val i = msgs.indexOfLast { it.role == PromptMessage.USER }
        if (i < 0) return msgs
        val recent = msgs.filter { it.role != PromptMessage.SYSTEM }.map { it.content }
        val budget = CardPrompt.loreBudget(card, contextTokensFor(c.engine.state.value as? EngineState.Ready))
        val suffix = CardPrompt.turnSuffix(card, cardUser(), recent, budget, conv.id.hashCode())
        if (suffix.isEmpty()) return msgs
        return msgs.toMutableList().also { it[i] = it[i].copy(content = it[i].content + "\n\n" + suffix) }
    }

    /** Card of the conversation on screen (avatar + name in the top bar and on replies). */
    val character: StateFlow<CharacterStore.Entry?> = kotlinx.coroutines.flow.combine(
        shown.map { it.characterId }.distinctUntilChanged(), c.characters.revision,
    ) { id, _ -> id }
        .map { id -> id?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.characters.get(it) }.getOrNull() } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // card chat on screen → its bound voice as an override; any other chat → the global voice.
        viewModelScope.launch {
            character.collect { e ->
                val card = e?.card
                c.synthesizer.override = card?.let {
                    val pack = VoicePack.entries.firstOrNull { p -> p.id == it.bindTtsPack }
                    VoiceBinding.forCard(it.bindTtsPack, it.bindTtsSpeaker, it.bindCloneVoice,
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
        val greets = CardPrompt.greetings(card, cardUser(), conv0.id.hashCode())
        val opening = greets.firstOrNull()?.let { g ->
            ChatMessage(role = Role.ASSISTANT, content = g, modelName = card.name,
                versions = if (greets.size > 1) listOf(MessageVersion()) + greets.drop(1).map { MessageVersion(content = it) } else emptyList(),
                activeVersion = 0)
        }
        val conv = conv0.copy(messages = listOfNotNull(opening))
        save(conv)
        if (runningInBackground()) { viewing.value = conv; noteBackground() } else { viewing.value = null; conversation.value = conv }
        // Bindings (only to what exists; missing → keep the current setting).
        card.bindModel.takeIf { it.isNotBlank() }?.let { file ->
            val m = c.models.models.value.firstOrNull { it.fileName == file }
            if (m != null && c.engine.readyModel?.path != m.path && genJob?.isActive != true) {
                // remember what was loaded before the first card swap (kept across card → card).
                if (preCardModelPath == null) preCardModelPath = c.engine.readyModel?.path
                loadModel(m)
            }
        }
        // the bound voice is applied per chat by [character] (VoiceBinding), never saved to settings.
    }

    /** cards for the top-bar 角色 dropdown. */
    fun characterEntries(): List<CharacterStore.Entry> =
        runCatching { c.characters.list() }.getOrDefault(emptyList())

    /**
     * top-bar 角色 pick. [id] null = 「无角色」. A chat without user messages (new, or only the
     * card's opening) takes the card in place; otherwise a new chat starts with it (the old one stays).
     */
    fun pickCharacter(id: String?) {
        val cur = viewing.value ?: conversation.value
        when (CharacterPick.decide(cur.characterId, id, cur.messages.any { it.role == Role.USER })) {
            CharacterPick.Action.NONE -> Unit
            CharacterPick.Action.NEW_PLAIN -> newChat()
            CharacterPick.Action.APPLY_IN_PLACE -> startCharacterChat(id ?: return, reuse = cur)
            CharacterPick.Action.NEW_WITH_CARD -> startCharacterChat(id ?: return)
        }
    }

    /** the 智能体 system prompt for [conv]: the user's instructions and the frozen memory as their own sections. */
    internal fun agentSystemFor(conv: Conversation, ready: EngineState.Ready?, tools: List<AgentTool>): String {
        val memory = ContextAssembly.memoryBlock(conv, memoryInjectFor(true), contextTokensFor(ready), c.agent.memorySettings.current.epoch)
        return agentSystem(tools, cardSystem(conv, c.engine.effective(ready?.model).systemPrompt, ready), ready?.model?.fileName, memory)
    }

    /** the open conversation's plan (计划卡片). */
    val plan: StateFlow<Plan?> = kotlinx.coroutines.flow.combine(c.agent.plans.plans, conversation) { m, conv -> m[conv.id] }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    fun clearPlan() { c.agent.plans.clear(conversation.value.id) }

    init {
        // live state for the 运行状态 rack in 设置.
        viewModelScope.launch { streamingId.collect { id -> SystemStatus.update { it.copy(generating = id != null) } } }
        viewModelScope.launch { _speakingMessageId.collect { id -> SystemStatus.update { it.copy(voiceActive = id != null) } } }
    }

    /** A tool call waiting for 允许一次 / 总是允许 / 拒绝 in the chat. */
    data class PermissionAsk(
        val toolLabel: String,
        val toolName: String,
        val preview: String,
        internal val answer: kotlinx.coroutines.CompletableDeferred<UserDecision>,
        /** Unused since 2.2.2 (was a command prefix); null = the whole tool. */
        val alwaysPrefix: String? = null,
    )

    internal val _permissionAsk = MutableStateFlow<PermissionAsk?>(null)
    val permissionAsk: StateFlow<PermissionAsk?> = _permissionAsk.asStateFlow()

    fun answerPermission(d: UserDecision) {
        val a = _permissionAsk.value ?: return
        _permissionAsk.value = null
        a.answer.complete(d)
    }

    @Volatile internal var agentRunning = false

    /**
     * GPU first load taking long (see [com.iris.localai.engine.load.SlowLoadHint]): the model's
     * own backend becomes CPU and, once the running load returns, it is reloaded on the CPU.
     */
    /** (n_ctx, trained n_ctx) of the loaded model, (0, 0) when none. */
    fun loadedContext(): Pair<Int, Int> =
        (c.engine.state.value as? EngineState.Ready)?.details?.let { it.contextLength to it.trainContextLength } ?: (0 to 0)

    /** 上下文长度 from the chat's context sheet: saved for this model, then the model reloads. */
    fun setContextLength(n: Int) {
        val st = c.engine.state.value as? EngineState.Ready ?: return
        c.modelConfigs.update(st.model.id) { it.copy(contextLength = n) }
        notice.value = I18n.s(R.string.v256_ctx_len_reload, shortTokens(n))
        viewModelScope.launch { c.engine.reload() }
    }

    fun switchLoadingModelToCpu() {
        val st = c.engine.state.value as? EngineState.Loading ?: return
        val m = st.model
        c.modelConfigs.update(m.id) { it.copy(backend = Backend.CPU.name) }
        notice.value = SlowLoadHint.SWITCHED
        viewModelScope.launch {
            val end = c.engine.state.first { it !is EngineState.Loading }
            if (end is EngineState.Ready && end.model.path == m.path && end.details.activeBackend != Backend.CPU) c.engine.reload()
            else if (end is EngineState.Failed && end.model?.path == m.path) c.engine.load(m)
        }
    }


    internal var warmedKey: String? = null
    internal var warmJob: Job? = null
    internal val _warming = MutableStateFlow<Activity?>(null)
    /** 「预热上下文」 while the open conversation is prefilled after a model switch. */
    val warming: StateFlow<Activity?> = _warming.asStateFlow()

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
            _warming.value = Activity(I18n.s(R.string.chat_f0ffdf))
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
        val tools = AgentTools.enabled(ag, c.agent.history != null, memoryOn, c.agent.knowledge.nonEmptyCached(), c.agent.imageGenOnCached())
        val eff = c.engine.effective()
        val maxNew = AgentOutput.maxNew(eff.maxNewTokens, ready?.details?.contextLength?.takeIf { it > 0 } ?: eff.contextLength)
        freezeMemory(agentMode = true, ready = ready)
        val p = planFor(conversation.value, ready, voice = false, agent = true, maxNewOverride = maxNew)
        return listOf(PromptMessage(PromptMessage.SYSTEM, agentSystemFor(conversation.value, ready, tools))) +
            cardTurn(conversation.value, p.messages).filter { it.role != PromptMessage.SYSTEM }
    }

    /** every prompt sent for a reply, for 「查看提示内容」. */
    internal val promptLog = PromptLog()

    /** readable dump of everything sent to the model for [replyId]. */
    fun promptText(replyId: String): String = PromptLog.format(promptLog.get(replyId))

    /** the previous 智能体 run's prompt, continued by the next turn (see [continuedAgentBase]). */
    internal data class AgentTranscript(val convId: String, val anchorId: String, val anchorText: String, val messages: List<PromptMessage>)
    internal var agentTranscript: AgentTranscript? = null

    /** Engine-measured tokens in the window after a reply, or the plan's estimate plus the reply. */
    internal fun recordUsage(
        convId: String, plan: ContextPlan, stats: GenerationStats?,
        reply: String, ready: EngineState.Ready?, agentPromptTokens: Int = 0,
    ) {
        val used = if (agentPromptTokens > 0 && (stats == null || stats.kvUsed <= 0)) agentPromptTokens else when {
            stats == null -> plan.estimatedTokens + TokenEstimator.estimate(reply)
            stats.kvUsed > 0 -> stats.kvUsed
            stats.promptTokens > 0 -> stats.promptTokens + stats.generatedTokens
            else -> plan.estimatedTokens + stats.generatedTokens.coerceAtLeast(TokenEstimator.estimate(reply))
        }
        measured.update { it + (convId to used) }
        val limit = ready?.details?.contextLength ?: return
        if (limit > 0 && used >= limit * ContextUsage.NEAR_FULL && nearFullWarned.add(convId)) {
            notice.value = I18n.s(R.string.chat_4e6f51, used * 100 / limit)
        }
    }

    // ------------------------------------------------------------------ voice bridge

    override fun ask(userText: String): Flow<String> = channelFlow {
        // never a second reply into a chat that is still running in the background.
        if (viewing.value != null || genJob?.isActive == true) {
            notice.value = I18n.s(R.string.chat_aa1ed6)
            return@channelFlow
        }
        append(ChatMessage(role = Role.USER, content = userText))
        val deltas = Channel<String>(Channel.UNLIMITED)
        // only speakable text reaches TTS (no markdown / code / links / emoji / boilerplate,
        // ≤ 3 sentences; the bubble keeps the full reply). Tool cues pass straight through.
        val speech = VoiceSpeech()
        voiceCue = { cue -> speech.cue(cue).forEach { deltas.trySend(it) } }
        val job = startReply(Requester.VOICE) { d -> speech.push(d).forEach { deltas.trySend(it) } }
        job.invokeOnCompletion { speech.flush().forEach { deltas.trySend(it) }; voiceCue = null; deltas.close() }
        for (d in deltas) send(d)
    }.buffer(Channel.UNLIMITED)

    /** speaks a fixed short phrase in the running voice call (「我查一下」); null = not in a call. */
    @Volatile internal var voiceCue: ((String) -> Unit)? = null

    override fun cancelReply() = stop()

    // ------------------------------------------------------------------ read aloud

    fun speak(messageId: String) {
        val msg = (viewing.value ?: conversation.value).messages.firstOrNull { it.id == messageId } ?: return
        speakText(ThinkParser.parse(msg.content).answer, messageId)
    }

    /** 朗读任意文字（选择工具条「朗读」；[id] 只用于消息上的朗读中标记）。 */
    fun speakText(text: String, id: String = SELECTION_SPEAK_ID) {
        stopSpeaking()
        val messageId = id
        if (!c.synthesizer.isAvailable) {
            notice.value = if (c.synthesizer.systemSelected()) I18n.s(R.string.chat_5b9c6c) else I18n.s(R.string.chat_120479)
            return
        }
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
                    val speakable = SpeechText.speakable(sentence)
                    if (speakable.none { it.isLetterOrDigit() }) continue
                    c.synthesizer.speak(speakable, c.synthesizer.speaker(s.ttsSpeakerId), s.ttsSpeed) { out.write(it) }
                }
                out.drain() // the last ~0.5 s is still queued when the final write returns
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("ChatViewModel", "read aloud failed", t)
                notice.value = I18n.s(R.string.chat_21ec6b, t.message ?: t.javaClass.simpleName)
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
        const val SELECTION_SPEAK_ID = "selection"
        const val FRAME_MS = 33L
        const val GEN_TAG = "IrisGen"
        /** shown in the reply bubble while the request waits for a tier reload. */
        val RELOADING_STATUS: String get() = I18n.s(R.string.chat_54e6e3)
        /** the reply waits for a model load (bubble shown right away, 停止 works). */
        val LOADING_STATUS: String get() = I18n.s(R.string.chat_69b99c)
        val LOAD_FAILED: String get() = I18n.s(R.string.chat_5eb967)
        /** shown on a reply the watchdog ended. */
        val STALL_ERROR: String get() = I18n.s(R.string.chat_24e46f)
        const val PERSIST_MS = 3_000L
        /** reply cap per agent turn (tool calls are short; small windows need the room for results). */
        const val AGENT_MAX_NEW = 1536
        const val WARM_DELAY_MS = 600L
        val ATTACH_NO_VISION: String get() = I18n.s(R.string.chat_5e0b54)
    }
}

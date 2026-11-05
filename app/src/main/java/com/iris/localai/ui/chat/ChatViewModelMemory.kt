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
import com.iris.localai.ui.chat.ChatViewModel.Companion.GEN_TAG

// 2.4.1: split out of ChatViewModel.kt unchanged (extensions on ChatViewModel) — memory and 知识库 for a turn: kept blocks, injection, compaction, 记住这条.

/** 2.3.2: knowledge blocks that go into the prompt (newest 2 user turns, ≤ 15% of the window). */
internal fun ChatViewModel.keptKb(conv: Conversation, nCtx: Int): Map<String, com.iris.localai.knowledge.KbBlock> {
    val ids = conv.messages.filter { it.role == Role.USER && it.content.isNotBlank() }.map { it.id }
    val all = synchronized(kbBlocks) { HashMap(kbBlocks) }
    return com.iris.localai.knowledge.KbBudget.select(ids, all, nCtx)
}

/** 2.2.9 C: picks the FACT items relevant to the newest user message (once per message). */
internal fun ChatViewModel.prepareTurnMemory(agentMode: Boolean, ready: EngineState.Ready?) {
    val user = conversation.value.messages.lastOrNull { it.role == Role.USER && it.content.isNotBlank() } ?: return
    if (synchronized(memBlocks) { memBlocks.containsKey(user.id) }) return
    val block = if (!memoryInjectFor(agentMode)) "" else runCatching {
        com.iris.localai.context.ContextAssembly.turnMemory(c.agent.memoryDocs.store.items(), user.content, contextTokensFor(ready))
    }.getOrDefault("")
    synchronized(memBlocks) { com.iris.localai.knowledge.KbChat.remember(memBlocks, user.id, block) }
}

/**
 * 2.1.0: finds knowledge snippets for the newest user message and marks the reply.
 * 2.2.9: also in a voice call (half the characters); a follow-up borrows the previous question's
 * keywords and favours the files the previous reply cited; neighbouring chunks are merged.
 */
internal suspend fun ChatViewModel.prepareKnowledge(requester: Requester, replyId: String) {
    if (requester == Requester.API || !c.settings.current.knowledgeInChat) return
    val msgs = conversation.value.messages
    val ui = msgs.indexOfLast { it.role == Role.USER && it.content.isNotBlank() }
    if (ui < 0) return
    val user = msgs[ui]
    val pi = msgs.subList(0, ui).indexOfLast { it.role == Role.USER && it.content.isNotBlank() }
    val prevUser = if (pi >= 0) msgs[pi].content else null
    val prevFiles = if (pi >= 0) msgs.subList(pi + 1, ui).flatMap { com.iris.localai.knowledge.KbSource.files(it.kbSources) }.distinct() else emptyList()
    val base = if (requester == Requester.VOICE) com.iris.localai.knowledge.KbPrompt.INJECT_CHARS / 2 else com.iris.localai.knowledge.KbPrompt.INJECT_CHARS
    val kb = c.agent.knowledge
    // 2.3.2: room left in the 15% budget next to the previous turn's block, and its chunks (dedupe).
    val ids = msgs.subList(0, ui + 1).filter { it.role == Role.USER && it.content.isNotBlank() }.map { it.id }
    val nCtx = contextTokensFor((c.engine.state.value as? EngineState.Ready))
    val (room, exclude) = synchronized(kbBlocks) { com.iris.localai.knowledge.KbBudget.plan(ids, HashMap(kbBlocks), user.id, nCtx) }
    val maxChars = minOf(base, com.iris.localai.knowledge.KbBudget.charsFor(room))
    val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.iris.localai.knowledge.KbChat.hits(kb, user.content, prevUser, prevFiles, maxChars, exclude).also { h -> if (h.isNotEmpty()) runCatching { kb.cite(h.map { it.file }) } }
    }
    synchronized(kbBlocks) {
        if (hits.isEmpty()) kbBlocks.remove(user.id)
        else com.iris.localai.knowledge.KbChat.remember(kbBlocks, user.id, com.iris.localai.knowledge.KbBlock(com.iris.localai.knowledge.KbPrompt.inject(hits, maxChars), hits.flatMap { it.keys }.toSet()))
        // Only blocks that can still be kept matter; the rest are forgotten.
        val keep = com.iris.localai.knowledge.KbBudget.select(ids, HashMap(kbBlocks), nCtx).keys
        kbBlocks.keys.retainAll(keep + ids.takeLast(com.iris.localai.knowledge.KbBudget.KEEP_TURNS))
    }
    if (hits.isEmpty()) return
    val sources = hits.map { com.iris.localai.knowledge.KbSource.label(it) }
    conversation.update { conv -> conv.copy(messages = conv.messages.map { if (it.id == replyId) it.copy(kbRefs = hits.size, kbSources = sources) else it }) }
}

/** Freezes the long-term memory for this conversation the first time it is used (stable prompt prefix). */
internal fun ChatViewModel.freezeMemory(agentMode: Boolean, ready: EngineState.Ready? = null) {
    val ms = c.agent.memorySettings.current
    val conv = conversation.value
    // 2.0.x: until the model has replied once, the snapshot is retaken (memory saved just now counts).
    if (!com.iris.localai.memory.MemoryPrompt.needsSnapshot(conv, ms.epoch)) return
    if (!ms.inject(agentMode)) return
    // 2.2.9 C: ABOUT + PREF only, newest first within the budget; FACTs ride on each turn.
    val snap = runCatching { c.agent.memoryDocs.snapshot(com.iris.localai.memory.MemoryPrompt.budget(contextTokensFor(ready))) }.getOrDefault("")
    conversation.update { it.copy(memorySnapshot = snap, memoryEpoch = ms.epoch) }
}

/**
 * Deterministic compaction (no model call). [manual] = 「压缩上下文」: folds everything but the
 * newest round. Returns the number of newly folded messages (0 = nothing to do).
 */
internal fun ChatViewModel.compactIfNeeded(agentMode: Boolean, ready: EngineState.Ready?, maxNew: Int, manual: Boolean): Int {
    val conv = conversation.value
    val s = c.settings.current
    val strategy = com.iris.localai.data.ContextStrategy.of(conv.contextStrategy ?: s.contextStrategy)
    val ms = c.agent.memorySettings.current
    if (!manual && !com.iris.localai.context.ContextAssembly.autoAllowed(agentMode, strategy, ms.autoCompact)) return 0
    // 2.2.17 D: count each message's memory/knowledge block, as planFor sends it.
    val blocks = if (agentMode || !s.knowledgeInChat) memOnly() else turnBlocks(conv, contextTokensFor(ready))
    val active = com.iris.localai.context.ContextAssembly.withExtras(
        com.iris.localai.context.ContextAssembly.active(conv.messages, conv.contextStartAfterId, agentMode, ready?.details?.supportsVision == true), blocks)
    val ctx = contextTokensFor(ready)
    val eff = c.engine.effective(ready?.model)
    val baseSystem = if (agentMode) {
        val ag = c.agent.settings.current
        com.iris.localai.agent.AgentTools.all.filter { ag.isEnabled(it) }.let { t ->
            agentSystem(t, cardSystem(conv, eff.systemPrompt, ready), ready?.model?.fileName)
        }
    } else withLang(cardSystem(conv, eff.systemPrompt, ready).trim())
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

internal fun ChatViewModel.saveMemoryItem(entry: String, kind: com.iris.localai.memory.MemoryItem.Kind, target: com.iris.localai.memory.MemoryTarget): String {
    if (!c.agent.memorySettings.current.enabled) return com.iris.localai.memory.MemoryCommand.DISABLED
    val r = runCatching { c.agent.memoryDocs.store.add(entry, kind, com.iris.localai.memory.MemoryItem.Source.USER) }
        .getOrElse { com.iris.localai.memory.ItemsEdit.Err(it.message ?: "write failed") }
    if (r is com.iris.localai.memory.ItemsEdit.Ok) {
        c.agent.memoryChanged()
        // A conversation the model has not answered yet picks the new memory up right away.
        if (com.iris.localai.memory.MemoryPrompt.needsSnapshot(conversation.value, c.agent.memorySettings.current.epoch)) {
            conversation.update { it.copy(memorySnapshot = null) }
        }
    }
    return com.iris.localai.memory.MemoryCommand.userMessage(r, entry, target)
}

/** After a reply completes: one rule-based suggestion from the user's message, shown under [replyId]. */
internal fun ChatViewModel.suggestMemory(convId: String, replyId: String) {
    val ms = c.agent.memorySettings.current
    if (!ms.enabled || !ms.suggest) return
    val conv = conversation.value
    if (conv.id != convId) return
    val idx = conv.messages.indexOfFirst { it.id == replyId }
    if (idx <= 0) return
    val user = conv.messages.subList(0, idx).lastOrNull { it.role == Role.USER } ?: return
    if (user.content.isBlank() || com.iris.localai.memory.MemoryCommand.parse(user.content) != null) return
    val ignored = synchronized(ignoredSuggestions) { ignoredSuggestions[convId]?.toSet().orEmpty() }
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val store = c.agent.memoryDocs.store
        val s = runCatching { com.iris.localai.memory.MemorySuggest.detect(user.content, store.items(), ignored).firstOrNull() }.getOrNull() ?: return@launch
        runCatching { store.addPending(s.text, s.kind) }
        c.agent.memoryChanged()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (conversation.value.id != convId) return@withContext
            conversation.update { cv -> cv.copy(messages = cv.messages.map { if (it.id == replyId) it.copy(memorySuggestion = s.text) else it }) }
            persist()
        }
    }
}

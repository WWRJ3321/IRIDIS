package com.iris.localai.memory

import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.Serializable
import java.io.File

/** The two bounded memory documents (Hermes style). */
enum class MemoryTarget(val fileName: String, val label: String, val hint: String) {
    USER("USER.md", "关于你", "你的身份、偏好、习惯（由智能体记录或你自己填写）"),
    MEMORY("MEMORY.md", "记住的事", "事实、约定、学到的经验");

    companion object {
        fun parse(s: String?): MemoryTarget? = when (s?.trim()?.lowercase()?.removeSuffix(".md")) {
            "user", "user_profile", "profile", "about_user" -> USER
            "memory", "memories", "notes", "facts", null, "" -> MEMORY
            else -> null
        }
    }
}

/** Result of one memory edit: the new document, or an error the model can act on. */
sealed interface MemoryEdit {
    data class Ok(val text: String, val message: String) : MemoryEdit
    data class Err(val message: String) : MemoryEdit
}

/**
 * add / replace / remove on one document, one entry per line ("- …"). Pure: the cap is checked
 * before anything is written, so a document can never grow past [cap] characters.
 */
object MemoryOps {
    const val CAP = 1500
    private const val ENTRY_MAX = 300

    fun entries(doc: String): List<String> = doc.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun clean(entry: String): String =
        entry.replace(Regex("\\s+"), " ").trim().removePrefix("- ").removePrefix("* ").trim()

    private fun key(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}，。！？；：、]+"), "")

    private fun join(lines: List<String>) = lines.joinToString("\n")

    fun add(doc: String, entry: String, cap: Int = CAP): MemoryEdit {
        val e = clean(entry)
        if (e.isEmpty()) return MemoryEdit.Err("content is empty")
        if (e.length > ENTRY_MAX) return MemoryEdit.Err("entry too long (${e.length} chars, max $ENTRY_MAX); keep it to one short fact")
        val lines = entries(doc)
        if (lines.any { key(it).contains(key(e)) }) return MemoryEdit.Err("already saved: nothing changed")
        val next = join(lines + "- $e")
        if (next.length > cap) {
            return MemoryEdit.Err("memory is full (${doc.length}/$cap chars). Use replace to merge entries or remove an outdated one first")
        }
        return MemoryEdit.Ok(next, "added (${next.length}/$cap chars)")
    }

    fun replace(doc: String, old: String, new: String, cap: Int = CAP): MemoryEdit {
        val e = clean(new)
        if (e.isEmpty()) return MemoryEdit.Err("new content is empty; use remove to delete an entry")
        if (e.length > ENTRY_MAX) return MemoryEdit.Err("entry too long (${e.length} chars, max $ENTRY_MAX)")
        val lines = entries(doc)
        val i = find(lines, old) ?: return MemoryEdit.Err(notFound(lines, old))
        val next = join(lines.toMutableList().also { it[i] = "- $e" })
        if (next.length > cap) return MemoryEdit.Err("would exceed the cap (${next.length}/$cap chars); shorten it")
        return MemoryEdit.Ok(next, "replaced (${next.length}/$cap chars)")
    }

    fun remove(doc: String, old: String): MemoryEdit {
        val lines = entries(doc)
        val i = find(lines, old) ?: return MemoryEdit.Err(notFound(lines, old))
        val next = join(lines.toMutableList().also { it.removeAt(i) })
        return MemoryEdit.Ok(next, "removed (${next.length}/$CAP chars)")
    }

    /** Index of the single line containing [old]; null when none or several match. */
    private fun find(lines: List<String>, old: String): Int? {
        val o = old.trim().removePrefix("- ").trim()
        if (o.isEmpty()) return null
        val hits = lines.indices.filter { lines[it].contains(o, ignoreCase = true) }
        return hits.singleOrNull()
    }

    private fun notFound(lines: List<String>, old: String): String {
        val o = old.trim().removePrefix("- ").trim()
        val n = if (o.isEmpty()) 0 else lines.count { it.contains(o, ignoreCase = true) }
        return if (n > 1) "old text matches $n entries; quote more of the entry" else "no entry contains \"${o.take(60)}\""
    }

    // ---- 2.2.9: the same operations on the item list (memory.json); error texts unchanged. ----

    private fun countMsg(n: Int, max: Int) = "$n/$max entries"

    fun add(items: List<MemoryItem>, kind: MemoryItem.Kind, entry: String, source: MemoryItem.Source,
            now: Long = System.currentTimeMillis(), max: Int = MemoryStore.MAX_ITEMS): ItemsEdit {
        val e = clean(entry)
        if (e.isEmpty()) return ItemsEdit.Err("content is empty")
        if (e.length > ENTRY_MAX) return ItemsEdit.Err("entry too long (${e.length} chars, max $ENTRY_MAX); keep it to one short fact")
        return when (val m = MemoryMerge.merge(items, MemoryItem(text = e, kind = kind, source = source, createdAt = now), now)) {
            is MemoryMerge.Duplicate -> ItemsEdit.Err("already saved: nothing changed")
            is MemoryMerge.Replaced -> ItemsEdit.Ok(m.items, "replaced outdated entry \"${m.old.text.take(60)}\" (${countMsg(m.items.size, max)})", replaced = m.old.text)
            is MemoryMerge.Added ->
                if (m.items.size > max) ItemsEdit.Err("memory is full (${items.size}/$max entries). Use replace to merge entries or remove an outdated one first")
                else ItemsEdit.Ok(m.items, "added (${countMsg(m.items.size, max)})")
        }
    }

    /** [kinds] = the legacy target; when nothing there matches, every item is searched. */
    fun replace(items: List<MemoryItem>, kinds: Set<MemoryItem.Kind>, old: String, new: String,
                now: Long = System.currentTimeMillis(), max: Int = MemoryStore.MAX_ITEMS): ItemsEdit {
        val e = clean(new)
        if (e.isEmpty()) return ItemsEdit.Err("new content is empty; use remove to delete an entry")
        if (e.length > ENTRY_MAX) return ItemsEdit.Err("entry too long (${e.length} chars, max $ENTRY_MAX)")
        val hit = findItem(items, kinds, old) ?: return ItemsEdit.Err(notFoundItems(items, kinds, old))
        val next = items.map { if (it.id == hit.id) it.copy(text = e, updatedAt = now) else it }
        return ItemsEdit.Ok(next, "replaced (${countMsg(next.size, max)})")
    }

    fun remove(items: List<MemoryItem>, kinds: Set<MemoryItem.Kind>, old: String, max: Int = MemoryStore.MAX_ITEMS): ItemsEdit {
        val hit = findItem(items, kinds, old) ?: return ItemsEdit.Err(notFoundItems(items, kinds, old))
        val next = items.filterNot { it.id == hit.id }
        return ItemsEdit.Ok(next, "removed (${countMsg(next.size, max)})")
    }

    private fun scope(items: List<MemoryItem>, kinds: Set<MemoryItem.Kind>, old: String): List<String> {
        val inTarget = items.filter { it.kind in kinds }
        val o = old.trim().removePrefix("- ").trim()
        return (if (o.isNotEmpty() && inTarget.any { it.text.contains(o, ignoreCase = true) }) inTarget else items).map { it.text }
    }

    private fun findItem(items: List<MemoryItem>, kinds: Set<MemoryItem.Kind>, old: String): MemoryItem? {
        val texts = scope(items, kinds, old)
        val i = find(texts, old) ?: return null
        val inTarget = items.filter { it.kind in kinds }
        val pool = if (inTarget.map { it.text } == texts) inTarget else items
        return pool[i]
    }

    private fun notFoundItems(items: List<MemoryItem>, kinds: Set<MemoryItem.Kind>, old: String) = notFound(scope(items, kinds, old), old)

    /** Text the user typed in settings: trimmed to the cap at a line boundary when possible. */
    fun capUserText(text: String, cap: Int = CAP): String {
        val t = text.trim()
        if (t.length <= cap) return t
        val cut = t.take(cap)
        val nl = cut.lastIndexOf('\n')
        return (if (nl > cap / 2) cut.substring(0, nl) else cut).trimEnd()
    }
}

/**
 * 2.2.9: adapter over [MemoryStore] (memory.json in the same directory). The items are rendered
 * back into the old USER.md / MEMORY.md text so the snapshot / injection is unchanged.
 */
class MemoryDocs(dir: File) {
    val store = MemoryStore(dir)
    val cap: Int get() = MemoryStore.MAX_ITEMS

    fun read(t: MemoryTarget): String = MemoryRender.doc(store.items(), t)

    fun isEmpty(): Boolean = store.items().isEmpty()

    fun clear() = store.clear()

    /** Read-modify-write of the item list under one lock. */
    fun edit(op: (List<MemoryItem>) -> ItemsEdit): ItemsEdit = store.editItems(op)

    /**
     * 2.2.9 C: the frozen block for a new conversation: ABOUT + PREF only (newest first, within
     * [maxTokens]); FACTs are recalled per turn ([turn]). "" when there is nothing saved.
     */
    fun snapshot(maxTokens: Int = Int.MAX_VALUE / 2): String = MemoryRecall.block(MemoryRecall.fixed(store.items(), maxTokens))

    /** 2.2.9 C: the FACT block for the user message [query] ("" when nothing is relevant). */
    fun turn(query: String, maxTokens: Int): String = MemoryRecall.turnBlock(store.items(), query, maxTokens)
}

/** How the snapshot enters the system prompt. Pure. */
object MemoryPrompt {
    /** 2.3: shorter (was 35 estimated tokens); same meaning. */
    const val HEADER = "【长期记忆】（可能过时，以用户当前说法为准）"

    /** 2.2.9: line under the frozen ABOUT/PREF block, and under a turn's 【相关记忆】. */
    const val NOTE = "（以上记忆可能过时，以用户当前说法为准）"
    const val TURN_NOTE = "（相关记忆，可能过时，以用户当前说法为准；无关就忽略）"

    /**
     * 2.2.9: the system-prompt block for a frozen [snapshot]. New snapshots carry their own plain
     * labels (【关于用户】/【用户偏好】); an older one taken as USER.md / MEMORY.md text is relabelled.
     * Over [maxTokens] whole lines go from the end (the snapshot is newest first per section).
     */
    fun frozen(snapshot: String, maxTokens: Int): String {
        if (snapshot.isBlank()) return ""
        if (!snapshot.trimStart().startsWith("【")) {
            val relabelled = snapshot.replace("USER.md:", MemoryRecall.ABOUT).replace("MEMORY.md:", MemoryRecall.FACTS)
            return block(relabelled, maxTokens)
        }
        val full = snapshot.trim() + "\n" + NOTE
        if (TokenEstimator.estimate(full) <= maxTokens) return full
        val lines = snapshot.trim().lines().toMutableList()
        while (lines.size > 1 && TokenEstimator.estimate(lines.joinToString("\n") + "\n" + NOTE) > maxTokens) lines.removeAt(lines.size - 1)
        if (lines.lastOrNull()?.startsWith("【") == true) lines.removeAt(lines.size - 1)
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n" + NOTE
    }

    fun snapshot(user: String, memory: String): String = buildString {
        if (user.isNotBlank()) append("USER.md:\n").append(user.trim())
        if (memory.isNotBlank()) { if (isNotEmpty()) append("\n"); append("MEMORY.md:\n").append(memory.trim()) }
    }

    /** The block appended to the system prompt, at most [maxTokens] (whole lines dropped from the end). */
    fun block(snapshot: String, maxTokens: Int): String {
        if (snapshot.isBlank()) return ""
        val full = "$HEADER\n$snapshot"
        if (TokenEstimator.estimate(full) <= maxTokens) return full
        val lines = full.lines().toMutableList()
        while (lines.size > 1 && TokenEstimator.estimate(lines.joinToString("\n") + "\n" + TRUNCATED) > maxTokens) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n") + "\n" + TRUNCATED
    }

    /** Injection budget: a fifth of the window (the snapshot shares it with the conversation). */
    fun budget(contextTokens: Int): Int = (contextTokens / 5).coerceAtLeast(200)

    const val TRUNCATED = "（记忆较长，其余部分未放入本次上下文）"

    /**
     * 2.0.x: whether the conversation's snapshot should be (re)taken now: never taken, taken before
     * 清空记忆, or the model has not answered in this conversation yet (so memory saved in the
     * meantime — e.g. with 「记住：…」 — is seen by the first reply). After the first real reply the
     * snapshot stays frozen (stable prompt prefix for the KV cache).
     */
    fun needsSnapshot(conv: com.iris.localai.data.Conversation, epoch: Int): Boolean =
        conv.memorySnapshot == null || conv.memoryEpoch != epoch ||
            conv.messages.none { it.role == com.iris.localai.data.Role.ASSISTANT && it.modelName != null && (it.content.isNotBlank() || it.agentSteps.isNotEmpty()) }
}

/**
 * 2.0.x: saving to memory without the model — 「记住：…」 typed in the composer (both modes) and
 * 「记住这条」 on a message. Pure; the write itself goes through [MemoryDocs.edit] + [MemoryOps.add].
 */
object MemoryCommand {
    private val PREFIX = Regex("^(?:请)?(?:帮我)?记住(?:一下)?\\s*[:：,，]?\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
    private val PREFIX_EN = Regex("^remember\\s*[:：]\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    /** "记住了" / "记住吗" / "记住没" … are not commands. */
    private val NOT_A_FACT = Regex("^[了吗没嘛啊呀哦么]")
    /** 「记住这条」 typed as text points at nothing. */
    private val VAGUE = setOf("这条", "这个", "这些", "这句", "这句话", "它", "那个")
    private const val ENTRY_MAX = 300

    /** The fact to save when [text] is a remember command; null otherwise (questions are not commands). */
    fun parse(text: String): String? {
        val t = text.trim()
        val fact = (PREFIX.find(t) ?: PREFIX_EN.find(t))?.groupValues?.get(1)?.trim() ?: return null
        if (fact.length < 2 || NOT_A_FACT.containsMatchIn(fact) || fact.trimEnd('。', '.', '!', '！') in VAGUE) return null
        if (fact.endsWith("?") || fact.endsWith("？")) return null
        return fact
    }

    /** One line, whitespace collapsed, at most 300 characters (the per-entry cap of [MemoryOps]). */
    fun entryFrom(text: String): String {
        val one = text.replace(Regex("\\s+"), " ").trim().removePrefix("- ").trim()
        return if (one.length <= ENTRY_MAX) one else one.take(ENTRY_MAX - 1).trimEnd() + "…"
    }

    /** About the user ("我…", "my …") → USER.md; anything else → MEMORY.md. */
    fun suggestTarget(text: String): MemoryTarget {
        val t = text.trim().lowercase()
        return if (t.startsWith("我") || t.startsWith("用户") || t.startsWith("i ") || t.startsWith("i'm") || t.startsWith("my ")) MemoryTarget.USER else MemoryTarget.MEMORY
    }

    const val DISABLED = "长期记忆已关闭，没有保存"

    /** What the user sees after a save ([MemoryOps] messages are written for the model, in English). */
    fun userMessage(r: ItemsEdit, entry: String, target: MemoryTarget): String = when (r) {
        is ItemsEdit.Ok -> if (r.replaced != null) "已更新：$entry（原：${r.replaced}）" else "已记住：$entry（「${target.label}」，之后的新对话都会参考）"
        is ItemsEdit.Err -> when {
            r.message.startsWith("already") -> "这条已经记过了：$entry"
            r.message.startsWith("memory is full") -> "记忆已满（最多 ${MemoryStore.MAX_ITEMS} 条），请先在记忆列表里删掉一些"
            r.message.startsWith("entry too long") -> "这条太长了（每条最多 $ENTRY_MAX 字），请精简后再试"
            r.message.startsWith("content is empty") -> "没有可记住的内容"
            else -> "没有保存：${r.message}"
        }
    }
}

/** 1.10.0 settings (prefs `iris_agent`, key `memory`; no existing key changes). */
@Serializable
data class MemorySettings(
    /** Master switch: off = no injection, no memory tool. */
    val enabled: Boolean = true,
    val injectAgent: Boolean = true,
    /** 2.0.x: on by default (was false: 对话 mode never saw the memory). */
    val injectChat: Boolean = true,
    /** Deterministic compaction when the conversation outgrows the window. */
    val autoCompactAgent: Boolean = true,
    val autoCompactChat: Boolean = true,
    /** Bumped by 清空记忆: snapshots frozen before it are no longer injected. */
    val epoch: Int = 0,
    /**
     * 2.0.x: settings stored before 2.0.x (no such key → false) had 对话模式注入 off only because
     * that was the old default; [migrated] turns it on once. Stored as true afterwards, so a user who
     * then turns it off keeps it off.
     */
    val chatInjectDefaultApplied: Boolean = false,
    /** 2.2.9: 「聊天时提议记住」 — the 「＋ 记住？」 chip under a reply. */
    val suggest: Boolean = true,
) {
    fun migrated(): MemorySettings = if (chatInjectDefaultApplied) this else copy(injectChat = true, chatInjectDefaultApplied = true)

    fun inject(agentMode: Boolean): Boolean = enabled && if (agentMode) injectAgent else injectChat
    fun autoCompact(agentMode: Boolean): Boolean = if (agentMode) autoCompactAgent else autoCompactChat
}

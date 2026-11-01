package com.iris.localai.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 2.2.9: one remembered fact (replaces the lines of USER.md / MEMORY.md). */
@Serializable
data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    /** One fact, at most [MemoryStore.ITEM_MAX] characters. */
    val text: String,
    val kind: Kind,
    val source: Source,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val useCount: Int = 0,
) {
    enum class Kind { ABOUT, PREF, FACT }
    enum class Source { USER, AGENT, SUGGESTED, MIGRATED }
}

/** memory.json: the saved items and the suggestions waiting for the user (「等你确认」). */
@Serializable
data class MemoryFile(
    val version: Int = 2,
    val items: List<MemoryItem> = emptyList(),
    val pending: List<MemoryItem> = emptyList(),
)

/** Result of an item-list edit: the new list, or an error the model can act on (same texts as before). */
sealed interface ItemsEdit {
    /** [replaced] = the text of an entry this one overwrote (same slot), shown as 「已更新（原：…）」. */
    data class Ok(val items: List<MemoryItem>, val message: String, val replaced: String? = null) : ItemsEdit
    data class Err(val message: String) : ItemsEdit
}

/** Which kinds a legacy `target` (user / memory) covers. */
val MemoryTarget.kinds: Set<MemoryItem.Kind>
    get() = if (this == MemoryTarget.USER) setOf(MemoryItem.Kind.ABOUT, MemoryItem.Kind.PREF) else setOf(MemoryItem.Kind.FACT)

/** Kind of a new entry written to a legacy target: USER → PREF when it reads like a preference, else ABOUT. */
fun MemoryTarget.kindFor(text: String): MemoryItem.Kind =
    if (this == MemoryTarget.MEMORY) MemoryItem.Kind.FACT
    else if (MemorySuggest.kindOf(text) == MemoryItem.Kind.PREF) MemoryItem.Kind.PREF else MemoryItem.Kind.ABOUT

/** First-run migration from the two documents. Pure. */
object MemoryMigration {
    private fun clean(line: String) = line.replace(Regex("\\s+"), " ").trim().removePrefix("- ").removePrefix("* ").trim()

    fun fromDocs(user: String, memory: String, now: Long = System.currentTimeMillis()): List<MemoryItem> {
        var t = now
        fun conv(doc: String, kind: MemoryItem.Kind) = MemoryOps.entries(doc).map(::clean).filter { it.isNotEmpty() }.map {
            val text = if (it.length <= MemoryStore.ITEM_MAX) it else it.take(MemoryStore.ITEM_MAX - 1).trimEnd() + "…"
            MemoryItem(text = text, kind = kind, source = MemoryItem.Source.MIGRATED, createdAt = t++)
        }
        return conv(user, MemoryItem.Kind.ABOUT) + conv(memory, MemoryItem.Kind.FACT)
    }
}

/** Duplicate / slot-conflict rules. Pure. */
object MemoryMerge {
    sealed interface Outcome { val items: List<MemoryItem> }
    data class Added(override val items: List<MemoryItem>, val item: MemoryItem) : Outcome
    data class Duplicate(override val items: List<MemoryItem>, val existing: MemoryItem) : Outcome
    data class Replaced(override val items: List<MemoryItem>, val item: MemoryItem, val old: MemoryItem) : Outcome

    fun key(s: String): String = s.lowercase().replace(Regex("[\\s\\p{Punct}，。！？；：、「」“”‘’（）]+"), "")

    /** Key without a leading 我 / 用户 (so "我住在香港" and "住在香港" are the same fact). */
    private fun core(s: String) = key(s).removePrefix("用户").removePrefix("我")

    fun sameFact(a: String, b: String): Boolean {
        val x = core(a); val y = core(b)
        if (x.isEmpty() || y.isEmpty()) return x == y
        if (x == y) return true
        if (minOf(x.length, y.length) < 2) return false
        val (long, short) = if (x.length >= y.length) x to y else y to x
        if (!long.contains(short)) return false
        // "我不喜欢…" is not a duplicate of "我喜欢…".
        return long.replaceFirst(short, "").none { it in "不没别非" }
    }

    fun isDup(items: List<MemoryItem>, text: String): Boolean = items.any { sameFact(it.text, text) }

    private val NAME = Regex("^(?:我|用户)?(?:叫|的名字是|名字是|名叫)")
    private val DEVICE = Regex("(?:^(?:我|用户)?(?:在用|用|使用)(?:的是)?.{1,30}手机)|(?:手机(?:是|用的是|型号是))")
    private val LOCATION = Regex("^(?:我|用户)?(?:现在|目前)?(?:住在|住|搬到了?|搬去了?|定居在?|在(?!用|做|想|学|看|写|找|问|考虑|开发|使用))")
    private val RULE = Regex("^(?:请|你)?(?:以后|之后|今后)(?:都|请|你)?(.+)$")
    private val NEVER = Regex("^(?:请|你)?(?:别|不要)再(.+)$")

    /** The "slot" a fact fills; two facts in one slot conflict (the newer replaces the older). */
    fun slot(text: String): String? {
        val t = text.replace(Regex("\\s+"), "").trim()
        return when {
            NAME.containsMatchIn(t) -> "name"
            DEVICE.containsMatchIn(t) -> "device"
            LOCATION.containsMatchIn(t) -> "location"
            else -> (RULE.find(t) ?: NEVER.find(t))?.groupValues?.get(1)
                ?.replace(Regex("^(?:都|请|你|给我|帮我|也)+"), "")?.let(::key)?.take(4)?.takeIf { it.isNotEmpty() }?.let { "rule:$it" }
        }
    }

    fun merge(items: List<MemoryItem>, item: MemoryItem, now: Long = item.updatedAt): Outcome {
        items.firstOrNull { sameFact(it.text, item.text) }?.let { ex ->
            val bumped = ex.copy(updatedAt = now)
            return Duplicate(items.map { if (it.id == ex.id) bumped else it }, bumped)
        }
        val s = slot(item.text)
        if (s != null) items.firstOrNull { slot(it.text) == s }?.let { old ->
            val next = old.copy(text = item.text, kind = item.kind, source = item.source, updatedAt = now)
            return Replaced(items.map { if (it.id == old.id) next else it }, next, old)
        }
        return Added(items + item, item)
    }
}

/** memory.json in the same directory as the old USER.md / MEMORY.md; atomic writes. */
class MemoryStore(private val dir: File) {
    companion object {
        const val FILE = "memory.json"
        const val MAX_ITEMS = 200
        const val ITEM_MAX = 300
        const val MAX_PENDING = 30
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private val lock = Any()
    private var cache: MemoryFile? = null

    fun file(): MemoryFile = synchronized(lock) {
        cache ?: load().also { cache = it }
    }

    fun items(): List<MemoryItem> = file().items
    fun pending(): List<MemoryItem> = file().pending

    private fun load(): MemoryFile {
        val f = File(dir, FILE)
        if (f.isFile) return runCatching { json.decodeFromString(MemoryFile.serializer(), f.readText(Charsets.UTF_8)) }.getOrElse { MemoryFile() }
        val user = File(dir, MemoryTarget.USER.fileName)
        val mem = File(dir, MemoryTarget.MEMORY.fileName)
        if (!user.isFile && !mem.isFile) return MemoryFile()
        val migrated = MemoryFile(items = MemoryMigration.fromDocs(
            user.takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: "",
            mem.takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: "",
        ))
        write(migrated)
        listOf(user, mem).filter { it.isFile }.forEach { it.renameTo(File(dir, it.name + ".bak")) }
        return migrated
    }

    private fun write(m: MemoryFile) {
        dir.mkdirs()
        val f = File(dir, FILE)
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json.encodeToString(MemoryFile.serializer(), m), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    }

    private fun save(m: MemoryFile) { write(m); cache = m }

    /** Read-modify-write of the item list under one lock (the agent tool, 「记住：…」). */
    fun editItems(op: (List<MemoryItem>) -> ItemsEdit): ItemsEdit = synchronized(lock) {
        val cur = file()
        val r = op(cur.items)
        if (r is ItemsEdit.Ok) {
            // A saved fact no longer waits in 「等你确认」.
            save(cur.copy(items = r.items, pending = cur.pending.filterNot { p -> r.items.any { MemoryMerge.sameFact(it.text, p.text) } }))
        }
        r
    }

    fun add(text: String, kind: MemoryItem.Kind, source: MemoryItem.Source, now: Long = System.currentTimeMillis()): ItemsEdit =
        editItems { MemoryOps.add(it, kind, text, source, now) }

    fun update(id: String, text: String, kind: MemoryItem.Kind, now: Long = System.currentTimeMillis()): ItemsEdit = editItems { items ->
        val t = MemoryCommand.entryFrom(text)
        when {
            t.isEmpty() -> ItemsEdit.Err("content is empty")
            items.none { it.id == id } -> ItemsEdit.Err("no such entry")
            else -> ItemsEdit.Ok(items.map { if (it.id == id) it.copy(text = t, kind = kind, updatedAt = now) else it }, "updated")
        }
    }

    fun delete(id: String) = synchronized(lock) { val c = file(); save(c.copy(items = c.items.filterNot { it.id == id })) }

    /** Adds a suggestion to 「等你确认」 (skipped when already saved or already waiting). */
    fun addPending(text: String, kind: MemoryItem.Kind, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val c = file()
        if (MemoryMerge.isDup(c.items, text) || MemoryMerge.isDup(c.pending, text)) return false
        val p = MemoryItem(text = text, kind = kind, source = MemoryItem.Source.SUGGESTED, createdAt = now)
        save(c.copy(pending = (c.pending + p).takeLast(MAX_PENDING)))
        true
    }

    fun acceptPending(id: String, now: Long = System.currentTimeMillis()): ItemsEdit {
        val p = pending().firstOrNull { it.id == id } ?: return ItemsEdit.Err("no such entry")
        val r = add(p.text, p.kind, MemoryItem.Source.USER, now)
        if (r is ItemsEdit.Err) dismissPending(id)
        return r
    }

    fun dismissPending(id: String) = synchronized(lock) { val c = file(); save(c.copy(pending = c.pending.filterNot { it.id == id })) }

    fun dismissPendingText(text: String) = synchronized(lock) {
        val c = file(); save(c.copy(pending = c.pending.filterNot { MemoryMerge.sameFact(it.text, text) }))
    }

    fun clear() = synchronized(lock) {
        save(MemoryFile())
        MemoryTarget.entries.forEach { File(dir, it.fileName).delete() }
    }
}

/** The adapter: items rendered back into the old two-document text, so injection keeps working. Pure. */
object MemoryRender {
    fun doc(items: List<MemoryItem>, t: MemoryTarget): String {
        val ks = if (t == MemoryTarget.USER) listOf(MemoryItem.Kind.ABOUT, MemoryItem.Kind.PREF) else listOf(MemoryItem.Kind.FACT)
        return ks.flatMap { k -> items.filter { it.kind == k } }.joinToString("\n") { "- ${it.text}" }
    }
}

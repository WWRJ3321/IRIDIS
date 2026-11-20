package com.iris.localai.data

import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Conversations are stored one JSON file per conversation plus a small index, all written
 * atomically (AtomicFile) so a crash or kill mid-write can never corrupt history.
 */
class ConversationRepository(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** told about every save / delete (the raw history index follows the JSON files). Never throws into a save. */
    interface Listener {
        fun onSaved(conversation: Conversation)
        fun onDeleted(id: String)
        fun onDeletedAll()
    }

    @Volatile var listener: Listener? = null

    private inline fun notify(block: Listener.() -> Unit) {
        val l = listener ?: return
        runCatching { l.block() }.onFailure { Log.w(TAG, "history listener failed", it) }
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val indexFile get() = AtomicFile(File(dir, "index.json"))

    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()

    @Volatile private var initialized = false

    /** 侧栏长按拖动排出的顺序（order.json，id 列表；空 = 按最近更新）。不在列表里的（新对话）排在最上面。 */
    @Volatile private var order: List<String> = emptyList()
    private val _customOrder = MutableStateFlow(false)
    val customOrder: StateFlow<Boolean> = _customOrder.asStateFlow()
    private val orderFile get() = AtomicFile(File(dir, "order.json"))

    /** Idempotent: the app and the chat screen both call it at start-up; the index is read once. */
    suspend fun init() = withContext(io) {
        if (initialized) return@withContext
        mutex.withLock {
            if (initialized) return@withLock
            dir.mkdirs()
            val fromIndex = runCatching {
                json.decodeFromString<List<ConversationSummary>>(indexFile.readFully().decodeToString())
            }.getOrNull()
            val list = fromIndex ?: rebuildIndexLocked()
            order = runCatching { json.decodeFromString<List<String>>(orderFile.readFully().decodeToString()) }.getOrDefault(emptyList())
            _customOrder.value = order.isNotEmpty()
            _summaries.value = sorted(list.filter { isSafeId(it.id) && fileOf(it.id).exists() })
            runCatching { loadTrashLocked() }.onFailure { Log.w(TAG, "trash index failed", it) }
            initialized = true
        }
    }

    // Reads take the lock too: AtomicFile.readFully() restores/deletes the backup file, which
    // must never interleave with a concurrent write of the same conversation.
    suspend fun load(id: String): Conversation? = withContext(io) {
        mutex.withLock { loadLocked(id) }
    }

    suspend fun save(conversation: Conversation) = withContext(io) {
        mutex.withLock { saveLocked(conversation) }
        notify { onSaved(conversation) }
    }

    /** chats in 回收站, one at a time (their attachments are still referenced). */
    suspend fun forEachTrashed(block: (Conversation) -> Unit) = withContext(io) {
        for (t in _trash.value) mutex.withLock {
            runCatching { json.decodeFromString<Conversation>(AtomicFile(trashFile(t.id)).readFully().decodeToString()) }.getOrNull()
        }?.let(block)
    }

    /** every saved conversation, one at a time (history index backfill). */
    suspend fun forEachSaved(block: (Conversation) -> Unit) = withContext(io) {
        for (s in _summaries.value) load(s.id)?.let(block)
    }

    private fun loadLocked(id: String): Conversation? {
        if (!isSafeId(id)) return null
        val f = fileOf(id)
        if (!f.exists() && !File(f.path + ".bak").exists()) return null
        return runCatching {
            json.decodeFromString<Conversation>(AtomicFile(f).readFully().decodeToString())
        }.onFailure { Log.w(TAG, "load($id) failed", it) }.getOrNull()
    }

    private fun saveLocked(conversation: Conversation) {
        if (!isSafeId(conversation.id)) throw IllegalArgumentException("invalid conversation id")
        writeAtomic(fileOf(conversation.id), json.encodeToString(Conversation.serializer(), conversation))
        val summary = ConversationSummary(
            conversation.id, conversation.title, conversation.updatedAt, conversation.pinned,
        )
        val next = _summaries.value.filterNot { it.id == conversation.id } + summary
        publishLocked(next)
    }

    suspend fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim(), titled = true) }

    suspend fun setPinned(id: String, pinned: Boolean) = update(id) { it.copy(pinned = pinned) }

    /** 侧栏拖动排序的结果（显示顺序的全部 id）；只记顺序，不改对话本身。 */
    suspend fun reorder(ids: List<String>) = withContext(io) {
        mutex.withLock {
            val known = _summaries.value.map { it.id }.toSet()
            order = ids.filter { it in known && isSafeId(it) }.distinct()
            writeAtomic(File(dir, "order.json"), kotlinx.serialization.json.JsonArray(order.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
            _customOrder.value = order.isNotEmpty()
            publishLocked(_summaries.value)
        }
    }

    // ---- 2.2.14: 回收站. Deleted chats move to a sibling folder and are purged after 30 days. ----

    private val trashDir get() = File(dir.parentFile ?: dir, dir.name + "-trash")
    private val _trash = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trash: StateFlow<List<TrashEntry>> = _trash.asStateFlow()

    private fun trashFile(id: String) = File(trashDir, "$id.json")

    /** Called with a chat as it leaves 回收站 for good (its attachment copies go with it). */
    @Volatile var purgeHook: ((Conversation) -> Unit)? = null

    private fun purgeFileLocked(id: String) {
        val f = trashFile(id)
        purgeHook?.let { h ->
            runCatching { json.decodeFromString<Conversation>(AtomicFile(f).readFully().decodeToString()) }.getOrNull()
                ?.let { c -> runCatching { h(c) } }
        }
        AtomicFile(f).delete()
    }

    private fun loadTrashLocked() {
        val list = runCatching {
            json.decodeFromString<List<TrashEntry>>(AtomicFile(File(trashDir, "index.json")).readFully().decodeToString())
        }.getOrNull().orEmpty().filter { isSafeId(it.id) && trashFile(it.id).exists() }
        val now = System.currentTimeMillis()
        val (expired, keep) = list.partition { TrashPolicy.expired(it.deletedAt, now) }
        expired.forEach { purgeFileLocked(it.id) }
        publishTrashLocked(keep)
    }

    private fun publishTrashLocked(list: List<TrashEntry>) {
        val s = list.sortedByDescending { it.deletedAt }
        _trash.value = s
        trashDir.mkdirs()
        writeAtomic(File(trashDir, "index.json"), json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TrashEntry.serializer()), s))
    }

    private fun moveToTrashLocked(id: String, now: Long) {
        if (!isSafeId(id)) return
        val src = fileOf(id)
        val conv = loadLocked(id)
        if (conv == null) { AtomicFile(src).delete(); return }
        trashDir.mkdirs()
        writeAtomic(trashFile(id), json.encodeToString(Conversation.serializer(), conv))
        AtomicFile(src).delete()
        _trashPending += TrashEntry(id, conv.title, now)
    }

    private val _trashPending = ArrayList<TrashEntry>()

    private fun flushTrashLocked() {
        if (_trashPending.isEmpty()) return
        val ids = _trashPending.map { it.id }.toSet()
        publishTrashLocked(_trash.value.filterNot { it.id in ids } + _trashPending)
        _trashPending.clear()
    }

    /** Moves the chat to 回收站. */
    suspend fun delete(id: String) = withContext(io) {
        mutex.withLock {
            moveToTrashLocked(id, System.currentTimeMillis())
            flushTrashLocked()
            publishLocked(_summaries.value.filterNot { it.id == id })
        }
        notify { onDeleted(id) }
    }

    /** [permanent] (存储页「删除全部聊天记录」) also empties 回收站; otherwise everything moves there. */
    suspend fun deleteAll(permanent: Boolean = false) = withContext(io) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (permanent) {
                _trash.value.forEach { AtomicFile(trashFile(it.id)).delete() }
                publishTrashLocked(emptyList())
            } else {
                _summaries.value.forEach { moveToTrashLocked(it.id, now) }
                flushTrashLocked()
            }
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
            publishLocked(emptyList())
        }
        notify { onDeletedAll() }
    }

    /** Back from 回收站 into the list (keeps its own dates). */
    suspend fun restore(id: String): Conversation? = withContext(io) {
        val conv = mutex.withLock {
            if (!isSafeId(id)) return@withLock null
            val c = runCatching { json.decodeFromString<Conversation>(AtomicFile(trashFile(id)).readFully().decodeToString()) }.getOrNull()
            if (c != null) saveLocked(c)
            AtomicFile(trashFile(id)).delete()
            publishTrashLocked(_trash.value.filterNot { it.id == id })
            c
        }
        conv?.let { notify { onSaved(it) } }
        conv
    }

    suspend fun purge(id: String) = withContext(io) {
        mutex.withLock {
            if (isSafeId(id)) purgeFileLocked(id)
            publishTrashLocked(_trash.value.filterNot { it.id == id })
        }
    }

    suspend fun emptyTrash() = withContext(io) {
        mutex.withLock {
            _trash.value.forEach { purgeFileLocked(it.id) }
            publishTrashLocked(emptyList())
        }
    }

    /** Read-modify-write under one lock, so a rename can't overwrite a reply saved meanwhile. */
    private suspend fun update(id: String, block: (Conversation) -> Conversation) = withContext(io) {
        mutex.withLock {
            val conv = loadLocked(id) ?: return@withLock
            saveLocked(block(conv))
        }
    }

    /** Ids become file names; refuse anything that could escape the directory. */
    private fun isSafeId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun fileOf(id: String) = File(dir, "$id.json")

    private fun publishLocked(list: List<ConversationSummary>) {
        val s = sorted(list)
        _summaries.value = s
        writeAtomic(File(dir, "index.json"), json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ConversationSummary.serializer()), s,
        ))
    }

    private fun rebuildIndexLocked(): List<ConversationSummary> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "index.json" }
            .orEmpty()
            .mapNotNull { f ->
                runCatching {
                    val c = json.decodeFromString<Conversation>(AtomicFile(f).readFully().decodeToString())
                    ConversationSummary(c.id, c.title, c.updatedAt, c.pinned)
                }.getOrNull()
            }

    private fun writeAtomic(file: File, text: String) {
        val af = AtomicFile(file)
        val out = af.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (t: Throwable) {
            af.failWrite(out)
            throw t
        }
    }

    private fun sorted(list: List<ConversationSummary>) = ConversationOrder.sort(list, order)

    private companion object {
        const val TAG = "ConversationRepo"
    }
}

/** 侧栏排序（纯函数，单测）：置顶在前；组内新对话（不在自定义顺序里）在上、按更新时间，其余按拖出的顺序。 */
object ConversationOrder {
    /** 2.7.4：长按后手指累计位移超过 touchSlop 才算拖动排序；没超过松手 = 弹菜单。 */
    fun isDrag(totalDx: Float, totalDy: Float, slop: Float): Boolean =
        kotlin.math.abs(totalDy) > slop || kotlin.math.abs(totalDx) > slop
    fun sort(list: List<ConversationSummary>, order: List<String>): List<ConversationSummary> {
        val pos = HashMap<String, Int>().also { m -> order.forEachIndexed { i, id -> m.putIfAbsent(id, i) } }
        return list.sortedWith(
            compareByDescending<ConversationSummary> { it.pinned }
                .thenBy { if (it.id in pos) 1 else 0 }
                .thenBy { pos[it.id] ?: 0 }
                .thenByDescending { it.updatedAt },
        )
    }

    /** 拖动：把 [fromId] 放到 [toId] 的位置；只在同一组（置顶 / 非置顶）里移动，跨组不动。 */
    fun move(list: List<ConversationSummary>, fromId: String, toId: String): List<ConversationSummary> {
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to || list[from].pinned != list[to].pinned) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/**
 * 侧栏对话的统一手势判定（纯函数，单测；2.7.5 起，2.7.6 去掉长按菜单）。一个 pointerInput 里走完：按下 → 等长按超时 → 之后看位移。
 * 长按前：抬起 = 短按打开；移动超过 touchSlop 或事件被别人消费 = 放弃（交给列表滚动）。
 * 长按到时（没动）：震动并直接进入多选、选中该条（按住时就显示）。
 * 之后：原地松手 = 留在多选；移动超过 touchSlop = 退出多选并拖动排序。
 */
object HistoryPress {
    /**
     * 2.7.6：TAP 短按打开；CANCEL 放弃（交给列表滚动）；SELECT 长按到时（手指没动）直接进多选并选中该条；
     * KEEP 进多选后原地松手（留在多选）；DRAG 进多选后移动超过 touchSlop（退出多选、拖动排序）。
     */
    enum class Outcome { TAP, CANCEL, SELECT, KEEP, DRAG }

    /** 长按超时前的一次事件；null = 继续等。超时前移动 = 普通滚动（CANCEL）。 */
    fun beforeLongPress(up: Boolean, consumed: Boolean, dx: Float, dy: Float, slop: Float): Outcome? = when {
        up -> if (consumed) Outcome.CANCEL else Outcome.TAP
        consumed || ConversationOrder.isDrag(dx, dy, slop) -> Outcome.CANCEL
        else -> null
    }

    /** 长按超时到了（期间没移动超 slop）：按住时就进入多选。 */
    fun onLongPress(): Outcome = Outcome.SELECT

    /** 进入多选后、仍按住时的一次事件；null = 继续等。松手 = 留在多选；移动超 slop = 拖动。 */
    fun afterLongPress(up: Boolean, dx: Float, dy: Float, slop: Float): Outcome? = when {
        up -> Outcome.KEEP
        ConversationOrder.isDrag(dx, dy, slop) -> Outcome.DRAG
        else -> null
    }

    /** 各结果对多选集合的作用：SELECT 选中该条；DRAG 退出多选（清空）；其余不变。 */
    fun selection(outcome: Outcome, selected: Set<String>, id: String): Set<String> = when (outcome) {
        Outcome.SELECT -> selected + id
        Outcome.DRAG -> emptySet()
        else -> selected
    }

    /**
     * 拖动中条目的 translationY：起拖时的布局位置 + 手指（屏幕坐标）位移 − 当前布局位置。
     * 起拖那一刻为 0（不跳位）；重排 / 分组变平铺导致布局位置变化时自动抵消。
     */
    fun dragOffset(startOffset: Int, fingerDy: Float, currentOffset: Int): Float = startOffset + fingerDy - currentOffset
}

/** one chat in 回收站. */
@kotlinx.serialization.Serializable
data class TrashEntry(val id: String, val title: String, val deletedAt: Long)

object TrashPolicy {
    const val KEEP_DAYS = 30
    fun expired(deletedAt: Long, now: Long, days: Int = KEEP_DAYS): Boolean = now - deletedAt >= days * 86_400_000L
    fun daysLeft(deletedAt: Long, now: Long, days: Int = KEEP_DAYS): Int =
        ((days * 86_400_000L - (now - deletedAt)) / 86_400_000L).toInt().coerceIn(0, days)
}

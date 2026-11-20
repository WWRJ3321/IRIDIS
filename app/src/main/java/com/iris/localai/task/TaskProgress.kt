package com.iris.localai.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 2.7.2：长任务进度的唯一状态源（主进程单例）。任务方写（begin / progress / finish），所有「显示面」只读：
 * 进度通知、HyperOS 超级岛、生图页；以后的应用内灵动岛、语音播报、悬浮窗也挂这里（[TaskSurface]），
 * 不再各自持有一份进度。每个任务一个槽（[slot]，按 id），另有 [active] 汇总正在跑的全部任务。纯 JVM（单测）。
 */
object TaskProgress {
    /** 任务种类：显示面据此挑图标 / 文案。目前只有生图接入。 */
    enum class Kind { IMAGE_GEN, CHAT, DOWNLOAD, VOICE, OTHER }

    enum class Phase { RUNNING, DONE, STOPPED, FAILED }

    data class Task(
        val id: String,
        val kind: Kind,
        /** 显示面的标题（通知标题 / 岛上的主文字）。 */
        val title: String,
        /** 次要文字（生图 = 模型名）。 */
        val subtitle: String = "",
        val step: Int = 0,
        val total: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        val phase: Phase = Phase.RUNNING,
        /** 成功产物（生图 = 图片路径）。 */
        val result: String? = null,
        val error: String? = null,
        /** 每次 begin 递增（显示面 / 页面据此分辨是不是同一个任务）。 */
        val serial: Long = 0,
        /** 种类专属数据（生图 = 预览帧、种子等），显示面不认识就忽略。 */
        val payload: Any? = null,
    ) {
        val finished: Boolean get() = phase != Phase.RUNNING
        val stopped: Boolean get() = phase == Phase.STOPPED
        /** 0..100；总步数未知 = -1（不定进度）。 */
        val percent: Int get() = if (total <= 0) -1 else (step.coerceAtLeast(0) * 100 / total).coerceIn(0, 100)
    }

    /** 一个显示面（通知、超级岛、应用内灵动岛、悬浮窗、语音…）：任务每次变化都会收到最新状态（结束后收到 finished 的那一份）。 */
    fun interface TaskSurface {
        fun render(task: Task)
    }

    private val slots = HashMap<String, MutableStateFlow<Task?>>()
    private val stops = HashMap<String, () -> Unit>()
    private val surfaces = java.util.concurrent.CopyOnWriteArrayList<TaskSurface>()
    private val _active = MutableStateFlow<List<Task>>(emptyList())
    /** 正在跑的任务（按开始时间），给以后的应用内灵动岛 / 悬浮窗用。 */
    val active: StateFlow<List<Task>> = _active.asStateFlow()
    @Volatile private var serial = 0L

    private fun mutable(id: String): MutableStateFlow<Task?> = synchronized(slots) { slots.getOrPut(id) { MutableStateFlow(null) } }

    /** 任务 [id] 的状态（null = 从没开始过）。 */
    fun slot(id: String): StateFlow<Task?> = mutable(id).asStateFlow()

    fun current(id: String): Task? = mutable(id).value

    fun running(id: String): Boolean = current(id)?.finished == false

    /** 开始（或重新开始）任务 [id]；[stop] = 显示面上的「停止」。返回这次的序号。 */
    fun begin(id: String, kind: Kind, title: String, subtitle: String = "", total: Int = 0, payload: Any? = null, stop: (() -> Unit)? = null): Long {
        val s = synchronized(slots) { ++serial }
        synchronized(stops) { if (stop != null) stops[id] = stop else stops.remove(id) }
        publish(id) { Task(id, kind, title, subtitle, total = total, serial = s, payload = payload) }
        return s
    }

    fun progress(id: String, step: Int, total: Int) = publish(id) { t -> if (t == null || t.finished) t else t.copy(step = step, total = total) }

    /** 改种类专属数据（预览帧等）；任务已结束则不动。 */
    fun payload(id: String, block: (Any?) -> Any?) = publish(id) { t -> if (t == null || t.finished) t else t.copy(payload = block(t.payload)) }

    fun finish(id: String, phase: Phase, result: String? = null, error: String? = null, payload: ((Any?) -> Any?)? = null) {
        synchronized(stops) { stops.remove(id) }
        publish(id) { t ->
            if (t == null) null
            else t.copy(phase = if (phase == Phase.RUNNING) Phase.DONE else phase, result = result, error = error, payload = payload?.invoke(t.payload) ?: t.payload)
        }
    }

    /** 显示面上的「停止」。 */
    fun requestStop(id: String) { synchronized(stops) { stops[id] }?.invoke() }

    fun addSurface(s: TaskSurface) { surfaces += s }
    fun removeSurface(s: TaskSurface) { surfaces -= s }

    private fun publish(id: String, block: (Task?) -> Task?) {
        val flow = mutable(id)
        var changed: Task? = null
        flow.update { old -> block(old).also { if (it != old) changed = it } }
        val t = changed ?: return
        _active.update { list -> (list.filter { it.id != id } + listOfNotNull(t.takeIf { !it.finished })).sortedBy { it.startedAt } }
        for (s in surfaces) runCatching { s.render(t) }
    }

    /** 只给单测：清空。 */
    internal fun resetForTest() {
        synchronized(slots) { slots.clear() }
        synchronized(stops) { stops.clear() }
        surfaces.clear(); _active.value = emptyList()
    }
}

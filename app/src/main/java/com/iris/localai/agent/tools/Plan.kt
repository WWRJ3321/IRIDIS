package com.iris.localai.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 1.14.0: one step of a plan. */
@Serializable
enum class PlanStatus(val mark: String, val label: String) { TODO(" ", "待办"), DOING("~", "进行中"), DONE("x", "完成"), SKIPPED("-", "跳过") }

@Serializable
data class PlanStep(val text: String, val status: PlanStatus = PlanStatus.TODO)

/**
 * 1.14.0 计划模式: the plan of one conversation. It is written into the system prompt on every
 * run (never into the history), so compaction can never fold it away; it stays until every step
 * is done or the plan is cleared.
 */
@Serializable
data class Plan(
    val title: String = "",
    val steps: List<PlanStep> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val done: Int get() = steps.count { it.status == PlanStatus.DONE || it.status == PlanStatus.SKIPPED }
    val finished: Boolean get() = steps.isNotEmpty() && done == steps.size
    val current: Int get() = steps.indexOfFirst { it.status == PlanStatus.DOING }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.status == PlanStatus.TODO }

    /** The block the system prompt carries (English frame, the user's own step words). */
    fun promptBlock(): String = buildString {
        append("# Current plan").append(if (title.isNotBlank()) " — $title" else "").append(" (kept until done; update it with plan)\n")
        steps.forEachIndexed { i, s -> append(i + 1).append(". [").append(s.status.mark).append("] ").append(s.text).append('\n') }
        if (!finished) append("Continue with step ").append(current + 1).append(". Mark it done with plan(action: done, step: ").append(current + 1).append(").\n")
    }.trimEnd()
}

/** Plans by conversation id; [save] persists the whole map (JSON). */
class PlanStore(private val load: () -> String? = { null }, private val save: (String) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = MapSerializer(String.serializer(), Plan.serializer())
    private val _plans = MutableStateFlow(read())
    val plans: StateFlow<Map<String, Plan>> = _plans.asStateFlow()

    private fun read(): Map<String, Plan> = load()?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()

    fun get(convId: String?): Plan? = convId?.let { _plans.value[it] }
    @Synchronized fun put(convId: String, plan: Plan?) {
        val m = _plans.value.toMutableMap()
        if (plan == null || plan.steps.isEmpty()) m.remove(convId) else m[convId] = plan
        // Old finished plans go first when there are too many.
        val trimmed = if (m.size <= MAX) m else m.entries.sortedBy { it.value.updatedAt }.drop(m.size - MAX).associate { it.key to it.value }
        _plans.value = trimmed
        save(json.encodeToString(ser, trimmed))
    }
    fun clear(convId: String) = put(convId, null)

    companion object { const val MAX = 40; const val MAX_STEPS = 12 }
}

/** 1.14.0 `plan`: set / update / show the conversation's plan. Replaces the older `todo`. */
object PlanTool : AgentTool {
    override val name = "plan"
    override val label = "计划"
    override val description = "多步任务先列计划（界面显示为卡片，压缩后仍保留），逐步标记完成"
    override val promptLine = "plan(action: set|done|skip|show|clear, steps?, step?, title?)"
    override val risk = ToolRisk.SAFE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val store = env.plans ?: return ToolResult(false, "Error: plans are not available", "不可用")
        val id = env.conversationId ?: "_"
        val now = env.clock()
        val cur = store.get(id)
        val steps = stepsOf(args)
        val action = (Args.str(args, "action", "op") ?: if (steps != null) "set" else if (args["done"] != null) "done" else "show").trim().lowercase()
        val n = Args.str(args, "step", "n", "index", "done")?.trim()?.toIntOrNull()
        fun mark(st: PlanStatus): ToolResult {
            val p = cur ?: return ToolResult(false, "Error: no plan yet. Call plan(action: set, steps: [...]) first.", "暂无计划")
            val i = (n ?: (p.current + 1)) - 1
            if (i !in p.steps.indices) return ToolResult(false, "Error: step must be 1..${p.steps.size}", "步骤不存在")
            var list = p.steps.mapIndexed { k, s -> if (k == i) s.copy(status = st) else if (st == PlanStatus.DOING && s.status == PlanStatus.DOING) s.copy(status = PlanStatus.TODO) else s }
            // Finishing a step starts the next one.
            if (st == PlanStatus.DONE || st == PlanStatus.SKIPPED) list.indexOfFirst { it.status == PlanStatus.TODO }.takeIf { it >= 0 && list.none { s -> s.status == PlanStatus.DOING } }
                ?.let { nx -> list = list.mapIndexed { k, s -> if (k == nx) s.copy(status = PlanStatus.DOING) else s } }
            val np = p.copy(steps = list, updatedAt = now)
            store.put(id, np)
            return show(np)
        }
        return when (action) {
            "set", "new", "create", "replace" -> {
                val list = steps?.take(PlanStore.MAX_STEPS) ?: throw IllegalArgumentException("缺少参数 steps")
                if (list.isEmpty()) throw IllegalArgumentException("steps 为空")
                val p = Plan(Args.str(args, "title", "goal", "name").orEmpty().take(60),
                    list.mapIndexed { i, t -> PlanStep(t.take(120), if (i == 0) PlanStatus.DOING else PlanStatus.TODO) }, now)
                store.put(id, p)
                show(p)
            }
            "add", "append" -> {
                val p = cur ?: Plan(updatedAt = now)
                val more = steps ?: listOfNotNull(Args.str(args, "text", "item"))
                val np = p.copy(steps = (p.steps + more.map { PlanStep(it.take(120)) }).take(PlanStore.MAX_STEPS), updatedAt = now)
                store.put(id, np); show(np)
            }
            "done", "check", "complete", "tick" -> mark(PlanStatus.DONE)
            "doing", "start", "progress" -> mark(PlanStatus.DOING)
            "skip", "skipped" -> mark(PlanStatus.SKIPPED)
            "clear", "reset", "delete" -> { store.clear(id); ToolResult(true, "Plan cleared.", "已清除") }
            else -> cur?.let { show(it) } ?: ToolResult(true, "No plan yet.", "暂无计划")
        }
    }

    private fun show(p: Plan): ToolResult =
        ToolResult(true, p.promptBlock() + if (p.finished) "\nAll steps done." else "", if (p.finished) "全部完成" else "${p.done}/${p.steps.size} 完成", plan = p)

    internal fun stepsOf(args: JsonObject): List<String>? = (args["steps"] ?: args["items"] ?: args["todos"])?.let { el ->
        (el as? JsonArray)?.mapNotNull { e ->
            ((e as? JsonPrimitive)?.content ?: (e as? JsonObject)?.let { o -> Args.str(o, "text", "step", "title", "content") })?.trim()?.takeIf(String::isNotEmpty)
        } ?: (el as? JsonPrimitive)?.content?.split('\n', ';', '；')?.map { it.trim().trimStart('-', '*', ' ').replace(Regex("^\\d+[.)、]\\s*"), "") }?.filter { it.isNotEmpty() }
    }
}

/**
 * 1.14.0 按需载入: groups listed only by name in the system prompt; `activate(group)` returns
 * their full usage as a tool result (appended at the end, so the KV-cache prefix stays valid).
 */
object ActivateTool : AgentTool {
    override val name = "activate"
    override val label = "载入工具"
    override val description = "按需载入一组工具的完整用法（提示里只列名称，省上下文）"
    override val promptLine = "activate(group): load a tool group"
    override val risk = ToolRisk.SAFE

    /** Groups the prompt lists by name only when 按需载入 is on. */
    val LAZY = setOf(ToolGroup.PHONE)

    fun key(g: ToolGroup): String = g.title.lowercase()

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val raw = (Args.str(args, "group", "name", "tool", "toolset") ?: throw IllegalArgumentException("缺少参数 group")).trim().lowercase()
        val enabled = env.enabledTools
        val group = ToolGroup.entries.firstOrNull { key(it) == raw || it.label == raw || it.name.lowercase() == raw }
            ?: enabled.firstOrNull { it.name == raw }?.group
            ?: return ToolResult(false, "Error: no group \"$raw\". Groups: ${enabled.map { key(it.group) }.distinct().joinToString()}", "没有这组工具")
        val ts = enabled.filter { it.group == group }
        if (ts.isEmpty()) return ToolResult(false, "Error: the ${key(group)} tools are switched off in settings", "已关闭")
        val out = buildString {
            append("## ").append(group.title)
            if (group.hint.isNotEmpty()) append(" — ").append(group.hint)
            append('\n')
            ts.forEach { append("- ").append(it.promptLine).append('\n') }
            if (ts.any { it.promptLine.contains("time?") }) append("time = ").append(AgentPrompt.TIME_FORMAT).append(", local\n")
            append("You can call these tools now.")
        }
        return ToolResult(true, out, "已载入 ${group.label} · ${ts.size} 个")
    }
}

/** One [PlanStore] per process, persisted in SharedPreferences (2.2.2: was part of the removed heartbeat). */
object Plans {
    private const val PREFS = "iris_plans"
    @Volatile private var store: PlanStore? = null
    fun of(ctx: android.content.Context): PlanStore = store ?: synchronized(this) {
        store ?: run {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            PlanStore({ p.getString("plans", null) }, { p.edit().putString("plans", it).apply() }).also { store = it }
        }
    }
}

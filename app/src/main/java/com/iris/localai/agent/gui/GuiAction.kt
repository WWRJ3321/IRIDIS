package com.iris.localai.agent.gui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * GUI 智能体（第一阶段）：看屏幕 → 思考 → 操作。这里是纯逻辑（JVM 单测覆盖）：动作 schema、
 * 各 GUI 模型输出格式的解析、坐标归一化。安卓侧执行见 agent/gui/android/。
 */

/** A point as a fraction of the screen (0..1 on both axes), independent of the model's grid. */
data class NPoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) { "point out of range: $x,$y" } }
}

enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

/** What the agent can do on the phone. Coordinates are [NPoint]; [CoordMap] turns them into pixels. */
sealed interface GuiAction {
    /** [note] = the model flagged the tap as sensitive (AutoGLM `message="重要操作"`). */
    data class Tap(val at: NPoint, val note: String? = null) : GuiAction
    data class LongPress(val at: NPoint) : GuiAction
    data class DoubleTap(val at: NPoint) : GuiAction
    data class Swipe(val from: NPoint, val to: NPoint, val durationMs: Long = DEFAULT_SWIPE_MS) : GuiAction
    /** Types into the focused field, replacing what it holds. */
    data class Type(val text: String) : GuiAction
    data object Back : GuiAction
    data object Home : GuiAction
    /** Enter / IME action on the focused field. */
    data object Enter : GuiAction
    data class Launch(val app: String) : GuiAction
    data class Wait(val ms: Long) : GuiAction
    data class Finish(val message: String, val success: Boolean = true) : GuiAction
    /** The user must act (login, captcha, verification code); the agent waits. */
    data class TakeOver(val message: String) : GuiAction
    /** The model asks the user to choose (AutoGLM Interact / MAI-UI ask_user). */
    data class Ask(val question: String) : GuiAction

    companion object {
        const val DEFAULT_SWIPE_MS = 400L
        const val MAX_WAIT_MS = 10_000L
    }
}

/** One line for the step card / history (Chinese). */
fun GuiAction.label(): String = when (this) {
    is GuiAction.Tap -> "点击 ${pt(at)}" + (note?.let { "（$it）" } ?: "")
    is GuiAction.LongPress -> "长按 ${pt(at)}"
    is GuiAction.DoubleTap -> "双击 ${pt(at)}"
    is GuiAction.Swipe -> "滑动 ${pt(from)} → ${pt(to)}"
    is GuiAction.Type -> "输入「${text.take(40)}」"
    GuiAction.Back -> "返回"
    GuiAction.Home -> "回到桌面"
    GuiAction.Enter -> "回车"
    is GuiAction.Launch -> "打开 $app"
    is GuiAction.Wait -> "等待 ${ms / 1000.0} 秒"
    is GuiAction.Finish -> if (success) "完成：$message" else "结束（未完成）：$message"
    is GuiAction.TakeOver -> "请你接管：$message"
    is GuiAction.Ask -> "询问：$question"
}

private fun pt(p: NPoint) = "(${(p.x * 1000).toInt()},${(p.y * 1000).toInt()})"

/** How a model writes its actions. */
enum class GuiFormat {
    /** Open-AutoGLM: `<think>…</think><answer>do(action="Tap", element=[x,y])</answer>`. */
    AUTOGLM,
    /** Qwen3-VL / MAI-UI / GUI-Owl: `<tool_call>{"name":"mobile_use","arguments":{…}}</tool_call>`. */
    MOBILE_USE,
}

/**
 * A GUI model family: output format and coordinate grid. Sources: Open-AutoGLM handler.py divides
 * by 1000; MAI-UI agents use SCALE_FACTOR = 999; Qwen3-VL uses a relative 1000×1000 grid.
 */
enum class GuiProfile(val label: String, val format: GuiFormat, val scale: Float) {
    AUTOGLM("AutoGLM-Phone", GuiFormat.AUTOGLM, 1000f),
    MAI_UI("MAI-UI", GuiFormat.MOBILE_USE, 999f),
    QWEN_VL("通用", GuiFormat.MOBILE_USE, 1000f);

    companion object {
        /** By model file name; anything unknown is treated as a generic Qwen3-VL model. */
        fun forModel(name: String?): GuiProfile {
            val n = name.orEmpty().lowercase()
            return when {
                "autoglm" in n -> AUTOGLM
                "mai-ui" in n || "mai_ui" in n || "maiui" in n -> MAI_UI
                else -> QWEN_VL
            }
        }
    }
}

/** Grid coordinates → pixels, clamped inside the screen. */
object CoordMap {
    fun toPixels(p: NPoint, width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val x = (p.x * width).toInt().coerceIn(0, width - 1)
        val y = (p.y * height).toInt().coerceIn(0, height - 1)
        return x to y
    }

    /** Model grid value → fraction; values a little outside the grid are clamped, far outside are errors. */
    fun fromGrid(x: Double, y: Double, scale: Float): NPoint {
        val slack = scale * 0.05
        if (x < -slack || y < -slack || x > scale + slack || y > scale + slack) throw IllegalArgumentException("坐标超出范围：[$x,$y]（0–${scale.toInt()}）")
        return NPoint((x / scale).toFloat().coerceIn(0f, 1f), (y / scale).toFloat().coerceIn(0f, 1f))
    }

    /** A swipe in [dir] across [at] (default: screen centre), covering about 40 % of the screen. */
    fun swipe(dir: SwipeDir, at: NPoint? = null): GuiAction.Swipe {
        val c = at ?: NPoint(0.5f, 0.5f)
        val d = 0.2f
        // "swipe up" = finger moves up = content scrolls down (MAI-UI / Qwen convention).
        val (dx, dy) = when (dir) { SwipeDir.UP -> 0f to -d; SwipeDir.DOWN -> 0f to d; SwipeDir.LEFT -> -d to 0f; SwipeDir.RIGHT -> d to 0f }
        fun cl(v: Float) = v.coerceIn(0.02f, 0.98f)
        return GuiAction.Swipe(NPoint(cl(c.x - dx), cl(c.y - dy)), NPoint(cl(c.x + dx), cl(c.y + dy)))
    }
}

/** One parsed model turn. Exactly one of [action] / [error] is set. */
data class GuiParse(val thought: String, val action: GuiAction?, val error: String? = null)

/** Parses the raw reply of a GUI model. Tolerant: either format is accepted whatever the profile. */
object GuiActionParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val THINK = Regex("<(think|thinking)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val ANSWER = Regex("<answer>(.*?)(</answer>|$)", RegexOption.DOT_MATCHES_ALL)
    private val TOOL_CALL = Regex("<tool_call>(.*?)(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)
    private val CALL = Regex("\\b(do|finish)\\s*\\(", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String, profile: GuiProfile): GuiParse {
        val thought = THINK.find(raw)?.groupValues?.get(2)?.trim()
            ?: raw.substringBefore("<answer>").substringBefore("<tool_call>").substringBefore("do(").substringBefore("finish(").trim().take(400)
        return try {
            val action = parseAction(raw, profile) ?: return GuiParse(thought, null, "没有找到动作（需要 do(...) / finish(...) 或 <tool_call>）")
            GuiParse(thought, action)
        } catch (e: IllegalArgumentException) {
            GuiParse(thought, null, e.message ?: "动作格式错误")
        }
    }

    private fun parseAction(raw: String, profile: GuiProfile): GuiAction? {
        // 2.2.14: actions only outside reasoning (unclosed <think> / lone </think> too).
        val stripped = com.iris.localai.agent.ToolCallParser.outsideThink(THINK.replace(raw, ""))
        TOOL_CALL.find(stripped)?.let { return fromMobileUse(it.groupValues[1].trim(), profile) }
        val answer = ANSWER.find(stripped)?.groupValues?.get(1)?.trim() ?: stripped
        CALL.find(answer)?.let { m -> return fromAutoGlm(answer.substring(m.range.first), profile) }
        // A bare JSON object (some quantised models drop the tags).
        val brace = stripped.indexOf('{')
        if (brace >= 0 && "\"action\"" in stripped) return fromMobileUse(stripped.substring(brace, stripped.lastIndexOf('}') + 1), profile)
        return null
    }

    // ---------------------------------------------------------------- Open-AutoGLM

    internal fun fromAutoGlm(call: String, profile: GuiProfile): GuiAction {
        val name = call.substringBefore('(').trim()
        val args = AutoGlmArgs.parse(call.substringAfter('(').let { closeParen(it) })
        if (name == "finish") return GuiAction.Finish(args.str("message") ?: "")
        val action = args.str("action") ?: throw IllegalArgumentException("do(...) 缺少 action")
        fun point(key: String): NPoint {
            val v = args.nums(key) ?: throw IllegalArgumentException("$action 缺少 $key=[x,y]")
            if (v.size < 2) throw IllegalArgumentException("$key 需要 [x,y]")
            return CoordMap.fromGrid(v[0], v[1], profile.scale)
        }
        return when (action.lowercase().replace(' ', '_')) {
            "tap", "click" -> GuiAction.Tap(point("element"), args.str("message"))
            "long_press" -> GuiAction.LongPress(point("element"))
            "double_tap" -> GuiAction.DoubleTap(point("element"))
            "swipe" -> GuiAction.Swipe(point("start"), point("end"))
            "type", "type_name" -> GuiAction.Type(args.str("text") ?: throw IllegalArgumentException("Type 缺少 text"))
            "back" -> GuiAction.Back
            "home" -> GuiAction.Home
            "enter" -> GuiAction.Enter
            "launch", "open", "open_app" -> GuiAction.Launch(args.str("app") ?: args.str("name") ?: throw IllegalArgumentException("Launch 缺少 app"))
            "wait" -> GuiAction.Wait(seconds(args.str("duration")))
            "take_over" -> GuiAction.TakeOver(args.str("message") ?: "需要你来操作")
            "interact" -> GuiAction.Ask(args.str("message") ?: "有多个选项，请选择")
            else -> throw IllegalArgumentException("不支持的动作：$action")
        }
    }

    /** Text up to the call's closing parenthesis (quotes respected). */
    private fun closeParen(s: String): String {
        var depth = 0; var inStr = false; var esc = false
        for ((i, c) in s.withIndex()) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '(', '[' -> depth++; ']' -> depth--; ')' -> if (depth == 0) return s.substring(0, i) else depth-- }
        }
        return s
    }

    private fun seconds(s: String?): Long {
        val n = s?.let { Regex("[0-9]+(\\.[0-9]+)?").find(it)?.value?.toDoubleOrNull() } ?: 1.0
        return (n * 1000).toLong().coerceIn(200L, GuiAction.MAX_WAIT_MS)
    }

    // ---------------------------------------------------------------- mobile_use (Qwen3-VL / MAI-UI)

    internal fun fromMobileUse(text: String, profile: GuiProfile): GuiAction {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw IllegalArgumentException("tool_call 不是合法 JSON") }
        val a = (obj["arguments"] as? JsonObject) ?: obj
        val action = a.s("action") ?: throw IllegalArgumentException("mobile_use 缺少 action")
        fun point(vararg keys: String): NPoint? {
            val arr = keys.firstNotNullOfOrNull { a[it] as? JsonArray } ?: return null
            val v = arr.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            return when (v.size) {
                2 -> CoordMap.fromGrid(v[0], v[1], profile.scale)
                4 -> CoordMap.fromGrid((v[0] + v[2]) / 2, (v[1] + v[3]) / 2, profile.scale) // MAI-UI may answer a box
                else -> throw IllegalArgumentException("坐标需要 [x,y]")
            }
        }
        fun need(vararg keys: String) = point(*keys) ?: throw IllegalArgumentException("$action 缺少 ${keys.first()}")
        return when (action.lowercase()) {
            "click", "tap" -> GuiAction.Tap(need("coordinate"))
            "long_press" -> GuiAction.LongPress(need("coordinate"))
            "double_click", "double_tap" -> GuiAction.DoubleTap(need("coordinate"))
            "swipe", "drag" -> {
                val to = point("coordinate2", "end_coordinate")
                val from = point("coordinate", "start_coordinate")
                val dir = a.s("direction")
                when {
                    from != null && to != null -> GuiAction.Swipe(from, to)
                    dir != null -> CoordMap.swipe(SwipeDir.entries.firstOrNull { it.name.equals(dir.trim(), true) }
                        ?: throw IllegalArgumentException("未知滑动方向：$dir"), from)
                    else -> throw IllegalArgumentException("swipe 需要 coordinate2 或 direction")
                }
            }
            "type" -> GuiAction.Type(a.s("text") ?: throw IllegalArgumentException("type 缺少 text"))
            "system_button", "key" -> when ((a.s("button") ?: a.s("text") ?: "").lowercase()) {
                "back" -> GuiAction.Back
                "home" -> GuiAction.Home
                "enter" -> GuiAction.Enter
                else -> throw IllegalArgumentException("不支持的按键：${a.s("button") ?: a.s("text")}")
            }
            "open", "launch", "open_app", "awake" -> GuiAction.Launch(a.s("text") ?: a.s("app") ?: a.s("name") ?: throw IllegalArgumentException("open 缺少 text"))
            "wait" -> GuiAction.Wait(((a["time"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).let { (it * 1000).toLong().coerceIn(200L, GuiAction.MAX_WAIT_MS) })
            "terminate" -> GuiAction.Finish(a.s("text") ?: "", success = a.s("status")?.lowercase() != "failure" && a.s("status")?.lowercase() != "fail")
            "answer" -> GuiAction.Finish(a.s("text") ?: "")
            "ask_user" -> GuiAction.Ask(a.s("text") ?: "请选择")
            else -> throw IllegalArgumentException("不支持的动作：$action")
        }
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** `action="Tap", element=[1,2], message="x"` → map. Strings keep escapes `\"` `\\` `\n`. */
internal class AutoGlmArgs private constructor(private val values: Map<String, Any>) {
    fun str(k: String): String? = values[k] as? String
    @Suppress("UNCHECKED_CAST")
    fun nums(k: String): List<Double>? = values[k] as? List<Double>

    companion object {
        fun parse(s: String): AutoGlmArgs {
            val out = HashMap<String, Any>()
            var i = 0
            fun skipWs() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
            while (true) {
                skipWs()
                if (i >= s.length) break
                val keyStart = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val key = s.substring(keyStart, i)
                while (i < s.length && s[i].isWhitespace()) i++
                if (key.isEmpty() || i >= s.length || s[i] != '=') throw IllegalArgumentException("参数格式错误：${s.take(60)}")
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                when (s[i]) {
                    '"', '\'' -> {
                        val q = s[i++]; val sb = StringBuilder()
                        while (i < s.length && s[i] != q) {
                            if (s[i] == '\\' && i + 1 < s.length) { i++; sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else sb.append(s[i])
                            i++
                        }
                        i++
                        out[key] = sb.toString()
                    }
                    '[' -> {
                        val end = s.indexOf(']', i).takeIf { it > 0 } ?: throw IllegalArgumentException("缺少 ]")
                        out[key] = s.substring(i + 1, end).split(',').mapNotNull { it.trim().toDoubleOrNull() }
                        i = end + 1
                    }
                    else -> {
                        val st = i
                        while (i < s.length && s[i] != ',') i++
                        out[key] = s.substring(st, i).trim()
                    }
                }
            }
            return AutoGlmArgs(out)
        }
    }
}

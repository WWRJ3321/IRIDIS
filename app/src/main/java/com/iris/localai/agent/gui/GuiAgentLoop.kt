package com.iris.localai.agent.gui

import com.iris.localai.engine.PromptMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** What the agent sees before each step. */
data class GuiObservation(
    /** Absolute path of a screenshot file (null = could not capture; the model then works from the tree). */
    val screenshot: String?,
    val width: Int,
    val height: Int,
    val tree: UiNode?,
    val foregroundPkg: String? = null,
)

data class ExecResult(val ok: Boolean, val message: String = "")

/** Perception + execution on one screen (形态 A: the phone's own display; 形态 B: a virtual display). */
interface GuiDevice {
    suspend fun observe(): GuiObservation
    suspend fun execute(action: GuiAction, obs: GuiObservation): ExecResult
}

/** The vision model: one stand-alone completion (messages may carry the screenshot). */
fun interface GuiModel {
    suspend fun complete(messages: List<PromptMessage>): String
}

interface GuiCallbacks {
    /** A sensitive action: true = go ahead. */
    suspend fun confirm(action: GuiAction, reason: String): Boolean
    /** The user must act (login / captcha / blocked action); returns when they say done (false = stop). */
    suspend fun takeOver(message: String): Boolean
    /** The model asks a question; null = no answer (stop). */
    suspend fun ask(question: String): String?
    fun onStep(step: GuiStep) {}
}

enum class GuiStepStatus { OK, FAILED, DENIED, BLOCKED, FORMAT_ERROR }

data class GuiStep(val index: Int, val thought: String, val action: GuiAction?, val status: GuiStepStatus, val note: String = "")

enum class GuiStop { DONE, GAVE_UP, MAX_STEPS, STOPPED, ERROR }

data class GuiOutcome(val stop: GuiStop, val message: String, val steps: List<GuiStep>)

/** Shared stop switch: the floating 停止 bar and the in-app button flip it; the loop checks it every step. */
object GuiStopSignal {
    private val flag = AtomicBoolean(false)
    fun request() = flag.set(true)
    fun reset() = flag.set(false)
    val requested: Boolean get() = flag.get()
}

object GuiLimits {
    const val DEFAULT_MAX_STEPS = 30
    const val MAX_FORMAT_RETRIES = 2
    /** Same screen + same action this many times in a row → warn the model; twice that → stop. */
    const val REPEAT_WARN = 3
    const val HISTORY_STEPS = 8
    /** Time for the UI to settle after an action before the next screenshot. */
    const val SETTLE_MS = 800L
}

/**
 * The see → think → act loop. Only the newest screenshot is sent (small models, small context);
 * earlier steps go as one text line each. Stops on finish, the step cap, 停止, a refused action or
 * repeated format errors.
 */
class GuiAgentLoop(
    private val model: GuiModel,
    private val device: GuiDevice,
    private val callbacks: GuiCallbacks,
    private val profile: GuiProfile,
    private val maxSteps: Int = GuiLimits.DEFAULT_MAX_STEPS,
    private val settleMs: Long = GuiLimits.SETTLE_MS,
    private val stop: () -> Boolean = { GuiStopSignal.requested },
    private val includeTree: Boolean = true,
    private val today: String = "",
) {
    private val steps = ArrayList<GuiStep>()

    suspend fun run(task: String): GuiOutcome {
        val history = ArrayList<String>()
        var formatErrors = 0
        var lastKey: String? = null
        var repeats = 0
        var note: String? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (stop()) return end(GuiStop.STOPPED, "已停止")
                if (steps.count { it.status != GuiStepStatus.FORMAT_ERROR } >= maxSteps) return end(GuiStop.MAX_STEPS, "达到步数上限（$maxSteps 步）")
                val obs = device.observe()
                val elements = UiTreeSerializer.elements(obs.tree, obs.width, obs.height, profile.scale)
                val msgs = GuiPrompt.messages(profile, task, history, obs, elements, includeTree, note, today)
                note = null
                val raw = model.complete(msgs)
                if (stop()) return end(GuiStop.STOPPED, "已停止")
                val parsed = GuiActionParser.parse(raw, profile)
                val action = parsed.action
                if (action == null) {
                    formatErrors++
                    record(parsed.thought, null, GuiStepStatus.FORMAT_ERROR, parsed.error.orEmpty())
                    if (formatErrors > GuiLimits.MAX_FORMAT_RETRIES) return end(GuiStop.ERROR, "模型输出无法解析：${parsed.error}")
                    note = "Your last reply could not be read (${parsed.error}). Answer with exactly one action in the required format."
                    continue
                }
                formatErrors = 0
                when (action) {
                    is GuiAction.Finish -> {
                        record(parsed.thought, action, GuiStepStatus.OK)
                        return end(if (action.success) GuiStop.DONE else GuiStop.GAVE_UP, action.message)
                    }
                    is GuiAction.TakeOver -> {
                        record(parsed.thought, action, GuiStepStatus.OK)
                        if (!callbacks.takeOver(action.message)) return end(GuiStop.STOPPED, "你没有接管，已停止")
                        history += line(action, "user finished")
                        continue
                    }
                    is GuiAction.Ask -> {
                        val answer = callbacks.ask(action.question)
                        record(parsed.thought, action, GuiStepStatus.OK, answer.orEmpty())
                        if (answer == null) return end(GuiStop.STOPPED, "没有收到回答，已停止")
                        history += line(action, "user answered: $answer")
                        continue
                    }
                    else -> Unit
                }
                when (val v = GuiSafety.review(action, elements, obs.width, obs.height, obs.foregroundPkg)) {
                    is GuiVerdict.Block -> {
                        record(parsed.thought, action, GuiStepStatus.BLOCKED, v.reason)
                        if (!callbacks.takeOver(v.reason)) return end(GuiStop.STOPPED, v.reason)
                        history += line(action, "not done by agent (${v.reason}); the user handled it")
                        continue
                    }
                    is GuiVerdict.Confirm -> if (!callbacks.confirm(action, v.reason)) {
                        record(parsed.thought, action, GuiStepStatus.DENIED, v.reason)
                        return end(GuiStop.STOPPED, "你拒绝了：${v.reason}")
                    }
                    GuiVerdict.Allow -> Unit
                }
                if (stop()) return end(GuiStop.STOPPED, "已停止")
                val r = device.execute(action, obs)
                record(parsed.thought, action, if (r.ok) GuiStepStatus.OK else GuiStepStatus.FAILED, r.message)
                history += line(action, if (r.ok) "ok" else "failed: ${r.message}")
                val key = "${UiTreeSerializer.signature(elements)}:$action"
                repeats = if (key == lastKey) repeats + 1 else 1
                lastKey = key
                if (repeats >= GuiLimits.REPEAT_WARN * 2) return end(GuiStop.ERROR, "同一操作重复 $repeats 次没有效果，已停止")
                if (repeats >= GuiLimits.REPEAT_WARN) note = "The same action on the same screen was repeated $repeats times without effect. Try something different."
                if (action !is GuiAction.Wait && settleMs > 0) delay(settleMs)
            }
        } catch (e: CancellationException) {
            end(GuiStop.STOPPED, "已停止")
            throw e
        }
    }

    private fun line(a: GuiAction, result: String) = "${a.label()} → $result"

    private fun record(thought: String, action: GuiAction?, status: GuiStepStatus, note: String = "") {
        val s = GuiStep(steps.size + 1, thought, action, status, note)
        steps += s
        callbacks.onStep(s)
    }

    private fun end(stop: GuiStop, message: String) = GuiOutcome(stop, message, steps.toList())
}

/** Prompts per model family. Kept short: every token is prefilled on the phone each step. */
object GuiPrompt {
    fun system(profile: GuiProfile, today: String = ""): String = when (profile.format) {
        GuiFormat.AUTOGLM -> buildString {
            if (today.isNotEmpty()) append("今天的日期是: ").append(today).append('\n')
            append(
                """你是一个手机操作智能体，根据任务、操作历史和当前截图，每次只执行一个操作。
严格按格式输出：
<think>{简短推理}</think>
<answer>{操作}</answer>
操作（坐标从左上角 (0,0) 到右下角 (999,999)）：
- do(action="Launch", app="xxx")
- do(action="Tap", element=[x,y])
- do(action="Tap", element=[x,y], message="重要操作")  涉及支付、隐私等敏感按钮时使用
- do(action="Type", text="xxx")  先点击输入框
- do(action="Swipe", start=[x1,y1], end=[x2,y2])
- do(action="Long Press", element=[x,y])
- do(action="Double Tap", element=[x,y])
- do(action="Back")
- do(action="Home")
- do(action="Wait", duration="x seconds")
- do(action="Take_over", message="xxx")  登录、验证码等需要用户操作时
- do(action="Interact")  有多个符合条件的选项、需要用户选择时
- finish(message="xxx")  任务完成
规则：先确认当前应用是否正确，不是就 Launch；进入无关页面先 Back；页面没加载最多 Wait 三次；操作前检查上一步是否生效；结束前确认任务完整完成。"""
            )
        }
        GuiFormat.MOBILE_USE -> {
            val max = profile.scale.toInt()
            """You are a GUI agent operating an Android phone. Each turn you get the task, the action history and the current screenshot; perform exactly one next action.
Output:
<thinking>short reasoning</thinking>
<tool_call>
{"name": "mobile_use", "arguments": {...}}
</tool_call>
Coordinates are relative on a ${max}x$max grid (0,0 top-left).
Actions:
{"action": "click", "coordinate": [x, y]}
{"action": "long_press", "coordinate": [x, y]}
{"action": "swipe", "coordinate": [x1, y1], "coordinate2": [x2, y2]}
{"action": "swipe", "direction": "up|down|left|right"}
{"action": "type", "text": "..."}  (click the input first)
{"action": "system_button", "button": "back|home|enter"}
{"action": "open", "text": "app name"}
{"action": "wait", "time": seconds}
{"action": "ask_user", "text": "question"}
{"action": "terminate", "status": "success|failure", "text": "result for the user"}
Rules: open the right app first; check the last action worked before the next; never pay, send or delete unless the task says so; finish with terminate."""
        }
    }

    fun messages(
        profile: GuiProfile, task: String, history: List<String>, obs: GuiObservation, elements: List<UiElement>,
        includeTree: Boolean, note: String?, today: String = "",
    ): List<PromptMessage> {
        val user = buildString {
            append(if (profile.format == GuiFormat.AUTOGLM) "任务：" else "Task: ").append(task.trim()).append('\n')
            val recent = history.takeLast(GuiLimits.HISTORY_STEPS)
            if (recent.isNotEmpty()) {
                append(if (profile.format == GuiFormat.AUTOGLM) "操作历史：\n" else "History:\n")
                val skipped = history.size - recent.size
                recent.forEachIndexed { i, h -> append(skipped + i + 1).append(". ").append(h).append('\n') }
            }
            obs.foregroundPkg?.let { append(if (profile.format == GuiFormat.AUTOGLM) "当前应用：" else "Foreground app: ").append(it).append('\n') }
            if (includeTree && elements.isNotEmpty()) {
                append(if (profile.format == GuiFormat.AUTOGLM) "界面元素（坐标同截图网格）：\n" else "UI elements (same grid as the screenshot):\n")
                append(UiTreeSerializer.render(elements)).append('\n')
            }
            if (obs.screenshot == null) append(if (profile.format == GuiFormat.AUTOGLM) "（本步没有截图，请根据界面元素操作）\n" else "(No screenshot this step: act from the UI elements.)\n")
            note?.let { append(it).append('\n') }
        }.trimEnd()
        return listOf(
            PromptMessage(PromptMessage.SYSTEM, system(profile, today)),
            PromptMessage(PromptMessage.USER, user, listOfNotNull(obs.screenshot)),
        )
    }
}

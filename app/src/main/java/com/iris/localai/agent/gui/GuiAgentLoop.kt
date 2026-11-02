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
    /** 2.2.9: after an action, wait until the screen is stable; returns its fingerprint (null = unknown). */
    /** 2.2.9: the screen's fingerprint right now (null = use the observation's tree). */
    suspend fun snapshot(): ScreenState? = null
    suspend fun waitStable(fallbackMs: Long): ScreenState? { if (fallbackMs > 0) delay(fallbackMs); return null }
    /** 2.3.0: the node tree only (no screenshot), for scroll-until-found; null = use [observe]. */
    suspend fun tree(): UiNode? = observe().tree
}

/** 2.2.3: one text line found by OCR, in screen pixels (same space as [UiNode.bounds]). */
data class OcrLine(val text: String, val bounds: PxRect)

/**
 * 2.2.3: on-device OCR of a screenshot (full flavour: ML Kit Chinese, bundled). Used only when the
 * accessibility tree has few text elements (games, WebView, Flutter); the lines are added to the
 * UI element list as a reference, never used to snap the model's coordinates.
 */
fun interface GuiOcr {
    suspend fun recognize(screenshot: String, screenW: Int, screenH: Int): List<OcrLine>
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
    /** 2.1.9: what step [index] is doing now (the 停止 bar shows it, so a slow model never looks stuck). */
    fun onPhase(index: Int, phase: GuiPhase) {}
}

enum class GuiPhase(val label: String) { LOOK("看屏幕"), THINK("思考") }

enum class GuiStepStatus { OK, FAILED, DENIED, BLOCKED, FORMAT_ERROR }

data class GuiStep(val index: Int, val thought: String, val action: GuiAction?, val status: GuiStepStatus, val note: String = "")

enum class GuiStop { DONE, GAVE_UP, MAX_STEPS, STOPPED, ERROR }

data class GuiOutcome(val stop: GuiStop, val message: String, val steps: List<GuiStep>)

/** 2.2.2: where the GUI loop reads the user's 补充; the chat sets [source] while its agent runs. */
object GuiNotes {
    @Volatile var source: (() -> String?)? = null
    fun take(): String? = runCatching { source?.invoke() }.getOrNull()
}

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
    /** Fixed settle time, used only by devices that cannot detect a stable screen ([GuiDevice.waitStable]). */
    const val SETTLE_MS = 800L
    /** 2.3.0: scroll-until-found gives up after this many scrolls. */
    const val MAX_FIND_SCROLLS = 8
    /** 2.3.0: system ANR / crash dialogs cleared by the loop itself at most this often per run. */
    const val MAX_AUTO_DISMISS = 2
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
    /** 2.2.2: 补充 the user typed during the run (read before each step). */
    private val userNotes: () -> String? = { GuiNotes.take() },
    /** 2.2.3: OCR fallback when the tree has little text (null = none, e.g. the lite flavour). */
    private val ocr: GuiOcr? = null,
) {
    private val steps = ArrayList<GuiStep>()

    suspend fun run(task0: String): GuiOutcome {
        var task = task0
        val history = ArrayList<String>()
        var formatErrors = 0
        val guard = LoopGuard(warnAt = GuiLimits.REPEAT_WARN, stopAt = GuiLimits.REPEAT_WARN * 2)
        var note: String? = null
        // 2.3.0 (Mika ideas): stuck advice, step memory, ANR/crash dialogs, done check.
        val stuck = StuckTracker()
        val memory = StepMemory()
        var autoDismiss = 0
        var finishChecks = 0
        var lastOk: Boolean? = null
        var lastChanged: Boolean? = null
        var lastLabel = ""
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (stop()) return end(GuiStop.STOPPED, "已停止")
                if (steps.count { it.status != GuiStepStatus.FORMAT_ERROR } >= maxSteps) return end(GuiStop.MAX_STEPS, "达到步数上限（$maxSteps 步）")
                val stepNo = steps.size + 1
                userNotes()?.takeIf { it.isNotBlank() }?.let { add ->
                    task = task.trimEnd() + "\n用户补充：" + add.trim()
                    note = listOfNotNull(note, "The user just added: ${add.trim()} — follow it from this step on.").joinToString("\n")
                }
                callbacks.onPhase(stepNo, GuiPhase.LOOK)
                val obs = device.observe()
                val treeElements = UiTreeSerializer.elements(obs.tree, obs.width, obs.height, profile.scale)
                val shotPath = obs.screenshot
                val elements = if (ocr != null && includeTree && shotPath != null && UiTreeSerializer.needsOcr(treeElements)) {
                    val lines = try { ocr.recognize(shotPath, obs.width, obs.height) } catch (e: CancellationException) { throw e } catch (_: Throwable) { emptyList() }
                    UiTreeSerializer.withOcr(treeElements, lines, obs.width, obs.height, profile.scale)
                } else treeElements
                val check = ScreenDiagnosis.classify(treeElements, obs.foregroundPkg)
                val sysButton = check.button
                if (sysButton != null && autoDismiss < GuiLimits.MAX_AUTO_DISMISS &&
                    (check.kind == ScreenKind.APP_NOT_RESPONDING || check.kind == ScreenKind.APP_CRASHED)) {
                    // A system dialog, not part of the task: press its button without a model step.
                    autoDismiss++
                    val (gx, gy) = UiTreeSerializer.gridCentre(sysButton, obs.width, obs.height, profile.scale)
                    val press = GuiAction.Tap(CoordMap.fromGrid(gx.toDouble(), gy.toDouble(), profile.scale))
                    val r = device.execute(press, obs)
                    device.waitStable(settleMs)
                    val what = if (check.kind == ScreenKind.APP_NOT_RESPONDING) "应用无响应" else "应用已崩溃"
                    record("", press, if (r.ok) GuiStepStatus.OK else GuiStepStatus.FAILED, "系统提示$what，已自动点「${sysButton.label}」")
                    history += "（系统提示$what，已自动点「${sysButton.label}」）"
                    if (check.kind == ScreenKind.APP_CRASHED) note = listOfNotNull(note, "应用刚才崩溃了，需要时用 Launch 重新打开。").joinToString("\n")
                    continue
                }
                ScreenDiagnosis.hint(check)?.let { h -> note = listOfNotNull(note, h).joinToString("\n") }
                val msgs = GuiPrompt.messages(profile, task, history, obs, elements, includeTree, note, today,
                    earlier = memory.summary(history.size - GuiLimits.HISTORY_STEPS))
                note = null
                callbacks.onPhase(stepNo, GuiPhase.THINK)
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
                        if (action.success && FinishCheck.needsCheck(lastOk, lastChanged, finishChecks)) {
                            finishChecks++
                            record(parsed.thought, action, GuiStepStatus.DENIED, "先确认是否真的完成")
                            note = FinishCheck.note(lastLabel)
                            lastOk = null; lastChanged = null
                            continue
                        }
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
                when (val v = GuiSafety.review(action, elements, obs.width, obs.height, obs.foregroundPkg, parsed.thought)) {
                    is GuiVerdict.Block -> {
                        record(parsed.thought, action, GuiStepStatus.BLOCKED, v.reason)
                        if (!callbacks.takeOver(v.reason)) return end(GuiStop.STOPPED, v.reason)
                        history += line(action, "not done by agent (${v.reason}); the user handled it")
                        continue
                    }
                    is GuiVerdict.Confirm -> if (!callbacks.confirm(action, v.reason)) {
                        // 2.2.9: tell the model and let it decide (another way, ask, or finish).
                        record(parsed.thought, action, GuiStepStatus.DENIED, v.reason)
                        history += line(action, CANCELLED)
                        note = "$CANCELLED（${v.reason}）。不要重复这个操作；换别的做法、提问，或结束任务。"
                        continue
                    }
                    GuiVerdict.Allow -> Unit
                }
                if (stop()) return end(GuiStop.STOPPED, "已停止")
                val before = device.snapshot() ?: ScreenFingerprint.of(obs.tree)
                if (action is GuiAction.Scroll && !action.until.isNullOrBlank()) {
                    val (ok, msg, moved) = scrollUntil(action, obs, elements)
                    record(parsed.thought, action, if (ok) GuiStepStatus.OK else GuiStepStatus.FAILED, msg)
                    history += line(action, msg)
                    memory.record(action, obs.foregroundPkg, ok, moved)
                    lastOk = ok; lastChanged = moved; lastLabel = action.label()
                    when (guard.record(action, moved || ok)) {
                        LoopGuard.Verdict.STOP -> return end(GuiStop.ERROR, "同一操作重复 ${guard.count} 次没有效果，已停止")
                        LoopGuard.Verdict.WARN -> note = LoopGuard.hint(guard.count)
                        LoopGuard.Verdict.OK -> Unit
                    }
                    continue
                }
                val r = device.execute(action, obs)
                // 2.2.9: wait for a stable screen (not a fixed delay), then report whether it changed.
                val after = device.waitStable(if (action is GuiAction.Wait) 0 else settleMs)
                val changed = ChangeReport.changed(before, after)
                val change = ChangeReport.text(before, after)
                val res = (if (r.ok) "ok" else "failed: ${r.message}") + (change?.let { "; $it" } ?: "")
                record(parsed.thought, action, if (r.ok) GuiStepStatus.OK else GuiStepStatus.FAILED, listOfNotNull(r.message.takeIf { it.isNotEmpty() }, change).joinToString("；"))
                history += line(action, res)
                memory.record(action, after?.pkg, r.ok, changed)
                lastOk = r.ok; lastChanged = if (action is GuiAction.Wait) null else changed; lastLabel = action.label()
                val verdict = guard.record(action, changed)
                when (verdict) {
                    LoopGuard.Verdict.STOP -> return end(GuiStop.ERROR, "同一操作重复 ${guard.count} 次没有效果，已停止")
                    LoopGuard.Verdict.WARN -> note = LoopGuard.hint(guard.count)
                    LoopGuard.Verdict.OK -> Unit
                }
                val extra = listOfNotNull(
                    stuck.record(action, after?.pkg ?: obs.foregroundPkg, verdict == LoopGuard.Verdict.WARN, changed),
                    AppJump.note(action, obs.foregroundPkg, after?.pkg),
                    if (action is GuiAction.Scroll && changed == false) "滚动没有让画面变化：可能已经到头了。" else null,
                )
                if (extra.isNotEmpty()) note = (listOfNotNull(note) + extra).joinToString("\n")
            }
        } catch (e: CancellationException) {
            end(GuiStop.STOPPED, "已停止")
            throw e
        }
    }

    /**
     * 2.3.0 scroll-until-found: scrolls [a] without a model step per scroll until an element whose
     * text contains [GuiAction.Scroll.until] is on screen, the list stops moving, or
     * [GuiLimits.MAX_FIND_SCROLLS]. Reports where the text is (reference only; the model taps).
     * Returns (found, message for the model, whether the screen moved).
     */
    private suspend fun scrollUntil(a: GuiAction.Scroll, obs: GuiObservation, current: List<UiElement>): Triple<Boolean, String, Boolean> {
        val text = a.until!!.trim()
        fun at(e: UiElement): String { val (x, y) = UiTreeSerializer.gridCentre(e, obs.width, obs.height, profile.scale); return "($x,$y)" }
        UiTreeSerializer.find(current, text)?.let { return Triple(true, "「$text」已在屏幕上 ${at(it)}，不用滚动", false) }
        val step = a.copy(until = null)
        var moved = false
        var prev = device.snapshot() ?: ScreenFingerprint.of(obs.tree)
        for (i in 1..GuiLimits.MAX_FIND_SCROLLS) {
            currentCoroutineContext().ensureActive()
            if (stop()) break
            val r = device.execute(step, obs)
            if (!r.ok) return Triple(false, "滚动失败：${r.message}", moved)
            val after = device.waitStable(settleMs)
            val changed = ChangeReport.changed(prev, after)
            if (changed == true) moved = true
            val tree = device.tree()
            val els = UiTreeSerializer.elements(tree, obs.width, obs.height, profile.scale)
            UiTreeSerializer.find(els, text)?.let { return Triple(true, "滚动 $i 次后找到「$text」${at(it)}", true) }
            if (changed == false) return Triple(false, "滚动 $i 次已到尽头，没找到「$text」（可能只在截图里，或要换个说法/入口）", moved)
            prev = after
        }
        return Triple(false, "滚动 ${GuiLimits.MAX_FIND_SCROLLS} 次没找到「$text」", moved)
    }

    companion object { const val CANCELLED = "用户取消了这个操作" }

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
- do(action="Launch", app="xxx")  按应用名直接打开应用（不用去桌面找图标）
- do(action="Tap", element=[x,y])
- do(action="Tap", element=[x,y], message="重要操作")  涉及支付、隐私等敏感按钮时使用
- do(action="Type", text="xxx")  先点击输入框
- do(action="Swipe", start=[x1,y1], end=[x2,y2])
- do(action="Scroll", direction="down")  滚动列表看下面的内容（up/down/left/right，可加 element=[x,y] 指定列表）
- do(action="Scroll", direction="down", until="xxx")  一直滚到出现文字 xxx 或到底，返回它的位置
- do(action="Long Press", element=[x,y])  可加 duration="2 seconds"
- do(action="Double Tap", element=[x,y])
- do(action="Back")
- do(action="Home")
- do(action="Wait", duration="x seconds")
- do(action="Take_over", message="xxx")  登录、验证码等需要用户操作时
- do(action="Interact")  有多个符合条件的选项、需要用户选择时
- finish(message="xxx")  任务完成
规则：先确认当前应用是否正确，不是就直接 Launch；进入无关页面先 Back；页面没加载最多 Wait 三次；操作前检查上一步是否生效；找列表里的东西用 Scroll until；卡住时 Back 或重新 Launch；结束前看截图确认任务完整完成。"""
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
{"action": "long_press", "coordinate": [x, y], "time": seconds}  (time optional)
{"action": "swipe", "coordinate": [x1, y1], "coordinate2": [x2, y2]}
{"action": "swipe", "direction": "up|down|left|right"}
{"action": "scroll", "direction": "up|down|left|right"}  (content you want to see: down = further down; optional "coordinate" = which list)
{"action": "scroll", "direction": "down", "until": "text"}  (keeps scrolling until the text shows up or the end; tells you where it is)
{"action": "type", "text": "..."}  (click the input first)
{"action": "system_button", "button": "back|home|enter"}
{"action": "open", "text": "app name"}  (launches the app directly; no need to find its icon)
{"action": "wait", "time": seconds}
{"action": "ask_user", "text": "question"}
{"action": "terminate", "status": "success|failure", "text": "result for the user"}
Rules: decide from the screenshot; the UI element list (when given) is only a reference for where things are, tap the exact point you see. Open the right app first; check the last action worked before the next; to find something in a list use scroll with until; when stuck go back or reopen the app; never pay, send or delete unless the task says so; when the task is complete (check the screenshot shows it) or impossible, finish with terminate."""
        }
    }

    fun messages(
        profile: GuiProfile, task: String, history: List<String>, obs: GuiObservation, elements: List<UiElement>,
        includeTree: Boolean, note: String?, today: String = "",
        /** 2.3.0: one line summing up the steps that left the history window. */
        earlier: String? = null,
    ): List<PromptMessage> {
        val user = buildString {
            append(if (profile.format == GuiFormat.AUTOGLM) "任务：" else "Task: ").append(task.trim()).append('\n')
            val recent = history.takeLast(GuiLimits.HISTORY_STEPS)
            if (recent.isNotEmpty()) {
                append(if (profile.format == GuiFormat.AUTOGLM) "操作历史：\n" else "History:\n")
                val skipped = history.size - recent.size
                if (skipped > 0) earlier?.let { append(it).append('\n') }
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

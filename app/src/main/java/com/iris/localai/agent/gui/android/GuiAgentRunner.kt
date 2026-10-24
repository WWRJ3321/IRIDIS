package com.iris.localai.agent.gui.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.iris.localai.agent.GuiBlocker
import com.iris.localai.agent.GuiTaskBackend
import com.iris.localai.agent.GuiTaskResult
import com.iris.localai.agent.gui.GuiAction
import com.iris.localai.agent.gui.GuiTaskMapping
import com.iris.localai.agent.gui.GuiAgentLoop
import com.iris.localai.agent.gui.GuiCallbacks
import com.iris.localai.agent.gui.GuiModel
import com.iris.localai.agent.gui.GuiOutcome
import com.iris.localai.agent.gui.GuiProfile
import com.iris.localai.agent.gui.GuiStep
import com.iris.localai.agent.gui.GuiStepStatus
import com.iris.localai.agent.gui.GuiStop
import com.iris.localai.agent.gui.GuiStopSignal
import com.iris.localai.agent.gui.UiTreeSerializer
import com.iris.localai.agent.gui.label
import com.iris.localai.engine.EngineException
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.Requester
import com.iris.localai.engine.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The loaded IRIDIS model as the GUI model: greedy, short replies, the screenshot as the image. */
class EngineGuiModel(private val engine: EngineManager, private val maxNewTokens: Int = 512) : GuiModel {
    override suspend fun complete(messages: List<PromptMessage>): String {
        val params = engine.samplingFor(disableThinking = true).copy(temperature = 0f, topK = 1, topP = 1f, maxNewTokens = maxNewTokens)
        val sb = StringBuilder()
        engine.generate(messages, params, Requester.CHAT).collect { e ->
            when (e) {
                is GenEvent.Piece -> sb.append(e.text)
                is GenEvent.Done -> if (e.reason == StopReason.ERROR) throw EngineException(e.error ?: "生成失败")
            }
        }
        return sb.toString()
    }
}

/** What the settings page shows about the GUI agent. */
data class GuiRunState(val running: Boolean = false, val lines: List<String> = emptyList(), val result: String? = null)

/**
 * 2.2 第一阶段: runs one GUI task on the phone's own screen (形态 A) with the loaded model, the
 * floating 停止 bar and overlay confirmations. Entry points: 2.1.9 the chat (`screen` tool, [run]) and
 * 设置 › 智能体 › GUI 智能体 › 试运行 ([start]).
 */
class GuiAgentRunner(private val context: Context, private val engine: EngineManager, private val scope: CoroutineScope) : GuiTaskBackend {
    private val _state = MutableStateFlow(GuiRunState())
    val state: StateFlow<GuiRunState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var chatRun: Job? = null
    @Volatile private var chatInner: Job? = null

    /** 2.1.9: the guide the chat shows (无障碍 / 识图模型) after a `screen` call was blocked; null = none. */
    private val _setup = MutableStateFlow<GuiBlocker?>(null)
    val setup: StateFlow<GuiBlocker?> = _setup.asStateFlow()
    fun dismissSetup() { _setup.value = null }

    /** Why a run cannot start now, or null (no side effects). */
    fun blockerKind(): GuiBlocker? {
        if (job?.isActive == true || chatRun?.isActive == true) return GuiBlocker.BUSY
        if (!IrisAccessibilityService.connected) return GuiBlocker.A11Y_OFF
        val ready = engine.state.value as? EngineState.Ready ?: return GuiBlocker.NO_MODEL
        if (!ready.details.supportsVision) return GuiBlocker.NO_VISION
        return null
    }

    /** The settings page's text for [blockerKind]. */
    fun blockerText(): String? = when (blockerKind()) {
        null -> null
        GuiBlocker.A11Y_OFF -> "先在 系统设置 › 无障碍 里开启「IRIDIS GUI 智能体」"
        GuiBlocker.NO_MODEL -> "先加载一个能识图的模型"
        GuiBlocker.NO_VISION -> "当前模型不能识图：GUI 智能体需要视觉模型（如 Qwen3-VL、MAI-UI、AutoGLM-Phone）"
        GuiBlocker.BUSY -> "正在运行"
    }

    /** 2.1.9 `screen`: blocked → the chat shows the matching guide. */
    override fun blocker(): GuiBlocker? = blockerKind().also { if (it != null && it != GuiBlocker.BUSY) _setup.value = it }

    /** 2.1.9 `screen`: one run from the chat; IRIDIS goes to the background and comes back at the end. */
    override suspend fun run(task: String, progress: (String) -> Unit): GuiTaskResult {
        val me = kotlinx.coroutines.currentCoroutineContext()[Job]
        chatRun = me
        var handedOver = false
        try {
            val outcome = try {
                kotlinx.coroutines.coroutineScope {
                    val d = async { execute(task, com.iris.localai.agent.gui.GuiLimits.DEFAULT_MAX_STEPS, onLine = progress, onHandOver = { handedOver = true }) }
                    chatInner = d
                    d.await()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 悬浮条「停止」 cancels only the GUI run; the chat's 智能体 still reports it.
                if (me?.isActive == true) GuiOutcome(GuiStop.STOPPED, "已停止", emptyList()) else throw e
            }
            return GuiTaskMapping.result(outcome, handedOver)
        } finally {
            if (chatRun === me) { chatRun = null; chatInner = null }
            bringBack()
        }
    }

    /** Back to IRIDIS (its chat page is where the run started; singleTask keeps it). */
    private fun bringBack() {
        runCatching {
            val i = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            // The accessibility service may start activities from the background; the app context may not.
            (IrisAccessibilityService.instance ?: context).startActivity(i)
        }
    }

    fun start(task: String, maxSteps: Int = com.iris.localai.agent.gui.GuiLimits.DEFAULT_MAX_STEPS) {
        if (job?.isActive == true) return
        blockerText()?.let { _state.value = GuiRunState(result = it); return }
        job = scope.launch {
            val outcome = execute(task, maxSteps)
            _state.value = _state.value.copy(result = "${GuiTaskMapping.head(outcome.stop)}：${outcome.message}")
        }
    }

    private suspend fun execute(task: String, maxSteps: Int, onLine: (String) -> Unit = {}, onHandOver: () -> Unit = {}): GuiOutcome {
        val ready = engine.state.value as? EngineState.Ready ?: return GuiOutcome(GuiStop.ERROR, "没有加载模型", emptyList())
        val profile = GuiProfile.forModel(ready.model.fileName)
        GuiStopSignal.reset()
        _state.value = GuiRunState(running = true, lines = listOf("模型：${ready.model.fileName}（${profile.label} 格式）"))
        lineSink = onLine
        handOverSink = onHandOver
        val outcome: GuiOutcome = try {
            GuiOverlay.show("GUI 智能体 · 准备中") { stop() }
            IrisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(600)
            GuiAgentLoop(
                model = EngineGuiModel(engine), device = AccessibilityGuiDevice(context), callbacks = callbacks,
                profile = profile, maxSteps = maxSteps, today = LocalDate.now().toString(),
            ).run(task)
        } catch (e: kotlinx.coroutines.CancellationException) {
            GuiOverlay.hide()
            _state.value = _state.value.copy(running = false, result = "已停止")
            throw e
        } catch (e: Exception) {
            GuiOutcome(GuiStop.ERROR, e.message ?: e.javaClass.simpleName, emptyList())
        } finally {
            GuiOverlay.hide()
            lineSink = {}
            handOverSink = {}
        }
        _state.value = _state.value.copy(running = false)
        return outcome
    }
    @Volatile private var lineSink: (String) -> Unit = {}
    @Volatile private var handOverSink: () -> Unit = {}

    fun stop() {
        GuiStopSignal.request()
        job?.cancel()
        chatInner?.cancel()
    }

    private val callbacks = object : GuiCallbacks {
        override suspend fun confirm(action: GuiAction, reason: String) = GuiOverlay.ask("$reason\n要继续吗？", "允许", "拒绝")
        override suspend fun takeOver(message: String): Boolean =
            GuiOverlay.ask("需要你来操作：$message\n完成后点「我已完成」", "我已完成", "停止").also { if (!it) handOverSink() }
        // 第一阶段没有输入框：模型提问时停下，把问题带回 IRIDIS（聊天里由主智能体转述）。
        override suspend fun ask(question: String): String? { addLine("模型提问：$question"); handOverSink(); return null }
        override fun onStep(step: GuiStep) {
            val what = step.action?.label() ?: "格式错误"
            val mark = when (step.status) {
                GuiStepStatus.OK -> ""; GuiStepStatus.FAILED -> " ✗"; GuiStepStatus.DENIED -> "（已拒绝）"
                GuiStepStatus.BLOCKED -> "（需你操作）"; GuiStepStatus.FORMAT_ERROR -> "：${step.note}"
            }
            GuiOverlay.update("GUI 智能体 · 第 ${step.index} 步 · $what")
            addLine("${step.index}. $what$mark" + if (step.status == GuiStepStatus.FAILED && step.note.isNotEmpty()) "（${step.note}）" else "")
        }
    }

    private fun addLine(s: String) {
        _state.value = _state.value.copy(lines = (_state.value.lines + s).takeLast(40))
        lineSink(s)
    }

    /** 自检: one screenshot + node tree, no action. */
    suspend fun selfTest(): String {
        if (!IrisAccessibilityService.connected) return "无障碍服务未连接"
        return runCatching {
            val obs = AccessibilityGuiDevice(context).observe()
            val els = UiTreeSerializer.elements(obs.tree, obs.width, obs.height)
            "屏幕 ${obs.width}×${obs.height} · 截图" + (if (obs.screenshot != null) "成功" else "失败（安全窗口或系统限制）") +
                " · 界面元素 ${els.size} 个 · 前台 ${obs.foregroundPkg ?: "未知"}"
        }.getOrElse { "自检失败：${it.message}" }
    }
}

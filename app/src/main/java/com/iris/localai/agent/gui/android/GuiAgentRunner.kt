package com.iris.localai.agent.gui.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.iris.localai.agent.gui.GuiAction
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
 * floating 停止 bar and overlay confirmations. Entry point for now: 设置 › 智能体 › GUI 智能体 › 试运行.
 */
class GuiAgentRunner(private val context: Context, private val engine: EngineManager, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(GuiRunState())
    val state: StateFlow<GuiRunState> = _state.asStateFlow()
    private var job: Job? = null

    /** Why a run cannot start now, or null. */
    fun blocker(): String? {
        if (!IrisAccessibilityService.connected) return "先在 系统设置 › 无障碍 里开启「IRIDIS GUI 智能体」"
        val ready = engine.state.value as? EngineState.Ready ?: return "先加载一个能识图的模型"
        if (!ready.details.supportsVision) return "当前模型不能识图：GUI 智能体需要视觉模型（如 Qwen3-VL、MAI-UI、AutoGLM-Phone）"
        return null
    }

    fun start(task: String, maxSteps: Int = com.iris.localai.agent.gui.GuiLimits.DEFAULT_MAX_STEPS) {
        if (job?.isActive == true) return
        blocker()?.let { _state.value = GuiRunState(result = it); return }
        val ready = engine.state.value as EngineState.Ready
        val profile = GuiProfile.forModel(ready.model.fileName)
        GuiStopSignal.reset()
        _state.value = GuiRunState(running = true, lines = listOf("模型：${ready.model.fileName}（${profile.label} 格式）"))
        job = scope.launch {
            val outcome: GuiOutcome = try {
                GuiOverlay.show("GUI 智能体 · 准备中") { stop() }
                IrisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                delay(600)
                GuiAgentLoop(
                    model = EngineGuiModel(engine), device = AccessibilityGuiDevice(context), callbacks = callbacks,
                    profile = profile, maxSteps = maxSteps, today = LocalDate.now().toString(),
                ).run(task)
            } catch (e: kotlinx.coroutines.CancellationException) {
                GuiOutcome(GuiStop.STOPPED, "已停止", emptyList())
            } catch (e: Exception) {
                GuiOutcome(GuiStop.ERROR, e.message ?: e.javaClass.simpleName, emptyList())
            } finally {
                GuiOverlay.hide()
            }
            val head = when (outcome.stop) {
                GuiStop.DONE -> "完成"; GuiStop.GAVE_UP -> "未完成"; GuiStop.MAX_STEPS -> "达到步数上限"
                GuiStop.STOPPED -> "已停止"; GuiStop.ERROR -> "出错"
            }
            _state.value = _state.value.copy(running = false, result = "$head：${outcome.message}")
        }
    }

    fun stop() {
        GuiStopSignal.request()
        job?.cancel()
    }

    private val callbacks = object : GuiCallbacks {
        override suspend fun confirm(action: GuiAction, reason: String) = GuiOverlay.ask("$reason\n要继续吗？", "允许", "拒绝")
        override suspend fun takeOver(message: String) = GuiOverlay.ask("需要你来操作：$message\n完成后点「我已完成」", "我已完成", "停止")
        // 第一阶段没有输入框：模型提问时停下，把问题带回 IRIDIS。
        override suspend fun ask(question: String): String? { addLine("模型提问：$question"); return null }
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

    private fun addLine(s: String) { _state.value = _state.value.copy(lines = (_state.value.lines + s).takeLast(40)) }

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

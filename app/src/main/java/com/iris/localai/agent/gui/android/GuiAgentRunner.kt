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
class EngineGuiModel(private val engine: EngineManager, private val maxNewTokens: Int = 512, private val timeoutMs: Long = STEP_TIMEOUT_MS) : GuiModel {
    /** 2.1.9: characters of the reply so far (the 停止 bar shows it next to the seconds). */
    @Volatile var chars = 0
        private set

    override suspend fun complete(messages: List<PromptMessage>): String {
        val params = engine.samplingFor(disableThinking = true).copy(temperature = 0f, topK = 1, topP = 1f, maxNewTokens = maxNewTokens)
        val sb = StringBuilder()
        chars = 0
        val t0 = android.os.SystemClock.elapsedRealtime()
        var first = -1L
        val done = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            engine.generate(messages, params, Requester.CHAT).collect { e ->
                when (e) {
                    is GenEvent.Piece -> {
                        if (first < 0) first = android.os.SystemClock.elapsedRealtime() - t0
                        sb.append(e.text); chars = sb.length
                    }
                    is GenEvent.Done -> if (e.reason == StopReason.ERROR) throw EngineException(e.error ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7f7de8))
                }
            }
            true
        }
        android.util.Log.i(TAG, "step reply: first piece ${first}ms, total ${android.os.SystemClock.elapsedRealtime() - t0}ms, ${sb.length} chars, images=${messages.sumOf { it.images.size }}")
        if (done == null) throw EngineException(
            if (first < 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2b9315, timeoutMs / 1000)
            else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5efc4f, timeoutMs / 1000),
        )
        return sb.toString()
    }

    companion object {
        const val TAG = "IrisGui"
        /** One step (look + think) longer than this is treated as stuck. */
        const val STEP_TIMEOUT_MS = 180_000L
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
        if (!IrisAccessibilityService.connected) {
            // 2.1.9: 清后台 switched it off — put it back when allowed, give it a moment to connect.
            if (!IrisAccessibilityService.selfEnable(context)) return GuiBlocker.A11Y_OFF
            repeat(20) { if (!IrisAccessibilityService.connected) Thread.sleep(100) }
            if (!IrisAccessibilityService.connected) return GuiBlocker.A11Y_OFF
        }
        val ready = engine.state.value as? EngineState.Ready ?: return GuiBlocker.NO_MODEL
        if (!ready.details.supportsVision) return GuiBlocker.NO_VISION
        return null
    }

    /** The settings page's text for [blockerKind]. */
    fun blockerText(): String? = when (blockerKind()) {
        null -> null
        GuiBlocker.A11Y_OFF -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_13f110)
        GuiBlocker.NO_MODEL -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3f28a)
        GuiBlocker.NO_VISION -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b50199)
        GuiBlocker.BUSY -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c9e2c3)
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
                if (me?.isActive == true) GuiOutcome(GuiStop.STOPPED, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_829778), emptyList()) else throw e
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
            _state.value = _state.value.copy(result = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_b9fef6, GuiTaskMapping.head(outcome.stop), outcome.message))
        }
    }

    private suspend fun execute(task: String, maxSteps: Int, onLine: (String) -> Unit = {}, onHandOver: () -> Unit = {}): GuiOutcome {
        val ready = engine.state.value as? EngineState.Ready ?: return GuiOutcome(GuiStop.ERROR, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_65b1d0), emptyList())
        val profile = GuiProfile.forModel(ready.model.fileName)
        GuiStopSignal.reset()
        _state.value = GuiRunState(running = true, lines = listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_31d943, ready.model.fileName, profile.label)))
        lineSink = onLine
        handOverSink = onHandOver
        val model = EngineGuiModel(engine)
        guiModel = model
        // 2.1.9: IRIDIS sits in the background for the whole run — keep the CPU awake for the model.
        val wake = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "iris:gui-agent")
                .apply { setReferenceCounted(false); acquire(30 * 60_000L) }
        }.getOrNull()
        val outcome: GuiOutcome = try {
            GuiOverlay.show(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e27070)) { stop() }
            IrisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(600)
            GuiAgentLoop(
                model = model, device = AccessibilityGuiDevice(context), callbacks = callbacks,
                profile = profile, maxSteps = maxSteps, today = LocalDate.now().toString(),
                ocr = runCatching { GuiOcrProvider.get(context) }.getOrNull(),
            ).run(task)
        } catch (e: kotlinx.coroutines.CancellationException) {
            GuiOverlay.hide()
            _state.value = _state.value.copy(running = false, result = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_829778))
            throw e
        } catch (e: Exception) {
            GuiOutcome(GuiStop.ERROR, e.message ?: e.javaClass.simpleName, emptyList())
        } finally {
            ticker?.cancel(); ticker = null
            guiModel = null
            runCatching { if (wake?.isHeld == true) wake.release() }
            GuiOverlay.hide()
            lineSink = {}
            handOverSink = {}
        }
        _state.value = _state.value.copy(running = false)
        return outcome
    }
    @Volatile private var guiModel: EngineGuiModel? = null
    @Volatile private var ticker: Job? = null
    @Volatile private var lineSink: (String) -> Unit = {}
    @Volatile private var handOverSink: () -> Unit = {}

    /** 2.2.14: the chat whose agent started this run; 停止 ends that agent turn too (no re-call of `screen`). */
    @Volatile var onUserStop: (() -> Unit)? = null

    /**
     * 2.2.14: 停止 takes effect at once: flag for the loop, overlay (and any pending question)
     * removed now, the step's generation and the run cancelled, and the chat agent turn ended.
     */
    fun stop() {
        GuiStopSignal.request()
        GuiOverlay.hide()
        ticker?.cancel(); ticker = null
        job?.cancel()
        chatInner?.cancel()
        chatRun?.cancel()
        _state.value = _state.value.copy(running = false, result = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_829778))
        runCatching { onUserStop?.invoke() }
    }

    private val callbacks = object : GuiCallbacks {
        override suspend fun confirm(action: GuiAction, reason: String) = GuiOverlay.ask(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_24ecf6, reason), com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e6a5c3), com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2))
        override suspend fun takeOver(message: String): Boolean =
            GuiOverlay.ask(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_32ebdb, message), com.iris.localai.I18n.s(com.iris.localai.R.string.agent_433994), com.iris.localai.I18n.s(com.iris.localai.R.string.service_095e93)).also { if (!it) handOverSink() }
        // 第一阶段没有输入框：模型提问时停下，把问题带回 IRIDIS（聊天里由主智能体转述）。
        override suspend fun ask(question: String): String? { addLine(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b27633, question)); handOverSink(); return null }
        override fun onPhase(index: Int, phase: com.iris.localai.agent.gui.GuiPhase) {
            ticker?.cancel(); ticker = null
            val head = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_97e88b, index, phase.label)
            GuiOverlay.update(head)
            if (phase != com.iris.localai.agent.gui.GuiPhase.THINK) return
            val t0 = android.os.SystemClock.elapsedRealtime()
            ticker = scope.launch {
                while (true) {
                    delay(1000)
                    val sec = (android.os.SystemClock.elapsedRealtime() - t0) / 1000
                    val n = guiModel?.chars ?: 0
                    GuiOverlay.update(GuiPhaseText.thinking(head, sec, n))
                }
            }
        }
        override fun onStep(step: GuiStep) {
            ticker?.cancel(); ticker = null
            val what = step.action?.label() ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0257f0)
            val mark = when (step.status) {
                GuiStepStatus.OK -> ""; GuiStepStatus.FAILED -> " ✗"; GuiStepStatus.DENIED -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e10c40)
                GuiStepStatus.BLOCKED -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_971669); GuiStepStatus.FORMAT_ERROR -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ae5443, step.note)
            }
            GuiOverlay.update(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_97e88b, step.index, what))
            addLine("${step.index}. $what$mark" + if (step.status == GuiStepStatus.FAILED && step.note.isNotEmpty()) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_67f810, step.note) else "")
        }
    }

    private fun addLine(s: String) {
        _state.value = _state.value.copy(lines = (_state.value.lines + s).takeLast(40))
        lineSink(s)
    }

    /** 自检: one screenshot + node tree, no action. */
    suspend fun selfTest(): String {
        if (!IrisAccessibilityService.connected) return com.iris.localai.I18n.s(com.iris.localai.R.string.agent_da8247)
        return runCatching {
            val obs = AccessibilityGuiDevice(context).observe()
            val els = UiTreeSerializer.elements(obs.tree, obs.width, obs.height)
            com.iris.localai.I18n.s(com.iris.localai.R.string.agent_559515, obs.width, obs.height) + (if (obs.screenshot != null) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_330363) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de71af)) +
                com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dd68f5, els.size, obs.foregroundPkg ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1622dc))
        }.getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_123b18, it.message) }
    }
}

/** 2.1.9: the 停止 bar while the model works on a step (unit-tested). */
object GuiPhaseText {
    fun thinking(head: String, seconds: Long, chars: Int): String =
        if (chars <= 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0edf72, head, seconds) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1c64a4, head, seconds, chars)
}

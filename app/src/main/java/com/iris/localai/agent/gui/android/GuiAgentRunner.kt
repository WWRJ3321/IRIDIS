package com.iris.localai.agent.gui.android

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.agent.gui.GuiLimits
import com.iris.localai.agent.gui.GuiPhase
import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.iris.localai.agent.tools.GuiBlocker
import com.iris.localai.agent.tools.GuiTaskBackend
import com.iris.localai.agent.tools.GuiTaskResult
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
    /** characters of the reply so far (the 停止 bar shows it next to the seconds). */
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
                    is GenEvent.Done -> if (e.reason == StopReason.ERROR) throw EngineException(e.error ?: I18n.s(R.string.chat_7f7de8))
                }
            }
            true
        }
        android.util.Log.i(TAG, "step reply: first piece ${first}ms, total ${android.os.SystemClock.elapsedRealtime() - t0}ms, ${sb.length} chars, images=${messages.sumOf { it.images.size }}")
        if (done == null) throw EngineException(
            if (first < 0) I18n.s(R.string.agent_2b9315, timeoutMs / 1000)
            else I18n.s(R.string.agent_5efc4f, timeoutMs / 1000),
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

    /** the guide the chat shows (无障碍 / 识图模型) after a `screen` call was blocked; null = none. */
    private val _setup = MutableStateFlow<GuiBlocker?>(null)
    val setup: StateFlow<GuiBlocker?> = _setup.asStateFlow()
    fun dismissSetup() { _setup.value = null }

    /** Why a run cannot start now, or null (no side effects). */
    fun blockerKind(reconnect: Boolean = true, self: Job? = null): GuiBlocker? {
        if ((job?.isActive == true && job !== self) || chatRun?.isActive == true) return GuiBlocker.BUSY
        // 只读检查（界面重组时调用）不去重开无障碍、不 sleep——原来会在主线程卡最多 2 秒
        if (!reconnect && !IrisAccessibilityService.connected) return GuiBlocker.A11Y_OFF
        if (!IrisAccessibilityService.connected) {
            // 清后台 switched it off — put it back when allowed, give it a moment to connect.
            if (!IrisAccessibilityService.selfEnable(context)) return GuiBlocker.A11Y_OFF
            repeat(20) { if (!IrisAccessibilityService.connected) Thread.sleep(100) }
            if (!IrisAccessibilityService.connected) return GuiBlocker.A11Y_OFF
        }
        val ready = engine.state.value as? EngineState.Ready ?: return GuiBlocker.NO_MODEL
        if (!ready.details.supportsVision) return GuiBlocker.NO_VISION
        return null
    }

    /** The settings page's text for [blockerKind]. */
    fun blockerText(reconnect: Boolean = true, self: Job? = null): String? = when (blockerKind(reconnect, self)) {
        null -> null
        GuiBlocker.A11Y_OFF -> I18n.s(R.string.agent_13f110)
        GuiBlocker.NO_MODEL -> I18n.s(R.string.agent_f3f28a)
        GuiBlocker.NO_VISION -> I18n.s(R.string.agent_b50199)
        GuiBlocker.BUSY -> I18n.s(R.string.agent_c9e2c3)
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
                    val d = async { execute(task, GuiLimits.DEFAULT_MAX_STEPS, onLine = progress, onHandOver = { handedOver = true }) }
                    chatInner = d
                    d.await()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 悬浮条「停止」 cancels only the GUI run; the chat's 智能体 still reports it.
                if (me?.isActive == true) GuiOutcome(GuiStop.STOPPED, I18n.s(R.string.chat_829778), emptyList()) else throw e
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

    fun start(task: String, maxSteps: Int = GuiLimits.DEFAULT_MAX_STEPS) {
        if (job?.isActive == true) return
        job = scope.launch {
            // 检查（可能等无障碍重连最多 2 秒）移出主线程
            val me = coroutineContext[Job]
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { blockerText(self = me) }?.let { _state.value = GuiRunState(result = it); return@launch }
            val outcome = execute(task, maxSteps)
            _state.value = _state.value.copy(result = I18n.s(R.string.chat_b9fef6, GuiTaskMapping.head(outcome.stop), outcome.message))
        }
    }

    private suspend fun execute(task: String, maxSteps: Int, onLine: (String) -> Unit = {}, onHandOver: () -> Unit = {}): GuiOutcome {
        val ready = engine.state.value as? EngineState.Ready ?: return GuiOutcome(GuiStop.ERROR, I18n.s(R.string.agent_65b1d0), emptyList())
        val profile = GuiProfile.forModel(ready.model.fileName)
        GuiStopSignal.reset()
        _state.value = GuiRunState(running = true, lines = listOf(I18n.s(R.string.agent_31d943, ready.model.fileName, profile.label)))
        lineSink = onLine
        handOverSink = onHandOver
        val model = EngineGuiModel(engine)
        guiModel = model
        // IRIDIS sits in the background for the whole run — keep the CPU awake for the model.
        val wake = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "iris:gui-agent")
                .apply { setReferenceCounted(false); acquire(30 * 60_000L) }
        }.getOrNull()
        val outcome: GuiOutcome = try {
            GuiOverlay.show(I18n.s(R.string.agent_e27070)) { stop() }
            IrisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(600)
            GuiAgentLoop(
                model = model, device = AccessibilityGuiDevice(context), callbacks = callbacks,
                profile = profile, maxSteps = maxSteps, today = LocalDate.now().toString(),
                ocr = runCatching { GuiOcrProvider.get(context) }.getOrNull(),
            ).run(task)
        } catch (e: kotlinx.coroutines.CancellationException) {
            GuiOverlay.hide()
            _state.value = _state.value.copy(running = false, result = I18n.s(R.string.chat_829778))
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

    /** the chat whose agent started this run; 停止 ends that agent turn too (no re-call of `screen`). */
    @Volatile var onUserStop: (() -> Unit)? = null

    /**
     * 停止 takes effect at once: flag for the loop, overlay (and any pending question)
     * removed now, the step's generation and the run cancelled, and the chat agent turn ended.
     */
    fun stop() {
        GuiStopSignal.request()
        GuiOverlay.hide()
        ticker?.cancel(); ticker = null
        job?.cancel()
        chatInner?.cancel()
        chatRun?.cancel()
        _state.value = _state.value.copy(running = false, result = I18n.s(R.string.chat_829778))
        runCatching { onUserStop?.invoke() }
    }

    private val callbacks = object : GuiCallbacks {
        override suspend fun confirm(action: GuiAction, reason: String) = GuiOverlay.ask(I18n.s(R.string.agent_24ecf6, reason), I18n.s(R.string.agent_e6a5c3), I18n.s(R.string.chat_625fb2))
        override suspend fun takeOver(message: String): Boolean =
            GuiOverlay.ask(I18n.s(R.string.agent_32ebdb, message), I18n.s(R.string.agent_433994), I18n.s(R.string.service_095e93)).also { if (!it) handOverSink() }
        // 第一阶段没有输入框：模型提问时停下，把问题带回 IRIDIS（聊天里由主智能体转述）。
        override suspend fun ask(question: String): String? { addLine(I18n.s(R.string.agent_b27633, question)); handOverSink(); return null }
        override fun onPhase(index: Int, phase: GuiPhase) {
            ticker?.cancel(); ticker = null
            val head = I18n.s(R.string.agent_97e88b, index, phase.label)
            GuiOverlay.update(head)
            if (phase != GuiPhase.THINK) return
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
            val what = step.action?.label() ?: I18n.s(R.string.agent_0257f0)
            val mark = when (step.status) {
                GuiStepStatus.OK -> ""; GuiStepStatus.FAILED -> " ✗"; GuiStepStatus.DENIED -> I18n.s(R.string.agent_e10c40)
                GuiStepStatus.BLOCKED -> I18n.s(R.string.agent_971669); GuiStepStatus.FORMAT_ERROR -> I18n.s(R.string.agent_ae5443, step.note)
            }
            GuiOverlay.update(I18n.s(R.string.agent_97e88b, step.index, what))
            addLine("${step.index}. $what$mark" + if (step.status == GuiStepStatus.FAILED && step.note.isNotEmpty()) I18n.s(R.string.settings_67f810, step.note) else "")
        }
    }

    private fun addLine(s: String) {
        _state.value = _state.value.copy(lines = (_state.value.lines + s).takeLast(40))
        lineSink(s)
    }

    /** 自检: one screenshot + node tree, no action. */
    suspend fun selfTest(): String {
        if (!IrisAccessibilityService.connected) return I18n.s(R.string.agent_da8247)
        return runCatching {
            val obs = AccessibilityGuiDevice(context).observe()
            val els = UiTreeSerializer.elements(obs.tree, obs.width, obs.height)
            I18n.s(R.string.agent_559515, obs.width, obs.height) + (if (obs.screenshot != null) I18n.s(R.string.agent_330363) else I18n.s(R.string.agent_de71af)) +
                I18n.s(R.string.agent_dd68f5, els.size, obs.foregroundPkg ?: I18n.s(R.string.agent_1622dc))
        }.getOrElse { I18n.s(R.string.agent_123b18, it.message) }
    }
}

/** the 停止 bar while the model works on a step (unit-tested). */
object GuiPhaseText {
    fun thinking(head: String, seconds: Long, chars: Int): String =
        if (chars <= 0) I18n.s(R.string.agent_0edf72, head, seconds) else I18n.s(R.string.agent_1c64a4, head, seconds, chars)
}

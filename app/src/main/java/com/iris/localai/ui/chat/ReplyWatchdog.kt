package com.iris.localai.ui.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 2.1.8 「一发送就卡住」 safety net: a reply that shows no sign of life is ended with a reason
 * instead of spinning forever.
 *
 * Armed when a reply starts, disarmed by its first model output. Until then every sign of progress
 * ([touch]: load progress, summary text) restarts the clock. It fires when nothing happened for:
 *  - [Phase.MODEL] (waiting for a model load / tier reload): [LOAD_STALL_MS] — a GPU / NPU first
 *    load can compile for a long time without reporting progress, so this one is generous;
 *  - [Phase.PROMPT] (prefill until the first token): [FIRST_OUTPUT_MS], stretched for long prompts
 *    ([windowFor]: a slow CPU prefill of a long chat is not a hang).
 * After the first output it never fires (a streaming reply is alive; 停止 is the user's call).
 * Pure apart from [watch]'s delay; unit-tested with a virtual clock (v218/SendHangTest).
 */
class ReplyWatchdog(private val now: () -> Long = System::currentTimeMillis) {
    enum class Phase { MODEL, PROMPT }

    data class Verdict(val phase: Phase, val idleMs: Long) {
        val message: String get() = message(phase, idleMs)
    }

    @Volatile var phase: Phase = Phase.PROMPT
        private set
    @Volatile var disarmed: Boolean = false
        private set
    @Volatile private var windowMs: Long = FIRST_OUTPUT_MS
    @Volatile private var last: Long = now()

    /** The reply waits for a model (load in flight, on-demand load, tier reload). */
    fun waitingForModel() { phase = Phase.MODEL; touch() }

    /** The model is ready; the prompt goes in. [promptTokens] (estimate, 0 = unknown) stretches the window. */
    fun prompt(promptTokens: Int = 0) { phase = Phase.PROMPT; windowMs = windowFor(promptTokens); touch() }

    /** Something moved (load progress, summary text, a pass starting). */
    fun touch() { last = now() }

    /** The model produced output for this reply: it is alive, never fire again. */
    fun output() { disarmed = true }

    fun limitMs(): Long = if (phase == Phase.MODEL) LOAD_STALL_MS else windowMs

    /** Non-null when the reply has been silent past its limit. */
    fun stalled(): Verdict? {
        if (disarmed) return null
        val idle = now() - last
        return if (idle >= limitMs()) Verdict(phase, idle) else null
    }

    /** Polls until [job] ends (null) or the reply stalls (its verdict). */
    suspend fun watch(job: Job, tickMs: Long = TICK_MS): Verdict? {
        while (job.isActive) {
            delay(tickMs)
            if (!job.isActive) return null
            stalled()?.let { return it }
        }
        return null
    }

    companion object {
        const val FIRST_OUTPUT_MS = 90_000L
        const val LOAD_STALL_MS = 180_000L
        /** Extra time per prompt token before the first output (≈ a 33 tok/s worst-case prefill). */
        const val PER_TOKEN_MS = 30L
        const val MAX_FIRST_OUTPUT_MS = 600_000L
        const val TICK_MS = 1_000L
        /** After a stall the reply is cancelled; if it has not ended by then it is detached (busy cleared anyway). */
        const val DETACH_WAIT_MS = 5_000L

        fun windowFor(promptTokens: Int): Long =
            (FIRST_OUTPUT_MS + promptTokens.coerceAtLeast(0) * PER_TOKEN_MS).coerceAtMost(MAX_FIRST_OUTPUT_MS)

        fun message(phase: Phase, idleMs: Long): String {
            val s = idleMs / 1000
            return when (phase) {
                Phase.MODEL -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_ccf33d, s)
                Phase.PROMPT -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_efc79e, s)
            }
        }
    }
}

/**
 * 2.1.8: whether a reply runs — read from the raw sources. Before, ChatViewModel.busy() read the
 * derived `state` (combine … stateIn), which lags the source by a dispatch: right after a reply
 * ended it still said "generating", so the queue drain that runs at that moment skipped and a
 * 「待回复」 message waited forever. Pure.
 */
object ReplyBusy {
    fun of(streamingId: String?, jobActive: Boolean): Boolean = streamingId != null || jobActive
}

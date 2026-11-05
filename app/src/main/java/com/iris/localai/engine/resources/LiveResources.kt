package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * 1.7.2: when does a resource-tier change made *during* a reply reach the engine?
 *
 * Only GGUF on llama.cpp's CPU backend can take it mid-reply, and only the parts that ggml reads
 * per graph: thread count (llama_context::graph_compute → set_n_threads for every decode), and the
 * decoding thread's cores + nice value (ggml creates a disposable worker pool from that thread for
 * every graph when no threadpool is attached and OpenMP is off, so the workers inherit both).
 * Batch size only matters for prefill and is fixed in the context (n_batch / n_ubatch) → never
 * mid-reply. On the GPU (OpenCL) the CPU threads barely matter, and LiteRT-LM / MNN fix threads,
 * cores and power hint when the model is loaded → those never claim "立即生效".
 */
enum class LiveApply {
    IMMEDIATE, NEXT_REPLY, RELOAD,
    /**
     * 1.7.5, MNN on the CPU: cores + nice of the *existing* MNN threads change from the next token
     * (sched_setaffinity / setpriority on their tids, mid-reply or idle, no reload); a different
     * thread count still needs the 1.7.4 idle reload (MNN's pool size is fixed).
     */
    LIVE_POLICY,
}

/** 1.7.5: what one tier change actually did — drives the one-line status on the resource card. */
enum class TierApplyResult {
    /** Everything the new tier changes is already in effect (live re-pin confirmed by the kernel). */
    LIVE,
    /** Cores / priority are in effect now; the thread count follows with a reload when idle. */
    LIVE_THREADS_ON_RELOAD,
    /** Nothing could be applied to the loaded model; it is reloaded when idle. */
    RELOAD,
    /** Applied by the next reply (GGUF on the GPU, or GGUF/CPU between replies). */
    NEXT_REPLY,
    /** A reload for the tier is running. */
    RELOADING,
    /** That reload finished: the model now runs with the tier. */
    RELOADED,
    /** 1.7.7: the reload failed; the model was restored with the previous tier (setting reverted). */
    RELOAD_FAILED,
    /** No model loaded: the tier is used when one is. */
    NO_MODEL,
    /** 1.10.2: LiteRT-LM on the GPU / NPU — not reloaded for a speed change; the next load uses it. */
    NEXT_LOAD,
}

/** The part of a [ResourcePlan] that can be applied between two decodes. */
data class LiveChange(val threads: Int, val cpus: List<Int>, val nice: Int)

object LiveResourcePolicy {

    fun mode(format: ModelFormat?, backend: Backend?): LiveApply = when (format) {
        ModelFormat.GGUF -> if (backend == Backend.CPU) LiveApply.IMMEDIATE else LiveApply.NEXT_REPLY
        ModelFormat.MNN -> if (backend == Backend.CPU) LiveApply.LIVE_POLICY else LiveApply.RELOAD
        ModelFormat.LITERT -> LiveApply.RELOAD
        ModelFormat.QAIRT -> LiveApply.NEXT_REPLY
        null -> LiveApply.NEXT_REPLY
    }

    /** A live mode only when the format/backend rule *and* the engine itself both say so. */
    fun combine(byRule: LiveApply, byEngine: LiveApply?): LiveApply = when {
        byEngine == null -> byRule
        byRule == LiveApply.IMMEDIATE || byRule == LiveApply.LIVE_POLICY -> byEngine
        else -> byRule
    }

    /**
     * Cores to pin [threads] running threads to when the target tier's [cpus] are fewer than them.
     *
     * 1.7.5 released the pinning (every core) in that case, so a live switch 中→省电 / 全部→省电 on
     * MNN (thread count fixed until the idle reload) let the old threads run on the prime cores
     * (cpu6/7 on the 8 Elite) — the opposite of what a lower tier asks for. 1.7.6: never unpin.
     *  - [cpus] empty (target = every core) or already ≥ [threads] cores → [cpus] unchanged;
     *  - otherwise [cpus] plus the nearest cores from [nonPrime] (closest id first, the higher id on
     *    a tie, cpu0 last) until there is one core per thread or [nonPrime] runs out — then the
     *    threads share those cores (e.g. 8 threads on cpu0-5) rather than reaching the prime cores.
     * Examples on the 8 Elite: 中→省电 (2 threads, target cpu5) → cpu4-5; 全部→省电/中 (8 threads)
     * → cpu0-5. The idle reload then loads the target thread count and the plain target cores.
     * Prime cores already in [cpus] (高) stay; only non-prime cores are ever added.
     * 1.7.8: [nonPrime] is the plan's widen pool = the user's configured 省电 + 中 cores
     * (资源档配置, same backend kind), so the result only ever uses cores the user gave the lower
     * tiers; 「全部」 is gone, so 全部→高 (8 threads on cpu0-7) can no longer happen.
     * Mirrors thread_policy.h cores_for, which now trusts the list it is given.
     */
    fun coresFor(cpus: List<Int>, threads: Int, nonPrime: List<Int> = emptyList()): List<Int> {
        if (cpus.isEmpty() || cpus.size >= threads) return cpus
        val chosen = cpus.toMutableSet()
        val extra = nonPrime.filter { it !in chosen }
            .sortedWith(compareBy<Int>({ c -> cpus.minOf { kotlin.math.abs(it - c) } }, { -it }))
        for (c in extra) {
            if (chosen.size >= threads) break
            chosen += c
        }
        return chosen.sorted()
    }

    /** [coresFor] with the plan's own non-prime cores. */
    fun coresFor(plan: ResourcePlan, threads: Int): List<Int> = coresFor(plan.cpus, threads, plan.nonPrimeCpus)

    /**
     * 1.7.5: result of one tier change on a loaded model. [liveOk] = the engine confirmed the
     * live part (null = it was not attempted), [threadsDiffer] = the new tier uses another thread
     * count than the loaded model.
     */
    fun result(mode: LiveApply, generating: Boolean, liveOk: Boolean?, threadsDiffer: Boolean): TierApplyResult = when (mode) {
        LiveApply.LIVE_POLICY -> when {
            liveOk != true -> TierApplyResult.RELOAD
            threadsDiffer -> TierApplyResult.LIVE_THREADS_ON_RELOAD
            else -> TierApplyResult.LIVE
        }
        LiveApply.IMMEDIATE -> if (generating && liveOk == true) TierApplyResult.LIVE else TierApplyResult.NEXT_REPLY
        LiveApply.NEXT_REPLY -> TierApplyResult.NEXT_REPLY
        LiveApply.RELOAD -> TierApplyResult.RELOAD
    }

    /** 1.7.5: the one short status line under the tier bar (null = nothing changed yet). */
    fun statusText(r: TierApplyResult?): String? = when (r) {
        null -> null
        TierApplyResult.LIVE -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_4633d9)
        TierApplyResult.LIVE_THREADS_ON_RELOAD -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_c18fb3)
        TierApplyResult.RELOAD -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_998c99)
        TierApplyResult.NEXT_REPLY -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_08d6a2)
        TierApplyResult.RELOADING -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_54e6e3)
        TierApplyResult.RELOADED -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_6d5736)
        TierApplyResult.RELOAD_FAILED -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_acede9)
        TierApplyResult.NO_MODEL -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_b89512)
        TierApplyResult.NEXT_LOAD -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_862082)
    }

    /**
     * What may be applied mid-reply when moving from [current] to [next]; null when nothing the
     * decoder reads per token changes (e.g. only the batch size or the GPU permission differ).
     */
    fun liveChange(current: ResourcePlan?, next: ResourcePlan): LiveChange? {
        val change = LiveChange(next.threads, next.cpus, next.nice)
        if (current == null) return change
        return if (current.threads == next.threads && current.cpus == next.cpus && current.nice == next.nice) null else change
    }

    /** Parts of the new plan that this reply will not use (shown / logged honestly). */
    fun deferredParts(current: ResourcePlan?, next: ResourcePlan, mode: LiveApply): List<String> {
        if (mode != LiveApply.IMMEDIATE) return listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a8b0c2))
        if (current == null) return emptyList()
        val out = ArrayList<String>(2)
        if (current.batch != next.batch) out += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_e099c2)
        if (current.allowGpu != next.allowGpu) out += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_1c2f26)
        return out
    }

    /**
     * Subtitle of the composer's resource card. 1.7.4: MNN / LiteRT fix threads, cores and power at
     * load, so a change there is applied by reloading the same model (EngineManager tier reload):
     * right away when idle, after the current reply when generating. Never "立即生效" for them.
     */
    fun note(mode: LiveApply, generating: Boolean): String = when {
        mode == LiveApply.LIVE_POLICY && !generating -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_062c7f)
        mode == LiveApply.LIVE_POLICY -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_3e24d4)
        mode == LiveApply.RELOAD && !generating -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_e39bbb)
        mode == LiveApply.RELOAD -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_8e41e9)
        !generating -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_bcc05e)
        mode == LiveApply.IMMEDIATE -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_0a66ca)
        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_7f77bf)
    }

    /** 1.7.8: short card status when the model runs on the GPU / NPU (null on the CPU). */
    fun backendNote(active: Backend?): String? = if (active == null || active == Backend.CPU) null else com.iris.localai.I18n.s(com.iris.localai.R.string.engine_243289)

    /** 1.7.8: the full sentence for settings pages. */
    val GPU_SCOPE_NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_2203f1)

}

/**
 * Latest-wins mailbox between the UI thread (offer) and the decoding thread (take). Lock-free;
 * an unconsumed request is simply replaced by a newer one.
 */
class PendingLive<T : Any> {
    private val ref = AtomicReference<T?>(null)
    fun offer(value: T) { ref.set(value) }
    fun take(): T? = ref.getAndSet(null)
    fun clear() { ref.set(null) }
    val isPending: Boolean get() = ref.get() != null
}

/**
 * 1.7.8: the per-reply IrisResource line (EngineManager.generateOnce), so tiers and backends can be
 * compared on the phone with `adb logcat -s IrisResource`. Pure, unit-tested.
 */
object ResourceLog {
    /** What the tier controls on each backend, said once per line so nobody reads too much into it. */
    fun scope(active: Backend?): String = when (active) {
        Backend.CPU, null -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6fc6f4)
        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_a6ac8b)
    }

    /**
     * 1.7.9: "low" / "normal" / "high" (what the OpenCL context really got), "none" (driver took no
     * hint), "unsupported" (LiteRT); "-" on the CPU. Notes the tier's wish when it differs.
     */
    fun gpuHintText(active: Backend?, plan: ResourcePlan?, effective: String?): String {
        if (active == null || active == Backend.CPU || effective == null) return "-"
        val want = plan?.gpuHint?.native ?: return effective
        return when {
            effective == want -> effective
            effective == "low" || effective == "normal" || effective == "high" -> "$effective (tier wants $want, applied at the next reload)"
            else -> "$effective (tier wants $want)"
        }
    }

    fun perReply(
        backend: String,
        active: Backend?,
        plan: ResourcePlan?,
        loadedThreads: Int?,
        engineLive: LiveChange?,
        reason: StopReason?,
        tokens: Int?,
        tokPerSec: Float?,
        /** 1.7.9: [ModelDetails.gpuHint] — the GPU perf hint actually in effect. */
        gpuHint: String? = null,
    ): String {
        val tier = plan?.tier?.short ?: "?"
        val kind = plan?.kind?.name ?: "?"
        val threads = loadedThreads ?: plan?.threads
        // What the threads really got: the engine's own record (MNN/CPU) or what was applied from the plan.
        val cores = when {
            engineLive != null -> TierProfiles.cpuRange(engineLive.cpus) + " (engine)"
            plan != null -> TierProfiles.cpuRange(if (threads != null) LiveResourcePolicy.coresFor(plan, threads) else plan.cpus) + " (plan)"
            else -> "?"
        }
        val nice = engineLive?.nice ?: plan?.nice
        val speed = tokPerSec?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "?"
        return "reply resources: backend=$backend tier=$tier profile=$kind threads=${threads ?: "?"}" +
            (if (plan != null && threads != null && plan.threads != threads) " (tier wants ${plan.threads}, applied at the next reload)" else "") +
            " cores=$cores nice=${nice ?: "?"} gpuHint=${gpuHintText(active, plan, gpuHint)} tokens=${tokens ?: "?"} tok/s=$speed end=${reason?.name ?: "?"} | ${scope(active)}"
    }
}

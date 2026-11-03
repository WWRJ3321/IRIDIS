package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 1.7.4: a resource-tier change has to reach engines that fix their resources at load time.
 *
 * MNN writes thread_num / power into its config and creates its worker pool inside Llm::load();
 * LiteRT-LM fixes the CPU thread count (Backend.CPU(threadCount)) and its XNNPACK/dispatch threads
 * at engine creation. Neither can change them on a loaded model, so for those the only honest way
 * to apply a new tier is to reload the same model. GGUF on the CPU keeps 1.7.2's mid-reply switch
 * and never reloads.
 */
data class TierReloadDecision(val reload: Boolean, val changed: List<String>) {
    companion object {
        val NONE = TierReloadDecision(false, emptyList())
    }
}

enum class TierReloadResult { APPLIED, DEFERRED_BUSY, SKIPPED, FAILED }

object TierReloadPolicy {

    /**
     * Fields of [next] that differ from the plan the model was loaded with and that the loaded
     * engine actually reads at load time. [loadedPreferred]/[nextPreferred] = the backend a load
     * would start with (only differs when "自动" depends on the tier's GPU permission).
     */
    fun decide(
        format: ModelFormat?,
        activeBackend: Backend?,
        loaded: ResourcePlan?,
        next: ResourcePlan,
        loadedPreferred: Backend? = null,
        nextPreferred: Backend? = null,
        /** 1.7.5: cores/nice the loaded engine's threads have *now* (after live re-pins); null = unknown. */
        live: LiveChange? = null,
    ): TierReloadDecision {
        if (format == null || loaded == null) return TierReloadDecision.NONE
        val backendChanged = loadedPreferred != null && nextPreferred != null && loadedPreferred != nextPreferred
        val changed = ArrayList<String>(5)
        when (format) {
            ModelFormat.GGUF -> {
                // CPU: threads / cores / nice are applied between decodes (1.7.2), batch next load.
                // GPU/NPU: per-request threads; only a different backend choice needs a reload.
                if (activeBackend != Backend.CPU && backendChanged) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.models_e778d6)
            }
            ModelFormat.MNN -> if (activeBackend == Backend.CPU) {
                // 1.7.5: cores + nice are re-pinned on the existing threads (no reload); only the
                // pool size needs one. Power is always "normal" on the CPU (MNN binding off).
                if (loaded.threads != next.threads) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d27612)
                val curCpus = live?.cpus ?: LiveResourcePolicy.coresFor(loaded, loaded.threads)
                val curNice = live?.nice ?: loaded.nice
                if (curCpus != LiveResourcePolicy.coresFor(next, loaded.threads)) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.settings_976a3e)
                if (curNice != next.nice) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ee8ecb)
                if (backendChanged) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.models_e778d6)
            } else {
                // 1.7.8, MNN on the GPU: thread_num is the OpenCL mode (fixed by MnnEngine) and the
                // power hint is forced to normal, so neither needs a reload. Cores + nice reach the
                // generating (host) thread every reply; the reload only re-homes the driver threads
                // created at load.
                if (loaded.cpus != next.cpus) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.settings_976a3e)
                if (loaded.nice != next.nice) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ee8ecb)
                // 1.7.9: the Adreno perf hint is a property of the OpenCL context created at load.
                if (loaded.gpuHint != next.gpuHint) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.settings_117661)
                if (backendChanged) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.models_e778d6)
            }
            ModelFormat.LITERT -> {
                // 1.7.9: LiteRT-LM 0.18 has no GPU priority / perf option (Backend.GPU() takes no
                // arguments, EngineConfig has none) → a GPU 性能提示 change never reloads it.
                // 1.7.8: on the GPU / NPU LiteRT-LM takes no thread count (Backend.GPU()/NPU()).
                // 1.10.2: and no reload for the host threads' cores / priority either — that cost
                // the user a full 40 s+ engine init per speed change. They apply at the next load.
                if (ReloadRules.tierReloadsLiteRt(activeBackend)) {
                    if (loaded.threads != next.threads) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d27612)
                    if (loaded.cpus != next.cpus) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.settings_976a3e)
                    if (loaded.nice != next.nice) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ee8ecb)
                }
                if (backendChanged) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.models_e778d6)
            }
            // 2.2.0: GenieX QAIRT bundles take no thread / core setting; only a backend change matters.
            ModelFormat.QAIRT -> if (backendChanged) changed += com.iris.localai.I18n.s(com.iris.localai.R.string.models_e778d6)
        }
        return TierReloadDecision(changed.isNotEmpty(), changed)
    }

    fun describe(p: ResourcePlan?): String = if (p == null) "-" else
        "tier=${p.tier.short} threads=${p.threads} cpus=${if (p.cpus.isEmpty()) "all" else p.cpus.joinToString(",")} " +
            "nice=${p.nice} mnnPower=${p.mnnPower} gpuHint=${p.gpuHint.native} allowGpu=${p.allowGpu}"
}

/**
 * Debounces tier changes and runs at most one reload per burst, never during a reply:
 *  - every change restarts a [debounceMs] timer; only the last tier is evaluated;
 *  - idle → [reload] right away (in [scope]); generating → marked pending and evaluated again by
 *    [onGenerationFinished], against whatever tier is current then (switching back = no reload).
 */
class TierReloadScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val isBusy: () -> Boolean,
    /** null = no model loaded. */
    private val decide: () -> TierReloadDecision?,
    private val reload: suspend () -> TierReloadResult,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    /** Only the debounce delay lives here: cancelling it never touches a running reload. */
    private var job: Job? = null
    /** 1.7.7: evaluations (and the reloads they start) run one at a time and are never cancelled. */
    private val evalMutex = Mutex()
    @Volatile var pending: Boolean = false
        private set
    @Volatile var reloads: Int = 0
        private set
    /** 1.7.7: true while a reload started by this scheduler runs. */
    @Volatile var reloading: Boolean = false
        private set

    fun onTierChanged(label: String) {
        synchronized(lock) {
            job?.cancel()
            log("tier reload requested: $label (debounce ${debounceMs}ms)" + if (reloading) "; a reload is running, re-checking after it" else "")
            job = scope.launch {
                delay(debounceMs)
                runEvaluate("tier change")
            }
        }
    }

    /** Call when a reply ends (after the engine is no longer busy). */
    fun onGenerationFinished() {
        if (!pending) return
        synchronized(lock) {
            if (job?.isActive == true) return // a debounce timer is running; it evaluates soon
            job = scope.launch { runEvaluate("reply finished") }
        }
    }

    /**
     * 1.7.7: before, [onTierChanged] cancelled `job` even while it was inside [reload]; the
     * cancellation hit EngineManager.loadLocked after it had already unloaded the model, which then
     * ended Idle, and the follow-up evaluation found "no model loaded" — a request queued behind that
     * reload got "模型未加载". Now a started evaluation always completes (NonCancellable) and the
     * newer tier is evaluated after it.
     */
    private suspend fun runEvaluate(trigger: String) {
        evalMutex.withLock {
            withContext(NonCancellable) { evaluate(trigger) }
        }
    }

    private suspend fun evaluate(trigger: String) {
        val d = decide()
        if (d == null) { pending = false; log("tier reload skipped ($trigger): no model loaded"); return }
        if (!d.reload) { pending = false; log("tier reload not needed ($trigger): loaded engine already uses this tier or applies it live"); return }
        if (isBusy()) {
            pending = true
            log("tier reload deferred ($trigger): reply in progress, reloading after it ends; changed=${d.changed}")
            // The reply may have ended between isBusy() and pending = true (its end hook saw no pending).
            if (!isBusy()) evaluate("$trigger, reply just ended")
            return
        }
        pending = false
        reloading = true
        val r = try { reload() } finally { reloading = false }
        when (r) {
            TierReloadResult.APPLIED -> reloads++
            TierReloadResult.DEFERRED_BUSY -> {
                pending = true
                log("tier reload deferred ($trigger): a request started first; reloading after it ends")
                if (!isBusy()) evaluate("$trigger, reply just ended")
            }
            else -> Unit
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 400L
    }
}

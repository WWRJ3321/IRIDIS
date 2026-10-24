package com.iris.localai.engine

import android.content.Context
import android.util.Log
import com.iris.localai.data.AppSettings
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelRepository
import com.iris.localai.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 1.7.8: when the idle timer may unload the model (pure, unit-tested in V178Test). */
object IdleUnloadRule {
    fun canUnload(generating: Boolean, generationLocked: Boolean, registeredRequests: Int, reloading: Boolean): Boolean =
        !generating && !generationLocked && registeredRequests == 0 && !reloading
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(
        val model: LocalModel,
        val progress: Float,
        val since: Long = System.currentTimeMillis(),
        /** 1.7.4: non-null while the same model is reloaded to apply this resource tier. */
        val applyingTier: ResourceTier? = null,
        /** 1.9.0: the backend being tried right now (null = not chosen yet); drives [SlowLoadHint]. */
        val backend: Backend? = null,
    ) : EngineState
    data class Ready(val model: LocalModel, val details: ModelDetails) : EngineState
    data class Failed(val model: LocalModel?, val message: String) : EngineState
}

/** Who asked for a generation; shown in the UI while the engine is busy. */
enum class Requester { CHAT, VOICE, API }

/**
 * 1.14.3: identifies who may stop a generation. Before, [EngineManager.cancel] stopped whatever
 * ran, so a chat 停止 ended an API client's reply (and vice versa). Now a generation is tagged with
 * its owner and [EngineManager.cancel] only stops it for that same owner. One owner may be reused
 * for many generations (ChatViewModel keeps one); [EngineManager.generate] without one uses a fresh
 * owner nobody else holds (API requests: they stop by cancelling their own collection).
 */
class GenOwner(val name: String) {
    private val id = ids.incrementAndGet()
    override fun toString(): String = "$name#$id"
    private companion object { val ids = java.util.concurrent.atomic.AtomicLong() }
}

/**
 * 1.14.3: which owner's generation holds the engine (pure; unit-tested in V1143FixTest).
 * [begin] / [end] run under EngineManager's generation lock; [cancel] / [cancelAll] may come from
 * any thread. [stopEngine] is the engine's own (global) cancel; it is only called under [lock] so
 * the running generation cannot end and another owner's begin in between.
 */
class GenerationOwnership(private val stopEngine: () -> Unit) {
    /** One running generation. [stopRequested]: a cancel reached it (repeated on the next piece). */
    class Run internal constructor(val owner: GenOwner) {
        @Volatile var stopRequested: Boolean = false
            internal set
    }

    private val lock = Any()
    private var active: Run? = null

    val currentOwner: GenOwner? get() = synchronized(lock) { active?.owner }

    fun begin(owner: GenOwner): Run = synchronized(lock) { Run(owner).also { active = it } }

    fun end(run: Run) {
        synchronized(lock) { if (active === run) active = null }
    }

    /**
     * Stops the running generation only when it belongs to [owner]; a no-op (false) when another
     * owner's generation runs or nothing runs. True = the engine was asked to stop.
     */
    fun cancel(owner: GenOwner): Boolean = synchronized(lock) {
        val a = active
        if (a == null || a.owner !== owner) return@synchronized false
        a.stopRequested = true
        runCatching { stopEngine() }
        true
    }

    /** Load / unload: stops whatever runs (the old global cancel). Returns the owner it hit. */
    fun cancelAll(): GenOwner? = synchronized(lock) {
        val a = active
        a?.stopRequested = true
        runCatching { stopEngine() }
        a?.owner
    }
}

/** One row of the built-in speed test. [threads] 0 = the backend picks (LiteRT / NPU). */
data class BenchResult(val threads: Int, val promptTokens: Int, val prefillTps: Float, val decodeTps: Float, val firstTokenMs: Long)

/**
 * Single owner of the loaded model. Chat, voice mode and the HTTP API all go through here, so:
 *  - only one model is ever resident;
 *  - generations are serialised (an API call waits for a chat reply instead of racing it);
 *  - the model is unloaded after a configurable idle period to free RAM and let the SoC sleep.
 */
class EngineManager(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    val modelConfigs: com.iris.localai.data.ModelConfigRepository,
) {
    private val appContext = context.applicationContext
    val threadPolicy = ThreadPolicy(appContext)
    val crashMemory = BackendCrashMemory(appContext)
    /** Native load deaths are not Java exceptions; this keeps the next start from loading again. */
    private val startupGuard = StartupLoadGuard(appContext)
    /** 1.10.2: last load time + cache use per model / backend (model sheet, logcat IrisLoad). */
    val loadStats = LoadStatsStore(appContext)
    /** 1.10.2: why the model was last unloaded (logged with the next load). */
    @Volatile private var lastUnload: String? = null

    /** Probed on demand: the NPU runtime can be installed while the app runs. */
    fun environment(): BackendEnvironment = BackendEnvironment.probe(appContext)

    fun backendOptions(model: LocalModel): List<BackendOption> =
        BackendCapabilities.options(model.format, model.fileName, environment())

    /**
     * 2.2.0: which engine runs [model]. QAIRT folders: always GenieX. 2.1.3: GGUF follows the
     * backend ([com.iris.localai.engine.geniex.GgufEngineRule]): NPU → GenieX when it is usable,
     * GPU / CPU → llama.cpp. [backend] defaults to the backend a load starts with.
     */
    fun engineFor(
        model: LocalModel,
        cfg: com.iris.localai.data.EffectiveModelConfig = effective(model),
        backend: Backend? = null,
        options: List<BackendOption>? = null,
    ): String = when (model.format) {
        ModelFormat.QAIRT -> com.iris.localai.engine.geniex.GenieX.ENGINE_GENIEX
        ModelFormat.GGUF -> {
            val opts = options ?: backendOptions(model)
            val b = backend ?: BackendCapabilities.preferred(cfg.backend, model.format, model.fileName, opts)
            com.iris.localai.engine.geniex.GgufEngineRule.engineFor(
                b, hasGeniex && opts.any { it.backend == Backend.NPU && it.available },
            )
        }
        else -> com.iris.localai.engine.geniex.GenieX.ENGINE_LLAMA
    }

    /** 2.2.0: this build has the GenieX engine (full flavour). */
    val hasGeniex: Boolean get() = EngineFactory.geniex(appContext) != null

    /** 2.2.0: "GenieX · NPU" for a model GenieX would run (the backend a load starts with), else null. */
    fun geniexTag(model: LocalModel): String? =
        if (engineFor(model) == com.iris.localai.engine.geniex.GenieX.ENGINE_GENIEX)
            runCatching { com.iris.localai.engine.geniex.GenieX.tag(preferredBackend(model)) }.getOrNull()
        else null

    /** Global defaults merged with [model]'s own overrides. */
    fun effective(model: LocalModel? = readyModel): com.iris.localai.data.EffectiveModelConfig =
        com.iris.localai.data.EffectiveModelConfig.resolve(settings.current, model?.format, modelConfigs.get(model?.id))

    val tier: ResourceTier get() = ResourceTier.of(settings.current.resourceTier)

    /**
     * The backend a load of [model] starts with (its own choice, the engine default, or 自动).
     * 1.7.8: independent of the resource tier ([forTier] kept for callers; ignored) — the tier only
     * changes CPU resources, it never moves a model off the GPU the user picked.
     */
    @Suppress("UNUSED_PARAMETER")
    fun preferredBackend(
        model: LocalModel,
        cfg: com.iris.localai.data.EffectiveModelConfig = effective(model),
        forTier: ResourceTier = tier,
    ): Backend = BackendCapabilities.preferred(cfg.backend, model.format, model.fileName, backendOptions(model))

    /** Set after a successful non-CPU load: the first generation is still crash-guarded. */
    @Volatile private var guardFirstGeneration: Pair<String, Backend>? = null

    private val lifecycle = Mutex()
    private val generation = Mutex()
    /** Read by [cancel] / [cancelAll] from any thread; written only under [lifecycle] + [generation]. */
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var idleJob: Job? = null
    /** 1.14.3: the owner of the generation holding the engine; per-owner cancel goes through it. */
    private val ownership = GenerationOwnership { engine?.cancel() }

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _busy = MutableStateFlow<Requester?>(null)
    /** Non-null while a generation is running. */
    val busy: StateFlow<Requester?> = _busy.asStateFlow()

    val readyModel: LocalModel? get() = (state.value as? EngineState.Ready)?.model
    val readyDetails: ModelDetails? get() = (state.value as? EngineState.Ready)?.details

    /** 1.7.4: the plan / starting backend the resident model was loaded with (null = none loaded). */
    @Volatile private var loadedPlan: ResourcePlan? = null
    @Volatile private var loadedPreferred: Backend? = null

    /**
     * 1.7.7: requests vs. tier reloads (see [ReloadGate]). A request is registered from its start
     * (ChatViewModel: before ensureLoaded) to its end; a reload only begins with none registered.
     */
    private val gate = ReloadGate()

    /** 1.7.7: requests waiting for a tier reload to finish (UI: 「正在重载模型…」). */
    val waitingForReload: StateFlow<Int> = gate.waiting

    /** 1.7.7: a model a failed tier reload left unloaded; the next request loads it again. */
    @Volatile private var lostByReload: LocalModel? = null

    /** 1.7.7: one-off messages for the chat screen (reload failed / GPU fell back to CPU). */
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: kotlinx.coroutines.flow.SharedFlow<String> = _notices

    /**
     * Skip-preload text held until the chat screen collects it. [notices] does not replay,
     * so a decision made before the UI subscribes would otherwise be dropped.
     */
    private val _preloadSkipNotice = MutableStateFlow<String?>(null)
    val preloadSkipNotice: StateFlow<String?> = _preloadSkipNotice.asStateFlow()

    fun clearPreloadSkipNotice() { _preloadSkipNotice.value = null }

    /** 1.7.4: debounced "reload the same model with the new tier" for MNN / LiteRT. */
    private val tierReload = TierReloadScheduler(
        scope = scope,
        // 1.7.7: a registered request counts as busy even before it holds the generation lock.
        isBusy = { _busy.value != null || gate.requests > 0 },
        decide = ::decideTierReload,
        reload = ::reloadForTier,
        log = { Log.i(RES_TAG, it) },
    )

    /** True while a tier reload waits for the running reply to end. */
    val tierReloadPending: Boolean get() = tierReload.pending

    /** 1.7.5: what the last tier change really did (null = none since launch). Drives the card status. */
    private val _tierApply = MutableStateFlow<TierApplyResult?>(null)
    val tierApply: StateFlow<TierApplyResult?> = _tierApply.asStateFlow()

    /** 1.7.5: battery current / CPU evidence per tier, logcat only (IrisResource). */
    private val telemetry = ResourceTelemetry.android(appContext, scope) { Log.i(RES_TAG, it) }
    /** 1.7.6: last reply's per-tier numbers for 设置 › 资源诊断. */
    val telemetryReport get() = telemetry.lastReport
    @Volatile private var lastAppliedTier: ResourceTier? = null

    init {
        // 1.7.8: the user's 资源档配置 feed every plan.
        threadPolicy.profileSource = { settings.current.tierProfiles }
        // 1.7.7: a reload deferred because a request was registered runs when the last one ends.
        gate.onIdle = { tierReload.onGenerationFinished() }
        // Every writer of the tier (composer card, model sheet, settings page) goes through the settings.
        // 1.7.8: so does 资源档配置 — editing the profile of the tier in use is applied the same way
        // (live where possible, then the debounced, ReloadGate-guarded reload check).
        scope.launch {
            var lastTier = settings.current.resourceTier
            var lastProfiles = settings.current.tierProfiles
            settings.settings.map { it.resourceTier to it.tierProfiles }.distinctUntilChanged().drop(1).collect { (t, profiles) ->
                val next = ResourceTier.of(t)
                val tierChanged = t != lastTier
                lastTier = t
                val oldProfiles = lastProfiles
                lastProfiles = profiles
                val profileChanged = !tierChanged && ResourceBackendKind.entries.any { k ->
                    TierProfiles.resolve(oldProfiles, k, next, threadPolicy.topology) !=
                        TierProfiles.resolve(profiles, k, next, threadPolicy.topology)
                }
                if (!tierChanged && !profileChanged) return@collect // another tier's profile: nothing to do now
                // 1.7.5: cores / nice reach a loaded MNN (CPU) model at once, idle or mid-reply;
                // then the debounced reload check only reloads for what could not be applied live.
                if (profileChanged || lastAppliedTier != next) applyTierLive(next)
                tierReload.onTierChanged(next.label + if (profileChanged) "（资源档配置已修改）" else "")
            }
        }
    }

    /** 2.1.7: the load running / queued now; a message sent meanwhile waits for it ([ensureLoaded]). */
    private val inFlight = InFlightLoad()

    /** 2.1.7: the model of the load in flight (null = none). */
    val loadingModelPath: String? get() = inFlight.current()?.key

    suspend fun load(model: LocalModel, reason: String = "选择模型"): Boolean = inFlight.run(model.path) {
        lifecycle.withLock {
            // 1.10.2: the resident model is never reloaded by a plain load (same model = same backend
            // choice; a different backend / context only reloads through reload()).
            if (ReloadRules.alreadyLoaded(state.value, model.path)) {
                Log.i(LoadTelemetry.TAG, "load skipped ($reason): ${model.fileName} is already loaded")
                return@withLock true
            }
            cancelAll()
            loadLocked(model, applyingTier = null, reason = reason)
        }
    }

    /**
     * 1.10.2: background preload of the last-used model at app start (setting
     * 「启动时预加载上次的模型」 = the existing `autoLoadLastModel` key, default on).
     */
    suspend fun preloadAtStart(): PreloadDecision {
        val s = settings.current
        if (s.autoLoadLastModel && !s.lastModelFile.isNullOrBlank()) models.refresh()
        val m = models.find(s.lastModelFile)
        val blocked = startupGuard.blockAutoLoad()
        val d = PreloadPolicy.decide(s.autoLoadLastModel, s.lastModelFile, m != null, state.value is EngineState.Idle, blocked)
        Log.i(LoadTelemetry.TAG, "preload at start: $d${s.lastModelFile?.let { " ($it)" } ?: ""}")
        if (d == PreloadDecision.SKIP_AFTER_NATIVE_CRASH) _preloadSkipNotice.value = startupGuard.skipNotice()
        if (d == PreloadDecision.LOAD && m != null) load(m, reason = "启动预加载")
        return d
    }

    /** Must hold [lifecycle]. Waits for a running reply (never cancels it: [load] cancels first). */
    private suspend fun loadLocked(
        model: LocalModel,
        applyingTier: ResourceTier?,
        /** 1.7.7: load with this tier instead of the setting (restoring after a failed tier reload). */
        tierOverride: ResourceTier? = null,
        /** 1.7.7: CPU only (the first reply on a freshly loaded GPU backend failed). */
        cpuOnly: Boolean = false,
        /** 1.10.2: why this load happens (IrisLoad log). */
        reason: String = if (applyingTier != null) "资源档重载" else "加载",
    ): Boolean =
        generation.withLock {
            engine?.unload()
            engine = null
            loadedPlan = null
            loadedPreferred = null
            val since = System.currentTimeMillis()
            abandonedLoad = 0L
            fun abandoned() = abandonedLoad == since
            _state.value = EngineState.Loading(model, 0f, since, applyingTier)
            val cfg = effective(model)
            val useTier = tierOverride ?: tier
            // 1.7.8: CPU and GPU/NPU have separate profiles; the plan follows the backend being tried.
            var plan = threadPolicy.plan(useTier, cfg.threads, Backend.CPU)
            val options = backendOptions(model)
            val preferred = if (cpuOnly) Backend.CPU else preferredBackend(model, cfg, useTier)
            val crashed = crashMemory.crashed(model.id)
            val chain = BackendPlanner.chain(preferred, options, crashed)
            val errors = StringBuilder()
            options.firstOrNull { it.backend == preferred && !it.available }?.let {
                errors.append("[${preferred.label}] ${it.reason}\n")
            }
            if (preferred in crashed) errors.append("[${preferred.label}] 上次在这个后端上加载或首次生成时 App 崩溃，已跳过（模型设置里可重试）\n")
            var e: InferenceEngine? = null
            // Survives a SIGSEGV (the finally below does not run) so the next start can skip auto-load.
            com.iris.localai.util.CrashReporter.breadcrumb("加载模型 ${model.fileName}")
            try {
            try {
                if (chain.isEmpty()) throw EngineException("没有可用的后端：\n" + options.joinToString("\n") { "${it.backend.label}：${it.reason ?: "可用"}" })
                var details: ModelDetails? = null
                for (backend in chain) {
                    if (abandoned()) break
                    plan = threadPolicy.plan(useTier, cfg.threads, backend)
                    e = EngineFactory.create(appContext, model.format, engineFor(model, cfg, backend, options))
                    crashMemory.arm(model.id, backend)
                    if (!abandoned()) _state.value = EngineState.Loading(model, (_state.value as? EngineState.Loading)?.progress ?: 0f, since, applyingTier, backend)
                    val loadStart = System.currentTimeMillis()
                    // 1.10.3: GPU compile-cache guards — a load that never finished last time, too little space.
                    val marker = gpuLoadMarker(model, backend)
                    try {
                        details = e.load(
                            model,
                            LoadConfig(
                                contextLength = cfg.contextLength,
                                threads = plan.threads,
                                flashAttention = cfg.flashAttention,
                                backend = backend,
                                kvCacheType = if (cfg.flashAttention) cfg.kvCacheType else 0,
                                batchSize = cfg.batchSize.takeIf { it > 0 } ?: plan.batch,
                                useMmap = cfg.useMmap && !settings.current.mnnForceNoMmap,
                                noVisual = settings.current.mnnNoVisual,
                                resources = plan,
                            ),
                        ) { p ->
                            // Native loaders report progress very often; only publish visible changes.
                            val cur = _state.value
                            if (!abandoned() && (cur !is EngineState.Loading || p - cur.progress >= 0.01f || p >= 1f)) {
                                _state.value = EngineState.Loading(model, p, since, applyingTier, backend)
                            }
                        }
                        crashMemory.disarm()
                        marker?.let { runCatching { it.delete() } }
                        recordLoad(model, backend, System.currentTimeMillis() - loadStart, details, ok = true, reason = reason)
                        guardFirstGeneration = if (backend != Backend.CPU) model.id to backend else null
                        break
                    } catch (t: Throwable) {
                        crashMemory.disarm()
                        marker?.let { runCatching { it.delete() } }
                        if (t !is CancellationException) recordLoad(model, backend, System.currentTimeMillis() - loadStart, null, ok = false, reason = reason)
                        withContext(NonCancellable) { runCatching { e?.unload() } }
                        e = null
                        if (t is CancellationException) throw t
                        Log.w(TAG, "load on $backend failed", t)
                        errors.append("[${backend.label}] ").append(t.message ?: t.javaClass.simpleName).append('\n')
                    }
                }
                if (abandoned()) {
                    // 1.10.9: the user cancelled while the native call ran — release what it built.
                    Log.i(LoadTelemetry.TAG, "cancelled load returned after ${(System.currentTimeMillis() - since) / 1000}s: released")
                    withContext(NonCancellable) { runCatching { e?.unload() } }
                    guardFirstGeneration = null
                    if (_state.value is EngineState.Idle) _notices.tryEmit(LoadCancel.RELEASED)
                    return@withLock false
                }
                val d = details ?: throw EngineException(errors.toString().trim().ifEmpty { "加载失败" })
                val note = if (d.activeBackend != preferred) "已从 ${preferred.label} 回退到 ${d.activeBackend.label}：" +
                    errors.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().substringAfter("] ") else null
                // 1.7.8: never fall back silently (user rule) — log it and tell the chat screen.
                if (note != null) {
                    Log.w(RES_TAG, "backend FALLBACK: ${model.fileName} wanted ${preferred.label}, running ${d.activeBackend.label} " +
                        "(tier ${useTier.short}${if (applyingTier != null) ", tier reload" else ""}): ${errors.toString().trim().replace('\n', ' ')}")
                    _notices.tryEmit("${model.displayName}：$note")
                }
                // 2.1.8: bounded warm-up (WarmUpGuard): a first generation that never answers no longer
                // keeps the load in flight forever; its own cancellation propagates (no runCatching).
                if (cfg.warmup) {
                    val w = WarmUpGuard.run(onTimeout = { e?.cancel() }) { warmUp(e!!) }
                    if (w == WarmUpGuard.Outcome.TIMED_OUT) {
                        Log.w(TAG, "warm-up timed out after ${WarmUpGuard.TIMEOUT_MS}ms: ${model.fileName}")
                        _notices.tryEmit(WarmUpGuard.timeoutNotice(model.displayName))
                    }
                }
                engine = e
                lostByReload = null
                loadedPlan = plan
                loadedPreferred = preferred
                lastAppliedTier = plan.tier
                if (applyingTier == null) _tierApply.value = null   // a fresh model: no stale status
                _state.value = EngineState.Ready(model, d.copy(fallbackNote = note))
                settings.update { it.copy(lastModelFile = model.fileName) }
                startupGuard.noteLoadSucceeded()
                scheduleIdleUnload()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "load failed", t)
                withContext(NonCancellable) { runCatching { e?.unload() } }
                if (t is CancellationException) {
                    _state.value = EngineState.Idle
                    throw t
                }
                if (abandoned()) return@withLock false
                _state.value = EngineState.Failed(model, t.message ?: t.javaClass.simpleName)
                false
            }
            } finally {
                com.iris.localai.util.CrashReporter.breadcrumb(null)
            }
        }

    /**
     * 1.10.3: before a LiteRT GPU load: warn when the previous GPU load of this model never finished
     * (its marker is still there — the app was killed / frozen mid-compile, so the runtime rebuilds)
     * or when there is too little space for the GPU weight cache; then write the marker for this
     * load. Deleted when the load returns (success or failure); only a dead process leaves it.
     */
    private fun gpuLoadMarker(model: LocalModel, backend: Backend): java.io.File? {
        if (model.format != ModelFormat.LITERT || backend != Backend.GPU) return null
        return runCatching {
            val dir = java.io.File(appContext.filesDir, GpuCacheDiag.CACHE_DIR).apply { mkdirs() }
            val marker = java.io.File(dir, GpuCacheDiag.loadingMarker(model.fileName, backend))
            if (marker.exists()) {
                Log.w(LoadTelemetry.TAG, "previous GPU load of ${model.fileName} did not finish (marker from ${marker.readText().trim()}): the runtime will rebuild its cache")
                _notices.tryEmit("${model.displayName}：${GpuCacheDiag.INTERRUPTED_NOTICE}")
            }
            val free = dir.usableSpace
            if (GpuCacheDiag.lowSpace(free, model.sizeBytes)) {
                Log.w(LoadTelemetry.TAG, "low space for GPU cache: free=${LoadTelemetry.mb(free)} model=${LoadTelemetry.mb(model.sizeBytes)}")
                _notices.tryEmit(GpuCacheDiag.lowSpaceNotice(model.displayName, free, model.sizeBytes))
            }
            marker.writeText(System.currentTimeMillis().toString())
            marker
        }.getOrNull()
    }

    /** 1.10.2: stores + logs one load (adb logcat -s IrisLoad). */
    private fun recordLoad(model: LocalModel, backend: Backend, ms: Long, d: ModelDetails?, ok: Boolean, reason: String) {
        val r = LoadRecord(
            modelId = model.id,
            backend = backend.label,
            ms = ms,
            cache = LoadTelemetry.classify(backend, d?.cacheBefore),
            before = d?.cacheBefore,
            after = d?.cacheAfter,
            maxNumTokens = if (model.format == ModelFormat.LITERT) d?.contextLength ?: 0 else 0,
            ok = ok,
            at = System.currentTimeMillis(),
            reason = reason + (lastUnload?.let { "（上次卸载：$it）" } ?: ""),
        )
        lastUnload = null
        if (ok) loadStats.put(r)
        val free = runCatching { android.os.StatFs(appContext.filesDir.path).availableBytes }.getOrNull()
        Log.i(LoadTelemetry.TAG, LoadTelemetry.logLine(model.fileName, r, free))
    }

    /** One greedy token, then forget it: pages the weights in and builds kernels before the first real reply. */
    private suspend fun warmUp(e: InferenceEngine) {
        e.generate(
            listOf(PromptMessage(PromptMessage.USER, "你好")),
            SamplingConfig(temperature = 0f, topP = 1f, topK = 1, minP = 0f, repeatPenalty = 1f, maxNewTokens = 1, disableThinking = true),
        ).collect {}
        e.resetContext()
    }

    suspend fun unload(reason: String = "手动卸载") = lifecycle.withLock {
        cancelAll()
        if (engine != null) {
            lastUnload = reason
            Log.i(LoadTelemetry.TAG, "unload: ${readyModel?.fileName ?: "?"} ($reason)")
        }
        generation.withLock {
            engine?.unload()
            engine = null
            loadedPlan = null
            loadedPreferred = null
            idleJob?.cancel()
            _state.value = EngineState.Idle
        }
    }

    /** Reloads the current model, e.g. after changing context length or threads. */
    suspend fun reload() {
        val m = readyModel ?: return
        unload("重新加载")
        load(m, reason = "重新加载")
    }

    /** Loads the last used model if nothing is loaded (used by auto-load and the API). */
    suspend fun ensureLoaded(preferredFileName: String? = null): Boolean {
        if (preferredFileName == null) {
            // 2.1.7: a load is running (any model) → wait for it and answer with that model; never
            // cancel it to reload the previously used one. Follows a newer load started meanwhile.
            val awaited = inFlight.awaitInFlight()
            if (awaited != null) return awaited && state.value is EngineState.Ready
            if (state.value is EngineState.Ready) return true
        }
        models.refresh()
        val target = models.find(preferredFileName)
            ?: readyModel
            ?: defaultModel()
            ?: return false
        return load(target, reason = "按需加载")
    }

    /** 2.1.7 「默认模型」: the chosen default if still installed, else the last used model. */
    fun defaultModel(): LocalModel? =
        settings.current.defaultModelFile.takeIf { it.isNotBlank() }?.let { models.find(it) }
            ?: models.find(settings.current.lastModelFile)

    /**
     * Sampling for the loaded model: its own settings over the global defaults, and the resource
     * tier's threads / cores / priority. No pacing: speed is whatever those resources give.
     */
    fun samplingFor(
        disableThinking: Boolean,
        cfg: com.iris.localai.data.EffectiveModelConfig = effective(),
        contextShift: Boolean = false,
        keepTokens: Int = 4,
    ): SamplingConfig {
        // 1.7.8: the plan of the backend the model really runs on (CPU or GPU/NPU profile set).
        val plan = threadPolicy.plan(tier, cfg.threads, readyDetails?.activeBackend)
        return SamplingConfig(
            temperature = cfg.temperature,
            topP = cfg.topP,
            topK = cfg.topK,
            minP = cfg.minP,
            repeatPenalty = cfg.repeatPenalty,
            maxNewTokens = cfg.maxNewTokens,
            disableThinking = disableThinking,
            threads = plan.threads,
            repeatLastN = cfg.repeatLastN,
            presencePenalty = cfg.presencePenalty,
            frequencyPenalty = cfg.frequencyPenalty,
            seed = cfg.seed,
            contextShift = contextShift,
            keepTokens = keepTokens,
            resources = plan,
        )
    }

    /**
     * 1.7.7: runs [block] as one registered request (see [ReloadGate]): a tier reload cannot start
     * while it runs, and if one is running it waits for it first ([onWaiting] true/false).
     * ChatViewModel wraps ensureLoaded → plan → generate in this so the reload can never slip in
     * between them.
     */
    suspend fun <T> request(onWaiting: (Boolean) -> Unit = {}, block: suspend () -> T): T =
        gate.request(onWaiting, block)

    /**
     * Runs one generation. Requests queue behind each other; collection cancellation stops the
     * native work promptly. 1.7.7: registered with [gate] (waits for a running tier reload, then
     * starts by itself); a model a failed reload left unloaded is loaded again first; a first reply
     * on a freshly loaded GPU/NPU backend that fails before any output is retried once on the CPU.
     * 1.14.3: [owner] is who may stop it with [cancel] (default: a fresh owner, i.e. only collection
     * cancellation or [cancelAll] stop it).
     */
    fun generate(
        history: List<PromptMessage>,
        params: SamplingConfig,
        requester: Requester,
        owner: GenOwner = GenOwner(requester.name),
    ): Flow<GenEvent> = flow {
        gate.request(onWaiting = { w ->
            if (w) Log.i(RES_TAG, "request ($requester) queued: tier reload in progress, starts when it ends")
            else Log.i(RES_TAG, "request ($requester) released: tier reload finished, state=${state.value.javaClass.simpleName}")
        }) {
            recoverAfterFailedReload(requester)
            var cpuRetried = false
            while (true) {
                val retryOnCpu = generateOnce(history, params, requester, owner, allowCpuRetry = !cpuRetried) { emit(it) }
                if (!retryOnCpu) break
                cpuRetried = true
                val m = readyModel
                val from = readyDetails?.activeBackend
                val ok = m != null && lifecycle.withLock { loadLocked(m, applyingTier = null, cpuOnly = true, reason = "首次生成失败回退 CPU") }
                if (!ok) {
                    emit(GenEvent.Done(StopReason.ERROR, null, "${from?.label ?: "GPU"} 上首次生成失败，回退 CPU 也失败：" +
                        ((state.value as? EngineState.Failed)?.message ?: "模型未加载")))
                    break
                }
                Log.w(GEN_TAG, "first reply on ${from?.label} failed before any output → reloaded on CPU, retrying the same request")
                Log.w(RES_TAG, "backend FALLBACK: first reply on ${from?.label} failed before any output → now on CPU (this model's next reload tries ${from?.label} again)")
                _notices.tryEmit("${from?.label ?: "GPU"} 上首次生成失败，已自动回退到 CPU 并重新生成")
            }
        }
    }

    /** 1.7.7: a failed tier reload (and failed restore) left no model: load it again for this request. */
    private suspend fun recoverAfterFailedReload(requester: Requester) {
        if (engine != null && state.value is EngineState.Ready) return
        val m = lostByReload ?: return
        Log.w(RES_TAG, "request ($requester): the last tier reload left no model loaded → loading ${m.fileName} again")
        load(m, reason = "重载失败后恢复")
    }

    /**
     * One attempt. Returns true when the caller should reload on the CPU and try again: the first
     * reply after a non-CPU load ended in ERROR before any output (that Done is then not emitted).
     */
    private suspend fun generateOnce(
        history: List<PromptMessage>,
        params: SamplingConfig,
        requester: Requester,
        owner: GenOwner,
        allowCpuRetry: Boolean,
        emit: suspend (GenEvent) -> Unit,
    ): Boolean = generation.withLock {
        val e = engine
        if (e == null) {
            val why = (state.value as? EngineState.Failed)?.message?.let { "模型加载失败：$it" } ?: "模型未加载"
            Log.w(GEN_TAG, "request ($requester) rejected: $why")
            emit(GenEvent.Done(StopReason.ERROR, null, why))
            return@withLock false
        }
        idleJob?.cancel()
        _busy.value = requester
        val guard = guardFirstGeneration
        if (guard != null) crashMemory.arm(guard.first, guard.second)
        // 1.7.5: power / CPU evidence for this reply (logcat only).
        val plan = params.resources
        if (plan != null) telemetry.begin(plan.tier.short) { e.resourceThreadIds() }
        var tokens: Int? = null
        var tokPerSec: Float? = null
        // 1.7.7: why the reply ended + reasoning vs. answer tokens, under logcat tag IrisGen.
        val acct = com.iris.localai.util.ReplyAccounting(
            startInThinking = readyDetails?.thinkingPrefilled == true && !params.disableThinking,
        )
        var pieces = 0
        var done: GenEvent.Done? = null
        var retry = false
        val t0 = System.currentTimeMillis()
        val run = ownership.begin(owner) // ended in the finally below
        try {
            e.generate(history, params).collect { ev ->
                when (ev) {
                    is GenEvent.Piece -> {
                        // 1.14.3: a cancel that reached the engine before its native call started can
                        // be lost (MNN / LiteRT clear the flag at start): repeat it for this run only.
                        if (run.stopRequested) runCatching { e.cancel() }
                        pieces++; acct.onPiece(ev.text); telemetry.piece(); emit(ev)
                    }
                    is GenEvent.Done -> {
                        tokens = ev.stats?.generatedTokens; tokPerSec = ev.stats?.tokensPerSecond
                        done = ev
                        // 2.0: an NPU-only file cannot be reloaded on the CPU; report the native error
                        // ("NPU 上首次生成失败：…") instead of a doomed CPU retry that hid it.
                        val npuOnly = readyModel?.let { it.format == ModelFormat.LITERT && BackendCapabilities.npuTarget(it.fileName) != null } == true
                        when (val d = FirstReplyFallback.decide(
                            allowRetry = allowCpuRetry, guardedBackend = guard?.second,
                            failedBeforeOutput = ev.reason == StopReason.ERROR && pieces == 0,
                            npuOnlyModel = npuOnly, error = ev.error,
                        )) {
                            FirstReplyFallback.Decision.RetryOnCpu -> retry = true
                            FirstReplyFallback.Decision.PassThrough -> emit(ev)
                            is FirstReplyFallback.Decision.Report -> {
                                Log.w(GEN_TAG, "first reply on NPU failed before any output (NPU-only model, no CPU fallback): ${ev.error}")
                                val reported = GenEvent.Done(ev.reason, ev.stats, d.message)
                                done = reported
                                emit(reported)
                            }
                        }
                    }
                }
            }
        } finally {
            ownership.end(run)
            val d = done
            Log.i(
                GEN_TAG,
                "[$owner/$requester ${readyDetails?.activeBackend?.label ?: "?"} ${System.currentTimeMillis() - t0}ms] " +
                    if (d == null) "reply end: reason=CANCELLED (collector gone) ${acct.describe(StopReason.CANCELLED, null, params.maxNewTokens, null)}"
                    else acct.describe(d.reason, d.stats?.generatedTokens, params.maxNewTokens, d.error) +
                        if (retry) " → retrying on CPU" else "",
            )
            if (plan != null) telemetry.end(tokens, tokPerSec)
            // 1.7.8: one line per reply for comparing tiers / backends on the phone (adb logcat -s IrisResource).
            Log.i(RES_TAG, ResourceLog.perReply(
                backend = readyDetails?.backend ?: "?", active = readyDetails?.activeBackend, plan = plan,
                loadedThreads = loadedPlan?.threads, engineLive = runCatching { e.livePolicy() }.getOrNull(),
                reason = done?.reason, tokens = tokens, tokPerSec = tokPerSec, gpuHint = readyDetails?.gpuHint,
            ))
            if (guard != null) { crashMemory.disarm(); guardFirstGeneration = null }
            _busy.value = null
            scheduleIdleUnload()
            tierReload.onGenerationFinished() // 1.7.4: a deferred tier reload runs now
        }
        retry
    }

    /**
     * 1.14.3: stops the running generation only if it belongs to [owner]; another owner's generation
     * (chat vs. API vs. speed test) is left alone, and a still-queued one is not touched (its owner
     * cancels its collection). True = the engine was asked to stop.
     */
    fun cancel(owner: GenOwner): Boolean = ownership.cancel(owner)

    /** 1.14.3: the old global cancel, for load / unload only (whoever runs is stopped). */
    fun cancelAll() {
        ownership.cancelAll()?.let { Log.i(GEN_TAG, "cancelAll: stopped ($it)") }
    }

    /** 1.14.3: owner of the speed test's generations ([benchmark]). */
    private val benchOwner = GenOwner("bench")

    /** 1.14.3: 设置 › 测速 「停止」: stops only the speed test's own generation. */
    fun cancelBenchmark(): Boolean = cancel(benchOwner)

    /**
     * Kept for 设置 › 测速 「停止」 (SettingsViewModel.cancelBenchmark), its only remaining caller.
     * 1.14.3: no longer global — stops the speed test's generation only.
     */
    @Deprecated("Not global any more: use cancel(owner), cancelBenchmark() or cancelAll()", ReplaceWith("cancelBenchmark()"))
    fun cancel() { cancelBenchmark() }

    /** 1.10.9: [EngineState.Loading.since] of a load the user cancelled (0 = none). */
    @Volatile private var abandonedLoad = 0L

    /**
     * 1.10.9: 「取消加载」. The native load call itself cannot be interrupted, so the load is
     * abandoned: the UI is free at once (state Idle), nothing else may use the result, and when the
     * native call returns the half-loaded model is released immediately. A new load waits for that.
     */
    fun cancelLoad(): Boolean {
        val st = _state.value as? EngineState.Loading ?: return false
        if (st.applyingTier != null) return false   // a tier reload restores the model; never strand it
        abandonedLoad = st.since
        Log.i(LoadTelemetry.TAG, "load cancelled by user: ${st.model.fileName} after ${(System.currentTimeMillis() - st.since) / 1000}s")
        _state.value = EngineState.Idle
        // 2.1.7: a message waiting for this load is released now (not when the native call returns).
        inFlight.current()?.let { inFlight.end(it, false) }
        _notices.tryEmit(LoadCancel.NOTICE)
        return true
    }

    /** 1.7.2: how a tier change reaches the loaded model while it generates. */
    fun liveApplyMode(): LiveApply {
        val ready = state.value as? EngineState.Ready ?: return LiveApply.NEXT_REPLY
        // Both must agree: the format/backend rule and what the engine itself reports.
        val byRule = LiveResourcePolicy.mode(ready.model.format, ready.details.activeBackend)
        return LiveResourcePolicy.combine(byRule, engine?.liveApplyMode())
    }

    /**
     * 1.7.2: called after the user picked [newTier] while a reply runs. Applies threads / cores /
     * priority to that reply when the engine supports it (GGUF on the CPU); otherwise the new tier
     * is only in the settings and takes effect with the next reply (or the next load).
     */
    fun applyTierLive(newTier: ResourceTier): TierApplyResult {
        val from = lastAppliedTier ?: loadedPlan?.tier
        lastAppliedTier = newTier
        val st = state.value
        val result = when {
            st is EngineState.Loading && st.applyingTier != null -> TierApplyResult.RELOADING
            st !is EngineState.Ready -> TierApplyResult.NO_MODEL
            else -> {
                val mode = liveApplyMode()
                val generating = _busy.value != null
                val plan = threadPolicy.plan(newTier, effective().threads, st.details.activeBackend)
                val e = engine
                // 1.7.2 GGUF/CPU: only a running reply can take it; 1.7.5 MNN/CPU: idle or running.
                val attempt = mode == LiveApply.LIVE_POLICY || (mode == LiveApply.IMMEDIATE && generating)
                val liveOk = if (attempt && e != null) e.applyResourcesLive(plan) else null
                val threadsDiffer = loadedPlan?.threads != plan.threads
                // 1.10.2: LiteRT-LM on the GPU / NPU is not reloaded for a speed change (see
                // ReloadRules.tierReloadsLiteRt) — the new tier is used by its next load.
                LiveResourcePolicy.result(mode, generating, liveOk, threadsDiffer).let { r ->
                    if (r == TierApplyResult.RELOAD && st.model.format == ModelFormat.LITERT &&
                        !ReloadRules.tierReloadsLiteRt(st.details.activeBackend)) TierApplyResult.NEXT_LOAD else r
                }
            }
        }
        Log.i(RES_TAG, "tier change ${from?.short ?: "?"}→${newTier.short}: ${result.name} (${LiveResourcePolicy.statusText(result)})")
        telemetry.tierChanged(from?.short ?: "?", newTier.short, result)
        _tierApply.value = result
        return result
    }

    /** 1.7.4: does the current tier need the resident model reloaded? null = nothing loaded. */
    private fun decideTierReload(): TierReloadDecision? {
        val ready = state.value as? EngineState.Ready ?: return null
        val loaded = loadedPlan ?: return null
        val cfg = effective(ready.model)
        val next = threadPolicy.plan(tier, cfg.threads, ready.details.activeBackend)
        val nextPreferred = runCatching { preferredBackend(ready.model, cfg) }.getOrNull()
        return TierReloadPolicy.decide(
            ready.model.format, ready.details.activeBackend, loaded, next, loadedPreferred, nextPreferred, engine?.livePolicy(),
        )
    }

    /**
     * 1.7.4: reloads the resident model with the current tier, bypassing [load]'s same-model early
     * return. Never cancels a reply: if one is running it reports DEFERRED_BUSY and the scheduler
     * retries when it ends. The conversation lives in the app, so nothing is lost; engines without
     * a restored KV cache (MNN) simply prefill the history again on the next message.
     */
    private suspend fun reloadForTier(): TierReloadResult = lifecycle.withLock {
        val ready = state.value as? EngineState.Ready ?: return@withLock TierReloadResult.SKIPPED
        if (_busy.value != null) return@withLock TierReloadResult.DEFERRED_BUSY
        val d = decideTierReload()
        if (d == null || !d.reload) return@withLock TierReloadResult.SKIPPED
        // 1.7.7: a request that has started (even one still before the generation lock) wins; the
        // reload runs when the last request ends (gate.onIdle → scheduler).
        if (!gate.tryBeginReload()) {
            Log.i(RES_TAG, "tier reload deferred: ${gate.requests} request(s) in progress")
            return@withLock TierReloadResult.DEFERRED_BUSY
        }
        try {
            val t = tier
            val prevTier = loadedPlan?.tier
            val from = TierReloadPolicy.describe(loadedPlan)
            Log.i(RES_TAG, "tier reload APPLYING: ${ready.model.fileName} (${ready.model.format}/${ready.details.activeBackend}) changed=${d.changed} from [$from]")
            val started = System.currentTimeMillis()
            val ok = loadLocked(ready.model, applyingTier = t)
            val ms = System.currentTimeMillis() - started
            if (ok) {
                val now = readyDetails
                Log.i(RES_TAG, "tier reload APPLIED in ${ms}ms: ${ready.model.fileName} backend=${now?.activeBackend} [${TierReloadPolicy.describe(loadedPlan)}]")
                if (loadedPlan?.tier == tier) _tierApply.value = TierApplyResult.RELOADED
                TierReloadResult.APPLIED
            } else {
                val err = (state.value as? EngineState.Failed)?.message ?: "未知错误"
                Log.w(RES_TAG, "tier reload FAILED after ${ms}ms: $err")
                // 1.7.7: never leave the user without a model: load it again with the tier it ran on,
                // and put the setting back so card, plan and model agree.
                val restored = prevTier != null && loadLocked(ready.model, applyingTier = null, tierOverride = prevTier, reason = "资源档重载失败后恢复")
                if (restored) {
                    Log.w(RES_TAG, "tier reload: restored ${ready.model.fileName} with previous tier ${prevTier.short}; setting reverted")
                    settings.update { it.copy(resourceTier = prevTier.ordinal) }
                    _tierApply.value = TierApplyResult.RELOAD_FAILED
                    _notices.tryEmit("切换到「${t.label}」时重新加载模型失败：${err.lineSequence().first()}。已恢复为「${prevTier.label}」")
                } else {
                    lostByReload = ready.model
                    Log.w(RES_TAG, "tier reload: restore with previous tier failed too; next request loads again")
                    _notices.tryEmit("切换到「${t.label}」时重新加载模型失败：${err.lineSequence().first()}")
                }
                TierReloadResult.FAILED
            }
        } finally {
            gate.endReload()
        }
    }

    /**
     * Speed test on the loaded model: the same ~300-token prompt and 64 greedy tokens per thread
     * count, each with an empty KV cache so prefill is measured in full. A short warm-up run first
     * keeps page-in / kernel compilation out of the numbers.
     */
    suspend fun benchmark(threadCounts: List<Int>, onStep: (done: Int, total: Int) -> Unit): List<BenchResult> {
        if (threadCounts.isEmpty()) return emptyList()
        val prompt = buildString {
            append("下面是一段测试文字，请忽略它的内容。")
            repeat(12) { append("本地大模型在手机上运行时，预填充速度取决于矩阵乘法吞吐，解码速度取决于内存带宽。") }
            append("\n请从 1 开始用阿拉伯数字连续数下去，用逗号分隔，不要说别的。")
        }
        val history = listOf(PromptMessage(PromptMessage.USER, prompt))
        fun params(t: Int, n: Int) = SamplingConfig(
            temperature = 0f, topP = 1f, topK = 1, minP = 0f, repeatPenalty = 1f,
            maxNewTokens = n, disableThinking = true, threads = t,
        )
        suspend fun run(t: Int, n: Int): GenerationStats? {
            resetContext()
            var stats: GenerationStats? = null
            var err: String? = null
            generate(history, params(t, n), Requester.CHAT, benchOwner).collect { ev ->
                if (ev is GenEvent.Done) { stats = ev.stats; err = ev.error }
            }
            err?.let { throw EngineException(it) }
            return stats
        }
        val total = threadCounts.size + 1
        onStep(0, total)
        run(threadCounts.first(), 8) // warm-up
        val out = ArrayList<BenchResult>()
        threadCounts.forEachIndexed { i, t ->
            val s = run(t, 64)
            onStep(i + 2, total)
            if (s != null) {
                val pre = (s.promptTokens - s.reusedTokens).coerceAtLeast(0)
                out += BenchResult(
                    threads = t,
                    promptTokens = pre,
                    prefillTps = if (s.prefillMs > 0) pre * 1000f / s.prefillMs else 0f,
                    decodeTps = s.tokensPerSecond,
                    firstTokenMs = s.prefillMs,
                )
            }
        }
        resetContext()
        return out
    }

    suspend fun resetContext() {
        generation.withLock { engine?.resetContext() }
    }

    /** 2.3: 新对话 — keeps the KV cache where the engine reuses a common prefix by itself (system prompt). */
    suspend fun resetForNewChat() {
        generation.withLock { engine?.takeUnless { it.reusesCommonPrefix }?.resetContext() }
    }

    /** Restart the idle countdown (also call on user activity such as typing). */
    fun touch() = scheduleIdleUnload()

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        val minutes = settings.current.idleUnloadMinutes
        if (minutes <= 0 || state.value !is EngineState.Ready) return
        idleJob = scope.launch {
            delay(minutes * 60_000L)
            // 1.7.8: also not while a request is registered (between ensureLoaded and generate it
            // holds no lock yet) or a tier reload owns the engine — unloading there made that
            // request fail with 「模型未加载」.
            if (IdleUnloadRule.canUnload(busy.value != null, generation.isLocked, gate.requests, gate.reloading.value)) {
                idleJob = null // unload() must not cancel the coroutine it is running in
                Log.i(TAG, "idle for $minutes min → unloading model")
                unload("空闲 $minutes 分钟自动卸载")
            }
        }
    }

    private companion object {
        const val TAG = "EngineManager"
        const val RES_TAG = "IrisResource"
        /** 1.7.7: end-of-reply reason + thinking / answer tokens. */
        const val GEN_TAG = "IrisGen"
    }
}

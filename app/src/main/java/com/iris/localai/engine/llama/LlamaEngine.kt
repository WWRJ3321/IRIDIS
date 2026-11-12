package com.iris.localai.engine.llama

import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import com.iris.localai.engine.EngineException
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.InferenceEngine
import com.iris.localai.engine.LoadConfig
import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.engine.StopReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

/**
 * GGUF models through llama.cpp. Every native call except [cancel] runs on one dedicated
 * thread, so load / generate / unload can never race each other.
 *
 * Cancellation is per request: a stop issued while the request is still queued (behind another
 * generation, or before the native call started) is never lost, and a stale stop can never
 * cancel the *next* request.
 */
class LlamaEngine(private val context: android.content.Context? = null) : InferenceEngine {

    /** 2.2.15: which native library this engine drives (switched to [LlamaJnis.htp] for an NPU load). */
    @Volatile private var jni: LlamaJni = LlamaJnis.cpuGpu

    private val json = Json { ignoreUnknownKeys = true }

    /** Guards [handle] against destroy-while-cancel (cancel runs on arbitrary threads). */
    private val handleLock = Any()
    @Volatile private var handle = 0L
    @Volatile private var loaded = false
    /** 2.2.17: a projector is loaded for the current model. */
    @Volatile private var visionLoaded = false
    /** 2.5.0-beta9: MTP speculative decoding is on for the loaded model (native status "active …"). */
    @Volatile private var mtpActive = false
    @Volatile private var active: Request? = null

    // ---- 1.7.2: mid-reply resource-tier switch (CPU backend only) ----
    /** Backend the loaded model really runs on. */
    @Volatile private var activeBackend: com.iris.localai.engine.Backend = com.iris.localai.engine.Backend.CPU
    /** Plan the running reply currently uses (start plan, then the last live switch). */
    @Volatile private var runningPlan: com.iris.localai.engine.ResourcePlan? = null
    /** Java-side thread priority still to apply on the decoding thread (also updates Android's task profile). */
    private val pendingPriority = com.iris.localai.engine.PendingLive<Int>()

    private class Request {
        @Volatile var cancelled = false
    }

    private fun ensureHandle(npu: Boolean = false): Long {
        LlamaNative.unavailableReason?.let { throw EngineException(it) }
        val want = if (npu) {
            val ctx = context ?: throw EngineException(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_fabc89))
            LlamaHtpRuntime.ensureLoaded(ctx)?.let { throw EngineException(it) }
            LlamaJnis.htp
        } else LlamaJnis.cpuGpu
        synchronized(handleLock) {
            if (handle != 0L && jni !== want) { runCatching { jni.unload(handle); jni.destroy(handle) }; handle = 0L }
            jni = want
            if (handle == 0L) handle = jni.create()
            return handle
        }
    }

    override suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails =
        withContext(nativeThread) {
            val npu = config.backend == com.iris.localai.engine.Backend.NPU
            val h = ensureHandle(npu)
            loaded = false
            visionLoaded = false
            com.iris.localai.engine.NativeThreadPolicy.apply(config.resources)
            // 2.2.15: NPU = libiris_lhtp.so, where the only offload device is the Hexagon HTP ("GPU" type in ggml).
            val gpu = config.backend == com.iris.localai.engine.Backend.GPU || npu
            val path = model.path.toByteArray(Charsets.UTF_8)
            val listener = LoadProgressListener { p -> onProgress(p); true }
            // 2.5.0-beta9: MTP is set up inside the load; any failure there falls back to normal decoding.
            jni.setMtp(h, if (npu) 0 else config.mtpDraft.coerceIn(0, 8))
            // 2.5.1: MoE 专家按需加载（专家留 CPU、mmap 不预读）+ 激活专家数
            jni.setMoe(h, config.moeCpu, config.expertUsed.coerceIn(0, 32))
            val err = jni.load(
                h, path, config.contextLength, config.threads, config.batchSize.coerceIn(32, 2048),
                config.flashAttention, if (gpu) GPU_ALL_LAYERS else 0,
                if (config.flashAttention) config.kvCacheType.coerceIn(0, 2) else 0, config.useMmap, listener,
            )
            if (err != null) throw EngineException(err)
            loaded = true
            val mtp = if (config.mtpDraft > 0 && !npu) jni.mtpStatus(h) else "off"
            mtpActive = MtpStats.active(mtp)
            if (config.mtpDraft > 0) android.util.Log.i(LOG_TAG, "MTP ${model.fileName}: $mtp")
            val accel = if (npu) com.iris.localai.engine.Backend.NPU else com.iris.localai.engine.Backend.GPU
            activeBackend = if (gpu) accel else com.iris.localai.engine.Backend.CPU
            val m = runCatching { json.decodeFromString(NativeMeta.serializer(), jni.meta(h)) }
                .getOrDefault(NativeMeta())
            // No OpenCL device: llama.cpp silently stays on the CPU. Report it as a GPU failure so
            // the manager's fallback chain (and the UI's "实际后端") tell the truth.
            if (gpu && m.gpu.isBlank()) {
                loaded = false
                jni.unload(h)
                throw EngineException(if (npu) com.iris.localai.I18n.s(com.iris.localai.R.string.engine_1ac99d) else com.iris.localai.I18n.s(com.iris.localai.R.string.engine_4b134d))
            }
            // 2.2.17: vision projector via mtmd (GPU when the model is on the GPU, CPU otherwise / on failure).
            // A projector that fails to load leaves the model text-only; it never fails the load.
            var vision = false
            var visionNote: String? = null
            config.mmprojPath?.let { mp ->
                // 2.4.4: never on Adreno's OpenCL — the vision encoder there could hang the GPU and freeze
                // the whole phone (群友 iQOO Neo11, 9B + mmproj). CPU encodes one picture in seconds.
                // 2.4.5: 设置 › 模型与加载「识图用 GPU」 lifts that rule (off by default).
                val projGpu = VisionGpu.useGpu(gpu, npu, m.gpu, config.mmprojGpu)
                val e = runCatching { jni.loadMmproj(h, mp.toByteArray(Charsets.UTF_8), projGpu) }
                    .getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.engine_f7cffe, it.message ?: it.javaClass.simpleName) }
                if (e == null) vision = true
                else { visionNote = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_17c8ba, e); android.util.Log.w(LOG_TAG, "mmproj ${java.io.File(mp).name}: $e") }
            }
            visionLoaded = vision
            val vm = if (vision) runCatching { json.decodeFromString(NativeMeta.serializer(), jni.meta(h)) }.getOrDefault(m) else m
            ModelDetails(
                description = m.description,
                parameterCount = m.nParams,
                contextLength = m.nCtx,
                trainContextLength = m.nCtxTrain,
                supportsThinking = m.supportsThinking,
                thinkingPrefilled = m.thinkingPrefilled,
                hasChatTemplate = m.hasChatTemplate,
                supportsVision = vision && vm.vision,
                fallbackNote = visionNote,
                backend = when {
                    m.gpu.isBlank() -> "CPU · llama.cpp"
                    npu -> "NPU · ${m.gpu} · llama.cpp Hexagon"
                    else -> "GPU · ${m.gpu} · OpenCL"
                } + (if (mtpActive) " · MTP" else "") + if (vision && vm.vision) com.iris.localai.I18n.s(com.iris.localai.R.string.engine_fba350) + (if (vm.visionGpu) "GPU" else "CPU") else "",
                activeBackend = if (m.gpu.isNotBlank()) accel else com.iris.localai.engine.Backend.CPU,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = channelFlow {
        if (!loaded) {
            send(GenEvent.Done(StopReason.ERROR, null, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5d0468)))
            return@channelFlow
        }
        val req = Request()
        active = req
        val channel = com.iris.localai.engine.ChannelThink()
        // The native call blocks and cannot be interrupted; if the collector goes away (e.g. during
        // a long prefill that emits nothing), ask native code to stop this request.
        invokeOnClose { cause -> if (cause != null) cancelRequest(req) }
        // Encode on the caller's thread so the single native thread only runs native work.
        val roles = Array(history.size) { history[it].role.toByteArray(Charsets.UTF_8) }
        val contents = Array(history.size) { history[it].content.toByteArray(Charsets.UTF_8) }
        // 2.2.17: pictures of one message (the newest that has any) go through mtmd.
        val imageMsg = if (visionLoaded) MmprojPairing.imageMessage(history.map { it.images }) else -1
        val images = if (imageMsg >= 0) Array(history[imageMsg].images.size) { history[imageMsg].images[it].toByteArray(Charsets.UTF_8) } else null
        val floats = floatArrayOf(
            params.temperature, params.topP, params.minP, params.repeatPenalty,
            params.frequencyPenalty, params.presencePenalty,
        )
        val ints = intArrayOf(
            params.topK, params.repeatLastN, params.maxNewTokens, params.seed /* -1 = random */,
            if (params.disableThinking) 1 else 0, 1, /* honour a cancel issued before the call */
            if (params.contextShift) 1 else 0, params.keepTokens,
        )
        val result = try {
            withContext(nativeThread) {
                val h = handle
                if (h == 0L || !loaded) return@withContext null
                // Order matters (see cancelRequest): clear the native flag first, then look at
                // our own flag. A cancel that lands after the check sets the native flag again.
                jni.clearCancel(h)
                if (req.cancelled) return@withContext CANCELLED_JSON
                if (params.threads > 0) jni.setThreads(h, params.threads)
                // ggml creates its worker threads per graph from this thread: they inherit the
                // tier's cores and priority, so a tier change applies from the next reply on.
                com.iris.localai.engine.NativeThreadPolicy.apply(params.resources)
                pendingPriority.clear()
                runningPlan = params.resources
                com.iris.localai.engine.ThermalGovernor.beginReply()
                val sink = PieceSink { bytes ->
                    // Runs on this (decoding) thread between two llama_decode calls: the native side
                    // applies threads / cores / nice right before the next decode; the Java priority
                    // call additionally moves Android's task profile (background ↔ foreground).
                    pendingPriority.take()?.let { nice ->
                        val ok = runCatching { android.os.Process.setThreadPriority(nice) }.isSuccess
                        android.util.Log.i(LOG_TAG, "decode thread tid=${android.os.Process.myTid()} " +
                            "Java priority -> ${android.os.Process.getThreadPriority(android.os.Process.myTid())}" +
                            if (ok) "" else " (setThreadPriority failed)")
                    }
                    com.iris.localai.engine.ThermalGovernor.pace { req.cancelled }
                    // Unlimited buffer: trySend only fails once the collector is gone → stop generating.
                    !req.cancelled && channel.push(String(bytes, Charsets.UTF_8)).let { t -> t.isEmpty() || trySend(GenEvent.Piece(t)).isSuccess }
                }
                if (images != null) jni.generateVision(h, roles, contents, floats, ints, images, imageMsg, sink)
                else jni.generate(h, roles, contents, floats, ints, sink)
            }
        } finally {
            if (active === req) active = null
            runningPlan = null
        }
        channel.push("", final = true).takeIf { it.isNotEmpty() }?.let { send(GenEvent.Piece(it)) }
        if (result == null) {
            send(GenEvent.Done(StopReason.ERROR, null, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5d0468)))
            return@channelFlow
        }
        val s = runCatching { json.decodeFromString(NativeStats.serializer(), result) }.getOrDefault(NativeStats())
        val reported = when (s.stop) {
            0 -> StopReason.END
            1 -> StopReason.MAX_TOKENS
            2 -> StopReason.CANCELLED
            3 -> StopReason.CONTEXT_FULL
            else -> StopReason.ERROR
        }
        // 1.7.8: a limit set by the remaining context is CONTEXT_FULL, not LENGTH (see LengthRule).
        val reason = com.iris.localai.engine.LengthRule.reason(reported, s.generatedTokens, params.maxNewTokens, params.contextShift)
        val stats = GenerationStats(
            promptTokens = s.promptTokens,
            reusedTokens = s.reusedTokens,
            generatedTokens = s.generatedTokens,
            prefillMs = s.prefillUs / 1000,
            generateMs = s.generateUs / 1000,
            droppedMessages = s.droppedMessages,
            shiftedTokens = s.shiftedTokens,
            kvUsed = s.kvUsed,
        )
        MtpStats.logLine(s.draftedTokens, s.acceptedTokens, s.generatedTokens, s.generateUs)?.let { android.util.Log.i(LOG_TAG, it) }
        // 2.5.0-beta10: a draft / verify / rollback error turns MTP off natively for this session (GPU is experimental).
        if (mtpActive) {
            val h = handle
            val now = if (h != 0L) jni.mtpStatus(h) else "off"
            if (!MtpStats.active(now)) {
                mtpActive = false
                android.util.Log.w(LOG_TAG, "MTP turned off for this session: $now")
            }
        }
        if (s.liveSwitches > 0) android.util.Log.i(LOG_TAG, "reply finished with ${s.liveSwitches} live switch(es) applied by the decoder")
        val error = s.error.ifBlank { null } ?: when (reason) {
            StopReason.ERROR -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_c95271)
            StopReason.CONTEXT_FULL -> com.iris.localai.engine.LengthRule.contextFullMessage(s.generatedTokens, params.maxNewTokens)
            else -> null
        }
        send(GenEvent.Done(reason, stats, error))
    }.buffer(Channel.UNLIMITED)

    private fun cancelRequest(req: Request) {
        req.cancelled = true
        // Only touch native state if this request is (or is about to become) the running one.
        if (active === req) nativeCancel()
    }

    private fun nativeCancel() {
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) jni.cancel(h)
        }
    }

    override fun liveApplyMode(): com.iris.localai.engine.LiveApply =
        if (activeBackend == com.iris.localai.engine.Backend.CPU) com.iris.localai.engine.LiveApply.IMMEDIATE
        else com.iris.localai.engine.LiveApply.NEXT_REPLY

    /**
     * 1.7.2: hands a new tier to the running reply. Never blocks and never calls into llama while a
     * decode runs: the request is parked and the decoding thread applies it before its next decode.
     */
    override fun applyResourcesLive(plan: com.iris.localai.engine.ResourcePlan): Boolean {
        if (!loaded || active == null || liveApplyMode() != com.iris.localai.engine.LiveApply.IMMEDIATE) return false
        val change = com.iris.localai.engine.LiveResourcePolicy.liveChange(runningPlan, plan) ?: return true
        synchronized(handleLock) {
            val h = handle
            if (h == 0L) return false
            pendingPriority.offer(change.nice)
            jni.requestLivePolicy(h, change.threads, change.cpus.toIntArray(), change.nice)
        }
        android.util.Log.i(LOG_TAG, "live switch requested: ${plan.tier.label} threads=${change.threads} " +
            "cpus=${if (change.cpus.isEmpty()) "all" else change.cpus.joinToString(",")} nice=${change.nice}" +
            com.iris.localai.engine.LiveResourcePolicy.deferredParts(runningPlan, plan, com.iris.localai.engine.LiveApply.IMMEDIATE)
                .takeIf { it.isNotEmpty() }?.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b50566), prefix = "; next reply only: ").orEmpty())
        runningPlan = plan
        return true
    }

    override fun cancel() {
        val req = active
        if (req != null) cancelRequest(req) else nativeCancel()
    }

    /** iris_engine.cpp generate(): longest common token prefix kept, the rest removed and re-decoded. */
    override val reusesCommonPrefix: Boolean get() = true

    override suspend fun resetContext() = withContext(nativeThread) {
        val h = handle
        if (h != 0L) jni.reset(h)
    }

    /** Frees the model *and* the native engine object; a later [load] creates a fresh one. */
    override suspend fun unload() {
        cancel()
        withContext(nativeThread) {
            loaded = false
            val h = handle
            if (h != 0L) {
                jni.unload(h)
                synchronized(handleLock) {
                    handle = 0L
                    jni.destroy(h)
                }
            }
        }
    }

    @Serializable
    private data class NativeMeta(
        val description: String = "",
        val architecture: String = "",
        val gpu: String = "",
        val nParams: Long = 0,
        val sizeBytes: Long = 0,
        val nCtxTrain: Int = 0,
        val nCtx: Int = 0,
        val hasChatTemplate: Boolean = false,
        val supportsThinking: Boolean = false,
        val thinkingPrefilled: Boolean = false,
        val vision: Boolean = false,
        val visionGpu: Boolean = false,
    )

    @Serializable
    private data class NativeStats(
        val promptTokens: Int = 0,
        val reusedTokens: Int = 0,
        val prefillUs: Long = 0,
        val generatedTokens: Int = 0,
        val generateUs: Long = 0,
        val droppedMessages: Int = 0,
        val shiftedTokens: Int = 0,
        val kvUsed: Int = 0,
        val liveSwitches: Int = 0,
        /** 2.5.0-beta9: MTP draft tokens verified / accepted in this reply (0 without MTP). */
        val draftedTokens: Int = 0,
        val acceptedTokens: Int = 0,
        val stop: Int = 4,
        val error: String = "",
    )

    private companion object {
        const val GPU_ALL_LAYERS = 999
        const val LOG_TAG = "IrisResource"
        const val CANCELLED_JSON = """{"stop":2}"""

        /**
         * One native thread for every LlamaEngine instance. EngineManager creates a new engine per
         * model load; a per-instance executor leaked one thread per model switch.
         */
        val nativeThread by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "iris-llama").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }
}


/** 2.4.4: where the vision projector (mtmd) runs. Pure, unit-tested. */
object VisionGpu {
    /** The GPU llama.cpp reports is a Qualcomm Adreno (OpenCL backend): the projector stays on the CPU. */
    fun adrenoOpenCl(gpuName: String?): Boolean = gpuName.orEmpty().let { it.contains("adreno", ignoreCase = true) || it.contains("qualcomm", ignoreCase = true) }

    /**
     * 2.4.5: does the projector go to the GPU? Only when the model itself is on the GPU (not NPU); on
     * Adreno only when the user turned on 识图用 GPU ([allowAdreno]).
     */
    fun useGpu(modelOnGpu: Boolean, npu: Boolean, gpuName: String?, allowAdreno: Boolean): Boolean =
        modelOnGpu && !npu && (allowAdreno || !adrenoOpenCl(gpuName))
}

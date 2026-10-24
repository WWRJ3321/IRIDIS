package com.iris.localai.engine.mnn

import android.content.Context
import android.util.Log
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import com.iris.localai.engine.EngineException
import com.iris.localai.engine.MnnKleidiPolicy
import com.iris.localai.engine.NativeLoadCrash
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.InferenceEngine
import com.iris.localai.engine.LoadConfig
import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.engine.StopReason
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * 1.7.8: what goes into MNN's `thread_num`. On the CPU it is the worker count; on OpenCL MNN reads it
 * as GPU mode bits (llm.cpp:211-215 ORs 64|512; OpenCLBackend.cpp:1659-1683 decodes the tuning
 * level), so it must be a fixed mode, never the tier's thread count. Unit-tested in V178Test.
 */
object MnnThreadArg {
    /** MNN_GPU_TUNING_WIDE (MNNForwardType.h) — with llm.cpp's BUFFER bit = the documented 68. */
    const val OPENCL_MODE_WIDE = 4
    fun forBackend(useGpu: Boolean, threads: Int): Int = if (useGpu) OPENCL_MODE_WIDE else threads.coerceIn(1, 8)
    /** Tuning level MNN derives from a thread_num on OpenCL (for logs/tests): the single bit set, or null if 0 / several. */
    fun openClTuning(threadNum: Int): String? {
        val bits = listOf(1 to "NONE", 2 to "HEAVY", 4 to "WIDE", 8 to "NORMAL", 16 to "FAST").filter { threadNum and it.first != 0 }
        return bits.singleOrNull()?.second
    }
}

/**
 * MNN-LLM models (taobao-mnn folders: config.json + llm.mnn + llm.mnn.weight + tokenizer.txt).
 *
 * Mirrors [com.iris.localai.engine.llama.LlamaEngine]: every native call except cancel runs on
 * one dedicated thread; cancellation is per request. Multi-turn KV reuse comes from MNN's prompt
 * cache (the native side keeps it in step with the history the app sends back), the model's own
 * Jinja chat template renders the conversation, and `enable_thinking` follows 深度思考.
 *
 * Backends: CPU (ARMv8.2 FP16 + KleidiAI) or GPU (Adreno, OpenCL). An OpenCL load that fails falls
 * back to CPU; an OpenCL load that crashed the process is remembered per model and skipped.
 */
class MnnEngine(private val context: Context) : InferenceEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val handleLock = Any()
    @Volatile private var handle = 0L
    @Volatile private var loaded = false
    @Volatile private var visionLoaded = false
    @Volatile private var active: Request? = null
    private var modelId = ""
    private var backendLabel = ""
    @Volatile private var onCpu = false
    @Volatile private var loadedThreads = 0
    /** 1.7.5: cores + nice the MNN threads have now (set at load, by live re-pins and per reply). */
    @Volatile private var live: com.iris.localai.engine.LiveChange? = null

    private class Request { @Volatile var cancelled = false }

    /** Named JNI callback (never a lambda — see [MnnPieceSink]). */
    private class MnnSink(
        private val req: Request,
        private val out: SendChannel<GenEvent>,
    ) : MnnPieceSink {
        override fun onPiece(utf8: ByteArray): Boolean {
            com.iris.localai.engine.ThermalGovernor.pace { req.cancelled }
            return !req.cancelled && out.trySend(GenEvent.Piece(String(utf8, Charsets.UTF_8))).isSuccess
        }
    }

    private fun newHandle(): Long {
        MnnNative.unavailableReason?.let { throw EngineException(it) }
        synchronized(handleLock) {
            if (handle == 0L) handle = MnnNative.nativeCreate()
            if (handle == 0L) throw EngineException("MNN 引擎创建失败（内存不足？）")
            return handle
        }
    }

    private fun destroyHandle() {
        val h = handle
        if (h == 0L) return
        MnnNative.nativeUnload(h)
        synchronized(handleLock) {
            handle = 0L
            MnnNative.nativeDestroy(h)
        }
    }

    override suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails =
        withContext(nativeThread) {
            // 2.0.5: load libiris_mnn before the OpenCL probe. The probe used to run first, so on a
            // fresh process it hit UnsatisfiedLinkError and every GPU pick fell back to CPU.
            MnnNative.unavailableReason?.let { throw EngineException(it) }
            loaded = false
            modelId = model.fileName
            val dir = File(model.path)
            val cfg = File(dir, "config.json")
            if (!cfg.isFile || !File(dir, "llm.mnn").isFile) {
                throw EngineException("MNN 模型文件夹不完整：需要 config.json 与 llm.mnn（请下载仓库里的全部文件）")
            }
            onProgress(0.05f)
            val threads = config.threads.coerceIn(1, 8)
            val ctx = config.contextLength.coerceIn(512, 32768)
            val cacheDir = File(context.cacheDir, "mnn/${model.fileName.hashCode().toUInt().toString(16)}").apply { mkdirs() }

            val useGpu = when (config.backend) {
                com.iris.localai.engine.Backend.NPU -> throw EngineException("此版本的 MNN 未编入 NPU（QNN）后端")
                com.iris.localai.engine.Backend.GPU -> {
                    // 2.0.4: say why (dlerror) instead of a bare "no OpenCL".
                    val why = runCatching { MnnNative.nativeOpenClProbe() }.getOrElse { it.message ?: "probe failed" }
                    if (why.isNotEmpty()) throw EngineException("本机没有可用的 OpenCL（${why.take(300)}）")
                    true
                }
                com.iris.localai.engine.Backend.CPU -> false
            }
            // 1.7.4: MNN creates its worker pool inside nativeLoad; the native side applies the
            // tier's cores + nice to this thread just for that call (the workers inherit them) and
            // restores this thread afterwards. Java priority is not touched any more.
            val plan = config.resources
            // 1.7.8: on OpenCL MNN does not read thread_num as a thread count: Llm::initRuntime
            // (llm.cpp:211-215) passes it as ScheduleConfig.numThread | 64 | 512, and
            // OpenCLBackend (OpenCLBackend.cpp:1659-1683) decodes it as the GPU mode bits
            // (MNNForwardType.h: 1 TUNING_NONE, 2 HEAVY, 4 WIDE, 8 NORMAL, 16 FAST). ≤ 1.7.7 sent the
            // tier's thread count: 全部 = 8 → NORMAL tuning (the user's slowest MNN-GPU tier),
            // 省电 = 1 → no tuning at all, 中/高 = 2 → HEAVY. MNN's docs (llm.md: "OpenCL 推理时使用 68")
            // ask for 68 = BUFFER | WIDE; llm.cpp already ORs BUFFER, so 4 gives exactly that, on
            // every tier. The power hint only drives MNN's own core binding of its CPU runtime,
            // which would override our pinning of the host thread → always "normal" on the GPU.
            val power = if (useGpu) "normal" else plan?.mnnPower ?: "normal"
            val nativeThreads = MnnThreadArg.forBackend(useGpu, threads)
            // 1.7.9: the tier's GPU 性能提示 → Adreno cl_qcom_perf_hint of the OpenCL context (IRIS MNN
            // patch, OpenCLRuntime.cpp). Separate from power: it binds no cores and keeps thread_num=4.
            val hintArg = if (useGpu) "|" + (plan?.gpuHint?.native ?: "normal") else ""
            // Loading is one blocking native call; creep the bar so the UI never looks frozen.
            val ticker = Thread {
                var p = 0.05f
                try { while (true) { Thread.sleep(300); p += (0.9f - p) * 0.05f; onProgress(p) } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            try {
                val backend = if (useGpu) "opencl" else "cpu"
                // Oryon dies in KleidiAI::initKernelInfo during Llm::load (SIGSEGV / SIGILL in nativeLoad).
                // A previous nativeLoad death turns it off on any SoC for this attempt.
                val kleidiOn = !useGpu && MnnKleidiPolicy.enabled(
                    android.os.Build.SOC_MODEL,
                    NativeLoadCrash.traceShowsLoad(com.iris.localai.util.CrashReporter.nativeCrashText(context)),
                )
                if (!useGpu && !kleidiOn) Log.i(TAG, "MNN KleidiAI off (soc=${android.os.Build.SOC_MODEL})")
                val kleidiArg = if (useGpu) "" else "|kleidi=${if (kleidiOn) 1 else 0}"
                val h = newHandle()
                // 1.8.0: per-stage load logging to <files>/iris_mnn_load.log (export button reads it).
                runCatching { MnnNative.nativeSetDiagDir(context.getExternalFilesDir(null)?.path?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)) }
                // 1.8.0 crash-diag: 禁用视觉模块 switch → libMNN createLLM probe (strong override in mnn_engine.cpp).
                runCatching { MnnNative.nativeSetNoVisual(config.noVisual) }
                val err = MnnNative.nativeLoad(
                    h, cfg.path.toByteArray(Charsets.UTF_8), "$backend|$power$hintArg$kleidiArg", nativeThreads, ctx,
                    cacheDir.path.toByteArray(Charsets.UTF_8), config.useMmap,
                    (plan?.let { com.iris.localai.engine.LiveResourcePolicy.coresFor(it, threads) } ?: emptyList()).toIntArray(),
                    plan?.nice ?: 0, plan != null,
                )
                if (err != null) {
                    destroyHandle()
                    throw EngineException(err)
                }
                loaded = true
                onCpu = !useGpu
                loadedThreads = threads
                live = if (plan != null && !useGpu) com.iris.localai.engine.LiveChange(
                    threads, com.iris.localai.engine.LiveResourcePolicy.coresFor(plan, threads), plan.nice,
                ) else null
                backendLabel = if (useGpu) "GPU · OpenCL · MNN" else "CPU · MNN"
            } finally {
                ticker.interrupt()
            }
            val m = runCatching { json.decodeFromString(NativeMeta.serializer(), MnnNative.nativeMeta(handle)) }
                .getOrDefault(NativeMeta())
            onProgress(1f)
            visionLoaded = !config.noVisual && File(dir, "visual.mnn").isFile
            ModelDetails(
                description = model.displayName,
                parameterCount = 0,
                contextLength = ctx,
                trainContextLength = 0,
                supportsThinking = m.supportsThinking,
                // 1.10.12: Qwen-VL / Omni folders ship visual.mnn; MNN reads <img>path</img> in the prompt.
                supportsVision = visionLoaded,
                visionMaxSide = com.iris.localai.util.Attachments.MNN_VISION_MAX_SIDE,
                thinkingPrefilled = m.thinkingPrefilled,
                hasChatTemplate = m.hasChatTemplate,
                backend = backendLabel,
                activeBackend = config.backend,
                gpuHint = if (useGpu) m.gpuHint.ifEmpty { "none" } else null,
            )
        }

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = channelFlow {
        if (!loaded) {
            send(GenEvent.Done(StopReason.ERROR, null, "模型未加载"))
            return@channelFlow
        }
        val req = Request()
        active = req
        invokeOnClose { cause -> if (cause != null) cancelRequest(req) }
        val roles = Array(history.size) { history[it].role.toByteArray(Charsets.UTF_8) }
        val visionOk = visionLoaded
        val contents = Array(history.size) { i ->
            val m = history[i]
            val imgs = if (visionOk) m.images.filter { File(it).isFile } else emptyList()
            (imgs.joinToString("") { "<img>$it</img>" } + m.content).toByteArray(Charsets.UTF_8)
        }
        val floats = floatArrayOf(params.temperature, params.topP, params.minP, params.repeatPenalty)
        val plan = params.resources
        val ints = intArrayOf(
            params.topK, params.maxNewTokens, if (params.disableThinking) 1 else 0,
            plan?.nice ?: 0, if (plan != null) 1 else 0,
        )
        // 1.7.6: cores for the *loaded* thread count — widened onto neighbouring non-prime cores
        // when the tier has fewer cores than threads, never released onto the prime cores.
        val genCores = plan?.let { com.iris.localai.engine.LiveResourcePolicy.coresFor(it, loadedThreads) } ?: emptyList()
        val cpus = genCores.toIntArray()
        // The native side re-applies this reply's policy to the pool workers.
        if (plan != null && onCpu) live = com.iris.localai.engine.LiveChange(loadedThreads, genCores, plan.nice)
        // 2.1.1: the native call runs detached and is awaited cancellably. Prefill (incl. the visual
        // encoder) is one blocking MNN call that cannot be interrupted; with withContext the stop
        // button waited for all of it. Now a stop ends this request at once; the native side stops at
        // its first cancel check after prefill, and the next request queues behind it on nativeThread.
        val call = kotlinx.coroutines.CompletableDeferred<String?>()
        val sink = MnnSink(req, channel)
        nativeScope.launch {
            val r = runCatching {
                val h = handle
                if (h == 0L || !loaded) null
                else {
                    MnnNative.nativeClearCancel(h)
                    if (req.cancelled) CANCELLED_JSON
                    else {
                        com.iris.localai.engine.ThermalGovernor.beginReply()
                        MnnNative.nativeGenerate(h, roles, contents, floats, ints, cpus, sink)
                    }
                }
            }
            if (active === req) active = null
            r.fold({ call.complete(it) }, { call.completeExceptionally(it) })
        }
        val result = try {
            call.await()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            cancelRequest(req)
            throw ce
        }
        if (result == null) {
            send(GenEvent.Done(StopReason.ERROR, null, "模型未加载"))
            return@channelFlow
        }
        val s = runCatching { json.decodeFromString(NativeStats.serializer(), result) }.getOrDefault(NativeStats())
        val reason = when (s.stop) {
            0 -> StopReason.END
            1 -> StopReason.MAX_TOKENS
            2 -> StopReason.CANCELLED
            3 -> StopReason.CONTEXT_FULL
            else -> StopReason.ERROR
        }
        val stats = GenerationStats(
            promptTokens = s.promptTokens,
            reusedTokens = s.reusedTokens,
            generatedTokens = s.generatedTokens,
            prefillMs = s.prefillUs / 1000,
            generateMs = s.generateUs / 1000,
            droppedMessages = s.droppedMessages,
        )
        if (s.visionUs > 0) Log.i(TAG, "vision encoder ${s.visionUs / 1000} ms, prefill ${s.prefillUs / 1000} ms")
        val error = s.error.ifBlank { null } ?: if (reason == StopReason.ERROR) "推理失败" else null
        send(GenEvent.Done(reason, stats, error))
    }.buffer(Channel.UNLIMITED)

    override fun liveApplyMode(): com.iris.localai.engine.LiveApply =
        if (onCpu) com.iris.localai.engine.LiveApply.LIVE_POLICY else com.iris.localai.engine.LiveApply.RELOAD

    /**
     * 1.7.5: moves MNN's existing threads (pool workers recorded at load + the generating thread
     * while a reply runs) to the tier's cores / nice. Never blocks on the generating thread: the
     * native call only does sched_setaffinity / setpriority on tids. True only when the kernel
     * accepted both for every live thread.
     */
    override fun applyResourcesLive(plan: com.iris.localai.engine.ResourcePlan): Boolean {
        if (!loaded || !onCpu) return false
        // 1.7.6: the loaded thread count stays until the idle reload; with fewer target cores than
        // threads they go to the target + neighbouring non-prime cores (中→省电: cpu4-5), never all.
        val liveCores = com.iris.localai.engine.LiveResourcePolicy.coresFor(plan, loadedThreads)
        val json = synchronized(handleLock) {
            val h = handle
            if (h == 0L) return false
            MnnNative.nativeApplyLive(h, liveCores.toIntArray(), plan.nice)
        }
        val r = runCatching { this.json.decodeFromString(LiveReport.serializer(), json) }.getOrNull() ?: return false
        val ok = r.targets > 0 && r.affinityOk == r.targets && r.niceOk == r.targets
        Log.i(RES_TAG, "MNN live re-pin ${plan.tier.label}: ${if (ok) "APPLIED" else "INCOMPLETE"} " +
            "threads=${r.targets} affinity=${r.affinityOk} nice=${r.niceOk} gone=${r.gone} generating=${r.generating}" +
            (if (liveCores != plan.cpus) " (cores ${liveCores.joinToString(",")}: tier has ${plan.cpus.size} cores < $loadedThreads threads, prime cores excluded)" else "") +
            " | ${r.readback}" + if (plan.threads != loadedThreads) " | thread count ${loadedThreads}→${plan.threads} needs an idle reload" else "")
        if (ok) live = com.iris.localai.engine.LiveChange(
            loadedThreads, liveCores, plan.nice,
        )
        return ok
    }

    override fun livePolicy(): com.iris.localai.engine.LiveChange? = if (loaded && onCpu) live else null

    override fun resourceThreadIds(): IntArray = synchronized(handleLock) {
        val h = handle
        if (h == 0L || !loaded) IntArray(0) else runCatching { MnnNative.nativeThreadIds(h) }.getOrDefault(IntArray(0))
    }

    private fun cancelRequest(req: Request) {
        req.cancelled = true
        if (active === req) nativeCancel()
    }

    private fun nativeCancel() {
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) MnnNative.nativeCancel(h)
        }
    }

    override fun cancel() {
        val req = active
        if (req != null) cancelRequest(req) else nativeCancel()
    }

    override suspend fun resetContext() = withContext(nativeThread) {
        val h = handle
        if (h != 0L) MnnNative.nativeReset(h)
    }

    override suspend fun unload() {
        cancel()
        withContext(nativeThread) {
            loaded = false
            destroyHandle()
        }
    }

    @Serializable
    private data class NativeMeta(
        val supportsThinking: Boolean = false,
        val thinkingPrefilled: Boolean = false,
        val hasChatTemplate: Boolean = true,
        val nCtx: Int = 0,
        val backend: String = "",
        val gpuHint: String = "",
    )

    @Serializable
    private data class LiveReport(
        val targets: Int = 0,
        val affinityOk: Int = 0,
        val niceOk: Int = 0,
        val gone: Int = 0,
        val generating: Boolean = false,
        val coresWidened: Boolean = false,
        val readback: String = "",
    )

    @Serializable
    private data class NativeStats(
        val promptTokens: Int = 0,
        val reusedTokens: Int = 0,
        val generatedTokens: Int = 0,
        val prefillUs: Long = 0,
        val generateUs: Long = 0,
        val droppedMessages: Int = 0,
        val visionUs: Long = 0,
        val stop: Int = 4,
        val error: String = "",
    )

    private companion object {
        const val TAG = "MnnEngine"
        const val RES_TAG = "IrisResource"
        const val CANCELLED_JSON = """{"stop":2}"""
        /** 2.1.1: detached native generate calls (see [generate]); still serialised on [nativeThread]. */
        val nativeScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + nativeThread) }
        /** One native thread for all MnnEngine instances (a new engine is created per load). */
        val nativeThread by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "iris-mnn").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }
}

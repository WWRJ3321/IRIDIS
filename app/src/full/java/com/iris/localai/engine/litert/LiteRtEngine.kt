package com.iris.localai.engine.litert

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * .litertlm models through Google's LiteRT-LM runtime (Gemma 4, Qwen3 / Qwen3.5, LFM2.5, …).
 *
 * A LiteRT-LM [Conversation] is stateful and keeps its own KV cache. When the incoming history
 * extends what the live conversation has already seen (the normal "next turn" case) only the new
 * user message is sent. Anything else — another chat, an edited or regenerated message, changed
 * sampling — opens a fresh conversation seeded with the earlier turns as `initialMessages`, so
 * the model's own chat template renders them (no hand-built transcript).
 *
 * Reasoning streamed on a side channel (Gemma 4 / Qwen thinking) is re-wrapped in <think> tags so
 * the shared think-splitter in the UI and API layers handles it like llama.cpp output.
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LiteRtEngine(private val context: Context) : InferenceEngine {

    private var engine: Engine? = null
    @Volatile private var visionOn = false
    private var conversation: Conversation? = null
    private var conversationKey: ConvKey? = null
    /** Set after the model's own chat template failed; reset on every load. */
    @Volatile private var chatTemplateOverride: String? = null
    @Volatile private var conversationHistory: List<PromptMessage>? = null
    @Volatile private var live: Conversation? = null
    private var maxTokens = 4096
    private var backend = ""
    private val isNpu: Boolean get() = backend.startsWith("NPU")

    private data class ConvKey(val params: SamplingConfig, val system: String)

    override suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails =
        withContext(worker) {
            closeAll()
            chatTemplateOverride = null
            onProgress(0.05f)
            val npuFile = NpuModels.targetSoc(model.fileName) != null
            val npuKv = if (npuFile) NpuModels.kvCapacity(model.fileName) else null
            // NPU graphs have a fixed KV size; never request more than they were compiled with.
            // 1.10.2: one pure rule (LiteRtLoadKey) — only the file and its 上下文长度, so the cache stays valid.
            // 2.0: files without _ekvNNNN get their family's compiled KV size (Gemma 4 NPU: 4096), not 1024;
            // LiteRT-LM 0.18 does not report the KV size after load, so the file name is all we have.
            maxTokens = com.iris.localai.engine.LiteRtLoadKey.maxNumTokens(config.contextLength, npuFile, npuKv, com.iris.localai.engine.NpuIds.defaultKv(model.fileName))
            val cacheRoot = java.io.File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            migrateOldCacheOnce(cacheRoot)
            val modelFile = java.io.File(model.path)
            fun snapshot(): com.iris.localai.engine.CacheSnapshot? =
                if (config.backend == com.iris.localai.engine.Backend.NPU) null else runCatching {
                    com.iris.localai.engine.LiteRtCacheFiles.snapshot(
                        cacheRoot.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() },
                        model.fileName, modelFile.lastModified(), modelFile.length(), config.backend,
                    )
                }.getOrNull()
            val cacheBefore = snapshot()
            // 1.10.3: the whole cache dir for this model, before / after initialize and after close (adb logcat -s IrisLoad).
            fun logCache(stage: String) {
                if (config.backend == com.iris.localai.engine.Backend.NPU) return
                runCatching {
                    android.util.Log.i(
                        com.iris.localai.engine.LoadTelemetry.TAG,
                        com.iris.localai.engine.GpuCacheDiag.logLine(
                            stage, cacheRoot.path, cacheRoot.canWrite(),
                            cacheRoot.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() },
                            model.fileName, modelFile.lastModified(), modelFile.length(),
                        ) + " backend=${config.backend.label} model=${model.path}",
                    )
                }
            }
            logCache("before")
            loadedCacheLog = { stage -> logCache(stage) }
            val errors = StringBuilder()
            val npuModel = NpuModels.targetSoc(model.fileName)
            // 1.7.1: exactly the backend EngineManager asked for; the manager walks the fallback chain.
            val candidates: List<Pair<String, () -> Backend>> = when (config.backend) {
                com.iris.localai.engine.Backend.NPU -> {
                    if (npuModel == null) throw EngineException("通用 .litertlm 模型不能在 NPU 上运行，需要 NPU 编译版（文件名带 _sm8750）")
                    listOf(npuBackend(model.fileName, npuModel))
                }
                com.iris.localai.engine.Backend.GPU -> {
                    if (npuModel != null) throw EngineException("NPU 专用模型不能在 GPU 上运行")
                    listOf("GPU" to { Backend.GPU() })
                }
                com.iris.localai.engine.Backend.CPU -> {
                    if (npuModel != null) throw EngineException("NPU 专用模型不能在 CPU 上运行")
                    listOf("CPU" to { Backend.CPU(threadCount = config.threads.takeIf { it > 0 }) })
                }
            }
            // XNNPACK / dispatch threads are created during init and inherit this thread's cores + priority.
            com.iris.localai.engine.NativeThreadPolicy.apply(config.resources)
            // Engine init is one blocking native call (GPU: shader compile, can take minutes).
            // Creep the bar toward 90% so the UI never looks frozen at 5%.
            val ticker = Thread {
                var p = 0.05f
                try {
                    while (true) {
                        Thread.sleep(400)
                        p += (0.9f - p) * 0.03f
                        onProgress(p)
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            try {
            // 1.10.12: multimodal .litertlm files (Gemma 3n / 4, VLMs) get a vision backend so attached
            // images can be read; a file without a vision section that refuses it loads text-only.
            val wantVision = com.iris.localai.util.VisionModels.litertMayHaveVision(model.fileName)
            outer@ for ((name, make) in candidates) {
                val kvs: List<Int?> = if (name == "NPU") com.iris.localai.engine.NpuRetry.kvAttempts(npuKv) else listOf(maxTokens)
                for ((kvIndex, kv) in kvs.withIndex()) {
                if (kvIndex > 0 && !com.iris.localai.engine.NpuRetry.isAllocFailure(errors.toString())) break
                for (vision in if (wantVision && name != "NPU") listOf(true, false) else listOf(false)) {
                var e: Engine? = null
                try {
                    e = Engine(
                        EngineConfig(
                            modelPath = model.path,
                            backend = make(),
                            visionBackend = if (vision) (if (name == "GPU") Backend.GPU() else Backend.CPU()) else null,
                            maxNumTokens = kv,
                            // NPU files are AOT-compiled: no cache (a stale one from another runtime can break init), like Gallery.
                            cacheDir = if (name == "NPU") null else cacheRoot.path,
                        ),
                    )
                    e.initialize()
                    engine = e
                    visionOn = vision
                    backend = if (name == "NPU") (if (com.iris.localai.engine.NpuIds.isMediatekTarget(npuModel)) "NPU · NeuroPilot · LiteRT-LM" else "NPU · Hexagon · LiteRT-LM") else "$name · LiteRT-LM"
                    if (name == "NPU" && kv != null) maxTokens = kv
                    break@outer
                } catch (t: Throwable) {
                    runCatching { e?.close() }
                    val kvTag = if (name == "NPU") " 上下文 ${kv ?: "默认"}" else ""
                    errors.append("[$name${if (vision) "+视觉" else ""}$kvTag] ").append(rootMessage(t)).append('\n')
                }
                }
                }
            }
            } finally {
                ticker.interrupt()
            }
            if (engine == null) {
                val msg = errors.toString()
                val lower = msg.lowercase()
                throw EngineException(
                    when {
                        npuModel != null ->
                            (if (com.iris.localai.engine.NpuIds.isMediatekTarget(npuModel)) com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_init_failed) + "\n$msg"
                            else "NPU 初始化失败。常见原因：系统 DSP 固件与运行库版本不兼容，或模型不是为本机芯片编译的。\n$msg") +
                                "── 设备 ──\n" + npuDeviceInfo(modelFile) +
                                npuLogTail().let { if (it.isEmpty()) "\n（没有读到原生日志）" else "\n── 原生日志 ──\n$it" }
                        "npu" in lower || "dispatch" in lower || "signature" in lower ->
                            "该模型是为特定芯片(NPU)编译的版本，请改用通用 .litertlm 文件\n$msg"
                        model.fileName.endsWith(".task", ignoreCase = true) ->
                            "LiteRT-LM 无法加载这个 .task 文件，请下载同一模型的 .litertlm 版本\n$msg"
                        else -> "LiteRT-LM 模型加载失败：\n$msg"
                    },
                )
            }
            logCache("after")
            onProgress(1f)
            ModelDetails(
                description = model.displayName,
                parameterCount = 0,
                contextLength = maxTokens,
                trainContextLength = 0,
                supportsThinking = true,
                supportsVision = visionOn,
                hasChatTemplate = true,
                backend = backend,
                activeBackend = config.backend,
                // 1.7.9: LiteRT-LM 0.18 has no GPU priority / perf option (Backend.GPU() has no arguments).
                gpuHint = if (config.backend == com.iris.localai.engine.Backend.GPU) "unsupported" else null,
                cacheBefore = cacheBefore,
                cacheAfter = snapshot(),
            )
        }

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = channelFlow {
        invokeOnClose { cause -> if (cause != null) cancel() }
        val result = withContext(worker) {
            val eng = engine ?: return@withContext Triple(StopReason.ERROR, null as GenerationStats?, "模型未加载")
            var templateRetried = false
            while (true) {
            val t0 = System.currentTimeMillis()
            var emitted = false
            // 1.10.6: kept outside the try so a soft stop (cancel()) can record the partial reply.
            val reply = StringBuilder()
            softStop = false
            val outcome: Triple<StopReason, GenerationStats?, String?>? = try {
                val system = history.filter { it.role == PromptMessage.SYSTEM }.joinToString("\n\n") { it.content }
                val turns = history.filter { it.role != PromptMessage.SYSTEM }
                val last = turns.lastOrNull() ?: return@withContext Triple(StopReason.ERROR, null, "没有可发送的消息")
                val prior = history.dropLast(1)
                val key = ConvKey(params.copy(threads = 0, resources = null), system) // threads / cores don't affect the conversation
                val reuse = conversation?.isAlive == true && conversationKey == key && sameHistory(conversationHistory, prior)
                val conv = if (reuse) conversation!! else {
                    runCatching { conversation?.close() }
                    fun config(thinking: ThinkingConfig?) = ConversationConfig(
                        systemInstruction = system.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                        initialMessages = com.iris.localai.engine.HistoryMatch.seedTurns(turns.dropLast(1)).map {
                            if (it.role == PromptMessage.USER) Message.user(it.content) else Message.model(it.content)
                        },
                        // NPU graphs use their built-in sampler; Gallery passes null for NPU too.
                        samplerConfig = if (isNpu) null else SamplerConfig(
                            topK = params.topK.coerceAtLeast(1),
                            topP = params.topP.toDouble().coerceIn(0.01, 1.0),
                            temperature = params.temperature.toDouble().coerceAtLeast(0.0),
                        ),
                        maxOutputToken = params.maxNewTokens.takeIf { it > 0 },
                        thinkingConfig = thinking,
                        chatTemplate = chatTemplateOverride,
                    )
                    // Not every model's template knows a thinking switch; fall back to its default.
                    val created: Conversation =
                        runCatching { eng.createConversation(config(ThinkingConfig(enableThinking = !params.disableThinking))) }
                            .getOrElse { eng.createConversation(config(null)) }
                    conversation = created
                    conversationKey = key
                    created
                }
                conversationHistory = null // invalid until this turn completes

                var firstTokenAt = 0L
                var chars = 0
                var thinking = false
                var failure: Throwable? = null
                live = conv
                val finished = CompletableDeferred<Unit>()
                com.iris.localai.engine.ThermalGovernor.beginReply()
                run {
                    fun finish() { finished.complete(Unit) }
                    fun emit(s: String) {
                        if (s.isEmpty()) return
                        if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                        emitted = true
                        chars += s.length
                        com.iris.localai.engine.ThermalGovernor.pace()
                        trySend(GenEvent.Piece(s))
                    }
                    val images = if (visionOn) last.images.filter { java.io.File(it).isFile } else emptyList()
                    conv.sendMessageAsync(
                        if (images.isEmpty()) Message.user(last.content)
                        else Message.user(Contents.of(images.map { Content.ImageFile(it) } + Content.Text(last.content))),
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val thought = message.channels.values.joinToString("")
                                if (thought.isNotEmpty()) {
                                    if (!thinking) { emit("<think>"); thinking = true }
                                    emit(thought)
                                }
                                val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                if (text.isNotEmpty()) {
                                    if (thinking) { emit("</think>"); thinking = false }
                                    reply.append(text)
                                    emit(text)
                                }
                            }
                            override fun onDone() { finish() }
                            override fun onError(throwable: Throwable) { failure = throwable; finish() }
                        },
                    )
                }
                try {
                    finished.await()
                } catch (ce: CancellationException) {
                    // Wait (bounded) for the native side to really stop before this worker thread
                    // moves on: closing / reusing a conversation that is still decoding can crash.
                    runCatching { conv.cancelProcess() }
                    withContext(NonCancellable) { withTimeoutOrNull(CANCEL_WAIT_MS) { finished.await() } }
                    throw ce
                }
                live = null
                if (thinking) trySend(GenEvent.Piece("</think>"))
                failure?.let { throw it }

                conversationHistory = history + PromptMessage(PromptMessage.ASSISTANT, reply.toString().trim())
                val end = System.currentTimeMillis()
                val bench = runCatching { conv.getBenchmarkInfo() }.getOrNull()
                val genTokens = bench?.lastDecodeTokenCount?.takeIf { it > 0 } ?: (chars / 3)
                val hitLimit = params.maxNewTokens in 1..genTokens
                Triple(
                    if (hitLimit) StopReason.MAX_TOKENS else StopReason.END,
                    GenerationStats(
                        generatedTokens = genTokens,
                        prefillMs = if (firstTokenAt > 0) firstTokenAt - t0 else 0,
                        // 1.13.1: the runtime's own decode rate when it reports one (wall time from the
                        // first streamed piece included callback / UI delays and the stop drain).
                        generateMs = bench?.takeIf { it.lastDecodeTokensPerSecond > 0.0 && it.lastDecodeTokenCount > 0 }
                            ?.let { b -> (genTokens * 1000.0 / b.lastDecodeTokensPerSecond).toLong().coerceAtLeast(1L) }
                            ?: if (firstTokenAt > 0) end - firstTokenAt else 0,
                    ),
                    null,
                )
            } catch (t: Throwable) {
                live = null
                conversationHistory = null
                if (t is CancellationException) throw t
                val m = rootMessage(t)
                when {
                    "cancel" in m.lowercase() -> {
                        // 1.10.6: a soft stop (the agent cut a finished tool call, or the user's stop
                        // button) keeps the conversation and its KV cache: the next turn that extends
                        // this history is answered without re-prefilling everything.
                        if (softStop && conversation?.isAlive == true) {
                            conversationHistory = history + PromptMessage(PromptMessage.ASSISTANT, reply.toString().trim())
                            android.util.Log.i("IrisAgent", "litert: soft stop, conversation kept (${reply.length} chars)")
                        }
                        Triple(StopReason.CANCELLED, null, null)
                    }
                    // Some converted models ship a Jinja template that only accepts string content,
                    // while LiteRT-LM passes content as a list of parts. Retry once with a tolerant ChatML one.
                    !templateRetried && !emitted && chatTemplateOverride == null && "template" in m.lowercase() -> {
                        templateRetried = true
                        chatTemplateOverride = CHATML_TEMPLATE
                        android.util.Log.w("IrisGen", "litert: model template rejected the prompt ($m) — using ChatML fallback until next load")
                        runCatching { conversation?.close() }
                        conversation = null
                        conversationKey = null
                        null
                    }
                    else -> Triple(StopReason.ERROR, null, "推理失败：$m")
                }
            }
            if (outcome != null) return@withContext outcome
            }
            @Suppress("UNREACHABLE_CODE") Triple(StopReason.ERROR, null, null)
        }
        send(GenEvent.Done(result.first, result.second, result.third))
    }.buffer(Channel.UNLIMITED)

    override fun cancel() {
        // 1.10.6: soft stop. The running turn ends as CANCELLED and records its partial reply,
        // so the conversation (and its KV cache) is reused by the next turn (see generate).
        softStop = live != null
        runCatching { live?.cancelProcess() }
        if (!softStop) conversationHistory = null
    }

    @Volatile private var softStop = false

    override suspend fun resetContext() = withContext(worker) {
        runCatching { conversation?.close() }
        conversation = null
        conversationKey = null
        conversationHistory = null
    }

    override suspend fun unload() = withContext(worker) { closeAll() }

    /** 1.10.3: logs the cache dir of the loaded model again (after close: does the runtime write on close?). */
    @Volatile private var loadedCacheLog: ((String) -> Unit)? = null

    private fun closeAll() {
        runCatching { conversation?.close() }
        val hadEngine = engine != null
        runCatching { engine?.close() }
        if (hadEngine) loadedCacheLog?.invoke("after-close")
        loadedCacheLog = null
        conversation = null
        conversationKey = null
        engine = null
        conversationHistory = null
    }

    /**
     * 1.0.5 and earlier kept compiled caches in cacheDir; move them once (1.10.2: a marker file
     * instead of listing cacheDir on every load). Only files whose name looks like a LiteRT-LM
     * cache (`…mldrift…` / `…xnnpack…`) are moved, never anything else in cacheDir.
     */
    private fun migrateOldCacheOnce(cacheRoot: java.io.File) {
        val marker = java.io.File(cacheRoot, MIGRATED_MARKER)
        if (marker.exists()) return
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && com.iris.localai.engine.LiteRtCacheFiles.kindOf(f.name.lowercase()) != null) {
                    val dst = java.io.File(cacheRoot, f.name)
                    if (!dst.exists()) f.renameTo(dst)
                }
            }
            marker.writeText("1")
        }
    }

    /** Backend for a model AOT-compiled for a Qualcomm NPU. Such files cannot fall back to CPU/GPU. */
    private fun npuBackend(fileName: String, target: String): Pair<String, () -> Backend> {
        if (com.iris.localai.engine.NpuIds.isMediatekTarget(target)) return mediatekBackend(target)
        val rt = QualcommNpuRuntime.get(context)
        if (rt.arch == null) throw EngineException("这是高通 NPU 专用模型（$target），但本机芯片不支持。请改用通用 .litertlm 版本。")
        // 2.0: one rule with the backend picker (NpuIds): an _sm8750 file runs on SM8750 / SM8750P / SM8750-AB.
        if (!com.iris.localai.engine.NpuIds.socMatches(target, rt.socId)) {
            throw EngineException("这个 NPU 模型是为 ${target.uppercase()} 编译的，本机是 ${rt.socId.uppercase()}，无法运行。请下载带 _${rt.socId} 的版本。")
        }
        if (!rt.isInstalled) {
            val st = rt.refreshState()
            throw EngineException(
                if (st is com.iris.localai.engine.NpuState.NeedsRepair)
                    "高通 NPU 运行库不完整（${st.reason}），需要修复。请到 设置 › 模型与后端 › NPU 运行库 点「修复」重新下载后再加载。"
                else "NPU 模型需要高通 NPU 运行库 QAIRT 2.50（约 71 MB 下载；旧版运行库与当前引擎不匹配，需重新下载）。请到 设置 › 模型与后端 › NPU 运行库 下载后再加载。",
            )
        }
        runCatching {
            android.system.Os.setenv(
                "ADSP_LIBRARY_PATH",
                "${rt.dir.path};/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp",
                true,
            )
        }
        return "NPU" to { Backend.NPU(nativeLibraryDir = rt.dir.path) }
    }

    /**
     * 2.4.4 (Issue #6): a model AOT-compiled for a MediaTek NPU (`_mt6991`). The dispatch library ships
     * in the APK and is copied on demand; the NeuroPilot driver is the phone's own.
     */
    private fun mediatekBackend(target: String): Pair<String, () -> Backend> {
        val rt = MediaTekNpuRuntime.get(context)
        val here = rt.socId
            ?: throw EngineException(com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_not_mtk, target.uppercase(), "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}".trim()))
        if (!com.iris.localai.engine.NpuIds.socMatches(target, here)) {
            throw EngineException(com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_wrong_soc, target.uppercase(), here.uppercase(), here))
        }
        rt.ensureInstalled()
        rt.refreshState()
        return "NPU" to { Backend.NPU(nativeLibraryDir = rt.dir.path) }
    }

    /**
     * Models whose GPU path is known to be impractical on Adreno: Qwen3.5 (gated delta-net)
     * needs fp32 activations on GPU — ~110 s engine init and >5 GB peak on Snapdragon — while
     * CPU decode is about as fast.
     */
    private fun prefersCpu(fileName: String): Boolean {
        val n = fileName.lowercase()
        return "qwen3.5" in n || "qwen3_5" in n || "qwen35" in n
    }

    /**
     * The chat layer sends assistant turns back trimmed and without reasoning, so compare roles
     * and trimmed text rather than exact strings; otherwise the KV cache was never reused.
     */
    private fun sameHistory(a: List<PromptMessage>?, b: List<PromptMessage>): Boolean =
        com.iris.localai.engine.HistoryMatch.same(a, b)

    /**
     * 2.1.1: the Kotlin exception only says "Failed to allocate tensors"; the real reason (QNN error
     * code, FastRPC / rpcmem failure, skel not found…) is in this process's logcat. Keep the last
     * matching lines so the error card itself shows it, without exporting diagnostics.
     */
    private fun npuLogTail(max: Int = 12): String = runCatching {
        val p = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "brief", "--pid=${android.os.Process.myPid()}", "-t", "600"),
        )
        val lines = p.inputStream.bufferedReader().use { r -> r.readLines() }
        p.waitFor()
        com.iris.localai.engine.NpuLog.relevant(lines, max).ifEmpty { com.iris.localai.engine.NpuLog.fallback(lines, max) }
    }.getOrDefault("")

    /** 2.1.2: what a remote diagnosis needs, in one block under the NPU error. */
    private fun npuDeviceInfo(modelFile: java.io.File): String = runCatching {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = { b: Long -> "%.1f GB".format(b / 1e9) }
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · $soc · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.VERSION.INCREMENTAL}\n" +
            "内存 可用 ${gb(mi.availMem)} / ${gb(mi.totalMem)}${if (mi.lowMemory) " · 低内存" else ""}\n" +
            "模型 ${modelFile.length()} B · ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(modelFile.lastModified()))}"
    }.getOrDefault("")

    private fun rootMessage(t: Throwable): String =
        generateSequence(t) { it.cause }.last().let { it.message ?: it.javaClass.simpleName }

    private companion object {
        /** One worker for all instances (a new engine is created per load; per-instance executors leaked threads). */
        val worker by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "iris-litert").apply { isDaemon = true } }.asCoroutineDispatcher()
        }
        const val CANCEL_WAIT_MS = 5_000L
        /** filesDir (not cacheDir): survives 「清除缓存」 and is never trimmed by the system. */
        const val CACHE_DIR = "litert-cache"
        const val MIGRATED_MARKER = ".migrated-from-cachedir"

        /** ChatML (Qwen-family) template that accepts content as a string or as a list of text parts. */
        val CHATML_TEMPLATE = """
{%- for message in messages -%}
{%- set role = message.role -%}
{%- if role == 'model' -%}{%- set role = 'assistant' -%}{%- endif -%}
<|im_start|>{{ role }}
{% if message.content is string %}{{ message.content }}{% else %}{% for part in message.content %}{% if part.type == 'text' %}{{ part.text }}{% endif %}{% endfor %}{% endif %}<|im_end|>
{% endfor -%}
{%- if add_generation_prompt -%}
<|im_start|>assistant
{% if enable_thinking is defined and enable_thinking is false %}<think>

</think>

{% endif %}
{%- endif -%}
""".trimStart()
    }
}

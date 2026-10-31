package com.iris.localai.engine.geniex

import android.content.Context
import android.util.Log
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.EngineException
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.InferenceEngine
import com.iris.localai.engine.LiveApply
import com.iris.localai.engine.LoadConfig
import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.engine.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.onCompletion

/**
 * 2.2.0: Qualcomm GenieX 0.8.0 engine (full flavour).
 *  - GGUF files through GenieX's `llama_cpp` runtime on the NPU (Hexagon, default) / GPU (OpenCL) / CPU;
 *    a sibling `mmproj-*.gguf` ([GenieXVision.findMmproj]) turns on the VLM path (images).
 *  - AI Hub QAIRT bundle folders through the `qairt` runtime, NPU only; `vision_encoder.bin` in the
 *    folder turns on the VLM path.
 * The prompt is always the chat-templated conversation (applyChatTemplate, `enable_thinking` follows
 * 深度思考); the system prompt is the first message. llama_cpp reuses its KV prefix by itself; QAIRT
 * keeps the conversation in its KV cache, so a history that is not a continuation resets it first
 * ([GenieXTurns]). Stop = stopStream (never waits for the running native call).
 */
class GenieXEngine(context: Context) : InferenceEngine {

    private val runtime = GenieXRuntime.get(context)
    private val lock = Mutex()
    @Volatile private var llm: LlmWrapper? = null
    @Volatile private var vlm: VlmWrapper? = null
    @Volatile private var cancelled = false
    private var qairt = false
    private var visionOk = false
    private var backendLabel = ""
    /** QAIRT: what its KV cache holds (the last templated conversation + our reply). */
    private var cached: List<GenieXTurns.Msg> = emptyList()

    override suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails =
        withContext(Dispatchers.IO) {
            try {
                runtime.ensureLoaded()
            } catch (e: IllegalStateException) {
                throw EngineException(e.message ?: GenieX.NEED_RUNTIME, e)
            }
            onProgress(0.05f)
            qairt = model.format == ModelFormat.QAIRT
            val threads = config.threads.coerceIn(1, 8)
            val runtimeId: String
            val modelPath: String
            var mmproj: String? = null
            var ctx = 0
            val backend = if (qairt) Backend.NPU else config.backend
            if (qairt) {
                if (backend != Backend.NPU) throw EngineException("AI Hub QAIRT 包只能在 NPU 上运行")
                val dir = File(model.path)
                val names = dir.list().orEmpty().toSet()
                QairtBundle.missing(names)?.let { throw EngineException("QAIRT 模型文件夹不完整：$it（请完整解压 AI Hub 下载的 Genie 包）") }
                try {
                    runtime.ensureQnnLoaded()
                } catch (e: IllegalStateException) {
                    throw EngineException(e.message ?: "QNN 运行库加载失败", e)
                }
                runtimeId = GenieX.RUNTIME_QAIRT
                modelPath = File(dir, QairtBundle.modelPathName(names)).absolutePath
                visionOk = QairtBundle.isVision(names)
                ctx = runCatching { QairtBundle.contextSize(File(dir, QairtBundle.GENIE_CONFIG).readText()) }.getOrNull() ?: 0
            } else {
                val f = File(model.path)
                if (!f.isFile) throw EngineException("找不到模型文件：${model.path}")
                runtimeId = GenieX.RUNTIME_LLAMA_CPP
                modelPath = f.absolutePath
                mmproj = GenieXVision.findMmproj(f.name, f.parentFile?.list().orEmpty().toList())?.let { File(f.parentFile, it).absolutePath }
                visionOk = mmproj != null
                ctx = config.contextLength.coerceIn(512, 131072)
            }
            // QAIRT rejects n_ctx / n_gpu_layers != 0 (fixed in the bundle); llama_cpp: -1 = all layers, CPU forces 0.
            val mc = if (qairt) ModelConfig(nCtx = 0, nThreads = threads, nThreadsBatch = threads, nGpuLayers = 0)
            else ModelConfig(
                nCtx = ctx, nThreads = threads, nThreadsBatch = threads,
                nBatch = config.batchSize.coerceIn(32, 2048), nUBatch = config.batchSize.coerceIn(32, 512),
                nGpuLayers = if (backend == Backend.CPU) 0 else -1,
            )
            val unit = GenieX.computeUnit(backend)
            val ticker = Thread {
                var p = 0.05f
                try { while (true) { Thread.sleep(300); p += (0.9f - p) * 0.04f; onProgress(p) } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            try {
                if (visionOk) {
                    val r = VlmWrapper.Builder().vlmCreateInput(
                        VlmCreateInput(model_path = modelPath, mmproj_path = mmproj, config = mc, runtime_id = runtimeId, compute_unit = unit),
                    ).build()
                    val w = r.getOrElse { throw createError(it, backend) }
                    if (handleOf(w) == 0L) { runCatching { w.destroy() }; throw createError(null, backend) }
                    vlm = w
                } else {
                    val r = LlmWrapper.builder().llmCreateInput(
                        LlmCreateInput(model_path = modelPath, tokenizer_path = null, config = mc, runtime_id = runtimeId, compute_unit = unit),
                    ).build()
                    val w = r.getOrElse { throw createError(it, backend) }
                    if (handleOf(w) == 0L) { runCatching { w.destroy() }; throw createError(null, backend) }
                    llm = w
                }
            } finally {
                ticker.interrupt()
            }
            cached = emptyList()
            backendLabel = when {
                // 2.1.2: short labels — the sheet and chat header only need engine + backend.
                qairt -> "${GenieX.tag(Backend.NPU)}（QAIRT）"
                else -> GenieX.tag(backend)
            } + if (visionOk) " · 视觉" else ""
            Log.i(TAG, "loaded ${model.fileName}: $backendLabel ctx=$ctx threads=$threads mmproj=${mmproj != null}")
            onProgress(1f)
            ModelDetails(
                description = model.displayName,
                parameterCount = 0,
                contextLength = if (ctx > 0) ctx else config.contextLength,
                trainContextLength = 0,
                supportsThinking = com.iris.localai.engine.ThinkSupport.supports(File(model.path), GenieXSampling::thinkingModel),
                supportsVision = visionOk,
                hasChatTemplate = true,
                backend = backendLabel,
                activeBackend = backend,
            )
        }

    private fun createError(t: Throwable?, backend: Backend): EngineException {
        val hint = GenieX.codeHint(t?.message)
        val where = if (qairt) "QAIRT" else "${backend.label}（llama.cpp）"
        val msg = GenieX.error("GenieX 在 $where 上加载失败" + (hint?.let { "：$it" } ?: ""), t ?: IllegalStateException("native create returned no handle"))
        return EngineException(msg, t)
    }

    /** The wrappers keep the native handle private; 0 means the JNI create failed without throwing. */
    private fun handleOf(w: Any): Long = runCatching {
        w.javaClass.getDeclaredField("handle").apply { isAccessible = true }.getLong(w)
    }.getOrDefault(-1L)

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = channelFlow {
        val l = llm
        val v = vlm
        if (l == null && v == null) {
            send(GenEvent.Done(StopReason.ERROR, null, "模型未加载"))
            return@channelFlow
        }
        val lastUser = history.lastOrNull { it.role == PromptMessage.USER }
        if (!visionOk && lastUser != null && lastUser.images.isNotEmpty()) {
            send(GenEvent.Done(StopReason.ERROR, null, if (qairt) "这个 QAIRT 包没有视觉编码器（vision_encoder.bin），不能读图片"
                else "这个 GGUF 模型没有视觉投影文件（mmproj-*.gguf，需与模型放在同一文件夹），GenieX 不能读图片"))
            return@channelFlow
        }
        invokeOnClose { cause -> if (cause != null) this@GenieXEngine.cancel() }
        lock.withLock {
            cancelled = false
            val turns = history.map { GenieXTurns.Msg(it.role, it.content) }
            val started = System.currentTimeMillis()
            val out = StringBuilder()
            try {
                // 2.1.2: GGUF too. llama.cpp's prefix rollback on regenerate / edit left the Hexagon
                // backend producing nothing; a history that does not extend the cache resets first.
                if (!GenieXTurns.canContinue(cached, turns)) {
                    if (cached.isNotEmpty()) { l?.reset(); v?.reset() }
                    cached = emptyList()
                }
                val enableThinking = !params.disableThinking
                val images = if (visionOk) history.flatMap { m -> m.images.filter { File(it).isFile } } else emptyList()
                val prompt = if (v != null) {
                    val msgs = history.map { m ->
                        val parts = ArrayList<VlmContent>()
                        m.images.filter { File(it).isFile }.forEach { parts += VlmContent("image", it) }
                        parts += VlmContent("text", m.content)
                        VlmChatMessage(role = m.role, contents = parts)
                    }.toTypedArray()
                    v.applyChatTemplate(msgs, null, enableThinking).getOrThrow().formattedText
                } else {
                    val msgs = history.map { ChatMessage(role = it.role, content = it.content) }.toTypedArray()
                    l!!.applyChatTemplate(msgs, null, enableThinking).getOrThrow().formattedText
                }
                val s = GenieXSampling.args(params)
                val gen = GenerationConfig(
                    maxTokens = params.maxNewTokens.coerceAtLeast(1),
                    samplerConfig = SamplerConfig(
                        temperature = s.temperature, topP = s.topP, topK = s.topK, minP = s.minP,
                        repetitionPenalty = s.repetitionPenalty, presencePenalty = s.presencePenalty,
                        frequencyPenalty = s.frequencyPenalty, seed = s.seed,
                    ),
                    imagePaths = images.takeIf { it.isNotEmpty() }?.toTypedArray(),
                    imageCount = images.size,
                    slidingWindow = !qairt && params.contextShift,
                    slidingWindowNKeep = if (!qairt && params.contextShift) params.keepTokens.coerceAtLeast(0) else 0,
                )
                var done: GenEvent.Done? = null
                var attempt = 0
                while (true) {
                done = null
                val flow = v?.generateStreamFlow(prompt, gen) ?: l!!.generateStreamFlow(prompt, gen)
                val channel = com.iris.localai.engine.ChannelThink()
                flow.onCompletion { channel.push("", final = true).takeIf { it.isNotEmpty() && !cancelled }?.let { send(GenEvent.Piece(it)) } }.collect { r ->
                    when (r) {
                        is LlmStreamResult.Token -> {
                            out.append(r.text)
                            if (!cancelled) channel.push(r.text).takeIf { it.isNotEmpty() }?.let { send(GenEvent.Piece(it)) }
                        }
                        is LlmStreamResult.Completed -> {
                            val p = r.profile
                            val reason = GenieXSampling.stopReason(p.stopReason, cancelled, p.generatedTokens, params.maxNewTokens)
                            done = GenEvent.Done(
                                reason,
                                GenerationStats(
                                    promptTokens = p.promptTokens.toInt(),
                                    generatedTokens = p.generatedTokens.toInt(),
                                    prefillMs = p.promptTimeMs.toLong(),
                                    generateMs = p.decodeTimeMs.toLong(),
                                ),
                                if (reason == StopReason.CONTEXT_FULL) com.iris.localai.engine.LengthRule.contextFullMessage(p.generatedTokens.toInt(), params.maxNewTokens) else null,
                            )
                        }
                        is LlmStreamResult.Error -> {
                            done = GenEvent.Done(
                                if (cancelled) StopReason.CANCELLED else StopReason.ERROR, null,
                                if (cancelled) null else GenieX.error("GenieX 生成失败" + (GenieX.codeHint(r.throwable.message)?.let { "：$it" } ?: ""), r.throwable),
                            )
                        }
                        else -> {}
                    }
                }
                // 2.1.2: an empty reply that nobody stopped — reset the KV cache and try once more.
                if (GenieXRetry.shouldRetryEmpty(attempt, cancelled, out.isEmpty(), done?.reason)) {
                    attempt++
                    withContext(NonCancellable) { runCatching { l?.reset(); v?.reset() } }
                    continue
                }
                break
                }
                val d = done ?: GenEvent.Done(
                    if (cancelled) StopReason.CANCELLED else StopReason.END,
                    GenerationStats(generateMs = System.currentTimeMillis() - started),
                )
                // QAIRT: the cache now holds this conversation plus our reply (also a partial one after stop).
                cached = if (d.reason != StopReason.ERROR && out.isNotEmpty()) turns + GenieXTurns.Msg(PromptMessage.ASSISTANT, out.toString()) else emptyList()
                if (d.reason == StopReason.ERROR || out.isEmpty()) withContext(NonCancellable) { runCatching { l?.reset(); v?.reset() } }
                send(if (out.isEmpty() && d.reason == StopReason.END && !cancelled) GenEvent.Done(StopReason.ERROR, d.stats, GenieXRetry.EMPTY_MESSAGE) else d)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                cached = emptyList()
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "generate failed", t)
                cached = emptyList()
                withContext(NonCancellable) { runCatching { l?.reset(); v?.reset() } }
                send(GenEvent.Done(StopReason.ERROR, null, GenieX.error("GenieX 生成失败" + (GenieX.codeHint(t.message)?.let { "：$it" } ?: ""), t)))
            }
        }
    }.buffer(Channel.UNLIMITED)

    override fun cancel() {
        cancelled = true
        val l = llm
        val v = vlm
        if (l == null && v == null) return
        stopScope.launch { runCatching { l?.stopStream(); v?.stopStream() } }
    }

    override fun liveApplyMode(): LiveApply = LiveApply.NEXT_REPLY

    override suspend fun resetContext() {
        lock.withLock {
            cached = emptyList()
            withContext(Dispatchers.IO) { runCatching { llm?.reset(); vlm?.reset() } }
        }
    }

    override suspend fun unload() {
        cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                runCatching { llm?.destroy() }
                runCatching { vlm?.destroy() }
                llm = null
                vlm = null
                cached = emptyList()
            }
        }
    }

    private companion object {
        const val TAG = "GenieXEngine"
        val stopScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    }
}

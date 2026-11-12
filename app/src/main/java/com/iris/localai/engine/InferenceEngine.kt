package com.iris.localai.engine

import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import kotlinx.coroutines.flow.Flow

/** 1.10.12: [images] = absolute paths of pictures attached to this (user) message; only vision models read them. */
data class PromptMessage(val role: String, val content: String, val images: List<String> = emptyList()) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class LoadConfig(
    val contextLength: Int,
    val threads: Int,
    val flashAttention: Boolean,
    /** 1.7.1: the one backend to try. Engines no longer fall back internally; [EngineManager] walks the chain. */
    val backend: Backend = Backend.CPU,
    /** KV cache type: 0 = F16, 1 = Q8_0, 2 = Q4_0 (llama.cpp; quantised needs Flash Attention). */
    val kvCacheType: Int = 0,
    /** Prefill batch (llama.cpp). */
    val batchSize: Int = 512,
    val useMmap: Boolean = true,
    /** 1.8.0 crash-diag (MNN only): treat vision models as text-only, skip visual.mnn. */
    val noVisual: Boolean = false,
    /** Resource tier resolved for this device: cores / priority are applied on the engine thread. */
    val resources: ResourcePlan? = null,
    /** 2.2.17, GGUF (llama.cpp): absolute path of the vision projector to load with the model, or null. */
    val mmprojPath: String? = null,
    /** 2.4.5 设置「识图用 GPU」: allow the projector on an Adreno GPU (off = 2.4.4 rule, CPU there). */
    val mmprojGpu: Boolean = false,
    /** 2.5.0-beta9, GGUF (llama.cpp): MTP draft tokens per step, 0 = normal decoding. */
    val mtpDraft: Int = 0,
    /** 2.5.0-beta10, LiteRT-LM: ExperimentalFlags.enableSpeculativeDecoding for this load (set before every Engine). */
    val litertMtp: Boolean = false,
    /** 2.5.1, GGUF (llama.cpp) MoE: 专家张量留 CPU、mmap 按需读（不预读）。 */
    val moeCpu: Boolean = false,
    /** 2.5.1, GGUF MoE: 激活专家数覆盖，0 = 模型默认。 */
    val expertUsed: Int = 0,
)

data class SamplingConfig(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float,
    val maxNewTokens: Int,
    val disableThinking: Boolean,
    /** Decode threads for this request (0 = keep current). Lets the manager throttle on heat. */
    val threads: Int = 0,
    val repeatLastN: Int = 64,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0f,
    /** -1 = random. */
    val seed: Int = -1,
    /** llama.cpp context shift instead of stopping when the KV cache is full. */
    val contextShift: Boolean = false,
    /** Tokens kept at the start on a context shift (system prompt + attention sink). */
    val keepTokens: Int = 4,
    /** Cores / priority for this request (applied on the engine thread before decoding). */
    val resources: ResourcePlan? = null,
)

data class ModelDetails(
    val description: String,
    val parameterCount: Long,
    val contextLength: Int,
    val trainContextLength: Int,
    val supportsThinking: Boolean,
    /** 1.10.12: the loaded model can read attached images. */
    val supportsVision: Boolean = false,
    /**
     * 2.1.1: longest side of the picture copy handed to this engine (0 = Attachments.VISION_MAX_SIDE).
     * MNN's Qwen-VL encoders keep the picture's own size, so 1024 px meant ~3000 ViT patches on the CPU.
     */
    val visionMaxSide: Int = 0,
    /** The chat template opens <think> itself, so the reply starts mid-reasoning. */
    val thinkingPrefilled: Boolean = false,
    val hasChatTemplate: Boolean,
    /** Human-readable backend line, e.g. "GPU · Adreno 830 · OpenCL". */
    val backend: String,
    /** 1.7.1: the backend really in use (after any fallback). */
    val activeBackend: Backend = Backend.CPU,
    /** Why the model is not on the backend that was chosen (fallback / crash memory), or null. */
    val fallbackNote: String? = null,
    /**
     * 1.7.9: the GPU perf hint really in effect (IrisResource log): MNN on OpenCL reports the Adreno
     * cl_qcom_perf_hint of its context ("low"/"normal"/"high", "none" = driver took none);
     * LiteRT on the GPU "unsupported" (no such API); null on the CPU.
     */
    val gpuHint: String? = null,
    /** 1.10.2: LiteRT-LM compiled-cache files of this model before / after the load (null = no such cache). */
    val cacheBefore: CacheSnapshot? = null,
    val cacheAfter: CacheSnapshot? = null,
)

enum class StopReason { END, MAX_TOKENS, CANCELLED, CONTEXT_FULL, ERROR }

sealed interface GenEvent {
    data class Piece(val text: String) : GenEvent
    data class Done(val reason: StopReason, val stats: GenerationStats?, val error: String? = null) : GenEvent
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One loaded model. Implementations serialise their own native work. */
interface InferenceEngine {
    /** Throws [EngineException] with a user-facing message on failure. */
    suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails

    /** Streams text deltas, always terminated by exactly one [GenEvent.Done]. Cancelling collection stops generation. */
    fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent>

    /** Thread-safe, non-blocking request to stop the running generation. */
    fun cancel()

    /** 1.7.2: how a tier change made while this engine generates reaches it. */
    fun liveApplyMode(): LiveApply = LiveApply.RELOAD

    /** 1.7.2: apply [plan] to the running reply if this engine can; false = it will not. */
    fun applyResourcesLive(plan: ResourcePlan): Boolean = false

    /** 1.7.5: cores + nice the engine's own threads have right now (null = not tracked / not live). */
    fun livePolicy(): LiveChange? = null

    /** 1.7.5: native thread ids doing the inference work (for the IrisResource CPU report); empty = unknown. */
    fun resourceThreadIds(): IntArray = IntArray(0)

    /** Forget cached conversation state (KV cache / session). */
    suspend fun resetContext()

    /**
     * 2.3: true when the engine matches every request against what its KV cache holds on its own
     * (llama.cpp: longest common token prefix, the rest re-decoded), so a new chat need not drop the
     * cache: its first turn reuses the shared system prompt (智能体: ~500 tokens) instead of
     * prefilling it again. Engines that only continue an exact history keep the reset.
     */
    val reusesCommonPrefix: Boolean get() = false

    /** 2.4.0: native work that outlives the generation lock is still running (MNN prefill); unload waits for it. */
    val busyNative: Boolean get() = false

    suspend fun unload()
}

/**
 * 1.7.8: GGUF length limit vs. a full context. llama.cpp's decode loop runs
 * `max_new = min(max_new_tokens, n_ctx − kv − 1)` iterations (iris_engine.cpp, decode loop) and
 * reports MaxTokens when it ran out — also when the *context* was the smaller bound. In that case
 * every iteration produced a token, so generated == max_new < max_new_tokens tells the two apart
 * exactly (with context shift the bound is max_new_tokens alone). Pure, unit-tested.
 */
object LengthRule {
    fun reason(reported: StopReason, generated: Int, maxNewTokens: Int, contextShift: Boolean): StopReason =
        if (reported == StopReason.MAX_TOKENS && !contextShift && maxNewTokens > 0 && generated in 0 until maxNewTokens) StopReason.CONTEXT_FULL
        else reported

    fun contextFullMessage(generated: Int, maxNewTokens: Int): String =
        "上下文已满：回复写到 $generated tokens 时用完了上下文剩余空间（最大回复长度 $maxNewTokens）。" +
            "可在输入框上方的上下文菜单里改用「自动摘要」或「从这里开始新上下文」，或在模型设置里调大上下文长度"
}

package com.iris.localai.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
data class GenerationStats(
    val promptTokens: Int = 0,
    val reusedTokens: Int = 0,
    val generatedTokens: Int = 0,
    val prefillMs: Long = 0,
    val generateMs: Long = 0,
    val droppedMessages: Int = 0,
    /** 1.7.1: tokens discarded by KV context shift during this reply. */
    val shiftedTokens: Int = 0,
    /** Tokens in the KV cache when the reply ended (0 = unknown). */
    val kvUsed: Int = 0,
) {
    val tokensPerSecond: Float
        get() = if (generateMs > 0) generatedTokens * 1000f / generateMs else 0f
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Reasoning time for models that emit <think> blocks, in ms (0 = unknown / none). */
    val thinkingMs: Long = 0,
    val stats: GenerationStats? = null,
    /** Model that produced an assistant message (display only). */
    val modelName: String? = null,
    /** Generation ended early (stopped by user / error). */
    val interrupted: Boolean = false,
    /** Error text shown under the message; never sent back to the model. */
    val error: String? = null,
    /** Measured on the device for this reply: characters produced, first output → end, request → end. */
    val outputChars: Int = 0,
    val outputMs: Long = 0,
    val totalMs: Long = 0,
    /** 1.9.0 智能体 mode: the tool steps of this reply, drawn as cards above the answer. */
    val agentSteps: List<com.iris.localai.agent.AgentStep> = emptyList(),
    /** 1.10.12: files / pictures sent with a user message (copies in filesDir/attachments). */
    val attachments: List<Attachment> = emptyList(),
    /** 2.2: skills / agent tools the user picked for this turn (「技能」 chip); one prompt line on this turn only. */
    val refs: List<TurnRef> = emptyList(),
    /** 2.0.x: an assistant reply the user edited in place (shown as 「已编辑」). */
    val edited: Boolean = false,
    /**
     * 2.0.x message versions (edit / regenerate keep the old ones). Empty = a single version, the
     * message itself (every chat saved before this feature). Otherwise one entry per version; the
     * entry at [activeVersion] is a placeholder — the live message fields (and, for a user message,
     * the conversation after it) are that version. See [MessageVersions].
     */
    val versions: List<MessageVersion> = emptyList(),
    val activeVersion: Int = 0,
    /** 2.1.0: knowledge-base snippets this reply was given (shown as 「引用 n 段知识库」). */
    val kbRefs: Int = 0,
)

/**
 * 2.0.x: one stored (inactive) version of a [ChatMessage]. [tail] is only used for user messages:
 * the messages that followed this version, restored when the user switches back to it.
 */
@Serializable
data class MessageVersion(
    val content: String = "",
    val createdAt: Long = 0,
    val thinkingMs: Long = 0,
    val stats: GenerationStats? = null,
    val modelName: String? = null,
    val interrupted: Boolean = false,
    val error: String? = null,
    val outputChars: Int = 0,
    val outputMs: Long = 0,
    val totalMs: Long = 0,
    val agentSteps: List<com.iris.localai.agent.AgentStep> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val edited: Boolean = false,
    val tail: List<ChatMessage> = emptyList(),
)

@Serializable
enum class AttachmentKind { IMAGE, TEXT, FILE }

/** 1.10.12: one attached file. [text] = its text for the prompt (TEXT only, capped). */
@Serializable
data class Attachment(
    val name: String,
    val path: String,
    val mime: String? = null,
    val sizeBytes: Long = 0,
    val kind: AttachmentKind = AttachmentKind.FILE,
    val text: String? = null,
    val truncated: Boolean = false,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val pinned: Boolean = false,
    /** 1.7.1: [ContextStrategy] ordinal for this conversation; null = the global default. */
    val contextStrategy: Int? = null,
    /** 「从这里开始新上下文」: only messages *after* this id are sent to the model. */
    val contextStartAfterId: String? = null,
    /** Summary of the early part of the conversation (自动摘要), sent as leading context. */
    val summary: String = "",
    /** Last message id covered by [summary]. */
    val summaryUpToId: String? = null,
    /** 1.10.0: deterministic compaction of the early part (no model call); null = not compacted. */
    val compaction: com.iris.localai.context.CompactState? = null,
    /** 1.10.0: long-term memory frozen when this conversation first used it; null = not taken yet. */
    val memorySnapshot: String? = null,
    /** [com.iris.localai.memory.MemorySettings.epoch] when [memorySnapshot] was taken. */
    val memoryEpoch: Int = 0,
)

/** Lightweight index entry so the drawer never has to load full conversations. */
@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

enum class ModelFormat(val label: String, val extensions: Set<String>) {
    GGUF("GGUF · llama.cpp", setOf("gguf")),
    LITERT("LiteRT-LM", setOf("litertlm", "task")),
    /** A folder (taobao-mnn layout: config.json + llm.mnn + llm.mnn.weight + tokenizer.txt …). */
    MNN("MNN", emptySet()),
    /** 2.2.0: a Qualcomm AI Hub (QAIRT / Genie) bundle folder: genie_config.json + *.bin + tokenizer.json. Run by GenieX, NPU only. */
    QAIRT("GenieX · QAIRT", emptySet());

    companion object {
        fun fromFileName(name: String): ModelFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

data class LocalModel(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val format: ModelFormat,
) {
    val id: String get() = fileName
    val displayName: String get() = when (format) {
        ModelFormat.MNN -> ModelNames.displayMnn(fileName)
        ModelFormat.QAIRT -> com.iris.localai.engine.geniex.QairtBundle.displayName(fileName)
        else -> ModelNames.display(fileName)
    }
    val quantization: String? get() = if (isFolder) null else ModelNames.quantization(fileName)
    val isFolder: Boolean get() = format == ModelFormat.MNN || format == ModelFormat.QAIRT
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxNewTokens: Int = 2048,
    val contextLength: Int = 4096,
    /** 0 = automatic (decided by [powerMode]). */
    val threads: Int = 0,
    /** 0 = 省电 (2 threads, decode capped ~10 tok/s, throttles early), 1 = 均衡, 2 = 性能. */
    val powerMode: Int = 1,
    /** Target decode speed in tokens/s set from the composer; 0 = unlimited. */
    val targetTps: Int = 0,
    val flashAttention: Boolean = true,
    /** LiteRT-LM backend: 0 = auto, 1 = CPU, 2 = GPU. */
    val liteRtBackend: Int = 0,
    /** MNN backend: 0 = CPU (ARMv8.2 FP16), 1 = GPU (OpenCL). Falls back to CPU when OpenCL fails. */
    val mnnBackend: Int = 0,
    /** GGUF: run on the Adreno GPU (OpenCL) instead of the CPU. */
    val llamaGpu: Boolean = false,
    /** GGUF: store the KV cache as Q8_0 (needs Flash Attention). Halves KV memory traffic. */
    val kvQuant: Boolean = false,
    val deepThinking: Boolean = true,
    val lastModelFile: String? = null,
    val autoLoadLastModel: Boolean = true,
    /** 1.8.0 crash-diag: skip loading visual.mnn (treat vision models as text-only). Takes effect on next model load. */
    val mnnNoVisual: Boolean = false,
    /** 1.8.0 crash-diag: global override — force off mmap weight mapping on every load. Takes effect on next model load. */
    val mnnForceNoMmap: Boolean = false,
    val keepScreenOn: Boolean = true,

    // ---- Power ----
    /** Unload the model after this many idle minutes (0 = never). Frees RAM and lets the SoC idle. */
    val idleUnloadMinutes: Int = 30,
    /** Frosted glass effects. Always skipped while the system battery saver is on. */
    val glassEffects: Boolean = true,

    // ---- Local API server ----
    val apiEnabled: Boolean = false,
    val apiPort: Int = 8686,
    /** false = 127.0.0.1 only; true = listen on all interfaces (requires an API key). */
    val apiAllowLan: Boolean = false,
    val apiKey: String = "",

    // ---- Voice ----
    val asrLanguage: String = "auto",
    val ttsSpeakerId: Int = 3,
    /** Preferred speech-recognition / speech-synthesis pack ids (VoicePack.id). */
    val asrPack: String = "sense-voice-int8",
    val ttsPack: String = "kokoro-int8-multi-lang-v1_1",
    val ttsSpeed: Float = 1.0f,
    /** Read assistant replies aloud in normal chat (voice mode always speaks). */
    val autoSpeak: Boolean = false,
    val downloadSource: DownloadSource = DownloadSource.HF_MIRROR,
    /** Cloned voice (ZipVoice) used for voice chat and read-aloud; "" = the fixed voice of [ttsPack]. */
    val cloneVoiceId: String = "",
    /** 0 = 快 (few ZipVoice steps), 1 = 像 (more steps: closer to the reference, slower to start). */
    val cloneFidelity: Int = 0,
    val cloneStepsFast: Int = 4,
    val cloneStepsFine: Int = 12,

    // ---- 1.7.0 ----
    /** In-chat performance overlay (speed / CPU / memory / GPU if readable) while generating. */
    val perfOverlay: Boolean = false,
    /** Keep generating in the background (foreground service + progress notification). */
    val backgroundGeneration: Boolean = true,
    val askedNotificationPermission: Boolean = false,
    /** 1.10.13: asked once for 忽略电池优化 (background generation). */
    val askedBatteryExemption: Boolean = false,
    /** Voice chat: how easily talking over the reply interrupts it. 0 = 低, 1 = 中, 2 = 高. */
    val bargeInSensitivity: Int = 1,
    /** Voice chat: play replies as a call (USAGE_VOICE_COMMUNICATION) so the system AEC removes them from the mic. */
    val bargeInVoiceComm: Boolean = false,

    // ---- 1.7.1 ----
    /** [com.iris.localai.engine.ResourceTier] ordinal; -1 = not yet migrated from powerMode / targetTps. */
    val resourceTier: Int = -1,
    /** Default backend per engine ([ModelFormat.name] → "CPU" / "GPU" / "NPU"; missing = 自动). */
    val defaultBackends: Map<String, String> = emptyMap(),
    /** Set once the legacy backend switches (llamaGpu / liteRtBackend / mnnBackend) were moved over. */
    val backendsMigrated: Boolean = false,
    /** KV cache: 0 = F16, 1 = Q8_0, 2 = Q4_0; -1 = not yet migrated from [kvQuant]. */
    val kvCacheType: Int = -1,
    val repeatLastN: Int = 64,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0f,
    /** -1 = random seed. */
    val seed: Int = -1,
    /** Prefill batch; 0 = follow the resource tier. */
    val batchSize: Int = 0,
    val useMmap: Boolean = true,
    /** Run one tiny generation right after loading so the first reply does not pay for page-in / kernel setup. */
    val warmup: Boolean = false,
    /** [ContextStrategy] ordinal; conversations may override it. */
    val contextStrategy: Int = 1,
    /** Sliding window: number of user/assistant rounds to keep. */
    val contextKeepTurns: Int = 8,
    /** Summary strategy: compress once the history needs more than this % of the context budget. */
    val summaryTriggerPercent: Int = 75,
    /** 视觉效果: 0 = 省电, 1 = 均衡, 2 = 华丽; -1 = not yet migrated from [glassEffects]. */
    val visualEffects: Int = -1,
    /** 1.14.1: grey capsule behind the chat top-bar buttons (off: plain icons). */
    val topBarCapsules: Boolean = false,
    /** 1.14.1: 运行状态 rack at the top of 设置. */
    val showStatusRack: Boolean = true,
    /**
     * 1.14.7: first-launch welcome (with the disclaimer) was accepted. Default false, so fresh
     * installs *and* upgrades from older versions (no key in the stored JSON) see it once.
     */
    val welcomeAccepted: Boolean = false,
    /** 2.0.1: 温度限速 — pace generation from this temperature (°C, 35–50); 0 = off. */
    val thermalLimitC: Int = 0,
    /** 2.1.0 引用知识库: 对话 replies quote up to 3 snippets from workspace/knowledge (only when it has files). */
    val knowledgeInChat: Boolean = true,
    /** 2.2 渲染网页代码: a closed ```html / ```svg block (or a whole-reply HTML document) shows as a sandboxed preview card. */
    val renderHtml: Boolean = true,
    /** 2.2 加载网络图片: https images in replies and previews (fetched by the app, public hosts only, no cookies). */
    val loadNetworkImages: Boolean = true,

    // ---- 1.7.8 ----
    /**
     * 资源档配置: "CPU.LOW" / "GPU.HIGH" … → the user's threads / cores / nice for that tier on that
     * backend kind. Missing = generated from the core clusters ([com.iris.localai.engine.TierProfiles]).
     */
    val tierProfiles: Map<String, TierProfile> = emptyMap(),
    /**
     * Reasoning budget in tokens, on top of [maxNewTokens] (thinking no longer eats the answer's
     * limit). 0 = old behaviour: reasoning and answer share [maxNewTokens].
     */
    val thinkingBudget: Int = 2048,

    // ---- 2.1.0 ----
    /** 触感反馈: haptics for sends, steps, long-press, sliders… (ui/feedback/IrisFeedback). */
    val haptics: Boolean = true,
    /** 提示音: tiny soft sounds; never while silenced, in DND or during a voice session. */
    val sound: Boolean = true,

    // ---- 2.2.0 ----
    /** 默认 GGUF 引擎: "llama" (built-in llama.cpp) or "geniex" (Qualcomm GenieX, full flavour); per-model override in ModelOverrides.engine. */
    val ggufEngine: String = "llama",

    // ---- 2.1.4 外观 ----
    /** 配色: [com.iris.localai.ui.theme.IrisPalette.id] — "default" / "sakura" / "mint" / "lilac" / "cream". */
    val themePalette: String = "default",
    /** 自定义背景: file name in filesDir/backgrounds ([com.iris.localai.ui.theme.BackgroundImage]); "" = none. */
    val backgroundImage: String = "",
    /** 模糊程度 of the custom background, dp (0–25). */
    val backgroundBlur: Int = 0,
    /** 遮罩深浅: % of the theme background laid over the picture (0–90). */
    val backgroundScrim: Int = 40,

    // ---- 2.1.7 ----
    /** 「默认模型」: file name loaded by tapping the chat logo (and by auto-load); "" = 上次使用的模型. */
    val defaultModelFile: String = "",
    /** 「识图清晰度」: long side (px) of the picture copy a vision model reads — 384 快速 / 512 标准 / 1024 清晰 ([com.iris.localai.util.VisionQuality]). */
    val visionSide: Int = 512,

    // ---- 2.2 文件 ----
    /** 「文件」 page view: "LIST" (default) or "ARCHIVE" (「档案模式」, [com.iris.localai.files.FileViewStyle]). */
    val fileViewStyle: String = "LIST",
)

/** How a conversation's history is fitted into the model's context window. */
enum class ContextStrategy(val label: String, val hint: String) {
    SLIDING_WINDOW("滑动窗口", "只保留最近 N 轮对话（成对裁剪）"),
    TOKEN_BUDGET("按 token 预算", "保留系统提示、第一轮和尽量多的最近对话"),
    SUMMARY("自动摘要", "超过阈值时用当前模型把早期对话总结成一段，作为前置上下文"),
    KV_SHIFT("KV 上下文平移", "写满时丢弃中间一半 KV、保留开头（attention sink），不重算；仅 GGUF");

    companion object {
        fun of(i: Int?): ContextStrategy = entries.getOrElse(i ?: -1) { TOKEN_BUDGET }
    }
}

@Serializable
enum class DownloadSource(val label: String, val baseUrl: String) {
    HF_MIRROR("国内镜像 hf-mirror.com", "https://hf-mirror.com"),
    HUGGING_FACE("Hugging Face 官方", "https://huggingface.co"),
}

object ModelNames {
    private val QUANT = Regex(
        "(IQ\\d_[A-Z]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_\\d|Q\\d|F16|BF16|F32|INT[48]|q8|q4)",
        RegexOption.IGNORE_CASE,
    )

    fun quantization(fileName: String): String? =
        QUANT.findAll(fileName.substringBeforeLast('.')).lastOrNull()?.value?.uppercase()

    /** "Qwen3-1.7B-MNN" → "Qwen3 1.7B" (the format tag already says MNN). */
    fun displayMnn(folder: String): String {
        val base = folder.replace(Regex("[-_.]?MNN$", RegexOption.IGNORE_CASE), "")
        val cleaned = base.replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ").trim()
        return cleaned.ifEmpty { folder }
    }

    /** "Qwen3-4B-Instruct-Q4_K_M.gguf" → "Qwen3 4B Instruct" */
    fun display(fileName: String): String {
        var base = fileName.substringBeforeLast('.')
        quantization(fileName)?.let { q ->
            base = base.replace(Regex("[-_.]?${Regex.escape(q)}$", RegexOption.IGNORE_CASE), "")
        }
        val cleaned = base.replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ").trim()
        return polish(cleaned).ifEmpty { fileName }
    }

    /** "gemma 4 E2B it qualcomm sm8750" → "Gemma 4 E2B NPU": capitalised, no "it" suffix, chip tag → NPU. */
    fun polish(name: String): String {
        var n = name.replace(Regex("\\s+qualcomm\\s+sm\\d+\\b", RegexOption.IGNORE_CASE), " NPU")
        n = n.replace(Regex("\\s+it(?=\\s|$)"), "")
        n = n.replace(Regex("\\s+"), " ").trim()
        return n.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

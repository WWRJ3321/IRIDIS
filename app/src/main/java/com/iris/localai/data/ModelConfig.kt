package com.iris.localai.data

import android.content.Context
import com.iris.localai.engine.Backend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 1.7.1: per-model settings, keyed by model id (file / folder name). Every field is nullable:
 * null = follow the global default in [AppSettings]. "恢复默认" simply deletes the entry.
 */
@Serializable
data class ModelOverrides(
    /** "CPU" / "GPU" / "NPU"; null = the format's default backend (usually 自动). */
    val backend: String? = null,
    val contextLength: Int? = null,
    val maxNewTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val repeatPenalty: Float? = null,
    val repeatLastN: Int? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    /** -1 = random. */
    val seed: Int? = null,
    /** 0 = follow the resource tier. */
    val threads: Int? = null,
    /** 0 = follow the resource tier. */
    val batchSize: Int? = null,
    /** 0 = F16, 1 = Q8_0, 2 = Q4_0. */
    val kvCacheType: Int? = null,
    val flashAttention: Boolean? = null,
    val useMmap: Boolean? = null,
    val warmup: Boolean? = null,
    val thinking: Boolean? = null,
    /** Replaces the global 自定义指令 for this model when non-null. */
    val systemPrompt: String? = null,
    /** 2.2.0, GGUF only: "llama" / "geniex". 2.1.3: no longer chosen; old values are migrated ([com.iris.localai.engine.geniex.GgufEngineRule.migrate]). */
    val engine: String? = null,
    /** 2.2.17, GGUF: vision projector file name in the model's folder; "" = 不使用; null = 自动配对. */
    val mmproj: String? = null,
    /** 2.5.0-beta9, GGUF with MTP layers: MTP 加速（实验）. null = off (default). */
    val mtp: Boolean? = null,
    /** 2.5.1, GGUF MoE: 专家按需加载。null = 自动（MoE 且内存预检不足时开）。 */
    val moeCpu: Boolean? = null,
    /** 2.5.1, GGUF MoE: 激活专家数，0 / null = 模型默认。 */
    val expertUsed: Int? = null,
    /** 2.5.4, GGUF qwen35moe: LES 前瞻专家调度。null = 自动（专家按需加载生效时开）。 */
    val les: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == ModelOverrides()

    fun sanitized(): ModelOverrides = copy(
        backend = Backend.parse(backend)?.name,
        contextLength = contextLength?.coerceIn(512, 131072),
        maxNewTokens = maxNewTokens?.let { TokenLimit.sanitize(it) },
        temperature = temperature?.clampOr(0f, 2f, 0.7f),
        topP = topP?.clampOr(0.05f, 1f, 0.95f),
        topK = topK?.coerceIn(0, 200),
        minP = minP?.clampOr(0f, 0.5f, 0.05f),
        repeatPenalty = repeatPenalty?.clampOr(1f, 2f, 1.1f),
        repeatLastN = repeatLastN?.coerceIn(0, 2048),
        presencePenalty = presencePenalty?.clampOr(-2f, 2f, 0f),
        frequencyPenalty = frequencyPenalty?.clampOr(-2f, 2f, 0f),
        seed = seed?.coerceAtLeast(-1),
        threads = threads?.coerceIn(0, 16),
        batchSize = batchSize?.coerceIn(0, 2048),
        kvCacheType = kvCacheType?.coerceIn(0, 2),
        engine = com.iris.localai.engine.geniex.GenieX.parseEngine(engine),
        expertUsed = expertUsed?.coerceIn(0, com.iris.localai.engine.MoeLoad.MAX_EXPERTS_UI),
    )

    /** Keys whose change only takes effect after reloading the model. */
    fun loadSignature(): List<Any?> = listOf(backend, contextLength, batchSize, kvCacheType, flashAttention, useMmap, threads, engine, mmproj, mtp, moeCpu, expertUsed, les)
}

/** Global defaults + one model's overrides, fully resolved. */
data class EffectiveModelConfig(
    /** null = 自动. */
    val backend: Backend?,
    val contextLength: Int,
    val maxNewTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val seed: Int,
    val threads: Int,
    val batchSize: Int,
    val kvCacheType: Int,
    val flashAttention: Boolean,
    val useMmap: Boolean,
    val warmup: Boolean,
    val thinking: Boolean,
    val systemPrompt: String,
    /** 2.2.0: engine for GGUF files ([com.iris.localai.engine.geniex.GenieX.ENGINE_LLAMA] / ENGINE_GENIEX). */
    val engine: String = com.iris.localai.engine.geniex.GenieX.ENGINE_LLAMA,
    /** 2.2.17: [ModelOverrides.mmproj] as is (null = 自动). */
    val mmproj: String? = null,
    /** 2.5.0-beta9: MTP speculative decoding requested (only used when the GGUF has MTP layers). */
    val mtp: Boolean = false,
    /** 2.5.1: [ModelOverrides.moeCpu] as is (null = 自动). */
    val moeCpu: Boolean? = null,
    /** 2.5.1: 激活专家数，0 = 模型默认. */
    val expertUsed: Int = 0,
    /** 2.5.4: [ModelOverrides.les] as is (null = 自动). */
    val les: Boolean? = null,
) {
    companion object {
        fun resolve(s: AppSettings, format: ModelFormat?, o: ModelOverrides?): EffectiveModelConfig {
            val m = o ?: ModelOverrides()
            val formatDefault = format?.let { Backend.parse(s.defaultBackends[it.name]) }
            return EffectiveModelConfig(
                backend = Backend.parse(m.backend) ?: formatDefault,
                contextLength = m.contextLength ?: s.contextLength,
                maxNewTokens = m.maxNewTokens ?: s.maxNewTokens,
                temperature = m.temperature ?: s.temperature,
                topP = m.topP ?: s.topP,
                topK = m.topK ?: s.topK,
                minP = m.minP ?: s.minP,
                repeatPenalty = m.repeatPenalty ?: s.repeatPenalty,
                repeatLastN = m.repeatLastN ?: s.repeatLastN,
                presencePenalty = m.presencePenalty ?: s.presencePenalty,
                frequencyPenalty = m.frequencyPenalty ?: s.frequencyPenalty,
                seed = m.seed ?: s.seed,
                threads = m.threads ?: s.threads,
                batchSize = m.batchSize ?: s.batchSize,
                kvCacheType = m.kvCacheType ?: s.kvCacheType.coerceAtLeast(0),
                flashAttention = m.flashAttention ?: s.flashAttention,
                useMmap = m.useMmap ?: s.useMmap,
                warmup = m.warmup ?: s.warmup,
                thinking = m.thinking ?: s.deepThinking,
                systemPrompt = m.systemPrompt ?: s.systemPrompt,
                engine = com.iris.localai.engine.geniex.GenieX.parseEngine(m.engine)
                    ?: com.iris.localai.engine.geniex.GenieX.parseEngine(s.ggufEngine)
                    ?: com.iris.localai.engine.geniex.GenieX.ENGINE_LLAMA,
                mmproj = m.mmproj,
                mtp = m.mtp ?: false,
                moeCpu = m.moeCpu,
                expertUsed = m.expertUsed ?: 0,
                les = m.les,
            )
        }
    }
}

/** Per-model overrides, one small JSON file, written atomically. */
class ModelConfigRepository(context: Context) {
    private val file = File(context.filesDir, "model_configs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = MapSerializer(String.serializer(), ModelOverrides.serializer())
    private val lock = Any()
    private val _configs = MutableStateFlow(read())
    val configs: StateFlow<Map<String, ModelOverrides>> = _configs.asStateFlow()

    fun get(modelId: String?): ModelOverrides = modelId?.let { _configs.value[it] } ?: ModelOverrides()

    fun update(modelId: String, block: (ModelOverrides) -> ModelOverrides) = synchronized(lock) {
        val next = com.iris.localai.engine.geniex.GgufEngineRule.migrate(block(get(modelId)).sanitized())
        val map = _configs.value.toMutableMap()
        if (next.isEmpty) map.remove(modelId) else map[modelId] = next
        if (map == _configs.value) return@synchronized
        _configs.value = map
        write(map)
    }

    fun reset(modelId: String) = update(modelId) { ModelOverrides() }

    private fun read(): Map<String, ModelOverrides> = runCatching {
        if (!file.isFile) emptyMap() else json.decodeFromString(serializer, file.readText())
            // 2.1.3: an old per-model 「推理引擎」 is read once and folded into the backend.
            .mapValues { com.iris.localai.engine.geniex.GgufEngineRule.migrate(it.value.sanitized()) }
            .filterValues { !it.isEmpty }
    }.getOrDefault(emptyMap())

    private fun write(map: Map<String, ModelOverrides>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}


/** 2.2.14: 「无限」 max reply length — a sentinel above the slider's range. */
object TokenLimit {
    const val UNLIMITED = 1_000_000
    const val LABEL = "无限"

    fun isUnlimited(n: Int): Boolean = n >= UNLIMITED
    fun sanitize(n: Int): Int = if (isUnlimited(n)) UNLIMITED else n.coerceIn(64, 16384)
    fun label(n: Int, unit: String = ""): String = if (isUnlimited(n)) LABEL else "$n$unit"

    /** Slider position: 「无限」 sits one [step] past [max]. */
    fun toSlider(n: Int, max: Int, step: Int): Float = if (isUnlimited(n)) (max + step).toFloat() else n.toFloat()
    fun fromSlider(v: Float, max: Int, step: Int): Int =
        if (v > max + step / 2f) UNLIMITED else ((v / step).toInt() * step).coerceIn(step, max)

    /**
     * What the engine gets: llama.cpp stops at the context end itself (or shifts), other engines
     * get the context length so nothing allocates for a million tokens.
     */
    fun resolve(n: Int, contextLength: Int, llama: Boolean): Int =
        if (!isUnlimited(n)) n else if (llama) UNLIMITED else contextLength.coerceAtLeast(256)
}

package com.iris.localai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Settings persisted as one JSON blob in SharedPreferences; unknown/old keys are tolerated. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("iris_settings_v2", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    /**
     * Atomic read-modify-write. The engine (background threads) and the UI both update settings;
     * without the lock two concurrent updates could drop one another's change, and the older
     * value could be the one persisted.
     */
    fun update(block: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val next = block(_settings.value).sanitized()
            if (next == _settings.value) return
            _settings.value = next
            prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), next)).apply()
        }
    }

    /**
     * 1.7.8 (设置整理): 「恢复默认」 on 设置 › 生成 — sampling, answer length and reasoning budget only.
     * Keys and default values are unchanged; what used to be reset together with them (context
     * length, load options, default backends) now has its own reset on its own page.
     */
    fun resetGeneration() = update {
        val d = AppSettings()
        it.copy(
            temperature = d.temperature, topP = d.topP, topK = d.topK, minP = d.minP,
            repeatPenalty = d.repeatPenalty, maxNewTokens = d.maxNewTokens,
            repeatLastN = d.repeatLastN, presencePenalty = d.presencePenalty,
            frequencyPenalty = d.frequencyPenalty, seed = d.seed, thinkingBudget = d.thinkingBudget,
        )
    }

    /** 1.7.8: 「恢复默认」 on 设置 › 模型与后端 — default backends and load options. */
    fun resetLoadOptions() = update {
        val d = AppSettings()
        it.copy(
            flashAttention = d.flashAttention, llamaGpu = d.llamaGpu, kvQuant = d.kvQuant,
            kvCacheType = 0, batchSize = d.batchSize, useMmap = d.useMmap, warmup = d.warmup,
            defaultBackends = emptyMap(),
        )
    }

    /** 1.7.8: 「恢复默认」 on 设置 › 上下文与记忆. */
    fun resetContext() = update {
        val d = AppSettings()
        it.copy(
            contextLength = d.contextLength, contextStrategy = d.contextStrategy,
            contextKeepTurns = d.contextKeepTurns, summaryTriggerPercent = d.summaryTriggerPercent,
        )
    }

    private fun read(): AppSettings = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        ?.migrated()
        ?.sanitized()
        ?: AppSettings().migrated()

    private companion object {
        const val KEY = "settings"
    }
}

/** NaN-safe clamp: a corrupted or hand-edited value never reaches the native samplers. */
internal fun Float.clampOr(min: Float, max: Float, fallback: Float): Float =
    if (isNaN()) fallback else coerceIn(min, max)

/** Every field brought into its valid range (also used for values that arrive over the API). */
fun AppSettings.sanitized(): AppSettings {
    val d = AppSettings()
    return copy(
        temperature = temperature.clampOr(0f, 2f, d.temperature),
        topP = topP.clampOr(0.05f, 1f, d.topP),
        topK = topK.coerceIn(0, 200),
        minP = minP.clampOr(0f, 0.5f, d.minP),
        repeatPenalty = repeatPenalty.clampOr(1f, 2f, d.repeatPenalty),
        maxNewTokens = maxNewTokens.coerceIn(64, 16384),
        contextLength = contextLength.coerceIn(512, 131072),
        threads = threads.coerceIn(0, 12),
        powerMode = powerMode.coerceIn(0, 2),
        targetTps = targetTps.coerceIn(0, 60),
        thermalLimitC = com.iris.localai.engine.ThermalGovernor.sanitize(thermalLimitC),
        liteRtBackend = liteRtBackend.coerceIn(0, 2),
        mnnBackend = mnnBackend.coerceIn(0, 1),
        idleUnloadMinutes = idleUnloadMinutes.coerceIn(0, 240),
        apiPort = apiPort.coerceIn(1024, 65535),
        ttsSpeed = ttsSpeed.clampOr(0.5f, 2f, d.ttsSpeed),
        ttsSpeakerId = ttsSpeakerId.coerceIn(0, 102),
        asrLanguage = asrLanguage.takeIf { it.isNotBlank() && it != "ja" && it != "ko" } ?: d.asrLanguage, // 2.0.1: 日语 / 韩语 removed
        asrPack = asrPack.ifBlank { d.asrPack },
        ttsPack = ttsPack.ifBlank { d.ttsPack },
        lastModelFile = lastModelFile?.takeIf { it.isNotBlank() },
        cloneFidelity = cloneFidelity.coerceIn(0, 1),
        bargeInSensitivity = bargeInSensitivity.coerceIn(0, 2),
        cloneStepsFast = cloneStepsFast.coerceIn(2, 8),
        cloneStepsFine = cloneStepsFine.coerceIn(6, 32),
        // 1.7.8: three tiers (「全部」 removed); a stale 3 lands on HIGH (= 2), see migrated().
        resourceTier = resourceTier.coerceIn(-1, com.iris.localai.engine.ResourceTier.entries.size - 1),
        tierProfiles = com.iris.localai.engine.TierProfiles.sanitize(tierProfiles),
        thinkingBudget = thinkingBudget.coerceIn(0, 32768),
        defaultBackends = defaultBackends.filter { (k, v) ->
            ModelFormat.entries.any { it.name == k } && com.iris.localai.engine.Backend.parse(v) != null
        },
        kvCacheType = kvCacheType.coerceIn(-1, 2),
        ggufEngine = com.iris.localai.engine.geniex.GenieX.parseEngine(ggufEngine) ?: d.ggufEngine,
        repeatLastN = repeatLastN.coerceIn(0, 2048),
        presencePenalty = presencePenalty.clampOr(-2f, 2f, d.presencePenalty),
        frequencyPenalty = frequencyPenalty.clampOr(-2f, 2f, d.frequencyPenalty),
        seed = seed.coerceAtLeast(-1),
        batchSize = batchSize.coerceIn(0, 2048),
        contextStrategy = contextStrategy.coerceIn(0, ContextStrategy.entries.size - 1),
        contextKeepTurns = contextKeepTurns.coerceIn(1, 64),
        summaryTriggerPercent = summaryTriggerPercent.coerceIn(40, 95),
        visualEffects = visualEffects.coerceIn(-1, 2),
        themePalette = themePalette.takeIf { com.iris.localai.ui.theme.IrisPalette.isValid(it) } ?: d.themePalette,
        backgroundImage = backgroundImage.takeIf { com.iris.localai.ui.theme.BackgroundImage.isValidName(it) } ?: "",
        backgroundBlur = backgroundBlur.coerceIn(0, com.iris.localai.ui.theme.BackgroundImage.MAX_BLUR_DP),
        backgroundScrim = backgroundScrim.coerceIn(0, com.iris.localai.ui.theme.BackgroundImage.MAX_SCRIM),
        fileViewStyle = com.iris.localai.files.FileViewStyle.parse(fileViewStyle).name,
    )
}

/**
 * One-time moves of ≤ 1.7.0 settings (idempotent; a fresh install goes through it too):
 *  - 功耗档位 / 速率 target → resource tier ([com.iris.localai.engine.ResourcePlanner.migrateLegacy]);
 *  - the scattered backend switches → one default backend per engine (GGUF OpenCL switch → GPU,
 *    LiteRT 自动/CPU/GPU, MNN CPU/OpenCL); NPU files still default to NPU through 自动;
 *  - KV Q8 switch → KV cache type; glass switch → 视觉效果 (on = 均衡, off = 省电);
 *  - 1.7.8: resource tier 「全部」 (3) → 高 (2).
 */
fun AppSettings.migrated(): AppSettings {
    var s = this
    if (s.resourceTier < 0) {
        s = s.copy(resourceTier = com.iris.localai.engine.ResourcePlanner.migrateLegacy(s.powerMode, s.targetTps).ordinal)
    }
    // 1.7.8: 「全部」 (ordinal 3) was removed → 高.
    if (s.resourceTier >= com.iris.localai.engine.ResourceTier.entries.size) {
        s = s.copy(resourceTier = com.iris.localai.engine.ResourceTier.HIGH.ordinal)
    }
    if (!s.backendsMigrated) {
        val m = s.defaultBackends.toMutableMap()
        if (s.llamaGpu) m.putIfAbsent(ModelFormat.GGUF.name, "GPU")
        when (s.liteRtBackend) {
            1 -> m.putIfAbsent(ModelFormat.LITERT.name, "CPU")
            2 -> m.putIfAbsent(ModelFormat.LITERT.name, "GPU")
        }
        if (s.mnnBackend == 1) m.putIfAbsent(ModelFormat.MNN.name, "GPU")
        s = s.copy(defaultBackends = m, backendsMigrated = true)
    }
    if (s.kvCacheType < 0) s = s.copy(kvCacheType = if (s.kvQuant) 1 else 0)
    if (s.visualEffects < 0) s = s.copy(visualEffects = if (s.glassEffects) 1 else 0)
    // 2.1.3: 「GGUF 默认引擎 = GenieX」 is gone; the backend decides (NPU = GenieX). Keep the user's choice as the GGUF default backend.
    if (com.iris.localai.engine.geniex.GenieX.parseEngine(s.ggufEngine) == com.iris.localai.engine.geniex.GenieX.ENGINE_GENIEX) {
        val b = com.iris.localai.engine.geniex.GgufEngineRule.migrateDefaultBackend(s.ggufEngine, s.defaultBackends[ModelFormat.GGUF.name])
        s = s.copy(
            defaultBackends = if (b != null) s.defaultBackends + (ModelFormat.GGUF.name to b) else s.defaultBackends,
            ggufEngine = com.iris.localai.engine.geniex.GenieX.ENGINE_LLAMA,
        )
    }
    return s
}

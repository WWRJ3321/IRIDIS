package com.iris.localai.ui.models

import com.iris.localai.data.ModelOverrides
import com.iris.localai.engine.EngineFeatures

/**
 * 2.5.0 加载预设 in the per-model load sheet: one tap sets context length / KV cache / Flash
 * Attention; every option stays adjustable one by one under 「高级」. Pure (unit-tested).
 * Only the four fields below are touched; backend, sampling, prompt etc. are left as they are.
 */
enum class LoadPreset(val labelRes: Int) {
    DEFAULT(com.iris.localai.R.string.v250_preset_default),
    SAVER(com.iris.localai.R.string.v250_preset_saver),
    BALANCED(com.iris.localai.R.string.v250_preset_balanced),
    LONG(com.iris.localai.R.string.v250_preset_long);

    /** The overrides with this preset applied. */
    fun applyTo(o: ModelOverrides, f: EngineFeatures): ModelOverrides {
        val ctxFixed = f.fixedContext != null
        return when (this) {
            DEFAULT -> o.copy(contextLength = null, kvCacheType = null, flashAttention = null, batchSize = null)
            SAVER -> o.copy(
                contextLength = if (ctxFixed) null else 2048,
                kvCacheType = if (f.kvQuant && f.flashAttention) 1 else null,
                flashAttention = if (f.flashAttention) true else null,
                batchSize = if (f.batchSize) 256 else null,
            )
            BALANCED -> o.copy(contextLength = if (ctxFixed) null else 4096, kvCacheType = null, flashAttention = null, batchSize = null)
            LONG -> o.copy(
                contextLength = if (ctxFixed) null else 16384,
                kvCacheType = if (f.kvQuant && f.flashAttention) 1 else null,
                flashAttention = if (f.flashAttention) true else null,
                batchSize = null,
            )
        }
    }

    companion object {
        /** The preset the overrides currently match, or null (= 自定义). */
        fun match(o: ModelOverrides, f: EngineFeatures): LoadPreset? =
            entries.firstOrNull { p -> p.applyTo(o, f) == o }
    }
}

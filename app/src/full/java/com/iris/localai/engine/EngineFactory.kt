package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GenieXEngine
import com.iris.localai.engine.geniex.GenieXRuntime
import com.iris.localai.engine.geniex.GenieXSupport
import com.iris.localai.engine.litert.LiteRtEngine
import com.iris.localai.engine.llama.LlamaEngine
import com.iris.localai.engine.mnn.MnnEngine

/**
 * "full" flavour: GGUF (llama.cpp) + LiteRT-LM 0.18 + MNN-LLM 3.6.1, and (2.2.0) Qualcomm GenieX 0.8.0
 * for GGUF (when chosen per model / as the default GGUF engine) and AI Hub QAIRT bundle folders.
 */
object EngineFactory {
    val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF, ModelFormat.LITERT, ModelFormat.MNN, ModelFormat.QAIRT)

    fun npu(context: Context): NpuSupport = com.iris.localai.engine.litert.QualcommNpuRuntime.get(context)

    /** 2.2.0: the downloadable GenieX runtime. */
    fun geniex(context: Context): GenieXSupport = GenieXRuntime.get(context)

    fun create(context: Context, format: ModelFormat, engine: String = GenieX.ENGINE_LLAMA): InferenceEngine = when (format) {
        ModelFormat.GGUF -> if (engine == GenieX.ENGINE_GENIEX) GenieXEngine(context) else com.iris.localai.engine.llama.LlamaEngine(context)
        ModelFormat.LITERT -> LiteRtEngine(context)
        ModelFormat.MNN -> MnnEngine(context)
        ModelFormat.QAIRT -> GenieXEngine(context)
    }
}

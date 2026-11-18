package com.iris.localai.engine

import com.iris.localai.engine.edge0.Edge0Engine
import com.iris.localai.engine.litert.MediaTekNpuRuntime
import com.iris.localai.engine.litert.QualcommNpuRuntime
import com.iris.localai.engine.load.*
import com.iris.localai.engine.resources.*
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
    val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF, ModelFormat.LITERT, ModelFormat.MNN, ModelFormat.QAIRT, ModelFormat.EDGE0)

    fun npu(context: Context): NpuSupport =
        // Issue #6: Dimensity phones get the MediaTek NeuroPilot runtime, everything else the Qualcomm one.
        if (MediaTekNpuRuntime.isMediatek()) MediaTekNpuRuntime.get(context)
        else QualcommNpuRuntime.get(context)

    /** the downloadable GenieX runtime. */
    fun geniex(context: Context): GenieXSupport = GenieXRuntime.get(context)

    fun create(context: Context, format: ModelFormat, engine: String = GenieX.ENGINE_LLAMA): InferenceEngine = when (format) {
        ModelFormat.GGUF -> if (engine == GenieX.ENGINE_GENIEX) GenieXEngine(context) else LlamaEngine(context)
        ModelFormat.LITERT -> LiteRtEngine(context)
        ModelFormat.MNN -> MnnEngine(context)
        ModelFormat.QAIRT -> GenieXEngine(context)
        ModelFormat.EDGE0 -> Edge0Engine()
    }
}

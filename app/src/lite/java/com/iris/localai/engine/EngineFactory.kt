package com.iris.localai.engine

import com.iris.localai.engine.load.*
import com.iris.localai.engine.resources.*
import android.content.Context
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GenieXSupport
import com.iris.localai.engine.llama.LlamaEngine

/**
 * "lite" flavour (default, ~26 MB smaller): GGUF only. Google's LiteRT-LM runtime is the
 * single largest native library in the app, and every model it runs is also available as GGUF.
 */
object EngineFactory {
    val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF)

    fun npu(context: Context): NpuSupport? = null

    /** GenieX is full-flavour only. */
    fun geniex(context: Context): GenieXSupport? = null

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, format: ModelFormat, engine: String = GenieX.ENGINE_LLAMA): InferenceEngine = when (format) {
        // A GenieX choice stored by a full build (backup import) runs on llama.cpp here.
        ModelFormat.GGUF -> LlamaEngine()
        ModelFormat.LITERT -> throw EngineException("此版本仅支持 GGUF 模型。LiteRT-LM (.litertlm) 需安装 full 版本。")
        ModelFormat.MNN -> throw EngineException("此版本仅支持 GGUF 模型。MNN 模型需安装 full 版本。")
        ModelFormat.QAIRT -> throw EngineException("此版本仅支持 GGUF 模型。GenieX / AI Hub QAIRT 包需安装 full 版本。")
        ModelFormat.EDGE0 -> throw EngineException("此版本仅支持 GGUF 模型。Edge0 需安装 full 版本。")
    }
}

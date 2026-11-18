package com.iris.localai.ui.chat

import com.iris.localai.data.ModelFormat

/**
 * rules for prefilling the open conversation in the background after a model switch.
 * KV caches belong to one model, so the first question after switching used to prefill the whole
 * conversation (plus the 智能体 tool prompt) while the user waited. MNN (prompt cache) and
 * llama.cpp (prefix reuse) keep a shared prefix, so prefilling it early makes that wait disappear.
 * LiteRT-LM keeps its own conversation object and is left alone.
 */
object WarmUp {
    fun supported(format: ModelFormat?): Boolean = format == ModelFormat.MNN || format == ModelFormat.GGUF

    /** Only a finished conversation (ends with a reply) is worth prefilling. */
    fun worthIt(messageCount: Int, endsWithReply: Boolean): Boolean = messageCount >= 2 && endsWithReply
}

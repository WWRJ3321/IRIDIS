package com.iris.localai.knowledge

import androidx.annotation.Keep

/**
 * 2.5.1: JNI surface of the 知识库 embedder (app/src/main/cpp/jni_embed.cpp, inside libiris_llama /
 * libiris_llama_compat). Loading the library goes through [com.iris.localai.engine.llama.LlamaNative] (CPU check,
 * compat fallback); the embedder has its own llama_model + context and never touches the chat engine.
 */
@Keep
object KbEmbedNative {
    /** null when usable. */
    val unavailableReason: String? get() = com.iris.localai.engine.llama.LlamaNative.unavailableReason

    /** Handle (0 = failed). CPU, mmap, embeddings on, pooling from the model, n_ctx 2048. */
    @JvmStatic external fun nativeEmbedLoad(pathUtf8: ByteArray, threads: Int): Long
    /** L2-normalised vector, or null on failure. */
    @JvmStatic external fun nativeEmbed(handle: Long, textUtf8: ByteArray): FloatArray?
    @JvmStatic external fun nativeEmbedDim(handle: Long): Int
    @JvmStatic external fun nativeEmbedFree(handle: Long)

    fun load(path: String, threads: Int): Long =
        if (unavailableReason != null) 0L else runCatching { nativeEmbedLoad(path.toByteArray(Charsets.UTF_8), threads) }.getOrDefault(0L)

    fun embed(handle: Long, text: String): FloatArray? =
        if (handle == 0L) null else runCatching { nativeEmbed(handle, text.toByteArray(Charsets.UTF_8)) }.getOrNull()

    fun free(handle: Long) { if (handle != 0L) runCatching { nativeEmbedFree(handle) } }
}

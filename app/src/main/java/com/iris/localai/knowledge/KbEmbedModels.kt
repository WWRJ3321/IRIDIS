package com.iris.localai.knowledge

import java.io.File

/**
 * 2.5.1 知识库嵌入模型: the two downloadable embedding GGUFs (下载模型 › 知识库嵌入模型). They are downloaded like
 * any catalog GGUF (models/<name>/<file>) but are never chat models: [isEmbedFile] keeps them out of the model
 * list (ModelRepository.refresh) and away from 「下载完自动加载」. Pure except [locate] / [catalogItems].
 */
object KbEmbedModels {
    data class Model(
        /** Stored in the vector file and the preference; a different id = vectors rebuilt. */
        val id: String,
        val name: String,
        val repo: String,
        val file: String,
        val size: Long,
        /** Cosine gate for a hybrid hit ([KbVectors.select]). */
        val minCos: Double,
        val lowMemory: Boolean,
    ) {
        fun query(q: String): String = when (id) {
            QWEN3 -> "Instruct: Given a question, retrieve relevant passages that answer the question\nQuery: " + q
            GEMMA -> "task: search result | query: " + q
            else -> q
        }

        fun doc(t: String): String = when (id) {
            GEMMA -> "title: none | text: " + t
            else -> t
        }
    }

    const val QWEN3 = "qwen3-embedding-0.6b-q8_0"
    const val GEMMA = "embeddinggemma-2-q8_0"

    /** 2.5.1 余弦门槛 (normalised vectors). */
    const val QWEN3_MIN_COS = 0.45
    const val GEMMA_MIN_COS = 0.40

    /** Sizes from the HF tree API 2026-11-12 (Qwen/Qwen3-Embedding-0.6B-GGUF, ggml-org/embeddinggemma-2-GGUF). */
    val QWEN3_MODEL = Model(QWEN3, "Qwen3-Embedding-0.6B", "Qwen/Qwen3-Embedding-0.6B-GGUF", "Qwen3-Embedding-0.6B-Q8_0.gguf", 639_150_592L, QWEN3_MIN_COS, lowMemory = false)
    val GEMMA_MODEL = Model(GEMMA, "EmbeddingGemma 2", "ggml-org/embeddinggemma-2-GGUF", "embeddinggemma-2-Q8_0.gguf", 309_855_456L, GEMMA_MIN_COS, lowMemory = true)

    /** Default first. */
    val ALL: List<Model> = listOf(QWEN3_MODEL, GEMMA_MODEL)

    fun byId(id: String?): Model? = ALL.firstOrNull { it.id == id }

    /** A model file (or its folder) that is one of [ALL]: never listed as a chat model. */
    fun isEmbedFile(name: String): Boolean = ALL.any { it.file.equals(name, ignoreCase = true) }

    /** The folder MnnHub.downloadGguf writes a catalog GGUF into (GgufFolder.folderFor). */
    fun folderOf(m: Model): String = m.file.removeSuffix(".gguf")

    /** Model folders to look in (ModelDirs.Location.listDirs), wired at app start. */
    @Volatile var dirs: () -> List<File> = { emptyList() }

    /** The downloaded file of [m] (models/<name>/<file>, or loose in a models folder), or null. */
    fun locate(m: Model, roots: List<File> = runCatching { dirs() }.getOrDefault(emptyList())): File? =
        roots.asSequence().flatMap { r -> sequenceOf(File(File(r, folderOf(m)), m.file), File(r, m.file)) }
            .firstOrNull { it.isFile && it.length() == m.size }

    fun installed(): List<Model> = ALL.filter { locate(it) != null }

    /**
     * The model to use: the user's [chosen] id when downloaded; [OFF] = none; otherwise the first downloaded in
     * [ALL] order (Qwen3 by default). Pure on [installedIds].
     */
    fun pick(chosen: String?, installedIds: Collection<String>): Model? = when {
        chosen == OFF -> null
        chosen != null && chosen in installedIds -> byId(chosen)
        else -> ALL.firstOrNull { it.id in installedIds }
    }

    const val OFF = "off"

    /** Downloaded per the catalog card of [repo] (used by 下载模型, where these files are not in the model list). */
    fun isDownloaded(repo: String): Boolean = ALL.firstOrNull { it.repo == repo }?.let { locate(it) != null } == true

    /** 下载模型 › 知识库嵌入模型: one Q8_0 file each, no projector. */
    fun catalogItems(): List<com.iris.localai.data.RecommendedCatalog.Item> = ALL.map { m ->
        com.iris.localai.data.RecommendedCatalog.Item(
            m.repo, com.iris.localai.data.RecommendedCatalog.Section.EMBED, m.name,
            com.iris.localai.I18n.s(if (m.id == QWEN3) com.iris.localai.R.string.kb251_embed_qwen else com.iris.localai.R.string.kb251_embed_gemma),
            m.size, com.iris.localai.data.RecommendedCatalog.Engine.GGUF,
            com.iris.localai.data.RecommendedCatalog.Kind.Gguf(m.repo, m.file, m.size),
            top = true, quant = "Q8_0", licence = "Apache-2.0",
        )
    }
}

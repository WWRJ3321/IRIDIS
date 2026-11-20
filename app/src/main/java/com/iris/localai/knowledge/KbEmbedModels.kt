package com.iris.localai.knowledge

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.RecommendedCatalog
import java.io.File

/**
 * 知识库嵌入模型: the downloadable embedding GGUFs (2.5.2: Q8_0 and a smaller Q4 of each) (下载模型 › 知识库嵌入模型). They are downloaded like
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
        /** prompt family ([QWEN3] / [GEMMA]); the Q4 files use the same prefixes as their Q8_0. */
        val family: String = id,
        /** quant shown in the title and the detail sheet. */
        val quant: String = "Q8_0",
        /** one-line purpose (string resource id). */
        val purposeRes: Int = 0,
    ) {
        fun query(q: String): String = when (family) {
            QWEN3 -> "Instruct: Given a question, retrieve relevant passages that answer the question\nQuery: " + q
            GEMMA -> "task: search result | query: " + q
            else -> q
        }

        fun doc(t: String): String = when (family) {
            GEMMA -> "title: none | text: " + t
            else -> t
        }
    }

    const val QWEN3 = "qwen3-embedding-0.6b-q8_0"
    const val GEMMA = "embeddinggemma-2-q8_0"
    /** Q4 files: own ids (a switch between Q8_0 and Q4 rebuilds the vectors). */
    const val QWEN3_Q4 = "qwen3-embedding-0.6b-q4_k_m"
    const val GEMMA_Q4 = "embeddinggemma-2-q4_k_xl"

    /** 余弦门槛 (normalised vectors). */
    const val QWEN3_MIN_COS = 0.45
    const val GEMMA_MIN_COS = 0.40

    /** Sizes from the HF tree API 2026-11-12 (Qwen/Qwen3-Embedding-0.6B-GGUF, ggml-org/embeddinggemma-2-GGUF). */
    val QWEN3_MODEL = Model(QWEN3, "Qwen3-Embedding-0.6B · Q8", "Qwen/Qwen3-Embedding-0.6B-GGUF", "Qwen3-Embedding-0.6B-Q8_0.gguf", 639_150_592L, QWEN3_MIN_COS, lowMemory = false,
        family = QWEN3, quant = "Q8_0", purposeRes = R.string.kb251_embed_qwen)
    val GEMMA_MODEL = Model(GEMMA, "EmbeddingGemma 2 · Q8", "ggml-org/embeddinggemma-2-GGUF", "embeddinggemma-2-Q8_0.gguf", 309_855_456L, GEMMA_MIN_COS, lowMemory = true,
        family = GEMMA, quant = "Q8_0", purposeRes = R.string.kb251_embed_gemma)

    /**
     * Q4 (更省内存), sizes as given 2026-11-12 (PeterAM4/Qwen3-Embedding-0.6B-GGUF, unsloth/embeddinggemma-2-GGUF).
     * The third-party Qwen3 GGUF has tokenizer.ggml.add_eos_token=false; iris_embed.cpp appends the EOS itself for LAST pooling.
     */
    val QWEN3_Q4_MODEL = Model(QWEN3_Q4, "Qwen3-Embedding-0.6B · Q4", "PeterAM4/Qwen3-Embedding-0.6B-GGUF", "Qwen3-Embedding-0.6B-Q4_K_M-imat.gguf", 396_474_336L, QWEN3_MIN_COS, lowMemory = true,
        family = QWEN3, quant = "Q4_K_M", purposeRes = R.string.kb252_embed_qwen_q4)
    val GEMMA_Q4_MODEL = Model(GEMMA_Q4, "EmbeddingGemma 2 · Q4", "unsloth/embeddinggemma-2-GGUF", "embeddinggemma-2-UD-Q4_K_XL.gguf", 175_673_856L, GEMMA_MIN_COS, lowMemory = true,
        family = GEMMA, quant = "Q4_K_XL", purposeRes = R.string.kb252_embed_gemma_q4)

    /** Default first (Qwen3 Q8_0); 2.5.2: automatic pick prefers Q8_0 over Q4 of the same family, Qwen3 over Gemma. */
    val ALL: List<Model> = listOf(QWEN3_MODEL, QWEN3_Q4_MODEL, GEMMA_MODEL, GEMMA_Q4_MODEL)

    fun byId(id: String?): Model? = ALL.firstOrNull { it.id == id }

    /** A model file (or its folder) that is one of [ALL]: never listed as a chat model. */
    fun isEmbedFile(name: String): Boolean = ALL.any { it.file.equals(name, ignoreCase = true) }

    /** the file or its catalog folder (存储空间 classifies loose entries by name). */
    fun isEmbedName(name: String): Boolean = isEmbedFile(name) || ALL.any { folderOf(it).equals(name, ignoreCase = true) }

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
     * 2.7.2：模型文件指纹「大小-头尾 SHA-256 前 16 位」（读头尾各 [FP_SPAN] 字节，不读整个文件）。同一个 id 换了文件
     * （例如以后换成别的量化或 EmbeddingGemma 270M）指纹就变，向量据此重建。按路径 + 大小 + 修改时间缓存。空串 = 读不了。
     */
    fun fingerprint(f: File): String {
        val key = f.absolutePath + "|" + f.length() + "|" + f.lastModified()
        synchronized(fpCache) { fpCache[key]?.let { return it } }
        val fp = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val len = f.length()
            java.io.RandomAccessFile(f, "r").use { r ->
                val buf = ByteArray(64 * 1024)
                fun span(from: Long, n: Long) {
                    r.seek(from); var left = n
                    while (left > 0) { val k = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k <= 0) break; md.update(buf, 0, k); left -= k }
                }
                span(0, minOf(len, FP_SPAN))
                if (len > FP_SPAN) span(maxOf(FP_SPAN, len - FP_SPAN), len - maxOf(FP_SPAN, len - FP_SPAN))
            }
            len.toString() + "-" + md.digest().take(8).joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
        if (fp.isNotEmpty()) synchronized(fpCache) { fpCache[key] = fp }
        return fp
    }
    const val FP_SPAN = 1L shl 20
    private val fpCache = HashMap<String, String>()

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

    /** 下载模型 › 知识库嵌入模型: one GGUF file each (Q8_0 or Q4), no projector. */
    fun catalogItems(): List<RecommendedCatalog.Item> = ALL.map { m ->
        RecommendedCatalog.Item(
            m.repo, RecommendedCatalog.Section.EMBED, m.name,
            I18n.s(m.purposeRes),
            m.size, RecommendedCatalog.Engine.GGUF,
            RecommendedCatalog.Kind.Gguf(m.repo, m.file, m.size),
            top = true, quant = m.quant, licence = "Apache-2.0",
        )
    }
}

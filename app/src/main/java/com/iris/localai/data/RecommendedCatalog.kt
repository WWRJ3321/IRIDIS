package com.iris.localai.data

/**
 * 2.2.17 C: the 「推荐」 tab of 下载模型 — one list, grouped into 对话 / 特色模型 / 生图 / 语音, each
 * entry a plain-Chinese card (name, one-line purpose, size, engine) with the details (files, quant,
 * licence, repo) behind its detail sheet. Replaces the 2.2.12 grouped list in MnnHub. Pure (unit-tested).
 * Chat repo ids checked on the HF API 2026-11-01; ccore/Qwen3.5-4B-3D-Coder files/sizes/licence on 2026-11-03.
 */
object RecommendedCatalog {
    enum class Section(val title: String) { CHAT(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_859362)), FEATURED(com.iris.localai.I18n.s(com.iris.localai.R.string.data_6284d9)), IMAGE(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_19a9bc)), SPEECH(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_48df35)), TRANSLATE(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8b3607)), EMBED(com.iris.localai.I18n.s(com.iris.localai.R.string.kb251_embed_section)) }
    enum class Engine(val label: String) { GGUF("GGUF"), MNN("MNN"), LITERT("LiteRT"), NPU("NPU"), ONNX("ONNX"), BERGAMOT("Bergamot") }

    sealed interface Kind {
        /** Whole-repo download through MnnHub.start (MNN folder / LiteRT file / Q4 GGUF picked automatically); 2.4.4: [file] = that one file instead. */
        data class Hub(val repoId: String, val file: String? = null) : Kind
        /** One known GGUF file (+ optional vision projector) through MnnHub.startGguf. */
        data class Gguf(
            val repoId: String, val file: String, val size: Long, val mmproj: String? = null, val mmprojSize: Long = 0, val localName: String? = null,
            /** 2.5.1: 投影文件所在的另一个 HF 仓库（null = 与模型同仓库）。 */
            val mmprojRepo: String? = null,
        ) : Kind {
            /** 2.4.3: name on disk ([localName] renames a file like "Qwen3.5-4B.Q4_K_M.gguf" that would read as the base model). */
            val localFile: String get() = localName ?: file
            /** 2.5.1: 下载用的投影路径（跨仓库时带 [GgufSearch.crossRepoPath] 前缀）。 */
            val mmprojPath: String? get() = mmproj?.let { f -> mmprojRepo?.let { GgufSearch.crossRepoPath(it, f) } ?: f }
        }
        /** A 生图 model (ImageGenDownloader). */
        data class Image(val modelId: String) : Kind
        /** A voice pack (VoiceModelStore); size comes from its bundled manifest. */
        data class Voice(val packId: String) : Kind
        /** 2.3.0 ③: an offline translation pack (TranslatePackStore). */
        data class Translate(val packId: String) : Kind
    }

    data class Item(
        val id: String,
        val section: Section,
        val name: String,
        /** One line, plain Chinese. */
        val purpose: String,
        /** Approximate download size (0 = read at runtime, voice packs). */
        val sizeBytes: Long,
        val engine: Engine,
        val kind: Kind,
        /** Shown in the first, folded view of its section. */
        val top: Boolean = false,
        val quant: String? = null,
        val licence: String? = null,
        /** Tap to try after loading (特色模型). */
        val demoPrompt: String? = null,
        /** 2.4.4: listed only on this chip (base id like "mt6991"); an NPU build runs nowhere else. */
        val onlySoc: String? = null,
    ) {
        /** Repo the MnnHub state names while this downloads (null for 生图 / 语音). */
        val repoId: String? get() = when (kind) { is Kind.Hub -> kind.repoId; is Kind.Gguf -> kind.repoId; else -> null }
    }

    private const val GB = 1_000_000_000L
    private fun gb(v: Double) = (v * GB).toLong()
    private const val OPUS = "-Claude-4.6-Opus-Reasoning-Distilled-MNN"
    private const val APACHE = "Apache-2.0"

    private fun mnn(name: String, repo: String, purpose: String, size: Double, top: Boolean = false, licence: String? = APACHE) =
        Item("taobao-mnn/$repo", Section.CHAT, name, purpose, gb(size), Engine.MNN, Kind.Hub("taobao-mnn/$repo"), top, licence = licence)
    private fun litert(name: String, repo: String, purpose: String, size: Double, top: Boolean = false) =
        Item("litert-community/$repo", Section.CHAT, name, purpose, gb(size), Engine.LITERT, Kind.Hub("litert-community/$repo"), top)
    private fun spark(name: String, repo: String, purpose: String, size: Double, top: Boolean = false) =
        Item("XHToken/$repo", Section.CHAT, name, purpose, gb(size), Engine.GGUF, Kind.Hub("XHToken/$repo"), top, quant = "Q4_K_M")

    /**
     * 2.4.3: GGUF twin of an MNN entry (llama.cpp: CPU / GPU / Hexagon), one Q4_K_M file + optional vision projector.
     * Files and sizes from the HF tree API 2026-11-06 (lmstudio-community = Qwen's official weights; Jackrong = author of the Opus distills).
     */
    private fun gguf(
        name: String, repo: String, file: String, size: Long, purpose: String,
        mmproj: String? = null, mmprojSize: Long = 0, localName: String? = null, licence: String? = APACHE,
    ) = Item(repo, Section.CHAT, name, purpose, size, Engine.GGUF, Kind.Gguf(repo, file, size, mmproj, mmprojSize, localName), quant = "Q4_K_M", licence = licence)
    private fun lms35(size: String, bytes: Long, proj: Long, purpose: String) = gguf(
        "Qwen3.5 $size", "lmstudio-community/Qwen3.5-$size-GGUF", "Qwen3.5-$size-Q4_K_M.gguf", bytes, purpose,
        "mmproj-Qwen3.5-$size-BF16.gguf", proj,
    )
    private fun lms3(size: String, bytes: Long, purpose: String) =
        gguf("Qwen3 $size", "lmstudio-community/Qwen3-$size-GGUF", "Qwen3-$size-Q4_K_M.gguf", bytes, purpose)
    private const val OPUS_GGUF = "-Claude-4.6-Opus-Reasoning-Distilled-GGUF"
    private fun opus(name: String, size: String, bytes: Long, proj: String, projBytes: Long, purpose: String) = gguf(
        name, "Jackrong/Qwen3.5-$size$OPUS_GGUF", "Qwen3.5-$size.Q4_K_M.gguf", bytes, purpose, proj, projBytes,
        localName = "Qwen3.5-$size-Claude-4.6-Opus-Reasoning-Distilled-Q4_K_M.gguf",
    )

    /**
     * 2.5.1: Gemma 4 19B-A4B REAP（第三方专家剪枝 MoE，中文未测），mradermacher i1 量化；投影来自非 i1 仓库。
     * 文件名与大小来自 HF tree API 2026-11-12。建议开「专家按需加载」。
     */
    const val REAP_REPO = "mradermacher/gemma-4-19b-a4b-it-REAP-i1-GGUF"
    const val REAP_MMPROJ_REPO = "mradermacher/gemma-4-19b-a4b-it-REAP-GGUF"
    const val REAP_IQ3 = "$REAP_REPO#IQ3_XXS"
    const val REAP_Q4 = "$REAP_REPO#Q4_0"
    private fun reap(id: String, quant: String, bytes: Long) = Item(
        id, Section.CHAT, "Gemma 4 19B REAP（$quant）", com.iris.localai.I18n.s(com.iris.localai.R.string.v251_reap_purpose), bytes, Engine.GGUF,
        Kind.Gguf(REAP_REPO, "gemma-4-19b-a4b-it-REAP.i1-$quant.gguf", bytes, "gemma-4-19b-a4b-it-REAP.mmproj-Q8_0.gguf", 806_408_480L, mmprojRepo = REAP_MMPROJ_REPO),
        quant = "i1-$quant", licence = "Gemma",
    )

    const val NANBEIGE_REPO = "bartowski/Nanbeige_Nanbeige4.2-3B-GGUF"
    const val CODER_REPO = "ccore/Qwen3.5-4B-3D-Coder"
    const val CODER_DEMO = "用 Three.js 写一个单文件 HTML：星空中漂浮着一颗缓慢自转的彩色立方体，周围环绕几个发光小球，" +
        "有柔和灯光和阴影，可以用鼠标或手指拖动旋转视角。直接输出完整代码。"

    /** 2.4.4: base SoC id of this phone ("mt6991"), set once at app start; null in unit tests. */
    @Volatile var deviceSoc: String? = null

    const val GEMMA3_1B = "litert-community/Gemma3-1B-IT"
    /** 2.4.4 (Issue #6): Google's official MediaTek NPU builds — Dimensity 9300 / 9400 / 9500 only. */
    private fun mtkGemma(soc: String, bytes: Long) = Item(
        "$GEMMA3_1B#$soc", Section.CHAT, "Gemma 3 1B（NPU）", com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_gemma_purpose), bytes, Engine.LITERT,
        Kind.Hub(GEMMA3_1B, "Gemma3-1B-IT_q4_ekv1280_$soc.litertlm"), top = true, licence = "Gemma", onlySoc = soc,
    )

    const val QWEN3_06B = "litert-community/Qwen3-0.6B"
    /**
     * 2.4.5: Qwen3-0.6B compiled for the Dimensity 9500 NPU (MT6993; a16w8, 4096-token context, Apache-2.0).
     * Size from the HF tree API 2026-11-07 (1,202,913,280 B; the model card's table says 992 MB) — ModelScope
     * mirror lists the same size. The file carries no Jinja template: LiteRT-LM builds the prompt from the
     * file's own turn markers, so the NPU path needs no template of ours.
     */
    private val mtkQwen3 = Item(
        "$QWEN3_06B#mt6993", Section.CHAT, "Qwen3 0.6B（NPU）", com.iris.localai.I18n.s(com.iris.localai.R.string.mtk245_qwen_purpose), 1_202_913_280L, Engine.LITERT,
        Kind.Hub(QWEN3_06B, "Qwen3-0.6B.mediatek.mt6993.litertlm"), top = true, licence = APACHE, onlySoc = "mt6993",
    )

    val ITEMS: List<Item> = listOf(
        // 2.4.4: shown first on a matching Dimensity phone, hidden everywhere else (sizes: HF + ModelScope, 2026-11-07).
        mtkGemma("mt6989", 1_032_830_976L),
        mtkGemma("mt6991", 1_033_814_016L),
        mtkGemma("mt6993", 1_020_411_904L),
        mtkQwen3,
        // ---- 对话 (top = the six shown before 展开全部) ----
        litert("Gemma 4 E2B", "gemma-4-E2B-it-litert-lm", com.iris.localai.I18n.s(com.iris.localai.R.string.data_73508c), 2.6, top = true),
        mnn("Qwen3.5 4B", "Qwen3.5-4B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_8b23d8), 2.9, top = true),
        lms35("4B", 2_707_513_696L, 675_568_768L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_8b23d8)),
        mnn(com.iris.localai.I18n.s(com.iris.localai.R.string.data_6eff8b), "Qwen3.5-4B$OPUS", com.iris.localai.I18n.s(com.iris.localai.R.string.data_2814fe), 2.8, top = true, licence = null),
        opus(com.iris.localai.I18n.s(com.iris.localai.R.string.data_6eff8b), "4B", 2_707_510_080L, "mmproj-BF16.gguf", 675_568_864L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_2814fe)),
        litert("Gemma 4 E4B", "gemma-4-E4B-it-litert-lm", com.iris.localai.I18n.s(com.iris.localai.R.string.data_5fd1a5), 3.7, top = true),
        mnn("Qwen3.5 0.8B", "Qwen3.5-0.8B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_2b93b4), 0.55, top = true),
        lms35("0.8B", 527_502_816L, 207_345_952L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_2b93b4)),
        spark("Spark X2.6 4B", "Spark-X2.6-4B-GGUF", com.iris.localai.I18n.s(com.iris.localai.R.string.data_aca2d5), 3.5, top = true),
        mnn("Qwen3.5 2B", "Qwen3.5-2B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_63239b), 1.4),
        lms35("2B", 1_270_808_032L, 671_372_416L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_63239b)),
        mnn("Qwen3.5 9B", "Qwen3.5-9B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_7d4f8f), 7.274875830),
        lms35("9B", 5_627_044_256L, 921_704_480L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_7d4f8f)),
        mnn(com.iris.localai.I18n.s(com.iris.localai.R.string.data_1c3edc), "Qwen3.5-2B$OPUS", com.iris.localai.I18n.s(com.iris.localai.R.string.data_36299f), 1.4, licence = null),
        opus(com.iris.localai.I18n.s(com.iris.localai.R.string.data_1c3edc), "2B", 1_270_804_416L, "mmproj-BF16.gguf", 671_372_512L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_36299f)),
        mnn(com.iris.localai.I18n.s(com.iris.localai.R.string.data_45dc04), "Qwen3.5-0.8B$OPUS", com.iris.localai.I18n.s(com.iris.localai.R.string.data_aafdaf), 0.54, licence = null),
        opus(com.iris.localai.I18n.s(com.iris.localai.R.string.data_45dc04), "0.8B", 527_499_200L, "Qwen3.5-0.8B.BF16-mmproj.gguf", 207_346_048L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_aafdaf)),
        litert("Gemma 4 12B", "gemma-4-12B-it-litert-lm", com.iris.localai.I18n.s(com.iris.localai.R.string.data_e0fccf), 6.9),
        spark("Spark X2.6 1.7B", "Spark-X2.6-1.7B-GGUF", com.iris.localai.I18n.s(com.iris.localai.R.string.data_7c8f8e), 1.8),
        mnn("Qwen3 0.6B", "Qwen3-0.6B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_eec245), 0.45),
        lms3("0.6B", 484_219_808L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_eec245)),
        mnn("Qwen3 1.7B", "Qwen3-1.7B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_9b89f0), 1.2),
        lms3("1.7B", 1_282_439_328L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_9b89f0)),
        mnn("Qwen3 4B", "Qwen3-4B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_268c79), 2.7),
        lms3("4B", 2_497_280_704L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_268c79)),
        mnn("Qwen3 8B", "Qwen3-8B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_0ca462), 5.5),
        lms3("8B", 5_027_783_968L, com.iris.localai.I18n.s(com.iris.localai.R.string.data_0ca462)),
        // 2.5.5: 南北阁 Nanbeige4.2 3B（循环 Transformer：22 层跑两遍，比同尺寸慢）；bartowski imatrix，HF tree API 2026-11-14。
        gguf("Nanbeige 4.2 3B", NANBEIGE_REPO, "Nanbeige_Nanbeige4.2-3B-Q4_K_M.gguf", 2_684_023_968L, com.iris.localai.I18n.s(com.iris.localai.R.string.v255_nanbeige_purpose)),
        // 2.5.1: Gemma 4 19B-A4B REAP（MoE，12 GB 机 IQ3_XXS / 16 GB 机 Q4_0）
        reap(REAP_IQ3, "IQ3_XXS", 8_311_270_560L),
        reap(REAP_Q4, "Q4_0", 10_647_317_152L),
        // ---- 特色模型 ----
        Item(
            CODER_REPO, Section.FEATURED, "Qwen3.5 4B 3D Coder", com.iris.localai.I18n.s(com.iris.localai.R.string.data_c2d28e),
            2_708_804_096L, Engine.GGUF,
            Kind.Gguf(CODER_REPO, "Qwen3.5-4B-3D-Coder-Q4_K_M.gguf", 2_708_804_096L, "mmproj-Qwen3.5-4B-BF16.gguf", 675_568_768L),
            top = true, quant = "Q4_K_M", licence = APACHE, demoPrompt = CODER_DEMO,
        ),
        // 2.3.2 Bonsai 2 (PrismML, Qwen3.5 ternary): only the upstream Q2_0 files (PQ2_0 / PTQ1_0 need PrismML's fork).
        // HF prism-ml/Ternary-Bonsai-2-{2B,4B}-gguf checked 2026-11-04 (sizes from the tree API). 测试: CPU / OpenCL only, text only.
        Item(
            "prism-ml/Ternary-Bonsai-2-2B-gguf", Section.FEATURED, com.iris.localai.I18n.s(com.iris.localai.R.string.data232_bonsai, "2B"), com.iris.localai.I18n.s(com.iris.localai.R.string.data232_bonsai_purpose),
            546_689_504L, Engine.GGUF, Kind.Gguf("prism-ml/Ternary-Bonsai-2-2B-gguf", "Ternary-Bonsai-2-2B-Q2_0.gguf", 546_689_504L),
            quant = "Q2_0", licence = APACHE,
        ),
        Item(
            "prism-ml/Ternary-Bonsai-2-4B-gguf", Section.FEATURED, com.iris.localai.I18n.s(com.iris.localai.R.string.data232_bonsai, "4B"), com.iris.localai.I18n.s(com.iris.localai.R.string.data232_bonsai_purpose),
            1_212_103_520L, Engine.GGUF, Kind.Gguf("prism-ml/Ternary-Bonsai-2-4B-gguf", "Ternary-Bonsai-2-4B-Q2_0.gguf", 1_212_103_520L),
            quant = "Q2_0", licence = APACHE,
        ),
        // 2.4.3: 9B, upstream Q2_0 only (2,556,640,128 B, HF tree 2026-11-06); PQ2_0 / PTQ1_0 / MTP / DFlash need PrismML's fork.
        Item(
            "prism-ml/Ternary-Bonsai-2-9B-gguf", Section.FEATURED, com.iris.localai.I18n.s(com.iris.localai.R.string.data232_bonsai, "9B"), com.iris.localai.I18n.s(com.iris.localai.R.string.data232_bonsai_purpose),
            2_556_640_128L, Engine.GGUF, Kind.Gguf("prism-ml/Ternary-Bonsai-2-9B-gguf", "Ternary-Bonsai-2-9B-Q2_0.gguf", 2_556_640_128L),
            quant = "Q2_0", licence = APACHE,
        ),
        // ---- 生图 ----
        Item("sd15-qnn", Section.IMAGE, "Stable Diffusion 1.5", com.iris.localai.I18n.s(com.iris.localai.R.string.data_9026cb), 709_785_222L, Engine.NPU, Kind.Image("sd15-qnn"), top = true),
        Item("anything-v5-qnn", Section.IMAGE, "Anything V5.0", com.iris.localai.I18n.s(com.iris.localai.R.string.data_479b48), gb(0.86), Engine.NPU, Kind.Image("anything-v5-qnn"), top = true),
    ) + voiceItems() + translateItems() + com.iris.localai.knowledge.KbEmbedModels.catalogItems() // 2.5.1 知识库嵌入模型

    /** 语音: every downloadable voice pack (识别 / 朗读 / 音色克隆), same order as the 语音 page. */
    private fun voiceItems(): List<Item> = com.iris.localai.voice.VoicePack.entries.map { p ->
        val kind = when (p.kind) {
            com.iris.localai.voice.VoiceKind.ASR -> com.iris.localai.I18n.s(com.iris.localai.R.string.data_7c306e)
            com.iris.localai.voice.VoiceKind.TTS -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f9ab1f)
            com.iris.localai.voice.VoiceKind.CLONE -> com.iris.localai.I18n.s(com.iris.localai.R.string.data_8bc980)
        }
        Item(
            "voice:" + p.id, Section.SPEECH, p.title, "$kind · ${p.blurb}", 0L, Engine.ONNX, Kind.Voice(p.id),
            top = p == com.iris.localai.voice.VoicePack.ASR_SENSEVOICE || p == com.iris.localai.voice.VoicePack.TTS_KOKORO,
        )
    }

    /** 2.3.0 ③ 翻译: the offline Bergamot packs (one per direction), same as 设置 › 语音与翻译. */
    private fun translateItems(): List<Item> = com.iris.localai.translate.TranslatePack.entries.map { p ->
        Item("translate:" + p.id, Section.TRANSLATE, com.iris.localai.I18n.s(com.iris.localai.R.string.data_8df05e, p.title), com.iris.localai.I18n.s(com.iris.localai.R.string.data_4b65d4, p.blurb), p.totalBytes, Engine.BERGAMOT, Kind.Translate(p.id), top = true, licence = "MPL-2.0")
    }

    fun section(s: Section): List<Item> = ITEMS.filter { it.section == s && (it.onlySoc == null || it.onlySoc == deviceSoc) }
    fun byId(id: String): Item? = ITEMS.firstOrNull { it.id == id }
    fun byRepo(repoId: String): Item? = ITEMS.firstOrNull { it.repoId == repoId }

    /** Items shown for [s]: the top ones unless [expanded] (a section with ≤ 1 hidden item shows all). */
    fun visible(s: Section, expanded: Boolean): List<Item> {
        val all = section(s)
        val top = all.filter { it.top }
        return if (expanded || all.size - top.size <= 1) all else top
    }

    /**
     * Whether a local model file / folder [localName] is this repo's download. MNN folders are named
     * after the repo; single files (GGUF / LiteRT) start with the repo's stem followed by a quant tag
     * or end there. "Qwen3.5-4B" never matches "Qwen3.5-4B-Claude-…".
     */
    fun matchesLocal(repoId: String, localName: String): Boolean {
        fun norm(s: String) = s.lowercase().removeSuffix(".gguf").removeSuffix(".litertlm")
        val local = norm(localName)
        val repo = repoId.substringAfter('/').lowercase()
        if (local == repo) return true
        val stem = repo.removeSuffix("-gguf").removeSuffix("-mnn").removeSuffix("-litert-lm")
        if (local == stem) return true
        // 2.4.3: an MNN download is a folder named after the repo — never its GGUF twin "Qwen3.5-4B-Q4_K_M.gguf"
        if (repo.endsWith("-mnn")) return false
        return Regex("^" + Regex.escape(stem) + "[-._](i?q\\d|f16|bf16|f32)").containsMatchIn(local)
    }

    /** One short Chinese reason out of a (possibly multi-source, multi-line) download error. */
    fun shortReason(error: String?): String {
        val e = error?.trim().orEmpty()
        if (e.isEmpty()) return com.iris.localai.I18n.s(com.iris.localai.R.string.data_65e200)
        if (e == MnnHub.GATED || com.iris.localai.I18n.s(com.iris.localai.R.string.data_10091e) in e) return com.iris.localai.I18n.s(com.iris.localai.R.string.data_0834a0)
        var line = e.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return com.iris.localai.I18n.s(com.iris.localai.R.string.data_65e200)
        val head = line.substringBefore('：', "")
        val labels = DownloadSource.CONCRETE.map { it.label } + listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.data_662005), "hf-mirror", "HuggingFace", "Hugging Face")
        if (head.isNotEmpty() && head.length <= 24 && labels.any { head.contains(it, ignoreCase = true) }) line = line.substringAfter('：').trim()
        if (line.isEmpty()) return com.iris.localai.I18n.s(com.iris.localai.R.string.data_65e200)
        return if (line.length > 120) line.take(119) + "…" else line
    }
}

/** 2.2.17 C: what one recommended card shows (computed from the download states; pure). */
sealed interface RecStatus {
    data object Idle : RecStatus
    /** Another model is downloading (one at a time). */
    data object Blocked : RecStatus
    data class Downloading(val done: Long, val total: Long, val source: String = "", val notice: String? = null, val pausable: Boolean = true) : RecStatus {
        val fraction: Float? get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
    }
    data class Paused(val done: Long, val total: Long) : RecStatus
    data class Failed(val reason: String) : RecStatus
    /** On disk; [loaded] = the chat model in use (or the active voice pack). */
    data class Installed(val loaded: Boolean = false) : RecStatus

    companion object {
        /** Status of a Hub / GGUF entry from the MnnHub state and whether it is already on disk. */
        fun forHub(repoId: String, hub: MnnHub.State, installed: Boolean, loaded: Boolean): RecStatus = when {
            hub.repoId == repoId && hub.active -> Downloading(hub.doneBytes, hub.totalBytes, hub.source, hub.notice)
            hub.repoId == repoId && hub.paused -> Paused(hub.doneBytes, hub.totalBytes)
            hub.repoId == repoId && hub.error != null -> Failed(RecommendedCatalog.shortReason(hub.error))
            installed -> Installed(loaded)
            hub.active -> Blocked
            else -> Idle
        }

        /** 「下载」 / 「42%」 / 「继续」 / 「换源重试」 / 「加载」 / 「使用中」. */
        fun primaryLabel(s: RecStatus, section: RecommendedCatalog.Section): String = when (s) {
            Idle, Blocked -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f26ef9)
            is Downloading -> s.fraction?.let { "${(it * 100).toInt()}%" } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2d455c)
            is Paused -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_27ca56)
            is Failed -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b7da7)
            is Installed -> when (section) {
                RecommendedCatalog.Section.CHAT, RecommendedCatalog.Section.FEATURED -> if (s.loaded) com.iris.localai.I18n.s(com.iris.localai.R.string.data_4d419c) else com.iris.localai.I18n.s(com.iris.localai.R.string.models_4793ea)
                RecommendedCatalog.Section.SPEECH -> if (s.loaded) com.iris.localai.I18n.s(com.iris.localai.R.string.data_4d419c) else com.iris.localai.I18n.s(com.iris.localai.R.string.data_ecff77)
                RecommendedCatalog.Section.IMAGE, RecommendedCatalog.Section.TRANSLATE, RecommendedCatalog.Section.EMBED -> com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_9719fe)
            }
        }
    }
}

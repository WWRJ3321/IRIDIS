package com.iris.localai.data

/**
 * 2.2.17 C: the 「推荐」 tab of 下载模型 — one list, grouped into 对话 / 特色模型 / 生图 / 语音, each
 * entry a plain-Chinese card (name, one-line purpose, size, engine) with the details (files, quant,
 * licence, repo) behind its detail sheet. Replaces the 2.2.12 grouped list in MnnHub. Pure (unit-tested).
 * Chat repo ids checked on the HF API 2026-11-01; ccore/Qwen3.5-4B-3D-Coder files/sizes/licence on 2026-11-03.
 */
object RecommendedCatalog {
    enum class Section(val title: String) { CHAT(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_859362)), FEATURED(com.iris.localai.I18n.s(com.iris.localai.R.string.data_6284d9)), IMAGE(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_19a9bc)), SPEECH(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_48df35)), TRANSLATE(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8b3607)) }
    enum class Engine(val label: String) { GGUF("GGUF"), MNN("MNN"), LITERT("LiteRT"), NPU("NPU"), ONNX("ONNX"), BERGAMOT("Bergamot") }

    sealed interface Kind {
        /** Whole-repo download through MnnHub.start (MNN folder / LiteRT file / Q4 GGUF picked automatically). */
        data class Hub(val repoId: String) : Kind
        /** One known GGUF file (+ optional vision projector) through MnnHub.startGguf. */
        data class Gguf(val repoId: String, val file: String, val size: Long, val mmproj: String? = null, val mmprojSize: Long = 0) : Kind
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

    const val CODER_REPO = "ccore/Qwen3.5-4B-3D-Coder"
    const val CODER_DEMO = "用 Three.js 写一个单文件 HTML：星空中漂浮着一颗缓慢自转的彩色立方体，周围环绕几个发光小球，" +
        "有柔和灯光和阴影，可以用鼠标或手指拖动旋转视角。直接输出完整代码。"

    val ITEMS: List<Item> = listOf(
        // ---- 对话 (top = the six shown before 展开全部) ----
        litert("Gemma 4 E2B", "gemma-4-E2B-it-litert-lm", com.iris.localai.I18n.s(com.iris.localai.R.string.data_73508c), 2.6, top = true),
        mnn("Qwen3.5 4B", "Qwen3.5-4B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_8b23d8), 2.9, top = true),
        mnn(com.iris.localai.I18n.s(com.iris.localai.R.string.data_6eff8b), "Qwen3.5-4B$OPUS", com.iris.localai.I18n.s(com.iris.localai.R.string.data_2814fe), 2.8, top = true, licence = null),
        litert("Gemma 4 E4B", "gemma-4-E4B-it-litert-lm", com.iris.localai.I18n.s(com.iris.localai.R.string.data_5fd1a5), 3.7, top = true),
        mnn("Qwen3.5 0.8B", "Qwen3.5-0.8B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_2b93b4), 0.55, top = true),
        spark("Spark X2.6 4B", "Spark-X2.6-4B-GGUF", com.iris.localai.I18n.s(com.iris.localai.R.string.data_aca2d5), 3.5, top = true),
        mnn("Qwen3.5 2B", "Qwen3.5-2B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_63239b), 1.4),
        mnn("Qwen3.5 9B", "Qwen3.5-9B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_7d4f8f), 7.3),
        mnn(com.iris.localai.I18n.s(com.iris.localai.R.string.data_1c3edc), "Qwen3.5-2B$OPUS", com.iris.localai.I18n.s(com.iris.localai.R.string.data_36299f), 1.4, licence = null),
        mnn(com.iris.localai.I18n.s(com.iris.localai.R.string.data_45dc04), "Qwen3.5-0.8B$OPUS", com.iris.localai.I18n.s(com.iris.localai.R.string.data_aafdaf), 0.54, licence = null),
        litert("Gemma 4 12B", "gemma-4-12B-it-litert-lm", com.iris.localai.I18n.s(com.iris.localai.R.string.data_e0fccf), 6.9),
        spark("Spark X2.6 1.7B", "Spark-X2.6-1.7B-GGUF", com.iris.localai.I18n.s(com.iris.localai.R.string.data_7c8f8e), 1.8),
        mnn("Qwen3 0.6B", "Qwen3-0.6B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_eec245), 0.45),
        mnn("Qwen3 1.7B", "Qwen3-1.7B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_9b89f0), 1.2),
        mnn("Qwen3 4B", "Qwen3-4B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_268c79), 2.7),
        mnn("Qwen3 8B", "Qwen3-8B-MNN", com.iris.localai.I18n.s(com.iris.localai.R.string.data_0ca462), 5.5),
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
        // ---- 生图 ----
        Item("sd15-qnn", Section.IMAGE, "Stable Diffusion 1.5", com.iris.localai.I18n.s(com.iris.localai.R.string.data_9026cb), 709_785_222L, Engine.NPU, Kind.Image("sd15-qnn"), top = true),
        Item("anything-v5-qnn", Section.IMAGE, "Anything V5.0", com.iris.localai.I18n.s(com.iris.localai.R.string.data_479b48), gb(0.86), Engine.NPU, Kind.Image("anything-v5-qnn"), top = true),
    ) + voiceItems() + translateItems()

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

    fun section(s: Section): List<Item> = ITEMS.filter { it.section == s }
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
        return if (line.length > 40) line.take(39) + "…" else line
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
                RecommendedCatalog.Section.IMAGE, RecommendedCatalog.Section.TRANSLATE -> com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_9719fe)
            }
        }
    }
}

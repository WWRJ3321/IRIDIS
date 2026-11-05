package com.iris.localai.imagegen

enum class Tier { LOW, MID, HIGH }
/** 2.4.0: SDCPP = upstream stable-diffusion.cpp on the GPU (ggml Vulkan) with CPU fallback. */
enum class Backend { QNN, MNN_CPU, HEXAGON, SDCPP }

/** A file inside a downloaded archive, after flattening its top-level folder; [path] is relative to the model folder. */
data class UnpackedFile(val path: String, val size: Long)

/**
 * One download. [path] is relative to the imagegen models root (`<model id>/…`). A Hugging Face url
 * (`https://huggingface.co/…`) is fetched from the chosen [com.iris.localai.data.DownloadSource] host.
 * An archive ([unpacked] non-empty) is extracted into the model folder and deleted afterwards.
 */
data class RemoteFile(
    val url: String,
    val size: Long,
    val path: String,
    val unpacked: List<UnpackedFile> = emptyList(),
    /**
     * 2.2.12: ≥ 0 = [size] bytes starting here are ONE zip entry (local header + compressed data) cut out of the
     * remote zip at [url] with an HTTP Range request; it is inflated into [unpacked]`[0]` (checked against [crc]).
     * Lets a model take only the files it needs from a bigger archive.
     */
    val zipEntryAt: Long = -1L,
    val crc: Long = -1L,
) {
    val isArchive: Boolean get() = unpacked.isNotEmpty()
    val isZipSlice: Boolean get() = zipEntryAt >= 0
    /** The url with the slice range as a `#bytes=a-b` fragment (HttpDownload turns it into a Range header). */
    val fetchUrl: String get() = if (isZipSlice) "$url#bytes=$zipEntryAt-${zipEntryAt + size - 1}" else url
}

data class ImageModel(
    val id: String,
    val tier: Tier,
    val name: String,
    val backend: Backend,
    val files: List<RemoteFile>,
    val fixedSize: Int?,
    val defaultSteps: Int,
    val defaultCfg: Float,
    val needsHexagonRuntime: Boolean = false,
    /** HF repo the files come from (informational; when [files] is empty its file list would be resolved at download time). */
    val hfRepo: String? = null,
    /** 2.2.12: prompt / negative put into empty (or still-default) fields when this model is picked. */
    val defaultPrompt: String = "",
    val defaultNegative: String = "",
    /**
     * 2.2.12 QNN graph conventions. AI Hub's VAE divides by 0.18215 itself and returns [0,1];
     * Local Dream's expects latent / 0.18215 and returns [-1,1] (NCHW, detected from the tensor shape).
     */
    val vaeInScale: Float = 1f,
    val vaeOutSigned: Boolean = false,
    /** Lowest Hexagon HTP arch the model's context binaries run on. */
    val minHtpArch: Int = 73,
    /** One line under the name on the 生图 page (empty = none). */
    val note: String = "",
    /** 2.4.0: [Backend.SDCPP] models — which downloaded file is the diffusion model, text encoder and VAE. */
    val sdFiles: SdFiles? = null,
) {
    /** Files under [ImageCatalog.SHARED_DIR] other models may use too (kept when this one is deleted). */
    val sharedPaths: List<String> get() = files.map { it.path }.filter { it.startsWith(ImageCatalog.SHARED_DIR + "/") }
}

object ImageCatalog {
    /** taobao-mnn/stable-diffusion-v1-5-mnn-general, pinned to the revision checked 2026-10-22. */
    const val MNN_REPO = "taobao-mnn/stable-diffusion-v1-5-mnn-general"
    const val MNN_REV = "56c93a62ac9d6b5d18605632b85e25f87281f317"
    private fun mnnUrl(file: String) = "https://huggingface.co/$MNN_REPO/resolve/$MNN_REV/$file"

    /** CLIP BPE tokenizer (SD 1.5 text encoder = CLIP ViT-L/14), from the MNN repo. */
    private fun tokenizerFiles(modelId: String, sub: String) = listOf(
        RemoteFile(mnnUrl("vocab.json"), 1_059_962L, "$modelId/${sub}vocab.json"),
        RemoteFile(mnnUrl("merges.txt"), 524_619L, "$modelId/${sub}merges.txt"),
    )

    val SD15_QNN = ImageModel(
        id = "sd15-qnn", tier = Tier.LOW, name = "Stable Diffusion 1.5 (NPU)", backend = Backend.QNN,
        files = tokenizerFiles("sd15-qnn", "tokenizer/") + RemoteFile(
            url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases/v0.64.0/stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip",
            size = 709_785_222L,
            path = "sd15-qnn/.part/stable_diffusion_v1_5-qnn_context_binary-w8a16.zip",
            // zip central directory read 2026-10-22 (one top-level folder, flattened away)
            unpacked = listOf(
                UnpackedFile("text_encoder.bin", 163_058_576L),
                UnpackedFile("unet.bin", 880_144_176L),
                UnpackedFile("vae.bin", 60_452_704L),
                UnpackedFile("metadata.json", 4_134L),
            ),
        ),
        fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
    )

    /** Repo listing (api/models/…/tree/main) checked 2026-10-22; .gitattributes / README.md left out. */
    val SD15_MNN = ImageModel(
        id = "sd15-mnn", tier = Tier.LOW, name = "Stable Diffusion 1.5 (CPU)", backend = Backend.MNN_CPU,
        files = listOf(
            "alphas.txt" to 6_999L,
            "merges.txt" to 524_619L,
            "text_encoder.mnn" to 249_944L,
            "text_encoder.mnn.weight" to 238_120_368L,
            "unet.mnn" to 1_353_144L,
            "unet.mnn.weight" to 863_650_252L,
            "vae_decoder.mnn" to 183_960L,
            "vae_decoder.mnn.weight" to 49_639_636L,
            "vocab.json" to 1_059_962L,
        ).map { (f, size) -> RemoteFile(mnnUrl(f), size, "sd15-mnn/$f") },
        fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
        hfRepo = MNN_REPO,
    )

    /** Qualcomm AI Hub SD1.5 release v0.64.0 (w8a16 QNN context binaries), per-SoC zips. */
    private const val AIHUB_SD15 = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases/v0.64.0/stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_"

    /** xororz/sd-qnn (Local Dream's NPU models), pinned to the revision checked 2026-11-01. */
    const val LD_REPO = "xororz/sd-qnn"
    const val LD_REV = "64d16039e0c3a71d3c5db1faccc090faae93cb28"

    const val ANYTHING_V5_ID = "anything-v5-qnn"
    const val ANYTHING_PROMPT = "masterpiece, best quality, 1girl, solo, cute, white hair,"
    const val ANYTHING_NEGATIVE = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,"

    /**
     * Anything V5.0 (SD1.5 anime fine-tune, CreativeML OpenRAIL-M) as Local Dream ships it for the NPU
     * (AnythingV5_qnn2.28_8gen2.zip: Local Dream uses this one zip for 8 Gen 2 … 8 Elite Gen 5). Only UNet and
     * VAE decoder are taken from it (Range-sliced; its CLIP is an MNN CPU graph that libiris_imagegen cannot run).
     * The text encoder is AI Hub's SD1.5 QNN one (CLIP ViT-L/14; Anything V5's text encoder is the SD1.5 one
     * to ~1e-3, checked 2026-11-01), from the zip matching the chip: 8 Gen 3 (v75) or 8 Elite (v79+).
     * Zip central directories / local headers read 2026-11-01.
     */
    fun anythingV5(htpArch: Int?): ImageModel {
        val id = ANYTHING_V5_ID
        val ld = "https://huggingface.co/$LD_REPO/resolve/$LD_REV/AnythingV5_qnn2.28_8gen2.zip"
        val te = if (htpArch == 75)
            RemoteFile("${AIHUB_SD15}snapdragon_8gen3.zip", 124_431_624L, "$id/.part/text_encoder.slice",
                listOf(UnpackedFile("text_encoder.bin", 163_029_904L)), zipEntryAt = 585_291_399L, crc = 0x0ec6f127L)
        else
            RemoteFile("${AIHUB_SD15}snapdragon_8_elite_for_galaxy.zip", 124_432_784L, "$id/.part/text_encoder.slice",
                listOf(UnpackedFile("text_encoder.bin", 163_058_576L)), zipEntryAt = 585_350_860L, crc = 0x5fd1471dL)
        return ImageModel(
            id = id, tier = Tier.LOW, name = "Anything V5.0（动漫）", backend = Backend.QNN,
            files = tokenizerFiles(id, "tokenizer/") + te + listOf(
                RemoteFile(ld, 684_921_044L, "$id/.part/unet.slice", listOf(UnpackedFile("unet.bin", 880_553_304L)),
                    zipEntryAt = 290_997_738L, crc = 0x92c42f15L),
                RemoteFile(ld, 45_827_921L, "$id/.part/vae.slice", listOf(UnpackedFile("vae.bin", 59_949_944L)),
                    zipEntryAt = 245_169_817L, crc = 0x980945b8L),
            ),
            fixedSize = 512, defaultSteps = 20, defaultCfg = 7f,
            hfRepo = LD_REPO,
            defaultPrompt = ANYTHING_PROMPT, defaultNegative = ANYTHING_NEGATIVE,
            vaeInScale = 1f / 0.18215f, vaeOutSigned = true, minHtpArch = 75,
            note = "动漫风格 · 512×512 · NPU（骁龙 8 Gen 3 及以上）",
        )
    }

    /** For this phone's chip (unknown chip → the 8 Elite text encoder). */
    val ANYTHING_V5: ImageModel by lazy { anythingV5(runCatching { DeviceProbe.htpArch(DeviceProbe.socModel()) }.getOrNull()) }

    /** 2.4.0: files more than one model uses (the Qwen3-4B text encoder), relative to the imagegen root. */
    const val SHARED_DIR = "_shared"

    private fun hf(repo: String, rev: String, file: String) = "https://huggingface.co/$repo/resolve/$rev/$file"

    /**
     * 2.4.0: libiris_sd.so (stable-diffusion.cpp 3e92bf9 + JNI, CI build of native.yml) is not in the APK; it is
     * downloaded with the first sd.cpp model and only loaded after its SHA-256 matches [SD_LIB_SHA256] (SdNative).
     */
    const val SD_LIB_SHA256 = "1e5b7a0afc68a8c230ff883a78a43223955ab31166526e8a4429256fef221297"
    val SD_LIB = RemoteFile(
        "https://raw.githubusercontent.com/WWRJ3321/IRIDIS/sdlib-3e92bf9/prebuilt-native/arm64-v8a/libiris_sd.so",
        63_630_040L, "$SHARED_DIR/libiris_sd-3e92bf9.so",
    )

    /**
     * Qwen3-4B Q4_K_M (unsloth/Qwen3-4B-GGUF, Apache-2.0): the text encoder of both FLUX.2 Klein 4B and
     * Z-Image Turbo (Comfy-Org ships the same qwen_3_4b.safetensors, LFS oid 6c671498…, for both). Listing read 2026-11-05.
     */
    private val QWEN3_4B = RemoteFile(
        hf("unsloth/Qwen3-4B-GGUF", "22c9fc8a8c7700b76a1789366280a6a5a1ad1120", "Qwen3-4B-Q4_K_M.gguf"),
        2_497_281_312L, "$SHARED_DIR/Qwen3-4B-Q4_K_M.gguf",
    )

    /** FLUX.2 Klein 4B (Apache-2.0): leejet GGUF Q4_0 + FLUX.2 VAE (Comfy-Org/Flux-2-Klein-4B). 4 steps, CFG 1. */
    val FLUX2_KLEIN = ImageModel(
        id = "flux2-klein-4b", tier = Tier.MID, name = "FLUX.2 Klein 4B", backend = Backend.SDCPP,
        files = listOf(
            RemoteFile(hf("leejet/FLUX.2-klein-4B-GGUF", "3b1f5a9dc3abb32238b053aeb3d823c30afdacbd", "flux-2-klein-4b-Q4_0.gguf"),
                2_460_378_560L, "flux2-klein-4b/flux-2-klein-4b-Q4_0.gguf"),
            QWEN3_4B,
            SD_LIB,
            RemoteFile(hf("Comfy-Org/Flux-2-Klein-4B", "5b3abca5c0dc1ecd4eaf3340868d09063fc1c965", "split_files/vae/flux2-vae.safetensors"),
                336_211_292L, "flux2-klein-4b/flux2-vae.safetensors"),
        ),
        fixedSize = null, defaultSteps = 4, defaultCfg = 1f,
        hfRepo = "leejet/FLUX.2-klein-4B-GGUF",
        note = "通用写实 · 4 步 · GPU（Vulkan），约 5.3 GB",
        sdFiles = SdFiles("flux2-klein-4b/flux-2-klein-4b-Q4_0.gguf", QWEN3_4B.path, "flux2-klein-4b/flux2-vae.safetensors"),
    )

    /** Z-Image Turbo (Apache-2.0): leejet GGUF Q3_K + FLUX.1 VAE (Comfy-Org/Z-Image-Turbo). 8 steps, CFG 1; good with Chinese prompts and text. */
    val Z_IMAGE_TURBO = ImageModel(
        id = "z-image-turbo", tier = Tier.HIGH, name = "Z-Image Turbo", backend = Backend.SDCPP,
        files = listOf(
            RemoteFile(hf("leejet/Z-Image-Turbo-GGUF", "c61c0e422dc8b541b7548cf33a4ef8302b0f8085", "z_image_turbo-Q3_K.gguf"),
                3_143_559_104L, "z-image-turbo/z_image_turbo-Q3_K.gguf"),
            QWEN3_4B,
            SD_LIB,
            RemoteFile(hf("Comfy-Org/Z-Image-Turbo", "6fc90a3b1b653e935a0d175e260736de25b84df5", "split_files/vae/ae.safetensors"),
                335_304_388L, "z-image-turbo/ae.safetensors"),
        ),
        fixedSize = null, defaultSteps = 8, defaultCfg = 1f,
        hfRepo = "leejet/Z-Image-Turbo-GGUF",
        note = "擅长中英文字 · 8 步 · GPU（Vulkan），约 6 GB",
        sdFiles = SdFiles("z-image-turbo/z_image_turbo-Q3_K.gguf", QWEN3_4B.path, "z-image-turbo/ae.safetensors"),
    )

    val all: List<ImageModel> = listOf(SD15_QNN, ANYTHING_V5, SD15_MNN, FLUX2_KLEIN, Z_IMAGE_TURBO)

    fun byId(id: String): ImageModel? = all.firstOrNull { it.id == id }
}

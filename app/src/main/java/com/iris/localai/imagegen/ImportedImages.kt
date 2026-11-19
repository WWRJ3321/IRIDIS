package com.iris.localai.imagegen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 用户自己导入的生图模型（规避再分发/商用许可风险：App 不托管、不下载这些权重）。
 *
 * 只支持「扩散模型 + LLM 文本编码器 + VAE」三件套的 sd.cpp 架构——现有 libiris_sd.so 的 JNI 只传
 * diffusion_model_path / llm_path / vae_path（见 cpp/sdcpp/sd_jni.cpp），所以 SD1.5/SDXL 单文件、
 * FLUX.1（clip_l+t5）不在其列，要支持需改 JNI 并重新托管该库。
 *
 * 文件复制到生图模型根目录下的 `<id>/`；文本编码器 / VAE 可复用同一根目录里已下载的文件（相对路径）。
 * 清单存 filesDir/imagegen/imported.json，主进程与 :imagegen 进程都读它（IrisApp 里 [init]）。
 */
enum class ImportArch(val label: String, val llmPath: String, val vaePath: String, val steps: Int, val cfg: Float, val tagStyle: Boolean, val npuPack: Boolean = false) {
    ANIMA("Anima", "${ImageCatalog.SHARED_DIR}/Qwen3-0.6B-Base.Q8_0.gguf", "${ImageCatalog.ANIMA_OLD_ID}/qwen_image_vae.safetensors", 30, 4f, true),
    Z_IMAGE("Z-Image", "${ImageCatalog.SHARED_DIR}/Qwen3-4B-Q4_K_M.gguf", "z-image-turbo/ae.safetensors", 8, 1f, false),
    FLUX2_4B("FLUX.2 Klein 4B", "${ImageCatalog.SHARED_DIR}/Qwen3-4B-Q4_K_M.gguf", "flux2-klein-4b/flux2-vae.safetensors", 4, 1f, false),
    /** Anima 的 NPU 包（一整个文件夹的 QNN 文件，只在 HTP v79 上跑）。 */
    ANIMA_NPU("Anima NPU", "", "", 8, 1f, true, npuPack = true),
}

@Serializable
data class ImportedImage(
    val id: String,
    val name: String,
    val arch: String,
    /** 相对生图模型根目录。 */
    val diffusion: String,
    val llm: String,
    val vae: String,
    val steps: Int,
    val cfg: Float,
    /** 通用导入（arch = [AUTO]）：单文件模型与 CLIP / T5 编码器，均为相对路径，可空。 */
    val model: String = "",
    val clipL: String = "",
    val clipG: String = "",
    val t5: String = "",
    val tagStyle: Boolean = false,
    /** NPU 包布局："" = 8 Elite 布局，[PACK_LD] = [AnimaLdFiles] 布局。 */
    val pack: String = "",
    /** Anima NPU 包的调度偏移（高级里调，默认 3.0）。 */
    val shift: Float = AnimaMath.DEFAULT_SHIFT,
) {
    val sdFiles: SdFiles get() = SdFiles(diffusion, llm, vae, model, clipL, clipG, t5)

    companion object { const val PACK_LD = "ld" }

    val archOrNull: ImportArch? get() = ImportArch.entries.firstOrNull { it.name == arch }

    fun toModel(): ImageModel {
        val a = archOrNull
        if (a?.npuPack == true) return ImageModel(
            id = id, tier = Tier.MID, name = name, backend = Backend.QNN,
            files = emptyList(), fixedSize = null, defaultSteps = steps, defaultCfg = 1f,
            note = "NPU", minHtpArch = if (pack == PACK_LD) AnimaLdFiles.MIN_ARCH else 79, tagStyle = true, imported = true, animaQnn = true,
            animaShift = AnimaMath.clampShift(shift),
        )
        return ImageModel(
            id = id, tier = Tier.MID, name = name, backend = Backend.SDCPP,
            files = listOf(ImageCatalog.SD_LIB), // 只有引擎库需要下载；权重是用户的文件
            fixedSize = null, defaultSteps = steps, defaultCfg = cfg,
            note = "", // 导入的模型不按牌子标注
            sdFiles = sdFiles,
            tagStyle = a?.tagStyle == true || tagStyle,
            imported = true,
        )
    }
}

object ImportedImages {
    const val PREFIX = "imp-"
    /** 通用导入的 arch 字段值（不按牌子分）。 */
    const val AUTO = "AUTO"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var file: File? = null
    @Volatile private var cache: Pair<Long, List<ImportedImage>>? = null
    @Volatile private var checkedAt = 0L

    fun init(filesDir: File) { file = File(filesDir, "imagegen/imported.json") }

    fun isImported(id: String) = id.startsWith(PREFIX)

    /** 清单（文件没变就用缓存）。 */
    fun list(): List<ImportedImage> {
        val f = file ?: return emptyList()
        // 本进程写入会清缓存；另一个进程写的最多 2 秒后看到（少做主线程 stat）
        val now = System.currentTimeMillis()
        cache?.let { (_, l) -> if (now - checkedAt < 2000) return l }
        checkedAt = now
        val stamp = if (f.isFile) f.lastModified() xor f.length() else -1L
        cache?.let { (s, l) -> if (s == stamp) return l }
        val l = if (stamp == -1L) emptyList() else runCatching { decode(f.readText()) }.getOrDefault(emptyList())
        cache = stamp to l
        return l
    }

    fun models(): List<ImageModel> = list().map { it.toModel() }

    fun get(id: String): ImportedImage? = list().firstOrNull { it.id == id }

    @Synchronized fun put(e: ImportedImage) = save(list().filter { it.id != e.id } + e)

    @Synchronized fun remove(id: String) = save(list().filter { it.id != id })

    @Synchronized fun rename(id: String, name: String) =
        save(list().map { if (it.id == id) it.copy(name = cleanName(name, it.name)) else it })

    private fun save(l: List<ImportedImage>) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encode(l))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        cache = null
    }

    // ---- 纯函数（单测） ----
    fun encode(l: List<ImportedImage>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ImportedImage.serializer()), l)
    fun decode(s: String): List<ImportedImage> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ImportedImage.serializer()), s)
            .filter { e -> isImported(e.id) && e.sdFiles.paths().all(::safeRel) && (e.diffusion.isNotEmpty() || e.model.isNotEmpty() || e.archOrNull?.npuPack == true) }

    fun newId(seed: Long = System.nanoTime()): String = PREFIX + java.lang.Long.toHexString(seed and 0xFFFFFFFFL).padStart(8, '0')

    fun cleanName(name: String, fallback: String): String = name.trim().replace(Regex("\\s+"), " ").take(40).ifEmpty { fallback }

    /** 文件名 → 默认显示名：去扩展名与常见量化后缀。 */
    fun nameFromFile(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("[-_.](Q\\d[_A-Z0-9]*|F16|BF16|FP16|FP8[_A-Z0-9]*)$", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ').trim().take(40).ifEmpty { fileName }

    /** 只收 .gguf / .safetensors。 */
    fun acceptable(fileName: String): Boolean = fileName.lowercase().let { it.endsWith(".gguf") || it.endsWith(".safetensors") }

    /** 头几个字节与扩展名对得上（GGUF 魔数；safetensors 前 8 字节是小端头长度，后跟 '{'）。 */
    fun headerOk(fileName: String, head: ByteArray): Boolean {
        val n = fileName.lowercase()
        if (n.endsWith(".gguf")) return head.size >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'G'.code.toByte() && head[2] == 'U'.code.toByte() && head[3] == 'F'.code.toByte()
        if (n.endsWith(".safetensors")) {
            if (head.size < 9) return false
            var len = 0L
            for (i in 7 downTo 0) len = (len shl 8) or (head[i].toLong() and 0xFF)
            return len in 2..100_000_000L && head[8] == '{'.code.toByte()
        }
        return false
    }

    /** 存进清单的路径只能是根目录内的相对路径。 */
    fun safeRel(p: String): Boolean = p.isNotEmpty() && !p.startsWith("/") && p.split('/').none { it == ".." || it.isEmpty() }

    /** 复制进来的文件名：去掉路径分隔符等。 */
    fun safeFileName(n: String): String = n.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifEmpty { "model.bin" }
}

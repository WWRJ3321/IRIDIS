package com.iris.localai.imagegen

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.InputStream

/** LoRA 适用的模型结构（只用来判断能否配当前底模；界面不显示名字）。 */
enum class LoraArch { SD1, SD2, SDXL, SD3, FLUX1, FLUX2, FLUX_ANY, Z_IMAGE, ANIMA, QWEN_IMAGE, WAN, UNKNOWN;

    /** 这个 LoRA 能不能配 [base]（底模结构）。FLUX_ANY = 从键名分不出 FLUX.1/FLUX.2，两者都放行。 */
    fun fits(base: LoraArch): Boolean = when {
        base == UNKNOWN || this == UNKNOWN || this == WAN -> false
        this == FLUX_ANY -> base == FLUX1 || base == FLUX2 || base == FLUX_ANY
        base == FLUX_ANY -> this == FLUX1 || this == FLUX2
        else -> this == base
    }
}

enum class LoraFormat { KOHYA, PEFT, LOKR, LOHA }

/** 导入时读文件头得出的结论；[problem] 非空 = 不能用，界面直接显示原因。 */
data class LoraCheck(
    val problem: LoraProblem? = null,
    val format: LoraFormat? = null,
    val arch: LoraArch = LoraArch.UNKNOWN,
    val rank: Int = 0,
    val tensors: Int = 0,
    val textEncoderPart: Boolean = false,
) { val ok: Boolean get() = problem == null }

enum class LoraProblem { NOT_SAFETENSORS, NOT_LORA, FULL_MODEL, TEXT_ONLY, UNKNOWN_ARCH, VIDEO, DTYPE }

/**
 * LoRA 文件头检查（纯函数，单测）：safetensors = 8 字节小端头长度 + JSON（张量名 → dtype/shape）。
 * 只读头，不读权重。格式：kohya（lora_down/up）、diffusers/PEFT（lora_A/B）、LyCORIS LoKr / LoHa。
 * 结构按键名（及交叉注意力输入宽度）判断。部分思路参考 hexanima AnimaLora（MIT，见 third_party/NOTICE）。
 */
object LoraInspect {
    /** 头最大 64 MB（再大不是正常 LoRA）。 */
    const val MAX_HEADER = 64L shl 20
    private val DTYPES = setOf("F32", "F16", "BF16", "F8_E4M3", "F8_E5M2", "F64")

    /** 从流里读出 JSON 头；不是 safetensors 返回 null。 */
    fun readHeader(input: InputStream): String? {
        val lenB = ByteArray(8)
        if (input.readNBytesCompat(lenB) != 8) return null
        var len = 0L
        for (i in 7 downTo 0) len = (len shl 8) or (lenB[i].toLong() and 0xFF)
        if (len < 2 || len > MAX_HEADER) return null
        val b = ByteArray(len.toInt())
        if (input.readNBytesCompat(b) != b.size) return null
        val s = String(b, Charsets.UTF_8).trimEnd()
        return s.takeIf { it.startsWith("{") }
    }

    private fun InputStream.readNBytesCompat(b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    fun check(file: File): LoraCheck = runCatching { file.inputStream().buffered().use { check(readHeader(it)) } }
        .getOrElse { LoraCheck(LoraProblem.NOT_SAFETENSORS) }

    /** [header] = safetensors 的 JSON 头（null = 读不出）。 */
    fun check(header: String?): LoraCheck {
        if (header == null) return LoraCheck(LoraProblem.NOT_SAFETENSORS)
        val obj = runCatching { Json.parseToJsonElement(header).jsonObject }.getOrNull() ?: return LoraCheck(LoraProblem.NOT_SAFETENSORS)
        val shapes = HashMap<String, List<Int>>()
        val dtypes = HashSet<String>()
        for ((k, v) in obj) {
            if (k == "__metadata__") continue
            val o = v as? JsonObject ?: continue
            o["dtype"]?.jsonPrimitive?.content?.let { dtypes += it }
            shapes[k] = runCatching { o["shape"]!!.jsonArray.map { it.jsonPrimitive.intOrNull ?: 0 } }.getOrDefault(emptyList())
        }
        return check(shapes, dtypes)
    }

    private val LORA_KEY = Regex("lora_down|lora_up|lora_A|lora_B|lora\\.down|lora\\.up|lokr_w1|lokr_w2|hada_w1_a|hada_w2_a|lokr_t2")
    private val TE_KEY = Regex("^(lora_te\\d?_|te\\d?[._]|text_encoder(_2)?[._]|lora_te_text_model|text_model[._]|base_model\\.model\\.text|clip_[lg][._]|t5xxl[._]|lycoris_te)")
    private val UNET = Regex("(down_blocks|up_blocks|mid_block|input_blocks|output_blocks|middle_block)[._]")
    private val ANIMA = Regex("blocks[._]\\d+[._](self_attn|cross_attn)[._](q_proj|k_proj|v_proj|output_proj)|adaln_modulation_(self|cross)_attn|llm_adapter")
    private val WAN = Regex("blocks[._]\\d+[._](self_attn|cross_attn)[._][qkvo]([._]|$)")
    private val ZIMG = Regex("noise_refiner|context_refiner|layers[._]\\d+[._](attention|feed_forward)[._]")
    private val QWEN = Regex("transformer_blocks[._]\\d+[._](img_mlp|txt_mlp|img_mod|txt_mod)")
    private val FLUX = Regex("double_blocks|single_blocks|single_transformer_blocks")
    private val FLUX2 = Regex("double_stream_modulation|single_stream_modulation")
    private val SD3 = Regex("joint_blocks|context_block")
    private val DIFFUSERS_DIT = Regex("transformer_blocks[._]\\d+[._](attn|ff_context)")
    private val BLOCK_IDX = Regex("(single|double)_(?:transformer_)?blocks[._](\\d+)")
    private val ATTN2_K = Regex("attn2[._]to_k[._](lora_down|lora_A|lora\\.down)")
    private val FULL = Regex("^(model\\.diffusion_model\\.|first_stage_model\\.|cond_stage_model\\.|conditioner\\.|double_blocks\\.\\d+\\.img_attn\\.qkv\\.weight$|blocks\\.\\d+\\.self_attn\\.q_proj\\.weight$|layers\\.\\d+\\.attention\\.)")

    fun check(shapes: Map<String, List<Int>>, dtypes: Set<String>): LoraCheck {
        val keys = shapes.keys
        val loraKeys = keys.filter { LORA_KEY.containsMatchIn(it) }
        if (loraKeys.isEmpty()) {
            return LoraCheck(if (keys.count { FULL.containsMatchIn(it) } >= 8) LoraProblem.FULL_MODEL else LoraProblem.NOT_LORA, tensors = keys.size)
        }
        if (dtypes.any { it !in DTYPES }) return LoraCheck(LoraProblem.DTYPE, tensors = keys.size)
        val format = when {
            loraKeys.any { it.contains("lokr_") } -> LoraFormat.LOKR
            loraKeys.any { it.contains("hada_") } -> LoraFormat.LOHA
            loraKeys.any { it.contains("lora_A") || it.contains("lora_B") } -> LoraFormat.PEFT
            else -> LoraFormat.KOHYA
        }
        val te = loraKeys.filter { TE_KEY.containsMatchIn(it) }
        val model = loraKeys.filter { it !in te.toSet() }
        if (model.isEmpty()) return LoraCheck(LoraProblem.TEXT_ONLY, format, tensors = keys.size, textEncoderPart = true)
        val rank = model.mapNotNull { k ->
            val s = shapes[k] ?: return@mapNotNull null
            when {
                k.contains("lora_down") || k.contains("lora_A") || k.contains("lora.down") -> s.firstOrNull()
                k.contains("hada_w1_a") -> s.getOrNull(1)
                else -> null
            }
        }.filter { it > 0 }.maxOrNull() ?: 0
        val arch = archOf(model, shapes, te.isNotEmpty())
        val base = LoraCheck(null, format, arch, rank, keys.size, te.isNotEmpty())
        return when (arch) {
            LoraArch.UNKNOWN -> base.copy(problem = LoraProblem.UNKNOWN_ARCH)
            LoraArch.WAN -> base.copy(problem = LoraProblem.VIDEO)
            else -> base
        }
    }

    /** 结构判断（只看非文本编码器部分的键）。 */
    fun archOf(model: List<String>, shapes: Map<String, List<Int>>, hasTe: Boolean = false, allKeys: Collection<String> = shapes.keys): LoraArch {
        fun any(r: Regex) = model.any { r.containsMatchIn(it) }
        return when {
            any(ANIMA) -> LoraArch.ANIMA
            any(WAN) -> LoraArch.WAN
            any(QWEN) -> LoraArch.QWEN_IMAGE
            any(ZIMG) -> LoraArch.Z_IMAGE
            any(FLUX2) -> LoraArch.FLUX2
            any(FLUX) -> {
                val idx = model.mapNotNull { k -> BLOCK_IDX.find(k)?.let { it.groupValues[1] to it.groupValues[2].toInt() } }
                val maxSingle = idx.filter { it.first == "single" }.maxOfOrNull { it.second } ?: -1
                val maxDouble = idx.filter { it.first == "double" }.maxOfOrNull { it.second } ?: -1
                when {
                    maxSingle >= 30 || maxDouble >= 12 -> LoraArch.FLUX1
                    else -> LoraArch.FLUX_ANY
                }
            }
            any(SD3) -> LoraArch.SD3
            any(UNET) -> {
                // 交叉注意力 to_k 的输入宽度 = 文本向量宽度：768 SD1，1024 SD2，2048 SDXL
                val ctx = model.filter { ATTN2_K.containsMatchIn(it) }.mapNotNull { k -> shapes[k]?.getOrNull(1) }.maxOrNull()
                when {
                    ctx == 2048 -> LoraArch.SDXL
                    ctx == 1024 -> LoraArch.SD2
                    ctx == 768 -> LoraArch.SD1
                    allKeys.any { it.startsWith("lora_te1_") || it.startsWith("lora_te2_") || it.contains("text_encoder_2") } -> LoraArch.SDXL
                    model.any { Regex("(input_blocks_[4-8]_1|down_blocks_[12]_attentions_\\d)_transformer_blocks_[1-9]").containsMatchIn(it) } -> LoraArch.SDXL
                    else -> LoraArch.SD1
                }
            }
            any(DIFFUSERS_DIT) -> LoraArch.SD3
            else -> LoraArch.UNKNOWN
        }
    }

    /** 底模（sd.cpp 用的模型文件）的结构，按张量名判断；[ggufArch] = GGUF 的 general.architecture。 */
    fun baseArch(tensors: List<String>, ggufArch: String? = null): LoraArch {
        val g = ggufArch?.lowercase()
        when (g) {
            "sd1" -> return LoraArch.SD1
            "sd2" -> return LoraArch.SD2
            "sdxl" -> return LoraArch.SDXL
            "sd3" -> return LoraArch.SD3
            "flux" -> return LoraArch.FLUX1
            "flux2" -> return LoraArch.FLUX2
            "z_image" -> return LoraArch.Z_IMAGE
            "anima" -> return LoraArch.ANIMA
            "qwen_image" -> return LoraArch.QWEN_IMAGE
            "wan" -> return LoraArch.WAN
        }
        val t = tensors.map { it.removePrefix("model.diffusion_model.").removePrefix("diffusion_model.") }
        fun any(p: (String) -> Boolean) = t.any(p)
        return when {
            any { it.contains("llm_adapter") } -> LoraArch.ANIMA
            tensors.any { it.startsWith("conditioner.embedders.1") } -> LoraArch.SDXL
            tensors.any { it.startsWith("cond_stage_model.model.") } -> LoraArch.SD2
            tensors.any { it.startsWith("cond_stage_model.transformer.") } -> LoraArch.SD1
            any { it.contains("cap_embedder") || it.contains("noise_refiner") } -> LoraArch.Z_IMAGE
            any { it.contains("double_stream_modulation") } -> LoraArch.FLUX2
            any { it.startsWith("double_blocks.") } -> LoraArch.FLUX1
            any { it.startsWith("joint_blocks.") } -> LoraArch.SD3
            any { it.startsWith("transformer_blocks.") && it.contains("img_mlp") } -> LoraArch.QWEN_IMAGE
            any { Regex("^blocks\\.\\d+\\.self_attn\\.q_proj").containsMatchIn(it) } -> LoraArch.ANIMA
            any { Regex("^blocks\\.\\d+\\.self_attn\\.q\\.").containsMatchIn(it) } -> LoraArch.WAN
            any { it.startsWith("input_blocks.") } -> {
                val ctx = tensors.firstOrNull { it.endsWith("attn2.to_k.weight") }
                if (any { it.startsWith("label_emb.") }) LoraArch.SDXL else if (ctx != null) LoraArch.SD1 else LoraArch.SD1
            }
            else -> LoraArch.UNKNOWN
        }
    }

    private val TURBO = Regex("turbo|lightning|hyper|lcm|dmd|distill|\\d+\\s*-?\\s*steps?", RegexOption.IGNORE_CASE)
    private val STEPS = Regex("(\\d{1,2})\\s*-?\\s*steps?", RegexOption.IGNORE_CASE)

    /** 少步数（Turbo 一类）LoRA：文件名上看得出来。 */
    fun isTurbo(name: String): Boolean = TURBO.containsMatchIn(name)

    /** Turbo LoRA 的建议步数：名字里写了 N step 就用 N，否则 8。 */
    fun turboSteps(name: String): Int = STEPS.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..50 } ?: 8
}

/** 一个已导入的 LoRA。文件在 filesDir/imagegen/lora/<file>。 */
@Serializable
data class LoraEntry(
    val id: String,
    val name: String,
    val file: String,
    val arch: String,
    val format: String,
    val rank: Int = 0,
    val on: Boolean = false,
    val scale: Float = 1f,
    /** Turbo 一类 LoRA 的步数（null = 不是少步数 LoRA）。 */
    val steps: Int? = null,
) {
    val archOrUnknown: LoraArch get() = LoraArch.entries.firstOrNull { it.name == arch } ?: LoraArch.UNKNOWN
}

/** 生成时用的一个 LoRA。 */
data class LoraUse(val path: String, val scale: Float)

object LoraRules {
    const val MIN_SCALE = 0f
    const val MAX_SCALE = 2f

    fun clampScale(v: Float): Float = if (v.isNaN()) 1f else Math.round(v.coerceIn(MIN_SCALE, MAX_SCALE) * 20f) / 20f

    /** 当前底模能用、开着、强度非 0 的 LoRA。 */
    fun active(list: List<LoraEntry>, base: LoraArch, dir: File): List<LoraUse> =
        list.filter { it.on && it.archOrUnknown.fits(base) && clampScale(it.scale) > 0f }
            .map { LoraUse(File(dir, it.file).path, clampScale(it.scale)) }

    /** 文件名 → 存储名（只留安全字符）。 */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "lora" }
        return "$base.safetensors"
    }
}

/** LoRA 清单（filesDir/imagegen/loras.json）。主进程读写；生成时把路径随请求传给 :imagegen。 */
class LoraStore(filesDir: File) {
    val dir = File(filesDir, "imagegen/lora")
    private val listFile = File(filesDir, "imagegen/loras.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<LoraEntry> = runCatching {
        if (!listFile.isFile) emptyList() else json.decodeFromString<List<LoraEntry>>(listFile.readText()).filter { File(dir, it.file).isFile }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(list: List<LoraEntry>) {
        listFile.parentFile?.mkdirs()
        val tmp = File(listFile.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(listFile)) { listFile.writeText(tmp.readText()); tmp.delete() }
    }

    fun update(id: String, f: (LoraEntry) -> LoraEntry) = save(list().map { if (it.id == id) f(it) else it })

    fun delete(id: String) {
        val l = list()
        l.firstOrNull { it.id == id }?.let { File(dir, it.file).delete() }
        save(l.filter { it.id != id })
    }

    /** 不重名的存储文件。 */
    fun target(name: String): File {
        dir.mkdirs()
        val safe = LoraRules.safeFileName(name)
        var f = File(dir, safe)
        var i = 2
        while (f.exists()) { f = File(dir, safe.removeSuffix(".safetensors") + "-$i.safetensors"); i++ }
        return f
    }
}

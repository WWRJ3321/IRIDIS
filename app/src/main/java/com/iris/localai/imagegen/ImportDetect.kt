package com.iris.localai.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * 通用导入。读文件头（safetensors 的 JSON 头 / GGUF 的元数据与张量表）判断每个文件是什么，
 * 不按模型牌子列预设。架构本身由 sd.cpp 按张量名识别，这里只决定每个文件走哪个参数
 * （model / diffusion_model / clip_l / clip_g / t5xxl / llm / vae）和默认步数、CFG。
 */
enum class FileRole { CHECKPOINT, DIFFUSION, LLM, T5, CLIP_L, CLIP_G, VAE, NPU, UNKNOWN }

/** 主模型的大致家族：只用来填默认值与复用已下载的编码器，不显示给用户。 */
enum class Family { SD_CHECKPOINT, ANIMA, Z_IMAGE, FLUX2, DIT }

data class FileHead(val tensors: List<String>, val ggufArch: String? = null)

data class Defaults(val steps: Int, val cfg: Float, val tagStyle: Boolean)

object ImportDetect {
    private val json = Json { ignoreUnknownKeys = true }

    /** 读头；不是 safetensors / GGUF 或读失败返回 null。只读头部，不读权重。 */
    fun readHead(fileName: String, input: InputStream): FileHead? = runCatching {
        val n = fileName.lowercase()
        when {
            n.endsWith(".safetensors") -> readSafetensors(input)
            n.endsWith(".gguf") -> readGguf(input)
            else -> null
        }
    }.getOrNull()

    private fun readSafetensors(input: InputStream): FileHead? {
        val d = DataInputStream(input)
        val b = ByteArray(8); d.readFully(b)
        var len = 0L
        for (i in 7 downTo 0) len = (len shl 8) or (b[i].toLong() and 0xFF)
        if (len !in 2..64_000_000L) return null
        val h = ByteArray(len.toInt()); d.readFully(h)
        val obj = json.parseToJsonElement(String(h, Charsets.UTF_8)).jsonObject
        return FileHead(obj.keys.filter { it != "__metadata__" })
    }

    private class Le(input: InputStream) {
        private val d = DataInputStream(input)
        fun u8() = d.readUnsignedByte()
        fun u32(): Long { val b = ByteArray(4); d.readFully(b); var v = 0L; for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
        fun u64(): Long { val b = ByteArray(8); d.readFully(b); var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
        fun skip(n: Long) { var left = n; while (left > 0) { val s = d.skip(left); if (s <= 0) { d.readByte(); left-- } else left -= s } }
        fun str(): String { val n = u64(); if (n !in 0..1_000_000L) throw EOFException("bad str"); val b = ByteArray(n.toInt()); d.readFully(b); return String(b, Charsets.UTF_8) }
    }

    private fun skipValue(r: Le, type: Long) {
        when (type) {
            0L, 1L, 7L -> r.skip(1)
            2L, 3L -> r.skip(2)
            4L, 5L, 6L -> r.skip(4)
            10L, 11L, 12L -> r.skip(8)
            8L -> r.skip(r.u64())
            9L -> { val t = r.u32(); val n = r.u64(); if (t == 8L) repeat(n.toInt()) { r.skip(r.u64()) } else for (i in 0 until n) skipValue(r, t) }
            else -> throw EOFException("bad gguf type $type")
        }
    }

    private fun readGguf(input: InputStream): FileHead? {
        val r = Le(input)
        if (r.u32() != 0x46554747L) return null // "GGUF"
        val ver = r.u32(); if (ver < 2) return null
        val nTensors = r.u64(); val nKv = r.u64()
        if (nTensors !in 0..200_000L || nKv !in 0..100_000L) return null
        var arch: String? = null
        for (i in 0 until nKv) {
            val key = r.str(); val type = r.u32()
            if (key == "general.architecture" && type == 8L) arch = r.str() else skipValue(r, type)
        }
        val names = ArrayList<String>(nTensors.toInt())
        for (i in 0 until nTensors) {
            names += r.str()
            val nd = r.u32(); r.skip(8 * nd); r.skip(4); r.skip(8)
        }
        return FileHead(names, arch)
    }

    private val IMAGE_GGUF_ARCH = setOf("sd1", "sd2", "sdxl", "sd3", "flux", "flux2", "z_image", "qwen_image", "chroma", "anima", "wan", "lumina2")

    /** 的 NPU 包文件名（[AnimaQnnFiles.NAMES]）。 */
    fun isNpuFile(fileName: String) = fileName in AnimaQnnFiles.NAMES || isZip(fileName)

    /** NPU 包整包 zip（导入时解出包内认得的文件）。 */
    fun isZip(fileName: String) = fileName.lowercase().endsWith(".zip")

    /** 这批文件是 NPU 包：至少有一个 zip 或非通用名（[AnimaQnnFiles.WEAK] 之外）的包内文件。 */
    fun isNpuPlan(names: List<String>) = names.any { isZip(it) || (it in AnimaQnnFiles.NAMES && it !in AnimaQnnFiles.WEAK) }

    fun role(fileName: String, head: FileHead?): FileRole {
        if (isNpuFile(fileName) && !fileName.endsWith(".gguf") && !fileName.endsWith(".safetensors")) return FileRole.NPU
        if (head == null) return FileRole.UNKNOWN
        val t = head.tensors
        fun any(p: (String) -> Boolean) = t.any(p)
        val arch = head.ggufArch?.lowercase()
        if (any { it.startsWith("cond_stage_model.") || it.startsWith("conditioner.embedders.") || it.startsWith("text_encoders.") }) return FileRole.CHECKPOINT
        if (arch != null && arch.startsWith("t5") || any { it.startsWith("encoder.block.") || it.startsWith("enc.blk.") }) return FileRole.T5
        if (arch != null && arch !in IMAGE_GGUF_ARCH && any { it == "token_embd.weight" || it.startsWith("blk.") }) return FileRole.LLM
        if (any { it.startsWith("model.layers.") || it == "model.embed_tokens.weight" } && !any { isDiffusionKey(it) }) return FileRole.LLM
        if (any { it.contains("text_model.encoder.layers.") } && !any { isDiffusionKey(it) }) {
            val maxLayer = t.mapNotNull { Regex("text_model\\.encoder\\.layers\\.(\\d+)\\.").find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
            return if (maxLayer >= 20) FileRole.CLIP_G else FileRole.CLIP_L
        }
        if (any { isDiffusionKey(it) }) return FileRole.DIFFUSION
        if (any { it.startsWith("decoder.") || it.startsWith("first_stage_model.") || it.startsWith("vae.") }) return FileRole.VAE
        return FileRole.UNKNOWN
    }

    private fun isDiffusionKey(k: String) =
        k.startsWith("model.diffusion_model.") || k.startsWith("input_blocks.") || k.startsWith("double_blocks.") ||
            k.startsWith("joint_blocks.") || k.startsWith("transformer_blocks.") || k.startsWith("single_blocks.") ||
            k.startsWith("layers.") || k.startsWith("blocks.") || k.startsWith("net.") || k.contains("llm_adapter") ||
            k.startsWith("cap_embedder") || k.startsWith("x_embedder") || k.startsWith("img_in.")

    fun family(role: FileRole, head: FileHead?): Family {
        if (role == FileRole.CHECKPOINT) return Family.SD_CHECKPOINT
        val t = head?.tensors.orEmpty()
        return when {
            t.any { it.contains("llm_adapter") } -> Family.ANIMA
            t.any { it.contains("cap_embedder") || it.contains("noise_refiner") } -> Family.Z_IMAGE
            t.any { it.contains("double_stream_modulation_img") } -> Family.FLUX2
            else -> Family.DIT
        }
    }

    private val FAST = Regex("turbo|schnell|lightning|lcm|hyper|distill|klein|4step|8step", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("illustrious|noob|pony|anima|anything|anime|waifu|animagine", RegexOption.IGNORE_CASE)

    fun defaults(family: Family, fileName: String): Defaults {
        val tags = family == Family.ANIMA || TAGS.containsMatchIn(fileName)
        val fast = FAST.containsMatchIn(fileName)
        return when (family) {
            Family.ANIMA -> Defaults(30, 4f, true)
            Family.Z_IMAGE -> Defaults(8, 1f, tags)
            Family.FLUX2 -> Defaults(4, 1f, tags)
            Family.SD_CHECKPOINT -> if (fast) Defaults(6, 1.5f, tags) else Defaults(20, 7f, tags)
            Family.DIT -> if (fast) Defaults(6, 1f, tags) else Defaults(20, 3.5f, tags)
        }
    }

    /** 已下载、可复用的文本编码器 / VAE（相对模型根目录）；没有就是空串。 */
    fun reusePaths(family: Family): Pair<String, String> = when (family) {
        Family.ANIMA -> ImportArch.ANIMA.llmPath to ImportArch.ANIMA.vaePath
        Family.Z_IMAGE -> ImportArch.Z_IMAGE.llmPath to ImportArch.Z_IMAGE.vaePath
        Family.FLUX2 -> ImportArch.FLUX2_4B.llmPath to ImportArch.FLUX2_4B.vaePath
        else -> "" to ""
    }

    /** 粗估内存是否放得下：权重总大小超过总内存的 85% 就拒绝（mmap + GPU 缓冲区仍要占内存）。 */
    fun tooBig(totalBytes: Long, totalMem: Long): Boolean = totalMem > 0 && totalBytes > totalMem * 0.85
}

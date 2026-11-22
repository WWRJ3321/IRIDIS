package com.iris.localai.imagegen

import java.io.File

/**
 * 3.0.0 Beta3 r5：生图尺寸按模型类型给选项和默认值（纯逻辑，单测 V300B3R5Test）。
 *
 * - 固定尺寸的 NPU 包（SD1.5 / Anything NPU）：只有它那一档。
 * - Anima NPU 包：包里编好的几档（引擎第一次加载时写进模型目录的 [PACK_FILE]）；还没加载过就先给 512×512。
 * - SD1.5 / SD2（512 基）：512 档的常用比例。
 * - SDXL、FLUX.2、Z-Image、Anima 等（1024 基）：约 1MP 以内的常用比例（长边不超过 [ImageRequest.MAX_DIM]），默认 1024×1024。
 */
object ImageSizes {
    enum class Base(val side: Int) { SD15(512), XL(1024) }

    data class Option(val width: Int, val height: Int, val ratio: String) {
        val label: String get() = "$ratio · $width×$height"
    }

    /** 写在 Anima NPU 包目录里的尺寸清单（一行一个 "宽x高"）。 */
    const val PACK_FILE = ".iris_sizes"

    private val SD15 = listOf(
        Option(512, 512, "1:1"), Option(512, 768, "2:3"), Option(768, 512, "3:2"),
        Option(576, 768, "3:4"), Option(768, 576, "4:3"), Option(448, 800, "9:16"), Option(800, 448, "16:9"),
    )
    private val XL = listOf(
        Option(1024, 1024, "1:1"), Option(768, 1024, "3:4"), Option(1024, 768, "4:3"),
        Option(672, 1008, "2:3"), Option(1008, 672, "3:2"), Option(576, 1024, "9:16"), Option(1024, 576, "16:9"),
        Option(768, 768, "1:1"), Option(512, 512, "1:1"),
    )

    /** 模型的基准分辨率；[arch] = 读文件头得到的结构（导入的 sd.cpp 模型），UNKNOWN 时按名字猜。 */
    fun base(model: ImageModel, arch: LoraArch = LoraArch.UNKNOWN): Base = when {
        arch == LoraArch.SD1 || arch == LoraArch.SD2 -> Base.SD15
        arch != LoraArch.UNKNOWN -> Base.XL
        model.backend == Backend.MNN_CPU || model.fixedSize == 512 -> Base.SD15
        Regex("(?i)(sd[-_ ]?1\\.?5|sd15|v1-5|anything)").containsMatchIn(model.name + " " + model.id) -> Base.SD15
        else -> Base.XL
    }

    fun ratioOf(w: Int, h: Int): String {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(w, h).coerceAtLeast(1)
        val known = listOf(1 to 1, 2 to 3, 3 to 2, 3 to 4, 4 to 3, 9 to 16, 16 to 9)
        val r = w.toDouble() / h
        val near = known.minBy { (a, b) -> kotlin.math.abs(a.toDouble() / b - r) }
        return if (kotlin.math.abs(near.first.toDouble() / near.second - r) < 0.04) "${near.first}:${near.second}" else "${w / g}:${h / g}"
    }

    /** 可选尺寸。[packSizes] = Anima NPU 包里编好的尺寸（读 [PACK_FILE]）。 */
    fun options(model: ImageModel, arch: LoraArch = LoraArch.UNKNOWN, packSizes: List<Pair<Int, Int>> = emptyList()): List<Option> {
        model.fixedSize?.let { return listOf(Option(it, it, "1:1")) }
        if (model.animaQnn) {
            val s = packSizes.ifEmpty { listOf(512 to 512) }
            return s.distinct().map { (w, h) -> Option(w, h, ratioOf(w, h)) }
        }
        return if (base(model, arch) == Base.SD15) SD15 else XL
    }

    fun default(options: List<Option>): Option = options.first()

    /** 换模型时：当前尺寸在新模型的选项里且基准相同就留着，否则用新模型的默认值。 */
    fun pick(curW: Int, curH: Int, options: List<Option>, sameBase: Boolean): Pair<Int, Int> {
        val keep = sameBase && options.any { it.width == curW && it.height == curH }
        val d = default(options)
        return if (keep) curW to curH else d.width to d.height
    }

    fun parsePack(text: String?): List<Pair<Int, Int>> = text.orEmpty().lines().mapNotNull { l ->
        Regex("^(\\d{3,4})x(\\d{3,4})$").find(l.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    fun formatPack(sizes: List<Pair<Int, Int>>): String = sizes.joinToString("\n") { (w, h) -> "${w}x$h" }

    fun readPack(dir: File?): List<Pair<Int, Int>> = runCatching { parsePack(File(dir ?: return emptyList(), PACK_FILE).takeIf { it.isFile }?.readText()) }.getOrDefault(emptyList())
}

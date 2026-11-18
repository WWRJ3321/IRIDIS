package com.iris.localai.imagegen

import kotlin.random.Random

data class ImageRequest(
    val prompt: String,
    val negative: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7.5f,
    val seed: Long = -1,
    val initImagePath: String? = null,
    val strength: Float = 0.75f,
    /** 低内存模式：用完即释放、VAE 放 CPU（mmap）、尺寸上限 [ImageLowMem.MAX_DIM]。 */
    val lowMem: Boolean = false,
) {
    /** Negative prompt actually used: ignored without classifier-free guidance. */
    val effectiveNegative: String get() = if (cfg <= 1f) "" else negative

    fun normalize(model: ImageModel, random: Random = Random.Default): ImageRequest {
        fun dim(v: Int): Int = model.fixedSize ?: (v.coerceIn(MIN_DIM, MAX_DIM) / 16 * 16)
        val (w, h) = if (lowMem && model.fixedSize == null) ImageLowMem.fit(dim(width), dim(height)) else dim(width) to dim(height)
        return copy(
            width = w,
            height = h,
            steps = steps.coerceIn(1, 50),
            cfg = if (cfg.isNaN()) model.defaultCfg else cfg.coerceIn(1f, 20f),
            strength = if (strength.isNaN()) 0.75f else strength.coerceIn(0f, 1f),
            seed = if (seed < 0) random.nextLong(0, Long.MAX_VALUE) else seed,
        )
    }

    companion object {
        const val MIN_DIM = 256
        const val MAX_DIM = 1024
    }
}

/**
 * the 生图 seed field. Empty (or 「随机」) = a new random seed for every image; only a seed the
 * user typed stays fixed. The seed actually used is shown with the result and never written back into
 * the field (that turned every later image into a repeat until the field was cleared by hand). Pure.
 */
object ImageSeed {
    /** The value [ImageRequest.seed] gets from the field text: the typed seed, or -1 (= random, drawn in [ImageRequest.normalize]). */
    fun fromField(text: String): Long = text.trim().toLongOrNull()?.takeIf { it >= 0 } ?: -1L

    fun isRandom(text: String): Boolean = fromField(text) < 0

    /** The seed for one generation: the fixed one, or a fresh random draw each call. */
    fun pick(text: String, random: Random = Random.Default): Long =
        fromField(text).takeIf { it >= 0 } ?: random.nextLong(0, Long.MAX_VALUE)

    /** Field input: digits only, at most 18 (fits a Long). */
    fun sanitize(input: String): String = input.filter(Char::isDigit).take(18)
}

/**
 * 生图低内存模式（纯规则，单测覆盖）。思路：峰值 = 常驻权重 + 计算缓冲（随像素数涨）。
 * 1) 长边限到 [MAX_DIM]（等比缩，16 对齐）；2) sd.cpp 的 VAE 也放 CPU（权重留在 mmap，不复制进 Vulkan 缓冲）；
 * 3) 每张图生成完立刻释放全部权重；4) 生成前总是先卸载文本模型。文本编码器本来就在 CPU（mmap）。
 * 原生层的 params_backend=disk / max_vram 需要重编并重新托管 libiris_sd.so，留待交接（见 HANDOFF）。
 */
object ImageLowMem {
    const val MAX_DIM = 768
    const val GPU = "diffusion=vulkan0,te=cpu,vae=cpu"

    fun fit(w: Int, h: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= MAX_DIM) return w to h
        val k = MAX_DIM.toDouble() / long
        fun r(v: Int) = ((v * k).toInt() / 16 * 16).coerceAtLeast(ImageRequest.MIN_DIM)
        return r(w) to r(h)
    }

    /** sd.cpp 后端：低内存时 GPU 方案换成 VAE 也在 CPU 的版本。 */
    fun backend(b: String, lowMem: Boolean): String = if (lowMem && b == SdRules.GPU) GPU else b
}

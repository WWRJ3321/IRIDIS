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
) {
    /** Negative prompt actually used: ignored without classifier-free guidance. */
    val effectiveNegative: String get() = if (cfg <= 1f) "" else negative

    fun normalize(model: ImageModel, random: Random = Random.Default): ImageRequest {
        fun dim(v: Int): Int = model.fixedSize ?: (v.coerceIn(MIN_DIM, MAX_DIM) / 16 * 16)
        return copy(
            width = dim(width),
            height = dim(height),
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
 * 2.5.2: the 生图 seed field. Empty (or 「随机」) = a new random seed for every image; only a seed the
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

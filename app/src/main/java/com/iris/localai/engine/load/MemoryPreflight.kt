package com.iris.localai.engine

/**
 * 2.2.14: before a load, compare free RAM with what the model needs (weights + KV cache + a
 * fixed overhead; a GPU load also copies the weights into GPU buffers). Never blocks: a tight
 * fit only produces a warning that suggests a smaller model / tier, a shorter context or CPU.
 * Works next to the 2.2.13 crash memory (safe mode), not instead of it.
 */
object MemoryPreflight {
    enum class Level { OK, TIGHT, SHORT }
    data class Verdict(val level: Level, val needBytes: Long, val availBytes: Long, val message: String?)

    private const val MB = 1024L * 1024
    private const val OVERHEAD = 300 * MB

    /** Rough KV bytes per token, scaled with model size (1B ≈ 16 KB, 7B ≈ 100 KB at f16). */
    fun kvPerToken(modelBytes: Long): Long = maxOf(16 * 1024L, modelBytes / 40_000)

    fun need(modelBytes: Long, contextLength: Int, gpu: Boolean): Long =
        modelBytes + modelBytes / 8 + kvPerToken(modelBytes) * contextLength.coerceAtLeast(512) + OVERHEAD +
            (if (gpu) modelBytes else 0L)

    fun check(availBytes: Long, modelBytes: Long, contextLength: Int, gpu: Boolean): Verdict {
        if (modelBytes <= 0 || availBytes <= 0) return Verdict(Level.OK, 0, availBytes, null)
        val need = need(modelBytes, contextLength, gpu)
        val level = when {
            need <= availBytes -> Level.OK
            need <= availBytes + availBytes / 2 -> Level.TIGHT
            else -> Level.SHORT
        }
        if (level == Level.OK) return Verdict(level, need, availBytes, null)
        val tip = buildList {
            if (gpu) add("改用 CPU")
            if (contextLength > 2048) add("把上下文减到 ${maxOf(2048, contextLength / 2)}")
            add("换更小的模型或档位")
        }.joinToString("、")
        val head = if (level == Level.SHORT) "内存可能不够，加载时可能闪退" else "内存偏紧"
        return Verdict(level, need, availBytes, "$head：约需 ${gb(need)}，可用 ${gb(availBytes)}。建议$tip")
    }

    fun gb(b: Long): String = String.format(java.util.Locale.US, "%.1f GB", b / (1024.0 * MB))
}

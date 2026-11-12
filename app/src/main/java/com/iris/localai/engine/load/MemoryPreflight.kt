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

    /**
     * 2.5.1: 专家按需加载（MoE）时的内存需求：非专家张量 + 专家张量的 25%（常驻热点页）+ KV；
     * GPU 只额外拷贝非专家部分。[extraBytes] 为 mmproj 等其它文件。
     */
    fun needMoe(nonExpertBytes: Long, expertBytes: Long, extraBytes: Long, contextLength: Int, gpu: Boolean): Long {
        val resident = nonExpertBytes + expertBytes / 4 + extraBytes
        val total = nonExpertBytes + expertBytes + extraBytes
        return resident + resident / 8 + kvPerToken(total) * contextLength.coerceAtLeast(512) + OVERHEAD +
            (if (gpu) nonExpertBytes + extraBytes else 0L)
    }

    fun check(availBytes: Long, modelBytes: Long, contextLength: Int, gpu: Boolean, needOverride: Long = 0L): Verdict {
        if (modelBytes <= 0 || availBytes <= 0) return Verdict(Level.OK, 0, availBytes, null)
        val need = if (needOverride > 0) needOverride else need(modelBytes, contextLength, gpu)
        val level = when {
            need <= availBytes -> Level.OK
            need <= availBytes + availBytes / 2 -> Level.TIGHT
            else -> Level.SHORT
        }
        if (level == Level.OK) return Verdict(level, need, availBytes, null)
        val tip = buildList {
            if (gpu) add(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_52746f))
            if (contextLength > 2048) add(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ea59f5, maxOf(2048, contextLength / 2)))
            add(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_0e6914))
        }.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b50566))
        val head = if (level == Level.SHORT) com.iris.localai.I18n.s(com.iris.localai.R.string.engine_8b435d) else com.iris.localai.I18n.s(com.iris.localai.R.string.engine_6c66e0)
        return Verdict(level, need, availBytes, com.iris.localai.I18n.s(com.iris.localai.R.string.engine_ece637, head, gb(need), gb(availBytes), tip))
    }

    fun gb(b: Long): String = String.format(java.util.Locale.US, "%.1f GB", b / (1024.0 * MB))
}

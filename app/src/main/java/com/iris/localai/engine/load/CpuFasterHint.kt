package com.iris.localai.engine

/**
 * 1.10.3: the model sheet's one-line suggestion 「建议：这个模型在 CPU 上加载更快（可选）」 + 「改用 CPU」, from the
 * real load records of 1.10.2 ([LoadStatsStore], one record per model + backend, successful loads only).
 *
 * Shown when, for one model:
 *  - its last GPU load took more than [GPU_SLOW_MS] (60 s), and
 *  - a CPU load of the same model was faster than that GPU load, and
 *  - the model would still start on the GPU next time ([nextBackend]: its GPU choice or 自动 = GPU;
 *    after 「改用 CPU」 there is nothing left to suggest).
 * Only a suggestion: GPU stays the default (user: 「可我想用GPU」), nothing switches by itself.
 * Pure, unit-tested. Independent of [SlowLoadHint] (the 45 s card during a running GPU load).
 */
object CpuFasterHint {
    const val GPU_SLOW_MS = 60_000L
    /** A suggestion only: nothing changes unless the user taps [ACTION]; GPU stays the default. */
    val TEXT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_35156c)
    val ACTION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_52746f)

    fun shouldShow(records: Collection<LoadRecord>, modelId: String, nextBackend: String?): Boolean {
        if (!Backend.GPU.label.equals(nextBackend?.trim(), ignoreCase = true)) return false
        fun last(b: Backend) = records.filter { it.modelId == modelId && it.ok && it.backend.equals(b.label, ignoreCase = true) }.maxByOrNull { it.at }
        val gpu = last(Backend.GPU) ?: return false
        val cpu = last(Backend.CPU) ?: return false
        return gpu.ms > GPU_SLOW_MS && cpu.ms < gpu.ms
    }
}

package com.iris.localai.engine

/**
 * 2.2.14: a GPU (OpenCL) load that stops making progress. On some Adreno drivers the new
 * llama.cpp OpenCL backend sits in kernel compilation / driver init and never returns, and the
 * native call cannot be interrupted. Any change of the progress signature (load progress,
 * OpenCL log ticks, compiled-kernel cache files) counts as progress; [timeoutMs] without a
 * change is a hang. Pure logic, driven by [EngineManager] once per second.
 */
class GpuLoadWatchdog(val timeoutMs: Long = TIMEOUT_MS) {
    private var lastSignature: Long? = null
    private var lastChangeAt = 0L

    fun start(now: Long, signature: Long) {
        lastSignature = signature
        lastChangeAt = now
    }

    /** True when the signature has not changed for [timeoutMs]. */
    fun observe(now: Long, signature: Long): Boolean {
        if (lastSignature == null || signature != lastSignature) {
            start(now, signature)
            return false
        }
        return now - lastChangeAt >= timeoutMs
    }

    fun stalledFor(now: Long): Long = if (lastSignature == null) 0 else now - lastChangeAt

    companion object {
        const val TIMEOUT_MS = 25_000L

        /** Combines the three progress sources into one comparable value. */
        fun signature(progress: Float, ticks: Int, cacheFiles: Int): Long =
            ((progress.coerceIn(0f, 1f) * 1000).toLong() shl 42) or
                ((ticks.toLong() and 0x1FFFFF) shl 21) or (cacheFiles.toLong() and 0x1FFFFF)

        const val HANG_NOTICE = "GPU（OpenCL）加载卡住，已改用 CPU。可在模型设置里重试 GPU"

        fun hangDetail(ticks: Int, lastLog: String, stalledMs: Long): String =
            "OpenCL ${stalledMs / 1000}s 无进展 · 进度 $ticks" + lastLog.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }
}

package com.iris.localai.imagegen

/**
 * 2.4.2: what the 生图 progress line says (pure, unit-tested).
 *
 * sd.cpp reports three kinds of progress through one callback: weight loading (tensor counts — it loads
 * the text encoder and then the diffusion model lazily, inside the first generation: e.g. 386/386 then
 * 452/452), denoising steps (0/N when step 1 starts, then 1/N…N/N) and, at large sizes, VAE decode tiles.
 * 2.4.1 printed them all as 「第 x/y 步」, so a finished weight load looked like a frozen 452/452 step
 * while the (slow) first step was running.
 */
object SdProgress {
    enum class Phase { LOADING, STEP, FIRST_STEP, DECODING }

    data class State(
        val phase: Phase = Phase.FIRST_STEP,
        val done: Int = 0,
        val total: Int = 0,
        /** The last denoising step reached N/N: later counts are decode tiles. */
        val sampled: Boolean = false,
        /** A denoising step was reported (0/N counts): weight loads after it belong to step 1. */
        val stepping: Boolean = false,
        /** Denoising steps finished so far. */
        val lastStep: Int = 0,
    )

    /** Folds one callback ([done], [total]) into the state; [steps] = the requested step count. */
    fun next(s: State, done: Int, total: Int, steps: Int): State = when {
        total == steps && !s.sampled -> when {
            done >= steps -> s.copy(phase = Phase.DECODING, done = 0, total = 0, sampled = true, stepping = true)
            done <= 0 -> s.copy(phase = Phase.FIRST_STEP, done = 0, total = steps, stepping = true, lastStep = 0)
            else -> s.copy(phase = Phase.STEP, done = done, total = steps, stepping = true, lastStep = done)
        }
        s.sampled -> s.copy(phase = Phase.DECODING, done = done, total = total)
        done < total -> s.copy(phase = Phase.LOADING, done = done, total = total)
        // a weight load just finished: the step it was loaded for is now computing
        else -> s.copy(phase = if (s.lastStep > 0) Phase.STEP else Phase.FIRST_STEP, done = s.lastStep, total = steps)
    }

    /** 0..1 for the progress bar, null = indeterminate. */
    fun fraction(s: State, steps: Int): Float? = when (s.phase) {
        Phase.LOADING -> if (s.total > 0) s.done.toFloat() / s.total else null
        Phase.STEP -> s.done.toFloat() / steps.coerceAtLeast(1)
        Phase.FIRST_STEP, Phase.DECODING -> null
    }

    fun elapsed(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

    /** The line under the bar (Chinese; the 生图 page's other copy is localised through I18n). */
    fun label(s: State, steps: Int, elapsedSec: Long, gpu: Boolean): String {
        val t = "已用 " + elapsed(elapsedSec)
        return when (s.phase) {
            Phase.LOADING -> "加载模型权重 ${s.done}/${s.total} · $t"
            Phase.FIRST_STEP -> "第 1/$steps 步计算中（首步最慢" + (if (gpu) "，含 GPU 初始化" else "") + "）· $t"
            Phase.STEP -> "已完成 ${s.done}/$steps 步，第 ${(s.done + 1).coerceAtMost(steps)} 步计算中 · $t"
            Phase.DECODING -> "解码图片" + (if (s.total > 0) " ${s.done}/${s.total}" else "") + " · $t"
        }
    }

    /** Show 「复制生图日志」 once a run has gone this long without finishing (or after an error). */
    const val LOG_HINT_SEC = 120L
}

/** 2.4.2: the app's own recent sd.cpp / thermal log lines (logcat of this process), for 「复制生图日志」. */
object SdLog {
    fun recent(lines: Int = 400): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", lines.toString(), "-s", "IrisSd:V", "IrisSdq:V", "IrisThermal:V", "AndroidRuntime:E", "DEBUG:V")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val head = "IRIDIS ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · SoC ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE} · Android ${android.os.Build.VERSION.RELEASE}\n"
        head + out.ifBlank { "（没有读到生图日志）" }
    }.getOrElse { "读取日志失败：${it.message}" }
}

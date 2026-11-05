package com.iris.localai.engine

/**
 * 1.10.3: GPU compile-cache diagnostics and guards (user: 「可我想用GPU」 — GPU loads take hundreds of
 * seconds every time, i.e. the compile cache never seems to hit).
 *
 * What LiteRT-LM 0.18.0 does (source tag v0.18.0):
 *  - `EngineConfig.cacheDir` is passed straight to the native engine for every backend
 *    (Engine.initialize → nativeCreateEngine). For GPU, llm_executor_settings_utils.cc
 *    CreateCompilationOptions → SetGpuCacheOptions turns on BOTH `serialize_program_cache`
 *    (compiled shaders) and `serialize_external_tensors` (converted weights), with
 *    serialization dir = cacheDir and model_cache_key = `<file name>_<mtime s>_<size>`.
 *  - File names: `<cacheDir>/<file name>_<mtime s>_<size>_mldrift_program_cache.bin` and
 *    `…_mldrift_weight_cache.bin` (the 0.14 weight-cache name-order bug, LiteRT-LM #2777, is fixed
 *    in 0.18: GetWeightCacheFile and GetProgramCacheFile build the same order).
 *  - When the file for the current id is missing, the runtime deletes that model's other files of
 *    the same suffix and rebuilds. ML Drift marks a cache it is still building as stale; a process
 *    killed / frozen in the middle (minutes on the GPU) leaves it stale → the next load rebuilds.
 * There is no Kotlin-side option in 0.18 for anything else (no AdvancedSettings in EngineConfig).
 * Pure, unit-tested (V1103Test).
 */
object GpuCacheDiag {
    /** Same directory as LiteRtEngine.CACHE_DIR (filesDir/litert-cache). */
    const val CACHE_DIR = "litert-cache"
    const val PROGRAM_SUFFIX = "_mldrift_program_cache.bin"
    const val WEIGHT_SUFFIX = "_mldrift_weight_cache.bin"

    /** Leave this much room on top of the model size for the GPU weight cache (≈ model size) + slack. */
    const val SPACE_SLACK_BYTES = 512L * 1024 * 1024

    /** Marker written into the cache dir while a load runs; still there at the next load = it never finished. */
    fun loadingMarker(modelFileName: String, backend: Backend): String = ".loading-${backend.name}-$modelFileName"

    /** Which of the current id's GPU cache files exist. */
    data class GpuParts(val program: Long?, val weight: Long?) {
        val complete: Boolean get() = (program ?: 0) > 0 && (weight ?: 0) > 0
    }

    fun gpuParts(files: List<Pair<String, Long>>, modelFileName: String, lastModifiedMs: Long, sizeBytes: Long): GpuParts {
        val prefix = LiteRtCacheFiles.currentPrefix(modelFileName, lastModifiedMs, sizeBytes)
        fun size(suffix: String) = files.firstOrNull { it.first == prefix + suffix }?.second
        return GpuParts(size(PROGRAM_SUFFIX), size(WEIGHT_SUFFIX))
    }

    /**
     * IrisLoad line part: every file of this model in the cache dir with its size (sorted), plus how
     * many files of other models are there. Marker files are listed too (they start with '.').
     */
    fun listing(files: List<Pair<String, Long>>, modelFileName: String): String {
        val mine = files.filter { (n, _) -> n.startsWith(modelFileName) || n.endsWith(modelFileName) }.sortedBy { it.first }
        val others = files.size - mine.size
        val body = if (mine.isEmpty()) "(none)" else mine.joinToString(", ") { (n, b) -> "$n=${LoadTelemetry.mb(b)}" }
        return "$body; other files=$others"
    }

    /** One IrisLoad line describing the cache dir at [stage] (before / after / after close). */
    fun logLine(
        stage: String,
        dir: String,
        dirWritable: Boolean,
        files: List<Pair<String, Long>>,
        modelFileName: String,
        lastModifiedMs: Long,
        sizeBytes: Long,
    ): String {
        val p = gpuParts(files, modelFileName, lastModifiedMs, sizeBytes)
        return "cache $stage dir=$dir writable=$dirWritable key=${LiteRtCacheFiles.currentPrefix(modelFileName, lastModifiedMs, sizeBytes)} " +
            "gpuProgram=${p.program?.let(LoadTelemetry::mb) ?: "-"} gpuWeight=${p.weight?.let(LoadTelemetry::mb) ?: "-"} files=[${listing(files, modelFileName)}]"
    }

    /** True when the GPU weight cache (≈ the model's size) probably cannot be written. */
    fun lowSpace(freeBytes: Long, modelBytes: Long): Boolean = freeBytes in 0 until (modelBytes + SPACE_SLACK_BYTES)

    fun lowSpaceNotice(modelName: String, freeBytes: Long, modelBytes: Long): String =
        com.iris.localai.I18n.s(com.iris.localai.R.string.engine_fc5278, modelName, gb(freeBytes), gb(modelBytes))

    val INTERRUPTED_NOTICE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.engine_b8f9e9)

    private fun gb(b: Long) = String.format(java.util.Locale.ROOT, "%.1f GB", b / 1_073_741_824.0)
}

/**
 * 1.10.3: when the process is kept alive with a foreground service during a load. A GPU / NPU load
 * of a LiteRT-LM model compiles for minutes in one blocking native call; HyperOS / Android freeze
 * or kill a backgrounded app within seconds, which both stretches the load and leaves the runtime's
 * half-written cache stale (→ the next load compiles again).
 */
object LoadKeepAlivePolicy {
    fun keepAlive(state: EngineState): Boolean =
        state is EngineState.Loading &&
            state.model.format == com.iris.localai.data.ModelFormat.LITERT &&
            (state.backend == Backend.GPU || state.backend == Backend.NPU)
}

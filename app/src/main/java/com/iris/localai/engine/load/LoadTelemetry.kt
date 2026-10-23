package com.iris.localai.engine

import kotlinx.serialization.Serializable

/**
 * 1.10.2: real load telemetry (user: 「每次加载模型好慢，>40S」).
 *
 * LiteRT-LM 0.18 names its compiled caches after the model file (runtime/executor/
 * executor_settings_base.cc, util/file_util.cc, tag v0.18.0):
 *   `<cacheDir>/<model file name>_<mtime seconds>_<file size><suffix>`
 * with suffix `_mldrift_program_cache.bin` / `_mldrift_weight_cache.bin` (GPU, ML Drift) or
 * `.xnnpack_cache` (CPU, XNNPACK). The id is the model file's mtime (whole seconds) + size, so the
 * same file always maps to the same cache file. When the file for the current id is missing the
 * runtime deletes the other `<model file name>…<suffix>` files ("stale caches") and builds anew.
 *
 * Neither maxNumTokens nor the backend's thread count is part of the file name; maxNumTokens
 * changes the compiled GPU programs inside the program cache (KV-cache shapes), so a different
 * value recompiles them. Pure functions, unit-tested (V1102Test).
 */
object LiteRtCacheFiles {
    /** The runtime's file identifier: `<mtime in whole seconds>_<size in bytes>`. */
    fun identifier(lastModifiedMs: Long, sizeBytes: Long): String = "${Math.floorDiv(lastModifiedMs, 1000L)}_$sizeBytes"

    /** Prefix of every cache file of this exact model file (current id). */
    fun currentPrefix(modelFileName: String, lastModifiedMs: Long, sizeBytes: Long): String =
        "${modelFileName}_${identifier(lastModifiedMs, sizeBytes)}"

    /** Which backend a cache file belongs to (null = not a LiteRT-LM cache file of a known kind). */
    fun kindOf(name: String): Backend? = when {
        "mldrift" in name -> Backend.GPU
        "xnnpack" in name -> Backend.CPU
        else -> null
    }

    /**
     * Snapshot of the cache directory for one model, from (file name, size) pairs. Files of other
     * models are ignored; [backend] picks which kind counts as "this load's" cache.
     */
    fun snapshot(
        files: List<Pair<String, Long>>,
        modelFileName: String,
        lastModifiedMs: Long,
        sizeBytes: Long,
        backend: Backend,
    ): CacheSnapshot {
        val prefix = currentPrefix(modelFileName, lastModifiedMs, sizeBytes)
        val mine = files.filter { (n, _) -> n.startsWith(modelFileName) && kindOf(n) != null }
        val current = mine.filter { (n, _) -> n.startsWith(prefix) && kindOf(n) == backend }
        val stale = mine.filter { (n, _) -> !n.startsWith(prefix) }
        return CacheSnapshot(
            files = mine.size,
            bytes = mine.sumOf { it.second },
            currentFiles = current.size,
            currentBytes = current.sumOf { it.second },
            staleFiles = stale.size,
        )
    }
}

/** One look at a model's cache files. "current" = this file id and this backend's kind. */
@Serializable
data class CacheSnapshot(
    val files: Int = 0,
    val bytes: Long = 0,
    val currentFiles: Int = 0,
    val currentBytes: Long = 0,
    val staleFiles: Int = 0,
)

/** Whether a load could use a compiled cache. NONE = the engine has none (GGUF, MNN, NPU AOT). */
enum class CacheUse { HIT, MISS, NONE }

/** One finished (or failed) load, stored per model + backend. */
@Serializable
data class LoadRecord(
    val modelId: String,
    val backend: String,
    val ms: Long,
    val cache: CacheUse = CacheUse.NONE,
    val before: CacheSnapshot? = null,
    val after: CacheSnapshot? = null,
    val maxNumTokens: Int = 0,
    val ok: Boolean = true,
    val at: Long = 0,
    /** Why this load happened (启动预加载 / 选择模型 / 资源档重载 / …), for the log. */
    val reason: String = "",
)

object LoadTelemetry {
    const val TAG = "IrisLoad"

    /**
     * HIT when this backend's cache files for the current model file id were already there
     * before the load. NPU AOT files, GGUF and MNN have no LiteRT cache → NONE.
     */
    fun classify(backend: Backend, before: CacheSnapshot?): CacheUse = when {
        before == null || backend == Backend.NPU -> CacheUse.NONE
        before.currentFiles > 0 && before.currentBytes > 0 -> CacheUse.HIT
        else -> CacheUse.MISS
    }

    /** Whole seconds for the UI, at least 1. */
    fun seconds(ms: Long): Long = ((ms + 500) / 1000).coerceAtLeast(1)

    fun cacheLabel(c: CacheUse): String? = when (c) {
        CacheUse.HIT -> "已用缓存"
        CacheUse.MISS -> "首次编译"
        CacheUse.NONE -> null
    }

    /** Model sheet line: 「上次加载 12 秒 · 已用缓存」 / 「上次加载 48 秒 · 首次编译」 / 「上次加载 3 秒」. */
    fun summary(r: LoadRecord): String {
        val base = if (r.ok) "上次加载 ${seconds(r.ms)} 秒" else "上次加载失败（${seconds(r.ms)} 秒）"
        return cacheLabel(r.cache)?.let { "$base · $it" } ?: base
    }

    /** Elapsed time while a load runs: 「已用 7 秒」. */
    fun elapsed(ms: Long): String = "已用 ${(ms / 1000).coerceAtLeast(0)} 秒"

    fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1_048_576.0)

    private fun snap(s: CacheSnapshot?): String = if (s == null) "-" else
        "${s.files} files ${mb(s.bytes)} (this key ${s.currentFiles}/${mb(s.currentBytes)}, stale ${s.staleFiles})"

    /** One logcat line per load (adb logcat -s IrisLoad). */
    fun logLine(fileName: String, r: LoadRecord, freeBytes: Long? = null): String =
        "load ${if (r.ok) "OK" else "FAILED"} $fileName backend=${r.backend} ${r.ms}ms cache=${r.cache}" +
            (if (r.maxNumTokens > 0) " maxNumTokens=${r.maxNumTokens}" else "") +
            (if (r.reason.isNotEmpty()) " reason=${r.reason}" else "") +
            " before=[${snap(r.before)}] after=[${snap(r.after)}]" +
            (freeBytes?.let { " free=${mb(it)}" } ?: "")

    /** Store key: one record per model and backend. */
    fun key(modelId: String, backend: String): String = "$modelId|$backend"

    /**
     * The record the model sheet shows for [modelId]: the newest one of any backend (a load that
     * fell back to the CPU is still the model's last load).
     */
    fun latest(records: Collection<LoadRecord>, modelId: String): LoadRecord? =
        records.filter { it.modelId == modelId }.maxByOrNull { it.at }
}

/**
 * 1.10.2: the maxNumTokens a LiteRT-LM load asks for. Depends only on the model file and the
 * model's own 上下文长度 (settings / model settings) — never on the tier, agent mode, compaction or
 * the backend's threads — so the compiled GPU programs in the cache stay valid between loads.
 */
object LiteRtLoadKey {
    /** NPU graphs have a fixed KV size; never request more than they were compiled with. */
    fun maxNumTokens(contextLength: Int, npuFile: Boolean, npuKv: Int?, npuDefault: Int): Int =
        npuKv ?: if (npuFile) minOf(contextLength, npuDefault) else contextLength
}

/** 1.10.2: no needless reloads — a load of the model that is already resident is a no-op. */
object ReloadRules {
    fun alreadyLoaded(state: EngineState, modelPath: String): Boolean =
        state is EngineState.Ready && state.model.path == modelPath

    /**
     * Speed / tier changes on LiteRT-LM on the GPU or NPU: the maths runs on the accelerator and the
     * runtime takes no thread count there; only the host threads' cores / priority would change,
     * which a 40 s+ reload is not worth. They apply at the next load instead (status 「下次加载生效」).
     */
    fun tierReloadsLiteRt(activeBackend: Backend?): Boolean = activeBackend == Backend.CPU
}

/** 1.10.2: background preload of the last-used model at app start. */
enum class PreloadDecision { LOAD, DISABLED, NO_LAST_MODEL, MODEL_MISSING, ALREADY_LOADING_OR_LOADED, SKIP_AFTER_NATIVE_CRASH }

object PreloadPolicy {
    fun decide(
        enabled: Boolean,
        lastModelFile: String?,
        modelFound: Boolean,
        engineIdle: Boolean,
        /** Previous native load killed the process and has not been followed by a successful load. */
        blockedAfterNativeCrash: Boolean = false,
    ): PreloadDecision = when {
        !enabled -> PreloadDecision.DISABLED
        lastModelFile.isNullOrBlank() -> PreloadDecision.NO_LAST_MODEL
        !modelFound -> PreloadDecision.MODEL_MISSING
        !engineIdle -> PreloadDecision.ALREADY_LOADING_OR_LOADED
        blockedAfterNativeCrash -> PreloadDecision.SKIP_AFTER_NATIVE_CRASH
        else -> PreloadDecision.LOAD
    }
}

package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat

/**
 * 1.7.1: one backend abstraction for all three engines. Every engine declares which of these it
 * can run a given model on ([BackendCapabilities]); [EngineManager] picks one, loads, falls back
 * along [BackendPlanner.chain] (NPU → GPU → CPU), remembers native crashes ([BackendCrashMemory])
 * and reports the backend that is really in use ([ModelDetails.activeBackend]).
 */
enum class Backend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU");

    companion object {
        /** Fallback order, most specialised first. */
        val FALLBACK_ORDER = listOf(NPU, GPU, CPU)

        fun parse(s: String?): Backend? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** One selectable backend for one model, with the reason when it cannot be used. */
data class BackendOption(val backend: Backend, val available: Boolean, val reason: String? = null, val detail: String = "")

/** What the device offers; gathered once per process (see [BackendEnvironment.probe]). */
data class BackendEnvironment(
    val hasOpenCl: Boolean,
    /** A Qualcomm NPU this build can drive (SoC with a matching LiteRT dispatch library). */
    val npuSoc: String?,
    /** QAIRT / dispatch runtime installed (downloaded in settings). */
    val npuRuntimeInstalled: Boolean,
    /** The full flavour ships LiteRT-LM and MNN. */
    val fullFlavor: Boolean,
    /** 2.2.0: why GenieX cannot run right now (not in this build / not Snapdragon / runtime missing), null = ready. */
    val geniexBlock: String? = com.iris.localai.engine.geniex.GenieX.NOT_IN_BUILD,
    /** 2.2.0: this chip can run AI Hub QAIRT bundles through GenieX. */
    val geniexQairt: Boolean = false,
    /** 2.2.15: the built-in llama.cpp Hexagon runtime (libiris_lhtp + upstream ggml-hexagon) can run GGUF on this chip's NPU. */
    val htpReady: Boolean = false,
) {
    companion object {
        private val OPENCL_PATHS = listOf(
            "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
            "/vendor/lib64/egl/libGLES_mali.so",
        )

        /** 2.0: base id (SM8750P / SM8750-AB → sm8750), the same rule the LiteRT engine uses ([NpuIds]). */
        private fun socModel(): String? =
            if (android.os.Build.VERSION.SDK_INT >= 31) NpuIds.baseSocId(android.os.Build.SOC_MODEL) else null

        fun probe(context: Context): BackendEnvironment {
            val npu = runCatching { EngineFactory.npu(context) }.getOrNull()
            val gx = runCatching { EngineFactory.geniex(context) }.getOrNull()
            return BackendEnvironment(
                hasOpenCl = !BackendCrashMemory.openClDisabled && OPENCL_PATHS.any { java.io.File(it).exists() },
                npuSoc = npu?.takeIf { it.chipLabel != null }?.let { socModel() },
                npuRuntimeInstalled = npu?.state?.value is NpuState.Installed,
                fullFlavor = ModelFormat.LITERT in EngineFactory.supportedFormats,
                geniexBlock = com.iris.localai.engine.geniex.GenieXRules.block(gx?.state?.value),
                geniexQairt = gx?.qairtSupported == true,
                htpReady = runCatching { com.iris.localai.engine.llama.LlamaHtpRuntime.available(context) }.getOrDefault(false),
            )
        }
    }
}

/** Which features an engine supports for a model; the model settings page greys out the rest. */
data class EngineFeatures(
    val contextShift: Boolean = false,
    val flashAttention: Boolean = false,
    val kvQuant: Boolean = false,
    val mmap: Boolean = false,
    val minP: Boolean = true,
    val repeatPenalty: Boolean = true,
    val presenceFrequency: Boolean = false,
    val seed: Boolean = false,
    val batchSize: Boolean = false,
    /** Thread count can change between replies without reloading. */
    val liveThreads: Boolean = false,
    /** Context length is fixed by the model file (LiteRT NPU `_ekvNNNN`). */
    val fixedContext: Int? = null,
)

object BackendCapabilities {
    /** Matches `_sm8750` / `.sm8750.` / `-qcs8550.` in LiteRT-LM file names: AOT-compiled for one Qualcomm NPU ([NpuIds]). */
    fun npuTarget(fileName: String): String? = NpuIds.targetSoc(fileName)
    fun npuKv(fileName: String): Int? = NpuIds.kvCapacity(fileName)

    fun options(format: ModelFormat, fileName: String, env: BackendEnvironment, engine: String? = null): List<BackendOption> {
        val noCl = "本机没有可用的 OpenCL 驱动"
        return when (format) {
            // 2.1.3: no engine given (the normal case) → the backend decides the engine (NPU = GenieX).
            ModelFormat.GGUF -> if (engine == null) {
                com.iris.localai.engine.geniex.GgufEngineRule.options(env)
            } else if (engine == com.iris.localai.engine.geniex.GenieX.ENGINE_GENIEX) {
                com.iris.localai.engine.geniex.GenieXRules.ggufOptions(env)
            } else listOf(
                BackendOption(Backend.CPU, true, detail = "llama.cpp · ARM KleidiAI"),
                BackendOption(Backend.GPU, env.hasOpenCl, if (env.hasOpenCl) null else noCl, "Adreno · OpenCL（Q4_0 / Q8_0 最快）"),
                BackendOption(Backend.NPU, false, "llama.cpp 没有高通 NPU 后端；NPU 请用 _sm8750 编译的 .litertlm 模型"),
            )
            ModelFormat.LITERT -> {
                if (!env.fullFlavor) return listOf(
                    BackendOption(Backend.CPU, false, "此版本不含 LiteRT-LM"),
                    BackendOption(Backend.GPU, false, "此版本不含 LiteRT-LM"),
                    BackendOption(Backend.NPU, false, "此版本不含 LiteRT-LM"),
                )
                val target = npuTarget(fileName)
                if (target != null) {
                    // AOT-compiled for one NPU: the graph cannot run anywhere else.
                    val onlyNpu = "这是 ${target.uppercase()} NPU 专用编译的模型，只能在 NPU 上运行"
                    val npuReason = when {
                        env.npuSoc == null -> "本机芯片没有受支持的高通 NPU"
                        !NpuIds.socMatches(target, env.npuSoc) -> "模型为 ${target.uppercase()} 编译，本机是 ${env.npuSoc.uppercase()}"
                        !env.npuRuntimeInstalled -> "需要先在 设置 › 模型与加载 下载 NPU 运行库"
                        else -> null
                    }
                    listOf(
                        BackendOption(Backend.CPU, false, onlyNpu),
                        BackendOption(Backend.GPU, false, onlyNpu),
                        BackendOption(Backend.NPU, npuReason == null, npuReason, "Hexagon · QNN"),
                    )
                } else listOf(
                    BackendOption(Backend.CPU, true, detail = "XNNPACK"),
                    // 1.10.3: an explicit GPU pick is still honoured, but say what it costs.
                    BackendOption(Backend.GPU, true, detail = "Adreno · ML Drift · $LITERT_GPU_WARNING"),
                    BackendOption(Backend.NPU, false, "通用 .litertlm 不能上 NPU；需要带 _${env.npuSoc ?: "sm8750"} 的 NPU 编译版"),
                )
            }
            ModelFormat.MNN -> {
                if (!env.fullFlavor) return listOf(
                    BackendOption(Backend.CPU, false, "此版本不含 MNN"),
                    BackendOption(Backend.GPU, false, "此版本不含 MNN"),
                    BackendOption(Backend.NPU, false, "此版本不含 MNN"),
                )
                listOf(
                    BackendOption(Backend.CPU, true, detail = "ARMv8.2 FP16 · KleidiAI"),
                    BackendOption(Backend.GPU, env.hasOpenCl, if (env.hasOpenCl) null else noCl, "OpenCL"),
                    BackendOption(Backend.NPU, false, "未编入 MNN 的 QNN 后端（需按芯片离线转换的专用模型）"),
                )
            }
            ModelFormat.QAIRT -> com.iris.localai.engine.geniex.GenieXRules.qairtOptions(env)
        }
    }

    fun features(format: ModelFormat, fileName: String, backend: Backend? = null, engine: String? = null): EngineFeatures = when (format) {
        ModelFormat.GGUF -> if (engine == com.iris.localai.engine.geniex.GenieX.ENGINE_GENIEX) com.iris.localai.engine.geniex.GenieXRules.GGUF_FEATURES else EngineFeatures(
            contextShift = true, flashAttention = true, kvQuant = true, mmap = true,
            presenceFrequency = true, seed = true, batchSize = true, liveThreads = true,
        )
        ModelFormat.LITERT -> EngineFeatures(
            minP = false, repeatPenalty = false,
            // 2.0: compiled KV size, else the family default (Gemma 4 NPU 4096, unknown 1024).
            fixedContext = if (npuTarget(fileName) != null) NpuIds.historyBudget(fileName) else null,
        )
        ModelFormat.MNN -> EngineFeatures(mmap = true)
        ModelFormat.QAIRT -> com.iris.localai.engine.geniex.GenieXRules.QAIRT_FEATURES
    }

    /** Reason context shift is unavailable for this engine, or null when it is supported. */
    fun contextShiftUnsupported(format: ModelFormat?): String? = when (format) {
        ModelFormat.GGUF, null -> null
        ModelFormat.LITERT -> "LiteRT-LM 的会话 KV 由运行时管理，不支持上下文平移；将按 token 预算截断"
        ModelFormat.MNN -> "MNN 的 prompt cache 不支持位置平移；将按 token 预算截断"
        ModelFormat.QAIRT -> "QAIRT 包的 KV 缓存在编译时固定，不支持上下文平移；将按 token 预算截断"
    }

    /**
     * The backend "自动" picks: NPU-only files → NPU; LiteRT → GPU (Qwen3.5 / Agents-A1 → CPU, its GPU path
     * needs fp32 activations and minutes of init); GGUF / MNN → CPU (decode is bandwidth-bound and
     * the CPU path is the most robust). [allowGpu] = false keeps AUTO off the GPU (≤ 1.7.7 the 省电
     * tier did that; 1.7.8 never passes false any more — the tier only changes CPU resources).
     */
    fun autoBackend(format: ModelFormat, fileName: String, options: List<BackendOption>, allowGpu: Boolean = true): Backend {
        val avail = options.filter { it.available }.map { it.backend }.toSet()
        val wanted = when {
            // 2.1.3: GGUF 自动 stays on the CPU even when GenieX makes the NPU available (NPU is an explicit pick).
            Backend.NPU in avail && format != ModelFormat.GGUF -> Backend.NPU
            format == ModelFormat.LITERT && allowGpu && !prefersCpu(fileName) && Backend.GPU in avail -> Backend.GPU
            else -> Backend.CPU
        }
        return if (wanted in avail) wanted else options.firstOrNull { it.available }?.backend ?: Backend.CPU
    }

    /**
     * 1.7.8: the backend a load starts with. The user's explicit choice (model setting or the
     * engine's default backend) always wins and is independent of the resource tier; only 自动
     * ([explicit] == null) applies [autoBackend]'s fixed per-format policy.
     */
    fun preferred(explicit: Backend?, format: ModelFormat, fileName: String, options: List<BackendOption>): Backend =
        explicit ?: autoBackend(format, fileName, options, allowGpu = true)

    /** 1.10.3: shown on the LiteRT GPU option (model settings / default backend). */
    const val LITERT_GPU_WARNING = "首次加载需编译，可能要几分钟"

    /**
     * LiteRT models 自动 keeps on the CPU. Qwen3.5 (≤ 1.8.0) and, 1.9.0, Agents-A1 (a Qwen3.5-based
     * tool-calling fine-tune, e.g. `Agents-A1-4B_mixed_int4.litertlm`): on the GPU their first load
     * sat on 「加载中」 for minutes (shader compile + GPU cache, one blocking native call).
     */
    fun prefersCpu(fileName: String): Boolean {
        val n = fileName.lowercase()
        return CPU_FIRST.any { it in n }
    }

    /** Lower-case file-name fragments of [prefersCpu]. */
    val CPU_FIRST = listOf("qwen3.5", "qwen3_5", "qwen35", "agents-a1", "agents_a1")

    /** History budget of an NPU file of an unknown family without `_ekvNNNN` (Gemma 4: [NpuIds.GEMMA4_NPU_KV]). */
    const val NPU_DEFAULT_CONTEXT = NpuIds.UNKNOWN_NPU_KV
}

object BackendPlanner {
    /**
     * Load order for a model: the chosen backend first, then every *available* backend below it in
     * NPU → GPU → CPU order, skipping backends that crashed the process for this model before.
     * An explicitly unavailable choice falls straight down the chain.
     */
    fun chain(preferred: Backend, options: List<BackendOption>, crashed: Set<Backend> = emptySet()): List<Backend> {
        val avail = options.filter { it.available }.map { it.backend }.toSet()
        val start = Backend.FALLBACK_ORDER.indexOf(preferred)
        val out = Backend.FALLBACK_ORDER.drop(start).filter { it in avail && it !in crashed }
        // A crash on every option: still try the most robust one rather than refusing to load.
        return out.ifEmpty { Backend.FALLBACK_ORDER.drop(start).filter { it in avail }.takeLast(1) }
    }
}

/**
 * Remembers that loading / first decode on a backend killed the process (an uncatchable native
 * abort). Armed before the risky step, disarmed after it; a mark still armed at the next start is
 * a crash. Replaces the per-engine NpuCrashGuard / MnnGpuGuard.
 */
class BackendCrashMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iris_backend_crash", Context.MODE_PRIVATE)

    init {
        // A mark left armed means the process died during that step. Only a real crash counts:
        // being swiped away or killed in the background while armed is not the backend's fault.
        prefs.getString(KEY_ARMED, null)?.let { armed ->
            val e = prefs.edit().remove(KEY_ARMED)
            if (lastExitWasCrash(context)) {
                // 2.2.13: any crash during a load turns on crash-safe mode for the next load (CPU only).
                e.putBoolean(KEY_SAFE_MODE, true)
                // A CPU load that still crashed: the OpenCL registry probe (runs for every load) is the
                // prime suspect on some Adreno drivers, so keep the vendor OpenCL driver out from now on.
                if (armed.startsWith(Backend.CPU.name + "|")) e.putBoolean(KEY_NO_OPENCL, true)
                else e.putBoolean(BAD_PREFIX + armed, true)
            }
            e.commit()
        }
        openClDisabled = prefs.getBoolean(KEY_NO_OPENCL, false)
        if (openClDisabled) runCatching { android.system.Os.setenv("IRIS_NO_OPENCL", "1", true) }
    }

    /** 2.2.13: true once after a load crashed the app; that next load runs on CPU only. */
    fun takeSafeMode(): Boolean {
        if (!prefs.getBoolean(KEY_SAFE_MODE, false)) return false
        prefs.edit().remove(KEY_SAFE_MODE).commit()
        return true
    }

    private fun lastExitWasCrash(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return true
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return true
            last.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                last.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                last.reason == android.app.ApplicationExitInfo.REASON_SIGNALED ||
                last.reason == android.app.ApplicationExitInfo.REASON_ANR
        }.getOrDefault(true)
    }

    fun arm(modelId: String, backend: Backend) {
        prefs.edit().putString(KEY_ARMED, key(modelId, backend)).commit()
    }

    fun disarm() { prefs.edit().remove(KEY_ARMED).commit() }

    fun crashed(modelId: String): Set<Backend> =
        Backend.entries.filter { prefs.getBoolean(BAD_PREFIX + key(modelId, it), false) }.toSet()

    /** 2.2.14: an OpenCL (llama.cpp GPU) load hung on this device; GGUF defaults to CPU until retried. */
    val openClHung: Boolean get() = prefs.getBoolean(KEY_CL_HUNG, false)
    val openClHangDetail: String? get() = prefs.getString(KEY_CL_HANG_DETAIL, null)

    /** Written before the process restarts: remember the hang and which model to reload on CPU. */
    fun markOpenClHung(detail: String, reloadFile: String) {
        prefs.edit().remove(KEY_ARMED).putBoolean(KEY_CL_HUNG, true).putString(KEY_CL_HANG_DETAIL, detail)
            .putString(KEY_PENDING_CPU, reloadFile).commit()
    }

    /** The model to reload on CPU after a GPU hang restart (read once). */
    fun takePendingCpuReload(): String? {
        val f = prefs.getString(KEY_PENDING_CPU, null) ?: return null
        prefs.edit().remove(KEY_PENDING_CPU).commit()
        return f
    }

    fun forget(modelId: String) {
        val e = prefs.edit().remove(KEY_NO_OPENCL).remove(KEY_CL_HUNG).remove(KEY_CL_HANG_DETAIL)
        Backend.entries.forEach { e.remove(BAD_PREFIX + key(modelId, it)) }
        e.apply()
    }

    private fun key(modelId: String, b: Backend) = "${b.name}|$modelId"

    companion object {
        /** 2.2.13: OpenCL kept off for this process after a CPU load crashed (see init). */
        @Volatile var openClDisabled = false
            private set
        private const val KEY_ARMED = "armed"
        private const val KEY_SAFE_MODE = "safe_mode"
        private const val KEY_NO_OPENCL = "no_opencl"
        private const val KEY_CL_HUNG = "opencl_hung"
        private const val KEY_CL_HANG_DETAIL = "opencl_hang_detail"
        private const val KEY_PENDING_CPU = "pending_cpu_reload"
        private const val BAD_PREFIX = "bad:"
    }
}

/** Helper for the UI: the options of a model, plus whether a backend crashed before. */
fun LocalModel.backendOptions(env: BackendEnvironment, engine: String? = null): List<BackendOption> =
    BackendCapabilities.options(format, fileName, env, engine)

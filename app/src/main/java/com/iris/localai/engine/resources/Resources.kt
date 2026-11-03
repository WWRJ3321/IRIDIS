package com.iris.localai.engine

/**
 * 1.7.1: speed is no longer *chosen* (no tok/s target, no pacer sleeping between tokens). The user
 * picks how much of the phone inference may use; the model then runs as fast as those resources
 * allow. A tier sets the thread count, which cores the threads may run on, their scheduling
 * priority, the prefill batch size, MNN's power hint, and whether "自动" may put a model on the GPU.
 */
enum class ResourceTier(val label: String, val short: String, val hint: String) {
    LOW(com.iris.localai.I18n.s(com.iris.localai.R.string.glass_95f6bb), com.iris.localai.I18n.s(com.iris.localai.R.string.glass_95f6bb), com.iris.localai.I18n.s(com.iris.localai.R.string.engine_58b88a)),
    MEDIUM(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_6e6d30), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_aed1df), com.iris.localai.I18n.s(com.iris.localai.R.string.engine_a48137)),
    HIGH(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d4e66e), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4296d7), com.iris.localai.I18n.s(com.iris.localai.R.string.engine_478e37));
    // 1.7.8: 「全部」was removed (user request). A stored ordinal 3 is migrated to HIGH
    // (AppSettings.migrated / sanitized); [of] maps anything out of range to DEFAULT = HIGH too.

    companion object {
        val DEFAULT = HIGH
        fun of(i: Int): ResourceTier = entries.getOrElse(i) { DEFAULT }
        /** 1.7.8: ordinal the removed 「全部」 tier had in ≤ 1.7.7 settings. */
        const val REMOVED_ALL_ORDINAL = 3
    }
}

/**
 * 1.7.8: which set of tier profiles applies. On the GPU (and NPU) the heavy maths runs off the CPU;
 * the tier only places the *host* threads (kernel submission, sampling, tokenizer, the driver's
 * own threads created at load), so it gets its own profile set with different defaults.
 */
enum class ResourceBackendKind(val label: String) {
    CPU(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_d30939)), GPU(com.iris.localai.I18n.s(com.iris.localai.R.string.engine_b80954));

    companion object {
        /** null (not loaded yet) = CPU, the profile set a load starts from for CPU-default engines. */
        fun of(backend: Backend?): ResourceBackendKind = if (backend == null || backend == Backend.CPU) CPU else GPU
    }
}

/** CPU clusters, slowest first, each a list of core ids. Read from cpufreq. */
data class CpuTopology(val clusters: List<List<Int>>) {
    val all: List<Int> get() = clusters.flatten().sorted()
    val count: Int get() = all.size

    companion object {
        /** Groups cores by max frequency. [maxFreqKHz] index = core id; null = unreadable. */
        fun fromFrequencies(maxFreqKHz: List<Long?>): CpuTopology {
            val known = maxFreqKHz.withIndex().filter { it.value != null }
            if (known.isEmpty()) return CpuTopology(listOf(maxFreqKHz.indices.toList()))
            val groups = known.groupBy { it.value!! }.toSortedMap().values.map { g -> g.map { it.index } }
            return CpuTopology(groups)
        }

        fun read(): CpuTopology {
            val n = Runtime.getRuntime().availableProcessors()
            return fromFrequencies((0 until n).map { cpu ->
                runCatching {
                    java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull()
            })
        }
    }
}

/** Everything a tier resolves to on this device. [cpus] empty = no pinning. */
data class ResourcePlan(
    val tier: ResourceTier,
    val threads: Int,
    val cpus: List<Int>,
    /** Linux nice value for the inference threads (-8 … 10). */
    val nice: Int,
    /** llama.cpp prefill batch (logical); also bounds the physical micro-batch. */
    val batch: Int,
    /** May "自动" choose the GPU? Explicit per-model choices always win. */
    val allowGpu: Boolean,
    val allowNpu: Boolean,
    /** MNN backend power hint: low / normal / high. */
    val mnnPower: String,
    /** Short description of the cores for the UI ("能效核 ×6"). */
    val coreLabel: String,
    /**
     * Cores a live down-switch may add when more threads run than the target has cores
     * ([LiveResourcePolicy.coresFor]). 1.7.6: every core outside the prime cluster. 1.7.8: the
     * union of the user's configured 省电 + 中 cores for this backend kind (default 8 Elite: cpu0-5),
     * so cores the user only gave to 高 are never borrowed by a lower tier.
     * Empty = unknown (then the target cores are kept as they are).
     */
    val nonPrimeCpus: List<Int> = emptyList(),
    /** 1.7.8: the profile set this plan came from (CPU, or GPU/NPU host threads). */
    val kind: ResourceBackendKind = ResourceBackendKind.CPU,
    /**
     * 1.7.9: GPU 性能提示 of this tier (GPU set; default 省电 low / 中 normal / 高 high). Read by MNN on
     * OpenCL at load (Adreno cl_qcom_perf_hint); LiteRT-LM has no such option.
     */
    val gpuHint: com.iris.localai.data.GpuPerfHint = TierProfiles.defaultGpuHint(tier),
)

object ResourcePlanner {
    /**
     * [thermal] = PowerManager.THERMAL_STATUS_* (0 none … 6 shutdown). [userThreads] > 0 (per-model
     * or manual) replaces the tier's thread count but keeps its cores / priority; the core set is
     * widened when it has fewer cores than threads.
     *
     * 1.7.8: threads / cores / nice come from the user's 资源档配置 ([profile], already resolved and
     * validated by [TierProfiles.resolve]); null = this phone's default for [kind]. [widenPool] = the
     * cores a live down-switch may add when the target has fewer cores than running threads
     * (default: the 省电 + 中 cores of the same backend kind, see [TierProfiles.widenPool]).
     */
    fun plan(
        tier: ResourceTier,
        topo: CpuTopology,
        thermal: Int = 0,
        userThreads: Int = 0,
        kind: ResourceBackendKind = ResourceBackendKind.CPU,
        profile: com.iris.localai.data.TierProfile? = null,
        widenPool: List<Int>? = null,
    ): ResourcePlan {
        val all = TierProfiles.allCores(topo)
        val p = profile ?: TierProfiles.defaults(kind, tier, topo)
        var cores = p.cpus.filter { it in all }.distinct().sorted().ifEmpty { all }
        var threads = p.threads.coerceIn(1, 16)
        val nice = p.nice.coerceIn(TierProfiles.NICE_MIN, TierProfiles.NICE_MAX)
        val batch = when (tier) { ResourceTier.LOW -> 128; ResourceTier.MEDIUM -> 256; ResourceTier.HIGH -> 512 }
        if (userThreads > 0) {
            threads = userThreads.coerceIn(1, 16)
            if (threads > cores.size) cores = all
        }
        threads = throttle(tier, threads, thermal)
        // Pinning to every core is the same as not pinning.
        val pin = if (cores.size >= all.size) emptyList() else cores
        val pool = (widenPool ?: TierProfiles.widenPool(kind, topo, emptyMap())).filter { it in all }.sorted()
        return ResourcePlan(
            tier = tier,
            threads = threads,
            cpus = pin,
            nice = nice,
            batch = batch,
            // 1.7.8: the tier never changes the backend (user rule: 档位只调整 CPU 资源).
            allowGpu = true,
            allowNpu = true,
            mnnPower = when (tier) { ResourceTier.LOW -> "low"; ResourceTier.MEDIUM -> "normal"; ResourceTier.HIGH -> "high" },
            coreLabel = TierProfiles.coreLabel(cores, topo),
            nonPrimeCpus = pool,
            kind = kind,
            gpuHint = p.gpuHint ?: TierProfiles.defaultGpuHint(tier),
        )
    }

    /** Thermal step-down: 省电 backs off at the first sign of heat, the others when it gets warm. */
    fun throttle(tier: ResourceTier, threads: Int, thermal: Int): Int = when (tier) {
        ResourceTier.LOW -> if (thermal >= THERMAL_LIGHT) (threads - 1).coerceAtLeast(1) else threads
        else -> when {
            thermal >= THERMAL_SEVERE -> (threads / 2).coerceAtLeast(2)
            thermal >= THERMAL_MODERATE -> (threads - 1).coerceAtLeast(2)
            else -> threads
        }
    }.coerceAtMost(threads)

    /**
     * Old settings (≤ 1.7.0): powerMode 0 省电 / 1 均衡 / 2 性能 and a composer speed target.
     * 省电, or a target of ≤ 6 字/秒 (极省电 / 省电 levels), becomes 低; 性能 or 全速 became 全部,
     * which 1.7.8 removed → 高.
     */
    fun migrateLegacy(powerMode: Int, targetTps: Int): ResourceTier = when {
        powerMode == 0 -> ResourceTier.LOW
        targetTps in 1..6 -> ResourceTier.LOW
        targetTps in 7..10 -> ResourceTier.MEDIUM
        else -> ResourceTier.HIGH
    }

    // android.os.PowerManager.THERMAL_STATUS_* (kept here so the planner stays a pure function).
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
}

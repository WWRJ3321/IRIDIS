package com.iris.localai.engine

import com.iris.localai.data.GpuPerfHint
import com.iris.localai.data.TierProfile

/**
 * 1.7.8: 资源档配置 — per backend kind (CPU / GPU), per tier: threads, cores, nice.
 *
 * Pure functions (unit-tested in V178Test). Defaults are generated from the phone's core clusters:
 *
 * CPU set (unchanged from 1.7.6; two-cluster chips such as the Snapdragon 8 Elite, 6 performance
 * cores cpu0-5 + 2 prime cores cpu6-7):
 *   省电 1 thread · cpu5 · nice 10   中 2 threads · cpu0-5 · nice 0   高 2 threads · cpu6-7 · nice -4
 *   Other chips keep the pre-1.7.8 topology rules (slow cluster / non-prime cores / performance cores).
 *
 * GPU set (host threads only — the maths runs on the GPU):
 *   省电 1 · non-prime cores · nice 5   中 2 · non-prime cores · nice 0   高 2 · every core (no pinning) · nice -4
 * Why (user's on-device numbers, 2026-10-13, both models on the GPU):
 *   - LiteRT-LM (Gemma 4 E2B): only 「全部」 was faster, and it was the only tier with *no pinning*.
 *     LiteRtEngine pins the loading thread, and every thread LiteRT-LM creates during
 *     Engine.initialize() (its decode loop / session thread, the GPU driver's submission threads,
 *     the XNNPACK pool used for the CPU-side parts such as sampling and per-layer embeddings)
 *     inherits that mask and nice. 省电 (1 core) / 中 / 高 (2 cores each) squeezed all of them onto
 *     ≤ 2 cores, so the three looked the same. 高 on the GPU is therefore unpinned, like 「全部」.
 *   - nice -4, not 「全部」's -8: the gain came from the cores; -8 sits right next to the UI thread /
 *     RenderThread (nice -10) and makes scrolling the streaming reply compete with host threads.
 *   - 省电/中 stay off the prime cores (that is what makes them save power) but get a whole cluster,
 *     not one core, so the driver threads are not serialised behind the host thread.
 *   - MNN on the GPU: MNN reads thread_num as the OpenCL *mode bits*, not a thread count (fixed in
 *     MnnEngine 1.7.8), so the thread count here only matters as "1–2 host threads" and stays small.
 */
object TierProfiles {
    const val NICE_MIN = -8
    const val NICE_MAX = 19

    fun key(kind: ResourceBackendKind, tier: ResourceTier): String = "${kind.name}.${tier.name}"

    fun allCores(topo: CpuTopology): List<Int> =
        topo.clusters.filter { it.isNotEmpty() }.flatten().distinct().sorted().ifEmpty { listOf(0) }

    private fun clusters(topo: CpuTopology): List<List<Int>> =
        topo.clusters.filter { it.isNotEmpty() }.map { it.sorted() }.ifEmpty { listOf(listOf(0)) }

    /** Every core outside the fastest cluster (all cores on a one-cluster chip). */
    fun nonPrime(topo: CpuTopology): List<Int> {
        val cl = clusters(topo)
        val all = cl.flatten().sorted()
        return if (cl.size >= 2) (all - cl.last().toSet()).sorted() else all
    }

    /** 1.7.9: GPU 性能提示 default per tier — 省电 → low, 中 → normal, 高 → high. */
    fun defaultGpuHint(tier: ResourceTier): GpuPerfHint = when (tier) {
        ResourceTier.LOW -> GpuPerfHint.LOW
        ResourceTier.MEDIUM -> GpuPerfHint.NORMAL
        ResourceTier.HIGH -> GpuPerfHint.HIGH
    }

    /**
     * 1.7.9 migration: GPU entries saved by 1.7.8 have no gpuHint → the tier's default; CPU entries
     * never carry one (the CPU set has no GPU).
     */
    fun withHintDefault(kind: ResourceBackendKind, tier: ResourceTier, p: TierProfile): TierProfile =
        if (kind == ResourceBackendKind.GPU) (if (p.gpuHint == null) p.copy(gpuHint = defaultGpuHint(tier)) else p)
        else if (p.gpuHint != null) p.copy(gpuHint = null) else p

    fun defaults(kind: ResourceBackendKind, tier: ResourceTier, topo: CpuTopology): TierProfile {
        val cl = clusters(topo)
        val all = cl.flatten().sorted()
        val slowest = cl.first()
        val fastest = cl.last()
        if (kind == ResourceBackendKind.GPU) {
            val np = nonPrime(topo)
            return when (tier) {
                ResourceTier.LOW -> TierProfile(1, np, 5, defaultGpuHint(tier))
                ResourceTier.MEDIUM -> TierProfile(minOf(2, np.size), np, 0, defaultGpuHint(tier))
                ResourceTier.HIGH -> TierProfile(minOf(2, all.size), all, -4, defaultGpuHint(tier))
            }
        }
        if (cl.size == 2) {
            // 1.7.6 values for 省电/中 (2.0.3 widened 高, see below).
            return when (tier) {
                // The last core of the slower cluster: cpu0 takes most interrupts on Android.
                ResourceTier.LOW -> TierProfile(1, listOf(slowest.max()), 10)
                ResourceTier.MEDIUM -> TierProfile(minOf(2, slowest.size), slowest, 0)
                // 2.0.3: 2 prime cores cannot saturate LPDDR5X on a 9B model (7.4 tok/s on 8 Elite).
                // 高 = the prime cores plus the fastest performance cores, cores − 2 threads (max 6),
                // which measured clearly faster on device (8 Elite: 6 threads on cpu2-7).
                ResourceTier.HIGH -> {
                    val n = (all.size - 2).coerceIn(2, 6).coerceAtMost(all.size)
                    val cores = (fastest + slowest.sortedDescending()).take(maxOf(n, fastest.size)).sorted()
                    TierProfile(minOf(n, cores.size), cores, -4)
                }
            }
        }
        val perf = if (cl.size >= 3) cl.drop(1).flatten().sorted() else all
        return when (tier) {
            ResourceTier.LOW -> {
                val cores = if (cl.size >= 2) slowest else all
                TierProfile(minOf(2, cores.size), cores, 10)
            }
            ResourceTier.MEDIUM -> {
                val cores = if (cl.size >= 2) (all - fastest.toSet()).sorted() else all
                TierProfile((cores.size / 2).coerceIn(2, 4).coerceAtMost(cores.size), cores, 0)
            }
            ResourceTier.HIGH -> TierProfile((perf.size - 2).coerceIn(2, 6).coerceAtMost(perf.size), perf, -4)
        }
    }

    /** Problems that keep [p] from being saved on this phone (empty = valid). */
    fun validate(p: TierProfile, topo: CpuTopology): List<String> {
        val out = ArrayList<String>(3)
        val all = allCores(topo).toSet()
        val cpus = p.cpus.distinct()
        if (cpus.isEmpty()) out += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_06408f)
        val missing = cpus.filter { it !in all }
        if (missing.isNotEmpty()) out += "本机没有 ${missing.joinToString("、") { "cpu$it" }}"
        if (cpus.isNotEmpty() && p.threads !in 1..cpus.size) out += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_b25e22, cpus.size)
        if (cpus.isEmpty() && p.threads < 1) out += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_169dce)
        if (p.nice !in NICE_MIN..NICE_MAX) out += com.iris.localai.I18n.s(com.iris.localai.R.string.engine_cf98c5, NICE_MIN, NICE_MAX)
        return out
    }

    /** The user's profile when it is valid on this phone, otherwise the generated default. */
    fun resolve(stored: Map<String, TierProfile>, kind: ResourceBackendKind, tier: ResourceTier, topo: CpuTopology): TierProfile {
        val s = stored[key(kind, tier)]?.let { withHintDefault(kind, tier, it.copy(cpus = it.cpus.distinct().sorted())) }
        return if (s != null && validate(s, topo).isEmpty()) s else defaults(kind, tier, topo)
    }

    fun isCustom(stored: Map<String, TierProfile>, kind: ResourceBackendKind, tier: ResourceTier, topo: CpuTopology): Boolean =
        resolve(stored, kind, tier, topo) != defaults(kind, tier, topo)

    /**
     * Cores a live down-switch may add (LiveResourcePolicy.coresFor): what the user gave 省电 and 中
     * on this backend kind. Cores only 高 has (by default the prime cores) are never borrowed.
     */
    fun widenPool(kind: ResourceBackendKind, topo: CpuTopology, stored: Map<String, TierProfile>): List<Int> =
        (resolve(stored, kind, ResourceTier.LOW, topo).cpus + resolve(stored, kind, ResourceTier.MEDIUM, topo).cpus)
            .distinct().sorted()

    /**
     * Storage-side clamp (device-independent): drops unknown keys and structurally broken entries.
     * Whether the cores exist on *this* phone is checked by [resolve] (a restored backup from another
     * phone falls back to the default instead of pinning to cores that are not there).
     */
    fun sanitize(map: Map<String, TierProfile>): Map<String, TierProfile> {
        val keys = ResourceBackendKind.entries.flatMap { k -> ResourceTier.entries.map { key(k, it) } }.toSet()
        return map.filter { (k, p) ->
            val cpus = p.cpus.distinct()
            k in keys && cpus.isNotEmpty() && cpus.all { it in 0..255 } && p.threads in 1..cpus.size && p.nice in NICE_MIN..NICE_MAX
        }.mapValues { (k, p) ->
            val (kind, tier) = k.split('.').let { ResourceBackendKind.valueOf(it[0]) to ResourceTier.valueOf(it[1]) }
            withHintDefault(kind, tier, p.copy(cpus = p.cpus.distinct().sorted()))
        }
    }

    /** Clusters for the UI's quick picks, slowest first, with a name ("性能核 cpu0-5"). */
    fun clusterChoices(topo: CpuTopology): List<Pair<String, List<Int>>> {
        val cl = clusters(topo)
        return cl.mapIndexed { i, c -> clusterName(i, cl.size) to c }
    }

    private fun clusterName(index: Int, count: Int): String = when {
        count == 1 -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9ee927)
        index == count - 1 -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_608e08)
        index == 0 && count >= 3 -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_25c14e)
        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_eef149)
    }

    /** "超大核 ×2", "性能核 ×1", "全部核心 ×8", "核心 ×3". */
    fun coreLabel(cores: List<Int>, topo: CpuTopology): String {
        val cl = clusters(topo)
        val all = cl.flatten().toSet()
        val set = cores.toSet()
        val name = when {
            set == all || set.isEmpty() -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9ee927)
            else -> {
                val idx = cl.indexOfFirst { it.containsAll(set) }
                when {
                    idx >= 0 -> clusterName(idx, cl.size)
                    cl.size >= 3 && set == (all - cl.last().toSet()) -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_695c23)
                    cl.size >= 3 && set == (all - cl.first().toSet()) -> com.iris.localai.I18n.s(com.iris.localai.R.string.engine_eef149)
                    else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_976a3e)
                }
            }
        }
        return "$name ×${if (set.isEmpty()) all.size else set.size}"
    }

    /** "cpu0-5", "cpu5", "cpu0-2,6" for compact display. */
    fun cpuRange(cpus: List<Int>): String {
        if (cpus.isEmpty()) return com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a8b0c2)
        val s = cpus.distinct().sorted()
        val parts = ArrayList<String>()
        var a = s[0]; var b = s[0]
        for (c in s.drop(1)) { if (c == b + 1) b = c else { parts += if (a == b) "$a" else "$a-$b"; a = c; b = c } }
        parts += if (a == b) "$a" else "$a-$b"
        return "cpu" + parts.joinToString(",")
    }
    /** 1.7.8: the one write path for a profile (validation + "equal to default = no entry"). */
    fun store(
        settings: com.iris.localai.data.SettingsRepository,
        kind: ResourceBackendKind,
        tier: ResourceTier,
        p: TierProfile,
        topo: CpuTopology,
    ): List<String> {
        val clean = withHintDefault(kind, tier, p.copy(cpus = p.cpus.distinct().sorted()))
        val problems = validate(clean, topo)
        if (problems.isNotEmpty()) return problems
        settings.update { it.copy(tierProfiles = withProfile(it.tierProfiles, kind, tier, clean, topo)) }
        return emptyList()
    }

    /** Pure part of [store]: the map after saving [p] (caller has validated it). */
    fun withProfile(
        map: Map<String, TierProfile>, kind: ResourceBackendKind, tier: ResourceTier, p: TierProfile, topo: CpuTopology,
    ): Map<String, TierProfile> {
        val k = key(kind, tier)
        val clean = withHintDefault(kind, tier, p.copy(cpus = p.cpus.distinct().sorted()))
        return if (clean == defaults(kind, tier, topo)) map - k else map + (k to clean)
    }
}

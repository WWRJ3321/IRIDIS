package com.iris.localai.engine.resources

import com.iris.localai.data.TierProfile
import com.iris.localai.engine.*
import com.iris.localai.engine.load.*
import android.content.Context
import android.os.PowerManager
import com.iris.localai.engine.llama.LlamaNative

/**
 * Resolves the user's resource tier ([ResourceTier]) into a concrete [ResourcePlan] for this
 * device, including thermal step-down. Token generation on phones is memory-bandwidth bound: past
 * ~6 threads extra cores add heat and power draw without adding speed. 1.7.8: the user's own
 * per-backend profiles (资源档配置) decide threads / cores / nice.
 */
class ThreadPolicy(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    val topology: CpuTopology by lazy { CpuTopology.read() }

    /** Cores whose max frequency is within 75% of the fastest core (kept for the speed test). */
    val performanceCores: Int by lazy {
        val top = topology.clusters.lastOrNull()?.size ?: 1
        topology.count.takeIf { it > 0 }?.let { n ->
            if (topology.clusters.size >= 3) n - topology.clusters.first().size else n
        } ?: top
    }

    val thermalStatus: Int get() = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    /** the user's 资源档配置 (AppSettings.tierProfiles), read live (EngineManager wires it to the settings). */
    @Volatile var profileSource: () -> Map<String, TierProfile> = { emptyMap() }
    val profiles: Map<String, TierProfile> get() = profileSource()

    /**
     * The plan right now (after thermal throttling). [userThreads] > 0 overrides the count.
     * [backend] picks the CPU or the GPU profile set (null = CPU).
     */
    fun plan(tier: ResourceTier, userThreads: Int = 0, backend: Backend? = null): ResourcePlan =
        planFor(tier, userThreads, ResourceBackendKind.of(backend), thermalStatus)

    /** The plan without thermal throttling (what the settings page shows). */
    fun nominal(tier: ResourceTier, userThreads: Int = 0, backend: Backend? = null): ResourcePlan =
        planFor(tier, userThreads, ResourceBackendKind.of(backend), 0)

    fun nominal(tier: ResourceTier, kind: ResourceBackendKind): ResourcePlan = planFor(tier, 0, kind, 0)

    private fun planFor(tier: ResourceTier, userThreads: Int, kind: ResourceBackendKind, thermal: Int): ResourcePlan {
        val stored = profiles
        return ResourcePlanner.plan(
            tier, topology, thermal, userThreads, kind,
            profile = TierProfiles.resolve(stored, kind, tier, topology),
            widenPool = TierProfiles.widenPool(kind, topology, stored),
        )
    }

    val isPowerSaveMode: Boolean get() = power?.isPowerSaveMode == true
}

/**
 * Applies a plan's core set and priority to the calling thread. Native worker threads created
 * afterwards (ggml's per-graph pool, XNNPACK, MNN's pool) inherit both, so engines call this on
 * their own native thread before loading and before each generation.
 */
object NativeThreadPolicy {
    fun apply(plan: ResourcePlan?) {
        plan ?: return
        runCatching { android.os.Process.setThreadPriority(plan.nice) }
        if (LlamaNative.unavailableReason == null) {
            runCatching { LlamaNative.nativeSetThreadPolicy(plan.cpus.toIntArray(), plan.nice) }
        }
    }
}

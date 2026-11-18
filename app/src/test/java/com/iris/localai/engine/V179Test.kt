package com.iris.localai.engine

import com.iris.localai.data.AppSettings
import com.iris.localai.data.GpuPerfHint
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.TierProfile
import com.iris.localai.engine.Backend
import com.iris.localai.engine.resources.CpuTopology
import com.iris.localai.engine.resources.ResourceBackendKind
import com.iris.localai.engine.resources.ResourceLog
import com.iris.localai.engine.resources.ResourcePlanner
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.engine.StopReason
import com.iris.localai.engine.resources.TierProfiles
import com.iris.localai.engine.resources.TierReloadPolicy
import com.iris.localai.engine.mnn.MnnThreadArg
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.9: GPU 性能提示 per tier (MNN OpenCL → Adreno cl_qcom_perf_hint; LiteRT has no such option). */
class V179Test {
    private val elite = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val gpu = ResourceBackendKind.GPU
    private val cpu = ResourceBackendKind.CPU

    private fun plan(t: ResourceTier, kind: ResourceBackendKind = gpu, stored: Map<String, TierProfile> = emptyMap()) =
        ResourcePlanner.plan(t, elite, 0, 0, kind, TierProfiles.resolve(stored, kind, t, elite), TierProfiles.widenPool(kind, elite, stored))

    private fun src(path: String): String =
        listOf(path, "app/$path", "../$path").map { java.io.File(it) }.first { it.exists() }.readText()

    @Test fun gpuHintDefaultsFollowTheTier() {
        assertEquals(GpuPerfHint.LOW, TierProfiles.defaults(gpu, ResourceTier.LOW, elite).gpuHint)
        assertEquals(GpuPerfHint.NORMAL, TierProfiles.defaults(gpu, ResourceTier.MEDIUM, elite).gpuHint)
        assertEquals(GpuPerfHint.HIGH, TierProfiles.defaults(gpu, ResourceTier.HIGH, elite).gpuHint)
        assertEquals(GpuPerfHint.LOW, plan(ResourceTier.LOW).gpuHint)
        assertEquals(GpuPerfHint.NORMAL, plan(ResourceTier.MEDIUM).gpuHint)
        assertEquals(GpuPerfHint.HIGH, plan(ResourceTier.HIGH).gpuHint)
        assertEquals(listOf("low", "normal", "high"), GpuPerfHint.entries.map { it.native })
        assertEquals(listOf("省电", "普通", "高"), GpuPerfHint.entries.map { it.label })
    }

    @Test fun cpuSetCarriesNoGpuHintAndOtherDefaultsAreUnchanged() {
        for (t in ResourceTier.entries) assertNull(TierProfiles.defaults(cpu, t, elite).gpuHint)
        // 1.7.8 values unchanged
        assertEquals(TierProfile(1, listOf(0, 1, 2, 3, 4, 5), 5, GpuPerfHint.LOW), TierProfiles.defaults(gpu, ResourceTier.LOW, elite))
        assertEquals(TierProfile(2, listOf(0, 1, 2, 3, 4, 5, 6, 7), -4, GpuPerfHint.HIGH), TierProfiles.defaults(gpu, ResourceTier.HIGH, elite))
        assertEquals(TierProfile(6, listOf(2, 3, 4, 5, 6, 7), -4), TierProfiles.defaults(cpu, ResourceTier.HIGH, elite)) // 2.0.3
    }

    @Test fun storedGpuEntryWithoutHintIsMigratedToTheDefault() {
        // A 1.7.8 settings file: GPU entry without "gpuHint".
        val old = """{"tierProfiles":{"GPU.MEDIUM":{"threads":1,"cpus":[4,5],"nice":3},"CPU.LOW":{"threads":1,"cpus":[4],"nice":10}}}"""
        val s = json.decodeFromString(AppSettings.serializer(), old)
        assertNull(s.tierProfiles["GPU.MEDIUM"]!!.gpuHint)
        val clean = TierProfiles.sanitize(s.tierProfiles)
        assertEquals(TierProfile(1, listOf(4, 5), 3, GpuPerfHint.NORMAL), clean["GPU.MEDIUM"])
        assertNull(clean["CPU.LOW"]!!.gpuHint)
        // resolve fills it too (no sanitize step) and keeps the user's other values
        assertEquals(GpuPerfHint.NORMAL, TierProfiles.resolve(s.tierProfiles, gpu, ResourceTier.MEDIUM, elite).gpuHint)
        assertEquals(listOf(4, 5), TierProfiles.resolve(s.tierProfiles, gpu, ResourceTier.MEDIUM, elite).cpus)
        // a stray hint on a CPU entry is dropped
        assertNull(TierProfiles.sanitize(mapOf("CPU.HIGH" to TierProfile(2, listOf(6, 7), -4, GpuPerfHint.LOW)))["CPU.HIGH"]!!.gpuHint)
    }

    @Test fun hintPersistsAndEqualToDefaultIsNotStored() {
        val custom = TierProfile(1, listOf(0, 1, 2, 3, 4, 5), 5, GpuPerfHint.HIGH) // 省电 GPU with hint 高
        val m = TierProfiles.withProfile(emptyMap(), gpu, ResourceTier.LOW, custom, elite)
        assertEquals(custom, m["GPU.LOW"])
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), AppSettings(tierProfiles = m)))
        assertEquals(GpuPerfHint.HIGH, TierProfiles.resolve(back.tierProfiles, gpu, ResourceTier.LOW, elite).gpuHint)
        assertTrue(TierProfiles.isCustom(back.tierProfiles, gpu, ResourceTier.LOW, elite))
        // restoring the default hint (or saving the default with a null hint) removes the entry
        assertTrue(TierProfiles.withProfile(m, gpu, ResourceTier.LOW, custom.copy(gpuHint = GpuPerfHint.LOW), elite).isEmpty())
        assertTrue(TierProfiles.withProfile(m, gpu, ResourceTier.LOW, custom.copy(gpuHint = null), elite).isEmpty())
        // only the hint changed → still a custom entry
        assertEquals(GpuPerfHint.NORMAL, plan(ResourceTier.HIGH, stored = TierProfiles.withProfile(emptyMap(), gpu, ResourceTier.HIGH,
            TierProfiles.defaults(gpu, ResourceTier.HIGH, elite).copy(gpuHint = GpuPerfHint.NORMAL), elite)).gpuHint)
    }

    @Test fun mnnGpuReloadsWhenTheHintChangesButLiteRtAndCpuDoNot() {
        val d = TierReloadPolicy.decide(ModelFormat.MNN, Backend.GPU, plan(ResourceTier.MEDIUM), plan(ResourceTier.HIGH),
            loadedPreferred = Backend.GPU, nextPreferred = Backend.GPU)
        assertTrue("GPU 性能提示" in d.changed)
        assertTrue(d.reload)
        assertFalse("后端" in d.changed)
        // same hint on another tier (user setting) → no hint reload
        val stored = TierProfiles.withProfile(emptyMap(), gpu, ResourceTier.HIGH,
            TierProfiles.defaults(gpu, ResourceTier.HIGH, elite).copy(gpuHint = GpuPerfHint.NORMAL), elite)
        val same = TierReloadPolicy.decide(ModelFormat.MNN, Backend.GPU, plan(ResourceTier.MEDIUM), plan(ResourceTier.HIGH, stored = stored),
            loadedPreferred = Backend.GPU, nextPreferred = Backend.GPU)
        assertFalse("GPU 性能提示" in same.changed)
        // LiteRT on the GPU: only the hint differs → no reload
        val hintOnly = TierProfiles.withProfile(emptyMap(), gpu, ResourceTier.MEDIUM,
            TierProfiles.defaults(gpu, ResourceTier.MEDIUM, elite).copy(gpuHint = GpuPerfHint.HIGH), elite)
        val lt = TierReloadPolicy.decide(ModelFormat.LITERT, Backend.GPU, plan(ResourceTier.MEDIUM), plan(ResourceTier.MEDIUM, stored = hintOnly))
        assertFalse(lt.reload)
        val mnn = TierReloadPolicy.decide(ModelFormat.MNN, Backend.GPU, plan(ResourceTier.MEDIUM), plan(ResourceTier.MEDIUM, stored = hintOnly))
        assertEquals(listOf("GPU 性能提示"), mnn.changed)
    }


    @Test fun perReplyLogShowsTheEffectiveGpuHint() {
        val p = plan(ResourceTier.LOW)
        val line = ResourceLog.perReply("GPU · OpenCL · MNN", Backend.GPU, p, 1, null, StopReason.END, 10, 5f, gpuHint = "low")
        assertTrue(line, line.contains("gpuHint=low "))
        assertEquals("unsupported (tier wants low)", ResourceLog.gpuHintText(Backend.GPU, p, "unsupported"))
        assertEquals("none (tier wants low)", ResourceLog.gpuHintText(Backend.GPU, p, "none"))
        assertTrue(ResourceLog.gpuHintText(Backend.GPU, p, "high").startsWith("high (tier wants low"))
        assertEquals("-", ResourceLog.gpuHintText(Backend.CPU, plan(ResourceTier.LOW, cpu), null))
        val cpuLine = ResourceLog.perReply("CPU · MNN", Backend.CPU, plan(ResourceTier.LOW, cpu), 1, null, StopReason.END, 10, 5f)
        assertTrue(cpuLine.contains("gpuHint=- "))
    }

}

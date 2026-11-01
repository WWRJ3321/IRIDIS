package com.iris.localai.v178

import com.iris.localai.data.AppSettings
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.TierProfile
import com.iris.localai.data.migrated
import com.iris.localai.data.sanitized
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendOption
import com.iris.localai.engine.CpuTopology
import com.iris.localai.engine.IdleUnloadRule
import com.iris.localai.engine.LengthRule
import com.iris.localai.engine.LiveChange
import com.iris.localai.engine.LiveResourcePolicy
import com.iris.localai.engine.ResourceBackendKind
import com.iris.localai.engine.ResourceLog
import com.iris.localai.engine.ResourcePlanner
import com.iris.localai.engine.ResourceTier
import com.iris.localai.engine.StopReason
import com.iris.localai.engine.TierProfiles
import com.iris.localai.engine.TierReloadPolicy
import com.iris.localai.engine.mnn.MnnThreadArg
import com.iris.localai.ui.settings.SettingsText
import com.iris.localai.util.ApiThinkOnly
import com.iris.localai.util.StreamingThinkSplitter
import com.iris.localai.util.ThinkingBudget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.7.8: 资源档配置 (per backend), 「全部」 removed + migration, tier never changes the backend,
 * MNN OpenCL mode, per-reply resource log, reasoning budget, GGUF context-full, API think-only,
 * code-review fixes, settings reorganisation.
 */
class V178Test {
    private val elite = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))
    private val tri = CpuTopology(listOf(listOf(0, 1), listOf(2, 3, 4, 5, 6), listOf(7)))
    private val one = CpuTopology(listOf(listOf(0, 1, 2, 3)))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun plan(t: ResourceTier, kind: ResourceBackendKind = ResourceBackendKind.CPU, stored: Map<String, TierProfile> = emptyMap(), topo: CpuTopology = elite) =
        ResourcePlanner.plan(t, topo, 0, 0, kind, TierProfiles.resolve(stored, kind, t, topo), TierProfiles.widenPool(kind, topo, stored))

    // ---------------------------------------------------------------- defaults

    @Test fun cpuDefaultsOnEliteAreThe176Values() {
        val c = ResourceBackendKind.CPU
        assertEquals(TierProfile(1, listOf(5), 10), TierProfiles.defaults(c, ResourceTier.LOW, elite))
        assertEquals(TierProfile(2, listOf(0, 1, 2, 3, 4, 5), 0), TierProfiles.defaults(c, ResourceTier.MEDIUM, elite))
        assertEquals(TierProfile(6, listOf(2, 3, 4, 5, 6, 7), -4), TierProfiles.defaults(c, ResourceTier.HIGH, elite)) // 2.0.3
        val p = ResourceTier.entries.map { plan(it) }
        assertEquals(listOf(listOf(5), listOf(0, 1, 2, 3, 4, 5), listOf(2, 3, 4, 5, 6, 7)), p.map { it.cpus })
        assertEquals(listOf(10, 0, -4), p.map { it.nice })
    }

    @Test fun gpuDefaultsLetHighUseEveryCoreWithModestNice() {
        val g = ResourceBackendKind.GPU
        // 1.7.9: GPU defaults also carry the GPU 性能提示 (checked in V179Test); compared without it here.
        val low = TierProfiles.defaults(g, ResourceTier.LOW, elite).copy(gpuHint = null)
        val med = TierProfiles.defaults(g, ResourceTier.MEDIUM, elite).copy(gpuHint = null)
        val high = TierProfiles.defaults(g, ResourceTier.HIGH, elite).copy(gpuHint = null)
        assertEquals(TierProfile(1, listOf(0, 1, 2, 3, 4, 5), 5), low)
        assertEquals(TierProfile(2, listOf(0, 1, 2, 3, 4, 5), 0), med)
        assertEquals(TierProfile(2, listOf(0, 1, 2, 3, 4, 5, 6, 7), -4), high)
        // 高 on the GPU is unpinned like the old 「全部」 (the only tier that was fast on LiteRT-GPU)…
        assertEquals(emptyList<Int>(), plan(ResourceTier.HIGH, g).cpus)
        // …but not as aggressive: nice never below -4, host threads stay few (MNN GPU: 1–2).
        for (t in ResourceTier.entries) {
            val p = TierProfiles.defaults(g, t, elite)
            assertTrue(p.nice >= -4); assertTrue(p.threads in 1..2)
        }
        // 省电/中 never get the prime cores, and are never squeezed onto a single core.
        assertFalse(low.cpus.any { it >= 6 }); assertTrue(low.cpus.size > 1)
        assertFalse(med.cpus.any { it >= 6 })
    }

    @Test fun defaultsFollowOtherTopologies() {
        val c = ResourceBackendKind.CPU
        assertEquals(TierProfile(2, listOf(0, 1), 10), TierProfiles.defaults(c, ResourceTier.LOW, tri))
        assertEquals(TierProfile(3, listOf(0, 1, 2, 3, 4, 5, 6), 0), TierProfiles.defaults(c, ResourceTier.MEDIUM, tri))
        assertEquals(TierProfile(4, listOf(2, 3, 4, 5, 6, 7), -4), TierProfiles.defaults(c, ResourceTier.HIGH, tri))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), TierProfiles.defaults(ResourceBackendKind.GPU, ResourceTier.LOW, tri).cpus)
        // one cluster: everything is "all cores" (no pinning)
        for (k in ResourceBackendKind.entries) for (t in ResourceTier.entries) {
            val d = TierProfiles.defaults(k, t, one)
            assertEquals(listOf(0, 1, 2, 3), d.cpus)
            assertTrue(TierProfiles.validate(d, one).isEmpty())
        }
        // every generated default is valid
        for (topo in listOf(elite, tri, one)) for (k in ResourceBackendKind.entries) for (t in ResourceTier.entries)
            assertTrue("$k $t", TierProfiles.validate(TierProfiles.defaults(k, t, topo), topo).isEmpty())
    }

    // ---------------------------------------------------------------- validation

    @Test fun validationRejectsBadProfiles() {
        assertTrue(TierProfiles.validate(TierProfile(2, listOf(4, 5), 0), elite).isEmpty())
        assertTrue(TierProfiles.validate(TierProfile(1, emptyList(), 0), elite).any { "至少选 1 个核心" in it })
        assertTrue(TierProfiles.validate(TierProfile(3, listOf(4, 5), 0), elite).any { "1 到 2" in it })
        assertTrue(TierProfiles.validate(TierProfile(0, listOf(4, 5), 0), elite).isNotEmpty())
        assertTrue(TierProfiles.validate(TierProfile(1, listOf(5), -9), elite).any { "nice" in it })
        assertTrue(TierProfiles.validate(TierProfile(1, listOf(5), 20), elite).any { "nice" in it })
        assertTrue(TierProfiles.validate(TierProfile(1, listOf(5), -8), elite).isEmpty())
        assertTrue(TierProfiles.validate(TierProfile(1, listOf(5), 19), elite).isEmpty())
        assertTrue(TierProfiles.validate(TierProfile(1, listOf(9), 0), elite).any { "cpu9" in it })
    }

    @Test fun invalidOrForeignStoredProfileFallsBackToDefault() {
        val k = TierProfiles.key(ResourceBackendKind.CPU, ResourceTier.LOW)
        val good = mapOf(k to TierProfile(1, listOf(4), 5))
        assertEquals(TierProfile(1, listOf(4), 5), TierProfiles.resolve(good, ResourceBackendKind.CPU, ResourceTier.LOW, elite))
        // a profile restored from a phone with more cores: cpu11 is not here → default
        val foreign = mapOf(k to TierProfile(1, listOf(11), 5))
        assertEquals(TierProfiles.defaults(ResourceBackendKind.CPU, ResourceTier.LOW, elite), TierProfiles.resolve(foreign, ResourceBackendKind.CPU, ResourceTier.LOW, elite))
        // the CPU profile never leaks into the GPU set
        assertEquals(TierProfiles.defaults(ResourceBackendKind.GPU, ResourceTier.LOW, elite), TierProfiles.resolve(good, ResourceBackendKind.GPU, ResourceTier.LOW, elite))
    }

    // ---------------------------------------------------------------- persistence

    @Test fun profilesSurviveJsonRoundTripAndSanitize() {
        val map = mapOf(
            "CPU.LOW" to TierProfile(1, listOf(5, 4, 5), 3),
            "GPU.HIGH" to TierProfile(2, listOf(6, 7), -2),
            "ALL.HIGH" to TierProfile(1, listOf(1), 0),     // unknown key
            "CPU.MEDIUM" to TierProfile(4, listOf(1, 2), 0), // threads > cores
            "GPU.LOW" to TierProfile(1, listOf(1), 30),      // nice out of range
        )
        val s = AppSettings(tierProfiles = map).sanitized()
        assertEquals(setOf("CPU.LOW", "GPU.HIGH"), s.tierProfiles.keys)
        assertEquals(listOf(4, 5), s.tierProfiles["CPU.LOW"]!!.cpus)
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), s)).migrated().sanitized()
        assertEquals(s.tierProfiles, back.tierProfiles)
    }

    @Test fun savingTheDefaultStoresNothingAndResetRemovesTheEntry() {
        val d = TierProfiles.defaults(ResourceBackendKind.GPU, ResourceTier.MEDIUM, elite)
        val custom = TierProfile(2, listOf(2, 3), 1)
        val m1 = TierProfiles.withProfile(emptyMap(), ResourceBackendKind.GPU, ResourceTier.MEDIUM, custom, elite)
        // 1.7.9: a GPU entry saved without a hint gets the tier's default GPU 性能提示 (中 → NORMAL).
        assertEquals(mapOf("GPU.MEDIUM" to custom.copy(gpuHint = com.iris.localai.data.GpuPerfHint.NORMAL)), m1)
        assertTrue(TierProfiles.isCustom(m1, ResourceBackendKind.GPU, ResourceTier.MEDIUM, elite))
        val m2 = TierProfiles.withProfile(m1, ResourceBackendKind.GPU, ResourceTier.MEDIUM, d, elite)
        assertEquals(emptyMap<String, TierProfile>(), m2)
    }

    @Test fun oldJsonWithoutProfilesDecodes() {
        val s = json.decodeFromString(AppSettings.serializer(), """{"resourceTier":1,"maxNewTokens":1024}""").migrated().sanitized()
        assertEquals(emptyMap<String, TierProfile>(), s.tierProfiles)
        assertEquals(2048, s.thinkingBudget)
        assertEquals(1, s.resourceTier)
    }

    // ---------------------------------------------------------------- 「全部」 removed

    @Test fun storedAllTierMigratesToHigh() {
        assertEquals(3, ResourceTier.entries.size)
        assertEquals(ResourceTier.HIGH.ordinal, AppSettings(resourceTier = ResourceTier.REMOVED_ALL_ORDINAL).migrated().resourceTier)
        assertEquals(ResourceTier.HIGH.ordinal, AppSettings(resourceTier = 3).sanitized().resourceTier)
        val s = json.decodeFromString(AppSettings.serializer(), """{"resourceTier":3}""").migrated().sanitized()
        assertEquals(ResourceTier.HIGH.ordinal, s.resourceTier)
        assertEquals(ResourceTier.HIGH, ResourceTier.of(3))
        assertEquals(ResourceTier.HIGH, ResourcePlanner.migrateLegacy(2, 0))   // ≤1.7.0 性能 → 高
        // the other tiers are untouched
        for (t in ResourceTier.entries) assertEquals(t.ordinal, AppSettings(resourceTier = t.ordinal).migrated().sanitized().resourceTier)
    }

    @Test fun ggufEngineDefaultsToBuiltInLlama() {
        // 2.2.0: GenieX is opt-in; every existing GGUF model keeps running on llama.cpp.
        val now = json.parseToJsonElement(json.encodeToString(AppSettings.serializer(), AppSettings())).jsonObject
        assertEquals("\"llama\"", now["ggufEngine"].toString())
    }

    @Test fun noExistingKeyOrDefaultChanged() {
        val now = json.parseToJsonElement(json.encodeToString(AppSettings.serializer(), AppSettings())).jsonObject
        for ((k, v) in DEFAULTS_177) {
            assertTrue("key $k still exists", k in now)
            assertEquals("default of $k", v, now[k].toString())
        }
        assertEquals(setOf("tierProfiles", "thinkingBudget", "mnnNoVisual", "mnnForceNoMmap", "topBarCapsules", "showStatusRack", "welcomeAccepted", "thermalLimitC", "knowledgeInChat", "haptics", "sound", "ggufEngine", "themePalette", "backgroundImage", "backgroundBlur", "backgroundScrim", "renderHtml", "loadNetworkImages", "defaultModelFile", "visionSide", "fileViewStyle"), now.keys - DEFAULTS_177.map { it.first }.toSet() - setOf("askedBatteryExemption"))
    }

    // ---------------------------------------------------------------- pinning from the user's cores

    @Test fun liveDownSwitchUsesTheUsersConfiguredCores() {
        // defaults: 中 (2 threads) → 省电 keeps cpu5 + the nearest 中 core
        assertEquals(listOf(4, 5), LiveResourcePolicy.coresFor(plan(ResourceTier.LOW), 2))
        // user: 中 = cpu2-3 only, 省电 = cpu3 → the widen pool is cpu2-3, never cpu4/5 or the prime cores
        val stored = mapOf(
            "CPU.MEDIUM" to TierProfile(2, listOf(2, 3), 0),
            "CPU.LOW" to TierProfile(1, listOf(3), 10),
        )
        val low = plan(ResourceTier.LOW, stored = stored)
        assertEquals(listOf(2, 3), low.nonPrimeCpus)
        assertEquals(listOf(2, 3), LiveResourcePolicy.coresFor(low, 2))
        assertEquals(listOf(2, 3), LiveResourcePolicy.coresFor(low, 4)) // pool exhausted → share, never unpin
        // a core the user gave only to 高 is never borrowed by a lower tier
        val s2 = mapOf("CPU.HIGH" to TierProfile(2, listOf(5, 6), -4))
        assertFalse(LiveResourcePolicy.coresFor(plan(ResourceTier.LOW, stored = s2), 3).any { it >= 6 })
        // GPU set: its own pool
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plan(ResourceTier.LOW, ResourceBackendKind.GPU).nonPrimeCpus)
    }

    // ---------------------------------------------------------------- per backend

    @Test fun planFollowsTheBackendKind() {
        assertEquals(ResourceBackendKind.CPU, ResourceBackendKind.of(Backend.CPU))
        assertEquals(ResourceBackendKind.CPU, ResourceBackendKind.of(null))
        assertEquals(ResourceBackendKind.GPU, ResourceBackendKind.of(Backend.GPU))
        assertEquals(ResourceBackendKind.GPU, ResourceBackendKind.of(Backend.NPU))
        val cpuHigh = plan(ResourceTier.HIGH, ResourceBackendKind.CPU)
        val gpuHigh = plan(ResourceTier.HIGH, ResourceBackendKind.GPU)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), cpuHigh.cpus) // 2.0.3
        assertEquals(emptyList<Int>(), gpuHigh.cpus)
        assertEquals(ResourceBackendKind.GPU, gpuHigh.kind)
        // a custom GPU profile only affects GPU plans
        val stored = mapOf("GPU.HIGH" to TierProfile(1, listOf(7), 0))
        assertEquals(listOf(7), plan(ResourceTier.HIGH, ResourceBackendKind.GPU, stored).cpus)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), plan(ResourceTier.HIGH, ResourceBackendKind.CPU, stored).cpus)
    }

    // ---------------------------------------------------------------- the tier never changes the backend

    private val opts = listOf(BackendOption(Backend.NPU, false, "no"), BackendOption(Backend.GPU, true), BackendOption(Backend.CPU, true))

    @Test fun userChosenGpuStaysGpuOnEveryTier() {
        for (t in ResourceTier.entries) {
            // the planner no longer forbids the GPU on any tier…
            assertTrue(plan(t).allowGpu)
            // …and the starting backend is the user's choice, whatever the tier
            assertEquals(Backend.GPU, BackendCapabilities.preferred(Backend.GPU, ModelFormat.MNN, "Qwen3-0.6B-MNN", opts))
            assertEquals(Backend.GPU, BackendCapabilities.preferred(Backend.GPU, ModelFormat.LITERT, "gemma-4-E2B-it.litertlm", opts))
        }
        // switching tiers on an MNN model that runs on the GPU: never a backend change
        for (from in ResourceTier.entries) for (to in ResourceTier.entries) {
            val d = TierReloadPolicy.decide(
                ModelFormat.MNN, Backend.GPU, plan(from, ResourceBackendKind.GPU), plan(to, ResourceBackendKind.GPU),
                loadedPreferred = Backend.GPU, nextPreferred = Backend.GPU,
            )
            assertFalse("$from→$to", "后端" in d.changed)
            assertFalse("$from→$to: OpenCL mode is fixed", "线程" in d.changed || "power" in d.changed)
        }
    }

    @Test fun autoPolicyIsPerFormatNotPerTier() {
        // 自动: MNN / GGUF → CPU, LiteRT → GPU (Qwen3.5 → CPU) — the same on every tier.
        assertEquals(Backend.CPU, BackendCapabilities.preferred(null, ModelFormat.MNN, "Qwen3-0.6B-MNN", opts))
        assertEquals(Backend.GPU, BackendCapabilities.preferred(null, ModelFormat.LITERT, "gemma-4-E2B-it.litertlm", opts))
        assertEquals(Backend.CPU, BackendCapabilities.preferred(null, ModelFormat.LITERT, "Qwen3.5-2B.litertlm", opts))
    }

    @Test fun engineManagerNoLongerFeedsTheTierIntoTheBackendChoice() {
        val src = source("src/main/java/com/iris/localai/engine/EngineManager.kt")
        val fn = src.substringAfter("fun preferredBackend(").substringBefore("/** Set after a successful")
        assertFalse(fn.contains("allowGpu"))
        assertTrue(fn.contains("BackendCapabilities.preferred("))
        // a load fallback is logged and shown (never silent)
        assertTrue(src.contains("backend FALLBACK"))
        assertTrue(src.contains("_notices.tryEmit(\"${'$'}{model.displayName}：${'$'}note\")"))
    }

    // ---------------------------------------------------------------- MNN OpenCL mode

    @Test fun mnnOpenClThreadNumIsAFixedModeNotTheTierThreads() {
        // what ≤1.7.7 sent per tier, as MNN decoded it
        assertEquals("NONE", MnnThreadArg.openClTuning(1))   // 省电 (1 thread)
        assertEquals("HEAVY", MnnThreadArg.openClTuning(2))  // 中/高 (2 threads)
        assertEquals("NORMAL", MnnThreadArg.openClTuning(8)) // 全部 (8 threads) — the slow one
        // 1.7.8: always WIDE on the GPU (MNN docs: 68 = BUFFER | WIDE; llm.cpp adds BUFFER)
        for (t in 1..8) assertEquals(4, MnnThreadArg.forBackend(useGpu = true, threads = t))
        assertEquals("WIDE", MnnThreadArg.openClTuning(MnnThreadArg.forBackend(true, 8) or 64 or 512))
        assertEquals(68, MnnThreadArg.forBackend(true, 2) or 64)
        // the CPU keeps the real thread count
        assertEquals(3, MnnThreadArg.forBackend(useGpu = false, threads = 3))
    }

    // ---------------------------------------------------------------- per-reply log

    @Test fun perReplyResourceLogNamesEverything() {
        val p = plan(ResourceTier.HIGH, ResourceBackendKind.GPU)
        val line = ResourceLog.perReply("GPU · OpenCL · MNN", Backend.GPU, p, 2, null, StopReason.END, 120, 33.333f)
        for (x in listOf("backend=GPU · OpenCL · MNN", "tier=高", "profile=GPU", "threads=2", "cores=全部 (plan)", "nice=-4", "tok/s=33.3", "end=END", "宿主线程"))
            assertTrue("$x in $line", line.contains(x))
        val cpu = ResourceLog.perReply("CPU · MNN", Backend.CPU, plan(ResourceTier.LOW), 2, LiveChange(2, listOf(4, 5), 10), StopReason.MAX_TOKENS, 10, 50f)
        assertTrue(cpu.contains("cores=cpu4-5 (engine)")); assertTrue(cpu.contains("nice=10"))
        assertTrue(cpu.contains("tier wants 1"))
        assertEquals("cpu0-2,6", TierProfiles.cpuRange(listOf(6, 0, 1, 2)))
        assertNull(LiveResourcePolicy.backendNote(Backend.CPU))
        assertEquals("GPU：只影响宿主线程", LiveResourcePolicy.backendNote(Backend.GPU))
    }

    // ---------------------------------------------------------------- reasoning budget (B1)

    @Test fun thinkingBudgetStopsReasoningAndKeepsTheAnswerLimit() {
        val b = ThinkingBudget(budget = 3, answerLimit = 5, startInThinking = false)
        assertEquals(8, b.engineLimit())
        assertEquals(ThinkingBudget.Verdict.NONE, b.onPiece("<think>"))
        assertEquals(ThinkingBudget.Verdict.NONE, b.onPiece("a"))
        assertEquals(ThinkingBudget.Verdict.NONE, b.onPiece("b"))
        assertEquals(ThinkingBudget.Verdict.THINKING_EXHAUSTED, b.onPiece("c"))
        assertEquals(ThinkingBudget.Verdict.NONE, b.onPiece("d")) // fires once
    }

    @Test fun answerAfterShortReasoningGetsItsFullLimit() {
        val b = ThinkingBudget(budget = 100, answerLimit = 3, startInThinking = true)
        b.onPiece("x"); b.onPiece("</think>")
        assertEquals(ThinkingBudget.Verdict.NONE, b.onPiece("1"))
        assertEquals(ThinkingBudget.Verdict.NONE, b.onPiece("2"))
        assertEquals(ThinkingBudget.Verdict.ANSWER_EXHAUSTED, b.onPiece("3"))
        val off = ThinkingBudget(budget = 0, answerLimit = 3, startInThinking = false)
        assertEquals(3, off.engineLimit())
        repeat(10) { assertEquals(ThinkingBudget.Verdict.NONE, off.onPiece("<think>z")) }
    }

    @Test fun chatViewModelMetersOnlyTheReasoningPassAndMapsTheStopToLength() {
        val src = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertTrue(src.contains("if (!answerOnly && !noThinking && budget > 0)"))
        assertTrue(src.contains("meterStop != null && ev.reason == StopReason.CANCELLED"))
        assertTrue(src.contains("maxNewTokens = eff.maxNewTokens + thinkingBudget"))
    }

    // ---------------------------------------------------------------- GGUF context full (B2)

    @Test fun ggufLimitSetByTheContextIsContextFull() {
        assertEquals(StopReason.CONTEXT_FULL, LengthRule.reason(StopReason.MAX_TOKENS, 700, 2048, contextShift = false))
        assertEquals(StopReason.MAX_TOKENS, LengthRule.reason(StopReason.MAX_TOKENS, 2048, 2048, contextShift = false))
        assertEquals(StopReason.MAX_TOKENS, LengthRule.reason(StopReason.MAX_TOKENS, 700, 2048, contextShift = true))
        assertEquals(StopReason.END, LengthRule.reason(StopReason.END, 10, 2048, false))
        val m = LengthRule.contextFullMessage(700, 2048)
        assertTrue(m.contains("上下文已满") && m.contains("自动摘要") && m.contains("700"))
    }

    // ---------------------------------------------------------------- API think-only (B3)

    @Test fun apiThinkOnlyContinuesOnceThenFailsExplicitly() {
        assertEquals(ApiThinkOnly.Step.CONTINUE, ApiThinkOnly.afterFirst(false, "推理", "", StopReason.END, null, 512).step)
        assertEquals(ApiThinkOnly.Step.CONTINUE, ApiThinkOnly.afterFirst(false, "推理", "", StopReason.MAX_TOKENS, null, 512).step)
        assertEquals(ApiThinkOnly.Step.DONE, ApiThinkOnly.afterFirst(false, "推理", "答案", StopReason.END, null, 512).step)
        assertEquals(ApiThinkOnly.Step.DONE, ApiThinkOnly.afterFirst(true, "推理", "", StopReason.END, null, 512).step)
        assertEquals(ApiThinkOnly.Step.DONE, ApiThinkOnly.afterFirst(false, "推理", "", StopReason.CANCELLED, null, 512).step)
        val full = ApiThinkOnly.afterFirst(false, "推理", "", StopReason.CONTEXT_FULL, null, 512)
        assertEquals(ApiThinkOnly.Step.FAIL, full.step); assertTrue(full.message!!.contains("上下文已满"))
        assertEquals(ApiThinkOnly.Step.DONE, ApiThinkOnly.afterContinuation("推理", "答案", StopReason.END, null, 512).step)
        val still = ApiThinkOnly.afterContinuation("推理", "", StopReason.END, null, 512)
        assertEquals(ApiThinkOnly.Step.FAIL, still.step); assertTrue(still.message!!.contains("没有给出正文"))
        assertEquals(ApiThinkOnly.Step.DONE, ApiThinkOnly.afterContinuation("推理", "", StopReason.CANCELLED, null, 512).step)
    }

    // ---------------------------------------------------------------- code review fixes

    @Test fun splitterBareCloseBeforeALaterOpenTagIsReasoning() {
        val s = StreamingThinkSplitter(false)
        val out = s.push("我在想</think>答案里提到<think>标签") + s.flush()
        val thinking = out.filter { it.thinking }.joinToString("") { it.text }
        val answer = out.filter { !it.thinking }.joinToString("") { it.text }
        assertEquals("我在想", thinking)
        assertEquals("答案里提到<think>标签", answer)
        // same answer as the stored-message parser
        assertEquals("我在想", com.iris.localai.util.ThinkParser.parse("我在想</think>答案里提到<think>标签").thinking)
        // the normal cases are unchanged
        val n = StreamingThinkSplitter(false)
        val o = n.push("<think>a</think>b") + n.flush()
        assertEquals(listOf(StreamingThinkSplitter.Piece(true, "a"), StreamingThinkSplitter.Piece(false, "b")), o)
        // split tags still work, and a second block after the first is answer text (as ThinkParser)
        val q = StreamingThinkSplitter(false)
        val r = listOf("<thi", "nk>x</th", "ink>y<think>z").flatMap { q.push(it) } + q.flush()
        assertEquals("x", r.filter { it.thinking }.joinToString("") { it.text })
        assertEquals("y<think>z", r.filter { !it.thinking }.joinToString("") { it.text })
        assertEquals("y<think>z", com.iris.localai.util.ThinkParser.parse("<think>x</think>y<think>z").answer)
    }

    @Test fun idleUnloadWaitsForRegisteredRequestsAndReloads() {
        assertTrue(IdleUnloadRule.canUnload(false, false, 0, false))
        assertFalse(IdleUnloadRule.canUnload(false, false, 1, false)) // request between ensureLoaded and generate
        assertFalse(IdleUnloadRule.canUnload(false, false, 0, true))  // tier reload owns the engine
        assertFalse(IdleUnloadRule.canUnload(true, false, 0, false))
        assertFalse(IdleUnloadRule.canUnload(false, true, 0, false))
    }

    @Test fun summaryRunsInsideTheReplysTryBlock() {
        // A stop during 自动摘要 must still run the finally (clear streaming, stop the FGS).
        val src = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        val run = src.substringAfter("private suspend fun runReply(").substringBefore("/** Engine-measured tokens")
        val tryAt = run.indexOf("try {\n                plan = ").takeIf { it >= 0 && run.indexOf("planFor(", it) in it until it + 160 } ?: -1
        val summarizeAt = run.indexOf("summarize(plan)")
        val finallyAt = run.indexOf("} finally {\n                if (result == null)")
        assertTrue(tryAt in 0 until summarizeAt)
        assertTrue(summarizeAt < finallyAt)
    }

    // ---------------------------------------------------------------- settings reorganisation

    @Test fun settingsHomeIsGroupedByFunction() {
        assertEquals(listOf("模型与推理", "语音与服务", "界面与外观", "数据", "关于与调试"), SettingsText.HOME_GROUPS.map { it.first })
        // 2.1.3: 「上下文与记忆」 and 「记忆」 merged into one entry 「记忆与上下文」.
        assertEquals(listOf("模型与后端", "生成", "上下文、记忆和知识库", "性能与资源"), SettingsText.HOME_GROUPS[0].second)
        val src = source("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        for (title in SettingsText.HOME_GROUPS.map { it.first }) assertTrue(src.contains("SectionTitle(\"$title\")"))
        assertFalse(src.contains("全部资源")); assertFalse(src.contains("ResourceTier.ALL"))
        assertFalse(src.contains("生成参数与性能"))
        for (p in listOf("MODEL_BACKEND", "GENERATION", "CONTEXT", "PERFORMANCE", "TIER_PROFILES"))
            assertTrue(com.iris.localai.ui.settings.SettingsPage.entries.any { it.name == p })
    }

    @Test fun everySettingRowHasAOneLineDescription() {
        for (f in listOf("SettingsScreens.kt", "TierProfilesPage.kt")) {
            val src = source("src/main/java/com/iris/localai/ui/settings/$f")
            val body = if (f == "SettingsScreens.kt") src.substringAfter("fun SettingsHome(").substringBefore("fun AboutPage(") else src
            for (call in calls(body, listOf("SwitchRow(", "SliderRow(", "SegmentedRow(", "NavItem(")))
                assertTrue("$f: no description in $call", call.contains("subtitle") || call.startsWith("NavItem("))
            for (call in calls(body, listOf("NavItem(\"")))
                assertTrue("$f: NavItem needs a subtitle: $call", call.count { it == ',' } >= 2)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun source(path: String): String =
        listOf(path, "app/$path").map { java.io.File(it) }.first { it.exists() }.readText()

    /** Each call expression starting with one of [names] (balanced parentheses). */
    private fun calls(src: String, names: List<String>): List<String> {
        val out = ArrayList<String>()
        for (n in names) {
            var i = src.indexOf(n)
            while (i >= 0) {
                if (i > 0 && (src[i - 1].isLetterOrDigit() || src[i - 1] == '.')) { i = src.indexOf(n, i + 1); continue }
                if (src.lastIndexOf("fun ", i).let { it >= 0 && src.substring(it, i).trim() == "fun" || (it >= 0 && i - it <= 5) }) { i = src.indexOf(n, i + 1); continue }
                var depth = 0; var j = i + n.length - 1
                while (j < src.length) { if (src[j] == '(') depth++ else if (src[j] == ')') { depth--; if (depth == 0) break }; j++ }
                out += src.substring(i, minOf(j + 1, src.length))
                i = src.indexOf(n, j)
            }
        }
        return out
    }

    private companion object {
        /** AppSettings keys and default values of 1.7.7 (from its Models.kt), JSON-encoded. */
        val DEFAULTS_177: List<Pair<String, String>> = listOf(
        "themeMode" to "\"SYSTEM\"",
        "systemPrompt" to "\"\"",
        "temperature" to "0.7",
        "topP" to "0.95",
        "topK" to "40",
        "minP" to "0.05",
        "repeatPenalty" to "1.1",
        "maxNewTokens" to "2048",
        "contextLength" to "4096",
        "threads" to "0",
        "powerMode" to "1",
        "targetTps" to "0",
        "flashAttention" to "true",
        "liteRtBackend" to "0",
        "mnnBackend" to "0",
        "llamaGpu" to "false",
        "kvQuant" to "false",
        "deepThinking" to "true",
        "lastModelFile" to "null",
        "autoLoadLastModel" to "true",
        "keepScreenOn" to "true",
        "idleUnloadMinutes" to "30",
        "glassEffects" to "true",
        "apiEnabled" to "false",
        "apiPort" to "8686",
        "apiAllowLan" to "false",
        "apiKey" to "\"\"",
        "asrLanguage" to "\"auto\"",
        "ttsSpeakerId" to "3",
        "asrPack" to "\"sense-voice-int8\"",
        "ttsPack" to "\"kokoro-int8-multi-lang-v1_1\"",
        "ttsSpeed" to "1.0",
        "autoSpeak" to "false",
        "downloadSource" to "\"HF_MIRROR\"",
        "cloneVoiceId" to "\"\"",
        "cloneFidelity" to "0",
        "cloneStepsFast" to "4",
        "cloneStepsFine" to "12",
        "perfOverlay" to "false",
        "backgroundGeneration" to "true",
        "askedNotificationPermission" to "false",
        "bargeInSensitivity" to "1",
        "bargeInVoiceComm" to "false",
        "resourceTier" to "-1",
        "defaultBackends" to "{}",
        "backendsMigrated" to "false",
        "kvCacheType" to "-1",
        "repeatLastN" to "64",
        "presencePenalty" to "0.0",
        "frequencyPenalty" to "0.0",
        "seed" to "-1",
        "batchSize" to "0",
        "useMmap" to "true",
        "warmup" to "false",
        "contextStrategy" to "1",
        "contextKeepTurns" to "8",
        "summaryTriggerPercent" to "75",
        "visualEffects" to "-1",
        )
    }
}

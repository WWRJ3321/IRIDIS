package com.iris.localai.engine

import com.iris.localai.data.AppSettings
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelOverrides
import com.iris.localai.data.migrated
import com.iris.localai.data.sanitized
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.BackendOption
import com.iris.localai.engine.BackendPlanner
import com.iris.localai.engine.resources.CpuTopology
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.resources.ResourcePlanner
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.ui.glass.GlassMode
import com.iris.localai.ui.glass.GlassPolicy
import com.iris.localai.ui.glass.GlassQuality
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.MotionMath
import com.iris.localai.util.ContextPlanner
import com.iris.localai.util.ContextRequest
import com.iris.localai.util.CtxMessage
import com.iris.localai.util.streaming.ThinkParseCache
import com.iris.localai.util.TokenEstimator
import com.iris.localai.util.streaming.TypewriterPacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// ------------------------------------------------------------------------------------------------
// Context strategies
// ------------------------------------------------------------------------------------------------
class ContextPlannerTest {
    /** n rounds of user/assistant, each message [len] CJK characters. */
    private fun convo(n: Int, len: Int = 40): List<CtxMessage> = (0 until n).flatMap { i ->
        listOf(
            CtxMessage("u$i", PromptMessage.USER, "问".repeat(len) + i),
            CtxMessage("a$i", PromptMessage.ASSISTANT, "答".repeat(len) + i),
        )
    } + CtxMessage("u$n", PromptMessage.USER, "最后的问题")

    private fun req(
        msgs: List<CtxMessage>, strategy: ContextStrategy, ctx: Int = 100_000, keep: Int = 8,
        system: String = "你是助手", canShift: Boolean = true, startAfter: String? = null,
        summary: String = "", summaryUpTo: String? = null,
    ) = ContextRequest(
        system = system, messages = msgs, strategy = strategy, contextTokens = ctx, maxNewTokens = 512,
        keepTurns = keep, startAfterId = startAfter, engineCanShift = canShift, summary = summary, summaryUpToId = summaryUpTo,
    )

    private fun ids(p: com.iris.localai.util.ContextPlan) = p.messages.filter { it.role != PromptMessage.SYSTEM }.map { it.content.last() }

    @Test fun everythingFitsNothingDropped() {
        val p = ContextPlanner.plan(req(convo(3), ContextStrategy.TOKEN_BUDGET))
        assertEquals(0, p.droppedMessages)
        assertEquals(PromptMessage.SYSTEM, p.messages.first().role)
        assertEquals(1 + 7, p.messages.size)
        assertEquals("最后的问题", p.messages.last().content)
    }

    @Test fun slidingWindowKeepsLastRoundsInPairs() {
        val p = ContextPlanner.plan(req(convo(20), ContextStrategy.SLIDING_WINDOW, keep = 4))
        val hist = p.messages.drop(1)
        // Starts with a user message, alternates, ends with the latest question.
        assertEquals(PromptMessage.USER, hist.first().role)
        hist.zipWithNext().forEach { (a, b) -> assertTrue(a.role != b.role) }
        assertEquals("最后的问题", hist.last().content)
        val rounds = hist.count { it.role == PromptMessage.USER }
        assertTrue("window ≤ keep: $rounds", rounds in 1..4)
        assertEquals(0, p.droppedMessages % 2)
    }

    @Test fun slidingWindowMovesInStepsSoThePrefixIsStable() {
        // keep = 8 → step 2: two consecutive turns share the same first message half of the time.
        val firsts = (12..20).map { n -> ContextPlanner.plan(req(convo(n), ContextStrategy.SLIDING_WINDOW, keep = 8)).messages[1].content }
        val changes = firsts.zipWithNext().count { (a, b) -> a != b }
        assertTrue("window start changed $changes times over 8 turns", changes <= 5)
    }

    @Test fun tokenBudgetKeepsSystemFirstRoundAndRecent() {
        val msgs = convo(40, len = 60)
        val p = ContextPlanner.plan(req(msgs, ContextStrategy.TOKEN_BUDGET, ctx = 2048))
        assertTrue(p.droppedMessages > 0)
        assertTrue("fits the budget: ${p.estimatedTokens} / ${p.budget}", p.estimatedTokens <= p.budget)
        assertEquals("你是助手", p.messages[0].content)
        assertEquals(msgs[0].content, p.messages[1].content) // first round kept
        assertEquals(msgs[1].content, p.messages[2].content)
        assertEquals("最后的问题", p.messages.last().content)
        assertEquals(0, p.droppedMessages % 2) // whole rounds only
    }

    @Test fun tokenBudgetCutIsChunkedForKvReuse() {
        val base = convo(60, len = 30)
        // Grow the conversation one round at a time: the kept prefix (after the first round)
        // should stay the same for several turns in a row.
        val second = (40..60).map { n ->
            val m = base.take(n * 2) + CtxMessage("q$n", PromptMessage.USER, "新问题$n")
            ContextPlanner.plan(req(m, ContextStrategy.TOKEN_BUDGET, ctx = 2048)).messages[3].content
        }
        val changes = second.zipWithNext().count { (a, b) -> a != b }
        assertTrue("cut moved $changes times in 20 turns", changes <= 8)
    }

    @Test fun summaryAsksToFoldOldRounds() {
        val p = ContextPlanner.plan(req(convo(40, len = 60), ContextStrategy.SUMMARY, ctx = 4096))
        assertTrue(p.toSummarize.isNotEmpty())
        assertEquals("u0", p.toSummarize.first().id)
        assertEquals(PromptMessage.ASSISTANT, p.toSummarize.last().role) // folds whole rounds
    }

    @Test fun summaryIsUsedAsLeadingContext() {
        val msgs = convo(10)
        val p = ContextPlanner.plan(req(msgs, ContextStrategy.SUMMARY, summary = "用户在规划旅行", summaryUpTo = "a5"))
        // 2.3: the summary leads the history (joined to the first kept user turn); the system prompt is unchanged.
        assertEquals("你是助手", p.messages[0].content)
        assertTrue(p.messages[1].content.startsWith(ContextPlanner.SUMMARY_HEADER))
        assertTrue(p.messages[1].content.contains("用户在规划旅行"))
        assertTrue(p.messages[1].content.endsWith(msgs.first { it.id == "u6" }.content))
        assertEquals(12, p.droppedMessages)
        assertTrue(p.toSummarize.isEmpty())
    }

    @Test fun kvShiftOnlyWhereSupported() {
        val on = ContextPlanner.plan(req(convo(3), ContextStrategy.KV_SHIFT))
        assertTrue(on.contextShift)
        assertTrue(on.keepTokens >= 4)
        assertEquals(ContextStrategy.KV_SHIFT, on.applied)
        val off = ContextPlanner.plan(req(convo(3), ContextStrategy.KV_SHIFT, canShift = false))
        assertFalse(off.contextShift)
        assertEquals(ContextStrategy.TOKEN_BUDGET, off.applied)
    }

    @Test fun manualBreakDropsEverythingBefore() {
        val msgs = convo(5)
        val p = ContextPlanner.plan(req(msgs, ContextStrategy.TOKEN_BUDGET, startAfter = "a2"))
        assertEquals(6, p.droppedMessages)
        assertEquals(msgs.first { it.id == "u3" }.content, p.messages[1].content)
    }

    @Test fun latestQuestionSurvivesATinyWindow() {
        val p = ContextPlanner.plan(req(convo(30, len = 200), ContextStrategy.TOKEN_BUDGET, ctx = 512))
        assertEquals("最后的问题", p.messages.last().content)
    }

    @Test fun estimatorCountsCjkAndLatin() {
        assertEquals(4, TokenEstimator.estimate("你好世界"))
        assertTrue(TokenEstimator.estimate("hello world, this is a test") in 6..10)
    }
}

// ------------------------------------------------------------------------------------------------
// Per-model configuration
// ------------------------------------------------------------------------------------------------
class ModelConfigMergeTest {
    @Test fun unsetFieldsFollowGlobalDefaults() {
        val s = AppSettings(temperature = 0.4f, topK = 30, contextLength = 8192, deepThinking = true, systemPrompt = "全局")
        val e = EffectiveModelConfig.resolve(s, ModelFormat.GGUF, ModelOverrides(temperature = 1.1f, thinking = false))
        assertEquals(1.1f, e.temperature)
        assertEquals(30, e.topK)
        assertEquals(8192, e.contextLength)
        assertFalse(e.thinking)
        assertEquals("全局", e.systemPrompt)
        assertNull(e.backend)
    }

    @Test fun modelBackendBeatsEngineDefault() {
        val s = AppSettings(defaultBackends = mapOf("GGUF" to "GPU"))
        assertEquals(Backend.GPU, EffectiveModelConfig.resolve(s, ModelFormat.GGUF, null).backend)
        assertEquals(Backend.CPU, EffectiveModelConfig.resolve(s, ModelFormat.GGUF, ModelOverrides(backend = "CPU")).backend)
        assertNull(EffectiveModelConfig.resolve(s, ModelFormat.MNN, null).backend)
    }

    @Test fun overridesAreClampedAndEmptyMeansDefault() {
        val o = ModelOverrides(temperature = Float.NaN, topP = 9f, kvCacheType = 7, backend = "tpu", seed = -5).sanitized()
        assertEquals(0.7f, o.temperature)
        assertEquals(1f, o.topP)
        assertEquals(2, o.kvCacheType)
        assertNull(o.backend)
        assertEquals(-1, o.seed)
        assertTrue(ModelOverrides().isEmpty)
    }

    @Test fun modelSystemPromptReplacesGlobal() {
        val e = EffectiveModelConfig.resolve(AppSettings(systemPrompt = "全局"), ModelFormat.MNN, ModelOverrides(systemPrompt = "专属"))
        assertEquals("专属", e.systemPrompt)
    }
}

// ------------------------------------------------------------------------------------------------
// Backends: capabilities + fallback chain
// ------------------------------------------------------------------------------------------------
class BackendTest {
    private val env = BackendEnvironment(hasOpenCl = true, npuSoc = "sm8750", npuRuntimeInstalled = true, fullFlavor = true)

    @Test fun ggufHasNoNpu() {
        val o = BackendCapabilities.options(ModelFormat.GGUF, "qwen3-4b-q4_0.gguf", env)
        assertTrue(o.first { it.backend == Backend.CPU }.available)
        assertTrue(o.first { it.backend == Backend.GPU }.available)
        val npu = o.first { it.backend == Backend.NPU }
        assertFalse(npu.available)
        assertTrue(npu.reason!!.isNotBlank())
    }

    @Test fun npuCompiledLiteRtRunsOnlyOnNpu() {
        val o = BackendCapabilities.options(ModelFormat.LITERT, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm", env)
        assertEquals(listOf(Backend.NPU), o.filter { it.available }.map { it.backend })
        assertEquals(Backend.NPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, "x_sm8750.litertlm", o))
        assertEquals(1280, BackendCapabilities.features(ModelFormat.LITERT, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm").fixedContext)
        val wrongChip = BackendCapabilities.options(ModelFormat.LITERT, "m_sm8650.litertlm", env)
        assertTrue(wrongChip.none { it.available })
        val noRuntime = BackendCapabilities.options(ModelFormat.LITERT, "m_sm8750.litertlm", env.copy(npuRuntimeInstalled = false))
        assertTrue(noRuntime.first { it.backend == Backend.NPU }.reason!!.contains("运行库"))
    }

    @Test fun autoChoices() {
        val lit = BackendCapabilities.options(ModelFormat.LITERT, "gemma-4-E2B-it.litertlm", env)
        assertEquals(Backend.GPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, "gemma-4-E2B-it.litertlm", lit))
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, "Qwen3.5-2B.litertlm", lit))
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.LITERT, "gemma-4-E2B-it.litertlm", lit, allowGpu = false))
        val gg = BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", env)
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.GGUF, "a.gguf", gg))
    }

    @Test fun fallbackChainGoesDownAndSkipsCrashes() {
        val all = Backend.entries.map { BackendOption(it, true) }
        assertEquals(listOf(Backend.NPU, Backend.GPU, Backend.CPU), BackendPlanner.chain(Backend.NPU, all))
        assertEquals(listOf(Backend.GPU, Backend.CPU), BackendPlanner.chain(Backend.GPU, all))
        assertEquals(listOf(Backend.CPU), BackendPlanner.chain(Backend.CPU, all))
        assertEquals(listOf(Backend.CPU), BackendPlanner.chain(Backend.GPU, all, crashed = setOf(Backend.GPU)))
        val noGpu = listOf(BackendOption(Backend.CPU, true), BackendOption(Backend.GPU, false, "无 OpenCL"), BackendOption(Backend.NPU, false))
        assertEquals(listOf(Backend.CPU), BackendPlanner.chain(Backend.GPU, noGpu))
    }

    @Test fun npuOnlyModelKeepsNpuEvenAfterACrash() {
        val npuOnly = listOf(BackendOption(Backend.CPU, false), BackendOption(Backend.GPU, false), BackendOption(Backend.NPU, true))
        assertEquals(listOf(Backend.NPU), BackendPlanner.chain(Backend.NPU, npuOnly, crashed = setOf(Backend.NPU)))
    }

    @Test fun contextShiftSupport() {
        assertNull(BackendCapabilities.contextShiftUnsupported(ModelFormat.GGUF))
        assertTrue(BackendCapabilities.contextShiftUnsupported(ModelFormat.LITERT) != null)
        assertTrue(BackendCapabilities.contextShiftUnsupported(ModelFormat.MNN) != null)
    }
}

// ------------------------------------------------------------------------------------------------
// Resource tiers → threads / cores
// ------------------------------------------------------------------------------------------------
class ResourceTierTest {
    /** Snapdragon 8 Elite: 6 × 3.53 GHz + 2 × 4.32 GHz Oryon. */
    private val sm8750 = CpuTopology.fromFrequencies(List(6) { 3_532_800L } + List(2) { 4_320_000L })
    /** Classic big.LITTLE with a prime core: 4 little, 3 big, 1 prime. */
    private val tri = CpuTopology.fromFrequencies(List(4) { 2_000_000L } + List(3) { 2_800_000L } + listOf(3_300_000L))

    @Test fun topologyGroupsByFrequency() {
        assertEquals(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)), sm8750.clusters)
        assertEquals(3, tri.clusters.size)
    }

    @Test fun sm8750Mapping() {
        // 1.7.6 tiers on 2-cluster chips: 省电 1 thread cpu5 · 中 2 threads cpu0-5 · 高 2 threads cpu6-7 · 全部 8 threads.
        val low = ResourcePlanner.plan(ResourceTier.LOW, sm8750)
        assertEquals(1, low.threads)
        assertEquals(listOf(5), low.cpus)
        assertTrue(low.allowGpu) // 1.7.8: the tier never changes the backend any more
        assertTrue(low.nice > 0)
        val mid = ResourcePlanner.plan(ResourceTier.MEDIUM, sm8750)
        assertEquals(2, mid.threads)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), mid.cpus) // avoids the prime cores
        val high = ResourcePlanner.plan(ResourceTier.HIGH, sm8750)
        assertEquals(6, high.threads) // 2.0.3: prime + fastest performance cores
        assertEquals(listOf(2, 3, 4, 5, 6, 7), high.cpus)
        // 1.7.8: 「全部」 removed (three tiers).
        assertEquals(3, ResourceTier.entries.size)
        assertTrue(high.batch > low.batch)
        assertEquals("high", high.mnnPower)
        assertEquals("low", low.mnnPower)
    }

    @Test fun threeClusterChipUsesLittleCoresForLow() {
        val low = ResourcePlanner.plan(ResourceTier.LOW, tri)
        assertEquals(listOf(0, 1, 2, 3), low.cpus)
        val high = ResourcePlanner.plan(ResourceTier.HIGH, tri)
        assertEquals(listOf(4, 5, 6, 7), high.cpus) // performance cores only
        assertEquals(2, high.threads)
    }

    @Test fun monotonicThreads() {
        // 1.7.6: 中 and 高 both use 2 threads on the 8 Elite, so "more resources" is judged on
        // (core class, priority, threads) together: none gets worse a step up, at least one improves.
        val prime = sm8750.clusters.last().toSet()
        val ps = ResourceTier.entries.map { ResourcePlanner.plan(it, sm8750) }
        fun coreClass(cpus: List<Int>) = if (cpus.isEmpty() || cpus.any { it in prime }) 1 else 0
        for (i in 1 until ps.size) {
            val a = ps[i - 1]; val b = ps[i]
            assertTrue(coreClass(b.cpus) >= coreClass(a.cpus))
            assertTrue(b.nice <= a.nice)
            assertTrue(b.threads >= a.threads)
            assertTrue(coreClass(b.cpus) > coreClass(a.cpus) || b.nice < a.nice || b.threads > a.threads)
        }
    }

    @Test fun manualThreadsWidenTheCoreSet() {
        val p = ResourcePlanner.plan(ResourceTier.LOW, tri, userThreads = 6)
        assertEquals(6, p.threads)
        assertTrue(p.cpus.isEmpty())
    }

    @Test fun thermalStepDown() {
        // 省电 (1 thread) cannot drop below 1; 高 (2 threads) keeps its floor of 2. (1.7.8: no 全部.)
        assertEquals(1, ResourcePlanner.plan(ResourceTier.LOW, sm8750, thermal = ResourcePlanner.THERMAL_LIGHT).threads)
        assertEquals(5, ResourcePlanner.plan(ResourceTier.HIGH, sm8750, thermal = ResourcePlanner.THERMAL_MODERATE).threads) // 2.0.3: 高 = 6
        assertEquals(3, ResourcePlanner.plan(ResourceTier.HIGH, sm8750, thermal = ResourcePlanner.THERMAL_SEVERE).threads)
        // the step-down itself is unchanged where a tier has threads to give (3-cluster 中 = 3 threads)
        assertEquals(3, ResourcePlanner.plan(ResourceTier.MEDIUM, tri).threads)
        assertEquals(2, ResourcePlanner.plan(ResourceTier.MEDIUM, tri, thermal = ResourcePlanner.THERMAL_MODERATE).threads)
    }
}

// ------------------------------------------------------------------------------------------------
// Old settings → 1.7.1
// ------------------------------------------------------------------------------------------------
class SettingsMigrationTest {
    @Test fun powerModeAndSpeedTargetBecomeATier() {
        assertEquals(ResourceTier.LOW.ordinal, AppSettings(powerMode = 0).migrated().resourceTier)
        assertEquals(ResourceTier.HIGH.ordinal, AppSettings(powerMode = 1).migrated().resourceTier)
        assertEquals(ResourceTier.HIGH.ordinal, AppSettings(powerMode = 2).migrated().resourceTier) // 1.7.8: 性能 → 高 (全部 removed)
        assertEquals(ResourceTier.LOW.ordinal, AppSettings(powerMode = 1, targetTps = 3).migrated().resourceTier)
        assertEquals(ResourceTier.MEDIUM.ordinal, AppSettings(powerMode = 2, targetTps = 10).migrated().resourceTier)
    }

    @Test fun scatteredBackendSwitchesBecomeDefaults() {
        val m = AppSettings(llamaGpu = true, liteRtBackend = 1, mnnBackend = 1).migrated()
        assertEquals(mapOf("GGUF" to "GPU", "LITERT" to "CPU", "MNN" to "GPU"), m.defaultBackends)
        assertTrue(m.backendsMigrated)
        assertEquals(emptyMap<String, String>(), AppSettings().migrated().defaultBackends)
    }

    @Test fun kvAndGlassSwitches() {
        assertEquals(1, AppSettings(kvQuant = true).migrated().kvCacheType)
        assertEquals(0, AppSettings().migrated().kvCacheType)
        assertEquals(0, AppSettings(glassEffects = false).migrated().visualEffects)
        assertEquals(1, AppSettings().migrated().visualEffects)
    }

    @Test fun migrationIsIdempotentAndKeepsUserChoices() {
        val once = AppSettings(powerMode = 0, llamaGpu = true).migrated()
        val user = once.copy(resourceTier = ResourceTier.MEDIUM.ordinal, defaultBackends = emptyMap())
        assertEquals(user, user.migrated())
    }

    @Test fun oldJsonWithoutNewKeysDecodesAndMigrates() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"powerMode":2,"targetTps":0,"llamaGpu":true,"kvQuant":true,"glassEffects":false,"someRemovedKey":1}"""
        val s = json.decodeFromString(AppSettings.serializer(), old).migrated().sanitized()
        assertEquals(ResourceTier.HIGH.ordinal, s.resourceTier) // 1.7.8: was 全部
        assertEquals("GPU", s.defaultBackends["GGUF"])
        assertEquals(1, s.kvCacheType)
        assertEquals(0, s.visualEffects)
    }

    @Test fun newFieldsAreSanitized() {
        val s = AppSettings(
            resourceTier = 9, kvCacheType = 5, presencePenalty = Float.NaN, contextStrategy = 42,
            contextKeepTurns = 0, defaultBackends = mapOf("GGUF" to "TPU", "FOO" to "CPU", "MNN" to "GPU"),
        ).sanitized()
        assertEquals(2, s.resourceTier) // 1.7.8: three tiers, out of range → 高
        assertEquals(2, s.kvCacheType)
        assertEquals(0f, s.presencePenalty)
        assertEquals(ContextStrategy.entries.size - 1, s.contextStrategy)
        assertEquals(1, s.contextKeepTurns)
        assertEquals(mapOf("MNN" to "GPU"), s.defaultBackends)
    }
}

// ------------------------------------------------------------------------------------------------
// Glass policy + motion maths
// ------------------------------------------------------------------------------------------------
class GlassPolicyTest {
    // 1.7.3: liquid glass removed — 均衡 / 华丽 are both frosted glass.
    @Test fun levels() {
        assertEquals(GlassMode.TRANSLUCENT, GlassPolicy.resolve(GlassPolicy.LEVEL_ECO).mode)
        val balanced = GlassPolicy.resolve(GlassPolicy.LEVEL_BALANCED)
        val rich = GlassPolicy.resolve(GlassPolicy.LEVEL_RICH)
        assertEquals(GlassMode.FROSTED, balanced.mode)
        assertEquals(GlassMode.FROSTED, rich.mode)
        assertEquals(2, balanced.downsampleSmall); assertEquals(4, balanced.downsampleLarge)
        assertEquals(1, rich.downsampleSmall); assertEquals(2, rich.downsampleLarge)
        assertEquals(GlassMode.values().toSet(), setOf(GlassMode.TRANSLUCENT, GlassMode.FROSTED))
    }

    @Test fun windowBlurOnlyAtRich() {
        assertTrue(GlassPolicy.resolve(GlassPolicy.LEVEL_RICH).windowBlur)
        assertFalse(GlassPolicy.resolve(GlassPolicy.LEVEL_BALANCED).windowBlur)
        assertFalse(GlassPolicy.resolve(GlassPolicy.LEVEL_ECO).windowBlur)
        assertFalse(GlassPolicy.resolve(2, powerSave = true).windowBlur)
    }

    @Test fun downgrades() {
        assertEquals(GlassQuality.Downgrade.POWER_SAVE, GlassPolicy.resolve(2, powerSave = true).downgradedBy)
        assertEquals(GlassMode.TRANSLUCENT, GlassPolicy.resolve(2, thermalStatus = GlassPolicy.THERMAL_SEVERE).mode)
        assertEquals(GlassMode.FROSTED, GlassPolicy.resolve(2, thermalStatus = GlassPolicy.THERMAL_MODERATE).mode)
        assertEquals(GlassQuality.Downgrade.THERMAL, GlassPolicy.resolve(2, thermalStatus = GlassPolicy.THERMAL_MODERATE).downgradedBy)
        assertEquals(GlassQuality.Downgrade.USER_OFF, GlassPolicy.resolve(2, glassSwitch = false).downgradedBy)
        assertFalse(GlassPolicy.resolve(2, thermalStatus = GlassPolicy.THERMAL_MODERATE).windowBlur)
    }
}

class MotionMathTest {
    @Test fun criticallyDampedTokensNeverOvershoot() {
        listOf(IrisMotion.Smooth, IrisMotion.FadeIn, IrisMotion.FadeOut, IrisMotion.Color).forEach { tk ->
            assertEquals(0f, MotionMath.overshoot(tk.damping))
            var t = 0f
            while (t < 2f) { assertTrue(MotionMath.springValue(t, tk.response, tk.damping) <= 1.0001f); t += 0.01f }
        }
    }

    @Test fun bouncyOvershootsALittle() {
        val o = MotionMath.overshoot(IrisMotion.Bouncy.damping)
        assertTrue("overshoot $o", o in 0.02f..0.2f)
    }

    @Test fun stiffnessFromResponse() {
        assertEquals(39.478f, MotionMath.stiffness(1f), 0.01f)
        assertTrue(MotionMath.stiffness(0.2f) > MotionMath.stiffness(0.5f))
    }

    @Test fun settleTimesAreReasonable() {
        assertTrue(MotionMath.settleTime(IrisMotion.Snappy.response, IrisMotion.Snappy.damping) < 0.8f)
        assertTrue(MotionMath.settleTime(IrisMotion.Smooth.response, IrisMotion.Smooth.damping) < 1.0f)
    }

    @Test fun rubberBandAndAnchors() {
        assertTrue(abs(MotionMath.rubberBand(1000f, 300f)) < 300f)
        assertEquals(0f, MotionMath.rubberBand(0f, 300f))
        assertEquals(300f, MotionMath.settleAnchor(120f, 3000f, floatArrayOf(0f, 300f)))
        assertEquals(0f, MotionMath.settleAnchor(120f, -50f, floatArrayOf(0f, 300f)))
    }
}

// ------------------------------------------------------------------------------------------------
// Think start smoothing
// ------------------------------------------------------------------------------------------------
class ThinkStartTest {
    @Test fun partialTagsAreHeldBack() {
        assertEquals(4, ThinkParseCache.partialTagSuffix("<thi"))
        assertEquals(1, ThinkParseCache.partialTagSuffix("abc<"))
        assertEquals(5, ThinkParseCache.partialTagSuffix("想法</thi"))
        assertEquals(0, ThinkParseCache.partialTagSuffix("<think>"))
        assertEquals(0, ThinkParseCache.partialTagSuffix("a<b"))
        val c = ThinkParseCache()
        // "<thi" must never show up as answer text while the tag is still arriving.
        listOf("<", "<th", "<thin", "<think>", "<think>\n", "<think>\n好").forEach { raw ->
            assertEquals("for '$raw'", "", c.parseStreaming(raw).answer)
        }
        assertTrue(c.parseStreaming("<think>\n好").thinkingOpen)
    }

    @Test fun slowStreamIsRevealedWithoutStopAndGo() {
        // ~12 chars/s arriving 2 chars every 1/6 s: the pacer should not catch up and stall.
        val p = TypewriterPacer()
        var target = 0
        var prev = 0
        var stalls = 0
        var now = 0f
        for (frame in 0 until 60 * 6) {
            now = frame / 60f
            if (frame % 10 == 0) target += 2
            val s = p.step(target, 1f / 60f, done = false, nowSec = now)
            if (frame > 120 && s == prev) stalls++
            prev = s
        }
        assertTrue("arrival measured ≈12/s, got ${p.arrival}", p.arrival in 9f..15f)
        // A stall frame is fine now and then (sub-character progress), long freezes are not.
        assertTrue("too many frozen frames: $stalls", stalls < 200)
        assertTrue("never runs ahead of the text", prev <= target)
    }

    @Test fun firstCharactersWaitForAShortPrebuffer() {
        val p = TypewriterPacer()
        assertEquals(0, p.step(1, 1f / 60f, done = false, nowSec = 0f))
        assertEquals(0, p.step(2, 1f / 60f, done = false, nowSec = 0.05f))
        var shown = 0
        var t = 0.05f
        repeat(30) { t += 1f / 60f; shown = p.step(6, 1f / 60f, done = false, nowSec = t) }
        assertTrue(shown > 0)
    }
}

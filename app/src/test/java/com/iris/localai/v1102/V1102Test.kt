package com.iris.localai.v1102

import com.iris.localai.data.AppSettings
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelOverrides
import com.iris.localai.data.TierProfile
import com.iris.localai.engine.Backend
import com.iris.localai.engine.CacheSnapshot
import com.iris.localai.engine.CacheUse
import com.iris.localai.engine.CpuTopology
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.LiteRtCacheFiles
import com.iris.localai.engine.LiteRtLoadKey
import com.iris.localai.engine.LiveResourcePolicy
import com.iris.localai.engine.LoadRecord
import com.iris.localai.engine.LoadTelemetry
import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PreloadDecision
import com.iris.localai.engine.PreloadPolicy
import com.iris.localai.engine.ReloadRules
import com.iris.localai.engine.ResourceBackendKind
import com.iris.localai.engine.ResourcePlanner
import com.iris.localai.engine.ResourceTier
import com.iris.localai.engine.TierApplyResult
import com.iris.localai.engine.TierProfiles
import com.iris.localai.engine.TierReloadPolicy
import com.iris.localai.ui.chat.ComposerAction
import com.iris.localai.ui.chat.ComposerActions
import com.iris.localai.ui.chat.ComposerPillStyle
import com.iris.localai.ui.chat.ComposerText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.10.2: A — composer (voice back on the right, 智能体 top-left); B — model load speed
 * (stable LiteRT-LM cache key, preload at start, no needless reloads, real load telemetry).
 */
class V1102Test {

    private val elite = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))

    private fun plan(t: ResourceTier, kind: ResourceBackendKind = ResourceBackendKind.GPU, stored: Map<String, TierProfile> = emptyMap()) =
        ResourcePlanner.plan(t, elite, 0, 0, kind, TierProfiles.resolve(stored, kind, t, elite), TierProfiles.widenPool(kind, elite, stored))

    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    private fun flat(s: String) = s.replace(Regex("\\s+"), " ")

    private val composer by lazy { source("src/main/java/com/iris/localai/ui/chat/Composer.kt") }
    private val composerBody by lazy { flat(composer.substringAfter("fun Composer(").substringBefore("private fun ListeningIndicator")) }

    // ================================================================ A. composer

    @Test fun agentPillIsTopLeftAboveTheTextField() {
        val b = composerBody
        val agent = b.indexOf("ComposerText.AGENT,")
        val field = b.indexOf("BasicTextField(")
        val edit = b.indexOf("\"编辑消息\"")
        assertTrue("agent pill exists", agent > 0)
        assertTrue("agent pill above the text field", agent < field)
        assertTrue("below the 编辑消息 row", edit in 1 until agent)
        // its own row, start-aligned (no weight / end arrangement before it)
        val row = b.substring(b.lastIndexOf("Row(", agent), agent)
        assertFalse(row.contains("Arrangement.End") || row.contains("Alignment.End"))
        assertTrue(b.substring(agent, b.indexOf(")", b.indexOf("compact", agent))).contains("compact = true"))
        assertTrue(b.substring(agent, field).contains("onClick = onToggleAgent"))
    }

    @Test fun thinkingAndSpeedStayInTheBottomRow() {
        val b = composerBody
        val field = b.indexOf("BasicTextField(")
        val think = b.indexOf("ComposerText.THINK,")
        val speed = b.indexOf("SpeedChip(state.resourceTier")
        // 2.2: the mic + round button became one voice capsule (see v220/V220ComposerTest)
        val action = b.indexOf("ComposerActions.of(")
        val mic = b.indexOf("VoiceCapsuleButton(")
        assertTrue(field in 1 until think)
        assertTrue(think < speed && speed < action && action < mic)
        // 智能体 + 技能 (2.2) + 思考 pills + one speed chip
        assertEquals(3, Regex("ComposerPill\\(").findAll(b).count())
        assertEquals(1, Regex("SpeedChip\\(").findAll(b).count())
    }

    @Test fun voiceIsBackOnTheRightAndThePlusMenuIsGone() {
        assertFalse(composer.contains("MoreButton"))
        assertFalse(composer.contains("DropdownMenuItem("))
        assertFalse(composerBody.contains("ComposerText.VOICE_INPUT"))
        assertFalse(composer.contains("Icons.Rounded.Add,"))
        // press-and-hold, as in 1.10.0: press → start, release → end.
        // 2.2: now the long-press of the one voice capsule (tap = 语音对话, as the round button was).
        val hold = flat(source("src/main/java/com/iris/localai/ui/chat/ComposerVoiceSkills.kt").substringAfter("internal fun VoiceCapsuleButton").substringBefore("// ---------"))
        assertTrue(hold.contains("awaitEachGesture"))
        val start = hold.indexOf("start()"); val wait = hold.indexOf("awaitPointerEvent()"); val end = hold.indexOf("end()", wait)
        assertTrue(start in 1 until wait && wait < end)
        assertEquals("按住说话", ComposerText.HOLD_TO_TALK)
        assertTrue(ComposerText.LISTENING.contains("松开结束"))
        assertTrue(composerBody.contains("onHoldStart = onHoldToTalkStart"))
        assertTrue(composerBody.contains("onHoldEnd = onHoldToTalkEnd"))
        assertTrue(composerBody.contains("onCall = onVoiceMode"))
        assertTrue(composerBody.contains("onStop = onStop"))
        assertTrue(composerBody.contains("onSend = onSend"))
        // ChatScreen still hands the same dictation start / end to the composer
        val screen = source("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("onHoldToTalkStart = {") && screen.contains("onHoldToTalkEnd = {"))
        assertTrue(screen.contains("vm.dictation.start()"))
        assertTrue(screen.contains("onVoiceMode = "))
    }

    @Test fun roundActionFollowsTheOldRules() {
        // 2.1.7: text while generating = 发送 (补充 / interrupt).
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = true, hasText = true))
        assertEquals(ComposerAction.STOP, ComposerActions.of(generating = true, hasText = false))
        assertEquals(ComposerAction.SEND, ComposerActions.of(generating = false, hasText = true))
        assertEquals(ComposerAction.VOICE, ComposerActions.of(generating = false, hasText = false))
    }

    @Test fun compactPillKeepsTheColoursButIsSmaller() {
        assertTrue(ComposerPillStyle.COMPACT_HEIGHT_DP < ComposerPillStyle.HEIGHT_DP)
        assertTrue(ComposerPillStyle.COMPACT_HEIGHT_DP >= 24f)
        val pill = flat(source("src/main/java/com/iris/localai/ui/chat/ComposerControls.kt").substringAfter("internal fun ComposerPill("))
        // colours come from the same style function for both sizes (dark + light)
        assertTrue(pill.contains("ComposerPillStyle.background(on, dark)") && pill.contains("ComposerPillStyle.content(on, dark)"))
        assertFalse(Regex("if \\(compact\\)[^,]*Color\\(").containsMatchIn(pill))
    }

    // ================================================================ B1. cache key stability

    @Test fun cacheIdentifierMatchesTheRuntime() {
        // file_util.cc: StrCat(<mtime whole seconds>, "_", <size>)
        assertEquals("1760000123_2570000000", LiteRtCacheFiles.identifier(1_760_000_123_999L, 2_570_000_000L))
        assertEquals("1760000123_5", LiteRtCacheFiles.identifier(1_760_000_123_000L, 5))
        assertEquals("a.litertlm_1760000123_5", LiteRtCacheFiles.currentPrefix("a.litertlm", 1_760_000_123_456L, 5))
        assertEquals(Backend.GPU, LiteRtCacheFiles.kindOf("x_1_2_mldrift_program_cache.bin"))
        assertEquals(Backend.GPU, LiteRtCacheFiles.kindOf("x_1_2_mldrift_weight_cache.bin"))
        assertEquals(Backend.CPU, LiteRtCacheFiles.kindOf("x_1_2.xnnpack_cache"))
        assertNull(LiteRtCacheFiles.kindOf(".migrated-from-cachedir"))
    }

    @Test fun snapshotSeparatesCurrentStaleAndOtherModels() {
        val f = "Agents-A1-4B.litertlm"
        val files = listOf(
            "${f}_100_7_mldrift_program_cache.bin" to 40L,
            "${f}_100_7_mldrift_weight_cache.bin" to 2000L,
            "${f}_100_7.xnnpack_cache" to 300L,
            "${f}_99_7_mldrift_program_cache.bin" to 30L,          // older file id → stale
            "Qwen3-0.6B.litertlm_5_5_mldrift_program_cache.bin" to 9L, // another model
            ".migrated-from-cachedir" to 1L,
        )
        val gpu = LiteRtCacheFiles.snapshot(files, f, 100_500L, 7L, Backend.GPU)
        assertEquals(CacheSnapshot(files = 4, bytes = 2370, currentFiles = 2, currentBytes = 2040, staleFiles = 1), gpu)
        val cpu = LiteRtCacheFiles.snapshot(files, f, 100_500L, 7L, Backend.CPU)
        assertEquals(1, cpu.currentFiles); assertEquals(300L, cpu.currentBytes)
        val empty = LiteRtCacheFiles.snapshot(emptyList(), f, 100_500L, 7L, Backend.GPU)
        assertEquals(CacheSnapshot(), empty)
    }

    @Test fun maxNumTokensDependsOnlyOnFileAndContextLength() {
        assertEquals(4096, LiteRtLoadKey.maxNumTokens(4096, npuFile = false, npuKv = null, npuDefault = 1024))
        assertEquals(8192, LiteRtLoadKey.maxNumTokens(8192, npuFile = false, npuKv = null, npuDefault = 1024))
        assertEquals(1280, LiteRtLoadKey.maxNumTokens(4096, npuFile = true, npuKv = 1280, npuDefault = 1024))
        assertEquals(1024, LiteRtLoadKey.maxNumTokens(4096, npuFile = true, npuKv = null, npuDefault = 1024))
        // the context length a load uses does not move with the tier / agent mode / thinking
        val base = AppSettings()
        val o = ModelOverrides()
        val ctx = EffectiveModelConfig.resolve(base, ModelFormat.LITERT, o).contextLength
        for (t in 0..2) for (think in listOf(true, false)) {
            val s = base.copy(resourceTier = t, deepThinking = think)
            assertEquals(ctx, EffectiveModelConfig.resolve(s, ModelFormat.LITERT, o).contextLength)
        }
        // only the user changes it (model settings or global 上下文长度)
        assertEquals(2048, EffectiveModelConfig.resolve(base, ModelFormat.LITERT, ModelOverrides(contextLength = 2048)).contextLength)
    }

    @Test fun engineUsesTheStableKeyAndAPersistentCache() {
        val lt = flat(source("src/full/java/com/iris/localai/engine/litert/LiteRtEngine.kt"))
        // 2.0: the NPU default is per family (NpuIds.defaultKv: Gemma 4 NPU 4096, unknown 1024), still a pure function of the file.
        assertTrue(lt.contains("maxTokens = com.iris.localai.engine.LiteRtLoadKey.maxNumTokens(config.contextLength, npuFile, npuKv, com.iris.localai.engine.NpuIds.defaultKv(model.fileName))"))
        // 2.1.2: NPU walks NpuRetry.kvAttempts(npuKv) (compiled size first, then 2048 / 1536 on an allocation failure).
        assertTrue(lt.contains("if (name == \"NPU\") com.iris.localai.engine.NpuRetry.kvAttempts(npuKv) else listOf(maxTokens)"))
        assertTrue(lt.contains("maxNumTokens = kv,"))
        assertTrue(lt.contains("java.io.File(context.filesDir, CACHE_DIR)"))
        assertTrue(lt.contains("const val CACHE_DIR = \"litert-cache\""))
        assertTrue(lt.contains("cacheDir = if (name == \"NPU\") null else cacheRoot.path"))
        // the old cacheDir migration runs once (marker), not on every load
        val mig = lt.substringAfter("private fun migrateOldCacheOnce").substringBefore("/** Backend for a model")
        assertTrue(mig.contains("if (marker.exists()) return") && mig.contains("marker.writeText"))
        assertTrue(mig.contains("LiteRtCacheFiles.kindOf("))
        val load = lt.substringAfter("override suspend fun load(").substringBefore("override fun generate(")
        assertFalse(load.contains("context.cacheDir.listFiles"))
        // nothing in the app deletes the LiteRT cache directory
        val roots = listOf("src/main/java", "src/full/java").map { r -> listOf(r, "app/$r", "../$r").map { File(it) }.first { it.exists() } }
        for (root in roots) root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val t = f.readText()
            if ("litert-cache" in t || "CACHE_DIR" in t) {
                assertFalse("${f.name} deletes the cache", Regex("(CACHE_DIR|litert-cache|cacheRoot)[^\\n]*(deleteRecursively|\\.delete\\(\\))").containsMatchIn(t))
            }
        }
        // EngineManager feeds the model's own 上下文长度, nothing else
        val em = source("src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(em.contains("contextLength = cfg.contextLength,"))
    }

    // ================================================================ B2. preload at start

    @Test fun preloadDecision() {
        assertEquals(PreloadDecision.LOAD, PreloadPolicy.decide(true, "a.litertlm", modelFound = true, engineIdle = true))
        assertEquals(PreloadDecision.DISABLED, PreloadPolicy.decide(false, "a.litertlm", modelFound = true, engineIdle = true))
        assertEquals(PreloadDecision.NO_LAST_MODEL, PreloadPolicy.decide(true, null, modelFound = false, engineIdle = true))
        assertEquals(PreloadDecision.NO_LAST_MODEL, PreloadPolicy.decide(true, " ", modelFound = false, engineIdle = true))
        assertEquals(PreloadDecision.MODEL_MISSING, PreloadPolicy.decide(true, "gone.litertlm", modelFound = false, engineIdle = true))
        assertEquals(PreloadDecision.ALREADY_LOADING_OR_LOADED, PreloadPolicy.decide(true, "a.litertlm", modelFound = true, engineIdle = false))
    }

    @Test fun preloadRunsAtProcessStartWithTheExistingSwitch() {
        // default on, same key as before (no settings key added or renamed)
        assertTrue(AppSettings().autoLoadLastModel)
        val json = Json { encodeDefaults = true }
        assertTrue(json.encodeToString(AppSettings.serializer(), AppSettings()).contains("\"autoLoadLastModel\":true"))
        val app = source("src/main/java/com/iris/localai/IrisApp.kt")
        assertTrue(app.contains("container.engine.preloadAtStart()"))
        val em = flat(source("src/main/java/com/iris/localai/engine/EngineManager.kt"))
        assertTrue(em.contains("PreloadPolicy.decide(s.autoLoadLastModel, s.lastModelFile, m != null, state.value is EngineState.Idle)"))
        assertTrue(em.contains("load(m, reason = \"启动预加载\")"))
        // ChatViewModel no longer starts a second load of its own
        val vm = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("c.models.find(s.lastModelFile)?.let { c.engine.load(it) }"))
        val ui = source("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        assertTrue(ui.contains("\"启动时预加载上次的模型\", s.autoLoadLastModel"))
    }

    // ================================================================ B3. no needless reloads

    private val model = LocalModel("Agents-A1-4B.litertlm", "/m/Agents-A1-4B.litertlm", 2_570_000_000L, ModelFormat.LITERT)
    private val details = ModelDetails("x", 0, 4096, 0, true, hasChatTemplate = true, backend = "GPU · LiteRT-LM", activeBackend = Backend.GPU)

    @Test fun sameModelIsNotReloaded() {
        assertTrue(ReloadRules.alreadyLoaded(EngineState.Ready(model, details), model.path))
        assertFalse(ReloadRules.alreadyLoaded(EngineState.Ready(model, details), "/m/other.litertlm"))
        assertFalse(ReloadRules.alreadyLoaded(EngineState.Idle, model.path))
        assertFalse(ReloadRules.alreadyLoaded(EngineState.Loading(model, 0.5f), model.path))
        assertFalse(ReloadRules.alreadyLoaded(EngineState.Failed(model, "x"), model.path))
        val em = flat(source("src/main/java/com/iris/localai/engine/EngineManager.kt"))
        assertTrue(em.contains("if (ReloadRules.alreadyLoaded(state.value, model.path)) {"))
    }

    @Test fun speedChangesNeverReloadLiteRtOnTheGpuOrNpu() {
        for (b in listOf(Backend.GPU, Backend.NPU)) for (from in ResourceTier.entries) for (to in ResourceTier.entries) {
            val d = TierReloadPolicy.decide(
                ModelFormat.LITERT, b, plan(from), plan(to), loadedPreferred = b, nextPreferred = b,
            )
            assertFalse("$b $from→$to: ${d.changed}", d.reload)
        }
        // a custom GPU profile (other cores / priority) does not reload either
        val custom = TierProfiles.withProfile(emptyMap(), ResourceBackendKind.GPU, ResourceTier.HIGH, TierProfile(1, listOf(6), 10, null), elite)
        assertFalse(TierReloadPolicy.decide(ModelFormat.LITERT, Backend.GPU, plan(ResourceTier.HIGH), plan(ResourceTier.HIGH, stored = custom)).reload)
        // a real backend change still reloads; LiteRT on the CPU keeps its thread / core reloads
        assertEquals(listOf("后端"), TierReloadPolicy.decide(ModelFormat.LITERT, Backend.GPU, plan(ResourceTier.HIGH), plan(ResourceTier.HIGH), Backend.GPU, Backend.CPU).changed)
        val cpuLow = plan(ResourceTier.LOW, ResourceBackendKind.CPU); val cpuHigh = plan(ResourceTier.HIGH, ResourceBackendKind.CPU)
        assertTrue(TierReloadPolicy.decide(ModelFormat.LITERT, Backend.CPU, cpuLow, cpuHigh).reload)
        assertTrue(ReloadRules.tierReloadsLiteRt(Backend.CPU))
        assertFalse(ReloadRules.tierReloadsLiteRt(Backend.GPU))
        assertFalse(ReloadRules.tierReloadsLiteRt(Backend.NPU))
    }

    @Test fun gpuSpeedChangeSaysNextLoad() {
        assertEquals("下次加载模型时生效", LiveResourcePolicy.statusText(TierApplyResult.NEXT_LOAD))
        val em = flat(source("src/main/java/com/iris/localai/engine/EngineManager.kt"))
        assertTrue(em.contains("!ReloadRules.tierReloadsLiteRt(st.details.activeBackend)) TierApplyResult.NEXT_LOAD else r"))
    }

    // ================================================================ B4. telemetry

    @Test fun classifyCacheUse() {
        val hit = CacheSnapshot(files = 2, bytes = 100, currentFiles = 2, currentBytes = 100)
        assertEquals(CacheUse.HIT, LoadTelemetry.classify(Backend.GPU, hit))
        assertEquals(CacheUse.MISS, LoadTelemetry.classify(Backend.GPU, CacheSnapshot()))
        assertEquals(CacheUse.MISS, LoadTelemetry.classify(Backend.GPU, CacheSnapshot(files = 1, bytes = 5, staleFiles = 1)))
        assertEquals(CacheUse.MISS, LoadTelemetry.classify(Backend.CPU, CacheSnapshot(currentFiles = 1, currentBytes = 0)))
        assertEquals(CacheUse.NONE, LoadTelemetry.classify(Backend.GPU, null))      // GGUF / MNN
        assertEquals(CacheUse.NONE, LoadTelemetry.classify(Backend.NPU, hit))       // NPU: AOT, no cache
    }

    @Test fun summaryAndElapsedTexts() {
        fun r(ms: Long, c: CacheUse, ok: Boolean = true) = LoadRecord("m", "GPU", ms, c, ok = ok)
        assertEquals("上次加载 12 秒 · 已用缓存", LoadTelemetry.summary(r(12_400, CacheUse.HIT)))
        assertEquals("上次加载 48 秒 · 首次编译", LoadTelemetry.summary(r(47_600, CacheUse.MISS)))
        assertEquals("上次加载 3 秒", LoadTelemetry.summary(r(3_000, CacheUse.NONE)))
        assertEquals("上次加载 1 秒 · 已用缓存", LoadTelemetry.summary(r(200, CacheUse.HIT)))
        assertEquals("已用 7 秒", LoadTelemetry.elapsed(7_900))
        assertEquals(1L, LoadTelemetry.seconds(0))
        assertEquals(40L, LoadTelemetry.seconds(40_499))
        assertEquals(41L, LoadTelemetry.seconds(40_500))
    }

    @Test fun logLineHasEverythingForAdb() {
        val rec = LoadRecord(
            "Agents-A1-4B.litertlm", "GPU", 41_234, CacheUse.MISS,
            before = CacheSnapshot(1, 30L * 1_048_576, 0, 0, 1),
            after = CacheSnapshot(2, 2048L * 1_048_576, 2, 2048L * 1_048_576, 0),
            maxNumTokens = 4096, reason = "启动预加载",
        )
        val line = LoadTelemetry.logLine("Agents-A1-4B.litertlm", rec, 10L * 1_073_741_824)
        for (part in listOf("load OK", "Agents-A1-4B.litertlm", "backend=GPU", "41234ms", "cache=MISS", "maxNumTokens=4096",
            "reason=启动预加载", "before=[1 files 30.0MB", "stale 1", "after=[2 files 2048.0MB", "free=10240.0MB")) {
            assertTrue("$part in $line", line.contains(part))
        }
        assertEquals("IrisLoad", LoadTelemetry.TAG)
        assertTrue(LoadTelemetry.logLine("x", rec.copy(ok = false)).startsWith("load FAILED"))
    }

    @Test fun recordsRoundTripAndLatestWins() {
        val j = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val a = LoadRecord("m", "GPU", 40_000, CacheUse.MISS, before = CacheSnapshot(), after = CacheSnapshot(2, 9, 2, 9, 0), maxNumTokens = 4096, at = 1)
        assertEquals(a, j.decodeFromString(LoadRecord.serializer(), j.encodeToString(LoadRecord.serializer(), a)))
        val b = a.copy(backend = "CPU", ms = 9_000, cache = CacheUse.HIT, at = 2)
        val other = a.copy(modelId = "n", at = 3)
        assertEquals(b, LoadTelemetry.latest(listOf(a, b, other), "m"))
        assertNull(LoadTelemetry.latest(listOf(other), "m"))
        assertEquals("m|GPU", LoadTelemetry.key("m", "GPU"))
    }

    @Test fun telemetryIsWiredToEngineAndSheet() {
        val em = flat(source("src/main/java/com/iris/localai/engine/EngineManager.kt"))
        assertTrue(em.contains("recordLoad(model, backend, System.currentTimeMillis() - loadStart, details, ok = true, reason = reason)"))
        assertTrue(em.contains("Log.i(LoadTelemetry.TAG, LoadTelemetry.logLine(model.fileName, r, free))"))
        val lt = flat(source("src/full/java/com/iris/localai/engine/litert/LiteRtEngine.kt"))
        assertTrue(lt.contains("cacheBefore = cacheBefore, cacheAfter = snapshot(),"))
        val sheet = source("src/main/java/com/iris/localai/ui/chat/ModelPickerSheet.kt")
        assertTrue(sheet.contains("extra = lastLoad(m)"))
        assertTrue(sheet.contains("正在加载 \${l.model.displayName} · \$secs 秒")) // elapsed seconds while loading
        assertTrue(sheet.contains("IrisProgressBar(")) // bar kept
        val screen = source("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("LoadTelemetry.latest(loadRecords.values, m.id)?.let(com.iris.localai.engine.LoadTelemetry::summary)"))
        // a separate prefs file — existing settings untouched
        assertTrue(source("src/main/java/com/iris/localai/engine/load/LoadStatsStore.kt").contains("const val PREFS = \"iris_load_stats\""))
    }

    @Test fun versionBumped() {
        val gradle = source("build.gradle.kts")
        assertTrue(Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toInt() >= 142)
        val v = Regex("versionName = \"(\\d+)\\.(\\d+)\\.(\\d+)").find(gradle)!!.groupValues.drop(1).map { it.toInt() }
        assertTrue(v[0] > 1 || v[1] > 10 || (v[1] == 10 && v[2] >= 2))
    }
}

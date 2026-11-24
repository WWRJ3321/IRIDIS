package com.iris.localai

import com.iris.localai.agent.core.AgentContainer
import com.iris.localai.agent.gui.android.GuiAgentRunner
import com.iris.localai.agent.tools.ChatModelYield
import com.iris.localai.agent.tools.GuiTaskBackend
import com.iris.localai.agent.workspace.StorageLayout
import com.iris.localai.agent.workspace.WorkspaceLocation
import com.iris.localai.character.CharacterStore
import com.iris.localai.data.Conversation
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.DownloadSources
import com.iris.localai.data.MnnHub
import com.iris.localai.data.ModelAliases
import com.iris.localai.data.ModelConfigRepository
import com.iris.localai.data.ModelStorage
import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.NpuIds
import com.iris.localai.engine.load.LoadKeepAlivePolicy
import com.iris.localai.engine.load.LoadTelemetry
import com.iris.localai.engine.resources.CoolDown
import com.iris.localai.engine.resources.ThermalCutoff
import com.iris.localai.engine.resources.ThermalGovernor
import com.iris.localai.imagegen.ImageGenDownloader
import com.iris.localai.imagegen.ImageGenEngines
import com.iris.localai.imagegen.ImageGenIpc
import com.iris.localai.imagegen.ImportedImages
import com.iris.localai.knowledge.KbEmbedModels
import com.iris.localai.knowledge.KbSemantic
import com.iris.localai.knowledge.KbVecStore
import com.iris.localai.memory.HistoryRecords
import com.iris.localai.service.DownloadNotifier
import com.iris.localai.service.LoadKeepAliveService
import com.iris.localai.translate.TranslatePackStore
import com.iris.localai.translate.Translator
import com.iris.localai.util.HintLog
import com.iris.localai.util.diagnostics.CrashReporter
import com.iris.localai.util.io.Trees
import com.iris.localai.voice.CloneVoiceStore
import com.iris.localai.voice.CustomTtsStore
import android.app.Application
import com.iris.localai.data.ConversationRepository
import com.iris.localai.data.ModelRepository
import com.iris.localai.data.SettingsRepository
import com.iris.localai.engine.EngineManager
import com.iris.localai.voice.SpeechRecognizer
import com.iris.localai.voice.SpeechSynthesizer
import com.iris.localai.voice.VoiceModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency container: every long-lived object is created exactly once here, so the
 * object graph is visible in one place and trivially replaceable in tests.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val models = ModelRepository(app)
    /** Whole-repository MNN model downloads (taobao-mnn) from Hugging Face / hf-mirror. */
    val mnnHub = MnnHub(models)
    /** 生图 model downloads (app scope, survive leaving the 生图 page). */
    val imageGenDownloader = ImageGenDownloader(scope)
    val conversations = ConversationRepository(File(app.filesDir, "conversations"))
    /** per-model backend + parameters (null fields follow the global defaults). */
    val modelConfigs = ModelConfigRepository(app)
    val engine = EngineManager(app, scope, settings, models, modelConfigs)
    /** 智能体 settings, the app-private workspace (filesDir/workspace) and its undo history. */
    val agent = AgentContainer(app)
    /** model downloads and imports follow the storage location (Download/IRIDIS when 工作区位置 is 下载). */
    init {
        ModelStorage.storageRoot = { agent.appRoot }
        ModelStorage.requestedShared = { agent.settings.current.workspaceLocation != WorkspaceLocation.PRIVATE }
    }
    /** 知识库向量检索: embedding models live in the model folders; vectors beside the index (filesDir/knowledge). */
    init { KbEmbedModels.dirs = { models.location().listDirs }; agent.knowledge.semantic = KbSemantic(app, agent.knowledge, File(app.filesDir, "knowledge/" + KbVecStore.FILE), scope) }
    /** GUI 智能体（形态 A，无障碍）第一阶段的运行器。 */
    val gui by lazy { GuiAgentRunner(app, engine, scope) }
    /** the chat's `screen` tool runs the GUI agent (created on first use). */
    init { agent.gui = object : GuiTaskBackend {
        override fun blocker() = gui.blocker()
        override suspend fun run(task: String, progress: (String) -> Unit) = gui.run(task, progress)
    } }
    /** generate_image may unload the chat model for a large image model and load it back after. */
    init { agent.chatYield = object : ChatModelYield {
        override suspend fun yieldModel(): (suspend () -> Unit)? {
            val m = engine.readyModel ?: return null
            engine.unload("为生图让出内存")
            return { engine.load(m, reason = "生图完成，重新加载") }
        }
    } }
    val voiceStore = VoiceModelStore(app)
    /** 角色卡: Download/IRIDIS/characters (or filesDir/iridis/characters). */
    val characters = CharacterStore({ agent.appRoot }, File(app.filesDir, "iridis/characters"))
    /** 本地翻译. */
    val translator by lazy { Translator(engine, settings, TranslatePackStore(app), scope) }
    val recognizer = SpeechRecognizer(app, voiceStore, settings)
    val cloneVoices = CloneVoiceStore(app)
    /** imported sherpa-onnx TTS models (设置 › 语音 › 导入). */
    val customTts = CustomTtsStore(app, voiceStore)
    val synthesizer = SpeechSynthesizer(voiceStore, settings, cloneVoices, app, customTts)
}

class IrisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        I18n.init(this) // 2.3.1
        // 3.0.0：悬浮窗识屏 / 问 AI 的默认提示词跟随界面语言
        com.iris.localai.prompts.Prompts.localized = { k ->
            when (k) {
                com.iris.localai.prompts.PromptKey.FLOAT_SCREEN -> I18n.s(R.string.b30_prompt_float_screen)
                com.iris.localai.prompts.PromptKey.FLOAT_ASK -> I18n.s(R.string.b30_prompt_float_ask)
                else -> null
            }
        }
        ModelAliases.init(this) // 模型自定义名，先于模型列表
        ImportedImages.init(filesDir) // 导入的生图模型（:imagegen 进程也要）
        // 2.7.1：生图低内存模式的默认值按本机内存（低于约 9.5 GB 默认开）
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            com.iris.localai.imagegen.ImageLowMem.autoOn = com.iris.localai.imagegen.ImageLowMem.defaultOn(android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem)
        }
        // the tiny restart process (AppRestart) needs none of the app.
        if (android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":restart")) return
        CrashReporter.install(this)
        // chip-specific catalog entries (Dimensity NPU builds) are listed only on their chip.
        RecommendedCatalog.deviceSoc = NpuIds.baseSocId(android.os.Build.SOC_MODEL)
        // remove the leftover rootfs of a removed feature once, never following links.
        java.io.File(filesDir, "alpine").takeIf { it.exists() }?.let { dir ->
            Thread({ runCatching { Trees.deleteNoFollow(dir.toPath()) } }, "iris-cleanup").start()
        }
        // ggml's OpenCL backend caches compiled Adreno kernels here, so only the first GPU load compiles.
        runCatching {
            val dir = java.io.File(cacheDir, "opencl").apply { mkdirs() }
            android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", dir.path, true)
        }
        // the separate 生图 process (ImageGenService) needs only the OpenCL cache above.
        if (ImageGenIpc.isImageGenProcess()) return
        container = AppContainer(this)
        DownloadNotifier.start(this, container) // progress notification for every model download
        com.iris.localai.task.LoadIsland.start(container.scope, container.engine) // Beta2：加载模型也上灵动岛
        // 无障碍保活 (2.2.14) removed; GuiAgentRunner still calls IrisAccessibilityService.selfEnable before a task (2.1.9).
        // 「启动时预加载上次的模型」 — start loading the last model in the background right away,
        // while the UI is still coming up (PreloadPolicy decides; same lock as every other load).
        // only when the UI actually comes up — a boot broadcast, an alarm or the API service
        // starting the process no longer maps a multi-GB model in the background.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                container.scope.launch {
                    runCatching { container.engine.preloadAtStart() }
                        .onFailure { android.util.Log.w(LoadTelemetry.TAG, "preload failed", it) }
                }
            }
        })
        // keep the process alive (foreground service) while a LiteRT model compiles for the GPU / NPU,
        // so a backgrounded app is not frozen / killed halfway (that leaves the GPU cache unfinished).
        container.scope.launch {
            var keeping = false
            container.engine.state.collect { s ->
                val want = LoadKeepAlivePolicy.keepAlive(s)
                if (want && !keeping) LoadKeepAliveService.start(this@IrisApp)
                keeping = want
            }
        }
        // 温度限速 — battery temperature (°C) and the user's limit feed ThermalGovernor.
        runCatching {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    val t = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (t != Int.MIN_VALUE) ThermalGovernor.onTemperature(t / 10f)
                    if (t != Int.MIN_VALUE) checkThermalCutoff(t / 10f)
                }
            }
            registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { r.onReceive(this, it) }
        }
        CoolDown.poll = {
            runCatching { registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) }
                .getOrNull()?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f } ?: Float.NaN
        }
        // remember the download source that last worked (自动 tries it first).
        DownloadSources.lastGood = DownloadSource.byName(container.settings.current.lastGoodSource)
        DownloadSources.onLastGood = { src -> container.settings.update { it.copy(lastGoodSource = src.name) } }
        container.scope.launch {
            container.settings.settings.collect {
                ThermalGovernor.setLimit(it.thermalLimitC)
                ThermalCutoff.setCutoff(it.thermalCutoffC)
                CoolDown.setLimit(it.coolPauseC, it.coolStopNow)
            }
        }
        // 终止温度: battery broadcasts can be sparse, so also poll (cheap: sticky intent +
        // thermal status / headroom) every 8 s, only while the cutoff is on and something is loaded.
        container.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(8_000)
                if (ThermalCutoff.cutoffC > 0 && thermalActive()) {
                    val t = runCatching {
                        registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    }.getOrNull()
                    checkThermalCutoff(if (t == null || t == Int.MIN_VALUE) Float.NaN else t / 10f)
                }
            }
        }
        // removed the per-level speed estimates; drop their old store.
        runCatching { deleteSharedPreferences("speed_stats") }
        container.scope.launch {
            container.conversations.init()
            container.models.refresh()
        }
        // the agent workspace moved to Download/IRISLocalAI — copy the 1.9.0 private files once.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.agent.migrateIfNeeded() }.onFailure { android.util.Log.w("IrisWorkspace", "migration failed", it) }.getOrNull()?.let { r ->
                container.agent.workspaceNote.value =
                    if (r.ok) "已把 ${r.copied + r.skipped} 个工作区文件迁移到 ${container.agent.workspace.value.root.path}" + (if (r.renamed > 0) "（${r.renamed} 个重名文件加了「（私有目录）」后缀）" else "")
                    else "工作区迁移未完成：${r.failed} 个文件复制失败，原文件仍保留在应用私有目录，下次启动会再试"
            }
        }
        // package com.iridis — move Download/IRISLocalAI to Download/IRIDIS once, drop a stale SAF grant.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.agent.clearStaleTreeGrant() }.onFailure { android.util.Log.w("IrisWorkspace", "tree grant cleanup failed", it) }
            runCatching { container.agent.migrateLegacyFolder() }.onFailure { android.util.Log.w("IrisWorkspace", "legacy folder migration failed", it) }.getOrNull()?.let { r ->
                if (r.copied + r.skipped > 0) container.agent.workspaceNote.value =
                    if (r.ok) "已把旧文件夹 ${com.iris.localai.agent.workspace.WorkspaceResolver.LEGACY_FOLDER} 的 ${r.copied + r.skipped} 个文件迁移到 ${container.agent.workspace.value.root.path}"
                    else "旧文件夹迁移未完成：${r.failed} 个文件复制失败，原文件仍保留"
            }
            // Download/IRIDIS is the resource root now; its old user files move into workspace/ once.
            runCatching { container.agent.migrateLayout() }.onFailure { android.util.Log.w("IrisWorkspace", "layout migration failed", it) }.getOrNull()
                ?.let { r -> StorageLayout.notice(r)?.let { container.agent.workspaceNote.value = it } }
        }
        // the raw history index (recall) follows every save; once, it indexes the saved conversations.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val store = container.agent.history ?: return@launch
            container.conversations.listener = object : ConversationRepository.Listener {
                override fun onSaved(conversation: Conversation) =
                    store.syncConversation(conversation.id, HistoryRecords.of(conversation))
                override fun onDeleted(id: String) = store.deleteConversation(id)
                override fun onDeletedAll() = store.deleteAll()
            }
            container.conversations.init()
            if (store.needsBackfill) {
                runCatching {
                    container.conversations.forEachSaved { conv -> store.syncConversation(conv.id, HistoryRecords.of(conv)) }
                    store.markBackfilled()
                }.onFailure { android.util.Log.w("IrisHistory", "backfill failed", it) }
            }
        }
    }

    /** 终止温度: a model is loaded, a reply / GUI task / image is generating. */
    private fun thermalActive(): Boolean =
        container.engine.state.value !is EngineState.Idle ||
            container.engine.readyModel != null ||
            ImageGenEngines.get(this).busy

    private fun checkThermalCutoff(tempC: Float) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val status = runCatching { pm?.currentThermalStatus ?: 0 }.getOrDefault(0)
        val headroom = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { pm?.getThermalHeadroom(10) ?: Float.NaN }.getOrDefault(Float.NaN) else Float.NaN
        if (!ThermalCutoff.check(tempC, status, headroom, thermalActive())) return
        // an MNN prefill cannot be interrupted, so the unload waits for it — say so.
        val waiting = runCatching { container.engine.busyNow }.getOrDefault(false)
        val msg = ThermalCutoff.notice(tempC) + (if (waiting) "，当前计算结束后完成卸载" else "")
        android.util.Log.w("IrisThermal", msg)
        container.scope.launch {
            // Stop everything first (GUI agent, every engine generation, image gen), then unload.
            runCatching { container.gui.stop() }
            runCatching { container.engine.cancelAll() }
            val img = ImageGenEngines.get(this@IrisApp)
            runCatching { img.cancel() }
            runCatching { container.engine.unload("温度过高") }
            runCatching { container.recognizer.release() }
            runCatching { container.synthesizer.release() }
            runCatching { container.translator.release() }   // the offline translation model too
            launch(Dispatchers.IO) { runCatching { img.release() } }
        }
        HintLog.add(msg, HintLog.Source.SYSTEM)
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return // :imagegen / :restart processes have no container
        // The system is under real memory pressure while we are in the background: release the
        // speech models first (cheap to reload); the LLM itself follows the idle-unload policy.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            container.scope.launch {
                container.recognizer.release()
                container.synthesizer.release()
                container.translator.release()
            }
        }
    }
}

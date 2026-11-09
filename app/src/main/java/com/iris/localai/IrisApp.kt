package com.iris.localai

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
    val mnnHub = com.iris.localai.data.MnnHub(models)
    /** 2.1.7: 生图 model downloads (app scope, survive leaving the 生图 page). */
    val imageGenDownloader = com.iris.localai.imagegen.ImageGenDownloader(scope)
    val conversations = ConversationRepository(File(app.filesDir, "conversations"))
    /** 1.7.1: per-model backend + parameters (null fields follow the global defaults). */
    val modelConfigs = com.iris.localai.data.ModelConfigRepository(app)
    val engine = EngineManager(app, scope, settings, models, modelConfigs)
    /** 1.9.0: 智能体 settings, the app-private workspace (filesDir/workspace) and its undo history. */
    val agent = com.iris.localai.agent.AgentContainer(app)
    /** 2.2: GUI 智能体（形态 A，无障碍）第一阶段的运行器。 */
    val gui by lazy { com.iris.localai.agent.gui.android.GuiAgentRunner(app, engine, scope) }
    /** 2.1.9: the chat's `screen` tool runs the GUI agent (created on first use). */
    init { agent.gui = object : com.iris.localai.agent.GuiTaskBackend {
        override fun blocker() = gui.blocker()
        override suspend fun run(task: String, progress: (String) -> Unit) = gui.run(task, progress)
    } }
    val voiceStore = VoiceModelStore(app)
    /** 2.3.2 角色卡: Download/IRIDIS/characters (or filesDir/iridis/characters). */
    val characters = com.iris.localai.character.CharacterStore({ agent.appRoot }, File(app.filesDir, "iridis/characters"))
    /** 2.3.0 ③ 本地翻译. */
    val translator by lazy { com.iris.localai.translate.Translator(engine, settings, com.iris.localai.translate.TranslatePackStore(app), scope) }
    val recognizer = SpeechRecognizer(app, voiceStore, settings)
    val cloneVoices = com.iris.localai.voice.CloneVoiceStore(app)
    /** 2.4.5: imported sherpa-onnx TTS models (设置 › 语音 › 导入). */
    val customTts = com.iris.localai.voice.CustomTtsStore(app, voiceStore)
    val synthesizer = SpeechSynthesizer(voiceStore, settings, cloneVoices, app, customTts)
}

class IrisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.iris.localai.I18n.init(this) // 2.3.1
        // 2.2.14: the tiny restart process (AppRestart) needs none of the app.
        if (android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":restart")) return
        com.iris.localai.util.CrashReporter.install(this)
        // 2.4.4: chip-specific catalog entries (Dimensity NPU builds) are listed only on their chip.
        com.iris.localai.data.RecommendedCatalog.deviceSoc = com.iris.localai.engine.NpuIds.baseSocId(android.os.Build.SOC_MODEL)
        // 2.2.2: remove the leftover rootfs of a removed feature once, never following links.
        java.io.File(filesDir, "alpine").takeIf { it.exists() }?.let { dir ->
            Thread({ runCatching { com.iris.localai.util.io.Trees.deleteNoFollow(dir.toPath()) } }, "iris-cleanup").start()
        }
        // ggml's OpenCL backend caches compiled Adreno kernels here, so only the first GPU load compiles.
        runCatching {
            val dir = java.io.File(cacheDir, "opencl").apply { mkdirs() }
            android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", dir.path, true)
        }
        // 2.4.6: the separate 生图 process (ImageGenService) needs only the OpenCL cache above.
        if (com.iris.localai.imagegen.ImageGenIpc.isImageGenProcess()) return
        container = AppContainer(this)
        com.iris.localai.service.DownloadNotifier.start(this, container) // 2.4.4: progress notification for every model download
        // 2.5.0 Beta5: 无障碍保活 (2.2.14) removed; GuiAgentRunner still calls IrisAccessibilityService.selfEnable before a task (2.1.9).
        // 1.10.2: 「启动时预加载上次的模型」 — start loading the last model in the background right away,
        // while the UI is still coming up (PreloadPolicy decides; same lock as every other load).
        // 1.13.0: only when the UI actually comes up — a boot broadcast, an alarm or the API service
        // starting the process no longer maps a multi-GB model in the background.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                container.scope.launch {
                    runCatching { container.engine.preloadAtStart() }
                        .onFailure { android.util.Log.w(com.iris.localai.engine.LoadTelemetry.TAG, "preload failed", it) }
                }
            }
        })
        // 1.10.3: keep the process alive (foreground service) while a LiteRT model compiles for the GPU / NPU,
        // so a backgrounded app is not frozen / killed halfway (that leaves the GPU cache unfinished).
        container.scope.launch {
            var keeping = false
            container.engine.state.collect { s ->
                val want = com.iris.localai.engine.LoadKeepAlivePolicy.keepAlive(s)
                if (want && !keeping) com.iris.localai.service.LoadKeepAliveService.start(this@IrisApp)
                keeping = want
            }
        }
        // 2.0.1: 温度限速 — battery temperature (°C) and the user's limit feed ThermalGovernor.
        runCatching {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    val t = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (t != Int.MIN_VALUE) com.iris.localai.engine.ThermalGovernor.onTemperature(t / 10f)
                    if (t != Int.MIN_VALUE) checkThermalCutoff(t / 10f)
                }
            }
            registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { r.onReceive(this, it) }
        }
        // 2.2.16: remember the download source that last worked (自动 tries it first).
        com.iris.localai.data.DownloadSources.lastGood = com.iris.localai.data.DownloadSource.byName(container.settings.current.lastGoodSource)
        com.iris.localai.data.DownloadSources.onLastGood = { src -> container.settings.update { it.copy(lastGoodSource = src.name) } }
        container.scope.launch {
            container.settings.settings.collect {
                com.iris.localai.engine.ThermalGovernor.setLimit(it.thermalLimitC)
                com.iris.localai.engine.ThermalCutoff.setCutoff(it.thermalCutoffC)
            }
        }
        // 2.2.10 终止温度: battery broadcasts can be sparse, so also poll (cheap: sticky intent +
        // thermal status / headroom) every 8 s, only while the cutoff is on and something is loaded.
        container.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(8_000)
                if (com.iris.localai.engine.ThermalCutoff.cutoffC > 0 && thermalActive()) {
                    val t = runCatching {
                        registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    }.getOrNull()
                    checkThermalCutoff(if (t == null || t == Int.MIN_VALUE) Float.NaN else t / 10f)
                }
            }
        }
        // 1.6.0 removed the per-level speed estimates; drop their old store.
        runCatching { deleteSharedPreferences("speed_stats") }
        container.scope.launch {
            container.conversations.init()
            container.models.refresh()
        }
        // 1.10.0: the agent workspace moved to Download/IRISLocalAI — copy the 1.9.0 private files once.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.agent.migrateIfNeeded() }.onFailure { android.util.Log.w("IrisWorkspace", "migration failed", it) }.getOrNull()?.let { r ->
                container.agent.workspaceNote.value =
                    if (r.ok) "已把 ${r.copied + r.skipped} 个工作区文件迁移到 ${container.agent.workspace.value.root.path}" + (if (r.renamed > 0) "（${r.renamed} 个重名文件加了「（私有目录）」后缀）" else "")
                    else "工作区迁移未完成：${r.failed} 个文件复制失败，原文件仍保留在应用私有目录，下次启动会再试"
            }
        }
        // 2.1.0: package com.iridis — move Download/IRISLocalAI to Download/IRIDIS once, drop a stale SAF grant.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.agent.clearStaleTreeGrant() }.onFailure { android.util.Log.w("IrisWorkspace", "tree grant cleanup failed", it) }
            runCatching { container.agent.migrateLegacyFolder() }.onFailure { android.util.Log.w("IrisWorkspace", "legacy folder migration failed", it) }.getOrNull()?.let { r ->
                if (r.copied + r.skipped > 0) container.agent.workspaceNote.value =
                    if (r.ok) "已把旧文件夹 ${com.iris.localai.agent.WorkspaceResolver.LEGACY_FOLDER} 的 ${r.copied + r.skipped} 个文件迁移到 ${container.agent.workspace.value.root.path}"
                    else "旧文件夹迁移未完成：${r.failed} 个文件复制失败，原文件仍保留"
            }
            // 2.1.1: Download/IRIDIS is the resource root now; its old user files move into workspace/ once.
            runCatching { container.agent.migrateLayout() }.onFailure { android.util.Log.w("IrisWorkspace", "layout migration failed", it) }.getOrNull()
                ?.let { r -> com.iris.localai.agent.StorageLayout.notice(r)?.let { container.agent.workspaceNote.value = it } }
        }
        // 1.10.0: the raw history index (recall) follows every save; once, it indexes the saved conversations.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val store = container.agent.history ?: return@launch
            container.conversations.listener = object : ConversationRepository.Listener {
                override fun onSaved(conversation: com.iris.localai.data.Conversation) =
                    store.syncConversation(conversation.id, com.iris.localai.memory.HistoryRecords.of(conversation))
                override fun onDeleted(id: String) = store.deleteConversation(id)
                override fun onDeletedAll() = store.deleteAll()
            }
            container.conversations.init()
            if (store.needsBackfill) {
                runCatching {
                    container.conversations.forEachSaved { conv -> store.syncConversation(conv.id, com.iris.localai.memory.HistoryRecords.of(conv)) }
                    store.markBackfilled()
                }.onFailure { android.util.Log.w("IrisHistory", "backfill failed", it) }
            }
        }
    }

    /** 2.2.10 终止温度: a model is loaded, a reply / GUI task / image is generating. */
    private fun thermalActive(): Boolean =
        container.engine.state.value !is com.iris.localai.engine.EngineState.Idle ||
            container.engine.readyModel != null ||
            com.iris.localai.imagegen.ImageGenEngines.get(this).busy

    private fun checkThermalCutoff(tempC: Float) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val status = runCatching { pm?.currentThermalStatus ?: 0 }.getOrDefault(0)
        val headroom = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { pm?.getThermalHeadroom(10) ?: Float.NaN }.getOrDefault(Float.NaN) else Float.NaN
        if (!com.iris.localai.engine.ThermalCutoff.check(tempC, status, headroom, thermalActive())) return
        // 2.4.2: an MNN prefill cannot be interrupted, so the unload waits for it — say so.
        val waiting = runCatching { container.engine.busyNow }.getOrDefault(false)
        val msg = com.iris.localai.engine.ThermalCutoff.notice(tempC) + (if (waiting) "，当前计算结束后完成卸载" else "")
        android.util.Log.w("IrisThermal", msg)
        container.scope.launch {
            // Stop everything first (GUI agent, every engine generation, image gen), then unload.
            runCatching { container.gui.stop() }
            runCatching { container.engine.cancelAll() }
            val img = com.iris.localai.imagegen.ImageGenEngines.get(this@IrisApp)
            runCatching { img.cancel() }
            runCatching { container.engine.unload("温度过高") }
            runCatching { container.recognizer.release() }
            runCatching { container.synthesizer.release() }
            runCatching { container.translator.release() }   // 2.4.2: the offline translation model too
            launch(Dispatchers.IO) { runCatching { img.release() } }
        }
        com.iris.localai.util.HintLog.add(msg, com.iris.localai.util.HintLog.Source.SYSTEM)
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return // 2.4.6: :imagegen / :restart processes have no container
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

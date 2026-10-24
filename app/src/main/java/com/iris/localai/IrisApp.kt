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
    val voiceStore = VoiceModelStore(app)
    val recognizer = SpeechRecognizer(app, voiceStore, settings)
    val cloneVoices = com.iris.localai.voice.CloneVoiceStore(app)
    val synthesizer = SpeechSynthesizer(voiceStore, settings, cloneVoices)
}

class IrisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.iris.localai.util.CrashReporter.install(this)
        // ggml's OpenCL backend caches compiled Adreno kernels here, so only the first GPU load compiles.
        runCatching {
            val dir = java.io.File(cacheDir, "opencl").apply { mkdirs() }
            android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", dir.path, true)
        }
        container = AppContainer(this)
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
                }
            }
            registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { r.onReceive(this, it) }
        }
        container.scope.launch {
            container.settings.settings.collect { com.iris.localai.engine.ThermalGovernor.setLimit(it.thermalLimitC) }
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The system is under real memory pressure while we are in the background: release the
        // speech models first (cheap to reload); the LLM itself follows the idle-unload policy.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            container.scope.launch {
                container.recognizer.release()
                container.synthesizer.release()
            }
        }
    }
}

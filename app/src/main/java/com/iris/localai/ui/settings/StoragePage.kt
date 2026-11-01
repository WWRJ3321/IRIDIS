package com.iris.localai.ui.settings

import com.iris.localai.ui.theme.IrisChartColors
import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.IrisApp
import com.iris.localai.data.Format
import com.iris.localai.data.LocalModel
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.ModelCache
import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.models.ModelDeleteDialog
import com.iris.localai.ui.models.ModelsViewModel
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.util.StorageUsage
import com.iris.localai.util.StorageUsage.Category
import com.iris.localai.util.StorageUsage.Ids
import com.iris.localai.voice.PackState
import com.iris.localai.voice.VoicePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 1.14.3 设置 › 存储空间. Sizes come from [StorageUsage.scan] on Dispatchers.IO; every delete goes
 * through the code that already owns it (ModelsViewModel.delete + its dialog, ModelCache.clear,
 * SettingsViewModel.deletePack, ConversationRepository.deleteAll, UndoStore.clear).
 */
class StorageViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IrisApp).container
    private val ctx = app

    private val _report = MutableStateFlow<StorageUsage.Report?>(null)
    val report: StateFlow<StorageUsage.Report?> = _report.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _free = MutableStateFlow<Long?>(null)
    val free: StateFlow<Long?> = _free.asStateFlow()
    /** Result of the last clear / delete ("已释放 …"). */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    val engine = c.engine.state
    val models = c.models.models
    val conversations = c.conversations.summaries
    private val scanLock = Mutex()

    /** Where each category lives — the same constants the owning code uses. */
    private fun paths(): StorageUsage.Paths {
        val files = ctx.filesDir
        val ext = ctx.getExternalFilesDir(null)
        val extCache = ctx.externalCacheDir
        val db = ctx.getDatabasePath("iris_history.db")
        return StorageUsage.Paths(
            roots = listOfNotNull(com.iris.localai.util.Backup.dataDir(ctx), ext, extCache),
            modelsDir = c.models.privateDir,
            sharedModelsDir = c.models.location().sharedDir?.takeIf { it.isDirectory },
            litertCache = File(files, ModelCache.LITERT_DIR),
            mnnCache = File(ctx.cacheDir, "mnn"),
            openclCache = File(ctx.cacheDir, "opencl"),           // IrisApp: GGML_OPENCL_KERNEL_CACHE_DIR
            npuRuntime = File(files, "npu"),                      // QualcommNpuRuntime.dir (full)
            voiceRoot = c.voiceStore.root,                        // 2.1.1: Download/IRIDIS/voice with 全部文件访问
            voiceClones = File(files, "voice-clones"),            // CloneVoiceStore.root
            workspace = c.agent.workspace.value.root,             // 2.1.1: Download/IRIDIS/workspace
            skillsDir = File(c.agent.appRoot, com.iris.localai.agent.StorageLayout.SKILLS),
            knowledgeDir = File(c.agent.appRoot, com.iris.localai.agent.StorageLayout.KNOWLEDGE),
            privateWorkspace = c.agent.privateRoot,
            undo = File(files, "agent-undo"),                     // AgentContainer.undo
            memory = File(files, "memory"),                       // AgentContainer.memoryDocs
            conversations = File(files, "conversations"),         // AppContainer.conversations
            attachments = File(files, "attachments"),             // Attachments.dir
            historyDb = listOf("", "-wal", "-shm", "-journal").map { File(db.path + it) },
            cacheDir = ctx.cacheDir,
            externalCache = extCache,
            logFiles = listOfNotNull(File(files, com.iris.localai.util.CrashReporter.FILE), ext?.let { File(it, "iris_mnn_load.log") }) +
                ext?.listFiles()?.filter { it.isFile && it.name.endsWith(".irislocalai.app.copy") }.orEmpty(),
            restoreStage = File(files, ".restore"),                 // Backup.restore staging
        )
    }

    fun refresh() = viewModelScope.launch {
        scanLock.withLock {
            _scanning.value = true
            try {
                val r = withContext(Dispatchers.IO) {
                    c.conversations.init()
                    val list = c.models.refresh()
                    val modelRefs = list.map { StorageUsage.ModelRef(it.id, it.displayName, it.fileName, File(it.path)) }
                    val voiceRefs = VoicePack.entries.map { StorageUsage.VoiceRef(it.id, it.title, c.voiceStore.dir(it)) }
                    _free.value = runCatching { android.os.StatFs(ctx.filesDir.path).availableBytes }.getOrNull()
                    StorageUsage.scan(paths(), modelRefs, voiceRefs, c.conversations.summaries.value.size)
                }
                _report.value = r
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun freed(bytes: Long, what: String) { _note.value = "$what，释放 ${Format.bytes(bytes)}" }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { _note.value = "操作失败：${it.message ?: it.javaClass.simpleName}" }
        refresh()
    }

    fun clearModelCache(fileName: String) = io {
        val (n, b) = ModelCache.clear(ctx, fileName)
        freed(b, "已清除 $n 个缓存文件")
    }

    fun clearOrphanCaches() = io {
        // Fresh list (never a stale / empty one), so a present model's cache is never taken for an orphan.
        val names = c.models.refresh().map { it.fileName }
        val files = StorageUsage.orphanCaches(File(ctx.filesDir, ModelCache.LITERT_DIR), File(ctx.cacheDir, "mnn"), names)
        freed(files.sumOf { StorageUsage.deleteTree(it) }, "已清除已删除模型的缓存")
    }

    fun clearOpenCl() = io { freed(StorageUsage.clearChildren(File(ctx.cacheDir, "opencl")), "已清除 GPU 内核缓存") }


    fun clearUndo() = io {
        val dir = File(ctx.filesDir, "agent-undo")
        val b = StorageUsage.sizeOf(dir)
        c.agent.undo.clear()
        c.agent.changed()
        freed(b, "已清除撤销记录")
    }

    fun clearCaches() = io {
        val p = paths()
        var b = StorageUsage.clearChildren(p.cacheDir, p.cacheKeep) + StorageUsage.clearChildren(p.externalCache)
        p.logFiles.forEach { b += StorageUsage.deleteTree(it) }
        b += StorageUsage.deleteTree(p.restoreStage)
        freed(b, "已清除缓存与日志")
    }

    /** Absolute paths of every attachment a saved message points at. */
    private suspend fun referencedAttachments(): Set<String> {
        val refs = HashSet<String>()
        // 2.0.x: stored message versions / branches keep their attachments too.
        c.conversations.forEachSaved { conv -> com.iris.localai.data.MessageVersions.allAttachments(conv.messages).forEach { refs += File(it.path).absolutePath } }
        c.conversations.forEachTrashed { conv -> com.iris.localai.data.MessageVersions.allAttachments(conv.messages).forEach { refs += File(it.path).absolutePath } }
        return refs
    }

    fun clearOrphanAttachments() = io {
        val files = StorageUsage.orphanAttachments(File(ctx.filesDir, "attachments"), referencedAttachments(), System.currentTimeMillis())
        freed(files.sumOf { StorageUsage.deleteTree(it) }, "已清理 ${files.size} 个未引用的附件")
    }

    fun deleteAllAttachments() = io {
        val files = StorageUsage.removableAttachments(File(ctx.filesDir, "attachments"), referencedAttachments(), System.currentTimeMillis())
        freed(files.sumOf { StorageUsage.deleteTree(it) }, "已删除 ${files.size} 个附件")
    }

    /** Every conversation (ConversationRepository.deleteAll, which also empties the history index) and their attachments. */
    fun deleteAllHistory() = io {
        val refs = referencedAttachments()
        val before = StorageUsage.sizeOf(File(ctx.filesDir, "conversations"))
        c.conversations.deleteAll(permanent = true)
        val files = StorageUsage.removableAttachments(File(ctx.filesDir, "attachments"), refs, System.currentTimeMillis())
        freed(before + files.sumOf { StorageUsage.deleteTree(it) }, "已删除全部聊天记录")
    }

    fun noteDone(text: String) { _note.value = text }

    // ---- 2.1.0: models folder (Download/IRIDIS/models with 全部文件访问) ----
    data class ModelsFolder(val path: String, val shared: Boolean, val pendingCount: Int = 0, val pendingBytes: Long = 0)
    private val _folder = MutableStateFlow<ModelsFolder?>(null)
    val folder: StateFlow<ModelsFolder?> = _folder.asStateFlow()
    val move = c.models.moveState

    /** In-use model paths: never moved underneath the engine. */
    private fun inUse(): Set<String> = when (val s = engine.value) {
        is EngineState.Ready -> setOf(s.model.path)
        is EngineState.Loading -> setOf(s.model.path)
        else -> emptySet()
    }

    /** Re-reads the folder (on resume, after the permission screen). */
    fun refreshFolder() = viewModelScope.launch {
        _folder.value = withContext(Dispatchers.IO) {
            val loc = c.models.location()
            val plan = c.models.pendingMove(inUse())
            ModelsFolder(loc.dir.absolutePath, loc.shared, plan.move.size, plan.move.sumOf { com.iris.localai.data.ModelDirs.totalBytes(it) })
        }
    }

    fun moveModels() {
        if (move.value?.active == true) return
        viewModelScope.launch {
            c.models.clearMoveError()
            runCatching { withContext(Dispatchers.IO) { c.models.moveToShared(inUse()) } }
                .onSuccess { (moved, skipped) ->
                    _note.value = "已把 $moved 个模型移到 ${com.iris.localai.data.ModelDirs.SHARED_LABEL}" +
                        if (skipped > 0) "（$skipped 个同名或正在使用，未移动）" else ""
                }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) _note.value = "移动失败：${it.message ?: it.javaClass.simpleName}" }
            refreshFolder()
            refresh()
        }
    }
}

/** Shown before a destructive action. */
private sealed interface StoragePending {
    data class Model(val model: LocalModel) : StoragePending
    data class Voice(val pack: VoicePack) : StoragePending
    data object Undo : StoragePending
    data object Caches : StoragePending
    data object OrphanCaches : StoragePending
    data object OrphanAttachments : StoragePending
    data object AllAttachments : StoragePending
    data object AllHistory : StoragePending
}

object StorageText {
    /** Typed into the 「删除全部聊天记录」 dialog to unlock it. */
    const val WIPE_WORD = "删除"
    const val IN_USE = "正在使用，不能删除"
}

/** The model is loaded or loading (chat, voice, the API or a scheduled run may be using it): never deletable here. */
internal fun modelInUse(path: String, s: EngineState): Boolean = when (s) {
    is EngineState.Ready -> s.model.path == path
    is EngineState.Loading -> s.model.path == path
    else -> false
}

@Composable
fun StoragePage(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onModels: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val storage: StorageViewModel = viewModel()
    val modelsVm: ModelsViewModel = viewModel()
    val report by storage.report.collectAsStateWithLifecycle()
    val scanning by storage.scanning.collectAsStateWithLifecycle()
    val free by storage.free.collectAsStateWithLifecycle()
    val note by storage.note.collectAsStateWithLifecycle()
    val engine by storage.engine.collectAsStateWithLifecycle()
    val models by storage.models.collectAsStateWithLifecycle()
    val conversations by storage.conversations.collectAsStateWithLifecycle()
    val voiceStates by vm.voicePacks.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<StoragePending?>(null) }
    val folder by storage.folder.collectAsStateWithLifecycle()
    val move by storage.move.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { storage.refresh() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        // Back from the 全部文件访问 screen: the models folder may have switched.
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) storage.refreshFolder()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val anyLoading = engine is EngineState.Loading
    val r = report

    SubpageScaffold("存储空间", onBack) {
        ScrollBody {
            StorageSummary(r, free, scanning, note)

            // ---- 模型 ----
            CategorySection(Category.MODELS, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    if (e.id == Ids.MODEL_LEFTOVER || e.id == Ids.MODEL_LEFTOVER_SHARED) {
                        SettingsRow(e.label, subtitle = "${sizeLine(e)} · 中断的导入 / 下载，或无法识别的文件")
                    } else {
                        val m = models.firstOrNull { Ids.MODEL_PREFIX + it.id == e.id }
                        val inUse = m != null && modelInUse(m.path, engine)
                        SettingsRow(
                            e.label,
                            subtitle = listOfNotNull(
                                Format.bytes(e.bytes), m?.format?.label, if (inUse) StorageText.IN_USE else null,
                                if (e.external) "在 ${com.iris.localai.data.ModelDirs.SHARED_LABEL}" else null,
                            ).joinToString(" · "),
                            subtitleMaxLines = 2,
                            trailing = {
                                IrisButton(
                                    "删除", { m?.let { pending = StoragePending.Model(it) } },
                                    style = IrisButtonStyle.Text, compact = true, destructive = true, enabled = m != null && !inUse,
                                )
                            },
                        )
                    }
                }
                if (g.entries.isEmpty()) SettingsRow("还没有模型", subtitle = "在模型管理中下载或导入")
                Divider()
                SettingsRow("模型管理", subtitle = "下载、导入、卸载模型", onClick = onModels, trailing = { Chevron() })
                ModelsFolderRows(folder, move, onMove = { storage.moveModels() })
            }
            GroupFootnote("正在使用的模型不能在这里删除，请先在模型管理中卸载。")
            folder?.let { GroupFootnote(it.path, monospace = true) }

            // ---- 模型缓存 ----
            CategorySection(Category.MODEL_CACHE, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    when {
                        e.id == Ids.CACHE_ORPHAN -> ActionRow(e, "清除", enabled = !anyLoading, detail = "模型已删除，缓存不再使用") { pending = StoragePending.OrphanCaches }
                        e.id == Ids.CACHE_OPENCL -> ActionRow(e, "清除", enabled = !anyLoading, detail = "llama.cpp OpenCL 编译结果；清除后下次 GPU 加载重新编译") { storage.clearOpenCl() }
                        e.id == Ids.CACHE_NPU -> SettingsRow(
                            e.label, subtitle = "${sizeLine(e)} · 在「模型与后端」中管理",
                            onClick = { onOpen(SettingsPage.MODEL_BACKEND) }, trailing = { Chevron() },
                        )
                        else -> {
                            val m = models.firstOrNull { Ids.CACHE_PREFIX + it.id == e.id }
                            val loading = (engine as? EngineState.Loading)?.model?.path == m?.path
                            val loaded = m != null && (engine as? EngineState.Ready)?.model?.path == m.path
                            ActionRow(
                                e, "清除", enabled = m != null && !loading,
                                detail = when {
                                    loading -> "正在加载，稍后再清除"
                                    loaded -> "GPU 编译结果 / 权重缓存（已加载，不影响本次运行）"
                                    else -> "GPU 编译结果 / 权重缓存；清除后下次加载会重新编译"
                                },
                            ) { m?.let { storage.clearModelCache(it.fileName) } }
                        }
                    }
                }
                if (g.entries.isEmpty()) SettingsRow("没有缓存", subtitle = "GGUF 模型不生成缓存；LiteRT / MNN 首次加载后才会有")
            }

            // ---- 语音模型 ----
            CategorySection(Category.VOICE, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    when (e.id) {
                        Ids.VOICE_CLONES -> SettingsRow(
                            e.label, subtitle = "${e.count ?: 0} 个音色 · ${Format.bytes(e.bytes)}",
                            onClick = { onOpen(SettingsPage.VOICE) }, trailing = { Chevron() },
                        )
                        Ids.VOICE_OTHER -> SettingsRow(e.label, subtitle = sizeLine(e) + if (e.external) " · " + StorageLayoutText.VOICE_SHARED else "")
                        else -> {
                            val pack = VoicePack.byId(e.id.removePrefix(Ids.VOICE_PREFIX))
                            val st = pack?.let { voiceStates[it] }
                            val busy = st is PackState.Downloading || st == PackState.Extracting
                            val kind = when (pack?.kind) {
                                com.iris.localai.voice.VoiceKind.ASR -> "识别"
                                com.iris.localai.voice.VoiceKind.TTS -> "朗读"
                                com.iris.localai.voice.VoiceKind.CLONE -> "克隆"
                                null -> null
                            }
                            val state = when {
                                busy -> "下载中"
                                st == PackState.Installed -> null
                                else -> "未下载完成"
                            }
                            SettingsRow(
                                e.label, subtitle = listOfNotNull(Format.bytes(e.bytes), kind, state, StorageLayoutText.VOICE_SHARED.takeIf { e.external }).joinToString(" · "),
                                trailing = {
                                    IrisButton(
                                        "删除", { pack?.let { pending = StoragePending.Voice(it) } },
                                        style = IrisButtonStyle.Text, compact = true, destructive = true, enabled = pack != null && !busy,
                                    )
                                },
                            )
                        }
                    }
                }
                if (g.entries.isEmpty()) SettingsRow("没有下载语音模型", subtitle = "在「语音」中下载识别和朗读模型", onClick = { onOpen(SettingsPage.VOICE) }, trailing = { Chevron() })
            }

            // ---- 技能 / 工作区文件 ----
            CategorySection(Category.WORKSPACE, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    when (e.id) {
                        Ids.WS_FILES -> SettingsRow(
                            e.label, subtitle = sizeLine(e) + if (e.external) " · " + StorageLayoutText.WORKSPACE_SHARED else "",
                            subtitleMaxLines = 2, onClick = onOpenFiles, trailing = { Chevron() },
                        )
                        Ids.WS_SKILLS -> SettingsRow(
                            e.label, subtitle = "${e.count ?: 0} 个技能 · ${Format.bytes(e.bytes)}" + if (e.external) " · " + StorageLayoutText.SKILLS_SHARED else "",
                            subtitleMaxLines = 2,
                        )
                        Ids.WS_KNOWLEDGE -> SettingsRow(
                            e.label, subtitle = "${e.count ?: 0} 个文件 · ${Format.bytes(e.bytes)}" + if (e.external) " · " + StorageLayoutText.KNOWLEDGE_SHARED else "",
                            subtitleMaxLines = 2,
                        )
                        Ids.WS_UNDO -> ActionRow(e, "清除", detail = "智能体改文件前保存的旧版本") { pending = StoragePending.Undo }
                        Ids.WS_MEMORY -> SettingsRow(e.label, subtitle = sizeLine(e), onClick = { onOpen(SettingsPage.MEMORY) }, trailing = { Chevron() })
                        else -> SettingsRow(e.label, subtitle = "${sizeLine(e)} · 切换工作区位置前留下的文件", subtitleMaxLines = 2)
                    }
                }
            }
            GroupFootnote("工作区文件不提供一键清空，请在「文件」中管理。")
            GroupFootnote(StorageLayoutText.SUMMARY)

            // ---- 聊天记录 ----
            CategorySection(Category.CHATS, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    val sub = when (e.id) {
                        Ids.CHATS -> "${e.count ?: conversations.size} 个对话 · ${Format.bytes(e.bytes)}"
                        Ids.ATTACHMENTS -> "${e.count ?: 0} 个文件 · ${Format.bytes(e.bytes)}"
                        else -> sizeLine(e)
                    }
                    SettingsRow(e.label, subtitle = sub)
                }
                val att = g.entry(Ids.ATTACHMENTS)
                val hasAttachments = (att?.files ?: 0) > 0
                Divider()
                SettingsRow(
                    "清理未引用的附件", subtitle = "删除已不属于任何对话的附件文件",
                    enabled = hasAttachments, onClick = { pending = StoragePending.OrphanAttachments },
                )
                Divider()
                SettingsRow(
                    "删除全部附件", titleColor = Iris.colors.danger, subtitle = "对话文字保留，其中的图片和文件将无法再打开",
                    enabled = hasAttachments, onClick = { pending = StoragePending.AllAttachments },
                )
                Divider()
                SettingsRow(
                    "删除全部聊天记录", titleColor = Iris.colors.danger, subtitle = "永久删除所有对话及其附件，无法恢复",
                    enabled = conversations.isNotEmpty() || hasAttachments, onClick = { pending = StoragePending.AllHistory },
                )
            }

            // ---- 缓存与日志 ----
            CategorySection(Category.CACHE_LOGS, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    SettingsRow(e.label, subtitle = sizeLine(e))
                }
                Divider()
                SettingsRow(
                    "清除缓存与日志", subtitle = "不影响模型、模型缓存、对话和设置",
                    enabled = g.bytes > 0, onClick = { pending = StoragePending.Caches },
                )
            }

            // ---- 其他 ----
            CategorySection(Category.OTHER, r) { g ->
                g.entries.forEach { e -> SettingsRow(e.label, subtitle = Format.bytes(e.bytes)) }
            }
            // 2.0.x: 备份与日志 moved here from 设置 › 智能体 (it is about the app's data).
            com.iris.localai.ui.agent.BackupSection()
        }
    }

    when (val p = pending) {
        null -> Unit
        is StoragePending.Model -> ModelDeleteDialog(
            p.model,
            onConfirm = {
                // Re-check at confirm time: a load may have started while the dialog was open.
                if (!modelInUse(p.model.path, storage.engine.value)) modelsVm.delete(p.model).invokeOnCompletion { storage.refresh() }
                else storage.noteDone("${p.model.fileName} ${StorageText.IN_USE}")
            },
            onDismiss = { pending = null },
        )
        is StoragePending.Voice -> ConfirmDialog(
            title = "删除语音模型？",
            message = "将删除「${p.pack.title}」，需要时可以重新下载。",
            confirmText = "删除",
            onConfirm = { vm.deletePack(p.pack).invokeOnCompletion { storage.refresh() } },
            onDismiss = { pending = null },
        )
        StoragePending.Undo -> ConfirmDialog(
            title = "清除撤销记录？", message = "之后无法再撤销智能体此前对文件的修改。",
            confirmText = "清除", onConfirm = { storage.clearUndo() }, onDismiss = { pending = null },
        )
        StoragePending.Caches -> ConfirmDialog(
            title = "清除缓存与日志？", message = "删除临时文件、日志和崩溃记录。模型缓存、对话和设置不受影响。",
            confirmText = "清除", onConfirm = { storage.clearCaches() }, onDismiss = { pending = null },
        )
        StoragePending.OrphanCaches -> ConfirmDialog(
            title = "清除旧缓存？", message = "这些缓存属于已删除的模型。重新导入同一个模型时会重新编译。",
            confirmText = "清除", onConfirm = { storage.clearOrphanCaches() }, onDismiss = { pending = null },
        )
        StoragePending.OrphanAttachments -> ConfirmDialog(
            title = "清理未引用的附件？", message = "只删除不属于任何已保存对话的附件（最近一小时内添加的会保留）。",
            confirmText = "清理", onConfirm = { storage.clearOrphanAttachments() }, onDismiss = { pending = null },
        )
        StoragePending.AllAttachments -> ConfirmDialog(
            title = "删除全部附件？", message = "对话文字会保留，但其中的图片和文件将无法再打开，无法恢复。",
            confirmText = "删除", onConfirm = { storage.deleteAllAttachments() }, onDismiss = { pending = null },
        )
        StoragePending.AllHistory -> StrongWipeDialog(
            count = conversations.size,
            onConfirm = { storage.deleteAllHistory() },
            onDismiss = { pending = null },
        )
    }
}

private fun sizeLine(e: StorageUsage.Entry): String =
    Format.bytes(e.bytes) + if (e.files > 1) " · ${e.files} 个文件" else ""

/** Size, a short explanation and one action button. */
@Composable
private fun ActionRow(
    e: StorageUsage.Entry,
    action: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    detail: String? = null,
    onClick: () -> Unit,
) {
    SettingsRow(
        e.label,
        subtitle = listOfNotNull(Format.bytes(e.bytes), detail).joinToString(" · "),
        subtitleMaxLines = 2,
        trailing = { IrisButton(action, onClick, style = IrisButtonStyle.Text, compact = true, destructive = destructive, enabled = enabled) },
    )
}

/** Header + card of one category; placeholder rows until the first scan is in. */
@Composable
private fun CategorySection(cat: Category, r: StorageUsage.Report?, content: @Composable ColumnScope.(StorageUsage.Group) -> Unit) {
    val g = r?.group(cat)
    SectionTitle(if (g == null) cat.title else "${cat.title} · ${Format.bytes(g.bytes)}")
    SettingsGroup {
        if (g == null) {
            PlaceholderRow()
            PlaceholderRow()
        } else this.content(g)
    }
}

@Composable
private fun PlaceholderRow() {
    val c = Iris.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = IrisDimens.rowMinHeight)
            .padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.rowVerticalPadding)
            .pulse(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.46f).height(14.dp).clip(IrisRadius.pillShape).background(c.surfaceVariant))
            Spacer(Modifier.height(IrisDimens.space8))
            Box(Modifier.fillMaxWidth(0.28f).height(11.dp).clip(IrisRadius.pillShape).background(c.surfaceVariant))
        }
        Spacer(Modifier.width(IrisDimens.space12))
        Box(Modifier.size(width = 40.dp, height = 14.dp).clip(IrisRadius.pillShape).background(c.surfaceVariant))
    }
}

/** Fixed, muted colour per category (the bar and its legend). */
@Composable
private fun categoryColor(cat: Category): Color {
    val c = Iris.colors
    return when (cat) {
        Category.MODELS -> c.accent
        Category.MODEL_CACHE -> IrisChartColors.violet
        Category.VOICE -> IrisChartColors.teal
        Category.WORKSPACE -> IrisChartColors.green
        Category.CHATS -> IrisChartColors.coral
        Category.CACHE_LOGS -> IrisChartColors.grey
        Category.OTHER -> c.textTertiary.copy(alpha = 0.45f)
    }
}

@Composable
private fun StorageSummary(r: StorageUsage.Report?, free: Long?, scanning: Boolean, note: String?) {
    val c = Iris.colors
    SettingsGroup(Modifier.padding(top = IrisDimens.space8)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space20)) {
            if (r == null) {
                Box(Modifier.size(width = 120.dp, height = 28.dp).clip(IrisRadius.pillShape).background(c.surfaceVariant).pulse())
            } else {
                Text(Format.bytes(r.total), style = IrisType.display, color = c.text)
            }
            Spacer(Modifier.height(IrisDimens.space6))
            Text(
                when {
                    r == null -> "正在计算…"
                    else -> "IRIDIS 共占用" + (free?.let { " · 手机剩余 ${Format.bytes(it)}" } ?: "") + if (scanning) " · 正在更新…" else ""
                },
                style = IrisType.secondary, color = c.textSecondary,
            )
            Spacer(Modifier.height(IrisDimens.space16))
            StorageBar(r)
            if (r != null) {
                Spacer(Modifier.height(14.dp))
                Legend(r)
            }
            if (note != null) {
                Spacer(Modifier.height(IrisDimens.space12))
                Text(note, style = IrisType.secondary, color = c.accent)
            }
        }
    }
}

/** One bar, one segment per category; segment widths spring to new values (IrisMotion.Gentle). */
@Composable
private fun StorageBar(r: StorageUsage.Report?) {
    val c = Iris.colors
    val segs = remember(r) { r?.let { StorageUsage.segments(it).toMap() } }
    val cats = Category.entries
    val colors = cats.map { categoryColor(it) }
    // Category.entries is constant, so the number of animation states never changes between compositions.
    val fractions = cats.map { cat ->
        animateFloatAsState(segs?.get(cat) ?: 0f, motionSpec(IrisMotion.Gentle, 0.0005f), label = "storage-${cat.name}")
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(IrisRadius.pillShape)
            .background(c.surfaceVariant)
            .then(if (r == null) Modifier.pulse() else Modifier)
            .drawBehind {
                val gap = 1.5.dp.toPx()
                var x = 0f
                fractions.forEachIndexed { i, f ->
                    val w = size.width * f.value
                    if (w >= 0.5f) {
                        drawRect(colors[i], topLeft = Offset(x, 0f), size = Size((w - gap).coerceAtLeast(0.5f), size.height))
                        x += w
                    }
                }
            },
    )
}

@Composable
private fun Legend(r: StorageUsage.Report) {
    val c = Iris.colors
    val shown = Category.entries.filter { (r.group(it)?.appBytes ?: 0L) > 0L }
    Column {
        shown.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.height(IrisDimens.space8))
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { cat ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(IrisDimens.space8).clip(IrisRadius.pillShape).background(categoryColor(cat)))
                        Spacer(Modifier.width(IrisDimens.space8))
                        Text(
                            "${cat.title} ${Format.bytes(r.group(cat)?.appBytes ?: 0L)}",
                            style = IrisType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 「删除全部聊天记录」: the confirm button unlocks only after typing [StorageText.WIPE_WORD]. */
@Composable
private fun StrongWipeDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Iris.colors
    var typed by rememberSaveable { mutableStateOf("") }
    IrisDialog(
        onDismiss = onDismiss,
        title = "删除全部聊天记录？",
        confirmText = "永久删除",
        onConfirm = { onConfirm(); onDismiss() },
        destructive = true,
        confirmEnabled = typed.trim() == StorageText.WIPE_WORD,
    ) {
        Column {
            Text(
                "将永久删除全部 $count 个对话及其附件，无法恢复。确认请输入「${StorageText.WIPE_WORD}」。",
                style = IrisType.secondary, color = c.textSecondary,
            )
            Spacer(Modifier.height(IrisDimens.space12))
            OutlinedTextField(
                typed, { typed = it },
                singleLine = true,
                textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text(StorageText.WIPE_WORD, style = IrisType.body, color = c.textTertiary) },
                shape = IrisRadius.mediumShape,
                colors = irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 2.1.0 存储空间 › 模型: the 全部文件访问 hint (models stay in the app folder and are lost on uninstall
 * without it) and the one-time 「把模型移到 下载/IRIDIS/models」 with progress. Never automatic.
 */
@Composable
private fun ColumnScope.ModelsFolderRows(folder: StorageViewModel.ModelsFolder?, move: com.iris.localai.data.ModelRepository.MoveState?, onMove: () -> Unit) {
    val f = folder ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!f.shared) {
        Divider()
        SettingsRow(
            ModelFolderText.GRANT_TITLE, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
            subtitle = ModelFolderText.GRANT_HINT, subtitleMaxLines = 3,
            onClick = { com.iris.localai.ui.models.openAllFilesAccess(context) }, trailing = { Chevron() },
        )
    } else if (move?.active == true) {
        Divider()
        SettingsRow(
            ModelFolderText.MOVING, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
            subtitle = "${move.current} · ${Format.bytes(move.done)} / ${Format.bytes(move.total)}", subtitleMaxLines = 2,
        )
        com.iris.localai.ui.components.IrisProgressBar(move.progress, Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space4))
    } else if (f.pendingCount > 0) {
        Divider()
        SettingsRow(
            ModelFolderText.MOVE, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
            subtitle = "${f.pendingCount} 个模型 · ${Format.bytes(f.pendingBytes)} · 移过去后重装也不会丢" +
                (move?.error?.let { " · 上次失败：$it" } ?: ""),
            subtitleMaxLines = 3,
            trailing = { IrisButton("移动", onMove, style = IrisButtonStyle.Text, compact = true) },
        )
    }
}

/** 2.1.1: Download/IRIDIS is the app's resource root; only workspace/ belongs to the agent. */
object StorageLayoutText {
    private val L = com.iris.localai.agent.StorageLayout
    val SUMMARY = L.SUMMARY + "。智能体只能访问 ${L.WORKSPACE}。"
    val WORKSPACE_SHARED = "在 ${L.label(L.WORKSPACE)}，不计入应用占用"
    val SKILLS_SHARED = "在 ${L.label(L.SKILLS)}，不计入应用占用"
    val KNOWLEDGE_SHARED = "在 ${L.label(L.KNOWLEDGE)}，不计入应用占用"
    val VOICE_SHARED = "在 ${L.label(L.VOICE)}"
}

object ModelFolderText {
    const val MOVE = "把模型移到下载目录"
    const val MOVING = "正在移动模型…"
    const val GRANT_TITLE = "改存到下载目录"
    const val GRANT_HINT = "现在存在 App 里，卸载会一起删除。改存后放在 下载/IRIDIS，重装也不会丢"
}

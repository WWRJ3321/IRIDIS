package com.iris.localai.ui.settings

import com.iris.localai.ui.theme.IrisChartColors
import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                    StorageUsage.scan(paths(), modelRefs, voiceRefs, c.conversations.summaries.value.size, c.models.protectedParts())
                }
                _report.value = r
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun freed(bytes: Long, what: String) { _note.value = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4de0d0, what, Format.bytes(bytes)) }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { _note.value = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_48dd00, it.message ?: it.javaClass.simpleName) }
        refresh()
    }

    /** 2.3.1: leftovers in the models dirs, re-read now (the list on screen may be stale). */
    private suspend fun currentLeftovers(): List<StorageUsage.Leftover> {
        val refs = c.models.refresh().map { StorageUsage.ModelRef(it.id, it.displayName, it.fileName, File(it.path)) }
        val p = paths()
        val prot = c.models.protectedParts()
        val shared = p.sharedModelsDir?.takeIf { runCatching { it.canonicalPath != p.modelsDir.canonicalPath }.getOrDefault(true) }
        return StorageUsage.leftovers(p.modelsDir, refs, prot, shared = false) + StorageUsage.leftovers(shared, refs, prot, shared = true)
    }

    /** Deletes one leftover file / folder, only if it is still a leftover and not part of a running / paused download. */
    fun deleteLeftover(path: String) = io {
        val l = currentLeftovers().firstOrNull { it.file.absolutePath == path }
        when {
            l == null -> _note.value = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6f639b)
            l.active -> _note.value = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_15003a, l.name)
            else -> freed(StorageUsage.deleteTree(l.file), com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b0b6b9, l.name))
        }
    }

    /** 「清理未完成的下载」: every partial file except those of a download running or paused right now. */
    fun clearPartials() = io {
        val list = StorageUsage.clearablePartials(currentLeftovers())
        freed(list.sumOf { StorageUsage.deleteTree(it.file) }, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c3e35f, list.size))
    }

    fun clearModelCache(fileName: String) = io {
        val (n, b) = ModelCache.clear(ctx, fileName)
        freed(b, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e47130, n))
    }

    fun clearOrphanCaches() = io {
        // Fresh list (never a stale / empty one), so a present model's cache is never taken for an orphan.
        val names = c.models.refresh().map { it.fileName }
        val files = StorageUsage.orphanCaches(File(ctx.filesDir, ModelCache.LITERT_DIR), File(ctx.cacheDir, "mnn"), names)
        freed(files.sumOf { StorageUsage.deleteTree(it) }, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5aa6dc))
    }

    fun clearOpenCl() = io { freed(StorageUsage.clearChildren(File(ctx.cacheDir, "opencl")), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_28ee5d)) }


    fun clearUndo() = io {
        val dir = File(ctx.filesDir, "agent-undo")
        val b = StorageUsage.sizeOf(dir)
        c.agent.undo.clear()
        c.agent.changed()
        freed(b, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_94bf40))
    }

    fun clearCaches() = io {
        val p = paths()
        var b = StorageUsage.clearChildren(p.cacheDir, p.cacheKeep) + StorageUsage.clearChildren(p.externalCache)
        p.logFiles.forEach { b += StorageUsage.deleteTree(it) }
        b += StorageUsage.deleteTree(p.restoreStage)
        freed(b, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d5ade4))
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
        freed(files.sumOf { StorageUsage.deleteTree(it) }, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a34d76, files.size))
    }

    fun deleteAllAttachments() = io {
        val files = StorageUsage.removableAttachments(File(ctx.filesDir, "attachments"), referencedAttachments(), System.currentTimeMillis())
        freed(files.sumOf { StorageUsage.deleteTree(it) }, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_04107d, files.size))
    }

    /** Every conversation (ConversationRepository.deleteAll, which also empties the history index) and their attachments. */
    fun deleteAllHistory() = io {
        val refs = referencedAttachments()
        val before = StorageUsage.sizeOf(File(ctx.filesDir, "conversations"))
        c.conversations.deleteAll(permanent = true)
        val files = StorageUsage.removableAttachments(File(ctx.filesDir, "attachments"), refs, System.currentTimeMillis())
        freed(before + files.sumOf { StorageUsage.deleteTree(it) }, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1d6756))
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
                    _note.value = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fd26cc, moved, com.iris.localai.data.ModelDirs.SHARED_LABEL) +
                        if (skipped > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_174ae7, skipped) else ""
                }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) _note.value = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fe6c61, it.message ?: it.javaClass.simpleName) }
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
    data class LeftoverFile(val item: StorageUsage.Leftover) : StoragePending
    data class Partials(val count: Int, val bytes: Long) : StoragePending
}

object StorageText {
    /** Typed into the 「删除全部聊天记录」 dialog to unlock it. */
    val WIPE_WORD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad)
    val IN_USE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_aaa128)
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
    /** 2.3.1: leftovers sheet open for the app dir (false) or Download/IRIDIS (true). */
    var leftoverSheet by remember { mutableStateOf<Boolean?>(null) }
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

    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5b7998), onBack) {
        ScrollBody {
            // 2.5.0 Beta3: two tabs, 占用 / 清理与备份 (the 位置 tab folded into 占用; its 模型与加载 jump row removed).
            var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
            com.iris.localai.ui.components.PageTabs(StorageTabs.labels(), tab, { tab = it })
            if (tab == StorageTabs.OVERVIEW) {
            StorageSummary(r, free, scanning, note)

            // ---- 模型 ----
            CategorySection(Category.MODELS, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    if (e.id == Ids.MODEL_LEFTOVER || e.id == Ids.MODEL_LEFTOVER_SHARED) {
                        SettingsRow(
                            e.label, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d07d97, sizeLine(e)),
                            onClick = { leftoverSheet = e.id == Ids.MODEL_LEFTOVER_SHARED }, trailing = { Chevron() },
                        )
                    } else {
                        val m = models.firstOrNull { Ids.MODEL_PREFIX + it.id == e.id }
                        val inUse = m != null && modelInUse(m.path, engine)
                        SettingsRow(
                            e.label,
                            subtitle = listOfNotNull(
                                Format.bytes(e.bytes), m?.format?.label, if (e.withProjector) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dec5b6) else null,
                                if (inUse) StorageText.IN_USE else null,
                                if (e.external) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b044a5, com.iris.localai.data.ModelDirs.SHARED_LABEL) else null,
                            ).joinToString(" · "),
                            subtitleMaxLines = 2,
                            trailing = {
                                IrisButton(
                                    com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), { m?.let { pending = StoragePending.Model(it) } },
                                    style = IrisButtonStyle.Text, compact = true, destructive = true, enabled = m != null && !inUse,
                                )
                            },
                        )
                    }
                }
                if (g.entries.isEmpty()) SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_d38003), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e2b81b))
            }
            }

            if (tab == StorageTabs.CLEAN) {
            // ---- 模型缓存 ----
            CategorySection(Category.MODEL_CACHE, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    when {
                        e.id == Ids.CACHE_ORPHAN -> ActionRow(e, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), enabled = !anyLoading, detail = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_37c271)) { pending = StoragePending.OrphanCaches }
                        e.id == Ids.CACHE_OPENCL -> ActionRow(e, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), enabled = !anyLoading, detail = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_94f1cb)) { storage.clearOpenCl() }
                        e.id == Ids.CACHE_NPU -> SettingsRow(
                            e.label, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_17a5e2, sizeLine(e)),
                            onClick = { onOpen(SettingsPage.MODEL_BACKEND) }, trailing = { Chevron() },
                        )
                        else -> {
                            val m = models.firstOrNull { Ids.CACHE_PREFIX + it.id == e.id }
                            val loading = (engine as? EngineState.Loading)?.model?.path == m?.path
                            val loaded = m != null && (engine as? EngineState.Ready)?.model?.path == m.path
                            ActionRow(
                                e, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), enabled = m != null && !loading,
                                detail = when {
                                    loading -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a0c7d7)
                                    loaded -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6b149e)
                                    else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7f95af)
                                },
                            ) { m?.let { storage.clearModelCache(it.fileName) } }
                        }
                    }
                }
                if (g.entries.isEmpty()) SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_373e22), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_bc44bd))
            }
            }

            if (tab == StorageTabs.OVERVIEW) {
            // ---- 语音模型 ----
            CategorySection(Category.VOICE, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    when (e.id) {
                        Ids.VOICE_CLONES -> SettingsRow(
                            e.label, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ecdf9c, e.count ?: 0, Format.bytes(e.bytes)),
                            onClick = { onOpen(SettingsPage.VOICE) }, trailing = { Chevron() },
                        )
                        Ids.VOICE_OTHER -> SettingsRow(e.label, subtitle = sizeLine(e) + if (e.external) " · " + StorageLayoutText.VOICE_SHARED else "")
                        else -> {
                            val pack = VoicePack.byId(e.id.removePrefix(Ids.VOICE_PREFIX))
                            val st = pack?.let { voiceStates[it] }
                            val busy = st is PackState.Downloading || st == PackState.Extracting
                            val kind = when (pack?.kind) {
                                com.iris.localai.voice.VoiceKind.ASR -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_72eff8)
                                com.iris.localai.voice.VoiceKind.TTS -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f9ab1f)
                                com.iris.localai.voice.VoiceKind.CLONE -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_25fe25)
                                null -> null
                            }
                            val state = when {
                                busy -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2d455c)
                                st == PackState.Installed -> null
                                else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5d44a3)
                            }
                            SettingsRow(
                                e.label, subtitle = listOfNotNull(Format.bytes(e.bytes), kind, state, StorageLayoutText.VOICE_SHARED.takeIf { e.external }).joinToString(" · "),
                                trailing = {
                                    IrisButton(
                                        com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), { pack?.let { pending = StoragePending.Voice(it) } },
                                        style = IrisButtonStyle.Text, compact = true, destructive = true, enabled = pack != null && !busy,
                                    )
                                },
                            )
                        }
                    }
                }
                if (g.entries.isEmpty()) SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b6a94c), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b39d0f), onClick = { onOpen(SettingsPage.VOICE) }, trailing = { Chevron() })
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
                            e.label, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e6115d, e.count ?: 0, Format.bytes(e.bytes)) + if (e.external) " · " + StorageLayoutText.SKILLS_SHARED else "",
                            subtitleMaxLines = 2,
                        )
                        Ids.WS_KNOWLEDGE -> SettingsRow(
                            e.label, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9c50ee, e.count ?: 0, Format.bytes(e.bytes)) + if (e.external) " · " + StorageLayoutText.KNOWLEDGE_SHARED else "",
                            subtitleMaxLines = 2,
                        )
                        Ids.WS_UNDO -> ActionRow(e, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), detail = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ed4ae3)) { pending = StoragePending.Undo }
                        Ids.WS_MEMORY -> SettingsRow(e.label, subtitle = sizeLine(e), onClick = { onOpen(SettingsPage.MEMORY) }, trailing = { Chevron() })
                        else -> SettingsRow(e.label, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_164a6b, sizeLine(e)), subtitleMaxLines = 2)
                    }
                }
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cb4eb0))

            // ---- 其他 ----
            CategorySection(Category.OTHER, r) { g ->
                g.entries.forEach { e -> SettingsRow(e.label, subtitle = Format.bytes(e.bytes)) }
            }
            }

            if (tab == StorageTabs.CLEAN) {
            // ---- 聊天记录 ----
            CategorySection(Category.CHATS, r) { g ->
                g.entries.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    val sub = when (e.id) {
                        Ids.CHATS -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4a7e9a, e.count ?: conversations.size, Format.bytes(e.bytes))
                        Ids.ATTACHMENTS -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9c50ee, e.count ?: 0, Format.bytes(e.bytes))
                        else -> sizeLine(e)
                    }
                    SettingsRow(e.label, subtitle = sub)
                }
                val att = g.entry(Ids.ATTACHMENTS)
                val hasAttachments = (att?.files ?: 0) > 0
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d17392), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d7124c),
                    enabled = hasAttachments, onClick = { pending = StoragePending.OrphanAttachments },
                )
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9e67e6), titleColor = Iris.colors.danger, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3a6f4e),
                    enabled = hasAttachments, onClick = { pending = StoragePending.AllAttachments },
                )
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b7050e), titleColor = Iris.colors.danger, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a937c5),
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
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_50c43a), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_50e95d),
                    enabled = g.bytes > 0, onClick = { pending = StoragePending.Caches },
                )
            }
            }

            if (tab == StorageTabs.CLEAN) {
            // 2.0.x: 备份与日志 moved here from 设置 › 智能体 (it is about the app's data).
            com.iris.localai.ui.agent.BackupSection()
            }

            if (tab == StorageTabs.OVERVIEW) {
            // ---- 位置: where the models live ----
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.v250_tab_location))
            SettingsGroup {
                ModelsFolderRows(folder, move, onMove = { storage.moveModels() })
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4f3ac2))
            folder?.let { GroupFootnote(it.path, monospace = true) }
            GroupFootnote(StorageLayoutText.SUMMARY)
            }
        }
    }

    leftoverSheet?.let { shared ->
        val items = r?.leftovers.orEmpty().filter { it.shared == shared }
        LeftoverSheet(
            items,
            onDelete = { pending = StoragePending.LeftoverFile(it) },
            onClearPartials = { list -> pending = StoragePending.Partials(list.size, list.sumOf { it.bytes }) },
            onDismiss = { leftoverSheet = null },
        )
    }

    when (val p = pending) {
        null -> Unit
        is StoragePending.LeftoverFile -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6589f9), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a8f0b6, p.item.name, Format.bytes(p.item.bytes)),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), onConfirm = { storage.deleteLeftover(p.item.file.absolutePath); pending = null }, onDismiss = { pending = null },
        )
        is StoragePending.Partials -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_99e12a), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_15e818, p.count, Format.bytes(p.bytes)),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e47bb1), onConfirm = { storage.clearPartials(); pending = null }, onDismiss = { pending = null },
        )
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
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0a35ee),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_578347, p.pack.title),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
            onConfirm = { vm.deletePack(p.pack).invokeOnCompletion { storage.refresh() } },
            onDismiss = { pending = null },
        )
        StoragePending.Undo -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_46aa9a), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_64d5d0),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), onConfirm = { storage.clearUndo() }, onDismiss = { pending = null },
        )
        StoragePending.Caches -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4b37e9), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_71427c),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), onConfirm = { storage.clearCaches() }, onDismiss = { pending = null },
        )
        StoragePending.OrphanCaches -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2e578b), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ccd81b),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), onConfirm = { storage.clearOrphanCaches() }, onDismiss = { pending = null },
        )
        StoragePending.OrphanAttachments -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2df9b8), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_502471),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e47bb1), onConfirm = { storage.clearOrphanAttachments() }, onDismiss = { pending = null },
        )
        StoragePending.AllAttachments -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_71c3cc), message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5b079e),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), onConfirm = { storage.deleteAllAttachments() }, onDismiss = { pending = null },
        )
        StoragePending.AllHistory -> StrongWipeDialog(
            count = conversations.size,
            onConfirm = { storage.deleteAllHistory() },
            onDismiss = { pending = null },
        )
    }
}

/** 2.5.0 Beta3: the two tabs of 存储空间 (占用 / 清理与备份). */
object StorageTabs {
    const val OVERVIEW = 0
    const val CLEAN = 1
    fun labels(): List<String> = listOf(
        com.iris.localai.I18n.s(com.iris.localai.R.string.v250_tab_usage),
        com.iris.localai.I18n.s(com.iris.localai.R.string.v250_tab_cleanup),
    )
}

/** 2.3.1: kind label of a leftover in the models folder. */
internal fun leftoverKindLabel(l: StorageUsage.Leftover): String = when (l.kind) {
    StorageUsage.LeftoverKind.PARTIAL -> if (l.active) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ddc5f0) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f57ca2)
    StorageUsage.LeftoverKind.PROJECTOR -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_961998)
    StorageUsage.LeftoverKind.UNKNOWN -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ad160b)
}

/** 2.3.1: what is in 「未完成的导入与其他文件」, with per-file delete and 「清理未完成的下载」. */
@Composable
private fun LeftoverSheet(
    items: List<StorageUsage.Leftover>,
    onDelete: (StorageUsage.Leftover) -> Unit,
    onClearPartials: (List<StorageUsage.Leftover>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Iris.colors
    val clearable = StorageUsage.clearablePartials(items)
    com.iris.localai.ui.components.IrisSheet(onDismiss = onDismiss) {
        com.iris.localai.ui.components.SheetHeader(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6f70b9))
        Column(Modifier.padding(horizontal = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_22a44d), style = IrisType.caption, color = c.textTertiary)
            if (items.isEmpty()) Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e18bc7), style = IrisType.caption, color = c.textSecondary)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    SettingsRow(
                        l.name,
                        subtitle = "${Format.bytes(l.bytes)} · ${leftoverKindLabel(l)}",
                        subtitleMaxLines = 2,
                        trailing = {
                            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), { onDelete(l) }, style = IrisButtonStyle.Text, compact = true, destructive = true, enabled = !l.active)
                        },
                    )
                }
            }
            if (clearable.isNotEmpty()) {
                IrisButton(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dc251b, Format.bytes(clearable.sumOf { it.bytes })), { onClearPartials(clearable) },
                    style = IrisButtonStyle.Tonal, destructive = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(IrisDimens.space16))
        }
    }
}

private fun sizeLine(e: StorageUsage.Entry): String =
    Format.bytes(e.bytes) + if (e.files > 1) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8d4d33, e.files) else ""

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
                    r == null -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_00ff10)
                    else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e433ac) + (free?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_080d83, Format.bytes(it)) } ?: "") + if (scanning) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_03e3be) else ""
                },
                style = IrisType.secondary, color = c.textSecondary,
            )
            Spacer(Modifier.height(IrisDimens.space16))
            StorageBar(r)
            if (r != null) {
                Spacer(Modifier.height(com.iris.localai.ui.theme.IrisSpace.M))
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
                            style = IrisType.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5005da),
        confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_96d2b7),
        onConfirm = { onConfirm(); onDismiss() },
        destructive = true,
        confirmEnabled = typed.trim() == StorageText.WIPE_WORD,
    ) {
        Column {
            Text(
                com.iris.localai.I18n.s(com.iris.localai.R.string.settings_02f436, count, StorageText.WIPE_WORD),
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
            subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3a7c89, f.pendingCount, Format.bytes(f.pendingBytes)) +
                (move?.error?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_85bada, it) } ?: ""),
            subtitleMaxLines = 3,
            trailing = { IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_68d982), onMove, style = IrisButtonStyle.Text, compact = true) },
        )
    }
}

/** 2.1.1: Download/IRIDIS is the app's resource root; only workspace/ belongs to the agent. */
object StorageLayoutText {
    private val L = com.iris.localai.agent.StorageLayout
    val SUMMARY: String get() = L.SUMMARY + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_788fe7, L.WORKSPACE)
    val WORKSPACE_SHARED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a64c10, L.label(L.WORKSPACE))
    val SKILLS_SHARED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a64c10, L.label(L.SKILLS))
    val KNOWLEDGE_SHARED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a64c10, L.label(L.KNOWLEDGE))
    val VOICE_SHARED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b044a5, L.label(L.VOICE))
}

object ModelFolderText {
    val MOVE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_027203)
    val MOVING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5b633a)
    val GRANT_TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.models_0b5542)
    val GRANT_HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_506943)
}

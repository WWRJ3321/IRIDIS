package com.iris.localai.ui.files

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.agent.WorkspaceResolver
import com.iris.localai.files.Crumb
import com.iris.localai.files.FileEntry
import com.iris.localai.files.FilePreview
import com.iris.localai.files.FileProtection
import com.iris.localai.files.FileSort
import com.iris.localai.files.FileViewStyle
import com.iris.localai.files.FilesIo
import com.iris.localai.files.FilesPaths
import com.iris.localai.files.FilesSorting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Whether the app can see everything under Download/IRIDIS. */
enum class FilesAccess {
    /** 全部文件访问 (or Android < 11): every file is visible. */
    FULL,
    /** Scoped storage: only files IRIDIS created itself are listed — show the 全部文件访问 guidance. */
    LIMITED,
    /** No shared storage / the folder cannot be read at all. */
    UNAVAILABLE,
}

/** The one dialog the page shows at a time (rendered by [FilesDialogs], whatever the view style). */
sealed interface FileDialog {
    data object NewFolder : FileDialog
    data class Rename(val entry: FileEntry, val warning: String?) : FileDialog
    data class Delete(val entry: FileEntry, val warning: String?) : FileDialog
    /** In-app text / Markdown preview; [text] null while reading. */
    data class Preview(val entry: FileEntry, val text: String?, val markdown: Boolean) : FileDialog
}

/**
 * Everything a view of the 「文件」 page needs. [ListFileView] renders it today; part B's
 * `CardStackFileView(state, actions)` consumes the same object.
 */
data class FilesState(
    /** Absolute path of Download/IRIDIS (footnote), null when there is no Download folder. */
    val rootPath: String? = null,
    /** Root-relative folder shown ("" = root). */
    val rel: String = "",
    val crumbs: List<Crumb> = FilesPaths.crumbs(""),
    /** Sorted; at the root the system folders come first. */
    val entries: List<FileEntry> = emptyList(),
    val sort: FileSort = FileSort.NAME,
    val ascending: Boolean = true,
    val viewStyle: FileViewStyle = FileViewStyle.LIST,
    /** True until the current folder has been read once (no empty-state flash). */
    val loading: Boolean = true,
    val access: FilesAccess = FilesAccess.FULL,
    /** The folder could not be listed (missing or unreadable). */
    val unreadable: Boolean = false,
    /** One-line result of the last action ("已删除 …"); cleared by [FilesActions.consumeMessage]. */
    val message: String? = null,
    val dialog: FileDialog? = null,
) {
    val atRoot: Boolean get() = rel.isEmpty()
}

/** User intents. Every view style calls these; none touches files itself. */
interface FilesActions {
    fun navigate(rel: String)
    /** One level up; false when already at the root (let Back leave the page). */
    fun up(): Boolean
    /** Folder → open it; small text → in-app preview; anything else → another app (ACTION_VIEW). */
    fun onEntryClick(entry: FileEntry)
    fun openExternal(entry: FileEntry)
    fun share(entry: FileEntry)
    fun requestRename(entry: FileEntry)
    fun requestDelete(entry: FileEntry)
    fun requestNewFolder()
    fun confirmRename(newName: String)
    fun confirmDelete()
    fun confirmNewFolder(name: String)
    fun dismissDialog()
    /** Same sort again flips the direction. */
    fun setSort(sort: FileSort)
    fun setViewStyle(style: FileViewStyle)
    fun refresh()
    fun consumeMessage()
    fun canRename(entry: FileEntry): Boolean = FileProtection.canRename(entry.relPath)
    fun canDelete(entry: FileEntry): Boolean = FileProtection.canDelete(entry.relPath)
}

class FilesViewModel(app: Application) : AndroidViewModel(app), FilesActions {
    private val c = (app as IrisApp).container
    private val ctx = app

    private val local = MutableStateFlow(FilesState())

    /** The page state, with [FilesState.viewStyle] following the saved setting. */
    val state: StateFlow<FilesState> = combine(local, c.settings.settings.map { FileViewStyle.parse(it.fileViewStyle) }) { s, v -> s.copy(viewStyle = v) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    private var loadJob: Job? = null

    private fun downloads(): File? = runCatching {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) null
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }.getOrNull()

    /** Download/IRIDIS — the same root WorkspaceResolver / StorageLayout use. */
    fun root(): File? = downloads()?.let { WorkspaceResolver.downloadsRoot(it) }

    private fun allFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    init { refresh() }

    private fun update(block: (FilesState) -> FilesState) { local.value = block(local.value) }

    override fun refresh() = load(local.value.rel)

    private fun load(rel: String) {
        val clean = FilesPaths.normalize(rel) ?: ""
        update { it.copy(rel = clean, crumbs = FilesPaths.crumbs(clean), loading = it.rel != clean || it.loading) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val root = root()
            val listed = withContext(Dispatchers.IO) { root?.let { FilesIo.list(it, clean) } }
            val access = when {
                root == null -> FilesAccess.UNAVAILABLE
                allFilesAccess() -> FilesAccess.FULL
                else -> FilesAccess.LIMITED
            }
            update { s ->
                if (s.rel != clean) s
                else s.copy(
                    rootPath = root?.path,
                    entries = FilesSorting.sort(listed.orEmpty(), s.sort, s.ascending),
                    loading = false,
                    access = access,
                    unreadable = listed == null,
                )
            }
        }
    }

    override fun navigate(rel: String) = load(rel)

    override fun up(): Boolean {
        val s = local.value
        if (s.atRoot) return false
        load(FilesPaths.parent(s.rel))
        return true
    }

    override fun setSort(sort: FileSort) {
        val s = local.value
        val (next, asc) = FilesSorting.next(s.sort, s.ascending, sort)
        update { it.copy(sort = next, ascending = asc, entries = FilesSorting.sort(it.entries, next, asc)) }
    }

    override fun setViewStyle(style: FileViewStyle) = c.settings.update { it.copy(fileViewStyle = style.name) }

    override fun consumeMessage() = update { it.copy(message = null) }
    override fun dismissDialog() = update { it.copy(dialog = null) }

    override fun onEntryClick(entry: FileEntry) {
        when {
            entry.isDir -> load(entry.relPath)
            FilePreview.canPreview(entry) -> preview(entry)
            else -> openExternal(entry)
        }
    }

    private fun preview(entry: FileEntry) {
        val md = FilePreview.isMarkdown(entry.extension)
        update { it.copy(dialog = FileDialog.Preview(entry, null, md)) }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val f = root()?.let { FilesPaths.resolve(it, entry.relPath) } ?: error(com.iris.localai.I18n.s(com.iris.localai.R.string.files_a0b599))
                    val bytes = FilePreview.readHead(f)
                    if (!FilePreview.looksText(bytes)) com.iris.localai.I18n.s(com.iris.localai.R.string.files_f38b91) else String(bytes, Charsets.UTF_8)
                }.getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3b78ad, it.message) }
            }
            update { s -> val d = s.dialog; if (d is FileDialog.Preview && d.entry == entry) s.copy(dialog = d.copy(text = text)) else s }
        }
    }

    private fun mime(entry: FileEntry): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension) ?: FilePreview.mimeFallback(entry.extension)

    private fun uriFor(entry: FileEntry): android.net.Uri? {
        val f = root()?.let { FilesPaths.resolve(it, entry.relPath) }?.takeIf { it.isFile } ?: return null
        return runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f) }.getOrNull()
    }

    private fun start(intent: Intent, title: String, fail: String) {
        val ok = runCatching {
            ctx.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) update { it.copy(message = fail) }
    }

    override fun openExternal(entry: FileEntry) {
        if (entry.isDir) return
        val uri = uriFor(entry) ?: return update { it.copy(message = com.iris.localai.I18n.s(com.iris.localai.R.string.files_840000, entry.name)) }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(entry)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(i, com.iris.localai.I18n.s(com.iris.localai.R.string.files_14aecb), com.iris.localai.I18n.s(com.iris.localai.R.string.files_e73091, entry.name))
    }

    override fun share(entry: FileEntry) {
        if (entry.isDir) return
        val uri = uriFor(entry) ?: return update { it.copy(message = com.iris.localai.I18n.s(com.iris.localai.R.string.files_bdfa4a, entry.name)) }
        val i = Intent(Intent.ACTION_SEND).setType(mime(entry)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(i, com.iris.localai.I18n.s(com.iris.localai.R.string.files_c31f48), com.iris.localai.I18n.s(com.iris.localai.R.string.files_f54daa))
    }

    override fun requestRename(entry: FileEntry) {
        if (!FileProtection.canRename(entry.relPath)) return update { it.copy(message = com.iris.localai.I18n.s(com.iris.localai.R.string.files_4831d0)) }
        update { it.copy(dialog = FileDialog.Rename(entry, FileProtection.warningText(entry.relPath))) }
    }

    override fun requestDelete(entry: FileEntry) {
        if (!FileProtection.canDelete(entry.relPath)) return update { it.copy(message = com.iris.localai.I18n.s(com.iris.localai.R.string.files_b92433)) }
        update { it.copy(dialog = FileDialog.Delete(entry, FileProtection.warningText(entry.relPath))) }
    }

    override fun requestNewFolder() = update { it.copy(dialog = FileDialog.NewFolder) }

    private fun io(block: (File) -> FilesIo.Result) {
        update { it.copy(dialog = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { root()?.let(block) ?: FilesIo.Result.Failed(com.iris.localai.I18n.s(com.iris.localai.R.string.files_a3936b)) }
            update { it.copy(message = when (r) { is FilesIo.Result.Ok -> r.message; is FilesIo.Result.Failed -> r.message }) }
            refresh()
            c.agent.changed() // workspace / skills / knowledge listings elsewhere re-read
        }
    }

    override fun confirmRename(newName: String) {
        val d = local.value.dialog as? FileDialog.Rename ?: return
        io { FilesIo.rename(it, d.entry.relPath, newName) }
    }

    override fun confirmDelete() {
        val d = local.value.dialog as? FileDialog.Delete ?: return
        io { FilesIo.delete(it, d.entry.relPath) }
    }

    override fun confirmNewFolder(name: String) {
        val rel = local.value.rel
        io { FilesIo.mkdir(it, rel, name) }
    }

    /** Back from the 全部文件访问 screen: more files may be visible now. */
    fun onResume() = refresh()
}

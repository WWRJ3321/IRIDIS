package com.iris.localai.ui.agent

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.EmptyState
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.TextInputDialog
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.SmoothRoundedShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 1.12.2 pure helpers of the 文件 page (unit-tested). */
object FilesOps {
    data class Item(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long, val children: Int = 0)

    /** Folders first, then files, each by name (case-insensitive); hidden dot-files skipped. */
    fun list(root: File, rel: String): List<Item> {
        val dir = if (rel.isEmpty()) root else File(root, rel)
        val kids = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
        return kids.map { f ->
            val p = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            Item(f.name, p, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified(),
                if (f.isDirectory) f.listFiles()?.count { !it.name.startsWith(".") } ?: 0 else 0)
        }.sortedWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
    }

    /** "a/b/c" → [("工作区",""), ("a","a"), ("b","a/b"), ("c","a/b/c")]. */
    fun crumbs(rel: String): List<Pair<String, String>> {
        val out = mutableListOf(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4fa8c1) to "")
        var acc = ""
        rel.split('/').filter { it.isNotEmpty() }.forEach { seg -> acc = if (acc.isEmpty()) seg else "$acc/$seg"; out += seg to acc }
        return out
    }

    fun parent(rel: String): String = rel.substringBeforeLast('/', "")

    /** A plain file name: no separators, not "." / "..", ≤ 120 chars. */
    fun validName(name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && n.length <= 120 && n != "." && n != ".." && n.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    /** "a.txt" → "a (2).txt" while the name is taken. */
    fun unique(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var i = 2
        while (true) {
            val n = name.substring(0, dot) + " ($i)" + name.substring(dot)
            if (!File(dir, n).exists()) return n
            i++
        }
    }

    fun size(bytes: Long): String = AgentText.size(bytes)
}

/**
 * 1.12.2 文件: a small file manager for the agent's workspace — browse folders, preview text,
 * import from the phone, new folder, rename, export, delete (backed up, undoable).
 */
@Composable
fun FilesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val revision by agent.revision.collectAsStateWithLifecycle()
    val ws by agent.workspace.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = Iris.colors
    var rel by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf<FilesOps.Item?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FilesOps.Item?>(null) }
    var deleting by remember { mutableStateOf<FilesOps.Item?>(null) }
    var exporting by remember { mutableStateOf<FilesOps.Item?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = rel.isNotEmpty()) { rel = FilesOps.parent(rel) }

    // 1.13.0: null while the folder is read, so an empty state does not flash before the list.
    val loaded by produceState<Pair<String, List<FilesOps.Item>>?>(null, revision, ws, rel) {
        value = rel to withContext(Dispatchers.IO) { runCatching { FilesOps.list(agent.sandbox.root, rel) }.getOrDefault(emptyList()) }
    }
    val items = loaded?.takeIf { it.first == rel }?.second.orEmpty()
    val loading = loaded?.first != rel
    val reduceMotion = com.iris.localai.ui.theme.LocalReduceMotion.current
    val undoCount by produceState(0, revision) { value = withContext(Dispatchers.IO) { agent.undo.count() } }

    fun dirFile(): File = if (rel.isEmpty()) agent.sandbox.root else agent.sandbox.resolve(rel)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val dir = dirFile().apply { mkdirs() }
                uris.count { uri ->
                    runCatching {
                        val raw = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                            if (cur.moveToFirst()) cur.getString(0) else null
                        } ?: "file"
                        val name = FilesOps.unique(dir, raw.replace('/', '_'))
                        context.contentResolver.openInputStream(uri)!!.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                    }.isSuccess
                }
            }
            message = if (n == uris.size) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_121c52, n) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ec03b6, n, uris.size - n)
            agent.changed()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = exporting
        exporting = null
        if (uri == null || f == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> agent.sandbox.resolve(f.path).inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
            }
            message = if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_234c82, f.name) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dd51ab)
        }
    }

    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2a0c47), onBack, scrollUnder = false, actions = {
        PressableIcon(Icons.AutoMirrored.Outlined.Undo, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e9f3fb), enabled = undoCount > 0) {
            scope.launch {
                message = withContext(Dispatchers.IO) { runCatching { agent.undo.undoLast(agent.sandbox) }.getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6336b9, it.message) } } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_40caf8)
                agent.changed()
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item(key = "crumbs") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val crumbs = FilesOps.crumbs(rel)
                    crumbs.forEachIndexed { i, (label, path) ->
                        val last = i == crumbs.lastIndex
                        Text(
                            label, style = if (last) IrisType.rowStrong else IrisType.secondary,
                            color = if (last) c.text else c.accent, maxLines = 1,
                            modifier = Modifier.clip(IrisRadius.smallShape).clickable(enabled = !last) { rel = path }.padding(horizontal = IrisDimens.space4, vertical = IrisDimens.space4),
                        )
                        if (!last) Text("›", style = IrisType.secondary, color = c.textTertiary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space4)) {
                    com.iris.localai.ui.components.IrisButton(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8d9a07), onClick = { importer.launch(arrayOf("*/*")) }, icon = Icons.Outlined.Add,
                        style = com.iris.localai.ui.components.IrisButtonStyle.Tonal, compact = true,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = IrisDimens.space4))
                    com.iris.localai.ui.components.IrisButton(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3a485), onClick = { newFolder = true }, icon = Icons.Outlined.CreateNewFolder,
                        style = com.iris.localai.ui.components.IrisButtonStyle.Tonal, compact = true,
                    )
                }
            }
            message?.let { m -> item(key = "msg") { GroupFootnote(m) } }
            if (items.isEmpty() && !loading) {
                item(key = "empty") {
                    EmptyState(
                        title = if (rel.isEmpty()) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e83b67) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e96910),
                        message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1b4e0b),
                        icon = Icons.Outlined.FolderOpen,
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            }
            itemsIndexed(items, key = { _, it -> it.path }) { i, it ->
                val first = i == 0
                val last = i == items.lastIndex
                val r = IrisRadius.large
                val shape = SmoothRoundedShape(
                    topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
                    bottomEnd = if (last) r else 0.dp, bottomStart = if (last) r else 0.dp,
                )
                Column((if (reduceMotion) Modifier else Modifier.animateItem()).padding(horizontal = IrisDimens.groupGutter).fillMaxWidth().clip(shape).background(c.surfaceElevated)) {
                    if (!first) Divider()
                    SettingsRow(
                        it.name,
                        icon = if (it.dir) Icons.Outlined.Folder else Icons.Outlined.Description,
                        subtitle = (if (it.dir) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b5d2d7, it.children) else FilesOps.size(it.size)) + " · " +
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(it.modified)),
                        subtitleMaxLines = 1,
                        onClick = { if (it.dir) rel = it.path else viewing = it },
                        trailing = {
                            Box {
                                PressableIcon(Icons.Outlined.MoreVert, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ea320a, it.name), size = 40.dp, iconSize = IrisDimens.space20) { menuFor = it.path }
                                IrisMenu(expanded = menuFor == it.path, onDismiss = { menuFor = null }) {
                                    IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b), Icons.Outlined.DriveFileRenameOutline) { menuFor = null; renaming = it }
                                    if (!it.dir) IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_961d7f), Icons.Outlined.FileUpload) { menuFor = null; exporting = it; exportLauncher.launch(it.name) }
                                    IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), Icons.Outlined.DeleteOutline, destructive = true) { menuFor = null; deleting = it }
                                }
                            }
                        },
                    )
                }
            }
            item(key = "foot") {
                GroupFootnote(ws.root.path + com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d0f22a), monospace = false, modifier = Modifier.padding(top = IrisDimens.space8))
            }
        }
    }

    message?.let { m -> androidx.compose.runtime.LaunchedEffect(m) { kotlinx.coroutines.delay(4000); if (message == m) message = null } }

    viewing?.let { f ->
        val text by produceState<String?>(null, f) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = agent.sandbox.resolve(f.path)
                    val bytes = file.inputStream().use { s -> s.readNBytes(64 * 1024) }
                    if (bytes.take(4096).any { it == 0.toByte() }) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d7e40a, FilesOps.size(f.size))
                    else String(bytes, Charsets.UTF_8).let { if (f.size > bytes.size) it + com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8971dc) else it }
                }.getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3b78ad, it.message) }
            }
        }
        IrisDialog(onDismiss = { viewing = null }, title = f.name, confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91), onConfirm = { viewing = null }, dismissText = null) {
            Box(
                Modifier.fillMaxWidth().clip(IrisRadius.smallShape).background(c.codeBackground)
                    .heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            ) {
                SelectionContainer { Text(text ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a), style = IrisType.monoSmall, color = c.text) }
            }
        }
    }
    if (newFolder) {
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3a485), initial = "", placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d7ec2d),
            onConfirm = { name ->
                newFolder = false
                if (!FilesOps.validName(name)) { message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0780c6); return@TextInputDialog }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { File(dirFile(), FilesOps.unique(dirFile(), name.trim())).mkdirs() }.getOrDefault(false) }
                    message = if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_76ace8, name.trim()) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c71e1b)
                    agent.changed()
                }
            },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { f ->
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b), initial = f.name,
            onConfirm = { name ->
                renaming = null
                val n = name.trim()
                if (!FilesOps.validName(n) || n == f.name) { if (n != f.name) message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0780c6); return@TextInputDialog }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val from = agent.sandbox.resolve(f.path)
                            val to = File(from.parentFile, n)
                            !to.exists() && from.renameTo(to)
                        }.getOrDefault(false)
                    }
                    message = if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_eac80a, n) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f32d54)
                    agent.changed()
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { f ->
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4a78de, f.name),
            message = if (f.dir) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_47d706, f.children) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0fbeac),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
            onConfirm = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val target = agent.sandbox.resolve(f.path)
                            target.walkBottomUp().filter { it.isFile }.forEach { agent.undo.snapshot(agent.sandbox, it, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad)) }
                            target.deleteRecursively()
                        }.getOrDefault(false)
                    }
                    message = if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b0b6b9, f.name) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_acf066)
                    agent.changed()
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

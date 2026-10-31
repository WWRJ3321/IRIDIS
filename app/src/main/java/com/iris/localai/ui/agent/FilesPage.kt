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
        val out = mutableListOf("工作区" to "")
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
            message = if (n == uris.size) "已导入 $n 个文件" else "已导入 $n 个，${uris.size - n} 个失败"
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
            message = if (ok) "已导出 ${f.name}" else "导出失败"
        }
    }

    SubpageScaffold("文件", onBack, actions = {
        PressableIcon(Icons.AutoMirrored.Outlined.Undo, "撤销上次修改", enabled = undoCount > 0) {
            scope.launch {
                message = withContext(Dispatchers.IO) { runCatching { agent.undo.undoLast(agent.sandbox) }.getOrElse { "撤销失败：${it.message}" } } ?: "没有可撤销的修改"
                agent.changed()
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item(key = "crumbs") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = IrisDimens.groupGutter, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val crumbs = FilesOps.crumbs(rel)
                    crumbs.forEachIndexed { i, (label, path) ->
                        val last = i == crumbs.lastIndex
                        Text(
                            label, style = if (last) IrisType.rowStrong else IrisType.secondary,
                            color = if (last) c.text else c.accent, maxLines = 1,
                            modifier = Modifier.clip(IrisRadius.smallShape).clickable(enabled = !last) { rel = path }.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                        if (!last) Text("›", style = IrisType.secondary, color = c.textTertiary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = 4.dp)) {
                    com.iris.localai.ui.components.IrisButton(
                        "导入", onClick = { importer.launch(arrayOf("*/*")) }, icon = Icons.Outlined.Add,
                        style = com.iris.localai.ui.components.IrisButtonStyle.Tonal, compact = true,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                    com.iris.localai.ui.components.IrisButton(
                        "新建文件夹", onClick = { newFolder = true }, icon = Icons.Outlined.CreateNewFolder,
                        style = com.iris.localai.ui.components.IrisButtonStyle.Tonal, compact = true,
                    )
                }
            }
            message?.let { m -> item(key = "msg") { GroupFootnote(m) } }
            if (items.isEmpty() && !loading) {
                item(key = "empty") {
                    EmptyState(
                        title = if (rel.isEmpty()) "工作区还是空的" else "空文件夹",
                        message = "智能体写入的文件会出现在这里，也可以从手机导入",
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
                        subtitle = (if (it.dir) "${it.children} 项" else FilesOps.size(it.size)) + " · " +
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(it.modified)),
                        subtitleMaxLines = 1,
                        onClick = { if (it.dir) rel = it.path else viewing = it },
                        trailing = {
                            Box {
                                PressableIcon(Icons.Outlined.MoreVert, "更多：${it.name}", size = 40.dp, iconSize = 20.dp) { menuFor = it.path }
                                IrisMenu(expanded = menuFor == it.path, onDismiss = { menuFor = null }) {
                                    IrisMenuItem("重命名", Icons.Outlined.DriveFileRenameOutline) { menuFor = null; renaming = it }
                                    if (!it.dir) IrisMenuItem("导出到手机", Icons.Outlined.FileUpload) { menuFor = null; exporting = it; exportLauncher.launch(it.name) }
                                    IrisMenuItem("删除", Icons.Outlined.DeleteOutline, destructive = true) { menuFor = null; deleting = it }
                                }
                            }
                        },
                    )
                }
            }
            item(key = "foot") {
                GroupFootnote(ws.root.path + "\n删除和覆盖前自动备份，右上角可撤销。", monospace = false, modifier = Modifier.padding(top = IrisDimens.space8))
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
                    if (bytes.take(4096).any { it == 0.toByte() }) "（二进制文件，${FilesOps.size(f.size)}，可导出后用其他 App 打开）"
                    else String(bytes, Charsets.UTF_8).let { if (f.size > bytes.size) it + "\n…（只显示前 64 KB，完整内容请导出）" else it }
                }.getOrElse { "读取失败：${it.message}" }
            }
        }
        IrisDialog(onDismiss = { viewing = null }, title = f.name, confirmText = "关闭", onConfirm = { viewing = null }, dismissText = null) {
            Box(
                Modifier.fillMaxWidth().clip(IrisRadius.smallShape).background(c.codeBackground)
                    .heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            ) {
                SelectionContainer { Text(text ?: "正在读取…", style = IrisType.monoSmall, color = c.text) }
            }
        }
    }
    if (newFolder) {
        TextInputDialog(
            title = "新建文件夹", initial = "", placeholder = "名称",
            onConfirm = { name ->
                newFolder = false
                if (!FilesOps.validName(name)) { message = "名称不可用"; return@TextInputDialog }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { File(dirFile(), FilesOps.unique(dirFile(), name.trim())).mkdirs() }.getOrDefault(false) }
                    message = if (ok) "已新建 ${name.trim()}" else "新建失败"
                    agent.changed()
                }
            },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { f ->
        TextInputDialog(
            title = "重命名", initial = f.name,
            onConfirm = { name ->
                renaming = null
                val n = name.trim()
                if (!FilesOps.validName(n) || n == f.name) { if (n != f.name) message = "名称不可用"; return@TextInputDialog }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val from = agent.sandbox.resolve(f.path)
                            val to = File(from.parentFile, n)
                            !to.exists() && from.renameTo(to)
                        }.getOrDefault(false)
                    }
                    message = if (ok) "已重命名为 $n" else "重命名失败（可能已有同名文件）"
                    agent.changed()
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { f ->
        ConfirmDialog(
            title = "删除 ${f.name}？",
            message = if (f.dir) "文件夹里的 ${f.children} 项也会删除。删除前会自动备份，可撤销。" else "删除前会自动备份，可撤销。",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val target = agent.sandbox.resolve(f.path)
                            target.walkBottomUp().filter { it.isFile }.forEach { agent.undo.snapshot(agent.sandbox, it, "删除") }
                            target.deleteRecursively()
                        }.getOrDefault(false)
                    }
                    message = if (ok) "已删除 ${f.name}" else "删除失败"
                    agent.changed()
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

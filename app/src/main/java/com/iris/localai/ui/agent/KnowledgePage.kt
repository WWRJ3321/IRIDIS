package com.iris.localai.ui.agent

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.knowledge.KbFileInfo
import com.iris.localai.knowledge.KbFormats
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.HairlineDivider
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 2.1.0 texts of the 知识库 page (unit-tested). */
object KnowledgeText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7e4333)
    val TOGGLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_bc270f)
    val TOGGLE_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_db3b61)
    val REBUILD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_be0ade)
    val ADD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2a6c4d)
    val DELETE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad)
    val PASTE_TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_32c65d)
    const val MAX_TITLE = 40

    /** 2.2.17 D: a pasted entry's title — its first non-blank line (markdown #/-/> stripped), short. */
    fun titleFrom(text: String): String {
        val line = text.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ').trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (line.length > MAX_TITLE) line.take(MAX_TITLE).trimEnd() else line.ifEmpty { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8b3889) }
    }

    /** File name for a pasted entry: the title, made safe, as .md (indexed like an imported file). */
    fun fileName(title: String): String {
        val t = title.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000\\n\\r\\t]"), " ").replace(Regex(" +"), " ").trim().take(MAX_TITLE).trimEnd('.', ' ')
        return t.ifEmpty { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8b3889) } + ".md"
    }
    /** 2.3.2 */
    val NEW_TEXT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_new_text)
    val EDIT_TEXT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_edit_text)
    val READONLY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_readonly)
    val EMPTY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ce81e3)
    val NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_16ca25)

    fun status(files: Int, chunks: Int, unsupported: Int): String =
        if (files == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e93a44) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6b7690, files, chunks) + if (unsupported > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f07c0e, unsupported) else ""

    /** [appRoot] = Download/IRIDIS (2.1.1: the 知识库 is an app resource beside workspace/, not inside it). */
    fun folder(appRoot: String): String = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_36bea8, appRoot, KnowledgeBase.FOLDER)

    /** 2.2.9: 「42 段 · 引用 6 次」 / 「正在读取…」. */
    fun fileLine(f: KbFileInfo): String = if (!f.indexed) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a) else if (f.chunks == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d554e5) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_882d2c, f.chunks, f.cites)

    /** Badge in the file tile: the extension, upper case, at most 4 letters. */
    fun badge(path: String): String = KbFormats.ext(path).uppercase().take(4).ifEmpty { "TXT" }

    fun entry(files: Int, chunks: Int): String = if (files == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e93a44) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6b7690, files, chunks)
}

/** 2.2.9: the row in 记忆与上下文 that opens the 知识库 page, plus 引用知识库. */
@Composable
fun KnowledgeEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val files by produceState<List<KbFileInfo>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { container.agent.knowledge.files() }.getOrNull() }
    }
    SectionTitle(KnowledgeText.TITLE)
    SettingsGroup {
        SettingsRow(
            KnowledgeText.TITLE, icon = IrisIcons.Folder,
            subtitle = files?.let { l -> KnowledgeText.entry(l.size, l.sumOf { it.chunks }) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a),
            onClick = onOpen, trailing = { com.iris.localai.ui.components.Chevron() },
        )
        Divider()
        SwitchRow(KnowledgeText.TOGGLE, settings.knowledgeInChat, subtitle = KnowledgeText.TOGGLE_SUB, icon = IrisIcons.Memory) { on ->
            container.settings.update { it.copy(knowledgeInChat = on) }
        }
    }
}

/** 2.2.9 D1: 知识库 — files with chunk / cite counts and indexing state, add, delete, 重建索引. */
@Composable
fun KnowledgePage(
    onBack: () -> Unit,
    /**
     * 2.5.0 Beta6: 新建文字 / 编辑文字 open as their own route (MainActivity Route.KB_EDITOR, push / pop slide and
     * predictive back). The file to edit is handed over in [KbEditorNav]. null = editor shown in place (old behaviour).
     */
    onOpenEditor: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val agent = container.agent
    val ws by agent.workspace.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KbFileInfo?>(null) }
    // 2.3.2: current folder ("" = knowledge/), the editor (null = closed; path "" = new text), folder dialogs.
    // 2.5.0 Beta6: saveable, so the folder survives a trip to the editor route (the page leaves composition).
    var folder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf<KbEditorState?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deletingFolder by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<KbFileInfo?>(null) }
    var showTrash by remember { mutableStateOf(false) } // 2.3.3 回收站
    val c = Iris.colors
    fun toast(id: Int) = Toast.makeText(context, com.iris.localai.I18n.s(id), Toast.LENGTH_SHORT).show()

    // Listed first (unread files show 「正在读取…」), then the index is brought up to date and listed again.
    val folders by produceState(emptyList<String>(), tick, ws) {
        value = withContext(Dispatchers.IO) { runCatching { agent.knowledge.folders() }.getOrDefault(emptyList()) }
    }
    val trashItems by produceState(emptyList<com.iris.localai.knowledge.KbTrash.Item>(), tick, ws) {
        value = withContext(Dispatchers.IO) { runCatching { agent.knowledge.trashItems() }.getOrDefault(emptyList()) }
    }
    val files by produceState<List<KbFileInfo>?>(null, tick, ws) {
        value = withContext(Dispatchers.IO) { runCatching { agent.knowledge.files() }.getOrNull() }
        if (value?.any { !it.indexed } == true) {
            value = withContext(Dispatchers.IO) { runCatching { agent.knowledge.refresh(); agent.knowledge.files() }.getOrNull() }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= com.iris.localai.knowledge.KbFormats.maxBytes(name, KnowledgeBase.MAX_BYTES) } }
                        bytes != null && agent.knowledge.add(name, bytes, folder) != null
                    }.getOrDefault(false)
                }
            }
            busy = false
            tick++
            Toast.makeText(context, if (added == uris.size) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0cf789, added) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_321562, added, uris.size), Toast.LENGTH_SHORT).show()
        }
    }

    // 2.5.0 Beta6: route mode hands the target to KbEditorNav and pushes; in-place mode keeps the old local state.
    fun openEditor(ed: KbEditorState) {
        if (onOpenEditor != null) { KbEditorNav.target = ed.copy(folder = folder); onOpenEditor() } else editor = ed
    }

    editor?.let { ed ->
        KbEditorPage(ed, onClose = { editor = null }) { title, body ->
            scope.launch {
                val ok = saveKbText(agent, ed.copy(folder = folder), title, body)
                tick++
                if (ok) { editor = null; toast(com.iris.localai.R.string.kb232_saved) } else toast(com.iris.localai.R.string.kb232_failed)
            }
        }
        return
    }

    SubpageScaffold(KnowledgeText.TITLE, onBack) {
        ScrollBody {
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                KnowledgeText.ADD, { pick.launch(arrayOf("text/*", "application/json", "application/epub+zip", "application/octet-stream")) },
                icon = Icons.Outlined.Add, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
            )
            Spacer(Modifier.height(IrisDimens.space8))
            // 2.2.17 D: paste text straight in (saved as a .md file and indexed like any import).
            IrisButton(
                KnowledgeText.NEW_TEXT, { openEditor(KbEditorState("", "", "")) }, style = com.iris.localai.ui.components.IrisButtonStyle.Tonal,
                icon = IrisIcons.Copy, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
            )
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_new_folder), { newFolder = true }, style = com.iris.localai.ui.components.IrisButtonStyle.Tonal,
                icon = IrisIcons.FolderPlus, enabled = !busy && com.iris.localai.knowledge.KbPaths.depth(folder) < com.iris.localai.knowledge.KbPaths.MAX_FOLDER_DEPTH,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
            )
            Spacer(Modifier.height(IrisDimens.space16))
            // 2.3.2: breadcrumbs (知识库 › a › b), each tappable.
            KbCrumbs(folder) { folder = it }
            val subs = com.iris.localai.knowledge.KbPaths.subfolders(folders, folder)
            val list = files?.filter { com.iris.localai.knowledge.KbPaths.parent(it.path) == folder }
            if (list == null) {
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a), style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(horizontal = IrisDimens.groupGutter + IrisDimens.rowPadding, vertical = IrisDimens.space16))
            } else if (list.isEmpty() && subs.isEmpty()) {
                Text(KnowledgeText.EMPTY, style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(horizontal = IrisDimens.groupGutter + IrisDimens.rowPadding, vertical = IrisDimens.space16))
            } else {
                SettingsGroup {
                    subs.forEachIndexed { i, sub ->
                        if (i > 0) HairlineDivider()
                        KbFolderRow(sub, onOpen = { folder = sub }, onRename = { renaming = sub }, onDelete = { deletingFolder = sub })
                    }
                    list.forEachIndexed { i, f ->
                        if (i > 0 || subs.isNotEmpty()) HairlineDivider()
                        KbFileRow(
                            f, onDelete = { deleting = f }, onMove = { moving = f },
                            onOpen = {
                                if (!com.iris.localai.knowledge.KbPaths.editable(f.path)) { Toast.makeText(context, KnowledgeText.READONLY, Toast.LENGTH_SHORT).show() }
                                else scope.launch {
                                    val text = withContext(Dispatchers.IO) { runCatching { agent.knowledge.readText(f.path) }.getOrNull() }
                                    if (text == null) toast(com.iris.localai.R.string.kb232_failed)
                                    else openEditor(KbEditorState(f.path, f.path.substringAfterLast('/').substringBeforeLast('.'), text))
                                }
                            },
                        )
                    }
                }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b271e4))
            SettingsGroup {
                SettingsRow(
                    KnowledgeText.REBUILD, icon = IrisIcons.Reload, enabled = !busy,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_eb0217) + KnowledgeText.folder(ws.appRoot.path),
                    subtitleMaxLines = 4,
                    onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { agent.knowledge.refresh(force = true) } }
                            busy = false
                            tick++
                        }
                    },
                )
            }
            GroupFootnote(KnowledgeText.NOTE)
            // 2.3.3: 回收站 — deleted files and folders, kept 30 days.
            SettingsGroup {
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_trash), icon = IrisIcons.Trash,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_trash_sub, trashItems.size),
                    onClick = { showTrash = true },
                    trailing = { com.iris.localai.ui.components.Chevron() },
                )
            }
        }
    }

    if (showTrash) {
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { showTrash = false }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_trash),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91), onConfirm = { showTrash = false },
            dismissText = if (trashItems.isEmpty()) null else com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_empty_trash),
            onDismissClick = {
                scope.launch { withContext(Dispatchers.IO) { runCatching { agent.knowledge.emptyTrash() } }; tick++ }
            },
        ) {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (trashItems.isEmpty()) Text(com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_trash_empty), style = IrisType.secondary, color = c.textTertiary)
                trashItems.forEach { t ->
                    val days = ((com.iris.localai.knowledge.KbTrash.KEEP_MS - (System.currentTimeMillis() - t.deletedAt)) / 86_400_000L).coerceAtLeast(0)
                    SettingsRow(
                        t.path, icon = if (t.folder) IrisIcons.Folder else IrisIcons.Copy,
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_days_left, days),
                        trailing = {
                            Row {
                                com.iris.localai.ui.components.IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_restore), {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { runCatching { agent.knowledge.restore(t.id) }.getOrNull() }
                                        if (r == null) toast(com.iris.localai.R.string.kb232_failed)
                                        tick++
                                    }
                                }, style = com.iris.localai.ui.components.IrisButtonStyle.Text, compact = true)
                                com.iris.localai.ui.components.IrisButton(KnowledgeText.DELETE, {
                                    scope.launch { withContext(Dispatchers.IO) { runCatching { agent.knowledge.removeFromTrash(t.id) } }; tick++ }
                                }, style = com.iris.localai.ui.components.IrisButtonStyle.Text, compact = true)
                            }
                        },
                    )
                }
            }
        }
    }

    if (newFolder) com.iris.localai.ui.components.TextInputDialog(
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_new_folder), initial = "",
        placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_folder_name),
        onConfirm = { name ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { agent.knowledge.mkdir(folder, name) }.getOrNull() }
                if (r == null) toast(com.iris.localai.R.string.kb232_folder_bad)
                tick++
            }
        },
        onDismiss = { newFolder = false },
    )

    renaming?.let { path ->
        com.iris.localai.ui.components.TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_rename), initial = path.substringAfterLast('/'),
            placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_folder_name),
            onConfirm = { name ->
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { agent.knowledge.renameFolder(path, name) }.getOrNull() }
                    if (r == null) toast(com.iris.localai.R.string.kb232_folder_bad)
                    tick++
                }
            },
            onDismiss = { renaming = null },
        )
    }

    deletingFolder?.let { path ->
        // 2.3.3: a non-empty folder asks first (it goes to the 回收站); an empty one goes at once.
        val empty = remember(path) { agent.knowledge.folderEmpty(path) }
        fun doDelete() = scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { agent.knowledge.deleteFolder(path) }.getOrDefault(false) }
            if (!ok) toast(com.iris.localai.R.string.kb232_failed)
            tick++
        }
        if (empty) { androidx.compose.runtime.LaunchedEffect(path) { doDelete(); deletingFolder = null } }
        else ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_delete_folder, path.substringAfterLast('/')),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_to_trash),
            confirmText = KnowledgeText.DELETE,
            onConfirm = { doDelete() },
            onDismiss = { deletingFolder = null },
        )
    }

    moving?.let { f ->
        val here = com.iris.localai.knowledge.KbPaths.parent(f.path)
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { moving = null }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_move),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2), onConfirm = { moving = null }, dismissText = null,
        ) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                (listOf("") + folders).filter { it != here }.forEach { target ->
                    SettingsRow(
                        if (target.isEmpty()) com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_root) else target, icon = IrisIcons.Folder,
                        onClick = {
                            moving = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { agent.knowledge.move(f.path, target) }.getOrNull() }
                                if (r == null) toast(com.iris.localai.R.string.kb232_failed)
                                tick++
                            }
                        },
                    )
                }
            }
        }
    }

    deleting?.let { f ->
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_883acf, f.path.substringAfterLast('/')),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.kb233_to_trash),
            confirmText = KnowledgeText.DELETE,
            onConfirm = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { agent.knowledge.delete(f.path) }.getOrDefault(false) }
                    if (!ok) Toast.makeText(context, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_acf066), Toast.LENGTH_SHORT).show()
                    tick++
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun KbFileRow(f: KbFileInfo, onDelete: () -> Unit, onMove: () -> Unit = {}, onOpen: () -> Unit = {}) {
    val c = Iris.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = IrisDimens.space16, top = IrisDimens.space16, bottom = IrisDimens.space16, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(IrisRadius.smallShape).background(c.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(KnowledgeText.badge(f.path), style = IrisType.caption, color = c.textSecondary, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(start = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
            Text(f.path.substringAfterLast('/'), style = IrisType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(KnowledgeText.fileLine(f), style = IrisType.caption, color = if (f.indexed) c.textSecondary else c.warning)
        }
        Box {
            PressableIcon(IrisIcons.More, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0ec9ea), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { menu = true }
            IrisMenu(menu, { menu = false }) {
                if (com.iris.localai.knowledge.KbPaths.editable(f.path)) IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_edit), IrisIcons.Edit) { menu = false; onOpen() }
                IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_move), IrisIcons.Folder) { menu = false; onMove() }
                IrisMenuItem(KnowledgeText.DELETE, null, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

/** 2.3.2: a sub-folder row: tap opens it; ⋯ rename / delete. */
@Composable
private fun KbFolderRow(path: String, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = Iris.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = IrisDimens.space16, top = IrisDimens.space12, bottom = IrisDimens.space12, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(IrisRadius.smallShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(IrisIcons.Folder, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = IrisDimens.space16)) {
            Text(path.substringAfterLast('/'), style = IrisType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_folder), style = IrisType.caption, color = c.textSecondary)
        }
        Box {
            PressableIcon(IrisIcons.More, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0ec9ea), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { menu = true }
            IrisMenu(menu, { menu = false }) {
                IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_rename), IrisIcons.Edit) { menu = false; onRename() }
                IrisMenuItem(KnowledgeText.DELETE, null, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

/** 2.3.2: 知识库 › a › b — each part opens that folder. */
@Composable
private fun KbCrumbs(folder: String, onOpen: (String) -> Unit) {
    val c = Iris.colors
    val crumbs = com.iris.localai.knowledge.KbPaths.crumbs(folder, KnowledgeText.TITLE)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (path, label) ->
            if (i > 0) Text(" › ", style = IrisType.secondary, color = c.textTertiary)
            val last = i == crumbs.lastIndex
            Text(
                label, style = IrisType.secondary, color = if (last) c.text else c.accent, maxLines = 1,
                modifier = if (last) Modifier else Modifier.clickable { onOpen(path) },
            )
        }
    }
}

/**
 * 2.3.2: what the editor opens on. [path] "" = a new text (saved as 「title.md」 in [folder]).
 * 2.5.0 Beta6: [folder] added (default "") so the editor route knows where a new text goes.
 */
data class KbEditorState(val path: String, val title: String, val text: String, val folder: String = "")

/**
 * 2.5.0 Beta6: hand-over for the 知识库 › 编辑 route. The enum back stack in MainActivity carries no
 * arguments, so KnowledgePage puts the opened file (path, title, text already read, current folder) here
 * right before push(Route.KB_EDITOR). Process-scoped and never cleared (it is simply overwritten on the next
 * open), so the exiting page never sees null mid-animation; after process death it is null and the route pops.
 */
object KbEditorNav {
    var target by mutableStateOf<KbEditorState?>(null)
}

/** 2.5.0 Beta6: shared by the in-place editor and the route — overwrite an existing file, or add a new .md. */
private suspend fun saveKbText(agent: com.iris.localai.agent.AgentContainer, ed: KbEditorState, title: String, body: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            if (ed.path.isNotEmpty()) agent.knowledge.writeText(ed.path, body)
            else agent.knowledge.add(KnowledgeText.fileName(title.ifBlank { KnowledgeText.titleFrom(body) }), body.toByteArray(Charsets.UTF_8), ed.folder)?.also { agent.knowledge.refresh() } != null
        }.getOrDefault(false)
    }

/** 2.5.0 Beta6: 知识库 › 新建文字 / 编辑文字 as a real page (MainActivity Route.KB_EDITOR). */
@Composable
fun KbEditorRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val scope = rememberCoroutineScope()
    // Read once: the target cannot change under an open editor.
    val ed = remember { KbEditorNav.target }
    if (ed == null) {
        // Restored after process death with nothing to edit: go back to the list.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    KbEditorPage(ed, onClose = onBack, handleBack = false) { title, body ->
        scope.launch {
            val ok = saveKbText(agent, ed, title, body)
            if (ok) {
                Toast.makeText(context, com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_saved), Toast.LENGTH_SHORT).show()
                onBack()
            } else Toast.makeText(context, com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 2.3.2 文字编辑页 (was the 2.2.17 「粘贴文字」 dialog): title + a multi-line box typed by hand. 「粘贴」 inserts
 * the clipboard at the cursor only when tapped; the clipboard is never read on its own. An existing file
 * keeps its name (title read-only) and is overwritten in place.
 */
@Composable
private fun KbEditorPage(
    state: KbEditorState, onClose: () -> Unit,
    /** 2.5.0 Beta6: false on the route — MainActivity's PredictiveBackHandler pops it (a local BackHandler would shadow it). */
    handleBack: Boolean = true,
    onSave: (String, String) -> Unit,
) {
    val c = Iris.colors
    val context = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val isNew = state.path.isEmpty()
    var title by remember { mutableStateOf(state.title) }
    var body by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(state.text)) }
    androidx.activity.compose.BackHandler(enabled = handleBack, onBack = onClose)
    SubpageScaffold(
        if (isNew) KnowledgeText.NEW_TEXT else KnowledgeText.EDIT_TEXT, onClose, scrollUnder = false,
        actions = {
            com.iris.localai.ui.components.IrisButton(
                com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb), { onSave(title, body.text) },
                enabled = body.text.isNotBlank(),
            )
        },
    ) {
        Column(
            // 2.5.0 Beta6: card-edge inset token (groupGutter) like the other subpages' free elements.
            Modifier.fillMaxSize().imePadding().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space8),
            verticalArrangement = Arrangement.spacedBy(IrisDimens.space8),
        ) {
            androidx.compose.material3.OutlinedTextField(
                title, { title = it }, singleLine = true, enabled = isNew,
                textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text(KnowledgeText.PASTE_TITLE, style = IrisType.body, color = c.textTertiary) },
                shape = IrisRadius.mediumShape,
                colors = com.iris.localai.ui.components.irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            IrisButton(
                com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_paste), {
                    val t = clip.getText()?.text.orEmpty()
                    if (t.isEmpty()) Toast.makeText(context, com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_clip_empty), Toast.LENGTH_SHORT).show()
                    else {
                        val sel = body.selection
                        val next = body.text.substring(0, sel.min) + t + body.text.substring(sel.max)
                        body = androidx.compose.ui.text.input.TextFieldValue(next.take((KnowledgeBase.MAX_BYTES / 4).toInt()), androidx.compose.ui.text.TextRange((sel.min + t.length).coerceAtMost(next.length)))
                    }
                },
                style = com.iris.localai.ui.components.IrisButtonStyle.Tonal, icon = IrisIcons.Copy,
            )
            androidx.compose.material3.OutlinedTextField(
                body, { body = it },
                textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_body_hint), style = IrisType.body, color = c.textTertiary) },
                shape = IrisRadius.mediumShape,
                colors = com.iris.localai.ui.components.irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

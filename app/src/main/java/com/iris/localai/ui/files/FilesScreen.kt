package com.iris.localai.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.files.FileEntry
import com.iris.localai.files.FileNames
import com.iris.localai.files.FileSizes
import com.iris.localai.files.FileSort
import com.iris.localai.files.FileViewStyle
import com.iris.localai.files.SystemFolder
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.EmptyState
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.markdown.Markdown
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.theme.SmoothRoundedShape

/** Visible copy of the 「文件」 page. */
internal object FilesText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2a0c47)
    val ARCHIVE_MODE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_331dd7)
    val VIEW_STYLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_096449)
    val SYSTEM_BADGE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_8a8b89)
    val NEW_FOLDER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3a485)
    /** Same wording as 智能体 › 工作区. */
    val GRANT_TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5cd94f)
    val GRANT_HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_b1e01a)
    val UNAVAILABLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_28bcd7)
    val UNAVAILABLE_HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.files_536c9d)
}

internal fun systemIcon(s: SystemFolder): ImageVector = when (s) {
    SystemFolder.MODELS -> IrisIcons.Cube
    SystemFolder.VOICE -> IrisIcons.Voice
    SystemFolder.SKILLS -> IrisIcons.Skill
    SystemFolder.KNOWLEDGE -> IrisIcons.Memory
    SystemFolder.CONFIG -> IrisIcons.Settings
    SystemFolder.WORKSPACE -> IrisIcons.Agent
}

internal fun styleIcon(s: FileViewStyle): ImageVector = when (s) {
    FileViewStyle.LIST -> IrisIcons.File
    FileViewStyle.CARDS -> IrisIcons.Layers
}

internal fun entryIcon(e: FileEntry): ImageVector = e.system?.let(::systemIcon) ?: if (e.isDir) IrisIcons.Folder else IrisIcons.File

private val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)

/** "2026-10-23 13:40". */
internal fun formatModified(ms: Long): String = synchronized(timeFormat) { timeFormat.format(java.util.Date(ms)) }

/** "12 项 · 2026-10-23 13:40" / "4.20 GB · …"; system folders lead with their folder name. */
internal fun entrySubtitle(e: FileEntry): String {
    val what = if (e.isDir) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b5d2d7, e.childCount) else FileSizes.format(e.size)
    val time = formatModified(e.modified)
    return (if (e.system != null) "${e.name} · " else "") + "$what · $time"
}

/**
 * 2.2 「文件」: file manager for the app root Download/IRIDIS. The view is picked by
 * [com.iris.localai.data.AppSettings.fileViewStyle]; dialogs, Back and the top bar are shared.
 */
@Composable
fun AppFilesPage(onBack: () -> Unit) {
    val vm: FilesViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = !state.atRoot) { vm.up() }
    state.message?.let { m -> LaunchedEffect(m) { kotlinx.coroutines.delay(4000); vm.consumeMessage() } }

    val styled = state.viewStyle != FileViewStyle.LIST
    var styleMenu by remember { mutableStateOf(false) }
    SubpageScaffold(FilesText.TITLE, onBack, scrollUnder = false, actions = {
        // 2.1.9: small view-style menu — 列表 / 卡片.
        Box {
            PressableIcon(
                styleIcon(state.viewStyle), FilesText.VIEW_STYLE + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dec892) + state.viewStyle.label,
                tint = if (styled) Iris.colors.accent else Iris.colors.text,
                background = if (styled) Iris.colors.accentContainer else androidx.compose.ui.graphics.Color.Transparent,
            ) { styleMenu = true }
            IrisMenu(expanded = styleMenu, onDismiss = { styleMenu = false }) {
                FileViewStyle.entries.forEach { s ->
                    IrisMenuItem(s.label, if (s == state.viewStyle) IrisIcons.Check else styleIcon(s)) {
                        styleMenu = false
                        vm.setViewStyle(s)
                    }
                }
            }
        }
    }) {
        when (state.viewStyle) {
            FileViewStyle.LIST -> ListFileView(state, vm)
            // 2.1.9 「卡片」: the first 档案模式 card deck, kept as its own style.
            FileViewStyle.CARDS -> CardStackFileView(state, vm)
        }
    }
    FilesDialogs(state, vm)
}

/** Breadcrumbs, sort and 新建文件夹 — shared header of every view (the archive HUD draws its own path tag). */
@Composable
internal fun FilesHeader(state: FilesState, actions: FilesActions, showCrumbs: Boolean = true) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth()) {
        if (showCrumbs) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.crumbs.forEachIndexed { i, crumb ->
                val last = i == state.crumbs.lastIndex
                Text(
                    crumb.label, style = if (last) IrisType.rowStrong else IrisType.secondary,
                    color = if (last) c.text else c.accent, maxLines = 1,
                    modifier = Modifier.clip(IrisRadius.smallShape).clickable(enabled = !last) { actions.navigate(crumb.relPath) }
                        .padding(horizontal = IrisDimens.space4, vertical = IrisDimens.space4),
                )
                if (!last) Text("›", style = IrisType.secondary, color = c.textTertiary)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(IrisIcons.Sort, com.iris.localai.I18n.s(com.iris.localai.R.string.files_c360e9), Modifier.padding(end = IrisDimens.space4).size(18.dp), tint = c.textTertiary)
            FileSort.entries.forEach { s ->
                val on = s == state.sort
                Text(
                    s.label + if (on) (if (state.ascending) " ↑" else " ↓") else "",
                    style = IrisType.labelSmall, color = if (on) c.accent else c.textSecondary,
                    modifier = Modifier.clip(IrisRadius.smallShape).clickable { actions.setSort(s) }.padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6),
                )
            }
            Spacer(Modifier.weight(1f))
            IrisButton(FilesText.NEW_FOLDER, onClick = actions::requestNewFolder, icon = IrisIcons.FolderPlus, style = IrisButtonStyle.Tonal, compact = true)
        }
    }
}

/** The 全部文件访问 guidance (LIMITED) or the unreadable notice. */
@Composable
internal fun FilesAccessNotice(state: FilesState) {
    val context = LocalContext.current
    val c = Iris.colors
    if (state.access == FilesAccess.LIMITED) {
        Box(Modifier.padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space6).clip(IrisRadius.smallShape).background(c.surfaceElevated)) {
            SettingsRow(
                FilesText.GRANT_TITLE, icon = IrisIcons.Folder, subtitle = FilesText.GRANT_HINT, subtitleMaxLines = 3,
                onClick = { com.iris.localai.ui.models.openAllFilesAccess(context) },
            )
        }
    }
}

/** 2.2 list view: one grouped card of rows. */
@Composable
fun ListFileView(state: FilesState, actions: FilesActions, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val reduceMotion = LocalReduceMotion.current
    var menuFor by remember { mutableStateOf<String?>(null) }
    val items = state.entries
    LazyColumn(modifier.fillMaxSize().navigationBarsPadding()) {
        item(key = "header") { FilesHeader(state, actions) }
        item(key = "access") { FilesAccessNotice(state) }
        state.message?.let { m -> item(key = "msg") { GroupFootnote(m) } }
        if (items.isEmpty() && !state.loading) {
            item(key = "empty") {
                EmptyState(
                    title = when {
                        state.unreadable && state.atRoot -> FilesText.UNAVAILABLE
                        state.unreadable -> com.iris.localai.I18n.s(com.iris.localai.R.string.files_b4991b)
                        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e96910)
                    },
                    message = if (state.unreadable) FilesText.UNAVAILABLE_HINT else null,
                    icon = IrisIcons.Folder,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        }
        itemsIndexed(items, key = { _, e -> e.relPath }) { i, e ->
            val first = i == 0
            val last = i == items.lastIndex
            val r = IrisRadius.large
            val shape = SmoothRoundedShape(
                topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
                bottomEnd = if (last) r else 0.dp, bottomStart = if (last) r else 0.dp,
            )
            Column(
                (if (reduceMotion) Modifier else Modifier.animateItem())
                    .padding(horizontal = IrisDimens.groupGutter).fillMaxWidth().clip(shape).background(c.surfaceElevated),
            ) {
                if (!first) Divider()
                SettingsRow(
                    e.displayName,
                    icon = entryIcon(e),
                    subtitle = entrySubtitle(e),
                    subtitleMaxLines = 1,
                    onClick = { actions.onEntryClick(e) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (e.system != null) SystemBadge()
                            val canRename = actions.canRename(e)
                            val canDelete = actions.canDelete(e)
                            if (!e.isDir || canRename || canDelete) Box {
                                PressableIcon(IrisIcons.More, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ea320a, e.displayName), size = 40.dp, iconSize = IrisDimens.space20) { menuFor = e.relPath }
                                IrisMenu(expanded = menuFor == e.relPath, onDismiss = { menuFor = null }) {
                                    if (!e.isDir) {
                                        IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.files_1d93c3), IrisIcons.OpenExternal) { menuFor = null; actions.openExternal(e) }
                                        IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.files_c31f48), IrisIcons.Share) { menuFor = null; actions.share(e) }
                                    }
                                    if (canRename) IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b), IrisIcons.Edit) { menuFor = null; actions.requestRename(e) }
                                    if (canDelete) IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), IrisIcons.Trash, destructive = true) { menuFor = null; actions.requestDelete(e) }
                                }
                            }
                        }
                    },
                )
            }
        }
        item(key = "foot") {
            state.rootPath?.let { GroupFootnote(it, modifier = Modifier.padding(top = IrisDimens.space8)) }
        }
    }
}

/** Small 「系统」 tag on the app's own folders. */
@Composable
internal fun SystemBadge() {
    val c = Iris.colors
    Text(
        FilesText.SYSTEM_BADGE, style = IrisType.micro, color = c.onAccentContainer,
        modifier = Modifier.padding(end = IrisDimens.space6).clip(IrisRadius.smallShape).background(c.accentContainer).padding(horizontal = IrisDimens.space6, vertical = IrisDimens.space2),
    )
}

/** Rename / delete / new folder / preview — rendered from [FilesState.dialog] for every view style. */
@Composable
fun FilesDialogs(state: FilesState, actions: FilesActions) {
    val c = Iris.colors
    when (val d = state.dialog) {
        null -> Unit
        FileDialog.NewFolder -> FileNameDialog(
            title = FilesText.NEW_FOLDER, initial = "", warning = null, state = state, current = null,
            onConfirm = actions::confirmNewFolder, onDismiss = actions::dismissDialog,
        )
        is FileDialog.Rename -> FileNameDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b), initial = d.entry.name, warning = d.warning, state = state, current = d.entry.name,
            onConfirm = actions::confirmRename, onDismiss = actions::dismissDialog,
        )
        is FileDialog.Delete -> ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4a78de, d.entry.name),
            message = listOfNotNull(
                d.warning,
                if (d.entry.isDir) com.iris.localai.I18n.s(com.iris.localai.R.string.files_9ca853, d.entry.childCount) else null,
                com.iris.localai.I18n.s(com.iris.localai.R.string.files_a3a5ce),
            ).joinToString("\n"),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
            onConfirm = { actions.confirmDelete() },
            onDismiss = { if (state.dialog == d) actions.dismissDialog() },
        )
        is FileDialog.Preview -> IrisDialog(
            onDismiss = actions::dismissDialog, title = d.entry.name,
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91), onConfirm = actions::dismissDialog,
            dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.files_9f2ce6), onDismissClick = { actions.dismissDialog(); actions.openExternal(d.entry) },
        ) {
            Box(
                Modifier.fillMaxWidth().clip(IrisRadius.smallShape).background(if (d.markdown) c.surfaceVariant else c.codeBackground)
                    .heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            ) {
                val text = d.text
                when {
                    text == null -> Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a), style = IrisType.secondary, color = c.textTertiary)
                    d.markdown -> SelectionContainer { Markdown(text) }
                    else -> SelectionContainer { Text(text, style = IrisType.monoSmall, color = c.text) }
                }
            }
        }
    }
}

/** Name input with inline validation ([FileNames.validate]); the view model checks again on disk. */
@Composable
private fun FileNameDialog(
    title: String,
    initial: String,
    warning: String?,
    state: FilesState,
    current: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Iris.colors
    var value by remember { mutableStateOf(initial) }
    val problem = remember(value) { FileNames.validate(value, state.rel, state.entries.map { it.name }, current) }
    val unchanged = current != null && value.trim() == current
    IrisDialog(
        onDismiss = onDismiss, title = title, confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.components_38cf16),
        confirmEnabled = problem == null && !unchanged,
        onConfirm = { onConfirm(value.trim()) },
    ) {
        Column {
            warning?.let { Text(it, style = IrisType.secondary, color = c.warning, modifier = Modifier.padding(bottom = IrisDimens.space8)) }
            androidx.compose.material3.OutlinedTextField(
                value, { value = it }, singleLine = true,
                isError = problem != null && value.isNotEmpty(),
                textStyle = IrisType.body.copy(color = c.text),
                placeholder = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d7ec2d), style = IrisType.body, color = c.textTertiary) },
                shape = IrisRadius.mediumShape,
                colors = irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (problem != null && value.isNotEmpty()) Text(problem.message, style = IrisType.caption, color = c.danger, modifier = Modifier.padding(top = IrisDimens.space6))
        }
    }
}

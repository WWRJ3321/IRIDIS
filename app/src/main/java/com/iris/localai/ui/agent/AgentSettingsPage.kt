package com.iris.localai.ui.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.AgentSettings
import com.iris.localai.agent.AgentTools
import com.iris.localai.agent.Permission
import com.iris.localai.agent.WorkspaceLocation
import com.iris.localai.agent.WorkspaceResolver
import com.iris.localai.agent.SafWorkspaceFiles
import com.iris.localai.agent.listAll
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedControl
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 1.9.0 one-line texts of 设置 › 智能体 (unit-tested). */
object AgentText {
    val PLAN_FIRST: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_82dbde)
    val LAZY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_088993)
    val NAV: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7b8eeb)
    val MODE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7228e9)
    val STEPS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_171de9)
    val PERMISSION_NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_08d02e)
    val TOOLS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_82e503)
    val WORKSPACE_NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3c92e3)
    /** 1.10.0 workspace location texts. */
    val LOCATION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c637d6)
    val LOCATION_NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6b1d8b)
    val SAF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4365e4)
    val SAF_HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_51ab4f)
    val SAF_DONE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_107ee5)
    val SAF_WRONG: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4c8ee5)
    fun path(p: String) = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f89c73, p)
    val PERMISSION_OPTIONS = listOf(Permission.ALLOW, Permission.ASK, Permission.DENY)

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    }
}

private data class WsFile(val path: String, val file: File, val size: Long, val modified: Long)

@Composable
fun AgentSettingsPage(onBack: () -> Unit, onOpenBrowser: () -> Unit = {}, onOpenFiles: () -> Unit = {}) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val s by agent.settings.settings.collectAsStateWithLifecycle()
    val revision by agent.revision.collectAsStateWithLifecycle()
    val ws by agent.workspace.collectAsStateWithLifecycle()
    // 2.1.0: the old Download/IRISLocalAI folder could not be moved automatically.
    val legacyHint by agent.legacyFolderHint.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // 2.3.0 ②: MCP 服务 subpage.
    var showMcp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showMcp) {
        androidx.activity.compose.BackHandler { showMcp = false }
        McpPage(onBack = { showMcp = false })
        return
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!WorkspaceResolver.isWorkspaceTree(SafWorkspaceFiles.treeDocId(uri))) {
            message = AgentText.SAF_WRONG
            return@rememberLauncherForActivityResult
        }
        // 1.10.6: write too, so 智能体 can edit files the user put in the folder.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        agent.settings.update { it.copy(workspaceTreeUri = uri.toString()) }
        agent.changed()
        message = AgentText.SAF_DONE
        // 2.1.1: with the grant, files the user had put at the root can be moved into workspace/ now.
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { agent.migrateLayout() }.getOrNull() }
            r?.let { com.iris.localai.agent.StorageLayout.notice(it) }?.let { message = AgentText.SAF_DONE + "\n" + it }
        }
    }

    val files by produceState(emptyList<WsFile>(), revision, ws) {
        value = withContext(Dispatchers.IO) { agent.sandbox.listAll().map { (p, f) -> WsFile(p, f, f.length(), f.lastModified()) } }
    }
    val granted by produceState(false, revision, s.workspaceTreeUri) {
        value = withContext(Dispatchers.IO) { agent.externalFiles() != null }
    }
    val undoInfo by produceState<Pair<Int, String?>>(0 to null, revision) {
        value = withContext(Dispatchers.IO) { agent.undo.count() to agent.undo.peek()?.let { "${it.action} ${it.path}" } }
    }

    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_72fbb9), onBack) {
        ScrollBody {
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f0789e))
            SettingsGroup {
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_28a44c), s.agentMode, subtitle = AgentText.MODE, icon = com.iris.localai.ui.icons.IrisIcons.Agent) { on ->
                    agent.settings.update { it.copy(agentMode = on) }
                }
                Divider()
                SliderRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c3e1c1), s.maxSteps.toFloat(), AgentSettings.MIN_STEPS.toFloat()..AgentSettings.MAX_STEPS.toFloat(),
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8fdb54, s.maxSteps), subtitle = AgentText.STEPS,
                    steps = AgentSettings.MAX_STEPS - AgentSettings.MIN_STEPS - 1,
                ) { v -> agent.settings.update { it.copy(maxSteps = v.toInt()) } }
                // 2.0.x: the less-used switches fold away.
                Divider()
                com.iris.localai.ui.components.AdvancedFold("agent-mode") {
                    SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_97de54), s.planFirst, subtitle = AgentText.PLAN_FIRST) { on -> agent.settings.update { it.copy(planFirst = on) } }
                    Divider()
                    SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e816f9), s.lazyTools, subtitle = AgentText.LAZY) { on -> agent.settings.update { it.copy(lazyTools = on) } }
                }
            }

            // Tools grouped by kind.
            // 2.0.x: one line per tool — switch, and the permission as a value that opens a menu.
            SectionTitle(AgentText.TOOLS)
            AgentTools.all.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).forEach { (group, groupTools) ->
            SectionTitle(group.label)
            SettingsGroup {
                groupTools.forEachIndexed { i, tool ->
                    if (i > 0) Divider()
                    ToolRow(
                        label = tool.label, description = tool.description, on = s.isEnabled(tool), permission = s.permission(tool),
                        onToggle = { v -> agent.settings.update { it.copy(enabled = it.enabled + (tool.name to v)) } },
                        onPermission = { p -> agent.settings.update { it.copy(permissions = it.permissions + (tool.name to p.name)) } },
                    )
                }
            }
            }
            GroupFootnote(AgentText.PERMISSION_NOTE)

            // 2.3.0 ②: remote MCP servers (tools join the built-in ones).
            SectionTitle(McpText.TITLE)
            SettingsGroup {
                val mcpServers by agent.mcp.servers.collectAsStateWithLifecycle()
                SettingsRow(McpText.TITLE, icon = com.iris.localai.ui.icons.IrisIcons.Globe,
                    subtitle = if (mcpServers.isEmpty()) McpText.NAV else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_392241, mcpServers.count { it.enabled }, mcpServers.size),
                    onClick = { showMcp = true }, trailing = { com.iris.localai.ui.components.Chevron() })
            }

            // the agent's in-app browser.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_912302))
            SettingsGroup {
                SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_912302), icon = com.iris.localai.ui.icons.IrisIcons.Globe, subtitle = ToolPageText.BROWSER_NAV,
                    onClick = onOpenBrowser, trailing = { com.iris.localai.ui.components.Chevron() })
            }

            // 2.2: GUI 智能体（无障碍，形态 A）.
            GuiAgentSection()

            // search provider, skills, backup and logs.
            AgentExtraSections()

            var allFiles by remember { mutableStateOf(agent.hasAllFilesAccess()) }
            var customAsk by remember { mutableStateOf(false) }
            var showPicker by remember { mutableStateOf(false) }
            if (showPicker) WorkspaceFolderPicker(
                volumes = remember { agent.storageVolumes() },
                start = s.customWorkspace.ifBlank { null },
                validate = { agent.validateCustom(it)?.let { p -> agent.customReason(p).substringBefore(com.iris.localai.I18n.s(com.iris.localai.R.string.ws233_fallback)) } },
                onPick = { path ->
                    showPicker = false; customAsk = false
                    scope.launch { withContext(Dispatchers.IO) { agent.setCustomWorkspace(path) } }
                },
                onDismiss = { showPicker = false },
            )
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4fa8c1))
            SettingsGroup {
                // 1.10.0: 下载/IRIDIS/workspace (2.1.1, default) or 应用私有目录, with the real path.
                Column(Modifier.fillMaxWidth().padding(bottom = com.iris.localai.ui.theme.IrisSpace.M)) {
                    SettingsRow(
                        AgentText.LOCATION, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = AgentText.path(WorkspaceResolver.display(ws)) + (ws.fallbackReason?.let { "\n⚠ $it" } ?: "") +
                            (legacyHint?.let { "\n⚠ $it" } ?: "") + "\n" + AgentText.LOCATION_NOTE,
                        subtitleColor = if (ws.fallbackReason != null || legacyHint != null) Iris.colors.danger else Iris.colors.textTertiary,
                    )
                    val locs = WorkspaceLocation.entries
                    SegmentedControl(
                        locs.map { wsLabel(it) }, locs.indexOf(s.workspaceLocation).takeIf { !customAsk } ?: locs.indexOf(WorkspaceLocation.CUSTOM),
                        { idx ->
                            if (locs[idx] == WorkspaceLocation.CUSTOM) {
                                // 2.3.3: a real folder needs 全部文件访问; without it the grant row shows below.
                                if (agent.hasAllFilesAccess()) showPicker = true else customAsk = true
                            } else { customAsk = false; scope.launch { withContext(Dispatchers.IO) { agent.setWorkspaceLocation(locs[idx]) } } }
                        },
                        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding),
                    )
                }
                // 2.3.3: 自定义位置… — the picked folder, tap to choose another.
                if (s.workspaceLocation == WorkspaceLocation.CUSTOM || customAsk) {
                    Divider()
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.ws233_custom_row), icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = if (!allFiles) com.iris.localai.I18n.s(com.iris.localai.R.string.ws233_need_access)
                        else s.customWorkspace.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.ws233_p_blank) },
                        subtitleColor = if (!allFiles) Iris.colors.danger else Iris.colors.textTertiary,
                        enabled = allFiles,
                        onClick = { showPicker = true },
                        trailing = { com.iris.localai.ui.components.Chevron() },
                    )
                }
                if (ws.location == WorkspaceLocation.DOWNLOADS || customAsk || s.workspaceLocation == WorkspaceLocation.CUSTOM) {
                    Divider()
                    // 1.10.6: 全部文件访问 — the simplest way to let 智能体 read and edit every file here.
                    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    androidx.compose.runtime.DisposableEffect(lifecycle) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                allFiles = agent.hasAllFilesAccess(); agent.changed()
                                // 2.3.3: access just granted → the custom folder can be used / picked now.
                                if (allFiles && agent.settings.current.workspaceLocation == WorkspaceLocation.CUSTOM) scope.launch { withContext(Dispatchers.IO) { agent.refreshWorkspace() } }
                                if (allFiles && customAsk) showPicker = true
                                // 2.1.0: access may have just been granted — the old folder can be moved now.
                                if (legacyHint != null) scope.launch { withContext(Dispatchers.IO) { runCatching { agent.migrateLegacyFolder() } } }
                                // 2.1.1: with 全部文件访问 the root's old files are visible — finish the move into workspace/.
                                if (allFiles) scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { agent.migrateLayout() }.getOrNull() }
                                    r?.let { com.iris.localai.agent.StorageLayout.notice(it) }?.let { message = it }
                                }
                            }
                        }
                        lifecycle.addObserver(obs)
                        onDispose { lifecycle.removeObserver(obs) }
                    }
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5cd94f), icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = if (allFiles) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_174efe)
                        else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_eea705),
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + context.packageName)))
                            }.onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                        },
                    )
                    if (ws.location == WorkspaceLocation.DOWNLOADS) {
                    Divider()
                    SettingsRow(
                        AgentText.SAF, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = if (granted) AgentText.SAF_DONE else AgentText.SAF_HINT,
                        onClick = { runCatching { treeLauncher.launch(SafWorkspaceFiles.initialUri()) } },
                    )
                    }
                }
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e9f3fb), icon = Icons.AutoMirrored.Outlined.Undo,
                    subtitle = undoInfo.second?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_506e19, it, undoInfo.first) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_40caf8),
                    enabled = undoInfo.first > 0,
                    onClick = {
                        scope.launch {
                            message = withContext(Dispatchers.IO) { runCatching { agent.undo.undoLast(agent.sandbox) }.getOrElse { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6336b9, it.message) } } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_40caf8)
                            agent.changed()
                        }
                    },
                )
                Divider()
                // 1.13.0: one row into the 文件 page (the inline list duplicated it and read whole files into memory).
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9371cb), icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                    subtitle = (if (files.isEmpty()) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d22dc6) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9c50ee, files.size, AgentText.size(files.sumOf { it.size }))) +
                        "\n" + ws.root.path,
                    onClick = onOpenFiles,
                    trailing = { com.iris.localai.ui.components.Chevron() },
                )
            }
            GroupFootnote(AgentText.WORKSPACE_NOTE)
            message?.let { GroupFootnote(it) }
        }
    }
}

/**
 * 2.0.x: one tool on one line — label + description, the current permission as a tappable value
 * (menu: 允许 / 询问 / 拒绝) and the on/off switch. Replaces the always-visible segmented control.
 */
@Composable
private fun ToolRow(
    label: String,
    description: String,
    on: Boolean,
    permission: Permission,
    onToggle: (Boolean) -> Unit,
    onPermission: (Permission) -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    SettingsRow(
        label, subtitle = description, subtitleMaxLines = 2,
        role = androidx.compose.ui.semantics.Role.Switch, onClick = { onToggle(!on) }, interaction = interaction,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.iris.localai.ui.components.Fold(on) {
                Box {
                    Text(
                        permission.label + " ▾", style = IrisType.secondary, color = Iris.colors.textSecondary, maxLines = 1,
                        modifier = Modifier
                            .clip(IrisRadius.pillShape)
                            .clickable(onClickLabel = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_82635d)) { menu = true }
                            .padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6),
                    )
                    com.iris.localai.ui.components.IrisMenu(menu, { menu = false }) {
                        AgentText.PERMISSION_OPTIONS.forEach { p ->
                            com.iris.localai.ui.components.IrisMenuItem(p.label, if (p == permission) Icons.Outlined.Check else null) {
                                menu = false; onPermission(p)
                            }
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = IrisDimens.space4))
            com.iris.localai.ui.components.IrisSwitch(checked = on, interaction = interaction)
        }
    }
}

/** 2.3.3: localized names of the workspace locations. */
private fun wsLabel(l: WorkspaceLocation): String = com.iris.localai.I18n.s(when (l) {
    WorkspaceLocation.DOWNLOADS -> com.iris.localai.R.string.ws233_loc_downloads
    WorkspaceLocation.PRIVATE -> com.iris.localai.R.string.ws233_loc_private
    WorkspaceLocation.CUSTOM -> com.iris.localai.R.string.ws233_loc_custom
})

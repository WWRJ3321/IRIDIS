package com.iris.localai.ui.agent

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.agent.tools.ActivateTool
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.tools.MemoryTool
import com.iris.localai.agent.tools.ReadFileTool
import com.iris.localai.agent.tools.SearchLocalTool
import com.iris.localai.agent.tools.WriteFileTool
import com.iris.localai.agent.workspace.StorageLayout
import com.iris.localai.ui.components.AdvancedFold
import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.components.Fold
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.IrisSwitch
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
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
import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.workspace.WorkspaceLocation
import com.iris.localai.agent.workspace.WorkspaceResolver
import com.iris.localai.agent.workspace.SafWorkspaceFiles
import com.iris.localai.agent.tools.listAll
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

/** one-line texts of 设置 › 智能体 (unit-tested). */
object AgentText {
    val PLAN_FIRST: String get() = I18n.s(R.string.agent_82dbde)
    val LAZY: String get() = I18n.s(R.string.agent_088993)
    val NAV: String get() = I18n.s(R.string.agent_7b8eeb)
    val MODE: String get() = I18n.s(R.string.agent_7228e9)
    val STEPS: String get() = I18n.s(R.string.agent_171de9)
    val PERMISSION_NOTE: String get() = I18n.s(R.string.agent_08d02e)
    val TOOLS: String get() = I18n.s(R.string.agent_82e503)
    val WORKSPACE_NOTE: String get() = I18n.s(R.string.agent_3c92e3)
    /** workspace location texts. */
    val LOCATION: String get() = I18n.s(R.string.agent_c637d6)
    val LOCATION_NOTE: String get() = I18n.s(R.string.agent_6b1d8b)
    val SAF: String get() = I18n.s(R.string.agent_4365e4)
    val SAF_HINT: String get() = I18n.s(R.string.agent_51ab4f)
    val SAF_DONE: String get() = I18n.s(R.string.agent_107ee5)
    val SAF_WRONG: String get() = I18n.s(R.string.agent_4c8ee5)
    fun path(p: String) = I18n.s(R.string.agent_f89c73, p)
    val PERMISSION_OPTIONS = listOf(Permission.ALLOW, Permission.ASK, Permission.DENY)

    // ---- 2.5.0 Beta5: 读写文件 + 本地搜索 as one row (each tool keeps its own enabled / permission key) ----
    val FILES_LABEL: String get() = I18n.s(R.string.v250_tool_files)
    val FILES_DESC: String get() = I18n.s(R.string.v250_tool_files_sub)
    /** read_file, write_file, search_local — one switch and one permission value in settings. */
    val FILE_TOOLS: List<AgentTool> get() = listOf(
        ReadFileTool, WriteFileTool, SearchLocalTool,
    )
    /** Tools shown on their own row: not the merged file tools, not 长期记忆 (on 上下文、记忆和知识库), not the internal 载入工具. */
    /** one short line per tool in 设置 (the model still gets the full [AgentTool.description]). */
    private val SHORT = mapOf(
        "plan" to "多步任务先列计划",
        "web_search" to "搜网页和新闻",
        "web_fetch" to "打开网页读文字",
        "browser" to "在内置浏览器里点击、输入",
        "screen" to "看屏幕、代你点按（需无障碍）",
        "skill" to "按需读取技能说明",
        "generate_image" to "本机生图，存到工作区",
        "phone" to "闹钟、日历、打开 App 等手机操作",
        "kb_search" to "检索知识库",
        "recall" to "翻找较早的对话记录",
    )
    fun shortDesc(tool: AgentTool): String =
        SHORT[tool.name]?.takeIf { I18n.uiLang() == "zh-CN" } ?: tool.description
    fun listedTools(): List<AgentTool> = AgentTools.all.filter { t ->
        FILE_TOOLS.none { it.name == t.name } && t.name != MemoryTool.name && t.name != ActivateTool.name
    }
    /** On while any of the three is on (so the row never hides a tool that can still run). */
    fun filesOn(s: AgentSettings): Boolean = FILE_TOOLS.any { s.isEnabled(it) }
    fun withFilesOn(s: AgentSettings, on: Boolean): AgentSettings = s.copy(enabled = s.enabled + FILE_TOOLS.map { it.name to on })
    /** 拒绝 when all three are denied, 允许 when all three are allowed, otherwise 询问. */
    fun filesPermission(s: AgentSettings): Permission {
        val ps = FILE_TOOLS.map { s.permission(it) }
        return when {
            ps.all { it == Permission.DENY } -> Permission.DENY
            ps.all { it == Permission.ALLOW } -> Permission.ALLOW
            else -> Permission.ASK
        }
    }
    /** 允许 / 拒绝 = all three; 询问 = ask before writing, read and search allowed (the default pair). */
    fun withFilesPermission(s: AgentSettings, p: Permission): AgentSettings = s.copy(
        permissions = s.permissions + FILE_TOOLS.map { t ->
            t.name to (if (p == Permission.ASK && t.name != WriteFileTool.name) Permission.ALLOW else p).name
        },
    )

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    }
}

private data class WsFile(val path: String, val file: File, val size: Long, val modified: Long)

@Composable
fun AgentSettingsPage(
    onBack: () -> Unit, onOpenBrowser: () -> Unit = {}, onOpenFiles: () -> Unit = {},
    /**
     * MCP 服务 opens as its own route in MainActivity's AnimatedContent (push / pop slide and
     * predictive back like every other subpage). null = shown in place (old behaviour).
     */
    onOpenMcp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val s by agent.settings.settings.collectAsStateWithLifecycle()
    val revision by agent.revision.collectAsStateWithLifecycle()
    val ws by agent.workspace.collectAsStateWithLifecycle()
    // the old Download/IRISLocalAI folder could not be moved automatically.
    val legacyHint by agent.legacyFolderHint.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // MCP 服务 subpage.
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
        // write too, so 智能体 can edit files the user put in the folder.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        agent.settings.update { it.copy(workspaceTreeUri = uri.toString()) }
        agent.changed()
        message = AgentText.SAF_DONE
        // with the grant, files the user had put at the root can be moved into workspace/ now.
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { agent.migrateLayout() }.getOrNull() }
            r?.let { StorageLayout.notice(it) }?.let { message = AgentText.SAF_DONE + "\n" + it }
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

    SubpageScaffold(I18n.s(R.string.chat_72fbb9), onBack) {
        ScrollBody {
            SectionTitle(I18n.s(R.string.agent_f0789e))
            SettingsGroup(Modifier.tourTarget(TourId.AGENT_MODE)) {
                SwitchRow(I18n.s(R.string.agent_28a44c), s.agentMode, subtitle = AgentText.MODE, icon = IrisIcons.Agent) { on ->
                    agent.settings.update { it.copy(agentMode = on) }
                }
                Divider()
                SliderRow(
                    I18n.s(R.string.agent_c3e1c1), s.maxSteps.toFloat(), AgentSettings.MIN_STEPS.toFloat()..AgentSettings.MAX_STEPS.toFloat(),
                    I18n.s(R.string.agent_8fdb54, s.maxSteps), subtitle = AgentText.STEPS,
                    steps = AgentSettings.MAX_STEPS - AgentSettings.MIN_STEPS - 1,
                ) { v -> agent.settings.update { it.copy(maxSteps = v.toInt()) } }
                // 2.0.x: the less-used switches fold away.
                Divider()
                // 先问清楚（默认开）
                SwitchRow(I18n.s(R.string.s267_ask), s.askFirst, subtitle = I18n.s(R.string.s267_ask_sub)) { on -> agent.settings.update { it.copy(askFirst = on) } }
                Divider()
                AdvancedFold("agent-mode") {
                    SwitchRow(I18n.s(R.string.agent_97de54), s.planFirst, subtitle = AgentText.PLAN_FIRST) { on -> agent.settings.update { it.copy(planFirst = on) } }
                    Divider()
                    SwitchRow(I18n.s(R.string.agent_e816f9), s.lazyTools, subtitle = AgentText.LAZY) { on -> agent.settings.update { it.copy(lazyTools = on) } }
                }
            }

            // one group under 「工具」 (the per-kind headers held one row each); 读取/写入文件 +
            // 本地搜索 are one row, 长期记忆 is on 上下文、记忆和知识库, the internal 载入工具 is not listed.
            // 2.0.x: one line per tool — switch, and the permission as a value that opens a menu.
            SectionTitle(AgentText.TOOLS)
            SettingsGroup {
                ToolRow(
                    label = AgentText.FILES_LABEL, description = AgentText.FILES_DESC,
                    on = AgentText.filesOn(s), permission = AgentText.filesPermission(s),
                    onToggle = { v -> agent.settings.update { AgentText.withFilesOn(it, v) } },
                    onPermission = { p -> agent.settings.update { AgentText.withFilesPermission(it, p) } },
                )
                AgentText.listedTools().forEach { tool ->
                    Divider()
                    ToolRow(
                        label = tool.label, description = AgentText.shortDesc(tool), on = s.isEnabled(tool), permission = s.permission(tool),
                        onToggle = { v -> agent.settings.update { it.copy(enabled = it.enabled + (tool.name to v)) } },
                        onPermission = { p -> agent.settings.update { it.copy(permissions = it.permissions + (tool.name to p.name)) } },
                    )
                }
            }
            GroupFootnote(AgentText.PERMISSION_NOTE)

            // remote MCP servers (tools join the built-in ones).
            SectionTitle(McpText.TITLE)
            SettingsGroup(Modifier.tourTarget(TourId.MCP)) {
                val mcpServers by agent.mcp.servers.collectAsStateWithLifecycle()
                SettingsRow(McpText.TITLE, icon = IrisIcons.Globe,
                    subtitle = if (mcpServers.isEmpty()) McpText.NAV else I18n.s(R.string.agent_392241, mcpServers.count { it.enabled }, mcpServers.size),
                    onClick = { onOpenMcp?.invoke() ?: run { showMcp = true } }, trailing = { Chevron() })
            }

            // the agent's in-app browser.
            SectionTitle(I18n.s(R.string.agent_912302))
            SettingsGroup {
                SettingsRow(I18n.s(R.string.agent_912302), icon = IrisIcons.Globe, subtitle = ToolPageText.BROWSER_NAV,
                    onClick = onOpenBrowser, trailing = { Chevron() })
            }

            // GUI 智能体（无障碍，形态 A）. 2.5.0 Beta5: 无障碍保活 removed.
            GuiAgentSection()

            // search provider, skills, backup and logs.
            AgentExtraSections()

            var allFiles by remember { mutableStateOf(agent.hasAllFilesAccess()) }
            var customAsk by remember { mutableStateOf(false) }
            var showPicker by remember { mutableStateOf(false) }
            if (showPicker) WorkspaceFolderPicker(
                volumes = remember { agent.storageVolumes() },
                start = s.customWorkspace.ifBlank { null },
                validate = { agent.validateCustom(it)?.let { p -> agent.customReason(p).substringBefore(I18n.s(R.string.ws233_fallback)) } },
                onPick = { path ->
                    showPicker = false; customAsk = false
                    scope.launch { withContext(Dispatchers.IO) { agent.setCustomWorkspace(path) } }
                },
                onDismiss = { showPicker = false },
            )
            SectionTitle(I18n.s(R.string.agent_4fa8c1))
            SettingsGroup {
                // 下载/IRIDIS/workspace (2.1.1, default) or 应用私有目录, with the real path.
                Column(Modifier.fillMaxWidth().padding(bottom = IrisSpace.M)) {
                    SettingsRow(
                        AgentText.LOCATION, icon = IrisIcons.Folder,
                        subtitle = AgentText.path(WorkspaceResolver.display(ws)) + (ws.fallbackReason?.let { "\n⚠ $it" } ?: "") +
                            (legacyHint?.let { "\n⚠ $it" } ?: ""),
                        subtitleColor = if (ws.fallbackReason != null || legacyHint != null) Iris.colors.danger else Iris.colors.textTertiary,
                    )
                    val locs = WorkspaceLocation.entries
                    SegmentedControl(
                        locs.map { wsLabel(it) }, locs.indexOf(s.workspaceLocation).takeIf { !customAsk } ?: locs.indexOf(WorkspaceLocation.CUSTOM),
                        { idx ->
                            if (locs[idx] == WorkspaceLocation.CUSTOM) {
                                // a real folder needs 全部文件访问; without it the grant row shows below.
                                if (agent.hasAllFilesAccess()) showPicker = true else customAsk = true
                            } else { customAsk = false; scope.launch { withContext(Dispatchers.IO) { agent.setWorkspaceLocation(locs[idx]) } } }
                        },
                        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding),
                    )
                }
                // 自定义位置… — the picked folder, tap to choose another.
                if (s.workspaceLocation == WorkspaceLocation.CUSTOM || customAsk) {
                    Divider()
                    SettingsRow(
                        I18n.s(R.string.ws233_custom_row), icon = IrisIcons.Folder,
                        subtitle = if (!allFiles) I18n.s(R.string.ws233_need_access)
                        else s.customWorkspace.ifBlank { I18n.s(R.string.ws233_p_blank) },
                        subtitleColor = if (!allFiles) Iris.colors.danger else Iris.colors.textTertiary,
                        enabled = allFiles,
                        onClick = { showPicker = true },
                        trailing = { Chevron() },
                    )
                }
                if (ws.location == WorkspaceLocation.DOWNLOADS || customAsk || s.workspaceLocation == WorkspaceLocation.CUSTOM) {
                    Divider()
                    // 全部文件访问 — the simplest way to let 智能体 read and edit every file here.
                    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    androidx.compose.runtime.DisposableEffect(lifecycle) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                allFiles = agent.hasAllFilesAccess(); agent.changed()
                                // access just granted → the custom folder can be used / picked now.
                                if (allFiles && agent.settings.current.workspaceLocation == WorkspaceLocation.CUSTOM) scope.launch { withContext(Dispatchers.IO) { agent.refreshWorkspace() } }
                                if (allFiles && customAsk) showPicker = true
                                // access may have just been granted — the old folder can be moved now.
                                if (legacyHint != null) scope.launch { withContext(Dispatchers.IO) { runCatching { agent.migrateLegacyFolder() } } }
                                // with 全部文件访问 the root's old files are visible — finish the move into workspace/.
                                if (allFiles) scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { agent.migrateLayout() }.getOrNull() }
                                    r?.let { StorageLayout.notice(it) }?.let { message = it }
                                }
                            }
                        }
                        lifecycle.addObserver(obs)
                        onDispose { lifecycle.removeObserver(obs) }
                    }
                    SettingsRow(
                        I18n.s(R.string.agent_5cd94f), icon = IrisIcons.Folder,
                        subtitle = if (allFiles) I18n.s(R.string.agent_174efe)
                        else I18n.s(R.string.agent_eea705),
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + context.packageName)))
                            }.onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                        },
                    )
                    // the SAF grant is only a fallback — hidden once 全部文件访问 is on.
                    if (ws.location == WorkspaceLocation.DOWNLOADS && !allFiles) {
                    Divider()
                    SettingsRow(
                        AgentText.SAF, icon = IrisIcons.Folder,
                        subtitle = if (granted) AgentText.SAF_DONE else AgentText.SAF_HINT,
                        onClick = { runCatching { treeLauncher.launch(SafWorkspaceFiles.initialUri()) } },
                    )
                    }
                }
                Divider()
                SettingsRow(
                    I18n.s(R.string.agent_e9f3fb), icon = Icons.AutoMirrored.Outlined.Undo,
                    subtitle = undoInfo.second?.let { I18n.s(R.string.agent_506e19, it, undoInfo.first) } ?: I18n.s(R.string.agent_40caf8),
                    enabled = undoInfo.first > 0,
                    onClick = {
                        scope.launch {
                            message = withContext(Dispatchers.IO) { runCatching { agent.undo.undoLast(agent.sandbox) }.getOrElse { I18n.s(R.string.agent_6336b9, it.message) } } ?: I18n.s(R.string.agent_40caf8)
                            agent.changed()
                        }
                    },
                )
                Divider()
                // one row into the 文件 page (the inline list duplicated it and read whole files into memory).
                SettingsRow(
                    I18n.s(R.string.agent_9371cb), icon = IrisIcons.Folder,
                    // no second copy of the workspace path (the 位置 row above shows it).
                    subtitle = (if (files.isEmpty()) I18n.s(R.string.agent_d22dc6) else I18n.s(R.string.agent_9c50ee, files.size, AgentText.size(files.sumOf { it.size }))),
                    onClick = onOpenFiles,
                    trailing = { Chevron() },
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
            Fold(on) {
                Box {
                    Text(
                        permission.label + " ▾", style = IrisType.secondary, color = Iris.colors.textSecondary, maxLines = 1,
                        modifier = Modifier
                            .clip(IrisRadius.pillShape)
                            .clickable(onClickLabel = I18n.s(R.string.agent_82635d)) { menu = true }
                            .padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6),
                    )
                    IrisMenu(menu, { menu = false }) {
                        AgentText.PERMISSION_OPTIONS.forEach { p ->
                            IrisMenuItem(p.label, if (p == permission) Icons.Outlined.Check else null) {
                                menu = false; onPermission(p)
                            }
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = IrisDimens.space4))
            IrisSwitch(checked = on, interaction = interaction)
        }
    }
}

/** localized names of the workspace locations. */
private fun wsLabel(l: WorkspaceLocation): String = I18n.s(when (l) {
    WorkspaceLocation.DOWNLOADS -> R.string.ws233_loc_downloads
    WorkspaceLocation.PRIVATE -> R.string.ws233_loc_private
    WorkspaceLocation.CUSTOM -> R.string.ws233_loc_custom
})

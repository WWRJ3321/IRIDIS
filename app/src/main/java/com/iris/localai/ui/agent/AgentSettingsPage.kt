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
    const val PLAN_FIRST = "多步任务先列步骤，你确认后再执行"
    const val LAZY = "工具用法用到时再载入，省上下文、首字更快"
    const val NAV = "工具、权限与工作区"
    const val MODE = "输入框也可切换；关闭则不调用工具"
    const val STEPS = "一次回复最多调用几次工具"
    const val PERMISSION_NOTE = "点工具右侧的权限可切换：允许 = 直接执行，询问 = 每次确认，拒绝 = 不执行。"
    const val TOOLS = "工具与权限"
    const val WORKSPACE_NOTE = "智能体只能访问这个工作区（下载/IRIDIS 里的模型、语音、技能、知识库和备份不在其中）；修改前自动备份，可逐步撤销（最多 20 步）。"
    /** 1.10.0 workspace location texts. */
    const val LOCATION = "工作区位置"
    const val LOCATION_NOTE = "默认在「下载/IRIDIS/workspace」，文件管理器可见；切换位置不会搬动已有文件。"
    const val SAF = "授权访问这个文件夹（备选）"
    const val SAF_HINT = "授权 Download/IRIDIS 一次，智能体才能读写你放进 workspace 的文件；已开「全部文件访问」时不需要。"
    const val SAF_DONE = "已授权：智能体可以列出、读取和修改你放进去的文件（1.10.5 及更早的授权是只读的，需重新授权一次）"
    const val SAF_WRONG = "请选择 Download/IRIDIS 这个文件夹（选其他文件夹不会生效；智能体只会访问其中的 workspace）"
    fun path(p: String) = "实际路径：$p"
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

    SubpageScaffold("智能体", onBack) {
        ScrollBody {
            SectionTitle("模式")
            SettingsGroup {
                SwitchRow("智能体模式", s.agentMode, subtitle = AgentText.MODE, icon = com.iris.localai.ui.icons.IrisIcons.Agent) { on ->
                    agent.settings.update { it.copy(agentMode = on) }
                }
                Divider()
                SliderRow(
                    "最大步数", s.maxSteps.toFloat(), AgentSettings.MIN_STEPS.toFloat()..AgentSettings.MAX_STEPS.toFloat(),
                    "${s.maxSteps} 步", subtitle = AgentText.STEPS,
                    steps = AgentSettings.MAX_STEPS - AgentSettings.MIN_STEPS - 1,
                ) { v -> agent.settings.update { it.copy(maxSteps = v.toInt()) } }
                // 2.0.x: the less-used switches fold away.
                Divider()
                com.iris.localai.ui.components.AdvancedFold("agent-mode") {
                    SwitchRow("计划模式", s.planFirst, subtitle = AgentText.PLAN_FIRST) { on -> agent.settings.update { it.copy(planFirst = on) } }
                    Divider()
                    SwitchRow("按需载入工具", s.lazyTools, subtitle = AgentText.LAZY) { on -> agent.settings.update { it.copy(lazyTools = on) } }
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

            // the agent's in-app browser.
            SectionTitle("浏览器")
            SettingsGroup {
                SettingsRow("浏览器", icon = com.iris.localai.ui.icons.IrisIcons.Globe, subtitle = ToolPageText.BROWSER_NAV,
                    onClick = onOpenBrowser, trailing = { com.iris.localai.ui.components.Chevron() })
            }

            // 2.2: GUI 智能体（无障碍，形态 A）.
            GuiAgentSection()

            // search provider, skills, backup and logs.
            AgentExtraSections()

            SectionTitle("工作区")
            SettingsGroup {
                // 1.10.0: 下载/IRIDIS/workspace (2.1.1, default) or 应用私有目录, with the real path.
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    SettingsRow(
                        AgentText.LOCATION, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = AgentText.path(WorkspaceResolver.display(ws)) + (ws.fallbackReason?.let { "\n⚠ $it" } ?: "") +
                            (legacyHint?.let { "\n⚠ $it" } ?: "") + "\n" + AgentText.LOCATION_NOTE,
                        subtitleColor = if (ws.fallbackReason != null || legacyHint != null) Iris.colors.danger else Iris.colors.textTertiary,
                    )
                    val locs = WorkspaceLocation.entries
                    SegmentedControl(
                        locs.map { it.label }, locs.indexOf(s.workspaceLocation),
                        { idx -> scope.launch { withContext(Dispatchers.IO) { agent.setWorkspaceLocation(locs[idx]) } } },
                        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding),
                    )
                }
                if (ws.location == WorkspaceLocation.DOWNLOADS) {
                    Divider()
                    // 1.10.6: 全部文件访问 — the simplest way to let 智能体 read and edit every file here.
                    var allFiles by remember { mutableStateOf(agent.hasAllFilesAccess()) }
                    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    androidx.compose.runtime.DisposableEffect(lifecycle) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                allFiles = agent.hasAllFilesAccess(); agent.changed()
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
                        "全部文件访问（推荐）", icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = if (allFiles) "已开启 · 可读写工作区里的任何文件"
                        else "未开启 · 点按去系统设置开启，否则你放进工作区的文件可能读不到",
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + context.packageName)))
                            }.onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                        },
                    )
                    Divider()
                    SettingsRow(
                        AgentText.SAF, icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                        subtitle = if (granted) AgentText.SAF_DONE else AgentText.SAF_HINT,
                        onClick = { runCatching { treeLauncher.launch(SafWorkspaceFiles.initialUri()) } },
                    )
                }
                Divider()
                SettingsRow(
                    "撤销上次修改", icon = Icons.AutoMirrored.Outlined.Undo,
                    subtitle = undoInfo.second?.let { "撤销「$it」 · 还可撤销 ${undoInfo.first} 步" } ?: "没有可撤销的修改",
                    enabled = undoInfo.first > 0,
                    onClick = {
                        scope.launch {
                            message = withContext(Dispatchers.IO) { runCatching { agent.undo.undoLast(agent.sandbox) }.getOrElse { "撤销失败：${it.message}" } } ?: "没有可撤销的修改"
                            agent.changed()
                        }
                    },
                )
                Divider()
                // 1.13.0: one row into the 文件 page (the inline list duplicated it and read whole files into memory).
                SettingsRow(
                    "工作区文件", icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                    subtitle = (if (files.isEmpty()) "还没有文件 · 智能体写入的文件会出现在这里" else "${files.size} 个文件 · ${AgentText.size(files.sumOf { it.size })}") +
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
                            .clickable(onClickLabel = "更改权限") { menu = true }
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

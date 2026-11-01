package com.iris.localai.ui.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.Permission
import com.iris.localai.agent.SearchProvider
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.CheckRow
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.util.Backup
import com.iris.localai.util.LogExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Sections of 设置 › 智能体: 联网搜索 (provider), 技能, 备份与日志.
 */
@Composable
fun AgentExtraSections() {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val s by agent.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val c = Iris.colors

    // ---- 联网搜索 ----
    SectionTitle("联网搜索")
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space6)) {
            SettingsRow("搜索服务", subtitle = s.search.label)
            // 2.0.0: six services no longer fit a segmented control: one check row each.
            SearchProvider.entries.forEach { p ->
                Divider()
                CheckRow(p.short, selected = s.search == p) {
                    agent.settings.update { it.copy(searchProvider = p.name, searchProviderChosen = true) }
                }
            }
            if (s.search == SearchProvider.SEARXNG) {
                var url by remember(s.searxUrl) { mutableStateOf(s.searxUrl) }
                OutlinedTextField(
                    url, { url = it; agent.settings.update { st -> st.copy(searxUrl = it.trim()) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8),
                    singleLine = true, label = { Text("SearXNG 地址，如 https://searx.example.org") },
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                )
            }
        }
    }
    GroupFootnote("「谷歌/必应（浏览器）」用内置浏览器搜索，遇到人机验证请在浏览器页完成一次。其他服务只收到搜索词。自建 SearXNG 需公网地址。")

    // ---- 技能 ----
    SectionTitle("技能")
    val skills by produceState(emptyList<com.iris.localai.agent.SkillInfo>(), s) {
        value = withContext(Dispatchers.IO) { agent.skills.invalidate(); agent.skills.all() }
    }
    SettingsGroup {
        if (skills.isEmpty()) SettingsRow("没有技能")
        skills.forEachIndexed { i, sk ->
            if (i > 0) Divider()
            SettingsRow(sk.name, subtitle = sk.description.ifBlank { "（无说明）" } + " · " + sk.source, subtitleMaxLines = 2)
        }
    }
    GroupFootnote(SkillText.FOLDER)
}

/**
 * 备份与日志 (导出备份 / 从备份恢复 / 导出日志). Lives on 设置 › 存储空间 since 2.0.x: it is about
 * the app's data, not the agent.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    var message by remember { mutableStateOf<String?>(null) }
    // 2.1.0: restore shows what the backup holds first; export asks whether to include the API key.
    var confirmRestore by remember { mutableStateOf<Pair<android.net.Uri, Backup.Info>?>(null) }
    var askExport by remember { mutableStateOf(false) }
    var includeApiKey by remember { mutableStateOf(false) }
    val c = Iris.colors
    // ---- 备份与日志 ----
    SectionTitle("备份与日志")
    val exportBackup = rememberLauncherForActivityResult(CreateDocumentInConfig("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val withKey = includeApiKey
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        Backup.export(Backup.dataDir(context), it, com.iris.localai.BuildConfig.VERSION_NAME,
                            workspace = agent.appRoot, includeApiKey = withKey)
                    }
                }.fold({ "已备份 $it 个文件" + if (withKey) "（含 API 密钥，请妥善保管）" else "" }, { "备份失败：${it.message}" })
            }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.inspect(it) } } }
            info.onSuccess { confirmRestore = uri to it }.onFailure { message = "无法读取备份：${it.message}" }
        }
    }
    val exportLogs = rememberLauncherForActivityResult(CreateDocumentInConfig("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(LogExport.collect().toByteArray()) } }
                    .fold({ "日志已导出" }, { "导出失败：${it.message}" })
            }
        }
    }
    SettingsGroup {
        SettingsRow("导出备份", subtitle = "对话、记忆、技能、模型配置和设置（不含模型与语音包）", onClick = { includeApiKey = false; askExport = true })
        Divider()
        SettingsRow("导入备份", subtitle = "会替换现在的对话和设置，完成后 IRIDIS 自动重启", onClick = { pickRestore.launch(arrayOf("application/zip", "application/octet-stream")) })
        Divider()
        SettingsRow("导出日志", subtitle = "本次运行的日志，反馈问题时附上", onClick = {
            exportLogs.launch("IRIS-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".txt")
        })
    }

    if (askExport) {
        IrisDialog(
            onDismiss = { askExport = false }, title = "导出备份",
            confirmText = "选择保存位置",
            onConfirm = {
                askExport = false
                exportBackup.launch("IRIDIS-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
            },
        ) {
            Column {
                Text("包含对话、记忆、技能、模型配置和设置。", style = IrisType.secondary, color = c.textSecondary)
                com.iris.localai.ui.components.SwitchRow("包含 API 密钥", checked = includeApiKey,
                    subtitle = "本地 API 服务的密钥；默认不导出") { includeApiKey = it }
            }
        }
    }
    confirmRestore?.let { (uri, info) ->
        val m = info.manifest
        val date = if (m.createdAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(m.createdAt)) else "未知"
        val version = m.appVersion.ifBlank { if (info.legacy) "IRIS LocalAI（旧版）" else "未知" }
        IrisDialog(
            onDismiss = { confirmRestore = null }, title = "导入备份？",
            confirmText = "导入并重启", destructive = true,
            onConfirm = {
                confirmRestore = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(Backup.dataDir(context), it, workspace = agent.appRoot) } }
                    }
                    r.onSuccess { Backup.restart(context) }.onFailure { message = "导入失败：${it.message}" }
                }
            },
        ) {
            Text(
                "版本：$version\n日期：$date\n文件数：${info.dataFiles}" + (if (m.includesApiKey && !info.legacy) "\n包含 API 密钥" else "") +
                    "\n\n现在的对话、记忆和设置会被备份里的内容替换，导入完成后 IRIDIS 会自动重启。",
                style = IrisType.secondary, color = c.textSecondary,
            )
        }
    }
    message?.let { m ->
        IrisDialog(onDismiss = { message = null }, title = m, confirmText = "好", onConfirm = { message = null }, dismissText = null)
    }
}

object SkillText {
    /** 2.1.1: user skills are an app resource beside the workspace (the agent reads them only through the 技能 tool). */
    val FOLDER = "自定义技能放在 ${com.iris.localai.agent.StorageLayout.label(com.iris.localai.agent.StorageLayout.SKILLS)}/<名字>/SKILL.md" +
        "（应用私有目录模式下在应用内部）。智能体只能用「技能」工具读取，不能修改。"
}

/**
 * 2.1.1: the save dialog for backups and logs opens in Download/IRIDIS/config (created when missing);
 * the user can still pick any other folder.
 */
class CreateDocumentInConfig(mime: String) : ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        super.createIntent(context, input).apply {
            runCatching {
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?.let { com.iris.localai.agent.StorageLayout.configDir(it).mkdirs() }
            }
            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri(
                com.iris.localai.agent.WorkspaceResolver.EXTERNAL_STORAGE_AUTHORITY, com.iris.localai.agent.StorageLayout.docId(com.iris.localai.agent.StorageLayout.CONFIG)))
        }
}

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
    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7ad823))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space6)) {
            SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1e3939), subtitle = s.search.label)
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
                    singleLine = true, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a31d9b)) },
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                )
            }
        }
    }
    GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_add6ac))

    // ---- 技能 ----
    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_699143))
    val skills by produceState(emptyList<com.iris.localai.agent.SkillInfo>(), s) {
        value = withContext(Dispatchers.IO) { agent.skills.invalidate(); agent.skills.all() }
    }
    SettingsGroup {
        if (skills.isEmpty()) SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3d3223))
        skills.forEachIndexed { i, sk ->
            if (i > 0) Divider()
            SettingsRow(sk.name, subtitle = sk.description.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_69d2be) } + " · " + sk.source, subtitleMaxLines = 2)
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
    // 2.5.0-beta7: 「导出完整日志」 asks whether to take every chat or only the latest one.
    var askFullLog by remember { mutableStateOf(false) }
    var fullLogAll by remember { mutableStateOf(true) }
    val c = Iris.colors
    // ---- 备份与日志 ----
    SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b56fbe))
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
                }.fold({ com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dfdfa5, it) + if (withKey) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_732b53) else "" }, { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dc5948, it.message) })
            }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.inspect(it) } } }
            info.onSuccess { confirmRestore = uri to it }.onFailure { message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c2e70a, it.message) }
        }
    }
    val exportLogs = rememberLauncherForActivityResult(CreateDocumentInConfig("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(LogExport.collect().toByteArray()) } }
                    .fold({ com.iris.localai.I18n.s(com.iris.localai.R.string.agent_af33a3) }, { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e3fdcf, it.message) })
            }
        }
    }
    // 2.5.0-beta7: run log + full chats (messages, reasoning, tool calls) as one zip, no API key.
    val exportFullLog = rememberLauncherForActivityResult(CreateDocumentInConfig("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val all = fullLogAll
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val container = (context.applicationContext as IrisApp).container
                    val repo = container.conversations
                    val ids = repo.summaries.value.let { list -> if (all) list.sortedByDescending { it.updatedAt } else listOfNotNull(list.maxByOrNull { it.updatedAt }) }
                    val chats = ids.mapNotNull { repo.load(it.id) }
                    val engineLine = when (val st = container.engine.state.value) {
                        is com.iris.localai.engine.EngineState.Ready -> st.model.fileName + " · " + st.details.backend
                        else -> "none"
                    }
                    val zone = java.time.ZoneId.systemDefault()
                    val appLine = "IRIDIS ${com.iris.localai.BuildConfig.VERSION_NAME} (${com.iris.localai.BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    val header = com.iris.localai.util.FullLogExport.header(appLine, engineLine, zone, System.currentTimeMillis(), chats.size, if (all) "all" else "latest only")
                    val log = runCatching { LogExport.collect() }.getOrElse { "(run log unavailable: ${it.message})" }
                    context.contentResolver.openOutputStream(uri)!!.use { com.iris.localai.util.FullLogExport.writeZip(it, header, log, chats, zone) }
                    chats.size
                }.fold({ com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_done, it) }, { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e3fdcf, it.message) })
            }
        }
    }
    SettingsGroup {
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9344b8), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3febe2), onClick = { includeApiKey = false; askExport = true })
        Divider()
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_90c045), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9023e9), onClick = { pickRestore.launch(arrayOf("application/zip", "application/octet-stream")) })
        Divider()
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_609e15), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ad19c0), onClick = {
            exportLogs.launch("IRIS-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".txt")
        })
        Divider()
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_title), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_sub), onClick = { fullLogAll = true; askFullLog = true })
    }

    if (askFullLog) {
        IrisDialog(
            onDismiss = { askFullLog = false }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_title),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b08bb8),
            onConfirm = {
                askFullLog = false
                exportFullLog.launch("IRIDIS-full-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
            },
        ) {
            Column {
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_desc), style = IrisType.secondary, color = c.textSecondary)
                com.iris.localai.ui.components.SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_all), checked = fullLogAll,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.v250_fulllog_all_sub)) { fullLogAll = it }
            }
        }
    }

    if (askExport) {
        IrisDialog(
            onDismiss = { askExport = false }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9344b8),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b08bb8),
            onConfirm = {
                askExport = false
                exportBackup.launch("IRIDIS-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
            },
        ) {
            Column {
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e4ad4f), style = IrisType.secondary, color = c.textSecondary)
                com.iris.localai.ui.components.SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_436b96), checked = includeApiKey,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ee037a)) { includeApiKey = it }
            }
        }
    }
    confirmRestore?.let { (uri, info) ->
        val m = info.manifest
        val date = if (m.createdAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(m.createdAt)) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1622dc)
        val version = m.appVersion.ifBlank { if (info.legacy) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c1e1ee) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1622dc) }
        IrisDialog(
            onDismiss = { confirmRestore = null }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_441799),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_449fd7), destructive = true,
            onConfirm = {
                confirmRestore = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(Backup.dataDir(context), it, workspace = agent.appRoot) } }
                    }
                    r.onSuccess { Backup.restart(context) }.onFailure { message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5afd82, it.message) }
                }
            },
        ) {
            Text(
                com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c2a42c, version, date, info.dataFiles) + (if (m.includesApiKey && !info.legacy) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_05df6c) else "") +
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f7cd9b),
                style = IrisType.secondary, color = c.textSecondary,
            )
        }
    }
    message?.let { m ->
        IrisDialog(onDismiss = { message = null }, title = m, confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ac2c8f), onConfirm = { message = null }, dismissText = null)
    }
}

object SkillText {
    /** 2.1.1: user skills are an app resource beside the workspace (the agent reads them only through the 技能 tool). */
    val FOLDER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c48c91, com.iris.localai.agent.StorageLayout.label(com.iris.localai.agent.StorageLayout.SKILLS)) +
        com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0062b5)
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

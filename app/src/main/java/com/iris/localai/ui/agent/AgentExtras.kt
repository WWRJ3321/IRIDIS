package com.iris.localai.ui.agent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import com.iris.localai.BuildConfig
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.agent.tools.SkillInfo
import com.iris.localai.agent.workspace.StorageLayout
import com.iris.localai.agent.workspace.WorkspaceResolver
import com.iris.localai.engine.EngineState
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.util.io.FullLogExport
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
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.web.SearchProvider
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
import com.iris.localai.util.io.Backup
import com.iris.localai.util.io.LogExport
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
    SectionTitle(I18n.s(R.string.agent_7ad823))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space6)) {
            // three choices, no header row (谷歌 / 必应 search in the in-app browser).
            SearchProvider.LISTED.forEachIndexed { i, p ->
                if (i > 0) Divider()
                CheckRow(p.short, selected = SearchProvider.shownAs(s.search) == p) {
                    agent.settings.update { it.copy(searchProvider = p.name, searchProviderChosen = true) }
                }
            }
            if (s.search == SearchProvider.SEARXNG) {
                var url by remember(s.searxUrl) { mutableStateOf(s.searxUrl) }
                OutlinedTextField(
                    url, { url = it; agent.settings.update { st -> st.copy(searxUrl = it.trim()) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8),
                    singleLine = true, label = { Text(I18n.s(R.string.agent_a31d9b)) },
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                )
            }
        }
    }
    GroupFootnote(I18n.s(R.string.agent_add6ac))

    // ---- 生图（2.5.7）：generate_image 用哪个模型，内存不够时是否先卸载文本模型 ----
    val imageModels by produceState(emptyList<ImageModel>(), s) {
        value = withContext(Dispatchers.IO) { agent.imageModels() }
    }
    if (imageModels.isNotEmpty()) {
        SectionTitle(I18n.s(R.string.chat_19a9bc))
        SettingsGroup {
            val picked = s.imageModel.takeIf { id -> imageModels.any { it.id == id } }.orEmpty()
            CheckRow(I18n.s(R.string.settings_3aed2c), selected = picked.isEmpty()) { agent.settings.update { it.copy(imageModel = "") } }
            imageModels.forEach { m ->
                Divider()
                CheckRow(m.name, selected = picked == m.id) { agent.settings.update { it.copy(imageModel = m.id) } }
            }
            Divider()
            SwitchRow(I18n.s(R.string.b4r2_img_make_room), s.imageMakeRoom, subtitle = I18n.s(R.string.b4r2_img_make_room_sub)) { on ->
                agent.settings.update { it.copy(imageMakeRoom = on) }
            }
        }
        GroupFootnote(I18n.s(R.string.b4r2_img_ref_fallback))
    }

    // ---- 技能 ----
    SectionTitle(I18n.s(R.string.chat_699143))
    val skills by produceState(emptyList<SkillInfo>(), s) {
        value = withContext(Dispatchers.IO) { agent.skills.invalidate(); agent.skills.all() }
    }
    SettingsGroup {
        if (skills.isEmpty()) SettingsRow(I18n.s(R.string.agent_3d3223))
        skills.forEachIndexed { i, sk ->
            if (i > 0) Divider()
            SettingsRow(sk.name, subtitle = sk.description.ifBlank { I18n.s(R.string.agent_69d2be) }, subtitleMaxLines = 2)
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
    // restore shows what the backup holds first; export asks whether to include the API key.
    var confirmRestore by remember { mutableStateOf<Pair<android.net.Uri, Backup.Info>?>(null) }
    var askExport by remember { mutableStateOf(false) }
    var includeApiKey by remember { mutableStateOf(false) }
    // 「导出完整日志」 asks whether to take every chat or only the latest one.
    var askFullLog by remember { mutableStateOf(false) }
    var fullLogAll by remember { mutableStateOf(true) }
    // 2.7.2：设置导出 / 导入（JSON）
    var askCfgExport by remember { mutableStateOf(false) }
    var cfgWithKey by remember { mutableStateOf(false) }
    var confirmCfg by remember { mutableStateOf<com.iris.localai.util.io.ConfigTransfer.Parsed?>(null) }
    val container = remember { (context.applicationContext as IrisApp).container }
    val c = Iris.colors
    // ---- 备份与日志 ----
    SectionTitle(I18n.s(R.string.agent_b56fbe))
    val exportBackup = rememberLauncherForActivityResult(CreateDocumentInConfig("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val withKey = includeApiKey
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    (context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use {
                        Backup.export(Backup.dataDir(context), it, BuildConfig.VERSION_NAME,
                            workspace = agent.appRoot, includeApiKey = withKey)
                    }
                }.fold({ I18n.s(R.string.agent_dfdfa5, it) + if (withKey) I18n.s(R.string.agent_732b53) else "" }, { I18n.s(R.string.agent_dc5948, it.message) })
            }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use { Backup.inspect(it) } } }
            info.onSuccess { confirmRestore = uri to it }.onFailure { message = I18n.s(R.string.agent_c2e70a, it.message) }
        }
    }
    val exportLogs = rememberLauncherForActivityResult(CreateDocumentInConfig("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { (context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use { it.write(LogExport.collect().toByteArray()) } }
                    .fold({ I18n.s(R.string.agent_af33a3) }, { I18n.s(R.string.agent_e3fdcf, it.message) })
            }
        }
    }
    // run log + full chats (messages, reasoning, tool calls) as one zip, no API key.
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
                        is EngineState.Ready -> st.model.fileName + " · " + st.details.backend
                        else -> "none"
                    }
                    val zone = java.time.ZoneId.systemDefault()
                    val appLine = "IRIDIS ${com.iris.localai.BuildConfig.VERSION_NAME} (${com.iris.localai.BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    val header = FullLogExport.header(appLine, engineLine, zone, System.currentTimeMillis(), chats.size, if (all) "all" else "latest only")
                    val log = runCatching { LogExport.collect() }.getOrElse { "(run log unavailable: ${it.message})" }
                    (context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use { FullLogExport.writeZip(it, header, log, chats, zone) }
                    chats.size
                }.fold({ I18n.s(R.string.v250_fulllog_done, it) }, { I18n.s(R.string.agent_e3fdcf, it.message) })
            }
        }
    }
    val exportCfg = rememberLauncherForActivityResult(CreateDocumentInConfig("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val withKey = cfgWithKey
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    (context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use {
                        com.iris.localai.util.io.ConfigIo.export(container, it, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, withKey)
                    }
                }.fold({ I18n.s(R.string.v272_cfg_exported, it) }, { I18n.s(R.string.agent_dc5948, it.message) })
            }
        }
    }
    val pickCfg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use { com.iris.localai.util.io.ConfigIo.inspect(it) } } }
            r.onSuccess { confirmCfg = it }.onFailure { e ->
                message = if (e is com.iris.localai.util.io.ConfigTransfer.BadFile) I18n.s(R.string.v272_cfg_bad) else I18n.s(R.string.v272_cfg_failed, e.message)
            }
        }
    }
    SettingsGroup {
        SettingsRow(I18n.s(R.string.agent_9344b8), subtitle = I18n.s(R.string.agent_3febe2), onClick = { includeApiKey = false; askExport = true })
        Divider()
        SettingsRow(I18n.s(R.string.agent_90c045), subtitle = I18n.s(R.string.agent_9023e9), onClick = { pickRestore.launch(arrayOf("application/zip", "application/octet-stream")) })
        Divider()
        SettingsRow(I18n.s(R.string.v272_cfg_export), subtitle = I18n.s(R.string.v272_cfg_export_sub), onClick = { cfgWithKey = false; askCfgExport = true })
        Divider()
        SettingsRow(I18n.s(R.string.v272_cfg_import), subtitle = I18n.s(R.string.v272_cfg_import_sub), onClick = { pickCfg.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        Divider()
        SettingsRow(I18n.s(R.string.agent_609e15), subtitle = I18n.s(R.string.agent_ad19c0), onClick = {
            exportLogs.launch("IRIS-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".txt")
        })
        Divider()
        SettingsRow(I18n.s(R.string.v250_fulllog_title), subtitle = I18n.s(R.string.v250_fulllog_sub), onClick = { fullLogAll = true; askFullLog = true })
    }

    if (askFullLog) {
        IrisDialog(
            onDismiss = { askFullLog = false }, title = I18n.s(R.string.v250_fulllog_title),
            confirmText = I18n.s(R.string.agent_b08bb8),
            onConfirm = {
                askFullLog = false
                exportFullLog.launch("IRIDIS-full-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
            },
        ) {
            Column {
                Text(I18n.s(R.string.v250_fulllog_desc), style = IrisType.secondary, color = c.textSecondary)
                SwitchRow(I18n.s(R.string.v250_fulllog_all), checked = fullLogAll,
                    subtitle = I18n.s(R.string.v250_fulllog_all_sub)) { fullLogAll = it }
            }
        }
    }

    if (askCfgExport) {
        IrisDialog(
            onDismiss = { askCfgExport = false }, title = I18n.s(R.string.v272_cfg_export),
            confirmText = I18n.s(R.string.agent_b08bb8),
            onConfirm = {
                askCfgExport = false
                exportCfg.launch("IRIDIS-settings-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".json")
            },
        ) {
            Column {
                Text(I18n.s(R.string.v272_cfg_export_sub), style = IrisType.secondary, color = c.textSecondary)
                SwitchRow(I18n.s(R.string.agent_436b96), checked = cfgWithKey, subtitle = I18n.s(R.string.agent_ee037a)) { cfgWithKey = it }
            }
        }
    }
    confirmCfg?.let { p ->
        val date = if (p.createdAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(p.createdAt)) else I18n.s(R.string.agent_1622dc)
        IrisDialog(
            onDismiss = { confirmCfg = null }, title = I18n.s(R.string.v272_cfg_import),
            confirmText = I18n.s(R.string.v272_cfg_import_go),
            onConfirm = {
                confirmCfg = null
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        runCatching { com.iris.localai.util.io.ConfigIo.apply(container, p) }
                            .fold({ I18n.s(R.string.v272_cfg_imported, it.settings, it.cards, it.skipped) }, { I18n.s(R.string.v272_cfg_failed, it.message) })
                    }
                }
            },
        ) {
            Text(I18n.s(R.string.v272_cfg_confirm, p.appVersion.ifBlank { I18n.s(R.string.agent_1622dc) }, date, p.cards.size),
                style = IrisType.secondary, color = c.textSecondary)
        }
    }
    if (askExport) {
        IrisDialog(
            onDismiss = { askExport = false }, title = I18n.s(R.string.agent_9344b8),
            confirmText = I18n.s(R.string.agent_b08bb8),
            onConfirm = {
                askExport = false
                exportBackup.launch("IRIDIS-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
            },
        ) {
            Column {
                Text(I18n.s(R.string.agent_e4ad4f), style = IrisType.secondary, color = c.textSecondary)
                SwitchRow(I18n.s(R.string.agent_436b96), checked = includeApiKey,
                    subtitle = I18n.s(R.string.agent_ee037a)) { includeApiKey = it }
            }
        }
    }
    confirmRestore?.let { (uri, info) ->
        val m = info.manifest
        val date = if (m.createdAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(m.createdAt)) else I18n.s(R.string.agent_1622dc)
        val version = m.appVersion.ifBlank { if (info.legacy) I18n.s(R.string.agent_c1e1ee) else I18n.s(R.string.agent_1622dc) }
        IrisDialog(
            onDismiss = { confirmRestore = null }, title = I18n.s(R.string.agent_441799),
            confirmText = I18n.s(R.string.agent_449fd7), destructive = true,
            onConfirm = {
                confirmRestore = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开所选文件")).use { Backup.restore(Backup.dataDir(context), it, workspace = agent.appRoot) } }
                    }
                    r.onSuccess { Backup.restart(context) }.onFailure { message = I18n.s(R.string.agent_5afd82, it.message) }
                }
            },
        ) {
            Text(
                I18n.s(R.string.agent_c2a42c, version, date, info.dataFiles) + (if (m.includesApiKey && !info.legacy) I18n.s(R.string.agent_05df6c) else "") +
                    I18n.s(R.string.agent_f7cd9b),
                style = IrisType.secondary, color = c.textSecondary,
            )
        }
    }
    message?.let { m ->
        // 首行当标题，全文放正文（可滚动、可复制）
        val head = m.lineSequence().first().take(40)
        IrisDialog(onDismiss = { message = null }, title = head, confirmText = I18n.s(R.string.settings_ac2c8f), onConfirm = { message = null }, dismissText = null) {
            if (m != head) Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(m, style = IrisType.secondary, color = Iris.colors.textSecondary) }
            }
        }
    }
}

object SkillText {
    /** user skills are an app resource beside the workspace (the agent reads them only through the 技能 tool). */
    val FOLDER: String get() = I18n.s(R.string.agent_c48c91, StorageLayout.label(StorageLayout.SKILLS)) +
        I18n.s(R.string.agent_0062b5)
}

/**
 * the save dialog for backups and logs opens in Download/IRIDIS/config (created when missing);
 * the user can still pick any other folder.
 */
class CreateDocumentInConfig(mime: String) : ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        super.createIntent(context, input).apply {
            runCatching {
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?.let { StorageLayout.configDir(it).mkdirs() }
            }
            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri(
                WorkspaceResolver.EXTERNAL_STORAGE_AUTHORITY, StorageLayout.docId(StorageLayout.CONFIG)))
        }
}

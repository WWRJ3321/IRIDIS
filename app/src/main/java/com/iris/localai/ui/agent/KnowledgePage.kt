package com.iris.localai.ui.agent

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    const val TITLE = "知识库"
    const val TOGGLE = "引用知识库"
    const val TOGGLE_SUB = "回答时自动参考最相关的资料"
    const val REBUILD = "重建索引"
    const val ADD = "添加文件（txt / md / csv / html…）"
    const val DELETE = "删除"
    const val EMPTY = "还没有文件。添加后，聊天里提到相关内容会自动引用。"
    const val NOTE = "支持 txt、md、csv、json、html 等文本文件。放进文件夹即可，自动在本机建索引。"

    fun status(files: Int, chunks: Int, unsupported: Int): String =
        if (files == 0) "还没有文件" else "$files 个文件 · $chunks 段" + if (unsupported > 0) " · $unsupported 个不支持" else ""

    /** [appRoot] = Download/IRIDIS (2.1.1: the 知识库 is an app resource beside workspace/, not inside it). */
    fun folder(appRoot: String): String = "用文件管理器把文件放进：$appRoot/${KnowledgeBase.FOLDER}"

    /** 2.2.9: 「42 段 · 引用 6 次」 / 「正在读取…」. */
    fun fileLine(f: KbFileInfo): String = if (!f.indexed) "正在读取…" else if (f.chunks == 0) "没有可读的文字" else "${f.chunks} 段 · 引用 ${f.cites} 次"

    /** Badge in the file tile: the extension, upper case, at most 4 letters. */
    fun badge(path: String): String = KbFormats.ext(path).uppercase().take(4).ifEmpty { "TXT" }

    fun entry(files: Int, chunks: Int): String = if (files == 0) "还没有文件" else "$files 个文件 · $chunks 段"
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
            subtitle = files?.let { l -> KnowledgeText.entry(l.size, l.sumOf { it.chunks }) } ?: "正在读取…",
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
fun KnowledgePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val agent = container.agent
    val ws by agent.workspace.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KbFileInfo?>(null) }
    val c = Iris.colors

    // Listed first (unread files show 「正在读取…」), then the index is brought up to date and listed again.
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
                        val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= KnowledgeBase.MAX_BYTES } }
                        bytes != null && agent.knowledge.add(name, bytes) != null
                    }.getOrDefault(false)
                }
            }
            busy = false
            tick++
            Toast.makeText(context, if (added == uris.size) "已添加 $added 个文件" else "已添加 $added / ${uris.size} 个文件（过大或无法读取的已跳过）", Toast.LENGTH_SHORT).show()
        }
    }

    SubpageScaffold(KnowledgeText.TITLE, onBack) {
        ScrollBody {
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                KnowledgeText.ADD, { pick.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                icon = Icons.Outlined.Add, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16),
            )
            Spacer(Modifier.height(IrisDimens.space16))
            val list = files
            if (list == null) {
                Text("正在读取…", style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(IrisDimens.space16))
            } else if (list.isEmpty()) {
                Text(KnowledgeText.EMPTY, style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(IrisDimens.space16))
            } else {
                SettingsGroup {
                    list.forEachIndexed { i, f ->
                        if (i > 0) HairlineDivider()
                        KbFileRow(f, onDelete = { deleting = f })
                    }
                }
            }

            SectionTitle("索引")
            SettingsGroup {
                SettingsRow(
                    KnowledgeText.REBUILD, icon = IrisIcons.Reload, enabled = !busy,
                    subtitle = "重新读取全部文件（一般不需要：文件变化会自动更新）\n" + KnowledgeText.folder(ws.appRoot.path),
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
        }
    }

    deleting?.let { f ->
        ConfirmDialog(
            title = "删除「${f.path.substringAfterLast('/')}」？",
            message = "文件会从知识库文件夹删除，之后不再被引用。",
            confirmText = KnowledgeText.DELETE,
            onConfirm = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { agent.knowledge.delete(f.path) }.getOrDefault(false) }
                    if (!ok) Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                    tick++
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun KbFileRow(f: KbFileInfo, onDelete: () -> Unit) {
    val c = Iris.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = IrisDimens.space16, top = IrisDimens.space16, bottom = IrisDimens.space16, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(IrisRadius.smallShape).background(c.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(KnowledgeText.badge(f.path), style = IrisType.caption, color = c.textSecondary, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(start = IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
            Text(f.path, style = IrisType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(KnowledgeText.fileLine(f), style = IrisType.caption, color = if (f.indexed) c.textSecondary else c.warning)
        }
        Box {
            PressableIcon(IrisIcons.More, "更多", size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { menu = true }
            IrisMenu(menu, { menu = false }) {
                IrisMenuItem(KnowledgeText.DELETE, null, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

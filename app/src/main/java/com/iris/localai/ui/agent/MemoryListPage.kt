package com.iris.localai.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import com.iris.localai.memory.ItemsEdit
import com.iris.localai.memory.MemoryFile
import com.iris.localai.memory.MemoryItem
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.HairlineDivider
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 2.2.9 texts of the memory list (unit-tested). */
object MemoryListText {
    const val TITLE = "记忆"
    const val SEARCH = "搜索记忆"
    const val ALL = "全部"
    const val PENDING = "等你确认"
    const val SAVED = "已记住"
    const val ACCEPT = "记住"
    const val IGNORE = "忽略"
    const val ADD = "手动添加一条"
    const val EDIT = "编辑"
    const val DELETE = "删除"
    const val EMPTY = "还没有记住任何事。聊天里说「记住：…」，或点回复下的「记住？」。"
    const val NO_MATCH = "没有匹配的记忆"

    fun kind(k: MemoryItem.Kind): String = when (k) {
        MemoryItem.Kind.ABOUT -> "关于你"
        MemoryItem.Kind.PREF -> "偏好"
        MemoryItem.Kind.FACT -> "事实"
    }

    fun source(s: MemoryItem.Source): String = when (s) {
        MemoryItem.Source.USER -> "你说的"
        MemoryItem.Source.AGENT -> "智能体记的"
        MemoryItem.Source.SUGGESTED -> "建议"
        MemoryItem.Source.MIGRATED -> "旧版导入"
    }

    /** 刚刚 / n 分钟前 / n 小时前 / MM-dd. */
    fun whenLabel(now: Long, t: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = now - t
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000L} 分钟前"
            d < 86_400_000L -> "${d / 3_600_000L} 小时前"
            else -> Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT))
        }
    }

    fun pendingFrom(t: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "来自 " + Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)) + " 的对话"

    /** Search + kind filter (null = all). */
    fun filter(items: List<MemoryItem>, query: String, kind: MemoryItem.Kind?): List<MemoryItem> {
        val q = query.trim()
        return items.filter { (kind == null || it.kind == kind) && (q.isEmpty() || it.text.contains(q, ignoreCase = true)) }
    }

    fun edited(r: ItemsEdit): String? = when (r) {
        is ItemsEdit.Ok -> r.replaced?.let { "已更新（原：$it）" }
        is ItemsEdit.Err -> if (r.message.startsWith("already")) "这条已经记过了" else if (r.message.startsWith("memory is full")) "记忆已满，请先删掉一些" else r.message
    }
}

@Composable
fun MemoryListPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val store = agent.memoryDocs.store
    val revision by agent.memoryRevision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val data by produceState(MemoryFile(), revision) { value = withContext(Dispatchers.IO) { store.file() } }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<MemoryItem.Kind?>(null) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemoryItem?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val c = Iris.colors

    fun act(block: () -> ItemsEdit?) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            note = r?.let { MemoryListText.edited(it) }
            agent.memoryChanged()
        }
    }

    SubpageScaffold(MemoryListText.TITLE, onBack) {
        ScrollBody {
            OutlinedTextField(
                query, { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = c.textTertiary) },
                placeholder = { Text(MemoryListText.SEARCH, style = IrisType.body, color = c.textTertiary) },
                textStyle = IrisType.body.copy(color = c.text),
                shape = IrisRadius.pillShape,
                colors = irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = IrisDimens.space8),
            )
            Spacer(Modifier.height(IrisDimens.space16))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                FilterPill("${MemoryListText.ALL} ${data.items.size}", kind == null) { kind = null }
                MemoryItem.Kind.entries.forEach { k ->
                    FilterPill("${MemoryListText.kind(k)} ${data.items.count { it.kind == k }}", kind == k) { kind = if (kind == k) null else k }
                }
            }
            note?.let {
                Spacer(Modifier.height(IrisDimens.space8))
                Text(it, style = IrisType.caption, color = c.textSecondary)
            }

            val pending = MemoryListText.filter(data.pending, query, kind)
            if (pending.isNotEmpty()) {
                SectionTitle("${MemoryListText.PENDING} · ${pending.size}")
                Column(verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                    pending.forEach { p ->
                        SettingsGroup {
                            Column(Modifier.fillMaxWidth().padding(IrisDimens.space16), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                                KindTag(p.kind)
                                Text(p.text, style = IrisType.body, color = c.text)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(MemoryListText.pendingFrom(p.createdAt), style = IrisType.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                                    IrisButton(MemoryListText.ACCEPT, { act { store.acceptPending(p.id) } }, compact = true)
                                    Spacer(Modifier.width(IrisDimens.space8))
                                    IrisButton(MemoryListText.IGNORE, { act { store.dismissPending(p.id); null } }, style = IrisButtonStyle.Outline, compact = true)
                                }
                            }
                        }
                    }
                }
            }

            SectionTitle(MemoryListText.SAVED)
            val shown = MemoryListText.filter(data.items, query, kind).sortedByDescending { it.updatedAt }
            if (shown.isEmpty()) {
                Text(
                    if (data.items.isEmpty()) MemoryListText.EMPTY else MemoryListText.NO_MATCH,
                    style = IrisType.secondary, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8),
                )
            } else {
                val now = System.currentTimeMillis()
                SettingsGroup {
                    shown.forEachIndexed { i, item ->
                        if (i > 0) HairlineDivider(start = IrisDimens.space16)
                        MemoryRow(item, now, onEdit = { editing = item }, onDelete = { deleting = item })
                    }
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
            IrisButton(MemoryListText.ADD, { adding = true }, style = IrisButtonStyle.Outline, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(IrisDimens.space24))
        }
    }

    if (adding || editing != null) {
        val e = editing
        ItemDialog(
            initialText = e?.text ?: "", initialKind = e?.kind ?: (kind ?: MemoryItem.Kind.ABOUT),
            title = if (e == null) MemoryListText.ADD else MemoryListText.EDIT,
            onDismiss = { adding = false; editing = null },
        ) { text, k ->
            adding = false; editing = null
            act { if (e == null) store.add(text, k, MemoryItem.Source.USER) else store.update(e.id, text, k) }
        }
    }
    deleting?.let { d ->
        ConfirmDialog(
            title = "删除这条记忆？", message = d.text, confirmText = MemoryListText.DELETE,
            onConfirm = { act { store.delete(d.id); null } },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Iris.colors
    Box(
        Modifier.clip(IrisRadius.pillShape)
            .background(if (selected) c.accent else c.surfaceVariant)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = IrisType.secondary, color = if (selected) c.onAccent else c.text) }
}

@Composable
private fun KindTag(kind: MemoryItem.Kind) {
    val c = Iris.colors
    val (bg, fg) = when (kind) {
        MemoryItem.Kind.ABOUT -> c.success.copy(alpha = 0.16f) to c.success
        MemoryItem.Kind.PREF -> c.accentContainer to c.onAccentContainer
        MemoryItem.Kind.FACT -> c.surfaceVariant to c.textSecondary
    }
    Text(
        MemoryListText.kind(kind), style = IrisType.caption, color = fg,
        modifier = Modifier.clip(IrisRadius.pillShape).background(bg).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space2),
    )
}

@Composable
private fun MemoryRow(item: MemoryItem, now: Long, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = Iris.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit)
            .padding(start = IrisDimens.space16, top = IrisDimens.space12, bottom = IrisDimens.space12, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                KindTag(item.kind)
                Text(item.text, style = IrisType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${MemoryListText.source(item.source)} · ${MemoryListText.whenLabel(now, item.updatedAt)}",
                style = IrisType.caption, color = c.textTertiary,
            )
        }
        Box {
            PressableIcon(IrisIcons.More, "更多", size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { menu = true }
            IrisMenu(menu, { menu = false }) {
                IrisMenuItem(MemoryListText.EDIT, IrisIcons.Edit) { menu = false; onEdit() }
                IrisMenuItem(MemoryListText.DELETE, null, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
private fun ItemDialog(initialText: String, initialKind: MemoryItem.Kind, title: String, onDismiss: () -> Unit, onSave: (String, MemoryItem.Kind) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var k by remember { mutableStateOf(initialKind) }
    val over = text.trim().length > com.iris.localai.memory.MemoryStore.ITEM_MAX
    IrisDialog(
        onDismiss = onDismiss, title = title, confirmText = "保存",
        confirmEnabled = text.isNotBlank() && !over,
        onConfirm = { onSave(text.trim(), k) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            OutlinedTextField(
                text, { text = it }, minLines = 2, maxLines = 6,
                textStyle = IrisType.body.copy(color = Iris.colors.text),
                placeholder = { Text("一条一件事，例如：回答尽量简洁", style = IrisType.body, color = Iris.colors.textTertiary) },
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                MemoryItem.Kind.entries.forEach { kk -> FilterPill(MemoryListText.kind(kk), k == kk) { k = kk } }
            }
            Text(
                "${text.trim().length} / ${com.iris.localai.memory.MemoryStore.ITEM_MAX} 字",
                style = IrisType.caption, color = if (over) Iris.colors.danger else Iris.colors.textTertiary,
            )
        }
    }
}


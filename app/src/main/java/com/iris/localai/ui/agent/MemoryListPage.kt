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
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_44e4d7)
    val SEARCH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_603d2a)
    val ALL: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a8b0c2)
    val PENDING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_493b7b)
    val SAVED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4e42e4)
    val ACCEPT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f7d9d3)
    val IGNORE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c0d5d6)
    val ADD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_68c7b1)
    val EDIT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_95b351)
    val DELETE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad)
    val EMPTY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c0ab88)
    val NO_MATCH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_aebf07)

    fun kind(k: MemoryItem.Kind): String = when (k) {
        MemoryItem.Kind.ABOUT -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d89889)
        MemoryItem.Kind.PREF -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_43b9ff)
        MemoryItem.Kind.FACT -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_beb171)
    }

    fun source(s: MemoryItem.Source): String = when (s) {
        MemoryItem.Source.USER -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6055c8)
        MemoryItem.Source.AGENT -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_bb6a19)
        MemoryItem.Source.SUGGESTED -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_19444e)
        MemoryItem.Source.MIGRATED -> com.iris.localai.I18n.s(com.iris.localai.R.string.agent_bd277e)
    }

    /** 刚刚 / n 分钟前 / n 小时前 / MM-dd. */
    fun whenLabel(now: Long, t: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = now - t
        return when {
            d < 60_000L -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_4181f7)
            d < 3_600_000L -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_aea893, d / 60_000L)
            d < 86_400_000L -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_d935fb, d / 3_600_000L)
            else -> Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT))
        }
    }

    fun pendingFrom(t: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        com.iris.localai.I18n.s(com.iris.localai.R.string.agent_313dfa) + Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)) + com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4d864e)

    /** Search + kind filter (null = all). */
    fun filter(items: List<MemoryItem>, query: String, kind: MemoryItem.Kind?): List<MemoryItem> {
        val q = query.trim()
        return items.filter { (kind == null || it.kind == kind) && (q.isEmpty() || it.text.contains(q, ignoreCase = true)) }
    }

    fun edited(r: ItemsEdit): String? = when (r) {
        is ItemsEdit.Ok -> r.replaced?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dd55e7, it) }
        is ItemsEdit.Err -> if (r.message.startsWith("already")) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_62bbba) else if (r.message.startsWith("memory is full")) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_cb13e2) else r.message
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

    // 2.5.0 Beta3: 长期记忆 (master switch) and 清空记忆 moved here from 开发者 › 上下文与记忆.
    val ms by agent.memorySettings.settings.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    if (clearing) {
        com.iris.localai.ui.components.ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5ad835),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_49ac8d),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_288f0c),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { agent.memoryDocs.clear() }
                    agent.memorySettings.update { it.copy(epoch = it.epoch + 1) }
                    agent.memoryChanged()
                }
            },
            onDismiss = { clearing = false },
        )
    }

    SubpageScaffold(MemoryListText.TITLE, onBack) {
        ScrollBody {
            SettingsGroup(Modifier.padding(top = IrisDimens.space8)) {
                com.iris.localai.ui.components.SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7), ms.enabled, subtitle = MemoryText.MASTER, icon = com.iris.localai.ui.icons.IrisIcons.Memory) { on ->
                    agent.memorySettings.update { it.copy(enabled = on) }
                }
                com.iris.localai.ui.components.Divider()
                com.iris.localai.ui.components.SettingsRow(
                    MemoryText.CLEAR, titleColor = c.danger,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ab89b3),
                    enabled = data.items.isNotEmpty() || data.pending.isNotEmpty(),
                    onClick = { clearing = true },
                )
            }
            Spacer(Modifier.height(IrisDimens.space8))
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
            // 2.2.14: saving an empty text clears the item (edit) or does nothing (add).
            when {
                text.isBlank() && e != null -> act { store.delete(e.id); null }
                text.isBlank() -> Unit
                else -> act { if (e == null) store.add(text, k, MemoryItem.Source.USER) else store.update(e.id, text, k) }
            }
        }
    }
    deleting?.let { d ->
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8af8e7), message = d.text, confirmText = MemoryListText.DELETE,
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
            PressableIcon(IrisIcons.More, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0ec9ea), size = 40.dp, iconSize = 18.dp, tint = c.textSecondary) { menu = true }
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
        onDismiss = onDismiss, title = title, confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb),
        confirmEnabled = !over,
        onConfirm = { onSave(text.trim(), k) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            OutlinedTextField(
                text, { text = it }, minLines = 2, maxLines = 6,
                textStyle = IrisType.body.copy(color = Iris.colors.text),
                placeholder = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2ca522), style = IrisType.body, color = Iris.colors.textTertiary) },
                shape = IrisRadius.mediumShape, colors = irisTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                MemoryItem.Kind.entries.forEach { kk -> FilterPill(MemoryListText.kind(kk), k == kk) { k = kk } }
            }
            Text(
                com.iris.localai.I18n.s(com.iris.localai.R.string.agent_37f497, text.trim().length, com.iris.localai.memory.MemoryStore.ITEM_MAX),
                style = IrisType.caption, color = if (over) Iris.colors.danger else Iris.colors.textTertiary,
            )
        }
    }
}


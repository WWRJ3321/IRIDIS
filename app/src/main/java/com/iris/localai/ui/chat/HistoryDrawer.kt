package com.iris.localai.ui.chat

import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.data.ConversationSummary
import com.iris.localai.data.Format
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.EmptyState
import com.iris.localai.ui.components.NavRow
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.StatusDot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.iris.localai.ui.components.TextInputDialog
import com.iris.localai.ui.components.floatingSurface
import com.iris.localai.ui.components.glassSurface
import com.iris.localai.ui.components.largeSurface
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion

private sealed interface DrawerEntry {
    val key: String
    data class Header(val label: String) : DrawerEntry { override val key get() = "h-$label" }
    data class Item(val summary: ConversationSummary) : DrawerEntry { override val key get() = summary.id }
}

@Composable
fun HistoryDrawer(
    summaries: List<ConversationSummary>,
    currentId: String,
    apiRunning: Boolean,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onApi: () -> Unit,
    onFiles: () -> Unit = {},
    onImageGen: () -> Unit = {},
    /** 2.3.2 角色卡 */
    onCharacters: () -> Unit = {},
    runningId: String? = null,
    open: Boolean = true,
    trash: List<com.iris.localai.data.TrashEntry> = emptyList(),
    onRestore: (String) -> Unit = {},
    onPurge: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    // 1.13.2: long press → multi-select (user: 一个一个删除太麻烦了).
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingMany by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    // Selections of conversations that no longer exist drop out.
    LaunchedEffect(summaries) { if (selecting) selected = selected.intersect(summaries.map { it.id }.toSet()) }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    // 1.14.3: leaving the drawer (back to the chat, or to another page) ends multi-select.
    LaunchedEffect(open) { if (!open) { selected = emptySet(); deletingMany = false } }

    // Flattened once per data change; the list itself only diffs keys.
    val entries = remember(summaries, query) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) summaries else summaries.filter { it.title.contains(q, ignoreCase = true) }
        val pinned = filtered.filter { it.pinned }
        val rest = filtered.filterNot { it.pinned }.groupBy { Format.dateGroup(it.updatedAt) }
        buildList {
            if (pinned.isNotEmpty()) { add(DrawerEntry.Header(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_3d43ff))); pinned.forEach { add(DrawerEntry.Item(it)) } }
            rest.forEach { (k, v) -> add(DrawerEntry.Header(k)); v.forEach { add(DrawerEntry.Item(it)) } }
        }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(IrisDimens.drawerWidth)
            // 2.2.5: near-opaque large surface (was see-through glass in 2.2.4).
            .largeSurface(com.iris.localai.ui.theme.SmoothRoundedShape(0.dp, IrisRadius.sheet, IrisRadius.sheet, 0.dp), com.iris.localai.ui.theme.IrisElevation.menu)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = selecting,
            transitionSpec = { fadeIn(IrisMotion.enter()) togetherWith fadeOut(IrisMotion.standard()) },
            label = "drawerTop",
        ) { sel ->
            if (sel) {
                val visibleIds = entries.mapNotNull { (it as? DrawerEntry.Item)?.summary?.id }
                val all = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
                Row(
                    Modifier.fillMaxWidth().padding(start = IrisDimens.space8, end = IrisDimens.space8, top = IrisDimens.space12).heightIn(min = 44.dp + IrisDimens.space4 + 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableIcon(Icons.Outlined.Close, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6be271), size = 40.dp, iconSize = IrisDimens.iconMedium, tint = c.text) { selected = emptySet() }
                    Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1e06ae, selected.size), style = IrisType.rowStrong, color = c.text, modifier = Modifier.weight(1f).padding(start = IrisDimens.space4))
                    com.iris.localai.ui.components.IrisButton(
                        if (all) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_101195) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_66eeac),
                        { selected = if (all) selected - visibleIds.toSet() else selected + visibleIds },
                        style = com.iris.localai.ui.components.IrisButtonStyle.Text, compact = true,
                    )
                }
            } else {
                Column {
                    SearchField(query, onChange = { query = it }, placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_92a62b))
                    Spacer(Modifier.height(IrisDimens.space4))
                    NavRow(Icons.Outlined.EditNote, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1ac07a), emphasized = true, onClick = onNewChat)
                }
            }
        }

        val listState = rememberLazyListState()
        // Opening a different conversation or searching starts from the top.
        LaunchedEffect(query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }
        Box(Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_48abff) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_192363),
                    message = if (query.isBlank()) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_9502a9) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4000cb),
                    icon = if (query.isBlank()) Icons.Outlined.Forum else Icons.Outlined.SearchOff,
                    modifier = Modifier.padding(top = IrisDimens.space32),
                )
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = IrisDimens.space8)) {
                    items(
                        entries,
                        key = { it.key },
                        contentType = { if (it is DrawerEntry.Header) 0 else 1 },
                    ) { e ->
                        val anim = if (reduce) Modifier else Modifier.animateItem(
                            fadeInSpec = IrisMotion.standard(),
                            placementSpec = IrisMotion.Gentle.offset(),
                            fadeOutSpec = IrisMotion.standard(),
                        )
                        when (e) {
                            is DrawerEntry.Header -> Text(
                                e.label, style = IrisType.caption.copy(fontWeight = IrisType.labelSmall.fontWeight), color = c.textTertiary,
                                modifier = anim.padding(start = IrisDimens.pageGutter, top = IrisDimens.space16, bottom = IrisDimens.space4),
                            )
                            is DrawerEntry.Item -> {
                                val s = e.summary
                                ConversationItem(
                                    s, selected = s.id == currentId, modifier = anim, running = s.id == runningId,
                                    selecting = selecting, checked = s.id in selected,
                                    onToggle = { selected = if (s.id in selected) selected - s.id else selected + s.id },
                                    onClick = { onOpen(s.id) },
                                    onRename = { renaming = s },
                                    onPin = { onPin(s.id, !s.pinned) },
                                    onDelete = { deleting = s },
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16).height(IrisDimens.hairline).background(c.outline))
        Spacer(Modifier.height(IrisDimens.space4))
        AnimatedVisibility(selecting, enter = fadeIn(IrisMotion.enter()) + androidx.compose.animation.expandVertically(IrisMotion.Snappy.size()), exit = fadeOut(IrisMotion.standard()) + androidx.compose.animation.shrinkVertically(IrisMotion.Snappy.size())) {
            val chosen = summaries.filter { it.id in selected }
            val allPinned = chosen.isNotEmpty() && chosen.all { it.pinned }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SelectAction(Icons.Outlined.PushPin, if (allPinned) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_84e4fa) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_3d43ff), enabled = chosen.isNotEmpty()) {
                    chosen.forEach { if (it.pinned == allPinned) onPin(it.id, !allPinned) }
                    selected = emptySet()
                }
                SelectAction(Icons.Outlined.DriveFileRenameOutline, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b), enabled = chosen.size == 1) {
                    renaming = chosen.first(); selected = emptySet()
                }
                SelectAction(Icons.Outlined.DeleteOutline, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), enabled = chosen.isNotEmpty(), destructive = true) { deletingMany = true }
            }
        }
        if (!selecting) {
        // 2.2.14: 回收站 entry.
        Text(
            if (trash.isEmpty()) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2896bd) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_e25eb2, trash.size),
            style = IrisType.caption, color = c.textSecondary,
            modifier = Modifier
                .padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space4)
                .clip(IrisRadius.smallShape)
                .clickable { showTrash = true }
                .padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
        )
        // 2.1: one row of icon buttons instead of five stacked rows — more room for the list.
        Row(
            Modifier.fillMaxWidth().tourTarget(TourId.DRAWER_NAV).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DrawerNavButton(com.iris.localai.ui.icons.IrisIcons.Cube, DrawerNavText.MODELS, onClick = onModels)
            // 2.3.3: 角色 moved to the top-bar 角色 pill (its 「管理角色…」 opens the page).
            DrawerNavButton(com.iris.localai.ui.icons.IrisIcons.Image, DrawerNavText.IMAGEGEN, onClick = onImageGen)
            DrawerNavButton(com.iris.localai.ui.icons.IrisIcons.Folder, DrawerNavText.FILES, onClick = onFiles)
            DrawerNavButton(com.iris.localai.ui.icons.IrisIcons.Plug, DrawerNavText.API, badge = apiRunning, onClick = onApi)
            DrawerNavButton(com.iris.localai.ui.icons.IrisIcons.Settings, DrawerNavText.SETTINGS, onClick = onSettings)
        }
        }
        Spacer(Modifier.height(IrisDimens.space8))
    }

    if (deletingMany) {
        val n = selected.size
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7fa0f1, n),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7eba23) + if (runningId != null && runningId in selected) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_15ebef) else "",
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
            onConfirm = { selected.forEach(onDelete); selected = emptySet() },
            onDismiss = { deletingMany = false },
        )
    }

    renaming?.let { s ->
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b),
            initial = s.title,
            onConfirm = { onRename(s.id, it.trim()) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f8de76),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_3693f6, s.title.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1ac07a) }),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
            onConfirm = { onDelete(s.id) },
            onDismiss = { deleting = null },
        )
    }
    if (showTrash) TrashSheet(trash, onRestore, onPurge, onEmptyTrash, onDismiss = { showTrash = false })
}

/** 2.1: short labels of the drawer's bottom navigation. */
internal object DrawerNavText {
    val MODELS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8000f1)
    /** 2.1.7 */
    val IMAGEGEN: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_19a9bc)
    val CHARACTERS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ch232_nav)
    val FILES: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2a0c47)
    const val API = "API"
    val SETTINGS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_e366cc)
    val API_RUNNING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_992c3b)
}

/** Icon over a short label; [badge] puts a live dot on the icon (API server running). */
@Composable
private fun DrawerNavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, badge: Boolean = false, onClick: () -> Unit) {
    val c = Iris.colors
    Column(
        Modifier
            .clip(IrisRadius.mediumShape)
            .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (badge) stateDescription = DrawerNavText.API_RUNNING }
            .padding(horizontal = com.iris.localai.ui.theme.IrisSpace.S, vertical = IrisDimens.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, null, tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge))
            if (badge) StatusDot(c.success, active = true, size = 7.dp, modifier = Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(IrisDimens.space2))
        Text(label, style = IrisType.caption, color = c.textSecondary, maxLines = 1)
    }
}

/** Rounded search field with leading icon and a clear button. */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = Iris.colors
    Row(
        Modifier
            .padding(start = IrisDimens.space16, end = IrisDimens.space16, top = IrisDimens.space12)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(IrisRadius.mediumShape)
            .background(c.surfaceVariant)
            .padding(start = IrisDimens.space12, end = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = c.textTertiary, modifier = Modifier.size(IrisDimens.iconMedium))
        Spacer(Modifier.width(IrisDimens.space8))
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = IrisType.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = IrisType.body, color = c.textTertiary)
                    inner()
                }
            },
        )
        AnimatedVisibility(value.isNotEmpty(), enter = com.iris.localai.ui.motion.IrisMotion.fadeIn(), exit = com.iris.localai.ui.motion.IrisMotion.fadeOut()) {
            PressableIcon(Icons.Outlined.Close, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), size = 36.dp, iconSize = IrisDimens.iconSmall, tint = c.textTertiary) { onChange("") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    s: ConversationSummary,
    selected: Boolean,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Iris.colors
    var menu by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when { selecting && checked -> c.accentContainer; !selecting && selected -> c.surfaceVariant; else -> Color.Transparent },
        IrisMotion.standard(), label = "convBg",
    )
    val haptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = IrisDimens.space8, vertical = com.iris.localai.ui.theme.IrisSpace.XS)
                .heightIn(min = 44.dp)
                .clip(IrisRadius.mediumShape)
                .background(bg)
                .combinedClickable(
                    onClick = if (selecting) onToggle else onClick,
                    // 1.13.2: long press starts multi-select (rename / pin / delete live in its bar).
                    onLongClick = { haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.LONG_PRESS); onToggle() },
                    onLongClickLabel = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_443f46),
                )
                .padding(horizontal = IrisDimens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                selecting,
                enter = fadeIn(IrisMotion.enter()) + androidx.compose.animation.expandHorizontally(IrisMotion.Snappy.size()),
                exit = fadeOut(IrisMotion.standard()) + androidx.compose.animation.shrinkHorizontally(IrisMotion.Snappy.size()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.iris.localai.ui.components.IrisRadio(checked)
                    Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.S))
                }
            }
            if (s.pinned) {
                Icon(Icons.Outlined.PushPin, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(IrisDimens.space6))
            }
            Text(
                s.title.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1ac07a) }, style = if (selected) IrisType.rowStrong else IrisType.body, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            // 1.12.3: this conversation's reply keeps running while another one is shown.
            if (running) {
                Spacer(Modifier.width(IrisDimens.space6))
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d679ae), style = IrisType.caption, color = c.accent, maxLines = 1)
            }
        }
        com.iris.localai.ui.components.IrisMenu(menu, { menu = false }) {
            com.iris.localai.ui.components.IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8ce4b), Icons.Outlined.DriveFileRenameOutline) { menu = false; onRename() }
            com.iris.localai.ui.components.IrisMenuItem(if (s.pinned) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_84e4fa) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_3d43ff), Icons.Outlined.PushPin) { menu = false; onPin() }
            com.iris.localai.ui.components.IrisMenuItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), Icons.Outlined.DeleteOutline, destructive = true) { menu = false; onDelete() }
        }
    }
}

@Composable
private fun SelectAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val c = Iris.colors
    val tint = when { !enabled -> c.textTertiary.copy(alpha = 0.5f); destructive -> c.danger; else -> c.text }
    Column(
        Modifier
            .clip(IrisRadius.mediumShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(IrisDimens.iconLarge))
        Spacer(Modifier.height(IrisDimens.space2))
        Text(label, style = IrisType.caption, color = tint)
    }
}

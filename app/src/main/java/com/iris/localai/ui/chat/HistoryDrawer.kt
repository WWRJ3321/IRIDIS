package com.iris.localai.ui.chat

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.ChatExport
import com.iris.localai.data.HistorySearch
import com.iris.localai.data.TrashEntry
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.IrisRadio
import com.iris.localai.ui.feedback.FeedbackEvent
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.IrisElevation
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.theme.SmoothRoundedShape
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
import androidx.compose.material.icons.outlined.Share
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
import com.iris.localai.data.ConversationOrder
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
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
    /** a message that matches the search (snippet + highlight); tap → that message. */
    data class Hit(val hit: HistorySearch.Hit) : DrawerEntry { override val key get() = "m-" + hit.conversationId + "-" + hit.messageId }
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
    /** 角色卡 */
    onCharacters: () -> Unit = {},
    runningId: String? = null,
    open: Boolean = true,
    trash: List<TrashEntry> = emptyList(),
    onRestore: (String) -> Unit = {},
    onPurge: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    /** 全文搜索: message hits for a query. */
    onSearchMessages: suspend (String) -> List<HistorySearch.Hit> = { emptyList() },
    onOpenAt: (String, String) -> Unit = { id, _ -> onOpen(id) },
    /** share the selected conversations as one file. */
    onExport: (Set<String>, ChatExport.Format) -> Unit = { _, _ -> },
    /** 用户拖动排过序：非置顶的对话不再按日期分组。 */
    customOrder: Boolean = false,
    /** 长按拖动松手：显示顺序的全部 id。 */
    onReorder: (List<String>) -> Unit = {},
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    // long press → multi-select (user: 一个一个删除太麻烦了).
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingMany by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    // Selections of conversations that no longer exist drop out.
    LaunchedEffect(summaries) { if (selecting) selected = selected.intersect(summaries.map { it.id }.toSet()) }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    // leaving the drawer (back to the chat, or to another page) ends multi-select.
    LaunchedEffect(open) { if (!open) { selected = emptySet(); deletingMany = false } }

    // message hits, searched off the main thread after a short pause in typing.
    var hits by remember { mutableStateOf<List<HistorySearch.Hit>>(emptyList()) }
    LaunchedEffect(query, summaries) {
        val q = query.trim()
        if (q.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        hits = runCatching { onSearchMessages(q) }.getOrDefault(emptyList())
    }
    var exportMenu by remember { mutableStateOf(false) }
    // 长按拖动排序：拖动中的对话、它的位移、拖动中的临时顺序（松手后交给 onReorder）。
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var live by remember { mutableStateOf<List<ConversationSummary>?>(null) }
    LaunchedEffect(summaries) { if (dragId == null) live = null }
    val latestSummaries by rememberUpdatedState(summaries)
    val latestReorder by rememberUpdatedState(onReorder)
    val dragHaptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val shown = live ?: summaries
    val flat = customOrder || live != null
    // Flattened once per data change; the list itself only diffs keys.
    val entries = remember(shown, query, hits, flat) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) shown else shown.filter { it.title.contains(q, ignoreCase = true) }
        val pinned = filtered.filter { it.pinned }
        val others = filtered.filterNot { it.pinned }
        buildList {
            if (pinned.isNotEmpty()) { add(DrawerEntry.Header(I18n.s(R.string.chat_3d43ff))); pinned.forEach { add(DrawerEntry.Item(it)) } }
            if (flat) {
                if (pinned.isNotEmpty() && others.isNotEmpty()) add(DrawerEntry.Header(I18n.s(R.string.hd27e_chats)))
                others.forEach { add(DrawerEntry.Item(it)) }
            } else others.groupBy { Format.dateGroup(it.updatedAt) }.forEach { (k, v) -> add(DrawerEntry.Header(k)); v.forEach { add(DrawerEntry.Item(it)) } }
            if (q.isNotEmpty() && hits.isNotEmpty()) {
                add(DrawerEntry.Header(I18n.s(R.string.hs263_in_messages)))
                hits.forEach { add(DrawerEntry.Hit(it)) }
            }
        }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width(IrisDimens.drawerWidth)
            // near-opaque large surface (was see-through glass in 2.2.4).
            .largeSurface(SmoothRoundedShape(0.dp, IrisRadius.sheet, IrisRadius.sheet, 0.dp), IrisElevation.menu)
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
                    PressableIcon(Icons.Outlined.Close, I18n.s(R.string.chat_6be271), size = 40.dp, iconSize = IrisDimens.iconMedium, tint = c.text) { selected = emptySet() }
                    Text(I18n.s(R.string.chat_1e06ae, selected.size), style = IrisType.rowStrong, color = c.text, modifier = Modifier.weight(1f).padding(start = IrisDimens.space4))
                    IrisButton(
                        if (all) I18n.s(R.string.chat_101195) else I18n.s(R.string.chat_66eeac),
                        { selected = if (all) selected - visibleIds.toSet() else selected + visibleIds },
                        style = IrisButtonStyle.Text, compact = true,
                    )
                }
            } else {
                Column {
                    SearchField(query, onChange = { query = it }, placeholder = I18n.s(R.string.chat_92a62b))
                    Spacer(Modifier.height(IrisDimens.space4))
                    NavRow(Icons.Outlined.EditNote, I18n.s(R.string.chat_1ac07a), emphasized = true, onClick = onNewChat)
                }
            }
        }

        val listState = rememberLazyListState()
        // Opening a different conversation or searching starts from the top.
        LaunchedEffect(query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }
        Box(Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) I18n.s(R.string.chat_48abff) else I18n.s(R.string.chat_192363),
                    message = if (query.isBlank()) I18n.s(R.string.chat_9502a9) else I18n.s(R.string.chat_4000cb),
                    icon = if (query.isBlank()) Icons.Outlined.Forum else Icons.Outlined.SearchOff,
                    modifier = Modifier.padding(top = IrisDimens.space32),
                )
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = IrisDimens.space8)) {
                    items(
                        entries,
                        key = { it.key },
                        contentType = { when (it) { is DrawerEntry.Header -> 0; is DrawerEntry.Item -> 1; is DrawerEntry.Hit -> 2 } },
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
                            is DrawerEntry.Hit -> if (!selecting) SearchHitRow(e.hit, anim) { onOpenAt(e.hit.conversationId, e.hit.messageId) }
                            is DrawerEntry.Item -> {
                                val s = e.summary
                                val dragging = dragId == s.id
                                val canDrag = !selecting && query.isBlank()
                                ConversationItem(
                                    s, selected = s.id == currentId || dragging, running = s.id == runningId,
                                    modifier = if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragDy } else anim,
                                    selecting = selecting, checked = s.id in selected,
                                    dragModifier = if (!canDrag) null else Modifier.pointerInput(s.id) {
                                        var moved = false
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { moved = false; dragId = s.id; dragDy = 0f; live = latestSummaries; dragHaptic.perform(FeedbackEvent.LONG_PRESS) },
                                            onDragEnd = {
                                                val l = live
                                                dragId = null; dragDy = 0f
                                                // 长按不动 = 原来的多选；拖过 = 记下新顺序。
                                                if (!moved) { live = null; selected = selected + s.id } else if (l != null) latestReorder(l.map { it.id })
                                            },
                                            onDragCancel = { dragId = null; dragDy = 0f; live = null },
                                        ) { ch, d ->
                                            ch.consume()
                                            if (d.y != 0f) moved = true
                                            dragDy += d.y
                                            val info = listState.layoutInfo.visibleItemsInfo
                                            val me = info.firstOrNull { it.key == s.id } ?: return@detectDragGesturesAfterLongPress
                                            val mid = me.offset + dragDy + me.size / 2f
                                            val target = info.firstOrNull { it.key != s.id && it.contentType == 1 && mid >= it.offset && mid <= it.offset + it.size }
                                                ?: return@detectDragGesturesAfterLongPress
                                            val cur = live ?: return@detectDragGesturesAfterLongPress
                                            val next = ConversationOrder.move(cur, s.id, target.key as String)
                                            if (next !== cur) { live = next; dragDy -= (target.offset - me.offset) }
                                        }
                                    },
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
                SelectAction(Icons.Outlined.PushPin, if (allPinned) I18n.s(R.string.chat_84e4fa) else I18n.s(R.string.chat_3d43ff), enabled = chosen.isNotEmpty()) {
                    chosen.forEach { if (it.pinned == allPinned) onPin(it.id, !allPinned) }
                    selected = emptySet()
                }
                // 分享 / 导出 the chosen conversations (Markdown / TXT / JSON, one file).
                Box {
                    SelectAction(Icons.Outlined.Share, I18n.s(R.string.ex263_share), enabled = chosen.isNotEmpty()) { exportMenu = true }
                    IrisMenu(exportMenu, onDismiss = { exportMenu = false }) {
                        for (f in ChatExport.Format.entries) {
                            IrisMenuItem(ExportText.label(f), null) {
                                exportMenu = false; onExport(chosen.map { it.id }.toSet(), f); selected = emptySet()
                            }
                        }
                    }
                }
                SelectAction(Icons.Outlined.DriveFileRenameOutline, I18n.s(R.string.chat_c8ce4b), enabled = chosen.size == 1) {
                    renaming = chosen.first(); selected = emptySet()
                }
                SelectAction(Icons.Outlined.DeleteOutline, I18n.s(R.string.chat_2f4aad), enabled = chosen.isNotEmpty(), destructive = true) { deletingMany = true }
            }
        }
        if (!selecting) {
        // 回收站 entry.
        Text(
            if (trash.isEmpty()) I18n.s(R.string.chat_2896bd) else I18n.s(R.string.chat_e25eb2, trash.size),
            style = IrisType.caption, color = c.textSecondary,
            modifier = Modifier
                .padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space4)
                .clip(IrisRadius.smallShape)
                .clickable { showTrash = true }
                .padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
        )
        // one row of icon buttons instead of five stacked rows — more room for the list.
        Row(
            Modifier.fillMaxWidth().tourTarget(TourId.DRAWER_NAV).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DrawerNavButton(IrisIcons.Cube, DrawerNavText.MODELS, onClick = onModels)
            // 角色 moved to the top-bar 角色 pill (its 「管理角色…」 opens the page).
            DrawerNavButton(IrisIcons.Image, DrawerNavText.IMAGEGEN, onClick = onImageGen)
            DrawerNavButton(IrisIcons.Folder, DrawerNavText.FILES, onClick = onFiles)
            DrawerNavButton(IrisIcons.Plug, DrawerNavText.API, badge = apiRunning, onClick = onApi)
            DrawerNavButton(IrisIcons.Settings, DrawerNavText.SETTINGS, onClick = onSettings)
        }
        }
        Spacer(Modifier.height(IrisDimens.space8))
    }

    if (deletingMany) {
        val n = selected.size
        ConfirmDialog(
            title = I18n.s(R.string.chat_7fa0f1, n),
            message = I18n.s(R.string.chat_7eba23) + if (runningId != null && runningId in selected) I18n.s(R.string.chat_15ebef) else "",
            confirmText = I18n.s(R.string.chat_2f4aad),
            onConfirm = { selected.forEach(onDelete); selected = emptySet() },
            onDismiss = { deletingMany = false },
        )
    }

    renaming?.let { s ->
        TextInputDialog(
            title = I18n.s(R.string.chat_c8ce4b),
            initial = s.title,
            onConfirm = { onRename(s.id, it.trim()) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = I18n.s(R.string.chat_f8de76),
            message = I18n.s(R.string.chat_3693f6, s.title.ifBlank { I18n.s(R.string.chat_1ac07a) }),
            confirmText = I18n.s(R.string.chat_2f4aad),
            onConfirm = { onDelete(s.id) },
            onDismiss = { deleting = null },
        )
    }
    if (showTrash) TrashSheet(trash, onRestore, onPurge, onEmptyTrash, onDismiss = { showTrash = false })
}

/** export format names in the 分享 menu. */
internal object ExportText {
    fun label(f: ChatExport.Format): String = when (f) {
        ChatExport.Format.MARKDOWN -> "Markdown (.md)"
        ChatExport.Format.TXT -> I18n.s(R.string.ex263_txt)
        ChatExport.Format.JSON -> "JSON (.json)"
    }
}

/** one search hit — conversation title, then the snippet with the matches highlighted. */
@Composable
private fun SearchHitRow(h: HistorySearch.Hit, modifier: Modifier, onClick: () -> Unit) {
    val c = Iris.colors
    val text = remember(h, c.accent) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(h.snippet)
            h.ranges.forEach { r ->
                if (r.first >= 0 && r.last < h.snippet.length) addStyle(
                    androidx.compose.ui.text.SpanStyle(color = c.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, background = c.accent.copy(alpha = 0.14f)),
                    r.first, r.last + 1,
                )
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = IrisDimens.space8, vertical = IrisSpace.XS)
            .clip(IrisRadius.mediumShape)
            .clickable(onClick = onClick)
            .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
    ) {
        Text(h.title.ifBlank { I18n.s(R.string.chat_1ac07a) }, style = IrisType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text, style = IrisType.secondary, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** short labels of the drawer's bottom navigation. */
internal object DrawerNavText {
    val MODELS: String get() = I18n.s(R.string.chat_8000f1)
    /** 2.1.7 */
    val IMAGEGEN: String get() = I18n.s(R.string.chat_19a9bc)
    val CHARACTERS: String get() = I18n.s(R.string.ch232_nav)
    val FILES: String get() = I18n.s(R.string.chat_2a0c47)
    const val API = "API"
    val SETTINGS: String get() = I18n.s(R.string.chat_e366cc)
    val API_RUNNING: String get() = I18n.s(R.string.chat_992c3b)
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
            .padding(horizontal = IrisSpace.S, vertical = IrisDimens.space6),
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
        AnimatedVisibility(value.isNotEmpty(), enter = IrisMotion.fadeIn(), exit = IrisMotion.fadeOut()) {
            PressableIcon(Icons.Outlined.Close, I18n.s(R.string.chat_4403fc), size = 36.dp, iconSize = IrisDimens.iconSmall, tint = c.textTertiary) { onChange("") }
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
    /** 长按拖动排序（有它时长按不动由它进多选）。 */
    dragModifier: Modifier? = null,
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
                .padding(horizontal = IrisDimens.space8, vertical = IrisSpace.XS)
                .heightIn(min = 44.dp)
                .clip(IrisRadius.mediumShape)
                .background(bg)
                .then(dragModifier ?: Modifier)
                .combinedClickable(
                    onClick = if (selecting) onToggle else onClick,
                    // long press starts multi-select (rename / pin / delete live in its bar); with drag on, the drag detector does it.
                    onLongClick = if (dragModifier != null) null else ({ haptic.perform(FeedbackEvent.LONG_PRESS); onToggle() }),
                    onLongClickLabel = I18n.s(R.string.chat_443f46),
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
                    IrisRadio(checked)
                    Spacer(Modifier.width(IrisSpace.S))
                }
            }
            if (s.pinned) {
                Icon(Icons.Outlined.PushPin, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(IrisDimens.space6))
            }
            Text(
                s.title.ifBlank { I18n.s(R.string.chat_1ac07a) }, style = if (selected) IrisType.rowStrong else IrisType.body, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            // this conversation's reply keeps running while another one is shown.
            if (running) {
                Spacer(Modifier.width(IrisDimens.space6))
                Text(I18n.s(R.string.chat_d679ae), style = IrisType.caption, color = c.accent, maxLines = 1)
            }
        }
        IrisMenu(menu, { menu = false }) {
            IrisMenuItem(I18n.s(R.string.chat_c8ce4b), Icons.Outlined.DriveFileRenameOutline) { menu = false; onRename() }
            IrisMenuItem(if (s.pinned) I18n.s(R.string.chat_84e4fa) else I18n.s(R.string.chat_3d43ff), Icons.Outlined.PushPin) { menu = false; onPin() }
            IrisMenuItem(I18n.s(R.string.chat_2f4aad), Icons.Outlined.DeleteOutline, destructive = true) { menu = false; onDelete() }
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

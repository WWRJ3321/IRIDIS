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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.iris.localai.data.HistoryPress
import com.iris.localai.data.DrawerGroups
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
    /** 3.0 AI 工具箱 */
    onToolbox: () -> Unit = {},
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
    // long press → multi-select (user: 一个一个删除太麻烦了).
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingMany by remember { mutableStateOf(false) }
    // 2.7.6：长按到时进入多选、手指还按着的那一条（按住期间保留它的手势检测，好接着判断松手 / 拖动）。
    var holdId by remember { mutableStateOf<String?>(null) }
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
    // 2.7.7（代码先行）：拖动中不改列表顺序，拖动行按手指画（视口坐标的 top），其它行用位移让位；松手才重排 + 归位动画。
    var dragId by remember { mutableStateOf<String?>(null) }
    // 2.7.7：拖动行画在列表上方的浮层里（视口坐标 top = 手指 − 抓取偏移），列表里原行留空位（透明、钉住收手势）；
    // 自动滚动只动列表内容，浮层不动 = 一直在手指下。
    var dragTop by remember { mutableFloatStateOf(0f) }      // 浮层 top（列表视口坐标）
    var dragVel by remember { mutableFloatStateOf(0f) }      // Beta2：每次移动的位移，给浮层一点随速度的倾斜
    var dragScrolled by remember { mutableFloatStateOf(0f) } // 起拖以来自动滚动累计（内容坐标换算用，只加一次）
    val fingerHolder = remember { floatArrayOf(Float.NaN) }  // 手指在列表视口里的 y（视口 Box 的 Initial 阶段写入）
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    var dragSize by remember { mutableIntStateOf(0) }
    var settleId by remember { mutableStateOf<String?>(null) }
    var settleP by remember { mutableFloatStateOf(0f) }
    var dropTop by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var live by remember { mutableStateOf<List<ConversationSummary>?>(null) }
    LaunchedEffect(summaries) { if (dragId == null) live = null }
    val latestSummaries by rememberUpdatedState(summaries)
    val latestReorder by rememberUpdatedState(onReorder)
    val latestOpen by rememberUpdatedState(onOpen)
    val dragHaptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val latestEntriesHolder = remember { arrayOfNulls<List<DrawerEntry>>(1) }
    val shown = live ?: summaries
    // 2.7.6 r2：自定义顺序（拖动排序后）也保留日期分组标题，组内按自定义顺序（[DrawerGroups.byDate]）；
    // 以前首次拖动松手后切成平铺，「今天 / 昨天…」标题全部消失。
    // Flattened once per data change; the list itself only diffs keys.
    val entries = remember(shown, query, hits) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) shown else shown.filter { it.title.contains(q, ignoreCase = true) }
        val pinned = filtered.filter { it.pinned }
        val others = filtered.filterNot { it.pinned }
        buildList {
            if (pinned.isNotEmpty()) { add(DrawerEntry.Header(I18n.s(R.string.chat_3d43ff))); pinned.forEach { add(DrawerEntry.Item(it)) } }
            DrawerGroups.byDate(others, { Format.dateGroup(it.updatedAt) }, { it.updatedAt })
                .forEach { (k, v) -> add(DrawerEntry.Header(k)); v.forEach { add(DrawerEntry.Item(it)) } }
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
        // 顶栏高度固定（搜索栏一直参与测量），多选栏只是叠在上面淡入淡出：进 / 出多选列表不上下跳；
        // 因拖动退出多选时多选栏立即消失（不留「已选 0 项」残影）。
        val selAlpha by animateFloatAsState(
            if (selecting) 1f else 0f,
            if (dragId != null) snap() else IrisMotion.standard(),
            label = "drawerTop",
        )
        var shownCount by remember { mutableIntStateOf(0) }
        if (selecting) shownCount = selected.size
        Box {
            Column(
                Modifier
                    .graphicsLayer { alpha = 1f - selAlpha }
                    .then(if (selecting) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                SearchField(query, onChange = { query = it }, placeholder = I18n.s(R.string.chat_92a62b))
                Spacer(Modifier.height(IrisDimens.space4))
                NavRow(Icons.Outlined.EditNote, I18n.s(R.string.chat_1ac07a), emphasized = true, onClick = onNewChat)
            }
            if (selecting || selAlpha > 0f) {
                val visibleIds = entries.mapNotNull { (it as? DrawerEntry.Item)?.summary?.id }
                val all = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
                Row(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = selAlpha }
                        // 挡住下面的搜索栏（叠层期间点不到它）
                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                        .padding(horizontal = IrisDimens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableIcon(Icons.Outlined.Close, I18n.s(R.string.chat_6be271), size = 40.dp, iconSize = IrisDimens.iconMedium, tint = c.text) { selected = emptySet() }
                    Text(I18n.s(R.string.chat_1e06ae, shownCount), style = IrisType.rowStrong, color = c.text, modifier = Modifier.weight(1f).padding(start = IrisDimens.space4))
                    IrisButton(
                        if (all) I18n.s(R.string.chat_101195) else I18n.s(R.string.chat_66eeac),
                        { selected = if (all) selected - visibleIds.toSet() else selected + visibleIds },
                        style = IrisButtonStyle.Text, compact = true,
                    )
                }
            }
        }

        val listState = rememberLazyListState()
        val overlayLift by animateFloatAsState(if (dragId != null) 1f else 0f, if (reduce) snap() else IrisMotion.standard(), label = "overlayLift")
        // Opening a different conversation or searching starts from the top.
        LaunchedEffect(query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }
        Box(
            Modifier
                .weight(1f)
                // 手指位置记在列表视口坐标里（这个 Box 不随列表滚动 / 行位移而动）；只看不消费，先于行的手势读到。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            ev.changes.firstOrNull { it.pressed }?.let { fingerHolder[0] = it.position.y }
                        }
                    }
                },
        ) {
            if (entries.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) I18n.s(R.string.chat_48abff) else I18n.s(R.string.chat_192363),
                    message = if (query.isBlank()) I18n.s(R.string.chat_9502a9) else I18n.s(R.string.chat_4000cb),
                    icon = if (query.isBlank()) Icons.Outlined.Forum else Icons.Outlined.SearchOff,
                    modifier = Modifier.padding(top = IrisDimens.space32),
                )
            } else {
                latestEntriesHolder[0] = entries
                val density = androidx.compose.ui.platform.LocalDensity.current
                val edgePx = with(density) { 56.dp.toPx() }
                val maxSpeedPx = with(density) { 900.dp.toPx() }   // 每秒，贴到最边时
                // 重算落点（手指移动 / 自动滚动后）：同组可见行的布局中心 vs 拖动行视觉中心。
                fun retarget(id: String) {
                    val es = latestEntriesHolder[0] ?: return
                    val info = listState.layoutInfo.visibleItemsInfo
                    if (es.getOrNull(dragFrom) !is DrawerEntry.Item) return
                    // 只在同一组（置顶 / 同一日期分组，标题之间）里放置：分组标题一直显示，跨组落点没有意义。
                    val sec = DrawerGroups.sectionOf(dragFrom) { es.getOrNull(it) is DrawerEntry.Header }
                    val slots = info.filter { it.key != id && es.getOrNull(it.index) is DrawerEntry.Item && DrawerGroups.sectionOf(it.index) { j -> es.getOrNull(j) is DrawerEntry.Header } == sec }
                        .map { HistoryPress.Slot(it.index, it.offset, it.size) }
                    dragTo = HistoryPress.dropIndexContent(dragFrom, slots, dragTop, dragSize, dragScrolled)
                }
                fun autoScroll(id: String) = dragScope.launch {
                    var last = 0L
                    while (dragId == id) {
                        val now = withFrameNanos { it }
                        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                        last = now
                        if (dt <= 0f) continue
                        val li = listState.layoutInfo
                        val step = HistoryPress.autoScrollStep(dragTop, dragSize, 0, li.viewportSize.height, edgePx, maxSpeedPx * dt)
                        if (step == 0f) continue
                        val used = listState.scrollBy(step)   // 到列表两端 used = 0，自然停住
                        if (used == 0f || dragId != id) continue
                        // 浮层 dragTop 不变（仍在手指下）；只记滚动量，落点按同帧布局重算，不再自己累计行位置
                        dragScrolled += used
                        retarget(id)
                    }
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = IrisDimens.space8)) {
                    itemsIndexed(
                        entries,
                        key = { _, it -> it.key },
                        contentType = { _, it -> when (it) { is DrawerEntry.Header -> 0; is DrawerEntry.Item -> 1; is DrawerEntry.Hit -> 2 } },
                    ) { index, e ->
                        // 拖动 / 归位期间不做 placement 动画（位移由下面的 shift 和归位动画负责，否则松手时会从旧位置再滑一次）
                        val anim = if (reduce || dragId != null || settleId != null) Modifier else Modifier.animateItem(
                            fadeInSpec = IrisMotion.standard(),
                            placementSpec = IrisMotion.Gentle.offset(),
                            fadeOutSpec = IrisMotion.standard(),
                        )
                        // 其它行给拖动行让位：拖动中平滑移动；松手（列表同一帧重排）时直接归零，不跳。
                        val itemKey = (e as? DrawerEntry.Item)?.summary?.id
                        val shiftTarget = if (dragId != null && itemKey != dragId) HistoryPress.neighborShift(index, dragFrom, dragTo, dragSize).toFloat() else 0f
                        val shift by animateFloatAsState(
                            shiftTarget,
                            if (dragId == null || reduce) snap() else spring(dampingRatio = HistoryPress.SHIFT_DAMPING, stiffness = HistoryPress.SHIFT_STIFFNESS),
                            label = "dragShift",
                        )
                        val shiftMod = Modifier.graphicsLayer { translationY = shift }
                        when (e) {
                            is DrawerEntry.Header -> Text(
                                e.label, style = IrisType.caption.copy(fontWeight = IrisType.labelSmall.fontWeight), color = c.textTertiary,
                                modifier = anim.then(shiftMod).padding(start = IrisDimens.pageGutter, top = IrisDimens.space16, bottom = IrisDimens.space4),
                            )
                            is DrawerEntry.Hit -> if (!selecting) SearchHitRow(e.hit, anim) { onOpenAt(e.hit.conversationId, e.hit.messageId) }
                            is DrawerEntry.Item -> {
                                val s = e.summary
                                val dragging = dragId == s.id
                                val settling = !dragging && settleId == s.id
                                val canDrag = query.isBlank() && (!selecting || holdId == s.id)
                                val press = remember(s.id) { MutableInteractionSource() }
                                val coordsHolder = remember(s.id) { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
                                val pinnable = LocalPinnableContainer.current
                                val lift by animateFloatAsState(if (dragging) 1f else 0f, if (reduce) snap() else IrisMotion.standard(), label = "dragLift")
                                ConversationItem(
                                    s, selected = s.id == currentId || dragging, running = s.id == runningId,
                                    lift = if (dragging) 0f else lift,
                                    modifier = anim
                                        .zIndex(if (settling) 1f else 0f)
                                        .graphicsLayer {
                                            // 拖动中原行只留空位（画在浮层里），保持布局与手势
                                            alpha = if (dragging) 0f else 1f
                                            translationY = when {
                                                dragging -> 0f
                                                settling -> {
                                                    val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == s.id }?.offset
                                                    if (top == null) 0f else HistoryPress.settleOffset(settleP, dropTop, top)
                                                }
                                                else -> shift
                                            }
                                        },
                                    selecting = selecting, checked = s.id in selected,
                                    pressSource = press,
                                    onLongPressSelect = { selected = HistoryPress.selection(HistoryPress.Outcome.SELECT, selected, s.id) },
                                    dragModifier = if (!canDrag) null else Modifier
                                        .onGloballyPositioned { coordsHolder[0] = it }
                                        .pointerInput(s.id) {
                                        // 一个手势走完：短按打开 / 长按到时直接进多选 / 之后移动 = 退出多选并拖动排序。
                                        val slop = viewConfiguration.touchSlop
                                        val lpt = viewConfiguration.longPressTimeoutMillis
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val pid = down.id
                                            // 手指位置换算到屏幕坐标：本行在动（被挤 / 被画在别处）时本地坐标会变，不能算手指移动。
                                            fun root(o: androidx.compose.ui.geometry.Offset) =
                                                coordsHolder[0]?.takeIf { it.isAttached }?.localToRoot(o) ?: o
                                            val downRoot = root(down.position)
                                            val p = PressInteraction.Press(down.position)
                                            press.tryEmit(p)
                                            var dragging = false
                                            var pin: androidx.compose.ui.layout.PinnableContainer.PinnedHandle? = null
                                            // 松手 / 取消：commit=true 才重排。拖动行从松手时的视觉位置归位到新布局位置。
                                            fun finish(commit: Boolean) {
                                                if (!dragging) return
                                                dragging = false
                                                val es = latestEntriesHolder[0].orEmpty()
                                                val base = latestSummaries
                                                val targetId = (es.getOrNull(dragTo) as? DrawerEntry.Item)?.summary?.id
                                                val next = if (commit && dragTo != dragFrom && targetId != null) ConversationOrder.move(base, s.id, targetId) else base
                                                dropTop = dragTop
                                                settleP = if (reduce) 0f else 1f
                                                settleId = s.id
                                                dragId = null; dragFrom = -1; dragTo = -1
                                                if (next !== base) { live = next; latestReorder(next.map { it.id }) }
                                                settleJob?.cancel()
                                                if (!reduce) settleJob = dragScope.launch {
                                                    animate(1f, 0f, animationSpec = spring(dampingRatio = HistoryPress.SETTLE_DAMPING, stiffness = HistoryPress.SETTLE_STIFFNESS)) { v, _ -> settleP = v }
                                                    if (settleId == s.id) settleId = null
                                                } else settleId = null
                                            }
                                            try {
                                                // 1) 长按超时前：短按 / 放弃
                                                val early = withTimeoutOrNull(lpt) {
                                                    var o: HistoryPress.Outcome? = null
                                                    while (o == null) {
                                                        val ev = awaitPointerEvent()
                                                        val ch = ev.changes.firstOrNull { it.id == pid }
                                                        o = if (ch == null) HistoryPress.Outcome.CANCEL else {
                                                            val d = root(ch.position) - downRoot
                                                            val up = ch.changedToUpIgnoreConsumed()
                                                            HistoryPress.beforeLongPress(up, ch.isConsumed, d.x, d.y, slop).also {
                                                                if (it == HistoryPress.Outcome.TAP) ch.consume()
                                                            }
                                                        }
                                                    }
                                                    o
                                                }
                                                if (early != null) {
                                                    if (early == HistoryPress.Outcome.TAP) { press.tryEmit(PressInteraction.Release(p)); latestOpen(s.id) }
                                                    else press.tryEmit(PressInteraction.Cancel(p))
                                                    return@awaitEachGesture
                                                }
                                                // 2) 长按到了（没动）：震动，按住时就进入多选并选中该条
                                                dragHaptic.perform(FeedbackEvent.LONG_PRESS)
                                                holdId = s.id
                                                selected = HistoryPress.selection(HistoryPress.onLongPress(), emptySet(), s.id)
                                                var outcome: HistoryPress.Outcome? = null
                                                var last = down
                                                while (outcome == null) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == pid }
                                                    if (ch == null) { outcome = HistoryPress.Outcome.CANCEL; break }
                                                    last = ch
                                                    val d = root(ch.position) - downRoot
                                                    outcome = HistoryPress.afterLongPress(!ch.pressed, d.x, d.y, slop)
                                                    ch.consume()
                                                }
                                                if (outcome != HistoryPress.Outcome.DRAG) {
                                                    // 原地松手（或指针被系统取消）：留在多选
                                                    press.tryEmit(PressInteraction.Release(p)); return@awaitEachGesture
                                                }
                                                // 3) 移动超过 touchSlop：退出多选（顶栏 / 底栏 / 单选圈同一帧切换，行高不变），拖动排序
                                                press.tryEmit(PressInteraction.Cancel(p))
                                                val me = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == s.id }
                                                val coords = coordsHolder[0]
                                                selected = HistoryPress.selection(HistoryPress.Outcome.DRAG, selected, s.id)
                                                holdId = null
                                                if (coords == null || !coords.isAttached || me == null) return@awaitEachGesture
                                                val startTop = me.offset.toFloat()
                                                val f0 = fingerHolder[0].takeUnless { it.isNaN() } ?: (startTop + last.position.y)
                                                val grab = HistoryPress.grabOffset(f0, startTop)
                                                settleJob?.cancel(); settleId = null
                                                dragTop = startTop; dragScrolled = 0f
                                                dragFrom = me.index; dragTo = me.index; dragSize = me.size
                                                dragging = true
                                                dragId = s.id
                                                // 自动滚动会把本行的布局位置滚出视口：钉住，别让 LazyColumn 回收它（回收 = 手势中断）
                                                pin = pinnable?.pin()
                                                autoScroll(s.id)
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == pid } ?: break
                                                    ch.consume()
                                                    if (!ch.pressed) { finish(commit = true); break }
                                                    if (!coords.isAttached) break
                                                    val f = fingerHolder[0].takeUnless { it.isNaN() } ?: continue
                                                    val nt = HistoryPress.overlayTop(f, grab, dragSize, listState.layoutInfo.viewportSize.height)
                                                    dragVel = nt - dragTop
                                                    dragTop = nt
                                                    retarget(s.id)
                                                }
                                            } finally {
                                                finish(commit = false)
                                                pin?.release()
                                                if (holdId == s.id) holdId = null
                                            }
                                        }
                                    },
                                    onSelect = { selected = selected + s.id },
                                    onToggle = { selected = if (s.id in selected) selected - s.id else selected + s.id },
                                    onClick = { onOpen(s.id) },
                                )
                            }
                        }
                    }
                }
                // 拖动浮层：画在列表上面，top = dragTop（视口坐标），不随列表滚动。
                val dragItem = dragId?.let { id -> entries.firstNotNullOfOrNull { (it as? DrawerEntry.Item)?.summary?.takeIf { s -> s.id == id } } }
                if (dragItem != null) {
                    // Beta2：浮层随拖动速度轻微倾斜、略放大，手指停下就回正——拿起 / 移动 / 放下连成一体
                    LaunchedEffect(dragVel) { if (dragVel != 0f) { kotlinx.coroutines.delay(70); dragVel = 0f } }
                    val tilt by animateFloatAsState(
                        if (reduce) 0f else HistoryPress.tiltFor(dragVel),
                        if (reduce) snap() else IrisMotion.Tilt.float(), label = "dragTilt",
                    )
                    ConversationItem(
                        dragItem, selected = true, running = dragItem.id == runningId,
                        lift = overlayLift,
                        modifier = Modifier.zIndex(2f).graphicsLayer {
                            translationY = dragTop
                            rotationZ = tilt
                            val sc = 1f + 0.025f * overlayLift
                            scaleX = sc; scaleY = sc
                        },
                        onClick = {},
                    )
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
            DrawerNavButton(IrisIcons.Tool, DrawerNavText.TOOLBOX, onClick = onToolbox)
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
    val TOOLBOX: String get() = I18n.s(R.string.v30_toolbox)
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
    /** 拖动中 0→1：浮起（阴影 + 微放大 + 不透明底，盖住邻行文字）。 */
    lift: Float = 0f,
    selecting: Boolean = false,
    checked: Boolean = false,
    /** 统一手势（短按 / 长按进多选 / 之后拖动排序）；有它时不再挂 combinedClickable。 */
    dragModifier: Modifier? = null,
    pressSource: MutableInteractionSource? = null,
    onLongPressSelect: () -> Unit = {},
    onToggle: () -> Unit = {},
    onClick: () -> Unit,
    onSelect: () -> Unit = {},
) {
    val c = Iris.colors
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
                .then(
                    if (lift > 0f) Modifier
                        .graphicsLayer {
                            shadowElevation = 8.dp.toPx() * lift
                            shape = IrisRadius.mediumShape
                            clip = false
                            val sc = 1f + 0.02f * lift
                            scaleX = sc; scaleY = sc
                        }
                        .background(c.surface, IrisRadius.mediumShape)
                    else Modifier,
                )
                .clip(IrisRadius.mediumShape)
                .background(bg)
                .then(
                    if (dragModifier != null) Modifier
                        .semantics(mergeDescendants = true) {
                            val open = onClick
                            this.onClick(label = null, action = { open(); true })
                            onLongClick(I18n.s(R.string.chat_443f46)) { onLongPressSelect(); true }
                        }
                        .then(if (pressSource != null) Modifier.indication(pressSource, LocalIndication.current) else Modifier)
                        .then(dragModifier)
                    else Modifier.combinedClickable(
                        onClick = if (selecting) onToggle else onClick,
                        onLongClick = { haptic.perform(FeedbackEvent.LONG_PRESS); if (selecting) onToggle() else onSelect() },
                        onLongClickLabel = I18n.s(R.string.chat_443f46),
                    ),
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

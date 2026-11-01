package com.iris.localai.ui.files

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import com.iris.localai.files.ArchiveMath
import com.iris.localai.files.FileEntry
import com.iris.localai.files.FilePreview
import com.iris.localai.files.FileSizes
import com.iris.localai.ui.components.EmptyState
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.glass.LocalGlassQuality
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisColors
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.theme.SmoothRoundedShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Card metrics of the archive deck. */
private val CARD_HEIGHT = 136.dp
private val CARD_MAX_WIDTH = 520.dp
private const val CAMERA_DISTANCE = 14f
private val MAX_SHADOW = 10.dp
/** Blur levels of the decrypt sweep (index 0 = sharp). */
private const val BLUR_STEPS = 5
private val BLUR_MAX = 14.dp

private val monoTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)

/** Tab / accent colour of a card, from the active palette (works in every theme and in dark mode). */
internal fun cardKindColor(kind: ArchiveMath.Kind, c: IrisColors): Color = when (kind) {
    ArchiveMath.Kind.SYSTEM -> c.accent
    ArchiveMath.Kind.FOLDER -> c.accent.copy(alpha = 0.65f)
    ArchiveMath.Kind.TEXT -> c.success
    ArchiveMath.Kind.MEDIA -> c.warning
    ArchiveMath.Kind.MODEL -> c.accent
    ArchiveMath.Kind.PACKAGE -> c.textSecondary
    ArchiveMath.Kind.OTHER -> c.textTertiary
}

/** Holder for the running settle animation (no state reads, so writes never recompose). */
private class DeckJobs { var settle: Job? = null }

/**
 * 2.1.9 「卡片」 (was the first 「档案模式」): the optional pseudo-3D card-deck view of the 「文件」 page. Entries are frosted
 * "archive cassette" cards in a vertical (looping when > 5) deck; the focused card faces front,
 * neighbours recede and tilt. Swipe / fling to browse; tap the focused card to open it (folders
 * fall back and the next folder rises, files get a decrypt sheet); long press = the list's menu.
 *
 * Same [FilesState] / [FilesActions] as [ListFileView]; dialogs come from [FilesDialogs].
 * Only the visible window (≤ 7 cards) is composed, and every per-frame value is read in
 * graphicsLayer / draw lambdas, so scrolling recomposes only when the focus steps.
 * With reduced motion there is no tilt, wave or decrypt sweep. Design inspiration: LBEILC/RhineLabUI (MIT).
 */
@Composable
fun CardStackFileView(state: FilesState, actions: FilesActions, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val entries = state.entries
    val n = entries.size
    val scope = rememberCoroutineScope()
    val pos = remember { mutableFloatStateOf(0f) }
    val phase = remember { Animatable(0f) }
    val wave = remember { Animatable(1f) }
    val jobs = remember { DeckJobs() }
    var waveTick by remember { mutableIntStateOf(0) }
    var shownRel by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf<FileEntry?>(null) }
    var lastReveal by remember { mutableStateOf<FileEntry?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val focusLogical by remember { derivedStateOf { pos.floatValue.roundToInt() } }
    val focusIndex = ArchiveMath.focusIndex(focusLogical.toFloat(), n)

    // A folder finished loading: reset to its first card and let the cards rise in a wave.
    LaunchedEffect(state.rel, state.loading) {
        if (state.loading || shownRel == state.rel) return@LaunchedEffect
        shownRel = state.rel
        jobs.settle?.cancel()
        pos.floatValue = 0f
        if (reduce) phase.snapTo(0f) else {
            phase.snapTo(-1f)
            phase.animateTo(0f, IrisMotion.progress(IrisMotion.ARCHIVE_RISE_MS))
        }
    }
    // Entries added / removed: keep the focus valid.
    LaunchedEffect(n) {
        pos.floatValue = if (ArchiveMath.loops(n)) pos.floatValue else ArchiveMath.focusIndex(pos.floatValue, n).toFloat()
    }
    // Selection wave after each settle on a card.
    LaunchedEffect(waveTick) {
        if (waveTick == 0 || reduce) return@LaunchedEffect
        wave.snapTo(0f)
        wave.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_WAVE_MS))
    }

    fun settleTo(target: Float, velocity: Float = 0f) {
        jobs.settle?.cancel()
        val changed = target.roundToInt() != focusLogical || abs(pos.floatValue - target) > 0.5f
        jobs.settle = scope.launch {
            if (reduce) pos.floatValue = target
            else animate(pos.floatValue, target, velocity, IrisMotion.ArchiveSnap.float()) { v, _ -> pos.floatValue = v }
            if (changed) waveTick++
        }
    }

    fun open(e: FileEntry) {
        if (!e.isDir) { reveal = e; lastReveal = e; return }
        if (reduce) { actions.onEntryClick(e); return }
        val before = state.rel
        scope.launch {
            phase.animateTo(1f, IrisMotion.progressOut(IrisMotion.ARCHIVE_FALL_MS))
            actions.onEntryClick(e)
            // Safety: the folder never changed (could not be opened) → bring the cards back.
            delay(1500)
            if (shownRel == before && phase.value > 0f) phase.animateTo(0f, IrisMotion.progress(IrisMotion.ARCHIVE_RISE_MS))
        }
    }

    BackHandler(enabled = reveal != null) { reveal = null }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            ArchiveHud(state, actions, n, focusIndex)
            FilesHeader(state, actions, showCrumbs = false)
            FilesAccessNotice(state)
            state.message?.let { GroupFootnote(it) }
            if (n == 0 && !state.loading) {
                EmptyState(
                    title = when {
                        state.unreadable && state.atRoot -> FilesText.UNAVAILABLE
                        state.unreadable -> "无法读取这个文件夹"
                        else -> "空文件夹"
                    },
                    message = if (state.unreadable) FilesText.UNAVAILABLE_HINT else null,
                    icon = IrisIcons.Archive,
                    modifier = Modifier.padding(top = 48.dp).fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
            } else {
                val density = LocalDensity.current
                val cardHeightPx = with(density) { CARD_HEIGHT.toPx() }
                // One card of scroll = the focused card's spacing to its neighbour.
                val unitPx = cardHeightPx * ArchiveMath.spacing(1f)
                val dragState = rememberDraggableState { dy ->
                    pos.floatValue = ArchiveMath.dragStep(pos.floatValue, -dy / unitPx, n)
                }
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds().draggable(
                        dragState, Orientation.Vertical, enabled = n > 1,
                        onDragStarted = { jobs.settle?.cancel() },
                        onDragStopped = { v ->
                            val cardsPerSecond = -v / unitPx
                            settleTo(ArchiveMath.snapTarget(pos.floatValue, cardsPerSecond, n), cardsPerSecond)
                        },
                    ),
                ) {
                    val cardWidth = (maxWidth - IrisDimens.groupGutter * 2 - IrisDimens.space16).coerceAtMost(CARD_MAX_WIDTH)
                    val half = ArchiveMath.halfWindow(n)
                    val veilColor = Iris.colors.background
                    val base = focusLogical
                    for (k in -half..half) {
                        val logical = base + k
                        val idx = ArchiveMath.entryAt(logical, n)
                        if (idx >= 0) key(logical) {
                            val e = entries[idx]
                            val slot = abs(k)
                            val layer = remember { ArchiveMath.Layer() }
                            Box(
                                Modifier.align(Alignment.Center)
                                    // 2.2.14: draw order follows the live scroll position (placement only, no
                                    // recomposition): cards no longer swap layers abruptly mid-drag (flicker).
                                    .layout { m, cons ->
                                        val p = m.measure(cons)
                                        layout(p.width, p.height) { p.place(0, 0, zIndex = ArchiveMath.drawOrder(logical - pos.floatValue)) }
                                    }
                                    .width(cardWidth).height(CARD_HEIGHT)
                                    .graphicsLayer {
                                        val d = logical - pos.floatValue
                                        ArchiveMath.layer(d, tilt = !reduce, out = layer)
                                        val depth = layer.alpha
                                        ArchiveMath.transit(phase.value, slot, layer)
                                        // opaque inside the window (depth is the veil drawn below), faded only at its edge
                                        val transitAlpha = if (depth > 0f) layer.alpha / depth else 0f
                                        layer.alpha = transitAlpha * ArchiveMath.edgeFade(d, half)
                                        val lift = if (reduce) 0f else ArchiveMath.waveLift(wave.value, slot)
                                        translationY = (layer.y + lift + ArchiveMath.deckBias(pos.floatValue, n)) * cardHeightPx
                                        scaleX = layer.scale
                                        scaleY = layer.scale
                                        alpha = layer.alpha
                                        rotationX = layer.rotationX
                                        rotationY = layer.rotationY
                                        cameraDistance = CAMERA_DISTANCE * this.density
                                        shadowElevation = layer.elevation * MAX_SHADOW.toPx()
                                        shape = cardShape
                                        clip = false
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        val veil = ArchiveMath.depthVeil(logical - pos.floatValue)
                                        if (veil > 0.005f) drawRoundRect(veilColor.copy(alpha = veil), cornerRadius = CornerRadius(IrisRadius.medium.toPx()))
                                    },
                            ) {
                                ArchiveCard(
                                    e, idx, focused = k == 0,
                                    contentAlpha = { ArchiveMath.contentAlpha(logical - pos.floatValue) },
                                    onClick = { if (k == 0) open(e) else settleTo(logical.toFloat()) },
                                    onLongClick = { menuFor = e.relPath },
                                )
                                EntryMenu(e, actions, expanded = menuFor == e.relPath, onDismiss = { menuFor = null })
                            }
                        }
                    }
                }
            }
            state.rootPath?.let { GroupFootnote(it, modifier = Modifier.padding(bottom = IrisDimens.space8)) }
        }

        // Decrypt sheet for files.
        AnimatedVisibility(reveal != null, enter = IrisMotion.fadeIn(), exit = IrisMotion.fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Iris.colors.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { reveal = null },
            )
        }
        AnimatedVisibility(
            reveal != null, modifier = Modifier.align(Alignment.BottomCenter),
            enter = IrisMotion.riseIn(0.4f), exit = IrisMotion.sinkOut(0.4f),
        ) {
            lastReveal?.let { e ->
                val index = entries.indexOfFirst { it.relPath == e.relPath }.coerceAtLeast(0)
                DecryptSheet(e, index, state, actions, onClose = { reveal = null })
            }
        }
    }
}

private val cardShape = IrisRadius.mediumShape

/** Title + rolling count, the path tag (clickable segments) and the focused card's rolling serial. */
@Composable
private fun ArchiveHud(state: FilesState, actions: FilesActions, count: Int, focusIndex: Int) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space6)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                state.crumbs.last().label, style = IrisType.display, color = c.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            RollingNumber(count, minWidth = 2, style = monoTitle, color = c.accent)
            Text(" 项", style = IrisType.caption, color = c.textSecondary, modifier = Modifier.padding(bottom = IrisDimens.space4))
        }
        Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space4), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f, fill = false).clip(IrisRadius.smallShape).background(c.surfaceVariant)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = IrisDimens.space6, vertical = IrisDimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.crumbs.forEachIndexed { i, crumb ->
                    val last = i == state.crumbs.lastIndex
                    val seg = if (i == 0) crumb.label else crumb.relPath.substringAfterLast('/')
                    Text("/", style = IrisType.monoSmall, color = c.textTertiary)
                    Text(
                        seg, style = IrisType.monoSmall, color = if (last) c.text else c.accent, maxLines = 1,
                        modifier = Modifier.clip(IrisRadius.smallShape).clickable(enabled = !last) { actions.navigate(crumb.relPath) }
                            .padding(horizontal = IrisDimens.space2, vertical = IrisDimens.space4),
                    )
                }
            }
            if (count > 0) {
                Spacer(Modifier.width(10.dp))
                Text("ARC-", style = IrisType.monoSmall, color = c.textTertiary)
                RollingNumber(focusIndex + 1, minWidth = 4, style = IrisType.monoSmall, color = c.textSecondary)
            }
        }
    }
}

/** Odometer number: each digit ([ArchiveMath.digits]) rolls on its own; plain text with reduced motion. */
@Composable
internal fun RollingNumber(value: Int, minWidth: Int, style: TextStyle, color: Color) {
    val digits = remember(value, minWidth) { ArchiveMath.digits(value, minWidth) }
    if (LocalReduceMotion.current) {
        Text(digits.joinToString(""), style = style, color = color, maxLines = 1)
        return
    }
    Row {
        digits.forEachIndexed { i, d ->
            key(ArchiveMath.slotFromEnd(i, digits.size)) {
                AnimatedContent(
                    targetState = d,
                    transitionSpec = { IrisMotion.roll(ArchiveMath.rollsUp(initialState, targetState)) },
                    label = "archiveDigit",
                ) { v -> Text(v.toString(), style = style, color = color, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** One frosted "archive cassette": tab, serial, type tag, name, size / time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveCard(e: FileEntry, index: Int, focused: Boolean, contentAlpha: () -> Float, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Iris.colors
    val kind = ArchiveMath.kind(e)
    val tab = cardKindColor(kind, c)
    // 「卡片」 fix: opaque cards, so nothing behind the focused card shows through it.
    val fill = c.surfaceElevated.copy(alpha = 1f)
    val tint = tab.copy(alpha = if (focused) 0.10f else 0.05f)
    val rim = c.glassRim.takeIf { it.alpha > 0f } ?: c.outline
    val highlight = Color.White.copy(alpha = if (c.isDark) 0.08f else 0.45f)
    Box(
        Modifier.fillMaxSize().clip(cardShape)
            .drawWithCache {
                val bg = Brush.linearGradient(listOf(tint, Color.Transparent), start = Offset.Zero, end = Offset(size.width, size.height))
                val stroke = Stroke(width = 1.dp.toPx())
                val tabW = 56.dp.toPx(); val tabH = IrisDimens.space4.toPx(); val inset = 18.dp.toPx()
                val line = 0.75.dp.toPx()
                onDrawBehind {
                    drawRect(fill)
                    drawRect(bg)
                    // inner highlight lines
                    drawLine(highlight, Offset(inset, 1.5f * line), Offset(size.width - inset, 1.5f * line), strokeWidth = line)
                    drawLine(highlight, Offset(size.width - inset * 3, size.height - inset * 0.6f), Offset(size.width - inset * 0.6f, size.height - inset * 0.6f), strokeWidth = line)
                    // top-edge coloured tab
                    drawRoundRect(tab, topLeft = Offset(inset, 0f), size = Size(tabW, tabH), cornerRadius = CornerRadius(tabH / 2))
                    // index ticks (cassette spine)
                    val tick = 3.dp.toPx(); val x0 = size.width - inset
                    for (i in 0 until 4) {
                        val x = x0 - i * tick * 1.6f
                        drawLine(rim.copy(alpha = rim.alpha * 0.8f), Offset(x, inset * 0.7f), Offset(x, inset * 0.7f + if (i == 0) tick * 3 else tick * 2), strokeWidth = line)
                    }
                }
            }
            .then(Modifier.drawWithContent { drawContent(); drawCardRim(this, rim) })
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = IrisDimens.space12),
    ) {
        // 「卡片」 fix: only the focused card's text is drawn (it fades within half a card of scroll).
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ArchiveMath.serial(index), style = IrisType.monoSmall, color = tab.copy(alpha = 1f))
                Spacer(Modifier.width(IrisDimens.space8))
                Text(
                    ArchiveMath.typeTag(e), style = IrisType.micro.copy(fontFamily = FontFamily.Monospace), color = c.textSecondary,
                    modifier = Modifier.clip(IrisRadius.smallShape).background(c.surfaceVariant).padding(horizontal = IrisDimens.space6, vertical = 1.dp),
                )
                Spacer(Modifier.weight(1f))
                if (e.system != null) SystemBadge()
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(entryIcon(e), null, Modifier.size(22.dp), tint = if (e.isDir) c.accent else c.textSecondary)
                Spacer(Modifier.width(10.dp))
                Text(e.displayName, style = IrisType.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(IrisDimens.space4))
            Text(
                entrySubtitle(e), style = IrisType.caption.copy(fontFamily = FontFamily.Monospace), color = c.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun drawCardRim(scope: androidx.compose.ui.graphics.drawscope.DrawScope, rim: Color) = with(scope) {
    val r = IrisRadius.medium.toPx()
    drawRoundRect(rim, cornerRadius = CornerRadius(r), style = Stroke(width = 1.dp.toPx()))
}

/** The list view's ⋯ menu, opened by a long press on a card. */
@Composable
internal fun EntryMenu(e: FileEntry, actions: FilesActions, expanded: Boolean, onDismiss: () -> Unit) {
    val canRename = actions.canRename(e)
    val canDelete = actions.canDelete(e)
    if (e.isDir && !canRename && !canDelete) {
        if (expanded) LaunchedEffect(Unit) { onDismiss() }
        return
    }
    IrisMenu(expanded = expanded, onDismiss = onDismiss) {
        if (!e.isDir) {
            IrisMenuItem("用其他应用打开", IrisIcons.OpenExternal) { onDismiss(); actions.openExternal(e) }
            IrisMenuItem("分享", IrisIcons.Share) { onDismiss(); actions.share(e) }
        }
        if (canRename) IrisMenuItem("重命名", IrisIcons.Edit) { onDismiss(); actions.requestRename(e) }
        if (canDelete) IrisMenuItem("删除", IrisIcons.Trash, destructive = true) { onDismiss(); actions.requestDelete(e) }
    }
}

/**
 * File details, revealed by a diagonal sweep from frosted to clear (blurred on API 31+ when the
 * visual-effects level blurs, a translucent veil otherwise; instant with reduced motion).
 */
@Composable
private fun DecryptSheet(e: FileEntry, index: Int, state: FilesState, actions: FilesActions, onClose: () -> Unit) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val blurs = LocalGlassQuality.current.blurs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val density = LocalDensity.current
    val t = remember(e.relPath) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(e.relPath) { if (!reduce) t.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_DECRYPT_MS)) }
    val effects: Array<RenderEffect?> = remember(density, blurs) {
        val max = with(density) { BLUR_MAX.toPx() }
        Array(BLUR_STEPS) { i -> if (i == 0 || !blurs) null else BlurEffect(max * i / (BLUR_STEPS - 1), max * i / (BLUR_STEPS - 1), TileMode.Decal) }
    }
    val veilPath = remember { Path() }
    val pts = remember { FloatArray(12) }
    val veil = c.surfaceVariant.copy(alpha = if (blurs) 0.55f else 0.92f)
    val edge = c.accent
    val tab = cardKindColor(ArchiveMath.kind(e), c)
    val sheetShape = SmoothRoundedShape(topStart = IrisRadius.sheet, topEnd = IrisRadius.sheet, bottomEnd = 0.dp, bottomStart = 0.dp)

    Column(
        Modifier.fillMaxWidth().widthIn(max = 640.dp).clip(sheetShape).background(c.surfaceElevated)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding().padding(horizontal = IrisDimens.space20, vertical = IrisDimens.space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = IrisDimens.space4, height = 18.dp).clip(IrisRadius.smallShape).background(tab))
            Spacer(Modifier.width(IrisDimens.space8))
            Text(ArchiveMath.serial(index), style = IrisType.mono, color = c.accent)
            Spacer(Modifier.width(IrisDimens.space8))
            Text(ArchiveMath.typeTag(e), style = IrisType.monoSmall, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            PressableIcon(IrisIcons.Close, "关闭", size = 40.dp, iconSize = IrisDimens.space20, onClick = onClose)
        }
        Box(
            Modifier.fillMaxWidth().padding(vertical = IrisDimens.space8).clip(IrisRadius.mediumShape)
                .drawWithContent {
                    drawContent()
                    val tv = t.value
                    if (tv < 1f) {
                        val count = ArchiveMath.veilPolygon(tv, size.width, size.height, pts)
                        if (count >= 3) {
                            veilPath.reset()
                            veilPath.moveTo(pts[0], pts[1])
                            for (i in 1 until count) veilPath.lineTo(pts[i * 2], pts[i * 2 + 1])
                            veilPath.close()
                            drawPath(veilPath, veil)
                        }
                        val f = ArchiveMath.sweepFront(tv, size.width, size.height)
                        drawLine(edge, Offset(f, 0f), Offset(f - size.height, size.height), strokeWidth = IrisDimens.space2.toPx())
                    }
                },
        ) {
            Column(
                Modifier.fillMaxWidth().graphicsLayer { renderEffect = effects[ArchiveMath.blurStep(t.value, BLUR_STEPS)] }
                    .background(c.surfaceVariant.copy(alpha = 0.5f)).padding(14.dp),
            ) {
                Text(e.name, style = IrisType.title, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                DetailRow("类型", if (e.extension.isEmpty()) "文件" else e.extension.uppercase(java.util.Locale.ROOT))
                DetailRow("大小", FileSizes.format(e.size))
                DetailRow("修改时间", formatModified(e.modified))
                DetailRow("路径", ArchiveMath.pathTag(e.relPath), mono = true)
            }
        }
        val preview = FilePreview.canPreview(e)
        Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space4)) {
            if (preview) IrisButton("预览", onClick = { actions.onEntryClick(e) }, icon = IrisIcons.Eye, modifier = Modifier.weight(1f), compact = true)
            else IrisButton("打开", onClick = { actions.openExternal(e) }, icon = IrisIcons.OpenExternal, modifier = Modifier.weight(1f), compact = true)
            Spacer(Modifier.width(IrisDimens.space8))
            IrisButton("分享", onClick = { actions.share(e) }, icon = IrisIcons.Share, style = IrisButtonStyle.Tonal, modifier = Modifier.weight(1f), compact = true)
        }
        val canRename = actions.canRename(e)
        val canDelete = actions.canDelete(e)
        Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space8)) {
            if (preview) {
                IrisButton("其他应用打开", onClick = { actions.openExternal(e) }, icon = IrisIcons.OpenExternal, style = IrisButtonStyle.Tonal, modifier = Modifier.weight(1f), compact = true)
                Spacer(Modifier.width(IrisDimens.space8))
            }
            if (canRename) {
                IrisButton("重命名", onClick = { onClose(); actions.requestRename(e) }, icon = IrisIcons.Edit, style = IrisButtonStyle.Tonal, modifier = Modifier.weight(1f), compact = true)
                Spacer(Modifier.width(IrisDimens.space8))
            }
            if (canDelete) IrisButton("删除", onClick = { onClose(); actions.requestDelete(e) }, icon = IrisIcons.Trash, style = IrisButtonStyle.Tonal, destructive = true, modifier = Modifier.weight(1f), compact = true)
        }
        state.rootPath?.let { Text(it, style = IrisType.micro, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    val c = Iris.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.width(72.dp))
        Text(value, style = if (mono) IrisType.monoSmall else IrisType.secondary, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

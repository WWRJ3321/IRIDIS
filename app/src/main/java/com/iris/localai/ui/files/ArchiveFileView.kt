package com.iris.localai.ui.files

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.files.ArchiveMath
import com.iris.localai.files.FileEntry
import com.iris.localai.files.FilePreview
import com.iris.localai.files.FileSizes
import com.iris.localai.files.FileSort
import com.iris.localai.files.FilesPaths
import com.iris.localai.ui.glass.LocalGlassQuality
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.markdown.Markdown
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisColors
import com.iris.localai.ui.theme.IrisPalette
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** The white intro of 「档案」 plays once per app session. */
internal object ArchiveSession {
    @Volatile var introShown = false
}

/** Field palette from the theme: warm off-white (light) / graphite (dark); other palettes get a hint of their accent. */
internal fun fieldPalette(c: IrisColors): ArchiveMath.FieldColors {
    val base = if (c.isDark) ArchiveMath.FieldColors.DARK else ArchiveMath.FieldColors.LIGHT
    val isDefault = c.accent == IrisPalette.DEFAULT.light.accent || c.accent == IrisPalette.DEFAULT.dark.accent
    return if (isDefault) base else ArchiveMath.FieldColors.tinted(base, c.accent.toArgb(), if (c.isDark) 0.10f else 0.08f)
}

// ---- Swiss HUD type: system sans + monospace, thin rules, no boxes
private val hudMicro = TextStyle(fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, fontFamily = FontFamily.SansSerif)
private val hudLabel = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, fontFamily = FontFamily.SansSerif)
private val hudAction = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, fontFamily = FontFamily.SansSerif)
private val hudNumber = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, fontFamily = FontFamily.SansSerif)
private val hudName = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
private val hudCounter = TextStyle(fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif)
private val hudArrow = TextStyle(fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif)
private val hudMono = TextStyle(fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace)

/** Scroll distance of one slab (dp) for a vertical swipe. */
private val SWIPE_UNIT = 64.dp
private const val BLUR_STEPS = 5
private val BLUR_MAX = 12.dp

/** Running jobs (no state reads, so writes never recompose). */
private class FieldJobs { var settle: Job? = null; var transit: Job? = null }

/**
 * 2.1.9 「档案」 (档案模式 重做): a full-screen isometric field of archive slabs. Each entry of the
 * current column (文件夹 / 文档 / 媒体 / 模型 / 其他) is one slab of the active row through the
 * middle of the screen; the selected one stands upright, its neighbours lift in a decaying wave.
 * Swipe up / down or ↑ ↓ to browse (looping); tap the standing slab or ACCESS FILE to open it
 * (folders: the slabs lie down, the field slides one row, the new folder's slabs rise; files:
 * the decrypt detail); long press = the list's menu.
 *
 * The field is one [ArchiveMath.SlabField] drawn with a single `drawVertices` call; every
 * animated value is read in the draw phase only, so animation redraws without recomposing.
 * Reduced motion: no intro, rise, wave or decrypt. Same [FilesState] / [FilesActions] as the
 * list; dialogs come from [FilesDialogs]. Design inspiration: LBEILC/RhineLabUI (MIT) — no code,
 * fonts, names or logos copied.
 */
@Composable
fun ArchiveFileView(state: FilesState, actions: FilesActions, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val c = Iris.colors
    val palette = remember(c) { fieldPalette(c) }
    val bg = Color(palette.background)
    val ink = c.text
    val grey = c.textSecondary
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ---- columns (categories) and the entries of the shown one
    val columns = remember(state.entries) { ArchiveMath.columns(state.entries) }
    var chosenColumn by remember { mutableStateOf<ArchiveMath.Column?>(null) }
    val column = ArchiveMath.keepColumn(columns, chosenColumn)
    val columnIndex = columns.indexOf(column).coerceAtLeast(0)
    val entries = remember(state.entries, column) { ArchiveMath.inColumn(state.entries, column) }
    val n = entries.size

    // ---- animated field state (read in draw only)
    val pos = remember { mutableFloatStateOf(0f) }
    val rise = remember { Animatable(0f) }
    val shift = remember { Animatable(0f) }
    val wave = remember { Animatable(1f) }
    val drop = remember { Animatable(0f) }
    val fieldAlpha = remember { Animatable(1f) }
    var intro by remember { mutableStateOf(!reduce && !ArchiveSession.introShown) }
    val jobs = remember { FieldJobs() }
    val field = remember { ArchiveMath.SlabField() }
    var shownKey by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<FileEntry?>(null) }
    var lastDetail by remember { mutableStateOf<FileEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(IntOffset.Zero) }
    var sortMenu by remember { mutableStateOf(false) }

    val focusLogical by remember { derivedStateOf { pos.floatValue.roundToInt() } }
    val sel = if (n == 0) 0 else ArchiveMath.wrap(focusLogical, n)
    val current = entries.getOrNull(sel)
    val number = column?.let { ArchiveMath.fileNumber(it, sel) } ?: "—"

    // First open in this session: white intro, then the field fades in and settles.
    LaunchedEffect(Unit) {
        if (!intro) return@LaunchedEffect
        ArchiveSession.introShown = true
        fieldAlpha.snapTo(0f); drop.snapTo(1f); rise.snapTo(0f)
        delay(IrisMotion.ARCHIVE_INTRO_MS.toLong())
        intro = false
        launch { fieldAlpha.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_SETTLE_MS)) }
        drop.animateTo(0f, IrisMotion.progress(IrisMotion.ARCHIVE_SETTLE_MS))
        rise.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_RISE_MS))
    }

    // A folder (or column) finished loading: the old slabs lie down, the field slides one row,
    // the new entries rise from the selection outwards. Slabs carry no text, so the swap is invisible.
    val key = state.rel + "\u0000" + column?.name
    LaunchedEffect(key, state.loading) {
        if (state.loading || shownKey == key) return@LaunchedEffect
        val first = shownKey == null
        shownKey = key
        jobs.settle?.cancel()
        jobs.transit?.cancel()
        jobs.transit = launch {
            if (reduce) {
                pos.floatValue = 0f; shift.snapTo(0f); rise.snapTo(1f); return@launch
            }
            if (!first && rise.value > 0.01f) coroutineScope {
                launch { rise.animateTo(0f, IrisMotion.progressOut(IrisMotion.ARCHIVE_FALL_MS)) }
                shift.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_RISE_MS))
            }
            shift.snapTo(0f)
            pos.floatValue = 0f
            if (intro || drop.value > 0f) return@launch // the intro raises the slabs itself
            rise.snapTo(0f)
            rise.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_RISE_MS))
        }
    }

    fun settleTo(target: Float, velocity: Float = 0f) {
        jobs.settle?.cancel()
        val from = pos.floatValue.roundToInt()
        jobs.settle = scope.launch {
            if (reduce) pos.floatValue = target
            else animate(pos.floatValue, target, velocity, IrisMotion.ArchiveSnap.float()) { v, _ -> pos.floatValue = v }
            if (!reduce && target.roundToInt() != from) {
                wave.snapTo(0f)
                wave.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_WAVE_MS))
            }
        }
    }

    fun step(delta: Int) { if (n > 0) settleTo((pos.floatValue.roundToInt() + delta).toFloat()) }

    fun open(e: FileEntry) {
        if (e.isDir) actions.onEntryClick(e) // the transition runs when the folder has loaded
        else { detail = e; lastDetail = e }
    }

    fun closeDetail() {
        detail = null
        if (reduce) return
        // back from a file: the slab lies down and stands up again
        scope.launch {
            rise.animateTo(0f, IrisMotion.progressOut(IrisMotion.ARCHIVE_FALL_MS))
            rise.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_RISE_MS))
        }
    }

    fun switchColumn(delta: Int) {
        if (columns.size <= 1) return
        chosenColumn = columns[ArchiveMath.wrap(columnIndex + delta, columns.size)]
    }

    BackHandler(enabled = detail != null) { closeDetail() }

    val currentEntry by rememberUpdatedState(current)
    val onTap by rememberUpdatedState { o: Offset ->
        val slop = with(density) { 6.dp.toPx() }
        val hit = field.hit(o.x, o.y, slop)
        if (hit != null && n > 0) {
            if (hit == field.selectedColumn) currentEntry?.let { open(it) } else settleTo(hit.toFloat())
        }
    }
    val onLongPress by rememberUpdatedState { o: Offset ->
        val slop = with(density) { 6.dp.toPx() }
        if (n > 0 && field.hit(o.x, o.y, slop) == field.selectedColumn) {
            menuAnchor = IntOffset(o.x.roundToInt(), o.y.roundToInt())
            menuOpen = true
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(bg)) {
        val w = maxWidth
        val h = maxHeight
        val unitPx = with(density) { SWIPE_UNIT.toPx() }
        val drag = rememberDraggableState { dy -> if (n > 0) { pos.floatValue -= dy / unitPx } }

        // ---- the slab field: one canvas, one drawVertices call
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = fieldAlpha.value }
                .draggable(
                    drag, Orientation.Vertical, enabled = n > 0 && detail == null,
                    onDragStarted = { jobs.settle?.cancel() },
                    onDragStopped = { v -> settleTo(ArchiveMath.fieldSnap(pos.floatValue, -v / unitPx), -v / unitPx) },
                )
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap(it) }, onLongPress = { onLongPress(it) }) }
                .drawWithCache {
                    field.layout(size.width, size.height, this.density)
                    val paint = android.graphics.Paint()
                    onDrawBehind {
                        val count = field.build(pos.floatValue, n > 0, rise.value, shift.value, wave.value, drop.value, palette)
                        if (count > 0) drawIntoCanvas {
                            it.nativeCanvas.drawVertices(
                                android.graphics.Canvas.VertexMode.TRIANGLES, count * 2, field.verts, 0,
                                null, 0, field.colors, 0, null, 0, 0, paint,
                            )
                        }
                    }
                },
        )

        // ---- legibility scrims behind the HUD
        Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.88f), bg.copy(alpha = 0f)))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(250.dp)
                .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg.copy(alpha = 0.82f), bg.copy(alpha = 0.95f)))),
        )
        Box(
            Modifier.fillMaxSize().drawBehind {
                val center = Offset(size.width * 0.74f, size.height * 0.37f)
                drawRect(Brush.radialGradient(listOf(bg.copy(alpha = 0.86f), bg.copy(alpha = 0.55f), bg.copy(alpha = 0f)), center, size.width * 0.62f))
            },
        )

        // ---- top row: label, sort, new folder; access notice / message below
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("IRIDIS / ARCHIVE", style = hudLabel, color = ink)
                Spacer(Modifier.weight(1f))
                Box {
                    Text(
                        "SORT  " + state.sort.label + if (state.ascending) " ↑" else " ↓", style = hudAction, color = ink,
                        modifier = Modifier.clickable { sortMenu = true }.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    IrisMenu(expanded = sortMenu, onDismiss = { sortMenu = false }) {
                        FileSort.entries.forEach { s ->
                            val on = s == state.sort
                            IrisMenuItem(s.label + if (on) (if (state.ascending) "  ↑" else "  ↓") else "", if (on) IrisIcons.Check else IrisIcons.Sort) {
                                sortMenu = false
                                actions.setSort(s)
                            }
                        }
                    }
                }
                Text(
                    "+ " + FilesText.NEW_FOLDER, style = hudAction, color = ink,
                    modifier = Modifier.clickable { actions.requestNewFolder() }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            FilesAccessNotice(state)
            state.message?.let { Text(it, style = hudAction, color = grey, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }

        // ---- center-right block: path, file number, rule, name, access
        Column(Modifier.offset(x = w * 0.45f, y = h * 0.27f).width(w * 0.55f)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("DOWNLOAD", style = hudMicro, color = grey)
                state.crumbs.forEachIndexed { i, crumb ->
                    val last = i == state.crumbs.lastIndex
                    Text("  /  ", style = hudMicro, color = grey)
                    Text(
                        crumb.label, style = hudMicro, color = if (last) ink else grey, maxLines = 1,
                        modifier = Modifier.clickable(enabled = !last) { actions.navigate(crumb.relPath) }.padding(vertical = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FILE NUMBER: ", style = hudNumber, color = ink, maxLines = 1)
                if (column != null && n > 0) {
                    Text(column.code + "-", style = hudNumber, color = ink)
                    RollingNumber(sel + 1, minWidth = 3, style = hudNumber, color = ink)
                } else Text("—", style = hudNumber, color = ink)
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(6.dp).drawBehind {
                    val y = size.height / 2f
                    val dot = 4.dp.toPx()
                    drawRect(ink, Offset(0f, y - dot / 2f), Size(dot, dot))
                    drawLine(ink.copy(alpha = 0.7f), Offset(dot + 8.dp.toPx(), y), Offset(size.width, y), strokeWidth = 0.7.dp.toPx())
                },
            )
            Column(Modifier.padding(start = 16.dp, end = 20.dp, top = 12.dp)) {
                when {
                    current != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(current.displayName, style = hudName, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (current.system != null) Text(" 「${FilesText.SYSTEM_BADGE}」", style = hudAction, color = grey)
                        }
                        if (current.system != null) Text(current.name, style = hudMono, color = grey)
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.clickable { open(current) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (current.isDir) "进入  ACCESS FOLDER" else "打开  ACCESS FILE", style = hudAction, color = ink)
                            Spacer(Modifier.width(18.dp))
                            Text("→", style = hudArrow, color = ink)
                        }
                    }
                    state.loading -> Text("READING …", style = hudAction, color = grey)
                    else -> {
                        Text(
                            when {
                                state.unreadable && state.atRoot -> FilesText.UNAVAILABLE
                                state.unreadable -> "无法读取这个文件夹"
                                else -> "空文件夹"
                            },
                            style = hudName, color = ink,
                        )
                        if (state.unreadable) Text(FilesText.UNAVAILABLE_HINT, style = hudAction, color = grey, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ---- bottom: counter + ruler, column switch, footer
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            Text("ARCHIVE / SELECT", style = hudMicro, color = ink)
            Row(verticalAlignment = Alignment.Bottom) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (n > 0) RollingNumber(sel + 1, minWidth = 2, style = hudCounter, color = ink)
                    else Text("00", style = hudCounter, color = ink)
                    Text("  /  " + ArchiveMath.counter(n), style = hudAction, color = grey, modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("↑", style = hudArrow, color = ink, modifier = Modifier.clickable(enabled = n > 1) { step(-1) }.padding(10.dp))
                    val ticks = ArchiveMath.rulerTicks(n)
                    val on = ArchiveMath.rulerSelected(sel, n)
                    Box(
                        Modifier.width(112.dp).height(16.dp).drawBehind {
                            if (ticks <= 0) return@drawBehind
                            val gap = size.width / maxOf(ticks, 1)
                            for (i in 0 until ticks) {
                                val x = gap * (i + 0.5f)
                                val tall = i == on
                                drawLine(
                                    if (tall) ink else ink.copy(alpha = 0.35f),
                                    Offset(x, if (tall) 0f else size.height * 0.4f), Offset(x, size.height),
                                    strokeWidth = (if (tall) 1.4.dp else 0.8.dp).toPx(),
                                )
                            }
                        },
                    )
                    Text("↓", style = hudArrow, color = ink, modifier = Modifier.clickable(enabled = n > 1) { step(1) }.padding(10.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val many = columns.size > 1
                Text("←", style = hudArrow, color = if (many) ink else grey, modifier = Modifier.clickable(enabled = many) { switchColumn(-1) }.padding(12.dp))
                Column(Modifier.widthIn(min = 120.dp).padding(horizontal = 8.dp)) {
                    Text(ArchiveMath.columnLabel(columnIndex, columns.size.coerceAtLeast(1)), style = hudMicro, color = grey)
                    Text(column?.label ?: "—", style = hudAction, color = ink)
                }
                Text("→", style = hudArrow, color = if (many) ink else grey, modifier = Modifier.clickable(enabled = many) { switchColumn(1) }.padding(12.dp))
            }
            Spacer(Modifier.height(6.dp))
            current?.let { e ->
                Text(
                    (if (e.isDir) "${e.childCount} 项" else FileSizes.format(e.size)) + "  ·  " + formatModified(e.modified),
                    style = hudMono, color = grey, maxLines = 1,
                )
            }
            state.rootPath?.let { Text(it, style = hudMono, color = grey, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }

        // ---- long press = the list's menu, anchored where the finger is
        current?.let { e ->
            Box(Modifier.offset { menuAnchor }.size(1.dp)) {
                EntryMenu(e, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }

        // ---- file detail (decrypt)
        AnimatedVisibility(detail != null, enter = IrisMotion.fadeIn(), exit = IrisMotion.fadeOut()) {
            lastDetail?.let { e ->
                val idx = entries.indexOfFirst { it.relPath == e.relPath }.coerceAtLeast(0)
                val col = ArchiveMath.columnOf(e)
                ArchiveDetail(e, ArchiveMath.fileNumber(col, idx), col, state, actions, palette, onClose = { closeDetail() })
            }
        }

        // ---- intro: blank white, small bold title
        AnimatedVisibility(intro, enter = IrisMotion.fadeIn(), exit = IrisMotion.fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(if (c.isDark) Color(0xFF0E0E10) else Color.White)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(start = 20.dp, top = 12.dp)) {
                    Text("IRIDIS", style = hudLabel.copy(fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 1.sp), color = ink)
                    Text("ARCHIVE DATABASE", style = hudMicro.copy(fontWeight = FontWeight.Bold), color = ink)
                }
            }
        }
    }
}

// ================================================================ file detail

/** Redaction bar over a value that retracts left → right, revealing the text under it. */
private fun Modifier.redacted(bar: Color, progress: () -> Float): Modifier = drawWithContent {
    val p = progress()
    if (p >= 1f) { drawContent(); return@drawWithContent }
    if (p > 0f) clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
    drawRect(bar, Offset(size.width * p, 0f), Size(size.width * (1f - p), size.height))
}

/**
 * The decrypt detail of a file: a face-on frosted glass slab (screws, label, faint rings — or the
 * image's thumbnail) that a diagonal line sweeps and collapses across while it clears top-down;
 * fields first shown as black bars that retract to reveal the text; 01 概述 / 02 预览 tabs; the
 * black 打开 / 分享 / 重命名 / 删除 bar. Portrait stacks the slab (~40 %) over the fields.
 */
@Composable
private fun ArchiveDetail(
    e: FileEntry,
    number: String,
    column: ArchiveMath.Column,
    state: FilesState,
    actions: FilesActions,
    palette: ArchiveMath.FieldColors,
    onClose: () -> Unit,
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val bg = Color(palette.background)
    val t = remember(e.relPath) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(e.relPath) { if (!reduce) t.animateTo(1f, IrisMotion.progress(IrisMotion.ARCHIVE_REVEAL_MS)) }
    val maxSide = with(LocalDensity.current) { 360.dp.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, e.relPath, state.rootPath) { value = loadThumb(state.rootPath, e, maxSide) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(bg.copy(alpha = 0.95f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val wide = maxWidth > maxHeight
        val glassHeight = maxHeight * 0.4f
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DetailBack(onClose)
                    GlassSlab(number, thumb, { t.value }, palette, Modifier.weight(1f).fillMaxWidth())
                }
                DetailFields(e, number, column, state, actions, { t.value }, onClose, Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                DetailBack(onClose)
                GlassSlab(number, thumb, { t.value }, palette, Modifier.fillMaxWidth().height(glassHeight))
                DetailFields(e, number, column, state, actions, { t.value }, onClose, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DetailBack(onClose: () -> Unit) {
    Row(
        Modifier.padding(start = 12.dp, top = 6.dp).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("←", style = hudArrow, color = Iris.colors.text)
        Spacer(Modifier.width(12.dp))
        Text("ARCHIVE OVERVIEW", style = hudMicro.copy(fontWeight = FontWeight.Bold), color = Iris.colors.text)
    }
}

/** The face-on frosted glass slab of the detail. */
@Composable
private fun GlassSlab(number: String, thumb: ImageBitmap?, t: () -> Float, palette: ArchiveMath.FieldColors, modifier: Modifier) {
    val c = Iris.colors
    val ink = c.text
    val density = LocalDensity.current
    val blurs = LocalGlassQuality.current.blurs
    val effects: Array<RenderEffect?> = remember(density, blurs) {
        val max = with(density) { BLUR_MAX.toPx() }
        Array(BLUR_STEPS) { i -> if (i == 0 || !blurs) null else BlurEffect(max * i / (BLUR_STEPS - 1), max * i / (BLUR_STEPS - 1), TileMode.Decal) }
    }
    val dark = c.isDark
    val glassFill = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.42f)
    val rimLight = if (dark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.95f)
    val rimDark = Color(ArchiveMath.lerpColor(palette.shade, palette.background, 0.35f))
    val frost = Color(palette.haze).copy(alpha = if (dark) 0.75f else 0.72f)
    val ring = Color(ArchiveMath.lerpColor(palette.lit, palette.shade, 0.45f))
    val isDefault = c.accent == IrisPalette.DEFAULT.light.accent || c.accent == IrisPalette.DEFAULT.dark.accent
    val copper = if (isDefault) Color(0xFFC4875A) else c.accent
    val line = remember { FloatArray(2) }
    val digits = number.substringAfter('-')

    Box(modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        // base: thickness edge, glass face, inner frame
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val r = CornerRadius(14.dp.toPx())
                val edge = 6.dp.toPx()
                drawRoundRect(rimDark.copy(alpha = 0.35f), topLeft = Offset(0f, edge), size = Size(size.width, size.height - edge), cornerRadius = r)
                drawRoundRect(glassFill, size = Size(size.width, size.height - edge), cornerRadius = r)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.04f else 0.30f), Color.Transparent)),
                    size = Size(size.width, size.height - edge), cornerRadius = r,
                )
            },
        )
        // content: the image thumbnail, or faint rings — blurred while frosted (API 31+, when blur is on)
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer { renderEffect = effects[ArchiveMath.blurStep(ArchiveMath.frostFront(t()), BLUR_STEPS)] }
                .drawBehind {
                    val w = size.width; val h = size.height - 6.dp.toPx()
                    if (thumb != null) {
                        val box = min(w, h) * 0.72f
                        val scale = min(box / thumb.width, box / thumb.height)
                        val dw = (thumb.width * scale).roundToInt(); val dh = (thumb.height * scale).roundToInt()
                        val left = (w * 0.54f - dw / 2f).roundToInt(); val top = (h * 0.5f - dh / 2f).roundToInt()
                        drawImage(thumb, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh), alpha = 0.92f)
                        drawRect(rimLight, Offset(left.toFloat(), top.toFloat()), Size(dw.toFloat(), dh.toFloat()), style = Stroke(1.dp.toPx()))
                    } else {
                        val big = min(w, h) * 0.30f
                        val cBig = Offset(w * 0.44f, h * 0.56f)
                        drawCircle(ring.copy(alpha = 0.55f), big, cBig, style = Stroke(big * 0.22f))
                        drawCircle(rimLight, big * 1.11f, cBig, style = Stroke(1.dp.toPx()))
                        drawArc(copper.copy(alpha = 0.6f), 115f, 70f, false, Offset(cBig.x - big * 0.89f, cBig.y - big * 0.89f), Size(big * 1.78f, big * 1.78f), style = Stroke(2.dp.toPx()))
                        val small = big * 0.42f
                        val cSmall = Offset(w * 0.75f, h * 0.33f)
                        drawCircle(ring.copy(alpha = 0.5f), small, cSmall, style = Stroke(small * 0.34f))
                        drawCircle(copper.copy(alpha = 0.55f), small * 0.78f, cSmall, style = Stroke(small * 0.22f))
                    }
                },
        )
        // frost veil (clears top-down), rims, screws, the decrypt line and markers
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val edge = 6.dp.toPx()
                val face = Size(size.width, size.height - edge)
                val r = CornerRadius(14.dp.toPx())
                val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, face.width, face.height, r)) }
                val inset = 12.dp.toPx()
                val screw = 4.5.dp.toPx()
                val sq = 5.dp.toPx()
                onDrawBehind {
                    val tv = t()
                    val front = ArchiveMath.frostFront(tv) * face.height
                    if (front < face.height) clipPath(clip) {
                        drawRect(frost, Offset(0f, front), Size(face.width, face.height - front))
                        drawLine(ink.copy(alpha = 0.18f), Offset(0f, front), Offset(face.width, front), strokeWidth = 1.dp.toPx())
                    }
                    drawRoundRect(rimLight, size = face, cornerRadius = r, style = Stroke(1.4.dp.toPx()))
                    drawRoundRect(rimDark, size = face, cornerRadius = r, style = Stroke(0.6.dp.toPx()))
                    drawRoundRect(
                        rimLight.copy(alpha = rimLight.alpha * 0.7f), topLeft = Offset(inset * 1.6f, inset * 1.6f),
                        size = Size(face.width - inset * 3.2f, face.height - inset * 3.2f), cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(0.7.dp.toPx()),
                    )
                    for (k in 0 until 4) {
                        val p = Offset(if (k and 1 == 0) inset else face.width - inset, if (k and 2 == 0) inset else face.height - inset)
                        drawCircle(rimDark.copy(alpha = 0.55f), screw, p)
                        drawCircle(rimLight, screw * 0.45f, p - Offset(screw * 0.2f, screw * 0.2f))
                    }
                    val bl = Offset(face.width * 0.12f, face.height * 0.86f)
                    val tr = Offset(face.width * 0.86f, face.height * 0.14f)
                    if (ArchiveMath.decryptLine(tv, line)) {
                        val a = bl + (tr - bl) * line[0]
                        val b = bl + (tr - bl) * line[1]
                        drawLine(ink, a, b, strokeWidth = 1.2.dp.toPx())
                        drawRect(ink, a - Offset(sq / 2, sq / 2), Size(sq, sq))
                        drawRect(ink, b - Offset(sq / 2, sq / 2), Size(sq, sq))
                    }
                    drawRect(ink, tr - Offset(sq / 2, sq / 2), Size(sq * 0.8f, sq * 0.8f))
                    drawRect(ink, Offset(face.width * 0.87f, face.height * 0.64f), Size(sq * 0.8f, sq * 0.8f))
                }
            },
        )
        // label tag and the big number
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 30.dp, top = 30.dp)
                .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text("IRIDIS · NO.$digits", style = hudMicro.copy(fontWeight = FontWeight.Bold), color = ink)
            Text(number, style = hudMono, color = c.textSecondary)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 28.dp)) {
            Text("NO.$digits", style = hudCounter.copy(fontSize = 30.sp, lineHeight = 34.sp), color = ink)
            Text("INTERNAL DATABASE", style = hudMicro, color = c.textSecondary)
        }
    }
}

@Composable
private fun DetailFields(
    e: FileEntry,
    number: String,
    column: ArchiveMath.Column,
    state: FilesState,
    actions: FilesActions,
    t: () -> Float,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val c = Iris.colors
    val ink = c.text
    val grey = c.textSecondary
    val bar = ink
    var tab by remember(e.relPath) { mutableIntStateOf(0) }
    val type = if (e.extension.isEmpty()) "文件" else e.extension.uppercase(java.util.Locale.ROOT)
    val canRename = actions.canRename(e)
    val canDelete = actions.canDelete(e)
    val preview = FilePreview.canPreview(e)

    Column(modifier.padding(horizontal = 20.dp).padding(top = 14.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FILE $number", style = hudMicro.copy(fontWeight = FontWeight.Bold), color = ink)
            Spacer(Modifier.weight(1f))
            Text("REFERENCE AREA", style = hudMicro, color = grey)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            e.name, style = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif),
            color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.redacted(bar) { ArchiveMath.redactProgress(t(), 0) },
        )
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(type, style = hudName, color = ink, modifier = Modifier.redacted(bar) { ArchiveMath.redactProgress(t(), 1) })
            Spacer(Modifier.width(10.dp))
            Text(column.label, style = hudMicro, color = grey)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(ink))
        Spacer(Modifier.height(12.dp))
        Row {
            DetailField("类型 / TYPE", type, 2, t, Modifier.weight(1f))
            DetailField("大小 / SIZE", FileSizes.format(e.size), 3, t, Modifier.weight(1f))
        }
        Row {
            DetailField("修改 / MODIFIED", formatModified(e.modified), 4, t, Modifier.weight(1f))
            DetailField("状态 / STATUS", if (canRename && canDelete) "▪ 已归档 · 可编辑" else "▪ 受保护", 5, t, Modifier.weight(1f))
        }
        DetailField("路径 / PATH", ArchiveMath.pathTag(e.relPath), 6, t, Modifier.fillMaxWidth(), mono = true)

        // tabs
        Row(Modifier.padding(top = 8.dp)) {
            listOf("概述", "预览").forEachIndexed { i, label ->
                val on = tab == i
                Row(
                    Modifier.clickable { tab = i }.drawBehind {
                        if (on) drawLine(ink, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5.dp.toPx())
                    }.padding(end = 18.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("0${i + 1}  ", style = hudMono, color = if (on) ink else grey)
                    Text(label, style = hudAction, color = if (on) ink else grey)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.7.dp).background(grey.copy(alpha = 0.4f)))
        Spacer(Modifier.height(10.dp))
        if (tab == 0) {
            Text("ABSTRACT / 摘要", style = hudMicro, color = grey)
            Spacer(Modifier.height(6.dp))
            Text(
                abstractOf(e, type, preview), style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.SansSerif), color = ink,
                modifier = Modifier.redacted(bar) { ArchiveMath.redactProgress(t(), 7) },
            )
        } else {
            DetailPreview(e, state, preview)
        }
        Spacer(Modifier.height(16.dp))
        // black action bar
        Row(Modifier.fillMaxWidth().height(48.dp).background(ink), verticalAlignment = Alignment.CenterVertically) {
            val onInk = if (c.isDark) Color.Black else Color.White
            ActionCell("打开", onInk, Modifier.weight(1f)) { actions.openExternal(e) }
            BarDivider(onInk)
            ActionCell("分享", onInk, Modifier.weight(1f)) { actions.share(e) }
            if (canRename) { BarDivider(onInk); ActionCell("重命名", onInk, Modifier.weight(1f)) { onClose(); actions.requestRename(e) } }
            if (canDelete) { BarDivider(onInk); ActionCell("删除", onInk, Modifier.weight(1f)) { onClose(); actions.requestDelete(e) } }
        }
        Spacer(Modifier.height(8.dp))
        state.rootPath?.let { Text(it, style = hudMono, color = grey, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BarDivider(color: Color) {
    Box(Modifier.width(0.5.dp).height(22.dp).background(color.copy(alpha = 0.35f)))
}

@Composable
private fun ActionCell(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = hudAction, color = color)
    }
}

@Composable
private fun DetailField(label: String, value: String, index: Int, t: () -> Float, modifier: Modifier, mono: Boolean = false) {
    val c = Iris.colors
    Column(modifier.padding(bottom = 12.dp, end = 8.dp)) {
        Text(label, style = hudMicro, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            value, style = if (mono) hudMono.copy(fontSize = 11.sp, lineHeight = 15.sp) else hudName.copy(fontSize = 14.sp),
            color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.redacted(c.text) { ArchiveMath.redactProgress(t(), index) },
        )
    }
}

private fun abstractOf(e: FileEntry, type: String, preview: Boolean): String = buildString {
    append(type).append(" 文件，").append(FileSizes.format(e.size)).append("，最后修改于 ").append(formatModified(e.modified)).append("。")
    append("归档于 ").append(ArchiveMath.pathTag(FilesPaths.parent(e.relPath))).append("。")
    append(if (preview) "可在「预览」中直接阅读。" else "这一类型不能在应用内预览，可用「打开」交给其他应用。")
}

/** 02 预览: in-app text / Markdown (same limits as the list's preview), the image, or a note. */
@Composable
private fun DetailPreview(e: FileEntry, state: FilesState, preview: Boolean) {
    val c = Iris.colors
    if (!preview) {
        Text("无法在应用内预览这一类型 · 可用「打开」交给其他应用", style = hudAction, color = c.textSecondary)
        return
    }
    val text by produceState<String?>(null, e.relPath, state.rootPath) { value = readPreview(state.rootPath, e) }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 420.dp).background(c.codeBackground.copy(alpha = 0.5f))
            .verticalScroll(rememberScrollState()).padding(10.dp),
    ) {
        val s = text
        when {
            s == null -> Text("READING …", style = hudAction, color = c.textTertiary)
            FilePreview.isMarkdown(e.extension) -> SelectionContainer { Markdown(s) }
            else -> SelectionContainer { Text(s, style = hudMono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.text) }
        }
    }
}

/** The file under Download/IRIDIS (null when it would leave the root). */
private fun resolve(rootPath: String?, e: FileEntry): File? = rootPath?.let { FilesPaths.resolve(File(it), e.relPath) }

/** Downscaled thumbnail for png / jpg / webp (≤ [maxSide] px on the larger side), off the main thread. */
private suspend fun loadThumb(rootPath: String?, e: FileEntry, maxSide: Int): ImageBitmap? {
    if (!ArchiveMath.isImage(e.extension)) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val f = resolve(rootPath, e) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply { inSampleSize = ArchiveMath.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
        }.getOrNull()
    }
}

private suspend fun readPreview(rootPath: String?, e: FileEntry): String = withContext(Dispatchers.IO) {
    runCatching {
        val f = resolve(rootPath, e) ?: error("路径不在 IRIDIS 内")
        val bytes = FilePreview.readHead(f)
        if (!FilePreview.looksText(bytes)) "（看起来不是文本文件，可用其他应用打开）" else String(bytes, Charsets.UTF_8)
    }.getOrElse { "读取失败：${it.message}" }
}

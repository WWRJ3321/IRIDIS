package com.iris.localai.ui.files

import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.iris.localai.files.FileProtection
import com.iris.localai.files.FilesIo
import com.iris.localai.files.SystemFolder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
    // 打开转场: the selected slab is carried to the detail's glass panel (hidden in the field meanwhile)
    val anim = remember { DetailAnim() }
    val shared = remember { SharedSlab() }
    var hideSelected by remember { mutableStateOf(false) }
    val blurs = LocalGlassQuality.current.blurs
    val backdropBlur: Array<RenderEffect?> = remember(density, blurs) {
        val max = with(density) { BLUR_MAX.toPx() }
        Array(BLUR_STEPS) { i -> if (i == 0 || !blurs) null else BlurEffect(max * i / (BLUR_STEPS - 1), max * i / (BLUR_STEPS - 1), TileMode.Decal) }
    }
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

    /** Opens the detail of [e] (file or folder): the standing slab's face becomes the glass panel. */
    fun open(e: FileEntry) {
        if (detail != null) return
        jobs.settle?.cancel()
        shared.hasFrom = field.selectedFace(shared.from)
        val center = ArchiveMath.quadCenter(shared.from, FloatArray(2))
        shared.originX = if (shared.hasFrom && field.width > 0f) (center[0] / field.width).coerceIn(0f, 1f) else 0.5f
        shared.originY = if (shared.hasFrom && field.height > 0f) (center[1] / field.height).coerceIn(0f, 1f) else 0.5f
        anim.closing = false
        detail = e
        hideSelected = shared.hasFrom
        scope.launch {
            anim.close.snapTo(0f)
            if (reduce) anim.open.snapTo(1f)
            else {
                anim.open.snapTo(0f)
                anim.open.animateTo(1f, IrisMotion.timeline(IrisMotion.ARCHIVE_OPEN_MS))
            }
        }
    }

    /** Reverse: info fades, the panel shrinks back into its slot, the field clears. */
    fun closeDetail() {
        if (detail == null || anim.closing) return
        if (reduce) { detail = null; hideSelected = false; return }
        anim.closeFromPush = anim.push()
        anim.closeFromInfo = anim.info()
        anim.closing = true
        scope.launch {
            anim.open.stop()
            anim.close.snapTo(0f)
            anim.close.animateTo(1f, IrisMotion.timeline(IrisMotion.ARCHIVE_CLOSE_MS))
            detail = null
            hideSelected = false
            anim.closing = false
            anim.open.snapTo(0f)
        }
    }

    /** 进入 from the detail: straight into the folder (its own lie-down / rise transition plays). */
    fun enterFromDetail(e: FileEntry) {
        detail = null
        hideSelected = false
        anim.closing = false
        scope.launch { anim.open.snapTo(0f); anim.close.snapTo(0f) }
        actions.onEntryClick(e)
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
                .graphicsLayer {
                    // depth of field while the detail is open: zoom toward the slab, fade toward the background, blur
                    val p = if (detail != null) anim.push() else 0f
                    alpha = fieldAlpha.value * ArchiveMath.backdropAlpha(p)
                    val z = ArchiveMath.backdropScale(p)
                    scaleX = z; scaleY = z
                    transformOrigin = TransformOrigin(shared.originX, shared.originY)
                    renderEffect = backdropBlur[ArchiveMath.blurStep(1f - p, BLUR_STEPS)]
                }
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
                        val count = field.build(pos.floatValue, n > 0, rise.value, shift.value, wave.value, drop.value, palette, hideSelected)
                        if (count > 0) drawIntoCanvas {
                            it.nativeCanvas.drawVertices(
                                android.graphics.Canvas.VertexMode.TRIANGLES, count * 2, field.verts, 0,
                                null, 0, field.colors, 0, null, 0, 0, paint,
                            )
                        }
                    }
                },
        )

        // ---- HUD (fades out while the detail's panel travels)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (detail != null) 1f - anim.push() else 1f }) {
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
                            Modifier.clickable { if (current.isDir) actions.onEntryClick(current) else open(current) }.padding(vertical = 6.dp),
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

        }

        // ---- long press = the list's menu, anchored where the finger is
        current?.let { e ->
            Box(Modifier.offset { menuAnchor }.size(1.dp)) {
                EntryMenu(e, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }

        // ---- detail: the slab carried forward into a glass panel, scan, decrypt (no sheet, no dialog fade)
        detail?.let { e ->
            val idx = entries.indexOfFirst { it.relPath == e.relPath }.let { if (it < 0) sel else it }
            val col = ArchiveMath.columnOf(e)
            ArchiveDetail(
                e, ArchiveMath.fileNumber(col, idx), ArchiveMath.serialLabel(idx, n), state, actions, palette, anim, shared,
                onClose = { closeDetail() }, onEnter = { enterFromDetail(e) },
            )
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

// ================================================================ file detail (2.1.9 打开转场与详情页重做)

/**
 * The open / close timelines of the detail. Every value is read in layout / draw only, so the
 * transition redraws without recomposing. Phases come from [ArchiveMath] (push, scan, rows),
 * eased with [IrisMotion.ProgressEasing].
 */
private class DetailAnim {
    val open = Animatable(0f)
    val close = Animatable(0f)
    var closing by mutableStateOf(false)
    var closeFromPush = 1f
    var closeFromInfo = 1f
    private val ease: (Float) -> Float = { IrisMotion.ProgressEasing.transform(it) }
    fun push(): Float = if (closing) ArchiveMath.closePush(close.value, closeFromPush, ease) else ArchiveMath.pushOf(open.value, ease)
    fun info(): Float = if (closing) ArchiveMath.closeInfo(close.value) * closeFromInfo else ArchiveMath.infoOf(open.value)
    fun scan(): Float = ArchiveMath.scanOf(open.value, ease)
    fun content(): Float = if (closing) ArchiveMath.closeContent(close.value) else 1f
    fun row(i: Int): Float = ArchiveMath.rowOf(open.value, i, ease)
}

/** Where the selected slab's face was on screen when the detail opened (field coordinates, px). */
private class SharedSlab {
    val from = FloatArray(8)
    var hasFrom = false
    /** Transform origin of the field's zoom, as fractions of the canvas. */
    var originX = 0.5f
    var originY = 0.5f
}

/** Maps the glass panel's own rectangle onto the interpolated quad between the slab face and the panel. */
private class GlassTransform {
    val matrix = android.graphics.Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val from = FloatArray(8)
    var x = 0f
    var y = 0f

    fun update(w: Float, h: Float, p: Float, shared: SharedSlab) {
        ArchiveMath.rectQuad(0f, 0f, w, h, src)
        if (shared.hasFrom) for (i in 0 until 4) {
            from[i * 2] = shared.from[i * 2] - x
            from[i * 2 + 1] = shared.from[i * 2 + 1] - y
        } else ArchiveMath.scaleQuad(src, 0.82f, from)
        ArchiveMath.lerpQuad(from, src, p, dst)
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) matrix.reset()
    }
}

/** What the detail read from disk (off the main thread). */
private class Loaded(
    val thumb: ImageBitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val head: List<String> = emptyList(),
    val lines: Int? = null,
    val children: Int? = null,
    val childNames: List<String> = emptyList(),
)

private val hudTitle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
private val hudSub = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
private val hudValue = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
private val hudBody = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.SansSerif)
private val clockFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

/** Folder shown on the glass's tag: the parent folder (系统目录 by its label), IRIDIS at the root. */
private fun folderLabel(rel: String): String {
    val parent = FilesPaths.parent(rel)
    return when {
        parent.isEmpty() -> FilesPaths.ROOT_LABEL
        !parent.contains('/') -> SystemFolder.of(parent)?.label ?: parent
        else -> parent.substringAfterLast('/')
    }
}

/**
 * The file / folder detail. The glass panel is laid out at its final place and drawn through a
 * perspective matrix that carries it from the selected slab's face ([SharedSlab]) to that place
 * (no sheet, no fade-in dialog); a diagonal scan then reveals its contents; the info area starts as
 * redaction bars that retract row by row. Portrait: the panel above the info; landscape: side by side.
 */
@Composable
private fun ArchiveDetail(
    e: FileEntry,
    number: String,
    serial: String,
    state: FilesState,
    actions: FilesActions,
    palette: ArchiveMath.FieldColors,
    anim: DetailAnim,
    shared: SharedSlab,
    onClose: () -> Unit,
    onEnter: () -> Unit,
) {
    val bg = Color(palette.background)
    val maxSide = with(LocalDensity.current) { 420.dp.roundToPx() }
    val loaded by produceState<Loaded?>(null, e.relPath, e.modified, e.size, state.rootPath) { value = loadDetail(state.rootPath, e, maxSide) }
    val host = remember { FloatArray(2) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { val p = it.positionInRoot(); host[0] = p.x; host[1] = p.y }
            .drawBehind { drawRect(bg.copy(alpha = ArchiveMath.backdropVeil(anim.push()))) }
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) { onClose(); true } else false },
    ) {
        val wide = maxWidth > maxHeight
        val infoAlpha = Modifier.graphicsLayer { alpha = anim.info() }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.1f).fillMaxHeight()) {
                    DetailBack(onClose, infoAlpha)
                    GlassPanel(e, number, loaded, palette, anim, shared, host, Modifier.padding(start = 20.dp, end = 8.dp, bottom = 18.dp).weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 20.dp)) {
                    Column(infoAlpha.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 18.dp)) {
                        DetailInfo(e, number, loaded, state, actions, anim, onClose, onEnter)
                    }
                    DetailFooter(e, serial, actions, onEnter, infoAlpha.navigationBarsPadding())
                }
            }
        } else {
            val glassHeight = min(maxHeight.value * 0.36f, maxWidth.value * 0.80f).dp
            Column(Modifier.fillMaxSize()) {
                DetailBack(onClose, infoAlpha)
                GlassPanel(e, number, loaded, palette, anim, shared, host, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(glassHeight))
                Column(infoAlpha.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp)) {
                    DetailInfo(e, number, loaded, state, actions, anim, onClose, onEnter)
                }
                DetailFooter(e, serial, actions, onEnter, infoAlpha.padding(horizontal = 20.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun DetailBack(onClose: () -> Unit, modifier: Modifier) {
    val c = Iris.colors
    Row(
        modifier.padding(start = 12.dp, top = 6.dp).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("←", style = hudArrow, color = c.text)
        Spacer(Modifier.width(12.dp))
        Text("档案总览", style = hudMicro.copy(fontWeight = FontWeight.Bold), color = c.text)
        Spacer(Modifier.width(10.dp))
        Text(
            "ESC", style = hudMicro.copy(fontSize = 7.sp, letterSpacing = 0.6.sp), color = c.textSecondary,
            modifier = Modifier.border(0.7.dp, c.textSecondary.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Clips drawing to the part of the panel the scan has swept ([inside]) or to the rest. */
private fun Modifier.scanClip(inside: Boolean, scan: () -> Float, path: Path, pts: FloatArray): Modifier = drawWithContent {
    val s = scan()
    val n = ArchiveMath.scanPolygon(s, size.width, size.height, pts)
    when {
        s >= 1f -> if (inside) drawContent()
        n < 3 -> if (!inside) drawContent()
        else -> {
            path.reset()
            path.moveTo(pts[0], pts[1])
            for (i in 1 until n) path.lineTo(pts[i * 2], pts[i * 2 + 1])
            path.close()
            clipPath(path, if (inside) ClipOp.Intersect else ClipOp.Difference) { this@drawWithContent.drawContent() }
        }
    }
}

/** The frosted glass panel: plate, contents (faint and blurred until the scan reveals them), scan line. */
@Composable
private fun GlassPanel(
    e: FileEntry,
    number: String,
    loaded: Loaded?,
    palette: ArchiveMath.FieldColors,
    anim: DetailAnim,
    shared: SharedSlab,
    host: FloatArray,
    modifier: Modifier,
) {
    val c = Iris.colors
    val ink = c.text
    val density = LocalDensity.current
    val blurs = LocalGlassQuality.current.blurs
    val blur: RenderEffect? = remember(density, blurs) {
        if (!blurs) null else with(density) { BlurEffect(9.dp.toPx(), 9.dp.toPx(), TileMode.Decal) }
    }
    val faint = if (blur != null) 0.55f else 0.20f
    val tf = remember { GlassTransform() }
    val pathA = remember { Path() }; val ptsA = remember { FloatArray(10) }
    val pathB = remember { Path() }; val ptsB = remember { FloatArray(10) }
    val seg = remember { FloatArray(4) }

    Box(
        modifier
            .onGloballyPositioned { val p = it.positionInRoot(); tf.x = p.x - host[0]; tf.y = p.y - host[1] }
            .drawWithContent {
                val p = anim.push()
                if (p >= 0.999f) { drawContent(); return@drawWithContent }
                tf.update(size.width, size.height, p, shared)
                drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.concat(tf.matrix) }
                drawContent()
                drawIntoCanvas { it.nativeCanvas.restore() }
            },
    ) {
        GlassPlate(palette, Modifier.matchParentSize())
        // before the scan passes: the visual, faint and out of focus
        Box(
            Modifier.matchParentSize()
                .scanClip(false, { anim.scan() }, pathA, ptsA)
                .graphicsLayer {
                    alpha = faint * anim.content() * ArchiveMath.phase(anim.push(), 0.55f, 1f)
                    renderEffect = blur
                },
        ) { GlassVisual(e, loaded, palette) }
        // what the scan has revealed: tag, number and the visual in focus
        Box(Modifier.matchParentSize().graphicsLayer { alpha = anim.content() }.scanClip(true, { anim.scan() }, pathB, ptsB)) {
            GlassVisual(e, loaded, palette)
            GlassLabels(e, number)
        }
        // the scan line with its end markers
        Spacer(
            Modifier.matchParentSize().drawBehind {
                val s = anim.scan()
                if (!ArchiveMath.scanSegment(s, size.width, size.height, seg)) return@drawBehind
                val sq = 5.dp.toPx()
                val a = Offset(seg[0], seg[1]); val b = Offset(seg[2], seg[3])
                drawLine(ink, a, b, strokeWidth = 1.dp.toPx())
                drawRect(ink, a - Offset(sq / 2, sq / 2), Size(sq, sq))
                drawRect(ink, b - Offset(sq / 2, sq / 2), Size(sq, sq))
                val m = sq * 0.7f
                drawRect(ink.copy(alpha = 0.7f), Offset(0f, size.height - m), Size(m, m))
                drawRect(ink.copy(alpha = 0.7f), Offset(size.width - m, 0f), Size(m, m))
            },
        )
    }
}

/** Plate of the glass: thickness, frosted face, rims, inner frame, channel line, screws, hatching. */
@Composable
private fun GlassPlate(palette: ArchiveMath.FieldColors, modifier: Modifier) {
    val c = Iris.colors
    val dark = c.isDark
    val glassFill = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.46f)
    val rimLight = if (dark) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.95f)
    val rimDark = Color(ArchiveMath.lerpColor(palette.shade, palette.background, 0.35f))
    val sheen = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.34f)
    Spacer(
        modifier.drawWithCache {
            val edge = 6.dp.toPx()
            val face = Size(size.width, size.height - edge)
            val r = CornerRadius(14.dp.toPx())
            val inset = 14.dp.toPx()
            val screw = 4.5.dp.toPx()
            val hair = 0.7.dp.toPx()
            onDrawBehind {
                drawRoundRect(rimDark.copy(alpha = 0.35f), topLeft = Offset(0f, edge), size = face, cornerRadius = r)
                drawRoundRect(glassFill, size = face, cornerRadius = r)
                drawRoundRect(Brush.linearGradient(listOf(sheen, Color.Transparent), Offset.Zero, Offset(face.width * 0.6f, face.height)), size = face, cornerRadius = r)
                drawRoundRect(rimLight, size = face, cornerRadius = r, style = Stroke(1.4.dp.toPx()))
                drawRoundRect(rimDark, size = face, cornerRadius = r, style = Stroke(0.6.dp.toPx()))
                // inner frame and a channel line down the left, like a circuit groove
                drawRoundRect(
                    rimLight.copy(alpha = rimLight.alpha * 0.75f), topLeft = Offset(inset * 1.5f, inset * 1.5f),
                    size = Size(face.width - inset * 3f, face.height - inset * 3f), cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(hair),
                )
                val gx = inset * 2.3f
                drawLine(rimDark.copy(alpha = 0.35f), Offset(gx, face.height * 0.34f), Offset(gx, face.height * 0.66f), strokeWidth = hair)
                drawLine(rimDark.copy(alpha = 0.35f), Offset(gx, face.height * 0.66f), Offset(gx + inset, face.height * 0.66f + inset), strokeWidth = hair)
                for (k in 0 until 4) {
                    val p = Offset(if (k and 1 == 0) inset * 0.8f else face.width - inset * 0.8f, if (k and 2 == 0) inset * 0.8f else face.height - inset * 0.8f)
                    drawCircle(rimDark.copy(alpha = 0.55f), screw, p)
                    drawCircle(rimLight, screw * 0.45f, p - Offset(screw * 0.2f, screw * 0.2f))
                }
                // hatching and a small ring bottom-right
                val hy = face.height - inset * 2.1f
                for (i in 0 until 9) {
                    val x = face.width * 0.60f + i * 4.dp.toPx()
                    drawLine(rimDark.copy(alpha = 0.40f), Offset(x, hy + 6.dp.toPx()), Offset(x + 4.dp.toPx(), hy - 4.dp.toPx()), strokeWidth = hair)
                }
                val ring = Offset(face.width - inset * 3.2f, hy)
                drawCircle(rimDark.copy(alpha = 0.45f), 5.dp.toPx(), ring, style = Stroke(hair * 1.4f))
                drawCircle(rimLight, 2.dp.toPx(), ring)
            }
        },
    )
}

/** Tag (folder, INFO, NO.xxx) top-left; the big number, database line and system marker bottom-left. */
@Composable
private fun GlassLabels(e: FileEntry, number: String) {
    val c = Iris.colors
    val ink = c.text
    val digits = number.substringAfter('-')
    val top = ArchiveMath.systemTop(e.relPath)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 22.dp)
                .background(if (c.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.86f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(folderLabel(e.relPath), style = hudMicro.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Box(Modifier.width(16.dp).height(3.dp).background(ink))
                    Text("INFO", style = hudMicro.copy(fontSize = 7.sp), color = c.textSecondary)
                }
            }
            Text("NO.$digits", style = hudName.copy(fontSize = 17.sp, lineHeight = 21.sp), color = ink)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 22.dp)) {
            Text("NO.$digits", style = hudCounter.copy(fontSize = 28.sp, lineHeight = 32.sp), color = ink)
            Text("INTERNAL DATABASE · IRIDIS 存储", style = hudMicro.copy(fontSize = 7.sp), color = c.textSecondary)
            if (e.system != null || top != null) {
                Spacer(Modifier.height(3.dp))
                Text("▪ 系统目录 / " + (e.system?.label ?: top?.label ?: ""), style = hudMicro.copy(fontSize = 7.sp), color = ink)
            }
        }
    }
}

/** The main visual inside the glass: thumbnail, printed first lines, a folder drawer, or the type glyph. */
@Composable
private fun GlassVisual(e: FileEntry, loaded: Loaded?, palette: ArchiveMath.FieldColors) {
    val c = Iris.colors
    val ink = c.text
    val dark = c.isDark
    val visual = ArchiveMath.visualOf(e.isDir, e.extension)
    val thumb = loaded?.thumb
    val head = loaded?.head.orEmpty()
    val paper = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.72f)
    val rimLight = if (dark) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.95f)
    val ringColor = Color(ArchiveMath.lerpColor(palette.lit, palette.shade, 0.55f))
    val isDefault = c.accent == IrisPalette.DEFAULT.light.accent || c.accent == IrisPalette.DEFAULT.dark.accent
    val copper = if (isDefault) Color(0xFFC4875A) else c.accent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = maxWidth * 0.54f
        val vh = maxHeight * 0.64f
        Box(Modifier.align(Alignment.CenterEnd).padding(end = maxWidth * 0.07f).size(vw, vh)) {
            when {
                visual == ArchiveMath.Visual.IMAGE && thumb != null -> Spacer(
                    Modifier.fillMaxSize().drawBehind {
                        val pad = 7.dp.toPx()
                        val scale = min((size.width - pad * 2) / thumb.width, (size.height - pad * 2) / thumb.height)
                        val dw = (thumb.width * scale).roundToInt(); val dh = (thumb.height * scale).roundToInt()
                        val left = ((size.width - dw) / 2f).roundToInt(); val top = ((size.height - dh) / 2f).roundToInt()
                        drawRect(paper, Offset(left - pad, top - pad), Size(dw + pad * 2, dh + pad * 2))
                        drawImage(thumb, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh))
                        drawRect(rimLight, Offset(left.toFloat(), top.toFloat()), Size(dw.toFloat(), dh.toFloat()), style = Stroke(1.dp.toPx()))
                        val m = 8.dp.toPx(); val sw = 1.dp.toPx()
                        val l = left - pad; val t = top - pad; val r = left + dw + pad; val b = top + dh + pad
                        for ((x, y, dx, dy) in listOf(listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f))) {
                            drawLine(ink, Offset(x, y), Offset(x + m * dx, y), strokeWidth = sw)
                            drawLine(ink, Offset(x, y), Offset(x, y + m * dy), strokeWidth = sw)
                        }
                    },
                )
                visual == ArchiveMath.Visual.TEXT && head.isNotEmpty() -> Column(
                    Modifier.fillMaxSize().background(paper).drawBehind {
                        drawRect(ink.copy(alpha = 0.25f), style = Stroke(0.7.dp.toPx()))
                    }.padding(horizontal = 9.dp, vertical = 8.dp),
                ) {
                    Row {
                        Text("PRINT", style = hudMicro.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold), color = ink)
                        Spacer(Modifier.weight(1f))
                        Text(e.extension.uppercase(java.util.Locale.ROOT), style = hudMicro.copy(fontSize = 7.sp), color = c.textSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    head.forEachIndexed { i, line ->
                        Row {
                            Text((i + 1).toString().padStart(2, '0'), style = hudMono.copy(fontSize = 7.sp, lineHeight = 10.sp), color = c.textTertiary)
                            Spacer(Modifier.width(6.dp))
                            Text(line, style = hudMono.copy(fontSize = 7.sp, lineHeight = 10.sp), color = ink, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                }
                visual == ArchiveMath.Visual.FOLDER -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    Text("DIR", style = hudCounter.copy(fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = 4.sp), color = ink.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.End))
                    Spacer(Modifier.weight(1f))
                    val names = loaded?.childNames.orEmpty()
                    val slats = if (names.isEmpty()) 3 else names.size
                    for (i in 0 until slats) {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 4.dp).height(17.dp).background(paper)
                                .drawBehind { drawLine(ringColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
                                .padding(horizontal = 7.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(names.getOrElse(i) { "—" }, style = hudMono.copy(fontSize = 8.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                else -> Box(
                    Modifier.fillMaxSize().drawBehind {
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val r = min(size.width, size.height) * 0.46f
                        val hair = 0.8.dp.toPx()
                        drawCircle(ringColor.copy(alpha = 0.55f), r, Offset(cx, cy), style = Stroke(hair))
                        drawCircle(ringColor.copy(alpha = 0.35f), r * 0.78f, Offset(cx, cy), style = Stroke(hair))
                        drawArc(copper.copy(alpha = 0.7f), 200f, 64f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx()))
                        for (i in 0 until 48) {
                            val a = (i * 7.5f) * (PI.toFloat() / 180f)
                            val long = i % 6 == 0
                            val r0 = r * (if (long) 1.04f else 1.02f); val r1 = r * 1.10f
                            drawLine(ringColor.copy(alpha = if (long) 0.7f else 0.4f), Offset(cx + r0 * cos(a), cy + r0 * sin(a)), Offset(cx + r1 * cos(a), cy + r1 * sin(a)), strokeWidth = hair)
                        }
                        drawLine(ink.copy(alpha = 0.25f), Offset(cx - r * 1.25f, cy), Offset(cx - r * 0.86f, cy), strokeWidth = hair)
                        drawLine(ink.copy(alpha = 0.25f), Offset(cx + r * 0.86f, cy), Offset(cx + r * 1.25f, cy), strokeWidth = hair)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    val glyph = ArchiveMath.glyphOf(e.isDir, e.extension)
                    Text(
                        glyph, style = hudCounter.copy(fontSize = if (glyph.length > 5) 24.sp else 32.sp, lineHeight = 36.sp, letterSpacing = 2.sp),
                        color = ink, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Text that starts as a redaction bar per line and is revealed left → right; the title stops after
 * its first [prefixChars] characters before clearing the rest.
 */
@Composable
private fun RedactText(
    text: String,
    style: TextStyle,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Iris.colors.text,
    bar: Color = Iris.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    prefixChars: Int = 0,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            val p = progress()
            if (p >= 1f) { drawContent(); return@drawWithContent }
            val l = layout
            if (l == null) { drawRect(bar); return@drawWithContent }
            val lines = l.lineCount
            for (i in 0 until lines) {
                val left = l.getLineLeft(i); val right = l.getLineRight(i)
                val top = l.getLineTop(i); val bottom = l.getLineBottom(i)
                val width = (right - left).coerceAtLeast(1f)
                var q = ArchiveMath.lineReveal(p, i, lines)
                if (i == 0 && prefixChars > 0) {
                    val end = min(prefixChars, l.getLineEnd(0, visibleEnd = true))
                    val pre = ((l.getHorizontalPosition(end, true) - left) / width).coerceIn(0f, 1f)
                    q = ArchiveMath.titleReveal(q, pre)
                }
                val x = left + width * q
                if (q > 0f) clipRect(left = 0f, top = top, right = x, bottom = bottom) { this@drawWithContent.drawContent() }
                if (q < 1f) {
                    val inset = (bottom - top) * 0.12f
                    drawRect(bar, Offset(x, top + inset), Size(right - x, bottom - top - inset * 2f))
                }
            }
        },
    )
}

@Composable
private fun DetailInfo(
    e: FileEntry,
    number: String,
    loaded: Loaded?,
    state: FilesState,
    actions: FilesActions,
    anim: DetailAnim,
    onClose: () -> Unit,
    onEnter: () -> Unit,
) {
    val c = Iris.colors
    val ink = c.text
    val grey = c.textSecondary
    var tab by remember(e.relPath) { mutableIntStateOf(0) }
    val canRename = actions.canRename(e)
    val canDelete = actions.canDelete(e)
    val fixed = FileProtection.isFixed(e.relPath)
    val top = ArchiveMath.systemTop(e.relPath)
    val typeName = ArchiveMath.typeName(e)
    val preview = FilePreview.canPreview(e)
    val warning = FileProtection.warningText(e.relPath)
    val typeValue = when {
        e.isDir -> if (e.system != null) "系统文件夹" else "文件夹"
        e.extension.isEmpty() -> "文件"
        else -> e.extension.uppercase(java.util.Locale.ROOT)
    }
    val children = loaded?.children ?: e.childCount
    val sizeValue = if (e.isDir) "$children 项" else FileSizes.format(e.size)
    val status = if (canRename && canDelete) "▪ 可编辑" else "▪ 受保护"
    val tag = when {
        e.system != null -> "系统目录"
        top != null -> top.label + " 目录"
        FilesPaths.parent(e.relPath).isEmpty() -> "根目录"
        else -> folderLabel(e.relPath)
    }
    val facts = ArchiveMath.Facts(
        typeName = typeName, isDir = e.isDir, sizeText = FileSizes.format(e.size), modifiedText = formatModified(e.modified),
        previewable = preview, lines = loaded?.lines, imageWidth = loaded?.imageWidth ?: 0, imageHeight = loaded?.imageHeight ?: 0,
        children = if (e.isDir) children else null, childNames = loaded?.childNames.orEmpty(),
        systemLabel = if (e.system == null) top?.label else null, fixed = fixed, warning = warning,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("FILE $number", style = hudMicro.copy(fontWeight = FontWeight.Bold), color = ink)
        Spacer(Modifier.weight(1f))
        Text("REFERENCE AREA", style = hudMicro.copy(fontSize = 7.sp), color = grey)
    }
    Spacer(Modifier.height(10.dp))
    RedactText(e.displayName, hudTitle, { anim.row(0) }, maxLines = 2, prefixChars = ArchiveMath.titlePrefixChars(e.displayName.length))
    if (e.system != null) Text(e.name, style = hudMono, color = grey)
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RedactText(typeName, hudSub, { anim.row(1) })
        Spacer(Modifier.width(10.dp))
        Text(tag, style = hudMicro.copy(fontSize = 8.sp), color = grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (e.system != null) Text("  「${FilesText.SYSTEM_BADGE}」", style = hudMicro, color = grey)
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(2.dp).background(ink))
    Spacer(Modifier.height(14.dp))
    Row {
        DetailField("类型 / TYPE", typeValue, 2, anim, Modifier.weight(1f))
        DetailField("大小 / SIZE", sizeValue, 3, anim, Modifier.weight(1f))
    }
    Row {
        DetailField("修改时间 / MODIFIED", formatModified(e.modified), 4, anim, Modifier.weight(1f))
        DetailField("状态 / STATUS", status, 5, anim, Modifier.weight(1f))
    }

    // tabs
    Row(Modifier.padding(top = 4.dp)) {
        listOf("概述", "属性", "操作").forEachIndexed { i, label ->
            val on = tab == i
            Row(
                Modifier.clickable { tab = i }.drawBehind {
                    if (on) drawLine(ink, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5.dp.toPx())
                }.padding(end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("0${i + 1}  ", style = hudMono.copy(fontSize = 8.sp), color = if (on) ink else grey)
                Text(label, style = hudAction, color = if (on) ink else grey)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(0.7.dp).background(grey.copy(alpha = 0.4f)))
    Spacer(Modifier.height(12.dp))
    when (tab) {
        0 -> {
            Text("ABSTRACT / 摘要", style = hudMicro, color = grey)
            Spacer(Modifier.height(6.dp))
            RedactText(ArchiveMath.summary(facts), hudBody, { anim.row(6) })
            val head = loaded?.head.orEmpty()
            if (!e.isDir && head.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("HEAD / 前几行", style = hudMicro, color = grey)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().background(c.codeBackground.copy(alpha = 0.5f)).padding(10.dp)) {
                    head.take(6).forEach { Text(it, style = hudMono.copy(fontSize = 11.sp, lineHeight = 15.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        1 -> DetailProperties(e, state, fixed, warning)
        else -> DetailActions(e, actions, canRename, canDelete, warning, onClose, onEnter)
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DetailField(label: String, value: String, row: Int, anim: DetailAnim, modifier: Modifier) {
    val c = Iris.colors
    Column(modifier.padding(bottom = 14.dp, end = 8.dp)) {
        Text(label, style = hudMicro.copy(fontSize = 8.sp), color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        RedactText(value, hudValue, { anim.row(row) }, maxLines = 2)
    }
}

/** 02 属性: full path (selectable, copy), extension, MIME, folder, bytes, protection. */
@Composable
private fun DetailProperties(e: FileEntry, state: FilesState, fixed: Boolean, warning: String?) {
    val c = Iris.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember(e.relPath) { mutableStateOf(false) }
    val full = state.rootPath?.let { it.trimEnd('/') + "/" + e.relPath } ?: ArchiveMath.pathTag(e.relPath)
    val mime = if (e.isDir) "inode/directory" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.extension) ?: FilePreview.mimeFallback(e.extension)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("完整路径 / PATH", style = hudMicro.copy(fontSize = 8.sp), color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(
                if (copied) "已复制" else "复制", style = hudAction, color = c.text,
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(full)); copied = true }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        SelectionContainer { Text(full, style = hudMono.copy(fontSize = 11.sp, lineHeight = 15.sp), color = c.text) }
        Spacer(Modifier.height(12.dp))
        PropRow("扩展名 / EXTENSION", if (e.isDir) "—" else if (e.extension.isEmpty()) "无" else "." + e.extension)
        PropRow("MIME", mime, mono = true)
        PropRow("所在目录 / FOLDER", ArchiveMath.pathTag(FilesPaths.parent(e.relPath)), mono = true)
        if (!e.isDir) PropRow("大小 / BYTES", String.format(java.util.Locale.ROOT, "%,d B", e.size), mono = true)
        PropRow("修改时间 / MODIFIED", formatModified(e.modified))
        PropRow(
            "系统保护 / PROTECTED",
            when {
                fixed -> "是 · IRIDIS 系统目录，不能重命名或删除"
                warning != null -> "否 · 位于模型目录，改名或删除会影响模型加载"
                else -> "否"
            },
        )
    }
}

@Composable
private fun PropRow(label: String, value: String, mono: Boolean = false) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, style = hudMicro.copy(fontSize = 8.sp), color = c.textSecondary)
        Spacer(Modifier.height(3.dp))
        Text(value, style = if (mono) hudMono.copy(fontSize = 11.sp, lineHeight = 15.sp) else hudValue, color = c.text)
    }
}

/** 03 操作: the same FilesActions as the list; protected entries show why an action is off. */
@Composable
private fun DetailActions(
    e: FileEntry,
    actions: FilesActions,
    canRename: Boolean,
    canDelete: Boolean,
    warning: String?,
    onClose: () -> Unit,
    onEnter: () -> Unit,
) {
    val locked = "IRIDIS 系统目录固定在根目录"
    Column {
        if (e.isDir) {
            ActionRow(IrisIcons.Folder, "进入文件夹", "浏览这个文件夹的档案", true) { onEnter() }
        } else {
            ActionRow(IrisIcons.File, "打开", if (FilePreview.canPreview(e)) "在应用内阅读" else "交给其他应用打开", true) { actions.onEntryClick(e) }
            ActionRow(IrisIcons.OpenExternal, "用其他应用打开", "系统的「打开方式」", true) { actions.openExternal(e) }
            ActionRow(IrisIcons.Share, "分享", "发送到其他应用", true) { actions.share(e) }
        }
        ActionRow(IrisIcons.Edit, "重命名", if (!canRename) "$locked，不能重命名" else warning ?: "修改名称", canRename) { onClose(); actions.requestRename(e) }
        ActionRow(IrisIcons.Trash, "删除", if (!canDelete) "$locked，不能删除" else warning ?: "删除前会再次确认", canDelete, danger = true) { onClose(); actions.requestDelete(e) }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, hint: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = Iris.colors
    val tint = when {
        !enabled -> c.textTertiary
        danger -> c.danger
        else -> c.text
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .drawBehind { drawLine(c.textSecondary.copy(alpha = 0.25f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 0.7.dp.toPx()) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = hudValue, color = tint)
            Text(hint, style = hudMicro.copy(fontSize = 9.sp, letterSpacing = 0.4.sp), color = c.textSecondary)
        }
        if (enabled) Text("→", style = hudArrow, color = tint)
    }
}

/** Dark primary bar「+ 打开文件 · OPEN」(folders: 进入), 分享 ↓, and the HUD line under it. */
@Composable
private fun DetailFooter(e: FileEntry, serial: String, actions: FilesActions, onEnter: () -> Unit, modifier: Modifier) {
    val c = Iris.colors
    val ink = c.text
    val onInk = if (c.isDark) Color.Black else Color.White
    Column(modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxHeight().background(ink)
                    .clickable { if (e.isDir) onEnter() else actions.onEntryClick(e) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+  " + if (e.isDir) "进入文件夹" else "打开文件", style = hudAction.copy(fontWeight = FontWeight.Bold), color = onInk)
                Spacer(Modifier.weight(1f))
                Text(if (e.isDir) "ENTER" else "OPEN", style = hudMicro, color = onInk.copy(alpha = 0.7f))
            }
            if (!e.isDir) Row(
                Modifier.fillMaxHeight().clickable { actions.share(e) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分享", style = hudAction, color = ink)
                Spacer(Modifier.width(10.dp))
                Text("↓", style = hudArrow, color = ink)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▪  IRIDIS · 本地存储", style = hudMicro.copy(fontSize = 8.sp), color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(serial, style = hudMono, color = c.textSecondary)
            Spacer(Modifier.width(14.dp))
            HudClock()
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun HudClock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            value = System.currentTimeMillis()
        }
    }
    Text(synchronized(clockFormat) { clockFormat.format(java.util.Date(now)) }, style = hudMono, color = Iris.colors.textSecondary)
}

/** The file under Download/IRIDIS (null when it would leave the root). */
private fun resolve(rootPath: String?, e: FileEntry): File? = rootPath?.let { FilesPaths.resolve(File(it), e.relPath) }

private const val HEAD_BYTES = 16L * 1024

/** Thumbnail and size (images), first lines and line count (text), children (folders) — all confined to the root. */
private suspend fun loadDetail(rootPath: String?, e: FileEntry, maxSide: Int): Loaded = withContext(Dispatchers.IO) {
    runCatching {
        if (e.isDir) {
            val root = rootPath?.let { File(it) } ?: return@runCatching Loaded()
            val kids = FilesIo.list(root, e.relPath)
            val names = kids.orEmpty().sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase(java.util.Locale.ROOT) })).take(4).map { it.displayName }
            return@runCatching Loaded(children = kids?.size, childNames = names)
        }
        val f = resolve(rootPath, e) ?: return@runCatching Loaded()
        if (ArchiveMath.isImage(e.extension)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching Loaded()
            val opts = BitmapFactory.Options().apply { inSampleSize = ArchiveMath.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            return@runCatching Loaded(BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap(), bounds.outWidth, bounds.outHeight)
        }
        if (FilePreview.isText(e.extension)) {
            val head = FilePreview.readHead(f, HEAD_BYTES)
            if (!FilePreview.looksText(head)) return@runCatching Loaded()
            val lines = if (e.size <= FilePreview.MAX_BYTES) ArchiveMath.lineCount(FilePreview.readHead(f)) else null
            return@runCatching Loaded(head = ArchiveMath.headLines(String(head, Charsets.UTF_8), 10, 44), lines = lines)
        }
        Loaded()
    }.getOrElse { Loaded() }
}

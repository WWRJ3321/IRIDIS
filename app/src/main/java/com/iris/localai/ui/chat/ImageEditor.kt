package com.iris.localai.ui.chat

import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.Iris
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.theme.IrisFontSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** geometry of the picture editor (crop box, view ↔ picture mapping). Pure, unit-tested. */
object ImageEditMath {
    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

    /** Where a [bw]×[bh] picture sits in a [vw]×[vh] view (fit, centred). */
    fun fit(bw: Float, bh: Float, vw: Float, vh: Float): Rect {
        if (bw <= 0f || bh <= 0f || vw <= 0f || vh <= 0f) return Rect.Zero
        val s = min(vw / bw, vh / bh)
        val w = bw * s
        val h = bh * s
        return Rect((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
    }

    /** View point → picture pixel (may fall outside the picture). */
    fun toImage(p: Offset, fit: Rect, bw: Float): Offset {
        val s = if (fit.width > 0f) bw / fit.width else 1f
        return Offset((p.x - fit.left) * s, (p.y - fit.top) * s)
    }

    /** Picture pixel → view point. */
    fun toView(p: Offset, fit: Rect, bw: Float): Offset {
        val s = if (bw > 0f) fit.width / bw else 1f
        return Offset(fit.left + p.x * s, fit.top + p.y * s)
    }

    /** Which part of [crop] a touch at [p] (picture px) grabs: a corner within [radius], else inside = move. */
    fun hit(p: Offset, crop: Rect, radius: Float): Handle? {
        val corners = listOf(
            Handle.TOP_LEFT to crop.topLeft, Handle.TOP_RIGHT to crop.topRight,
            Handle.BOTTOM_LEFT to crop.bottomLeft, Handle.BOTTOM_RIGHT to crop.bottomRight,
        )
        corners.minByOrNull { (_, c) -> (c - p).getDistance() }?.let { (h, c) -> if ((c - p).getDistance() <= radius) return h }
        return if (crop.contains(p)) Handle.MOVE else null
    }

    /** [crop] after dragging [h] by [d] (picture px), kept inside [bw]×[bh] and at least [minSide] each way. */
    fun drag(crop: Rect, h: Handle, d: Offset, bw: Float, bh: Float, minSide: Float): Rect {
        val o = dragBox(floatArrayOf(crop.left, crop.top, crop.right, crop.bottom), h, d.x, d.y, bw, bh, minSide)
        return Rect(o[0], o[1], o[2], o[3])
    }

    /** [drag] on a plain box (left, top, right, bottom). */
    fun dragBox(box: FloatArray, h: Handle, dx: Float, dy: Float, bw: Float, bh: Float, minSide: Float): FloatArray {
        val (l, t, r, b) = box.toList()
        val m = minSide.coerceAtMost(min(bw, bh))
        return when (h) {
            Handle.MOVE -> {
                val x = dx.coerceIn(-l, bw - r)
                val y = dy.coerceIn(-t, bh - b)
                floatArrayOf(l + x, t + y, r + x, b + y)
            }
            Handle.TOP_LEFT -> floatArrayOf((l + dx).coerceIn(0f, r - m), (t + dy).coerceIn(0f, b - m), r, b)
            Handle.TOP_RIGHT -> floatArrayOf(l, (t + dy).coerceIn(0f, b - m), (r + dx).coerceIn(l + m, bw), b)
            Handle.BOTTOM_LEFT -> floatArrayOf((l + dx).coerceIn(0f, r - m), t, r, (b + dy).coerceIn(t + m, bh))
            Handle.BOTTOM_RIGHT -> floatArrayOf(l, t, (r + dx).coerceIn(l + m, bw), (b + dy).coerceIn(t + m, bh))
        }
    }

    /** Whole-pixel crop box inside the picture, or null when it is (about) the whole picture. */
    fun pixels(crop: Rect, bw: Int, bh: Int): android.graphics.Rect? =
        pixelBox(crop.left, crop.top, crop.right, crop.bottom, bw, bh)?.let { android.graphics.Rect(it[0], it[1], it[2], it[3]) }

    /** [pixels] as (left, top, right, bottom). */
    fun pixelBox(left: Float, top: Float, right: Float, bottom: Float, bw: Int, bh: Int): IntArray? {
        val l = left.roundToInt().coerceIn(0, bw - 1)
        val t = top.roundToInt().coerceIn(0, bh - 1)
        val r = right.roundToInt().coerceIn(l + 1, bw)
        val b = bottom.roundToInt().coerceIn(t + 1, bh)
        return if (l == 0 && t == 0 && r == bw && b == bh) null else intArrayOf(l, t, r, b)
    }

    /** Size of a [w]×[h] picture after [quarters] quarter turns. */
    fun rotated(w: Int, h: Int, quarters: Int): Pair<Int, Int> = if (abs(quarters) % 2 == 1) h to w else w to h

    /** Pen width in picture pixels: about 1/150 of the longer side, at least 3 px. */
    fun penWidth(bw: Int, bh: Int): Float = (maxOf(bw, bh) / 150f).coerceAtLeast(3f)

    /** The editor's pen colours. */
    val PEN = listOf(0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF0A84FF, 0xFFFFFFFF, 0xFF000000)

    /** Saved as PNG (second = true) when the original name says so, else JPEG; the name keeps its stem. */
    fun outName(name: String): Pair<String, Boolean> {
        val ext = name.substringAfterLast('.', "").lowercase()
        val stem = if (name.contains('.')) name.substringBeforeLast('.') else name
        return if (ext == "png") "$stem.png" to true else "$stem.jpg" to false
    }
}

/** bitmap operations of the editor (Android only). */
internal object ImageEditOps {
    fun rotateLeft(b: Bitmap): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun crop(b: Bitmap, r: android.graphics.Rect): Bitmap = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())

    fun draw(b: Bitmap, strokes: List<PenStroke>): Bitmap {
        if (strokes.isEmpty()) return b
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        strokes.forEach { s ->
            paint.color = s.color.toArgb(); paint.strokeWidth = s.width
            if (s.points.size == 1) {
                paint.style = android.graphics.Paint.Style.FILL
                c.drawCircle(s.points[0].x, s.points[0].y, s.width / 2f, paint)
                paint.style = android.graphics.Paint.Style.STROKE
            } else {
                val p = android.graphics.Path()
                s.points.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                c.drawPath(p, paint)
            }
        }
        return out
    }

    fun write(b: Bitmap, file: java.io.File, format: Bitmap.CompressFormat): Boolean =
        file.outputStream().use { b.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 92, it) }
}

/** One doodle stroke in picture pixels. */
data class PenStroke(val points: List<Offset>, val color: Color, val width: Float)

private enum class EditTool { CROP, DRAW }

/**
 * full-screen picture editor — crop (drag the corners or the box), rotate left, doodle
 * (six colours). Undo steps back one stroke or one crop / rotation. 完成 hands back the result.
 */
@Composable
internal fun ImageEditor(source: Bitmap, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    // Hardware bitmaps can't be drawn on / cut with a matrix: work on a software copy.
    val base = remember(source) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    }
    val history = remember(base) { mutableStateListOf(base) }
    val cur = history.last()
    var tool by remember { mutableStateOf<EditTool?>(null) }
    var crop by remember(cur) { mutableStateOf(Rect(0f, 0f, cur.width.toFloat(), cur.height.toFloat())) }
    val strokes = remember(cur) { mutableStateListOf<PenStroke>() }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var pen by remember { mutableStateOf(Color(ImageEditMath.PEN[0])) }
    val img = remember(cur) { cur.asImageBitmap() }
    val white = Color.White
    val highlight = Iris.colors.accent

    fun bake(): Bitmap {
        var b = cur
        if (strokes.isNotEmpty()) b = ImageEditOps.draw(b, strokes.toList())
        if (tool == EditTool.CROP) ImageEditMath.pixels(crop, b.width, b.height)?.let { b = ImageEditOps.crop(b, it) }
        return b
    }
    fun commit(next: Bitmap) { if (next !== cur) history.add(next) }
    fun pick(t: EditTool?) { commit(bake()); tool = if (tool == t) null else t }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize().systemBarsPadding().padding(top = 56.dp, bottom = 132.dp, start = 16.dp, end = 16.dp)
                .pointerInput(tool, cur) {
                    val f = ImageEditMath.fit(cur.width.toFloat(), cur.height.toFloat(), size.width.toFloat(), size.height.toFloat())
                    val toImg = { p: Offset -> ImageEditMath.toImage(p, f, cur.width.toFloat()) }
                    val s = if (f.width > 0f) cur.width / f.width else 1f
                    when (tool) {
                        EditTool.CROP -> {
                            var h: ImageEditMath.Handle? = null
                            detectDragGestures(
                                onDragStart = { p -> h = ImageEditMath.hit(toImg(p), crop, 32.dp.toPx() * s) },
                                onDragEnd = { h = null }, onDragCancel = { h = null },
                            ) { ch, d ->
                                val hh = h ?: return@detectDragGestures
                                ch.consume()
                                crop = ImageEditMath.drag(crop, hh, d * s, cur.width.toFloat(), cur.height.toFloat(), 48.dp.toPx() * s)
                            }
                        }
                        EditTool.DRAW -> detectDragGestures(
                            onDragStart = { p -> live = listOf(toImg(p)) },
                            onDragEnd = { if (live.isNotEmpty()) strokes.add(PenStroke(live, pen, ImageEditMath.penWidth(cur.width, cur.height))); live = emptyList() },
                            onDragCancel = { live = emptyList() },
                        ) { ch, _ -> ch.consume(); live = live + toImg(ch.position) }
                        null -> Unit
                    }
                },
        ) {
            val f = ImageEditMath.fit(cur.width.toFloat(), cur.height.toFloat(), size.width, size.height)
            if (f.width <= 0f) return@Canvas
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(cur.width, cur.height),
                dstOffset = IntOffset(f.left.roundToInt(), f.top.roundToInt()), dstSize = IntSize(f.width.roundToInt(), f.height.roundToInt()))
            val k = f.width / cur.width
            fun strokeOf(pts: List<Offset>, color: Color) {
                if (pts.isEmpty()) return
                val w = ImageEditMath.penWidth(cur.width, cur.height) * k
                if (pts.size == 1) { drawCircle(color, w / 2f, ImageEditMath.toView(pts[0], f, cur.width.toFloat())); return }
                val p = Path()
                pts.forEachIndexed { i, o -> val v = ImageEditMath.toView(o, f, cur.width.toFloat()); if (i == 0) p.moveTo(v.x, v.y) else p.lineTo(v.x, v.y) }
                drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            strokes.forEach { strokeOf(it.points, it.color) }
            strokeOf(live, pen)
            if (tool == EditTool.CROP) {
                val tl = ImageEditMath.toView(crop.topLeft, f, cur.width.toFloat())
                val br = ImageEditMath.toView(crop.bottomRight, f, cur.width.toFloat())
                val r = Rect(tl, br)
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset(f.left, f.top), Size(f.width, r.top - f.top))
                drawRect(dim, Offset(f.left, r.bottom), Size(f.width, f.bottom - r.bottom))
                drawRect(dim, Offset(f.left, r.top), Size(r.left - f.left, r.height))
                drawRect(dim, Offset(r.right, r.top), Size(f.right - r.right, r.height))
                drawRect(white, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
                for (i in 1..2) {
                    val x = r.left + r.width * i / 3f
                    val y = r.top + r.height * i / 3f
                    drawLine(white.copy(alpha = 0.45f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                    drawLine(white.copy(alpha = 0.45f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                }
                val l = 18.dp.toPx(); val t = 3.dp.toPx()
                listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f)).forEach { (c, dir) ->
                    drawLine(white, c, c + Offset(l * dir.x, 0f), t, cap = StrokeCap.Round)
                    drawLine(white, c, c + Offset(0f, l * dir.y), t, cap = StrokeCap.Round)
                }
            }
        }
        // Top: 取消 · 完成
        Row(
            Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarText(I18n.s(R.string.img262_cancel), white.copy(alpha = 0.85f), onCancel)
            Spacer(Modifier.weight(1f))
            BarText(I18n.s(R.string.img262_done), highlight) { onDone(bake()) }
        }
        // Bottom: tool options, then the tools.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(bottom = 8.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                when (tool) {
                    EditTool.DRAW -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ImageEditMath.PEN.forEach { argb ->
                            val col = Color(argb)
                            val sel = pen == col
                            Box(
                                Modifier.size(if (sel) 28.dp else 22.dp).clip(CircleShape).background(col)
                                    .border(if (sel) 2.5.dp else 1.dp, if (sel) white else white.copy(alpha = 0.4f), CircleShape)
                                    .clickable { pen = col },
                            )
                        }
                    }
                    EditTool.CROP -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        BarText(I18n.s(R.string.img262_reset), white) { crop = Rect(0f, 0f, cur.width.toFloat(), cur.height.toFloat()) }
                        BarText(I18n.s(R.string.img262_apply), highlight) { commit(bake()); tool = null }
                    }
                    null -> Unit
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Outlined.Crop, I18n.s(R.string.img262_crop), tool == EditTool.CROP) { pick(EditTool.CROP) }
                ToolButton(Icons.Outlined.RotateLeft, I18n.s(R.string.img262_rotate), false) { commit(ImageEditOps.rotateLeft(bake())); tool = null }
                ToolButton(Icons.Outlined.Brush, I18n.s(R.string.img262_draw), tool == EditTool.DRAW) { pick(EditTool.DRAW) }
                ToolButton(
                    Icons.AutoMirrored.Outlined.Undo, I18n.s(R.string.img262_undo), false,
                    enabled = strokes.isNotEmpty() || history.size > 1,
                ) {
                    if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                    else if (history.size > 1) history.removeAt(history.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun BarText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = IrisFontSize.M,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun ToolButton(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val a by animateFloatAsState(if (enabled) 1f else 0.35f, IrisMotion.FadeIn.float(), label = "toolAlpha")
    val tint = (if (selected) Iris.colors.accent else Color.White).copy(alpha = a)
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = IrisFontSize.XS)
    }
}

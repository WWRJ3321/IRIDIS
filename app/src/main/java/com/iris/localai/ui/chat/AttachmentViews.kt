package com.iris.localai.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.iris.localai.ui.motion.IrisMotion
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iris.localai.data.Attachment
import com.iris.localai.data.AttachmentKind
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.util.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 1.10.12: a row of attachments (composer: removable; messages: read-only).
 * 1.13.0: a LazyRow — scrolls sideways, items spring in, fade + shrink out when removed and the
 * rest slide over; in the composer a long press picks one up to drag it to a new place.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AttachmentStrip(
    list: List<Attachment>,
    onRemove: ((Attachment) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
) {
    if (list.isEmpty()) return
    val reduce = com.iris.localai.ui.theme.LocalReduceMotion.current
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val spacingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val latest = androidx.compose.runtime.rememberUpdatedState(list)
    val gestures = if (onMove == null || list.size < 2) Modifier else Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { at ->
                state.layoutInfo.visibleItemsInfo.firstOrNull { at.x.toInt() in it.offset..(it.offset + it.size) }?.let {
                    dragKey = it.key as? String; dragDx = 0f
                    haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.LONG_PRESS)
                }
            },
            onDrag = { change, d ->
                val key = dragKey ?: return@detectDragGesturesAfterLongPress
                change.consume()
                dragDx += d.x
                val items = state.layoutInfo.visibleItemsInfo
                val cur = items.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                val center = cur.offset + dragDx + cur.size / 2f
                val target = items.firstOrNull { it.key != key && center >= it.offset && center <= it.offset + it.size }
                if (target != null && target.index in latest.value.indices) {
                    val shift = (target.size + spacingPx) * if (target.index > cur.index) 1 else -1
                    onMove(cur.index, target.index)
                    dragDx -= shift
                }
                // Near an edge: scroll so the strip follows the finger.
                val vp = state.layoutInfo.viewportEndOffset
                if (center > vp - 24f) state.dispatchRawDelta(12f) else if (center < 24f) state.dispatchRawDelta(-12f)
            },
            onDragEnd = { dragKey = null; dragDx = 0f },
            onDragCancel = { dragKey = null; dragDx = 0f },
        )
    }
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.then(gestures),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(list.size, key = { list[it].path }) { i ->
            val a = list[i]
            val dragging = dragKey == a.path
            val lift by androidx.compose.animation.core.animateFloatAsState(
                if (dragging) 1.08f else 1f, IrisMotion.Snappy.float(), label = "lift",
            )
            val itemAnim = if (reduce) Modifier else Modifier.animateItem(
                fadeInSpec = IrisMotion.FadeIn.float(),
                placementSpec = if (dragging) null else IrisMotion.Snappy.offset(),
                fadeOutSpec = IrisMotion.FadeOut.float(),
            )
            Box(
                itemAnim
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (dragging) dragDx else 0f
                        scaleX = lift; scaleY = lift
                        shadowElevation = if (dragging) 8.dp.toPx() else 0f
                        shape = IrisRadius.smallShape; clip = false
                    },
            ) {
                if (a.kind == AttachmentKind.IMAGE) ImageThumb(a, 64.dp) else FileChip(a)
                if (onRemove != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                        PressableIcon(
                            Icons.Rounded.Close, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8506eb, a.name), size = 22.dp, iconSize = 13.dp,
                            tint = Iris.colors.onAccent, background = Iris.colors.text.copy(alpha = 0.55f),
                            onClick = { onRemove(a) },
                        )
                    }
                }
            }
        }
    }
}

/** 1.13.0: pure reorder used by the composer's drag (kept separate for tests). */
object AttachmentOrder {
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}

@Composable
private fun ImageThumb(a: Attachment, size: Dp) {
    val c = Iris.colors
    var bmp by remember(a.path) { mutableStateOf<ImageBitmap?>(null) }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    LaunchedEffect(a.path) {
        bmp = withContext(Dispatchers.IO) { decodeThumb(a.path, px * 2) }
    }
    Box(
        Modifier.size(size).clip(IrisRadius.smallShape).background(c.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, a.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Icon(Icons.Outlined.InsertDriveFile, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FileChip(a: Attachment) {
    val c = Iris.colors
    Row(
        Modifier
            .height(64.dp)
            .widthIn(min = 120.dp, max = 200.dp)
            .clip(IrisRadius.smallShape)
            .background(c.surfaceVariant)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(c.accentContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (a.kind == AttachmentKind.TEXT) Icons.Outlined.Description else Icons.Outlined.InsertDriveFile,
                null, tint = c.onAccentContainer, modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(end = 14.dp)) {
            Text(a.name, style = IrisType.labelSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Attachments.ext(a.name).uppercase().ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2a0c47) } + " · " + Attachments.sizeLabel(a.sizeBytes),
                style = IrisType.caption, color = c.textSecondary, maxLines = 1,
            )
        }
    }
}

private fun decodeThumb(path: String, target: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var sample = 1
    while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

package com.iris.localai.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.HomeItem
import com.iris.localai.data.HomeLayout
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlin.math.roundToInt

/** 3.0.0：布局编辑页拖动收尾的纯逻辑（单测）。 */
object LayoutDrag {
    /** 拖动中显示预览；松手后到设置写回前显示 [pending]；否则显示已保存的。 */
    fun shown(preview: HomeLayout?, pending: HomeLayout?, saved: HomeLayout): HomeLayout = preview ?: pending ?: saved
    /** 设置已写回到和 [pending] 一样。 */
    fun settled(pending: HomeLayout, saved: HomeLayout): Boolean = pending.encode() == saved.encode()
}

/** 3.0.0 Beta6 设置 › 主页布局：拖动按钮换格子。 */
object LayoutText {
    fun label(i: HomeItem): String = I18n.s(when (i) {
        HomeItem.MENU -> R.string.b6_item_menu
        HomeItem.MODEL -> R.string.b6_item_model
        HomeItem.CHARACTER -> R.string.b6_item_character
        HomeItem.NEW_CHAT -> R.string.b6_item_new
        HomeItem.AGENT -> R.string.b6_item_agent
        HomeItem.SKILLS -> R.string.b6_item_skills
        HomeItem.ATTACH -> R.string.b6_item_attach
        HomeItem.THINK -> R.string.b6_item_think
        HomeItem.SPEED -> R.string.b6_item_speed
        HomeItem.VOICE -> R.string.b6_item_voice
    })

    fun icon(i: HomeItem): ImageVector = when (i) {
        HomeItem.MENU -> Icons.Outlined.Menu
        HomeItem.MODEL -> IrisIcons.Cube
        HomeItem.CHARACTER -> IrisIcons.Person
        HomeItem.NEW_CHAT -> Icons.Outlined.EditNote
        HomeItem.AGENT -> IrisIcons.Agent
        HomeItem.SKILLS -> IrisIcons.Skill
        HomeItem.ATTACH -> IrisIcons.Plus
        HomeItem.THINK -> IrisIcons.Think
        HomeItem.SPEED -> Icons.Rounded.Bolt
        HomeItem.VOICE -> IrisIcons.Voice
    }
}


/**
 * 3.0.0 Beta6 r2：编辑区就是一张缩小的主页——顶栏、聊天区、输入框，按钮长得和主页一样，
 * 左边一串靠左、右边一串靠右，没有空格子。拖动时其余按钮实时让位，松手即保存。
 * 手势挂在整个编辑区上（不挂在按钮上），按钮换排时手势不会断。
 */
@Composable
fun LayoutPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val saved = remember(s.homeLayout) { HomeLayout.parse(s.homeLayout) }
    val savedNow by rememberUpdatedState(saved)
    val c = Iris.colors
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // 3.0.0：rect 一律存 root 坐标；手势里用 down.position + 当时的 origin 比较（不再依赖组合时捕获的旧 origin）
    val rowRects = remember { mutableStateMapOf<HomeLayout.Row, Rect>() }
    val itemRects = remember { mutableStateMapOf<HomeItem, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<HomeItem?>(null) }
    var grabRect by remember { mutableStateOf(Rect.Zero) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var preview by remember { mutableStateOf<HomeLayout?>(null) }
    // 3.0.0：松手后保持新布局，直到设置真的写回（避免先闪回原位一帧）
    var pending by remember { mutableStateOf<HomeLayout?>(null) }
    var full by remember { mutableStateOf(false) }
    val shown = LayoutDrag.shown(preview, pending, saved)
    androidx.compose.runtime.LaunchedEffect(saved, pending) {
        val p = pending ?: return@LaunchedEffect
        if (LayoutDrag.settled(p, saved)) pending = null
        else { kotlinx.coroutines.delay(1500); pending = null }
    }

    fun target(item: HomeItem, p: Offset, base: HomeLayout): HomeLayout {
        val rows = HomeLayout.rowsOf(item.group).mapNotNull { r -> rowRects[r]?.let { r to it } }
        val (row, rr) = rows.minByOrNull { (_, rect) ->
            val dy = when { p.y < rect.top -> rect.top - p.y; p.y > rect.bottom -> p.y - rect.bottom; else -> 0f }; dy
        } ?: return base
        val end = p.x > rr.center.x
        val side = (if (end) base.end(row) else base.start(row)).filter { it != item }
        val pos = side.count { o -> (itemRects[o]?.center?.x ?: Float.MAX_VALUE) < p.x }
        val next = savedNow.move(item, row, end, pos)
        full = next === savedNow && savedNow.placeOf(item)?.row != row && savedNow.count(row) >= HomeLayout.cap(row)
        return next
    }

    fun save(next: HomeLayout) { vm.update { it.copy(homeLayout = next.encode()) } }

    SubpageScaffold(I18n.s(R.string.b6_layout), onBack) {
        ScrollBody {
            Text(
                I18n.s(if (full) R.string.b6r2_layout_full else R.string.b6r2_layout_hint),
                style = IrisType.secondary, color = if (full) c.danger else c.textSecondary, minLines = 2,
                modifier = Modifier.padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space8),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IrisDimens.pageGutter)
                    .clip(IrisRadius.largeShape)
                    .background(c.chatBackground)
                    .border(1.dp, c.outline.copy(alpha = 0.4f), IrisRadius.largeShape)
                    .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val hit = itemRects.entries.firstOrNull { it.value.inflate(6f).contains(down.position + origin) }?.key
                                ?: return@awaitEachGesture
                            val slop = awaitTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
                                ?: return@awaitEachGesture
                            dragging = hit; grabRect = itemRects[hit] ?: Rect.Zero
                            drag = slop.position - down.position; preview = savedNow; full = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val ok = drag(slop.id) { ch ->
                                drag += ch.positionChange(); ch.consume()
                                val next = target(hit, grabRect.center + drag, preview ?: savedNow)
                                if (next != preview) { preview = next; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                            val end = preview
                            if (ok && end != null && end != savedNow) { pending = end; save(end) }
                            dragging = null; drag = Offset.Zero; preview = null; full = false
                        }
                    },
            ) {
                Column(Modifier.fillMaxWidth().padding(IrisDimens.space8)) {
                    EditRow(shown, HomeLayout.Row.TOP, dragging, Modifier.height(48.dp), rowRects, itemRects)
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(I18n.s(R.string.b6_layout_chat), style = IrisType.secondary, color = c.textTertiary)
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(IrisRadius.composerShape)
                            .background(c.surface)
                            .border(1.dp, c.outline.copy(alpha = 0.6f), IrisRadius.composerShape)
                            .padding(IrisDimens.space8),
                    ) {
                        EditRow(shown, HomeLayout.Row.UPPER, dragging, Modifier.height(44.dp), rowRects, itemRects)
                        Text(
                            I18n.s(R.string.b6_layout_input), style = IrisType.body, color = c.textTertiary,
                            modifier = Modifier.padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space8),
                        )
                        EditRow(shown, HomeLayout.Row.LOWER, dragging, Modifier.height(44.dp), rowRects, itemRects)
                    }
                }
                // the button under the finger, floating above everything
                val d = dragging
                if (d != null) {
                    Box(
                        Modifier
                            .zIndex(3f)
                            .offset { val p = grabRect.topLeft - origin + drag; IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                            .size(with(density) { grabRect.width.toDp() }, with(density) { grabRect.height.toDp() })
                            .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f; shadowElevation = 12f; shape = CircleShape; clip = false },
                        contentAlignment = Alignment.Center,
                    ) { MiniButton(d, lifted = true) }
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
            IrisButton(
                I18n.s(R.string.b6_layout_reset), onClick = { save(HomeLayout.DEFAULT) },
                style = IrisButtonStyle.Tonal, enabled = !saved.isDefault,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter),
            )
        }
    }
}

/** one row like the home page: the left run, a gap, the right run. The dragged button leaves a faint hole. */
@Composable
private fun EditRow(
    layout: HomeLayout, row: HomeLayout.Row, dragging: HomeItem?, modifier: Modifier,
    rowRects: MutableMap<HomeLayout.Row, Rect>, itemRects: MutableMap<HomeItem, Rect>,
) {
    val c = Iris.colors
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (dragging != null && dragging.group == row.group) c.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent)
            .onGloballyPositioned { rowRects[row] = it.boundsInRoot() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val one: @Composable (HomeItem) -> Unit = { item ->
            key(item) {
                Box(
                    Modifier
                        .onGloballyPositioned { itemRects[item] = it.boundsInRoot() }
                        .graphicsLayer { alpha = if (item == dragging) 0.25f else 1f },
                ) { MiniButton(item, lifted = false) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            layout.start(row).forEach { one(it) }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            layout.end(row).forEach { one(it) }
        }
    }
}

/** a small copy of the real button: round icon buttons, pills with a label, the blue voice pill. */
@Composable
private fun MiniButton(item: HomeItem, lifted: Boolean) {
    val c = Iris.colors
    val accent = item == HomeItem.VOICE
    val bg = when { accent -> c.accent; lifted -> c.surface; else -> c.surfaceVariant.copy(alpha = 0.7f) }
    val fg = if (accent) c.onAccent else c.text
    val round = item == HomeItem.MENU || item == HomeItem.NEW_CHAT || item == HomeItem.ATTACH
    val shape = CircleShape
    Box(
        Modifier
            .height(34.dp)
            .then(if (round) Modifier.width(34.dp) else if (accent) Modifier.width(64.dp) else Modifier)
            .clip(shape)
            .background(bg)
            .then(if (lifted) Modifier.border(1.5.dp, c.accent, shape) else Modifier)
            .padding(horizontal = if (round || accent) 0.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val iconOnly = round || accent
            if (item != HomeItem.MODEL && item != HomeItem.CHARACTER) {
                Icon(LayoutText.icon(item), null, tint = fg, modifier = Modifier.size(16.dp))
            }
            if (!iconOnly) {
                Text(LayoutText.label(item), color = fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
                if (item == HomeItem.MODEL || item == HomeItem.CHARACTER || item == HomeItem.SPEED) {
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

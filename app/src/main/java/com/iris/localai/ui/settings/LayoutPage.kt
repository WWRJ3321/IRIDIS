package com.iris.localai.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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

private val CELL_H = 56.dp

@Composable
fun LayoutPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val layout = remember(s.homeLayout) { HomeLayout.parse(s.homeLayout) }
    val c = Iris.colors
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // cell rects (root coords) and the editor box's origin, so chips can sit on their cells.
    val cells = remember { mutableStateMapOf<HomeLayout.Slot, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<HomeItem?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var hover by remember { mutableStateOf<HomeLayout.Slot?>(null) }

    fun dropSlot(item: HomeItem, center: Offset): HomeLayout.Slot? =
        cells.entries.filter { it.key.row.group == item.group && it.value.inflate(8f).contains(center) }
            .minByOrNull { (it.value.center - center).getDistanceSquared() }?.key

    fun save(next: HomeLayout) { vm.update { it.copy(homeLayout = next.encode()) } }

    SubpageScaffold(I18n.s(R.string.b6_layout), onBack) {
        ScrollBody {
            Text(
                I18n.s(R.string.b6_layout_hint), style = IrisType.secondary, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space8),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IrisDimens.pageGutter)
                    .onGloballyPositioned { origin = it.boundsInRoot().topLeft },
            ) {
                // ---- layer 1: the slots, laid out like the real page ----
                Column {
                    SectionTitle(I18n.s(R.string.b6_layout_top))
                    SlotRow(HomeLayout.Row.TOP, hover) { slot, r -> cells[slot] = r }
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = IrisDimens.space12).height(72.dp)
                            .clip(IrisRadius.mediumShape).background(c.surfaceVariant.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(I18n.s(R.string.b6_layout_chat), style = IrisType.secondary, color = c.textTertiary) }
                    SectionTitle(I18n.s(R.string.b6_layout_composer))
                    Column(
                        Modifier.fillMaxWidth()
                            .border(1.dp, c.outline.copy(alpha = 0.6f), IrisRadius.composerShape)
                            .padding(IrisDimens.space8),
                    ) {
                        SlotRow(HomeLayout.Row.UPPER, hover) { slot, r -> cells[slot] = r }
                        Text(
                            I18n.s(R.string.b6_layout_input), style = IrisType.body, color = c.textTertiary,
                            modifier = Modifier.padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space12),
                        )
                        SlotRow(HomeLayout.Row.LOWER, hover) { slot, r -> cells[slot] = r }
                    }
                }
                // ---- layer 2: the buttons, each on its cell; the one being dragged follows the finger ----
                // no `return@key` here: a non-local return inside key{} breaks R8 (dex).
                for (item in HomeItem.entries) key(item) {
                  val cell = cells[layout.slotOf(item)]
                  if (cell != null) {
                    val rect: Rect = cell
                    val rectNow by rememberUpdatedState(rect)
                    val isDrag = dragging == item
                    val lift by animateFloatAsState(if (isDrag) 1.08f else 1f, label = "lift")
                    Box(
                        Modifier
                            .zIndex(if (isDrag) 2f else 1f)
                            .offset {
                                val base = rect.topLeft - origin + if (isDrag) drag else Offset.Zero
                                IntOffset(base.x.roundToInt(), base.y.roundToInt())
                            }
                            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                            .graphicsLayer { scaleX = lift; scaleY = lift }
                            .pointerInput(item, layout) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = item; drag = Offset.Zero
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag += amount
                                        hover = dropSlot(item, rectNow.center + drag)
                                    },
                                    onDragEnd = {
                                        val target = dropSlot(item, rectNow.center + drag)
                                        if (target != null) save(layout.move(item, target))
                                        dragging = null; drag = Offset.Zero; hover = null
                                    },
                                    onDragCancel = { dragging = null; drag = Offset.Zero; hover = null },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { LayoutChip(item, isDrag) }
                  }
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
            IrisButton(
                I18n.s(R.string.b6_layout_reset), onClick = { save(HomeLayout.DEFAULT) },
                style = IrisButtonStyle.Tonal, enabled = !layout.isDefault,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter),
            )
        }
    }
}

/** one row of [HomeLayout.SLOTS] cells: left side, a gap, right side. */
@Composable
private fun SlotRow(row: HomeLayout.Row, hover: HomeLayout.Slot?, onCell: (HomeLayout.Slot, Rect) -> Unit) {
    val c = Iris.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until HomeLayout.SLOTS) {
            if (i == HomeLayout.SIDE) Spacer(Modifier.width(12.dp))
            val slot = HomeLayout.Slot(row, i)
            val on = hover == slot
            Box(
                Modifier
                    .weight(1f)
                    .height(CELL_H)
                    .clip(IrisRadius.smallShape)
                    .background(if (on) c.accentContainer else c.surfaceVariant.copy(alpha = 0.45f))
                    .border(1.dp, if (on) c.accent else c.outline.copy(alpha = 0.35f), IrisRadius.smallShape)
                    .onGloballyPositioned { onCell(slot, it.boundsInRoot()) },
            )
        }
    }
}

@Composable
private fun LayoutChip(item: HomeItem, lifted: Boolean) {
    val c = Iris.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(3.dp)
            .clip(IrisRadius.smallShape)
            .background(if (lifted) c.accent else c.surface)
            .border(1.dp, c.outline.copy(alpha = 0.6f), IrisRadius.smallShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (lifted) c.onAccent else c.text
        Icon(LayoutText.icon(item), null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            LayoutText.label(item), color = fg, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

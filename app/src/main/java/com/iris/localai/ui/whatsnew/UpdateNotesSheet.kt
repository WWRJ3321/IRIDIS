package com.iris.localai.ui.whatsnew

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.iris.localai.ui.theme.IrisRadius
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.UpdateNotes
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType

/**
 * 此次更新（[history] = false：只当前版本，底部「更新历史」展开全部）/ 更新历史（全部版本）。
 */
@Composable
fun UpdateNotesSheet(current: String, history: Boolean, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf(history) }
    val releases = if (all) UpdateNotes.RELEASES else listOfNotNull(UpdateNotes.find(current))
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(I18n.s(if (all) R.string.v255_notes_history else R.string.v255_notes_title)) {
            IrisButton(I18n.s(R.string.v255_notes_ok), onDismiss, style = IrisButtonStyle.Text, compact = true)
        }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        val currentVer = UpdateNotes.find(current)?.version
        // Beta2：每版一张卡片；更新历史里当前版展开、旧版折叠成一行（版本号 + 条数），点开再看
        var expanded by remember { mutableStateOf(setOfNotNull(currentVer ?: releases.firstOrNull()?.version)) }
        Column(
            Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())
                .padding(horizontal = IrisDimens.pageGutter),
        ) {
            releases.forEachIndexed { i, r ->
                if (i > 0) Spacer(Modifier.height(IrisDimens.space8))
                val open = !all || r.version in expanded
                ReleaseCard(r, isCurrent = r.version == currentVer, open = open, collapsible = all) {
                    expanded = if (r.version in expanded) expanded - r.version else expanded + r.version
                }
            }
            if (!all) {
                Spacer(Modifier.height(IrisDimens.space8))
                IrisButton(I18n.s(R.string.v255_notes_history), { all = true }, style = IrisButtonStyle.Text, compact = true)
            }
            Spacer(Modifier.height(IrisDimens.space12))
        }
    }
}

@Composable
private fun ReleaseCard(r: UpdateNotes.Release, isCurrent: Boolean, open: Boolean, collapsible: Boolean, onToggle: () -> Unit) {
    val c = Iris.colors
    val shape = com.iris.localai.ui.theme.smoothShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(c.surfaceVariant.copy(alpha = if (c.isDark) 0.55f else 0.6f))
            // 不用按压高亮：高亮层跟着展开动画变高，看起来像一块黑影在动
            .then(if (collapsible) Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onToggle) else Modifier)
            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12)
            .animateContentSize(com.iris.localai.ui.motion.IrisMotion.Snappy.size()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.version, style = IrisType.titleSmall, color = c.text, modifier = Modifier.weight(1f, fill = false))
            if (isCurrent) {
                Spacer(Modifier.width(IrisDimens.space8))
                Text(
                    I18n.s(R.string.v30b2_notes_current), style = IrisType.micro, color = c.onAccentContainer,
                    modifier = Modifier.clip(IrisRadius.pillShape).background(c.accentContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (collapsible) {
                if (!open) Text(I18n.s(R.string.v30b2_notes_count, r.items.size), style = IrisType.caption, color = c.textTertiary)
                val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, com.iris.localai.ui.motion.IrisMotion.standard(), label = "notesChevron")
                Icon(com.iris.localai.ui.icons.IrisIcons.ChevronRight, null, tint = c.textTertiary,
                    modifier = Modifier.padding(start = IrisDimens.space4).size(18.dp).graphicsLayer { rotationZ = rot })
            }
        }
        if (open) {
            Group(I18n.s(R.string.v255_notes_feature), r.of(UpdateNotes.Tag.FEATURE), c.accent)
            Group(I18n.s(R.string.v255_notes_exp), r.of(UpdateNotes.Tag.EXPERIMENTAL), c.warning)
            Group(I18n.s(R.string.v255_notes_fix), r.of(UpdateNotes.Tag.FIX), c.success)
        }
    }
}

@Composable
private fun Group(title: String, items: List<UpdateNotes.Item>, tint: androidx.compose.ui.graphics.Color) {
    if (items.isEmpty()) return
    val c = Iris.colors
    Spacer(Modifier.height(IrisDimens.space12))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(IrisDimens.space8))
        Text(title, style = IrisType.caption.copy(fontWeight = IrisType.labelSmall.fontWeight), color = tint)
    }
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space6)) {
            Box(Modifier.padding(top = 8.dp, start = 2.dp, end = 10.dp).size(3.dp).clip(CircleShape).background(c.textTertiary))
            Text(if (item.res != 0) I18n.s(item.res) else item.text, style = IrisType.secondary, color = c.text.copy(alpha = 0.82f))
        }
    }
}

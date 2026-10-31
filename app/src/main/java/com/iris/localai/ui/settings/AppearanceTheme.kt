package com.iris.localai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisPalette
import com.iris.localai.ui.theme.IrisType

/** 2.1.4 外观 copy. */
object AppearanceText {
    const val PALETTE = "整体配色，浅色和深色都会跟着变"
    const val BACKGROUND_NONE = "选一张图片放在对话页后面"
    const val BACKGROUND_SET = "已设置，点按可换一张"
    const val BACKGROUND_FAILED = "这张图片读不出来，换一张试试"
    const val BLUR = "让背景图更柔和，不抢文字"
    const val SCRIM = "越深，文字越清楚"
    const val REMOVE = "恢复为纯色背景"
}

/** 配色: one round swatch per [IrisPalette]; the selected one carries a ring and a check mark. */
@Composable
fun PaletteSwatches(selectedId: String, onSelect: (IrisPalette) -> Unit) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        SettingsRow("配色", subtitle = AppearanceText.PALETTE, icon = com.iris.localai.ui.icons.IrisIcons.Contrast)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val selected = IrisPalette.of(selectedId)
            for (p in IrisPalette.entries) {
                val isSel = p == selected
                Column(
                    Modifier
                        .clip(com.iris.localai.ui.theme.IrisRadius.smallShape)
                        .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(p) })
                        .padding(horizontal = IrisDimens.space4, vertical = IrisDimens.space6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .then(if (isSel) Modifier.border(IrisDimens.space2, p.colors(c.isDark).accent, CircleShape) else Modifier)
                            .padding(IrisDimens.space4)
                            .clip(CircleShape)
                            .background(p.colors(c.isDark).accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) Icon(com.iris.localai.ui.icons.IrisIcons.Check, null, tint = p.colors(c.isDark).onAccent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(IrisDimens.space4))
                    Text(
                        p.label, style = IrisType.caption, textAlign = TextAlign.Center,
                        color = if (isSel) c.text else c.textSecondary,
                    )
                }
            }
        }
    }
}

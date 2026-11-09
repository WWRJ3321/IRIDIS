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
    val PALETTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_56fad7)
    val BACKGROUND_NONE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_90a050)
    val BACKGROUND_SET: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_74d7bb)
    val BACKGROUND_FAILED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_776483)
    val BLUR: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9e3cb6)
    val SCRIM: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9ed865)
    val REMOVE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_621ba4)
}

/** 配色: one round swatch per [IrisPalette]; the selected one carries a ring and a check mark. */
@Composable
fun PaletteSwatches(selectedId: String, onSelect: (IrisPalette) -> Unit) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(bottom = com.iris.localai.ui.theme.IrisSpace.M)) {
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9ad79a), subtitle = AppearanceText.PALETTE, icon = com.iris.localai.ui.icons.IrisIcons.Contrast)
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

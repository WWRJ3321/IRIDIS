package com.iris.localai.ui.settings
import com.iris.localai.ui.motion.selectTint
import com.iris.localai.ui.motion.SelectCheck

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisSpace
import androidx.compose.foundation.horizontalScroll
import com.iris.localai.ui.theme.swatchGradient

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

/** 外观 copy. */
object AppearanceText {
    val PALETTE: String get() = I18n.s(R.string.settings_56fad7)
    val BACKGROUND_NONE: String get() = I18n.s(R.string.settings_90a050)
    val BACKGROUND_SET: String get() = I18n.s(R.string.settings_74d7bb)
    val BACKGROUND_FAILED: String get() = I18n.s(R.string.settings_776483)
    val BLUR: String get() = I18n.s(R.string.settings_9e3cb6)
    val SCRIM: String get() = I18n.s(R.string.settings_9ed865)
    val REMOVE: String get() = I18n.s(R.string.settings_621ba4)
}

/** 配色: one round swatch per [IrisPalette]; the selected one carries a ring and a check mark. */
@Composable
fun PaletteSwatches(selectedId: String, onSelect: (IrisPalette) -> Unit) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(bottom = IrisSpace.M)) {
        SettingsRow(I18n.s(R.string.settings_9ad79a), icon = IrisIcons.Contrast)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = IrisDimens.rowPadding).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
        ) {
            val selected = IrisPalette.of(selectedId)
            for (p in IrisPalette.entries) {
                val isSel = p == selected
                Column(
                    Modifier
                        .clip(IrisRadius.smallShape)
                        .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(p) })
                        .padding(horizontal = IrisDimens.space4, vertical = IrisDimens.space6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val accent = p.colors(c.isDark).accent
                    // 未选中时用同色 0 透明，渐变时不经过黑色
                    val ring = selectTint(if (isSel) accent else accent.copy(alpha = 0f), "paletteRing")
                    Box(
                        Modifier
                            .size(44.dp)
                            .border(IrisDimens.space2, ring, CircleShape)
                            .padding(IrisDimens.space4)
                            .clip(CircleShape)
                            .then(p.swatchGradient?.let { g -> Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(g)) } ?: Modifier.background(p.colors(c.isDark).accent)),
                        contentAlignment = Alignment.Center,
                    ) {
                        SelectCheck(isSel) { Icon(IrisIcons.Check, null, tint = p.colors(c.isDark).onAccent, modifier = Modifier.size(18.dp)) }
                    }
                    Spacer(Modifier.height(IrisDimens.space4))
                    Text(
                        p.label, style = IrisType.caption, textAlign = TextAlign.Center,
                        color = selectTint(if (isSel) c.text else c.textSecondary, "paletteLabel"),
                    )
                }
            }
        }
    }
}

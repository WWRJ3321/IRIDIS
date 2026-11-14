package com.iris.localai.ui.whatsnew

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
 * 2.5.5: 此次更新（[history] = false：只当前版本，底部「更新历史」展开全部）/ 更新历史（全部版本）。
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
        Column(
            Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())
                .padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4),
        ) {
            releases.forEachIndexed { i, r ->
                if (i > 0) Spacer(Modifier.height(IrisDimens.space12))
                Text("IRIDIS ${r.version}", style = IrisType.titleSmall, color = Iris.colors.text)
                Group(I18n.s(R.string.v255_notes_feature), r.of(UpdateNotes.Tag.FEATURE))
                Group(I18n.s(R.string.v255_notes_exp), r.of(UpdateNotes.Tag.EXPERIMENTAL))
                Group(I18n.s(R.string.v255_notes_fix), r.of(UpdateNotes.Tag.FIX))
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
private fun Group(title: String, items: List<UpdateNotes.Item>) {
    if (items.isEmpty()) return
    val c = Iris.colors
    Spacer(Modifier.height(IrisDimens.space8))
    Text(title, style = IrisType.rowStrong, color = c.accent)
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space4)) {
            Text("•  ", style = IrisType.secondary, color = c.textSecondary)
            Text(item.text, style = IrisType.secondary, color = c.textSecondary)
        }
    }
}

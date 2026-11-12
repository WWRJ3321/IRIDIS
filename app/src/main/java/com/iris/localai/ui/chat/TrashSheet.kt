package com.iris.localai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.data.TrashEntry
import com.iris.localai.data.TrashPolicy
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType

/** 2.2.14: 回收站 — deleted chats, restore or delete for good; purged automatically after 30 days. */
@Composable
fun TrashSheet(
    entries: List<TrashEntry>,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onEmpty: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Iris.colors
    var confirmEmpty by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2896bd)) {
            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_288f0c), { confirmEmpty = true }, style = IrisButtonStyle.Text, compact = true, enabled = entries.isNotEmpty(), destructive = true)
        }
        Text(
            com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2b9512, TrashPolicy.KEEP_DAYS),
            style = IrisType.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space4),
        )
        if (entries.isEmpty()) {
            Text(
                com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4a409f), style = IrisType.body, color = c.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(IrisDimens.pageGutter),
            )
        } else {
            LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)) {
                items(entries, key = { it.id }) { e ->
                    // 2.5.3: restored / purged rows fade out and the rest slide up.
                    androidx.compose.foundation.layout.Box(if (com.iris.localai.ui.theme.LocalReduceMotion.current) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.animateItem(fadeInSpec = com.iris.localai.ui.motion.IrisMotion.standard(), placementSpec = com.iris.localai.ui.motion.IrisMotion.Gentle.offset(), fadeOutSpec = com.iris.localai.ui.motion.IrisMotion.standard()), propagateMinConstraints = true) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1ac07a) }, style = IrisType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_cab824, TrashPolicy.daysLeft(e.deletedAt, now)), style = IrisType.caption, color = c.textTertiary)
                        }
                        Spacer(Modifier.width(IrisDimens.space8))
                        Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
                            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c7db6d), { onRestore(e.id) }, style = IrisButtonStyle.Tonal, compact = true)
                            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), { onPurge(e.id) }, style = IrisButtonStyle.Text, compact = true, destructive = true)
                        }
                    }
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = IrisDimens.space12))
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2563fa),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c04740, entries.size),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_288f0c),
            onConfirm = { onEmpty() },
            onDismiss = { confirmEmpty = false },
        )
    }
}

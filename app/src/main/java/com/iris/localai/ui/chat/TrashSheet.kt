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
        SheetHeader("回收站") {
            IrisButton("清空", { confirmEmpty = true }, style = IrisButtonStyle.Text, compact = true, enabled = entries.isNotEmpty(), destructive = true)
        }
        Text(
            "删除的对话保留 ${TrashPolicy.KEEP_DAYS} 天，之后自动删除",
            style = IrisType.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space4),
        )
        if (entries.isEmpty()) {
            Text(
                "回收站是空的", style = IrisType.body, color = c.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(IrisDimens.pageGutter),
            )
        } else {
            LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)) {
                items(entries, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title.ifBlank { "新对话" }, style = IrisType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${TrashPolicy.daysLeft(e.deletedAt, now)} 天后删除", style = IrisType.caption, color = c.textTertiary)
                        }
                        Spacer(Modifier.width(IrisDimens.space8))
                        Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space4)) {
                            IrisButton("恢复", { onRestore(e.id) }, style = IrisButtonStyle.Tonal, compact = true)
                            IrisButton("删除", { onPurge(e.id) }, style = IrisButtonStyle.Text, compact = true, destructive = true)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = IrisDimens.space12))
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "清空回收站？",
            message = "${entries.size} 个对话将被永久删除，无法恢复。",
            confirmText = "清空",
            onConfirm = { onEmpty() },
            onDismiss = { confirmEmpty = false },
        )
    }
}

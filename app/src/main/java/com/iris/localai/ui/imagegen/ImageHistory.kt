package com.iris.localai.ui.imagegen

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.iris.localai.data.Format
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.markdown.ChatImageLoader
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType
import java.io.File

/** 2.4.3: copy of the 生图 history manager. */
object HistoryText {
    val MANAGE get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_manage)
    val DONE get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_done)
    val SELECT_ALL get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_select_all)
    val SELECT_NONE get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_select_none)
    val REUSE get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_reuse)
    val COPY_PROMPT get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_copy_prompt)
    val REUSED get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_reused)
    val COPIED get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_copied)
    val NO_PARAMS get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_no_params)
    val DELETE_ONE_TITLE get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_del_one_title)
    val DELETE_ONE_MSG get() = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_del_one_msg)
    fun deleteManyTitle(n: Int) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_del_many_title, n)
    fun header(n: Int, bytes: Long) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_header, n, Format.bytes(bytes))
    fun selected(n: Int) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_selected, n)
    fun deleteN(n: Int) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_delete_n, n)
    fun negative(t: String) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_negative, t)
    fun steps(n: Int) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_steps, n)
    fun seed(v: Any) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_seed, v.toString())
    fun strength(v: Any) = com.iris.localai.I18n.s(com.iris.localai.R.string.ih243_strength, v.toString())
    fun position(i: Int, n: Int) = "${i + 1} / $n"
}

/** History section header: count and size; 管理 / 全选 / 删除（n）/ 完成 while selecting. */
@Composable
internal fun HistoryHeader(
    count: Int, bytes: Long, selecting: Boolean, selectedCount: Int,
    onManage: () -> Unit, onToggleAll: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = IrisDimens.groupGutter, end = IrisDimens.space8, top = IrisDimens.space24),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selecting) HistoryText.selected(selectedCount) else HistoryText.header(count, bytes),
            style = IrisType.sectionHeader, color = Iris.colors.textTertiary, modifier = Modifier.weight(1f),
        )
        if (selecting) {
            IrisButton(if (selectedCount == count) HistoryText.SELECT_NONE else HistoryText.SELECT_ALL, onClick = onToggleAll, style = IrisButtonStyle.Text, compact = true)
            IrisButton(HistoryText.deleteN(selectedCount), onClick = onDelete, style = IrisButtonStyle.Text, compact = true,
                destructive = true, enabled = selectedCount > 0, icon = IrisIcons.Trash)
            IrisButton(HistoryText.DONE, onClick = onDone, style = IrisButtonStyle.Text, compact = true)
        } else {
            IrisButton(HistoryText.MANAGE, onClick = onManage, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Check)
        }
    }
}

/** One history tile: tap opens (or toggles while selecting), long press starts selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryTile(path: String, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Iris.colors
    Box(
        Modifier.padding(horizontal = IrisDimens.space2).aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Thumb(path, Modifier.fillMaxSize())
        if (selecting) {
            if (selected) Box(Modifier.fillMaxSize().clip(com.iris.localai.ui.theme.IrisRadius.mediumShape).border(2.dp, c.accent, com.iris.localai.ui.theme.IrisRadius.mediumShape))
            Box(
                Modifier.align(Alignment.TopEnd).padding(IrisDimens.space6).size(22.dp).clip(CircleShape)
                    .background(if (selected) c.accent else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(IrisIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}

/**
 * Full-screen viewer of history image [index]: the picture, its parameters, ‹ › to the neighbours,
 * and 保存 / 分享 / 发到对话 / 用这组参数 / 复制提示词 / 删除.
 */
@Composable
internal fun HistoryViewer(
    paths: List<String>, index: Int, vm: ImageGenViewModel,
    onIndex: (Int) -> Unit, onClose: () -> Unit, onSendToChat: (String) -> Unit,
    onReused: () -> Unit, onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    val i = index.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
    val path = paths.getOrNull(i) ?: run { onClose(); return }
    val params by produceState<ImageGenFiles.Params?>(null, path) { value = vm.params(path) }
    val file = File(path)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(IrisIcons.Close, onClose)
                Text(HistoryText.position(i, paths.size), style = IrisType.labelSmall, color = Color.White, modifier = Modifier.weight(1f).padding(start = IrisDimens.space8))
                Text(file.name.removeSuffix(".png"), style = IrisType.caption, color = Color.White.copy(alpha = 0.6f))
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Thumb(path, Modifier.fillMaxSize(), maxSide = 1536, fit = true, plain = true)
                if (i > 0) Box(Modifier.align(Alignment.CenterStart).padding(IrisDimens.space8)) { RoundIcon(IrisIcons.ChevronLeft) { onIndex(i - 1) } }
                if (i < paths.size - 1) Box(Modifier.align(Alignment.CenterEnd).padding(IrisDimens.space8)) { RoundIcon(IrisIcons.ChevronRight) { onIndex(i + 1) } }
            }
            Column(Modifier.fillMaxWidth().background(Iris.colors.surface).padding(IrisDimens.space12)) {
                val p = params
                Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    if (p == null) {
                        Text(HistoryText.NO_PARAMS, style = IrisType.caption, color = Iris.colors.textTertiary)
                    } else {
                        Text(p.prompt, style = IrisType.secondary, color = Iris.colors.text)
                        if (p.negative.isNotBlank()) Text(HistoryText.negative(p.negative), style = IrisType.caption, color = Iris.colors.textSecondary, modifier = Modifier.padding(top = IrisDimens.space4))
                        Text(metaLine(p, file), style = IrisType.caption, color = Iris.colors.textTertiary, modifier = Modifier.padding(top = IrisDimens.space4))
                    }
                }
                Spacer(Modifier.height(IrisDimens.space8))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                    if (p != null) {
                        IrisButton(HistoryText.REUSE, icon = IrisIcons.Reload, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                            vm.reuse(p); toast(HistoryText.REUSED); onReused()
                        })
                        IrisButton(HistoryText.COPY_PROMPT, icon = IrisIcons.Copy, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", p.prompt))
                            toast(HistoryText.COPIED)
                        })
                    }
                    IrisButton(ImageGenText.SAVE, icon = IrisIcons.Download, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                        val ok = runCatching { ChatImageLoader.saveToGallery(context, file.readBytes()) }.getOrDefault(false)
                        toast(if (ok) com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_e8561b) else com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_6de920))
                    })
                    IrisButton(ImageGenText.SHARE, icon = IrisIcons.Share, style = IrisButtonStyle.Tonal, compact = true, onClick = {
                        runCatching { ChatImageLoader.share(context, file.readBytes()) }
                    })
                    IrisButton(ImageGenText.TO_CHAT, icon = IrisIcons.Chat, style = IrisButtonStyle.Tonal, compact = true, onClick = { onClose(); onSendToChat(path) })
                    IrisButton(ImageGenText.DELETE, icon = IrisIcons.Trash, style = IrisButtonStyle.Tonal, compact = true, destructive = true, onClick = { onDelete(path) })
                }
            }
        }
    }
}

/** "Z-Image Turbo · 512×512 · 8 steps · CFG 1.0 · seed 123 · 1.2 MB" (localized). */
private fun metaLine(p: ImageGenFiles.Params, file: File): String = listOfNotNull(
    p.modelId?.let { id -> ImageCatalog.byId(id)?.name ?: id },
    if (p.width != null && p.height != null) "${p.width}×${p.height}" else null,
    p.steps?.let { HistoryText.steps(it) },
    p.cfg?.let { "CFG $it" },
    p.seed?.let { HistoryText.seed(it) },
    p.strength?.let { HistoryText.strength(it) },
    Format.bytes(file.length()),
).joinToString(" · ")

@Composable
private fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

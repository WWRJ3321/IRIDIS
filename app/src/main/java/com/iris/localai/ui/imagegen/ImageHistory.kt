package com.iris.localai.ui.imagegen

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import com.iris.localai.ui.motion.SelectCheck
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.motion.enterOnce
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.setValue
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

/** copy of the 生图 history manager. */
object HistoryText {
    val MANAGE get() = I18n.s(R.string.ih243_manage)
    val DONE get() = I18n.s(R.string.ih243_done)
    val SELECT_ALL get() = I18n.s(R.string.ih243_select_all)
    val SELECT_NONE get() = I18n.s(R.string.ih243_select_none)
    val REUSE get() = I18n.s(R.string.ih243_reuse)
    val COPY_PROMPT get() = I18n.s(R.string.ih243_copy_prompt)
    val REUSED get() = I18n.s(R.string.ih243_reused)
    val COPIED get() = I18n.s(R.string.ih243_copied)
    val NO_PARAMS get() = I18n.s(R.string.ih243_no_params)
    val DELETE_ONE_TITLE get() = I18n.s(R.string.ih243_del_one_title)
    val DELETE_ONE_MSG get() = I18n.s(R.string.ih243_del_one_msg)
    fun deleteManyTitle(n: Int) = I18n.s(R.string.ih243_del_many_title, n)
    fun header(n: Int, bytes: Long) = I18n.s(R.string.ih243_header, n, Format.bytes(bytes))
    fun selected(n: Int) = I18n.s(R.string.ih243_selected, n)
    fun deleteN(n: Int) = I18n.s(R.string.ih243_delete_n, n)
    fun negative(t: String) = I18n.s(R.string.ih243_negative, t)
    fun steps(n: Int) = I18n.s(R.string.ih243_steps, n)
    fun seed(v: Any) = I18n.s(R.string.ih243_seed, v.toString())
    fun strength(v: Any) = I18n.s(R.string.ih243_strength, v.toString())
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
            val ring = com.iris.localai.ui.motion.selectTint(if (selected) c.accent else Color.Transparent, "tileRing")
            Box(Modifier.fillMaxSize().clip(IrisRadius.mediumShape).border(2.dp, ring, IrisRadius.mediumShape))
            Box(
                Modifier.align(Alignment.TopEnd).padding(IrisDimens.space6).size(22.dp).clip(CircleShape)
                    .background(com.iris.localai.ui.motion.selectTint(if (selected) c.accent else Color.Black.copy(alpha = 0.35f), "tileDot"))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { SelectCheck(selected) { Icon(IrisIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) } }
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
        Column(Modifier.fillMaxSize().enterOnce().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            // r5：顶栏只留关闭、序号、「参数」开关（文件名去掉）；图可双指缩放、双击放大、放大后拖动
            var info by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(IrisIcons.Close, onClose)
                Text(HistoryText.position(i, paths.size), style = IrisType.labelSmall, color = Color.White, modifier = Modifier.weight(1f).padding(start = IrisDimens.space8))
                RoundIcon(IrisIcons.Info) { info = !info }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ZoomImage(path, Modifier.fillMaxSize())
                if (i > 0) Box(Modifier.align(Alignment.CenterStart).padding(IrisDimens.space8)) { RoundIcon(IrisIcons.ChevronLeft) { onIndex(i - 1) } }
                if (i < paths.size - 1) Box(Modifier.align(Alignment.CenterEnd).padding(IrisDimens.space8)) { RoundIcon(IrisIcons.ChevronRight) { onIndex(i + 1) } }
            }
            // Beta4 r2：详情不再是黑页下面突兀的一块白底——改成同色系深色面板（顶部圆角、细分隔线），
            // 「i」开关用展开/收起动画；提示词可长按选择，旁边一个复制按钮；操作改成一排图标+短标签，全部一屏放下不用横滑。
            val p = params
            val copy: (String) -> Unit = { t ->
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", t))
                toast(HistoryText.COPIED)
            }
            // 面板打开时从底部滑上来（减少动态效果时直接出现）
            val rmOpen = com.iris.localai.ui.theme.LocalReduceMotion.current
            val rise = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (rmOpen) 0f else 1f) }
            androidx.compose.runtime.LaunchedEffect(Unit) { rise.animateTo(0f, com.iris.localai.ui.motion.IrisMotion.Gentle.float()) }
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer { translationY = size.height * rise.value; alpha = 1f - rise.value * 0.6f }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(ViewerChrome.PANEL)
                    .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
            ) {
                val rm = com.iris.localai.ui.theme.LocalReduceMotion.current
                androidx.compose.animation.AnimatedVisibility(
                    info,
                    enter = if (rm) androidx.compose.animation.EnterTransition.None else com.iris.localai.ui.motion.IrisMotion.expand(),
                    exit = if (rm) androidx.compose.animation.ExitTransition.None else com.iris.localai.ui.motion.IrisMotion.collapse(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space12)) {
                        if (p == null) {
                            Text(HistoryText.NO_PARAMS, style = IrisType.caption, color = ViewerChrome.TEXT3)
                        } else {
                            Row(verticalAlignment = Alignment.Top) {
                                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).heightIn(max = 132.dp).verticalScroll(rememberScrollState())) {
                                    Column {
                                        Text(p.prompt, style = IrisType.caption.copy(lineHeight = 20.sp), color = ViewerChrome.TEXT1)
                                        if (p.negative.isNotBlank()) Text(HistoryText.negative(p.negative), style = IrisType.micro, color = ViewerChrome.TEXT2, modifier = Modifier.padding(top = IrisDimens.space6))
                                    }
                                }
                                Box(
                                    Modifier.padding(start = IrisDimens.space4).size(40.dp).clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.10f)).clickable { copy(p.prompt) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(IrisIcons.Copy, HistoryText.COPY_PROMPT, tint = Color.White, modifier = Modifier.size(16.dp)) }
                            }
                            Text(metaLine(p, file), style = IrisType.micro, color = ViewerChrome.TEXT3, maxLines = 2, modifier = Modifier.padding(top = IrisDimens.space8))
                        }
                        Box(Modifier.padding(top = IrisDimens.space12).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.10f)))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (p != null) ViewerAction(IrisIcons.Reload, HistoryText.REUSE) { vm.reuse(p); toast(HistoryText.REUSED); onReused() }
                    ViewerAction(IrisIcons.Download, ImageGenText.SAVE) {
                        val ok = runCatching { ChatImageLoader.saveToGallery(context, file.readBytes()) }.getOrDefault(false)
                        toast(if (ok) I18n.s(R.string.imagegen_e8561b) else I18n.s(R.string.imagegen_6de920))
                    }
                    ViewerAction(IrisIcons.Share, ImageGenText.SHARE) { runCatching { ChatImageLoader.share(context, file.readBytes()) } }
                    ViewerAction(IrisIcons.Chat, ImageGenText.TO_CHAT) { onClose(); onSendToChat(path) }
                    ViewerAction(IrisIcons.Trash, ImageGenText.DELETE, tint = ViewerChrome.DANGER) { onDelete(path) }
                }
            }
        }
    }
}

/** 看图页深色面板的配色（与黑底同系，不跟随浅色主题）。 */
private object ViewerChrome {
    val PANEL = Color(0xFF16171A)
    val TEXT1 = Color.White.copy(alpha = 0.92f)
    val TEXT2 = Color.White.copy(alpha = 0.62f)
    val TEXT3 = Color.White.copy(alpha = 0.45f)
    val DANGER = Color(0xFFFF6B6B)
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clip(IrisRadius.mediumShape).clickable(onClick = onClick).padding(vertical = IrisDimens.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(label, style = IrisType.micro, color = if (tint == Color.White) ViewerChrome.TEXT2 else tint, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = IrisDimens.space4))
    }
}

/** r5：看图页的缩放（纯逻辑，单测 V300B3R5Test）。 */
object ImageZoom {
    const val MIN = 1f
    const val MAX = 5f
    const val DOUBLE_TAP = 2.5f
    fun scale(cur: Float, zoom: Float): Float = (cur * zoom).coerceIn(MIN, MAX)
    /** 放大后能拖动的范围：图边不离开视口；1 倍时归零。 */
    fun clampPan(x: Float, y: Float, scale: Float, w: Float, h: Float): Pair<Float, Float> {
        if (scale <= 1f) return 0f to 0f
        val mx = w * (scale - 1f) / 2f; val my = h * (scale - 1f) / 2f
        return x.coerceIn(-mx, mx) to y.coerceIn(-my, my)
    }
    fun doubleTap(cur: Float): Float = if (cur > 1.01f) MIN else DOUBLE_TAP
}

@Composable
private fun ZoomImage(path: String, modifier: Modifier) {
    var scale by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var tx by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var ty by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = {
                    scale = ImageZoom.doubleTap(scale)
                    if (scale <= 1f) { tx = 0f; ty = 0f }
                })
            }
            .pointerInput(path) {
                detectTransformGestures { _: androidx.compose.ui.geometry.Offset, pan: androidx.compose.ui.geometry.Offset, zoom: Float, _: Float ->
                    scale = ImageZoom.scale(scale, zoom)
                    val (x, y) = ImageZoom.clampPan(tx + pan.x, ty + pan.y, scale, size.width.toFloat(), size.height.toFloat())
                    tx = x; ty = y
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Thumb(path, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = tx; translationY = ty }, maxSide = 2048, fit = true, plain = true)
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

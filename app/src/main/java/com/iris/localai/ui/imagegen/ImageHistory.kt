package com.iris.localai.ui.imagegen

import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    // Beta4 r3b：不再开独立对话框窗口，直接盖在生图页上（窗口本身是全面屏，状态栏/手势条位置可靠）。
    androidx.activity.compose.BackHandler(onBack = onClose)
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
    androidx.compose.runtime.DisposableEffect(activity) {
        val w = activity?.window
        val ctl = w?.let { androidx.core.view.WindowInsetsControllerCompat(it, it.decorView) }
        val oldS = ctl?.isAppearanceLightStatusBars; val oldN = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false; ctl?.isAppearanceLightNavigationBars = false
        onDispose { if (ctl != null) { oldS?.let { ctl.isAppearanceLightStatusBars = it }; oldN?.let { ctl.isAppearanceLightNavigationBars = it } } }
    }
    run {
        val rm = com.iris.localai.ui.theme.LocalReduceMotion.current
        // 「i」= 详情面板显隐；打开看图时面板从底部滑上来。
        val info = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { info.targetState = true }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
            // 图铺满整屏（顶栏、面板浮在上面），双指缩放、双击放大、放大后拖动
            ZoomImage(path, Modifier.fillMaxSize())
            // 顶栏：上方渐变压暗，状态栏下面
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(IrisIcons.Close, onClose)
                Text(HistoryText.position(i, paths.size), style = IrisType.labelSmall, color = Color.White, modifier = Modifier.weight(1f).padding(start = IrisDimens.space8))
                RoundIcon(IrisIcons.Info) { info.targetState = !info.targetState }
            }
            if (i > 0) Box(Modifier.align(Alignment.CenterStart).padding(IrisDimens.space8)) { RoundIcon(IrisIcons.ChevronLeft) { onIndex(i - 1) } }
            if (i < paths.size - 1) Box(Modifier.align(Alignment.CenterEnd).padding(IrisDimens.space8)) { RoundIcon(IrisIcons.ChevronRight) { onIndex(i + 1) } }

            val p = params
            val copy: (String) -> Unit = { t ->
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", t))
                toast(HistoryText.COPIED)
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = info,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (rm) androidx.compose.animation.fadeIn() else
                    androidx.compose.animation.slideInVertically(com.iris.localai.ui.motion.IrisMotion.Gentle.offset()) { it } +
                        androidx.compose.animation.fadeIn(),
                exit = if (rm) androidx.compose.animation.fadeOut() else
                    androidx.compose.animation.slideOutVertically(com.iris.localai.ui.motion.IrisMotion.Smooth.offset()) { it } +
                        androidx.compose.animation.fadeOut(),
            ) {
                // 深色面板一直铺到屏幕底边（手势条下面也是面板色），内容在手势条上方
                Column(
                    Modifier.fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(ViewerChrome.PANEL)
                        .navigationBarsPadding()
                        .padding(start = IrisDimens.space16, end = IrisDimens.space16, top = IrisDimens.space8, bottom = IrisDimens.space8),
                ) {
                    if (p == null) {
                        Text(HistoryText.NO_PARAMS, style = IrisType.caption, color = ViewerChrome.TEXT3, modifier = Modifier.padding(vertical = IrisDimens.space8))
                    } else {
                        // 标题行：「提示词」+ 复制（一直可见，不会被长文字挤掉）
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.s(R.string.b4r3_prompt), style = IrisType.micro, color = ViewerChrome.TEXT3, modifier = Modifier.weight(1f))
                            Row(
                                Modifier.height(40.dp).clip(com.iris.localai.ui.theme.IrisRadius.pillShape).clickable { copy(p.prompt) }
                                    .padding(horizontal = IrisDimens.space12),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(IrisIcons.Copy, null, tint = ViewerChrome.TEXT1, modifier = Modifier.size(16.dp))
                                Text(I18n.s(R.string.b4r3_copy), style = IrisType.micro, color = ViewerChrome.TEXT1, modifier = Modifier.padding(start = IrisDimens.space6))
                            }
                        }
                        // 提示词：默认 3 行带省略号，点一下展开（展开后可滚动、长按选择）
                        var open by androidx.compose.runtime.saveable.rememberSaveable(path) { androidx.compose.runtime.mutableStateOf(false) }
                        Box(
                            Modifier.fillMaxWidth()
                                .then(if (rm) Modifier else Modifier.animateContentSize(com.iris.localai.ui.motion.IrisMotion.Gentle.size()))
                                .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { open = !open },
                        ) {
                            if (open) {
                                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                    Column {
                                        Text(p.prompt, style = IrisType.caption.copy(lineHeight = 20.sp), color = ViewerChrome.TEXT1)
                                        if (p.negative.isNotBlank()) Text(HistoryText.negative(p.negative), style = IrisType.micro, color = ViewerChrome.TEXT2, modifier = Modifier.padding(top = IrisDimens.space6))
                                    }
                                }
                            } else {
                                Text(p.prompt, style = IrisType.caption.copy(lineHeight = 20.sp), color = ViewerChrome.TEXT1, maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        Text(metaLine(p, file), style = IrisType.micro, color = ViewerChrome.TEXT3, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = IrisDimens.space8))
                    }
                    Box(Modifier.padding(vertical = IrisDimens.space8).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.10f)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (p != null) ViewerAction(IrisIcons.Reload, HistoryText.REUSE) { vm.reuse(p); toast(HistoryText.REUSED); onReused() }
                        ViewerAction(IrisIcons.Download, ImageGenText.SAVE) {
                            val ok = runCatching { ChatImageLoader.saveToGallery(context, file.readBytes()) }.getOrDefault(false)
                            toast(if (ok) I18n.s(R.string.imagegen_e8561b) else I18n.s(R.string.imagegen_6de920))
                        }
                        // Beta5：放大 2×/4×（第一次用时下 17.9 MB 模型），结果作为新图存进历史并切过去
                        val ui by vm.ui.collectAsState()
                        var upMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        val busy = ui.upscaling != null
                        val upLabel = when {
                            ui.upscaling == null -> I18n.s(R.string.b5_upscale)
                            ui.upscaleDownloading -> I18n.s(R.string.b5_upscale_dl, (ui.upscaleProgress * 100).toInt())
                            else -> I18n.s(R.string.b5_upscale_run, (ui.upscaleProgress * 100).toInt())
                        }
                        // 包里没有 libiris_upscale.so 时不显示（免得先下 18 MB 再报错）
                        if (androidx.compose.runtime.remember { com.iris.localai.imagegen.UpscaleNative.available }) Box {
                            ViewerAction(IrisIcons.Expand, upLabel) { if (!busy) upMenu = true }
                            com.iris.localai.ui.components.IrisMenu(upMenu, { upMenu = false }) {
                                for (k in listOf(2, 4)) com.iris.localai.ui.components.IrisMenuItem(I18n.s(R.string.b5_upscale_x, k), IrisIcons.Expand) {
                                    upMenu = false
                                    vm.upscale(path, k) { np -> if (np != null) { toast(I18n.s(R.string.b5_upscale_done)); onIndex(0) } }
                                }
                            }
                        }
                        ViewerAction(IrisIcons.Share, ImageGenText.SHARE) { runCatching { ChatImageLoader.share(context, file.readBytes()) } }
                        ViewerAction(IrisIcons.Chat, ImageGenText.TO_CHAT) { onClose(); onSendToChat(path) }
                        ViewerAction(IrisIcons.Trash, ImageGenText.DELETE, tint = ViewerChrome.DANGER) { onDelete(path) }
                    }
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
        Modifier.width(58.dp).clip(IrisRadius.mediumShape).clickable(onClick = onClick).padding(vertical = IrisDimens.space6),
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

package com.iris.localai.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.translate.Translator
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.theme.IrisType

/**
 * 2.7.0 长按消息文本的选择工具条（替换系统的「复制 / 全选」）：深色胶囊，横排
 * 复制 · 全选 · 引用 · 朗读 · 翻译 · 分享。只用于消息正文；输入框仍是系统菜单。
 *
 * Compose 的 [TextToolbar] 只给「复制」「全选」回调，拿选中文字的办法是：在选择区域内换一个
 * [ClipboardManager]，引用/朗读/翻译/分享时让「复制」写到这里而不是系统剪贴板。
 */
class SelectionActions(
    val onQuote: (String) -> Unit,
    val onSpeak: (String) -> Unit,
    /** null = 没有翻译功能，不显示该项。 */
    val onTranslate: ((String) -> Unit)?,
)

/** ChatScreen 提供；没有提供时消息正文仍用系统选择菜单。 */
val LocalSelectionActions = staticCompositionLocalOf<SelectionActions?> { null }

object SelectionMenuText {
    /** 选中文字作为引用（每行 `> `），接在输入框原有内容后面，光标落在引用下方。 */
    fun quote(sel: String): String =
        sel.trim().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" } + "\n\n"

    fun quoteInto(input: String, sel: String): String {
        if (sel.isBlank()) return input
        val q = quote(sel)
        return when {
            input.isBlank() -> q
            input.endsWith("\n\n") -> input + q
            input.endsWith("\n") -> input + "\n" + q
            else -> input + "\n\n" + q
        }
    }
}

/** 选择工具条请求（位置 + Compose 给的回调）。 */
internal class IrisTextToolbar : TextToolbar {
    class Req(val rect: Rect, val onCopy: (() -> Unit)?, val onSelectAll: (() -> Unit)?)

    var request by mutableStateOf<Req?>(null)
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        request = Req(rect, onCopyRequested, onSelectAllRequested)
    }

    override fun hide() { request = null }

    override val status: TextToolbarStatus
        get() = if (request != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
}

/** 选择区域内的剪贴板：平时直通系统；[grab] 期间截下「复制」的文字。 */
internal class GrabClipboard(private val system: ClipboardManager) : ClipboardManager {
    private var capture = false
    private var captured: String? = null

    override fun setText(annotatedString: AnnotatedString) {
        if (capture) captured = annotatedString.text else system.setText(annotatedString)
    }

    override fun getText(): AnnotatedString? = system.getText()

    fun grab(copy: () -> Unit): String? {
        capture = true
        captured = null
        try { copy() } finally { capture = false }
        return captured
    }
}

/** 工具条放在选区上方（放不下就放下方），水平居中并夹在屏幕内。 */
internal class BarPosition(private val rect: Rect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (rect.center.x - popupContentSize.width / 2f).toInt().coerceIn(0, maxX)
        val above = rect.top.toInt() - popupContentSize.height - gap
        val y = if (above >= gap) above else (rect.bottom.toInt() + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/** 消息正文的可选择区域，带 App 自己的选择工具条。 */
@Composable
fun MessageSelection(content: @Composable () -> Unit) {
    val actions = LocalSelectionActions.current
    if (actions == null) { SelectionContainer { content() }; return }
    val system = LocalClipboardManager.current
    val context = LocalContext.current
    val grab = remember(system) { GrabClipboard(system) }
    val toolbar = remember { IrisTextToolbar() }
    CompositionLocalProvider(LocalClipboardManager provides grab, LocalTextToolbar provides toolbar) {
        SelectionContainer { content() }
    }
    val req = toolbar.request ?: return
    fun selected(): String? = req.onCopy?.let { c -> grab.grab(c) }?.takeIf { it.isNotBlank() }
    fun withText(act: (String) -> Unit) { val t = selected(); toolbar.hide(); if (t != null) act(t) }
    val items = ArrayList<BarItem>()
    val copy = req.onCopy
    val selectAll = req.onSelectAll
    if (copy != null) items.add(BarItem(I18n.s(R.string.sel27_copy)) { copy(); toolbar.hide() })
    if (selectAll != null) items.add(BarItem(I18n.s(R.string.sel27_all)) { selectAll() })
    if (copy != null) {
        items.add(BarItem(I18n.s(R.string.sel27_quote)) { withText(actions.onQuote) })
        items.add(BarItem(I18n.s(R.string.sel27_speak)) { withText(actions.onSpeak) })
        val tr = actions.onTranslate
        if (tr != null) items.add(BarItem(I18n.s(R.string.sel27_translate)) { withText(tr) })
        items.add(BarItem(I18n.s(R.string.sel27_share)) {
            withText { t ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        })
    }
    if (items.isEmpty()) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(req.rect, gap) { BarPosition(req.rect, gap) },
        onDismissRequest = { toolbar.hide() },
        properties = PopupProperties(focusable = false),
    ) {
        SelectionBar(items)
    }
}

/** 深色圆角胶囊 + 横排文字按钮。 */
@Composable
private fun SelectionBar(items: List<BarItem>) {
    Row(
        Modifier
            .padding(horizontal = IrisSpace.S)
            .clip(IrisRadius.pillShape)
            .background(BAR_COLOR)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = IrisSpace.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Box(
                Modifier.clip(IrisRadius.pillShape).clickable(onClick = item.onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(item.label, style = IrisType.label, color = Color.White, maxLines = 1)
            }
        }
    }
}

internal class BarItem(val label: String, val onClick: () -> Unit)

private val BAR_COLOR = Color(0xF2232326)

/** 选中文字的翻译结果（流式），可把译文引用进输入框。 */
@Composable
internal fun SelectionTranslateDialog(text: String, translator: Translator, onDismiss: () -> Unit, onQuote: (String) -> Unit) {
    var out by remember(text) { mutableStateOf("") }
    var failed by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        try { translator.translate(text).collect { if (it.isNotEmpty()) out = it } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { failed = e.message ?: I18n.s(R.string.chat_032ee1) }
    }
    IrisDialog(
        onDismiss = onDismiss,
        title = I18n.s(R.string.sel27_translate),
        confirmText = I18n.s(R.string.sel27_quote),
        confirmEnabled = out.isNotBlank(),
        onConfirm = { onQuote(out); onDismiss() },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(failed ?: out.ifEmpty { "…" }, style = IrisType.body, color = Iris.colors.text)
            }
        }
    }
}

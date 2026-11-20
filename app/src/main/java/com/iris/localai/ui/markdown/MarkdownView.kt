package com.iris.localai.ui.markdown

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.ui.theme.IrisFontSize
import com.iris.localai.ui.theme.IrisSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisColors
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalChatTextScale
import com.iris.localai.ui.theme.chatScaled
import kotlinx.coroutines.delay

/**
 * Renders Markdown. While [streaming], parsing is incremental (only the tail after the last
 * finished block is re-parsed per token, finished blocks keep their instances and skip
 * recomposition). Finished messages come from a shared LRU cache, so scrolling never re-parses.
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color? = null,
    streaming: Boolean = false,
) {
    val incremental = remember { IncrementalMarkdown() }
    val blocks = remember(text, streaming) { if (streaming) incremental.parse(text) else MarkdownCache.get(text) }
    val c = Iris.colors
    val color = textColor ?: c.text
    // a finished reply that is one whole HTML document previews as a page (when 渲染网页代码 is on).
    val wholeHtml = remember(text, streaming) { !streaming && HtmlBlocks.wholeDocument(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(IrisSpace.S)) {
        if (wholeHtml) HtmlCodeBlock(text.trim(), HtmlBlocks.Kind.HTML) { blocks.forEach { Block(it, c, color) } }
        else blocks.forEach { Block(it, c, color) }
    }
}

/** Builds (and remembers) the styled inline text of a block; keyed by content so equal text is never rebuilt. */
@Composable
private fun rememberInline(text: String, c: IrisColors): AnnotatedString {
    val f = LocalChatTextScale.current
    return remember(text, c, f) { inline(text, c, f) }
}

@Composable
private fun Block(block: MdBlock, c: IrisColors, color: androidx.compose.ui.graphics.Color) {
    val f = LocalChatTextScale.current // 对话文字大小（只在对话页 ≠ 1）
    val body = remember(color, f) { IrisType.message.copy(color = color).chatScaled(f) }
    when (block) {
        is MdBlock.Heading -> {
            val head = when (block.level) { 1 -> IrisType.mdH1; 2 -> IrisType.mdH2; 3 -> IrisType.mdH3; else -> IrisType.message.copy(fontWeight = FontWeight.SemiBold) }
            Text(
                rememberInline(block.text, c),
                style = head.copy(color = color).chatScaled(f),
                modifier = Modifier.padding(top = if (block.level <= 2) IrisDimens.space6 else IrisDimens.space2),
            )
        }
        is MdBlock.Paragraph -> Text(rememberInline(block.text, c), style = body)
        is MdBlock.Code -> when (val kind = HtmlBlocks.kindOf(block)) {
            null -> CodeBlock(block, c)
            else -> HtmlCodeBlock(block.code, kind) { CodeBlock(block, c) }
        }
        is MdBlock.Image -> MarkdownImage(block)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(IrisDimens.space2)).background(c.outline))
            Column(Modifier.padding(start = IrisDimens.space12), verticalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                block.blocks.forEach { Block(it, c, c.textSecondary) }
            }
        }
        is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 18).dp)) {
            when (block.checked) {
                null -> Text(
                    block.marker, style = body.copy(color = c.textSecondary),
                    modifier = Modifier.widthIn(min = 22.dp),
                )
                else -> Icon(
                    if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                    tint = c.textSecondary, modifier = Modifier.padding(top = IrisDimens.space4, end = IrisDimens.space6).size(18.dp),
                )
            }
            Text(rememberInline(block.text, c), style = body, modifier = Modifier.weight(1f))
        }
        is MdBlock.Table -> TableBlock(block, c)
        is MdBlock.Math -> MathBlock(block, c, color)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4).height(1.dp).background(c.outline))
    }
}

private fun inline(text: String, c: IrisColors, f: Float = 1f): AnnotatedString = buildAnnotatedString {
    fun emit(nodes: List<MdInline>) {
        nodes.forEach { n ->
            when (n) {
                is MdInline.Text -> append(n.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { emit(n.children) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(n.children) }
                is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { emit(n.children) }
                is MdInline.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = IrisFontSize.S.chatScaled(f), background = c.surfaceVariant),
                ) { append("\u2009" + n.text + "\u2009") }
                is MdInline.Math -> withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) { append(TexText.render(n.tex)) }
                is MdInline.Link -> withLink(
                    LinkAnnotation.Url(
                        n.url,
                        TextLinkStyles(SpanStyle(color = c.onAccentContainer, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(n.text) }
            }
        }
    }
    emit(InlineParser.parse(text))
}

@Composable
private fun CodeBlock(block: MdBlock.Code, c: IrisColors) {
    val clipboard = LocalClipboardManager.current
    val cf = LocalChatTextScale.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val shape = IrisRadius.mediumShape
    Column(Modifier.fillMaxWidth().clip(shape).background(c.codeBackground).border(IrisDimens.hairline, c.outline, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(start = IrisSpace.M, end = IrisDimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language.ifBlank { "text" }, color = c.textTertiary, fontSize = IrisFontSize.XS,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
            )
            PressableIcon(
                if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = if (copied) I18n.s(R.string.chat_52e6ab) else I18n.s(R.string.markdown_224996), size = 36.dp, iconSize = IrisDimens.space16, tint = c.textSecondary,
            ) {
                clipboard.setText(AnnotatedString(block.code))
                copied = true
            }
        }
        // 长代码默认折叠，回复里不再一屏屏都是代码；生成中不折叠
        val lines = remember(block.code) { block.code.count { it == '\n' } + 1 }
        val foldable = block.closed && lines > CODE_FOLD_LINES
        var expanded by remember(block.code) { mutableStateOf(false) }
        val shown = remember(block.code, expanded, foldable) {
            if (foldable && !expanded) block.code.lineSequence().take(CODE_PEEK_LINES).joinToString("\n") else block.code
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = IrisSpace.M, end = IrisSpace.M, bottom = if (foldable) IrisDimens.space4 else IrisDimens.space12)) {
            Text(
                shown, fontFamily = FontFamily.Monospace, fontSize = IrisFontSize.XS.chatScaled(cf), lineHeight = 20.sp.chatScaled(cf), color = c.text,
                softWrap = false,
            )
        }
        if (foldable) {
            Text(
                if (expanded) I18n.s(R.string.md258_fold) else I18n.s(R.string.md258_unfold, lines),
                color = c.onAccentContainer, fontSize = IrisFontSize.S,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(start = IrisSpace.M, end = IrisSpace.M, top = IrisDimens.space4, bottom = IrisDimens.space12),
            )
        }
    }
}

/** 超过这么多行的代码块折叠，只露前 [CODE_PEEK_LINES] 行。 */
internal const val CODE_FOLD_LINES = 40
internal const val CODE_PEEK_LINES = 20

/** 独立公式：转成 Unicode 文本，居中，过宽可横向滑动；长按复制原 LaTeX 由消息菜单负责。 */
@Composable
private fun MathBlock(block: MdBlock.Math, c: IrisColors, color: androidx.compose.ui.graphics.Color) {
    val text = remember(block.tex) { TexText.render(block.tex) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = IrisDimens.space4), contentAlignment = Alignment.Center) {
        Text(
            text, color = color, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
            fontSize = IrisFontSize.M.chatScaled(LocalChatTextScale.current), lineHeight = 26.sp.chatScaled(LocalChatTextScale.current), textAlign = TextAlign.Center, softWrap = false,
        )
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, c: IrisColors) {
    val cols = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    val rows = listOf(block.header) + block.rows
    val shape = IrisRadius.mediumShape
    Box(Modifier.fillMaxWidth().clip(shape).border(IrisDimens.hairline, c.outline, shape).horizontalScroll(rememberScrollState())) {
        GridLayout(columns = cols, headerColor = c.surfaceVariant, lineColor = c.outline) {
            rows.forEachIndexed { r, row ->
                for (col in 0 until cols) {
                    val align = when (block.align.getOrNull(col)) {
                        MdBlock.Align.CENTER -> TextAlign.Center
                        MdBlock.Align.END -> TextAlign.End
                        else -> TextAlign.Start
                    }
                    Text(
                        rememberInline(row.getOrElse(col) { "" }, c),
                        style = TextStyle(
                            fontSize = IrisFontSize.S, lineHeight = 20.sp, color = c.text, textAlign = align,
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                        ).chatScaled(),
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = IrisDimens.space12, vertical = IrisSpace.S),
                    )
                }
            }
        }
    }
}

/** Lays children out row-major in a grid where every column is as wide as its widest cell. */
@Composable
private fun GridLayout(
    columns: Int,
    headerColor: androidx.compose.ui.graphics.Color,
    lineColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    // Row heights are known after layout; the draw pass of the same frame reads them.
    val metrics = remember { mutableStateOf(GridMetrics(0, emptyList())) }
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = Modifier.drawWithGrid(metrics, headerColor, lineColor),
    ) { measurables, constraints ->
        if (columns == 0) return@Layout layout(0, 0) {}
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val rowCount = (placeables.size + columns - 1) / columns
        val widths = IntArray(columns)
        val heights = IntArray(rowCount)
        placeables.forEachIndexed { i, p ->
            widths[i % columns] = maxOf(widths[i % columns], p.width, 64.dp.roundToPx())
            heights[i / columns] = maxOf(heights[i / columns], p.height)
        }
        val totalW = widths.sum()
        val totalH = heights.sum()
        val m = GridMetrics(heights.firstOrNull() ?: 0, heights.toList())
        if (metrics.value != m) metrics.value = m
        layout(totalW, totalH) {
            var y = 0
            for (r in 0 until rowCount) {
                var x = 0
                for (col in 0 until columns) {
                    val idx = r * columns + col
                    if (idx < placeables.size) placeables[idx].place(x, y)
                    x += widths[col]
                }
                y += heights[r]
            }
        }
    }
}

private data class GridMetrics(val headerHeight: Int, val rowHeights: List<Int>)

private fun Modifier.drawWithGrid(
    metrics: androidx.compose.runtime.State<GridMetrics>,
    headerColor: androidx.compose.ui.graphics.Color,
    lineColor: androidx.compose.ui.graphics.Color,
): Modifier = this.then(
    Modifier.drawBehind {
        val m = metrics.value
        drawRect(headerColor, size = androidx.compose.ui.geometry.Size(size.width, m.headerHeight.toFloat()))
        var y = 0f
        m.rowHeights.dropLast(1).forEach { h ->
            y += h
            drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
        }
    },
)

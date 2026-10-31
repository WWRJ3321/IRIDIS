package com.iris.localai.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    // 2.2: a finished reply that is one whole HTML document previews as a page (when 渲染网页代码 is on).
    val wholeHtml = remember(text, streaming) { !streaming && HtmlBlocks.wholeDocument(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (wholeHtml) HtmlCodeBlock(text.trim(), HtmlBlocks.Kind.HTML) { blocks.forEach { Block(it, c, color) } }
        else blocks.forEach { Block(it, c, color) }
    }
}

/** Builds (and remembers) the styled inline text of a block; keyed by content so equal text is never rebuilt. */
@Composable
private fun rememberInline(text: String, c: IrisColors): AnnotatedString = remember(text, c) { inline(text, c) }

@Composable
private fun Block(block: MdBlock, c: IrisColors, color: androidx.compose.ui.graphics.Color) {
    val body = remember(color) { IrisType.message.copy(color = color) }
    when (block) {
        is MdBlock.Heading -> {
            val head = when (block.level) { 1 -> IrisType.mdH1; 2 -> IrisType.mdH2; 3 -> IrisType.mdH3; else -> IrisType.message.copy(fontWeight = FontWeight.SemiBold) }
            Text(
                rememberInline(block.text, c),
                style = head.copy(color = color),
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
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space4).height(1.dp).background(c.outline))
    }
}

private fun inline(text: String, c: IrisColors): AnnotatedString = buildAnnotatedString {
    fun emit(nodes: List<MdInline>) {
        nodes.forEach { n ->
            when (n) {
                is MdInline.Text -> append(n.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { emit(n.children) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(n.children) }
                is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { emit(n.children) }
                is MdInline.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = c.surfaceVariant),
                ) { append("\u2009" + n.text + "\u2009") }
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
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val shape = IrisRadius.mediumShape
    Column(Modifier.fillMaxWidth().clip(shape).background(c.codeBackground).border(IrisDimens.hairline, c.outline, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = IrisDimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language.ifBlank { "text" }, color = c.textTertiary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
            )
            PressableIcon(
                if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = if (copied) "已复制" else "复制代码", size = 36.dp, iconSize = IrisDimens.space16, tint = c.textSecondary,
            ) {
                clipboard.setText(AnnotatedString(block.code))
                copied = true
            }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = IrisDimens.space12)) {
            Text(
                block.code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = c.text,
                softWrap = false,
            )
        }
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
                            fontSize = 14.sp, lineHeight = 20.sp, color = c.text, textAlign = align,
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = IrisDimens.space12, vertical = 9.dp),
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

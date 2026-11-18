package com.iris.localai.ui.markdown

/**
 * A small, dependency-free Markdown block parser tuned for LLM output.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 *
 * Supported: ATX headings, paragraphs, fenced code (``` and ~~~, unterminated fences render
 * as code while streaming), block quotes, ordered/unordered/task lists with nesting,
 * GitHub tables, thematic breaks, and $$ display math (shown as code).
 * a closing fence must be at least as long as its opening fence (CommonMark), so a ````html
 * block may contain ``` lines; `![alt](url)` inside a paragraph becomes its own [MdBlock.Image].
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListItem(val depth: Int, val marker: String, val text: String, val checked: Boolean?) : MdBlock
    data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
    /** `![alt](url)` split out of a paragraph; [url] is classified by [ImageRefs] when shown. */
    data class Image(val alt: String, val url: String) : MdBlock
    /** 独立公式（$$…$$ / \\[…\\]），按 [TexText] 转成可读文本居中显示。 */
    data class Math(val tex: String, val closed: Boolean = true) : MdBlock

    enum class Align { START, CENTER, END }
}

object MarkdownParser {
    private val SINGLE_MATH = Regex("^\\$\\$(.+)\\$\\$$|^\\\\\\[(.+)\\\\]$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val UL = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val OL = Regex("^(\\s*)(\\d{1,9})([.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(source: String): List<MdBlock> = parseLines(source.replace("\r\n", "\n").split('\n'))

    private fun parseLines(lines: List<String>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += splitImages(para.toString().trim())
            para.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2]
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (closesFence(l, marker)) { closed = true; break }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(l)
                    i++
                }
                out += MdBlock.Code(lang, code.toString(), closed)
                i++
                continue
            }

            // 单行 $$…$$ / \[…\]
            val single = SINGLE_MATH.matchEntire(t)
            if (single != null) {
                flush()
                out += MdBlock.Math(single.groupValues[1].ifEmpty { single.groupValues[2] })
                i++
                continue
            }
            if (t == "$$" || t == "\\[") {
                val close = if (t == "$$") "$$" else "\\]"
                flush()
                val math = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != close) {
                    if (math.isNotEmpty()) math.append('\n')
                    math.append(lines[i]); i++
                }
                out += MdBlock.Math(math.toString(), closed = i < lines.size)
                i++
                continue
            }

            when {
                t.isEmpty() -> flush()
                HEADING.matches(t) -> {
                    flush()
                    val m = HEADING.matchEntire(t)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                RULE.matches(line) -> { flush(); out += MdBlock.Rule }
                t.startsWith(">") -> {
                    flush()
                    val inner = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += MdBlock.Quote(parseLines(inner))
                    continue
                }
                t.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    val align = cells(lines[i + 1]).map {
                        when {
                            it.startsWith(":") && it.endsWith(":") -> MdBlock.Align.CENTER
                            it.endsWith(":") -> MdBlock.Align.END
                            else -> MdBlock.Align.START
                        }
                    }
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += cells(lines[i]); i++
                    }
                    out += MdBlock.Table(header, align, rows)
                    continue
                }
                UL.matches(line) -> {
                    flush()
                    val m = UL.matchEntire(line)!!
                    val (checked, text) = task(m.groupValues[3])
                    out += MdBlock.ListItem(depth(m.groupValues[1]), "•", text, checked)
                }
                OL.matches(line) -> {
                    flush()
                    val m = OL.matchEntire(line)!!
                    out += MdBlock.ListItem(depth(m.groupValues[1]), m.groupValues[2] + ".", m.groupValues[4], null)
                }
                // Lazy continuation of a list item.
                para.isEmpty() && out.lastOrNull() is MdBlock.ListItem && line.startsWith("  ") -> {
                    val last = out.removeAt(out.lastIndex) as MdBlock.ListItem
                    out += last.copy(text = last.text + "\n" + t)
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(t)
                }
            }
            i++
        }
        flush()
        return out
    }

    /** CommonMark: same character, at least as long as the opening fence, nothing else on the line. */
    internal fun closesFence(line: String, marker: String): Boolean {
        val t = line.trim()
        return t.length >= marker.length && t.all { it == marker[0] }
    }

    /** `![alt](url "title")`; url without spaces (data: URIs included), optionally in <…>. */
    internal val IMAGE = Regex("!\\[([^\\]\\n]*)]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)")

    /** A paragraph without images stays one [MdBlock.Paragraph]; images become [MdBlock.Image] between text parts. */
    internal fun splitImages(text: String): List<MdBlock> {
        if (!text.contains("![")) return listOf(MdBlock.Paragraph(text))
        val out = ArrayList<MdBlock>()
        var last = 0
        for (m in IMAGE.findAll(text)) {
            val before = text.substring(last, m.range.first).trim()
            if (before.isNotEmpty()) out += MdBlock.Paragraph(before)
            out += MdBlock.Image(m.groupValues[1].trim(), m.groupValues[2])
            last = m.range.last + 1
        }
        val rest = text.substring(last).trim()
        if (rest.isNotEmpty()) out += MdBlock.Paragraph(rest)
        return out
    }

    private fun depth(indent: String): Int {
        val spaces = indent.sumOf { if (it == '\t') 4L else 1L }.toInt()
        return (spaces / 2).coerceAtMost(6)
    }

    private fun task(text: String): Pair<Boolean?, String> {
        val m = TASK.matchEntire(text) ?: return null to text
        return (m.groupValues[1] != " ") to m.groupValues[2]
    }

    internal fun cells(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (c == '|') { out += cur.toString().trim(); cur.setLength(0) } else cur.append(c)
            i++
        }
        out += cur.toString().trim()
        return out
    }
}

/** Inline spans: **bold**, *italic*, `code`, ~~strike~~, [link](url), <https://autolink>, $math$. */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    /** 行内公式（$…$ / \\(…\\)）。 */
    data class Math(val tex: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
}

object InlineParser {
    private val TOKEN = Regex(
        "`([^`\\n]+)`" +                                   // 1 code
            "|\\*\\*(.+?)\\*\\*|__(.+?)__" +              // 2,3 bold
            "|~~(.+?)~~" +                                // 4 strike
            "|\\[([^\\]\\n]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)" + // 5,6 link
            "|<(https?://[^>\\s]+)>" +                    // 7 autolink
            "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])" + // 8 italic *
            "|(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])" +     // 9 italic _
            "|(?<![\\w$])\\$(?![\\s$])([^$\\n]+?)(?<!\\s)\\$(?![\\w$])" + // 10 inline math
            "|\\\\\\((.+?)\\\\\\)", // 11 \\( inline math \\) (2.5.8)
    )

    fun parse(text: String): List<MdInline> {
        val out = ArrayList<MdInline>()
        var last = 0
        for (m in TOKEN.findAll(text)) {
            if (m.range.first > last) out += MdInline.Text(text.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> if (TexText.looksMath(g[1])) MdInline.Math(g[1]) else MdInline.Code(g[1])
                g[2].isNotEmpty() -> MdInline.Bold(parse(g[2]))
                g[3].isNotEmpty() -> MdInline.Bold(parse(g[3]))
                g[4].isNotEmpty() -> MdInline.Strike(parse(g[4]))
                g[5].isNotEmpty() -> MdInline.Link(g[5], g[6])
                g[7].isNotEmpty() -> MdInline.Link(g[7], g[7])
                g[8].isNotEmpty() -> MdInline.Italic(parse(g[8]))
                g[9].isNotEmpty() -> MdInline.Italic(parse(g[9]))
                g[10].isNotEmpty() -> MdInline.Math(g[10])
                g[11].isNotEmpty() -> MdInline.Math(g[11])
                else -> MdInline.Text(m.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += MdInline.Text(text.substring(last))
        return out
    }
}

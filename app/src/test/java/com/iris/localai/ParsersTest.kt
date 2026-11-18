package com.iris.localai

import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelNames
import com.iris.localai.ui.markdown.InlineParser
import com.iris.localai.ui.markdown.MarkdownParser
import com.iris.localai.ui.markdown.MdBlock
import com.iris.localai.ui.markdown.MdInline
import com.iris.localai.util.streaming.SentenceSplitter
import com.iris.localai.util.thinking.StreamingThinkSplitter
import com.iris.localai.util.thinking.ThinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test fun headingsParagraphsAndRules() {
        val b = MarkdownParser.parse("# Title\n\nHello\nworld\n\n---\n## Sub ##")
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Hello\nworld"), b[1])
        assertEquals(MdBlock.Rule, b[2])
        assertEquals(MdBlock.Heading(2, "Sub"), b[3])
    }

    @Test fun fencedCodeKeepsIndentationAndHandlesStreaming() {
        val closed = MarkdownParser.parse("```kotlin\nfun a() {\n    return\n}\n```\nafter")
        assertEquals(MdBlock.Code("kotlin", "fun a() {\n    return\n}", closed = true), closed[0])
        assertEquals(MdBlock.Paragraph("after"), closed[1])
        val open = MarkdownParser.parse("```py\nprint(1)")
        assertEquals(MdBlock.Code("py", "print(1)", closed = false), open.single())
    }

    @Test fun nestedAndTaskLists() {
        val b = MarkdownParser.parse("- a\n  - b\n1. one\n- [x] done\n- [ ] todo")
        assertEquals(MdBlock.ListItem(0, "•", "a", null), b[0])
        assertEquals(MdBlock.ListItem(1, "•", "b", null), b[1])
        assertEquals(MdBlock.ListItem(0, "1.", "one", null), b[2])
        assertEquals(MdBlock.ListItem(0, "•", "done", true), b[3])
        assertEquals(MdBlock.ListItem(0, "•", "todo", false), b[4])
    }

    @Test fun tablesWithAlignmentAndEscapedPipes() {
        val b = MarkdownParser.parse("| a | b |\n|:--|--:|\n| 1 | x\\|y |")
        val t = b.single() as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(MdBlock.Align.START, MdBlock.Align.END), t.align)
        assertEquals(listOf(listOf("1", "x|y")), t.rows)
    }

    @Test fun quotesContainBlocks() {
        val q = MarkdownParser.parse("> # h\n> text").single() as MdBlock.Quote
        assertEquals(2, q.blocks.size)
    }

    @Test fun inlineSpans() {
        val n = InlineParser.parse("a **b** *c* `d` ~~e~~ [f](https://x.y) snake_case_word")
        assertTrue(n.any { it is MdInline.Bold })
        assertTrue(n.any { it is MdInline.Italic })
        assertTrue(n.any { it is MdInline.Code && it.text == "d" })
        assertTrue(n.any { it is MdInline.Strike })
        assertTrue(n.any { it is MdInline.Link && it.url == "https://x.y" })
        // underscores inside words are not emphasis
        assertTrue(n.last() is MdInline.Text && (n.last() as MdInline.Text).text.endsWith("snake_case_word"))
    }
}

class ThinkParserTest {
    @Test fun qwenStyle() {
        val p = ThinkParser.parse("<think>reason</think>\n\nanswer")
        assertEquals("reason", p.thinking); assertEquals("answer", p.answer); assertFalse(p.thinkingOpen)
    }

    @Test fun deepseekPrefilled() {
        val p = ThinkParser.parse("reason here</think>answer")
        assertEquals("reason here", p.thinking); assertEquals("answer", p.answer)
    }

    @Test fun streamingOpen() {
        val p = ThinkParser.parse("<think>still thinking")
        assertTrue(p.thinkingOpen); assertEquals("", p.answer)
    }

    @Test fun plain() {
        val p = ThinkParser.parse("just text")
        assertFalse(p.hasThinking); assertEquals("just text", p.answer)
    }

    @Test fun historyStripsReasoning() = assertEquals("ok", ThinkParser.stripForHistory("<think>x</think>ok"))
}

class StreamingThinkSplitterTest {
    private fun run(s: StreamingThinkSplitter, deltas: List<String>): Pair<String, String> {
        val t = StringBuilder(); val a = StringBuilder()
        (deltas.flatMap { s.push(it) } + s.flush()).forEach { (if (it.thinking) t else a).append(it.text) }
        return t.toString() to a.toString()
    }

    @Test fun tagsSplitAcrossDeltas() {
        val (t, a) = run(StreamingThinkSplitter(), listOf("<th", "ink>abc</th", "ink>xyz"))
        assertEquals("abc", t); assertEquals("xyz", a)
    }

    @Test fun prefilledStartsInThinking() {
        val (t, a) = run(StreamingThinkSplitter(startInThinking = true), listOf("r1 ", "r2</think>", "ans"))
        assertEquals("r1 r2", t); assertEquals("ans", a)
    }

    @Test fun noTagsMeansAllAnswer() {
        val (t, a) = run(StreamingThinkSplitter(), listOf("a < b", " and c"))
        assertEquals("", t); assertEquals("a < b and c", a)
    }
}

class SentenceSplitterTest {
    @Test fun emitsCompleteSentencesAsTheyArrive() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.push("你好，我是"))
        assertEquals(listOf("你好，我是 IRIS。"), s.push(" IRIS。今天"))
        assertEquals(listOf("今天天气不错！"), s.push("天气不错！"))
        assertEquals(emptyList<String>(), s.flush())
    }

    @Test fun keepsDecimalsTogether() {
        val s = SentenceSplitter()
        val out = s.push("Pi is 3.14 roughly. Next") + s.flush()
        assertEquals(listOf("Pi is 3.14 roughly.", "Next"), out)
    }

    @Test fun stripsMarkdownAndSkipsCode() {
        val s = SentenceSplitter()
        val out = s.push("## **要点**\n- 第一点\n```\ncode()\n```\n看 [文档](http://x)。") + s.flush()
        assertEquals(listOf("要点", "第一点", "看 文档。"), out)
    }

    @Test fun longClausesSplitAtCommas() {
        val s = SentenceSplitter(softLimit = 10)
        val out = s.push("这是一个非常非常长的从句没有句号，后面还有")
        assertEquals(listOf("这是一个非常非常长的从句没有句号，"), out)
    }
}

class ModelNamesTest {
    @Test fun displayNameAndQuant() {
        assertEquals("Qwen3 4B Instruct", ModelNames.display("Qwen3-4B-Instruct-Q4_K_M.gguf"))
        assertEquals("Q4_K_M", ModelNames.quantization("Qwen3-4B-Instruct-Q4_K_M.gguf"))
        assertEquals("Gemma3 1b", ModelNames.display("gemma3-1b-it-int4.litertlm"))
        assertEquals("Gemma 4 E2B NPU", ModelNames.display("gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
        assertEquals("Gemma 4 E4B", ModelNames.display("gemma-4-E4B-it.litertlm"))
        assertEquals(ModelFormat.GGUF, ModelFormat.fromFileName("a.GGUF"))
        assertEquals(ModelFormat.LITERT, ModelFormat.fromFileName("a.litertlm"))
        assertEquals(null, ModelFormat.fromFileName("a.bin"))
    }
}

class VoiceDownloadTest {
    @Test fun pathSegmentsArePercentEncoded() {
        assertEquals(
            "espeak-ng-data/voices/%21v/Mr%20serious",
            com.iris.localai.voice.VoiceModelStore.encodePath("espeak-ng-data/voices/!v/Mr serious"),
        )
        assertEquals("model.int8.onnx", com.iris.localai.voice.VoiceModelStore.encodePath("model.int8.onnx"))
    }
}

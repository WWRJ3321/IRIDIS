package com.iris.localai.knowledge

import com.iris.localai.agent.core.AgentPrompt
import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.agent.tools.AgentTools
import com.iris.localai.agent.workspace.ExternalFiles
import com.iris.localai.agent.tools.KbSearchTool
import com.iris.localai.agent.tools.KnowledgeSearch
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.workspace.UndoStore
import com.iris.localai.agent.workspace.WorkspaceSandbox
import com.iris.localai.data.AppSettings
import com.iris.localai.knowledge.KbChanges
import com.iris.localai.knowledge.KbChat
import com.iris.localai.knowledge.KbChunk
import com.iris.localai.knowledge.KbChunker
import com.iris.localai.knowledge.KbFileEntry
import com.iris.localai.knowledge.KbFormats
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbPrompt
import com.iris.localai.knowledge.KbRanker
import com.iris.localai.knowledge.KbSourceFile
import com.iris.localai.knowledge.KbTokenizer
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.util.CtxMessage
import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V210KnowledgeTest {

    // ---------------------------------------------------------------- tokenizer

    @Test fun tokenizerCjkBigramsAndLatinWords() {
        assertEquals(listOf("iridis", "本地", "地知", "知识", "识库", "v2"), KbTokenizer.tokens("IRIDIS 本地知识库v2"))
        assertEquals(listOf("hello", "world", "42"), KbTokenizer.tokens("Hello, WORLD! 42"))
        // a lone CJK character between punctuation stays a unigram
        assertEquals(listOf("猫", "很", "可爱"), KbTokenizer.tokens("猫，很「可爱」"))
        assertEquals(listOf("api", "接口", "文档"), KbTokenizer.tokens("API接口；文档"))
        assertTrue(KbTokenizer.tokens("  ，。!? ").isEmpty())
        assertEquals(listOf("こん", "んに", "にち", "ちは"), KbTokenizer.tokens("こんにちは"))
    }

    // ---------------------------------------------------------------- chunker

    @Test fun chunkerSizesOverlapAndBoundaries() {
        assertEquals(emptyList<String>(), KbChunker.chunk("   \n "))
        assertEquals(listOf("短文本"), KbChunker.chunk("短文本"))
        val sentence = "这是一个用来测试分段的句子，长度适中。"
        val text = (1..80).joinToString("") { "$it$sentence" }
        val parts = KbChunker.chunk(text)
        assertTrue(parts.size > 3)
        parts.forEach { assertTrue("${it.length}", it.length <= KbChunker.SIZE) }
        // cut at sentence ends
        parts.dropLast(1).forEach { assertTrue(it, it.endsWith("。")) }
        // consecutive parts overlap
        for (i in 1 until parts.size) assertTrue(parts[i - 1].takeLast(20) in parts[i] || parts[i].take(20) in parts[i - 1])
        // all text is covered
        (1..80).forEach { n -> assertTrue("$n", parts.any { p -> p.contains("${n}这是") }) }
        // no boundary at all: hard cuts, still terminates
        val hard = KbChunker.chunk("x".repeat(1234))
        assertTrue(hard.all { it.length <= KbChunker.SIZE }); assertTrue(hard.joinToString("").length >= 1234)
    }

    // ---------------------------------------------------------------- ranking

    @Test fun rankingPrefersRelevantChunks() {
        val chunks = listOf(
            KbChunk("菜谱.md", "红烧肉的做法：五花肉切块，冰糖炒糖色，加酱油和水慢炖一小时。"),
            KbChunk("合同.txt", "租赁合同约定每月租金 3500 元，押一付三，合同期限一年。"),
            KbChunk("notes.md", "Kotlin coroutines: use withContext(Dispatchers.IO) for blocking file reads."),
            KbChunk("合同.txt", "违约责任：提前退租需支付一个月租金作为违约金。"),
        )
        val r = KbRanker(chunks)
        assertEquals("合同.txt", r.search("房租多少钱一个月", 3).first().file)
        assertEquals("菜谱.md", r.search("红烧肉怎么做", 3).first().file)
        assertEquals("notes.md", r.search("kotlin io dispatcher", 3).first().file)
        assertTrue(r.search("违约金", 3).first().text.contains("违约"))
        assertTrue(r.search("量子物理", 3).isEmpty())
        assertTrue(r.search("", 3).isEmpty())
        // the file name counts too
        assertEquals("菜谱.md", r.search("菜谱", 1).single().file)
        assertTrue(r.search("合同 租金", 4).size <= 4)
    }

    @Test fun oneFileDoesNotCrowdOutOthers() {
        val chunks = (1..5).map { KbChunk("a.txt", "苹果 苹果 苹果 第$it 段") } + KbChunk("b.txt", "苹果 一次")
        val hits = KbRanker(chunks).search("苹果", 3)
        assertEquals(3, hits.size)
        assertTrue(hits.any { it.file == "b.txt" })
    }

    // ---------------------------------------------------------------- change detection

    @Test fun changeDetectionBySizeAndMtime() {
        val old = listOf(KbFileEntry("a.md", 10, 100, listOf("x")), KbFileEntry("b.md", 20, 200, listOf("y")), KbFileEntry("c.md", 5, 1))
        val now = listOf(KbSourceFile("a.md", 10, 100), KbSourceFile("b.md", 21, 200), KbSourceFile("d.md", 1, 1))
        val p = KbChanges.plan(old, now)
        assertEquals(listOf("a.md"), p.keep.map { it.path })
        assertEquals(listOf("b.md", "d.md"), p.reindex.map { it.path })
        assertEquals(listOf("c.md"), p.removed)
        assertTrue(p.changed)
        assertFalse(KbChanges.plan(old.take(1), now.take(1)).changed)
        assertEquals(1, KbChanges.plan(old.take(1), now.take(1), force = true).reindex.size)
        // mtime alone changed
        assertEquals(1, KbChanges.plan(old.take(1), listOf(KbSourceFile("a.md", 10, 101))).reindex.size)
    }

    // ---------------------------------------------------------------- the folder

    private fun tmp(): File = Files.createTempDirectory("kb").toFile()

    @Test fun knowledgeBaseIndexesFolderAndNoticesChanges() {
        val ws = tmp()
        val idx = File(tmp(), "index.json")
        val kb = KnowledgeBase({ ws }, idx)
        assertFalse(kb.hasFiles())
        assertFalse(kb.nonEmptyCached())
        val dir = File(ws, KnowledgeBase.FOLDER).apply { mkdirs() }
        File(dir, "猫.md").writeText("# 养猫\n猫粮每天两次，每次 40 克。疫苗每年一次。")
        File(dir, "page.html").writeText("<html><head><style>x{}</style></head><body><p>Wi-Fi 密码是 iris2026</p></body></html>")
        File(dir, "photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "sub").mkdirs(); File(dir, "sub/旅行.txt").writeText("东京行程：第一天浅草寺，第二天秋叶原。")
        val s = kb.refresh()
        assertEquals(3, s.files); assertEquals(1, s.unsupported); assertTrue(s.chunks >= 3)
        assertTrue(idx.isFile)
        assertEquals("猫.md", kb.search("猫粮喂多少", 4).first().file)
        assertEquals("page.html", kb.search("wifi 密码", 4).first().file)
        assertFalse(kb.search("wifi 密码", 4).first().text.contains("<p>"))
        assertEquals("sub/旅行.txt", kb.search("东京第二天去哪", 4).first().file)

        // a new index object reads the saved index
        val again = KnowledgeBase({ ws }, idx)
        assertTrue(again.nonEmptyCached())
        // edit + delete are noticed on refresh
        File(dir, "猫.md").writeText("猫粮每天三次，每次 30 克，记得换水。")
        File(dir, "page.html").delete()
        val s2 = again.refresh()
        assertEquals(2, s2.files)
        assertTrue(again.search("猫粮", 1).single().text.contains("三次"))
        assertTrue(again.search("wifi 密码", 4).none { it.file == "page.html" })

        // 添加文件: copied into knowledge/ with a free name
        assertEquals("猫 (2).md", again.add("猫.md", "重名文件".toByteArray()))
        assertEquals("a_b.txt", again.add("a/b.txt", "x".toByteArray()))
        assertEquals(4, again.refresh(force = true).files)
    }

    @Test fun safOnlyFilesAreIndexed() {
        val ws = tmp()
        val ext = object : ExternalFiles {
            override fun list(relDir: String) = when (relDir) {
                "knowledge" -> listOf(ExternalFiles.Entry("note.txt", false, 30), ExternalFiles.Entry("a.pdf", false, 9))
                else -> null
            }
            override fun read(relPath: String, maxBytes: Long) = if (relPath == "knowledge/note.txt") "会议纪要：周五发布 2.1.0 版本".toByteArray() else null
        }
        val kb = KnowledgeBase({ ws }, File(tmp(), "i.json"), { ext })
        val s = kb.refresh()
        assertEquals(1, s.files); assertEquals(1, s.unsupported)
        assertEquals("note.txt", kb.search("什么时候发布", 2).first().file)
    }

    @Test fun formats() {
        assertTrue(KbFormats.supported("a.MD")); assertTrue(KbFormats.supported("x.htm"))
        assertFalse(KbFormats.supported("a.pdf")); assertFalse(KbFormats.supported(".hidden.txt"))
        assertEquals(null, KbFormats.text("a.txt", byteArrayOf(0, 1, 2)))
        assertEquals("你好", KbFormats.text("a.txt", "\uFEFF你好".toByteArray()))
    }

    // ---------------------------------------------------------------- prompt / tool / settings

    @Test fun injectionIsCappedAndAttachedToTheQuestion() {
        val hits = (1..3).map { KbHit("f$it.md", "很长的资料".repeat(200), 5.0) }
        val block = KbPrompt.inject(hits)
        val body = block.lines().filter { it.startsWith("[") }.joinToString("") { it.substringAfter(": ") }
        assertTrue("${body.length}", body.length <= KbPrompt.INJECT_CHARS)
        assertTrue(block.contains("f1.md")); assertTrue(block.startsWith("<knowledge>"))
        assertEquals("", KbPrompt.inject(emptyList()))
        val msgs = listOf(CtxMessage("u1", "user", "旧问题"), CtxMessage("a1", "assistant", "答"), CtxMessage("u2", "user", "新问题"))
        val out = KbChat.attach(msgs, "u2", block)
        assertEquals("旧问题", out[0].content)
        assertTrue(out[2].content.startsWith("<knowledge>")); assertTrue(out[2].content.endsWith("新问题"))
        assertEquals("引用 3 段知识库", KbPrompt.chip(3))
    }

    @Test fun kbSearchTool() {
        val sb = WorkspaceSandbox(tmp())
        val env = ToolEnv(sb, UndoStore(tmp()), knowledge = KnowledgeSearch { q, n -> listOf(KbHit("合同.txt", "每月租金 3500 元", 3.0)).take(n).filter { q.isNotBlank() } })
        val r = KbSearchTool.run(buildJsonObject { put("query", "租金") }, env)
        assertTrue(r.ok); assertTrue(r.output.contains("[合同.txt]")); assertTrue(r.summary.startsWith("1 段"))
        val none = KbSearchTool.run(buildJsonObject { put("query", "租金") }, ToolEnv(sb, UndoStore(tmp())))
        assertFalse(none.ok)
    }

    @Test fun kbSearchIsOnDemandAndPromptStaysSmall() {
        // not in the fixed list: costs no prompt tokens while the folder is empty
        assertFalse(AgentTools.all.contains(KbSearchTool))
        assertEquals(KbSearchTool, AgentTools.byName("kb_search"))
        val s = AgentSettings()
        assertFalse(AgentTools.enabled(s, true, true).contains(KbSearchTool))
        val withKb = AgentTools.enabled(s, true, true, knowledgeOn = true)
        // the default prompt (按需载入 on) with every tool and the knowledge base
        val p = AgentPrompt.build(withKb, style = AgentPrompt.CallStyle.QWEN, lazy = s.lazyTools)
        assertTrue(p.contains("kb_search(query)"))
        assertTrue("prompt ~${TokenEstimator.estimate(p)}", TokenEstimator.estimate(p) <= 800)
        assertTrue(KbSearchTool.promptLine.length <= 60)
    }

    @Test fun settingDefaultOn() {
        assertTrue(AppSettings().knowledgeInChat)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(AppSettings.serializer(), "{}").knowledgeInChat)
    }
}

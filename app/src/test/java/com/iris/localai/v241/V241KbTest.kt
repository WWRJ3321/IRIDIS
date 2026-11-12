package com.iris.localai.v241

import com.iris.localai.agent.KbSearchTool
import com.iris.localai.agent.KnowledgeSearch
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.knowledge.KbChat
import com.iris.localai.knowledge.KbChunk
import com.iris.localai.knowledge.KbCover
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbPrompt
import com.iris.localai.knowledge.KbRanker
import com.iris.localai.knowledge.KnowledgeBase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.4.1 K: 知识库 no longer "cites" on small talk; real questions still hit; the label only when the reply cites. */
class V241KbTest {
    private fun kb(): Pair<KnowledgeBase, File> {
        val root = Files.createTempDirectory("kb241").toFile()
        val kb = KnowledgeBase({ root }, File(root, "idx/kb.json"))
        kb.add("租房合同.md", "租房合同：月租金 3500 元，押金一个月，押一付三，租期两年。退租后 30 天内退还押金。".toByteArray())
        kb.add("菜谱.md", "番茄炒蛋：鸡蛋三个，番茄两个，盐少许。先炒鸡蛋再下番茄。".toByteArray())
        kb.add("旅行.md", "东京行程：第一天浅草寺、上野公园，第二天秋叶原、银座。".toByteArray())
        kb.add("IRIDIS 手册.md", "知识库支持 txt、md、csv、html 文件，放进 knowledge 文件夹即可自动索引。".toByteArray())
        kb.add("笔记.md", "Rust 所有权：每个值只有一个所有者，借用分为可变借用与不可变借用。".toByteArray())
        kb.refresh()
        return kb to root
    }

    private val chat = listOf("你好", "今天心情不错", "讲个笑话", "帮我写首诗", "你好呀，今天怎么样", "谢谢你", "hello", "hi, how are you")

    @Test fun smallTalkHitsNothing() {
        val (kb, root) = kb()
        for (q in chat) assertTrue(q, KbChat.hits(kb, q).isEmpty())
        // also as a follow-up of a real question: small talk must not drag the contract back in
        assertTrue(KbChat.hits(kb, "讲个笑话", previousUser = "押金什么时候退").isEmpty())
        root.deleteRecursively()
    }

    @Test fun realQuestionsStillHit() {
        val (kb, root) = kb()
        assertEquals("租房合同.md", KbChat.hits(kb, "月租金多少").first().file)
        assertEquals("菜谱.md", KbChat.hits(kb, "番茄炒蛋怎么做").first().file)
        assertEquals("旅行.md", KbChat.hits(kb, "东京行程第二天去哪").first().file)
        assertEquals("IRIDIS 手册.md", KbChat.hits(kb, "知识库支持哪些文件格式").first().file)
        assertEquals("笔记.md", KbChat.hits(kb, "Rust 的借用规则").first().file)
        // 2.5.1: a follow-up no longer borrows the previous question's words
        assertEquals(KbChat.hits(kb, "那押金呢").map { it.file }, KbChat.hits(kb, "那押金呢", previousUser = "月租金多少").map { it.file })
        assertEquals("租房合同.md", KbChat.hits(kb, "押金什么时候退还").first().file)
        root.deleteRecursively()
    }

    @Test fun coverageGate() {
        fun h(c: KbCover) = KbHit("f.md", "t", 2.0, 0, cover = c)
        assertFalse(KbChat.relevant(h(KbCover(1, 3, 0.4, 0.3, false, false))))   // one of three terms
        assertFalse(KbChat.relevant(h(KbCover(2, 6, 0.2, 0.3, false, false))))   // two terms, too little of the query
        assertTrue(KbChat.relevant(h(KbCover(2, 4, 0.5, 0.3, false, false))))
        assertFalse(KbChat.relevant(h(KbCover(2, 4, 0.5, 0.1, false, false))))   // low normalised score
        assertFalse(KbChat.relevant(h(KbCover(1, 1, 1.0, 0.4, false, false))))   // single 2-char word
        assertTrue(KbChat.relevant(h(KbCover(1, 1, 1.0, 0.4, true, false))))     // …in the file name
        assertTrue(KbChat.relevant(h(KbCover(1, 1, 1.0, 0.4, false, true))))     // ≥ 3 characters
        assertTrue(KbChat.relevant(KbHit("f.md", "t", 0.1)))                     // no cover: nothing to judge
        val r = KbRanker(listOf(KbChunk("a.md", "今天的天气很好", 0), KbChunk("b.md", "押金退还规则", 0)))
        assertTrue(r.terms("你好").isEmpty())
        assertTrue(r.terms("今天怎么样").isEmpty())
        assertEquals(listOf("押金"), r.terms("押金呢").filter { it == "押金" })
    }

    @Test fun commonTermsDoNotCount() {
        // "说明" is in every chunk → not counted once there are ≥ 4 chunks
        val r = KbRanker((0 until 5).map { KbChunk("d$it.md", "说明 第 $it 部分 内容$it", 0) })
        assertFalse("说明" in r.terms("说明 3"))
        assertTrue("说明" in r.terms("说明"))   // all terms common: they still rank
    }

    @Test fun kbSearchToolUsesTheGate() {
        val (kb, root) = kb()
        val sb = WorkspaceSandbox(Files.createTempDirectory("kb241s").toFile())
        val env = ToolEnv(sb, UndoStore(Files.createTempDirectory("kb241u").toFile()), knowledge = KnowledgeSearch { q, n -> kb.search(q, n) })
        assertTrue(KbSearchTool.run(buildJsonObject { put("query", "讲个笑话") }, env).output.startsWith("No knowledge snippets match"))
        assertTrue(KbSearchTool.run(buildJsonObject { put("query", "押金退还") }, env).output.contains("[租房合同.md]"))
        root.deleteRecursively()
    }

    @Test fun labelOnlyWhenCited() {
        val src = listOf("租房合同.md · 第 1 段", "菜谱.md · 第 1 段")
        assertEquals(listOf(src[0]), KbPrompt.cited("根据《租房合同》，押金一个月。", src))
        assertEquals(listOf(src[1]), KbPrompt.cited("先炒鸡蛋 [2]。", src))
        assertEquals(src, KbPrompt.cited("见 租房合同.md 与 [2]", src))
        assertTrue(KbPrompt.cited("你好！有什么可以帮你？", src).isEmpty())
        assertTrue(KbPrompt.citesAny("答案见 [1]", 2)); assertFalse(KbPrompt.citesAny("没有引用", 2))
        val block = KbPrompt.inject(listOf(KbHit("a.md", "内容", 1.0)))
        assertTrue(block.contains("仅当资料直接回答")); assertTrue(block.contains("不要提及"))
    }
}

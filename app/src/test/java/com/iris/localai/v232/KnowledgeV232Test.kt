package com.iris.localai.v232

import com.iris.localai.knowledge.KbBlock
import com.iris.localai.knowledge.KbBudget
import com.iris.localai.knowledge.KbChat
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbMerge
import com.iris.localai.knowledge.KbPaths
import com.iris.localai.knowledge.KbQuery
import com.iris.localai.knowledge.KnowledgeBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KnowledgeV232Test {
    private fun block(chars: Int, vararg keys: Pair<String, Int>) = KbBlock("知".repeat(chars), keys.toSet())

    @Test fun budgetKeepsOnlyNewestTwoTurns() {
        val ids = listOf("u1", "u2", "u3", "u4")
        val blocks = ids.associateWith { block(100) }
        assertEquals(setOf("u3", "u4"), KbBudget.select(ids, blocks, 8192).keys)
    }

    @Test fun budgetDropsOldestOverFifteenPercent() {
        val ids = listOf("u1", "u2")
        val blocks = mapOf("u1" to block(700), "u2" to block(700))
        // 15% of 8192 = 1228 tokens: both (1400) do not fit, the older goes.
        assertEquals(setOf("u2"), KbBudget.select(ids, blocks, 8192).keys)
        assertEquals(setOf("u1", "u2"), KbBudget.select(ids, blocks, 16384).keys)
        val total = KbBudget.select(ids, blocks, 8192).values.sumOf { it.tokens }
        assertTrue(total <= KbBudget.limit(8192))
    }

    @Test fun planGivesNewestRoomAndExcludesKeptChunks() {
        val ids = listOf("u1", "u2", "u3")
        val blocks = mapOf("u1" to block(100, "a.md" to 0), "u2" to block(300, "a.md" to 1, "a.md" to 2))
        val (room, exclude) = KbBudget.plan(ids, blocks, "u3", 8192)
        assertEquals(KbBudget.limit(8192) - 300, room)
        assertEquals(setOf("a.md" to 1, "a.md" to 2), exclude) // u1 is out of the 2-turn window
        // Small window: the older block would leave < MIN_ROOM → dropped, newest gets it all.
        val (room2, ex2) = KbBudget.plan(ids, blocks, "u3", 2400)
        assertEquals(KbBudget.limit(2400), room2)
        assertTrue(ex2.isEmpty())
    }

    @Test fun dedupeSkipsInjectedChunks() {
        val hits = listOf(KbHit("a.md", "x", 3.0, 1), KbHit("a.md", "y", 2.0, 4), KbHit("b.md", "z", 1.0, 1))
        assertEquals(listOf(4, 1), KbChat.dedupe(hits, setOf("a.md" to 1)).map { it.chunkIndex })
        val chunks = listOf("c0 one two three", "c1 four five six", "c2 seven eight")
        val ex = KbMerge.expand(listOf(KbHit("f", chunks[1], 2.0, 1)), { chunks }, 1000, already = setOf("f" to 0))
        assertEquals(listOf(1, 2), ex.single().covers)
        assertEquals(setOf("f" to 1, "f" to 2), ex.single().keys)
    }

    @Test fun followUpAckHasNoTerms() {
        for (q in listOf("好的", "继续", "谢谢", "好的，继续", "嗯嗯", "OK", "thanks")) assertTrue(q, KbQuery.effective(q).isEmpty())
        assertFalse(KbQuery.effective("租金多少").isEmpty())
    }

    @Test fun carriedHitNeedsHigherScore() {
        val a = KbHit("a.md", "x", 1.0, 0); val b = KbHit("b.md", "y", 1.6, 0); val d = KbHit("c.md", "z", 0.9, 3)
        assertEquals(listOf(b, d), KbChat.gateCarried(listOf(a, b, d), setOf("c.md" to 3)))
    }

    @Test fun ackDoesNotSearchKnowledgeBase() {
        val root = Files.createTempDirectory("kb232").toFile()
        val kb = KnowledgeBase({ root }, File(root, "idx/kb.json"))
        assertTrue(kb.add("合同.md", "租房合同：月租金 3500 元，押一付三，租期两年。".toByteArray()) != null)
        kb.add("菜谱.md", "番茄炒蛋：鸡蛋三个，番茄两个，盐少许。".toByteArray())
        kb.add("旅行.md", "东京行程：浅草寺、上野公园、秋叶原。".toByteArray())
        kb.refresh()
        assertTrue(KbChat.hits(kb, "月租金多少").isNotEmpty())
        assertTrue(KbChat.hits(kb, "好的", previousUser = "月租金多少").isEmpty())
        assertTrue(KbChat.hits(kb, "继续", previousUser = "月租金多少").isEmpty())
        root.deleteRecursively()
    }

    @Test fun pathSafety() {
        assertNull(KbPaths.folder("../x")); assertNull(KbPaths.folder("a/../b")); assertNull(KbPaths.folder("/abs"))
        assertNull(KbPaths.folder("a/.hidden")); assertNull(KbPaths.folder("a\\b")); assertNull(KbPaths.folder("a/b/c/d"))
        assertEquals("a/b/c", KbPaths.folder("a/b/c/")); assertEquals("", KbPaths.folder(""))
        assertNull(KbPaths.name("a:b")); assertNull(KbPaths.name("..")); assertNull(KbPaths.name("x\ny")); assertEquals("资料", KbPaths.name(" 资料 "))
        assertNull(KbPaths.file("a/b/c/d/e.md")); assertEquals("a/b/c/e.md", KbPaths.file("a/b/c/e.md"))
        assertTrue(KbPaths.under("a/b/x.md", "a")); assertFalse(KbPaths.under("ab/x.md", "a"))
        assertEquals(listOf("" to "KB", "a" to "a", "a/b" to "b"), KbPaths.crumbs("a/b", "KB"))
        assertTrue(KbPaths.editable("x.md")); assertFalse(KbPaths.editable("x.html"))
    }

    @Test fun foldersEditMove() {
        val root = Files.createTempDirectory("kb232f").toFile()
        val kb = KnowledgeBase({ root }, File(root, "idx/kb.json"))
        assertEquals("工作", kb.mkdir("", "工作"))
        assertEquals("工作/2026", kb.mkdir("工作", "2026"))
        assertNull(kb.mkdir("", "../evil")); assertNull(kb.mkdir("", "工作"))
        assertEquals("工作/2026/x", kb.mkdir("工作/2026", "x"))
        assertNull(kb.mkdir("工作/2026/x", "too-deep"))
        val p = kb.add("笔记.md", "旧内容 苹果".toByteArray(), "工作")!!
        assertEquals("工作/笔记.md", p)
        kb.refresh()
        assertTrue(kb.writeText(p, "新内容 香蕉"))
        assertEquals("新内容 香蕉", kb.readText(p))
        assertTrue(kb.search("香蕉", 3).isNotEmpty()); assertTrue(kb.search("苹果", 3).isEmpty())
        val moved = kb.move(p, "工作/2026")!!
        assertEquals("工作/2026/笔记.md", moved)
        assertEquals("资料", kb.renameFolder("工作", "资料"))
        assertTrue(File(root, "knowledge/资料/2026/笔记.md").isFile)
        assertFalse(kb.writeText("../outside.md", "x"))
        assertFalse(kb.folderEmpty("资料"))
        assertTrue(kb.deleteFolder("资料"))
        assertTrue(kb.files().isEmpty())
        assertFalse(kb.deleteFolder(""))
        root.deleteRecursively()
    }
}

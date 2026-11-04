package com.iris.localai.v229

import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Conversation
import com.iris.localai.data.Role
import com.iris.localai.knowledge.KbChat
import com.iris.localai.knowledge.KbChunk
import com.iris.localai.knowledge.KbChunker
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbMerge
import com.iris.localai.knowledge.KbPrompt
import com.iris.localai.knowledge.KbQuery
import com.iris.localai.knowledge.KbRanker
import com.iris.localai.knowledge.KbSource
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryItem.Kind
import com.iris.localai.memory.MemoryItem.Source
import com.iris.localai.memory.MemoryPrompt
import com.iris.localai.memory.MemoryRecall
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecallKbV229Test {
    private fun item(text: String, kind: Kind, t: Long) = MemoryItem(text = text, kind = kind, source = Source.USER, createdAt = t)

    private val items = listOf(
        item("名字叫阿明", Kind.ABOUT, 1),
        item("回答尽量简短", Kind.PREF, 2),
        item("我住在九龙", Kind.FACT, 3),
        item("养了一只猫叫豆豆", Kind.FACT, 4),
        item("项目 IRIDIS 用 Kotlin 写", Kind.FACT, 5),
    )

    // ---- C ----

    @Test fun whereDoILiveBringsLocation() {
        val b = MemoryRecall.turnBlock(items, "我住哪", 500)
        assertTrue(b, b.contains("九龙"))
        assertTrue(b.startsWith(MemoryRecall.FACTS))
        assertFalse(b.contains("豆豆"))
        // ABOUT location is always there (frozen part).
        val about = listOf(item("我住在九龙", Kind.ABOUT, 1))
        assertTrue(MemoryRecall.block(MemoryRecall.select(about, "随便聊聊", 500)).contains("九龙"))
    }

    @Test fun unrelatedQuestionGetsNoFacts() {
        assertEquals("", MemoryRecall.turnBlock(items, "今天天气怎么样", 500))
        assertEquals("", ContextAssembly.turnMemory(items, "写一首诗", 4096))
        val sel = MemoryRecall.select(items, "今天天气怎么样", 500)
        assertEquals(setOf(Kind.ABOUT, Kind.PREF), sel.map { it.kind }.toSet())
    }

    @Test fun overBudgetDropsOldestKeepsNewest() {
        val many = (1..60).map { item("偏好第 $it 条：" + "很长的说明".repeat(4), Kind.PREF, it.toLong()) }
        val kept = MemoryRecall.fixed(many, 200)
        assertTrue(kept.isNotEmpty() && kept.size < 60)
        assertTrue(kept.any { it.updatedAt == 60L })
        assertFalse(kept.any { it.updatedAt == 1L })
        assertEquals(kept.sortedByDescending { it.updatedAt }, kept)
    }

    @Test fun systemPromptStableFromSecondTurn() {
        val snap = MemoryRecall.block(MemoryRecall.fixed(items, 1000))
        assertTrue(snap.contains(MemoryRecall.ABOUT) && snap.contains(MemoryRecall.PREF))
        assertFalse(snap.contains("九龙")) // FACT not frozen
        val u = ChatMessage(role = Role.USER, content = "你好")
        val a = ChatMessage(role = Role.ASSISTANT, content = "你好！", modelName = "m")
        val conv1 = Conversation(messages = listOf(u, a), memorySnapshot = snap, memoryEpoch = 0)
        assertFalse(MemoryPrompt.needsSnapshot(conv1, 0))
        val sys1 = ContextAssembly.system("基础", ContextAssembly.memoryBlock(conv1, true, 4096))
        val conv2 = conv1.copy(messages = conv1.messages + ChatMessage(role = Role.USER, content = "我住哪") + ChatMessage(role = Role.ASSISTANT, content = "九龙", modelName = "m"))
        val sys2 = ContextAssembly.system("基础", ContextAssembly.memoryBlock(conv2, true, 4096))
        assertEquals(sys1, sys2)
        assertFalse(sys1.contains("USER.md") || sys1.contains("MEMORY.md"))
        // An old-format snapshot is relabelled.
        val legacy = ContextAssembly.memoryBlock(conv1.copy(memorySnapshot = "USER.md:\n- a\nMEMORY.md:\n- b"), true, 4096)
        assertFalse(legacy.contains("USER.md"))
    }

    // ---- D ----

    private val contract = "租房合同.md"
    private fun ranker() = KbRanker(listOf(
        KbChunk(contract, "第七条 押金：退租后 30 天内无息退还押金，扣除未结清的水电费。", 0),
        KbChunk(contract, "第八条 违约：任何一方提前解约，违约金为一个月租金。", 1),
        KbChunk("劳动合同.md", "员工提前离职须支付违约金。", 0),
        KbChunk("Rust 学习笔记.md", "所有权与借用：每个值只有一个所有者。", 0),
    ))

    @Test fun followUpHitsSameFile() {
        val r = ranker()
        val first = r.search("押金什么时候退", 3)
        assertEquals(contract, first.first().file)
        assertTrue(KbQuery.isFollowUp("那违约金呢"))
        assertFalse(KbQuery.isFollowUp("Rust 的所有权和借用规则是什么样的"))
        val q = KbQuery.build("那违约金呢", "押金什么时候退", listOf(contract), r::idf)
        assertTrue(q, q.startsWith("那违约金呢 ") && q.contains("押金"))
        val second = r.search(q, 3, boostFiles = listOf(contract))
        assertEquals(contract, second.first().file)
        assertEquals(1, second.first().chunkIndex)
        // Not a follow-up → unchanged.
        assertEquals("Rust 的所有权和借用规则是什么样的", KbQuery.build("Rust 的所有权和借用规则是什么样的", "押金什么时候退", emptyList()))
    }

    @Test fun adjacentChunksMerged() {
        val text = (1..12).joinToString("") { "第${it}句说明合同条款的一部分内容，" + "细节".repeat(10) + "。" }
        val chunks = KbChunker.chunk(text, size = 200, overlap = 40)
        assertTrue(chunks.size >= 3)
        val hit = KbHit("a.md", chunks[1], 3.0, 1)
        val out = KbMerge.expand(listOf(hit, KbHit("a.md", chunks[2], 2.0, 2)), { chunks }, 600)
        assertEquals(1, out.size) // chunk 2 was absorbed by chunk 1's merge
        val merged = out.single().text
        assertTrue(merged.contains(chunks[1]))
        assertTrue(merged.contains(chunks[2].takeLast(30)))
        assertTrue(merged.length < chunks[0].length + chunks[1].length + chunks[2].length)
        // Budget respected.
        assertEquals(chunks[1], KbMerge.expand(listOf(hit), { chunks }, chunks[1].length + 5).single().text)
    }

    @Test fun adaptiveThreshold() {
        fun h(s: Double) = KbHit("f", "t", s, 0)
        assertEquals(listOf(5.0, 1.9), KbChat.keep(listOf(h(5.0), h(1.9), h(1.6))).map { it.score })
        assertEquals(listOf(1.0, 0.7), KbChat.keep(listOf(h(1.0), h(0.7), h(0.5))).map { it.score })
    }

    @Test fun deletedFileNoLongerReturned() {
        val root = Files.createTempDirectory("kb").toFile()
        val kb = KnowledgeBase({ root }, File(root, "idx/kb_index.json"))
        kb.add("租房合同.md", "押金在退租后三十天内退还。".toByteArray())
        kb.add("笔记.md", "Rust 所有权笔记。".toByteArray())
        kb.refresh()
        assertEquals(contract, kb.search("押金退还", 3).first().file)
        kb.cite(listOf(contract, contract))
        assertEquals(1, kb.files().first { it.path == contract }.cites)
        assertTrue(File(root, "idx/${KnowledgeBase.STATS}").isFile)
        assertTrue(kb.files().all { it.indexed && it.chunks == 1 })
        assertTrue(kb.delete(contract))
        assertTrue(kb.search("押金退还", 3).none { it.file == contract })
        assertEquals(listOf("笔记.md"), kb.files().map { it.path })
        assertTrue(kb.chunksOf(contract).isEmpty())
    }

    @Test fun sourcesLabelAndCompat() {
        val l = KbSource.label(KbHit(contract, "x", 1.0, 2))
        assertEquals("租房合同.md · 第 3 段", l)
        assertEquals("出处：租房合同.md · 第 3 段", KbSource.chip(l))
        assertEquals(contract to 2, KbSource.parse(l))
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val old = json.decodeFromString(ChatMessage.serializer(), """{"id":"1","role":"ASSISTANT","content":"hi","kbRefs":2,"createdAt":1700000000000}""")
        assertEquals(emptyList<String>(), old.kbSources)
        // 2.3.4: fixed createdAt (the default is "now", which made the round trip flaky across a millisecond).
        assertEquals(1_700_000_000_000L, old.createdAt)
        val m = old.copy(kbSources = listOf(l), createdAt = 1_700_000_000_000L)
        assertEquals(m, json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), m)))
        // Voice: half the characters.
        val hits = listOf(KbHit(contract, "很长".repeat(500), 2.0, 0))
        assertTrue(KbPrompt.inject(hits, KbPrompt.INJECT_CHARS / 2).length < KbPrompt.inject(hits).length)
    }
}

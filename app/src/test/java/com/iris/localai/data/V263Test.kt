package com.iris.localai.data

import com.iris.localai.data.ChatExport
import com.iris.localai.data.ChatMessage
import com.iris.localai.data.Conversation
import com.iris.localai.data.HistorySearch
import com.iris.localai.data.PartCleanup
import com.iris.localai.data.Role
import com.iris.localai.data.UpdateNotes
import com.iris.localai.ui.components.FoldMotion
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.TimeZone

class V263Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()
    private fun main(p: String) = src("src/main/java/com/iris/localai/$p")
    private val utc = TimeZone.getTimeZone("UTC")


    // ---- 1. 清除已下载
    @Test fun partVictimsMatchPartsAndSegmentsOnly() {
        val names = listOf("a.gguf.part", "a.gguf.part.s0", "a.gguf.part.s1048576", "a.gguf.part.sx", "a.gguf", "b.gguf.part", "a.gguf.part.s")
        assertEquals(listOf("a.gguf.part", "a.gguf.part.s0", "a.gguf.part.s1048576"), PartCleanup.victims(names, listOf("a.gguf.part")))
        assertTrue(PartCleanup.victims(names, emptyList()).isEmpty())
    }

    @Test fun discardDeletesFilesFoldersAndEmptiedModelFolder() {
        val root = kotlin.io.path.createTempDirectory("v263").toFile()
        val dir = File(root, "Qwen").apply { mkdirs() }
        File(dir, "q.gguf.part").writeBytes(ByteArray(100))
        File(dir, "q.gguf.part.s0").writeBytes(ByteArray(50))
        File(dir, "mm.gguf.part").apply { mkdirs(); File(this, "x").writeBytes(ByteArray(7)) }
        val freed = PartCleanup.discard(dir, listOf("q.gguf.part", "mm.gguf.part"), keepDir = root)
        assertEquals(157L, freed)
        assertFalse(dir.exists()) // emptied model folder removed
        assertTrue(root.exists())
        // the models root itself is never removed, other files stay
        File(root, "keep.gguf").writeText("k"); File(root, "z.part").writeText("zz")
        PartCleanup.discard(root, listOf("z.part"), keepDir = root)
        assertTrue(File(root, "keep.gguf").exists()); assertFalse(File(root, "z.part").exists())
        root.deleteRecursively()
    }



    // ---- 3. 全文搜索
    @Test fun plainDropsThinking() {
        assertEquals("答案 在这里", HistorySearch.plain("<think>想一想 苹果</think>答案\n\n在这里"))
        assertEquals("前面", HistorySearch.plain("前面<think>没写完"))
    }

    @Test fun snippetHighlightsEveryTerm() {
        val text = "x".repeat(100) + "Apple pie and banana" + "y".repeat(100)
        val (snip, ranges) = HistorySearch.snippet(text, listOf("apple", "BANANA"))!!
        assertTrue(snip.startsWith("…")); assertTrue(snip.endsWith("…"))
        assertEquals(2, ranges.size)
        assertEquals("Apple", snip.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("banana", snip.substring(ranges[1].first, ranges[1].last + 1))
        assertNull(HistorySearch.snippet(text, listOf("apple", "cherry")))
        assertEquals(listOf(0..5, 7..8), HistorySearch.merge(listOf(3..5, 0..3, 7..8)))
    }

    @Test fun searchOrdersAndLimits() {
        val old = HistorySearch.Doc("a", "旧", 1, listOf(HistorySearch.Msg("m1", true, "关于 Kotlin 的问题")))
        val new = HistorySearch.Doc("b", "新", 2, (1..5).map { HistorySearch.Msg("n$it", it % 2 == 0, "kotlin 第$it 条") })
        val hits = HistorySearch.search(listOf(old, new), " Kotlin ")
        assertEquals(HistorySearch.PER_CONVERSATION + 1, hits.size)
        assertEquals("b", hits.first().conversationId)
        assertEquals("n5", hits.first().messageId) // newest message first
        assertEquals("a", hits.last().conversationId)
        assertTrue(HistorySearch.search(listOf(old, new), "   ").isEmpty())
        assertTrue(HistorySearch.search(listOf(old, new), "kotlin 问题").single().messageId == "m1")
        val conv = Conversation(id = "c", title = "T", messages = listOf(ChatMessage(id = "x", role = Role.ASSISTANT, content = "<think>秘密</think>公开")))
        assertTrue(HistorySearch.search(listOf(HistorySearch.docOf(conv)), "秘密").isEmpty())
        assertEquals(1, HistorySearch.search(listOf(HistorySearch.docOf(conv)), "公开").size)
    }


    // ---- 4. 多选导出
    private val conv = Conversation(
        id = "c1", title = "测试/对话", createdAt = 0, updatedAt = 0,
        messages = listOf(
            ChatMessage(id = "u", role = Role.USER, content = "你好", createdAt = 60_000),
            ChatMessage(id = "a", role = Role.ASSISTANT, content = "<think>想</think>你好！", createdAt = 120_000, modelName = "Qwen"),
        ),
    )

    @Test fun markdownAndTxt() {
        val md = ChatExport.markdown(listOf(conv), "我", "助手", "新对话", utc)
        assertTrue(md.startsWith("# 测试/对话\n"))
        assertTrue(md.contains("**我** · 1970-01-01 00:01\n\n你好\n"))
        assertTrue(md.contains("**助手 · Qwen** · 1970-01-01 00:02\n\n你好！"))
        assertFalse(md.contains("想"))
        val two = ChatExport.markdown(listOf(conv, conv.copy(title = "")), "我", "助手", "新对话", utc)
        assertTrue(two.contains("\n\n---\n\n# 新对话"))
        val txt = ChatExport.txt(listOf(conv), "我", "助手", "新对话", utc)
        assertTrue(txt.contains("[我 1970-01-01 00:01]\n你好"))
        assertFalse(txt.contains("<think>"))
    }

    @Test fun jsonRoundTrips() {
        val j = ChatExport.json(listOf(conv), "2.6.3", 5)
        val f = kotlinx.serialization.json.Json.decodeFromString(ChatExport.ExportFile.serializer(), j)
        assertEquals("IRIDIS", f.app); assertEquals("2.6.3", f.version)
        assertEquals(listOf("user", "assistant"), f.conversations.single().messages.map { it.role })
        assertEquals("你好！", f.conversations.single().messages[1].content)
        assertEquals("Qwen", f.conversations.single().messages[1].model)
    }


    @Test fun stringsInSevenLanguages() {
        for (loc in listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")) {
            val x = src("src/main/res/$loc/strings.xml")
            for (k in listOf("dl263_discard", "dl263_discard_msg", "hs263_in_messages", "ex263_user", "ex263_assistant", "ex263_share", "ex263_failed", "ex263_txt"))
                assertTrue("$loc $k", x.contains("name=\"$k\""))
        }
    }
}

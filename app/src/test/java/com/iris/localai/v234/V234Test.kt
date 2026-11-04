package com.iris.localai.v234

import com.iris.localai.character.CardPrompt
import com.iris.localai.character.ModelRestore
import com.iris.localai.knowledge.KbQuery
import com.iris.localai.knowledge.KbRefs
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.ui.components.MorphGeometry
import com.iris.localai.voice.VoiceBinding
import com.iris.localai.voice.VoiceOverride
import com.iris.localai.voice.VoiceSel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V234Test {
    @Test fun pureAckSkipsOnlyWholeMessage() {
        for (q in listOf("好的", "继续", "嗯", "OK", "ok!", "thanks", "Thank you", "好的，继续", "谢谢！", "没问题", "  收到。 ", "go on", "got it"))
            assertTrue(q, KbQuery.isPureAck(q) && KbQuery.effective(q).isEmpty())
        for (q in listOf("问题", "有个问题", "可以吗", "可以帮我查合同吗", "对吗", "行不行", "对，租金多少", "好的，那押金呢？", "继续讲第三条",
                "ok what about the deposit", "这个问题怎么解决", "可以"))
            assertFalse(q, KbQuery.effective(q).isEmpty())
    }

    @Test fun previewUsesGlobalVoice() {
        val g = VoiceSel("kokoro", 3, "")
        val o = VoiceOverride("melo", 1, "clone1")
        assertEquals(g, VoiceBinding.effective(g, VoiceBinding.applicable(o, globalOnly = true)))
        assertEquals("melo", VoiceBinding.effective(g, VoiceBinding.applicable(o, globalOnly = false)).pack)
    }

    @Test fun modelRestoreOffer() {
        assertEquals("/m/a.gguf", ModelRestore.offer("card1", null, "/m/a.gguf", "/m/b.gguf"))
        assertNull(ModelRestore.offer("card1", null, "/m/a.gguf", "/m/a.gguf")) // same model
        assertNull(ModelRestore.offer("card1", null, null, "/m/b.gguf"))        // nothing remembered
        assertNull(ModelRestore.offer("card1", "card2", "/m/a.gguf", "/m/b.gguf")) // card → card
        assertNull(ModelRestore.offer(null, null, "/m/a.gguf", "/m/b.gguf"))
    }

    @Test fun refRemovalNeverDeletesAndRestores() {
        val root = Files.createTempDirectory("kb234").toFile()
        val idx = File(root, "app/idx.json")
        val refs = KbRefs(File(root, "app/refs.txt"))
        assertTrue(refs.hide("doc/a.md", 1000L))
        assertEquals(setOf("doc/a.md"), refs.hidden())
        val it = refs.items().single()
        assertEquals("doc/a.md", it.path)
        assertEquals("doc/a.md", refs.restore(it.id))
        assertTrue(refs.hidden().isEmpty())
        refs.hide("b.md", 0L); assertEquals(1, refs.purge(now = KnowledgeBaseKeep + 1))
        assertTrue("purged stays hidden", "b.md" in refs.hidden()); assertTrue(refs.items().isEmpty())
        // A file seen only through the SAF grant (content://, the user's own file) is hidden, never deleted.
        var deletes = 0
        val ext = object : com.iris.localai.agent.ExternalFiles {
            override fun list(relDir: String) = if (relDir == "knowledge") listOf(com.iris.localai.agent.ExternalFiles.Entry("saf.md", false, 6)) else emptyList()
            override fun read(relPath: String, maxBytes: Long) = "原文".toByteArray()
            override fun delete(relPath: String): Boolean { deletes++; return true }
        }
        val kb = KnowledgeBase({ File(root, "store") }, idx, { ext })
        try {
            assertTrue(kb.scan().first.any { it.path == "saf.md" })
            assertTrue(kb.delete("saf.md"))
            assertEquals("original never deleted", 0, deletes)
            assertTrue(kb.scan().first.none { it.path == "saf.md" })
            val ref = kb.trashItems().first { it.id.startsWith(KbRefs.PREFIX) }
            assertEquals("saf.md", kb.restore(ref.id))
            assertTrue(kb.scan().first.any { it.path == "saf.md" })
        } finally { root.deleteRecursively() }
    }

    @Test fun morphPanelClampedOnScreen() {
        val d = 3f; val screen = 360 * d; val m = 16 * d; val max = 280 * d
        // pill on the right (角色): expands leftward, right edge = pill right edge, inside margins
        val (w, l) = MorphGeometry.place(pillLeft = 290 * d, pillW = 48 * d, screenW = screen, margin = m, maxW = max)
        assertEquals(max, w, 0.01f); assertEquals(338 * d - max, l, 0.01f)
        assertTrue(l >= m && l + w <= screen - m + 0.01f)
        // pill on the left (模型): grows rightward from its left edge
        assertEquals(20 * d, MorphGeometry.place(20 * d, 120 * d, screen, m, max).second, 0.01f)
        // narrow screen: width = screen − 32dp
        val (w2, l2) = MorphGeometry.place(200 * d, 48 * d, 280 * d, m, max)
        assertEquals(248 * d, w2, 0.01f); assertEquals(m, l2, 0.01f)
    }

    @Test fun cardTurnSuffixHasPostHistory() {
        val card = com.iris.localai.character.CardSpec.parse("""{"spec":"chara_card_v2","data":{"name":"Ann","post_history_instructions":"Stay in character, {{user}}."}}""")
        assertEquals("[Instruction]\nStay in character, Bob.", CardPrompt.turnSuffix(card, "Bob", listOf("hi"), 100))
    }

    private val KnowledgeBaseKeep = com.iris.localai.knowledge.KbTrash.KEEP_MS
}

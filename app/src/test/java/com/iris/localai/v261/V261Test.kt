package com.iris.localai.v261

import com.iris.localai.data.GenerationStats
import com.iris.localai.data.UpdateNotes
import com.iris.localai.data.plusPass
import com.iris.localai.knowledge.KbChunk
import com.iris.localai.knowledge.KbCover
import com.iris.localai.knowledge.KbHit
import com.iris.localai.knowledge.KbVectors
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V261Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()

    @Test fun version() {
        // 2.6.2: no longer the newest release.
        assertTrue(UpdateNotes.RELEASES.any { it.version == "2.6.1" })
        assertTrue(src("CHANGELOG.md").contains("## 2.6.1 Beta"))
    }

    @Test fun statsMergeTwoPasses() {
        val a = GenerationStats(promptTokens = 50, generatedTokens = 900, generateMs = 12_000)
        val b = GenerationStats(promptTokens = 950, generatedTokens = 6, generateMs = 90)
        val m = a.plusPass(b)!!
        assertEquals(906, m.generatedTokens)
        assertEquals(12_090L, m.generateMs)
        assertEquals(50, m.promptTokens)
        assertSame(b, (null as GenerationStats?).plusPass(b))
        assertSame(a, a.plusPass(null))
    }

    @Test fun keywordHitSurvivesLowCosine() {
        val chunks = listOf(KbChunk("a.md", "t", 0))
        val cover = KbCover(2, 2, 1.0, 0.5, false, false)
        val out = KbVectors.select(chunks, listOf(KbHit("a.md", "t", 3.0, 0, cover = cover)), doubleArrayOf(0.2), 0.45)
        assertEquals(listOf("a.md"), out.map { it.file })
    }

    @Test fun thinkPillAnimatesAndKbRowGrouped() {
        val bar = src("src/main/java/com/iris/localai/ui/chat/ThinkLevelBar.kt")
        assertTrue("scaleIn(" in bar && "MutableTransitionState" in bar)
        assertTrue("KbEmbedRow(agent.knowledge, inGroup = true)" in src("src/main/java/com/iris/localai/ui/agent/KnowledgePage.kt"))
    }
}

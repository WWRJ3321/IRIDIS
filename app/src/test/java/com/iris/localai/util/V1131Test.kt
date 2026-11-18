package com.iris.localai.util

import com.iris.localai.engine.load.ModelCache
import com.iris.localai.util.streaming.ReplySpeed
import com.iris.localai.util.streaming.ReplyTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V1131Test {
    @Test fun longPausesDoNotCountAsOutputTime() {
        var now = 0L
        val t = ReplyTimer { now }
        now = 1_000; t.onText("你好")
        now = 1_500; t.onText("世界")
        now = 31_500; t.onText("工具后")    // 30 s tool run → counts 2.5 s
        now = 32_000; t.finish()
        assertEquals(500L + ReplyTimer.GAP_CAP_MS + 500L, t.outputMs())
        assertEquals(32_000L, t.totalMs())
    }

    @Test fun formatAddsEngineTokenRate() {
        assertEquals("25 字/秒 · 12.5 tok/s · 用时 4.5s", ReplySpeed.format(100, 4000, 4500, 12.5f))
        assertEquals("25 字/秒 · 用时 4.5s", ReplySpeed.format(100, 4000, 4500))
    }

    @Test fun litertCacheFilesMatchOnlyTheirModel() {
        assertTrue(ModelCache.belongs("gemma-4-E2B.litertlm_1760000000_2754000000_mldrift_program_cache.bin", "gemma-4-E2B.litertlm"))
        assertFalse(ModelCache.belongs("gemma-4-E2B.litertlm.bak_1_2", "gemma-4-E2B.litertlm"))
        assertFalse(ModelCache.belongs("gemma-4-E2B.litertlm_x", "gemma-4-E2B.litertlm"))
        val root = Files.createTempDirectory("lc").toFile()
        File(root, "a.litertlm_1_2_xnnpack").writeText("12345")
        File(root, "b.litertlm_1_2_xnnpack").writeText("1")
        val mine = ModelCache.litert(root, "a.litertlm")
        assertEquals(1, mine.size)
        assertEquals(5L, ModelCache.bytes(mine))
    }
}

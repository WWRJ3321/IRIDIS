package com.iris.localai.v260

import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.ThinkLevel
import com.iris.localai.ui.chat.ThinkLevelBar
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V260Test {
    private fun src(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }.readText()

    @Test fun budgetsPerLevel() {
        assertEquals(1024, ThinkLevel.budget(1, 2048))
        assertEquals(4096, ThinkLevel.budget(2, 2048))
        assertEquals(0, ThinkLevel.budget(3, 2048))
        assertEquals(2048, ThinkLevel.budget(0, 2048))
        // models with their own effort are not capped
        assertEquals(0, ThinkLevel.budget(1, 2048, "oss"))
        assertEquals(0, ThinkLevel.budget(0, 2048, "spark"))
    }

    @Test fun effortOnlyForModelsWithLevels() {
        assertEquals(0, ThinkLevel.effort(2, ""))
        assertEquals(1, ThinkLevel.effort(1, "oss"))
        assertEquals(3, ThinkLevel.effort(3, "spark"))
        assertEquals(0, ThinkLevel.effort(0, "oss"))
    }

    @Test fun barIndexAndCurrent() {
        // 2.6.1: three segments 低 中 高 → levels 1..3; 关 is the tap, not in the bar
        assertEquals(1, ThinkLevelBar.indexAt(-5f, 100f))
        assertEquals(1, ThinkLevelBar.indexAt(50f, 100f))
        assertEquals(3, ThinkLevelBar.indexAt(250f, 100f))
        assertEquals(3, ThinkLevelBar.indexAt(999f, 100f))
        assertEquals(3, ThinkLevelBar.current(false, 3))
        assertEquals(2, ThinkLevelBar.current(true, 0))
        assertEquals(1, ThinkLevelBar.current(true, 1))
    }

    @Test fun nativeEffortWired() {
        val eng = src("src/main/cpp/iris_engine.cpp")
        assertTrue(eng.contains("is_oss_template"))
        assertTrue(eng.contains("Reasoning: "))
        assertTrue(eng.contains("thinking_effort=\" + std::string(effort == 1 ? \"low\" : \"high\")"))
        val jni = src("src/main/cpp/jni_bridge.cpp")
        assertTrue(jni.contains("sp.think_effort = std::clamp<jint>(i[8], 0, 3);"))
        assertTrue(jni.contains("thinkEffort"))
        assertTrue(src("src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt").contains("params.keepTokens, params.thinkEffort"))
    }

    @Test fun composerUsesLongPressPill() {
        val c = src("src/main/java/com/iris/localai/ui/chat/Composer.kt")
        assertTrue(c.contains("ThinkPill("))
        assertTrue(c.contains("onPick = onThinkLevel"))
    }

    @Test fun releaseNotes() {
        assertNotNull(UpdateNotes.find("2.6.0"))
        assertEquals("2.6.0", UpdateNotes.base("2.6.0-beta"))
    }
}

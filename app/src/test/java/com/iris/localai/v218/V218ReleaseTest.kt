package com.iris.localai.v218

import com.iris.localai.engine.WarmUpGuard
import com.iris.localai.imagegen.IdleReleaser
import com.iris.localai.ui.chat.ReplyWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.8: fix/voice-echo + fix/send-hang + fix/search-query + perf/imagegen merged on release/2.1.7. */
class V218ReleaseTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }
    private fun src(p: String) = file("src/main/java/com/iris/localai/$p").readText()

    @Test fun version() {
        val gradle = listOf("app/build.gradle.kts", "build.gradle.kts").map { File(it) }.first { it.exists() }.readText()
        // 2.1.9+: later releases keep bumping
        assertTrue(Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toInt() >= 186)
    }

    @Test fun mergedFixesPresent() {
        // fix/voice-echo
        assertTrue(src("voice/EchoGuard.kt").contains("class HalfDuplexGate"))
        assertTrue(src("voice/EchoGuard.kt").contains("class EchoTextGuard"))
        // fix/send-hang
        assertEquals(90_000L, ReplyWatchdog.FIRST_OUTPUT_MS)
        assertEquals(60_000L, WarmUpGuard.TIMEOUT_MS)
        assertTrue(src("engine/EngineManager.kt").contains("WarmUpGuard"))
        // perf/imagegen
        assertEquals(60_000L, IdleReleaser.DEFAULT_TIMEOUT_MS)
        assertTrue(src("ui/imagegen/ImageGenViewModel.kt").contains("releaseWhenIdle()"))
    }

    @Test fun docsExist() {
        val doc = listOf("docs/changelog/PROGRESS-2.1.8.md", "../docs/changelog/PROGRESS-2.1.8.md").map { File(it) }.first { it.exists() }.readText()
        assertTrue(doc.contains("半双工"))
        assertTrue(doc.contains("看门狗"))
        val cl = listOf("CHANGELOG.md", "../CHANGELOG.md").map { File(it) }.first { it.exists() }.readText()
        assertTrue(cl.contains("## 2.1.8"))
    }
}

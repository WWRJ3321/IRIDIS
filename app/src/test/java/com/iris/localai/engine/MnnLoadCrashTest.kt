package com.iris.localai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Startup crash loop: SIGSEGV inside MnnNative_nativeLoad on SM8750 must not reload forever. */
class MnnLoadCrashTest {
    private fun file(p: String) = File(p).let { if (it.exists()) it else File("app/$p") }

    @Test fun oryonDisablesKleidi() {
        assertFalse(MnnKleidiPolicy.enabled("SM8750"))
        assertFalse(MnnKleidiPolicy.enabled("SM8750P"))
        assertFalse(MnnKleidiPolicy.enabled("sm8750-ab"))
        assertFalse(MnnKleidiPolicy.enabled("SM8850"))
        assertTrue(MnnKleidiPolicy.enabled("SM8650"))
        assertTrue(MnnKleidiPolicy.enabled(null))
        assertTrue(MnnKleidiPolicy.enabled(""))
        assertFalse(MnnKleidiPolicy.enabled("SM8650", lastNativeLoadCrashed = true))
    }

    @Test fun tombstoneAndBreadcrumb() {
        val tomb = """
            iris-mnn
            Java_com_iris_localai_engine_mnn_MnnNative_nativeLoad(
            libiris_mnn.so
        """.trimIndent()
        assertTrue(NativeLoadCrash.traceShowsLoad(tomb))
        assertFalse(NativeLoadCrash.traceShowsLoad("iris-mnn\nlibiris_mnn.so"))
        assertTrue(NativeLoadCrash.breadcrumbShowsLoad("2026-10-23 22:16:16 加载模型 qwen.mnn"))
        assertFalse(NativeLoadCrash.breadcrumbShowsLoad("2026-10-23 22:16:16 加载语音识别 sense-voice"))
        assertFalse(NativeLoadCrash.breadcrumbShowsLoad(null))
        assertTrue(NativeLoadCrash.hit(tomb, null, latestExitNative = false))
        assertFalse(NativeLoadCrash.hit("", "加载模型 a.mnn", latestExitNative = false))
        assertTrue(NativeLoadCrash.hit("", "加载模型 a.mnn", latestExitNative = true))
        assertTrue(NativeLoadCrash.breadcrumbOnly("", "加载模型 a.mnn", latestExitNative = true))
        assertFalse(NativeLoadCrash.breadcrumbOnly(tomb, "加载模型 a.mnn", latestExitNative = true))
        assertFalse(NativeLoadCrash.hit("libhwui.so", "加载语音合成 x", latestExitNative = true))
        assertEquals(LoadCrashGate.SKIP_NOTICE, LoadCrashGate.notice(breadcrumbOnly = false))
        assertEquals(LoadCrashGate.SKIP_NOTICE_NO_SYMBOL, LoadCrashGate.notice(breadcrumbOnly = true))
    }

    @Test fun latchSkipsUntilALoadSucceeds() {
        assertFalse(LoadCrashGate.skipPreload(persisted = false, crashedAgain = false))
        assertTrue(LoadCrashGate.skipPreload(persisted = false, crashedAgain = true))
        assertTrue(LoadCrashGate.skipPreload(persisted = true, crashedAgain = false))
        assertEquals(true, LoadCrashGate.latch(persisted = false, crashedAgain = true, loadSucceeded = false))
        assertEquals(true, LoadCrashGate.latch(persisted = true, crashedAgain = false, loadSucceeded = false))
        assertEquals(false, LoadCrashGate.latch(persisted = true, crashedAgain = true, loadSucceeded = true))
        assertEquals(PreloadDecision.SKIP_AFTER_NATIVE_CRASH, PreloadPolicy.decide(true, "a.mnn", true, true, true))
        assertEquals(PreloadDecision.LOAD, PreloadPolicy.decide(true, "a.mnn", true, true, false))
    }

    @Test fun loadPathCarriesTheGuard() {
        val em = file("src/main/java/com/iris/localai/engine/EngineManager.kt").readText()
        assertTrue(em.contains("startupGuard.blockAutoLoad()"))
        assertTrue(em.contains("加载模型 "))
        assertTrue(em.contains("startupGuard.noteLoadSucceeded()"))
        assertTrue(em.contains("_preloadSkipNotice.value = startupGuard.skipNotice()"))
        val guard = file("src/main/java/com/iris/localai/engine/load/StartupLoadGuard.kt").readText()
        assertTrue(guard.contains(".commit()"))
        assertFalse(guard.contains(".apply()"))
        val vm = file("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt").readText()
        assertTrue(vm.contains("c.engine.preloadSkipNotice.collect"))
        val mnn = file("src/full/java/com/iris/localai/engine/mnn/MnnEngine.kt").readText()
        assertTrue(mnn.contains("MnnKleidiPolicy.enabled"))
        assertTrue(mnn.contains("|kleidi="))
        val cpp = file("src/main/cpp/mnn/mnn_engine.cpp").readText()
        assertTrue(cpp.contains("kleidi="))
        assertTrue(cpp.contains("backendType == \"cpu\" && kleidi"))
    }
}

package com.iris.localai.v213

import com.iris.localai.data.AppSettings
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelOverrides
import com.iris.localai.data.migrated
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.NpuState
import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GenieXRules
import com.iris.localai.engine.geniex.GgufEngineRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.1.3 界面简化: GGUF engine follows the backend; settings entries merged / moved; one load indicator. */
class V213SimplifyTest {
    private fun source(path: String): String =
        listOf(path, "app/$path").map { java.io.File(it) }.first { it.exists() }.readText()

    private val ready = BackendEnvironment(hasOpenCl = true, npuSoc = "sm8750", npuRuntimeInstalled = false, fullFlavor = true, geniexBlock = null, geniexQairt = true)

    // ---------------------------------------------------------------- GGUF engine rule

    @Test fun backendDecidesTheGgufEngine() {
        assertEquals(GenieX.ENGINE_GENIEX, GgufEngineRule.engineFor(Backend.NPU, geniexReady = true))
        assertEquals(GenieX.ENGINE_LLAMA, GgufEngineRule.engineFor(Backend.NPU, geniexReady = false))
        assertEquals(GenieX.ENGINE_LLAMA, GgufEngineRule.engineFor(Backend.GPU, geniexReady = true))
        assertEquals(GenieX.ENGINE_LLAMA, GgufEngineRule.engineFor(Backend.CPU, geniexReady = true))
    }

    @Test fun npuOnlyWhenGenieXIsUsable() {
        val o = BackendCapabilities.options(ModelFormat.GGUF, "Qwen3-4B-Q4_0.gguf", ready)
        assertTrue(o.all { it.available })
        assertTrue(o.first { it.backend == Backend.NPU }.detail.startsWith("GenieX"))
        // 自动 still means CPU for GGUF (default unchanged)
        assertEquals(Backend.CPU, BackendCapabilities.autoBackend(ModelFormat.GGUF, "Qwen3-4B-Q4_0.gguf", o))
        // runtime not downloaded: NPU greyed out with a short reason; CPU / GPU still fine
        val missing = BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", ready.copy(geniexBlock = GenieXRules.block(NpuState.NotInstalled)))
        val npu = missing.first { it.backend == Backend.NPU }
        assertFalse(npu.available)
        assertEquals("需先在 设置 › 模型与后端 下载 GenieX", npu.reason)
        assertTrue(missing.first { it.backend == Backend.CPU }.available)
        assertTrue(missing.first { it.backend == Backend.GPU }.available)
        // lite build: no GenieX at all
        val lite = BackendEnvironment(hasOpenCl = true, npuSoc = null, npuRuntimeInstalled = false, fullFlavor = false)
        assertEquals(GenieX.NOT_IN_BUILD, BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", lite).first { it.backend == Backend.NPU }.reason)
        // QAIRT bundles still NPU-only on GenieX
        assertEquals(listOf(Backend.NPU), BackendCapabilities.options(ModelFormat.QAIRT, "x_genie_bundle", ready).filter { it.available }.map { it.backend })
    }

    @Test fun oldEngineOverridesMigrateSafely() {
        assertEquals(ModelOverrides(backend = "NPU"), GgufEngineRule.migrate(ModelOverrides(engine = "geniex")))
        assertEquals(ModelOverrides(backend = "GPU"), GgufEngineRule.migrate(ModelOverrides(engine = "geniex", backend = "GPU")))
        assertTrue(GgufEngineRule.migrate(ModelOverrides(engine = "llama")).isEmpty)
        assertEquals(ModelOverrides(topK = 5), GgufEngineRule.migrate(ModelOverrides(topK = 5)))
        assertNull(GgufEngineRule.migrate(ModelOverrides(engine = "geniex", temperature = 0.3f)).engine)
    }

    @Test fun oldGlobalGenieXDefaultBecomesNpuBackend() {
        val m = AppSettings(ggufEngine = "geniex").migrated()
        assertEquals("NPU", m.defaultBackends["GGUF"])
        assertEquals(GenieX.ENGINE_LLAMA, m.ggufEngine)
        val keep = AppSettings(ggufEngine = "geniex", defaultBackends = mapOf("GGUF" to "GPU")).migrated()
        assertEquals("GPU", keep.defaultBackends["GGUF"])
        assertNull(AppSettings().migrated().defaultBackends["GGUF"])
        assertEquals("llama", AppSettings().ggufEngine) // default unchanged
    }

    @Test fun modelSheetHasNoEngineSwitch() {
        val sheet = source("src/main/java/com/iris/localai/ui/models/ModelConfigSheet.kt")
        assertFalse(sheet.contains("\"推理引擎\""))
        assertFalse(sheet.contains("跟随默认"))
        assertFalse(source("src/main/java/com/iris/localai/ui/settings/GenieXSettings.kt").contains("GGUF 默认引擎"))
        val mgr = source("src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(mgr.contains("engineFor(model, cfg, backend, options)"))
    }

    // ---------------------------------------------------------------- settings layout

    @Test fun settingsEntriesMergedAndMoved() {
        val src = source("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        val home = src.substringAfter("fun SettingsHome(").substringBefore("/** 设置 › 模型与后端. */")
        assertTrue(home.contains("NavItem(ContextMemoryText.NAV"))
        assertFalse(home.contains("NavItem(\"上下文与记忆\""))
        assertFalse(home.contains("NavItem(\"记忆\""))
        assertFalse(home.contains("RuntimeRack("))
        assertFalse(home.contains("SettingsPage.DIAGNOSTICS"))
        val perf = src.substringAfter("fun PerformancePage(").substringBefore("fun AboutPage(")
        assertFalse(perf.contains("SettingsPage.TIER_PROFILES"))
        assertTrue(perf.contains("TierSlider(")) // 低速 / 高速 / 极速 stays
        val about = src.substringAfter("fun AboutPage(").substringBefore("internal const val ABOUT_PRIVACY")
        assertTrue(about.contains("NavItem(\"开发者选项\""))
        val dev = src.substringAfter("fun DeveloperPage(")
        for (t in listOf("SettingsPage.TIER_PROFILES", "SettingsPage.DIAGNOSTICS", "\"运行状态机架\"", "\"性能浮窗\"", "\"顶栏按钮底色\"", "RuntimeRack(vm, onOpen)"))
            assertTrue(t, dev.contains(t))
        val appearance = src.substringAfter("fun AppearancePage(").substringBefore("fun DeveloperPage(")
        for (t in listOf("\"运行状态机架\"", "\"顶栏按钮底色\"", "SwitchRow(\"性能浮窗\"")) assertFalse(t, appearance.contains(t))
        assertTrue(com.iris.localai.ui.settings.SettingsPage.entries.any { it.name == "DEVELOPER" })
        val main = source("src/main/java/com/iris/localai/ui/MainActivity.kt")
        assertTrue(main.contains("SettingsPage.DEVELOPER -> Route.DEVELOPER"))
        assertTrue(main.contains("Route.CONTEXT, Route.MEMORY -> {"))
        // defaults unchanged
        val s = AppSettings()
        assertTrue(s.showStatusRack); assertFalse(s.perfOverlay); assertFalse(s.topBarCapsules)
    }

    @Test fun rarelyUsedOptionsAreFolded() {
        val src = source("src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        val load = src.substringAfter("AdvancedFold(\"load\"").substringBefore("GroupFootnote(\"修改后需重新加载模型。\")")
        for (t in listOf("\"Flash Attention\"", "\"mmap 加载\"", "\"加载后预热\"")) assertTrue(t, load.contains(t))
        val gen = src.substringAfter("fun GenerationPage(")
        val visible = gen.substringBefore("AdvancedFold(\"sampling\"")
        val folded = gen.substringAfter("AdvancedFold(\"sampling\"").substringBefore("GroupFootnote(")
        assertTrue(visible.contains("\"最大回复长度\"") && visible.contains("\"温度\""))
        for (t in listOf("\"Top-P\"", "\"Top-K\"", "\"Min-P\"", "\"重复惩罚\"", "\"惩罚窗口\"", "\"Presence 惩罚\"", "\"Frequency 惩罚\"", "\"固定随机种子\"")) {
            assertTrue(t, folded.contains(t)); assertFalse(t, visible.contains(t))
        }
    }

    // ---------------------------------------------------------------- chat loading status

    @Test fun oneLoadingIndicator() {
        val chat = source("src/main/java/com/iris/localai/ui/chat/ChatScreen.kt")
        assertFalse(chat.contains("IrisProgressBar("))
        assertTrue(chat.contains("is EngineState.Loading -> com.iris.localai.engine.SlowLoadHint.subtitle(null, 0)"))
        assertEquals("加载中", com.iris.localai.engine.SlowLoadHint.subtitle(null, 0))
        assertTrue(chat.contains("ActivityIndicator(ui.engine, act, backdrop, onCancelLoad = vm::cancelLoad"))
    }

    // ---------------------------------------------------------------- API bind address (bug fix)

    @Test fun apiBindsIpv4Loopback() {
        assertEquals("127.0.0.1", com.iris.localai.server.ApiPolicy.bindHost(lan = false))
        assertEquals("0.0.0.0", com.iris.localai.server.ApiPolicy.bindHost(lan = true))
        assertTrue(java.net.InetAddress.getByName(com.iris.localai.server.ApiPolicy.bindHost(false)) is java.net.Inet4Address)
        val svc = source("src/main/java/com/iris/localai/server/ApiService.kt")
        assertFalse(svc.contains("getLoopbackAddress"))
        assertTrue(svc.contains("InetAddress.getByName(ApiPolicy.bindHost(lan))"))
    }

    @Test fun ipv4ClientReachesTheBoundServer() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = com.iris.localai.server.HttpServer(java.net.InetAddress.getByName(com.iris.localai.server.ApiPolicy.bindHost(false)), port) { _, _ -> }
        server.start()
        try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 2000); assertTrue(it.isConnected) }
        } finally { server.stop() }
    }
}

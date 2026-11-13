package com.iris.localai.v254

import com.iris.localai.engine.GgufQuant
import com.iris.localai.engine.MoeLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.4：① LES 决策（只 qwen35moe；null = 跟随专家按需加载）；② LES 接线（JNI / 原生 / 补丁）；
 * ③ 下载源统一入口；④ Three.js 预览按尺寸加载；⑤ 7 语言字符串。
 */
class V254Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")

    private val q35 = GgufQuant.Info(types = emptySet(), arch = "qwen35moe", expertCount = 256, expertUsed = 8)
    private val q3 = GgufQuant.Info(types = emptySet(), arch = "qwen3moe", expertCount = 128, expertUsed = 8)
    private val dense = GgufQuant.Info(types = emptySet(), arch = "qwen35moe")

    @Test fun lesDecision() {
        assertTrue(MoeLoad.lesSupported(q35))
        assertFalse(MoeLoad.lesSupported(q3))
        assertFalse(MoeLoad.lesSupported(dense))
        assertFalse(MoeLoad.lesSupported(null))
        // 自动 = 跟随专家按需加载
        assertTrue(MoeLoad.lesActive(null, q35, moeActive = true))
        assertFalse(MoeLoad.lesActive(null, q35, moeActive = false))
        // 用户指定
        assertTrue(MoeLoad.lesActive(true, q35, moeActive = false))
        assertFalse(MoeLoad.lesActive(false, q35, moeActive = true))
        // 架构不符一律不开
        assertFalse(MoeLoad.lesActive(true, q3, moeActive = true))
        assertEquals("qwen35moe", MoeLoad.LES_ARCH)
    }

    @Test fun lesOverrideReloads() {
        val a = com.iris.localai.data.ModelOverrides()
        assertTrue(a.loadSignature() != a.copy(les = true).loadSignature())
        assertTrue(src("app/src/main/java/com/iris/localai/engine/EngineManager.kt")
            .contains("les = !safeMode && model.format == ModelFormat.GGUF && MoeLoad.lesActive(cfg.les, quant, moeCpu)"))
    }

    @Test fun lesWiring() {
        val jni = src("app/src/main/cpp/jni_bridge.cpp")
        assertTrue(jni.contains("IRIS_JNI(nativeSetLes)") && jni.contains("IRIS_JNI(nativeLesStatus)"))
        val eng = src("app/src/main/cpp/iris_engine.cpp")
        assertTrue(eng.contains("static std::string les_write_manifest("))
        assertTrue(eng.contains("mp.moe_experts_path = les_manifest.c_str();"))
        assertTrue(eng.contains("if (arch != \"qwen35moe\")"))
        val kt = src("app/src/main/java/com/iris/localai/engine/llama/LlamaHtp.kt")
        assertTrue(kt.contains("runCatching { LlamaNative.nativeSetLes(h, on, dir) }"))
        assertTrue(src("app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt").contains("activeBackend = if (gpu && !lesOn) accel"))
        val patch = File(root(), "native-patches/llama-les.patch")
        assertTrue(patch.isFile && patch.readText().contains("llama-moe-slot.cpp"))
        assertTrue(src("native-patches/README.md").contains("llama-les.patch"))
    }

    @Test fun downloadSourceSinglePlace() {
        val s = src("app/src/main/java/com/iris/localai/ui/settings/SettingsScreens.kt")
        assertTrue(s.contains("DOWNLOAD_SOURCE"))
        assertFalse(src("app/src/main/java/com/iris/localai/ui/models/DownloadModelsScreen.kt").contains("fun SourceChip("))
    }

    @Test fun webPreviewWaitsForSize() {
        val r = src("app/src/main/java/com/iris/localai/ui/markdown/RichViews.kt")
        assertTrue(r.contains("loadWhenSized"))
    }

    @Test fun strings() {
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in listOf("v254_les", "v254_les_auto", "v254_les_sub")) assertTrue("$d/$k", x.contains("name=\"$k\""))
        }
    }
}

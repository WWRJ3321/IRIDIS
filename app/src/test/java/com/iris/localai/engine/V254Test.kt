package com.iris.localai.engine

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





    @Test fun strings() {
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in listOf("v254_les", "v254_les_auto", "v254_les_sub")) assertTrue("$d/$k", x.contains("name=\"$k\""))
        }
    }
}

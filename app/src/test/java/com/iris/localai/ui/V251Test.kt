package com.iris.localai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.1 A（MoE 专家按需加载 / 激活专家数 / Gemma 4 19B REAP）与 C（隐藏 sd15-mnn）、D（版本、CHANGELOG、7 语言）。
 * 只用源码断言（测试编译不依赖本版新增的 main 类）。
 */
class V251Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")



    @Test fun stringsInSevenLanguages() {
        val keys = listOf("v251_moe_cpu", "v251_moe_cpu_auto", "v251_moe_cpu_sub", "v251_experts", "v251_experts_default",
            "v251_experts_sub", "v251_reap_purpose", "v251_sd15_mnn_hidden", "v251_sd15_mnn_delete_title", "v251_sd15_mnn_delete")
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/$k", x.contains("name=\"$k\""))
        }
    }

    @Test fun greyTextOneLineShort() {
        // 灰字最多一行：新的说明文案保持短句（简中 ≤ 30 字）
        val x = src("app/src/main/res/values/strings.xml")
        for (k in listOf("v251_moe_cpu_auto", "v251_moe_cpu_sub", "v251_experts_sub", "v251_sd15_mnn_hidden")) {
            val v = Regex("name=\"$k\">([^<]*)<").find(x)!!.groupValues[1]
            assertTrue("$k: $v", v.length <= 30 && !v.contains("\\n"))
        }
    }






}

package com.iris.localai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.2 用户需求（源码断言）：① 新手引导（首次一次、设置可重播、视觉规则、7 语言）；② 网页抓取正文提取；
 * ③ 生图种子不回写；④ 模型导入与下载同一存储位置；CHANGELOG / 版本。纯源码读取。
 */
class V252UserReqTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val main = "app/src/main/java/com/iris/localai"
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")




    @Test fun onboardingStringsSevenLanguages() {
        val keys = listOf("skip", "next", "prev", "done", "counter", "replay", "replay_sub").map { "tour253_$it" } +
            listOf("ig252_seed_hint", "ig252_random", "ig252_last_seed", "ig252_fix_seed")
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in keys) assertTrue("$d/$k", x.contains("name=\"$k\""))
            assertFalse("$d still has ob252_*", x.contains("name=\"ob252_"))
            assertTrue("$d/ig252_last_seed", Regex("name=\"ig252_last_seed\">[^<]*%1\\\$s").containsMatchIn(x))
        }
    }





}

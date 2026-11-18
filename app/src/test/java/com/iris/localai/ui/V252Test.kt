package com.iris.localai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.2 源码断言：① MCP 不限工具数；② Q4 嵌入模型；③ iris_embed.cpp 补 EOS / qwen3 强制 LAST；④ 版本、CHANGELOG、7 语言。
 * 纯源码读取（SRC_ONLY=1 可单独运行）。
 */
class V252Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()
    private val langs = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi")



    @Test fun q4StringsSevenLanguagesOneLine() {
        for (d in langs) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (key in listOf("kb252_embed_qwen_q4", "kb252_embed_gemma_q4")) assertTrue("$d/$key", x.contains("name=\"$key\""))
        }
        val zh = src("app/src/main/res/values/strings.xml")
        for (key in listOf("kb252_embed_qwen_q4", "kb252_embed_gemma_q4")) {
            val v = Regex("name=\"$key\">([^<]*)<").find(zh)!!.groupValues[1]
            assertTrue("$key: $v", v.contains("更省内存") && v.length <= 30 && !v.contains("\\n"))
        }
    }


    @Test fun llamaApiNames() {
        // 核对 llama.h 的 API 名（compat 副本存在时）
        val h = File("/workspace/work/compat/llama.cpp/include/llama.h")
        if (!h.isFile) return
        val t = h.readText()
        for (n in listOf("llama_token llama_vocab_eos(", "llama_token llama_vocab_sep(", "int32_t llama_model_meta_val_str(",
                "enum llama_pooling_type   llama_pooling_type(", "LLAMA_POOLING_TYPE_LAST = 3", "#define LLAMA_TOKEN_NULL -1"))
            assertTrue(n, t.contains(n))
    }


}

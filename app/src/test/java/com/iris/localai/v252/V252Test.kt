package com.iris.localai.v252

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

    // ---- ① MCP ----
    @Test fun mcpNoCap() {
        val p = src("app/src/main/java/com/iris/localai/agent/mcp/McpProtocol.kt")
        assertTrue(p.contains("const val UNLIMITED = Int.MAX_VALUE"))
        assertTrue(p.contains("const val DEFAULT = UNLIMITED"))
        assertFalse(p.contains("const val DEFAULT = 20"))
        assertTrue(src("app/src/main/java/com/iris/localai/agent/mcp/McpManager.kt").contains("fun injected(cap: Int = McpCap.DEFAULT)"))
        assertTrue(src("app/src/main/java/com/iris/localai/agent/core/AgentSettings.kt").contains("m.injected().injected"))
        val page = src("app/src/main/java/com/iris/localai/ui/agent/McpPage.kt")
        assertFalse(page.contains("McpCap"))
        assertFalse(page.contains("McpText.over") || page.contains("fun over("))
        assertFalse(page.contains("agent_c91761"))
        for (d in langs) assertFalse("$d/agent_c91761", src("app/src/main/res/$d/strings.xml").contains("name=\"agent_c91761\""))
    }

    // ---- ② Q4 嵌入模型 ----
    @Test fun q4EmbedModels() {
        val k = src("app/src/main/java/com/iris/localai/knowledge/KbEmbedModels.kt")
        assertTrue(k.contains("\"PeterAM4/Qwen3-Embedding-0.6B-GGUF\", \"Qwen3-Embedding-0.6B-Q4_K_M-imat.gguf\", 396_474_336L, QWEN3_MIN_COS"))
        assertTrue(k.contains("\"unsloth/embeddinggemma-2-GGUF\", \"embeddinggemma-2-UD-Q4_K_XL.gguf\", 175_673_856L, GEMMA_MIN_COS"))
        assertTrue(k.contains("val ALL: List<Model> = listOf(QWEN3_MODEL, QWEN3_Q4_MODEL, GEMMA_MODEL, GEMMA_Q4_MODEL)"))
        assertTrue(k.contains("\"Qwen3-Embedding-0.6B · Q8\"") && k.contains("\"Qwen3-Embedding-0.6B · Q4\""))
        assertTrue(k.contains("\"EmbeddingGemma 2 · Q8\"") && k.contains("\"EmbeddingGemma 2 · Q4\""))
        assertTrue(k.contains("family = QWEN3, quant = \"Q4_K_M\"") && k.contains("family = GEMMA, quant = \"Q4_K_XL\""))
        assertTrue(k.contains("fun query(q: String): String = when (family)") && k.contains("fun doc(t: String): String = when (family)"))
        assertTrue(k.contains("quant = m.quant, licence = \"Apache-2.0\""))
        assertTrue(k.contains("Section.EMBED"))
    }

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

    // ---- ③ 原生嵌入器 ----
    @Test fun embedderEosAndQwen3Last() {
        val c = src("app/src/main/cpp/iris_embed.cpp")
        assertTrue(c.contains("llama_token t = llama_vocab_eos(vocab);"))
        assertTrue(c.contains("if (t == LLAMA_TOKEN_NULL) t = llama_vocab_sep(vocab);"))
        assertTrue(c.contains("llama_pooling_type(e->ctx) == LLAMA_POOLING_TYPE_LAST"))
        assertTrue(c.contains("if (end != LLAMA_TOKEN_NULL && toks.back() != end)"))
        assertTrue(c.contains("toks.push_back(end);"))
        // 截断保留末尾 EOS：补 EOS 在截断之前
        assertTrue(c.indexOf("toks.push_back(end);") < c.indexOf("if (n > e->n_batch)"))
        assertTrue(c.contains("const bool keep_last = end != LLAMA_TOKEN_NULL || llama_vocab_get_add_eos(vocab);"))
        assertTrue(c.contains("llama_model_meta_val_str(model, \"general.architecture\", buf, sizeof(buf))"))
        assertTrue(c.contains("(pt == LLAMA_POOLING_TYPE_UNSPECIFIED || pt == LLAMA_POOLING_TYPE_NONE) && model_arch(model) == \"qwen3\""))
        assertTrue(c.contains("cp.pooling_type = LLAMA_POOLING_TYPE_LAST;"))
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

    // ---- ④ 版本 / CHANGELOG ----
    @Test fun version() {
        val g = src("app/build.gradle.kts")
        assertTrue(g.contains("versionCode = 251"))
        assertTrue(g.contains("versionName = \"2.5.2\""))
    }

    @Test fun changelog() {
        val c = src("CHANGELOG.md")
        val sec = c.substring(c.indexOf("## 2.5.2"), c.indexOf("## 2.5.1（"))
        assertTrue(sec.lines()[1] == "**本版未在真机测试。**")
        assertTrue(sec.contains("MCP") && sec.contains("20 个工具"))
        assertTrue(sec.contains("Q4_K_M") && sec.contains("Q4_K_XL") && sec.contains("更省内存"))
        assertTrue(sec.contains("结束符") && sec.contains("qwen3"))
    }
}

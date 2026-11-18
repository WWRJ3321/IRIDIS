package com.iris.localai.engine

import com.iris.localai.agent.core.ParsedReply
import com.iris.localai.agent.core.ToolCallParser
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.data.UpdateNotes
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.StopReason
import com.iris.localai.engine.edge0.Edge0Assets
import com.iris.localai.engine.edge0.Edge0Native
import com.iris.localai.engine.edge0.Edge0Prompt
import com.iris.localai.engine.edge0.Edge0Support
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2.6.5: Edge0-35B (experimental) — device gate, asset folder rule, prompt/stream mapping, catalog, native isolation. */
class V265Test {
    private fun file(p: String) = listOf("app/$p", p).map { File(it) }.first { it.exists() }
    private fun src(p: String) = file(p).readText()
    private fun main(p: String) = src("src/main/java/com/iris/localai/$p")
    private fun root(p: String) = listOf(p, "../$p").map { File(it) }.first { it.exists() }


    // ---- device gate
    private val x4 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 bti"
    private val a76 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"

    @Test fun cpuGate() {
        assertTrue(Edge0Support.cpuOk(listOf("arm64-v8a"), x4))
        assertFalse(Edge0Support.cpuOk(listOf("arm64-v8a"), a76))
        assertFalse(Edge0Support.cpuOk(listOf("x86_64"), x4))
        assertFalse(Edge0Support.cpuOk(listOf("arm64-v8a"), "i8mm"))
    }

    @Test fun cpuinfoEveryCore() {
        val ok = "processor\t: 0\nFeatures\t: $x4\n\nprocessor\t: 1\nFeatures\t: $x4\n"
        val mixed = "processor\t: 0\nFeatures\t: $x4\n\nprocessor\t: 1\nFeatures\t: $a76\n"
        assertTrue(Edge0Support.cpuinfoOk(listOf("arm64-v8a"), ok))
        assertFalse(Edge0Support.cpuinfoOk(listOf("arm64-v8a"), mixed))
        assertFalse(Edge0Support.cpuinfoOk(listOf("arm64-v8a"), "processor\t: 0\n"))
    }

    // ---- asset folder
    @Test fun assetFolderRule() {
        val set = setOf("assets.tsv", "model.json", "provenance.json", "base.gguf", "lora.gguf", "prerouter.gguf",
            "tokenizer.json", "chat_template.jinja", "generation_config.json")
        assertTrue(Edge0Assets.isFileSet(set))
        assertFalse(Edge0Assets.isFileSet(set - "prerouter.gguf"))
        assertFalse(Edge0Assets.isFileSet(setOf("config.json", "llm.mnn")))
        assertTrue(Edge0Assets.modelJsonOk("{\"format_version\": 1, \"architecture\": \"edge0_35b\"}"))
        assertFalse(Edge0Assets.modelJsonOk("{\"architecture\": \"edge0_8b\"}"))
    }

    @Test fun assetDirOnDisk() {
        val d = kotlin.io.path.createTempDirectory("e0").toFile()
        Edge0Assets.FILES.forEach { File(d, it).writeText("x") }
        File(d, "model.json").writeText("{\"format_version\":1,\"architecture\":\"edge0_35b\"}")
        assertTrue(Edge0Assets.isAssetDir(d))
        File(d, "model.json").writeText("{}")
        assertFalse(Edge0Assets.isAssetDir(d))
        d.deleteRecursively()
    }

    @Test fun formatIsFolder() {
        assertTrue(LocalModel("Edge0-35B-IRIDIS", "/x", 1, ModelFormat.EDGE0).isFolder)
        assertEquals("Edge0-35B", LocalModel("Edge0-35B-IRIDIS", "/x", 1, ModelFormat.EDGE0).fileDisplayName)
        assertTrue(ModelFormat.EDGE0.extensions.isEmpty())
    }

    // ---- prompt / stream
    @Test fun inputJsonSplitsThinking() {
        val j = Json.parseToJsonElement(Edge0Prompt.inputJson(listOf(
            PromptMessage(PromptMessage.SYSTEM, "sys"),
            PromptMessage(PromptMessage.USER, "hi"),
            PromptMessage(PromptMessage.ASSISTANT, "<think>plan</think>hello"),
        ))).jsonObject
        val m = j["messages"]!!.jsonArray
        assertEquals(3, m.size)
        assertEquals("hello", m[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("plan", m[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertNull(m[1].jsonObject["reasoning_content"])
        assertNull(j["tools"])
    }

    @Test fun inputJsonTools() {
        val tool = Json.parseToJsonElement("{\"type\":\"function\",\"function\":{\"name\":\"read_file\"}}") as JsonObject
        val j = Json.parseToJsonElement(Edge0Prompt.inputJson(listOf(PromptMessage(PromptMessage.USER, "x")), listOf(tool))).jsonObject
        assertEquals(1, j["tools"]!!.jsonArray.size)
    }

    @Test fun streamWrapsReasoning() {
        val s = Edge0Prompt.Stream()
        val out = StringBuilder()
        out.append(s.map(Edge0Native.REASONING, "a"))
        out.append(s.map(Edge0Native.REASONING, "b"))
        out.append(s.map(Edge0Native.ANSWER, "c"))
        out.append(s.finish())
        assertEquals("<think>ab</think>c", out.toString())
        val t = Edge0Prompt.Stream()
        assertEquals("<think>x", t.map(Edge0Native.REASONING, "x"))
        assertEquals("</think>", t.finish())
        assertEquals("", t.finish())
    }

    @Test fun toolCallReadableByAgent() {
        val s = Edge0Prompt.Stream()
        val text = s.map(Edge0Native.TOOL_BEGIN, "read_file") + s.map(Edge0Native.TOOL_ARGS, "{\"path\":") +
            s.map(Edge0Native.TOOL_ARGS, "\"a.txt\"}") + s.map(Edge0Native.TOOL_END, "{\"id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}")
        assertTrue(text, text.startsWith("<tool_call>") && text.endsWith("</tool_call>"))
        val p = ToolCallParser.parse(text)
        assertTrue(p.toString(), p is ParsedReply.Call)
        p as ParsedReply.Call
        assertEquals("read_file", p.call.name)
        assertEquals("a.txt", p.call.args["path"]!!.jsonPrimitive.content)
    }

    @Test fun stopMapping() {
        assertEquals(StopReason.END, Edge0Prompt.stop("end"))
        assertEquals(StopReason.END, Edge0Prompt.stop("tool_calls"))
        assertEquals(StopReason.MAX_TOKENS, Edge0Prompt.stop("length"))
        assertEquals(StopReason.CONTEXT_FULL, Edge0Prompt.stop("capacity"))
        assertEquals(StopReason.CANCELLED, Edge0Prompt.stop("cancelled"))
        assertEquals(StopReason.ERROR, Edge0Prompt.stop("failed"))
        assertEquals(StopReason.ERROR, Edge0Prompt.stop(null))
    }

    // ---- catalog / download
    @Test fun catalogEntry() {
        val it = RecommendedCatalog.byId(Edge0Assets.REPO)!!
        assertEquals("WWRJ-2025/Edge0-35B-IRIDIS", it.repoId)
        assertEquals(RecommendedCatalog.Engine.EDGE0, it.engine)
        assertEquals(RecommendedCatalog.Section.CHAT, it.section)
        assertTrue(it.kind is RecommendedCatalog.Kind.Hub && (it.kind as RecommendedCatalog.Kind.Hub).file == null)
        assertTrue(it.name.contains("Edge0-35B"))
        assertTrue(it.purpose.isNotBlank() && it.purpose.length <= 40)
        assertTrue(it.sizeBytes > 15_000_000_000L)
        assertEquals("Apache-2.0", it.licence)
        assertTrue(RecommendedCatalog.matchesLocal(Edge0Assets.REPO, "Edge0-35B-IRIDIS"))
    }




    @Test fun prebuiltLibraryPresent() {
        val so = root("prebuilt-native/arm64-v8a/libiris_edge0.so")
        assertTrue(so.length() in 1_000_000L..20_000_000L)
        // ELF dynamic symbol names: no ggml symbol may leak out (it would clash with libiris_llama.so)
        val text = String(so.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("Java_com_iris_localai_engine_edge0_Edge0Native_nativeGenerate"))
        assertFalse(Regex("\\u0000ggml_init\\u0000").containsMatchIn(text))
    }

    @Test fun stringsInSevenLanguages() {
        listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-de", "values-es", "values-hi").forEach { d ->
            val s = src("src/main/res/$d/strings.xml")
            listOf("e265_unsupported", "e265_purpose", "e265_files", "e265_bad_assets").forEach { k -> assertTrue("$d $k", s.contains("name=\"$k\"")) }
        }
    }
}

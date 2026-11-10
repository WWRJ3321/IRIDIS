package com.iris.localai.v250

import com.iris.localai.data.AppSettings
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.ModelOverrides
import com.iris.localai.engine.Backend
import com.iris.localai.engine.GgufQuant
import com.iris.localai.engine.MtpTag
import com.iris.localai.engine.llama.MtpStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 2.5.0-beta9: MTP speculative decoding (detection, default off, fallback, stats). */
class V259MtpTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()

    /** A tiny GGUF v3 header: [kvs] as (key, type, value bytes), one F32 tensor. */
    private fun gguf(vararg kvs: Triple<String, Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, block: ByteBuffer.() -> Unit) = out.write(ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(block).array())
        fun str(s: String) { val b = s.toByteArray(); le(8) { putLong(b.size.toLong()) }; out.write(b) }
        out.write("GGUF".toByteArray())
        le(4) { putInt(3) }; le(8) { putLong(1) }; le(8) { putLong(kvs.size.toLong()) }
        for ((k, t, v) in kvs) { str(k); le(4) { putInt(t) }; out.write(v) }
        str("token_embd.weight"); le(4) { putInt(1) }; le(8) { putLong(16) }; le(4) { putInt(0) }; le(8) { putLong(0) }
        return out.toByteArray()
    }
    private fun s(v: String): ByteArray { val b = v.toByteArray(); return ByteBuffer.allocate(8 + b.size).order(ByteOrder.LITTLE_ENDIAN).putLong(b.size.toLong()).put(b).array() }
    private fun u32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    @Test fun nextnDetectedFromHeader() {
        val info = GgufQuant.scan(ByteArrayInputStream(gguf(
            Triple("general.architecture", 8, s("qwen3next")),
            Triple("general.name", 8, s("x")),
            Triple("qwen3next.nextn_predict_layers", 4, u32(1)),
        )))!!
        assertEquals("qwen3next", info.arch); assertEquals(1, info.nextn); assertTrue(info.mtp)
        assertEquals(setOf(0), info.types)
        // key before the architecture, other int width
        val i2 = GgufQuant.scan(ByteArrayInputStream(gguf(
            Triple("glm4moe.nextn_predict_layers", 5, u32(2)),
            Triple("general.architecture", 8, s("glm4moe")),
        )))!!
        assertEquals(2, i2.nextn); assertTrue(i2.mtp)
    }

    @Test fun noMtpWithoutKeyOrForOtherArch() {
        val none = GgufQuant.scan(ByteArrayInputStream(gguf(Triple("general.architecture", 8, s("llama")))))!!
        assertEquals(0, none.nextn); assertFalse(none.mtp)
        val other = GgufQuant.scan(ByteArrayInputStream(gguf(
            Triple("general.architecture", 8, s("llama")),
            Triple("qwen3next.nextn_predict_layers", 4, u32(1)),
        )))!!
        assertFalse(other.mtp)
        val zero = GgufQuant.scan(ByteArrayInputStream(gguf(
            Triple("general.architecture", 8, s("deepseek2")),
            Triple("deepseek2.nextn_predict_layers", 4, u32(0)),
        )))!!
        assertFalse(zero.mtp)
        assertFalse(GgufQuant.Info(setOf(1)).mtp) // old constructor keeps working
    }

    @Test fun switchIsOffByDefault() {
        assertNull(ModelOverrides().mtp)
        assertFalse(EffectiveModelConfig.resolve(AppSettings(), ModelFormat.GGUF, null).mtp)
        assertTrue(EffectiveModelConfig.resolve(AppSettings(), ModelFormat.GGUF, ModelOverrides(mtp = true)).mtp)
        assertTrue(ModelOverrides().loadSignature() != ModelOverrides(mtp = true).loadSignature())
        assertEquals(0, com.iris.localai.engine.LoadConfig(contextLength = 4096, threads = 4, flashAttention = true).mtpDraft)
    }

    @Test fun draftOnlyWhenEnabledDetectedAndCpu() {
        val mtp = GgufQuant.Info(setOf(1), "qwen3next", 1)
        val plain = GgufQuant.Info(setOf(1), "llama", 0)
        assertEquals(GgufQuant.MTP_DRAFT, GgufQuant.mtpDraft(true, mtp, Backend.CPU))
        assertTrue(GgufQuant.MTP_DRAFT in 2..3)
        assertEquals(0, GgufQuant.mtpDraft(false, mtp, Backend.CPU))
        assertEquals(0, GgufQuant.mtpDraft(true, plain, Backend.CPU))
        assertEquals(0, GgufQuant.mtpDraft(true, null, Backend.CPU))
        assertEquals(0, GgufQuant.mtpDraft(true, mtp, Backend.GPU))
        assertEquals(0, GgufQuant.mtpDraft(true, mtp, Backend.NPU))
        // safe mode (after a crash) never asks for MTP
        assertTrue(src("app/src/main/java/com/iris/localai/engine/EngineManager.kt").contains("mtpDraft = if (safeMode || model.format != ModelFormat.GGUF) 0"))
    }

    @Test fun statsAndStatus() {
        assertNull(MtpStats.acceptRate(0, 0))
        assertEquals(0.5f, MtpStats.acceptRate(10, 5)!!, 1e-6f)
        assertEquals(1f, MtpStats.acceptRate(4, 9)!!, 1e-6f)
        assertNull(MtpStats.logLine(0, 0, 100, 1_000_000))
        val line = MtpStats.logLine(30, 21, 50, 2_000_000)!!
        assertTrue(line, line.contains("21/30") && line.contains("70.0%") && line.contains("25.00 tok/s"))
        assertTrue(MtpStats.active("active n=3"))
        assertFalse(MtpStats.active("fallback: model has no MTP layers"))
        assertFalse(MtpStats.active("off")); assertFalse(MtpStats.active(null))
    }

    @Test fun tagOnlyWhenDetected() {
        assertNull(MtpTag.join(null, "GGUF", false))
        assertEquals("GGUF · MTP", MtpTag.join(null, "GGUF", true))
        assertEquals("NPU · MTP", MtpTag.join("NPU", "GGUF", true))
        assertEquals("NPU", MtpTag.join("NPU", "GGUF", false))
    }

    @Test fun nativeFallsBackInsteadOfFailing() {
        val e = src("app/src/main/cpp/iris_engine.cpp")
        for (r in listOf("model has no MTP layers", "target memory cannot drop rejected drafts", "cannot create the MTP context",
                "MTP memory cannot drop drafts", "speculative init failed", "MTP width mismatch"))
            assertTrue(r, e.contains("fail(\"$r\")"))
        // init_spec runs after the context exists and cannot fail the load
        val load = e.substringAfter("bool Engine::load(").substringBefore("bool Engine::create_context(")
        assertTrue(load.contains("init_spec(params);"))
        assertTrue(load.contains("mp.load_mtp = params.spec_draft > 0;"))
        assertTrue(e.contains("catch (...)"))
        // shown tokens only: accepted drafts + the verified token, rejected drafts never counted
        assertTrue(e.contains("st.accepted_tokens += (int32_t) queue_kv;"))
        assertTrue(src("app/src/main/cpp/iris_engine.h").contains("int32_t spec_draft = 0;"))
        val j = src("app/src/main/cpp/jni_bridge.cpp")
        assertTrue(j.contains("\\\"draftedTokens\\\":") && j.contains("\\\"acceptedTokens\\\":"))
        val le = src("app/src/main/java/com/iris/localai/engine/llama/LlamaEngine.kt")
        assertTrue(le.contains("val draftedTokens: Int = 0,") && le.contains("val acceptedTokens: Int = 0,"))
        assertTrue(src("app/src/main/cpp/CMakeLists.txt").contains("target_link_libraries(iris_llama PRIVATE llama-common)"))
    }

    @Test fun stringsInEveryLanguage() {
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-de", "values-es", "values-ja", "values-hi")) {
            val x = src("app/src/main/res/$d/strings.xml")
            assertTrue(d, x.contains("name=\"v259_mtp\"") && x.contains("name=\"v259_mtp_sub\""))
        }
    }

    // ---- D: image models are not leftovers ----
    @Test fun imagegenDirIsNotALeftover() {
        val u = com.iris.localai.util.StorageUsage
        assertEquals(com.iris.localai.imagegen.ImageGenFiles.MODELS_SUB.substringAfterLast('/'), u.IMAGEGEN_DIR)
        for (shared in listOf(false, true)) {
            val dir = kotlin.io.path.createTempDirectory("v259img").toFile()
            File(dir, "imagegen/sd15").mkdirs(); File(dir, "imagegen/sd15/unet.bin").writeBytes(ByteArray(2048))
            File(dir, "junk.bin").writeBytes(ByteArray(10)); File(dir, "x.gguf.part").writeBytes(ByteArray(10))
            val names = u.leftovers(dir, emptyList(), emptySet(), shared = shared).map { it.name }
            assertFalse(names.toString(), "imagegen" in names)
            assertTrue("junk.bin" in names && "x.gguf.part" in names)
            dir.deleteRecursively()
        }
        // a plain file named imagegen is still listed
        val d2 = kotlin.io.path.createTempDirectory("v259img2").toFile(); File(d2, "imagegen").writeText("x")
        assertTrue(u.leftovers(d2, emptyList(), emptySet(), shared = false).any { it.name == "imagegen" })
        d2.deleteRecursively()
        val page = src("app/src/main/java/com/iris/localai/ui/settings/StoragePage.kt")
        val row = page.substringAfter("if (e.id == Ids.IMAGEGEN) {").substringBefore("} else if")
        assertFalse(row.contains("destructive")); assertFalse(row.contains("pending ="))
        assertTrue(src("app/src/main/java/com/iris/localai/ui/MainActivity.kt").contains("onImageModels = { push(Route.IMAGEGEN) }"))
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-de", "values-es", "values-ja", "values-hi")) {
            val x = src("app/src/main/res/$d/strings.xml")
            for (k in listOf("v259_imagegen_models", "v259_imagegen_manage", "v259_live_title_thinking", "v259_live_title_generating", "v259_live_chars", "v259_live_chip_thinking", "v259_live_chip"))
                assertTrue("$d $k", x.contains("name=\"$k\""))
        }
    }

    // ---- E: Live Update wording / style ----
    @Test fun liveUpdateTitleBodyChip() {
        val g = com.iris.localai.service.GenerationText
        assertEquals("思考中", g.liveTitle(0)); assertEquals("生成中", g.liveTitle(3))
        assertEquals("Qwen3 4B · 12 字", g.liveBody("Qwen3 4B", 12))
        assertEquals("Qwen3 4B · 8.5 tok/s", g.liveBody("Qwen3 4B", 12, 8.5f))
        assertEquals("12 字", g.liveBody(null, 12))
        assertEquals("Qwen3 4B", g.liveBody(" Qwen3 4B ", 0))
        assertEquals("正在思考…", g.liveBody("  ", 0))
        assertEquals("思考", g.liveChip(0)); assertEquals("7字", g.liveChip(7))
        val s = src("app/src/main/java/com/iris/localai/service/GenerationService.kt")
        val live = s.substringAfter("val modelName = runCatching").substringBefore("private fun done(")
        assertTrue(live.contains("GenerationText.liveTitle(chars)"))
        assertTrue(live.contains("setLargeIcon"))
        assertTrue(s.contains("Notification.ProgressStyle().setProgressIndeterminate(true)"))
        assertTrue(live.contains("BigTextStyle().bigText(text)"))       // fallback keeps BigText = body
        assertTrue(s.contains("service_095e93), stop)"))                 // 停止 kept
        // ≤ Android 15 branch untouched
        assertTrue(s.substringAfter("if (!live) {").substringBefore("}").contains("GenerationText.progress(j?.title, chars)"))
        val icon = src("app/src/main/res/drawable/ic_live_large.xml")
        assertFalse(icon.contains("gradient")); assertTrue(icon.contains("#FF3A3A3C"))
    }
}

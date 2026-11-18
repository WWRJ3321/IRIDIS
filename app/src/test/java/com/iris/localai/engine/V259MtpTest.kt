package com.iris.localai.engine

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


    @Test fun stringsInEveryLanguage() {
        for (d in listOf("values", "values-zh-rTW", "values-en", "values-de", "values-es", "values-ja", "values-hi")) {
            val x = src("app/src/main/res/$d/strings.xml")
            assertTrue(d, x.contains("name=\"v259_mtp\"") && x.contains("name=\"v259_mtp_sub\""))
        }
    }


}

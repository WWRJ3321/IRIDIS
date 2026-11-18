package com.iris.localai.imagegen

import com.iris.localai.imagegen.ClipTokenizer
import com.iris.localai.imagegen.EulerScheduler
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageRequest
import com.iris.localai.imagegen.QParams
import com.iris.localai.imagegen.QnnGraph
import com.iris.localai.imagegen.QnnInfoJson
import com.iris.localai.imagegen.QnnProbe
import com.iris.localai.imagegen.QnnSdEngine
import com.iris.localai.imagegen.QnnTensorSpec
import com.iris.localai.imagegen.Quant
import com.iris.localai.imagegen.SdPipeline
import com.iris.localai.imagegen.SdQuantDefaults
import com.iris.localai.imagegen.TensorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sqrt

/** QnnSdEngine's Kotlin half (quant mapping, CFG, NHWC shapes, timestep quant) against fake QNN graphs. */
class QnnSdPipelineTest {

    // ---- fakes: specs as the AI Hub w8a16 binaries report them (QNN offset = −zp) ----
    private fun u16(name: String, dims: List<Int>, q: QParams?) =
        QnnTensorSpec(name, dims, "ufixed16", dims.fold(1L) { a, b -> a * b } * 2, q?.scale, q?.let { -it.zp })

    private val teInSpec = QnnTensorSpec("input_ids", listOf(1, 77), "int32", 77L * 4)
    private val teOutSpec = u16("text_embeddings", listOf(1, 77, 768), SdQuantDefaults.TE_OUT)
    private val latentSpec = u16("latent", listOf(1, 64, 64, 4), SdQuantDefaults.UNET_LATENT)
    private val timestepSpec = u16("timestep", listOf(1, 1), SdQuantDefaults.UNET_TIMESTEP)
    private val textEmbSpec = u16("text_emb", listOf(1, 77, 768), SdQuantDefaults.UNET_TEXT_EMB)
    private val unetOutSpec = u16("output_latent", listOf(1, 64, 64, 4), SdQuantDefaults.UNET_OUT)
    private val vaeInSpec = u16("latent", listOf(1, 64, 64, 4), SdQuantDefaults.VAE_IN)
    private val vaeOutSpec = u16("image", listOf(1, 512, 512, 3), QParams(1f / 65535f, 0))

    private fun BufferWriter(buf: ByteBuffer) = buf.duplicate().order(ByteOrder.nativeOrder()).also { it.clear() }

    /** Text encoder: every embedding value = +0.5 for a real prompt, −0.25 for the empty one (ids[1] == EOS). */
    inner class FakeTe(override val inputs: List<QnnTensorSpec> = listOf(teInSpec), override val outputs: List<QnnTensorSpec> = listOf(teOutSpec)) : QnnGraph {
        val seen = ArrayList<IntArray>()
        var closed = false
        override fun execute(inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
            val b = BufferWriter(inputs[0])
            val ids = IntArray(77) { b.getInt(it * 4) }
            seen.add(ids)
            val v = if (ids[1] == ClipTokenizer.EOS) -0.25f else 0.5f
            val o = BufferWriter(outputs[0])
            val q = Quant.quantize(v, SdQuantDefaults.TE_OUT.scale, SdQuantDefaults.TE_OUT.zp).toShort()
            repeat(77 * 768) { o.putShort(q) }
        }
        override fun close() { closed = true }
    }

    /** UNet with inputs in a non-canonical order; predicts COND for the prompt embedding, UNCOND for the empty one. */
    inner class FakeUnet(
        override val inputs: List<QnnTensorSpec> = listOf(textEmbSpec, latentSpec, timestepSpec),
        override val outputs: List<QnnTensorSpec> = listOf(unetOutSpec),
    ) : QnnGraph {
        val timestepQ = ArrayList<Int>()
        val textFirst = ArrayList<Float>()
        val latents = ArrayList<FloatArray>()
        val sizes = ArrayList<List<Int>>()
        var closed = false
        override fun execute(inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
            sizes.add(inputs.map { it.capacity() })
            val te = this.inputs.indexOfFirst { it.name == "text_emb" }
            val ts = this.inputs.indexOfFirst { it.name == "timestep" }
            val lt = this.inputs.indexOfFirst { it.name == "latent" }
            timestepQ.add(BufferWriter(inputs[ts]).getShort(0).toInt() and 0xFFFF)
            val tq = BufferWriter(inputs[te]).getShort(0).toInt() and 0xFFFF
            val text = Quant.dequantize(tq, textEmbSpec.scale!!, textEmbSpec.zp!!)
            textFirst.add(text)
            val lb = BufferWriter(inputs[lt])
            latents.add(FloatArray(64 * 64 * 4) { Quant.dequantize(lb.getShort(it * 2).toInt() and 0xFFFF, latentSpec.scale!!, latentSpec.zp!!) })
            val v = if (text > 0f) COND else UNCOND
            val q = Quant.quantize(v, SdQuantDefaults.UNET_OUT.scale, SdQuantDefaults.UNET_OUT.zp).toShort()
            val o = BufferWriter(outputs[0])
            repeat(64 * 64 * 4) { o.putShort(q) }
        }
        override fun close() { closed = true }
    }

    /** VAE: pixel p gets R=1, G=0.5, B=0 (NHWC), records the latent it was given. */
    inner class FakeVae(
        override val inputs: List<QnnTensorSpec> = listOf(vaeInSpec),
        override val outputs: List<QnnTensorSpec> = listOf(vaeOutSpec),
    ) : QnnGraph {
        var calls = 0
        var closed = false
        override fun execute(inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) {
            calls++
            val o = BufferWriter(outputs[0])
            repeat(512 * 512) { o.putShort(65535.toShort()); o.putShort(32768.toShort()); o.putShort(0) }
        }
        override fun close() { closed = true }
    }

    private val cond = IntArray(77) { ClipTokenizer.PAD }.also { it[0] = ClipTokenizer.BOS; it[1] = 320; it[2] = ClipTokenizer.EOS }
    private val uncond = IntArray(77) { ClipTokenizer.PAD }.also { it[0] = ClipTokenizer.BOS; it[1] = ClipTokenizer.EOS }

    // ---- codec / quant mapping ----

    @Test fun qnnOffsetIsNegativeZeroPoint() {
        val spec = u16("x", listOf(1), null).copy(scale = 0.5f, offset = -100)
        assertEquals(100, spec.zp)
        val b = TensorCodec.encode(spec, floatArrayOf(1.5f), QParams(1f, 0))
        assertEquals(103, b.order(ByteOrder.nativeOrder()).getShort(0).toInt() and 0xFFFF)
        assertEquals(1.5f, TensorCodec.decode(spec, b, QParams(1f, 0))[0], 0f)
        val zero = TensorCodec.encode(spec, floatArrayOf(0f), QParams(1f, 0))
        assertEquals(100, zero.order(ByteOrder.nativeOrder()).getShort(0).toInt() and 0xFFFF)
    }

    @Test fun fallsBackToKnownQuantWhenBinaryHasNone() {
        val bare = u16("timestep", listOf(1, 1), null)
        val b = TensorCodec.encode(bare, floatArrayOf(500f), SdQuantDefaults.UNET_TIMESTEP)
        assertEquals(Quant.quantize(500f, 0.0147707f, 0), b.order(ByteOrder.nativeOrder()).getShort(0).toInt() and 0xFFFF)
    }

    @Test fun textEmbeddingIsRequantisedFromTeToUnet() {
        val p = SdPipeline(FakeTe(), FakeUnet(), FakeVae())
        val emb = p.textEmbedding(cond).order(ByteOrder.nativeOrder())
        assertEquals(77L * 768 * 2, emb.capacity().toLong())
        val q = emb.getShort(0).toInt() and 0xFFFF
        val te = SdQuantDefaults.TE_OUT
        val teValue = Quant.dequantize(Quant.quantize(0.5f, te.scale, te.zp), te.scale, te.zp)
        assertEquals(Quant.quantize(teValue, 0.000933156f, 30103), q)
        // TE emitted 0.5 in its own quant: a straight copy of the raw uint16 would be wrong
        assertFalse(q == Quant.quantize(0.5f, SdQuantDefaults.TE_OUT.scale, SdQuantDefaults.TE_OUT.zp))
    }

    @Test fun tokenIdsGoInAsInt32() {
        val te = FakeTe()
        SdPipeline(te, FakeUnet(), FakeVae()).encodeText(cond)
        assertArrayEquals(cond, te.seen.single())
    }

    // ---- loop ----

    @Test fun cfgRunsUnetTwicePerStepWithQuantisedTimesteps() {
        val unet = FakeUnet()
        val vae = FakeVae()
        val steps = 4
        val progress = ArrayList<Int>()
        val px = SdPipeline(FakeTe(), unet, vae).generate(cond, uncond, steps, 7.5f, 42L) { s, t -> assertEquals(steps, t); progress.add(s) }
        assertEquals(listOf(1, 2, 3, 4), progress)
        assertEquals(2 * steps, unet.timestepQ.size)
        val sched = EulerScheduler().apply { setTimesteps(steps) }
        val expected = sched.timesteps.flatMap { t -> List(2) { Quant.quantize(t, 0.0147707f, 0) } }
        assertEquals(expected, unet.timestepQ)
        // uncond first, then cond, every step
        for (i in 0 until steps) {
            assertTrue(unet.textFirst[2 * i] < 0f)
            assertTrue(unet.textFirst[2 * i + 1] > 0f)
        }
        // buffer sizes in the UNet's own input order: text_emb, latent, timestep
        assertEquals(listOf(77 * 768 * 2, 64 * 64 * 4 * 2, 2), unet.sizes.first())
        assertEquals(1, vae.calls)
        assertEquals(512 * 512, px.size)
    }

    @Test fun cfgOneSkipsTheUnconditionalPass() {
        val unet = FakeUnet()
        SdPipeline(FakeTe(), unet, FakeVae()).generate(cond, uncond, 3, 1f, 1L)
        assertEquals(3, unet.timestepQ.size)
        assertTrue(unet.textFirst.all { it > 0f })
    }

    @Test fun cfgCombineAndEulerUseTheGuidedNoise() {
        val g = SdPipeline.cfgCombine(floatArrayOf(1f, -2f), floatArrayOf(3f, 0f), 7.5f)
        assertEquals(1f + 7.5f * 2f, g[0], 1e-6f)
        assertEquals(-2f + 7.5f * 2f, g[1], 1e-6f)

        // one step: x1 = x0 + noise·(σ1 − σ0) with noise = UNCOND + cfg(COND − UNCOND) (dequantised UNet outputs)
        val unet = FakeUnet()
        val pipe = SdPipeline(FakeTe(), unet, FakeVae())
        val sched = EulerScheduler().apply { setTimesteps(2) }
        val x0 = SdPipeline.initialLatent(7L, sched.initNoiseSigma)
        pipe.generate(cond, uncond, 2, 5f, 7L)
        val qOut = SdQuantDefaults.UNET_OUT
        fun dq(v: Float) = Quant.dequantize(Quant.quantize(v, qOut.scale, qOut.zp), qOut.scale, qOut.zp)
        val noise = dq(UNCOND) + 5f * (dq(COND) - dq(UNCOND))
        val ls = latentSpec.scale!!; val lz = latentSpec.zp!!
        fun viaLatentQuant(v: Float) = Quant.dequantize(Quant.quantize(v, ls, lz), ls, lz)
        // step 1's UNet input is x0 scaled; step 2's is x1 = x0 + noise·(σ1 − σ0) scaled (both through the latent quant)
        val d0 = sqrt(sched.sigmas[0] * sched.sigmas[0] + 1f)
        val d1 = sqrt(sched.sigmas[1] * sched.sigmas[1] + 1f)
        for (k in x0.indices step 97) {
            assertEquals(viaLatentQuant(x0[k] / d0), unet.latents[0][k], ls * 1.01f)
            val x1 = x0[k] + noise * (sched.sigmas[1] - sched.sigmas[0])
            assertEquals(viaLatentQuant(x1 / d1), unet.latents[2][k], ls * 1.01f)
        }
    }

    @Test fun cancelStopsBetweenSteps() {
        val unet = FakeUnet()
        var steps = 0
        try {
            SdPipeline(FakeTe(), unet, FakeVae()).generate(cond, uncond, 10, 7.5f, 1L, isCancelled = { steps >= 3 }) { s, _ -> steps = s }
            fail("expected cancellation")
        } catch (e: SdPipeline.CancelledException) { /* expected */ }
        assertEquals(3, steps)
        assertEquals(6, unet.timestepQ.size)
    }

    // ---- shapes / layout ----

    @Test fun latentIsNhwcAndImageIsNhwcRgb() {
        val p = SdPipeline(FakeTe(), FakeUnet(), FakeVae())
        assertEquals(listOf(1, 64, 64, 4), p.unetLatent.dims)
        assertEquals(512, p.width); assertEquals(512, p.height)
        val px = p.generate(cond, uncond, 1, 7.5f, 3L)
        assertEquals(0xFFFF8000.toInt(), px[0]) // R=255, G=128, B=0, opaque
        assertEquals(px[0], px[px.size - 1])
    }

    @Test fun rejectsNchwVaeOutput() {
        val nchw = u16("image", listOf(1, 3, 512, 512), null)
        try {
            SdPipeline(FakeTe(), FakeUnet(), FakeVae(outputs = listOf(nchw)))
            fail("NCHW output must be rejected")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("NHWC")) }
    }

    @Test fun findsUnetInputsByShapeWhenNamesDiffer() {
        val renamed = listOf(latentSpec.copy(name = "in0"), timestepSpec.copy(name = "in1"), textEmbSpec.copy(name = "in2"))
        val p = SdPipeline(FakeTe(), FakeUnet(inputs = renamed), FakeVae())
        assertEquals("in0", p.unetLatent.name); assertEquals("in1", p.unetTimestep.name); assertEquals("in2", p.unetText.name)
    }

    @Test fun toArgbPacksAndClamps() {
        val px = SdPipeline.toArgb(floatArrayOf(2f, -1f, 0.5f), 1, 1)
        assertEquals(0xFFFF0080.toInt(), px[0])
    }

    @Test fun initialLatentIsSeededGaussianTimesSigma() {
        val a = SdPipeline.initialLatent(123L, 14.6f)
        val b = SdPipeline.initialLatent(123L, 14.6f)
        assertArrayEquals(a, b, 0f)
        assertFalse(a.contentEquals(SdPipeline.initialLatent(124L, 14.6f)))
        val r = java.util.Random(123L)
        assertEquals((r.nextGaussian() * 14.6f).toFloat(), a[0], 0f)
        val std = sqrt(a.map { it * it }.average()).toFloat()
        assertTrue("std $std", abs(std - 14.6f) < 0.5f)
    }

    // ---- JSON from libiris_imagegen ----

    @Test fun parsesNativeInfoAndProbe() {
        val (ins, outs) = QnnInfoJson.parse(
            """{"graph":"unet","inputs":[{"name":"latent","dims":[1,64,64,4],"dtype":"ufixed16","bytes":32768,"scale":0.000241763,"offset":-33983},""" +
                """{"name":"timestep","dims":[1,1],"dtype":"ufixed16","bytes":2,"scale":0.0147707,"offset":0}],""" +
                """"outputs":[{"name":"output_latent","dims":[1,64,64,4],"dtype":"float32","bytes":65536}]}""",
        )
        assertEquals(2, ins.size)
        assertEquals(33983, ins[0].zp)
        assertEquals(64 * 64 * 4, ins[0].elements)
        assertNull(outs[0].scale)
        assertEquals("float32", outs[0].dtype)

        val p = QnnProbe.parse("""{"backendOk":true,"deviceOk":true,"error":null,"cdsp":{"present":true,"unsignedPd":true,"arch":79,"error":null}}""")
        assertTrue(p.backendOk && p.deviceOk && p.cdspPresent && p.unsignedPd)
        assertEquals(79, p.htpArch)
        assertNull(p.error)
        val bad = QnnProbe.parse("""{"backendOk":false,"deviceOk":false,"error":"dlopen libQnnHtp.so: not found","cdsp":{"present":false,"unsignedPd":false,"arch":0,"error":"x"}}""")
        assertNull(bad.htpArch)
        assertEquals("dlopen libQnnHtp.so: not found", bad.error)
    }

    // ---- engine wiring (no Bitmap reached) ----

    @Test fun engineReportsMissingFiles() = runBlocking {
        val dir = Files.createTempDirectory("sdqnn").toFile()
        val e = QnnSdEngine(modelDir = { dir }, loader = { fail("must not open"); throw IllegalStateException() })
        val r = e.generate(ImageRequest("a cat"), ImageCatalog.SD15_QNN) { _, _ -> }
        assertTrue(r.exceptionOrNull()!!.message!!.contains("unet.bin"))
    }

    @Test fun engineClosesOpenedGraphsWhenALaterOneFails() = runBlocking {
        val dir = Files.createTempDirectory("sdqnn").toFile()
        listOf("text_encoder.bin", "unet.bin", "vae.bin").forEach { File(dir, it).writeText("x") }
        File(dir, "tokenizer").mkdirs()
        File(dir, "tokenizer/vocab.json").writeText("{}")
        File(dir, "tokenizer/merges.txt").writeText("#version: 0.2\n")
        val te = FakeTe()
        val e = QnnSdEngine(modelDir = { dir }, loader = { f -> if (f.name == "text_encoder.bin") te else throw IllegalStateException("unet: boom") })
        val r = e.generate(ImageRequest("a cat"), ImageCatalog.SD15_QNN) { _, _ -> }
        assertEquals("unet: boom", r.exceptionOrNull()!!.message)
        assertTrue(te.closed)
    }

    @Test fun engineVotesBurstAroundTheLoopAndRelaxesOnCancel() = runBlocking {
        val dir = Files.createTempDirectory("sdqnn").toFile()
        listOf("text_encoder.bin", "unet.bin", "vae.bin").forEach { File(dir, it).writeText("x") }
        File(dir, "tokenizer").mkdirs()
        File(dir, "tokenizer/vocab.json").writeText("{}")
        File(dir, "tokenizer/merges.txt").writeText("#version: 0.2\n")
        val votes = ArrayList<String>()
        val unet = FakeUnet()
        val perf = object : com.iris.localai.imagegen.HtpPerf {
            override fun burst() { votes.add("burst:${unet.timestepQ.size}") }
            override fun relax() { votes.add("relax:${unet.timestepQ.size}") }
        }
        val graphs = mapOf("text_encoder.bin" to FakeTe(), "unet.bin" to unet, "vae.bin" to FakeVae())
        lateinit var e: QnnSdEngine
        e = QnnSdEngine(modelDir = { dir }, loader = { f -> graphs.getValue(f.name) }, perf = perf)
        val r = e.generate(ImageRequest("a cat", steps = 4, cfg = 7.5f, seed = 1L), ImageCatalog.SD15_QNN) { step, _ -> if (step == 2) e.cancel() }
        assertTrue(r.exceptionOrNull() is SdPipeline.CancelledException)
        assertEquals(listOf("burst:0", "relax:4"), votes) // 2 steps × (uncond + cond)
        assertTrue(e.isLoaded)
        e.release()
        assertFalse(e.isLoaded)
        assertTrue(unet.closed)
    }

    @Test fun engineSurvivesAThrowingPerfVote() = runBlocking {
        val dir = Files.createTempDirectory("sdqnn").toFile()
        listOf("text_encoder.bin", "unet.bin", "vae.bin").forEach { File(dir, it).writeText("x") }
        File(dir, "tokenizer").mkdirs()
        File(dir, "tokenizer/vocab.json").writeText("{}")
        File(dir, "tokenizer/merges.txt").writeText("#version: 0.2\n")
        val unet = FakeUnet()
        val perf = object : com.iris.localai.imagegen.HtpPerf {
            override fun burst() = throw IllegalStateException("setPowerConfig failed")
            override fun relax() = throw IllegalStateException("setPowerConfig failed")
        }
        val graphs = mapOf("text_encoder.bin" to FakeTe(), "unet.bin" to unet, "vae.bin" to FakeVae())
        lateinit var e: QnnSdEngine
        e = QnnSdEngine(modelDir = { dir }, loader = { f -> graphs.getValue(f.name) }, perf = perf)
        val r = e.generate(ImageRequest("a cat", steps = 2, cfg = 7.5f, seed = 1L), ImageCatalog.SD15_QNN) { step, _ -> if (step == 1) e.cancel() }
        assertTrue(r.exceptionOrNull() is SdPipeline.CancelledException) // the loop ran despite the failed vote
        assertEquals(2, unet.timestepQ.size)
    }

    companion object {
        const val COND = 0.2f
        const val UNCOND = -0.1f
    }
}

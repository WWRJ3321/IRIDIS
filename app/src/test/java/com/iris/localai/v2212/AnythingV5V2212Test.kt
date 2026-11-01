package com.iris.localai.v2212

import com.iris.localai.imagegen.Half
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.QnnGraph
import com.iris.localai.imagegen.QnnTensorSpec
import com.iris.localai.imagegen.SdPipeline
import com.iris.localai.imagegen.ZipSlice
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class AnythingV5V2212Test {

    @Test fun catalogEntryPerChip() {
        val g3 = ImageCatalog.anythingV5(75)
        val elite = ImageCatalog.anythingV5(79)
        assertEquals("Anything V5.0（动漫）", g3.name)
        assertEquals(512, g3.fixedSize)
        assertTrue(g3.files.single { it.unpacked.firstOrNull()?.path == "text_encoder.bin" }.url.contains("8gen3"))
        assertTrue(elite.files.single { it.unpacked.firstOrNull()?.path == "text_encoder.bin" }.url.contains("8_elite"))
        for (m in listOf(g3, elite)) {
            val names = m.files.flatMap { f -> f.unpacked.map { it.path }.ifEmpty { listOf(f.path.substringAfter("${m.id}/")) } }
            assertTrue(names.containsAll(listOf("text_encoder.bin", "unet.bin", "vae.bin", "tokenizer/vocab.json", "tokenizer/merges.txt")))
            val total = m.files.sumOf { it.size }
            assertTrue("size $total", total in 800_000_000L..1_200_000_000L)
            assertTrue(m.defaultPrompt.startsWith("masterpiece") && m.defaultNegative.startsWith("lowres"))
            assertEquals(75, m.minHtpArch)
        }
        assertTrue(ImageCatalog.all.any { it.id == ImageCatalog.ANYTHING_V5_ID })
        assertEquals(ImageCatalog.SD15_QNN, ImageCatalog.all.first())
    }

    @Test fun rangeFragment() {
        val f = ImageCatalog.anythingV5(79).files.first { it.isZipSlice }
        val (url, a, b) = HttpDownload.splitRange(f.fetchUrl)!!
        assertEquals(f.url, url)
        assertEquals(f.zipEntryAt, a)
        assertEquals(f.size, b - a + 1)
        assertNull(HttpDownload.splitRange("https://x/y.zip"))
    }

    @Test fun zipSliceExtract() = runBlocking {
        val dir = createTempDirectory()
        val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("top/a.txt")); z.write("hello".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("top/unet.bin")); z.write(payload); z.closeEntry()
        }
        val zip = bos.toByteArray()
        // find the second local header
        var off = -1
        for (i in 1 until zip.size - 4) if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4b.toByte() && zip[i + 2] == 3.toByte() && zip[i + 3] == 4.toByte()) { off = i; break }
        assertTrue(off > 0)
        val slice = File(dir, "s.slice").apply { writeBytes(zip.copyOfRange(off, zip.size)) }
        val crc = java.util.zip.CRC32().apply { update(payload) }.value
        val out = File(dir, "unet.bin")
        ZipSlice.extract(slice, out, payload.size.toLong(), crc)
        assertArrayEquals(payload, out.readBytes())
        var failed = false
        try { ZipSlice.extract(slice, File(dir, "bad.bin"), payload.size.toLong(), crc xor 1) } catch (e: java.io.IOException) { failed = true }
        assertTrue(failed)
        dir.deleteRecursively(); Unit
    }

    @Test fun halfRoundTrip() {
        for (v in floatArrayOf(0f, 1f, -1f, 0.5f, 0.333f, 65504f, -2.75f, 1e-5f)) {
            val back = Half.toFloat(Half.fromFloat(v))
            assertTrue("$v -> $back", abs(back - v) <= maxOf(abs(v) * 1e-3f, 1e-7f))
        }
    }

    /** Local Dream layout: NCHW latent, VAE input × 1/0.18215, NCHW output in [-1,1]. */
    @Test fun pipelineNchwSignedVae() {
        fun spec(name: String, dims: List<Int>, dtype: String = "float32") =
            QnnTensorSpec(name, dims, dtype, dims.fold(1) { a, b -> a * b }.toLong() * (if (dtype == "float16") 2 else 4))
        class G(override val inputs: List<QnnTensorSpec>, override val outputs: List<QnnTensorSpec>, val run: (Array<ByteBuffer>, Array<ByteBuffer>) -> Unit) : QnnGraph {
            override fun execute(inputs: Array<ByteBuffer>, outputs: Array<ByteBuffer>) = run(inputs, outputs)
            override fun close() {}
        }
        val te = G(listOf(spec("ids", listOf(1, 77), "int32")), listOf(spec("h", listOf(1, 77, 768)))) { _, o -> o[0].order(ByteOrder.nativeOrder()) }
        val unet = G(listOf(spec("latent", listOf(1, 4, 64, 64)), spec("timestep", listOf(1), "int32"), spec("text", listOf(1, 77, 768))),
            listOf(spec("out", listOf(1, 4, 64, 64)))) { _, _ -> }
        var vaeInFirst = 0f
        val vae = G(listOf(spec("latent", listOf(1, 4, 64, 64))), listOf(spec("img", listOf(1, 3, 512, 512), "float16"))) { i, o ->
            vaeInFirst = i[0].order(ByteOrder.nativeOrder()).getFloat(0)
            val b = o[0].order(ByteOrder.nativeOrder()); val px = 512 * 512
            for (p in 0 until px) { b.putShort(p * 2, Half.fromFloat(1f)); b.putShort((px + p) * 2, Half.fromFloat(-1f)); b.putShort((2 * px + p) * 2, Half.fromFloat(0f)) }
        }
        val p = SdPipeline(te, unet, vae, 1f / 0.18215f, true)
        assertEquals(512, p.width); assertEquals(512, p.height)
        val lat = FloatArray(SdPipeline.LATENT_ELEMENTS) { 0.18215f }
        val rgb = p.decode(lat)
        assertEquals(1f, vaeInFirst, 1e-4f)
        assertEquals(1f, rgb[0], 1e-3f); assertEquals(0f, rgb[1], 1e-3f); assertEquals(0.5f, rgb[2], 1e-3f)
        assertEquals(1f, rgb[3], 1e-3f)
        val argb = SdPipeline.toArgb(rgb, 512, 512)
        assertEquals(0xFF, (argb[0] shr 16) and 0xFF); assertEquals(0, (argb[0] shr 8) and 0xFF)
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("v2212").toFile()
}

package com.iris.localai.imagegen

import com.iris.localai.imagegen.AnimaMath
import com.iris.localai.imagegen.AnimaQnnFiles
import com.iris.localai.imagegen.ComfyWeights
import com.iris.localai.imagegen.ImportArch
import com.iris.localai.imagegen.ImportedImage
import com.iris.localai.imagegen.QwenBpe
import com.iris.localai.imagegen.T5Unigram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnimaQnnTest {
    @Test fun weightsFollowComfy() {
        val p = ComfyWeights.parse("1girl, (white hair:1.2), hatsune miku \\(vocaloid\\), (smile)")
        assertEquals("1girl, ", p[0].first); assertEquals(1f, p[0].second)
        assertEquals("white hair" to 1.2f, p[1])
        assertEquals(", hatsune miku (vocaloid), ", p[2].first)
        assertEquals("smile", p[3].first); assertEquals(1.1f, p[3].second, 1e-6f)
    }

    @Test fun sigmasShift3Simple() {
        val s = AnimaMath.sigmas(8)
        assertEquals(9, s.size); assertEquals(1f, s[0], 1e-6f); assertEquals(0f, s[8])
        assertTrue((0 until 8).all { s[it] > s[it + 1] })
    }

    @Test fun patchifyRoundTrip() {
        val hp = 4; val wp = 3
        val lat = FloatArray(16 * 2 * hp * 2 * wp) { it.toFloat() }
        val x = FloatArray(hp * wp * 68)
        AnimaMath.patchify(lat, x, hp, wp)
        // x's channel layout (c m n) vs output (p1 p2 c): rebuild a fake output from x and unpatchify
        val o = FloatArray(hp * wp * 64)
        for (r in 0 until hp * wp) for (c in 0 until 16) for (m in 0..1) for (n in 0..1) o[r * 64 + m * 32 + n * 16 + c] = x[r * 68 + c * 4 + m * 2 + n]
        val back = FloatArray(lat.size)
        AnimaMath.unpatchify(o, back, hp, wp)
        assertTrue(lat.contentEquals(back))
    }

    @Test fun sizesPicked() {
        val sizes = AnimaMath.parseSizes(listOf("s512x512", "s384x672", "s672x384", "s768x768", "s576x1024", "s1024x576"))
        assertEquals(6, sizes.size)
        assertEquals(768 to 768, AnimaMath.pickSize(sizes, 768, 768))
        assertEquals(576 to 1024, AnimaMath.pickSize(sizes, 720, 1280))
        assertEquals(384 to 672, AnimaMath.pickSize(sizes, 400, 700))
        assertEquals(512 to 512, AnimaMath.pickSize(emptyList(), 1024, 1024))
    }

    @Test fun packModelAndFiles() {
        val m = ImportedImage("imp-00000001", "A", ImportArch.ANIMA_NPU.name, "imp-00000001", "imp-00000001", "imp-00000001", 8, 1f).toModel()
        assertTrue(m.animaQnn && m.imported); assertEquals(79, m.minHtpArch)
        val d = Files.createTempDirectory("pack").toFile()
        assertTrue(AnimaQnnFiles(d).missing().size == 10)
        (AnimaQnnFiles.ELITE_NAMES - "qwen_embed_q8.raw").forEach { File(d, it).writeText("x") }
        assertTrue(AnimaQnnFiles(d).missing().isEmpty())
        d.deleteRecursively()
    }

    /** 与 HF tokenizers 0.x 的结果对照（文件只在开发机上有，没有就跳过）。 */
    @Test fun tokenizersMatchHf() {
        val q = File("/workspace/work/anima-qnn/qwen.json"); val t = File("/workspace/work/anima-qnn/t5.json")
        assumeTrue(q.isFile && t.isFile)
        val qb = QwenBpe.fromJson(q.readText()); val tu = T5Unigram.fromJson(t.readText())
        assertEquals(listOf(13629, 22362, 11, 1850, 4271, 11, 220, 16, 28552, 11, 13529, 11, 1293, 6303, 6869), qb.encode("masterpiece, best quality, 1girl, solo, long blue hair"))
        assertEquals(listOf(20975, 6, 200, 463, 6, 209, 18722, 6, 6729, 6, 307, 1692, 1268), tu.encode("masterpiece, best quality, 1girl, solo, long blue hair"))
        assertEquals(listOf(71, 1862, 2886, 296, 38242), qb.encode("hatsune miku"))
        assertEquals(listOf(3, 547, 7, 444, 1337, 2729), tu.encode("hatsune miku"))
        assertEquals(listOf(220, 51950, 94880, 586, 220, 24918, 26022, 24918, 59496, 220, 856), qb.encode("  café naïve ２０２６  x"))
        val s = "1girl, white hair,  cat ears, 微笑, holding lily"
        assertEquals(listOf(16, 28552, 11, 4158, 6869, 11, 220, 8251, 24230, 11, 84141, 106, 48738, 11, 9963, 326, 1541), qb.encode(s))
        assertEquals(listOf(209, 18722, 6, 872, 1268, 6, 1712, 11581, 6, 3, 2, 6, 3609, 3, 40, 9203), tu.encode(s))
    }
}

class LicenseCleanupTest {
    @Test fun nonCommercialNotOffered() {
        assertTrue(com.iris.localai.imagegen.ImageCatalog.all.none { it.id == com.iris.localai.imagegen.ImageCatalog.ANYTHING_V5_ID })
        val delisted = com.iris.localai.voice.VoicePack.entries.filter { it.delisted }.map { it.name }.toSet()
        assertEquals(setOf("TTS_PIPER_XIAOYA", "TTS_MATCHA_BAKER", "CLONE_ZIPVOICE"), delisted)
        assertTrue(com.iris.localai.data.RecommendedCatalog.ITEMS.none { it.id == "anything-v5-qnn" || it.id.contains("zipvoice") || it.id.contains("baker") || it.id.contains("xiao_ya") })
        val notes = com.iris.localai.data.UpdateNotes.RELEASES.flatMap { r -> r.items.map { it.text } }
        assertTrue(notes.none { it.contains("Anima") || it.contains("许可由你自备") })
    }
}

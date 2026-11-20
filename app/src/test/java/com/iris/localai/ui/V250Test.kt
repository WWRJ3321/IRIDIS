package com.iris.localai.ui

import com.iris.localai.service.GenerationText
import com.iris.localai.service.LiveUpdate
import com.iris.localai.ui.theme.IrisDesignScale
import com.iris.localai.ui.theme.IrisStyle
import com.iris.localai.ui.theme.SoftNeutral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V250Test {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private fun src(rel: String) = File(root(), rel).readText()

    // ---- 1 design tokens ----
    @Test fun designScaleHasFiveFontsFiveSpacesThreeCorners() {
        assertEquals(listOf(28f, 20f, 16f, 14f, 12f), IrisDesignScale.FONT_SP)
        assertEquals(listOf(4f, 8f, 12f, 16f, 24f), IrisDesignScale.SPACE_DP)
        assertEquals(listOf(12f, 20f, 28f), IrisDesignScale.CORNER_DP)
        assertEquals(14f, IrisDesignScale.nearest(15f, IrisDesignScale.FONT_SP), 0f)
    }

    @Test fun nearestTiesGoToSmaller() {
        assertEquals(8f, IrisDesignScale.nearest(10f, IrisDesignScale.SPACE_DP), 0f)
        assertEquals(16f, IrisDesignScale.nearest(18f, IrisDesignScale.FONT_SP), 0f)
        assertEquals(20f, IrisDesignScale.nearest(22f, IrisDesignScale.CORNER_DP), 0f)
        assertEquals(28f, IrisDesignScale.nearest(30f, IrisDesignScale.CORNER_DP), 0f)
    }







    /** No real hue cast: channels within 6 (the iOS-like greys lean ≤ 6 cool; the old Beta #141826 is +18). */
    private fun neutral(argb: Long): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 6
    }


    // ---- 4 Live Updates ----
    @Test fun liveUpdateOnlyOnApi36() {
        assertFalse(LiveUpdate.supported(35)); assertTrue(LiveUpdate.supported(36)); assertTrue(LiveUpdate.supported(37))
        assertEquals("android.requestPromotedOngoing", LiveUpdate.EXTRA_REQUEST_PROMOTED_ONGOING)
        assertEquals("IRIDIS", LiveUpdate.title("  "))
        assertEquals("周报", LiveUpdate.title("周报"))
        assertEquals(40, LiveUpdate.title("长".repeat(80)).length)
    }

    @Test fun liveUpdateTexts() {
        assertEquals("正在思考…", GenerationText.liveText(0))
        assertEquals("生成中 · 12 字", GenerationText.liveText(12))
        // 2.5.0-beta9: shorter chip
        assertEquals("思考", GenerationText.liveChip(0))
        assertEquals("5字", GenerationText.liveChip(5))
    }




    @Test fun newStringsInEveryLanguage() {
        val res = File(root(), "app/src/main/res")
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").isFile }
        assertEquals(7, dirs.size)
        val keys = Regex("name=\"(v250_[a-z_]+)\"").findAll(File(res, "values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(keys.size >= 25) // 2.6.9 精简删了未用的 v250_ 文案
        for (d in dirs) {
            val t = File(d, "strings.xml").readText()
            for (k in keys) assertTrue("${d.name} $k", t.contains("name=\"$k\""))
        }
    }









    private fun epub(entries: List<Pair<String, String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            for ((n, t) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test fun epubFollowsSpine() {
        val k = com.iris.localai.knowledge.KbFormats
        assertTrue(k.supported("书.epub"))
        assertTrue(k.maxBytes("a.epub", 4_000_000L) > 4_000_000L)
        assertEquals(32_000_000L, k.maxBytes("a.txt", 4_000_000L)) // 2.7.2：txt 上限 32 MB（流式切片）
        val book = epub(listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
            "OEBPS/content.opf" to "<package><manifest><item id=\"c2\" href=\"Text/ch%202.xhtml\" media-type=\"application/xhtml+xml\"/><item href=\"Text/ch1.xhtml\" id=\"c1\"/></manifest><spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>",
            "OEBPS/Text/ch 2.xhtml" to "<html><body><p>第二章 乙</p></body></html>",
            "OEBPS/Text/ch1.xhtml" to "<?xml version=\"1.0\"?><html><head><style>p{}</style></head><body><h1>第一章</h1><p>甲</p></body></html>",
        ))
        val t = k.text("书.epub", book)!!
        assertTrue(t, t.contains("第一章") && t.contains("第二章"))
        assertTrue(t.indexOf("第一章") < t.indexOf("第二章"))
        assertFalse(t.contains("<p>"))
        assertEquals(null, k.text("坏.epub", "not a zip".toByteArray()))
        assertEquals(listOf("OEBPS/Text/a.xhtml"), com.iris.localai.knowledge.EpubText.spine("OEBPS/content.opf", "<item id='a' href='Text/a.xhtml'/><itemref idref='a'/>"))
        assertEquals("OEBPS/b.xhtml", com.iris.localai.knowledge.EpubText.resolve("OEBPS/Text", "../b.xhtml#x"))
    }







    @Test fun downloadResumesWhileProgressing() = kotlinx.coroutines.runBlocking<Unit> {
        val ds = com.iris.localai.data.DownloadSources
        val dir = kotlin.io.path.createTempDirectory("v250b8").toFile(); val target = File(dir, "big.gguf")
        val a = com.iris.localai.data.SourceUrl(com.iris.localai.data.DownloadSource.MODELSCOPE, "https://modelscope.cn/x")
        val b = com.iris.localai.data.SourceUrl(com.iris.localai.data.DownloadSource.HF_MIRROR, "https://hf-mirror.com/x")
        var callsA = 0; var callsB = 0
        // every try writes 3 bytes then stalls: 10 tries on the same source finish 30 bytes
        ds.fetchAny(listOf(a, b), target, 30, {}, fetch = { u, t, _, _ ->
            val part = com.iris.localai.util.io.HttpDownload.partFile(t)
            if ("modelscope" in u) callsA++ else callsB++
            part.appendBytes(ByteArray(3))
            if (part.length() >= 30) part.renameTo(t) else throw java.net.SocketTimeoutException("Read timed out")
        }, retryDelayMs = 0)
        assertEquals(30L, target.length()); assertEquals(10, callsA); assertEquals(0, callsB)
        // no progress: two tries, then the next source (as before)
        target.delete(); callsA = 0; callsB = 0
        ds.fetchAny(listOf(a, b), target, 5, {}, fetch = { u, t, _, _ ->
            if ("modelscope" in u) { callsA++; throw java.net.SocketTimeoutException("Read timed out") }
            callsB++; t.writeBytes(ByteArray(5))
        }, retryDelayMs = 0)
        assertEquals(2, callsA); assertEquals(1, callsB)
        // progress every time but never done: the per-source cap stops it
        target.delete(); com.iris.localai.util.io.HttpDownload.partFile(target).delete(); callsA = 0
        try {
            ds.fetchAny(listOf(a), target, 1_000, {}, fetch = { _, t, _, _ ->
                callsA++; com.iris.localai.util.io.HttpDownload.partFile(t).appendBytes(ByteArray(1)); throw java.io.IOException("reset")
            }, retryDelayMs = 0)
            org.junit.Assert.fail()
        } catch (e: java.io.IOException) { assertEquals(ds.MAX_ATTEMPTS_PER_SOURCE, callsA) }
        assertEquals(1500L, ds.retryDelay(1500, 1)); assertEquals(30_000L, ds.retryDelay(1500, 25))
        dir.deleteRecursively()
    }
}

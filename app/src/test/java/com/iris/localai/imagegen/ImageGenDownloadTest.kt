package com.iris.localai.imagegen

import com.iris.localai.data.DownloadSource
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageDl
import com.iris.localai.imagegen.ImageDownloads
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.ImageModel
import com.iris.localai.imagegen.RemoteFile
import com.iris.localai.imagegen.Tier
import com.iris.localai.imagegen.UnpackedFile
import com.iris.localai.imagegen.ZipExtract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImageGenDownloadTest {
    private fun tmp(): File = Files.createTempDirectory("imgdl").toFile()

    private fun zip(file: File, entries: List<Pair<String, ByteArray?>>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                data?.let { z.write(it) }
                z.closeEntry()
            }
        }
        return file
    }

    @Test fun mnnFileListIsHardcoded() {
        val m = ImageCatalog.SD15_MNN
        assertTrue(m.files.isNotEmpty())
        val names = m.files.map { it.path.removePrefix("sd15-mnn/") }.toSet()
        for (n in listOf("text_encoder.mnn", "text_encoder.mnn.weight", "unet.mnn", "unet.mnn.weight",
            "vae_decoder.mnn", "vae_decoder.mnn.weight", "vocab.json", "merges.txt", "alphas.txt")) assertTrue(n, n in names)
        for (f in m.files) {
            assertTrue(f.path.startsWith("sd15-mnn/"))
            assertTrue(f.url, f.url.startsWith("https://huggingface.co/${ImageCatalog.MNN_REPO}/resolve/${ImageCatalog.MNN_REV}/"))
            assertTrue(f.url.endsWith("/" + f.path.removePrefix("sd15-mnn/")))
            assertFalse(f.isArchive)
        }
        assertEquals(1_154_788_884L, m.files.sumOf { it.size })
    }

    @Test fun mirrorFirstThenOtherHost() {
        val u = "https://huggingface.co/a/b/resolve/main/x.bin"
        assertEquals(listOf("https://hf-mirror.com/a/b/resolve/main/x.bin", u), ImageDownloads.urlsFor(u, DownloadSource.HF_MIRROR))
        assertEquals(listOf(u, "https://hf-mirror.com/a/b/resolve/main/x.bin"), ImageDownloads.urlsFor(u, DownloadSource.HUGGING_FACE))
        val s3 = "https://example.s3.amazonaws.com/m.zip"
        assertEquals(listOf(s3), ImageDownloads.urlsFor(s3, DownloadSource.HF_MIRROR))
    }

    @Test fun extractFlattensTopFolder() = runBlocking<Unit> {
        val dir = tmp()
        val z = zip(File(dir, "m.zip"), listOf(
            "model_x/" to null,
            "model_x/unet.bin" to ByteArray(5000) { it.toByte() },
            "model_x/metadata.json" to "{}".toByteArray(),
            "model_x/sub/a.txt" to "a".toByteArray(),
        ))
        val dest = File(dir, "out")
        var last = 0L to 0L
        val files = ZipExtract.extract(z, dest) { d, t -> last = d to t }
        assertEquals(3, files.size)
        assertEquals(5000L, File(dest, "unet.bin").length())
        assertEquals("{}", File(dest, "metadata.json").readText())
        assertEquals("a", File(dest, "sub/a.txt").readText())
        assertFalse(File(dest, "model_x").exists())
        assertEquals(5003L to 5003L, last)
        assertTrue(dest.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun topDirRules() {
        assertEquals("t", ZipExtract.commonTopDir(listOf("t/", "t/a", "t/b/c")))
        assertNull(ZipExtract.commonTopDir(listOf("a", "t/b")))
        assertNull(ZipExtract.commonTopDir(listOf("t/a", "u/b")))
        assertNull(ZipExtract.flattened("t/", "t"))
        assertEquals("a", ZipExtract.flattened("t/a", "t"))
        assertEquals("t/a", ZipExtract.flattened("t/a", null))
    }

    @Test fun extractWithoutTopFolderAndRejectsZipSlip() = runBlocking<Unit> {
        val dir = tmp()
        val dest = File(dir, "out")
        ZipExtract.extract(zip(File(dir, "a.zip"), listOf("a.bin" to byteArrayOf(1), "b/c.bin" to byteArrayOf(2))), dest)
        assertTrue(File(dest, "a.bin").isFile && File(dest, "b/c.bin").isFile)
        try {
            ZipExtract.extract(zip(File(dir, "evil.zip"), listOf("../evil.txt" to byteArrayOf(1))), File(dir, "out2"))
            fail("zip-slip must be rejected")
        } catch (_: IOException) { }
        assertFalse(File(dir, "evil.txt").exists())
    }

    /** A small model shaped like sd15-qnn: tokenizer file + zip that unpacks into the model folder. */
    private fun fakeModel(src: File): Pair<ImageModel, Map<String, File>> {
        val payload = ByteArray(3000) { (it * 7).toByte() }
        val z = zip(File(src, "m.zip"), listOf("top/" to null, "top/unet.bin" to payload, "top/metadata.json" to "{\"a\":1}".toByteArray()))
        val vocab = File(src, "vocab.json").apply { writeText("{\"a\":0}") }
        val model = ImageModel(
            id = "fake", tier = Tier.LOW, name = "Fake", backend = Backend.QNN,
            files = listOf(
                RemoteFile("https://huggingface.co/r/resolve/main/vocab.json", vocab.length(), "fake/tokenizer/vocab.json"),
                RemoteFile("https://example.com/m.zip", z.length(), "fake/.part/m.zip",
                    unpacked = listOf(UnpackedFile("unet.bin", 3000), UnpackedFile("metadata.json", 7))),
            ),
            fixedSize = 512, defaultSteps = 20, defaultCfg = 7.5f,
        )
        return model to mapOf("vocab.json" to vocab, "m.zip" to z)
    }

    @Test fun fullDownloadUnpacksAndIsDetected() = runBlocking<Unit> {
        val src = tmp(); val root = tmp()
        val (model, sources) = fakeModel(src)
        val calls = mutableListOf<String>()
        var failMirrorOnce = true
        val states = mutableListOf<ImageDl>()
        assertFalse(ImageGenFiles.isDownloaded(root, model))
        ImageDownloads.download(root, model, DownloadSource.HF_MIRROR, fetch = { url, target, expected, onBytes ->
            calls += url
            if (url.startsWith("https://hf-mirror.com") && failMirrorOnce) { failMirrorOnce = false; throw IOException("flaky") }
            val from = sources.getValue(url.substringAfterLast('/'))
            from.copyTo(target, overwrite = true)
            assertEquals(expected, target.length())
            onBytes(expected)
        }, publish = { states += it }, retryDelayMs = 0)

        assertTrue(ImageGenFiles.isDownloaded(root, model))
        val dir = File(root, "fake")
        assertEquals(3000L, File(dir, "unet.bin").length())
        assertEquals("{\"a\":1}", File(dir, "metadata.json").readText())
        assertTrue(File(dir, "tokenizer/vocab.json").isFile)
        assertFalse("zip deleted", File(dir, ".part/m.zip").exists())
        assertFalse(".part folder removed", File(dir, ".part").exists())
        assertFalse("flattened", File(dir, "top").exists())
        assertEquals(listOf("https://hf-mirror.com/r/resolve/main/vocab.json", "https://hf-mirror.com/r/resolve/main/vocab.json", "https://example.com/m.zip"), calls)
        assertTrue(states.any { it is ImageDl.Extracting })
        val lastDl = states.filterIsInstance<ImageDl.Downloading>().last()
        assertEquals(lastDl.total, lastDl.done)

        // a second run finds everything done and fetches nothing
        calls.clear()
        ImageDownloads.download(root, model, DownloadSource.HF_MIRROR, fetch = { u, _, _, _ -> calls += u }, publish = {}, retryDelayMs = 0)
        assertTrue(calls.isEmpty())

        assertTrue(ImageDownloads.delete(listOf(root, tmp()), model))
        assertFalse(dir.exists())
        assertFalse(ImageGenFiles.isDownloaded(root, model))
    }

    @Test fun fallsBackToOtherHostAndReportsFailure() = runBlocking<Unit> {
        val src = tmp(); val root = tmp()
        val (model, sources) = fakeModel(src)
        val hosts = mutableListOf<String>()
        ImageDownloads.download(root, model, DownloadSource.HF_MIRROR, fetch = { url, target, _, _ ->
            hosts += url.substringBefore("/r/")
            if (url.startsWith("https://hf-mirror.com")) throw IOException("mirror down")
            sources.getValue(url.substringAfterLast('/')).copyTo(target, overwrite = true)
        }, publish = {}, retryDelayMs = 0)
        assertEquals(3, hosts.count { it == "https://hf-mirror.com" })
        assertTrue(ImageGenFiles.isDownloaded(root, model))

        val root2 = tmp()
        try {
            ImageDownloads.download(root2, model, DownloadSource.HF_MIRROR, fetch = { _, _, _, _ -> throw IOException("offline") }, publish = {}, retryDelayMs = 0)
            fail("must throw")
        } catch (e: IOException) { assertEquals("offline", e.message) }
        assertFalse(ImageGenFiles.isDownloaded(root2, model))
    }

    @Test fun nothingToDownloadForHexagonModels() = runBlocking<Unit> {
        try {
            ImageDownloads.download(tmp(), ImageCatalog.FLUX2_KLEIN, DownloadSource.HF_MIRROR, fetch = { _, _, _, _ -> }, publish = {})
            fail("must throw")
        } catch (_: IOException) { }
    }

}

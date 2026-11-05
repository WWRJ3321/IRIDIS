package com.iris.localai.v217.imagegen

import com.iris.localai.imagegen.ImageCatalog
import com.iris.localai.imagegen.ImageGenFiles
import com.iris.localai.imagegen.ImageRequest
import com.iris.localai.imagegen.PngParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class ImageGenFilesTest {
    private val t = LocalDateTime.of(2026, 10, 22, 2, 4, 5)
    private fun tmp(): File = Files.createTempDirectory("imggen").toFile()

    /** 1×1 PNG: signature + IHDR + IDAT + IEND. */
    private val png: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + PngParams.chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0)) +
        PngParams.chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0, 0, 0, 1, 0, 1)) + PngParams.chunk("IEND", ByteArray(0))

    @Test fun outputNameAndPath() {
        assertEquals("IMG_20261022_020405.png", ImageGenFiles.outputName(t))
        val ws = tmp()
        val f = ImageGenFiles.outputFile(ws, t)
        assertEquals(File(File(ws, "images"), "IMG_20261022_020405.png"), f)
        assertEquals("models/imagegen", ImageGenFiles.MODELS_SUB)
    }

    @Test fun sameSecondGetsSuffix() {
        val ws = tmp()
        ImageGenFiles.write(ImageGenFiles.outputFile(ws, t), png, "a")
        val second = ImageGenFiles.outputFile(ws, t)
        assertEquals("IMG_20261022_020405_2.png", second.name)
        ImageGenFiles.write(second, png, "b")
        assertEquals("IMG_20261022_020405_3.png", ImageGenFiles.outputFile(ws, t).name)
    }

    @Test fun writeEmbedsParametersAndHistoryIsNewestFirst() {
        val ws = tmp()
        val req = ImageRequest("一只猫, cat", negative = "blurry", seed = 42).normalize(ImageCatalog.SD15_QNN)
        val params = ImageGenFiles.parametersText(req, ImageCatalog.SD15_QNN)
        assertTrue(params.startsWith("一只猫, cat\nNegative prompt: blurry\nSteps: 20"))
        assertTrue(params.contains("Seed: 42") && params.contains("Size: 512x512") && params.contains("Model: sd15-qnn"))
        val f = ImageGenFiles.write(ImageGenFiles.outputFile(ws, t), png, params)
        assertEquals(params, PngParams.read(f.readBytes()))
        assertFalse(File(f.path + ".part").exists())
        val later = ImageGenFiles.write(ImageGenFiles.outputFile(ws, t.plusMinutes(1)), png, params)
        File(ws, "images/other.png").writeBytes(png)
        assertEquals(listOf(later.name, f.name), ImageGenFiles.history(ws).map { it.name })
        assertTrue(ImageGenFiles.history(tmp()).isEmpty())
    }

    @Test fun negativeDroppedWithoutCfg() {
        val req = ImageRequest("p", negative = "n", cfg = 1f, seed = 1).normalize(ImageCatalog.SD15_MNN)
        assertFalse(ImageGenFiles.parametersText(req, ImageCatalog.SD15_MNN).contains("Negative prompt"))
    }

    @Test fun downloadedDetection() {
        val root = tmp()
        val qnn = ImageCatalog.SD15_QNN
        assertFalse(ImageGenFiles.isDownloaded(root, qnn))
        assertEquals(File(root, "sd15-qnn"), ImageGenFiles.modelDir(root, qnn))
        fun sized(f: File, n: Long) { f.parentFile!!.mkdirs(); java.io.RandomAccessFile(f, "rw").use { it.setLength(n) } }
        for (rf in qnn.files.filter { !it.isArchive }) sized(File(root, rf.path), rf.size)
        val zip = qnn.files.single { it.isArchive }
        sized(File(root, zip.path), zip.size)
        assertFalse("the zip alone is not a model", ImageGenFiles.isDownloaded(root, qnn))
        for (u in zip.unpacked) sized(File(root, "sd15-qnn/${u.path}"), u.size)
        File(root, zip.path).delete()
        assertTrue(ImageGenFiles.isDownloaded(root, qnn))
        sized(File(root, "sd15-qnn/unet.bin"), 10)
        assertFalse("size must match", ImageGenFiles.isDownloaded(root, qnn))

        val mnn = ImageCatalog.SD15_MNN
        assertFalse(ImageGenFiles.isDownloaded(root, mnn))
        val last = mnn.files.last()
        for (rf in mnn.files.dropLast(1)) sized(File(root, rf.path), rf.size)
        sized(File(root, last.path + ".part"), last.size)
        assertFalse("still a .part", ImageGenFiles.isDownloaded(root, mnn))
        File(root, last.path + ".part").renameTo(File(root, last.path))
        assertTrue(ImageGenFiles.isDownloaded(root, mnn))

        File(root, "flux2-klein-4b/x").apply { parentFile!!.mkdirs(); writeText("x") }
        assertFalse("Hexagon models never count", ImageGenFiles.isDownloaded(root, ImageCatalog.FLUX2_KLEIN))
    }

    @Test fun sizes() {
        assertEquals(709_785_222L + 1_059_962L + 524_619L, ImageGenFiles.sizeBytes(ImageCatalog.SD15_QNN))
        assertEquals(1_154_788_884L, ImageGenFiles.sizeBytes(ImageCatalog.SD15_MNN))
        assertNull(ImageGenFiles.sizeBytes(ImageCatalog.FLUX2_KLEIN))
    }
}

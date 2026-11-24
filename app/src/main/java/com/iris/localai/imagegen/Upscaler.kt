package com.iris.localai.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iris.localai.util.io.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 3.0.0 Beta5：生图后超分，libiris_upscale.so（stable-diffusion.cpp 的 ESRGAN 放大器，CPU）。 */
object UpscaleNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("iris_upscale") }.isSuccess }
    @JvmStatic external fun nativeLoad(path: String, threads: Int, tile: Int): Long
    @JvmStatic external fun nativeScale(handle: Long): Int
    @JvmStatic external fun nativeUpscale(handle: Long, rgb: ByteArray, width: Int, height: Int): ByteArray?
    @JvmStatic external fun nativeProgress(): Int
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * 放大 2×/4×：模型 Real-ESRGAN x4plus anime 6B（xinntao/Real-ESRGAN，BSD-3-Clause，17.9 MB），第一次用时下载。
 * 2× = 4× 结果再缩一半（同一个模型，画质比直接 2× 插值好）。纯逻辑部分见 [UpscaleRules]（单测 V300B5Test）。
 */
object Upscaler {
    const val MODEL_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth"
    const val MODEL_SIZE = 17_938_799L
    const val MODEL_SHA256 = "f872d837d3c90ed2e05227bed711af5671a6fd1c9f7d7e91c911a61f155e99da"
    private const val MODEL_NAME = "RealESRGAN_x4plus_anime_6B.pth"

    fun modelFile(context: Context): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "upscale"), MODEL_NAME)

    fun modelReady(context: Context): Boolean = modelFile(context).let { it.isFile && it.length() == MODEL_SIZE }

    /** 下载模型（可续传，校验 SHA-256）。 */
    suspend fun ensureModel(context: Context, onBytes: (Long) -> Unit) {
        val f = modelFile(context)
        if (f.isFile && f.length() == MODEL_SIZE) return
        f.parentFile?.mkdirs()
        HttpDownload.fetchOnce(MODEL_URL, f, MODEL_SIZE, MODEL_SHA256, onBytes)
    }

    /** [src] PNG → 放大后的 PNG 字节。[onProgress] 0..1。 */
    suspend fun upscale(context: Context, src: File, factor: Int, onProgress: (Float) -> Unit): ByteArray = withContext(Dispatchers.Default) {
        if (!UpscaleNative.available) throw IllegalStateException("upscale library missing")
        val bmp = BitmapFactory.decodeFile(src.path) ?: throw IllegalStateException("decode failed")
        val (w, h) = bmp.width to bmp.height
        UpscaleRules.check(w, h, factor)
        val rgb = UpscaleRules.argbToRgb(IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) })
        bmp.recycle()
        val handle = UpscaleNative.nativeLoad(modelFile(context).path, UpscaleRules.threads(Runtime.getRuntime().availableProcessors()), UpscaleRules.TILE)
        if (handle == 0L) throw IllegalStateException(UpscaleNative.nativeLastError().ifBlank { "model load failed" })
        val out = try {
            coroutineScope {
                val poll = launch { while (isActive) { onProgress(UpscaleNative.nativeProgress() / 1000f * 0.95f); delay(300) } }
                try { UpscaleNative.nativeUpscale(handle, rgb, w, h) } finally { poll.cancel() }
            }
        } finally { UpscaleNative.nativeFree(handle) }
            ?: throw IllegalStateException(UpscaleNative.nativeLastError().ifBlank { "upscale failed" })
        val (ow, oh) = UpscaleRules.header(out)
        // 逐行转像素写进 Bitmap（像素在 native 堆），不再整张生成 ow·oh 的 IntArray
        var big = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val row = IntArray(ow)
        for (y in 0 until oh) { UpscaleRules.rgbRow(out, 8 + y * ow * 3, row); big.setPixels(row, 0, ow, 0, y, ow, 1) }
        if (factor == 2) {
            val half = Bitmap.createScaledBitmap(big, ow / 2, oh / 2, true)
            big.recycle(); big = half
        }
        onProgress(1f)
        ByteArrayOutputStream().use { bo -> big.compress(Bitmap.CompressFormat.PNG, 100, bo); big.recycle(); bo.toByteArray() }
    }
}

/** 超分的纯逻辑（无 Android 依赖，单测 V300B5Test）。 */
object UpscaleRules {
    /** 输入边长上限：2× 时 1536（中间 4× 结果 6144）。 */
    const val MAX_SIDE = 1536
    /** 3.0.0：4× 输入边长上限 1024（输出 4096²），防 OOM。 */
    const val MAX_SIDE_4X = 1024
    const val TILE = 128
    fun maxSide(factor: Int): Int = if (factor >= 4) MAX_SIDE_4X else MAX_SIDE
    fun fits(w: Int, h: Int, factor: Int = 2): Boolean = w in 1..maxSide(factor) && h in 1..maxSide(factor)
    fun check(w: Int, h: Int, factor: Int = 2) {
        require(fits(w, h, factor)) { "image too large: ${w}x$h (max ${maxSide(factor)})" }
    }
    /** 一行 RGB（从 [off] 起 row.size 个像素）→ ARGB。 */
    fun rgbRow(b: ByteArray, off: Int, row: IntArray) {
        for (i in row.indices) {
            val p = off + i * 3
            row[i] = (0xFF shl 24) or ((b[p].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)
        }
    }
    /** 留一个核给界面。 */
    fun threads(cores: Int): Int = (cores - 1).coerceIn(1, 6)
    fun argbToRgb(px: IntArray): ByteArray {
        val o = ByteArray(px.size * 3)
        for (i in px.indices) { val c = px[i]; o[i * 3] = (c shr 16).toByte(); o[i * 3 + 1] = (c shr 8).toByte(); o[i * 3 + 2] = c.toByte() }
        return o
    }
    fun rgbToArgb(b: ByteArray, off: Int, n: Int): IntArray = IntArray(n) { i ->
        val p = off + i * 3
        (0xFF shl 24) or ((b[p].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)
    }
    /** nativeUpscale 返回值头 8 字节：宽、高（小端）。 */
    fun header(b: ByteArray): Pair<Int, Int> {
        fun le(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        val w = le(0); val h = le(4)
        require(w > 0 && h > 0 && b.size.toLong() == 8L + w.toLong() * h * 3) { "bad upscale output" }
        return w to h
    }
    /** 放大结果的参数文字：原参数后追加一行。 */
    fun parameters(original: String?, factor: Int): String =
        (original?.trimEnd()?.let { "$it, " } ?: "") + "Upscale: ${factor}x RealESRGAN_x4plus_anime_6B"
}

package com.iris.localai.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 自定义背景. The picked photo (system photo picker, no storage permission) is decoded once,
 * scaled down to the screen and copied to filesDir/backgrounds/bg-<time>.jpg; the original URI is
 * not kept. Only the file name is stored in settings ([com.iris.localai.data.AppSettings.backgroundImage]).
 */
object BackgroundImage {
    const val DIR = "backgrounds"
    const val MAX_BLUR_DP = 25
    const val MAX_SCRIM = 90
    private val NAME = Regex("bg-\\d{1,19}\\.jpg")

    fun isValidName(name: String?): Boolean = name != null && NAME.matches(name)

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(context: Context, name: String): File? =
        if (!isValidName(name)) null else File(dir(context), name).takeIf { it.isFile }

    /**
     * Decode size that covers a [screenW]×[screenH] screen (portrait) without upscaling: the
     * picture is scaled so both sides are at least the screen's, then [cropSize] trims the rest.
     */
    fun decodeSize(srcW: Int, srcH: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 1 to 1
        val sw = min(screenW, screenH).coerceAtLeast(1)
        val sh = max(screenW, screenH).coerceAtLeast(1)
        val scale = min(1f, max(sw.toFloat() / srcW, sh.toFloat() / srcH))
        return max(1, (srcW * scale).roundToInt()) to max(1, (srcH * scale).roundToInt())
    }

    /** Centre crop of a decoded [w]×[h] picture to the screen's portrait size (never larger than the picture). */
    fun cropSize(w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        min(w, min(screenW, screenH).coerceAtLeast(1)) to min(h, max(screenW, screenH).coerceAtLeast(1))

    /** Copies [uri] into app storage; returns the new file name, or null when it cannot be read. */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            // ImageDecoder applies the EXIF rotation and decodes straight to the reduced size.
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val (w, h) = decodeSize(info.size.width, info.size.height, screenW, screenH)
                dec.setTargetSize(w, h)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val (cw, ch) = cropSize(decoded.width, decoded.height, screenW, screenH)
            val cropped = if (cw == decoded.width && ch == decoded.height) decoded
            else Bitmap.createBitmap(decoded, (decoded.width - cw) / 2, (decoded.height - ch) / 2, cw, ch)
            val dir = dir(context).apply { mkdirs() }
            val name = "bg-${System.currentTimeMillis()}.jpg"
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
            check(tmp.renameTo(File(dir, name)))
            // only the new picture is kept
            dir.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
            name
        }.getOrNull()
    }

    /** 「移除背景」: deletes the stored picture(s). */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}

@Composable
private fun rememberBackgroundBitmap(name: String): ImageBitmap? {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, name) {
        value = if (name.isEmpty()) null else withContext(Dispatchers.IO) {
            BackgroundImage.file(context, name)?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    return bmp
}

/**
 * The custom background behind the chat: the picture (blurred by [blurDp]) under a scrim of the
 * theme's chat background ([scrimPercent]) so text stays readable. Draws nothing without a picture.
 */
@Composable
fun ChatBackdropImage(name: String, blurDp: Int, scrimPercent: Int, modifier: Modifier = Modifier) {
    if (name.isEmpty()) return
    val bmp = rememberBackgroundBitmap(name) ?: return
    val c = Iris.colors
    Box(modifier.fillMaxSize()) {
        val blur = blurDp.coerceIn(0, BackgroundImage.MAX_BLUR_DP)
        Image(
            bmp, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (blur > 0) Modifier.blur(blur.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
        )
        val scrim = scrimPercent.coerceIn(0, BackgroundImage.MAX_SCRIM) / 100f
        if (scrim > 0f) Box(Modifier.fillMaxSize().background(c.chatBackground.copy(alpha = scrim)))
    }
}

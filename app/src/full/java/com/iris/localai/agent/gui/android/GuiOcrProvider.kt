package com.iris.localai.agent.gui.android

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import android.graphics.Bitmap
import com.iris.localai.agent.gui.GuiOcr
import com.iris.localai.agent.gui.OcrDiff
import com.iris.localai.agent.gui.OcrLine
import com.iris.localai.agent.gui.PxRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 2.2.3: GUI OCR fallback with ML Kit text recognition (Chinese + Latin, bundled model: works
 * offline, no Play Services download). Lines are mapped from the (downscaled) screenshot back to
 * screen pixels. Any failure yields no lines.
 */
object GuiOcrProvider {
    @Volatile private var recognizer: TextRecognizer? = null

    private fun recognizer(): TextRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { recognizer = it }
        }

    /** 2.4.0: last screenshot's grid and lines (screen px), so an unchanged area is not recognised again. */
    private class Last(val grid: OcrDiff.Grid, val lines: List<OcrLine>, val at: Long)
    @Volatile private var last: Last? = null
    private const val MAX_AGE_MS = 60_000L

    private fun recognise(bmp: Bitmap, ox: Int, oy: Int, sx: Float, sy: Float): List<OcrLine> {
        val text = Tasks.await(recognizer().process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
        return text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val b = l.boundingBox ?: return@mapNotNull null
            val t = l.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            OcrLine(t, PxRect(((b.left + ox) * sx).toInt(), ((b.top + oy) * sy).toInt(), ((b.right + ox) * sx).toInt(), ((b.bottom + oy) * sy).toInt()))
        }
    }

    fun get(context: Context): GuiOcr = GuiOcr { path, screenW, screenH ->
        withContext(Dispatchers.Default) {
            runCatching {
                val bmp = BitmapFactory.decodeFile(path) ?: return@runCatching emptyList<OcrLine>()
                try {
                    val sx = screenW.toFloat() / bmp.width
                    val sy = screenH.toFloat() / bmp.height
                    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                    val grid = OcrDiff.grid(px, bmp.width, bmp.height)
                    val prev = last?.takeIf { System.currentTimeMillis() - it.at < MAX_AGE_MS }
                    val lines = when (val plan = OcrDiff.plan(prev?.grid, grid)) {
                        OcrDiff.Plan.Reuse -> prev!!.lines
                        OcrDiff.Plan.Full -> recognise(bmp, 0, 0, sx, sy)
                        is OcrDiff.Plan.Crop -> {
                            val r = plan.rect
                            val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width, r.height)
                            val fresh = try { recognise(crop, r.left, r.top, sx, sy) } finally { if (crop !== bmp) crop.recycle() }
                            val screenRect = PxRect((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt())
                            OcrDiff.merge(prev!!.lines, screenRect, fresh)
                        }
                    }
                    last = Last(grid, lines, System.currentTimeMillis())
                    lines
                } finally { bmp.recycle() }
            }.onFailure { last = null; Log.w("IrisGuiOcr", "ocr failed: ${it.message}") }.getOrDefault(emptyList())
        }
    }
}

package com.iris.localai.agent.gui.android

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.iris.localai.agent.gui.GuiOcr
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

    fun get(context: Context): GuiOcr = GuiOcr { path, screenW, screenH ->
        withContext(Dispatchers.Default) {
            runCatching {
                val bmp = BitmapFactory.decodeFile(path) ?: return@runCatching emptyList<OcrLine>()
                try {
                    val sx = screenW.toFloat() / bmp.width
                    val sy = screenH.toFloat() / bmp.height
                    val text = Tasks.await(recognizer().process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
                    text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                        val b = l.boundingBox ?: return@mapNotNull null
                        val t = l.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        OcrLine(t, PxRect((b.left * sx).toInt(), (b.top * sy).toInt(), (b.right * sx).toInt(), (b.bottom * sy).toInt()))
                    }
                } finally { bmp.recycle() }
            }.onFailure { Log.w("IrisGuiOcr", "ocr failed: ${it.message}") }.getOrDefault(emptyList())
        }
    }
}

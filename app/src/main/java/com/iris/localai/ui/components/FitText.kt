package com.iris.localai.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 2.4.4: one line that shrinks its font (down to [minScale] of [style]) until the whole text fits,
 * instead of cutting it with 「…」; only text that still does not fit at the smallest size is ellipsized.
 * Used where a second line would break the layout (pills, buttons, segments).
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.72f,
    textAlign: TextAlign? = null,
) {
    val base: TextUnit = if (style.fontSize.isSpecified) style.fontSize else 16.sp
    var scale by remember(text, base) { mutableStateOf(1f) }
    var ready by remember(text, base) { mutableStateOf(false) }
    Text(
        text,
        style = style.copy(fontSize = base * scale, lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight),
        color = color, maxLines = 1, softWrap = false, textAlign = textAlign,
        overflow = if (scale <= minScale) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            if (r.hasVisualOverflow && scale > minScale) scale = (scale - 0.07f).coerceAtLeast(minScale) else ready = true
        },
    )
}

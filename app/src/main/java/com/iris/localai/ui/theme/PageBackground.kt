package com.iris.localai.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

/**
 * 2.5.0 shared page skeleton. 柔光: a soft vertical gradient (tinted top → page colour by ~55 %);
 * 经典 / 纯色背景 / 自定义背景: the plain page colour, exactly as ≤ 2.4.6. Chat, welcome and every
 * subpage draw their background through this one place.
 */
enum class PageKind { SETTINGS, CHAT }

object PageGradient {
    /** Colour stops (pure, unit-tested): null = draw the plain colour. */
    fun stops(top: Color, base: Color): List<Pair<Float, Color>>? =
        if (!top.isSpecified || top == base) null else listOf(0f to top, 0.55f to base, 1f to base)
}

fun Modifier.irisPageBackground(kind: PageKind = PageKind.SETTINGS): Modifier = composed {
    val c = Iris.colors
    val base = if (kind == PageKind.CHAT) c.chatBackground else c.background
    val stops = PageGradient.stops(c.backgroundTop, base)
    if (stops == null) background(base)
    else background(Brush.verticalGradient(*stops.toTypedArray()))
}

/** The shared full-screen surface for chat / welcome / subpages. */
@Composable
fun IrisPageSurface(kind: PageKind, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().irisPageBackground(kind), content = content)
}

package com.iris.localai.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * 2.2.4: the one soft shadow of the design language — wide, faint, never a hard edge. Dark theme
 * uses a deeper (but still diffuse) shadow since the surface colour already lifts the element.
 */
fun Modifier.softShadow(elevation: Dp, shape: Shape, dark: Boolean): Modifier =
    if (elevation.value <= 0f) this else this.shadow(
        elevation, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.06f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.10f),
    )

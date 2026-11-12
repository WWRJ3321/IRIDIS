package com.iris.localai.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.iris.localai.ui.theme.LocalReduceMotion

/**
 * 2.5.3: one-shot entrance for full-screen dialog content (image viewer, web preview, image history):
 * fade in + 0.96 → 1 scale with the Gentle spring, instead of popping in. Only graphicsLayer work;
 * instant when the system removes animations.
 */
@Composable
fun Modifier.enterOnce(): Modifier {
    val reduce = LocalReduceMotion.current
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, IrisMotion.Gentle.float()) }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        val s = 0.96f + 0.04f * v
        scaleX = s; scaleY = s
    }
}

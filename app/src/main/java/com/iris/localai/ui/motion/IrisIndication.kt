package com.iris.localai.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * App-wide press feedback for every clickable (rows, list items, menu items, buttons):
 * an iOS-style highlight that springs in under the finger and fades out on release. A tap that
 * is shorter than the fade-in still flashes visibly. Provided as LocalIndication by the theme, so
 * any `clickable` without an explicit indication gets it. The alpha is read in draw only.
 */
class IrisIndication(private val color: Color, private val reduce: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HighlightNode(interactionSource, color, reduce)

    override fun equals(other: Any?): Boolean = other is IrisIndication && other.color == color && other.reduce == reduce
    override fun hashCode(): Int = color.hashCode() * 31 + reduce.hashCode()
}

private class HighlightNode(
    private val source: InteractionSource,
    private val color: Color,
    private val reduce: Boolean,
) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var downAt = 0L
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        downAt = System.currentTimeMillis()
                        launch {
                            if (reduce) alpha.snapTo(1f) else alpha.animateTo(1f, IrisMotion.Press.float()) { invalidateDraw() }
                            invalidateDraw()
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                        if (reduce) { alpha.snapTo(0f); invalidateDraw(); return@launch }
                        // A quick tap: let the highlight reach most of its strength before fading.
                        if (System.currentTimeMillis() - downAt < PressMotion.QUICK_TAP_MS && alpha.value < 0.7f) {
                            alpha.animateTo(0.85f, IrisMotion.Press.float()) { invalidateDraw() }
                        }
                        alpha.animateTo(0f, IrisMotion.FadeOut.float()) { invalidateDraw() }
                        invalidateDraw()
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val a = alpha.value
        if (a > 0.001f) {
            val col = color.copy(alpha = color.alpha * a.coerceIn(0f, 1f))
            // Beta2: icon-sized targets (near-square) get a round highlight instead of a square one;
            // wide targets (rows, pills) keep the rectangle, which their container already clips.
            if (PressMotion.isIconLike(size.width, size.height)) {
                drawCircle(col, radius = minOf(size.width, size.height) / 2f)
            } else drawRect(col)
        }
        drawContent()
    }
}

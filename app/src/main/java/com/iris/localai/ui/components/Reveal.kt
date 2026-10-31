package com.iris.localai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.LocalReduceMotion

/**
 * 1.13.0: rows that appear because of another setting (a tool's permission, an
 * optional field) unfold with the app's spring instead of snapping in; instant with reduce-motion.
 */
@Composable
fun RevealSection(visible: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = if (reduce) EnterTransition.None else IrisMotion.expand(),
        exit = if (reduce) ExitTransition.None else IrisMotion.collapse(),
    ) { Column(content = content) }
}

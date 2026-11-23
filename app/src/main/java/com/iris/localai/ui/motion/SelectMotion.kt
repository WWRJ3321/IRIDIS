package com.iris.localai.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.iris.localai.ui.theme.LocalReduceMotion

/**
 * Beta4 r2 动效查漏：选中态统一过渡。以前选中胶囊的底色、对勾都是瞬间跳变，
 * 现在颜色走 IrisMotion.Color，对勾走 popIn / popOut（减少动态效果时直接切换）。
 */
@Composable
fun selectTint(target: Color, label: String = "selectTint"): Color {
    val c by animateColorAsState(target, motionSpec(IrisMotion.Color), label = label)
    return c
}

@Composable
private fun checkEnter(): EnterTransition = if (LocalReduceMotion.current) EnterTransition.None else IrisMotion.popIn(0.5f)
@Composable
private fun checkExit(): ExitTransition = if (LocalReduceMotion.current) ExitTransition.None else IrisMotion.popOut(0.5f)

@Composable
fun RowScope.SelectCheck(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = checkEnter(), exit = checkExit()) { content() }
}

@Composable
fun BoxScope.SelectCheck(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = checkEnter(), exit = checkExit()) { content() }
}

package com.iris.localai.ui.glass

import com.iris.localai.ui.theme.IrisDimens
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 1.7.1: system cross-window blur behind a dialog / bottom-sheet window (Android 12+). Call inside
 * the dialog's content.
 *
 * 1.7.3: only at 华丽 ([GlassQuality.windowBlur]; never at 均衡 / 省电, in battery saver or when
 * hot) and only while the system has cross-window blur enabled (it is switched off in battery
 * saver, during some animations, and by the developer option). While the blur is on, the window's
 * dim is lowered to [dimWhileBlurred] so blur + dim do not turn the whole screen grey; when it is
 * cleared, the blur radius goes back to 0 and the original dim is restored. The system re-renders
 * the blur only when the windows behind change, so it costs nothing while idle.
 */
@Composable
fun DialogWindowBlur(quality: GlassQuality, radius: Dp = IrisDimens.space24, dimWhileBlurred: Float = 0.18f) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, quality.windowBlur, px) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val wm = view.context.getSystemService(WindowManager::class.java)
        val originalDim = window?.attributes?.dimAmount
        fun clear() {
            window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply {
                    blurBehindRadius = 0
                    if (originalDim != null) dimAmount = originalDim
                }
            }
        }
        val listener = java.util.function.Consumer<Boolean> { enabled ->
            window?.let { w ->
                if (enabled && quality.windowBlur) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply {
                        blurBehindRadius = px
                        if (originalDim != null) dimAmount = minOf(originalDim, dimWhileBlurred)
                    }
                } else {
                    clear()
                }
            }
        }
        val registered = if (window != null && wm != null && quality.windowBlur) {
            runCatching { wm.addCrossWindowBlurEnabledListener(view.context.mainExecutor, listener) }
                .onFailure { listener.accept(wm.isCrossWindowBlurEnabled) }
                .isSuccess
        } else {
            clear()
            false
        }
        onDispose {
            if (registered && wm != null) runCatching { wm.removeCrossWindowBlurEnabledListener(listener) }
            clear()
        }
    }
}

/** 1.7.3: lighter sheet scrim at 华丽, where the window behind is blurred instead of darkened. */
fun sheetScrim(base: androidx.compose.ui.graphics.Color, quality: GlassQuality): androidx.compose.ui.graphics.Color =
    if (quality.windowBlur) base.copy(alpha = base.alpha * 0.45f) else base

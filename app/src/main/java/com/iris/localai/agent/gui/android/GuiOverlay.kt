package com.iris.localai.agent.gui.android

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.iris.localai.agent.gui.GuiStopSignal
import kotlinx.coroutines.CompletableDeferred

/**
 * 2.2 GUI 智能体: the floating bar shown while a task runs — status + 停止, and the 允许/拒绝 or
 * 我已完成/停止 questions. An accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY), so it needs no
 * 悬浮窗 permission. It is hidden for each screenshot. 2.2.7: frosted capsule in the app's theme
 * (accent status dot, two-line text, tonal 停止), fade/slide in and out.
 */
object GuiOverlay {
    private val main = Handler(Looper.getMainLooper())
    /** 2.2.7: window root (transparent, padded so the capsule's shadow is not clipped). */
    private var bar: FrameLayout? = null
    private var titleView: TextView? = null
    private var status: TextView? = null
    private var dotAnim: AnimatorSet? = null
    private var question: LinearLayout? = null
    private var onStop: (() -> Unit)? = null
    @Volatile private var capturing = false

    /** 2.2.7: the app's theme (saved 配色 + 深色模式, system dark followed) resolved for plain views. */
    private class Look(
        val surface: Int, val hairline: Int, val title: Int, val sub: Int,
        val accent: Int, val errBg: Int, val errFg: Int,
    )

    private fun look(s: IrisAccessibilityService): Look {
        val settings = runCatching { (s.application as com.iris.localai.IrisApp).container.settings.settings.value }.getOrNull()
        val sysDark = (s.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (settings?.themeMode) {
            com.iris.localai.data.ThemeMode.LIGHT -> false
            com.iris.localai.data.ThemeMode.DARK -> true
            else -> sysDark
        }
        val c = com.iris.localai.ui.theme.IrisPalette.of(settings?.themePalette).colors(dark)
        // No per-window background blur for an overlay view (blur-behind would blur the whole
        // screen and the agent's screenshots), so the frosted surface is a little more opaque.
        val base = if (dark) c.surfaceElevated.toArgb() else Color.WHITE
        val surface = ColorUtils.setAlphaComponent(base, if (dark) 0xEB else 0xED)
        val danger = c.danger.toArgb()
        val opaque = ColorUtils.setAlphaComponent(base, 0xFF)
        return Look(
            surface = surface,
            hairline = if (dark) 0x1FFFFFFF else 0x14000000,
            title = c.text.toArgb(),
            sub = c.textSecondary.toArgb(),
            accent = c.accent.toArgb(),
            errBg = ColorUtils.blendARGB(opaque, danger, if (dark) 0.22f else 0.13f),
            errFg = ColorUtils.blendARGB(danger, if (dark) Color.WHITE else Color.BLACK, 0.18f),
        )
    }

    /** "GUI 智能体 · 第 1 步 · 思考 7s · 226 字" → title (first two parts) + status (the rest). */
    internal fun split(text: String): Pair<String, String> {
        val parts = text.split(" · ")
        return parts.take(2).joinToString(" · ") to parts.drop(2).joinToString(" · ")
    }

    fun show(text: String, stop: () -> Unit) = main.post {
        onStop = stop
        val s = IrisAccessibilityService.instance ?: return@post
        if (bar != null) { setText(text); return@post }
        val wm = s.getSystemService(WindowManager::class.java)
        val k = look(s)
        val root = FrameLayout(s).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(s, 12), dp(s, 12), dp(s, 12), dp(s, 16))
        }
        val card = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(k.surface)
                cornerRadius = dp(s, 22).toFloat()
                setStroke(maxOf(1, dp(s, 1) / 2), k.hairline)
            }
            elevation = dp(s, 8).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setPadding(dp(s, 14), 0, dp(s, 4), 0)
        }
        // Status dot in the theme accent, pulsing while the agent works.
        val dot = View(s).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(k.accent) }
        }
        val texts = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val t = TextView(s).apply {
            setTextColor(k.title); textSize = 13f; maxLines = 1; includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val st = TextView(s).apply {
            setTextColor(k.sub); textSize = 11f; maxLines = 1; includeFontPadding = false
            fontFeatureSettings = "tnum"
            setPadding(0, dp(s, 2), 0, 0)
        }
        texts.addView(t); texts.addView(st)
        val stopBtn = TextView(s).apply {
            this.text = "停止"
            setTextColor(k.errFg); textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(k.errFg, 0x33)),
                GradientDrawable().apply { setColor(k.errBg); cornerRadius = dp(s, 18).toFloat() }, null,
            )
            setPadding(dp(s, 14), 0, dp(s, 14), 0)
            setOnClickListener {
                GuiStopSignal.request()
                onStop?.invoke()
            }
        }
        card.addView(dot, LinearLayout.LayoutParams(dp(s, 8), dp(s, 8)))
        card.addView(texts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(s, 10) })
        card.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(s, 36)).apply { marginStart = dp(s, 12) })
        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(s, 44)))
        titleView = t; status = st
        setText(text, animate = false)
        // Enter: fade + slide down.
        card.alpha = 0f; card.translationY = -dp(s, 16).toFloat()
        runCatching { wm.addView(root, params(s, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dp(s, 24))) }
            .onSuccess {
                bar = root
                card.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
                dotAnim = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply { repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE },
                        ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 0.7f).apply { repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE },
                        ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 0.7f).apply { repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE },
                    )
                    duration = 700; start()
                }
            }
            .onFailure { titleView = null; status = null }
    }

    /** Title change (new step) cross-fades; the status line (ticking seconds) updates in place. */
    private fun setText(text: String, animate: Boolean = true) {
        val (title, sub) = split(text)
        val t = titleView ?: return
        if (t.text.toString() != title) {
            t.text = title
            if (animate) { t.alpha = 0.3f; t.animate().alpha(1f).setDuration(220).start() }
        }
        status?.let { st ->
            val wasGone = st.visibility == View.GONE
            st.text = sub
            st.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            if (animate && wasGone && sub.isNotEmpty()) { st.alpha = 0f; st.animate().alpha(1f).setDuration(220).start() }
        }
    }

    fun update(text: String) = main.post { setText(text) }

    fun hide() = main.post {
        val s = IrisAccessibilityService.instance
        val wm = s?.getSystemService(WindowManager::class.java)
        dotAnim?.cancel(); dotAnim = null
        bar?.let { v ->
            val card = v.getChildAt(0)
            // Exit: fade + slide up, then remove the window.
            if (card != null && v.isAttachedToWindow) {
                card.animate().alpha(0f).translationY(-card.height * 0.5f).setDuration(200)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction { runCatching { wm?.removeView(v) } }.start()
            } else runCatching { wm?.removeView(v) }
        }
        question?.let { v -> runCatching { wm?.removeView(v) } }
        bar = null; titleView = null; status = null; question = null; onStop = null
    }

    /** Called around each screenshot so the bar is not part of what the model sees. */
    fun setHiddenForCapture(hidden: Boolean) {
        capturing = hidden
        main.post {
            val v = if (capturing) View.INVISIBLE else View.VISIBLE
            bar?.visibility = v
            question?.visibility = v
        }
    }

    /** Shows [message] with two buttons; true = [yes]. Without the service the answer is false (safe). */
    suspend fun ask(message: String, yes: String, no: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        main.post {
            val s = IrisAccessibilityService.instance ?: run { answer.complete(false); return@post }
            val wm = s.getSystemService(WindowManager::class.java)
            question?.let { runCatching { wm.removeView(it) } }
            // 2.2.9: same frosted card + theme accent as the 停止 bar.
            val k = look(s)
            val box = LinearLayout(s).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(k.surface); cornerRadius = dp(s, 22).toFloat()
                    setStroke(maxOf(1, dp(s, 1) / 2), k.hairline)
                }
                elevation = dp(s, 8).toFloat()
                setPadding(dp(s, 16), dp(s, 14), dp(s, 16), dp(s, 12))
            }
            val t = TextView(s).apply { setTextColor(k.title); textSize = 14f; text = message }
            val buttons = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            fun done(v: Boolean) {
                runCatching { wm.removeView(box) }
                if (question === box) question = null
                answer.complete(v)
            }
            buttons.addView(button(s, no, ColorUtils.setAlphaComponent(k.sub, 0x24), k.title) { done(false) })
            buttons.addView(button(s, yes, k.accent, Color.WHITE) { done(true) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(s, 10) })
            box.addView(t)
            box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(s, 12) })
            runCatching { wm.addView(box, params(s, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(s, 96), widthFraction = true)) }
                .onSuccess { question = box }
                .onFailure { answer.complete(false) }
        }
        return answer.await()
    }

    private fun params(s: IrisAccessibilityService, gravity: Int, y: Int, widthFraction: Boolean = false) = WindowManager.LayoutParams(
        if (widthFraction) (s.resources.displayMetrics.widthPixels * 0.9f).toInt() else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity; this.y = y }

    private fun button(s: IrisAccessibilityService, label: String, color: Int, fg: Int = Color.WHITE, onClick: () -> Unit) = TextView(s).apply {
        text = label
        setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f
        background = pill(s, color)
        setPadding(dp(s, 14), dp(s, 6), dp(s, 14), dp(s, 6))
        setOnClickListener { onClick() }
    }

    private fun pill(s: IrisAccessibilityService, color: Int, radius: Float = 999f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radius.coerceAtMost(40f), s.resources.displayMetrics)
    }

    private fun dp(s: IrisAccessibilityService, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), s.resources.displayMetrics).toInt()
}

package com.iris.localai.agent.gui.android

import android.graphics.Color
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
 * 悬浮窗 permission. Plain views, no animation. It is hidden for each screenshot.
 */
object GuiOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var bar: LinearLayout? = null
    private var status: TextView? = null
    private var question: LinearLayout? = null
    private var onStop: (() -> Unit)? = null
    @Volatile private var capturing = false

    fun show(text: String, stop: () -> Unit) = main.post {
        onStop = stop
        val s = IrisAccessibilityService.instance ?: return@post
        if (bar != null) { status?.text = text; return@post }
        val wm = s.getSystemService(WindowManager::class.java)
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(s, 0xE6202124.toInt())
            setPadding(dp(s, 14), dp(s, 6), dp(s, 6), dp(s, 6))
        }
        val t = TextView(s).apply { setTextColor(Color.WHITE); textSize = 13f; this.text = text; maxLines = 1 }
        val stopBtn = button(s, "停止", 0xFFE5484D.toInt()) {
            GuiStopSignal.request()
            onStop?.invoke()
        }
        row.addView(t)
        row.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(s, 10) })
        runCatching { wm.addView(row, params(s, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dp(s, 36))) }
            .onSuccess { bar = row; status = t }
    }

    fun update(text: String) = main.post { status?.text = text }

    fun hide() = main.post {
        val s = IrisAccessibilityService.instance
        val wm = s?.getSystemService(WindowManager::class.java)
        bar?.let { v -> runCatching { wm?.removeView(v) } }
        question?.let { v -> runCatching { wm?.removeView(v) } }
        bar = null; status = null; question = null; onStop = null
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
            val box = LinearLayout(s).apply {
                orientation = LinearLayout.VERTICAL
                background = pill(s, 0xF0202124.toInt(), radius = 16f)
                setPadding(dp(s, 16), dp(s, 14), dp(s, 16), dp(s, 12))
            }
            val t = TextView(s).apply { setTextColor(Color.WHITE); textSize = 14f; text = message }
            val buttons = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            fun done(v: Boolean) {
                runCatching { wm.removeView(box) }
                if (question === box) question = null
                answer.complete(v)
            }
            buttons.addView(button(s, no, 0xFF5F6368.toInt()) { done(false) })
            buttons.addView(button(s, yes, 0xFF3D7BF7.toInt()) { done(true) },
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

    private fun button(s: IrisAccessibilityService, label: String, color: Int, onClick: () -> Unit) = TextView(s).apply {
        text = label
        setTextColor(Color.WHITE)
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

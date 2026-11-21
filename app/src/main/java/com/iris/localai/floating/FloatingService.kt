package com.iris.localai.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.iris.localai.I18n
import com.iris.localai.IrisApp
import com.iris.localai.R
import com.iris.localai.agent.gui.OcrLine
import com.iris.localai.agent.gui.UiNode
import com.iris.localai.agent.gui.android.AccessibilityGuiDevice
import com.iris.localai.agent.gui.android.GuiOcrProvider
import com.iris.localai.agent.gui.android.IrisAccessibilityService
import com.iris.localai.data.ThemeMode
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.GenOwner
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.Requester
import com.iris.localai.prompts.PromptKey
import com.iris.localai.prompts.Prompts
import com.iris.localai.ui.MainActivity
import com.iris.localai.ui.QuickNav
import com.iris.localai.ui.theme.IrisPalette
import com.iris.localai.voice.ASR_SAMPLE_RATE
import com.iris.localai.voice.Microphone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 悬浮窗的模式与小工具（纯 JVM，单测）。 */
object FloatingLogic {
    enum class Mode { TRANSLATE, SCREEN, ASK }

    /** 无障碍树里可读的文字，去重、按屏幕从上到下。 */
    fun screenText(root: UiNode?, maxChars: Int = 4000): String {
        if (root == null) return ""
        val out = ArrayList<Pair<Int, String>>()
        fun visit(n: UiNode) {
            if (!n.visible) return
            val t = n.text.ifBlank { n.desc }.trim()
            if (t.isNotEmpty() && !n.password) out += n.bounds.top to t
            n.children.forEach(::visit)
        }
        visit(root)
        val seen = HashSet<String>()
        return out.sortedBy { it.first }.map { it.second }.filter { seen.add(it) }.joinToString("\n").take(maxChars)
    }
}

/**
 * 3.0 悬浮窗：屏幕边上一颗小球，点开是面板——AI 翻译、同声传译（麦克风 → 识别 → 翻译字幕）、识屏（读屏幕文字让模型讲要点）、
 * 漫画翻译（截屏 OCR，把译文贴回气泡位置）、网页搜索、问 AI。需要「显示在其他应用上层」权限；识屏 / 漫画还要开 IRIDIS 无障碍。
 * 用普通 View 写（没有 Activity 生命周期），配色取 App 主题。
 */
class FloatingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var input: EditText? = null
    private var output: TextView? = null
    private var mode = FloatingLogic.Mode.TRANSLATE
    private var work: Job? = null
    private val chips = HashMap<FloatingLogic.Mode, TextView>()

    private val container get() = (application as IrisApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForegroundCompat()
        showBubble()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        listOf(panel, bubble).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        _running.value = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- look

    private class Look(val surface: Int, val text: Int, val sub: Int, val accent: Int, val chip: Int, val onAccent: Int)

    private fun look(): Look {
        val s = container.settings.current
        val sysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = when (s.themeMode) { ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; else -> sysDark }
        val c = IrisPalette.of(s.themePalette).colors(dark)
        val base = if (dark) c.surfaceElevated.toArgb() else android.graphics.Color.WHITE
        return Look(ColorUtils.setAlphaComponent(base, 0xF2), c.text.toArgb(), c.textSecondary.toArgb(), c.accent.toArgb(), c.surfaceVariant.toArgb(), c.onAccent.toArgb())
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val l = look()
        val size = dp(48f)
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_iris_mark)
            setColorFilter(android.graphics.Color.WHITE)
            val pad = dp(12f); setPadding(pad, pad, pad, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5660F0.toInt(), 0xFF18B4D2.toInt())).apply { shape = GradientDrawable.OVAL }
            elevation = dp(6f).toFloat()
            alpha = 0.92f
            contentDescription = "IRIDIS"
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = resources.displayMetrics.widthPixels - size - dp(8f); y = resources.displayMetrics.heightPixels / 3 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = bubbleLp.x; startY = bubbleLp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) + abs(dy) > dp(6f)) moved = true
                    if (moved) { bubbleLp.x = startX + dx.toInt(); bubbleLp.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, bubbleLp) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    else {
                        // 贴边
                        val w = resources.displayMetrics.widthPixels
                        bubbleLp.x = if (bubbleLp.x + size / 2 < w / 2) dp(8f) else w - size - dp(8f)
                        runCatching { wm.updateViewLayout(v, bubbleLp) }
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(v, bubbleLp)
        bubble = v
        l.hashCode()
    }

    // ---------------------------------------------------------------- panel

    private fun togglePanel() { if (panel == null) showPanel() else hidePanel() }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; input = null; output = null; chips.clear()
    }

    private fun showPanel() {
        val l = look()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14f); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(l.surface) }
            elevation = dp(10f).toFloat()
        }
        // 模式条
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in FloatingLogic.Mode.entries) {
            val chip = TextView(this).apply {
                text = modeLabel(m); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val ph = dp(12f); val pv = dp(6f); setPadding(ph, pv, ph, pv)
                setOnClickListener { selectMode(m) }
            }
            chips[m] = chip
            strip.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        val field = EditText(this).apply {
            setTextColor(l.text); setHintTextColor(l.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            hint = I18n.s(R.string.v30_fl_hint); maxLines = 4
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(l.chip) }
            val p = dp(12f); setPadding(p, p, p, p)
        }
        input = field
        root.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        val out = TextView(this).apply {
            setTextColor(l.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setLineSpacing(0f, 1.15f)
            movementMethod = ScrollingMovementMethod(); maxHeight = dp(240f); setTextIsSelectable(true)
        }
        output = out
        root.addView(out, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun action(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (primary) l.onAccent else l.text)
            val ph = dp(16f); val pv = dp(8f); setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(if (primary) l.accent else l.chip) }
            setOnClickListener { onClick() }
        }.also { actions.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) }) }
        action(I18n.s(R.string.v30_fl_paste), false) { pasteClipboard() }
        action(I18n.s(R.string.v30_fl_copy), false) { copyOut() }
        action(I18n.s(R.string.v30_fl_close), false) { hidePanel() }
        action(I18n.s(R.string.v30_fl_go), true) { run() }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        val w = minOf(dp(360f), resources.displayMetrics.widthPixels - dp(24f))
        val lp = WindowManager.LayoutParams(
            w, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (bubbleLp.y + dp(56f)).coerceAtMost(resources.displayMetrics.heightPixels - dp(420f)).coerceAtLeast(dp(40f))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, lp)
        panel = root
        selectMode(mode)
    }

    private fun selectMode(m: FloatingLogic.Mode) {
        mode = m
        val l = look()
        for ((k, v) in chips) {
            val sel = k == m
            v.setTextColor(if (sel) l.onAccent else l.text)
            v.background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(if (sel) l.accent else l.chip) }
        }
        input?.visibility = if (m == FloatingLogic.Mode.SCREEN) View.GONE else View.VISIBLE
        output?.text = when (m) {
            FloatingLogic.Mode.SCREEN -> if (IrisAccessibilityService.connected) I18n.s(R.string.v30_fl_screen_tip) else I18n.s(R.string.v30_fl_need_a11y)
            else -> ""
        }
    }

    private fun modeLabel(m: FloatingLogic.Mode) = when (m) {
        FloatingLogic.Mode.TRANSLATE -> I18n.s(R.string.v30_fl_translate)
        FloatingLogic.Mode.SCREEN -> I18n.s(R.string.v30_fl_screen)
        FloatingLogic.Mode.ASK -> I18n.s(R.string.v30_fl_ask)
    }

    private fun pasteClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (t.isNotBlank()) input?.setText(t)
    }

    private fun copyOut() {
        val t = output?.text?.toString().orEmpty()
        if (t.isBlank()) return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("IRIDIS", t))
    }

    private fun show(t: String) { output?.text = t }

    // ---------------------------------------------------------------- actions

    private fun run() {
        val text = input?.text?.toString()?.trim().orEmpty()
        input?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        work?.cancel()
        work = scope.launch {
            try {
                when (mode) {
                    FloatingLogic.Mode.TRANSLATE -> if (text.isNotEmpty()) translate(text)
                    FloatingLogic.Mode.ASK -> if (text.isNotEmpty()) ask(Prompts.get(PromptKey.FLOAT_ASK), text)
                    FloatingLogic.Mode.SCREEN -> readScreen()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "floating ${mode.name} failed", t)
                show(t.message ?: I18n.s(R.string.v30_fl_failed))
            }
        }
    }

    private suspend fun translate(text: String) {
        show("…")
        container.translator.translate(text).collect { show(it) }
    }

    private suspend fun ask(system: String, text: String) {
        val engine = container.engine
        if (engine.state.value !is EngineState.Ready) { show(I18n.s(R.string.v30_fl_no_model)); return }
        val sb = StringBuilder()
        show("…")
        engine.generate(
            listOf(PromptMessage(PromptMessage.SYSTEM, system), PromptMessage(PromptMessage.USER, text)),
            engine.samplingFor(disableThinking = true), Requester.CHAT, GenOwner("floating"),
        ).collect { ev ->
            when (ev) {
                is GenEvent.Piece -> { sb.append(ev.text); show(com.iris.localai.translate.TranslateOutput.clean(sb.toString())) }
                is GenEvent.Done -> if (ev.error != null && sb.isEmpty()) show(ev.error)
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        hidePanel()
    }

    /** 截屏前把自己的窗口藏起来。 */
    private suspend fun <T> hidden(block: suspend () -> T): T {
        val views = listOf(bubble, panel)
        views.forEach { it?.visibility = View.INVISIBLE }
        delay(160)
        return try { block() } finally { views.forEach { it?.visibility = View.VISIBLE } }
    }

    private suspend fun readScreen() {
        if (!IrisAccessibilityService.connected) { show(I18n.s(R.string.v30_fl_need_a11y)); return }
        show(I18n.s(R.string.v30_fl_reading))
        val obs = hidden { AccessibilityGuiDevice(this).observe() }
        var text = FloatingLogic.screenText(obs.tree)
        if (text.length < 40 && obs.screenshot != null) {
            text = GuiOcrProvider.get(this)?.recognize(obs.screenshot, obs.width, obs.height).orEmpty().joinToString("\n") { it.text }
        }
        if (text.isBlank()) { show(I18n.s(R.string.v30_fl_nothing)); return }
        if (container.engine.state.value !is EngineState.Ready) { show(text); return }
        ask(Prompts.get(PromptKey.FLOAT_SCREEN), text)
    }

    // ---------------------------------------------------------------- foreground

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, I18n.s(R.string.v30_floating), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(QuickNav.EXTRA, QuickNav.TOOLBOX).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_iris_mark)
            .setContentTitle(I18n.s(R.string.v30_floating))
            .setContentText(I18n.s(R.string.v30_fl_notif2))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, I18n.s(R.string.v30_fl_close), stop).build())
            .setOngoing(true)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        runCatching { startForeground(NOTIF_ID, n, type) }.onFailure { startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
    }

    companion object {
        private const val TAG = "IrisFloating"
        private const val CHANNEL = "floating"
        private const val NOTIF_ID = 3001
        private const val ACTION_STOP = "iris.floating.stop"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        fun permissionIntent(ctx: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun start(ctx: Context) { if (canDraw(ctx)) ctx.startForegroundService(Intent(ctx, FloatingService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatingService::class.java)) }
    }
}

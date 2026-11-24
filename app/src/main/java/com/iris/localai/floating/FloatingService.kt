package com.iris.localai.floating

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
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
    /** 小球被推出屏幕超过自身 1/4。 */
    fun pushedOut(x: Int, size: Int, screenW: Int): Boolean = x < -size / 4 || x > screenW - size + size / 4
    enum class Mode { TRANSLATE, SCREEN, SEARCH, ASK }

    /** Beta3 卡片（菜单 / 面板）贴在小球旁边、顶部和小球等高（小球在卡片右上或左上），夹在屏幕内（px）。
     *  贴底放不下时卡片往上收，调用方把小球也挪到返回的 y，保持等高。 */
    fun menuPos(ballX: Int, ballY: Int, ball: Int, menuW: Int, menuH: Int, screenW: Int, screenH: Int, gap: Int, left: Boolean): Pair<Int, Int> {
        val x = if (left) (ballX.coerceAtLeast(0) + ball + gap) else (ballX.coerceAtMost(screenW - ball) - gap - menuW)
        val y = ballY
        return x.coerceIn(gap, (screenW - menuW - gap).coerceAtLeast(gap)) to y.coerceIn(gap * 4, (screenH - menuH - gap * 4).coerceAtLeast(gap * 4))
    }

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

    /** Beta2：松手后贴向较近的一边。 */
    /** Beta3 菜单里的功能（顺序即菜单顺序）；搜索已并进问 AI。 */
    val MENU = listOf(Mode.TRANSLATE, Mode.SCREEN, Mode.ASK)

    fun dockLeft(x: Int, size: Int, screenW: Int): Boolean = x + size / 2 < screenW / 2

    /** Beta3 覆盖式屏幕翻译：无障碍树里带位置的文字块（有字就不再往子节点找），去掉无字母的。 */
    fun treeBlocks(root: UiNode?): List<OcrLine> {
        if (root == null) return emptyList()
        val out = ArrayList<OcrLine>()
        fun visit(n: UiNode) {
            if (!n.visible) return
            val t = n.text.trim()
            if (t.isNotEmpty() && !n.password && !n.editable && !n.bounds.isEmpty) { out += OcrLine(t, n.bounds); return }
            n.children.forEach(::visit)
        }
        visit(root)
        val seen = HashSet<String>()
        return out.filter { b -> b.text.any { it.isLetter() } && seen.add(b.text + b.bounds) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** 识字块和无障碍块取字多的那份（漫画走识字）。 */
    fun pickBlocks(tree: List<OcrLine>, ocr: List<OcrLine>): List<OcrLine> {
        val o = ocr.filter { b -> b.text.any { it.isLetter() } }
        return if (o.sumOf { it.text.length } > tree.sumOf { it.text.length } * 6 / 5 + 10) o else tree.ifEmpty { o }
    }

    /** 一块一行送去翻译：块内换行压成空格。 */
    fun joinBlocks(blocks: List<OcrLine>): String = blocks.joinToString("\n") { it.text.replace(Regex("\\s*\n\\s*"), " ") }

    /** 译文按行拆回各块；[done] 前最后一行可能还没写完，不用。r5：模型多插的空行不算一块。 */
    fun splitTranslated(out: String, n: Int, done: Boolean): List<String> {
        val raw = out.trim('\n').split('\n')
        val ready = (if (done) raw else raw.dropLast(1)).map { it.trim().replace(Regex("^\\d+[.、:：)]\\s*"), "") }.filter { it.isNotEmpty() }
        return ready.take(n)
    }

    /** r5：一批译完后行数对得上才用（多一行少一行都会让后面的块全错位）；对不上返回 null，改成逐块译。 */
    fun align(out: String, n: Int): List<String>? = splitTranslated(out, Int.MAX_VALUE, done = true).takeIf { it.size == n }

    /** r5：同样的字只译一次（重复的按钮、标签）。返回去重后的文本和每块对应的下标。 */
    fun dedupe(texts: List<String>): Pair<List<String>, IntArray> {
        val uniq = ArrayList<String>()
        val at = HashMap<String, Int>()
        val map = IntArray(texts.size) { i -> at.getOrPut(texts[i].trim()) { uniq += texts[i].trim(); uniq.size - 1 } }
        return uniq to map
    }

    /** r5：分批送翻译（小模型一次喂上百行容易错行、复读）：每批最多 [maxLines] 行、约 [maxChars] 字。 */
    fun chunks(texts: List<String>, maxLines: Int = 8, maxChars: Int = 480): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0; var chars = 0
        for (i in texts.indices) {
            val len = texts[i].length
            if (i > start && (i - start >= maxLines || chars + len > maxChars)) { out += start until i; start = i; chars = 0 }
            chars += len
        }
        if (start < texts.size) out += start until texts.size
        return out
    }

    /** 一块一行：块内换行压成空格。 */
    fun oneLine(t: String): String = t.replace(Regex("\\s*\n\\s*"), " ").trim()

    /**
     * r5：防重复触发——同一件事（同一模式 + 同一段文字）正在跑时再点不重开；跑完后 [windowMs] 内的重复点击也忽略。
     * 以前打开面板自动跑一次、用户再点「开始」又从头跑一次，看起来像「译了两遍」。
     */
    class RunGate(private val windowMs: Long = 1200) {
        private var key: String? = null
        private var busy = false
        private var endedAt = 0L
        @Synchronized fun tryStart(k: String, now: Long): Boolean {
            if (k == key && (busy || now - endedAt < windowMs)) return false
            key = k; busy = true
            return true
        }
        @Synchronized fun end(k: String, now: Long) { if (k == key) { busy = false; endedAt = now } }
        @Synchronized fun reset() { key = null; busy = false }
    }

    /** Beta3 屏幕翻译取哪份文字：截图识字明显更多（漫画、图片里的字）就用识字，否则用无障碍文字。 */
    fun pickScreenText(tree: String, ocr: String): String {
        val t = tree.trim(); val o = ocr.trim()
        return if (o.length > t.length * 6 / 5 + 10) o else t.ifEmpty { o }
    }

    /** 贴边位置：[peek] 为真时一半藏进屏幕边，只露半圆。 */
    fun edgeX(left: Boolean, size: Int, screenW: Int, margin: Int, peek: Boolean): Int =
        if (left) (if (peek) -size / 2 else margin) else (if (peek) screenW - size / 2 else screenW - size - margin)

    /** 小球中心是否落进底部关闭圈（半径 [r] 内）。 */
    fun inDismiss(cx: Float, cy: Float, tx: Float, ty: Float, r: Float): Boolean {
        val dx = cx - tx; val dy = cy - ty
        return dx * dx + dy * dy <= r * r
    }

    /** 闲置多久后贴边半隐（毫秒）。 */
    const val PEEK_AFTER_MS = 1600L
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
    private var menu: View? = null
    private var menuClosedAt = 0L
    private var menuLp: WindowManager.LayoutParams? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var input: EditText? = null
    private var output: TextView? = null
    private var mode = FloatingLogic.Mode.TRANSLATE
    private var work: Job? = null
    private val chips = HashMap<FloatingLogic.Mode, TextView>()
    private var dismissView: View? = null
    private var dismissBox: View? = null
    private var dockedLeft = false
    private var peekJob: Job? = null
    private var slide: ValueAnimator? = null

    private val container get() = (application as IrisApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForegroundCompat()
        // 3.0.0：「显示在其他应用上层」被收回后 sticky 重启时不再 addView 闪退
        if (!canDraw(this)) { stopSelf(); return }
        showBubble()
        if (bubble == null) { stopSelf(); return }
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !canDraw(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        peekJob?.cancel(); slide?.cancel()
        closeOverlay()
        listOf(panel, menu, bubble, dismissBox ?: dismissView).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
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
        val size = dp(container.settings.current.floatSize.coerceIn(36, 72).toFloat())
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_iris_mark)
            setColorFilter(android.graphics.Color.WHITE)
            val pad = size / 4; setPadding(pad, pad, pad, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5660F0.toInt(), 0xFF18B4D2.toInt())).apply { shape = GradientDrawable.OVAL }
            elevation = dp(6f).toFloat()
            alpha = 0f; scaleX = 0.4f; scaleY = 0.4f
            contentDescription = "IRIDIS"
        }
        val sw = resources.displayMetrics.widthPixels
        bubbleLp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = sw - size - dp(8f); y = resources.displayMetrics.heightPixels / 3 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    peekJob?.cancel(); slide?.cancel()
                    // 半隐状态下按住：先弹回完整小球
                    val w = resources.displayMetrics.widthPixels
                    if (bubbleLp.x < 0 || bubbleLp.x > w - size) {
                        bubbleLp.x = FloatingLogic.edgeX(dockedLeft, size, w, dp(8f), peek = false); runCatching { wm.updateViewLayout(v, bubbleLp) }
                    }
                    v.animate().alpha(0.95f).scaleX(0.9f).scaleY(0.9f).setDuration(120).start()
                    downX = e.rawX; downY = e.rawY; startX = bubbleLp.x; startY = bubbleLp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && abs(dx) + abs(dy) > dp(6f)) { moved = true; showDismiss() }
                    if (moved) {
                        bubbleLp.x = startX + dx.toInt(); bubbleLp.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, bubbleLp) }
                        follow(FloatingLogic.dockLeft(bubbleLp.x, size, resources.displayMetrics.widthPixels))
                        updateDismiss(bubbleLp.x + size / 2f, bubbleLp.y + size / 2f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.4f)).setDuration(260).start()
                    if (!moved) { if (e.actionMasked == MotionEvent.ACTION_UP) togglePanel() else schedulePeek() }
                    else {
                        val hit = dismissHit(bubbleLp.x + size / 2f, bubbleLp.y + size / 2f)
                        hideDismiss()
                        if (hit) { closeAll(); return@setOnTouchListener true }
                        val w = resources.displayMetrics.widthPixels
                        dockedLeft = FloatingLogic.dockLeft(bubbleLp.x, size, w)
                        // Beta2：手动半隐——把小球往屏幕外推出 1/4 以上再松手，立即贴边半隐
                        if (FloatingLogic.pushedOut(bubbleLp.x, size, w)) {
                            peekJob?.cancel()
                            slideTo(FloatingLogic.edgeX(dockedLeft, size, w, dp(8f), peek = true), 260L)
                            v.animate().alpha(0.6f).setDuration(260).start()
                        } else slideTo(FloatingLogic.edgeX(dockedLeft, size, w, dp(8f), peek = false), 320L) { schedulePeek() }
                    }
                    true
                }
                else -> false
            }
        }
        if (runCatching { wm.addView(v, bubbleLp) }.onFailure { Log.w(TAG, "bubble addView failed: ${it.message}") }.isFailure) return
        bubble = v
        dockedLeft = false
        v.animate().alpha(0.95f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(320).start()
        schedulePeek()
        // Beta3：设置里改小球大小，马上生效
        scope.launch {
            container.settings.settings.collect { st ->
                val px = dp(st.floatSize.coerceIn(36, 72).toFloat())
                val b = bubble ?: return@collect
                if (bubbleLp.width != px) {
                    bubbleLp.width = px; bubbleLp.height = px
                    val pad = px / 4; b.setPadding(pad, pad, pad, pad)
                    bubbleLp.x = FloatingLogic.edgeX(dockedLeft, px, resources.displayMetrics.widthPixels, dp(8f), peek = false)
                    runCatching { wm.updateViewLayout(b, bubbleLp) }
                    schedulePeek()
                }
            }
        }
    }

    /** 小球横向滑到 [targetX]（带减速曲线）。 */
    private fun slideTo(targetX: Int, ms: Long, done: () -> Unit = {}) {
        val v = bubble ?: return
        slide?.cancel()
        val from = bubbleLp.x
        slide = ValueAnimator.ofInt(from, targetX).apply {
            duration = ms; interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { bubbleLp.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(v, bubbleLp) }; follow(dockedLeft) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) done() }
            })
            start()
        }
    }

    /** 闲置 [FloatingLogic.PEEK_AFTER_MS] 后贴边半隐成半圆条；面板开着不隐。 */
    private fun schedulePeek() {
        peekJob?.cancel()
        peekJob = scope.launch {
            delay(FloatingLogic.PEEK_AFTER_MS)
            if (panel != null || menu != null || !container.settings.current.floatPeek) return@launch
            val size = bubbleLp.width
            slideTo(FloatingLogic.edgeX(dockedLeft, size, resources.displayMetrics.widthPixels, dp(8f), peek = true), 380L)
            bubble?.animate()?.alpha(0.6f)?.setDuration(380)?.start()
        }
    }

    // ---------------------------------------------------------------- 拖到底部关闭

    private fun showDismiss() {
        if (dismissView != null) return
        val d = dp(64f)
        // Beta2 修：窗口比圆大一圈，放大 / 位移时不被窗口裁成圆角方块
        val win = (d * 1.7f).toInt()
        val x = TextView(this).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC202024.toInt()); setStroke(dp(1.5f), 0x66FFFFFF) }
            alpha = 0f; scaleX = 0.5f; scaleY = 0.5f; translationY = dp(14f).toFloat()
        }
        val box = android.widget.FrameLayout(this).apply {
            clipChildren = false
            addView(x, android.widget.FrameLayout.LayoutParams(d, d, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            win, win, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(56f) - (win - d) / 2 }
        runCatching { wm.addView(box, lp) }.onSuccess {
            dismissView = x; dismissBox = box
            x.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(220).start()
        }
    }

    private fun dismissCenter(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return dm.widthPixels / 2f to (dm.heightPixels - dp(56f) - dp(32f)).toFloat()
    }

    private fun dismissHit(cx: Float, cy: Float): Boolean {
        if (dismissView == null) return false
        val (tx, ty) = dismissCenter()
        return FloatingLogic.inDismiss(cx, cy, tx, ty, dp(64f).toFloat())
    }

    private fun updateDismiss(cx: Float, cy: Float) {
        val x = dismissView ?: return
        val s = if (dismissHit(cx, cy)) 1.25f else 1f
        if (x.scaleX != s) x.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }

    private fun hideDismiss() {
        val x = dismissView ?: return
        dismissView = null
        val box = dismissBox; dismissBox = null
        x.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).translationY(dp(14f).toFloat()).setDuration(180)
            .withEndAction { runCatching { wm.removeView(box ?: x) } }.start()
    }

    /** 丢进关闭圈：小球缩小淡出，然后停服务。 */
    private fun closeAll() {
        hideMenu(); hidePanel()
        val v = bubble
        if (v == null) { stopSelf(); return }
        v.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).setDuration(200).withEndAction { stopSelf() }.start()
    }

    // ---------------------------------------------------------------- panel

    /** Beta3：卡片跟着小球走——顶部和小球等高，贴在小球朝屏幕中间的一侧。 */
    private fun follow(left: Boolean) {
        val dm = resources.displayMetrics
        val size = bubbleLp.width
        for ((view, lp) in listOf(menu to menuLp, panel to panelLp)) {
            if (view == null || lp == null) continue
            val h = view.height.takeIf { it > 0 } ?: lp.height.coerceAtLeast(0)
            val (x, y) = FloatingLogic.menuPos(bubbleLp.x, bubbleLp.y, size, lp.width, h, dm.widthPixels, dm.heightPixels, dp(10f), left)
            if (lp.x != x || lp.y != y) { lp.x = x; lp.y = y; runCatching { wm.updateViewLayout(view, lp) } }
            view.pivotX = if (left) 0f else lp.width.toFloat()
        }
    }

    /** 卡片被夹在屏幕内时把小球挪到卡片顶部，保持等高。 */
    private fun alignBall(y: Int) {
        val b = bubble ?: return
        if (bubbleLp.y != y) { bubbleLp.y = y; runCatching { wm.updateViewLayout(b, bubbleLp) } }
    }

    private fun togglePanel() {
        when {
            panel != null -> hidePanel()
            menu != null -> hideMenu()
            // 点小球时菜单外点击先把它收了：别马上又弹出来
            android.os.SystemClock.uptimeMillis() - menuClosedAt < 300 -> {}
            else -> showMenu()
        }
    }

    /** Beta3 毛玻璃底：半透明面 + 上亮下淡的发丝描边（悬浮窗拿不到跨窗口模糊，用透明度和描边做出玻璃感）。 */
    private fun glassBg(l: Look, radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(ColorUtils.setAlphaComponent(l.surface, 0xE6))
        setStroke(dp(0.8f).coerceAtLeast(1), ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, if (isDark()) 0x26 else 0x99))
    }

    private fun isDark(): Boolean = ColorUtils.calculateLuminance(look().surface) < 0.5

    /** Beta3（按宣传片）：点小球 → 小球旁边弹出一列菜单，点哪项再开对应面板。 */
    private fun showMenu() {
        val l = look()
        peekJob?.cancel()
        val on = container.settings.current.floatItems
        val items = listOf(
            Triple<Int, String, () -> Unit>(R.drawable.w31_ic_translate, I18n.s(R.string.v31_fl_tr_screen), { if (IrisAccessibilityService.connected) { hideMenu(); overlayTranslate() } else openMode(FloatingLogic.Mode.TRANSLATE) }),
            Triple<Int, String, () -> Unit>(R.drawable.w31_ic_screen, I18n.s(R.string.v30_fl_screen), { openMode(FloatingLogic.Mode.SCREEN) }),
            // Beta3：搜索并进问 AI（面板里有「搜索」按钮）
            Triple<Int, String, () -> Unit>(R.drawable.w31_ic_spark, I18n.s(R.string.v30_fl_ask), { openMode(FloatingLogic.Mode.ASK) }),
        ).filterIndexed { i, _ -> FloatingLogic.MENU[i].name in on } + listOf(
            Triple<Int, String, () -> Unit>(R.drawable.w31_ic_open, I18n.s(R.string.v31_fl_app), { hideMenu(); runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }),
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(6f); setPadding(p, p, p, p)
            background = glassBg(l, 22f)
            elevation = dp(8f).toFloat()
        }
        val rowH = dp(48f)
        for ((icon, label, act) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val ph = dp(14f); setPadding(ph, 0, dp(22f), 0)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(l.accent, 0x33)), null,
                    GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(android.graphics.Color.WHITE) },
                )
                setOnClickListener { act() }
            }
            row.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(l.accent) }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
            row.addView(TextView(this).apply {
                text = label; setTextColor(l.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                paint.isFakeBoldText = false; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14f) })
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
        }
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val mw = root.measuredWidth.coerceAtLeast(dp(168f)); val mh = root.measuredHeight
        val dm = resources.displayMetrics
        val size = bubbleLp.width
        val (x, y) = FloatingLogic.menuPos(bubbleLp.x, bubbleLp.y, size, mw, mh, dm.widthPixels, dm.heightPixels, dp(10f), dockedLeft)
        val lp = WindowManager.LayoutParams(
            mw, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        alignBall(y)
        menuLp = lp
        // 点菜单外面收起
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false }
        // 从小球那一侧展开：缩放 + 淡入，行依次出现
        root.pivotX = if (dockedLeft) 0f else mw.toFloat(); root.pivotY = 0f
        root.alpha = 0f; root.scaleX = 0.7f; root.scaleY = 0.7f
        for (i in 0 until root.childCount) root.getChildAt(i).apply { alpha = 0f; translationX = (if (dockedLeft) -1 else 1) * dp(10f).toFloat() }
        runCatching { wm.addView(root, lp) }.onSuccess {
            menu = root
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.2f)).setDuration(260).start()
            for (i in 0 until root.childCount) root.getChildAt(i).animate().alpha(1f).translationX(0f).setStartDelay(60L + i * 35L).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun hideMenu() {
        val m = menu ?: return
        menu = null; menuLp = null
        menuClosedAt = android.os.SystemClock.uptimeMillis()
        m.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(140).withEndAction { runCatching { wm.removeView(m) } }.start()
        if (panel == null) schedulePeek()
    }

    private fun openMode(m: FloatingLogic.Mode) {
        mode = m
        hideMenu()
        showPanel()
    }

    private fun hidePanel() {
        val p = panel ?: return
        panel = null; panelLp = null; input = null; output = null; chips.clear()
        p.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setInterpolator(DecelerateInterpolator()).setDuration(160)
            .withEndAction { runCatching { wm.removeView(p) } }.start()
        schedulePeek()
    }

    private fun showPanel() {
        val l = look()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14f); setPadding(pad, pad, pad, pad)
            background = glassBg(l, 24f)
            elevation = dp(10f).toFloat()
        }
        // Beta3：没有模式条，顶部只写当前功能名（从菜单里选的）
        root.addView(TextView(this).apply {
            text = modeLabel(mode); setTextColor(l.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val p = dp(4f); setPadding(p, 0, p, 0)
        })
        val field = EditText(this).apply {
            setTextColor(l.text); setHintTextColor(l.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            hint = I18n.s(R.string.v30_fl_hint); maxLines = 4
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            setOnEditorActionListener { _, a, _ -> if (a == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { run(); true } else false }
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
        if (mode == FloatingLogic.Mode.ASK) action(I18n.s(R.string.v30_fl_search), false) {
            val q = input?.text?.toString()?.trim().orEmpty(); if (q.isNotEmpty()) webSearch(q)
        }
        action(I18n.s(R.string.v30_fl_copy), false) { copyOut() }
        action(I18n.s(R.string.v30_fl_go), true) { run() }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        val dm = resources.displayMetrics
        val w = minOf(dp(360f), dm.widthPixels - bubbleLp.width - dp(30f))
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val (px, py) = FloatingLogic.menuPos(bubbleLp.x, bubbleLp.y, bubbleLp.width, w, root.measuredHeight.coerceAtLeast(dp(220f)), dm.widthPixels, dm.heightPixels, dp(10f), dockedLeft)
        val lp = WindowManager.LayoutParams(
            w, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px; y = py
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // Beta2：展开动画——从小球所在一侧弹出（缩放 + 淡入 + 轻微下落）
        peekJob?.cancel()
        root.alpha = 0f; root.scaleX = 0.6f; root.scaleY = 0.6f; root.translationY = 0f
        root.pivotX = if (dockedLeft) 0f else w.toFloat(); root.pivotY = 0f
        if (!canDraw(this) || runCatching { wm.addView(root, lp) }.onFailure { Log.w(TAG, "panel addView failed: ${it.message}") }.isFailure) { stopSelf(); return }
        panel = root; panelLp = lp
        alignBall(py)
        selectMode(mode)
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setInterpolator(OvershootInterpolator(1.1f)).setDuration(300).start()
        // Beta3 少一步：屏幕翻译 / 识屏一打开就跑；搜索 / 问 AI 先填剪贴板，键盘回车就发
        when (mode) {
            FloatingLogic.Mode.TRANSLATE, FloatingLogic.Mode.SCREEN -> root.post { run() }
            else -> {
                input?.let { f -> f.requestFocus(); f.setSelection(f.text.length); f.postDelayed({ getSystemService(InputMethodManager::class.java)?.showSoftInput(f, 0) }, 200) }
                // Android 10+ 只有拿到焦点的窗口才能读剪贴板：等面板窗口获得焦点再读
                pasteWhenFocused(root)
            }
        }
    }

    private fun selectMode(m: FloatingLogic.Mode) {
        mode = m
        val l = look()
        for ((k, v) in chips) {
            val sel = k == m
            v.setTextColor(if (sel) l.onAccent else l.text)
            v.background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(if (sel) l.accent else l.chip) }
        }
        input?.visibility = if (m == FloatingLogic.Mode.SCREEN || m == FloatingLogic.Mode.TRANSLATE) View.GONE else View.VISIBLE
        output?.text = when (m) {
            FloatingLogic.Mode.SCREEN, FloatingLogic.Mode.TRANSLATE -> if (IrisAccessibilityService.connected) I18n.s(R.string.v30_fl_screen_tip) else I18n.s(R.string.v30_fl_need_a11y)
            else -> ""
        }
    }

    private fun modeLabel(m: FloatingLogic.Mode) = when (m) {
        FloatingLogic.Mode.TRANSLATE -> I18n.s(R.string.v31_fl_tr_screen)
        FloatingLogic.Mode.SCREEN -> I18n.s(R.string.v30_fl_screen)
        FloatingLogic.Mode.SEARCH -> I18n.s(R.string.v30_fl_search)
        FloatingLogic.Mode.ASK -> I18n.s(R.string.v30_fl_ask)
    }

    private fun pasteWhenFocused(root: View) {
        if (root.hasWindowFocus()) { pasteClipboard(); return }
        root.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                root.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                if (panel === root && input?.text.isNullOrEmpty()) { pasteClipboard(); input?.let { it.setSelection(it.text.length) } }
            }
        })
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

    private val runGate = FloatingLogic.RunGate()

    private fun run() {
        val text = input?.text?.toString()?.trim().orEmpty()
        input?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        // r5：同一件事正在跑 / 刚跑完就不重开（翻译、识屏读的是当前屏幕，键只看模式）。
        val key = mode.name + "|" + (if (mode == FloatingLogic.Mode.TRANSLATE || mode == FloatingLogic.Mode.SCREEN) "" else text)
        if (!runGate.tryStart(key, android.os.SystemClock.elapsedRealtime())) return
        work?.cancel()
        work = scope.launch {
            try {
                when (mode) {
                    FloatingLogic.Mode.TRANSLATE -> translateScreen()
                    FloatingLogic.Mode.ASK -> if (text.isNotEmpty()) ask(Prompts.get(PromptKey.FLOAT_ASK), text)
                    FloatingLogic.Mode.SEARCH -> if (text.isNotEmpty()) webSearch(text)
                    FloatingLogic.Mode.SCREEN -> readScreen()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "floating ${mode.name} failed", t)
                show(t.message ?: I18n.s(R.string.v30_fl_failed))
            } finally {
                runGate.end(key, android.os.SystemClock.elapsedRealtime())
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

    /** 交给系统默认的搜索（浏览器 / 搜索 App 自己选），没有就开网页搜索。 */
    private fun webSearch(q: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(i) }.isFailure) openUrl("https://www.google.com/search?q=" + Uri.encode(q)) else hidePanel()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        hidePanel()
    }

    /** 截屏前把自己的窗口藏起来。 */
    private suspend fun <T> hidden(block: suspend () -> T): T {
        val views = listOf(bubble, panel, menu)
        views.forEach { it?.visibility = View.INVISIBLE }
        delay(160)
        return try { block() } finally { views.forEach { it?.visibility = View.VISIBLE } }
    }

    /** Beta3 屏幕翻译：先读无障碍文字，截图识字（漫画气泡这类图里的字）更多就用识字结果，再翻译。 */
    private suspend fun screenText(): String? {
        if (!IrisAccessibilityService.connected) { show(I18n.s(R.string.v30_fl_need_a11y)); return null }
        show(I18n.s(R.string.v30_fl_reading))
        val obs = hidden { AccessibilityGuiDevice(this).observe() }
        val tree = FloatingLogic.screenText(obs.tree)
        val ocr = obs.screenshot?.let { shot -> runCatching { GuiOcrProvider.get(this)?.recognize(shot, obs.width, obs.height).orEmpty().joinToString("\n") { it.text } }.getOrNull() }.orEmpty()
        val text = FloatingLogic.pickScreenText(tree, ocr)
        if (text.isBlank()) { show(I18n.s(R.string.v30_fl_nothing)); return null }
        return text
    }

    private suspend fun translateScreen() {
        val text = screenText() ?: return
        translate(text)
    }

    // ---------------------------------------------------------------- 覆盖式屏幕翻译（Beta3 r3）

    private var overlay: android.widget.FrameLayout? = null
    private var overlayBar: View? = null
    private var overlayJob: kotlinx.coroutines.Job? = null
    private var overlayText = ""

    /** r5：译过的块（目标语言|原文 → 译文），同一屏再点一次不用重译；最多留 400 条。 */
    private val overlayCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 400
    }
    private fun cacheOverlay(k: String, v: String) { overlayCache[k] = v }

    /** 截屏取字，译文直接盖在原文位置；底部一条小栏：原文/译文切换、复制、关闭。 */
    private fun overlayTranslate() {
        // r5：还在译时再点不重开（以前会取消后从头再译一遍）。
        if (overlayJob?.isActive == true) return
        closeOverlay()
        overlayJob = scope.launch {
            try {
                val obs = hidden { AccessibilityGuiDevice(this@FloatingService).observe() }
                val tree = FloatingLogic.treeBlocks(obs.tree)
                val ocr = obs.screenshot?.let { shot -> runCatching { GuiOcrProvider.get(this@FloatingService)?.recognize(shot, obs.width, obs.height).orEmpty() }.getOrNull() }.orEmpty()
                val blocks = FloatingLogic.pickBlocks(tree, ocr).take(120)
                showOverlayBar(I18n.s(R.string.v31_fl_ov_working))
                if (blocks.isEmpty()) { showOverlayBar(I18n.s(R.string.v30_fl_nothing)); return@launch }
                val screen = wm.currentWindowMetrics.bounds
                val sx = if (obs.width > 0) screen.width().toFloat() / obs.width else 1f
                val sy = if (obs.height > 0) screen.height().toFloat() / obs.height else 1f
                val views = buildOverlay(blocks, sx, sy)
                // r5：去重 → 先用缓存 → 其余分批译（每批行数对不上就逐块译），避免错行与「同一段译好几遍」。
                val (uniq, map) = FloatingLogic.dedupe(blocks.map { FloatingLogic.oneLine(it.text) })
                val out = arrayOfNulls<String>(uniq.size)
                val target = container.settings.current.translateTarget
                fun paint() = map.forEachIndexed { bi, ui -> out[ui]?.takeIf { it.isNotBlank() }?.let { t -> views[bi].apply { text = t; visibility = View.VISIBLE } } }
                val todo = uniq.indices.filter { i -> overlayCache["$target|${uniq[i]}"]?.also { out[i] = it } == null }
                paint()
                for (r in FloatingLogic.chunks(todo.map { uniq[it] })) {
                    val idx = todo.subList(r.first, r.last + 1)
                    val src = idx.map { uniq[it] }
                    var last = ""
                    container.translator.translate(src.joinToString("\n")).collect { o ->
                        last = o
                        FloatingLogic.splitTranslated(o, src.size, done = false).forEachIndexed { k, t -> out[idx[k]] = t }
                        paint()
                    }
                    val aligned = FloatingLogic.align(last, src.size)
                    if (aligned != null) aligned.forEachIndexed { k, t -> out[idx[k]] = t }
                    else for ((k, one) in src.withIndex()) {
                        var t = ""
                        container.translator.translate(one).collect { t = it }
                        out[idx[k]] = FloatingLogic.oneLine(t)
                        paint()
                    }
                    idx.forEach { i -> out[i]?.takeIf { it.isNotBlank() }?.let { cacheOverlay("$target|${uniq[i]}", it) } }
                    paint()
                }
                overlayText = blocks.indices.joinToString("\n") { out[map[it]].orEmpty() }
                showOverlayBar(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "overlay translate failed", t)
                showOverlayBar(t.message ?: I18n.s(R.string.v30_fl_failed))
            }
        }
    }

    private fun buildOverlay(blocks: List<OcrLine>, sx: Float, sy: Float): List<android.widget.TextView> {
        val root = android.widget.FrameLayout(this)
        val views = blocks.map { b ->
            val w = (b.bounds.width * sx).toInt().coerceAtLeast(dp(24f)); val h = (b.bounds.height * sy).toInt().coerceAtLeast(dp(12f))
            android.widget.TextView(this).apply {
                visibility = View.INVISIBLE
                setTextColor(0xFF15171C.toInt())
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF5FFFFFF.toInt()); cornerRadius = dp(3f).toFloat() }
                setPadding(dp(2f), 0, dp(2f), 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setAutoSizeTextTypeUniformWithConfiguration(6, maxOf(7, (h / resources.displayMetrics.scaledDensity * 0.8f).toInt().coerceAtMost(22)), 1, TypedValue.COMPLEX_UNIT_SP)
                root.addView(this, android.widget.FrameLayout.LayoutParams(w, h).apply { leftMargin = (b.bounds.left * sx).toInt(); topMargin = (b.bounds.top * sy).toInt() })
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(root, lp) }
        overlay = root
        // 控制栏放在覆盖层之上
        overlayBar?.let { runCatching { wm.removeView(it); wm.addView(it, it.layoutParams) } }
        return views
    }

    /** [status] 非空时栏里显示状态文字，空时显示按钮。 */
    private fun showOverlayBar(status: String?) {
        overlayBar?.let { runCatching { wm.removeView(it) } }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(6f), dp(6f), dp(6f))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEE0E0F13.toInt()); cornerRadius = dp(22f).toFloat() }
        }
        fun btn(label: String, onClick: (android.widget.TextView) -> Unit) = android.widget.TextView(this).apply {
            text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f)); setOnClickListener { onClick(this) }
            bar.addView(this)
        }
        if (status != null) {
            android.widget.TextView(this).apply { text = status; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; setPadding(0, dp(8f), dp(8f), dp(8f)) }.also(bar::addView)
            btn(I18n.s(R.string.v31_fl_ov_close)) { closeOverlay() }
        } else {
            btn(I18n.s(R.string.v31_fl_ov_orig)) { v ->
                val o = overlay ?: return@btn
                val showing = o.visibility == View.VISIBLE
                o.visibility = if (showing) View.INVISIBLE else View.VISIBLE
                v.text = I18n.s(if (showing) R.string.v31_fl_ov_tr else R.string.v31_fl_ov_orig)
            }
            btn(I18n.s(R.string.v31_fl_ov_copy)) { v ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("IRIDIS", overlayText))
                v.text = I18n.s(R.string.v31_fl_ov_copied)
            }
            btn(I18n.s(R.string.v31_fl_ov_close)) { closeOverlay() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; y = dp(48f) }
        runCatching { wm.addView(bar, lp) }
        overlayBar = bar
    }

    private fun closeOverlay() {
        overlayJob?.cancel(); overlayJob = null
        listOf(overlay, overlayBar).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        overlay = null; overlayBar = null; overlayText = ""
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

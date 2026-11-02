/*
 * 2.3.0: parts of this file (element compaction / unlabeled-controls check, accessibility scroll)
 * are adapted from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * Apache License 2.0. Modified for IRIDIS.
 */
package com.iris.localai.agent.gui.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.iris.localai.agent.gui.CoordMap
import com.iris.localai.agent.gui.ExecResult
import com.iris.localai.agent.gui.GuiAction
import com.iris.localai.agent.gui.GuiDevice
import com.iris.localai.agent.gui.GuiObservation
import com.iris.localai.agent.gui.PxRect
import com.iris.localai.agent.gui.ScreenFingerprint
import com.iris.localai.agent.gui.ScreenState
import com.iris.localai.agent.gui.StableWait
import com.iris.localai.agent.gui.TypeCheck
import com.iris.localai.agent.gui.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 2.2 GUI 智能体 形态 A: the accessibility service the user enables in 系统设置 › 无障碍.
 * It does nothing on its own: [AccessibilityGuiDevice] drives it only while a task runs.
 */
class IrisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        GuiOverlay.hide()
        A11yKeepAlive.onDisconnected(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        A11yKeepAlive.onDisconnected(applicationContext)
        super.onDestroy()
    }

    companion object {
        const val TAG = "IrisGui"

        @Volatile var instance: IrisAccessibilityService? = null
            private set

        val connected: Boolean get() = instance != null

        /** Whether the user switched the service on in system settings (it may still be connecting). */
        fun enabledInSettings(context: Context): Boolean {
            val me = ComponentName(context, IrisAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** 2.1.9: whether IRIDIS may switch the service back on itself (granted once over adb / Shizuku). */
        fun canSelfEnable(context: Context): Boolean =
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /**
         * 2.1.9: HyperOS turns the service off when 清后台 kills IRIDIS. With WRITE_SECURE_SETTINGS the
         * app puts it back in the enabled list (app start / before a task). True = it asked the system.
         */
        fun selfEnable(context: Context): Boolean {
            if (!canSelfEnable(context) || enabledInSettings(context)) return false
            return runCatching {
                val r = context.contentResolver
                val me = ComponentName(context, IrisAccessibilityService::class.java).flattenToString()
                val list = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11yList.add(list, me))
                Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "re-enabled after it was switched off")
                true
            }.onFailure { Log.w(TAG, "self-enable failed: ${it.message}") }.getOrDefault(false)
        }

        /** Opens 系统设置 › 无障碍 (the user switches it on there; apps cannot). */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        /** 应用信息 page: on Android 13+ a sideloaded app needs ⋮ › 允许受限制的设置 there first. */
        fun openAppInfo(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** 2.1.9: the `:`-separated enabled-services list with [me] added once (unit-tested). */
object A11yList {
    fun remove(list: String, me: String): String =
        list.split(':').map { it.trim() }.filter { it.isNotEmpty() && !it.equals(me, ignoreCase = true) }.joinToString(":")

    fun add(list: String, me: String): String {
        val items = list.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        return if (items.any { it.equals(me, ignoreCase = true) }) items.joinToString(":") else (items + me).joinToString(":")
    }
}

/** Reads the screen and acts on it through [IrisAccessibilityService] (default display only). */
class AccessibilityGuiDevice(
    private val context: Context,
    private val shotDir: File = File(context.cacheDir, "gui"),
    /** Longest side of the saved screenshot (the engines downscale again for their encoder). */
    private val maxSide: Int = 1280,
) : GuiDevice {
    private var shotNo = 0

    private fun svc(): IrisAccessibilityService =
        IrisAccessibilityService.instance ?: throw IllegalStateException("无障碍服务未开启：系统设置 › 无障碍 › IRIDIS GUI 智能体")

    override suspend fun observe(): GuiObservation {
        val s = svc()
        val (w, h) = screenSize(s)
        val shot = runCatching { screenshot(s) }.onFailure { Log.w(IrisAccessibilityService.TAG, "screenshot failed: ${it.message}") }.getOrNull()
        val root = withContext(Dispatchers.Default) { s.rootInActiveWindow?.let { NodeCopy.copy(it) } }
        return GuiObservation(shot, w, h, root, root?.pkg?.takeIf { it.isNotEmpty() })
    }

    override suspend fun execute(action: GuiAction, obs: GuiObservation): ExecResult {
        val s = svc()
        val w = obs.width; val h = obs.height
        fun px(p: com.iris.localai.agent.gui.NPoint) = CoordMap.toPixels(p, w, h)
        return when (action) {
            is GuiAction.Tap -> px(action.at).let { (x, y) -> gesture(s, stroke(x, y, x, y, 0, 60)) }
            is GuiAction.LongPress -> px(action.at).let { (x, y) -> gesture(s, stroke(x, y, x, y, 0, action.durationMs.coerceIn(100, 5000))) }
            is GuiAction.Scroll -> scroll(s, action, w, h)
            is GuiAction.DoubleTap -> px(action.at).let { (x, y) -> gesture(s, stroke(x, y, x, y, 0, 50), stroke(x, y, x, y, 160, 50)) }
            is GuiAction.Swipe -> {
                val (x1, y1) = px(action.from); val (x2, y2) = px(action.to)
                gesture(s, stroke(x1, y1, x2, y2, 0, action.durationMs.coerceIn(100, 3000)))
            }
            is GuiAction.Type -> typeText(s, action.text)
            GuiAction.Back -> global(s, AccessibilityService.GLOBAL_ACTION_BACK, "返回")
            GuiAction.Home -> global(s, AccessibilityService.GLOBAL_ACTION_HOME, "桌面")
            GuiAction.Enter -> {
                val f = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return ExecResult(false, "没有聚焦的输入框")
                ExecResult(f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id), "")
            }
            is GuiAction.Launch -> launch(action.app)
            is GuiAction.Wait -> { delay(action.ms); ExecResult(true) }
            is GuiAction.Finish, is GuiAction.TakeOver, is GuiAction.Ask -> ExecResult(true)
        }
    }

    /** 2.3.0: node tree only (no screenshot) for scroll-until-found. */
    override suspend fun tree(): UiNode? {
        val s = IrisAccessibilityService.instance ?: return null
        return withContext(Dispatchers.Default) { runCatching { s.rootInActiveWindow?.let { NodeCopy.copy(it) } }.getOrNull() }
    }

    /**
     * 2.3.0 (after Mika's AndroidScreen.scroll): the list's own accessibility scroll first (exact,
     * no fling), the swipe gesture when no scrollable node is found or it refuses.
     */
    private suspend fun scroll(s: IrisAccessibilityService, a: GuiAction.Scroll, w: Int, h: Int): ExecResult {
        val root = s.rootInActiveWindow
        val target = root?.let { r ->
            val at = a.at?.let { CoordMap.toPixels(it, w, h) }
            val all = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || all.size > 40) return
                if (n.isScrollable && n.isVisibleToUser) all += n to Rect().also { n.getBoundsInScreen(it) }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(r, 0)
            if (at != null) all.filter { it.second.contains(at.first, at.second) }.minByOrNull { it.second.width().toLong() * it.second.height() }?.first
            else all.maxByOrNull { it.second.width().toLong() * it.second.height() }?.first
        }
        if (target != null) {
            val wanted = when (a.dir) {
                com.iris.localai.agent.gui.SwipeDir.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                com.iris.localai.agent.gui.SwipeDir.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                com.iris.localai.agent.gui.SwipeDir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                com.iris.localai.agent.gui.SwipeDir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val ids = target.actionList.map { it.id }
            val id = when {
                wanted.id in ids -> wanted.id
                a.dir == com.iris.localai.agent.gui.SwipeDir.DOWN || a.dir == com.iris.localai.agent.gui.SwipeDir.RIGHT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD.takeIf { it in ids }
                else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD.takeIf { it in ids }
            }
            if (id != null && target.performAction(id)) return ExecResult(true)
        }
        val sw = CoordMap.scrollSwipe(a.dir, a.at)
        val (x1, y1) = CoordMap.toPixels(sw.from, w, h); val (x2, y2) = CoordMap.toPixels(sw.to, w, h)
        return gesture(s, stroke(x1, y1, x2, y2, 0, sw.durationMs))
    }

    /** 2.2.9: tree fingerprint of the active window (no screenshot: those are rate-limited by the system). */
    override suspend fun snapshot(): ScreenState? {
        val s = IrisAccessibilityService.instance ?: return null
        return withContext(Dispatchers.Default) { runCatching { s.rootInActiveWindow?.let { ScreenFingerprint.of(NodeCopy.copy(it)) } }.getOrNull() }
    }

    /** 2.2.9: poll every 150 ms until two samples match (min 250 ms, max 2.5 s). */
    override suspend fun waitStable(fallbackMs: Long): ScreenState? = StableWait.await({ snapshot() })

    private fun screenSize(s: IrisAccessibilityService): Pair<Int, Int> {
        val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** API 30+ screenshot: no MediaProjection prompt. Hides the 停止 bar for the shot; null on secure windows. */
    private suspend fun screenshot(s: IrisAccessibilityService): String? {
        GuiOverlay.setHiddenForCapture(true)
        try {
            delay(40)
            var bmp: Bitmap? = null
            for (attempt in 0..1) {
                bmp = capture(s)
                if (bmp != null) break
                delay(400) // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT: the system rate-limits screenshots
            }
            val b = bmp ?: return null
            return withContext(Dispatchers.IO) {
                val scaled = scale(b)
                shotDir.mkdirs()
                shotDir.listFiles()?.filter { it.name.startsWith("shot-") }?.sortedBy { it.lastModified() }?.dropLast(2)?.forEach { it.delete() }
                val f = File(shotDir, "shot-${System.currentTimeMillis()}-${shotNo++}.jpg")
                f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (scaled !== b) scaled.recycle()
                b.recycle()
                f.absolutePath
            }
        } finally {
            GuiOverlay.setHiddenForCapture(false)
        }
    }

    private suspend fun capture(s: IrisAccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                result.hardwareBuffer.close()
                if (cont.isActive) cont.resume(soft)
            }
            override fun onFailure(errorCode: Int) {
                Log.w(IrisAccessibilityService.TAG, "takeScreenshot error $errorCode")
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    private fun scale(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxSide) return b
        val r = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
    }

    private fun stroke(x1: Int, y1: Int, x2: Int, y2: Int, start: Long, dur: Long): GestureDescription.StrokeDescription {
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return GestureDescription.StrokeDescription(p, start, dur.coerceAtLeast(1))
    }

    private suspend fun gesture(s: IrisAccessibilityService, vararg strokes: GestureDescription.StrokeDescription): ExecResult =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
            val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(ExecResult(true)) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(ExecResult(false, "手势被系统取消")) }
            }, null)
            if (!sent && cont.isActive) cont.resume(ExecResult(false, "手势未能发送"))
        }

    private fun global(s: IrisAccessibilityService, action: Int, what: String) =
        if (s.performGlobalAction(action)) ExecResult(true) else ExecResult(false, "$what 失败")

    /**
     * Replaces the focused field's text: ACTION_SET_TEXT (any keyboard, no IME switch); when the field
     * refuses it or does not take the text, select all + paste from the clipboard. CJK and long text ok.
     */
    private suspend fun typeText(s: IrisAccessibilityService, text: String): ExecResult {
        val root = s.rootInActiveWindow ?: return ExecResult(false, "读不到当前窗口")
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditable(root) ?: return ExecResult(false, "没有聚焦的输入框，先点击输入框")
        if (field.isPassword) return ExecResult(false, "密码框由你自己输入")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            delay(80)
            field.refresh()
            val now = field.text?.toString()
            if (now == null || TypeCheck.took(now, text)) return ExecResult(true)
        }
        return paste(s, field, text)
    }

    /** The focused editable node, else the only editable one on screen. */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || all.size > 20) return
            if (n.isEditable) all += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return all.firstOrNull { it.isFocused } ?: all.singleOrNull()
    }

    private suspend fun paste(s: IrisAccessibilityService, field: AccessibilityNodeInfo, text: String): ExecResult {
        val cm = s.getSystemService(android.content.ClipboardManager::class.java) ?: return ExecResult(false, "输入框不接受文本")
        val old = runCatching { cm.primaryClip }.getOrNull()
        return try {
            withContext(Dispatchers.Main) { cm.setPrimaryClip(android.content.ClipData.newPlainText("iridis", text)) }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val len = field.text?.length ?: 0
            if (len > 0) field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            })
            val ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(150)
            if (ok) ExecResult(true, "已用粘贴输入") else ExecResult(false, "输入框不接受文本（设置文本和粘贴都失败）")
        } finally {
            if (old != null) runCatching { withContext(Dispatchers.Main) { cm.setPrimaryClip(old) } }
        }
    }

    /** By app name (or package), like the `phone` tool's open_app. */
    private fun launch(app: String): ExecResult {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
        // 2.1.9: PackageManager launch by 中文名 / English / package (fuzzy), never by hunting for the icon.
        val hit = com.iris.localai.agent.AppMatcher.match(app, apps) ?: return ExecResult(false, com.iris.localai.agent.AppMatcher.notFound(app, apps))
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return ExecResult(false, "无法打开 ${hit.first}")
        return runCatching {
            (IrisAccessibilityService.instance ?: context).startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ExecResult(true, hit.first)
        }.getOrElse { ExecResult(false, "打开 ${hit.first} 失败：${it.message}") }
    }
}

/** AccessibilityNodeInfo → [UiNode], bounded in depth and count so a huge list cannot stall a step. */
internal object NodeCopy {
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 1500

    fun copy(root: AccessibilityNodeInfo): UiNode {
        var count = 0
        val r = Rect()
        fun go(n: AccessibilityNodeInfo, depth: Int): UiNode {
            count++
            n.getBoundsInScreen(r)
            val kids = if (depth >= MAX_DEPTH) emptyList() else buildList {
                for (i in 0 until n.childCount) {
                    if (count >= MAX_NODES) break
                    val c = n.getChild(i) ?: continue
                    add(go(c, depth + 1))
                }
            }
            n.getBoundsInScreen(r)
            return UiNode(
                cls = n.className?.toString().orEmpty(),
                text = n.text?.toString().orEmpty(),
                desc = n.contentDescription?.toString().orEmpty(),
                viewId = n.viewIdResourceName.orEmpty(),
                bounds = PxRect(r.left, r.top, r.right, r.bottom),
                clickable = n.isClickable,
                longClickable = n.isLongClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                checkable = n.isCheckable,
                checked = n.isChecked,
                focused = n.isFocused,
                password = n.isPassword,
                enabled = n.isEnabled,
                visible = n.isVisibleToUser,
                pkg = n.packageName?.toString().orEmpty(),
                hint = n.hintText?.toString().orEmpty(),
                selected = n.isSelected,
                children = kids,
            )
        }
        return go(root, 0)
    }
}

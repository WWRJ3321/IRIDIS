package com.iris.localai.service

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.imagegen.PreviewFrame
import com.iris.localai.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 正在跑的那一张生图（主进程）。生图页的 ViewModel 写，[ImageGenJobService] 与重新打开的生图页读：
 * 离开页面、切到后台都不打断；回到生图页时接上进度、预览和结果。
 */
object ImageGenMonitor {
    data class Job(
        val model: String,
        val step: Int = 0,
        val total: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        val finished: Boolean = false,
        val stopped: Boolean = false,
        /** 成功时的图片路径。 */
        val result: String? = null,
        val error: String? = null,
        /** 失败是内存预检（页面给「仍然生成」）。 */
        val lowMem: Boolean = false,
        val seed: Long? = null,
        val usedPrompt: String? = null,
        val preview: PreviewFrame? = null,
        /** 每次 begin 加一，页面据此分辨是不是自己发起的那张。 */
        val serial: Long = 0,
    )

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job.asStateFlow()
    @Volatile private var stopHandler: (() -> Unit)? = null
    @Volatile private var serial = 0L

    /** 生图页可见（完成时在页面上就不再弹通知）。 */
    @Volatile var pageVisible = false

    /** 通知点开 → 打开生图页（AppNavigation 收集后 [consumeOpen]）。 */
    private val _open = MutableStateFlow(false)
    val openRequests: StateFlow<Boolean> = _open.asStateFlow()
    fun requestOpen() { _open.value = true }
    fun consumeOpen() { _open.value = false }

    fun begin(model: String, total: Int, stop: () -> Unit): Long {
        stopHandler = stop
        val s = ++serial
        _job.value = Job(model, total = total, serial = s)
        return s
    }

    fun progress(step: Int, total: Int) = _job.update { j -> if (j == null || j.finished) j else j.copy(step = step, total = total) }
    fun preview(f: PreviewFrame) = _job.update { j -> if (j == null || j.finished) j else j.copy(preview = f) }

    fun finish(result: String?, error: String?, stopped: Boolean, lowMem: Boolean = false, seed: Long? = null, usedPrompt: String? = null) {
        stopHandler = null
        _job.update { j -> j?.copy(finished = true, result = result, error = error, stopped = stopped, lowMem = lowMem, seed = seed, usedPrompt = usedPrompt) }
    }

    fun requestStop() { stopHandler?.invoke() }
    val running: Boolean get() = _job.value?.let { !it.finished } == true
}

/**
 * 小米 HyperOS 焦点通知（超级岛）参数（纯函数，单测）。写进通知 extras 的 [EXTRA]；
 * 系统不认（非 HyperOS、版本太旧、未放行）时就是一条普通进度通知。
 */
object HyperFocus {
    const val EXTRA = "miui.focus.param"
    /** Settings.System 里的焦点通知协议版本（≥1 支持焦点通知，≥3 有超级岛）。 */
    const val PROTOCOL_KEY = "notification_focus_protocol"

    fun protocol(context: Context): Int = runCatching {
        android.provider.Settings.System.getInt(context.contentResolver, PROTOCOL_KEY, 0)
    }.getOrDefault(0)

    fun param(title: String, content: String, percent: Int, chip: String): String {
        val p = percent.coerceIn(0, 100)
        val base = JSONObject().put("type", 2).put("title", title).put("content", content)
        val prog = JSONObject().put("progress", p).put("colorProgress", "#6C7BFF").put("colorProgressEnd", "#B57BFF")
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject().put("type", 1).put("textInfo", JSONObject().put("title", title)))
                .put("progressTextInfo", JSONObject().put("progressInfo", JSONObject().put("progress", p)).put("textInfo", JSONObject().put("title", chip))))
            .put("smallIslandArea", JSONObject().put("progressInfo", JSONObject().put("progress", p)))
        val v2 = JSONObject()
            .put("protocol", 1)
            .put("enableFloat", false)
            .put("updatable", true)
            .put("ticker", title)
            .put("aodTitle", "$title $chip")
            .put("baseInfo", base)
            .put("progressInfo", prog)
            .put("param_island", island)
        return JSONObject().put("param_v2", v2).toString()
    }

    /** 0..100（总步数未知 = 0）。 */
    fun percent(step: Int, total: Int): Int = if (total <= 0) 0 else (step * 100 / total).coerceIn(0, 100)
}

/**
 * 生图前台服务（specialUse，与聊天生成同类）：只在一张图生成期间存在，离开生图页 / 切后台不中断。
 * 实时进度通知（可点回生图页、带停止）；Android 16 用 ProgressStyle + Live Update，HyperOS 带焦点通知参数；
 * 完成后（不在生图页时）发一条带缩略图的通知。
 */
class ImageGenJobService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastShown = -1
    private var lastShownAt = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, I18n.s(R.string.ig271_n_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, I18n.s(R.string.ig271_n_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:imagegen")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        lifecycleScope.launch { ImageGenMonitor.job.collect { onJob(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            ImageGenMonitor.requestStop()
            return START_NOT_STICKY
        }
        val j = ImageGenMonitor.job.value
        try {
            ServiceCompat.startForeground(this, ID_PROGRESS, progress(j), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            // 此刻不允许（后台启动、配额…）：照常生成，只是没有前台保活。
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
        }
        if (j == null || j.finished) onJob(j)
        return START_NOT_STICKY
    }

    private fun onJob(j: ImageGenMonitor.Job?) {
        if (j == null) { stopSelf(); return }
        val nm = getSystemService(NotificationManager::class.java)
        if (j.finished) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!(inForeground && ImageGenMonitor.pageVisible) && runCatching { nm.areNotificationsEnabled() }.getOrDefault(false)) {
                runCatching { nm.notify(ID_DONE, done(j)) }
            }
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (j.step != lastShown && now - lastShownAt >= UPDATE_MS) {
            lastShown = j.step; lastShownAt = now
            runCatching { nm.notify(ID_PROGRESS, progress(j)) }
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 20,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, OPEN_IMAGEGEN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(j: ImageGenMonitor.Job?): Notification {
        val step = j?.step ?: 0; val total = j?.total ?: 0
        val title = I18n.s(R.string.ig271_n_title)
        val text = (j?.model?.take(32)?.let { "$it · " } ?: "") +
            (if (step <= 0) I18n.s(R.string.ig271_n_loading) else I18n.s(R.string.ig271_n_step, step, total))
        val chip = if (total > 0) I18n.s(R.string.ig271_n_chip, step, total) else "…"
        val stop = PendingIntent.getService(this, 21, Intent(this, ImageGenJobService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val focus = if (HyperFocus.protocol(this) >= 1) Bundle().apply { putString(HyperFocus.EXTRA, HyperFocus.param(title, text, HyperFocus.percent(step, total), chip)) } else null
        if (LiveUpdate.supported(android.os.Build.VERSION.SDK_INT)) {
            runCatching { return liveUpdate(title, text, chip, step, total, stop, focus, j?.startedAt) }
        }
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent())
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total.coerceAtLeast(0), step.coerceAtLeast(0), total <= 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, I18n.s(R.string.ig271_n_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        focus?.let { b.addExtras(it) }
        return b.build()
    }

    /** Android 16：ProgressStyle + 请求提升为 Live Update（状态栏胶囊 / 超级岛），系统决定是否提升。 */
    @androidx.annotation.RequiresApi(36)
    private fun liveUpdate(title: String, text: String, chip: String, step: Int, total: Int, stop: PendingIntent, focus: Bundle?, startedAt: Long?): Notification {
        val style = Notification.ProgressStyle().setStyledByProgress(true)
        if (total > 0) style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(total))).setProgress(step.coerceIn(0, total))
        else style.setProgressIndeterminate(true)
        val extras = Bundle().apply { putBoolean(LiveUpdate.EXTRA_REQUEST_PROMOTED_ONGOING, true); focus?.let { putAll(it) } }
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setColorized(false)
            .setShowWhen(true)
            .setWhen(startedAt ?: System.currentTimeMillis())
            .setUsesChronometer(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShortCriticalText(chip)
            .addAction(Notification.Action.Builder(null, I18n.s(R.string.ig271_n_stop), stop).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addExtras(extras)
            .build()
    }

    private fun done(j: ImageGenMonitor.Job): Notification {
        val ok = j.result != null
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setContentTitle(I18n.s(when { ok -> R.string.ig271_n_done; j.stopped -> R.string.ig271_n_stopped; else -> R.string.ig271_n_failed }))
            .setContentText(if (ok) j.model else (j.error ?: j.model))
            .setAutoCancel(true)
            .setContentIntent(openIntent())
        j.result?.let { path -> thumb(path)?.let { bmp -> b.setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?)) } }
        return b.build()
    }

    /** 缩略图（长边约 512）。 */
    private fun thumb(path: String): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImageGenJobService"
        const val EXTRA_OPEN = "iris.open"
        const val OPEN_IMAGEGEN = "imagegen"
        private const val ACTION_STOP = "iris.imagegen.STOP"
        private const val CHANNEL_PROGRESS = "imagegen"
        private const val CHANNEL_DONE = "imagegen_done"
        private const val ID_PROGRESS = 2101
        private const val ID_DONE = 2102
        private const val UPDATE_MS = 500L
        private const val MAX_WAKE_MS = 60 * 60_000L

        fun start(context: Context) {
            try {
                context.getSystemService(NotificationManager::class.java).cancel(ID_DONE)
                context.startForegroundService(Intent(context, ImageGenJobService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "cannot start imagegen service", t)
            }
        }
    }
}

package com.iris.localai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.iris.localai.AppContainer
import com.iris.localai.R
import com.iris.localai.ui.MainActivity
import com.iris.localai.voice.PackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** 2.4.4: one download in the notification (pure, unit-tested). */
data class DlItem(val key: String, val name: String, val done: Long, val total: Long, val extracting: Boolean = false, val failed: String? = null) {
    val active: Boolean get() = failed == null
}

/** 2.4.4: wording of the download notification (pure, unit-tested). */
object DownloadText {
    fun size(bytes: Long): String = com.iris.localai.data.Format.bytes(bytes)

    fun title(active: List<DlItem>): String = when (active.size) {
        0 -> ""
        1 -> com.iris.localai.I18n.s(R.string.dl244_title_one, active[0].name)
        else -> com.iris.localai.I18n.s(R.string.dl244_title_many, active.size)
    }

    fun body(active: List<DlItem>): String {
        if (active.size == 1) return line(active[0])
        return active.joinToString("\n") { "${it.name} · ${line(it)}" }
    }

    fun line(d: DlItem): String = when {
        d.extracting -> com.iris.localai.I18n.s(R.string.dl244_extracting)
        d.total > 0 -> com.iris.localai.I18n.s(R.string.dl244_progress, percent(d), size(d.done), size(d.total))
        d.done > 0 -> size(d.done)
        else -> com.iris.localai.I18n.s(R.string.dl244_preparing)
    }

    fun percent(d: DlItem): Int = if (d.total > 0) ((d.done.toDouble() / d.total) * 100).toInt().coerceIn(0, 100) else 0

    /** Overall progress of every active download (bytes summed), or null when none knows its size. */
    fun overall(active: List<DlItem>): Int? {
        val sized = active.filter { it.total > 0 && !it.extracting }
        if (sized.isEmpty()) return null
        return ((sized.sumOf { it.done }.toDouble() / sized.sumOf { it.total }) * 100).toInt().coerceIn(0, 100)
    }
}

/**
 * 2.4.4: every model download (对话 / 生图 / 语音 / 翻译) shows a progress notification and, while one is
 * running, a dataSync foreground service keeps IRIDIS from being frozen or killed in the background.
 * A finished download leaves a 「下载完成」 notification; a failed one 「下载失败」 with the reason.
 */
object DownloadNotifier {
    private const val TAG = "IrisDl"
    const val CHANNEL = "model_download"
    const val ID_PROGRESS = 2201
    private const val ID_RESULT = 2202
    @Volatile var running: List<DlItem> = emptyList(); private set
    @Volatile private var appCtx: Context? = null

    fun start(app: Context, c: AppContainer) {
        appCtx = app
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, com.iris.localai.I18n.s(R.string.dl244_channel), NotificationManager.IMPORTANCE_LOW),
        )
        c.scope.launch {
            var prev: Map<String, DlItem> = emptyMap()
            var serviceOn = false
            combine(c.mnnHub.state, c.imageGenDownloader.states, c.voiceStore.states, c.translator.packs.states) { hub, img, voice, tr ->
                buildList {
                    if (hub.active) add(DlItem("hub:" + hub.repoId, hub.repoId.substringAfter('/'), hub.doneBytes, hub.totalBytes))
                    else if (hub.error != null && !hub.paused && hub.repoId.isNotEmpty()) add(DlItem("hub:" + hub.repoId, hub.repoId.substringAfter('/'), 0, 0, failed = hub.error))
                    img.forEach { (id, s) ->
                        val name = com.iris.localai.imagegen.ImageCatalog.known.firstOrNull { it.id == id }?.name ?: id
                        when (s) {
                            is com.iris.localai.imagegen.ImageDl.Downloading -> add(DlItem("img:$id", name, s.done, s.total))
                            is com.iris.localai.imagegen.ImageDl.Extracting -> add(DlItem("img:$id", name, s.done, s.total, extracting = true))
                            is com.iris.localai.imagegen.ImageDl.Failed -> add(DlItem("img:$id", name, 0, 0, failed = s.message))
                        }
                    }
                    voice.forEach { (p, s) -> pack("voice:" + p.id, p.title, s)?.let(::add) }
                    tr.forEach { (p, s) -> pack("tr:" + p.id, p.title, s)?.let(::add) }
                }.associateBy { it.key }
            }.conflate().collect { now ->
                val active = now.values.filter { it.active }
                running = active
                // Finished = was active, now gone (or installed); failed = turned into an error.
                for ((k, was) in prev) {
                    if (!was.active) continue
                    val cur = now[k]
                    when {
                        cur == null -> if (finishedOk(c, k)) result(app, was.name, null)
                        cur.failed != null -> result(app, was.name, cur.failed)
                    }
                }
                prev = now
                if (active.isNotEmpty()) {
                    if (!serviceOn) serviceOn = DownloadService.start(app)
                    app.getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, progress(app, active))
                } else {
                    if (serviceOn) { serviceOn = false; DownloadService.stop() }
                    app.getSystemService(NotificationManager::class.java).cancel(ID_PROGRESS)
                }
                delay(800) // at most ~1 update a second (Android drops faster notification updates anyway)
            }
        }
    }

    private fun pack(key: String, title: String, s: PackState): DlItem? = when (s) {
        is PackState.Downloading -> DlItem(key, title, s.downloaded, s.total)
        PackState.Extracting -> DlItem(key, title, 0, 0, extracting = true)
        is PackState.Failed -> DlItem(key, title, 0, 0, failed = s.message)
        else -> null
    }

    /** A download that just left the list finished (not cancelled / paused). */
    private fun finishedOk(c: AppContainer, key: String): Boolean = when {
        key.startsWith("hub:") -> c.mnnHub.lastDone == key.removePrefix("hub:")
        key.startsWith("img:") -> c.imageGenDownloader.lastFinished == key.removePrefix("img:")
        key.startsWith("voice:") -> c.voiceStore.states.value.entries.any { "voice:" + it.key.id == key && it.value == PackState.Installed }
        key.startsWith("tr:") -> c.translator.packs.states.value.entries.any { "tr:" + it.key.id == key && it.value == PackState.Installed }
        else -> false
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 22, Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, active: List<DlItem>): Notification {
        val pct = DownloadText.overall(active)
        val body = DownloadText.body(active)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(DownloadText.title(active).ifEmpty { com.iris.localai.I18n.s(R.string.dl244_channel) })
            .setContentText(body.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(100, pct ?: 0, pct == null)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun result(ctx: Context, name: String, error: String?) = runCatching {
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (error == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(com.iris.localai.I18n.s(if (error == null) R.string.dl244_done else R.string.dl244_failed, name))
            .apply { if (error != null) setContentText(error).setStyle(NotificationCompat.BigTextStyle().bigText(error)) else setContentText(com.iris.localai.I18n.s(R.string.dl244_done_hint)) }
            .setAutoCancel(true).setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RESULT + (name.hashCode() and 0xfff), n)
    }.onFailure { Log.w(TAG, "result notification", it) }
}

/** 2.4.4: keeps the process alive while a model downloads; its notification is [DownloadNotifier]'s progress. */
class DownloadService : android.app.Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = DownloadNotifier.running
        if (active.isEmpty()) { stopNow(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, DownloadNotifier.ID_PROGRESS, DownloadNotifier.progress(this, active), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) {
            Log.w("IrisDl", "startForeground failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps dataSync at 6 h a day; the download itself goes on as long as the app lives. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopNow() }

    internal fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    companion object {
        @Volatile private var instance: DownloadService? = null
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java)); true
        } catch (t: Throwable) {
            // Not allowed now (started from the background, FGS quota…): the plain notification still shows.
            Log.w("IrisDl", "cannot start download service", t); false
        }
        fun stop() { instance?.let { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { s.stopNow() } } }
    }
}

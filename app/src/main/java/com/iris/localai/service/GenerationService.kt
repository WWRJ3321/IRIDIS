package com.iris.localai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.iris.localai.R
import com.iris.localai.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one chat reply that may run in the background. Written by ChatViewModel, read by the service. */
object GenerationMonitor {
    data class Job(
        val conversationId: String,
        val title: String,
        val chars: Int = 0,
        val finished: Boolean = false,
        val stopped: Boolean = false,
    )

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job.asStateFlow()
    @Volatile private var stopHandler: (() -> Unit)? = null

    fun begin(conversationId: String, title: String, stop: () -> Unit) {
        stopHandler = stop
        _job.value = Job(conversationId, title)
    }

    fun progress(chars: Int) = _job.update { j -> if (j == null || j.finished || j.chars == chars) j else j.copy(chars = chars) }

    fun finish(conversationId: String, chars: Int, stopped: Boolean) {
        stopHandler = null
        _job.update { j -> if (j?.conversationId == conversationId) j.copy(chars = chars, finished = true, stopped = stopped) else j }
    }

    fun requestStop() { stopHandler?.invoke() }

    /** 1.10.13: every streamed piece of the running reply (both modes), for the background stall check. */
    @Volatile var pieces: Long = 0L
        private set
    fun tick() { pieces++ }
    val running: Boolean get() = _job.value?.let { !it.finished } == true

    fun clear() { _job.value = null }
}

/**
 * Keeps a chat reply generating while IRIS is in the background (HyperOS and Android freeze
 * cached apps within seconds, which used to pause the reply until the app was reopened).
 * Lives exactly as long as one reply: foreground notification "正在生成… N 字" with a stop
 * button, a partial wake lock, then a "回复已完成" notification that reopens the conversation.
 * Voice chat never uses it (the voice screen closes when the app leaves the foreground).
 */
class GenerationService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastShownChars = -1
    private var lastShownAt = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, com.iris.localai.I18n.s(com.iris.localai.R.string.service_48967b), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, com.iris.localai.I18n.s(com.iris.localai.R.string.service_e7d00e), NotificationManager.IMPORTANCE_DEFAULT))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:generation")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        lifecycleScope.launch {
            GenerationMonitor.job.collect { j -> onJob(j) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            GenerationMonitor.requestStop()
            return START_NOT_STICKY
        }
        val j = GenerationMonitor.job.value
        try {
            ServiceCompat.startForeground(this, ID_PROGRESS, progress(j?.chars ?: 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            // Not allowed right now (started from the background, FGS quota…): generate without it.
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
        }
        if (j == null || j.finished) onJob(j)
        return START_NOT_STICKY
    }

    private fun onJob(j: GenerationMonitor.Job?) {
        if (j == null) { stopSelf(); return }
        val nm = getSystemService(NotificationManager::class.java)
        if (j.finished) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // In the app the reply is right there; only tell the user when they are elsewhere.
            val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!inForeground && runCatching { nm.areNotificationsEnabled() }.getOrDefault(false)) {
                nm.notify(ID_DONE, done(j))
            }
            GenerationMonitor.clear()
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        if (j.chars != lastShownChars && now - lastShownAt >= UPDATE_MS) {
            lastShownChars = j.chars
            lastShownAt = now
            runCatching { nm.notify(ID_PROGRESS, progress(j.chars)) }
        }
    }

    private fun openIntent(conversationId: String?): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (conversationId != null) putExtra(EXTRA_CONVERSATION, conversationId) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(chars: Int): Notification {
        val j = GenerationMonitor.job.value
        val stop = PendingIntent.getService(
            this, 11, Intent(this, GenerationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setContentTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.service_16efd7))
            .setContentText(GenerationText.progress(j?.title, chars))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(openIntent(j?.conversationId))
            .addAction(0, com.iris.localai.I18n.s(com.iris.localai.R.string.service_095e93), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun done(j: GenerationMonitor.Job): Notification =
        NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setContentTitle(if (j.stopped) com.iris.localai.I18n.s(com.iris.localai.R.string.service_96131e) else com.iris.localai.I18n.s(com.iris.localai.R.string.service_9937df))
            .setContentText(GenerationText.done(j.title, j.chars))
            .setAutoCancel(true)
            .setContentIntent(openIntent(j.conversationId))
            .build()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GenerationService"
        const val EXTRA_CONVERSATION = "iris.conversation"
        private const val ACTION_STOP = "iris.generation.STOP"
        private const val CHANNEL_PROGRESS = "generation"
        private const val CHANNEL_DONE = "generation_done"
        private const val ID_PROGRESS = 2001
        private const val ID_DONE = 2002
        private const val UPDATE_MS = 1_000L
        /** Safety net: the lock is released at the end of the reply anyway. */
        private const val MAX_WAKE_MS = 60 * 60_000L

        fun start(context: Context) {
            try {
                context.getSystemService(NotificationManager::class.java).cancel(ID_DONE)
                context.startForegroundService(Intent(context, GenerationService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "cannot start generation service", t)
            }
        }
    }
}

/** Notification wording (unit-tested). */
object GenerationText {
    fun progress(title: String?, chars: Int): String {
        val t = title?.takeIf { it.isNotBlank() }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.service_0cbdd1, it.take(16)) } ?: ""
        return if (chars <= 0) com.iris.localai.I18n.s(com.iris.localai.R.string.service_fde415, t) else com.iris.localai.I18n.s(com.iris.localai.R.string.service_92cfe0, t, chars)
    }

    fun done(title: String?, chars: Int): String {
        val t = title?.takeIf { it.isNotBlank() }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.service_0cbdd1, it.take(16)) } ?: ""
        return com.iris.localai.I18n.s(com.iris.localai.R.string.service_374df9, t, chars)
    }
}

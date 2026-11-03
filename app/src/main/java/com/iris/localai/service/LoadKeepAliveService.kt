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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.iris.localai.IrisApp
import com.iris.localai.R
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.LoadKeepAlivePolicy
import com.iris.localai.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * 1.10.3: keeps IRIS running while a LiteRT-LM model compiles for the GPU / NPU
 * ([LoadKeepAlivePolicy]): foreground notification 「正在加载模型」 + a partial wake lock, so the
 * system does not freeze or kill the app halfway through the compile (which would leave the GPU
 * cache unfinished and make the next load compile again). Stops itself as soon as the load ends.
 */
class LoadKeepAliveService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, com.iris.localai.I18n.s(com.iris.localai.R.string.service_a3db60), NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:model-load")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = (application as IrisApp).container.engine
        try {
            ServiceCompat.startForeground(this, ID, notification(engine.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycleScope.launch {
            engine.state.collect { s ->
                if (!LoadKeepAlivePolicy.keepAlive(s)) {
                    ServiceCompat.stopForeground(this@LoadKeepAliveService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(s: EngineState): Notification {
        val l = s as? EngineState.Loading
        val open = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setContentTitle(LoadKeepAliveText.TITLE)
            .setContentText(LoadKeepAliveText.text(l?.model?.displayName, l?.backend?.label))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IrisLoad"
        private const val CHANNEL = "model_load"
        private const val ID = 2101
        /** Safety net; the service stops when the load ends. */
        private const val MAX_WAKE_MS = 20 * 60_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LoadKeepAliveService::class.java))
            } catch (t: Throwable) {
                // Not allowed right now (app in the background, FGS quota…): the load goes on without it.
                Log.w(TAG, "cannot start load keep-alive service", t)
            }
        }
    }
}

/** Notification wording (unit-tested). */
object LoadKeepAliveText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.service_e79aea)

    fun text(model: String?, backend: String?): String =
        "${model ?: "模型"}${backend?.let { " · $it" } ?: ""} · 编译完成前请让 IRIDIS 保持运行"
}

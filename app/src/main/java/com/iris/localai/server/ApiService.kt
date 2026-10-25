package com.iris.localai.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.iris.localai.IrisApp
import com.iris.localai.R
import com.iris.localai.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hosts the local OpenAI-compatible API. Runs as a foreground service only while the user has
 * the API switched on; turning it off stops the service and closes the socket completely.
 * A partial wake lock is held only for the duration of each request, never while idle.
 */
class ApiService : LifecycleService() {

    private var server: HttpServer? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    /** Requests in flight: the lock is held from the first one's start to the last one's end. */
    private val inFlight = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:api-request")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            (application as IrisApp).container.settings.update { it.copy(apiEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startServer() {
        val c = (application as IrisApp).container
        val s = c.settings.current
        val lan = s.apiAllowLan && s.apiKey.isNotBlank()
        // 2.1.3: IPv4 127.0.0.1 (not the IPv6 ::1 loopback) unless LAN is on.
        val bind = InetAddress.getByName(ApiPolicy.bindHost(lan))
        val routes = ApiRoutes(c.engine, c.models, c.settings)
        // Not reference counted: with a timed acquire per request, the timeout's own release could
        // drop another request's count and the next release() threw "WakeLock under-locked".
        val srv = HttpServer(bind, s.apiPort) { req, res ->
            synchronized(inFlight) { if (inFlight.getAndIncrement() == 0) wakeLock.acquire(10 * 60_000L) }
            try {
                routes.handle(req, res)
            } finally {
                synchronized(inFlight) { if (inFlight.decrementAndGet() == 0 && wakeLock.isHeld) wakeLock.release() }
            }
        }
        srv.cors = { c.settings.current.apiKey.isNotBlank() }
        try {
            srv.start()
            server = srv
            _status.value = ApiStatus(running = true, port = s.apiPort, lan = lan, addresses = addresses(lan, s.apiPort))
        } catch (t: Throwable) {
            Log.w(TAG, "cannot start server", t)
            _status.value = ApiStatus(running = false, error = "端口 ${s.apiPort} 无法监听：${t.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (wakeLock.isHeld) wakeLock.release()
        _status.value = ApiStatus(running = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.api_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val port = (application as IrisApp).container.settings.current.apiPort
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_iris)
            .setContentTitle(getString(R.string.api_notification_title))
            .setContentText(getString(R.string.api_notification_text, port))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.api_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    data class ApiStatus(
        val running: Boolean = false,
        val port: Int = 0,
        val lan: Boolean = false,
        val addresses: List<String> = emptyList(),
        val error: String? = null,
    )

    companion object {
        private const val TAG = "ApiService"
        private const val CHANNEL = "api"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.iris.localai.STOP_API"

        private val _status = MutableStateFlow(ApiStatus())
        val status: StateFlow<ApiStatus> = _status.asStateFlow()

        fun start(context: Context) {
            // Android 12+ refuses a foreground-service start from the background; never crash for it.
            runCatching { context.startForegroundService(Intent(context, ApiService::class.java)) }
                .onFailure { _status.value = _status.value.copy(running = false, error = "无法启动 API 服务：${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApiService::class.java))
        }

        /** Restart so a changed port / LAN setting takes effect. */
        fun restart(context: Context) {
            stop(context)
            start(context)
        }

        private fun addresses(lan: Boolean, port: Int): List<String> {
            val local = listOf("http://127.0.0.1:$port/v1")
            if (!lan) return local
            val ips = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .map { "http://${it.hostAddress}:$port/v1" }
            }.getOrDefault(emptyList())
            return local + ips
        }
    }
}

package com.iris.localai.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 1.10.13: background generation needs the system to leave IRIS alone. The foreground service +
 * wake lock (GenerationService) is not enough on HyperOS / MIUI with 省电策略 = 智能限制: the app is
 * frozen anyway and the reply only continues when it is reopened.
 */
object BackgroundGuard {
    /** A reply counts as paused in the background when it produced (almost) nothing for this long. */
    const val STALL_MS = 20_000L
    const val STALL_MAX_PIECES = 2L

    const val STALL_NOTICE = "IRIDIS 在后台被系统暂停了，回复没有继续生成。请到 设置 › 应用 › IRIDIS › 省电策略 选「无限制」（小米/HyperOS 还要打开「自启动」），或在 设置 › 性能 › 后台运行 里允许"

    fun isExempt(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)


    /** The system dialog「允许 IRIS 始终在后台运行？」. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))

    /** App info page (HyperOS: 省电策略 / 自启动 live there). */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the best page: the system request when not yet exempt, else the app info page. */
    fun open(context: Context) {
        val first = if (!isExempt(context)) requestIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else appDetailsIntent(context)
        runCatching { context.startActivity(first) }.onFailure { runCatching { context.startActivity(appDetailsIntent(context)) } }
    }

    /** Pure: was the reply stalled while the app was away? (unit-tested) */
    fun stalled(awayMs: Long, piecesWhileAway: Long, stillRunning: Boolean): Boolean =
        stillRunning && awayMs >= STALL_MS && piecesWhileAway <= STALL_MAX_PIECES
}

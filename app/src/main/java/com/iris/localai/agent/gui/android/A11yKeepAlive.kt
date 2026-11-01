package com.iris.localai.agent.gui.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 2.2.14 无障碍保活: once IRIDIS holds WRITE_SECURE_SETTINGS (Shizuku one tap, or adb), it rewrites
 * enabled_accessibility_services + accessibility_enabled on app start, BOOT_COMPLETED and whenever the
 * service disconnects. No in-app adb pairing.
 */
object A11yKeepAlive {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    fun adbCommand(pkg: String = "com.iridis") = "adb shell pm grant $pkg $PERMISSION"

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun status(context: Context): KeepAliveStatus = KeepAliveStatus.of(
        granted(context), IrisAccessibilityService.enabledInSettings(context), IrisAccessibilityService.connected,
    )

    /** Puts the service back in the enabled list (no-op without the permission). */
    fun ensure(context: Context, forceRebind: Boolean = false): Boolean {
        if (!granted(context)) return false
        return runCatching {
            val r = context.contentResolver
            val me = ComponentName(context, IrisAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val listed = list.split(':').any { it.trim().equals(me, ignoreCase = true) }
            if (listed && !forceRebind) {
                if (Settings.Secure.getInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1)
                    Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                return@runCatching false
            }
            // Listed but dead (清后台) → take it out first so the system binds it again.
            if (listed) Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11yList.remove(list, me))
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11yList.add(A11yList.remove(list, me), me))
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(IrisAccessibilityService.TAG, "keep-alive: rewrote enabled services (rebind=$listed)")
            true
        }.onFailure { Log.w(IrisAccessibilityService.TAG, "keep-alive failed: ${it.message}") }.getOrDefault(false)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Service unbound/destroyed: rewrite after a short pause unless it came back on its own. */
    fun onDisconnected(context: Context) {
        if (!granted(context)) return
        val app = context.applicationContext
        main.postDelayed({ if (!IrisAccessibilityService.connected) ensure(app, forceRebind = true) }, 1_500)
    }

    // ------------------------------------------------------------- Shizuku
    fun shizukuRunning(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuPermitted(): Boolean = runCatching {
        !rikka.shizuku.Shizuku.isPreV11() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Asks Shizuku for permission; [onResult] true = allowed. */
    fun requestShizuku(onResult: (Boolean) -> Unit) {
        if (shizukuPermitted()) { onResult(true); return }
        runCatching {
            val code = 2214
            val l = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != code) return
                    rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(l)
            rikka.shizuku.Shizuku.requestPermission(code)
        }.onFailure { onResult(false) }
    }

    /** Runs `pm grant <pkg> WRITE_SECURE_SETTINGS` as shell through Shizuku. Blocking; call off main. */
    fun grantViaShizuku(context: Context): Result<Unit> = runCatching {
        check(shizukuRunning()) { "Shizuku 未运行" }
        check(shizukuPermitted()) { "Shizuku 未授权给 IRIDIS" }
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("pm", "grant", context.packageName, PERMISSION), null, null) as Process
        val err = p.errorStream.bufferedReader().readText()
        val rc = p.waitFor()
        check(rc == 0) { "pm grant 失败（$rc）${err.take(120)}" }
        check(granted(context)) { "授权未生效" }
        ensure(context)
    }

    // ------------------------------------------------------------- guides
    fun openDeveloperOptions(context: Context) = start(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    fun openBatterySettings(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        val i = if (pm?.isIgnoringBatteryOptimizations(context.packageName) == false)
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + context.packageName))
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (!start(context, i)) IrisAccessibilityService.openAppInfo(context)
    }

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** 自启动管理 (MIUI/HyperOS, ColorOS, EMUI, vivo); falls back to 应用信息. */
    fun openAutostart(context: Context) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        if (candidates.none { start(context, Intent().setComponent(it)) }) IrisAccessibilityService.openAppInfo(context)
    }

    private fun start(context: Context, i: Intent): Boolean =
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
}

/** 2.2.14: card status (unit-tested). */
enum class KeepAliveStatus(val label: String) {
    ACTIVE("已保活"), GRANTED_SERVICE_OFF("已授权 · 服务未开启"), NOT_GRANTED("未授权");

    companion object {
        fun of(granted: Boolean, enabledInSettings: Boolean, connected: Boolean): KeepAliveStatus = when {
            !granted -> NOT_GRANTED
            enabledInSettings || connected -> ACTIVE
            else -> GRANTED_SERVICE_OFF
        }
    }
}

/** 2.2.14: BOOT_COMPLETED → put the 无障碍 service back (only with the permission). */
class A11yBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) A11yKeepAlive.ensure(context)
    }
}

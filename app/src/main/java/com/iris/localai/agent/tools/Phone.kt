package com.iris.localai.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.app.NotificationManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.content.IntentFilter
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 1.12.0: what the `phone` tool can do; the Android side is [AndroidPhoneActions]. */
interface PhoneActions {
    fun setAlarm(hour: Int, minute: Int, label: String?): String
    fun startTimer(seconds: Int, label: String?): String
    fun addEvent(title: String, startMs: Long, endMs: Long, location: String?, notes: String?): String
    /** Launchable apps as (label, package). */
    fun apps(): List<Pair<String, String>>
    fun openApp(pkg: String): String
    fun share(text: String): String
    fun copy(text: String): String
    fun paste(): String?
    fun openUrl(url: String): String
    fun dial(number: String): String
    /** 2.5.0-beta7: torch on the back camera; [mode] = on | off | toggle. */
    fun flashlight(mode: String): String
    /** 2.5.0-beta7: [stream] = media | ring | alarm (null + query → all); [percent] null = query. */
    fun volume(stream: String?, percent: Int?): String
    /** 2.5.0-beta7: [mode] = silent | vibrate | normal; null = query. */
    fun ringerMode(mode: String?): String
    /** 2.5.0-beta7: screen brightness 0–100 %; null = query. Needs WRITE_SETTINGS, else opens that page. */
    fun brightness(percent: Int?): String
    /** 2.5.0-beta7: open a system settings page: wifi | bluetooth | location | airplane | display | battery | app ([pkg] for app details). */
    fun openSettings(page: String, pkg: String?): String
    /** 2.5.0-beta7: battery level and charging state. */
    fun battery(): String
}

/**
 * 1.12.0 `phone`: everyday phone actions through standard Android intents — no accessibility
 * service, no root. Alarms and timers go to the clock app (SET_ALARM permission); calendar events
 * and dialing open their app's own screen so the user saves / calls themselves. Asks first.
 */
object PhoneTool : AgentTool {
    override val name = "phone"
    override val label = "手机操作"
    override val description = "设闹钟、计时器、加日历、打开 App、分享、剪贴板、打开网页、拨号、手电筒、音量/响铃模式、亮度、系统设置页、电量（每次询问）"
    override val promptLine = "phone(action: alarm|timer|calendar|open_app|share|copy|paste|open_url|dial|flashlight|volume|ringer|brightness|settings|battery, time?, end?, seconds?, text?, name?, state?: on|off|toggle, stream?: media|ring|alarm, level?: 0-100, mode?: silent|vibrate|normal, page?: wifi|bluetooth|location|airplane|display|battery|app)"
    override val risk = ToolRisk.WRITE
    override val group = ToolGroup.PHONE

    private val HM = Regex("^(\\d{1,2})[:：.](\\d{2})$")

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val p = env.phone ?: return ToolResult(false, "Error: phone actions are not available", "不可用")
        val action = (Args.str(args, "action", "type", "op") ?: throw IllegalArgumentException("缺少参数 action")).trim().lowercase()
        val text = Args.str(args, "text", "label", "message", "title", "content")
        fun ok(s: String) = ToolResult(true, s, s)
        return when (action) {
            "alarm", "set_alarm" -> {
                val t = Args.str(args, "time", "at", "when") ?: throw IllegalArgumentException("缺少参数 time（HH:mm）")
                val (h, m) = parseHourMinute(t) ?: throw IllegalArgumentException("time 应为 HH:mm，例如 07:30")
                ok(p.setAlarm(h, m, text))
            }
            "timer", "countdown" -> {
                val s = Args.int(args, "seconds", "secs", "duration")
                    ?: Args.int(args, "minutes", "mins")?.times(60)
                    ?: throw IllegalArgumentException("缺少参数 seconds")
                if (s !in 1..86_400) throw IllegalArgumentException("seconds 应在 1–86400 之间")
                ok(p.startTimer(s, text))
            }
            "calendar", "event", "add_event" -> {
                val title = text ?: throw IllegalArgumentException("缺少参数 text（日程标题）")
                val start = Args.str(args, "time", "start", "when", "at")?.let { parseDateTime(it, env) }
                    ?: throw IllegalArgumentException("缺少或无法识别 time（yyyy-MM-dd HH:mm）")
                val end = Args.str(args, "end", "until")?.let { parseDateTime(it, env) } ?: (start + 3_600_000L)
                ok(p.addEvent(title, start, maxOf(end, start + 60_000L), Args.str(args, "location", "place"), Args.str(args, "notes", "description")))
            }
            "open_app", "open", "launch" -> {
                val q = Args.str(args, "name", "app", "app_name", "appName", "package", "target", "text") ?: throw IllegalArgumentException("缺少参数 name（应用名）。要在 App 里点击、发消息等多步操作，请改用 screen(task)")
                val apps = p.apps()
                // 2.1.9: Chinese / English / package, fuzzy; not found → the close names.
                val app = matchApp(q, apps) ?: return AppMatcher.notFound(q, apps).let { ToolResult(false, "Error: $it", it.substringBefore('。')) }
                p.openApp(app.second); ok("Opened ${app.first}")
            }
            "share" -> ok(p.share(text ?: throw IllegalArgumentException("缺少参数 text")))
            "copy", "clipboard" -> ok(p.copy(text ?: throw IllegalArgumentException("缺少参数 text")))
            "paste", "read_clipboard" -> p.paste()?.let { ToolResult(true, capTokens("Clipboard:\n$it", env.outputTokens), "${it.length} 字") }
                ?: ToolResult(false, "Error: the clipboard is empty or not readable", "剪贴板为空")
            "open_url", "url", "browse" -> {
                val u = Args.str(args, "url", "link", "text") ?: throw IllegalArgumentException("缺少参数 url")
                val url = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
                ok(p.openUrl(url))
            }
            "dial", "call" -> {
                val n = (Args.str(args, "number", "phone", "text") ?: throw IllegalArgumentException("缺少参数 number")).filter { it.isDigit() || it == '+' }
                if (n.length < 3) throw IllegalArgumentException("号码无效")
                ok(p.dial(n))
            }
            // 2.5.0-beta7: device controls.
            "flashlight", "torch", "flash", "手电筒" -> {
                val raw = Args.str(args, "state", "mode", "value", "on", "text") ?: "toggle"
                val m = torchMode(raw) ?: throw IllegalArgumentException("state 应为 on|off|toggle")
                ok(p.flashlight(m))
            }
            "volume", "get_volume", "set_volume", "音量" -> {
                val level = Args.int(args, "level", "percent", "value", "volume")
                if (level != null && level !in 0..100) throw IllegalArgumentException("level 应在 0–100 之间（百分比）")
                val rawStream = Args.str(args, "stream", "channel", "target", "kind")
                val stream = rawStream?.let { volumeStream(it) ?: throw IllegalArgumentException("stream 应为 media|ring|alarm") }?.takeIf { it != "all" }
                ok(p.volume(stream ?: if (level != null) "media" else null, level))
            }
            "ringer", "ringer_mode", "sound_mode", "silent", "mute", "vibrate", "normal", "ring" -> {
                val raw = when (action) { "silent", "mute" -> "silent"; "vibrate" -> "vibrate"; "normal", "ring" -> "normal"
                    else -> Args.str(args, "mode", "state", "value", "text") }
                val m = raw?.let { ringerMode(it) ?: throw IllegalArgumentException("mode 应为 silent|vibrate|normal") }
                ok(p.ringerMode(m))
            }
            "brightness", "screen_brightness", "亮度" -> {
                val level = Args.int(args, "level", "percent", "value", "brightness")
                if (level != null && level !in 0..100) throw IllegalArgumentException("level 应在 0–100 之间（百分比）")
                ok(p.brightness(level))
            }
            "settings", "open_settings", "setting", "设置" -> {
                val raw = Args.str(args, "page", "name", "target", "setting", "text") ?: throw IllegalArgumentException("缺少参数 page（wifi|bluetooth|location|airplane|display|battery|app）")
                val page = settingsPage(raw) ?: throw IllegalArgumentException("未知设置页：$raw。可选 wifi|bluetooth|location|airplane|display|battery|app")
                val pkg = if (page == "app") Args.str(args, "app", "package", "app_name")?.let { q -> matchApp(q, p.apps())?.second ?: throw IllegalArgumentException("找不到应用：$q") } else null
                ok(p.openSettings(page, pkg))
            }
            "battery", "battery_level", "电量" -> ok(p.battery())
            else -> throw IllegalArgumentException("未知 action：$action")
        }
    }

    /** 2.5.0-beta7: "on"/"开"/true → on, "off"/"关"/false → off, "toggle"/"切换" → toggle. */
    fun torchMode(raw: String): String? = when (raw.trim().lowercase()) {
        "on", "open", "true", "1", "开", "打开", "开启" -> "on"
        "off", "close", "false", "0", "关", "关闭", "关掉" -> "off"
        "toggle", "switch", "切换", "" -> "toggle"
        else -> null
    }

    fun volumeStream(raw: String): String? = when (raw.trim().lowercase()) {
        "media", "music", "媒体", "音乐" -> "media"
        "ring", "ringer", "ringtone", "call", "铃声", "来电" -> "ring"
        "alarm", "闹钟" -> "alarm"
        "all", "全部" -> "all"
        else -> null
    }

    fun ringerMode(raw: String): String? = when (raw.trim().lowercase()) {
        "silent", "mute", "静音" -> "silent"
        "vibrate", "vibration", "震动", "振动" -> "vibrate"
        "normal", "ring", "sound", "响铃", "正常" -> "normal"
        else -> null
    }

    /** Android 10+ apps can't switch Wi-Fi / Bluetooth themselves, so these open the page for the user. */
    fun settingsPage(raw: String): String? = when (raw.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "wifi", "wi_fi", "wlan", "无线" -> "wifi"
        "bluetooth", "bt", "蓝牙" -> "bluetooth"
        "location", "gps", "定位", "位置" -> "location"
        "airplane", "airplane_mode", "flight", "flight_mode", "飞行模式" -> "airplane"
        "display", "screen", "显示", "屏幕" -> "display"
        "battery", "power", "电池", "耗电" -> "battery"
        "app", "app_details", "application", "应用详情", "应用信息" -> "app"
        else -> null
    }

    fun parseHourMinute(t: String): Pair<Int, Int>? {
        val m = HM.find(t.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h to mi else null
    }

    private val DT = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")

    /** "yyyy-MM-dd HH:mm" (or "HH:mm" = today) in the phone's zone → epoch ms. */
    fun parseDateTime(s: String, env: ToolEnv): Long? {
        val t = s.trim()
        val zone = env.zone()
        parseHourMinute(t)?.let { (h, m) ->
            val now = java.time.Instant.ofEpochMilli(env.clock()).atZone(zone)
            return now.toLocalDate().atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        }
        for (p in DT) runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p)).atZone(zone).toInstant().toEpochMilli() }
        return null
    }

    /** Best match by name (中文 / English / alias), then prefix, contains, package — see [AppMatcher]. */
    fun matchApp(q: String, apps: List<Pair<String, String>>): Pair<String, String>? = AppMatcher.match(q, apps)
}

class AndroidPhoneActions(context: Context) : PhoneActions {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private fun start(i: Intent): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); "" } catch (e: android.content.ActivityNotFoundException) {
            throw IllegalStateException("没有能处理这个操作的 App")
        }
    }

    override fun setAlarm(hour: Int, minute: Int, label: String?): String {
        start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })
        return "Alarm set for %02d:%02d".format(hour, minute) + (label?.let { " ($it)" } ?: "")
    }

    override fun startTimer(seconds: Int, label: String?): String {
        start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })
        return "Timer started: ${seconds}s"
    }

    override fun addEvent(title: String, startMs: Long, endMs: Long, location: String?, notes: String?): String {
        start(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            .apply { location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }; notes?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) } })
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        return "Calendar opened with \"$title\" at ${f.format(java.time.Instant.ofEpochMilli(startMs))}; the user saves it there"
    }

    override fun apps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
    }

    override fun openApp(pkg: String): String {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("无法打开 $pkg")
        start(i); return "Opened $pkg"
    }

    override fun share(text: String): String {
        start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        return "Share sheet opened"
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var r: T? = null
        val latch = CountDownLatch(1)
        main.post { try { r = block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return r
    }

    override fun copy(text: String): String {
        onMain { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("IRIS", text)) }
        return "Copied ${text.length} characters"
    }

    override fun paste(): String? = onMain {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
    }

    override fun openUrl(url: String): String { start(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return "Opened $url" }

    override fun dial(number: String): String { start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))); return "Dialer opened with $number; the user places the call" }

    // ---- 2.5.0-beta7: device controls ----

    @Volatile private var torchOn: Boolean? = null
    @Volatile private var torchId: String? = null
    private var torchCb: CameraManager.TorchCallback? = null

    /** Back camera with a flash unit (else any camera with one). */
    private fun flashCamera(cm: CameraManager): String? {
        val withFlash = cm.cameraIdList.filter { id ->
            runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
        }
        return withFlash.firstOrNull { id ->
            runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }.getOrDefault(false)
        } ?: withFlash.firstOrNull()
    }

    override fun flashlight(mode: String): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = torchId ?: runCatching { flashCamera(cm) }.getOrNull()?.also { torchId = it }
            ?: throw IllegalStateException("这台设备没有可用的闪光灯")
        if (torchCb == null) {
            // Tracks the real torch state (also when the user flips it from quick settings).
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) torchOn = enabled }
                override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id) torchOn = false }
            }
            runCatching { cm.registerTorchCallback(cb, main); torchCb = cb }
        }
        val target = when (mode) { "on" -> true; "off" -> false; else -> torchOn != true }
        try {
            cm.setTorchMode(id, target)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            throw IllegalStateException("闪光灯暂时不可用（相机可能正被其他 App 使用）")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("闪光灯不可用")
        }
        torchOn = target
        return if (target) "Flashlight on" else "Flashlight off"
    }

    private val audio get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun streamOf(s: String) = when (s) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }

    private fun percentOf(a: AudioManager, stream: Int): Int {
        val min = a.getStreamMinVolume(stream); val max = a.getStreamMaxVolume(stream)
        return if (max <= min) 0 else ((a.getStreamVolume(stream) - min) * 100.0 / (max - min)).roundToIntSafe()
    }

    private fun Double.roundToIntSafe() = kotlin.math.round(this).toInt().coerceIn(0, 100)

    override fun volume(stream: String?, percent: Int?): String {
        val a = audio
        if (percent == null) {
            val names = stream?.let { listOf(it) } ?: listOf("media", "ring", "alarm")
            return names.joinToString(", ") { "$it ${percentOf(a, streamOf(it))}%" }.let { "Volume: $it; ringer mode ${ringerName(a.ringerMode)}" }
        }
        val s = stream ?: "media"
        val st = streamOf(s)
        val min = a.getStreamMinVolume(st); val max = a.getStreamMaxVolume(st)
        val idx = min + kotlin.math.round((max - min) * percent / 100.0).toInt()
        return try {
            a.setStreamVolume(st, idx.coerceIn(min, max), AudioManager.FLAG_SHOW_UI)
            "Volume $s set to ${percentOf(a, st)}%"
        } catch (e: SecurityException) {
            // Changing ring volume while Do Not Disturb is on needs DND access.
            openDndAccess()
            "Could not change $s volume: Do Not Disturb access is not granted to IRIDIS. The Do Not Disturb access page was opened; the user can allow IRIDIS there and try again"
        }
    }

    private fun ringerName(m: Int) = when (m) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }

    private fun openDndAccess() { runCatching { start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }

    override fun ringerMode(mode: String?): String {
        val a = audio
        if (mode == null) return "Ringer mode: ${ringerName(a.ringerMode)}"
        val target = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val noAccess = "Could not switch to $mode: changing the ringer mode here needs Do Not Disturb access, which IRIDIS doesn't have. The Do Not Disturb access page was opened; the user can allow IRIDIS there (or switch the mode from quick settings)"
        // Silent (and leaving silent) touches DND, which needs notification policy access.
        val touchesDnd = target == AudioManager.RINGER_MODE_SILENT || a.ringerMode == AudioManager.RINGER_MODE_SILENT
        if (touchesDnd && !nm.isNotificationPolicyAccessGranted) { openDndAccess(); return noAccess }
        return try {
            a.ringerMode = target
            "Ringer mode set to ${ringerName(a.ringerMode)}"
        } catch (e: SecurityException) { openDndAccess(); noAccess }
    }

    override fun brightness(percent: Int?): String {
        val cr = ctx.contentResolver
        val auto = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrDefault(false)
        if (percent == null) {
            val v = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
            return "Brightness: " + (v?.let { "${(it * 100 / 255).coerceIn(0, 100)}%" } ?: "unknown") + if (auto) " (auto)" else ""
        }
        if (!Settings.System.canWrite(ctx)) {
            runCatching { start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            return "Could not set brightness: IRIDIS needs the \"Modify system settings\" permission. That settings page was opened; the user can allow it and try again"
        }
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceIn(1, 255))
            "Brightness set to $percent%" + if (auto) " (auto brightness turned off)" else ""
        } catch (e: SecurityException) {
            throw IllegalStateException("没有修改系统设置的权限")
        }
    }

    override fun openSettings(page: String, pkg: String?): String {
        val i = when (page) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${pkg ?: ctx.packageName}"))
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = if (page == "battery") Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) else Intent(Settings.ACTION_SETTINGS)
        try { ctx.startActivity(i) } catch (e: android.content.ActivityNotFoundException) { start(fallback) }
        val note = when (page) {
            "wifi", "bluetooth", "airplane", "location" -> "; on Android 10+ apps can't switch this themselves, so the user toggles it there"
            else -> ""
        }
        return "Opened $page settings" + (if (page == "app") " for ${pkg ?: ctx.packageName}" else "") + note
    }

    override fun battery(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            ?: sticky?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && sc > 0) l * 100 / sc else null }
        val plug = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = bm.isCharging || plug != 0
        val via = when (plug) { BatteryManager.BATTERY_PLUGGED_AC -> " (AC)"; BatteryManager.BATTERY_PLUGGED_USB -> " (USB)"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> " (wireless)"; else -> "" }
        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { ", ${it / 10.0}°C" } ?: ""
        return "Battery: ${level?.let { "$it%" } ?: "unknown"}, " + (if (charging) "charging$via" else "not charging") + temp
    }
}

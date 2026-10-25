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
}

/**
 * 1.12.0 `phone`: everyday phone actions through standard Android intents — no accessibility
 * service, no root. Alarms and timers go to the clock app (SET_ALARM permission); calendar events
 * and dialing open their app's own screen so the user saves / calls themselves. Asks first.
 */
object PhoneTool : AgentTool {
    override val name = "phone"
    override val label = "手机操作"
    override val description = "设闹钟、计时器、加日历、打开 App、分享、剪贴板、打开网页、拨号（每次询问）"
    override val promptLine = "phone(action: alarm|timer|calendar|open_app|share|copy|paste|open_url|dial, time?, end?, seconds?, text?, name?)"
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
            else -> throw IllegalArgumentException("未知 action：$action")
        }
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
}

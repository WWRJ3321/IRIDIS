package com.iris.localai.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why the app died last time — Kotlin exceptions via the default handler, and native
 * crashes / low-memory kills via ApplicationExitInfo — so the next launch can show it.
 */
object CrashReporter {
    /** 1.14.3: internal so 设置 › 存储空间 can list (and clear) the crash record. */
    internal const val FILE = "last-crash.txt"
    private const val SEEN = "crash-seen-ts"

    @Volatile private var crumbFile: File? = null

    /**
     * True when the previous process died with native voice work in flight (a breadcrumb was left
     * behind). Startup then skips the automatic speech warm-up so one bad model can't crash-loop the app.
     */
    @Volatile var diedInNativeWorkLastRun = false
        private set

    /** Text of the breadcrumb file left by the previous process, or null. Kept after the file is deleted. */
    @Volatile var lastBreadcrumb: String? = null
        private set

    /**
     * Latest-exit snapshot. The trace stream can be read once; a missing stream is retried,
     * an opened stream (even with no printable text) is not.
     */
    private val exitLock = Any()
    private var traceSettled = false
    private var latestNative = false
    private var nativeTrace: String = ""

    /** Notes what native work is about to run, so a native crash report can say where it died. */
    fun breadcrumb(text: String?) {
        val f = crumbFile ?: return
        runCatching { if (text == null) f.delete() else f.writeText("${stamp(System.currentTimeMillis())} $text") }
    }

    /**
     * True when the latest process exit was a native crash, even if its tombstone had no printable text.
     * Cached with [nativeCrashText]; a failed binder read is retried.
     */
    fun latestExitWasNativeCrash(context: Context): Boolean = readExit(context).native

    /**
     * Printable tombstone text of the latest exit when that exit was a native crash, otherwise "".
     * Reused by the dialog and by [com.iris.localai.engine.StartupLoadGuard].
     */
    fun nativeCrashText(context: Context): String = readExit(context).trace

    private data class ExitSnap(val native: Boolean, val trace: String)

    private fun readExit(context: Context): ExitSnap = synchronized(exitLock) {
        if (traceSettled) return ExitSnap(latestNative, nativeTrace)
        val fresh = queryExit(context.applicationContext) ?: return ExitSnap(latestNative, nativeTrace)
        if (!fresh.native) {
            latestNative = false
            traceSettled = true
        } else {
            latestNative = true
            if (fresh.trace.isNotEmpty()) nativeTrace = fresh.trace
            // A null stream may appear a moment later. An opened stream will not.
            if (fresh.trace.isNotEmpty() || fresh.opened) traceSettled = true
        }
        ExitSnap(latestNative, nativeTrace)
    }

    private data class ExitRead(val native: Boolean, val trace: String, val opened: Boolean)

    /** Null when the exit-info call itself failed, so the caller can retry. */
    private fun queryExit(context: Context): ExitRead? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ExitRead(false, "", true)
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return ExitRead(false, "", true)
            val info = am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
                ?: return ExitRead(false, "", true)
            if (info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) ExitRead(false, "", true)
            else {
                val (text, opened) = printableRuns(info)
                ExitRead(true, text, opened)
            }
        }.getOrNull()
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val crumb = File(app.filesDir, "breadcrumb.txt")
        crumbFile = crumb
        lastBreadcrumb = runCatching { if (crumb.exists()) crumb.readText() else null }.getOrNull()
        diedInNativeWorkLastRun = lastBreadcrumb != null
        nativeCrashText(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(app.filesDir, FILE).writeText(
                    "${stamp(System.currentTimeMillis())} · 线程 ${t.name}\n${header()}\n${e.stackTraceToString().take(6000)}",
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The unseen report from the previous run, or null. Marks it seen. */
    fun takeReport(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            val text = f.readText()
            f.delete()
            return text
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val info = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1) }.getOrNull()?.firstOrNull() ?: return null
        if (info.timestamp <= seen) return null
        prefs.edit().putLong(SEEN, info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生库崩溃 (native)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足，被系统结束"
            ApplicationExitInfo.REASON_ANR -> "无响应 (ANR)"
            ApplicationExitInfo.REASON_CRASH -> "程序异常"
            ApplicationExitInfo.REASON_SIGNALED -> if (info.status == 9) "被系统强制结束（多半是内存不足）" else "收到信号 ${info.status}"
            else -> return null // normal exits, user swipes, updates
        }
        val crumb = File(context.filesDir, "breadcrumb.txt").takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
            ?: lastBreadcrumb
        val trace = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) shortTrace(nativeCrashText(context)) else ""
        val mem = "PSS ${info.pss / 1024} MB · RSS ${info.rss / 1024} MB"
        return buildString {
            append("${stamp(info.timestamp)} · $reason\n$mem\n${header()}\n${info.description ?: ""}")
            if (crumb != null) append("\n崩溃时正在：$crumb")
            if (trace.isNotBlank()) append("\n$trace")
        }
    }

    /**
     * Every printable run in the tombstone, and whether the trace stream was actually opened.
     * The dialog shows [shortTrace]; the load gate searches all of it.
     */
    private fun printableRuns(info: ApplicationExitInfo): Pair<String, Boolean> {
        val stream = info.traceInputStream ?: return "" to false
        return runCatching {
            val bytes = stream.use { it.readBytes() }
            val sb = StringBuilder()
            val run = StringBuilder()
            fun flush() {
                if (run.length >= 6) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(run)
                }
                run.setLength(0)
            }
            for (b in bytes) {
                val ch = b.toInt() and 0xFF
                if (ch in 0x20..0x7E) run.append(ch.toChar()) else flush()
            }
            flush()
            sb.toString() to true
        }.getOrDefault("" to true)
    }

    private fun shortTrace(full: String): String =
        full.lineSequence().filter { r ->
            listOf("SIG", "abort", "Abort", "sherpa", "onnx", "litert", "llama", "ggml", "Check failed", "error", ".so", "iris").any { it in r }
        }.distinct().take(40).joinToString("\n")

    private fun header() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""}"
    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}

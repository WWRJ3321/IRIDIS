package com.iris.localai.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.File

/**
 * 1.8.0 crash diagnostics: exports the last native crash (tombstone), the load-stage log written
 * by libiris_mnn while a model loads, and a filtered logcat snapshot, so a SIGSEGV inside MNN while
 * loading a big model can be analysed without root / adb.
 *
 * The public Download folder cannot be written directly under scoped storage, so every file is
 * published through MediaStore with the exact requested name (iris_tombstone.bin /
 * iris_crash.txt / iris_logcat.txt / iris_mnn_load.log) — the OS appends " (1)" on name clashes —
 * and a copy always stays in the app folder (Android/data/com.iris.localai/files/) which the
 * button reports when MediaStore is unavailable (*.irislocalai.app.copy files).
 */
object CrashDump {

    data class Item(val name: String, val bytes: Int, val where: String, val note: String? = null)
    data class Result(val items: List<Item>, val note: String)

    /** Runs IO; call from a background thread. */
    fun export(context: Context): Result {
        val items = mutableListOf<Item>()
        val notes = mutableListOf<String>()

        // ---- 1. native crash records (REASON_CRASH_NATIVE, up to 5) ----
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val events = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }
            .getOrDefault(emptyList())
        val native = events.filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
        notes += "exit records=${events.size}, native crashes=${native.size}" +
            if (native.isEmpty()) "（最近 5 条退出记录里没有 native crash）" else ""
        val tombstones = StringBuilder()
        val summary = StringBuilder()
        val now = System.currentTimeMillis()
        val uptime = android.os.SystemClock.elapsedRealtime()
        native.forEachIndexed { i, e ->
            tombstones.append("===== native crash #${i + 1} =====\n")
            val wall = now - (uptime - e.timestamp)
            summary.append("--- crash ${i + 1} ---\n")
                .append("description: ").append(e.description ?: "(none)").append('\n')
                .append("time: ").append(java.util.Date(wall)).append('\n')
                .append("pss: ").append(e.pss).append(" kB   rss: ").append(e.rss).append(" kB\n")
                .append("importance: ").append(e.importance).append('\n')
            val trace: String = try {
                e.traceInputStream?.use { ins -> TombstoneText.render(ins.readBytes()) } ?: "(此记录无 trace 内容)\n"
            } catch (ex: Exception) { "(读取 tombstone 失败: ${ex.message})\n" }
            tombstones.append(trace).append('\n')
            summary.append("trace head: ").append(trace.lineSequence().firstOrNull { it.isNotBlank() } ?: "(empty)").append('\n')
        }
        if (tombstones.isEmpty()) tombstones.append("(no REASON_CRASH_NATIVE in the last 5 exit records)\n")
        summary.append('\n')
            .append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
            .append("app: ").append(com.iris.localai.BuildConfig.VERSION_NAME)
            .append(" (").append(com.iris.localai.BuildConfig.VERSION_CODE).append(")\n")
            .append("pid now: ").append(Process.myPid()).append('\n')
            .append("collected: ").append(java.util.Date(now)).append('\n')
            .append(notes.joinToString("\n")).append('\n')

        items += publish(context, "iris_tombstone.bin", tombstones.toString().toByteArray())
        items += publish(context, "iris_crash.txt", summary.toString().toByteArray())

        // ---- 2. the live MNN load-stage log (written by libiris_mnn at every load stage) ----
        val loadLog = File(context.getExternalFilesDir(null), "iris_mnn_load.log")
        if (loadLog.isFile) {
            items += publish(context, "iris_mnn_load.log", loadLog.readBytes())
        } else {
            items += Item("iris_mnn_load.log", 0, "-", "还没有加载记录（加载过一次 MNN 模型后就有了）")
        }

        // ---- 3. filtered logcat: main + crash buffers (1.8.0: main 里的 MNN/MNNJNI 行是定位关键) ----
        val kept = StringBuilder()
        val run = runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "crash", "-b", "system"),
            )
            p.inputStream.bufferedReader().use { r -> drain(r, kept) }
            p.waitFor()
        }
        if (run.isSuccess) items += publish(context, "iris_logcat.txt", kept.toString().toByteArray())
        else items += Item("iris_logcat.txt", 0, "-", "logcat 读取失败: ${run.exceptionOrNull()?.message}")

        val downloaded = items.count { it.where == "Download" }
        return Result(items, if (downloaded > 0) "已写入 Download（$downloaded 个文件）" else "Download 写入失败，副本在 App 目录")
    }

    /**
     * Lines that matter for a loader crash: MNN, our tags (IrisLoad / IrisResource / IrisGen …), the
     * crash debugger, libc, tombstoned. 2.0: plus the NPU path — LiteRT / TFLite, QNN, and the
     * FastRPC / ADSP / CDSP layer the Hexagon runtime talks through — so NPU errors reach iris_logcat.txt.
     */
    internal val LOGCAT_KEYS = listOf(
        "mnn", "iris", "irisload", "irisresource", "debug", "libc", "tombstone", "linker", "androidruntime",
        "litert", "tflite", "qnn", "adsprpc", "fastrpc", "cdsp",
    )

    /** Case-insensitive: does this logcat line belong in iris_logcat.txt? */
    internal fun keepLogcatLine(line: String): Boolean {
        val low = line.lowercase()
        return LOGCAT_KEYS.any { low.contains(it) }
    }

    private fun drain(r: BufferedReader, kept: StringBuilder) {
        while (true) {
            val line = r.readLine() ?: break
            if (keepLogcatLine(line)) kept.append(line).append('\n')
        }
    }

    /** MediaStore → /sdcard/Download/<name>; fallback: files/<name>.irislocalai.app.copy. */
    private fun publish(context: Context, name: String, data: ByteArray): Item {
        val viaMedia = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".txt") || name.endsWith(".log")) "text/plain" else "application/octet-stream")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore 返回 null")
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("无法打开输出流")
            val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else name
            } ?: name
            Item(shown, data.size, "Download")
        }
        if (viaMedia.isSuccess) return viaMedia.getOrThrow()
        val f = File(context.getExternalFilesDir(null), "$name.irislocalai.app.copy")
        runCatching { f.writeBytes(data) }
        return Item(name, data.size, "App 目录", "MediaStore 失败: ${viaMedia.exceptionOrNull()?.message}")
    }
}
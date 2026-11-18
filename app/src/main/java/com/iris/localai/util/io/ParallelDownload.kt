package com.iris.localai.util.io

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.DownloadSources
import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分块下载 + sha256 校验（大模型下载慢、断线、文件坏）。
 *
 * `<target>.part` 仍然只是「已下完的连续前缀」，所以续传、换源、进度和旧版完全一样；
 * 前缀之后的块由 [threads] 个连接并行下到 `<target>.part.s<起点>`，块按顺序拼回 `.part`。
 * 每块单独按字节数校验、单独重试（坏块只重下这一块）；整文件下完后有 sha256（HF `X-Linked-Etag`）就校验，
 * 不符则删掉重下（[HttpDownload.ChecksumMismatch]，由 [com.iris.localai.data.DownloadSources.fetchAny] 重试）。
 * 源不支持 Range、文件太小、或 `#bytes=` 片段下载 → 退回单线程 [HttpDownload.fetchOnce]。
 */
object ParallelDownload {
    const val CHUNK = 16L shl 20
    const val MIN_SIZE = 64L shl 20
    const val THREADS = 4
    /** 前缀之后最多领先多少块（限制临时块文件占用）。 */
    const val AHEAD = 4
    private const val CHUNK_TRIES = 4
    private const val BUFFER = 256 * 1024
    private const val REPORT_EVERY = 1L shl 20

    fun segFile(target: File, start: Long) = File(target.path + ".part.s" + start)

    /** [offset] 到 [expected] 按 [chunk] 切块（左闭右开）。纯函数。 */
    fun chunks(offset: Long, expected: Long, chunk: Long = CHUNK): List<LongRange> {
        if (chunk <= 0 || offset >= expected) return emptyList()
        val out = ArrayList<LongRange>()
        var a = offset
        while (a < expected) { val b = minOf(a + chunk, expected); out += a until b; a = b }
        return out
    }

    suspend fun fetch(
        url0: String, target: File, expected: Long, onBytes: (Long) -> Unit,
        threads: Int = THREADS, chunk: Long = CHUNK, minSize: Long = MIN_SIZE,
    ) {
        if (HttpDownload.splitRange(url0) != null || expected < minSize || threads <= 1 || expected <= 0) {
            HttpDownload.fetchOnce(url0, target, expected, onBytes); return
        }
        // 探测：1 字节 Range，拿到总大小、是否支持分段和 sha256
        val (probe, sha) = HttpDownload.openMeta(url0, 0, 0)
        val ranged: Boolean
        try {
            val code = probe.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw DownloadSources.NotFound(I18n.s(R.string.util_89463f))
            if (code == 401 || code == 403) throw DownloadSources.NotFound(I18n.s(R.string.util_795bfa, code))
            ranged = code == HttpURLConnection.HTTP_PARTIAL
            if (ranged) {
                val total = HttpDownload.remoteTotal(probe.getHeaderField("Content-Range"), -1L, true, 0)
                if (total != null && total != expected) throw DownloadSources.SizeMismatch(I18n.s(R.string.util_cb7e72, total, expected))
            }
        } finally {
            probe.disconnect()
        }
        if (!ranged) { HttpDownload.fetchOnce(url0, target, expected, sha, onBytes); return }

        val part = HttpDownload.partFile(target)
        if (part.exists() && part.length() > expected) part.delete()
        if (!part.exists()) part.createNewFile()
        val offset = part.length()
        val plan = chunks(offset, expected, chunk)
        // 清掉不在本次切块里的旧块文件
        val starts = plan.map { it.first }.toSet()
        target.parentFile?.listFiles { f -> f.name.startsWith(target.name + ".part.s") }?.forEach { f ->
            val st = f.name.substringAfterLast(".part.s").toLongOrNull()
            if (st == null || st !in starts) f.delete()
        }
        val progress = AtomicLong(offset + plan.sumOf { r -> segFile(target, r.first).takeIf { it.exists() }?.length()?.coerceAtMost(r.last - r.first + 1) ?: 0L })
        val lastReport = AtomicLong(progress.get())
        val throttle = ProgressThrottle()
        onBytes(progress.get())
        val done = BooleanArray(plan.size)
        var nextAppend = 0
        val appended = AtomicLong(offset)
        val mutex = Mutex()
        val next = AtomicInteger(0)

        suspend fun flush() = mutex.withLock {
            while (nextAppend < plan.size && done[nextAppend]) {
                val r = plan[nextAppend]
                val seg = segFile(target, r.first)
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(r.first)
                    seg.inputStream().use { ins ->
                        val buf = ByteArray(BUFFER)
                        while (true) { val n = ins.read(buf); if (n < 0) break; raf.write(buf, 0, n) }
                    }
                    raf.setLength(r.last + 1)
                }
                seg.delete()
                appended.set(r.last + 1)
                nextAppend++
            }
        }

        suspend fun one(r: LongRange) {
            val seg = segFile(target, r.first)
            val len = r.last - r.first + 1
            var failures = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (seg.exists() && seg.length() > len) seg.delete()
                val have = if (seg.exists()) seg.length() else 0L
                if (have == len) return
                try {
                    val conn = HttpDownload.open(url0, r.first + have, r.last)
                    try {
                        if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) throw IOException(I18n.s(R.string.util_7f9f79, url0))
                        RandomAccessFile(seg, "rw").use { raf ->
                            raf.seek(have)
                            var written = have
                            conn.inputStream.use { ins ->
                                val buf = ByteArray(BUFFER)
                                while (written < len) {
                                    currentCoroutineContext().ensureActive()
                                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), len - written).toInt())
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    written += n
                                    val p = progress.addAndGet(n.toLong())
                                    if (p - lastReport.get() >= REPORT_EVERY && throttle.ready()) { lastReport.set(p); onBytes(p) }
                                }
                            }
                            if (written != len) throw IOException(I18n.s(R.string.util_8b8d88, written, len, seg.name))
                        }
                    } finally {
                        conn.disconnect()
                    }
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // 坏块 / 断线：只重下这一块（续上已有字节），几次都失败才交给上层换源
                    if (++failures >= CHUNK_TRIES) throw e
                    delay(1000L * failures)
                }
            }
        }

        coroutineScope {
            repeat(minOf(threads, plan.size)) {
                launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= plan.size) break
                        while (plan[i].first >= appended.get() + AHEAD * chunk * threads) delay(100)
                        one(plan[i])
                        mutex.withLock { done[i] = true }
                        flush()
                    }
                }
            }
        }
        flush()
        if (part.length() != expected) throw IOException(I18n.s(R.string.util_8b8d88, part.length(), expected, target.name))
        HttpDownload.verify(part, sha)
        if (!part.renameTo(target)) throw IOException(I18n.s(R.string.util_310b87, target.name))
        onBytes(expected)
    }
}

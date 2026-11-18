package com.iris.localai.util.io

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.DownloadSources
import com.iris.localai.util.*
import com.iris.localai.util.diagnostics.*
import com.iris.localai.util.streaming.*
import com.iris.localai.util.thinking.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable single-file HTTP download shared by the voice packs and the NPU runtime.
 * Data goes to `<target>.part` and is renamed only once it has exactly the expected size, so a
 * half-written file is never mistaken for a complete one.
 */
object HttpDownload {
    private const val PART = ".part"
    private const val BUFFER = 256 * 1024
    private const val REPORT_EVERY = 1L shl 20

    fun partFile(target: File) = File(target.path + PART)

    /** `url#bytes=a-b` → (url, a, b): fetch only that byte range of the remote file. */
    fun splitRange(url: String): Triple<String, Long, Long>? {
        val m = Regex("#bytes=(\\d+)-(\\d+)$").find(url) ?: return null
        return Triple(url.substring(0, m.range.first), m.groupValues[1].toLong(), m.groupValues[2].toLong())
    }

    /** One attempt (callers own the retry / mirror policy). [onBytes] gets the bytes on disk so far. */
    suspend fun fetchOnce(url0: String, target: File, expected: Long, onBytes: (Long) -> Unit) = fetchOnce(url0, target, expected, null, onBytes)

    /** [sha256] (lower-case hex) = check the whole file before it is renamed; null = size only. */
    suspend fun fetchOnce(url0: String, target: File, expected: Long, sha256: String?, onBytes: (Long) -> Unit) {
        val range = splitRange(url0)
        val url = range?.first ?: url0
        val base = range?.second ?: 0L
        val part = partFile(target)
        if (part.exists() && part.length() > expected) part.delete()
        val offset = if (part.exists()) part.length() else 0L
        if (expected > 0 && offset == expected) { // finished earlier, rename failed / was interrupted
            verify(part, sha256)
            if (!part.renameTo(target)) throw IOException(I18n.s(R.string.util_310b87, target.name))
            onBytes(expected)
            return
        }
        val conn = open(url, base + offset, range?.third ?: -1L)
        try {
            val code = conn.responseCode
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw DownloadSources.NotFound(I18n.s(R.string.util_89463f))
            if (code == 401 || code == 403) throw DownloadSources.NotFound(I18n.s(R.string.util_795bfa, code))
            if (code != HttpURLConnection.HTTP_OK && !resumed) throw IOException(I18n.s(R.string.util_19cb06, code, url))
            if (range != null && !resumed) throw IOException(I18n.s(R.string.util_7f9f79, url))
            // a source holding another version of the file is skipped before anything is written.
            if (range == null && expected > 0) {
                val total = remoteTotal(conn.getHeaderField("Content-Range"), conn.contentLengthLong, resumed, offset)
                if (total != null && total != expected) throw DownloadSources.SizeMismatch(I18n.s(R.string.util_cb7e72, total, expected))
            }
            RandomAccessFile(part, "rw").use { raf ->
                var written = if (resumed) offset else 0L // 200 = server ignored Range: start over
                raf.setLength(written)
                raf.seek(written)
                conn.inputStream.use { ins ->
                    val buf = ByteArray(BUFFER)
                    var lastReport = written
                    val throttle = ProgressThrottle()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n
                        if (written - lastReport >= REPORT_EVERY && throttle.ready()) { lastReport = written; onBytes(written) }
                    }
                }
                if (written != expected) throw IOException(I18n.s(R.string.util_8b8d88, written, expected, target.name))
            }
        } finally {
            conn.disconnect()
        }
        verify(part, sha256)
        if (!part.renameTo(target)) throw IOException(I18n.s(R.string.util_310b87, target.name))
        onBytes(expected)
    }

    /** the whole file did not hash to the source's sha256: the `.part` is deleted so the next try starts clean. */
    class ChecksumMismatch(msg: String) : IOException(msg)

    /** sha256 of [part] against [sha256] (null = skip). A mismatch deletes [part] and throws [ChecksumMismatch]. */
    suspend fun verify(part: File, sha256: String?) {
        if (sha256 == null) return
        val got = sha256Of(part)
        if (!got.equals(sha256, ignoreCase = true)) {
            part.delete()
            throw ChecksumMismatch(I18n.s(R.string.v256_sha_bad, part.name.removeSuffix(PART)))
        }
    }

    suspend fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** HF / hf-mirror put the LFS sha256 in `X-Linked-Etag` of the resolve hop (CDN ETags may be other hashes: never used); anything else → null. Pure. */
    fun shaFromEtag(v: String?): String? = v?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { HEX64.matches(it) }?.lowercase()

    /** full size of the remote file from a 206 Content-Range or a 200 Content-Length; null = unknown. Pure. */
    fun remoteTotal(contentRange: String?, contentLength: Long, partial: Boolean, offset: Long): Long? {
        if (partial) {
            val t = contentRange?.substringAfterLast('/', "")?.trim()
            return t?.toLongOrNull() ?: contentLength.takeIf { it >= 0 }?.plus(offset)
        }
        return contentLength.takeIf { it >= 0 }
    }

    /**
     * GET with manual redirect handling (so a Range header survives cross-host redirects).
     * read timeout = 20 s without bytes (a stall → the caller moves to the next source).
     */
    fun open(start: String, rangeFrom: Long, rangeTo: Long = -1L, maxRedirects: Int = 8, timeoutMs: Int = 0): HttpURLConnection =
        openMeta(start, rangeFrom, rangeTo, maxRedirects, timeoutMs).first

    /** [open] plus the sha256 any hop advertised ([shaFromEtag]). */
    fun openMeta(start: String, rangeFrom: Long, rangeTo: Long = -1L, maxRedirects: Int = 8, timeoutMs: Int = 0): Pair<HttpURLConnection, String?> {
        var url = start
        var sha: String? = null
        repeat(maxRedirects) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = if (timeoutMs > 0) timeoutMs else 15_000
                readTimeout = if (timeoutMs > 0) timeoutMs else DownloadSources.STALL_MS
                setRequestProperty("User-Agent", "IRIS-LocalAI/1.0")
                if (rangeTo >= 0) setRequestProperty("Range", "bytes=$rangeFrom-$rangeTo")
                else if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = try { c.responseCode } catch (e: IOException) { c.disconnect(); throw e }
            if (sha == null) sha = shaFromEtag(c.getHeaderField("X-Linked-Etag"))
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                url = URL(URL(url), loc ?: throw IOException(I18n.s(R.string.util_fcc7a6))).toString()
            } else {
                return c to sha
            }
        }
        throw IOException(I18n.s(R.string.util_9b8100))
    }
}

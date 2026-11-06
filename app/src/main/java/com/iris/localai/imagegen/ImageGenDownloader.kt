package com.iris.localai.imagegen

import com.iris.localai.data.DownloadSource
import com.iris.localai.data.DownloadSources
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Download state of one 生图 model (absent from the map = idle). */
sealed interface ImageDl {
    data class Downloading(val done: Long, val total: Long, val file: String, val source: String = "") : ImageDl {
        val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }
    /** 解压中: [done] / [total] uncompressed bytes. */
    data class Extracting(val done: Long, val total: Long) : ImageDl {
        val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }
    data class Failed(val message: String) : ImageDl
}

/** One resumable fetch attempt of [url] into [target] (via `<target>.part`); [onBytes] = bytes on disk. */
typealias Fetcher = suspend (url: String, target: File, expected: Long, onBytes: (Long) -> Unit) -> Unit

/**
 * Zip extraction for model archives: entries go into `dest/` with a single shared top-level folder
 * flattened away; each file is written to `<name>.part` and renamed when complete. Rejects zip-slip.
 */
object ZipExtract {
    /** The one top-level folder every entry sits in, else null. */
    fun commonTopDir(names: List<String>): String? {
        val files = names.filter { !it.endsWith("/") }
        if (files.isEmpty() || files.any { '/' !in it }) return null
        val tops = names.map { it.substringBefore('/') }.toSet()
        return tops.singleOrNull()?.takeIf { it != "." && it != ".." && it.isNotEmpty() }
    }

    /** [name] relative to `dest` after dropping [top] (null for the folder entry itself). */
    fun flattened(name: String, top: String?): String? {
        val rel = if (top != null) name.removePrefix("$top/") else name
        return rel.takeIf { it.isNotEmpty() && !it.endsWith("/") }
    }

    /** Extracts [zip] into [dest]; [onProgress] gets (uncompressed bytes done, total). Returns the files written. */
    suspend fun extract(zip: File, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): List<File> {
        val out = mutableListOf<File>()
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val top = commonTopDir(entries.map { it.name })
            val total = entries.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
            val root = dest.canonicalFile
            var done = 0L
            onProgress(0, total)
            val buf = ByteArray(256 * 1024)
            for (e in entries) {
                val rel = flattened(e.name, top) ?: continue
                if (e.isDirectory) continue
                val f = File(root, rel).canonicalFile
                if (!f.path.startsWith(root.path + File.separator)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_36db28, e.name))
                f.parentFile?.mkdirs()
                val part = File(f.path + ".part")
                var lastReport = done
                zf.getInputStream(e).use { ins ->
                    part.outputStream().use { os ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= (4L shl 20)) { lastReport = done; onProgress(done, total) }
                        }
                    }
                }
                f.delete()
                if (!part.renameTo(f)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.util_310b87, f.name))
                out += f
            }
            onProgress(total, total)
        }
        return out
    }
}

/** 2.2.12: one zip entry cut out of a remote zip by byte range ([RemoteFile.isZipSlice]). */
object ZipSlice {
    /**
     * [slice] starts with the entry's local file header; inflates (or copies, method 0) its data into [out]
     * via `<out>.part`, then checks size and CRC-32 ([crc] < 0 skips the CRC check).
     */
    suspend fun extract(slice: File, out: File, size: Long, crc: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        out.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        java.io.RandomAccessFile(slice, "r").use { raf ->
            val h = ByteArray(30); raf.readFully(h)
            fun u16(i: Int) = (h[i].toInt() and 0xFF) or ((h[i + 1].toInt() and 0xFF) shl 8)
            if (u16(0) != 0x4b50 || u16(2) != 0x0403) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_371496, slice.name))
            val method = u16(8)
            if (method != 0 && method != 8) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_0777c1, method, slice.name))
            raf.seek(30L + u16(26) + u16(28))
            val raw = java.io.BufferedInputStream(java.io.FileInputStream(raf.fd), 256 * 1024)
            val ins = if (method == 8) java.util.zip.InflaterInputStream(raw, java.util.zip.Inflater(true), 256 * 1024) else raw
            val sum = java.util.zip.CRC32()
            var done = 0L; var last = 0L
            val buf = ByteArray(256 * 1024)
            onProgress(0, size)
            part.outputStream().use { os ->
                while (done < size) {
                    currentCoroutineContext().ensureActive()
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
                    if (n < 0) break
                    os.write(buf, 0, n); sum.update(buf, 0, n); done += n
                    if (done - last >= (4L shl 20)) { last = done; onProgress(done, size) }
                }
            }
            if (done != size) { part.delete(); throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_1d5bf5, done, size, out.name)) }
            if (crc >= 0 && sum.value != crc) { part.delete(); throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_65c161, out.name)) }
        }
        out.delete()
        if (!part.renameTo(out)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.util_310b87, out.name))
        onProgress(size, size)
    }
}

/** Pure download rules (unit-tested). */
object ImageDownloads {

    /** Urls to try for [url] before probing (2.2.16: 魔搭 when mapped → hf-mirror → HF); non-HF urls as they are. */
    fun urlsFor(url: String, source: DownloadSource): List<String> {
        val ref = DownloadSources.parseHf(url)
        val order = DownloadSources.order(source, ref != null && DownloadSources.modelScopeFor(ref.repo) != null)
        return DownloadSources.candidates(url, order).map { it.url }
    }

    /** Bytes already on disk for [f] (complete file, its `.part`, or done because the archive is unpacked). */
    fun have(root: File, model: ImageModel, f: RemoteFile): Long {
        if (f.isArchive && unpacked(root, model, f)) return f.size
        val target = File(root, f.path)
        if (target.isFile && target.length() == f.size) return f.size
        return HttpDownload.partFile(target).takeIf { it.isFile }?.length()?.coerceAtMost(f.size) ?: 0L
    }

    fun unpacked(root: File, model: ImageModel, f: RemoteFile): Boolean {
        val dir = ImageGenFiles.modelDir(root, model)
        return f.unpacked.all { u -> File(dir, u.path).let { it.isFile && it.length() == u.size } }
    }

    /** Free bytes needed to finish: the rest of the downloads plus archives still to unpack, + 256 MB headroom. */
    fun bytesNeeded(root: File, model: ImageModel): Long =
        model.files.sumOf { f ->
            val rest = f.size - have(root, model, f)
            rest + if (f.isArchive && !unpacked(root, model, f)) f.unpacked.sumOf { it.size } else 0L
        } + 256L * 1024 * 1024

    /**
     * Downloads every file of [model] into [root] (resumable), unpacks archives into the model folder
     * and deletes them, then removes the `.part` scratch folder. [fetch] performs one attempt.
     */
    suspend fun download(
        root: File,
        model: ImageModel,
        source: DownloadSource,
        fetch: Fetcher,
        publish: (ImageDl) -> Unit,
        retryDelayMs: Long = 1500,
        demote: DownloadSource? = null,
        probe: (String) -> Long? = DownloadSources::probeOnce,
        onSource: (DownloadSource?) -> Unit = {},
    ) {
        if (model.files.isEmpty()) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_ca539c))
        val dir = ImageGenFiles.modelDir(root, model).apply { mkdirs() }
        val free = dir.usableSpace
        val need = bytesNeeded(root, model)
        if (free in 1 until need) {
            throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_9c1de2, com.iris.localai.data.Format.bytes(need), com.iris.localai.data.Format.bytes(free)))
        }
        val total = model.files.sumOf { it.size }
        var done = model.files.sumOf { have(root, model, it) }
        publish(ImageDl.Downloading(done, total, ""))
        for (f in model.files) {
            currentCoroutineContext().ensureActive()
            if (f.isArchive && unpacked(root, model, f)) continue
            val target = File(root, f.path).apply { parentFile?.mkdirs() }
            if (!(target.isFile && target.length() == f.size)) {
                val name = f.path.substringAfterLast('/')
                val before = done - have(root, model, f)
                var via = ""
                DownloadSources.fetchAny(DownloadSources.resolve(f.fetchUrl, source, demote, probe), target, f.size, { got ->
                    done = before + got
                    publish(ImageDl.Downloading(done, total, name, via))
                }, fetch, onSource = { c -> via = c.source?.short ?: ""; onSource(c.source) }, retryDelayMs = retryDelayMs)
                done = before + f.size
            }
            if (f.isArchive) {
                publish(ImageDl.Extracting(0, f.unpacked.sumOf { it.size }))
                if (f.isZipSlice) ZipSlice.extract(target, File(dir, f.unpacked.single().path), f.unpacked.single().size, f.crc) { d, t -> publish(ImageDl.Extracting(d, t)) }
                else ZipExtract.extract(target, dir) { d, t -> publish(ImageDl.Extracting(d, t)) }
                if (!unpacked(root, model, f)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_ac40fd, target.name))
                target.delete()
                publish(ImageDl.Downloading(done, total, ""))
            }
        }
        File(dir, ".part").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    }


    /** Removes the model folder from every root. */
    fun delete(roots: List<File>, model: ImageModel, others: List<ImageModel> = ImageCatalog.known): Boolean {
        val ok = roots.map { ImageGenFiles.modelDir(it, model) }.filter { it.exists() }.all { it.deleteRecursively() }
        // 2.4.0: a shared file (the Qwen3-4B text encoder) goes only when no other downloaded model uses it.
        for (root in roots) for (path in model.sharedPaths) {
            val usedElsewhere = others.any { o -> o.id != model.id && path in o.sharedPaths && ImageGenFiles.modelDir(root, o).exists() }
            if (!usedElsewhere) File(root, path).delete()
        }
        return ok
    }
}

/**
 * App-scoped 生图 model downloads (like MnnHub: jobs live in AppContainer.scope, so they keep going
 * when the 生图 page is closed). One job per model; [states] drives the page's progress bars.
 */
class ImageGenDownloader(private val scope: CoroutineScope, private val fetch: Fetcher = { u, t, e, b -> HttpDownload.fetchOnce(u, t, e, b) }) {
    private val _states = MutableStateFlow<Map<String, ImageDl>>(emptyMap())
    val states: StateFlow<Map<String, ImageDl>> = _states.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    /** 2.4.4: id of the last model whose download finished (for the 「下载完成」 notification). */
    @Volatile var lastFinished: String? = null; private set

    fun isActive(id: String): Boolean = synchronized(jobs) { jobs[id]?.isActive == true }

    /** Source each model's download was last using (换源重试 moves it to the end). */
    private val lastSource = java.util.concurrent.ConcurrentHashMap<String, DownloadSource>()

    fun start(model: ImageModel, root: File, source: DownloadSource, demote: DownloadSource? = null, onDone: () -> Unit = {}): Job? = synchronized(jobs) {
        if (jobs[model.id]?.isActive == true) return null
        val job = scope.launch(Dispatchers.IO) {
            try {
                downloadInto(root, model, source, demote)
                lastFinished = model.id
                _states.update { it - model.id }
                onDone()
            } catch (e: CancellationException) {
                _states.update { it - model.id }
                throw e
            } catch (e: Throwable) {
                _states.update { it + (model.id to ImageDl.Failed(e.message ?: com.iris.localai.I18n.s(com.iris.localai.R.string.data_65e200))) }
            }
        }
        jobs[model.id] = job
        job
    }

    /** 2.2.16 「换源重试」: the failed download again with the source it was using moved last. */
    fun retryOtherSource(model: ImageModel, root: File, source: DownloadSource, onDone: () -> Unit = {}): Job? =
        start(model, root, source, lastSource[model.id] ?: DownloadSource.HF_MIRROR, onDone)

    private suspend fun downloadInto(root: File, model: ImageModel, source: DownloadSource, demote: DownloadSource?) {
        val publish: (ImageDl) -> Unit = { s -> _states.update { it + (model.id to s) } }
        if (model.files.isEmpty()) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_ca539c))
        ImageDownloads.download(root, model, source, fetch, publish, demote = demote, onSource = { s -> if (s != null) lastSource[model.id] = s })
    }

    fun cancel(id: String) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        _states.update { it - id }
    }

    fun clearError(id: String) = _states.update { m -> if (m[id] is ImageDl.Failed) m - id else m }

    /** Cancels a running download, then deletes the model folder (downloaded files and partials) from [roots]. */
    suspend fun delete(model: ImageModel, roots: List<File>): Boolean {
        val job = synchronized(jobs) { jobs.remove(model.id) }
        job?.cancel(); job?.join()
        _states.update { it - model.id }
        return withContext(Dispatchers.IO) { ImageDownloads.delete(roots, model) }
    }
}

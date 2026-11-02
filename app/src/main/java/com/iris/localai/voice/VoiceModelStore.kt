package com.iris.localai.voice

import android.content.Context
import android.os.StatFs
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.Format
import com.iris.localai.util.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** CLONE = the ZipVoice zero-shot model: never a voice by itself, it speaks with a saved reference voice. */
enum class VoiceKind { ASR, TTS, CLONE }

/** A downloadable voice model: a pinned Hugging Face repo revision and the files we need from it. */
enum class VoicePack(val id: String, val kind: VoiceKind, val title: String, val blurb: String) {
    ASR_SENSEVOICE("sense-voice-int8", VoiceKind.ASR, "SenseVoice Small", "中/英/粤/日/韩 · 最快，日常推荐"),
    ASR_PARAFORMER("paraformer-zh-int8", VoiceKind.ASR, "Paraformer 中文", "达摩院 · 普通话和中英混说更准"),
    ASR_QWEN3("qwen3-asr-0.6b-int8", VoiceKind.ASR, "Qwen3-ASR 0.6B", "旗舰精度 · 方言与多语种 · 较慢，约占 1.2 GB 内存"),
    ASR_ZIPFORMER_CTC("zipformer-ctc-zh-int8-2025-07-03", VoiceKind.ASR, "Zipformer CTC 中文", "k2 新一代 · 纯普通话，识别快而准 · 输出不带标点"),
    TTS_KOKORO("kokoro-int8-multi-lang-v1_1", VoiceKind.TTS, "Kokoro 中英 · 标准", "103 种音色 · 合成最快"),
    TTS_KOKORO_HQ("kokoro-multi-lang-v1_1", VoiceKind.TTS, "Kokoro 中英 · 高音质", "同样 103 种音色 · 全精度，更清晰自然"),
    TTS_MELO("vits-melo-tts-zh_en", VoiceKind.TTS, "MeloTTS 中英混读", "一个自然女声 · 中英夹杂读得最顺"),
    TTS_PIPER_XIAOYA("vits-piper-zh_CN-xiao_ya-medium", VoiceKind.TTS, "Piper 小雅 · 极速", "中文女声 · 开口最快、最省电（仅中文）"),
    TTS_MATCHA_BAKER("matcha-icefall-zh-baker", VoiceKind.TTS, "Matcha 标贝 · 极速", "中文女声 · 和 Piper 一样快，语调更自然（仅中文）"),
    CLONE_ZIPVOICE("zipvoice-distill-int8-zh-en", VoiceKind.CLONE, "ZipVoice 音色克隆", "中英 · 用你录的 5–15 秒声音说话");

    /** Single-voice packs: the speaker id is ignored. */
    val singleSpeaker: Boolean get() = this != TTS_KOKORO && this != TTS_KOKORO_HQ

    val manifestAsset: String get() = "voice/$id.json"

    companion object {
        fun byId(id: String): VoicePack? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Pack manifest (bundled asset). Files normally come from a pinned Hugging Face revision through
 * the chosen mirror; an entry with [Entry.url] is fetched from that absolute URL instead (GitHub
 * releases, for files not on Hugging Face) and is then checked against its SHA-256. A pack may
 * also ship as one [Archive] (.tar.bz2), downloaded resumably, verified, then unpacked.
 */
@Serializable
internal data class Manifest(
    val repo: String = "",
    val revision: String = "",
    val files: List<Entry> = emptyList(),
    val archive: Archive? = null,
) {
    @Serializable
    data class Entry(val path: String, val size: Long, val url: String? = null, val sha256: String? = null)

    @Serializable
    data class Archive(
        val url: String,
        val size: Long,
        val sha256: String,
        /** Leading directory inside the tarball, removed on extraction ("name/"). */
        val strip: String = "",
        /** Paths (after [strip]) starting with any of these are skipped (test wavs, READMEs). */
        val skip: List<String> = emptyList(),
        /** Files that must exist after extraction. */
        val required: List<String> = emptyList(),
    )

    /** Bytes to download. */
    val totalBytes: Long get() = files.sumOf { it.size } + (archive?.size ?: 0L)
}

sealed interface PackState {
    data object NotInstalled : PackState
    data class Downloading(val downloaded: Long, val total: Long) : PackState {
        val fraction: Float get() = if (total > 0) (downloaded.toDouble() / total).toFloat() else 0f
    }
    /** Archive downloaded; unpacking (no byte progress). */
    data object Extracting : PackState
    data object Installed : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Downloads voice models file-by-file from a pinned revision (resumable with HTTP Range,
 * verified by size). A pack is usable only after every file is complete and a marker is
 * written, so a half-finished download is never loaded.
 * 2.1.1: like the text models ([com.iris.localai.data.ModelDirs]) new packs go to the public
 * `Download/IRIDIS/voice` when 全部文件访问 is granted, else to the app-specific `files/voice`;
 * an installed pack is found in either ([VoiceDirs]).
 */
class VoiceModelStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    /** App-specific folder (pre-2.1.1 location, and the fallback without 全部文件访问). */
    val privateRoot: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "voice").apply { mkdirs() }

    /** Re-evaluated on every call, so granting 全部文件访问 later switches new downloads to the shared folder. */
    fun location(): com.iris.localai.data.ModelDirs.Location = com.iris.localai.data.ModelDirs.resolve(
        allFilesAccess = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false),
        storageState = runCatching { android.os.Environment.getExternalStorageState() }.getOrNull(),
        downloadsDir = runCatching { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
        privateDir = privateRoot,
        sub = com.iris.localai.agent.StorageLayout.VOICE,
    )

    /** Where new packs are written right now. */
    val root: File get() = location().dir

    private val _states = MutableStateFlow(VoicePack.entries.associateWith { stateOnDisk(it) })
    val states: StateFlow<Map<VoicePack, PackState>> = _states.asStateFlow()

    /** The installed copy of [pack] (shared or private), else where a download of it goes now. */
    fun dir(pack: VoicePack): File = location().let { VoiceDirs.packDir(it.listDirs, it.dir, pack.id, MARKER) }
    fun isInstalled(pack: VoicePack) = File(dir(pack), MARKER).exists()

    /**
     * The pack of [kind] to use: the user's choice if installed, else the first installed one.
     * Answered from the in-memory state (kept in sync by download/delete), so UI code can call
     * it freely without touching the disk.
     */
    fun active(kind: VoiceKind, preferredId: String): VoicePack? {
        val st = _states.value
        fun ok(p: VoicePack) = p.kind == kind && st[p] == PackState.Installed
        return VoicePack.byId(preferredId)?.takeIf(::ok) ?: VoicePack.entries.firstOrNull(::ok)
    }
    fun totalBytes(pack: VoicePack): Long = manifest(pack).totalBytes

    private val manifests = java.util.concurrent.ConcurrentHashMap<VoicePack, Manifest>()

    /** Bundled asset, parsed once. */
    private fun manifest(pack: VoicePack): Manifest = manifests.getOrPut(pack) {
        context.assets.open(pack.manifestAsset).use { json.decodeFromString(Manifest.serializer(), it.readBytes().decodeToString()) }
    }

    private fun stateOnDisk(pack: VoicePack): PackState =
        if (isInstalled(pack)) PackState.Installed else PackState.NotInstalled

    suspend fun download(pack: VoicePack, source: DownloadSource) = withContext(Dispatchers.IO) {
        this@VoiceModelStore.source = source
        val m = manifest(pack)
        val dir = dir(pack).apply { mkdirs() }
        val free = StatFs(dir.absolutePath).availableBytes
        val archiveFile = File(dir, ARCHIVE)
        val have = m.files.sumOf { e -> File(dir, e.path).takeIf { it.exists() }?.length() ?: 0L } +
            (if (m.archive != null) (HttpDownload.partFile(archiveFile).takeIf { it.exists() }?.length() ?: 0L) else 0L)
        // An archive needs room for itself plus its unpacked content (≈1.6× for these models).
        val need = m.totalBytes - have + (m.archive?.size?.times(16)?.div(10) ?: 0L) + 64L * 1024 * 1024
        try {
            if (free < need) {
                throw IOException("存储空间不足：需要 ${Format.bytes(need)}，可用 ${Format.bytes(free)}")
            }
            var done = have
            publish(pack, PackState.Downloading(done, m.totalBytes))
            for (entry in m.files) {
                coroutineContext.ensureActive()
                val target = File(dir, entry.path)
                if (target.exists() && target.length() == entry.size) continue
                target.parentFile?.mkdirs()
                val url = entry.url ?: "${com.iris.localai.data.DownloadSources.HF}/${m.repo}/resolve/${m.revision}/${encodePath(entry.path)}"
                val before = done - (HttpDownload.partFile(target).takeIf { it.exists() }?.length() ?: 0L)
                fetch(url, target, entry.size) { got ->
                    done = before + got
                    publish(pack, PackState.Downloading(done, m.totalBytes))
                }
                entry.sha256?.let { verify(target, it) }
                done = before + entry.size
            }
            m.archive?.let { a ->
                if (!File(dir, ARCHIVE_DONE).exists()) {
                    val before = done - (HttpDownload.partFile(archiveFile).takeIf { it.exists() }?.length() ?: 0L)
                    if (!(archiveFile.exists() && archiveFile.length() == a.size)) { // killed while unpacking: reuse it
                        fetch(a.url, archiveFile, a.size) { got ->
                            done = before + got
                            publish(pack, PackState.Downloading(done, m.totalBytes))
                        }
                    }
                    publish(pack, PackState.Extracting)
                    verify(archiveFile, a.sha256)
                    TarBz2.extract(archiveFile, dir, a.strip, a.skip)
                    val missing = a.required.filterNot { File(dir, it).exists() }
                    if (missing.isNotEmpty()) throw IOException("解压后缺少文件：${missing.joinToString()}")
                    File(dir, ARCHIVE_DONE).writeText(a.sha256)
                    archiveFile.delete()
                }
            }
            File(dir, MARKER).writeText(m.revision.ifEmpty { "1" })
            publish(pack, PackState.Installed)
        } catch (t: Throwable) {
            publish(pack, if (t is kotlinx.coroutines.CancellationException) stateOnDisk(pack) else PackState.Failed(t.message ?: "下载失败"))
            throw t
        }
    }

    /** A corrupt (or tampered-with) file is deleted so the next attempt fetches it again. */
    private fun verify(file: File, sha256: String) {
        val got = Sha256.of(file)
        if (!got.equals(sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("${file.name} 校验失败，请重试下载")
        }
    }

    fun delete(pack: VoicePack) {
        publish(pack, PackState.NotInstalled) // first, so nothing picks the pack while it is being removed
        location().listDirs.forEach { File(it, pack.id).deleteRecursively() }
    }

    private fun publish(pack: VoicePack, s: PackState) = _states.update { it + (pack to s) }

    /** 2.2.16: Hugging Face files go through the download sources (自动 / 魔搭 / hf-mirror / HF, falling through). */
    @Volatile private var source: DownloadSource = DownloadSource.AUTO

    private suspend fun fetch(url: String, target: File, expected: Long, onBytes: (Long) -> Unit) {
        com.iris.localai.data.DownloadSources.fetchAny(
            com.iris.localai.data.DownloadSources.resolve(url, source), target, expected, onBytes,
            fetch = { u, t, e, b -> HttpDownload.fetchOnce(u, t, e, b) }, attemptsPerSource = 3,
        )
    }

    internal companion object {
        /** Percent-encodes each path segment (file names such as "!v/Mr serious" contain spaces). */
        fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

        const val MARKER = ".complete"
        const val ARCHIVE = ".archive.tar.bz2"
        const val ARCHIVE_DONE = ".archive-extracted"
        const val PART = ".part"
    }
}

/** 2.1.1: where a voice pack lives across the shared and private folders. Pure, unit-tested. */
object VoiceDirs {
    /** First folder of [dirs] with a complete [id] (its [marker] present), else `writeDir/id`. */
    fun packDir(dirs: List<File>, writeDir: File, id: String, marker: String): File =
        dirs.map { File(it, id) }.firstOrNull { File(it, marker).exists() } ?: File(writeDir, id)
}

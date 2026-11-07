package com.iris.localai.voice

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * 2.4.5: text-to-speech models the user imports in sherpa-onnx format (a folder, a .tar.bz2, or a
 * download link). The type is read from the files: `voices.bin` = Kokoro, a vocoder `.onnx`
 * (vocos / hifigan) = Matcha, otherwise one `.onnx` with `lexicon.txt` + `tokens.txt` or
 * `espeak-ng-data` = VITS (Piper / MeloTTS / …). GPT-SoVITS and RVC are not sherpa-onnx models;
 * an original Piper voice (`.onnx` + `.onnx.json`, no tokens.txt) has to be converted first.
 */
enum class CustomTtsType(val label: String) { KOKORO("Kokoro"), MATCHA("Matcha"), VITS("VITS / Piper") }

/** File names inside the model folder (relative), as handed to sherpa-onnx. */
@Serializable
data class CustomTtsSpec(
    val type: CustomTtsType,
    val model: String,
    val tokens: String,
    val vocoder: String = "",
    val voices: String = "",
    val lexicons: List<String> = emptyList(),
    val dataDir: String = "",
    val dictDir: String = "",
    val ruleFsts: List<String> = emptyList(),
)

/** What `iridis-tts.json` in the model folder holds. */
@Serializable
data class CustomTtsModel(val id: String, val name: String, val spec: CustomTtsSpec, val bytes: Long = 0)

/** A model on disk. */
data class InstalledTts(val model: CustomTtsModel, val dir: File) {
    val id: String get() = model.id
    /** Value stored in [com.iris.localai.data.AppSettings.ttsPack] when it is the chosen voice. */
    val packId: String get() = CustomTts.PREFIX + model.id
}

object CustomTts {
    /** [com.iris.localai.data.AppSettings.ttsPack] values of imported models start with this. */
    const val PREFIX = "custom:"
    const val META = "iridis-tts.json"
    const val SUBDIR = "custom"

    fun idOf(packId: String): String? = packId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.ifBlank { null }

    /** Folder / id for a model called [name]: letters, digits, `-_.` only; never empty. Pure. */
    fun slug(name: String): String {
        val s = name.trim().removeSuffix(".tar.bz2").replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(64)
        return s.ifEmpty { "tts" }
    }

    /** [base], or base-2, base-3 … not in [taken]. Pure. */
    fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    /** Speaker id to pass for a model with [numSpeakers] voices (single-voice models: 0; out-of-range ids are pulled in). */
    fun speaker(requested: Int, numSpeakers: Int): Int = if (numSpeakers <= 1) 0 else requested.coerceIn(0, numSpeakers - 1)
}

/** Why a folder / archive is not an importable model. */
enum class TtsReject { EMPTY, NO_MODEL, PIPER_RAW, GPT_SOVITS_RVC, NO_TOKENS, MATCHA_NO_VOCODER, NO_TEXT_FRONTEND, BAD_URL }

sealed interface TtsDetectResult {
    data class Ok(val spec: CustomTtsSpec) : TtsDetectResult
    data class Rejected(val reason: TtsReject) : TtsDetectResult
}

/** Recognises a sherpa-onnx TTS model from its file list. Pure, unit-tested. */
object TtsDetect {
    /** Common leading folder of every path ("vits-piper-x/"), or "" (tarballs wrap the model in one folder). */
    fun commonRoot(paths: Collection<String>): String {
        val clean = paths.map { it.removePrefix("./") }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return ""
        val firsts = clean.map { it.substringBefore('/', "") }
        val head = firsts.first()
        if (head.isEmpty() || firsts.any { it != head }) return ""
        // only folders below it (every path continues past the head)
        return if (clean.all { it.startsWith("$head/") }) "$head/" else ""
    }

    private val WEIGHTS_ONLY = listOf(".pth", ".ckpt", ".index", ".safetensors", ".pt", ".bin")

    /**
     * [paths]: every file of the model, relative ("tokens.txt", "espeak-ng-data/phontab"), after
     * [commonRoot] is removed. [hint]: the folder / archive name (helps name a Matcha model without vocoder).
     */
    fun detect(paths: Collection<String>, hint: String = ""): TtsDetectResult {
        val files = paths.map { it.removePrefix("./") }.filter { it.isNotBlank() && !it.endsWith("/") }
        if (files.isEmpty()) return TtsDetectResult.Rejected(TtsReject.EMPTY)
        val top = files.filter { '/' !in it }
        val dirs = files.filter { '/' in it }.map { it.substringBefore('/') }.toSet()
        val lower = top.associateBy { it.lowercase() }
        val onnx = top.filter { it.lowercase().endsWith(".onnx") }
        if (onnx.isEmpty()) {
            val weights = top.any { n -> WEIGHTS_ONLY.any { n.lowercase().endsWith(it) } && n.lowercase() != "voices.bin" }
            return TtsDetectResult.Rejected(if (weights) TtsReject.GPT_SOVITS_RVC else TtsReject.NO_MODEL)
        }
        val tokens = lower["tokens.txt"]
        if (tokens == null) {
            val piper = onnx.any { o -> lower.containsKey(o.lowercase() + ".json") } || top.any { it.lowercase().endsWith(".onnx.json") }
            return TtsDetectResult.Rejected(if (piper) TtsReject.PIPER_RAW else TtsReject.NO_TOKENS)
        }
        val espeak = if ("espeak-ng-data" in dirs) "espeak-ng-data" else ""
        val dict = if ("dict" in dirs) "dict" else ""
        val lexicons = top.filter { it.lowercase().startsWith("lexicon") && it.lowercase().endsWith(".txt") }.sorted()
        val fsts = top.filter { it.lowercase().endsWith(".fst") }.sorted()
        val voices = lower["voices.bin"]
        if (voices != null) {
            if (espeak.isEmpty()) return TtsDetectResult.Rejected(TtsReject.NO_TEXT_FRONTEND)
            // int8 first: the faster file on a phone (the built-in default Kokoro is the int8 one too)
            val model = onnx.firstOrNull { it.lowercase() == "model.int8.onnx" } ?: onnx.firstOrNull { it.lowercase() == "model.onnx" }
                ?: onnx.firstOrNull { "int8" in it.lowercase() } ?: onnx.first()
            return TtsDetectResult.Ok(CustomTtsSpec(CustomTtsType.KOKORO, model, tokens, voices = voices, lexicons = lexicons, dataDir = espeak, dictDir = dict, ruleFsts = fsts))
        }
        val frontend = lexicons.isNotEmpty() || espeak.isNotEmpty()
        val vocoder = onnx.firstOrNull { n -> VOCODER.any { it in n.lowercase() } }
        if (vocoder != null) {
            val acoustic = onnx.filter { it != vocoder }.let { rest -> rest.firstOrNull { "model" in it.lowercase() || "steps" in it.lowercase() } ?: rest.firstOrNull() }
                ?: return TtsDetectResult.Rejected(TtsReject.NO_MODEL)
            if (!frontend) return TtsDetectResult.Rejected(TtsReject.NO_TEXT_FRONTEND)
            return TtsDetectResult.Ok(CustomTtsSpec(CustomTtsType.MATCHA, acoustic, tokens, vocoder = vocoder, lexicons = mainLexicon(lexicons), dataDir = espeak, dictDir = dict, ruleFsts = fsts))
        }
        if (onnx.any { it.lowercase().startsWith("model-steps") } || "matcha" in hint.lowercase()) return TtsDetectResult.Rejected(TtsReject.MATCHA_NO_VOCODER)
        if (!frontend) return TtsDetectResult.Rejected(TtsReject.NO_TEXT_FRONTEND)
        // fp32 first for VITS (the int8 export was slower on the aarch64 test, see SpeechSynthesizer)
        val model = onnx.firstOrNull { it.lowercase() == "model.onnx" } ?: onnx.firstOrNull { "int8" !in it.lowercase() } ?: onnx.first()
        return TtsDetectResult.Ok(CustomTtsSpec(CustomTtsType.VITS, model, tokens, lexicons = mainLexicon(lexicons), dataDir = espeak, dictDir = dict, ruleFsts = fsts))
    }

    private val VOCODER = listOf("vocos", "hifigan", "vocoder")

    /** VITS / Matcha take one lexicon: `lexicon.txt` when there are several. */
    private fun mainLexicon(l: List<String>): List<String> = if (l.size <= 1) l else listOf(l.firstOrNull { it.lowercase() == "lexicon.txt" } ?: l.first())

    /** A download link we can import: https, ending in .tar.bz2 (query allowed). Null = fine. Pure. */
    fun checkUrl(url: String): TtsReject? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return TtsReject.BAD_URL
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        return if (path.endsWith(".tar.bz2")) null else TtsReject.BAD_URL
    }
}

/** Import progress shown in 设置 › 语音. */
data class TtsImportState(
    val active: Boolean = false,
    /** 0 = copying / downloading, 1 = unpacking, 2 = checking. */
    val phase: Int = 0,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    /** Name of the model the last import added. */
    val added: String? = null,
)

class TtsImportException(val reason: TtsReject) : IOException(reason.name)

/**
 * Imported TTS models: `<voice folder>/custom/<id>/` (shared `Download/IRIDIS/voice` with 全部文件访问,
 * else the app folder — the same rule as the voice packs), each with its `iridis-tts.json`.
 */
class CustomTtsStore(private val context: Context, private val voiceStore: VoiceModelStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun roots(): List<File> = voiceStore.location().listDirs.map { File(it, CustomTts.SUBDIR) }
    private fun writeRoot(): File = File(voiceStore.root, CustomTts.SUBDIR).apply { mkdirs() }

    private val _models = MutableStateFlow<List<InstalledTts>>(emptyList())
    val models: StateFlow<List<InstalledTts>> = _models.asStateFlow()

    private val _import = MutableStateFlow(TtsImportState())
    val importState: StateFlow<TtsImportState> = _import.asStateFlow()
    fun clearImportMessage() { _import.value = TtsImportState() }

    @Volatile private var scanned = false

    fun refresh(): List<InstalledTts> {
        scanned = true
        val list = roots().flatMap { root ->
            root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { d ->
                runCatching { InstalledTts(json.decodeFromString(CustomTtsModel.serializer(), File(d, CustomTts.META).readText()), d) }.getOrNull()
            }
        }.distinctBy { it.id }.sortedBy { it.model.name.lowercase() }
        _models.value = list
        return list
    }

    fun find(packId: String): InstalledTts? {
        val id = CustomTts.idOf(packId) ?: return null
        return (if (scanned) _models.value else refresh()).firstOrNull { it.id == id }
            ?.takeIf { File(it.dir, CustomTts.META).isFile }
    }

    fun delete(id: String) {
        val m = _models.value.firstOrNull { it.id == id } ?: return
        _models.value = _models.value.filter { it.id != id }
        m.dir.deleteRecursively()
        refresh()
    }

    /** Text for [reason] (also used by the UI for the URL check). */
    fun message(reason: TtsReject): String = com.iris.localai.I18n.s(when (reason) {
        TtsReject.EMPTY, TtsReject.NO_MODEL -> com.iris.localai.R.string.tts245_no_model
        TtsReject.PIPER_RAW -> com.iris.localai.R.string.tts245_piper_raw
        TtsReject.GPT_SOVITS_RVC -> com.iris.localai.R.string.tts245_sovits
        TtsReject.NO_TOKENS -> com.iris.localai.R.string.tts245_no_tokens
        TtsReject.MATCHA_NO_VOCODER -> com.iris.localai.R.string.tts245_no_vocoder
        TtsReject.NO_TEXT_FRONTEND -> com.iris.localai.R.string.tts245_no_frontend
        TtsReject.BAD_URL -> com.iris.localai.R.string.tts245_bad_url
    })

    private suspend fun runImport(block: suspend () -> InstalledTts) {
        _import.value = TtsImportState(active = true)
        try {
            val m = block()
            _import.value = TtsImportState(added = m.model.name)
        } catch (t: Throwable) {
            _import.value = TtsImportState(error = if (t is kotlinx.coroutines.CancellationException) null else (t as? TtsImportException)?.let { message(it.reason) } ?: (t.message ?: t.javaClass.simpleName))
            if (t is kotlinx.coroutines.CancellationException) throw t
        }
    }

    private fun tempDir(): File = File(writeRoot(), ".import-" + System.currentTimeMillis()).apply { mkdirs() }

    private fun checkSpace(dir: File, need: Long) {
        val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < need) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_9c1de2, com.iris.localai.data.Format.bytes(need), com.iris.localai.data.Format.bytes(free)))
    }

    /** 选择文件夹 (ACTION_OPEN_DOCUMENT_TREE). The file list is checked before anything is copied. */
    suspend fun importTree(tree: Uri) = runImport {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            data class Doc(val rel: String, val id: String, val size: Long)
            val docs = ArrayList<Doc>()
            fun walk(parentId: String, prefix: String, depth: Int) {
                if (depth > 6) return
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                resolver.query(children, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                ), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0); val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        if (c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$prefix$name/", depth + 1)
                        else docs += Doc("$prefix$name", id, if (c.isNull(3)) 0L else c.getLong(3))
                    }
                }
            }
            walk(rootId, "", 0)
            val folderName = rootId.substringAfterLast(':').substringAfterLast('/')
            val strip = TtsDetect.commonRoot(docs.map { it.rel })
            val rels = docs.map { it.rel.removePrefix(strip) }
            when (val r = TtsDetect.detect(rels, folderName)) {
                is TtsDetectResult.Rejected -> throw TtsImportException(r.reason)
                is TtsDetectResult.Ok -> Unit
            }
            val total = docs.sumOf { it.size }
            checkSpace(writeRoot(), total + 32L * 1024 * 1024)
            val tmp = tempDir()
            try {
                var done = 0L
                _import.value = TtsImportState(active = true, phase = 0, total = total)
                for (d in docs) {
                    currentCoroutineContext().ensureActive()
                    val out = File(tmp, d.rel.removePrefix(strip)).apply { parentFile?.mkdirs() }
                    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
                    (resolver.openInputStream(uri) ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_31f711, d.rel))).use { ins ->
                        out.outputStream().buffered(1 shl 20).use { os -> ins.copyTo(os, 1 shl 20) }
                    }
                    done += d.size
                    _import.value = TtsImportState(active = true, phase = 0, done = done, total = total)
                }
                finish(tmp, folderName)
            } catch (t: Throwable) { tmp.deleteRecursively(); throw t }
        }
    }

    /** 选择 .tar.bz2 (a sherpa-onnx release archive). */
    suspend fun importArchive(uri: Uri) = runImport {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "tts"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
                }
            }
            if (!name.lowercase().endsWith(".tar.bz2")) throw TtsImportException(TtsReject.BAD_URL)
            if (size > 0) checkSpace(writeRoot(), size * 3)
            val tmp = tempDir()
            try {
                val archive = File(tmp, ".archive.tar.bz2")
                _import.value = TtsImportState(active = true, phase = 0, total = size.coerceAtLeast(0))
                (resolver.openInputStream(uri) ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_31f711, name))).use { ins ->
                    archive.outputStream().buffered(1 shl 20).use { os -> copyWithProgress(ins, os, size) }
                }
                extractAndFinish(tmp, archive, name.removeSuffix(".tar.bz2").removeSuffix(".TAR.BZ2"))
            } catch (t: Throwable) { tmp.deleteRecursively(); throw t }
        }
    }

    /** 粘贴下载链接: an https link to a .tar.bz2 (e.g. a k2-fsa/sherpa-onnx `tts-models` release asset). */
    suspend fun importUrl(url: String) = runImport {
        withContext(Dispatchers.IO) {
            TtsDetect.checkUrl(url)?.let { throw TtsImportException(it) }
            val u = url.trim()
            val name = u.substringBefore('?').substringBefore('#').substringAfterLast('/').removeSuffix(".tar.bz2")
            val tmp = tempDir()
            try {
                val archive = File(tmp, ".archive.tar.bz2")
                val conn = com.iris.localai.util.HttpDownload.open(u, 0L)
                try {
                    val code = conn.responseCode
                    if (code != java.net.HttpURLConnection.HTTP_OK) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.util_19cb06, code, u))
                    val size = conn.contentLengthLong
                    if (size > 0) checkSpace(writeRoot(), size * 3)
                    _import.value = TtsImportState(active = true, phase = 0, total = size.coerceAtLeast(0))
                    conn.inputStream.use { ins -> archive.outputStream().buffered(1 shl 20).use { os -> copyWithProgress(ins, os, size) } }
                    if (size > 0 && archive.length() != size) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.util_8b8d88, archive.length(), size, archive.name))
                } finally { conn.disconnect() }
                extractAndFinish(tmp, archive, name)
            } catch (t: Throwable) { tmp.deleteRecursively(); throw t }
        }
    }

    private suspend fun copyWithProgress(ins: java.io.InputStream, os: java.io.OutputStream, total: Long) {
        val buf = ByteArray(1 shl 18)
        var done = 0L
        var last = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = ins.read(buf)
            if (n < 0) break
            os.write(buf, 0, n)
            done += n
            if (done - last >= (1L shl 20)) { last = done; _import.value = TtsImportState(active = true, phase = 0, done = done, total = total.coerceAtLeast(0)) }
        }
    }

    private suspend fun extractAndFinish(tmp: File, archive: File, name: String): InstalledTts {
        _import.value = TtsImportState(active = true, phase = 1)
        val out = File(tmp, "x").apply { mkdirs() }
        TarBz2.extract(archive, out, "", emptyList())
        archive.delete()
        return finish(out, name)
    }

    /** Detects the model in [dir] (descending a single wrapping folder), moves it to its place, writes its meta. */
    private fun finish(dir: File, nameHint: String): InstalledTts {
        _import.value = TtsImportState(active = true, phase = 2)
        var root = dir
        while (true) {
            val kids = root.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            val only = kids.singleOrNull()
            if (only != null && only.isDirectory) root = only else break
        }
        val rels = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val name = (if (root != dir) root.name else nameHint).ifBlank { nameHint }
        val spec = when (val r = TtsDetect.detect(rels, name)) {
            is TtsDetectResult.Rejected -> throw TtsImportException(r.reason)
            is TtsDetectResult.Ok -> r.spec
        }
        val taken = refresh().map { it.id }.toSet() + writeRoot().list().orEmpty().toSet()
        val id = CustomTts.unique(CustomTts.slug(name), taken)
        val target = File(writeRoot(), id)
        if (!root.renameTo(target)) {
            root.copyRecursively(target, overwrite = true)
        }
        // whatever is left of the temporary folder (the wrapper, an archive)
        generateSequence(dir) { it.parentFile }.firstOrNull { it.name.startsWith(".import-") }?.deleteRecursively()
        val bytes = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val model = CustomTtsModel(id, name, spec, bytes)
        File(target, CustomTts.META).writeText(json.encodeToString(CustomTtsModel.serializer(), model))
        refresh()
        return InstalledTts(model, target)
    }
}

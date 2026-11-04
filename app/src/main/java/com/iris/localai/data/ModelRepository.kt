package com.iris.localai.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.iris.localai.engine.EngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * LLM models (2.1.0): in the public `Download/IRIDIS/models` when 全部文件访问 is granted (survives an
 * uninstall / the applicationId change), else in the app-specific external directory
 * `Android/data/<pkg>/files/models` (no permission needed). Both folders are listed; see [ModelDirs].
 * Users can import through the system picker, download, or push files there with adb / a file manager.
 */
class ModelRepository(private val context: Context) {

    /** The app-specific folder (pre-2.1.0 location, and the fallback). Resolved on first use, not at startup. */
    val privateDir: File by lazy {
        (context.getExternalFilesDir(ModelDirs.SUB) ?: File(context.filesDir, ModelDirs.SUB)).apply { mkdirs() }
    }

    /** Re-evaluated on every call (cheap), so granting 全部文件访问 later switches to the shared folder. */
    fun location(): ModelDirs.Location = ModelDirs.resolve(
        allFilesAccess = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false),
        storageState = runCatching { android.os.Environment.getExternalStorageState() }.getOrNull(),
        downloadsDir = runCatching { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
        privateDir = privateDir,
    )

    /** Where new models are written right now (shared when available). */
    val modelsDir: File get() = location().dir.apply { mkdirs() }

    /** `.part` files of imports in progress; [refresh] must not delete them underneath the copy. */
    private val activeParts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    suspend fun refresh(): List<LocalModel> = withContext(Dispatchers.IO) {
        val loc = location()
        loc.dir.mkdirs()
        val mnn = ModelFormat.MNN in EngineFactory.supportedFormats
        val listings = loc.listDirs.map { dir ->
            val files = dir.listFiles().orEmpty()
            // Remove leftovers from interrupted imports (but never one that is being written right now).
            files.filter { it.name.endsWith(PART_SUFFIX) && it.name !in activeParts && it.name !in heldParts }.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
            files.flatMap { f -> entriesOf(f, mnn) }
                // 2.2.5: projector files (mmproj) belong to their parent model; never listed as models
                // anywhere (home menu, 档位与加载, 模型管理, GUI pickers). They stay on disk (and move
                // with the models), they are just not entries.
                .filterNot { isProjectorFile(it.fileName) }
                // 2.2.16: split GGUF — only part 1 is a model (llama.cpp opens the rest next to it)
                .filterNot { isSplitTail(it.fileName) }
        }
        val list = ModelOrder.apply(ModelDirs.merge(listings) { it.fileName }.sortedBy { it.displayName.lowercase() }, savedOrder())
        _models.value = list
        list
    }

    /** 2.2.2: the user's own order (模型管理 › 拖动排序); the home menu lists models in this order. */
    private val orderPrefs by lazy { context.getSharedPreferences("iris_model_order", Context.MODE_PRIVATE) }

    private fun savedOrder(): List<String> = orderPrefs.getString("order", "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** Moves [from] to [to] in the current list and remembers the new order. */
    fun move(from: Int, to: Int) {
        val cur = _models.value.toMutableList()
        if (from !in cur.indices || to !in cur.indices || from == to) return
        cur.add(to, cur.removeAt(from))
        _models.value = cur
        // Keep names of models that are not on disk right now (e.g. a removed SD card) in place.
        val names = cur.map { it.fileName }
        val merged = names + savedOrder().filter { it !in names }
        orderPrefs.edit().putString("order", merged.joinToString("\n")).apply()
    }

    /** 2.3.5: a GGUF model folder (main .gguf + optional mmproj*.gguf) lists its main(s); everything else as [entryOf]. */
    private fun entriesOf(f: File, mnn: Boolean): List<LocalModel> {
        if (f.isDirectory && !f.name.endsWith(PART_SUFFIX) && !f.name.startsWith(".") && ModelFormat.GGUF in EngineFactory.supportedFormats) {
            val names = f.list().orEmpty().toList()
            if (!(mnn && MnnModels.isModelDir(f))) {
                val mains = GgufFolder.mainsIn(names)
                if (mains.isNotEmpty()) return mains.mapNotNull { n ->
                    val main = File(f, n)
                    val stem = n.replace(Regex("-00001-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE), "")
                    val size = main.length() + names.filter { it != n && GgufFolder.isSplitTail(it) && it.startsWith(stem) }.sumOf { File(f, it).length() }
                    if (main.isFile && main.length() > 0) LocalModel(main.name, main.absolutePath, size, ModelFormat.GGUF) else null
                }
            }
        }
        return listOfNotNull(entryOf(f, mnn))
    }

    private fun entryOf(f: File, mnn: Boolean = ModelFormat.MNN in EngineFactory.supportedFormats): LocalModel? {
        if (f.name.endsWith(PART_SUFFIX) || f.name.startsWith(".")) return null
        if (f.isDirectory) {
            if (mnn && MnnModels.isModelDir(f)) return LocalModel(f.name, f.absolutePath, MnnModels.folderSize(f), ModelFormat.MNN)
            // 2.2.0: Qualcomm AI Hub QAIRT bundle folders (GenieX, full flavour).
            if (ModelFormat.QAIRT in EngineFactory.supportedFormats &&
                com.iris.localai.engine.geniex.QairtBundle.isFileSet(f.list().orEmpty().toSet())
            ) return LocalModel(f.name, f.absolutePath, MnnModels.folderSize(f), ModelFormat.QAIRT)
            return null
        }
        val format = ModelFormat.fromFileName(f.name)?.takeIf { it in EngineFactory.supportedFormats } ?: return null
        val size = f.length() // 0 for directories / missing files
        return if (size <= 0 || !f.isFile) null else LocalModel(f.name, f.absolutePath, size, format)
    }

    /** Progress of [moveToShared]; null when idle. */
    data class MoveState(val done: Long, val total: Long, val current: String, val error: String? = null, val active: Boolean = true) {
        val progress: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }
    private val _move = MutableStateFlow<MoveState?>(null)
    val moveState: StateFlow<MoveState?> = _move.asStateFlow()

    /** Private-folder models that a move would take (empty when the shared folder is not available). */
    fun pendingMove(keep: Set<String> = emptySet()): ModelDirs.MovePlan {
        val loc = location()
        if (!loc.shared) return ModelDirs.MovePlan(emptyList(), emptyList())
        return ModelDirs.planMove(loc.privateDir, loc.dir, { entryOf(it) != null }, keep)
    }

    /**
     * One-time move of the private models into `Download/IRIDIS/models` (user-started from 存储空间;
     * never automatic — it can be many GB). [keep] = paths in use (skipped). Returns (moved, skipped).
     */
    suspend fun moveToShared(keep: Set<String> = emptySet()): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val loc = location()
        if (!loc.shared) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_44db6e))
        val plan = ModelDirs.planMove(loc.privateDir, loc.dir, { entryOf(it) != null }, keep)
        val total = plan.move.sumOf { ModelDirs.totalBytes(it) }
        var base = 0L
        var moved = 0
        val job = coroutineContext
        _move.value = MoveState(0, total, "")
        try {
            for (src in plan.move) {
                val part = src.name + PART_SUFFIX
                if (!activeParts.add(part)) continue
                try {
                    val size = ModelDirs.totalBytes(src)
                    _move.value = MoveState(base, total, src.name)
                    ModelDirs.moveEntry(src, loc.dir, checkCancel = { job.ensureActive() }) { got ->
                        _move.value = MoveState(base + got, total, src.name)
                    }
                    base += size
                    moved++
                } finally {
                    activeParts.remove(part)
                }
            }
            _move.value = null
        } catch (t: Throwable) {
            _move.value = if (t is kotlinx.coroutines.CancellationException) null
            else MoveState(base, total, "", error = t.message ?: t.javaClass.simpleName, active = false)
            refresh()
            throw t
        }
        refresh()
        moved to plan.skipped.size
    }

    fun clearMoveError() { if (_move.value?.active == false) _move.value = null }

    fun find(fileName: String?): LocalModel? = fileName?.let { n -> _models.value.firstOrNull { it.fileName == n } }

    suspend fun delete(model: LocalModel) = withContext(Dispatchers.IO) {
        val f = File(model.path)
        // 2.3.5: a GGUF model in its own folder → the whole folder (main + mmproj + shards) goes,
        // unless the folder is a models root or still holds another main model.
        val parent = f.parentFile
        val roots = location().listDirs.map { it.absolutePath }.toSet() + modelsDir.absolutePath
        if (f.isFile && parent != null && parent.absolutePath !in roots && GgufFolder.isGguf(f.name) &&
            GgufFolder.mainsIn(parent.list().orEmpty().toList()).size <= 1
        ) { parent.deleteRecursively(); refresh(); return@withContext }
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        // 2.2.16: the other parts of a split GGUF and the projector downloaded with it go too
        f.parentFile?.listFiles()?.filter { companionOf(f.name, it.name) }?.forEach { it.delete() }
        refresh()
    }

    /** Copies a user-picked document into the models directory, reporting progress in [0,1]. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val fileName = sanitizeFileName(name ?: uri.lastPathSegment ?: "model.gguf")
        val format = ModelFormat.fromFileName(fileName)?.takeIf { it in EngineFactory.supportedFormats }
            ?: throw IOException("不支持的文件类型：$fileName（支持 ${EngineFactory.supportedFormats.flatMap { it.extensions }.joinToString(" / ") { ".$it" }}）")

        val modelsDir = this@ModelRepository.modelsDir
        // 2.3.5: a GGUF goes into models/<name>/ (so a projector can sit next to it); other formats stay loose.
        val ggufDir = if (format == ModelFormat.GGUF) File(modelsDir, GgufFolder.folderFor(fileName)) else null
        val target = File(ggufDir ?: modelsDir, fileName)
        if (target.exists() || find(fileName) != null || (ggufDir != null && ggufDir.exists() && GgufFolder.mainsIn(ggufDir.list().orEmpty().toList()).isNotEmpty())) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_f95dbe, fileName))
        if (size > 0) {
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < size + 256L * 1024 * 1024) {
                throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_9c1de2, Format.bytes(size), Format.bytes(free)))
            }
        }

        val part = File(modelsDir, fileName + PART_SUFFIX)
        if (!activeParts.add(part.name)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_0ba8e0, fileName))
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_c15415))
            input.use { ins ->
                part.outputStream().buffered(1 shl 20).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    var first = true
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (first && format == ModelFormat.GGUF) {
                            first = false
                            if (n < 4 || buf[0] != 'G'.code.toByte() || buf[1] != 'G'.code.toByte() ||
                                buf[2] != 'U'.code.toByte() || buf[3] != 'F'.code.toByte()
                            ) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_0f2c7d))
                        }
                        out.write(buf, 0, n)
                        copied += n
                        if (size > 0 && copied - lastReport > 8L * 1024 * 1024) {
                            lastReport = copied
                            onProgress((copied.toDouble() / size).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
            target.parentFile?.mkdirs(); if (!part.renameTo(target)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_04dd9d))
            onProgress(1f)
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            activeParts.remove(part.name)
        }
        refresh()
        LocalModel(target.name, target.absolutePath, target.length(), format)
    }

    private fun sanitizeFileName(name: String): String = ModelFiles.sanitizeFileName(name)

    /** 2.3.5 (#8): copy a user-picked mmproj GGUF next to [model]; returns its file name (always contains "mmproj"). */
    suspend fun importMmproj(model: LocalModel, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val raw = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "mmproj.gguf"
        var name = sanitizeFileName(raw)
        if (!GgufFolder.isGguf(name)) throw IOException("请选择 .gguf 格式的 mmproj 文件")
        if (!isProjectorFile(name)) name = "mmproj-$name"
        val dir = File(model.path).parentFile ?: modelsDir
        val target = File(dir, name)
        if (target.exists()) return@withContext name
        val part = File(dir, name + PART_SUFFIX)
        try {
            (resolver.openInputStream(uri) ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_31f711, name))).use { ins ->
                part.outputStream().buffered(1 shl 20).use { os -> ins.copyTo(os, 1 shl 20) }
            }
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_04dd9d))
        } catch (t: Throwable) { part.delete(); throw t }
        name
    }

    /**
     * Copies a user-picked folder (system folder picker, ACTION_OPEN_DOCUMENT_TREE) that holds an
     * MNN model — config.json + llm.mnn + weights + tokenizer — into the models directory.
     */
    suspend fun importFolder(tree: Uri, onPair: (modelId: String, mmproj: String) -> Unit = { _, _ -> }, onProgress: (Float) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        val qairt = ModelFormat.QAIRT in EngineFactory.supportedFormats
        val resolver = context.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        data class Entry(val rel: String, val docId: String, val size: Long)
        val entries = ArrayList<Entry>()
        fun walk(docId: String, prefix: String, depth: Int) {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(
                children,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = ModelFiles.sanitizeFileName(c.getString(1) ?: continue)
                    val mime = c.getString(2).orEmpty()
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(id, "$prefix$name/", depth + 1)
                    } else if (MnnModels.shouldCopy(name)) {
                        entries += Entry(prefix + name, id, size)
                    }
                }
            }
        }
        walk(rootId, "", 0)
        val topNames = entries.filter { '/' !in it.rel }.map { it.rel }.toSet()
        // 2.3.5 (#8): a folder of GGUF files (main + mmproj) → flat GGUF imports, projector linked.
        if (!MnnModels.isModelFileSet(topNames) && topNames.any { GgufFolder.isGguf(it) }) {
            val ggufEntries = entries.filter { '/' !in it.rel && GgufFolder.isGguf(it.rel) }
            val sniffed = ggufEntries.filter { !isProjectorFile(it.rel) }.filter { e ->
                runCatching {
                    resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, e.docId))?.use { ins ->
                        val b = ByteArray(256 * 1024); var n = 0
                        while (n < b.size) { val r = ins.read(b, n, b.size - n); if (r < 0) break; n += r }
                        GgufFolder.headerIsProjector(b.copyOf(n))
                    } ?: false
                }.getOrDefault(false)
            }.map { it.rel }.toSet()
            val plan = GgufFolder.classify(topNames, sniffed) ?: throw IOException("文件夹里只有视觉投影 (mmproj)，没有主模型 GGUF")
            if (ModelFormat.GGUF !in EngineFactory.supportedFormats) throw IOException("不支持 GGUF")
            val root = this@ModelRepository.modelsDir
            // 2.3.5: the pair lives in models/<folder name>/
            val dirName = sanitizeFileName(
                resolver.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                    arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: GgufFolder.folderFor(plan.mains.first()),
            )
            val modelsDir = File(root, dirName)
            if (modelsDir.exists() || plan.mains.any { find(it) != null }) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_f95dbe, dirName))
            modelsDir.mkdirs()
            plan.copy.values.firstOrNull { File(modelsDir, it).exists() }?.let { throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_f95dbe, it)) }
            val total = ggufEntries.sumOf { it.size.coerceAtLeast(0) }
            if (total > 0) {
                val free = StatFs(modelsDir.absolutePath).availableBytes
                if (free < total + 256L * 1024 * 1024) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_9c1de2, Format.bytes(total), Format.bytes(free)))
            }
            var copied = 0L; var lastReport = 0L
            val buf = ByteArray(1 shl 20)
            for (e in ggufEntries) {
                val targetName = plan.copy[e.rel] ?: continue
                val part = File(modelsDir, targetName + PART_SUFFIX)
                if (!activeParts.add(part.name)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_8a5901, targetName))
                try {
                    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
                    (resolver.openInputStream(uri) ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_31f711, e.rel))).use { ins ->
                        part.outputStream().buffered(1 shl 20).use { os ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = ins.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n); copied += n
                                if (total > 0 && copied - lastReport > 8L * 1024 * 1024) {
                                    lastReport = copied
                                    onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    if (!part.renameTo(File(modelsDir, targetName))) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_04dd9d))
                } catch (t: Throwable) {
                    part.delete(); if (modelsDir.list().isNullOrEmpty()) modelsDir.delete(); throw t
                } finally {
                    activeParts.remove(part.name)
                }
            }
            onProgress(1f)
            plan.pairs.forEach { (m, p) -> onPair(m, p) }
            refresh()
            val main = File(modelsDir, plan.mains.first())
            return@withContext LocalModel(main.name, main.absolutePath, main.length(), ModelFormat.GGUF)
        }
        if (ModelFormat.MNN !in EngineFactory.supportedFormats) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_c1bcc7))
        val folderFormat = when {
            MnnModels.isModelFileSet(topNames) -> ModelFormat.MNN
            qairt && com.iris.localai.engine.geniex.QairtBundle.isFileSet(topNames) -> ModelFormat.QAIRT
            else -> throw IOException(
                com.iris.localai.I18n.s(com.iris.localai.R.string.data_ac190c) +
                    if (qairt) com.iris.localai.I18n.s(com.iris.localai.R.string.data_840d31) else "",
            )
        }
        val folderName = sanitizeFileName(
            resolver.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "mnn-model" },
        )
        val modelsDir = this@ModelRepository.modelsDir
        val target = File(modelsDir, folderName)
        if (target.exists() || find(folderName) != null) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_f95dbe, folderName))
        val total = entries.sumOf { it.size.coerceAtLeast(0) }
        if (total > 0) {
            val free = StatFs(modelsDir.absolutePath).availableBytes
            if (free < total + 256L * 1024 * 1024) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_9c1de2, Format.bytes(total), Format.bytes(free)))
        }
        val part = File(modelsDir, folderName + PART_SUFFIX)
        if (!activeParts.add(part.name)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_8a5901, folderName))
        try {
            part.deleteRecursively()
            part.mkdirs()
            var copied = 0L
            var lastReport = 0L
            val buf = ByteArray(1 shl 20)
            for (e in entries) {
                val out = File(part, e.rel).apply { parentFile?.mkdirs() }
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
                (resolver.openInputStream(uri) ?: throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_31f711, e.rel))).use { ins ->
                    out.outputStream().buffered(1 shl 20).use { os ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            copied += n
                            if (total > 0 && copied - lastReport > 8L * 1024 * 1024) {
                                lastReport = copied
                                onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_04dd9d))
            onProgress(1f)
        } catch (t: Throwable) {
            part.deleteRecursively()
            throw t
        } finally {
            activeParts.remove(part.name)
        }
        refresh()
        LocalModel(target.name, target.absolutePath, MnnModels.folderSize(target), folderFormat)
    }

    /** Lets [MnnHub] write `<name>.part` folders without [refresh] deleting them underneath it. */
    internal fun claimPart(name: String): Boolean = activeParts.add(name)
    internal fun releasePart(name: String) { activeParts.remove(name) }

    /** 2.3.1: `.part` names of a paused download (MnnHub 「暂停」): kept by [refresh] until it resumes or is cancelled. */
    private val heldParts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    internal fun holdParts(names: Collection<String>) { heldParts.clear(); heldParts.addAll(names) }
    internal fun unholdParts() { heldParts.clear() }

    /** 2.3.1: `.part` names that must not be deleted now (imports / downloads running, or paused). */
    fun protectedParts(): Set<String> = activeParts.toSet() + heldParts

    internal companion object {
        const val PART_SUFFIX = ".part"
    }
}

/** MNN model folders (taobao-mnn layout). Pure helpers, unit-tested. */
object MnnModels {
    /** A folder is an MNN LLM when it has the runtime config and the graph. */
    fun isModelFileSet(names: Set<String>): Boolean = "config.json" in names && "llm.mnn" in names

    fun isModelDir(dir: File): Boolean = dir.isDirectory && isModelFileSet(dir.list().orEmpty().toSet())

    /** Repository files the runtime never reads. */
    fun shouldCopy(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name.startsWith(".")) return false
        val lower = name.lowercase()
        return !(lower == "readme.md" || lower.endsWith(".md") || lower == "license" || lower.endsWith(".png") || lower.endsWith(".jpg"))
    }

    fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** "taobao-mnn/Qwen3-1.7B-MNN" or a full huggingface.co / hf-mirror URL → "taobao-mnn/Qwen3-1.7B-MNN". */
    fun parseRepoId(input: String): String? {
        var s = input.trim().removeSuffix("/")
        s = s.replace(Regex("^https?://(www\\.)?(huggingface\\.co|hf-mirror\\.com)/", RegexOption.IGNORE_CASE), "")
        s = s.substringBefore("/tree/").substringBefore("/blob/").substringBefore("/resolve/")
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.size == 1 && Regex("[A-Za-z0-9._-]+").matches(parts[0])) return "taobao-mnn/${parts[0]}"
        if (parts.size != 2) return null
        if (!parts.all { Regex("[A-Za-z0-9._-]+").matches(it) }) return null
        return "${parts[0]}/${parts[1]}"
    }
}

/** Pure helpers (unit-tested). */
object ModelFiles {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private const val MAX_LEN = 180

    /**
     * Makes a picker display name safe as a file name. Long names are shortened in the middle
     * so the extension (which decides the engine) survives; hidden / empty names get a stem.
     */
    fun sanitizeFileName(name: String): String {
        var n = ILLEGAL.replace(name.substringAfterLast('/').trim(), "_").trimStart('.')
        if (n.isEmpty()) n = "model"
        if (n.length > MAX_LEN) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 16) n.substring(dot) else ""
            n = n.substring(0, MAX_LEN - ext.length) + ext
        }
        return n
    }
}

/** 2.2.16: part 2…N of a split GGUF (`-00002-of-00003.gguf`). */
fun isSplitTail(name: String): Boolean =
    GgufSearch.SPLIT.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 1 } ?: false

/** Files that belong to model file [model]: its other split parts, and a projector saved as `<model>-mmproj…`. Pure. */
fun companionOf(model: String, other: String): Boolean {
    if (other == model) return false
    val m = GgufSearch.SPLIT.find(model)
    if (m != null) {
        val prefix = model.substring(0, m.range.first)
        if (GgufSearch.SPLIT.find(other)?.let { other.substring(0, it.range.first) == prefix && it.groupValues[2] == m.groupValues[2] } == true) return true
    }
    val stem = model.replace(GgufSearch.SPLIT, ".gguf").removeSuffix(".gguf")
    return other.startsWith("$stem-") && isProjectorFile(other)
}

/** 2.2.5: vision projector files (mmproj etc.) — attached to their parent model, never a model themselves. */
fun isProjectorFile(name: String): Boolean {
    val n = name.lowercase()
    return "mmproj" in n || "projector" in n
}

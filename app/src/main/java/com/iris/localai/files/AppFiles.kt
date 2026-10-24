package com.iris.localai.files

import com.iris.localai.agent.StorageLayout
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * 2.2 「文件」: pure logic of the in-app file manager for the app root `Download/IRIDIS`
 * (no Android types, unit-tested in FilesLogicTest). The page ([com.iris.localai.ui.files.FilesViewModel])
 * and every view style (list now, archive in part B) go through these helpers, so paths never
 * leave the root and the app's own folders stay put.
 */

/**
 * View style menu on the page's top bar (列表 / 卡片); stored as [com.iris.localai.data.AppSettings.fileViewStyle].
 * The 「档案」 slab-field view was taken out before release (shelved in the handoff, branch shelf/archive-terminal);
 * a stored "ARCHIVE" falls back to [LIST].
 */
enum class FileViewStyle(val label: String) {
    LIST("列表"),
    /** 2.1.9 「卡片」: the pseudo-3D card deck (CardStackFileView, the first 「档案模式」). */
    CARDS("卡片");

    companion object {
        fun parse(s: String?): FileViewStyle = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: LIST
    }
}

enum class FileSort(val label: String) { NAME("名称"), TIME("时间"), SIZE("大小") }

/** The app's own folders at the root, in their fixed display order ([StorageLayout.RESERVED]). */
enum class SystemFolder(val dir: String, val label: String) {
    MODELS(StorageLayout.MODELS, "模型"),
    VOICE(StorageLayout.VOICE, "语音"),
    SKILLS(StorageLayout.SKILLS, "技能"),
    KNOWLEDGE(StorageLayout.KNOWLEDGE, "知识库"),
    CONFIG(StorageLayout.CONFIG, "配置"),
    WORKSPACE(StorageLayout.WORKSPACE, "工作区");

    companion object {
        /** Case-insensitive, like shared storage. */
        fun of(name: String): SystemFolder? = entries.firstOrNull { it.dir.equals(name, ignoreCase = true) }
    }
}

/**
 * One row. [relPath] is root-relative with `/` separators ("" = the root itself, never an entry).
 * [system] is set only for the app's folders directly under the root.
 */
data class FileEntry(
    val name: String,
    val relPath: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int = 0,
    val system: SystemFolder? = null,
) {
    /** 模型 / 语音 … for system folders, else the file name. */
    val displayName: String get() = system?.label ?: name
    val extension: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

data class Crumb(val label: String, val relPath: String)

object FilesPaths {
    const val ROOT_LABEL = "IRIDIS"

    /** Root-relative segments of [rel]; null when it is absolute-looking after cleanup or uses `..`. */
    fun segments(rel: String): List<String>? {
        val segs = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." || it.contains('\u0000') }) return null
        return segs
    }

    /** Normalised form ("a//b/./c/" → "a/b/c"); null = escapes. */
    fun normalize(rel: String): String? = segments(rel)?.joinToString("/")

    fun join(rel: String, name: String): String = if (rel.isEmpty()) name else "$rel/$name"

    fun parent(rel: String): String = normalize(rel)?.substringBeforeLast('/', "") ?: ""

    fun name(rel: String): String = rel.substringAfterLast('/')

    /** Depth below the root: "" = 0, "models" = 1, "models/a.gguf" = 2. */
    fun depth(rel: String): Int = segments(rel)?.size ?: 0

    /**
     * The file for [rel] inside [root], or null when it would leave the root — by `..`, or through a
     * symbolic link pointing elsewhere (checked on canonical paths).
     */
    fun resolve(root: File, rel: String): File? {
        val segs = segments(rel) ?: return null
        val f = segs.fold(root) { acc, s -> File(acc, s) }
        return if (isInside(root, f)) f else null
    }

    /** [file] is [root] or below it (canonical paths, so symlinks out of the root do not count). */
    fun isInside(root: File, file: File): Boolean {
        val r = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val f = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return f == r || f.path.startsWith(r.path + File.separator)
    }

    /** "models/a" → IRIDIS › 模型 › a (system folders by their Chinese label). */
    fun crumbs(rel: String): List<Crumb> {
        val out = mutableListOf(Crumb(ROOT_LABEL, ""))
        var acc = ""
        (segments(rel) ?: emptyList()).forEachIndexed { i, seg ->
            acc = join(acc, seg)
            out += Crumb(if (i == 0) SystemFolder.of(seg)?.label ?: seg else seg, acc)
        }
        return out
    }
}

object FilesSorting {
    /**
     * At the root the system folders come first in [SystemFolder] order whatever the sort; then
     * folders before files; then [sort] ([ascending] or not), ties by name.
     */
    fun sort(entries: List<FileEntry>, sort: FileSort, ascending: Boolean): List<FileEntry> {
        val (sys, rest) = entries.partition { it.system != null }
        val byName = compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
        val key: Comparator<FileEntry> = when (sort) {
            FileSort.NAME -> byName
            FileSort.TIME -> compareBy<FileEntry> { it.modified }.then(byName)
            FileSort.SIZE -> compareBy<FileEntry> { if (it.isDir) it.childCount.toLong() else it.size }.then(byName)
        }
        val directed = if (ascending) key else key.reversed()
        return sys.sortedBy { it.system!!.ordinal } + rest.sortedWith(compareBy<FileEntry> { !it.isDir }.then(directed))
    }

    /** Tapping the current sort flips the direction; a new sort starts at its natural direction. */
    fun next(current: FileSort, ascending: Boolean, tapped: FileSort): Pair<FileSort, Boolean> =
        if (tapped == current) tapped to !ascending else tapped to defaultAscending(tapped)

    /** Names A→Z; time and size newest / largest first. */
    fun defaultAscending(sort: FileSort): Boolean = sort == FileSort.NAME
}

object FileSizes {
    /** 0 B · 999 B · 1.0 KB · 12.3 MB · 4.20 GB (binary units). */
    fun format(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        val pattern = if (i >= 2) "%.2f %s" else "%.1f %s"
        return String.format(Locale.ROOT, pattern, v, units[i])
    }
}

/** Why a new name is refused (null = fine). Messages are user-facing. */
enum class NameProblem(val message: String) {
    EMPTY("名称不能为空"),
    TOO_LONG("名称太长（最多 120 个字符）"),
    ILLEGAL("名称不能包含 / \\ : * ? \" < > | 等字符"),
    DOTS("名称不能是 . 或 ..，也不能以 . 开头"),
    EXISTS("已有同名文件或文件夹"),
    RESERVED("这是 IRIDIS 的系统文件夹名，不能使用"),
}

object FileNames {
    const val MAX = 120
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /**
     * Checks [raw] (trimmed) as a new name in the folder [parentRel] holding [siblings].
     * Hidden names (leading dot) are refused because the page hides them. At the root, the
     * system folder names are refused. A case-only change of [current] is allowed.
     */
    fun validate(raw: String, parentRel: String, siblings: Collection<String>, current: String? = null): NameProblem? {
        val n = raw.trim()
        if (n.isEmpty()) return NameProblem.EMPTY
        if (n.length > MAX) return NameProblem.TOO_LONG
        if (n.any { it in ILLEGAL || it.code < 0x20 }) return NameProblem.ILLEGAL
        if (n == "." || n == ".." || n.startsWith(".")) return NameProblem.DOTS
        if (parentRel.isEmpty() && SystemFolder.of(n) != null && !(current != null && current.equals(n, ignoreCase = true))) return NameProblem.RESERVED
        val clash = siblings.any { it.equals(n, ignoreCase = true) && !(current != null && it == current) }
        if (clash) return NameProblem.EXISTS
        return null
    }
}

/**
 * What the page may do to an entry. The root and the app's folders under it are fixed: no rename,
 * delete or move. Files inside models/ and voice/ may be changed, with an extra warning.
 */
object FileProtection {
    /** The root itself or one of the app's folders directly under it. */
    fun isFixed(rel: String): Boolean {
        val segs = FilesPaths.segments(rel) ?: return true // anything escaping is treated as untouchable
        return segs.isEmpty() || (segs.size == 1 && SystemFolder.of(segs[0]) != null)
    }

    fun canRename(rel: String) = !isFixed(rel)
    fun canDelete(rel: String) = !isFixed(rel)
    fun canMove(rel: String) = !isFixed(rel)

    /** Inside models/ or voice/ (not the folder itself): the app may stop finding that model. */
    fun modelWarning(rel: String): Boolean {
        val segs = FilesPaths.segments(rel) ?: return false
        if (segs.size < 2) return false
        val top = SystemFolder.of(segs[0])
        return top == SystemFolder.MODELS || top == SystemFolder.VOICE
    }

    fun warningText(rel: String): String? = if (!modelWarning(rel)) null else when (SystemFolder.of(FilesPaths.segments(rel)!![0])) {
        SystemFolder.VOICE -> "这是语音模型目录里的文件，改名或删除后 IRIDIS 可能找不到这个语音包。"
        else -> "这是模型目录里的文件，改名或删除后 IRIDIS 可能找不到这个模型。"
    }

    /** Hidden from the listing: dot-files and app bookkeeping at the root. */
    fun isHidden(name: String): Boolean = name.startsWith(".") || StorageLayout.isInternal(name)
}

object FilePreview {
    /** In-app preview only up to this size; larger files open in another app. */
    const val MAX_BYTES = 256L * 1024

    private val TEXT = setOf(
        "txt", "md", "markdown", "log", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "properties", "csv", "tsv", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "sh",
        "c", "h", "cpp", "rs", "go", "sql", "jinja", "srt",
    )
    private val MARKDOWN = setOf("md", "markdown")

    fun isText(ext: String) = ext.lowercase(Locale.ROOT) in TEXT
    fun isMarkdown(ext: String) = ext.lowercase(Locale.ROOT) in MARKDOWN
    fun canPreview(e: FileEntry) = !e.isDir && isText(e.extension) && e.size <= MAX_BYTES

    /** Bytes look like text (no NUL in the first 4 KB). */
    fun looksText(bytes: ByteArray): Boolean = bytes.take(4096).none { it == 0.toByte() }

    /** Up to [max] bytes from the start of [f] (no API-33 readNBytes). */
    fun readHead(f: File, max: Long = MAX_BYTES): ByteArray = f.inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n); left -= n
        }
        out.toByteArray()
    }

    /** Fallback MIME type when the platform map does not know the extension. */
    fun mimeFallback(ext: String): String = when {
        isMarkdown(ext) -> "text/markdown"
        isText(ext) -> "text/plain"
        else -> "*/*"
    }
}

/** java.io operations confined to the root. */
object FilesIo {
    /** The visible entries of [rel] (system folders tagged at the root), unsorted; null = unreadable. */
    fun list(root: File, rel: String): List<FileEntry>? {
        val dir = FilesPaths.resolve(root, rel) ?: return null
        val kids = dir.listFiles() ?: return null
        val atRoot = FilesPaths.depth(rel) == 0
        return kids.filter { !FileProtection.isHidden(it.name) }.map { f ->
            val isDir = f.isDirectory
            FileEntry(
                name = f.name,
                relPath = FilesPaths.join(FilesPaths.normalize(rel) ?: "", f.name),
                isDir = isDir,
                size = if (isDir) 0L else f.length(),
                modified = f.lastModified(),
                childCount = if (isDir) f.list()?.count { !FileProtection.isHidden(it) } ?: 0 else 0,
                system = if (atRoot && isDir) SystemFolder.of(f.name) else null,
            )
        }
    }

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    fun mkdir(root: File, parentRel: String, name: String): Result {
        val parent = FilesPaths.resolve(root, parentRel) ?: return Result.Failed("路径不在 IRIDIS 内")
        FileNames.validate(name, parentRel, parent.list()?.toList().orEmpty())?.let { return Result.Failed(it.message) }
        val target = File(parent, name.trim())
        if (!FilesPaths.isInside(root, target)) return Result.Failed("路径不在 IRIDIS 内")
        return if (target.mkdir()) Result.Ok("已新建 ${name.trim()}") else Result.Failed("新建失败（可能没有写入权限）")
    }

    fun rename(root: File, rel: String, newName: String): Result {
        if (!FileProtection.canRename(rel)) return Result.Failed("系统文件夹不能重命名")
        val from = FilesPaths.resolve(root, rel) ?: return Result.Failed("路径不在 IRIDIS 内")
        val parentRel = FilesPaths.parent(rel)
        val n = newName.trim()
        if (n == from.name) return Result.Ok("名称未改变")
        FileNames.validate(n, parentRel, from.parentFile?.list()?.toList().orEmpty(), current = from.name)?.let { return Result.Failed(it.message) }
        val to = File(from.parentFile, n)
        if (!FilesPaths.isInside(root, to)) return Result.Failed("路径不在 IRIDIS 内")
        val ok = if (n.equals(from.name, ignoreCase = true)) {
            // case-only change on a case-insensitive volume: go through a temporary name
            val tmp = File(from.parentFile, ".iridis-rename-" + System.nanoTime())
            from.renameTo(tmp) && (tmp.renameTo(to) || tmp.renameTo(from).let { false })
        } else !to.exists() && from.renameTo(to)
        return if (ok) Result.Ok("已重命名为 $n") else Result.Failed("重命名失败（可能没有写入权限）")
    }

    fun delete(root: File, rel: String): Result {
        if (!FileProtection.canDelete(rel)) return Result.Failed("系统文件夹不能删除")
        // Resolve the parent (a symlink entry may point outside; the link itself is still ours to remove).
        val parent = FilesPaths.resolve(root, FilesPaths.parent(rel)) ?: return Result.Failed("路径不在 IRIDIS 内")
        val target = File(parent, FilesPaths.name(FilesPaths.normalize(rel)!!))
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return Result.Failed("文件不存在")
        return if (deleteTree(target)) Result.Ok("已删除 ${target.name}") else Result.Failed("删除失败（部分文件可能没有权限）")
    }

    /** Deletes [f]; symbolic links are removed as links and never followed. */
    fun deleteTree(f: File): Boolean {
        val p = f.toPath()
        if (Files.isSymbolicLink(p)) return runCatching { Files.delete(p); true }.getOrDefault(false)
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete() || !f.exists()
    }
}

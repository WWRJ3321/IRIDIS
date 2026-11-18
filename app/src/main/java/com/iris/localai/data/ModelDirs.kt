package com.iris.localai.data

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.agent.workspace.WorkspaceResolver
import java.io.File
import java.io.IOException

/**
 * where LLM models live. With 全部文件访问 they go to the public `Download/IRIDIS/models`, which
 * survives an uninstall and the applicationId change (com.iris.localai → com.iris); without it they stay
 * in the app-specific `Android/data/<pkg>/files/models`. Pure java.io (no Android types) so the
 * resolution, the merged listing and the move are unit-tested on temp folders (V210ModelDirsTest);
 * [ModelRepository] passes the real Environment values and re-resolves on every use, so granting the
 * permission later switches without a restart.
 */
object ModelDirs {
    const val SUB = "models"
    const val NOMEDIA = ".nomedia"
    /** Shown to the user (same spelling as WorkspaceLocation.DOWNLOADS). */
    const val SHARED_LABEL = "下载/${WorkspaceResolver.FOLDER}/$SUB"

    data class Location(
        /** Where new downloads / imports are written. */
        val dir: File,
        /** [dir] is the shared folder. */
        val shared: Boolean,
        /** The shared folder whether or not it is usable right now (null = no Download dir). */
        val sharedDir: File?,
        val privateDir: File,
    ) {
        /** Folders to list, shared first (it wins on a name clash). */
        val listDirs: List<File> get() = listOfNotNull(sharedDir, privateDir).distinctBy { it.absolutePath }
    }

    /** `Download/IRIDIS/<sub>` ([SUB] = models; 2.1.1 voice packs use `voice`). */
    fun sharedDir(downloadsDir: File, sub: String = SUB): File = File(File(downloadsDir, WorkspaceResolver.FOLDER), sub)

    /**
     * Shared `Download/IRIDIS/<sub>` with 全部文件访问 (mounted and writable), else [privateDir].
     * also used for the voice packs ([sub] = `voice`), with the same fallback.
     */
    fun resolve(
        allFilesAccess: Boolean,
        storageState: String?,
        downloadsDir: File?,
        privateDir: File,
        usable: (File) -> Boolean = WorkspaceResolver::probe,
        sub: String = SUB,
        /**
         * the configured storage location (设置 › 智能体 › 工作区位置) is `Download/IRIDIS` ([followsStorage]):
         * the shared folder is used when it is writable even without 全部文件访问, so downloads *and*
         * imports land next to the workspace instead of in Android/data. Default false (2.1.0 rule).
         */
        followStorage: Boolean = false,
    ): Location {
        val shared = downloadsDir?.let { sharedDir(it, sub) }
        val ok = (allFilesAccess || followStorage) && storageState == WorkspaceResolver.MOUNTED && shared != null && usable(shared)
        val dir = if (ok) shared else null
        if (dir != null) ensureNoMedia(dir)
        return Location(dir ?: privateDir, ok, shared, privateDir)
    }

    /**
     * true when the app's storage root (AgentContainer.appRoot: `Download/IRIDIS` for 工作区位置 下载 /
     * 自定义, `filesDir/iridis` for 应用私有目录) is `Download/IRIDIS`. Pure (paths only).
     */
    fun followsStorage(storageRoot: File?, downloadsDir: File?, requestedShared: Boolean = false): Boolean {
        if (downloadsDir == null) return false
        // 设置里选的是 下载/自定义 就跟随 Download/IRIDIS——不再看工作区「当时」解析到哪。
        // 之前启动时存储没就绪/探测失败一次，工作区退回私有目录，模型页就一直提示「更改位置」。
        if (requestedShared) return true
        if (storageRoot == null) return false
        return samePath(storageRoot, File(downloadsDir, WorkspaceResolver.FOLDER))
    }

    /**
     * 路径比较——规范化、去尾斜杠，并把 /sdcard、/storage/self/primary、/mnt/user/0/primary 等别名
     * 统一成 /storage/emulated/0（软链接在 FUSE 上不一定能 canonical 出来）。Pure.
     */
    fun samePath(a: File, b: File): Boolean = norm(a) == norm(b)

    fun norm(f: File): String {
        var p = f.absoluteFile.normalize().path.trimEnd('/')
        for ((alias, real) in ALIASES) if (p == alias || p.startsWith("$alias/")) { p = real + p.removePrefix(alias); break }
        return p
    }

    private val ALIASES = listOf(
        "/sdcard" to "/storage/emulated/0",
        "/storage/self/primary" to "/storage/emulated/0",
        "/mnt/sdcard" to "/storage/emulated/0",
        "/mnt/user/0/primary" to "/storage/emulated/0",
        "/data/media/0" to "/storage/emulated/0",
    )

    /** the folder is the shared `Download/IRIDIS/<sub>` (by path, aliases included). Pure. */
    fun isShared(dir: File, downloadsDir: File?, sub: String = SUB): Boolean =
        downloadsDir != null && samePath(dir, sharedDir(downloadsDir, sub))

    /** Keeps gallery / music apps from scanning multi-GB weights. */
    fun ensureNoMedia(dir: File) {
        runCatching { File(dir, NOMEDIA).takeIf { !it.exists() }?.createNewFile() }
    }

    /** Merges per-folder listings (in [Location.listDirs] order): the first folder with a name wins. */
    fun <T> merge(listings: List<List<T>>, name: (T) -> String): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>()
        for (l in listings) for (m in l) if (seen.add(name(m))) out += m
        return out
    }

    data class MovePlan(val move: List<File>, val skipped: List<File>)

    /**
     * What a 「移到 下载/IRIDIS/models」 would move: every model entry of the private folder ([isModel]),
     * except one already in the shared folder under the same name or one in [keep] (in use).
     */
    fun planMove(privateDir: File, sharedDir: File, isModel: (File) -> Boolean, keep: Set<String> = emptySet()): MovePlan {
        val entries = privateDir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") && !it.name.endsWith(ModelRepository.PART_SUFFIX) && isModel(it) }
            .sortedBy { it.name }
        val (skip, move) = entries.partition { File(sharedDir, it.name).exists() || it.absolutePath in keep || it.name in keep }
        return MovePlan(move, skip)
    }

    fun totalBytes(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /**
     * Moves [src] (file or folder) into [dstDir]. A plain rename first; across FUSE / volumes that fails,
     * so it copies into `<name>.part`, checks every size, renames, then deletes the source. [onBytes] gets
     * the bytes copied so far for this entry. The source is only deleted after a verified copy.
     */
    fun moveEntry(
        src: File,
        dstDir: File,
        checkCancel: () -> Unit = {},
        /** The fast path; tests pass `{ _, _ -> false }` to force the copy (as across FUSE). */
        rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) },
        onBytes: (Long) -> Unit = {},
    ): File {
        dstDir.mkdirs()
        val target = File(dstDir, src.name)
        if (target.exists()) throw IOException(I18n.s(R.string.data_f95dbe, src.name))
        val total = totalBytes(src)
        if (rename(src, target)) { onBytes(total); return target }
        val part = File(dstDir, src.name + ModelRepository.PART_SUFFIX)
        part.deleteRecursively()
        try {
            var done = 0L
            val buf = ByteArray(1 shl 20)
            fun copyFile(from: File, to: File) {
                to.parentFile?.mkdirs()
                from.inputStream().use { ins ->
                    to.outputStream().use { os ->
                        while (true) {
                            checkCancel()
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            onBytes(done)
                        }
                    }
                }
                if (to.length() != from.length()) throw IOException(I18n.s(R.string.data_81fbbb, from.name))
            }
            if (src.isDirectory) {
                src.walkTopDown().filter { it.isFile }.forEach { f -> copyFile(f, File(part, f.relativeTo(src).path)) }
            } else copyFile(src, part)
            if (totalBytes(part) != total) throw IOException(I18n.s(R.string.data_81fbbb, src.name))
            if (!part.renameTo(target)) throw IOException(I18n.s(R.string.data_04dd9d))
        } catch (t: Throwable) {
            part.deleteRecursively()
            throw t
        }
        if (src.isDirectory) src.deleteRecursively() else src.delete()
        return target
    }
}

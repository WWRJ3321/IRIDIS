package com.iris.localai.data

import com.iris.localai.agent.WorkspaceResolver
import java.io.File
import java.io.IOException

/**
 * 2.1.0: where LLM models live. With 全部文件访问 they go to the public `Download/IRIDIS/models`, which
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
     * 2.1.1: also used for the voice packs ([sub] = `voice`), with the same fallback.
     */
    fun resolve(
        allFilesAccess: Boolean,
        storageState: String?,
        downloadsDir: File?,
        privateDir: File,
        usable: (File) -> Boolean = WorkspaceResolver::probe,
        sub: String = SUB,
    ): Location {
        val shared = downloadsDir?.let { sharedDir(it, sub) }
        val ok = allFilesAccess && storageState == WorkspaceResolver.MOUNTED && shared != null && usable(shared)
        if (ok) ensureNoMedia(shared!!)
        return Location(if (ok) shared!! else privateDir, ok, shared, privateDir)
    }

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
        if (target.exists()) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_f95dbe, src.name))
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
                if (to.length() != from.length()) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_81fbbb, from.name))
            }
            if (src.isDirectory) {
                src.walkTopDown().filter { it.isFile }.forEach { f -> copyFile(f, File(part, f.relativeTo(src).path)) }
            } else copyFile(src, part)
            if (totalBytes(part) != total) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_81fbbb, src.name))
            if (!part.renameTo(target)) throw IOException(com.iris.localai.I18n.s(com.iris.localai.R.string.data_04dd9d))
        } catch (t: Throwable) {
            part.deleteRecursively()
            throw t
        }
        if (src.isDirectory) src.deleteRecursively() else src.delete()
        return target
    }
}

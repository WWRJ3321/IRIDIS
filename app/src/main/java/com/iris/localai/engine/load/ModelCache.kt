package com.iris.localai.engine.load

import com.iris.localai.engine.*
import com.iris.localai.engine.resources.*
import android.content.Context
import java.io.File

/**
 * the compile / weight caches one model left on disk, so its sheet can show their size and
 * clear them (user: 模型单独…还可以清除缓存).
 *  - LiteRT-LM: `filesDir/litert-cache/<file name>_<mtime>_<size>…` (GPU program + weight caches).
 *  - MNN: `cacheDir/mnn/<hash of file name>/`.
 * GGUF (llama.cpp) keeps no cache of its own.
 */
object ModelCache {
    const val LITERT_DIR = "litert-cache"

    fun files(context: Context, fileName: String): List<File> =
        litert(File(context.filesDir, LITERT_DIR), fileName) + mnn(File(context.cacheDir, "mnn"), fileName)

    /** Files in [root] whose name belongs to [fileName] (LiteRT-LM names caches `<file>_<mtime>_<size><suffix>`). */
    fun litert(root: File, fileName: String): List<File> =
        root.listFiles().orEmpty().filter { it.isFile && belongs(it.name, fileName) }

    fun mnn(root: File, fileName: String): List<File> {
        val dir = File(root, mnnDirName(fileName))
        return if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.toList() else emptyList()
    }

    fun mnnDirName(fileName: String): String = fileName.hashCode().toUInt().toString(16)

    /** `<fileName>_<digits>_<digits>…` — not another model whose name merely starts the same. */
    fun belongs(cacheName: String, fileName: String): Boolean {
        if (!cacheName.startsWith(fileName + "_")) return false
        val rest = cacheName.substring(fileName.length + 1)
        return Regex("^\\d+_\\d+").containsMatchIn(rest)
    }

    fun bytes(files: List<File>): Long = files.sumOf { it.length() }

    /** Deletes the caches; returns (files deleted, bytes freed). */
    fun clear(context: Context, fileName: String): Pair<Int, Long> {
        val list = files(context, fileName)
        var n = 0; var b = 0L
        for (f in list) { val len = f.length(); if (f.delete()) { n++; b += len } }
        File(File(context.cacheDir, "mnn"), mnnDirName(fileName)).takeIf { it.isDirectory }?.deleteRecursively()
        return n to b
    }
}

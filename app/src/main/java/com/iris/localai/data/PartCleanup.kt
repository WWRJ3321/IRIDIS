package com.iris.localai.data

import java.io.File

/**
 * 「清除已下载」 for a paused download — removes its `.part` files / folders and the
 * parallel-download segment files (`<name>.part.s<start>`). Pure name matching is unit-tested.
 */
object PartCleanup {
    /** Names in a folder listing that belong to one of [parts] (the part itself or its segments). */
    fun victims(names: Collection<String>, parts: Collection<String>): List<String> =
        names.filter { n -> parts.any { p -> n == p || isSegmentOf(n, p) } }

    private fun isSegmentOf(name: String, part: String): Boolean {
        if (!name.startsWith("$part.s")) return false
        val tail = name.substring(part.length + 2)
        return tail.isNotEmpty() && tail.all { it.isDigit() }
    }

    /** Deletes them in [dir]; returns the bytes freed. An emptied model folder (not [keepDir]) goes too. */
    fun discard(dir: File, parts: Collection<String>, keepDir: File? = null): Long {
        val names = dir.list()?.toList().orEmpty()
        var freed = 0L
        victims(names, parts).forEach { n ->
            val f = File(dir, n)
            freed += if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
        if (keepDir != null && dir.absolutePath != keepDir.absolutePath && dir.list()?.isEmpty() == true) dir.delete()
        return freed
    }
}

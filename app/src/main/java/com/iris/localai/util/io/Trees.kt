package com.iris.localai.util.io

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** File-tree helpers that never follow symbolic links. */
object Trees {
    /** Deletes [root] and everything under it; a symlink is removed as a link, its target untouched. */
    fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { runCatching { Files.delete(file) }; return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult { runCatching { Files.delete(file) }; return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { runCatching { Files.delete(dir) }; return FileVisitResult.CONTINUE }
        })
    }
}

package com.iris.localai.agent.workspace

import com.iris.localai.agent.core.*
import com.iris.localai.agent.tools.*
import com.iris.localai.agent.web.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class SandboxException(message: String) : Exception(message)

/**
 * every path a tool touches resolves inside the app-private workspace
 * (`filesDir/workspace/`). Rejected: `..` segments, `~`, NUL, absolute paths outside the root and
 * anything whose canonical path (symlinks resolved) leaves the root. Pure JVM, unit-tested.
 */
class WorkspaceSandbox(root: File) {
    val root: File = root.apply { mkdirs() }.canonicalFile

    fun resolve(path: String?): File {
        var p = (path ?: "").trim().trim('"', '\'', '`').replace('\\', '/')
        if ('\u0000' in p) throw SandboxException("路径含非法字符")
        if (p.startsWith("~")) throw SandboxException("不允许访问工作区外的路径：$path")
        if (p.startsWith("/")) {
            // An absolute path is only accepted when it already points into the workspace.
            val abs = File(p)
            val canon = runCatching { abs.canonicalFile }.getOrNull() ?: throw SandboxException("无效路径：$path")
            if (!inside(canon)) throw SandboxException("不允许访问工作区外的路径：$path")
            p = canon.path.removePrefix(root.path).trimStart('/')
        }
        val segments = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw SandboxException("路径不能包含 ..：$path")
        val file = if (segments.isEmpty()) root else File(root, segments.joinToString(File.separator))
        val canon = runCatching { file.canonicalFile }.getOrNull() ?: throw SandboxException("无效路径：$path")
        if (!inside(canon)) throw SandboxException("路径经符号链接指向工作区外：$path")
        return canon
    }

    fun inside(f: File): Boolean = f.path == root.path || f.path.startsWith(root.path + File.separator)

    /** Workspace-relative path with forward slashes ("." for the root). */
    fun relative(f: File): String {
        val c = f.canonicalFile
        if (c.path == root.path) return "."
        return c.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')
    }
}

@Serializable
data class UndoMeta(
    val path: String, val existed: Boolean, val action: String, val time: Long,
    /** workspace root the snapshot belongs to ("" = 1.9.0, the private workspace). */
    val root: String = "",
)

/**
 * snapshot of a file taken right before write_file / edit_file / delete_file changes it.
 * 「撤销上次修改」 restores the newest one (repeatable, up to [max] steps back). Stored outside
 * the workspace ([dir] = filesDir/agent-undo) so tools can never see or edit their own undo data.
 */
class UndoStore(private val dir: File, private val max: Int = 20) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    init { dir.mkdirs() }

    private fun entries(): List<Long> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".meta") }
        .mapNotNull { it.name.removeSuffix(".meta").toLongOrNull() }
        .sorted()

    fun snapshot(sandbox: WorkspaceSandbox, file: File, action: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val ids = entries()
        val id = (ids.lastOrNull() ?: 0L) + 1
        val existed = file.isFile
        if (existed) file.copyTo(File(dir, "$id.bin"), overwrite = true)
        File(dir, "$id.meta").writeText(json.encodeToString(UndoMeta.serializer(), UndoMeta(sandbox.relative(file), existed, action, now, sandbox.root.path)))
        // Keep the newest [max].
        val all = ids + id
        all.dropLast(max).forEach { old -> File(dir, "$old.meta").delete(); File(dir, "$old.bin").delete() }
    }

    /** snapshot of a file only reachable through the SAF grant ([before] = its bytes, null = new file). */
    fun snapshotBytes(sandbox: WorkspaceSandbox, file: File, before: ByteArray?, action: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val ids = entries()
        val id = (ids.lastOrNull() ?: 0L) + 1
        if (before != null) File(dir, "$id.bin").writeBytes(before)
        File(dir, "$id.meta").writeText(json.encodeToString(UndoMeta.serializer(), UndoMeta(sandbox.relative(file), before != null, action, now, sandbox.root.path)))
        val all = ids + id
        all.dropLast(max).forEach { old -> File(dir, "$old.meta").delete(); File(dir, "$old.bin").delete() }
    }

    fun peek(): UndoMeta? = synchronized(lock) {
        val id = entries().lastOrNull() ?: return null
        runCatching { json.decodeFromString(UndoMeta.serializer(), File(dir, "$id.meta").readText()) }.getOrNull()
    }

    fun count(): Int = synchronized(lock) { entries().size }

    /** Restores the newest snapshot; returns what was done, or null when there is nothing to undo. */
    fun undoLast(sandbox: WorkspaceSandbox): String? = synchronized(lock) {
        val id = entries().lastOrNull() ?: return null
        val metaFile = File(dir, "$id.meta")
        val bin = File(dir, "$id.bin")
        val meta = runCatching { json.decodeFromString(UndoMeta.serializer(), metaFile.readText()) }.getOrNull()
        try {
            if (meta == null) return "撤销记录已损坏，已丢弃"
            // never restore into a different workspace location than the one it came from.
            if (meta.root.isNotEmpty() && meta.root != sandbox.root.path) return "这条撤销记录属于另一个工作区位置（${meta.root}），已丢弃"
            val target = sandbox.resolve(meta.path)
            if (meta.existed) {
                target.parentFile?.mkdirs()
                bin.copyTo(target, overwrite = true)
                "已恢复 ${meta.path}（撤销${meta.action}）"
            } else {
                if (target.isFile) target.delete()
                "已删除新建的 ${meta.path}（撤销${meta.action}）"
            }
        } finally {
            metaFile.delete(); bin.delete()
        }
    }

    fun clear() = synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }
}

package com.iris.localai.agent

import java.io.File

/**
 * 2.1.1: layout of the public `Download/IRIDIS` folder. The root is the app's resource / config
 * folder; only `workspace/` is the agent's sandbox:
 *
 * ```
 * Download/IRIDIS/
 *   models/     text models (ModelDirs, with 全部文件访问)
 *   voice/      speech packs (VoiceModelStore, with 全部文件访问)
 *   skills/     user skills (SkillCatalog; built-in ones stay in the APK)
 *   knowledge/  知识库 files (KnowledgeBase; read by kb_search, never by the file tools)
 *   config/     default place for backups / exported logs
 *   workspace/  the agent sandbox: file tools, the SAF view
 * ```
 *
 * Before 2.1.1 the whole root was the workspace, so user files sit next to `models/`. They are moved
 * into `workspace/` once ([plan] + [migrate]); a [MARKER] file at the root stops a second run.
 * Pure java.io, unit-tested (V211StorageLayoutTest); the SAF variant reuses [plan].
 */
object StorageLayout {
    const val MODELS = "models"
    const val VOICE = "voice"
    const val SKILLS = "skills"
    const val KNOWLEDGE = "knowledge"
    const val CONFIG = "config"
    const val WORKSPACE = "workspace"
    /** 应用私有目录 mode: `filesDir/iridis` holds skills/ and knowledge/ (beside `filesDir/workspace`). */
    const val PRIVATE_APP_DIR = "iridis"

    /** Names at the root that belong to the app and are never moved into the workspace. */
    val RESERVED = listOf(MODELS, VOICE, SKILLS, KNOWLEDGE, CONFIG, WORKSPACE)

    /** Written at the root after a complete migration. */
    const val MARKER = ".iridis-layout-v2"
    /** Added before the extension when the workspace already has an entry of that name. */
    const val CONFLICT_SUFFIX = "（迁移）"

    /** User-facing spelling (same as WorkspaceLocation / ModelDirs). */
    const val ROOT_LABEL = "下载/${WorkspaceResolver.FOLDER}"
    fun label(sub: String) = "$ROOT_LABEL/$sub"

    /** SAF document id of `Download/IRIDIS/<sub>` on the primary volume. */
    fun docId(sub: String) = "${WorkspaceResolver.TREE_DOC_ID}/$sub"
    /** `Download/IRIDIS/config`: where backups and exported logs are offered first. */
    fun configDir(downloadsDir: File) = File(WorkspaceResolver.downloadsRoot(downloadsDir), CONFIG)

    /** One line per folder, for 存储空间 and the README. */
    val SUMMARY = "$ROOT_LABEL：$MODELS 文本模型 · $VOICE 语音模型 · $SKILLS 技能 · $KNOWLEDGE 知识库 · $CONFIG 备份与日志 · $WORKSPACE 智能体工作区"

    /** Case-insensitive: shared storage on Android is. */
    fun isReserved(name: String) = RESERVED.any { it.equals(name, ignoreCase = true) }

    /** App bookkeeping at the root (marker, probe, `.nomedia`) — left alone. */
    fun isInternal(name: String) =
        name.startsWith(".iridis-", ignoreCase = true) || name.startsWith(".iris-", ignoreCase = true) || name.equals(".nomedia", ignoreCase = true)

    fun shouldMove(name: String) = name.isNotEmpty() && name != "." && name != ".." && !isReserved(name) && !isInternal(name)

    data class Move(val from: String, val to: String) {
        val renamed: Boolean get() = from != to
    }

    data class Plan(val moves: List<Move>, val kept: List<String>)

    /**
     * What to move from the root into `workspace/`: every root entry except the reserved folders and
     * app bookkeeping. A name the workspace already has (case-insensitive) gets [CONFLICT_SUFFIX]
     * (`a.txt` → `a（迁移）.txt`, then `a（迁移）2.txt` …), so nothing is ever overwritten.
     */
    fun plan(rootNames: Collection<String>, workspaceNames: Collection<String>): Plan {
        val taken = workspaceNames.mapTo(HashSet()) { it.lowercase() }
        val moves = ArrayList<Move>()
        val kept = ArrayList<String>()
        for (name in rootNames.distinct().sorted()) {
            if (!shouldMove(name)) { kept += name; continue }
            val to = if (name.lowercase() in taken) alternative(name, taken) else name
            taken += to.lowercase()
            moves += Move(name, to)
        }
        return Plan(moves, kept)
    }

    /** `a.txt` → `a（迁移）.txt`; `.git` / `notes` keep no extension. */
    fun alternative(name: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (true) {
            val c = base + CONFLICT_SUFFIX + (if (i > 1) i.toString() else "") + ext
            if (c.lowercase() !in taken) return c
            i++
        }
    }

    data class Result(val moved: Int, val renamed: Int, val failed: List<String>) {
        val ok: Boolean get() = failed.isEmpty()
        companion object { val NONE = Result(0, 0, emptyList()) }
    }

    /**
     * Whether the marker may be written: nothing failed, and the app saw every entry
     * ([fullView] = 全部文件访问 or a SAF grant of the root). Without either, files the user put
     * there are invisible, so the move is tried again on the next start.
     */
    fun complete(r: Result, fullView: Boolean) = r.ok && fullView

    /**
     * File pass + SAF pass. The SAF pass ([saf], null = no grant) re-plans whatever is still at the
     * root, so when it ran, its failures are the final ones; otherwise the File pass's are.
     */
    fun combine(direct: Result, saf: Result?): Result =
        if (saf == null) direct else Result(direct.moved + saf.moved, direct.renamed + saf.renamed, saf.failed)

    /** One-line notice after a run that moved something; null = nothing to say. */
    fun notice(r: Result): String? = when {
        r.moved == 0 && r.ok -> null
        r.ok -> "已把 ${r.moved} 项原有文件移到 ${label(WORKSPACE)}" + if (r.renamed > 0) "（${r.renamed} 项重名，加了「$CONFLICT_SUFFIX」后缀）" else ""
        else -> "移到 ${label(WORKSPACE)} 未完成：${r.failed.size} 项没有移动（${r.failed.take(3).joinToString("、")}），原文件仍在 $ROOT_LABEL"
    }

    fun markerExists(root: File) = File(root, MARKER).exists()

    fun writeMarker(root: File): Boolean = runCatching { File(root, MARKER).apply { if (!exists()) writeText("2\n") }.exists() }.getOrDefault(false)

    /**
     * Moves the root's user entries into `root/workspace` (created when missing). [move] is the
     * per-entry move; the default renames and falls back to a verified copy + delete.
     * A root entry called `workspace` that is not a folder blocks the run (reported as failed).
     */
    fun migrate(root: File, move: (File, File) -> Boolean = ::moveTree): Result {
        if (!root.isDirectory) return Result.NONE
        val ws = File(root, WORKSPACE)
        if (ws.exists() && !ws.isDirectory) return Result(0, 0, listOf(WORKSPACE))
        val names = root.list()?.toList().orEmpty()
        val plan = plan(names, ws.list()?.toList().orEmpty())
        if (plan.moves.isEmpty()) return Result.NONE
        ws.mkdirs()
        var moved = 0; var renamed = 0
        val failed = ArrayList<String>()
        for (m in plan.moves) {
            val src = File(root, m.from)
            val dst = File(ws, m.to)
            val ok = !dst.exists() && runCatching { move(src, dst) }.getOrDefault(false)
            if (ok) { moved++; if (m.renamed) renamed++ } else failed += m.from
        }
        return Result(moved, renamed, failed)
    }

    /**
     * Rename, else copy (never overwriting) + compare sizes + delete the source. Symbolic links are
     * moved as links by the rename only (never followed into a copy).
     */
    fun moveTree(src: File, dst: File): Boolean {
        if (dst.exists()) return false
        if (src.renameTo(dst)) return true
        if (java.nio.file.Files.isSymbolicLink(src.toPath())) return false
        val copied = runCatching { src.copyRecursively(dst, overwrite = false) }.getOrDefault(false)
        if (!copied || size(src) != size(dst)) { dst.deleteRecursively(); return false }
        return src.deleteRecursively()
    }

    private fun size(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    // ---------------------------------------------------------------- private workspace (应用私有目录)

    /** In 应用私有目录 mode, skills and the 知识库 live in `filesDir/iridis` instead of the workspace. */
    val EXTRACTED = listOf(SKILLS, KNOWLEDGE)

    /**
     * Which of [EXTRACTED] to move out of the private workspace into the private resource root:
     * present in the workspace and absent from the root (a clash is left where it is).
     */
    fun planExtract(workspaceNames: Collection<String>, rootNames: Collection<String>): List<String> {
        val inRoot = rootNames.mapTo(HashSet()) { it.lowercase() }
        return EXTRACTED.mapNotNull { n -> workspaceNames.firstOrNull { it.equals(n, ignoreCase = true) } }
            .filter { it.lowercase() !in inRoot }
    }

    /** Moves skills/ and knowledge/ out of [workspace] into [root] once. */
    fun extract(workspace: File, root: File, move: (File, File) -> Boolean = ::moveTree): Result {
        val names = planExtract(workspace.list()?.toList().orEmpty(), root.list()?.toList().orEmpty())
        if (names.isEmpty()) return Result.NONE
        root.mkdirs()
        var moved = 0
        val failed = ArrayList<String>()
        for (n in names) {
            val target = File(root, n.lowercase())
            if (runCatching { move(File(workspace, n), target) }.getOrDefault(false)) moved++ else failed += n
        }
        return Result(moved, 0, failed)
    }
}

/** SAF paths relative to the granted `Download/IRIDIS` tree. Pure. */
object SafPaths {
    /** [base] segments + [rel] segments; null when [rel] tries to leave with `..`. */
    fun join(base: List<String>, rel: String): List<String>? {
        val segs = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in segs) return null
        return base + segs
    }

    /** The agent's view: only `workspace/` inside the tree. */
    val WORKSPACE: List<String> = listOf(StorageLayout.WORKSPACE)
    /** App-internal readers (知识库): the whole tree. */
    val ROOT: List<String> = emptyList()
}

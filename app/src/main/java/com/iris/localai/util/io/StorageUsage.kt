package com.iris.localai.util

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * 1.14.3 设置 › 存储空间: how much the app keeps on disk, by category. Pure (java.io / java.nio only,
 * no Android types) so the whole scan runs on plain temp folders in V1143StorageTest; the screen
 * builds [Paths] from the real app directories (StorageViewModel.paths) and calls [scan] on
 * Dispatchers.IO.
 *
 * Symbolic links are never followed (a folder may hold absolute links such as
 * `/bin/sh → /bin/busybox`, which on the phone would point into Android's own /system).
 */
object StorageUsage {

    enum class Category(val title: String) {
        MODELS(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8000f1)),
        MODEL_CACHE(com.iris.localai.I18n.s(com.iris.localai.R.string.util_034fcf)),
        VOICE(com.iris.localai.I18n.s(com.iris.localai.R.string.util_589dad)),
        WORKSPACE(com.iris.localai.I18n.s(com.iris.localai.R.string.util_772bfe)),
        CHATS(com.iris.localai.I18n.s(com.iris.localai.R.string.util_71f3e3)),
        CACHE_LOGS(com.iris.localai.I18n.s(com.iris.localai.R.string.util_f12faf)),
        OTHER(com.iris.localai.I18n.s(com.iris.localai.R.string.util_0d98c7)),
    }

    /** Stable ids of the entries the screen attaches actions to. */
    object Ids {
        const val MODEL_PREFIX = "model:"
        const val MODEL_LEFTOVER = "model:leftover"
        const val MODEL_LEFTOVER_SHARED = "model:leftover-shared"
        const val CACHE_PREFIX = "cache:"
        const val CACHE_ORPHAN = "cache:orphan"
        const val CACHE_OPENCL = "cache:opencl"
        const val CACHE_NPU = "cache:npu"
        const val VOICE_PREFIX = "voice:"
        const val VOICE_CLONES = "voice:clones"
        const val VOICE_OTHER = "voice:other"
        const val WS_FILES = "ws:files"
        const val WS_SKILLS = "ws:skills"
        const val WS_KNOWLEDGE = "ws:knowledge"
        const val WS_PRIVATE = "ws:private"
        const val WS_UNDO = "ws:undo"
        const val WS_MEMORY = "ws:memory"
        const val CHATS = "chat:conversations"
        const val ATTACHMENTS = "chat:attachments"
        const val HISTORY_INDEX = "chat:index"
        const val CACHE_FILES = "logs:cache"
        const val LOG_FILES = "logs:logs"
        const val RESTORE_STAGE = "logs:restore"
        const val OTHER = "other"
    }

    data class Usage(val bytes: Long, val files: Int) {
        operator fun plus(o: Usage) = Usage(bytes + o.bytes, files + o.files)
        companion object { val ZERO = Usage(0, 0) }
    }

    /**
     * One line of a category. [external] = outside the app's own storage (the agent workspace in
     * Download/IRISLocalAI): listed, but not part of the total or the bar.
     */
    data class Entry(
        val id: String,
        val label: String,
        val bytes: Long,
        val files: Int = 0,
        val count: Int? = null,
        val external: Boolean = false,
        /** 2.3.1: a model entry whose size includes a paired vision projector (mmproj). */
        val withProjector: Boolean = false,
    )

    data class Group(val category: Category, val entries: List<Entry>) {
        val bytes: Long get() = entries.sumOf { it.bytes }
        /** Only what is inside the app's storage (drives the bar). */
        val appBytes: Long get() = entries.filterNot { it.external }.sumOf { it.bytes }
        fun entry(id: String) = entries.firstOrNull { it.id == id }
    }

    data class Report(val total: Long, val groups: List<Group>, val leftovers: List<Leftover> = emptyList()) {
        fun group(c: Category): Group? = groups.firstOrNull { it.category == c }
    }

    data class ModelRef(val id: String, val label: String, val fileName: String, val path: File)

    // ---------------------------------------------------------------- 2.3.1 leftovers in the models dirs

    enum class LeftoverKind { PARTIAL, PROJECTOR, UNKNOWN }

    /**
     * One file / folder in a models dir that is not a model and not paired with one.
     * [active] = a .part of a download that is running or paused (never deleted by 「清理未完成的下载」).
     * [shared] = in Download/IRIDIS/models (entry [Ids.MODEL_LEFTOVER_SHARED]).
     */
    data class Leftover(val file: File, val bytes: Long, val kind: LeftoverKind, val active: Boolean, val shared: Boolean) {
        val name: String get() = file.name
    }

    private val PARTIAL_SUFFIXES = listOf(".part", ".tmp", ".temp", ".download", ".crdownload", ".partial")

    /** Interrupted download / import: `.part` (ModelRepository / MnnHub / HttpDownload) or another temp name. */
    fun isPartialName(name: String): Boolean {
        val n = name.lowercase()
        return PARTIAL_SUFFIXES.any { n.endsWith(it) } || n.startsWith(".tmp") || n.startsWith(".pending-")
    }

    /**
     * Files next to [model] that belong to it: split parts 2..N and the vision projector llama.cpp would pair
     * with it (same rules as [com.iris.localai.engine.llama.MmprojPairing.pick]). Names only; pure.
     */
    fun companions(model: String, siblings: Collection<String>): Set<String> {
        val out = siblings.filter { com.iris.localai.data.companionOf(model, it) }.toMutableSet()
        com.iris.localai.engine.llama.MmprojPairing.pick(model, siblings)?.let { out += it }
        return out
    }

    /** Model file → its companion files in [dir] (projector / split parts), for the models directly in [dir]. */
    fun pairedIn(dir: File?, models: List<ModelRef>): Map<String, List<File>> {
        if (dir == null) return emptyMap()
        val children = dir.listFiles().orEmpty().filter { it.isFile }
        val names = children.map { it.name }
        return models.filter { canon(it.path.parentFile ?: it.path) == canon(dir) }.associate { m ->
            val c = companions(m.fileName, names)
            m.id to children.filter { it.name in c }
        }
    }

    /**
     * What sits in [dir] besides the models (and their companions): classified. [protectedParts] = names of
     * `.part` files / folders of downloads running or paused right now.
     */
    fun leftovers(dir: File?, models: List<ModelRef>, protectedParts: Set<String>, shared: Boolean): List<Leftover> {
        if (dir == null) return emptyList()
        val owned = pairedIn(dir, models).values.flatten().map { canon(it) }.toSet()
        return dir.listFiles().orEmpty()
            .filter { f -> canon(f) !in owned && models.none { isUnder(it.path, f) || isUnder(f, it.path) } && f.name != ".nomedia" }
            .map { f ->
                val kind = when {
                    isPartialName(f.name) -> LeftoverKind.PARTIAL
                    com.iris.localai.data.isProjectorFile(f.name) -> LeftoverKind.PROJECTOR
                    else -> LeftoverKind.UNKNOWN
                }
                Leftover(f, sizeOf(f), kind, active = kind == LeftoverKind.PARTIAL && f.name in protectedParts, shared = shared)
            }
            .sortedByDescending { it.bytes }
    }

    /** Leftovers 「清理未完成的下载」 removes: partial files not belonging to a running / paused download. */
    fun clearablePartials(list: List<Leftover>): List<Leftover> = list.filter { it.kind == LeftoverKind.PARTIAL && !it.active }
    data class VoiceRef(val id: String, val label: String, val dir: File)

    /**
     * Where everything lives. [roots] = the app's whole footprint (data dir, external files dir,
     * external cache dir); the total is their size, so nothing below is counted twice.
     */
    data class Paths(
        val roots: List<File>,
        val modelsDir: File,
        val litertCache: File,
        val mnnCache: File,
        val openclCache: File,
        val npuRuntime: File,
        val voiceRoot: File,
        val voiceClones: File,
        val workspace: File,
        val privateWorkspace: File,
        val undo: File,
        val memory: File,
        val conversations: File,
        val attachments: File,
        val historyDb: List<File>,
        val cacheDir: File,
        val externalCache: File?,
        val logFiles: List<File>,
        val restoreStage: File,
        /** 2.1.0: `Download/IRIDIS/models` (outside the app's storage: listed, not in the total); null = not used. */
        val sharedModelsDir: File? = null,
        /** 2.1.1: user skills — `Download/IRIDIS/skills` beside the workspace (inside it before). */
        val skillsDir: File = File(workspace, SKILLS_DIR),
        /** 2.1.1: 知识库 files (`Download/IRIDIS/knowledge`); null = not listed. */
        val knowledgeDir: File? = null,
        /** Children of [cacheDir] that belong to other categories or are live (never cleared as 「缓存」). */
        val cacheKeep: Set<String> = CACHE_KEEP,
    )

    /** cacheDir/mnn and cacheDir/opencl are model caches; npu-dl is a running NPU download; WebView is the browser's own. */
    val CACHE_KEEP = setOf("mnn", "opencl", "npu-dl", "WebView")

    /** Skills folder name (SkillCatalog.DIR); 2.1.1: under Download/IRIDIS, no longer in the workspace. */
    const val SKILLS_DIR = "skills"

    /** Attachments younger than this may belong to a message still being written in the composer. */
    const val ATTACHMENT_MIN_AGE_MS = 60L * 60 * 1000

    // ---------------------------------------------------------------- measuring

    private fun attrs(f: File): BasicFileAttributes? =
        runCatching { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()

    /** Bytes and file count under [f] (a file or a folder). Links count as themselves, never their target. */
    fun usage(f: File?): Usage {
        if (f == null) return Usage.ZERO
        val top = attrs(f) ?: return Usage.ZERO
        if (!top.isDirectory) return Usage(top.size(), 1)
        var bytes = 0L
        var files = 0
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (child in d.listFiles().orEmpty()) {
                val a = attrs(child) ?: continue
                when {
                    a.isDirectory -> stack.addLast(child)
                    else -> { bytes += a.size(); files++ }
                }
            }
        }
        return Usage(bytes, files)
    }

    fun sizeOf(f: File?): Long = usage(f).bytes

    private fun usageExcept(dir: File?, skip: Set<String>): Usage =
        dir?.listFiles().orEmpty().filter { it.name !in skip }.fold(Usage.ZERO) { acc, f -> acc + usage(f) }

    private fun canon(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** [f] is [root] or somewhere inside it. */
    fun isUnder(f: File, root: File): Boolean {
        val a = canon(f); val r = canon(root)
        return a == r || a.startsWith(r.trimEnd(File.separatorChar) + File.separator)
    }

    // ---------------------------------------------------------------- model caches

    /** Caches in [litertRoot] / [mnnRoot] that belong to [fileName] (same rules as engine.ModelCache). */
    fun modelCacheUsage(litertRoot: File, mnnRoot: File, fileName: String): Usage {
        val lit = litertRoot.listFiles().orEmpty()
            .filter { it.isFile && com.iris.localai.engine.ModelCache.belongs(it.name, fileName) }
            .fold(Usage.ZERO) { acc, f -> acc + usage(f) }
        return lit + usage(File(mnnRoot, com.iris.localai.engine.ModelCache.mnnDirName(fileName)).takeIf { it.isDirectory })
    }

    /**
     * Cache files / folders that belong to no model in [fileNames] (left behind by deleted models).
     * LiteRT load markers (`.loading-…`) of a missing model are included; other hidden files are not.
     */
    fun orphanCaches(litertRoot: File, mnnRoot: File, fileNames: Collection<String>): List<File> {
        val mnnDirs = fileNames.map { com.iris.localai.engine.ModelCache.mnnDirName(it) }.toSet()
        val lit = litertRoot.listFiles().orEmpty().filter { f ->
            f.isFile && fileNames.none { n ->
                com.iris.localai.engine.ModelCache.belongs(f.name, n) || f.name.endsWith("-$n")
            } && (!f.name.startsWith(".") || f.name.startsWith(".loading-"))
        }
        val mnn = mnnRoot.listFiles().orEmpty().filter { it.isDirectory && it.name !in mnnDirs }
        return lit + mnn
    }

    // ---------------------------------------------------------------- the scan

    fun scan(
        p: Paths,
        models: List<ModelRef>,
        voices: List<VoiceRef>,
        conversationCount: Int? = null,
        protectedParts: Set<String> = emptySet(),
    ): Report {
        val roots = p.roots.filter { it.exists() }
        fun outside(f: File) = f.exists() && roots.none { isUnder(f, it) }
        val total = roots.sumOf { sizeOf(it) }
        val groups = ArrayList<Group>()

        // 模型 — every model file / folder (2.3.1: + its paired projector / split parts), then the leftovers.
        val leftoverList = ArrayList<Leftover>()
        run {
            val sharedDir = p.sharedModelsDir?.takeIf { canon(it) != canon(p.modelsDir) }
            val paired = pairedIn(p.modelsDir, models) + pairedIn(sharedDir, models)
            val list = models.map { m ->
                val extra = paired[m.id].orEmpty()
                val u = extra.fold(usage(m.path)) { a, f -> a + usage(f) }
                Entry(Ids.MODEL_PREFIX + m.id, m.label, u.bytes, u.files, external = outside(m.path),
                    withProjector = extra.any { com.iris.localai.data.isProjectorFile(it.name) })
            }
            val own = leftovers(p.modelsDir, models, protectedParts, shared = false)
            val shr = leftovers(sharedDir, models, protectedParts, shared = true)
            leftoverList += own; leftoverList += shr
            val leftover = own.sumOf { it.bytes }
            val sharedLeftover = shr.sumOf { it.bytes }
            groups += Group(Category.MODELS, list.sortedByDescending { it.bytes } +
                listOfNotNull(
                    Entry(Ids.MODEL_LEFTOVER, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6f70b9), leftover, own.size, count = own.size, external = outside(p.modelsDir)).takeIf { own.isNotEmpty() },
                    Entry(Ids.MODEL_LEFTOVER_SHARED, com.iris.localai.I18n.s(com.iris.localai.R.string.util_45d2eb), sharedLeftover, shr.size, count = shr.size, external = true).takeIf { shr.isNotEmpty() },
                ))
        }

        // 模型缓存 — per model (LiteRT GPU program / weight caches + MNN cache dir), orphans, GGUF OpenCL kernels, NPU runtime.
        run {
            val per = models.mapNotNull { m ->
                val u = modelCacheUsage(p.litertCache, p.mnnCache, m.fileName)
                if (u.bytes > 0 || u.files > 0) Entry(Ids.CACHE_PREFIX + m.id, m.label, u.bytes, u.files) else null
            }
            val orphan = orphanCaches(p.litertCache, p.mnnCache, models.map { it.fileName }).fold(Usage.ZERO) { a, f -> a + usage(f) }
            val opencl = usage(p.openclCache)
            val npu = usage(p.npuRuntime)
            groups += Group(Category.MODEL_CACHE, per.sortedByDescending { it.bytes } + listOfNotNull(
                Entry(Ids.CACHE_ORPHAN, com.iris.localai.I18n.s(com.iris.localai.R.string.util_1a637f), orphan.bytes, orphan.files).takeIf { orphan.files > 0 },
                Entry(Ids.CACHE_OPENCL, com.iris.localai.I18n.s(com.iris.localai.R.string.util_237874), opencl.bytes, opencl.files).takeIf { opencl.files > 0 },
                Entry(Ids.CACHE_NPU, com.iris.localai.I18n.s(com.iris.localai.R.string.util_e55c25), npu.bytes, npu.files).takeIf { npu.files > 0 },
            ))
        }

        // 语音模型 — each downloaded pack (complete or partial), cloned voices, anything else in the voice dir.
        run {
            // 2.1.1: a pack may be in Download/IRIDIS/voice (outside the app's storage: listed, not in the total).
            val list = voices.mapNotNull { v -> val u = usage(v.dir); if (u.files > 0) Entry(Ids.VOICE_PREFIX + v.id, v.label, u.bytes, u.files, external = outside(v.dir)) else null }
            val clones = usage(p.voiceClones)
            val cloneCount = p.voiceClones.listFiles().orEmpty().count { it.isDirectory }
            val inRoot = voices.filter { isUnder(it.dir, p.voiceRoot) }.sumOf { sizeOf(it.dir) }
            val other = (sizeOf(p.voiceRoot) - inRoot -
                (if (isUnder(p.voiceClones, p.voiceRoot)) clones.bytes else 0L)).coerceAtLeast(0)
            groups += Group(Category.VOICE, list.sortedByDescending { it.bytes } + listOfNotNull(
                Entry(Ids.VOICE_CLONES, com.iris.localai.I18n.s(com.iris.localai.R.string.util_689281), clones.bytes, clones.files, count = cloneCount).takeIf { clones.files > 0 },
                Entry(Ids.VOICE_OTHER, com.iris.localai.I18n.s(com.iris.localai.R.string.util_98b920), other, external = outside(p.voiceRoot)).takeIf { other > 0 },
            ))
        }

        // 技能 / 工作区 — workspace (skills separately), the old private workspace, undo history, long-term memory.
        run {
            val skillsDir = p.skillsDir
            val skills = usage(skillsDir)
            val skillCount = skillsDir.listFiles().orEmpty().count { it.isDirectory }
            // Skills inside the workspace (pre-2.1.1 layout, or a test) are not counted twice.
            val ws = if (canon(skillsDir.parentFile ?: skillsDir) == canon(p.workspace)) usageExcept(p.workspace, setOf(SKILLS_DIR)) else usage(p.workspace)
            val ext = outside(p.workspace)
            val kb = p.knowledgeDir?.takeIf { !isUnder(it, p.workspace) }
            val kbUsage = usage(kb)
            val samePrivate = canon(p.privateWorkspace) == canon(p.workspace)
            val priv = if (samePrivate) Usage.ZERO else usage(p.privateWorkspace)
            val undo = usage(p.undo)
            val mem = usage(p.memory)
            groups += Group(Category.WORKSPACE, listOfNotNull(
                Entry(Ids.WS_FILES, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9371cb), ws.bytes, ws.files, external = ext),
                Entry(Ids.WS_SKILLS, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_699143), skills.bytes, skills.files, count = skillCount, external = outside(skillsDir)).takeIf { skills.files > 0 },
                kb?.let { Entry(Ids.WS_KNOWLEDGE, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7e4333), kbUsage.bytes, kbUsage.files, count = kbUsage.files, external = outside(it)) }?.takeIf { kbUsage.files > 0 },
                Entry(Ids.WS_PRIVATE, com.iris.localai.I18n.s(com.iris.localai.R.string.util_1f6d24), priv.bytes, priv.files).takeIf { priv.files > 0 },
                Entry(Ids.WS_UNDO, com.iris.localai.I18n.s(com.iris.localai.R.string.util_10f81c), undo.bytes, undo.files).takeIf { undo.files > 0 },
                Entry(Ids.WS_MEMORY, com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7), mem.bytes, mem.files).takeIf { mem.files > 0 },
            ))
        }

        // 聊天记录 — conversation JSON files (+ index), copied attachments, the full-text history index.
        run {
            val conv = usage(p.conversations)
            val count = conversationCount ?: p.conversations.listFiles().orEmpty()
                .count { it.isFile && it.name.endsWith(".json") && it.name != "index.json" }
            val att = usage(p.attachments)
            val idx = p.historyDb.fold(Usage.ZERO) { a, f -> a + usage(f) }
            groups += Group(Category.CHATS, listOfNotNull(
                Entry(Ids.CHATS, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_859362), conv.bytes, conv.files, count = count),
                Entry(Ids.ATTACHMENTS, com.iris.localai.I18n.s(com.iris.localai.R.string.util_c9a6ee), att.bytes, att.files, count = att.files),
                Entry(Ids.HISTORY_INDEX, com.iris.localai.I18n.s(com.iris.localai.R.string.util_6e9c11), idx.bytes, idx.files).takeIf { idx.files > 0 },
            ))
        }

        // 缓存与日志 — cacheDir (minus the model caches / live dirs), external cache, logs and crash records, restore leftovers.
        run {
            val cache = usageExcept(p.cacheDir, p.cacheKeep) + usage(p.externalCache)
            val logs = p.logFiles.fold(Usage.ZERO) { a, f -> a + usage(f) }
            val stage = usage(p.restoreStage)
            groups += Group(Category.CACHE_LOGS, listOfNotNull(
                Entry(Ids.CACHE_FILES, com.iris.localai.I18n.s(com.iris.localai.R.string.util_b0d417), cache.bytes, cache.files),
                Entry(Ids.LOG_FILES, com.iris.localai.I18n.s(com.iris.localai.R.string.util_b9dad5), logs.bytes, logs.files).takeIf { logs.files > 0 },
                Entry(Ids.RESTORE_STAGE, com.iris.localai.I18n.s(com.iris.localai.R.string.util_1d0149), stage.bytes, stage.files).takeIf { stage.files > 0 },
            ))
        }

        // 其他 — settings, databases, WebView data, …: what the total has that no category claimed.
        val claimed = groups.sumOf { it.appBytes }
        val other = (total - claimed).coerceAtLeast(0)
        groups += Group(Category.OTHER, listOf(Entry(Ids.OTHER, com.iris.localai.I18n.s(com.iris.localai.R.string.util_30bbce), other)))
        return Report(total, groups, leftoverList)
    }

    /** Bar segments: each category's share of [Report.total] (app storage only), in [Category] order; sums to ≤ 1. */
    fun segments(r: Report): List<Pair<Category, Float>> {
        if (r.total <= 0) return Category.entries.map { it to 0f }
        var left = 1f
        return Category.entries.map { c ->
            val f = ((r.group(c)?.appBytes ?: 0L).toDouble() / r.total).toFloat().coerceIn(0f, left)
            left -= f
            c to f
        }
    }

    // ---------------------------------------------------------------- clearing

    /** Deletes [f] (file or folder) without following links. Returns the bytes freed. */
    fun deleteTree(f: File): Long {
        val a = attrs(f) ?: return 0
        if (!a.isDirectory) { val n = a.size(); return if (f.delete()) n else 0 }
        var freed = 0L
        for (child in f.listFiles().orEmpty()) freed += deleteTree(child)
        f.delete()
        return freed
    }

    /** Deletes what is inside [dir] except the [keep] names; [dir] itself stays. Returns the bytes freed. */
    fun clearChildren(dir: File?, keep: Set<String> = emptySet()): Long =
        dir?.listFiles().orEmpty().filter { it.name !in keep }.sumOf { deleteTree(it) }

    /**
     * Attachment files no saved message points at, older than [minAgeMs] (a newer one may be waiting
     * in the composer). [referenced] = absolute paths from the conversations.
     */
    fun orphanAttachments(dir: File, referenced: Set<String>, now: Long, minAgeMs: Long = ATTACHMENT_MIN_AGE_MS): List<File> {
        val refs = referenced.map { File(it).absolutePath }.toSet()
        return dir.listFiles().orEmpty().filter { f ->
            f.isFile && f.absolutePath !in refs && now - f.lastModified() >= minAgeMs
        }
    }

    /** All attachments except unreferenced ones younger than [minAgeMs] (possibly still in the composer). */
    fun removableAttachments(dir: File, referenced: Set<String>, now: Long, minAgeMs: Long = ATTACHMENT_MIN_AGE_MS): List<File> {
        val refs = referenced.map { File(it).absolutePath }.toSet()
        return dir.listFiles().orEmpty().filter { f ->
            f.isFile && (f.absolutePath in refs || now - f.lastModified() >= minAgeMs)
        }
    }
}

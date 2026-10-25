package com.iris.localai.agent

import com.iris.localai.util.TokenEstimator
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 1.12.0 tool groups: the prompt lists tools under one header per group with one shared hint,
 * instead of repeating the same advice in every tool line (keeps the prompt small as tools grow).
 */
enum class ToolGroup(val title: String, val label: String, val hint: String) {
    FILES("Files", "文件", "workspace-relative paths; read before edit"),
    WEB("Web", "联网", "you are online; never say you have no internet"),
    SHELL("Terminal", "终端", ""),
    PHONE("Phone", "手机", "asks user first"),
    TASKS("Tasks", "定时", ""),
    MEMORY("Memory", "记忆", "use recall for older turns"),
    SKILLS("Skills", "技能", ""),
    /** 2.1.7: on-device image generation (generate_image). No hint: listed under "Other" in the prompt. */
    IMAGE("Image", "生图", ""),
    OTHER("Other", "其他", ""),
}

/** What a tool may do; decides its default permission ([PermissionPolicy.defaultFor]). */
enum class ToolRisk {
    SAFE, READ, WRITE, DELETE, NETWORK,
    /** 1.10.0: changes the long-term memory documents (outside the workspace). */
    MEMORY,
}

data class ToolResult(
    val ok: Boolean,
    /** Sent back to the model (already capped). */
    val output: String,
    /** One line for the step card. */
    val summary: String,
    /** 2.1.0: the plan after a `plan` call, for the step card (never sent to the model). */
    val plan: Plan? = null,
    /** 2.1.7: workspace-relative pictures the tool made (generate_image), shown on the step card. */
    val images: List<String> = emptyList(),
)

/** Everything a tool touches, injected so the tools stay testable on the JVM. */
class ToolEnv(
    val sandbox: WorkspaceSandbox,
    val undo: UndoStore,
    val clock: () -> Long = System::currentTimeMillis,
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
    val fetcher: WebFetcher? = null,
    /** Cap on what one tool result may add to the context (estimated tokens). */
    val outputTokens: Int = 900,
    /** 1.10.0: raw history search for `recall` (null = not available). */
    val history: HistorySearch? = null,
    /** 1.10.0: long-term memory for `memory` (null = memory switched off). */
    val memory: com.iris.localai.memory.MemoryDocs? = null,
    /** The conversation being answered (recall's default scope). */
    val conversationId: String? = null,
    /** Message ids recall leaves out (the request being answered). */
    val recallExclude: Set<String> = emptySet(),
    /** 1.10.0: read-only SAF view of files in the workspace folder that File access cannot see (null = no grant). */
    val external: ExternalFiles? = null,
    /**
     * 1.10.6: added to "not found" errors when files the user put in the workspace may be hidden
     * by scoped storage (Download workspace without 全部文件访问); null = no hint needed.
     */
    val accessHint: String? = null,
    /** 1.10.6: runs run_command (null = not available, e.g. JVM tests without a shell). */
    val shell: ShellRunner? = null,
    /** 1.11.0: which shell [shell] is (decides run_command's refusal rules). */
    val shellKind: CommandBackend = CommandBackend.BUILTIN,
    /** 1.11.0: the in-app WebView browser for `browser` (null = not available). */
    val browser: BrowserDriver? = null,
    /** 1.12.0: run_command prefix rules (设置 › 智能体 › 命令规则). */
    val commandRules: Map<String, Permission> = emptyMap(),
    /** 1.12.0: web_search provider and the SearXNG address. */
    val searchProvider: SearchProvider = SearchProvider.AUTO,
    val searxUrl: String = "",
    /** 2.0.0: the shared in-app browser for 谷歌/必应（浏览器） search (null = not available: HTTP search). */
    val searchBrowser: SearchBrowser? = null,
    /** 1.12.0: phone actions (alarm, calendar, open app …); null = not available. */
    val phone: PhoneActions? = null,
    /** 1.12.0: scheduled tasks; null = not available. */
    val schedules: ScheduleStore? = null,
    /** 1.12.0: skills in the workspace's skills folder. */
    val skills: SkillCatalog? = null,
    /** 1.12.0: the loaded model for process_file (one fresh prompt per part); null = not available. */
    val llm: ChunkLlm? = null,
    /** 1.12.0: how much file text one process_file part may hold (estimated tokens). */
    val chunkTokens: Int = 1200,
    /** 1.14.0: conversation plans (计划模式); null = not available. */
    val plans: PlanStore? = null,
    /** 1.14.0: the tools of this run, for `activate`. */
    val enabledTools: List<AgentTool> = emptyList(),
    /** 2.1.0: the knowledge folder for kb_search (null = empty / not available). */
    val knowledge: KnowledgeSearch? = null,
    /** 2.1.7: on-device image generation for generate_image (null = not available → "no model" error). */
    val imageGen: ImageGenBackend? = null,
    /** 2.1.9: the GUI agent for `screen` (null = not available). */
    val gui: GuiTaskBackend? = null,
)

/** 1.12.0: one stand-alone model call (no chat history), used by process_file. */
fun interface ChunkLlm {
    suspend fun complete(system: String, user: String): String
}

/**
 * 1.12.0: a tool that runs as a coroutine (it calls the model and may take minutes): cancellable
 * with 停止, and reports progress into its step card.
 */
interface SuspendTool : AgentTool {
    suspend fun runSuspend(args: JsonObject, env: ToolEnv, progress: (String) -> Unit): ToolResult
    override fun run(args: JsonObject, env: ToolEnv): ToolResult = throw IllegalStateException("$name runs as a coroutine")
}

/** 1.10.6: "file not found" text for the model, with the access hint when there is one. */
internal fun notFound(path: String, env: ToolEnv): ToolResult =
    ToolResult(false, "Error: file not found: $path" + (env.accessHint?.let { " ($it)" } ?: ""), if (env.accessHint != null) "文件不存在或无权访问" else "文件不存在")

interface AgentTool {
    val name: String
    /** Chinese name for the settings page and the step cards. */
    val label: String
    /** One line for the settings page (Chinese). */
    val description: String
    /** Prompt line: `read_file(path, offset?, limit?): …` (English, compact). */
    val promptLine: String
    val risk: ToolRisk
    val defaultEnabled: Boolean get() = true
    /** 1.11.1: permission when the user set none (null = by [risk], see [PermissionPolicy.defaultFor]). */
    val defaultPermission: Permission? get() = null
    /** 1.12.0: the tool group it is listed under in the prompt and the settings page. */
    val group: ToolGroup get() = ToolGroup.OTHER
    /**
     * 1.12.0: the permission of one call, given the tool's setting [base] (run_command refines it by
     * command prefix). Default: the tool's setting.
     */
    fun permissionFor(args: JsonObject, base: Permission, env: ToolEnv): Permission = base
    /** Runs on a background thread. Throw [SandboxException] / any exception for a failed call. */
    fun run(args: JsonObject, env: ToolEnv): ToolResult
}

/** Caps text to about [tokens] estimated tokens, saying how much was cut. */
fun capTokens(text: String, tokens: Int): String {
    if (TokenEstimator.estimate(text) <= tokens) return text
    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (TokenEstimator.estimate(text.substring(0, mid)) <= tokens) lo = mid else hi = mid - 1
    }
    return text.substring(0, lo) + "\n[TRUNCATED by the tool: showing $lo of ${text.length} characters. This note is not part of the content.]"
}

private const val MAX_FILE_BYTES = 1_000_000L
private const val MAX_WRITE_CHARS = 200_000

private fun requirePath(args: JsonObject): String =
    Args.str(args, "path", "file", "file_path", "filename") ?: throw IllegalArgumentException("缺少参数 path")

private fun looksBinary(bytes: ByteArray): Boolean = bytes.take(4096).any { it == 0.toByte() }

object ReadFileTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "read_file"
    override val label = "读取文件"
    override val description = "读取工作区里的文本文件"
    override val promptLine = "read_file(path, offset?, limit?): ≤200 lines"
    override val risk = ToolRisk.READ

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val path = requirePath(args)
        val f = env.sandbox.resolve(path)
        if (f.isDirectory) return ToolResult(false, "Error: $path is a directory; use list_dir", "是文件夹")
        val bytes: ByteArray = if (f.isFile && f.canRead()) {
            if (f.length() > MAX_FILE_BYTES) return ToolResult(false, "Error: file too large (${f.length()} bytes)", "文件过大")
            f.readBytes()
        } else {
            // 1.10.0: a file the user (or another app) put in Download/IRISLocalAI, read through the SAF grant.
            env.external?.read(env.sandbox.relative(f), MAX_FILE_BYTES)
                ?: return notFound(path, env)
        }
        if (looksBinary(bytes)) return ToolResult(false, "Error: binary file (${bytes.size} bytes), cannot show as text", "二进制文件")
        val lines = String(bytes, Charsets.UTF_8).split("\n")
        val offset = (Args.int(args, "offset", "start", "line") ?: 1).coerceAtLeast(1)
        val limit = (Args.int(args, "limit", "lines", "count") ?: 200).coerceIn(1, 2000)
        val from = (offset - 1).coerceAtMost(lines.size)
        // 1.12.0: stop at whole lines that fit the output budget, so the "continue at" line is exact.
        var to = from
        var used = 0
        val budget = (env.outputTokens - 80).coerceAtLeast(100)
        while (to < lines.size && to - from < limit) {
            val t = TokenEstimator.estimate(lines[to]) + 1
            if (to > from && used + t > budget) break
            used += t; to++
        }
        val body = lines.subList(from, to).joinToString("\n")
        val partial = !(from == 0 && to == lines.size)
        val header = if (partial) "[lines ${from + 1}-$to of ${lines.size}]\n" else ""
        val footer = if (!partial || to >= lines.size) "" else
            "\n[END OF PART — the file continues: read_file(path, offset=${to + 1}). To translate, summarize or convert the whole file use process_file. This note is not part of the content.]"
        val summary = if (partial) "第 ${from + 1}-$to 行 / 共 ${lines.size} 行" else "${lines.size} 行 · ${bytes.size} 字节"
        return ToolResult(true, capTokens(header + body, env.outputTokens) + footer, summary)
    }
}

object WriteFileTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "write_file"
    override val label = "写入文件"
    override val description = "新建或整体覆盖文件（覆盖前自动备份，可撤销）"
    override val promptLine = "write_file(path, content, append?)"
    override val risk = ToolRisk.WRITE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val path = requirePath(args)
        val content = Args.str(args, "content", "text", "data") ?: throw IllegalArgumentException("缺少参数 content")
        if (content.length > MAX_WRITE_CHARS) return ToolResult(false, "Error: content too long", "内容过长")
        val f = env.sandbox.resolve(path)
        if (f == env.sandbox.root || f.isDirectory) return ToolResult(false, "Error: $path is a directory", "是文件夹")
        val rel = env.sandbox.relative(f)
        val n = content.toByteArray(Charsets.UTF_8).size
        // 1.10.6: a file another app created is not writable through File; use the SAF grant.
        val ext = if (!f.isFile || !f.canWrite()) env.external?.read(rel, MAX_FILE_BYTES) else null
        if (ext != null) {
            env.undo.snapshotBytes(env.sandbox, f, ext, "覆盖", env.clock())
            // 1.13.0: append keeps the file's text (it used to overwrite it through the SAF grant).
            val appendMode = Args.bool(args, "append") == true
            val bytes = if (appendMode) ext + content.toByteArray(Charsets.UTF_8) else content.toByteArray(Charsets.UTF_8)
            if (!env.external!!.write(rel, bytes)) return ToolResult(false, "Error: no write access to $rel" + (env.accessHint?.let { " ($it)" } ?: ""), "无写入权限")
            return if (appendMode) ToolResult(true, "OK: appended to $rel ($n bytes)", "已追加 $rel · $n 字节")
            else ToolResult(true, "OK: overwrote $rel ($n bytes)", "已覆盖 $rel · $n 字节")
        }
        val existed = f.isFile
        val append = Args.bool(args, "append") == true && existed
        env.undo.snapshot(env.sandbox, f, if (append) "追加" else if (existed) "覆盖" else "新建", env.clock())
        f.parentFile?.mkdirs()
        if (append) {
            try { f.appendText(content, Charsets.UTF_8) } catch (e: java.io.IOException) {
                return ToolResult(false, "Error: cannot write $rel: ${e.message}", "无写入权限")
            }
            return ToolResult(true, "OK: appended $n bytes to $rel", "已追加 $rel · $n 字节")
        }
        try { f.writeText(content, Charsets.UTF_8) } catch (e: java.io.IOException) {
            return ToolResult(false, "Error: cannot write $rel: ${e.message}" + (env.accessHint?.let { " ($it)" } ?: ""), "无写入权限")
        }
        return ToolResult(true, "OK: ${if (existed) "overwrote" else "created"} $rel ($n bytes)", "${if (existed) "已覆盖" else "已新建"} $rel · $n 字节")
    }
}

object EditFileTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "edit_file"
    override val label = "修改文件"
    override val description = "按「查找 → 替换」修改文件的一部分（修改前自动备份，可撤销）"
    override val promptLine = "edit_file(path, old, new, all?): replace exact text"
    override val risk = ToolRisk.WRITE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val path = requirePath(args)
        val old = Args.str(args, "old", "old_string", "old_text", "search", "find") ?: throw IllegalArgumentException("缺少参数 old")
        val new = Args.str(args, "new", "new_string", "new_text", "replace", "replacement") ?: throw IllegalArgumentException("缺少参数 new")
        val all = Args.bool(args, "all", "replace_all") ?: false
        val f = env.sandbox.resolve(path)
        val rel0 = env.sandbox.relative(f)
        // 1.10.6: files the user put in the workspace (other owner) are edited through the SAF grant.
        val direct = f.isFile && f.canRead() && f.canWrite()
        val extBytes = if (!direct) env.external?.read(rel0, MAX_FILE_BYTES) else null
        if (!direct && extBytes == null) return if (f.isFile) ToolResult(false, "Error: no write access to $path" + (env.accessHint?.let { " ($it)" } ?: ""), "无写入权限") else notFound(path, env)
        if (direct && f.length() > MAX_FILE_BYTES) return ToolResult(false, "Error: file too large", "文件过大")
        val text = if (direct) f.readText(Charsets.UTF_8) else String(extBytes!!, Charsets.UTF_8)
        return when (val r = EditPatch.apply(text, old, new, all)) {
            is EditPatch.Result.Err -> ToolResult(false, "Error: ${r.message}", r.message.substringBefore('；').take(40))
            is EditPatch.Result.Ok -> {
                if (direct) {
                    env.undo.snapshot(env.sandbox, f, "修改", env.clock())
                    f.writeText(r.text, Charsets.UTF_8)
                } else {
                    env.undo.snapshotBytes(env.sandbox, f, extBytes, "修改", env.clock())
                    if (!env.external!!.write(rel0, r.text.toByteArray(Charsets.UTF_8)))
                        return ToolResult(false, "Error: no write access to $rel0" + (env.accessHint?.let { " ($it)" } ?: ""), "无写入权限")
                }
                val rel = rel0
                ToolResult(
                    true,
                    "OK: edited $rel (${r.replacements} replacement${if (r.replacements > 1) "s" else ""}${if (r.fuzzy) ", whitespace-insensitive match" else ""})",
                    "已修改 $rel · ${r.replacements} 处",
                )
            }
        }
    }
}

object ListDirTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "list_dir"
    override val label = "列出目录"
    override val description = "列出工作区文件夹里的文件"
    override val promptLine = "list_dir(path?)"
    override val risk = ToolRisk.READ

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val path = Args.str(args, "path", "dir", "folder") ?: "."
        val d = env.sandbox.resolve(path)
        // 1.10.0: entries only visible through the SAF grant are merged in (by name).
        val ext = env.external?.list(env.sandbox.relative(d)).orEmpty()
        if (!d.isDirectory && ext.isEmpty()) return ToolResult(false, "Error: not a folder: $path", "不是文件夹")
        val own = (d.listFiles() ?: emptyArray()).map { ExternalFiles.Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0) }
        val items = (own + ext.filter { e -> own.none { it.name == e.name } }).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        if (items.isEmpty()) return ToolResult(true, "(empty)", "空文件夹")
        val lines = items.take(200).map { if (it.isDir) it.name + "/" else "${it.name}  (${it.size} B)" }
        val more = if (items.size > 200) "\n…and ${items.size - 200} more" else ""
        return ToolResult(true, capTokens(lines.joinToString("\n") + more, env.outputTokens), "${items.size} 项")
    }
}

object SearchFilesTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "search_files"
    override val label = "搜索文件"
    override val description = "按文件名或内容搜索工作区"
    override val promptLine = "search_files(query, path?, mode?: name|content|both)"
    override val risk = ToolRisk.READ
    private const val MAX_HITS = 40

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val query = Args.str(args, "query", "pattern", "text", "q") ?: throw IllegalArgumentException("缺少参数 query")
        if (query.isBlank()) throw IllegalArgumentException("query 不能为空")
        val mode = (Args.str(args, "mode", "in") ?: "both").lowercase()
        val base = env.sandbox.resolve(Args.str(args, "path", "dir") ?: ".")
        if (!base.isDirectory) return ToolResult(false, "Error: not a folder", "不是文件夹")
        val glob = query.contains('*') || query.contains('?')
        val nameRx = if (glob) Regex("^" + query.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } } + "$", RegexOption.IGNORE_CASE) else null
        val hits = ArrayList<String>()
        var total = 0
        base.walkTopDown().onEnter { env.sandbox.inside(it.canonicalFile) }.forEach { f ->
            if (f == base) return@forEach
            val rel = env.sandbox.relative(f)
            if (mode != "content") {
                val nameHit = nameRx?.matches(f.name) ?: f.name.contains(query, ignoreCase = true)
                if (nameHit) { total++; if (hits.size < MAX_HITS) hits.add(rel + if (f.isDirectory) "/" else "") }
            }
            if (mode != "name" && !glob && f.isFile && f.length() <= MAX_FILE_BYTES && env.sandbox.inside(f.canonicalFile)) {
                val bytes = f.readBytes()
                if (!looksBinary(bytes)) {
                    String(bytes, Charsets.UTF_8).split("\n").forEachIndexed { i, line ->
                        if (line.contains(query, ignoreCase = true)) {
                            total++
                            if (hits.size < MAX_HITS) hits.add("$rel:${i + 1}: ${line.trim().take(160)}")
                        }
                    }
                }
            }
        }
        if (hits.isEmpty()) return ToolResult(true, "No matches for \"$query\"", "无结果")
        val more = if (total > hits.size) "\n…${total - hits.size} more matches" else ""
        return ToolResult(true, capTokens(hits.joinToString("\n") + more, env.outputTokens), "$total 处匹配")
    }
}

object DeleteFileTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "delete_file"
    override val label = "删除文件"
    override val description = "删除文件或空文件夹（删除前自动备份，可撤销）"
    override val promptLine = "delete_file(path)"
    override val risk = ToolRisk.DELETE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val path = requirePath(args)
        val f = env.sandbox.resolve(path)
        if (f == env.sandbox.root) return ToolResult(false, "Error: cannot delete the workspace root", "不能删除工作区根目录")
        val rel = env.sandbox.relative(f)
        return when {
            f.isFile -> {
                env.undo.snapshot(env.sandbox, f, "删除", env.clock())
                f.delete()
                ToolResult(true, "OK: deleted $rel", "已删除 $rel")
            }
            f.isDirectory -> {
                if (!f.list().isNullOrEmpty()) ToolResult(false, "Error: folder not empty: $rel", "文件夹不为空")
                else { f.delete(); ToolResult(true, "OK: deleted folder $rel", "已删除文件夹 $rel") }
            }
            // 1.10.6: a file only visible through the SAF grant.
            else -> env.external?.read(rel, MAX_FILE_BYTES)?.let { bytes ->
                env.undo.snapshotBytes(env.sandbox, f, bytes, "删除", env.clock())
                if (env.external.delete(rel)) ToolResult(true, "OK: deleted $rel", "已删除 $rel")
                else ToolResult(false, "Error: no permission to delete $rel", "无删除权限")
            } ?: notFound(path, env)
        }
    }
}

/** 1.12.0: move or rename inside the workspace (undo restores both ends), so mv needs no shell. */
object MoveFileTool : AgentTool {
    override val group = ToolGroup.FILES
    override val name = "move_file"
    override val label = "移动/重命名"
    override val description = "在工作区内移动或重命名文件（可撤销）"
    override val promptLine = "move_file(from, to)"
    override val risk = ToolRisk.WRITE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val from = Args.str(args, "from", "path", "source", "src", "old") ?: throw IllegalArgumentException("缺少参数 from")
        val to = Args.str(args, "to", "dest", "destination", "target", "new") ?: throw IllegalArgumentException("缺少参数 to")
        val src = env.sandbox.resolve(from)
        var dst = env.sandbox.resolve(to)
        if (!src.isFile) return notFound(from, env)
        if (dst.isDirectory) dst = File(dst, src.name)
        val a = env.sandbox.relative(src); val b = env.sandbox.relative(dst)
        if (src == dst) return ToolResult(true, "OK: $a unchanged", "未变化")
        if (dst.exists()) return ToolResult(false, "Error: $b already exists", "目标已存在")
        env.undo.snapshot(env.sandbox, dst, "新建", env.clock())
        env.undo.snapshot(env.sandbox, src, "移动", env.clock())
        dst.parentFile?.mkdirs()
        val ok = src.renameTo(dst) || runCatching { src.copyTo(dst); src.delete() }.getOrDefault(false)
        return if (ok) ToolResult(true, "OK: moved $a -> $b", "$a → $b") else ToolResult(false, "Error: cannot move $a", "移动失败")
    }
}

/**
 * 1.12.0 `todo`: a short plan for multi-step work (Claude Code TodoWrite / OpenCode todowrite).
 * Small models lose track after a few tool calls; the list comes back in every result. Kept per
 * conversation in memory only.
 */
object TodoTool : AgentTool {
    override val name = "todo"
    override val label = "任务清单"
    override val description = "多步任务时列出步骤并逐项打勾"
    override val promptLine = "todo(items?: [steps], done?: [n]): plan multi-step work, tick steps off"
    override val risk = ToolRisk.SAFE
    private val lists = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, Boolean>>>()

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val key = env.conversationId ?: "_"
        val items = (args["items"] ?: args["steps"] ?: args["todos"])?.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
                ?: (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.split('\n', ';', '；')?.map { it.trim().trimStart('-', '*', ' ') }?.filter { it.isNotEmpty() }
        }
        val list = if (items != null) items.take(12).map { it to false }.toMutableList().also { lists[key] = it } else lists.getOrPut(key) { mutableListOf() }
        val done = (args["done"] ?: args["check"])?.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() }
        }.orEmpty()
        done.forEach { n -> if (n in 1..list.size) list[n - 1] = list[n - 1].first to true }
        if (list.isEmpty()) return ToolResult(true, "No plan yet. Call todo(items: [...]) first.", "暂无清单")
        val text = list.mapIndexed { i, (t, d) -> "${i + 1}. [${if (d) "x" else " "}] $t" }.joinToString("\n")
        val left = list.count { !it.second }
        return ToolResult(true, text + if (left == 0) "\nAll done." else "", if (left == 0) "全部完成" else "${list.size - left}/${list.size} 完成")
    }

    fun clear(conversationId: String?) { lists.remove(conversationId ?: "_") }
}

object CalculatorTool : AgentTool {
    override val name = "calculator"
    override val label = "计算器"
    override val description = "精确计算数学表达式（安全解析，不执行代码）"
    override val promptLine = "calculator(expression): exact math"
    override val risk = ToolRisk.SAFE

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val expr = Args.str(args, "expression", "expr", "input", "query") ?: throw IllegalArgumentException("缺少参数 expression")
        return try {
            val v = Calculator.format(Calculator.evaluate(expr))
            ToolResult(true, "$expr = $v", "= $v")
        } catch (e: CalcException) {
            ToolResult(false, "Error: ${e.message}", e.message ?: "计算失败")
        }
    }
}

object DateTimeTool : AgentTool {
    override val name = "datetime"
    override val label = "日期时间"
    override val description = "获取当前日期、时间与星期"
    override val promptLine = "datetime(timezone?)"
    override val risk = ToolRisk.SAFE
    private val WEEK = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val zone = Args.str(args, "timezone", "tz", "zone")?.let { runCatching { ZoneId.of(it.trim()) }.getOrNull() } ?: env.zone()
        val t = Instant.ofEpochMilli(env.clock()).atZone(zone)
        val s = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)) +
            " ${WEEK[t.dayOfWeek.value - 1]} (${zone.id}, UTC${t.offset.id.replace("Z", "+00:00")})"
        return ToolResult(true, s, s.substringBefore(" ("))
    }
}

object WebFetchTool : AgentTool {
    override val group = ToolGroup.WEB
    override val name = "web_fetch"
    override val label = "网页读取"
    override val description = "联网读取一个网页的文字（和联网搜索一起默认开启，每次询问）"
    override val promptLine = "web_fetch(url): read a page (+images)"
    override val risk = ToolRisk.NETWORK
    // 1.12.0: on by default with web_search (Claude Code / OpenCode pair search with fetch): a
    // search hit the model could not open was a dead end. Still asks (a URL can carry data out).

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val url = Args.str(args, "url", "link", "href") ?: throw IllegalArgumentException("缺少参数 url")
        val fetcher = env.fetcher ?: return ToolResult(false, "Error: network is not available", "不可用")
        val r = fetcher.fetch(url.trim())
        if (r.status !in 200..299) return ToolResult(false, "Error: HTTP ${r.status} for ${r.finalUrl}", "HTTP ${r.status}")
        val html = r.contentType?.contains("html", ignoreCase = true) != false && r.body.contains('<')
        val text = if (html) HtmlText.toText(r.body) else r.body
        // 2.2: a few page images (og:image, <img> with alt) the reply can show as ![alt](url).
        val images = if (html) HtmlText.images(r.body, r.finalUrl) else emptyList()
        val tail = if (images.isEmpty()) "" else "\nImages (show with ![alt](url)):\n" + images.joinToString("\n") { it.markdown() }
        val budget = env.outputTokens - com.iris.localai.util.TokenEstimator.estimate(tail)
        return ToolResult(true, capTokens("[${r.finalUrl}]\n$text", budget.coerceAtLeast(env.outputTokens / 2)) + tail, "${text.length} 字")
    }
}

/** 2.1.7: what generate_image needs from 生图 (the repository + engine), injected so the tool stays JVM-testable. */
interface ImageGenBackend {
    /** Downloaded models that can run here, in catalog order. */
    fun runnableModels(): List<com.iris.localai.imagegen.ImageModel>
    suspend fun generate(
        req: com.iris.localai.imagegen.ImageRequest, model: com.iris.localai.imagegen.ImageModel,
        onProgress: (step: Int, total: Int) -> Unit,
    ): Result<ByteArray>
    /** Saves the PNG (workspace `images/IMG_….png`) and returns the file. */
    fun save(png: ByteArray, req: com.iris.localai.imagegen.ImageRequest, model: com.iris.localai.imagegen.ImageModel): File
    /** Stops a running [generate] (called when the agent run is stopped). */
    fun cancel() {}
}

/**
 * 2.1.7 `generate_image`: on-device text-to-image / image-to-image through the 生图 runtime.
 * The reference image must be a file inside the workspace sandbox; the result is saved as
 * `images/IMG_….png` and returned both in the text and in [ToolResult.images].
 */
object GenerateImageTool : SuspendTool {
    override val group = ToolGroup.IMAGE
    override val name = "generate_image"
    override val label = "生图"
    override val description = "用本机模型生成或修改图片（约 1 分钟），保存到工作区 images/"
    // 2.1.7: negative_prompt, seed, strength, width, height, steps, cfg are accepted but not advertised (prompt budget).
    override val promptLine = "generate_image(prompt, image?): on-device picture; image=workspace path to edit"
    override val risk = ToolRisk.WRITE
    /** Only ever creates a new file (never overwrites), so it runs without asking. */
    override val defaultPermission: Permission get() = Permission.ALLOW

    const val NO_MODEL = "没有可用的生图模型：请在侧栏「生图」下载并运行自检"

    /** The parsed call; [image] is still the workspace-relative path the model gave. */
    data class Parsed(
        val prompt: String, val negative: String, val width: Int?, val height: Int?, val steps: Int?,
        val cfg: Float?, val seed: Long, val image: String?, val strength: Float,
    )

    private fun num(args: JsonObject, vararg keys: String): Double? = Args.str(args, *keys)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    /** Lenient parsing: aliases, numbers as strings, blanks = absent. Throws for a missing prompt. */
    fun parse(args: JsonObject): Parsed {
        val prompt = Args.str(args, "prompt", "text", "description", "query")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("缺少参数 prompt")
        return Parsed(
            prompt = prompt,
            negative = Args.str(args, "negative_prompt", "negative", "neg")?.trim().orEmpty(),
            width = num(args, "width", "w")?.toInt(),
            height = num(args, "height", "h")?.toInt(),
            steps = num(args, "steps")?.toInt(),
            cfg = num(args, "cfg", "cfg_scale", "guidance")?.toFloat(),
            seed = num(args, "seed")?.toLong()?.takeIf { it >= 0 } ?: -1L,
            image = Args.str(args, "image", "init_image", "image_path", "path")?.trim()?.takeIf { it.isNotEmpty() },
            strength = num(args, "strength", "denoise", "denoising_strength")?.toFloat() ?: 0.75f,
        )
    }

    /** The reference image as a file inside the sandbox (throws [SandboxException] outside it). */
    fun resolveImage(path: String, env: ToolEnv): File = env.sandbox.resolve(path)

    /** Builds the normalized request for [model] (model defaults where the call left a value out). */
    fun request(p: Parsed, model: com.iris.localai.imagegen.ImageModel, initImage: String?): com.iris.localai.imagegen.ImageRequest =
        com.iris.localai.imagegen.ImageRequest(
            prompt = p.prompt, negative = p.negative,
            width = p.width ?: model.fixedSize ?: 512, height = p.height ?: model.fixedSize ?: 512,
            steps = p.steps ?: model.defaultSteps, cfg = p.cfg ?: model.defaultCfg, seed = p.seed,
            initImagePath = initImage, strength = p.strength,
        ).normalize(model)

    override suspend fun runSuspend(args: JsonObject, env: ToolEnv, progress: (String) -> Unit): ToolResult {
        val p = parse(args)
        val init: File? = p.image?.let { path ->
            val f = resolveImage(path, env)
            if (!f.isFile) return notFound(path, env)
            f
        }
        val models = env.imageGen?.runnableModels().orEmpty()
        if (models.isEmpty()) return ToolResult(false, "Error: $NO_MODEL", NO_MODEL.substringBefore('：'))
        // A reference image needs a model that takes one (not the fixed-size NPU context binary).
        val model = if (init != null) models.firstOrNull { it.backend != com.iris.localai.imagegen.Backend.QNN }
            ?: return ToolResult(false, "Error: the available image model cannot edit a picture; call without image", "该模型暂不支持参考图")
        else models.first()
        val backend = env.imageGen!!
        val req = request(p, model, init?.path)
        progress("生成中…")
        val png = try {
            backend.generate(req, model) { step, total -> progress("生成中 $step/$total") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            backend.cancel(); throw e
        }.getOrElse { e -> return ToolResult(false, "Error: image generation failed: ${e.message ?: e.javaClass.simpleName}", (e.message ?: "生图失败").take(40)) }
        val file = backend.save(png, req, model)
        val rel = if (env.sandbox.inside(file.canonicalFile)) env.sandbox.relative(file) else file.path
        return ToolResult(
            true,
            "saved: $rel (seed ${req.seed}, ${req.width}x${req.height})",
            "已保存 $rel",
            images = listOf(rel),
        )
    }
}

/** Registry of the core tools (1.9.0 + 1.10.0 recall / memory), in settings / prompt order. */
object AgentTools {
    val all: List<AgentTool> = listOf(
        ReadFileTool, WriteFileTool, EditFileTool, ListDirTool, SearchFilesTool, MoveFileTool, DeleteFileTool, ProcessFileTool,
        PlanTool, CalculatorTool, DateTimeTool, RecallTool, MemoryTool, WebSearchTool, WebFetchTool, BrowserTool, RunCommandTool,
        PhoneTool, ScreenTool, ScheduleTool, SkillTool, GenerateImageTool, ActivateTool,
    )

    /**
     * 2.1.0: tools offered only while their backing exists (kb_search: the knowledge folder has
     * files), so they cost no prompt tokens otherwise. Not in [all] / the settings tool list.
     */
    val onDemand: List<AgentTool> = listOf(KbSearchTool)

    fun byName(name: String): AgentTool? = all.firstOrNull { it.name == name } ?: onDemand.firstOrNull { it.name == name }

    /**
     * 1.12.0: the tools of one agent reply — enabled in settings, minus those whose backing is
     * missing (recall without the history database, memory while memory is off).
     */
    fun enabled(s: AgentSettings, historyAvailable: Boolean, memoryOn: Boolean, knowledgeOn: Boolean = false, imageGenOn: Boolean = false): List<AgentTool> =
        all.filter { s.isEnabled(it) }.filter {
            (it.name != RecallTool.name || historyAvailable) && (it.name != MemoryTool.name || memoryOn) &&
                // 2.1.7: generate_image only while a runnable image model is downloaded (no prompt cost otherwise).
                (it.name != GenerateImageTool.name || imageGenOn)
        } +
            (if (knowledgeOn) listOf(KbSearchTool) else emptyList())
}

/** Builds text from HTML for web_fetch (scripts, styles and tags removed, entities decoded). Pure. */
object HtmlText {
    private val META = Regex("(?is)<meta\\b[^>]*>")
    private val IMG = Regex("(?is)<img\\b[^>]*>")
    private fun attr(tag: String, name: String): String? =
        Regex("(?is)\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").find(tag)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        }
    private val SKIP = Regex("(?i)(logo|icon|sprite|avatar|pixel|spacer|blank|badge|tracking|1x1)")

    /**
     * 2.2: up to [max] images of a page for web_fetch: og:image / twitter:image first, then <img> that
     * carry alt text. Absolute https only, no data: / svg / tracking pixels / logos; duplicates dropped.
     */
    fun images(html: String, baseUrl: String, max: Int = 3): List<com.iris.localai.data.ChatImage> {
        val base = runCatching { java.net.URI(baseUrl) }.getOrNull()
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { toText(it) }?.take(60).orEmpty()
        fun abs(raw: String?): String? {
            val u = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() } ?: return null
            val resolved = runCatching { if (base != null) base.resolve(u.replace(" ", "%20")).toString() else u }.getOrNull() ?: return null
            if (!resolved.startsWith("https://", ignoreCase = true)) return null
            val path = resolved.substringBefore('?').lowercase()
            if (path.endsWith(".svg") || SKIP.containsMatchIn(path.substringAfterLast('/'))) return null
            return resolved
        }
        val out = LinkedHashMap<String, String>()
        for (m in META.findAll(html)) {
            val key = (attr(m.value, "property") ?: attr(m.value, "name"))?.lowercase() ?: continue
            if (key != "og:image" && key != "og:image:url" && key != "og:image:secure_url" && key != "twitter:image") continue
            abs(attr(m.value, "content"))?.let { out.putIfAbsent(it, title) }
        }
        for (m in IMG.findAll(html)) {
            if (out.size >= max) break
            val alt = attr(m.value, "alt")?.let { toText(it) }?.trim().orEmpty()
            if (alt.length < 2) continue
            val w = attr(m.value, "width")?.toIntOrNull()
            if (w != null && w < 64) continue
            abs(attr(m.value, "src") ?: attr(m.value, "data-src"))?.let { out.putIfAbsent(it, alt.take(60)) }
        }
        return out.entries.take(max).map { com.iris.localai.data.ChatImage(it.key, it.value) }
    }

    private val DROP = Regex("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1>")
    private val BREAK = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val COMMENT = Regex("(?s)<!--.*?-->")

    fun toText(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = DROP.replace(s, " ")
        s = BREAK.replace(s, "\n")
        s = TAG.replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

data class FetchResult(val status: Int, val contentType: String?, val body: String, val finalUrl: String)

fun interface WebFetcher {
    fun fetch(url: String): FetchResult
    /** 1.11.1: with a browser User-Agent (search pages serve bots little); default ignores it. */
    fun fetch(url: String, userAgent: String?): FetchResult = fetch(url)
}

/**
 * Real fetcher: http/https only, no loopback / private / link-local / CGNAT / … addresses (the
 * local API server and the LAN stay out of reach of a prompt-injected page), ≤ 3 redirects
 * re-checked, 512 KB, 15 s. 1.14.3: goes through [SafeHttpClient], which resolves each host once,
 * refuses it when any address is not public ([NetGuard]) and connects to exactly that address, so
 * DNS rebinding between the check and the connection no longer works. [resolve] is the DNS seam
 * (tests pass a fake one; default: [NetGuard.systemResolver]).
 */
class HttpWebFetcher(
    private val maxBytes: Int = 512 * 1024,
    private val resolve: (String) -> Array<java.net.InetAddress> = { NetGuard.systemResolver(it) },
) : WebFetcher {
    override fun fetch(url: String): FetchResult = fetch(url, null)

    override fun fetch(url: String, userAgent: String?): FetchResult {
        val headers = mapOf(
            "User-Agent" to (userAgent ?: "IRIS-LocalAI-Agent/1.9"),
            "Accept" to "text/html,text/plain,application/json;q=0.9,*/*;q=0.5",
        )
        val r = SafeHttpClient(resolve = resolve).get(url, headers, maxBytes, maxRedirects = 3)
        val type = r.header("content-type")
        val charset = type?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return FetchResult(r.code, type, String(r.body, charset), r.url)
    }

    companion object {
        /** Throws [SecurityException] when [host] is (or resolves to anything) not public. */
        fun checkPublicHost(host: String?) {
            NetGuard.resolvePublic(host)
        }
    }
}

/** Workspace file listing for the settings page (recursive, relative paths). */
fun WorkspaceSandbox.listAll(limit: Int = 500): List<Pair<String, File>> =
    root.walkTopDown().onEnter { inside(it.canonicalFile) }.filter { it.isFile }.take(limit)
        .map { relative(it) to it }.sortedBy { it.first.lowercase() }.toList()

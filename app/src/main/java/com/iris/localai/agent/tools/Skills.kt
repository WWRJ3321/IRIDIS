package com.iris.localai.agent.tools

import com.iris.localai.agent.core.*
import com.iris.localai.agent.web.*
import com.iris.localai.agent.workspace.*
import kotlinx.serialization.json.JsonObject
import java.io.File

/** one skill: a folder with a SKILL.md (agentskills.io layout). */
data class SkillInfo(
    val name: String, val description: String, val source: String, val body: () -> String,
    /** Other files of the skill, relative to its folder. */
    val files: () -> List<String> = { emptyList() },
    /** text of one of [files] (null = missing, too big or outside the skill folder). */
    val readFile: (String) -> String? = { null },
)

/**
 * Skills: instructions the model loads only when a task needs them, so they cost almost no
 * prompt (one line each). Built-in skills ship in the APK (assets/skills, read-only); the user adds their
 * own as `skills/<name>/SKILL.md` in the app's resource root (a same-named user skill wins).
 * that root is Download/IRIDIS (filesDir/iridis in 应用私有目录 mode), outside the agent
 * workspace, so the file tools can neither read nor change a skill; the [SkillTool] reads its
 * SKILL.md and, with `file`, one of its other files (the model can then write a copy into the
 * workspace to run it).
 */
class SkillCatalog(private val builtIn: () -> List<SkillInfo>, private val workspace: () -> File?) {
    @Volatile private var cache: Pair<Long, List<SkillInfo>>? = null

    /** Built-in + workspace skills; the folder is re-scanned at most every [TTL_MS]. */
    fun all(): List<SkillInfo> {
        val now = System.currentTimeMillis()
        cache?.let { (t, l) -> if (now - t < TTL_MS) return l }
        val user = workspace()?.let { scan(it) }.orEmpty()
        val names = user.map { it.name }.toSet()
        return (user + builtIn().filter { it.name !in names }).sortedBy { it.name }.also { cache = now to it }
    }

    fun invalidate() { cache = null }

    fun find(name: String): SkillInfo? {
        val n = name.trim().lowercase()
        return all().firstOrNull { it.name.lowercase() == n } ?: all().firstOrNull { it.name.lowercase().contains(n) }
    }

    companion object {
        const val DIR = "skills"
        const val FILE = "SKILL.md"
        const val TTL_MS = 15_000L

        fun scan(root: File): List<SkillInfo> {
            val dir = File(root, DIR)
            return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
                val f = File(d, FILE).takeIf { it.isFile && it.length() < 200_000 } ?: return@mapNotNull null
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                val (meta, _) = frontMatter(text)
                SkillInfo(
                    name = meta["name"]?.takeIf { it.isNotBlank() } ?: d.name,
                    description = meta["description"].orEmpty(),
                    source = "$DIR/${d.name}",
                    body = { runCatching { f.readText() }.getOrDefault(text) },
                    files = { d.walkTopDown().filter { it.isFile && it.name != FILE }.take(30).map { it.relativeTo(d).path.replace('\\', '/') }.toList() },
                    readFile = { rel -> readIn(d, rel) },
                )
            }.orEmpty()
        }

        /** A file inside [dir] (symlinks resolved, never outside it), as text; null when missing or over 200 KB. */
        fun readIn(dir: File, rel: String): String? = runCatching {
            val base = dir.canonicalFile
            val f = File(base, rel.trim().trimStart('/')).canonicalFile
            if (!f.path.startsWith(base.path + File.separator) || !f.isFile || f.length() > 200_000) null else f.readText()
        }.getOrNull()

        /** `---\nname: x\ndescription: y\n---\nbody` → (meta, body). Pure. */
        fun frontMatter(text: String): Pair<Map<String, String>, String> {
            val t = text.removePrefix("\uFEFF")
            if (!t.startsWith("---")) return emptyMap<String, String>() to t
            val end = t.indexOf("\n---", 3).takeIf { it > 0 } ?: return emptyMap<String, String>() to t
            val meta = t.substring(3, end).lines().mapNotNull { l ->
                val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim().trim('"', '\'')
            }.toMap()
            return meta to t.substring(end + 4).trimStart('-', '\n', '\r')
        }

        fun parse(name: String, text: String, source: String): SkillInfo {
            val (meta, _) = frontMatter(text)
            return SkillInfo(meta["name"] ?: name, meta["description"].orEmpty(), source, { text })
        }
    }
}

object SkillTool : AgentTool {
    override val name = "skill"
    override val label = "技能"
    override val description = "按需读取技能说明（内置技能 + 下载/IRIDIS/skills 里的 SKILL.md，只读）"
    override val promptLine = "skill(name, file?): load a skill"
    override val risk = ToolRisk.READ
    override val group = ToolGroup.SKILLS

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val cat = env.skills ?: return ToolResult(false, "Error: skills are not available", "不可用")
        val n = Args.str(args, "name", "skill", "id") ?: throw IllegalArgumentException("缺少参数 name")
        val s = cat.find(n) ?: return ToolResult(false, "Error: no skill \"$n\". Available: ${cat.all().joinToString { it.name }}", "没有这个技能")
        Args.str(args, "file", "path")?.takeIf { it.isNotBlank() }?.let { rel ->
            val text = s.readFile(rel) ?: return ToolResult(false, "Error: no file \"$rel\" in skill ${s.name}. Files: ${s.files().joinToString()}", "没有这个文件")
            return ToolResult(true, capTokens(text, env.outputTokens * 2), "${s.name}/$rel")
        }
        val (_, body) = SkillCatalog.frontMatter(s.body())
        val files = s.files()
        val out = buildString {
            append("Skill ").append(s.name).append(" (").append(s.source).append(")\n").append(body.trim())
            if (files.isNotEmpty()) append("\n\nFiles (read with skill(name, file)): ").append(files.joinToString())
        }
        return ToolResult(true, capTokens(out, env.outputTokens * 2), s.name)
    }
}

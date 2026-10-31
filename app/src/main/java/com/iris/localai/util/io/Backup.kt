package com.iris.localai.util

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 1.12.0 backup: conversations, memory, history index, per-model configs and settings in one zip
 * (no models, voice packs, or attachments — those are large and re-downloadable).
 * Restore writes the same paths back; the app then restarts so every repository re-reads them.
 *
 * 2.1.0 (IRIDIS, applicationId com.iridis): the new package cannot read the old one's private data,
 * so this zip is the way across. It now carries a [MANIFEST] (format + version, checked on import),
 * the workspace skills (`workspace/skills/…`) and, unless asked for, no API key. The old
 * [MARKER] is still written, and zips from 1.12.0 – 2.0.0 (marker only) still import.
 */
object Backup {
    const val MARKER = "iris-backup.txt"
    const val MANIFEST = "manifest.json"
    const val FORMAT = "iridis-backup"
    /** 1 = 1.12.0 – 2.0.0 (marker only); 2 = 2.1.0 (manifest, skills, API key optional). */
    const val FORMAT_VERSION = 2
    /**
     * Zip prefix of the user skills (restored into the current skills folder). The name is kept for
     * compatibility with 2.1.0 zips; since 2.1.1 the skills live in Download/IRIDIS/skills, not the workspace.
     */
    const val SKILLS_PREFIX = "workspace/skills/"
    /** Paths relative to the app's data dir (filesDir's parent). */
    val ITEMS = listOf(
        "files/conversations", "files/memory", "files/model_configs.json",
        "shared_prefs/iris_settings_v2.xml", "shared_prefs/iris_agent.xml",
        "shared_prefs/iris_plans.xml",
        "databases/iris_history.db",
    )
    const val SETTINGS_PREFS = "shared_prefs/iris_settings_v2.xml"

    @kotlinx.serialization.Serializable
    data class Manifest(
        val format: String = FORMAT,
        val formatVersion: Int = FORMAT_VERSION,
        val app: String = "IRIDIS",
        val appVersion: String = "",
        val createdAt: Long = 0,
        val includesApiKey: Boolean = false,
        val files: Int = 0,
        val items: List<String> = emptyList(),
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun manifestJson(m: Manifest): String = json.encodeToString(Manifest.serializer(), m)

    /** Parses and validates a manifest; throws [IllegalArgumentException] with a Chinese message. */
    fun parseManifest(text: String): Manifest {
        val m = runCatching { json.decodeFromString(Manifest.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("备份清单损坏") }
        if (m.format != FORMAT) throw IllegalArgumentException("不是 IRIDIS 备份文件")
        if (m.formatVersion < 1) throw IllegalArgumentException("备份格式无效")
        if (m.formatVersion > FORMAT_VERSION) throw IllegalArgumentException("备份来自更新版本的 IRIDIS（格式 ${m.formatVersion}），请先升级 App")
        return m
    }

    /**
     * Drops the local API server key from the settings prefs XML (SharedPreferences stores the
     * settings JSON as an XML-escaped string). Pure, unit-tested.
     */
    fun stripApiKey(prefsXml: String): String =
        prefsXml.replace(Regex("(&quot;apiKey&quot;\\s*:\\s*&quot;).*?(&quot;)"), "$1$2")
            .replace(Regex("(\"apiKey\"\\s*:\\s*\")[^\"]*(\")"), "$1$2")

    fun dataDir(ctx: Context): File = ctx.filesDir.parentFile ?: ctx.filesDir

    /**
     * Writes the zip; returns how many data files went in (marker and manifest not counted).
     * [workspace] = the folder whose `skills` subfolder is included when present (2.1.1: the app's resource
     * root Download/IRIDIS, not the agent sandbox).
     */
    fun export(
        root: File, out: OutputStream, version: String,
        workspace: File? = null, includeApiKey: Boolean = false, now: Long = System.currentTimeMillis(),
    ): Int {
        val entries = ArrayList<Pair<String, File>>()
        ITEMS.map { File(root, it) }.filter { it.exists() }.forEach { top ->
            top.walkTopDown().filter { it.isFile }.forEach { f -> entries += f.relativeTo(root).path.replace('\\', '/') to f }
        }
        workspace?.let { File(it, "skills") }?.takeIf { it.isDirectory }?.let { skills ->
            skills.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { f ->
                entries += SKILLS_PREFIX + f.relativeTo(skills).path.replace('\\', '/') to f
            }
        }
        val manifest = Manifest(appVersion = version, createdAt = now, includesApiKey = includeApiKey, files = entries.size,
            items = ITEMS.filter { File(root, it).exists() } + listOfNotNull(SKILLS_PREFIX.takeIf { entries.any { e -> e.first.startsWith(SKILLS_PREFIX) } }))
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER)); zip.write("IRIDIS backup\nversion=$version\nformat=$FORMAT_VERSION\n".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifestJson(manifest).toByteArray()); zip.closeEntry()
            entries.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(name))
                if (name == SETTINGS_PREFS && !includeApiKey) zip.write(stripApiKey(f.readText()).toByteArray())
                else f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return entries.size
    }

    /** What a backup holds, for the confirm dialog. Legacy (1.12.0 – 2.0.0) zips get formatVersion 1. */
    data class Info(val manifest: Manifest, val dataFiles: Int, val legacy: Boolean)

    /** Reads only the zip directory/manifest; throws [IllegalArgumentException] when it is not a usable backup. */
    fun inspect(input: InputStream): Info {
        var marked = false
        var manifest: Manifest? = null
        var n = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == MARKER -> marked = true
                    e.name == MANIFEST -> manifest = parseManifest(zip.readBytes().toString(Charsets.UTF_8))
                    !e.isDirectory -> n++
                }
            }
        }
        val m = manifest
        if (m != null) return Info(m, n, legacy = false)
        if (!marked) throw IllegalArgumentException("不是 IRIDIS / IRIS 备份文件")
        return Info(Manifest(formatVersion = 1, app = "IRIS LocalAI", files = n, includesApiKey = true), n, legacy = true)
    }

    /**
     * Restores a zip made by [export] (or by 1.12.0 – 2.0.0); only the listed paths are written.
     * Skills go into [workspace]`/skills` (merged, same-named files replaced) when given (2.1.1: Download/IRIDIS).
     * Returns the file count.
     */
    fun restore(root: File, input: InputStream, workspace: File? = null): Int {
        val canon = root.canonicalPath
        var n = 0
        var marked = false
        val staged = ArrayList<Pair<File, File>>()
        val stage = File(root, "files/.restore").apply { deleteRecursively(); mkdirs() }
        val skillsRoot = workspace?.let { File(it, "skills") }
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == MARKER) { marked = true; continue }
                    if (e.name == MANIFEST) { parseManifest(zip.readBytes().toString(Charsets.UTF_8)); marked = true; continue }
                    if (e.isDirectory) continue
                    // 1.13.0: no "..", and the file must stay inside its own item folder.
                    if (e.name.split('/', '\\').any { it == ".." }) continue
                    if (e.name.startsWith(SKILLS_PREFIX)) {
                        val sr = skillsRoot ?: continue
                        val dest = File(sr, e.name.removePrefix(SKILLS_PREFIX))
                        if (!dest.canonicalPath.startsWith(sr.canonicalPath + File.separator)) continue
                        val tmp = File(stage, "$n")
                        tmp.outputStream().use { zip.copyTo(it) }
                        staged += tmp to dest; n++
                        continue
                    }
                    if (ITEMS.none { e.name == it || e.name.startsWith("$it/") }) continue
                    val dest = File(root, e.name)
                    val inItem = ITEMS.any { item ->
                        val base = File(root, item).canonicalPath
                        dest.canonicalPath == base || dest.canonicalPath.startsWith(base + File.separator)
                    }
                    if (!inItem || !dest.canonicalPath.startsWith(canon + File.separator)) continue
                    val tmp = File(stage, "$n")
                    tmp.outputStream().use { zip.copyTo(it) }
                    staged += tmp to dest; n++
                }
            }
            if (!marked) throw IllegalArgumentException("不是 IRIDIS / IRIS 备份文件")
            // Replace whole folders so deleted conversations do not come back half-merged.
            ITEMS.filter { item -> staged.any { (_, d) -> d.path.startsWith(File(root, item).path) } }
                .map { File(root, it) }.filter { it.isDirectory }.forEach { it.deleteRecursively() }
            staged.forEach { (tmp, dest) -> dest.parentFile?.mkdirs(); tmp.copyTo(dest, overwrite = true) }
            File(root, "databases/iris_history.db-wal").delete(); File(root, "databases/iris_history.db-shm").delete()
        } finally { stage.deleteRecursively() }
        return n
    }

    /** Restarts the app (after a restore). */
    fun restart(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) ctx.startActivity(i)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** 1.12.0: the app's own log lines (IrisLoad, IrisGen, IrisAgent …) for bug reports, no adb needed. */
object LogExport {
    fun collect(maxLines: Int = 20_000): String {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString(), "--pid=" + android.os.Process.myPid()).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val header = "IRIDIS ${com.iris.localai.BuildConfig.VERSION_NAME} (${com.iris.localai.BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n"
        return header + text
    }
}

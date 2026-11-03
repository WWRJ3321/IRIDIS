package com.iris.localai.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Permission(val label: String) {
    ALLOW("允许"), ASK("询问"), DENY("拒绝");

    companion object {
        fun parse(s: String?): Permission? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** The user's answer in the in-chat confirmation (允许一次 / 总是允许 / 拒绝). */
enum class UserDecision { ALLOW_ONCE, ALWAYS_ALLOW, DENY }

/**
 * 1.9.0 agent settings, stored apart from [com.iris.localai.data.AppSettings] (own preferences
 * file `iris_agent`, key `agent`) so no existing key changes. Unknown keys are ignored.
 */
@Serializable
data class AgentSettings(
    /** Chat mode: false = 对话 (no tools, ≤ 1.8.0 behaviour), true = 智能体. Default 对话. */
    val agentMode: Boolean = false,
    /** Tool name → on/off; missing = the tool's default ([AgentTool.defaultEnabled]). */
    val enabled: Map<String, Boolean> = emptyMap(),
    /** Tool name → "ALLOW" / "ASK" / "DENY"; missing = [PermissionPolicy.defaultFor]. */
    val permissions: Map<String, String> = emptyMap(),
    /** Tool calls per reply before the agent must answer. */
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 1.10.0: workspace location, [WorkspaceLocation] name (default 下载/IRISLocalAI). */
    val workspace: String = WorkspaceLocation.DOWNLOADS.name,
    /** 1.10.0: persisted SAF grant of Download/IRISLocalAI (read files the app did not create); null = none. */
    val workspaceTreeUri: String? = null,
    /** 2.3.3: real path of the 自定义位置 workspace (used when [workspace] = CUSTOM). */
    val customWorkspace: String = "",
    /** 1.12.0: [SearchProvider] name for web_search, and the SearXNG address. 2.0.0 default 谷歌（浏览器）. */
    val searchProvider: String = SearchProvider.DEFAULT.name,
    /** 2.0.0: true once the user picked a provider in settings (an untouched AUTO is moved to the new default). */
    val searchProviderChosen: Boolean = false,
    val searxUrl: String = "",
    /** 1.14.0 按需载入: phone / scheduling tools are listed by name until activated. */
    val lazyTools: Boolean = true,
    /** 1.14.0 计划模式: multi-step tasks are planned first and wait for the user's OK. */
    val planFirst: Boolean = false,
) {
    val search: SearchProvider get() = SearchProvider.parse(searchProvider)
    val workspaceLocation: WorkspaceLocation get() = WorkspaceLocation.parse(workspace)

    fun isEnabled(tool: AgentTool): Boolean = enabled[tool.name] ?: tool.defaultEnabled
    fun permission(tool: AgentTool): Permission = PermissionPolicy.effective(tool, this)

    /** 2.0.0: settings stored before 2.0 with the old default AUTO, never changed by the user → 谷歌（浏览器）. */
    fun migrated(): AgentSettings =
        if (!searchProviderChosen && searchProvider == SearchProvider.AUTO.name) copy(searchProvider = SearchProvider.DEFAULT.name) else this

    fun sanitized(): AgentSettings = copy(
        maxSteps = maxSteps.coerceIn(MIN_STEPS, MAX_STEPS),
        enabled = enabled.filterKeys { AgentTools.byName(it) != null },
        permissions = permissions.filter { (k, v) -> AgentTools.byName(k) != null && Permission.parse(v) != null },
        workspace = WorkspaceLocation.parse(workspace).name,
    )

    companion object {
        const val DEFAULT_MAX_STEPS = 8
        const val MIN_STEPS = 1
        const val MAX_STEPS = 20
    }
}

/**
 * Permission rules (OpenCode-style allow / ask / deny per tool). Defaults: reading and pure
 * tools allow; writing, editing, deleting and network ask. Pure, unit-tested.
 */
object PermissionPolicy {
    fun defaultFor(risk: ToolRisk): Permission = when (risk) {
        ToolRisk.SAFE, ToolRisk.READ -> Permission.ALLOW
        ToolRisk.WRITE, ToolRisk.DELETE, ToolRisk.NETWORK, ToolRisk.MEMORY -> Permission.ASK
    }

    fun effective(tool: AgentTool, s: AgentSettings): Permission =
        Permission.parse(s.permissions[tool.name]) ?: tool.defaultPermission ?: defaultFor(tool.risk)

    /** The settings after the user answered a confirmation: only 总是允许 is remembered. */
    fun afterDecision(s: AgentSettings, tool: AgentTool, d: UserDecision): AgentSettings =
        if (d == UserDecision.ALWAYS_ALLOW) s.copy(permissions = s.permissions + (tool.name to Permission.ALLOW.name)) else s

    /** Whether this call may run, given the setting and (for ASK) the user's answer. */
    fun mayRun(p: Permission, decision: UserDecision?): Boolean = when (p) {
        Permission.ALLOW -> true
        Permission.DENY -> false
        Permission.ASK -> decision == UserDecision.ALLOW_ONCE || decision == UserDecision.ALWAYS_ALLOW
    }
}

class AgentSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("iris_agent", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()
    val current: AgentSettings get() = _settings.value

    fun update(block: (AgentSettings) -> AgentSettings) = synchronized(lock) {
        val next = block(_settings.value).sanitized()
        if (next == _settings.value) return@synchronized
        _settings.value = next
        prefs.edit().putString(KEY, json.encodeToString(AgentSettings.serializer(), next)).apply()
    }

    private fun read(): AgentSettings = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(AgentSettings.serializer(), it) }.getOrNull() }
        ?.migrated()?.sanitized() ?: AgentSettings()

    private companion object { const val KEY = "agent" }
}

/** App-wide agent state: settings, the sandboxed workspace and its undo history. */
class AgentContainer(context: Context) {
    val settings = AgentSettingsRepository(context)
    /** 2.3.0 ②: remote MCP servers; their enabled tools join the 9 built-in ones (capped). */
    val mcp = com.iris.localai.agent.mcp.McpManager(context).also { m -> AgentTools.mcp = { m.injected().injected } }
    /** 1.9.0 location; since 1.10.0 used when 应用私有目录 is chosen or Download is unavailable. */
    val privateRoot = java.io.File(context.filesDir, "workspace")
    private val ctx = context.applicationContext ?: context

    private class Ws(val choice: WorkspaceChoice, val sandbox: WorkspaceSandbox)

    private fun resolveWorkspace(): Ws {
        val loc = settings.current.workspaceLocation
        val downloads = runCatching { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        val state = runCatching { android.os.Environment.getExternalStorageState() }.getOrNull()
        val choice = WorkspaceResolver.resolve(loc, downloads, state, privateRoot,
            customPath = settings.current.customWorkspace, allFiles = hasAllFilesAccess(), volumes = storageVolumes())
            .let { ch -> ch.customProblem?.let { p -> ch.copy(fallbackReason = customReason(p)) } ?: ch }
        noteCustomFallback(choice)
        val sb = runCatching { WorkspaceSandbox(choice.root) }.getOrElse {
            WorkspaceSandbox(privateRoot).also { return Ws(WorkspaceChoice(loc, WorkspaceLocation.PRIVATE, privateRoot, "无法使用 ${choice.root.path}，智能体工作区暂时改用应用私有目录",
                appRoot = WorkspaceResolver.privateAppRoot(privateRoot)), it) }
        }
        return Ws(choice, sb)
    }

    private val ws = MutableStateFlow(resolveWorkspace())
    /** 1.10.0: the workspace in use (location, real path, fallback reason). */
    private val workspaceFlow = MutableStateFlow(ws.value.choice)
    val workspace: StateFlow<WorkspaceChoice> = workspaceFlow.asStateFlow()

    /** The sandbox of the current workspace (changes when the location setting changes). */
    val sandbox: WorkspaceSandbox get() = ws.value.sandbox

    /**
     * 2.1.1: the resource root holding skills/ and knowledge/ — `Download/IRIDIS` (beside the sandbox
     * `Download/IRIDIS/workspace`) or `filesDir/iridis` in 应用私有目录 mode. Never handed to a tool.
     */
    val appRoot: java.io.File get() = ws.value.choice.appRoot

    /** Re-resolves the location (after the setting changed, or storage came back). */
    fun refreshWorkspace() {
        val next = resolveWorkspace()
        ws.value = next
        workspaceFlow.value = next.choice
        changed()
    }

    /** 2.3.3: mounted storage roots (internal shared storage + SD cards / USB) for the folder picker. */
    fun storageVolumes(): List<java.io.File> = runCatching {
        val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
        sm.storageVolumes.filter { it.state == android.os.Environment.MEDIA_MOUNTED }.mapNotNull { it.directory }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(android.os.Environment.getExternalStorageDirectory())

    /** 2.3.3: Download/IRIDIS (only its workspace/ may be picked as a custom folder). */
    fun iridisRoot(): java.io.File? = runCatching {
        WorkspaceResolver.downloadsRoot(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS))
    }.getOrNull()

    fun validateCustom(path: String): CustomWorkspace.Problem? =
        CustomWorkspace.validate(path, hasAllFilesAccess(), storageVolumes(), iridisRoot())

    fun customReason(p: CustomWorkspace.Problem): String = com.iris.localai.I18n.s(when (p) {
        CustomWorkspace.Problem.BLANK -> com.iris.localai.R.string.ws233_p_blank
        CustomWorkspace.Problem.NO_ACCESS -> com.iris.localai.R.string.ws233_p_access
        CustomWorkspace.Problem.NOT_ABSOLUTE, CustomWorkspace.Problem.OUTSIDE_STORAGE -> com.iris.localai.R.string.ws233_p_outside
        CustomWorkspace.Problem.VOLUME_ROOT -> com.iris.localai.R.string.ws233_p_root
        CustomWorkspace.Problem.APP_DATA -> com.iris.localai.R.string.ws233_p_android
        CustomWorkspace.Problem.RESERVED -> com.iris.localai.R.string.ws233_p_reserved
        CustomWorkspace.Problem.NOT_WRITABLE -> com.iris.localai.R.string.ws233_p_write
    }) + com.iris.localai.I18n.s(com.iris.localai.R.string.ws233_fallback)

    /** 2.3.3: one toast per custom path when it had to fall back (shown again only after it worked once). */
    private fun noteCustomFallback(choice: WorkspaceChoice) {
        if (choice.requested != WorkspaceLocation.CUSTOM) return
        val prefs = ctx.getSharedPreferences("iris_agent", Context.MODE_PRIVATE)
        val path = settings.current.customWorkspace
        if (choice.location == WorkspaceLocation.CUSTOM) { prefs.edit().remove(CUSTOM_TOAST_KEY).apply(); return }
        if (prefs.getString(CUSTOM_TOAST_KEY, null) == path) return
        prefs.edit().putString(CUSTOM_TOAST_KEY, path).apply()
        val text = choice.fallbackReason ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** 2.3.3: use [path] (already validated) as the agent workspace. */
    fun setCustomWorkspace(path: String) {
        val canon = CustomWorkspace.canonical(path)?.path ?: path
        settings.update { it.copy(workspace = WorkspaceLocation.CUSTOM.name, customWorkspace = canon) }
        refreshWorkspace()
    }

    fun setWorkspaceLocation(loc: WorkspaceLocation) {
        settings.update { it.copy(workspace = loc.name) }
        refreshWorkspace()
        // 2.1.1: the newly chosen place may still have the old layout.
        runCatching { migrateLayout() }.getOrNull()?.let { r -> StorageLayout.notice(r)?.let { workspaceNote.value = it } }
    }

    /**
     * SAF grant of Download/IRIDIS (files the app did not create), only while the workspace is in Download.
     * 2.1.1: the agent's view is limited to `workspace/` inside the tree.
     */
    fun externalFiles(): ExternalFiles? = saf(SafPaths.WORKSPACE)

    /** 2.1.1: the whole granted tree, for the 知识库 (knowledge/) and the one-time move — never for tools. */
    private fun externalRoot(): SafWorkspaceFiles? = saf(SafPaths.ROOT)

    private fun saf(base: List<String>): SafWorkspaceFiles? {
        if (ws.value.choice.location != WorkspaceLocation.DOWNLOADS) return null
        val uri = settings.current.workspaceTreeUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() } ?: return null
        val granted = runCatching { ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }.getOrDefault(false)
        return if (granted) SafWorkspaceFiles(ctx, uri, base) else null
    }

    /**
     * 2.1.1: one-time move of the user files at the root of Download/IRIDIS (the 2.1.0 workspace) into
     * Download/IRIDIS/workspace — everything except [StorageLayout.RESERVED]. Uses direct File access,
     * then the SAF grant for what File access cannot see. The marker is written only when the app saw
     * everything (全部文件访问 or SAF) and nothing failed, otherwise it runs again next time.
     * 应用私有目录 mode: moves skills/ and knowledge/ out of filesDir/workspace into filesDir/iridis once.
     * [force] ignores the marker (after the old IRISLocalAI folder was copied into the root).
     * Call off the main thread. Returns null when nothing was attempted.
     */
    @Synchronized
    fun migrateLayout(force: Boolean = false): StorageLayout.Result? {
        val c = ws.value.choice
        val root = c.appRoot
        if (!force && StorageLayout.markerExists(root)) return null
        if (c.location == WorkspaceLocation.PRIVATE) {
            val r = StorageLayout.extract(c.root, root)
            if (r.ok && root.isDirectory) StorageLayout.writeMarker(root)
            if (r.moved > 0) { skills.invalidate(); changed() }
            return r.takeIf { it.moved > 0 || !it.ok }
        }
        val direct = StorageLayout.migrate(root)
        val tree = externalRoot()
        val r = StorageLayout.combine(direct, tree?.migrateLayout())
        if (StorageLayout.complete(r, fullView = hasAllFilesAccess() || tree != null)) StorageLayout.writeMarker(root)
        if (r.moved > 0) changed()
        return r.takeIf { it.moved > 0 || !it.ok }
    }

    /**
     * Copies the 1.9.0 private workspace into Download/IRISLocalAI once (originals deleted only after
     * a verified copy). Returns null when there was nothing to do.
     */
    fun migrateIfNeeded(): MigrationResult? {
        val prefs = ctx.getSharedPreferences("iris_agent", Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATED_KEY, false)) return null
        val c = ws.value.choice
        if (c.location != WorkspaceLocation.DOWNLOADS) return null // try again once Download is in use
        val hasFiles = privateRoot.walkTopDown().any { it.isFile }
        val r = if (hasFiles) WorkspaceMigration.migrate(privateRoot, c.root) else MigrationResult(0, 0, 0, 0)
        if (r.ok) prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
        if (hasFiles) changed()
        return r.takeIf { hasFiles }
    }

    /** 2.1.0: one-line hint when the old Download/IRISLocalAI folder could not be moved; null = none. */
    private val legacyHintFlow = MutableStateFlow<String?>(null)
    val legacyFolderHint: StateFlow<String?> = legacyHintFlow.asStateFlow()

    /**
     * 2.1.0: moves Download/IRISLocalAI (1.10.0 – 2.0.0) into Download/IRIDIS once, decided by
     * [LegacyWorkspace.decide]. The old folder is removed only after a verified, complete copy.
     * Call from a background thread. Returns the copy result when something was copied.
     */
    fun migrateLegacyFolder(): MigrationResult? {
        val c = ws.value.choice
        val downloads = runCatching { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        if (downloads == null) { legacyHintFlow.value = null; return null }
        val old = LegacyWorkspace.legacyRoot(downloads)
        val oldExists = old.isDirectory
        val visible = if (oldExists) old.walkTopDown().count { it.isFile && it.canRead() } else 0
        val allFiles = android.os.Build.VERSION.SDK_INT < 30 || runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        // 2.1.1: the old folder is copied into the resource root, then [migrateLayout] moves it into workspace/.
        // App-managed models/ and voice/ do not count as user files.
        val target = c.appRoot
        val newHasFiles = c.location == WorkspaceLocation.DOWNLOADS && target.listFiles().orEmpty()
            .filter { !StorageLayout.isInternal(it.name) && !it.name.equals(StorageLayout.MODELS, true) && !it.name.equals(StorageLayout.VOICE, true) }
            .any { f -> f.walkTopDown().any { it.isFile && !StorageLayout.isInternal(it.name) } }
        val action = LegacyWorkspace.decide(c.location == WorkspaceLocation.DOWNLOADS, oldExists, visible, allFiles, newHasFiles)
        var result: MigrationResult? = null
        legacyHintFlow.value = when (action) {
            LegacyWorkspace.Action.NONE -> null
            LegacyWorkspace.Action.HINT -> LegacyWorkspace.HINT
            LegacyWorkspace.Action.DELETE_OLD -> { runCatching { old.deleteRecursively() }; null }
            LegacyWorkspace.Action.MIGRATE -> {
                val r = WorkspaceMigration.migrate(old, target)
                result = r
                val left = old.walkTopDown().any { it.isFile }
                if (r.ok && !left) runCatching { old.deleteRecursively() }
                changed()
                if (r.copied > 0) runCatching { migrateLayout(force = true) }
                LegacyWorkspace.afterMigrate(r, left)
            }
        }
        return result
    }

    /** 2.1.0: a saved SAF grant that is not Download/IRIDIS (e.g. the old IRISLocalAI tree) is released and forgotten. */
    fun clearStaleTreeGrant() {
        val saved = settings.current.workspaceTreeUri ?: return
        val uri = runCatching { android.net.Uri.parse(saved) }.getOrNull()
        val docId = uri?.let { SafWorkspaceFiles.treeDocId(it) }
        if (WorkspaceResolver.isWorkspaceTree(docId)) return
        if (uri != null) runCatching {
            ctx.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        settings.update { it.copy(workspaceTreeUri = null) }
    }

    /** One-off workspace message (migration result) for the chat notice / settings page; null = none. */
    val workspaceNote = MutableStateFlow<String?>(null)

    val undo = UndoStore(java.io.File(context.filesDir, "agent-undo"))
    private val fetcher = HttpWebFetcher()
    val browser by lazy { WebViewBrowser(appContextForTools) }
    private val appContextForTools: Context = context.applicationContext ?: context

    /** 1.12.0: phone intents and skills (built-in assets + workspace/skills). */
    val phone: PhoneActions by lazy { AndroidPhoneActions(appContextForTools) }
    /** 1.14.0: plans by conversation. */
    val plans: PlanStore by lazy { Plans.of(appContextForTools) }
    val skills: SkillCatalog by lazy {
        val am = appContextForTools.assets
        val builtIn by lazy {
            runCatching { am.list(SkillCatalog.DIR).orEmpty().mapNotNull { d ->
                runCatching { am.open("${SkillCatalog.DIR}/$d/${SkillCatalog.FILE}").bufferedReader().use { it.readText() } }.getOrNull()
                    ?.let { SkillCatalog.parse(d, it, "内置") }
            } }.getOrDefault(emptyList())
        }
        // 2.1.1: user skills in Download/IRIDIS/skills (or filesDir/iridis/skills) — outside the sandbox.
        SkillCatalog({ builtIn }, { appRoot })
    }

    /** Bumped after any workspace change so the file browser and the undo row refresh. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()
    fun changed() { _revision.value++ }

    fun env(): ToolEnv = ToolEnv(sandbox, undo, fetcher = fetcher, external = externalFiles())

    // ---- 1.10.0: long-term memory + raw history ----

    /** USER.md / MEMORY.md in filesDir/memory — outside filesDir/workspace, so file tools can't reach them. */
    val memoryDocs = com.iris.localai.memory.MemoryDocs(java.io.File(context.filesDir, "memory"))
    val memorySettings = MemorySettingsRepository(context)

    private val appContext = context.applicationContext ?: context
    /** Opened on first use (off the main thread); null when the database cannot be opened. */
    val history: com.iris.localai.memory.HistoryStore? by lazy {
        runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(appContext.getDatabasePath("iris_history.db").apply { parentFile?.mkdirs() }, null)
            com.iris.localai.memory.HistoryStore(com.iris.localai.memory.AndroidSqlDriver(db))
        }.onFailure { android.util.Log.w("IrisHistory", "history store unavailable", it) }.getOrNull()
    }

    /**
     * 2.1.0 知识库, index in filesDir/knowledge/index.json. 2.1.1: the files are in Download/IRIDIS/knowledge
     * (or filesDir/iridis/knowledge), outside the sandbox; kb_search reaches them only through this.
     */
    val knowledge = com.iris.localai.knowledge.KnowledgeBase({ appRoot }, java.io.File(context.filesDir, "knowledge/index.json"), { externalRoot() })

    /** Memory changed in settings or by the tool: bumps so the 记忆 page re-reads. */
    private val _memoryRevision = MutableStateFlow(0)
    val memoryRevision: StateFlow<Int> = _memoryRevision.asStateFlow()
    fun memoryChanged() { _memoryRevision.value++ }

    /** Tool environment of one agent reply in [conversationId]. */
    fun env(conversationId: String, exclude: Set<String>, llm: ChunkLlm? = null, chunkTokens: Int = 1200, enabledTools: List<AgentTool> = emptyList()): ToolEnv = ToolEnv(
        sandbox, undo, fetcher = fetcher,
        history = history?.let { com.iris.localai.memory.HistorySearchAdapter(it) },
        memory = memoryDocs.takeIf { memorySettings.current.enabled },
        conversationId = conversationId, recallExclude = exclude,
        external = externalFiles(),
        accessHint = accessHint(),
        browser = browser,
        searchProvider = settings.current.search,
        searxUrl = settings.current.searxUrl,
        searchBrowser = browser,
        phone = phone,
        skills = skills,
        plans = plans,
        enabledTools = enabledTools,
        llm = llm,
        chunkTokens = chunkTokens,
        knowledge = KnowledgeSearch { q, n -> knowledge.search(q, n) },
        imageGen = imageGen,
        gui = gui,
    )

    /**
     * 2.1.7: whether a runnable image model is downloaded (generate_image is listed only then).
     * Re-checked at most once a minute so the prompt (and its KV cache) does not flip turn by turn.
     */
    fun imageGenOnCached(): Boolean {
        val now = System.currentTimeMillis()
        imageGenOn?.let { if (now - imageGenChecked < 60_000L) return it }
        imageGenChecked = now
        return runCatching { imageGen.runnableModels().isNotEmpty() }.getOrDefault(false).also { imageGenOn = it }
    }
    @Volatile private var imageGenOn: Boolean? = null
    @Volatile private var imageGenChecked = 0L

    /** 2.1.9: `screen`'s backend (set by the app container: GuiAgentRunner). */
    @Volatile var gui: GuiTaskBackend? = null

    /** 2.1.7: generate_image's backend (生图 repository + engine). */
    private val imageGen: ImageGenBackend by lazy {
        com.iris.localai.imagegen.RepositoryImageGenBackend(com.iris.localai.imagegen.ImageGenRepository(appContext))
    }

    /** 1.10.6: whether the app holds 全部文件访问 (MANAGE_EXTERNAL_STORAGE, Android 11+). */
    fun hasAllFilesAccess(): Boolean = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    /** 1.10.6: hint for the model when user-placed files may be hidden by scoped storage. */
    fun accessHint(): String? =
        if (ws.value.choice.location == WorkspaceLocation.DOWNLOADS && !hasAllFilesAccess())
            "if the user put this file in the folder themselves, tell them to turn on 全部文件访问 in 设置›智能体"
        else null

    private companion object { const val MIGRATED_KEY = "workspace_migrated_v1"; const val CUSTOM_TOAST_KEY = "custom_ws_fallback_toast" }
}

/** 1.10.0 memory / compaction settings: same prefs file as the agent (`iris_agent`), own key `memory`. */
class MemorySettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("iris_agent", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<com.iris.localai.memory.MemorySettings> = _settings.asStateFlow()
    val current: com.iris.localai.memory.MemorySettings get() = _settings.value

    fun update(block: (com.iris.localai.memory.MemorySettings) -> com.iris.localai.memory.MemorySettings) = synchronized(lock) {
        val next = block(_settings.value)
        if (next == _settings.value) return@synchronized
        _settings.value = next
        prefs.edit().putString(KEY, json.encodeToString(com.iris.localai.memory.MemorySettings.serializer(), next)).apply()
    }

    private fun read(): com.iris.localai.memory.MemorySettings {
        val stored = prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(com.iris.localai.memory.MemorySettings.serializer(), it) }.getOrNull() }
            ?: com.iris.localai.memory.MemorySettings()
        // 2.0.x: 对话模式注入 is on by default now; settings saved under the old default get it once.
        val next = stored.migrated()
        if (next != stored) prefs.edit().putString(KEY, json.encodeToString(com.iris.localai.memory.MemorySettings.serializer(), next)).apply()
        return next
    }

    private companion object { const val KEY = "memory" }
}

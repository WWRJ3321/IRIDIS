package com.iris.localai.agent.mcp

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.util.HintLog
import android.content.Context
import com.iris.localai.agent.tools.AgentTool
import com.iris.localai.agent.core.Permission
import com.iris.localai.agent.tools.ToolEnv
import com.iris.localai.agent.tools.ToolGroup
import com.iris.localai.agent.tools.ToolResult
import com.iris.localai.agent.tools.ToolRisk
import com.iris.localai.agent.tools.capTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** One remote MCP server (no secrets here: token / headers live encrypted in [McpSecrets]). */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** 总是允许: calls run without the first-call confirmation. */
    val alwaysAllow: Boolean = false,
    val disabledTools: Set<String> = emptySet(),
    /** Tool list from the last successful 测试连接 / 刷新 (the prompt is built from it, no network). */
    val tools: List<McpToolInfo> = emptyList(),
    val checkedAt: Long = 0,
)

/** one tool switch of one server (pure, unit-tested with 30+ tools). */
object McpToggle {
    fun set(s: McpServer, tool: String, on: Boolean): McpServer =
        s.copy(disabledTools = if (on) s.disabledTools - tool else s.disabledTools + tool)
}

/** Token + custom headers per server, AES-GCM with a non-exportable Android Keystore key. */
class McpSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("iris_mcp_secrets", Context.MODE_PRIVATE)

    @Serializable data class Secret(val token: String = "", val headers: Map<String, String> = emptyMap())

    fun get(id: String): Secret = prefs.getString(id, null)?.let { enc ->
        runCatching { JsonRpc.json.decodeFromString(Secret.serializer(), decrypt(enc)) }.getOrNull()
    } ?: Secret()

    fun put(id: String, s: Secret) {
        if (s.token.isBlank() && s.headers.isEmpty()) prefs.edit().remove(id).apply()
        else prefs.edit().putString(id, encrypt(JsonRpc.json.encodeToString(Secret.serializer(), s))).apply()
    }

    fun remove(id: String) = prefs.edit().remove(id).apply()

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val raw = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "iridis_mcp_secrets" }
}

/**
 * the user's remote MCP servers, their clients (one per server, kept for the app's
 * life, re-initialized on failure), and the [AgentTool]s offered to the model.
 */
class McpManager(context: Context) {
    private val prefs = context.getSharedPreferences("iris_mcp", Context.MODE_PRIVATE)
    val secrets = McpSecrets(context)
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()
    private val clients = HashMap<String, McpClient>()
    /** Servers the user allowed once this app session (no more confirmation until restart). */
    private val sessionAllowed = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun load(): List<McpServer> = runCatching {
        JsonRpc.json.decodeFromString(ListSerializer(McpServer.serializer()), prefs.getString("servers", null) ?: "[]")
    }.getOrDefault(emptyList())

    /** 编码放到单线程后台（工具多、schema 大时在主线程编码会卡到开关点不动）；界面立即用新值。 */
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "mcp-save").apply { isDaemon = true } }

    private fun save(list: List<McpServer>) {
        _servers.value = list
        saver.execute {
            if (_servers.value !== list) return@execute // 已有更新的值排在后面，跳过这次
            runCatching { prefs.edit().putString("servers", JsonRpc.json.encodeToString(ListSerializer(McpServer.serializer()), list)).apply() }
        }
    }

    /** 一次开/关某服务的全部工具。 */
    fun setAllTools(id: String, on: Boolean) = update(id) { s -> s.copy(disabledTools = if (on) emptySet() else s.tools.map { it.name }.toSet()) }

    fun update(id: String, f: (McpServer) -> McpServer) = save(_servers.value.map { if (it.id == id) f(it) else it })

    fun upsert(server: McpServer, token: String, headers: Map<String, String>) {
        val list = _servers.value
        save(if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server)
        secrets.put(server.id, McpSecrets.Secret(token.trim(), headers))
        dropClient(server.id)
    }

    fun delete(id: String) {
        save(_servers.value.filterNot { it.id == id })
        secrets.remove(id); dropClient(id); sessionAllowed.remove(id)
    }

    private fun dropClient(id: String) { synchronized(clients) { clients.remove(id) }?.let { c -> Thread { runCatching { c.close() } }.start() } }

    private fun client(s: McpServer): McpClient = synchronized(clients) {
        clients.getOrPut(s.id) { val sec = secrets.get(s.id); McpClient(s.url, McpHeaders.all(sec.token, sec.headers)) }
    }

    /** 测试连接 / 刷新: initialize + tools/list; the list is cached on the server. Blocking. */
    fun refresh(id: String): Result<Int> = runCatching {
        val s = _servers.value.first { it.id == id }
        dropClient(id)
        val tools = client(s).listTools()
        update(id) { it.copy(tools = tools, checkedAt = System.currentTimeMillis()) }
        tools.size
    }.onFailure { e -> HintLog.add(I18n.s(R.string.agent_cc1632, _servers.value.firstOrNull { it.id == id }?.name, McpClient.describe(e)), HintLog.Source.TOOL) }

    /** Every enabled tool of every enabled server, namespaced (before the cap). */
    fun enabledTools(): List<McpTool> {
        val pairs = _servers.value.filter { it.enabled }.flatMap { s -> s.tools.filter { it.name !in s.disabledTools }.map { s to it } }
        val names = McpNames.assign(pairs.map { (s, t) -> s.name to t.name })
        return pairs.mapIndexed { i, (s, t) -> McpTool(names[i], s, t, this) }
    }

    /** The tools injected into the prompt (capped) and how many were left out. */
    fun injected(cap: Int = McpCap.DEFAULT): McpCap.Pick<McpTool> = McpCap.select(enabledTools(), cap)

    fun isAllowed(serverId: String) = _servers.value.firstOrNull { it.id == serverId }?.alwaysAllow == true || serverId in sessionAllowed
    fun allowForSession(serverId: String) { sessionAllowed += serverId }
    fun allowAlways(serverId: String) { update(serverId) { it.copy(alwaysAllow = true) } }

    internal fun call(server: McpServer, tool: String, args: JsonObject): McpResult.Text = client(server).callTool(tool, args)

    companion object { fun newId() = java.util.UUID.randomUUID().toString().take(8) }
}

/** An MCP tool as an agent tool. Asks before the first call per server (总是允许 = never again). */
class McpTool(
    override val name: String,
    val server: McpServer,
    val info: McpToolInfo,
    private val manager: McpManager,
) : AgentTool {
    override val label = "${server.name} · ${info.name}"
    override val description = McpSchema.trimDescription(info.description)
    override val promptLine = McpSchema.promptLine(name, info.schemaJson(), info.description)
    override val risk = ToolRisk.NETWORK
    override val group = ToolGroup.MCP

    override fun permissionFor(args: JsonObject, base: Permission, env: ToolEnv): Permission =
        if (manager.isAllowed(server.id)) Permission.ALLOW else Permission.ASK

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        manager.allowForSession(server.id)
        val r = try {
            manager.call(server, info.name, McpSchema.unflatten(args, info.schemaJson()))
        } catch (e: Exception) {
            val msg = McpClient.describe(e)
            HintLog.add(I18n.s(R.string.agent_e7af6f, server.name, info.name, msg), HintLog.Source.TOOL)
            return ToolResult(false, "Error: MCP server ${server.name}: $msg", msg.take(40))
        }
        if (!r.ok) HintLog.add(I18n.s(R.string.agent_d88230, server.name, info.name, r.text.take(300)), HintLog.Source.TOOL)
        val out = capTokens(r.text.ifBlank { if (r.ok) "(empty result)" else "error" }, env.outputTokens)
        return ToolResult(r.ok, if (r.ok) out else "Error: $out", McpResult.summary(r))
    }
}

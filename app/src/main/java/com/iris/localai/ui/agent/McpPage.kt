package com.iris.localai.ui.agent

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.mcp.McpCap
import com.iris.localai.agent.mcp.McpHeaders
import com.iris.localai.agent.mcp.McpManager
import com.iris.localai.agent.mcp.McpServer
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object McpText {
    const val TITLE = "MCP 服务"
    const val NAV = "连接远程 MCP 服务器，扩展智能体工具"
    const val NOTE = "仅支持远程服务器（Streamable HTTP，自动兼容旧版 SSE）。令牌与请求头加密保存在本机。每个服务首次调用工具前会询问，可选「总是允许」。"
    const val EMPTY = "还没有 MCP 服务。点上方「添加服务」，填写服务器地址（如 https://example.com/mcp）。"
    fun over(total: Int, cap: Int) = "已启用 $total 个 MCP 工具，只向模型提供前 $cap 个；请关掉不常用的工具。"
}

/** 2.3.0 ②: 设置 › 智能体 › MCP 服务 — add / test / enable / delete remote MCP servers and their tools. */
@Composable
fun McpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val mcp = remember { (context.applicationContext as IrisApp).container.agent.mcp }
    val servers by mcp.servers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<McpServer?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    val c = Iris.colors

    fun test(id: String) {
        testing = id
        scope.launch {
            val r = withContext(Dispatchers.IO) { mcp.refresh(id) }
            testing = null
            Toast.makeText(context, r.fold({ "连接成功：$it 个工具" }, { "连接失败：" + com.iris.localai.agent.mcp.McpClient.describe(it) }), Toast.LENGTH_LONG).show()
        }
    }

    SubpageScaffold(McpText.TITLE, onBack) {
        ScrollBody {
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton("添加服务", { adding = true }, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16))
            val total = servers.filter { it.enabled }.sumOf { s -> s.tools.count { it.name !in s.disabledTools } }
            if (total > McpCap.DEFAULT) {
                Text(McpText.over(total, McpCap.DEFAULT), style = IrisType.secondary, color = c.danger, modifier = Modifier.padding(IrisDimens.space16))
            }
            if (servers.isEmpty()) {
                Text(McpText.EMPTY, style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(IrisDimens.space16))
            }
            servers.forEach { s ->
                SectionTitle(s.name)
                SettingsGroup {
                    SwitchRow("启用", s.enabled, subtitle = s.url + (if (s.alwaysAllow) "\n已总是允许" else "")) { on -> mcp.update(s.id) { it.copy(enabled = on) } }
                    Divider()
                    SettingsRow(
                        if (testing == s.id) "正在连接…" else "测试连接",
                        subtitle = if (s.checkedAt > 0) "${s.tools.size} 个工具" else "尚未连接",
                        enabled = testing == null, onClick = { test(s.id) },
                    )
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                        IrisButton("编辑", { editing = s }, style = IrisButtonStyle.Tonal, compact = true, modifier = Modifier.weight(1f))
                        if (s.alwaysAllow) IrisButton("改为询问", { mcp.update(s.id) { it.copy(alwaysAllow = false) } }, style = IrisButtonStyle.Tonal, compact = true, modifier = Modifier.weight(1f))
                        IrisButton("删除", { deleting = s }, style = IrisButtonStyle.Tonal, compact = true, destructive = true, modifier = Modifier.weight(1f))
                    }
                    s.tools.forEach { t ->
                        Divider()
                        SwitchRow(t.name, t.name !in s.disabledTools, subtitle = com.iris.localai.agent.mcp.McpSchema.trimDescription(t.description).ifBlank { null }, enabled = s.enabled) { on ->
                            mcp.update(s.id) { it.copy(disabledTools = if (on) it.disabledTools - t.name else it.disabledTools + t.name) }
                        }
                    }
                }
            }
            GroupFootnote(McpText.NOTE)
            Spacer(Modifier.height(IrisDimens.space16))
        }
    }

    if (adding || editing != null) {
        val orig = editing
        McpEditSheet(orig, mcp, onDone = { saved ->
            adding = false; editing = null
            if (saved != null) test(saved)
        })
    }
    deleting?.let { s ->
        ConfirmDialog("删除「${s.name}」？", "将删除这个服务及其保存的令牌。", "删除", onConfirm = { mcp.delete(s.id) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun McpEditSheet(orig: McpServer?, mcp: McpManager, onDone: (String?) -> Unit) {
    val secret = remember(orig) { orig?.let { mcp.secrets.get(it.id) } }
    var name by remember { mutableStateOf(orig?.name.orEmpty()) }
    var url by remember { mutableStateOf(orig?.url.orEmpty()) }
    var token by remember { mutableStateOf(secret?.token.orEmpty()) }
    var headers by remember { mutableStateOf(secret?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty()) }
    val urlOk = url.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) } && url.trim().length > 10
    val fieldMod = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space6)
    IrisSheet(onDismiss = { onDone(null) }) {
        Column(Modifier.fillMaxWidth().padding(vertical = IrisDimens.space8)) {
            Text(if (orig == null) "添加 MCP 服务" else "编辑 MCP 服务", style = IrisType.title, color = Iris.colors.text, modifier = Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8))
            OutlinedTextField(name, { name = it }, modifier = fieldMod, singleLine = true, label = { Text("名称") }, shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            OutlinedTextField(url, { url = it }, modifier = fieldMod, singleLine = true, label = { Text("服务器地址（https://…/mcp）") }, shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            OutlinedTextField(token, { token = it }, modifier = fieldMod, singleLine = true, label = { Text("令牌（可选，Bearer）") },
                visualTransformation = PasswordVisualTransformation(), shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            OutlinedTextField(headers, { headers = it }, modifier = fieldMod, label = { Text("自定义请求头（可选，每行 名称: 值）") }, minLines = 2, shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            if (url.trim().startsWith("http://", true)) Text("http 地址不加密，令牌可能被截获。", style = IrisType.secondary, color = Iris.colors.danger, modifier = Modifier.padding(horizontal = IrisDimens.space16))
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton("保存并测试连接", {
                val id = orig?.id ?: McpManager.newId()
                val n = name.trim().ifEmpty { runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: "MCP" }
                val base = orig ?: McpServer(id, n, url.trim())
                val urlChanged = orig != null && orig.url != url.trim()
                mcp.upsert(base.copy(name = n, url = url.trim(), tools = if (urlChanged) emptyList() else base.tools, alwaysAllow = if (urlChanged) false else base.alwaysAllow), token, McpHeaders.parse(headers))
                onDone(id)
            }, enabled = urlOk, modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16))
            Spacer(Modifier.height(IrisDimens.space8))
        }
    }
}

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
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ae4ece)
    val NAV: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b7ad17)
    val NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1462ba)
    val EMPTY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e1ee4f)
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
            Toast.makeText(context, r.fold({ com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1a6c05, it) }, { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4d5cd5) + com.iris.localai.agent.mcp.McpClient.describe(it) }), Toast.LENGTH_LONG).show()
        }
    }

    SubpageScaffold(McpText.TITLE, onBack) {
        // 2.5.0 Beta6: free elements inset to the card edge (groupGutter) / row text (groupGutter + rowPadding) like the other subpages.
        ScrollBody {
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_bd700b), { adding = true }, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter))
            // 2.5.2: no MCP tool limit any more (every enabled tool is injected), so no over-limit warning.
            if (servers.isEmpty()) {
                Text(McpText.EMPTY, style = IrisType.secondary, color = c.textTertiary, modifier = Modifier.padding(horizontal = IrisDimens.groupGutter + IrisDimens.rowPadding, vertical = IrisDimens.space16))
            }
            servers.forEach { s ->
                SectionTitle(s.name)
                SettingsGroup {
                    SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7854b5), s.enabled, subtitle = s.url + (if (s.alwaysAllow) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f28c74) else "")) { on -> mcp.update(s.id) { it.copy(enabled = on) } }
                    Divider()
                    SettingsRow(
                        if (testing == s.id) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_594397) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_69e747),
                        subtitle = if (s.checkedAt > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6dfe26, s.tools.size) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_16ae3a),
                        enabled = testing == null, onClick = { test(s.id) },
                    )
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                        IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_95b351), { editing = s }, style = IrisButtonStyle.Tonal, compact = true, modifier = Modifier.weight(1f))
                        if (s.alwaysAllow) IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5c5b0a), { mcp.update(s.id) { it.copy(alwaysAllow = false) } }, style = IrisButtonStyle.Tonal, compact = true, modifier = Modifier.weight(1f))
                        IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), { deleting = s }, style = IrisButtonStyle.Tonal, compact = true, destructive = true, modifier = Modifier.weight(1f))
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
        ConfirmDialog(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_883acf, s.name), com.iris.localai.I18n.s(com.iris.localai.R.string.agent_45c23e), com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad), onConfirm = { mcp.delete(s.id) }, onDismiss = { deleting = null })
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
            Text(if (orig == null) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3136ac) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_9d3143), style = IrisType.title, color = Iris.colors.text, modifier = Modifier.padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8))
            OutlinedTextField(name, { name = it }, modifier = fieldMod, singleLine = true, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d7ec2d)) }, shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            OutlinedTextField(url, { url = it }, modifier = fieldMod, singleLine = true, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_211eb1)) }, shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            OutlinedTextField(token, { token = it }, modifier = fieldMod, singleLine = true, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c3dde5)) },
                visualTransformation = PasswordVisualTransformation(), shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            OutlinedTextField(headers, { headers = it }, modifier = fieldMod, label = { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6a0a31)) }, minLines = 2, shape = IrisRadius.mediumShape, colors = irisTextFieldColors())
            if (url.trim().startsWith("http://", true)) Text(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_133a18), style = IrisType.secondary, color = Iris.colors.danger, modifier = Modifier.padding(horizontal = IrisDimens.space16))
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dd609c), {
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

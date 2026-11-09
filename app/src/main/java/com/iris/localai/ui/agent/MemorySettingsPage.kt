package com.iris.localai.ui.agent

import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.memory.MemoryOps
import com.iris.localai.memory.MemoryTarget
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1.10.0 texts of 设置 › 记忆 (unit-tested). */
object MemoryText {
    val NAV: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_84fa29)
    val MASTER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1415ca)
    val INJECT_AGENT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d63f7c)
    val INJECT_CHAT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_4edca2)
    val DOC_NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1025dc)
    val LIST: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_44e4d7)
    val SUGGEST: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d5fb8d)
    val SUGGEST_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f7329b)
    fun listSubtitle(n: Int, pending: Int): String =
        (if (n == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c13df5) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_15ec09, n)) + if (pending > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_11e7a2, pending) else ""
    /** 2.2.9 E: the merged switch, as seen in 开发者选项. */
    val COMPACT_ONE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_23641b)
    fun injectBudget(contextTokens: Int): String =
        com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ba6880, com.iris.localai.memory.MemoryPrompt.budget(contextTokens), contextTokens)
    val COMPACT_NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ef6f5e)
    val CLEAR: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_54309c)

    fun usage(len: Int, cap: Int = MemoryOps.CAP): String = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_37f497, len, cap)
}

@Composable
fun MemorySettingsPage(
    onBack: () -> Unit,
    /** 2.1.3: 「上下文与记忆」 and 「记忆」 are one page, 「记忆与上下文」 (the context sections come first). */
    title: String = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dfeb13),
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    header: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val ms by agent.memorySettings.settings.collectAsStateWithLifecycle()
    val revision by agent.memoryRevision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var rebuilding by remember { mutableStateOf(false) }
    val app = remember { (context.applicationContext as IrisApp).container.settings }
    val appSettings by app.settings.collectAsStateWithLifecycle()
    val ctxTokens = appSettings.contextLength
    var showList by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val counts by produceState(0 to 0, revision) {
        value = withContext(Dispatchers.IO) { agent.memoryDocs.store.file().let { it.items.size to it.pending.size } }
    }
    var showKb by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showKb) {
        androidx.activity.compose.BackHandler { showKb = false }
        KnowledgePage(onBack = { showKb = false })
        return
    }
    if (showList) {
        androidx.activity.compose.BackHandler { showList = false }
        MemoryListPage(onBack = { showList = false })
        return
    }
    val indexed by produceState<Pair<Int, String>?>(null, revision) {
        value = withContext(Dispatchers.IO) { agent.history?.let { runCatching { it.count() to it.ftsVersion.uppercase() }.getOrNull() } }
    }

    SubpageScaffold(title, onBack, actions = actions) {
        ScrollBody {
            header()
            // 2.5.0 Beta3: the 长期记忆 master switch, 清空记忆 (→ 记忆 list page), 聊天时提议记住 and 自动压缩
            // (→ 上下文 · 记忆 · 知识库, the one copy) and 重建索引 (→ 知识库 page) left this developer page.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7))
            SettingsGroup {
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_fcd608), ms.injectAgent, subtitle = MemoryText.INJECT_AGENT, icon = com.iris.localai.ui.icons.IrisIcons.Agent, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(injectAgent = on) }
                }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ec50c7), ms.injectChat, subtitle = MemoryText.INJECT_CHAT, icon = com.iris.localai.ui.icons.IrisIcons.Chat, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(injectChat = on) }
                }
            }
            if (!ms.enabled) GroupFootnote(MemoryText.MASTER)

            KnowledgeEntry(onOpen = { showKb = true })

            // Beta3 merge: 历史检索索引 + 记忆注入预算 (two read-only rows) → one status row.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.v250_mem_info))
            SettingsGroup {
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_38d78c) + " · " + com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0a67bd), icon = com.iris.localai.ui.icons.IrisIcons.Layers,
                    subtitle = (indexed?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3ac76a, it.first, it.second) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dc9d89)) +
                        "\n" + MemoryText.injectBudget(ctxTokens),
                )
            }
            GroupFootnote(MemoryText.COMPACT_NOTE)
        }
    }
}

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
    /** 2.5.0 Beta5: open 记忆列表 / 知识库 as routes (animated); null = shown in place. Before [header]: callers pass it as the trailing lambda. */
    onOpenList: (() -> Unit)? = null,
    onOpenKnowledge: (() -> Unit)? = null,
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
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7))
            SettingsGroup {
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7), ms.enabled, subtitle = MemoryText.MASTER, icon = com.iris.localai.ui.icons.IrisIcons.Memory) { on ->
                    agent.memorySettings.update { it.copy(enabled = on) }
                }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_fcd608), ms.injectAgent, subtitle = MemoryText.INJECT_AGENT, icon = com.iris.localai.ui.icons.IrisIcons.Agent, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(injectAgent = on) }
                }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ec50c7), ms.injectChat, subtitle = MemoryText.INJECT_CHAT, icon = com.iris.localai.ui.icons.IrisIcons.Chat, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(injectChat = on) }
                }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_44e4d7))
            SettingsGroup {
                SettingsRow(
                    MemoryText.LIST, icon = Icons.Outlined.Face,
                    subtitle = MemoryText.listSubtitle(counts.first, counts.second),
                    onClick = { onOpenList?.invoke() ?: run { showList = true } },
                    trailing = { com.iris.localai.ui.components.Chevron() },
                )
                Divider()
                SwitchRow(MemoryText.SUGGEST, ms.suggest, subtitle = MemoryText.SUGGEST_SUB, icon = Icons.Outlined.AutoAwesome, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(suggest = on) }
                }
                Divider()
                SettingsRow(
                    MemoryText.CLEAR, icon = Icons.Outlined.DeleteSweep,
                    titleColor = Iris.colors.danger,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ab89b3),
                    enabled = counts.first > 0 || counts.second > 0,
                    onClick = { clearing = true },
                )
            }
            GroupFootnote(MemoryText.DOC_NOTE)

            KnowledgeEntry(onOpen = { onOpenKnowledge?.invoke() ?: run { showKb = true } })

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_593d49))
            SettingsGroup {
                // 2.2.9 E: 智能体自动压缩 + 对话自动压缩 → one switch (= 主页面「快满时自动整理」).
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dc0f2e), ms.autoCompact, subtitle = MemoryText.COMPACT_ONE, icon = Icons.Outlined.Compress) { on ->
                    agent.memorySettings.update { it.withAutoCompact(on) }
                }
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.agent_38d78c), icon = com.iris.localai.ui.icons.IrisIcons.Layers,
                    subtitle = indexed?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3ac76a, it.first, it.second) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dc9d89),
                )
            }
            GroupFootnote(MemoryText.COMPACT_NOTE)

            // 2.2.9 F: index / budget details that used to sit on the normal page.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_23b687))
            SettingsGroup {
                SettingsRow(
                    KnowledgeText.TITLE + " · " + KnowledgeText.REBUILD, icon = com.iris.localai.ui.icons.IrisIcons.Reload, enabled = !rebuilding,
                    subtitle = if (rebuilding) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_34a084) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ac06c2),
                    onClick = {
                        rebuilding = true
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { agent.knowledge.refresh(force = true) } }
                            rebuilding = false
                        }
                    },
                )
                Divider()
                SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0a67bd), icon = com.iris.localai.ui.icons.IrisIcons.Layers, subtitle = MemoryText.injectBudget(ctxTokens))
            }
        }
    }

    if (clearing) {
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5ad835),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_49ac8d),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_288f0c),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { agent.memoryDocs.clear() }
                    agent.memorySettings.update { it.copy(epoch = it.epoch + 1) }
                    agent.memoryChanged()
                }
            },
            onDismiss = { clearing = false },
        )
    }
}

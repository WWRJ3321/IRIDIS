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
    const val NAV = "上下文、记忆和知识库"
    const val MASTER = "关闭后不再注入记忆，智能体也不能使用「长期记忆」工具"
    const val INJECT_AGENT = "每个新对话开始时把记忆快照放进智能体的系统提示"
    const val INJECT_CHAT = "对话模式也带上记忆快照（会占用一些上下文）"
    const val DOC_NOTE = "记忆只存在本机，最多 200 条。聊天里说「记住：…」或点「记住？」即可添加，新对话起生效。"
    const val LIST = "记忆"
    const val SUGGEST = "聊天时提议记住"
    const val SUGGEST_SUB = "你说到自己的情况或偏好时，在回复下方提示「记住？」，点了才保存"
    fun listSubtitle(n: Int, pending: Int): String =
        (if (n == 0) "还没有记住任何事" else "记得你 $n 件事") + if (pending > 0) " · $pending 条等你确认" else ""
    const val COMPACT_AGENT = "智能体模式超出上下文时，把较早的对话按规则压缩成摘要（不调用模型）"
    const val COMPACT_CHAT = "对话模式同样自动压缩；仅在「按 token 预算」策略下生效"
    /** 2.2.9 E: the merged switch, as seen in 开发者选项. */
    const val COMPACT_ONE = "上下文快满时，把较早的对话整理成要点"
    fun injectBudget(contextTokens: Int): String =
        "上下文的 1/5，当前约 ${com.iris.localai.memory.MemoryPrompt.budget(contextTokens)} tokens（按 $contextTokens 计）；关于你/偏好放系统提示，相关事实每轮最多用一半"
    const val COMPACT_NOTE = "整理不删除消息，原文仍保存在本机。点上下文用量可手动整理或撤销。"
    const val CLEAR = "清空记忆"

    fun usage(len: Int, cap: Int = MemoryOps.CAP): String = "$len / $cap 字"
}

@Composable
fun MemorySettingsPage(
    onBack: () -> Unit,
    /** 2.1.3: 「上下文与记忆」 and 「记忆」 are one page, 「记忆与上下文」 (the context sections come first). */
    title: String = "记忆与上下文",
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
            SectionTitle("长期记忆")
            SettingsGroup {
                SwitchRow("长期记忆", ms.enabled, subtitle = MemoryText.MASTER, icon = com.iris.localai.ui.icons.IrisIcons.Memory) { on ->
                    agent.memorySettings.update { it.copy(enabled = on) }
                }
                Divider()
                SwitchRow("智能体模式注入", ms.injectAgent, subtitle = MemoryText.INJECT_AGENT, icon = com.iris.localai.ui.icons.IrisIcons.Agent, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(injectAgent = on) }
                }
                Divider()
                SwitchRow("对话模式注入", ms.injectChat, subtitle = MemoryText.INJECT_CHAT, icon = com.iris.localai.ui.icons.IrisIcons.Chat, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(injectChat = on) }
                }
            }

            SectionTitle("记忆")
            SettingsGroup {
                SettingsRow(
                    MemoryText.LIST, icon = Icons.Outlined.Face,
                    subtitle = MemoryText.listSubtitle(counts.first, counts.second),
                    onClick = { showList = true },
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
                    subtitle = "删除全部已记住的内容",
                    enabled = counts.first > 0 || counts.second > 0,
                    onClick = { clearing = true },
                )
            }
            GroupFootnote(MemoryText.DOC_NOTE)

            KnowledgeEntry(onOpen = { showKb = true })

            SectionTitle("上下文压缩")
            SettingsGroup {
                // 2.2.9 E: 智能体自动压缩 + 对话自动压缩 → one switch (= 主页面「快满时自动整理」).
                SwitchRow("自动压缩", ms.autoCompact, subtitle = MemoryText.COMPACT_ONE, icon = Icons.Outlined.Compress) { on ->
                    agent.memorySettings.update { it.withAutoCompact(on) }
                }
                Divider()
                SettingsRow(
                    "历史检索索引", icon = com.iris.localai.ui.icons.IrisIcons.Layers,
                    subtitle = indexed?.let { "已索引 ${it.first} 条消息与工具记录 · SQLite ${it.second}" } ?: "数据库不可用，「回忆」工具已隐藏",
                )
            }
            GroupFootnote(MemoryText.COMPACT_NOTE)

            // 2.2.9 F: index / budget details that used to sit on the normal page.
            SectionTitle("索引与注入")
            SettingsGroup {
                SettingsRow(
                    KnowledgeText.TITLE + " · " + KnowledgeText.REBUILD, icon = com.iris.localai.ui.icons.IrisIcons.Reload, enabled = !rebuilding,
                    subtitle = if (rebuilding) "正在重建…" else "重新读取知识库全部文件，一般不需要：文件变化会自动更新",
                    onClick = {
                        rebuilding = true
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { agent.knowledge.refresh(force = true) } }
                            rebuilding = false
                        }
                    },
                )
                Divider()
                SettingsRow("记忆注入预算", icon = com.iris.localai.ui.icons.IrisIcons.Layers, subtitle = MemoryText.injectBudget(ctxTokens))
            }
        }
    }

    if (clearing) {
        ConfirmDialog(
            title = "清空长期记忆？",
            message = "全部记忆会被删除，无法恢复。已经开始的对话不再注入记忆。",
            confirmText = "清空",
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

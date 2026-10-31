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
    const val NAV = "长期记忆（USER.md / MEMORY.md）、知识库与上下文压缩"
    const val MASTER = "关闭后不再注入记忆，智能体也不能使用「长期记忆」工具"
    const val INJECT_AGENT = "每个新对话开始时把记忆快照放进智能体的系统提示"
    const val INJECT_CHAT = "对话模式也带上记忆快照（会占用一些上下文）"
    const val DOC_NOTE = "两份文档都只保存在本机 App 私有目录，工作区文件工具无法读写。每份上限约 ${MemoryOps.CAP} 字；对话开始时冻结一份快照（模型第一次回答前会更新），对话中途修改的记忆从下一个新对话起生效。聊天里输入「记住：…」或在消息上选「记住这条」即可添加，不需要模型。"
    const val COMPACT_AGENT = "智能体模式超出上下文时，把较早的对话按规则压缩成摘要（不调用模型）"
    const val COMPACT_CHAT = "对话模式同样自动压缩；仅在「按 token 预算」策略下生效"
    const val COMPACT_NOTE = "压缩不删除任何消息：原文与工具记录保存在本机数据库，智能体可用「回忆」工具检索。聊天里点上下文用量可手动「压缩上下文」或撤销。"
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
    var editing by remember { mutableStateOf<MemoryTarget?>(null) }
    var clearing by remember { mutableStateOf(false) }

    val docs by produceState(MemoryTarget.entries.associateWith { "" }, revision) {
        value = withContext(Dispatchers.IO) { MemoryTarget.entries.associateWith { agent.memoryDocs.read(it) } }
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

            SectionTitle("记忆文档")
            SettingsGroup {
                MemoryTarget.entries.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    val text = docs[t].orEmpty()
                    SettingsRow(
                        "${t.label} · ${t.fileName}",
                        icon = if (t == MemoryTarget.USER) Icons.Outlined.Face else Icons.Outlined.Description,
                        subtitle = (if (text.isBlank()) t.hint else text.lines().take(3).joinToString(" ")) + "\n" + MemoryText.usage(text.length),
                        subtitleMaxLines = 4,
                        onClick = { editing = t },
                    )
                }
                Divider()
                SettingsRow(
                    MemoryText.CLEAR, icon = Icons.Outlined.DeleteSweep,
                    titleColor = Iris.colors.danger,
                    subtitle = "删除 USER.md 与 MEMORY.md 的全部内容",
                    enabled = docs.values.any { it.isNotBlank() },
                    onClick = { clearing = true },
                )
            }
            GroupFootnote(MemoryText.DOC_NOTE)

            KnowledgeSection()

            SectionTitle("上下文压缩")
            SettingsGroup {
                SwitchRow("智能体自动压缩", ms.autoCompactAgent, subtitle = MemoryText.COMPACT_AGENT, icon = Icons.Outlined.Compress) { on ->
                    agent.memorySettings.update { it.copy(autoCompactAgent = on) }
                }
                Divider()
                SwitchRow("对话自动压缩", ms.autoCompactChat, subtitle = MemoryText.COMPACT_CHAT, icon = Icons.Outlined.Compress) { on ->
                    agent.memorySettings.update { it.copy(autoCompactChat = on) }
                }
                Divider()
                SettingsRow(
                    "历史检索索引", icon = com.iris.localai.ui.icons.IrisIcons.Layers,
                    subtitle = indexed?.let { "已索引 ${it.first} 条消息与工具记录 · SQLite ${it.second}" } ?: "数据库不可用，「回忆」工具已隐藏",
                )
            }
            GroupFootnote(MemoryText.COMPACT_NOTE)
        }
    }

    editing?.let { t ->
        var value by remember(t) { mutableStateOf(docs[t].orEmpty()) }
        val over = value.trim().length > MemoryOps.CAP
        IrisDialog(
            onDismiss = { editing = null },
            title = "${t.label} · ${t.fileName}",
            confirmText = "保存",
            confirmEnabled = !over,
            onConfirm = {
                val text = value
                editing = null
                scope.launch {
                    withContext(Dispatchers.IO) { agent.memoryDocs.write(t, text) }
                    agent.memoryChanged()
                }
            },
        ) {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value, { value = it },
                    minLines = 6, maxLines = 14,
                    textStyle = IrisType.body.copy(color = Iris.colors.text),
                    placeholder = { Text("每行一条，例如：\n- 喜欢简短的回答", style = IrisType.body, color = Iris.colors.textTertiary) },
                    shape = IrisRadius.mediumShape,
                    colors = irisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(IrisDimens.space6))
                Text(
                    MemoryText.usage(value.trim().length) + if (over) " · 超出上限，请删减" else "",
                    style = IrisType.caption, color = if (over) Iris.colors.danger else Iris.colors.textTertiary,
                )
            }
        }
    }
    if (clearing) {
        ConfirmDialog(
            title = "清空长期记忆？",
            message = "USER.md 与 MEMORY.md 会被删除，无法恢复。已经开始的对话不再注入记忆。",
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

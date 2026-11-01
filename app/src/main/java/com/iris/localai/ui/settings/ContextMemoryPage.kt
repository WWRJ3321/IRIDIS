package com.iris.localai.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.ContextStrategy
import com.iris.localai.ui.chat.ContextText
import com.iris.localai.ui.chat.ContextUsage
import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 2.2.9 F texts of 设置 › 上下文 · 记忆 · 知识库 (unit-tested; no implementation words here). */
object ContextMemoryText {
    const val TITLE = "上下文 · 记忆 · 知识库"
    const val NAV = "上下文、记忆和知识库"
    const val NAV_SUB = "对话用量、记住的事和你的资料"
    const val PRIVACY = "全部保存在这台手机里，不上传"
    const val HINT = "高级设置在开发者选项"
    const val FOOTNOTE = "索引、压缩细节在 关于 › 开发者选项"
    const val SEC_CONTEXT = "上下文"
    const val SEC_MEMORY = "记忆"
    const val SEC_KB = "知识库"
    const val SUGGEST = "聊天时提议记住"
    const val SUGGEST_SUB = "发现「我是…」「以后都…」这类话，回答下方出现一个「记住？」，点一下才保存"
    const val KB_TOGGLE = "回答时参考知识库"
    const val KB_SUB = "问到相关内容时，自动把对应段落交给模型，并在回答下标出出处"

    fun memoryTitle(n: Int): String = if (n == 0) "还没有记住任何事" else "记得你 $n 件事"
    fun memorySub(latest: String?, ago: String?, pending: Int): String = listOfNotNull(
        latest?.let { "最近记住：" + it.take(24) + if (it.length > 24) "…" else "" },
        ago,
        pending.takeIf { it > 0 }?.let { "$it 条等你确认" },
    ).joinToString(" · ").ifEmpty { "说「记住：…」或点回答下的「记住？」就能添加" }
    fun kbTitle(files: Int): String = if (files == 0) "还没有文件" else "$files 个文件"
    fun kbSub(files: Int, week: Int): String = if (files == 0) "添加资料后，回答时会自动参考" else "本周回答时引用了 $week 次"

    /** 「刚刚」/「5 分钟前」/「2 小时前」/「3 天前」. */
    fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - then).coerceAtLeast(0) / 60_000).toInt()
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "$m 分钟前"
            m < 60 * 24 -> "${m / 60} 小时前"
            else -> "${m / (60 * 24)} 天前"
        }
    }

    /** Words that must not appear on this page (acceptance list). */
    val FORBIDDEN = listOf("USER.md", "MEMORY.md", "FTS", "token 预算", "KV 平移", "KV 上下文平移")
}

/**
 * 2.2.9 F: 设置 › 上下文 · 记忆 · 知识库 — three cards, each a status line and one switch. Everything
 * else (window, strategies, injection, indexes, 清空记忆) lives in 开发者选项 › 上下文与记忆.
 */
@Composable
fun ContextMemoryPage(vm: SettingsViewModel, usage: ContextUsage, onBack: () -> Unit, onDeveloper: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val agent = container.agent
    val s by vm.settings.collectAsStateWithLifecycle()
    val ms by agent.memorySettings.settings.collectAsStateWithLifecycle()
    val revision by agent.memoryRevision.collectAsStateWithLifecycle()
    var showList by rememberSaveable { mutableStateOf(false) }
    var showKb by rememberSaveable { mutableStateOf(false) }
    var hint by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!s.contextHintShown) { hint = true; vm.update { it.copy(contextHintShown = true) } }
    }

    if (showList) {
        androidx.activity.compose.BackHandler { showList = false }
        com.iris.localai.ui.agent.MemoryListPage(onBack = { showList = false })
        return
    }
    if (showKb) {
        androidx.activity.compose.BackHandler { showKb = false }
        com.iris.localai.ui.agent.KnowledgePage(onBack = { showKb = false })
        return
    }

    val mem by produceState<Mem?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                agent.memoryDocs.store.file().let { f ->
                    val last = f.items.maxByOrNull { it.updatedAt }
                    Mem(f.items.size, f.pending.size, last?.text, last?.updatedAt ?: 0L)
                }
            }.getOrNull()
        }
    }
    val kb by produceState<Pair<Int, Int>?>(null, showKb) {
        value = withContext(Dispatchers.IO) { runCatching { agent.knowledge.files().size to agent.knowledge.citesThisWeek() }.getOrNull() }
    }
    val takeover = ContextAssembly.devTakeover(ContextStrategy.of(s.contextStrategy))

    SubpageScaffold(ContextMemoryText.TITLE, onBack) {
        ScrollBody {
            PrivacyBanner()
            if (hint) HintRow(onClick = onDeveloper)

            SectionTitle(ContextMemoryText.SEC_CONTEXT)
            SettingsGroup {
                UsageBlock(usage)
                InsetLine()
                SwitchRow(
                    ContextText.AUTO, ms.autoCompact,
                    subtitle = if (takeover) ContextAssembly.TAKEOVER else ContextText.AUTO_SUB,
                ) { on -> agent.memorySettings.update { it.withAutoCompact(on) } }
            }

            SectionTitle(ContextMemoryText.SEC_MEMORY)
            SettingsGroup {
                StatusRow(
                    mem?.let { ContextMemoryText.memoryTitle(it.n) } ?: "正在读取…",
                    mem?.let { m -> ContextMemoryText.memorySub(m.latest, m.latest?.let { ContextMemoryText.ago(m.at) }, m.pending) } ?: "",
                    onClick = { showList = true },
                )
                InsetLine()
                SwitchRow(ContextMemoryText.SUGGEST, ms.suggest, subtitle = ContextMemoryText.SUGGEST_SUB, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(suggest = on) }
                }
            }

            SectionTitle(ContextMemoryText.SEC_KB)
            SettingsGroup {
                StatusRow(
                    kb?.let { ContextMemoryText.kbTitle(it.first) } ?: "正在读取…",
                    kb?.let { ContextMemoryText.kbSub(it.first, it.second) } ?: "",
                    onClick = { showKb = true },
                )
                InsetLine()
                SwitchRow(ContextMemoryText.KB_TOGGLE, s.knowledgeInChat, subtitle = ContextMemoryText.KB_SUB) { on ->
                    vm.update { it.copy(knowledgeInChat = on) }
                }
            }

            GroupFootnote(ContextMemoryText.FOOTNOTE, Modifier.clickable(onClick = onDeveloper))
        }
    }
}

private data class Mem(val n: Int, val pending: Int, val latest: String?, val at: Long)

/** Success-tinted strip; text mixed toward the body colour so it reads on light themes too. */
@Composable
private fun PrivacyBanner() {
    val c = Iris.colors
    val fg = if (c.isDark) c.success else lerp(c.success, c.text, 0.35f)
    Row(
        Modifier
            .padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space8)
            .fillMaxWidth()
            .clip(IrisRadius.largeShape)
            .background(c.success.copy(alpha = if (c.isDark) 0.16f else 0.12f))
            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space12),
    ) {
        Box(Modifier.size(IrisDimens.space8 + IrisDimens.space4).clip(CircleShape).background(c.success))
        Text(ContextMemoryText.PRIVACY, style = IrisType.body, color = fg)
    }
}

@Composable
private fun HintRow(onClick: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier
            .padding(horizontal = IrisDimens.groupGutter)
            .fillMaxWidth()
            .clip(IrisRadius.largeShape)
            .background(c.accent.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ContextMemoryText.HINT, style = IrisType.secondary, color = c.accent, modifier = Modifier.weight(1f))
        Chevron()
    }
}

@Composable
private fun UsageBlock(u: ContextUsage) {
    val c = Iris.colors
    val frac by animateFloatAsState(u.fraction, motionSpec(IrisMotion.Gentle, 0.001f), label = "ctxCardFrac")
    val fill = if (u.nearFull) c.danger else c.accent
    val track = c.outline.copy(alpha = 0.45f)
    Column(Modifier.fillMaxWidth().padding(IrisDimens.space16)) {
        Text(ContextText.usageTitle(u), style = IrisType.title, color = c.text)
        Spacer(Modifier.height(IrisDimens.space12))
        Canvas(Modifier.fillMaxWidth().height(IrisDimens.space8)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (frac > 0f) drawRoundRect(fill, Offset.Zero, Size(size.width * frac, size.height), r)
        }
        Spacer(Modifier.height(IrisDimens.space8))
        Text(ContextText.usageDetail(u), style = IrisType.secondary, color = c.textTertiary)
    }
}

@Composable
private fun StatusRow(title: String, subtitle: String, onClick: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(IrisDimens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.title, color = c.text)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(IrisDimens.space4))
                Text(subtitle, style = IrisType.secondary, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(IrisDimens.space12))
        Chevron()
    }
}

@Composable
private fun InsetLine() {
    Box(Modifier.padding(horizontal = IrisDimens.space16).fillMaxWidth().height(IrisDimens.hairline).background(Iris.colors.outline))
}

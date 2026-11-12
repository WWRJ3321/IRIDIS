package com.iris.localai.ui.settings

import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
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
import androidx.compose.foundation.layout.heightIn
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
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_149bed)
    val NAV: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_84fa29)
    val NAV_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e011af)
    val PRIVACY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8c4fa6)
    val HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_949d51)
    val FOOTNOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f7ccc4)
    val SEC_CONTEXT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_50f198)
    val SEC_MEMORY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_44e4d7)
    val SEC_KB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7e4333)
    val SUGGEST: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d5fb8d)
    val SUGGEST_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7f2a54)
    val KB_TOGGLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_65e375)
    val KB_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cca5e7)
    /** 2.5.0 Beta5: 长期记忆 — the switch that was the memory tool row on 设置 › 智能体. */
    val MEMORY_SWITCH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_de49f7)
    val MEMORY_SWITCH_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1415ca)
    /** On = memory on and the agent's memory tool on (either off means the agent cannot remember). */
    fun memoryOn(memoryEnabled: Boolean, s: com.iris.localai.agent.AgentSettings): Boolean =
        memoryEnabled && s.isEnabled(com.iris.localai.agent.MemoryTool)
    fun withMemoryTool(s: com.iris.localai.agent.AgentSettings, on: Boolean): com.iris.localai.agent.AgentSettings =
        s.copy(enabled = s.enabled + (com.iris.localai.agent.MemoryTool.name to on))

    fun memoryTitle(n: Int): String = if (n == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c13df5) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_15ec09, n)
    fun memorySub(latest: String?, ago: String?, pending: Int): String = listOfNotNull(
        latest?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1cd2f0) + it.take(24) + if (it.length > 24) "…" else "" },
        ago,
        pending.takeIf { it > 0 }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fb4b41, it) },
    ).joinToString(" · ").ifEmpty { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2d204a) }
    fun kbTitle(files: Int): String = if (files == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e93a44) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f87840, files)
    fun kbSub(files: Int, week: Int): String = if (files == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fb6801) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0b7ad7, week)

    /** 「刚刚」/「5 分钟前」/「2 小时前」/「3 天前」. */
    fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - then).coerceAtLeast(0) / 60_000).toInt()
        return when {
            m < 1 -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_4181f7)
            m < 60 -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_aea893, m)
            m < 60 * 24 -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_d935fb, m / 60)
            else -> com.iris.localai.I18n.s(com.iris.localai.R.string.hints_b24ae9, m / (60 * 24))
        }
    }

    /** Words that must not appear on this page (acceptance list). */
    val FORBIDDEN = listOf("USER.md", "MEMORY.md", "FTS", com.iris.localai.I18n.s(com.iris.localai.R.string.settings_56ba83), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fc69cd), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6649d1))
}

/**
 * 2.2.9 F: 设置 › 上下文 · 记忆 · 知识库 — three cards, each a status line and one switch. Everything
 * else (window, strategies, injection, indexes, 清空记忆) lives in 开发者选项 › 上下文与记忆.
 */
@Composable
fun ContextMemoryPage(
    vm: SettingsViewModel, usage: ContextUsage, onBack: () -> Unit, onDeveloper: () -> Unit,
    /**
     * 2.5.0 Beta5: 记忆列表 / 知识库 open as their own routes in MainActivity's AnimatedContent (the same
     * push / pop slide and predictive back as every other settings subpage). null = shown in place (old behaviour).
     */
    onOpenList: (() -> Unit)? = null,
    onOpenKnowledge: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val agent = container.agent
    val s by vm.settings.collectAsStateWithLifecycle()
    val ms by agent.memorySettings.settings.collectAsStateWithLifecycle()
    // 2.5.0 Beta5: 长期记忆 switch moved here from 设置 › 智能体 (the memory tool row).
    val agentSettings by agent.settings.settings.collectAsStateWithLifecycle()
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
                    mem?.let { ContextMemoryText.memoryTitle(it.n) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a),
                    mem?.let { m -> ContextMemoryText.memorySub(m.latest, m.latest?.let { ContextMemoryText.ago(m.at) }, m.pending) } ?: "",
                    onClick = { onOpenList?.invoke() ?: run { showList = true } },
                )
                InsetLine()
                SwitchRow(
                    ContextMemoryText.MEMORY_SWITCH, ContextMemoryText.memoryOn(ms.enabled, agentSettings),
                    subtitle = ContextMemoryText.MEMORY_SWITCH_SUB,
                ) { on ->
                    agent.memorySettings.update { it.copy(enabled = on) }
                    agent.settings.update { ContextMemoryText.withMemoryTool(it, on) }
                }
                InsetLine()
                SwitchRow(ContextMemoryText.SUGGEST, ms.suggest, subtitle = ContextMemoryText.SUGGEST_SUB, enabled = ms.enabled) { on ->
                    agent.memorySettings.update { it.copy(suggest = on) }
                }
            }

            SectionTitle(ContextMemoryText.SEC_KB)
            SettingsGroup(Modifier.tourTarget(TourId.KB)) {
                StatusRow(
                    kb?.let { ContextMemoryText.kbTitle(it.first) } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_62a33a),
                    kb?.let { ContextMemoryText.kbSub(it.first, it.second) } ?: "",
                    onClick = { onOpenKnowledge?.invoke() ?: run { showKb = true } },
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
    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.rowVerticalPadding + IrisDimens.space4)) {
        // 2.5.0 Beta5: row title metrics (IrisType.title grew to 20 sp in 2.5.0 and no longer matched the rows).
        Text(ContextText.usageTitle(u), style = IrisType.row, color = c.text)
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
        // 2.5.0 Beta5: same paddings / min height / type as SettingsRow, so it lines up with the switch rows below.
        Modifier.fillMaxWidth().heightIn(min = IrisDimens.rowMinHeight).clickable(onClick = onClick)
            .padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IrisType.row, color = c.text)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(IrisDimens.space4))
                Text(subtitle, style = IrisType.rowSummary, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(IrisDimens.space12))
        Chevron()
    }
}

@Composable
private fun InsetLine() {
    Box(Modifier.padding(horizontal = IrisDimens.rowPadding).fillMaxWidth().height(IrisDimens.hairline).background(Iris.colors.outline))
}

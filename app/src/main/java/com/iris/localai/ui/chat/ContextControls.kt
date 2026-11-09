package com.iris.localai.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iris.localai.data.ContextStrategy
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType

/** "1.2k / 4k" with a thin fill bar; turns to the warning colour near the limit. Tap = context menu. */
@Composable
fun ContextMeter(usage: ContextUsage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (usage.limit <= 0 && usage.used <= 0) return
    val c = Iris.colors
    val frac by animateFloatAsState(usage.fraction, motionSpec(IrisMotion.Gentle, 0.001f), label = "ctxFrac")
    val tint by animateColorAsState(if (usage.nearFull) c.danger else c.textTertiary, motionSpec(IrisMotion.Color), label = "ctxTint")
    Row(
        modifier
            .clip(com.iris.localai.ui.theme.IrisRadius.pillShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_82e5eb, usage.used, usage.limit) }
            .padding(horizontal = com.iris.localai.ui.theme.IrisSpace.S, vertical = IrisDimens.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6),
    ) {
        val track = c.outline.copy(alpha = 0.45f)
        Canvas(Modifier.width(28.dp).height(IrisDimens.space4)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (frac > 0f) drawRoundRect(tint, Offset.Zero, Size(size.width * frac, size.height), r)
        }
        Text(
            (if (usage.exact) "" else "≈") + "${short(usage.used)} / ${if (usage.limit > 0) short(usage.limit) else "?"}" +
                (if (usage.summarizing) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_744a56) else "") + ContextText.meterSuffix(usage),
            style = IrisType.caption, color = tint, maxLines = 1,
        )
    }
}

private fun short(n: Int): String = shortTokens(n)

/** Per-conversation context menu: strategy override, manual break, summary. */
@Composable
fun ContextSheet(
    usage: ContextUsage,
    globalStrategy: ContextStrategy,
    keepTurns: Int,
    summary: String,
    hasMessages: Boolean,
    onStrategy: (Int?) -> Unit,
    onBreakHere: () -> Unit,
    onClearBreak: () -> Unit,
    onClearSummary: () -> Unit,
    onDismiss: () -> Unit,
    compactionText: String = "",
    onCompact: () -> Unit = {},
    onClearCompaction: () -> Unit = {},
    /** 2.2.9 E: 本对话的策略 only with 开发者选项 › 开发者模式 on. */
    showStrategy: Boolean = false,
) {
    val c = Iris.colors
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_50f198))
        Column(Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4)) {
            Text(ContextText.usageTitle(usage), style = IrisType.body, color = if (usage.nearFull) c.danger else c.text)
            Spacer(Modifier.height(IrisDimens.space4))
            Text(ContextText.usageDetail(usage), style = IrisType.secondary, color = c.textSecondary)
            Spacer(Modifier.height(IrisDimens.space6))
            val track = c.outline.copy(alpha = 0.4f)
            val fill = if (usage.nearFull) c.danger else c.textSecondary
            Canvas(Modifier.fillMaxWidth().height(IrisDimens.space6)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(fill, Offset.Zero, Size(size.width * usage.fraction, size.height), r)
            }
        }
        if (showStrategy || usage.overridden) {
        SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2436d1))
        SettingsGroup {
            StrategyRow(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_82ef16), com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6100c4, globalStrategy.label), selected = !usage.overridden, enabled = true) { onStrategy(null) }
            ContextStrategy.entries.forEach { st ->
                Divider()
                val unsupported = st == ContextStrategy.KV_SHIFT && usage.shiftUnsupported != null
                StrategyRow(
                    st.label,
                    when {
                        unsupported -> usage.shiftUnsupported!!
                        st == ContextStrategy.SLIDING_WINDOW -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_efeb27, keepTurns)
                        else -> st.hint
                    },
                    selected = usage.overridden && usage.strategy == st,
                    enabled = !unsupported,
                ) { onStrategy(st.ordinal) }
            }
        }
        }
        SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1107b5))
        SettingsGroup {
            SettingsRow(
                com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f2e622), icon = Icons.Outlined.ContentCut, enabled = hasMessages,
                subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_b31514),
                onClick = { onBreakHere(); onDismiss() },
            )
            if (usage.hasBreak) {
                Divider()
                SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_97702e), icon = Icons.Outlined.LinkOff, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_87afd1), onClick = { onClearBreak(); onDismiss() })
            }
            if (usage.hasSummary) {
                Divider()
                SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d20427), icon = Icons.Outlined.DeleteSweep, subtitle = summary, subtitleMaxLines = 4, onClick = { onClearSummary(); onDismiss() })
            }
        }
        // 1.10.0: deterministic compaction (no model call).
        SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1b6c77))
        SettingsGroup {
            SettingsRow(
                ContextText.COMPACT, icon = Icons.Outlined.Compress, enabled = hasMessages,
                subtitle = ContextText.COMPACT_HINT,
                onClick = { onCompact(); onDismiss() },
            )
            if (usage.compactedMessages > 0) {
                Divider()
                SettingsRow(
                    ContextText.UNDO_COMPACT, icon = Icons.Outlined.UnfoldMore,
                    subtitle = ContextText.compacted(usage) + "\n" + compactionText, subtitleMaxLines = 6,
                    onClick = { onClearCompaction(); onDismiss() },
                )
            }
        }
        GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f0c202) + if (usage.memoryInjected) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5d33fc) else "")
    }
}

@Composable
private fun StrategyRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SettingsRow(title, subtitle = subtitle, enabled = enabled, role = Role.RadioButton, onClick = onClick) {
        Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f08afd), tint = Iris.colors.text, modifier = Modifier.size(IrisDimens.iconMedium))
        }
    }
}

/** 1.10.0 texts of compaction (unit-tested). */
object ContextText {
    val COMPACT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_16a350)
    val COMPACT_HINT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_84cbc7)
    val UNDO_COMPACT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_a442e8)
    /** 2.2.9 E: the one switch. */
    val AUTO: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_bbba84)
    val AUTO_SUB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_25e748)
    fun compacted(u: ContextUsage): String = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_26ac1c, u.compactedMessages) + if (u.compactions > 1) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_71b5d7, u.compactions) else ""
    fun meterSuffix(u: ContextUsage): String = if (u.compactedMessages > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_fcc0f3) else ""
    fun divider(n: Int): String = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_e4733e, n)
    fun compactNotice(n: Int): String = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_a29a4d, n)
    /** 2.2.9 E: 「这次对话用了 41%」 (no conversation / no model → 「这次对话还没开始」). */
    fun usageTitle(u: ContextUsage): String = if (u.limit > 0 && u.used > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5cde4e, (u.fraction * 100).toInt()) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_98486a)
    /** 「这次对话用了 41% · 模型现在能看到最近 26 条」. */
    fun usageLine(u: ContextUsage): String = usageTitle(u) + if (u.visibleMessages > 0) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_25ff72, u.visibleMessages) else ""
    /** Detail line under the bar: 「约 3.4k / 8k · 模型现在能看到最近 26 条」. */
    fun usageDetail(u: ContextUsage): String = listOfNotNull(
        if (u.limit > 0) (if (u.exact) "" else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_768d35)) + "${shortTokens(u.used)} / ${shortTokens(u.limit)}" else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_3c47ff),
        u.visibleMessages.takeIf { it > 0 }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.chat_a20e8a, it) },
        // 2.3.2: knowledge snippets counted apart (part of the total above).
        u.knowledgeTokens.takeIf { it > 0 }?.let { com.iris.localai.I18n.s(com.iris.localai.R.string.kb232_meter, shortTokens(it)) },
    ).joinToString(" · ")
}

/** 「3.4k」 / 「12k」 / 「800」. */
fun shortTokens(n: Int): String = when {
    n >= 1024 && n % 1024 == 0 -> "${n / 1024}k" // window sizes: 8192 → 8k
    n >= 10_000 -> "${n / 1000}k"
    n >= 1000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000f)
    else -> "$n"
}

/** 1.10.0: thin marker after the last compacted message (tap = 撤销压缩). */
@Composable
fun CompactionDivider(count: Int, onClear: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).padding(end = IrisDimens.space8)) {
            Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawRect(c.outline.copy(alpha = 0.6f)) }
        }
        Icon(Icons.Outlined.Compress, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
        Text(
            ContextText.divider(count), style = IrisType.caption, color = c.textTertiary,
            modifier = Modifier.clip(com.iris.localai.ui.theme.IrisRadius.pillShape).clickable(onClick = onClear).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
        )
        Box(Modifier.weight(1f).height(1.dp).padding(start = IrisDimens.space8)) {
            Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawRect(c.outline.copy(alpha = 0.6f)) }
        }
    }
}

/** Divider shown in the message list after the message where a new context starts. */
@Composable
fun ContextBreakDivider(onClear: () -> Unit) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).padding(end = IrisDimens.space8)) {
            Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawRect(c.outline) }
        }
        Text(
            com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c7217c), style = IrisType.caption, color = c.textTertiary,
            modifier = Modifier.clip(com.iris.localai.ui.theme.IrisRadius.pillShape).clickable(onClick = onClear).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
        )
        Box(Modifier.weight(1f).height(1.dp).padding(start = IrisDimens.space8)) {
            Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawRect(c.outline) }
        }
    }
}

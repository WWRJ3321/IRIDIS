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
            .semantics { contentDescription = "上下文用量 ${usage.used} / ${usage.limit}" }
            .padding(horizontal = 10.dp, vertical = IrisDimens.space4),
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
                (if (usage.summarizing) " · 正在摘要…" else "") + ContextText.meterSuffix(usage),
            style = IrisType.caption, color = tint, maxLines = 1,
        )
    }
}

private fun short(n: Int): String = when {
    n >= 10_000 -> "${n / 1000}k"
    n >= 1000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000f)
    else -> "$n"
}

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
) {
    val c = Iris.colors
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader("上下文")
        Column(Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4)) {
            Text(
                if (usage.limit > 0) "${if (usage.exact) "已用" else "约用"} ${usage.used} / ${usage.limit} tokens（${(usage.fraction * 100).toInt()}%）"
                else "加载模型后显示上下文上限",
                style = IrisType.secondary, color = if (usage.nearFull) c.danger else c.textSecondary,
            )
            Spacer(Modifier.height(IrisDimens.space6))
            val track = c.outline.copy(alpha = 0.4f)
            val fill = if (usage.nearFull) c.danger else c.textSecondary
            Canvas(Modifier.fillMaxWidth().height(IrisDimens.space6)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(fill, Offset.Zero, Size(size.width * usage.fraction, size.height), r)
            }
        }
        SectionTitle("本对话的策略")
        SettingsGroup {
            StrategyRow("跟随全局默认", "现在是「${globalStrategy.label}」", selected = !usage.overridden, enabled = true) { onStrategy(null) }
            ContextStrategy.entries.forEach { st ->
                Divider()
                val unsupported = st == ContextStrategy.KV_SHIFT && usage.shiftUnsupported != null
                StrategyRow(
                    st.label,
                    when {
                        unsupported -> usage.shiftUnsupported!!
                        st == ContextStrategy.SLIDING_WINDOW -> "保留最近 $keepTurns 轮（设置里可改）"
                        else -> st.hint
                    },
                    selected = usage.overridden && usage.strategy == st,
                    enabled = !unsupported,
                ) { onStrategy(st.ordinal) }
            }
        }
        SectionTitle("分隔与摘要")
        SettingsGroup {
            SettingsRow(
                "从这里开始新上下文", icon = Icons.Outlined.ContentCut, enabled = hasMessages,
                subtitle = "上面的对话仍然显示，但之后的回复不再参考它们",
                onClick = { onBreakHere(); onDismiss() },
            )
            if (usage.hasBreak) {
                Divider()
                SettingsRow("取消分隔", icon = Icons.Outlined.LinkOff, subtitle = "重新把全部对话交给模型（受上下文长度限制）", onClick = { onClearBreak(); onDismiss() })
            }
            if (usage.hasSummary) {
                Divider()
                SettingsRow("清除摘要", icon = Icons.Outlined.DeleteSweep, subtitle = summary, subtitleMaxLines = 4, onClick = { onClearSummary(); onDismiss() })
            }
        }
        // 1.10.0: deterministic compaction (no model call).
        SectionTitle("压缩")
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
        GroupFootnote("裁剪按整轮（提问+回答）进行，并且成段移动，尽量让模型能复用已算好的 KV 缓存。" + if (usage.memoryInjected) "\n本对话已带上长期记忆快照。" else "")
    }
}

@Composable
private fun StrategyRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SettingsRow(title, subtitle = subtitle, enabled = enabled, role = Role.RadioButton, onClick = onClick) {
        Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, "已选择", tint = Iris.colors.text, modifier = Modifier.size(IrisDimens.iconMedium))
        }
    }
}

/** 1.10.0 texts of compaction (unit-tested). */
object ContextText {
    const val COMPACT = "压缩上下文"
    const val COMPACT_HINT = "把最近一轮之前的对话按规则压缩成摘要（目标、未完成事项、引用、关键信号），不调用模型，原文仍保留"
    const val UNDO_COMPACT = "撤销压缩"
    fun compacted(u: ContextUsage): String = "已压缩 ${u.compactedMessages} 条消息" + if (u.compactions > 1) "（${u.compactions} 次）" else ""
    fun meterSuffix(u: ContextUsage): String = if (u.compactedMessages > 0) " · 已压缩" else ""
    fun divider(n: Int): String = "以上 $n 条消息已压缩 · 撤销"
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
            "新上下文从这里开始 · 撤销", style = IrisType.caption, color = c.textTertiary,
            modifier = Modifier.clip(com.iris.localai.ui.theme.IrisRadius.pillShape).clickable(onClick = onClear).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
        )
        Box(Modifier.weight(1f).height(1.dp).padding(start = IrisDimens.space8)) {
            Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawRect(c.outline) }
        }
    }
}

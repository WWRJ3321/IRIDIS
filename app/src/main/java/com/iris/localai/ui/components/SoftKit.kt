package com.iris.localai.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisElevation
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.SoftLook
import com.iris.localai.ui.theme.softShadow

/** 2.5.0: what the settings 重点卡 shows for the engine (pure, unit-tested). */
enum class HeroStatus { NONE, LOADING, READY, FAILED }

/**
 * 2.5.0 设置首页顶部的重点卡: the current model and its state, with one action (切换模型).
 * 柔光: a coloured accent gradient card with white text; 经典: an ordinary grouped card.
 */
@Composable
fun HeroCard(
    label: String,
    title: String,
    status: String,
    state: HeroStatus,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Iris.colors
    val soft = softStyle()
    val fg = if (soft) Color.White else c.text
    val fg2 = if (soft) Color.White.copy(alpha = 0.82f) else c.textSecondary
    val bg = if (soft) Brush.linearGradient(
        listOf(c.accent, SoftLook.mix(c.accent, Color(0xFFE5779B), 0.45f), SoftLook.mix(c.accent, Color(0xFFE5779B), 0.8f)),
    ) else Brush.linearGradient(listOf(c.surfaceElevated, c.surfaceElevated))
    val dot = when (state) {
        HeroStatus.READY -> if (soft) Color(0xFF7CF0B0) else c.success
        HeroStatus.LOADING -> if (soft) Color.White.copy(alpha = 0.7f) else c.warning
        HeroStatus.FAILED -> if (soft) Color(0xFFFFB4B4) else c.danger
        HeroStatus.NONE -> null
    }
    Box(
        modifier.padding(horizontal = IrisDimens.groupGutter).fillMaxWidth()
            .softShadow(if (c.isDark && !soft) IrisElevation.none else IrisElevation.card, IrisRadius.largeShape, c.isDark)
            .clip(IrisRadius.largeShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(IrisDimens.space16),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = IrisType.caption, color = fg2)
            Spacer(Modifier.height(IrisDimens.space4))
            Text(title, style = IrisType.title.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(IrisDimens.space4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Box(Modifier.size(IrisDimens.space8).background(dot, CircleShape))
                    Spacer(Modifier.width(IrisDimens.space8))
                }
                Text(status, style = IrisType.secondary, color = fg2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(IrisDimens.space12))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.clip(IrisRadius.pillShape)
                        .background(if (soft) Color.White.copy(alpha = 0.22f) else c.accentContainer)
                        .clickable(onClick = onClick)
                        .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8),
                ) {
                    Text(action, style = IrisType.label, color = if (soft) Color.White else c.onAccentContainer)
                }
            }
        }
    }
}

/** 2.5.0: page-level tabs (a segmented control with the page's side insets), e.g. 存储 › 概览 / 清理 / 位置. */
@Composable
fun PageTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SegmentedControl(
        options, selected, onSelect,
        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space8),
    )
}

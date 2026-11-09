package com.iris.localai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
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
import com.iris.localai.ui.theme.softShadow

/** 2.5.0: what the settings 重点卡 shows for the engine (pure, unit-tested). */
enum class HeroStatus { NONE, LOADING, READY, FAILED }

/**
 * 2.5.0 设置首页顶部的重点卡: the current model and its state, with one action (切换模型).
 * Beta2: the same solid neutral card in both styles — no colour, no gradient; hierarchy comes from
 * type weight and spacing only. 柔光 shows the action as an accent text link and the state as text
 * (no status dot); 经典 keeps the dot and the tinted pill button.
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
    val dot = if (soft) null else when (state) {
        HeroStatus.READY -> c.success
        HeroStatus.LOADING -> c.warning
        HeroStatus.FAILED -> c.danger
        HeroStatus.NONE -> null
    }
    Box(
        modifier.padding(horizontal = IrisDimens.groupGutter).fillMaxWidth()
            .softShadow(if (c.isDark || soft) IrisElevation.none else IrisElevation.card, IrisRadius.largeShape, c.isDark)
            .clip(IrisRadius.largeShape)
            .background(c.surfaceElevated)
            .then(if (soft) Modifier.border(MatteGlass.HAIRLINE_DP.dp, MatteGlass.hairline(c.isDark), IrisRadius.largeShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(IrisDimens.space16),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = IrisType.caption, color = c.textTertiary)
            Spacer(Modifier.height(IrisDimens.space4))
            Text(title, style = IrisType.title.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(IrisDimens.space4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Box(Modifier.size(IrisDimens.space8).background(dot, CircleShape))
                    Spacer(Modifier.width(IrisDimens.space8))
                }
                Text(
                    status,
                    style = IrisType.secondary,
                    color = if (soft && state == HeroStatus.FAILED) c.danger else c.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(IrisDimens.space12))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (soft) {
                    Text(
                        action, style = IrisType.label, color = c.accent,
                        modifier = Modifier.clip(IrisRadius.pillShape).clickable(onClick = onClick)
                            .padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space4),
                    )
                } else {
                    Box(
                        Modifier.clip(IrisRadius.pillShape)
                            .background(c.accentContainer)
                            .clickable(onClick = onClick)
                            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space8),
                    ) {
                        Text(action, style = IrisType.label, color = c.onAccentContainer)
                    }
                }
            }
        }
    }
}

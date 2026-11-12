package com.iris.localai.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.R
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/**
 * 2.5.2 新手引导: pure page logic (unit-tested). Shown once after the welcome (AppSettings.onboardingDone,
 * stored as `onboarding_done`), replayable from 设置 › 关于与调试 › 新手引导.
 */
object Onboarding {
    const val PAGES = 5

    /** The guide is due: welcome accepted (disclaimer first), guide not finished or skipped yet. */
    fun shouldShow(welcomeAccepted: Boolean, onboardingDone: Boolean): Boolean = welcomeAccepted && !onboardingDone

    /** Next page index, or null when [page] is the last one (= finish). */
    fun next(page: Int): Int? = if (page >= PAGES - 1) null else (page + 1).coerceAtLeast(0)

    /** Previous page index, or null on the first page (back there closes the guide). */
    fun prev(page: Int): Int? = if (page <= 0) null else (page - 1).coerceAtMost(PAGES - 1)

    fun isLast(page: Int): Boolean = page >= PAGES - 1
}

/** 2.5.2: texts of the guide (title, body, one grey hint line per page). */
object OnboardingText {
    data class Page(val title: Int, val body: Int, val hint: Int)

    val PAGES = listOf(
        Page(R.string.ob252_model_title, R.string.ob252_model_body, R.string.ob252_model_hint),
        Page(R.string.ob252_agent_title, R.string.ob252_agent_body, R.string.ob252_agent_hint),
        Page(R.string.ob252_kb_title, R.string.ob252_kb_body, R.string.ob252_kb_hint),
        Page(R.string.ob252_image_title, R.string.ob252_image_body, R.string.ob252_image_hint),
        Page(R.string.ob252_settings_title, R.string.ob252_settings_body, R.string.ob252_settings_hint),
    )

    fun s(id: Int): String = com.iris.localai.I18n.s(id)
}

/**
 * 2.5.2 新手引导: a light full-screen guide over the app. Matte solid neutral card, one accent
 * colour (primary button + current page dot), monochrome icons, no gradient or blur; grey text is
 * at most one line per page. Back steps to the previous page; on the first page it closes.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = Iris.colors
    var page by rememberSaveable { mutableIntStateOf(0) }
    val icons: List<ImageVector> = listOf(IrisIcons.Cube, IrisIcons.Agent, IrisIcons.Folder, IrisIcons.Image, IrisIcons.Settings)
    BackHandler { Onboarding.prev(page)?.let { page = it } ?: onDone() }

    Box(
        Modifier.fillMaxSize().background(c.background).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(OnboardingText.s(R.string.ob252_title), style = IrisType.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                if (!Onboarding.isLast(page)) IrisButton(OnboardingText.s(R.string.ob252_skip), onClick = onDone, style = IrisButtonStyle.Text, compact = true)
            }
            Spacer(Modifier.height(IrisDimens.space16))
            // Matte solid neutral card (no glass, no gradient, no blur).
            Crossfade(targetState = page, label = "onboarding") { p ->
                val t = OnboardingText.PAGES[p]
                Column(
                    Modifier.fillMaxWidth()
                        .background(c.surface, IrisRadius.largeShape)
                        .padding(IrisDimens.space24)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        Modifier.size(48.dp).background(c.surfaceVariant, IrisRadius.mediumShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icons[p], null, tint = c.text, modifier = Modifier.size(IrisDimens.iconLarge)) }
                    Spacer(Modifier.height(IrisDimens.space16))
                    Text(OnboardingText.s(t.title), style = IrisType.title, color = c.text)
                    Spacer(Modifier.height(IrisDimens.space8))
                    Text(OnboardingText.s(t.body), style = IrisType.body, color = c.text)
                    Spacer(Modifier.height(IrisDimens.space12))
                    Text(
                        OnboardingText.s(t.hint), style = IrisType.secondary, color = c.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
            Row(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space6)) {
                repeat(Onboarding.PAGES) { i ->
                    Box(Modifier.size(if (i == page) 8.dp else 6.dp).background(if (i == page) c.accent else c.outline, CircleShape))
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
                if (page > 0) IrisButton(
                    OnboardingText.s(R.string.ob252_prev), onClick = { Onboarding.prev(page)?.let { page = it } },
                    style = IrisButtonStyle.Tonal, modifier = Modifier.weight(1f),
                )
                IrisButton(
                    OnboardingText.s(if (Onboarding.isLast(page)) R.string.ob252_done else R.string.ob252_next),
                    onClick = { Onboarding.next(page)?.let { page = it } ?: onDone() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

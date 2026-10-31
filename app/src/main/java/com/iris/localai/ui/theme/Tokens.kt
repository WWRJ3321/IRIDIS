package com.iris.localai.ui.theme

import android.animation.ValueAnimator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design tokens. Every screen pulls spacing, radii, type and motion from here so the app
 * reads as one product. Change a value here, not at the call site.
 */

/** 4-dp spacing scale plus a few semantic sizes. */
object IrisDimens {
    val space2 = 2.dp
    val space4 = 4.dp
    val space6 = 6.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space20 = 20.dp
    val space24 = 24.dp
    val space32 = 32.dp

    /** Horizontal inset of page content (titles, descriptions, groups' outer edge). */
    val pageGutter = 20.dp
    /** Horizontal inset of grouped cards from the screen edge. */
    val groupGutter = 16.dp
    /** Inner horizontal padding of rows inside a group (1.8.0: 16 → 20, HyperOS). */
    val rowPadding = 20.dp
    /** Vertical padding of a row (1.8.0: 12 → 15, generous HyperOS rows). */
    val rowVerticalPadding = 13.dp
    val rowMinHeight = 56.dp
    /** Minimum accessible touch target. */
    val touchTarget = 48.dp

    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val iconLarge = 22.dp

    val topBarHeight = 56.dp
    /** 2.2.4: floating circular top-bar buttons and the chat model pill. */
    val floatingButton = 44.dp
    val floatingButtonSmall = 40.dp
    /** 2.2.5: home top bar — round buttons and the model pill share one 48 dp height. */
    val topBarControl = 48.dp
    /** Height reserved for the large page title under the top bar (HyperOS / iOS large title). */
    val largeTitleHeight = 64.dp
    val drawerWidth = 304.dp
    val messageMaxWidth = 840.dp
    val hairline = 0.5.dp
    /** 1.10.10: the row above the composer holding the activity indicator and the context meter. */
    val activityRowHeight = 28.dp
}

/**
 * Corner radii. 1.8.0 (HyperOS 75% / iOS 25%): larger radii and continuous (smoothed) corners
 * via [SmoothRoundedShape]; the pill stays a true semicircle.
 */
object IrisRadius {
    /** Corner smoothing of every continuous shape (0 = plain circle, iOS ≈ 0.6). */
    const val SMOOTHING = 0.6f

    val small = 12.dp
    val medium = 16.dp
    /** 2.2.4: cards, grouped settings and floating menus (Kimi-like 20 dp; was 24). */
    val large = 20.dp
    val bubble = 22.dp
    val composer = 28.dp
    val sheet = 30.dp
    val dialog = 30.dp

    val smallShape = smoothShape(small)
    val mediumShape = smoothShape(medium)
    val largeShape = smoothShape(large)
    val bubbleShape = smoothShape(bubble)
    val composerShape = smoothShape(composer)
    val dialogShape = smoothShape(dialog)
    val pillShape = RoundedCornerShape(50)
    /** 2.2.4: alias of the radius scale 12 / 16 / 20 / full. */
    val full = pillShape
    /** 1.10.10: About-page app icon (iOS icon ratio ≈ 22% of 72 dp). */
    val appIconShape = smoothShape(16.dp)
}

/**
 * 2.2.4: elevation scale for the soft, low-contrast shadows of floating surfaces (top-bar buttons,
 * cards, menus). Use [com.iris.localai.ui.theme.softShadow] rather than raw `shadow(...)`.
 */
object IrisElevation {
    val none = 0.dp
    /** Cards resting on the page. */
    val card = 3.dp
    /** Floating top-bar buttons and pills. */
    val floating = 6.dp
    /** Menus and popovers. */
    val menu = 12.dp
}

/** 2.2.4: fixed category colours for charts (storage breakdown etc.), the same in light and dark. */
object IrisChartColors {
    val violet = androidx.compose.ui.graphics.Color(0xFF8E7CF0)
    val teal = androidx.compose.ui.graphics.Color(0xFF30B0C7)
    val green = androidx.compose.ui.graphics.Color(0xFF5AC17A)
    val coral = androidx.compose.ui.graphics.Color(0xFFEF7C74)
    val grey = androidx.compose.ui.graphics.Color(0xFF9AA3AE)
    val orange = androidx.compose.ui.graphics.Color(0xFFFF8A3D)
}

/**
 * Type scale. Colour is applied at the call site from [Iris.colors].
 * 1.8.0: HyperOS-sized rows (17 sp title / 14 sp summary), a bold large page title, and
 * SF-like weights (semibold titles, medium labels, slightly tightened large sizes).
 */
object IrisType {
    /** Large left-aligned page title that collapses into the top bar. */
    val largeTitle = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    /** Empty-state headline, About title. */
    val display = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    /** Page and sheet titles (the collapsed, centred top-bar title). */
    val title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    /** Chat top bar model name. */
    val titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    /** Chat message text. */
    val message = TextStyle(fontSize = 16.sp, lineHeight = 25.sp)
    /** Primary text of a list row (HyperOS ≈ 17 sp). */
    val row = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    /** Summary line under a row title (HyperOS ≈ 14 sp, grey). */
    val rowSummary = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    /** Emphasised list text (model names, drawer). */
    val rowStrong = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    /** Descriptions, secondary explanations, footnotes under groups. */
    val secondary = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    /** Small grey header above a group. */
    val sectionHeader = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
    /** Segmented control labels. */
    val segment = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    /** Subtitles under row titles, meta lines. */
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    /** 2.2.4: chat model pill — bold model name and the small engine/status line under it. */
    val pillTitle = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val pillCaption = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
    /** 2.2.4: compact menu rows (model menu). */
    val menuItem = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val menuItemSelected = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    /** 1.10.10: voice-mode spoken reply. */
    val voiceReply = TextStyle(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    /** 1.10.10: Markdown inside replies — same body metrics as [message], so streaming plain text
     *  and the finished Markdown never change line height. */
    val mdH1 = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val mdH2 = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val mdH3 = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val mdTable = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val mono = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
    val monoSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
}

/** True when the user turned animations off (developer options / accessibility "remove animations"). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Reads the system animator switch once per theme composition. */
fun systemAnimationsDisabled(): Boolean = !ValueAnimator.areAnimatorsEnabled()

@Composable
fun reduceMotion(): Boolean = LocalReduceMotion.current

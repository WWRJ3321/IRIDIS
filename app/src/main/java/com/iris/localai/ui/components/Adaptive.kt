package com.iris.localai.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * large-screen / landscape adaptation. Phones in portrait are unchanged; on a tablet, an
 * unfolded foldable or a phone in landscape, chat and settings keep a readable column centred on
 * the screen instead of stretching lines across 2000 px, and stay clear of a side camera cutout.
 */
object Adaptive {
    /** Chat column (messages + composer). */
    val chatMaxWidth: Dp = 760.dp
    /** Settings and other list pages. */
    val pageMaxWidth: Dp = 680.dp

    /** Side padding that centres a [max]-wide column on a [screen]-wide window. Pure (tested). */
    fun sidePadding(screen: Dp, max: Dp): Dp = ((screen - max) / 2).coerceAtLeast(0.dp)
}

/** Side padding for a LazyColumn's contentPadding, so its rows sit in the centred column. */
@Composable
fun readableSidePadding(max: Dp = Adaptive.chatMaxWidth): Dp =
    Adaptive.sidePadding(LocalConfiguration.current.screenWidthDp.dp, max)

/** Centres the content in a column at most [max] wide (full width on a phone). */
fun Modifier.readableWidth(max: Dp = Adaptive.pageMaxWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/** Keeps content clear of a camera cutout on the left/right edge (landscape). */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier =
    windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

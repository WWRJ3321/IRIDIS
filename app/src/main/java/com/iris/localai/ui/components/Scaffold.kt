package com.iris.localai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.iris.localai.ui.theme.LocalReduceMotion
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType

/**
 * 1.8.0 large title (HyperOS, with the iOS collapse): a subpage shows its title big, bold and
 * left-aligned at the top of the scrolling body; as it scrolls under the top bar the small
 * centred title fades in. [collapse] is 0 (large title fully visible) … 1 (collapsed).
 */
@Stable
class LargeTitleState(val title: String) {
    var collapse by mutableFloatStateOf(0f)
        internal set
    /** False when the body never placed a large title (the bar then always shows the small title). */
    var hasLargeTitle by mutableStateOf(false)
        internal set
}

private val LocalLargeTitle = compositionLocalOf<LargeTitleState?> { null }

/** Pure mapping used by the scaffold (unit-tested): how far the large title has scrolled under the bar. */
object LargeTitleMath {
    /** Scroll px → 0..1, collapsing over the height of the large title block. */
    fun collapse(scrollPx: Float, titleBlockPx: Float): Float =
        if (titleBlockPx <= 0f) 1f else (scrollPx / titleBlockPx).coerceIn(0f, 1f)

    /** Alpha of the small centred bar title: appears only in the last 40 % of the collapse. */
    fun barTitleAlpha(collapse: Float): Float = if (collapse >= 1f) 1f else ((collapse - 0.6f) * 2.5f).coerceIn(0f, 1f)

}

/** 2.2.4: height of the floating top bar a body scrolls under (status bar included); 0 = padded. */
private val LocalTopBarInset = compositionLocalOf { 0.dp }

/**
 * Shared chrome for every secondary page: floating glass back button, collapsing large title,
 * actions, body. With [scrollUnder] (pages whose body starts with [LargeTitle] / [ScrollBody]) the
 * body runs under the bar and is blurred by its glass; otherwise it starts below the bar.
 */
@Composable
fun SubpageScaffold(
    title: String,
    onBack: () -> Unit,
    scrollUnder: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val state = remember(title) { LargeTitleState(title) }
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    // Scroll-driven values are read in draw (graphicsLayer) only: no recomposition per frame.
    fun collapse() = if (state.hasLargeTitle) state.collapse else 1f
    val inset = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        IrisDimens.topBarHeight + IrisDimens.space8
    CompositionLocalProvider(LocalGlassHaze provides haze) {
        Box(Modifier.fillMaxSize().background(c.background).horizontalCutoutPadding()) {
            CompositionLocalProvider(LocalLargeTitle provides state, LocalTopBarInset provides if (scrollUnder) inset else 0.dp) {
                Box(
                    Modifier.fillMaxSize()
                        .glassBackdrop(haze)
                        .background(c.background)
                        .padding(top = if (scrollUnder) 0.dp else inset),
                ) { content() }
            }
            IrisTopBar(
                title, onBack, actions = actions,
                titleModifier = Modifier.graphicsLayer {
                    val barAlpha = LargeTitleMath.barTitleAlpha(collapse())
                    alpha = barAlpha
                    translationY = if (reduce) 0f else (1f - barAlpha) * IrisDimens.space6.toPx()
                },
            )
        }
    }
}

/**
 * The big left-aligned title at the top of a subpage body. [ScrollBody] places it itself; a
 * LazyColumn page puts it in its first item and passes its state to [TrackTopBarScroll].
 */
@Composable
fun LargeTitle(modifier: Modifier = Modifier) {
    val state = LocalLargeTitle.current ?: return
    val c = Iris.colors
    LaunchedEffect(state) { state.hasLargeTitle = true }
    val inset = LocalTopBarInset.current
    Box(
        modifier.fillMaxWidth().padding(top = inset).height(IrisDimens.largeTitleHeight)
            .padding(start = IrisDimens.groupGutter + 14.dp, end = IrisDimens.pageGutter),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            state.title, style = IrisType.largeTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            // Fades as it slides under the bar, so the two titles never read as duplicates.
            modifier = Modifier.graphicsLayer { alpha = 1f - (state.collapse * 1.4f).coerceIn(0f, 1f) },
        )
    }
}

/** Reports a LazyColumn's scroll position to the enclosing [SubpageScaffold] (large-title collapse). */
@Composable
fun TrackTopBarScroll(state: LazyListState) {
    val target = LocalLargeTitle.current ?: return
    val block = with(LocalDensity.current) { IrisDimens.largeTitleHeight.toPx() }
    LaunchedEffect(state, block) {
        snapshotFlow {
            if (state.firstVisibleItemIndex > 0) 1f
            else LargeTitleMath.collapse(state.firstVisibleItemScrollOffset.toFloat(), block)
        }.collect { target.collapse = it }
    }
}

@Composable
private fun TrackTopBarScroll(state: ScrollState) {
    val target = LocalLargeTitle.current ?: return
    val block = with(LocalDensity.current) { IrisDimens.largeTitleHeight.toPx() }
    LaunchedEffect(state, block) {
        snapshotFlow { LargeTitleMath.collapse(state.value.toFloat(), block) }.collect { target.collapse = it }
    }
}

/** Scrolling column body of a subpage (large title first), keyboard- and nav-bar-aware. */
@Composable
fun ScrollBody(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    TrackTopBarScroll(scroll)
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scroll)
            .navigationBarsPadding()
            .padding(bottom = IrisDimens.space32)
            .readableWidth(),
    ) {
        LargeTitle()
        content()
        Spacer(Modifier.height(IrisDimens.space8))
    }
}

/** Centred grey version footer at the end of a settings page (HyperOS «关于本机» style). */
@Composable
fun VersionFooter(line1: String, line2: String? = null, modifier: Modifier = Modifier) {
    val c = Iris.colors
    Column(
        modifier.fillMaxWidth().padding(top = IrisDimens.space32, bottom = IrisDimens.space8, start = IrisDimens.pageGutter, end = IrisDimens.pageGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(line1, style = IrisType.secondary, color = c.textTertiary, textAlign = TextAlign.Center)
        if (line2 != null) {
            Spacer(Modifier.height(IrisDimens.space2))
            Text(line2, style = IrisType.secondary, color = c.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

/** Monospace, selectable code surface (API examples, paths). */
@Composable
fun CodeSurface(text: String, modifier: Modifier = Modifier) {
    val c = Iris.colors
    SelectionContainer(
        modifier
            .fillMaxWidth()
            .clip(IrisRadius.mediumShape)
            .background(c.codeBackground),
    ) {
        Text(
            text, style = IrisType.monoSmall, color = c.text, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

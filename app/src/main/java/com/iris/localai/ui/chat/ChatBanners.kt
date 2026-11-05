package com.iris.localai.ui.chat

import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.snap
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.iris.localai.R
import com.iris.localai.data.Role
import com.iris.localai.data.LocalModel
import com.iris.localai.ui.components.floatingSurface
import com.iris.localai.ui.components.glassSurface
import com.iris.localai.ui.components.glassBackdrop
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.EngineState
import com.iris.localai.server.ApiService
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.glass.GlassStyle
import com.iris.localai.ui.glass.glass
import com.iris.localai.ui.glass.glassSource
import com.iris.localai.ui.glass.rememberGlassBackdrop
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.motion.motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 2.4.1: split out of ChatScreen.kt unchanged — banners and the activity indicator over the chat.

/**
 * 1.9.0: a GPU load past [com.iris.localai.engine.SlowLoadHint.NOTICE_SECS] explains the wait and
 * offers 「改用 CPU」 (applied by reloading as soon as the running load returns).
 */
@Composable
internal fun SlowLoadBanner(engine: EngineState, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onUseCpu: () -> Unit) {
    val c = Iris.colors
    val loading = engine as? EngineState.Loading
    val secs = loadingSeconds(engine)
    val text = loading?.takeIf { it.applyingTier == null }?.let { com.iris.localai.engine.SlowLoadHint.notice(it.backend, secs) }
    var dismissedFor by remember { mutableStateOf<Long?>(null) }
    val visible = text != null && dismissedFor != loading?.since
    AnimatedVisibility(visible = visible, enter = fadeIn(IrisMotion.enter()), exit = fadeOut(IrisMotion.standard())) {
        // 2.0.x: one compact card — text, 「改用 CPU」, and × (was two text buttons under the text).
        BannerCard(backdrop, Modifier.padding(bottom = IrisDimens.space8).fillMaxWidth()) {
            Text(text.orEmpty(), style = IrisType.secondary, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            com.iris.localai.ui.components.IrisButton(com.iris.localai.engine.SlowLoadHint.ACTION, { dismissedFor = loading?.since; onUseCpu() }, style = com.iris.localai.ui.components.IrisButtonStyle.Text, compact = true)
            com.iris.localai.ui.components.PressableIcon(Icons.Outlined.Close, com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91), size = IrisDimens.space32, iconSize = IrisDimens.iconSmall, tint = c.textTertiary) { dismissedFor = loading?.since }
        }
    }
}

/**
 * 2.0.x: the one look of the cards that float above the composer (slow-load hint, notices):
 * frosted, continuous corners, hairline border, info icon, the same padding.
 */
@Composable
internal fun BannerCard(
    backdrop: com.iris.localai.ui.glass.GlassBackdrop,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = Iris.colors
    val shape = IrisRadius.bubbleShape
    Row(
        modifier
            .glass(backdrop, shape, GlassStyle(tint = c.glassTintOpaque))
            .border(IrisDimens.hairline, c.outline.copy(alpha = 0.5f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, onClickLabel = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c335e9)) else Modifier)
            .padding(start = IrisDimens.space12, end = IrisDimens.space12, top = IrisDimens.space8, bottom = IrisDimens.space8)
            .heightIn(min = IrisDimens.space32),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, tint = c.accent, modifier = Modifier.size(IrisDimens.iconSmall + 2.dp))
        Spacer(Modifier.size(IrisDimens.space8))
        content()
    }
}

/**
 * 1.10.9 / 1.10.10: one small line right above the composer saying what the model is doing
 * (加载 / 预填充 / 思考 / 编写工具调用 / 运行工具 / 生成回答) and for how long. The phase crossfades
 * in place (no slide, no size jump), the seconds roll like an odometer in tabular figures, and a
 * phase must hold for [ActivityText.DWELL_MS] before it replaces the shown one, so quick flips
 * never flicker. While a model loads it carries 「取消」.
 */
@Composable
internal fun ActivityIndicator(engine: EngineState, activity: com.iris.localai.util.Activity?, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onCancelLoad: () -> Unit, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val loading = engine as? EngineState.Loading
    val target: String? = when {
        loading != null -> if (loading.applyingTier != null) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_213e62) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2040f7, loading.model.displayName)
        else -> activity?.label
    }
    val since = loading?.since ?: activity?.since ?: 0L
    // Debounced phase: the first one shows at once, later ones only once they held a moment.
    var shown by remember { mutableStateOf(target) }
    var shownSince by remember { mutableLongStateOf(since) }
    LaunchedEffect(target, since) {
        if (target != null && shown != null && target != shown) delay(com.iris.localai.util.Activity.DWELL_MS)
        shown = target; shownSince = since
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shown != null) { while (shown != null) { now = System.currentTimeMillis(); delay(250) } }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    val tnum = IrisType.caption.copy(fontFeatureSettings = "tnum")
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        AnimatedVisibility(visible = shown != null, enter = fadeIn(IrisMotion.enter()), exit = fadeOut(IrisMotion.standard())) {
            Row(
                Modifier
                    .glass(backdrop, IrisRadius.pillShape, GlassStyle(tint = Iris.colors.glassTintOpaque))
                    .padding(start = 10.dp, end = if (loading != null && loading.applyingTier == null) 2.dp else 10.dp)
                    .heightIn(min = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).pulse(true, min = 0.35f, periodMs = 1200).clip(CircleShape).background(c.accent))
                Spacer(Modifier.size(8.dp))
                androidx.compose.animation.AnimatedContent(
                    targetState = last.orEmpty(),
                    transitionSpec = { fadeIn(IrisMotion.enter()) togetherWith fadeOut(IrisMotion.standard()) using androidx.compose.animation.SizeTransform(clip = false) },
                    label = "activityLabel",
                ) { l -> Text(l, style = tnum, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val pct = loading?.progress?.takeIf { it > 0.01f && it < 1f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                val secs = ((now - shownSince) / 1000).coerceAtLeast(0)
                val tail = pct + if (secs >= 2) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8f0d77, secs) else ""
                if (tail.isNotEmpty()) com.iris.localai.ui.motion.RollingText(tail, style = tnum, color = c.textTertiary)
                if (loading != null && loading.applyingTier == null) {
                    Spacer(Modifier.size(4.dp))
                    // Fits the 22 dp capsule (a 36 dp IrisButton overflowed the fixed-height row).
                    Text(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2), style = IrisType.caption, color = c.accent,
                        modifier = Modifier.clip(IrisRadius.pillShape).clickable(onClickLabel = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6e60d2), onClick = onCancelLoad)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Dismissible notice above the composer; keeps showing the last text while it animates out. */
/** 2.3.4: one-time 「已切回普通对话，是否换回原模型 X？」 with a 换回 action; leaves by itself after a few seconds. */
@Composable
internal fun ModelRestoreBanner(model: com.iris.localai.data.LocalModel?, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onBack: () -> Unit, onDismiss: () -> Unit) {
    val c = Iris.colors
    LaunchedEffect(model) { if (model != null) { kotlinx.coroutines.delay(8_000L); onDismiss() } }
    AnimatedVisibility(visible = model != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space8), contentAlignment = Alignment.Center) {
            BannerCard(backdrop, Modifier.widthIn(max = 520.dp), onClick = onDismiss) {
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.mr234_hint, model?.displayName.orEmpty()), style = IrisType.secondary, color = c.text,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                androidx.compose.material3.TextButton(onClick = onBack) { Text(com.iris.localai.I18n.s(com.iris.localai.R.string.mr234_back), color = c.accent) }
            }
        }
    }
}

@Composable
internal fun NoticeBanner(notice: String?, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onDismiss: () -> Unit) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    var last by remember { mutableStateOf(notice) }
    if (notice != null) last = notice
    // 1.12.0: iOS-style toast — centred, wraps its text, opaque frosted card with continuous
    // corners and a soft shadow; springs up from below with a slight scale, and leaves by itself
    // after a few seconds (longer for longer text). Tap to close earlier.
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay((3_500L + notice.length * 60L).coerceAtMost(9_000L))
            onDismiss()
        }
    }
    Box(Modifier.fillMaxWidth().padding(bottom = IrisDimens.space8), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = notice != null,
            enter = if (reduce) fadeIn(snap()) else
                fadeIn(IrisMotion.FadeIn.float()) +
                    scaleIn(IrisMotion.Bouncy.float(), initialScale = 0.88f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)) +
                    slideInVertically(IrisMotion.Bouncy.offset()) { it / 2 },
            exit = if (reduce) fadeOut(snap()) else
                fadeOut(IrisMotion.FadeOut.float()) +
                    scaleOut(IrisMotion.FadeOut.float(), targetScale = 0.94f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
        ) {
            BannerCard(backdrop, Modifier.widthIn(max = 520.dp), onClick = onDismiss) {
                Text(last.orEmpty(), style = IrisType.secondary, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

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

// 2.4.1: split out of ChatScreen.kt unchanged — model / character pills and their MorphPanel menus.

internal fun engineTag(m: LocalModel): String = when (m.format) {
    ModelFormat.GGUF -> "GGUF"
    ModelFormat.MNN -> "MNN"
    ModelFormat.QAIRT -> "GenieX"
    ModelFormat.LITERT -> "LiteRT"
}

@Composable
internal fun ModelTitle(engine: EngineState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    com.iris.localai.ui.components.IrisPill(
        modifier,
        height = IrisDimens.topBarControl,
        onClick = onClick, onClickLabel = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f2d373),
    ) { ModelPillContent(engine, chevronTurn = 0f) }
}

/** 2.2.7: the pill's row content, shared by the pill and the header of the expanded panel. */
@Composable
internal fun RowScope.ModelPillContent(engine: EngineState, chevronTurn: Float, hPad: androidx.compose.ui.unit.Dp = IrisDimens.space12) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    // 2.2.13: the pill only says 「模型」; state is a small dot (ready = accent, loading = pulsing,
    // failed = danger, idle = none). The model name and backend live in the panel it opens.
    val dot = when (engine) {
        is EngineState.Ready -> c.accent
        is EngineState.Loading -> c.textTertiary
        is EngineState.Failed -> c.danger
        else -> null
    }
    Spacer(Modifier.width(hPad))
    Row(Modifier.weight(1f, fill = chevronTurn > 0f), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(
                Modifier.size(7.dp).pulse(engine is EngineState.Loading, min = 0.4f)
                    .background(dot, androidx.compose.foundation.shape.CircleShape),
            )
            Spacer(Modifier.width(IrisDimens.space8))
        }
        // 2.2.14: loaded (or loading) → the model's short name, ellipsized; none → 「模型」.
        val name = when (engine) {
            is EngineState.Ready -> engine.model
            is EngineState.Loading -> engine.model
            else -> null
        }?.let { com.iris.localai.data.ModelNames.short(it.displayName) }
        Text(
            name ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8000f1), style = IrisType.pillTitle, color = c.text, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 168.dp),
        )
    }
    Icon(
        Icons.Outlined.KeyboardArrowDown, null, tint = c.textSecondary,
        modifier = Modifier
            .padding(start = IrisDimens.space4, end = hPad)
            .size(16.dp)
            .graphicsLayer { rotationZ = 180f * chevronTurn },
    )
}

/**
 * 2.2.7: container transform from the top-bar pill into the model panel. One glass surface,
 * anchored at the pill's top-left, grows from the pill's size to the panel's (width, height and
 * corner radius follow [progress] 0 → 1); the pill's own row stays as the header (chevron turns
 * 180°) and the panel content fades in below it. Collapsing plays the same path backwards.
 * Same window as the chat, so the Haze glass keeps blurring the messages behind it.
 */
@Composable
internal fun MorphPanel(
    progress: () -> Float,
    expanded: Boolean,
    pillInRoot: androidx.compose.ui.geometry.Rect,
    onDismiss: () -> Unit,
    closeLabel: String,
    header: @Composable RowScope.(p: Float, hPad: androidx.compose.ui.unit.Dp) -> Unit,
    // 2.3.5: one morph for both pills. hugWidthPx > 0 → width = content in [168, maxWidth]dp and the panel
    // keeps the pill's left edge (角色); otherwise fixed maxWidth, anchor-aware (模型).
    maxWidth: androidx.compose.ui.unit.Dp = 280.dp,
    hugWidthPx: Int = 0,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = Iris.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var areaH by remember { mutableStateOf(0) }
    var areaW by remember { mutableStateOf(0) }
    var contentH by remember { mutableStateOf(0) }
    val p = progress()
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            origin = it.positionInRoot(); areaH = it.size.height; areaW = it.size.width
        },
    ) {
        // Light dim + tap catcher (only takes touches while open).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() }
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (c.isDark) 0.24f else 0.12f))
                .then(
                    if (expanded) Modifier.pointerInput(Unit) {
                        detectTapGestures { onDismiss() }
                    } else Modifier,
                ),
        )
        with(density) {
            // 2.2.13: the panel grows from the pill's left edge (pill sits left of the top bar), kept on screen.
            val pillLeft = pillInRoot.left - origin.x
            val top = pillInRoot.top - origin.y
            val pillW = pillInRoot.width
            val pillH = pillInRoot.height
            // 2.3.4: clamped to the SCREEN (not the parent area) with 16dp side margins, anchor-aware: a pill
            // right of centre (角色) expands leftward from its right edge; width min(screen − 32dp, 280dp).
            val screenW = conf.screenWidthDp.dp.toPx()
            val mg = 16.dp.toPx()
            val g = if (hugWidthPx > 0) {
                val dw = com.iris.localai.ui.components.MorphGeometry.dropWidth(hugWidthPx.toFloat(), screenW, mg, 168.dp.toPx(), maxWidth.toPx())
                dw to com.iris.localai.ui.components.MorphGeometry.dropLeft(pillInRoot.left, dw, screenW, mg)
            } else com.iris.localai.ui.components.MorphGeometry.place(pillInRoot.left, pillW, screenW, mg, maxWidth.toPx())
            val panelW = g.first
            val panelLeft = g.second - origin.x
            // 2.3.5: fixed height = the whole content (no scrolling); only the space left on screen caps it.
            val avail = if (areaH > 0) areaH - top - 16.dp.toPx() else Float.MAX_VALUE
            val maxH = avail.coerceAtLeast(pillH)
            val headerH = pillH + (52.dp.toPx() - pillH) * p
            val targetH = if (contentH > 0) contentH.toFloat().coerceAtMost(maxH) else pillH
            val w = pillW + (panelW - pillW) * p
            val left = pillLeft + (panelLeft - pillLeft) * p
            val h = pillH + (targetH - pillH) * p
            val corner = (pillH / 2f) + (24.dp.toPx() - pillH / 2f) * p
            val hPad = 12.dp + 4.dp * p
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()) }
                    .size(w.toDp(), h.toDp())
                    .glassSurface(
                        androidx.compose.foundation.shape.RoundedCornerShape(corner.toDp()),
                        com.iris.localai.ui.theme.IrisElevation.menu,
                        strong = true,
                    )
                    // taps on the glass itself never reach the dismiss catcher
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .width(panelW.toDp())
                        .heightIn(max = maxH.toDp())
                        .onSizeChanged { contentH = it.height + (52.dp.toPx() - headerH).toInt() },
                    horizontalAlignment = Alignment.Start,
                ) {
                    Row(
                        Modifier
                            .width(w.toDp()).height(headerH.toDp())
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null, onClickLabel = closeLabel,
                            ) { onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) { header(p, hPad) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .graphicsLayer { alpha = ((progress() - 0.3f) / 0.7f).coerceIn(0f, 1f) }
                            // only scrolls if the list is taller than the whole space below the pill
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(bottom = 4.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}


@Composable
internal fun ModelMorphPanel(
    progress: () -> Float,
    expanded: Boolean,
    pillInRoot: androidx.compose.ui.geometry.Rect,
    engine: EngineState,
    models: List<LocalModel>,
    currentPath: String?,
    onDismiss: () -> Unit,
    onPick: (LocalModel) -> Unit,
    onManage: () -> Unit,
    unloadEnabled: Boolean = true,
    onUnload: () -> Unit = {},
) {
    val c = Iris.colors
    MorphPanel(progress, expanded, pillInRoot, onDismiss, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_def9e9),
        header = { p, hPad -> ModelPillContent(engine, chevronTurn = p, hPad = hPad) },
        maxWidth = 236.dp, // 2.3.5: 280 → 236dp (user: too wide); structure untouched
    ) {
                        MorphHairline()
                        // 2.3.5: restrained height — 40dp rows, at most 5 visible; more scroll inside this list only
                        Column(Modifier.fillMaxWidth().heightIn(max = (40 * 5).dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        models.forEach { m ->
                            val selected = m.path == currentPath
                            Row(
                                Modifier.fillMaxWidth().height(40.dp).clickable(enabled = expanded) { onPick(m) }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    m.displayName,
                                    style = (if (selected) IrisType.menuItemSelected else IrisType.menuItem).copy(fontSize = 15.sp, lineHeight = 20.sp),
                                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text(
                                    engineTag(m), style = IrisType.micro.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.textSecondary,
                                    maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.padding(start = 8.dp),
                                )
                                if (selected) Icon(Icons.Outlined.Check, null, tint = c.accent, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                            }
                        }
                        }
                        if (models.isNotEmpty()) MorphHairline()
                        // 2.2.16: one entry for the merged 模型与加载 page (models + 档位 + load settings)
                        CompactMenuRow(ModelMenuText.MODELS_AND_LOADING, Icons.Outlined.Tune) { if (expanded) onManage() }
                        if (engine is EngineState.Ready) {
                            CompactMenuRow(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_41cb75), Icons.Outlined.Eject, enabled = unloadEnabled) { if (expanded) onUnload() }
                        }
    }
}

/** 2.3.3: the 角色 pill — 「角色」, or the active card's avatar + short name (avatar only when [compact]). */
@Composable
internal fun CharacterTitle(entry: com.iris.localai.character.CharacterStore.Entry?, compact: Boolean, modifier: Modifier = Modifier, expanded: () -> Float = { 0f }, onClick: () -> Unit) {
    val e = expanded()
    // 2.3.5: no accent ring while open — the panel now morphs over the pill and the ring's round corner
    // peeked out past the panel's 24dp corner.
    com.iris.localai.ui.components.IrisPill(
        modifier,
        height = IrisDimens.topBarControl,
        onClick = onClick, onClickLabel = com.iris.localai.I18n.s(com.iris.localai.R.string.ch233_pick),
    ) { CharacterPillContent(entry, compact, chevronTurn = e, fillRow = false) }
}

@Composable
internal fun RowScope.CharacterPillContent(
    entry: com.iris.localai.character.CharacterStore.Entry?,
    compact: Boolean,
    chevronTurn: Float,
    hPad: androidx.compose.ui.unit.Dp = IrisDimens.space12,
    fillRow: Boolean = chevronTurn > 0f,
) {
    val c = Iris.colors
    if (entry != null && compact) {
        // avatar only: 7 dp around a 28 dp avatar keeps the 42 dp pill round
        Spacer(Modifier.width(7.dp))
        com.iris.localai.ui.character.CharacterAvatar(entry.avatar, entry.card.name, 28.dp)
        Spacer(Modifier.width(7.dp))
        return
    }
    Spacer(Modifier.width(if (entry != null) 7.dp else hPad))
    Row(Modifier.weight(1f, fill = fillRow), verticalAlignment = Alignment.CenterVertically) {
        if (entry != null) {
            com.iris.localai.ui.character.CharacterAvatar(entry.avatar, entry.card.name, 28.dp)
            Spacer(Modifier.width(IrisDimens.space8))
        }
        Text(
            entry?.card?.name?.ifBlank { null } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.ch232_nav),
            style = IrisType.pillTitle, color = c.text, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
    Icon(
        Icons.Outlined.KeyboardArrowDown, null, tint = c.textSecondary,
        modifier = Modifier
            .padding(start = IrisDimens.space4, end = hPad)
            .size(16.dp)
            .graphicsLayer { rotationZ = 180f * chevronTurn },
    )
}

/**
 * 2.3.3: 角色 dropdown — 「无角色」, the cards (avatar + name), 「管理角色…」.
 * 2.3.5: thin dropdown BELOW the top bar (top = pill bottom + 6dp), left edge on the pill's left edge
 * (shifted left only to keep 16dp from the screen edge), width = content in [168, 220]dp, no header row;
 * fade + slight scale from top. Pills stay uncovered.
 */
@Composable
internal fun CharacterMorphPanel(
    progress: () -> Float,
    expanded: Boolean,
    pillInRoot: androidx.compose.ui.geometry.Rect,
    current: com.iris.localai.character.CharacterStore.Entry?,
    @Suppress("UNUSED_PARAMETER") compact: Boolean,
    cards: List<com.iris.localai.character.CharacterStore.Entry>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onManage: () -> Unit,
) {
    // 2.3.5: same pill → panel morph as 模型 (header = the pill itself), but slim: width hugs the rows in
    // [168, 220]dp from the pill's left edge, full height, no scrolling.
    val c = Iris.colors
    var contentW by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        // measure the rows' natural width (invisible, takes no space)
        Column(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).height(0.dp).graphicsLayer { alpha = 0f }
                .onSizeChanged { contentW = it.width },
        ) {
            CharRow(false, false, com.iris.localai.I18n.s(com.iris.localai.R.string.ch233_none), {}, fill = false) { Box(Modifier.size(28.dp)) }
            cards.forEach { e -> CharRow(true, false, e.card.name.ifBlank { e.id }, {}, fill = false) { Box(Modifier.size(28.dp)) } }
            CompactMenuRow(com.iris.localai.I18n.s(com.iris.localai.R.string.ch233_manage), Icons.Outlined.ManageAccounts, fill = false) {}
        }
        MorphPanel(progress, expanded, pillInRoot, onDismiss, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_def9e9),
            header = { p, hPad -> CharacterPillContent(current, compact = false, chevronTurn = p, hPad = hPad) },
            maxWidth = 220.dp,
            hugWidthPx = contentW.coerceAtLeast(1),
        ) {
            MorphHairline()
            CharRow(null == current?.id, expanded, com.iris.localai.I18n.s(com.iris.localai.R.string.ch233_none), { onPick(null) }) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PersonOff, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            cards.forEach { e ->
                CharRow(e.id == current?.id, expanded, e.card.name.ifBlank { e.id }, { onPick(e.id) }) { com.iris.localai.ui.character.CharacterAvatar(e.avatar, e.card.name, 28.dp) }
            }
            MorphHairline()
            CompactMenuRow(com.iris.localai.I18n.s(com.iris.localai.R.string.ch233_manage), Icons.Outlined.ManageAccounts) { if (expanded) onManage() }
        }
    }
}

@Composable
internal fun CharRow(selected: Boolean, enabled: Boolean, name: String, onClick: () -> Unit, fill: Boolean = true, lead: @Composable () -> Unit) {
    val c = Iris.colors
    Row(
        (if (fill) Modifier.fillMaxWidth() else Modifier).height(44.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Text(
            name,
            style = (if (selected) IrisType.menuItemSelected else IrisType.menuItem).copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).then(if (fill) Modifier.weight(1f) else Modifier),
        )
        if (selected) Icon(Icons.Outlined.Check, null, tint = c.accent, modifier = Modifier.padding(start = 8.dp).size(16.dp))
    }
}

/** 2.2.8: section hairline on the panel grid — inset 16 dp both sides, 8 dp section gap. */
@Composable
internal fun MorphHairline() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(IrisDimens.hairline).background(Iris.colors.outline))
}

@Composable
internal fun CompactMenuRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, fill: Boolean = true, onClick: () -> Unit) {
    val c = Iris.colors
    val a = if (enabled) 1f else 0.38f
    Row(
        (if (fill) Modifier.fillMaxWidth() else Modifier).height(44.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.textSecondary.copy(alpha = c.textSecondary.alpha * a), modifier = Modifier.size(18.dp))
        Text(text, style = IrisType.menuItem.copy(fontSize = 15.sp, lineHeight = 20.sp), color = c.text.copy(alpha = c.text.alpha * a), maxLines = 1, modifier = Modifier.padding(start = 10.dp))
    }
}

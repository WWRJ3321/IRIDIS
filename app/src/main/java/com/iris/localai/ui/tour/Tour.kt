package com.iris.localai.ui.tour

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.iris.localai.R
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.TourMotion
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 2.5.3 新手引导 (spotlight guided tour / coach marks). Replaces the 2.5.2 five-page guide.
 *
 * Targets register their bounds with [tourTarget]; [TourOverlay] dims the screen with a solid scrim,
 * cuts a rounded hole around the current target (animated between targets), and shows a matte solid
 * tooltip card with 跳过 / 上一步 / 下一步 / 完成. Only the highlighted target can receive taps: the scrim
 * and the card consume every pointer event. A target that is not on screen is skipped.
 */
enum class TourId {
    MENU, MODEL, CHARACTER, NEW_CHAT, INPUT, AGENT, SKILLS, ATTACH, THINK, SPEED, VOICE,
    DRAWER_NAV, DOWNLOAD, IMPORT, MODEL_LIST, TEMPERATURE, PERF_TIER, VOICE_TTS, KB, AGENT_MODE, MCP, REPLAY,
}

/** Where a step's target lives; MainActivity maps each one onto its route stack. DRAWER = chat with the drawer open. */
enum class TourScreen { CHAT, DRAWER, MODELS, GENERATION, PERFORMANCE, VOICE, CONTEXT, AGENT, SETTINGS }

/**
 * One step. [passThrough] = a tap inside the hole reaches the real control (it is pressed) and the tour
 * advances; false = the tap is consumed and only advances (the control would open a panel, a picker or
 * another page and break the tour).
 */
data class TourStep(val id: TourId, val screen: TourScreen, val title: Int, val body: Int, val passThrough: Boolean = false)

object TourSteps {
    val ALL: List<TourStep> = listOf(
        // ---- home: every control of the chat screen ----
        TourStep(TourId.MENU, TourScreen.CHAT, R.string.tour253_menu_t, R.string.tour253_menu_b),
        TourStep(TourId.MODEL, TourScreen.CHAT, R.string.tour253_model_t, R.string.tour253_model_b),
        TourStep(TourId.CHARACTER, TourScreen.CHAT, R.string.tour253_character_t, R.string.tour253_character_b),
        TourStep(TourId.NEW_CHAT, TourScreen.CHAT, R.string.tour253_newchat_t, R.string.tour253_newchat_b),
        TourStep(TourId.INPUT, TourScreen.CHAT, R.string.tour253_input_t, R.string.tour253_input_b),
        TourStep(TourId.AGENT, TourScreen.CHAT, R.string.tour253_agent_t, R.string.tour253_agent_b, passThrough = true),
        TourStep(TourId.SKILLS, TourScreen.CHAT, R.string.tour253_skills_t, R.string.tour253_skills_b),
        TourStep(TourId.ATTACH, TourScreen.CHAT, R.string.tour253_attach_t, R.string.tour253_attach_b),
        TourStep(TourId.THINK, TourScreen.CHAT, R.string.tour253_think_t, R.string.tour253_think_b, passThrough = true),
        TourStep(TourId.SPEED, TourScreen.CHAT, R.string.tour253_speed_t, R.string.tour253_speed_b),
        TourStep(TourId.VOICE, TourScreen.CHAT, R.string.tour253_voice_t, R.string.tour253_voice_b),
        TourStep(TourId.DRAWER_NAV, TourScreen.DRAWER, R.string.tour253_drawer_t, R.string.tour253_drawer_b),
        // ---- models ----
        TourStep(TourId.DOWNLOAD, TourScreen.MODELS, R.string.tour253_download_t, R.string.tour253_download_b),
        TourStep(TourId.IMPORT, TourScreen.MODELS, R.string.tour253_import_t, R.string.tour253_import_b),
        TourStep(TourId.MODEL_LIST, TourScreen.MODELS, R.string.tour253_models_t, R.string.tour253_models_b),
        // ---- settings ----
        TourStep(TourId.TEMPERATURE, TourScreen.GENERATION, R.string.tour253_temp_t, R.string.tour253_temp_b),
        TourStep(TourId.PERF_TIER, TourScreen.PERFORMANCE, R.string.tour253_perf_t, R.string.tour253_perf_b),
        TourStep(TourId.VOICE_TTS, TourScreen.VOICE, R.string.tour253_tts_t, R.string.tour253_tts_b),
        TourStep(TourId.KB, TourScreen.CONTEXT, R.string.tour253_kb_t, R.string.tour253_kb_b),
        TourStep(TourId.AGENT_MODE, TourScreen.AGENT, R.string.tour253_agentset_t, R.string.tour253_agentset_b),
        TourStep(TourId.MCP, TourScreen.AGENT, R.string.tour253_mcp_t, R.string.tour253_mcp_b),
        TourStep(TourId.REPLAY, TourScreen.SETTINGS, R.string.tour253_replay_t, R.string.tour253_replay_b),
    )
}

/** Plain rectangle for the pure geometry helpers (unit-tested without Compose). */
data class TourRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** 2.5.3: pure tour logic (unit-tested). */
object TourLogic {
    /** Due once after the welcome (disclaimer first) until finished or skipped; replay sets the flag back. */
    fun shouldShow(welcomeAccepted: Boolean, tourDone: Boolean): Boolean = welcomeAccepted && !tourDone

    fun next(index: Int, count: Int): Int? = if (index >= count - 1) null else (index + 1).coerceAtLeast(0)
    fun prev(index: Int): Int? = if (index <= 0) null else index - 1
    fun isLast(index: Int, count: Int): Boolean = index >= count - 1
    fun counter(index: Int, count: Int): Pair<Int, Int> = (index + 1).coerceIn(1, count.coerceAtLeast(1)) to count

    /** A measured target is usable when it is a real box and at least 40 % of it lies inside the view. */
    fun usable(r: TourRect, viewW: Float, viewH: Float): Boolean {
        if (r.width < 4f || r.height < 4f || viewW <= 0f || viewH <= 0f) return false
        val iw = (minOf(r.right, viewW) - maxOf(r.left, 0f)).coerceAtLeast(0f)
        val ih = (minOf(r.bottom, viewH) - maxOf(r.top, 0f)).coerceAtLeast(0f)
        return iw * ih >= 0.4f * r.width * r.height
    }

    /** The hole: the target grown by [pad] on each side, clamped to the view. */
    fun hole(r: TourRect, pad: Float, viewW: Float, viewH: Float): TourRect = TourRect(
        (r.left - pad).coerceIn(0f, viewW), (r.top - pad).coerceIn(0f, viewH),
        (r.right + pad).coerceIn(0f, viewW), (r.bottom + pad).coerceIn(0f, viewH),
    )

    /**
     * Tooltip top. Holes in the upper half prefer below, lower half prefer above; when neither side has
     * room the card goes to the roomier side, clamped inside [viewTop, viewBottom − tipH].
     */
    fun tipY(hole: TourRect, tipH: Float, viewTop: Float, viewBottom: Float, gap: Float): Float {
        val below = hole.bottom + gap
        val above = hole.top - gap - tipH
        val fitsBelow = below + tipH <= viewBottom
        val fitsAbove = above >= viewTop
        val upper = (hole.top + hole.bottom) / 2f < (viewTop + viewBottom) / 2f
        val y = when {
            upper && fitsBelow -> below
            !upper && fitsAbove -> above
            fitsBelow -> below
            fitsAbove -> above
            (viewBottom - hole.bottom) >= (hole.top - viewTop) -> below
            else -> above
        }
        return y.coerceIn(viewTop, (viewBottom - tipH).coerceAtLeast(viewTop))
    }

    /** Tooltip left: centred on the hole, clamped to the side margins. */
    fun tipX(holeCenterX: Float, tipW: Float, viewW: Float, margin: Float): Float =
        (holeCenterX - tipW / 2f).coerceIn(margin, (viewW - margin - tipW).coerceAtLeast(margin))

    /** Whether two measurements are the same position (layout settled) within [tol] px. */
    fun same(a: TourRect, b: TourRect, tol: Float = 1f): Boolean =
        kotlin.math.abs(a.left - b.left) <= tol && kotlin.math.abs(a.top - b.top) <= tol &&
            kotlin.math.abs(a.right - b.right) <= tol && kotlin.math.abs(a.bottom - b.bottom) <= tol
}

/**
 * Tour state. Lives in AppNavigation; targets always report their bounds (a plain map, no snapshot
 * writes from layout), the overlay polls it while a step waits for its target to settle.
 */
class TourController(val steps: List<TourStep> = TourSteps.ALL) {
    var active by mutableStateOf(false)
    var index by mutableIntStateOf(0)
        private set
    /** +1 after 下一步, −1 after 上一步 / back: the direction a missing target is skipped in. */
    var direction = 1
        private set
    var onFinish: () -> Unit = {}

    val currentStep: TourStep? get() = steps.getOrNull(index)

    private val rects = HashMap<TourId, Pair<Any, Rect>>()

    fun register(id: TourId, token: Any, bounds: Rect) { rects[id] = token to bounds }
    fun unregister(id: TourId, token: Any) { if (rects[id]?.first === token) rects.remove(id) }
    fun rectOf(id: TourId): Rect? = rects[id]?.second

    fun start() { direction = 1; index = 0; active = true }
    fun next() { direction = 1; TourLogic.next(index, steps.size)?.let { index = it } ?: finish() }
    fun back() { direction = -1; TourLogic.prev(index)?.let { index = it } ?: finish() }
    fun finish() { if (active) { active = false; onFinish() } }

    /** The current target never showed up: move on in the same direction (never stuck, never a crash). */
    fun skipMissing() {
        val n = index + direction
        when {
            n in steps.indices -> index = n
            direction < 0 && index + 1 in steps.indices -> { direction = 1; index += 1 }
            else -> finish()
        }
    }

    /** A pass-through step: the user pressed the real control. */
    fun onTargetTapped(id: TourId) { if (active && currentStep?.id == id) next() }

    companion object {
        /** Let a new page's push transition start before scrolling the target into view. */
        const val SCROLL_DELAY_MS = 90L
    }
}

val LocalTour = staticCompositionLocalOf<TourController?> { null }

/**
 * Marks a tour target: reports its bounds, scrolls itself into view while it is the current step and,
 * on a pass-through step, notices the tap that reaches it (Initial pass, never consumed here).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tourTarget(id: TourId): Modifier {
    val tour = LocalTour.current ?: return this
    val token = remember { Any() }
    DisposableEffect(tour, id) { onDispose { tour.unregister(id, token) } }
    val requester = remember { BringIntoViewRequester() }
    // Plain holder: written from layout, read only by the scroll effect (no snapshot writes per layout pass).
    val size = remember { IntArray(2) }
    val step = if (tour.active) tour.currentStep else null
    val current = step?.id == id
    val margin = with(LocalDensity.current) { 120.dp.toPx() }
    LaunchedEffect(current) {
        if (!current) return@LaunchedEffect
        delay(TourController.SCROLL_DELAY_MS)
        // Room above for the floating top bar and below for the tooltip card.
        try { requester.bringIntoView(Rect(0f, -margin, size[0].toFloat(), size[1] + margin)) }
        catch (e: CancellationException) { throw e } catch (ignored: Throwable) { }
    }
    val observeTap = current && step?.passThrough == true
    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { size[0] = it.size.width; size[1] = it.size.height; tour.register(id, token, it.boundsInRoot()) }
        .then(
            if (observeTap) Modifier.pointerInput(id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    if (up != null) tour.onTargetTapped(id)
                }
            } else Modifier,
        )
}

/** Swallows every pointer event that reaches this node (scrim pieces and the whole tooltip card). */
fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
        }
    }
}

private fun Rect.toTour() = TourRect(left, top, right, bottom)
private fun TourRect.toRect() = Rect(left, top, right, bottom)

/** What is on screen for one step: its index, the hole and the exact target (pass-through area). */
private data class Shown(val index: Int, val hole: Rect, val target: Rect)

private const val SCRIM_ALPHA = 0.65f

/**
 * The tour overlay: solid dim scrim with a rounded cut-out, a subtle pulse ring in the accent colour,
 * and the tooltip card. Draw it above the whole navigation so root coordinates match the targets.
 */
@Composable
fun TourOverlay(tour: TourController, navigate: (TourScreen) -> Unit) {
    val c = Iris.colors
    val density = LocalDensity.current
    val reduce = LocalReduceMotion.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var shown by remember { mutableStateOf<Shown?>(null) }
    var hasHole by remember { mutableStateOf(false) }
    val holeAnim = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val appear = remember { Animatable(0f) }
    val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val pad = with(density) { 6.dp.toPx() }
    val corner = with(density) { 12.dp.toPx() }
    var lastScreen by remember { mutableStateOf<TourScreen?>(null) }

    LaunchedEffect(Unit) {
        focus.clearFocus(force = true)
        if (reduce) appear.snapTo(1f) else appear.animateTo(1f, TourMotion.appear())
    }

    fun localRect(id: TourId): TourRect? {
        val r = tour.rectOf(id) ?: return null
        val t = TourRect(r.left - origin.x, r.top - origin.y, r.right - origin.x, r.bottom - origin.y)
        return t.takeIf { TourLogic.usable(it, viewSize.width.toFloat(), viewSize.height.toFloat()) }
    }

    LaunchedEffect(tour.index) {
        shown = null
        val step = tour.currentStep ?: return@LaunchedEffect
        // Let the drawer close before the page underneath slides away.
        if (lastScreen == TourScreen.DRAWER && step.screen != TourScreen.DRAWER) delay(320)
        // Another page: close the hole while it slides in (it reopens on the new target, no glide across pages).
        if (lastScreen != null && lastScreen != step.screen) hasHole = false
        lastScreen = step.screen
        navigate(step.screen)
        // Wait until the target is measured, on screen and no longer moving (page transition, scroll).
        var last: TourRect? = null
        var stable = 0
        var found: TourRect? = null
        for (attempt in 0 until 32) { // ≈ 2.2 s, then the step is skipped
            delay(70)
            val r = localRect(step.id)
            if (r != null && last != null && TourLogic.same(r, last)) { stable++; if (stable >= 2) { found = r; break } } else stable = 0
            last = r
        }
        val target = found ?: run { tour.skipMissing(); return@LaunchedEffect }
        val w = viewSize.width.toFloat(); val h = viewSize.height.toFloat()
        var hole = TourLogic.hole(target, pad, w, h).toRect()
        if (!hasHole || reduce) holeAnim.snapTo(hole)
        else scope.launch { holeAnim.animateTo(hole, TourMotion.hole()) }
        hasHole = true
        shown = Shown(tour.index, hole, target.toRect())
        // Keep following the target (keyboard, banners, late layout) while the step is up.
        while (isActive) {
            delay(150)
            val r = localRect(step.id) ?: continue
            val nh = TourLogic.hole(r, pad, w, h)
            if (!TourLogic.same(nh, hole.toTour(), 2f)) {
                hole = nh.toRect()
                shown = Shown(tour.index, hole, r.toRect())
                if (reduce) holeAnim.snapTo(hole) else scope.launch { holeAnim.animateTo(hole, TourMotion.hole()) }
            }
        }
    }

    // System back = previous step; on the first step it closes the tour. Re-registered per step so it
    // always sits above any handler a newly shown page added.
    key(tour.index) { BackHandler(enabled = tour.active) { tour.back() } }

    val pulse = if (reduce) null else rememberInfiniteTransition(label = "tourPulse").animateFloat(
        0f, 1f, TourMotion.pulse(), label = "tourPulseT",
    )
    val accent = c.accent
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .onSizeChanged { viewSize = it },
    ) {
        // ---- scrim with the cut-out hole (offscreen layer + BlendMode.Clear), pulse ring on the edge ----
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * appear.value))
            if (hasHole) {
                val r = holeAnim.value
                drawRoundRect(Color.Black, Offset(r.left, r.top), Size(r.width, r.height), CornerRadius(corner), blendMode = BlendMode.Clear)
                val t = pulse?.value
                if (t != null && shown != null) {
                    val grow = 2.dp.toPx() + 6.dp.toPx() * t
                    drawRoundRect(
                        accent.copy(alpha = 0.6f * (1f - t)),
                        Offset(r.left - grow, r.top - grow), Size(r.width + 2 * grow, r.height + 2 * grow),
                        CornerRadius(corner + grow), style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }

        // ---- input: only the highlighted target may receive taps ----
        val s = shown
        val step = tour.currentStep
        if (s != null && step?.passThrough == true) {
            // Four scrim pieces around the exact target consume everything; the target itself is uncovered,
            // so the tap reaches the real control and tourTarget() advances the tour.
            val t = s.target
            val vw = viewSize.width; val vh = viewSize.height
            val l = t.left.toInt(); val tp = t.top.toInt(); val r = t.right.toInt(); val b = t.bottom.toInt()
            Blocker(0, 0, vw, tp)
            Blocker(0, b, vw, vh - b)
            Blocker(0, tp, l, b - tp)
            Blocker(r, tp, vw - r, b - tp)
        } else {
            // One full-screen blocker: consumes every event; a tap inside the hole only advances.
            val holeNow by rememberUpdatedState(s?.hole)
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var lastPos = down.position
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            event.changes.firstOrNull { it.id == down.id }?.let { lastPos = it.position }
                            if (event.changes.none { it.pressed }) { released = true; break }
                        }
                        val hole = holeNow
                        if (released && hole != null && hole.contains(down.position) && hole.contains(lastPos)) tour.next()
                    }
                },
            )
        }

        // ---- tooltip card ----
        AnimatedContent(
            targetState = s,
            contentKey = { it?.index ?: -1 },
            transitionSpec = {
                if (reduce) IrisMotion.none
                else (fadeIn(IrisMotion.FadeIn.float()) + slideInVertically(IrisMotion.Gentle.offset()) { it / 12 }) togetherWith fadeOut(IrisMotion.FadeOut.float())
            },
            modifier = Modifier.fillMaxSize(),
            label = "tourTip",
        ) { st ->
            if (st != null) Box(Modifier.fillMaxSize()) {
                val st0 = tour.steps.getOrNull(st.index)
                if (st0 != null) TipCard(
                    step = st0, index = st.index, count = tour.steps.size, hole = st.hole,
                    viewW = viewSize.width.toFloat(), viewTop = safeTop + with(density) { 8.dp.toPx() },
                    viewBottom = viewSize.height - safeBottom - with(density) { 8.dp.toPx() },
                    onSkip = tour::finish, onPrev = tour::back, onNext = tour::next,
                )
            }
        }
    }
}

/** A scrim piece in exact pixels that swallows every pointer event. */
@Composable
private fun Blocker(x: Int, y: Int, w: Int, h: Int) {
    if (w <= 0 || h <= 0) return
    Box(
        Modifier
            .offset { IntOffset(x, y) }
            .layout { m, _ ->
                val p = m.measure(Constraints.fixed(w, h))
                layout(w, h) { p.place(0, 0) }
            }
            .consumeAllPointers(),
    )
}

/**
 * Matte solid neutral card: title + one short line, grey step counter (one line), 跳过 / 上一步 /
 * 下一步 (完成 on the last step). The whole card area consumes input, so nothing behind it is pressed.
 */
@Composable
private fun TipCard(
    step: TourStep, index: Int, count: Int, hole: Rect,
    viewW: Float, viewTop: Float, viewBottom: Float,
    onSkip: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit,
) {
    val c = Iris.colors
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    val maxW = with(density) { 380.dp.toPx() }
    val cardW = minOf(viewW - 2 * margin, maxW).coerceAtLeast(with(density) { 200.dp.toPx() })
    val (n, total) = TourLogic.counter(index, count)
    val last = TourLogic.isLast(index, count)
    Column(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints.fixedWidth(cardW.toInt()))
                layout(p.width, p.height) {
                    val x = TourLogic.tipX(hole.center.x, p.width.toFloat(), viewW, margin)
                    val y = TourLogic.tipY(hole.toTour(), p.height.toFloat(), viewTop, viewBottom, gap)
                    p.place(IntOffset(x.toInt(), y.toInt()))
                }
            }
            .consumeAllPointers()
            .background(c.surface, IrisRadius.mediumShape)
            .border(IrisDimens.hairline, c.outline, IrisRadius.mediumShape)
            .padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(com.iris.localai.I18n.s(step.title), style = IrisType.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(IrisDimens.space8))
            Text(
                com.iris.localai.I18n.s(R.string.tour253_counter, n, total), style = IrisType.caption, color = c.textSecondary,
                maxLines = 1, overflow = TextOverflow.Clip,
            )
        }
        Spacer(Modifier.height(IrisDimens.space4))
        Text(com.iris.localai.I18n.s(step.body), style = IrisType.secondary, color = c.text)
        Spacer(Modifier.height(IrisDimens.space12))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8)) {
            if (!last) IrisButton(com.iris.localai.I18n.s(R.string.tour253_skip), onClick = onSkip, style = IrisButtonStyle.Text, compact = true)
            Spacer(Modifier.weight(1f))
            if (index > 0) IrisButton(com.iris.localai.I18n.s(R.string.tour253_prev), onClick = onPrev, style = IrisButtonStyle.Tonal, compact = true)
            IrisButton(
                com.iris.localai.I18n.s(if (last) R.string.tour253_done else R.string.tour253_next),
                onClick = onNext, compact = true,
            )
        }
    }
}

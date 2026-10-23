package com.iris.localai.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.iris.localai.ui.theme.LocalReduceMotion

/**
 * A spring in iOS terms: [response] = seconds per (undamped) oscillation, [damping] = fraction of
 * critical damping (1 = no overshoot). All app motion is built from these tokens, so every
 * animation is interruptible (springs retarget from their current value *and* velocity) and the
 * whole app shares one feel.
 */
@Immutable
data class SpringToken(val response: Float, val damping: Float) {
    val stiffness: Float get() = MotionMath.stiffness(response)

    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

    fun float(): SpringSpec<Float> = spec(0.001f)
    fun offset(): SpringSpec<IntOffset> = spec(IntOffset.VisibilityThreshold)
    fun size(): SpringSpec<IntSize> = spec(IntSize.VisibilityThreshold)
}

/**
 * The motion system. Only this package may mention durations or easing curves; screens pick a
 * token (a [SpringToken] or one of the transitions below). A unit test fails the build if a
 * `tween(` / `durationMillis` shows up anywhere else in the UI code.
 */
object IrisMotion {
    // ---- Spring tokens (iOS-like) -----------------------------------------------------------
    /** Small state changes: icons, chips, toggles, selection. Quick, a hint of settle. */
    val Snappy = SpringToken(response = 0.32f, damping = 0.86f)
    /** Large surfaces: pages, drawer, sheets, glass panels. No overshoot. */
    val Smooth = SpringToken(response = 0.45f, damping = 1.0f)
    /** Things that pop into existence: check marks, the send button, the glass droplet. */
    val Bouncy = SpringToken(response = 0.42f, damping = 0.70f)
    /** Content appearing, list placement, expand / collapse. Soft, barely any overshoot. */
    val Gentle = SpringToken(response = 0.50f, damping = 0.92f)
    /** Finger down: fast, slightly underdamped. */
    val Press = SpringToken(response = 0.18f, damping = 0.75f)
    /** Finger up: the elastic rebound. */
    val Rebound = SpringToken(response = 0.36f, damping = 0.55f)
    /** Values that follow a finger (slider knobs, drag handles): a touch of lag, no wobble. */
    val Interactive = SpringToken(response = 0.24f, damping = 0.90f)
    /** Opacity in. Critically damped so alpha never overshoots. */
    val FadeIn = SpringToken(response = 0.30f, damping = 1.0f)
    /** Opacity out / leaving: a little quicker than coming in. */
    val FadeOut = SpringToken(response = 0.22f, damping = 1.0f)
    /** 1.7.5: short settle with no overshoot, for reduced visual effects (省电 level / battery saver / heat). */
    val Quick = SpringToken(response = 0.16f, damping = 1.0f)
    /** Colour changes (selection, theme-dependent tints). */
    val Color = SpringToken(response = 0.36f, damping = 1.0f)
    /** Stiff, no-overshoot step between detents (tier slider knob; ≈ the 1.7.4 spring, stiffness ≈ 900). */
    val Detent = SpringToken(response = 0.21f, damping = 1.0f)
    /** Live meters (microphone level): stiff with a hint of bounce, follows the signal closely. */
    val Meter = SpringToken(response = 0.21f, damping = 0.8f)

    // ---- Scales ------------------------------------------------------------------------------
    /** Press scale for icon buttons, for wide buttons / chips, for list rows. */
    const val PRESS_SCALE_ICON = 0.86f
    const val PRESS_SCALE_BUTTON = 0.96f
    const val PRESS_SCALE_ROW = 0.985f

    // ---- Repeating indicators (only while something is in progress; see LocalDecorativeMotion) ----
    const val PULSE_PERIOD_MS = 900
    const val DOTS_PERIOD_MS = 560
    const val DOTS_STAGGER_MS = 140
    const val SWEEP_PERIOD_MS = 1400
    const val ORB_BREATH_MS = 2400
    const val ORB_BREATH_BUSY_MS = 900
    /** 1.14.7: delay between consecutive items of a staggered entrance (welcome screen). */
    const val ENTRANCE_STAGGER_MS = 70L
    /** Pause of the "copied" check before it turns back into the copy icon. */
    const val CONFIRM_HOLD_MS = 1500L
    /** Smooth ease for looping indicators (a loop has no "current value" to retarget, so no spring). */
    val LoopEasing: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    /** One leg of a looping indicator (in progress only). Loops have no value to retarget, so no spring. */
    fun <T> loop(periodMs: Int, delayMs: Int = 0): androidx.compose.animation.core.DurationBasedAnimationSpec<T> =
        androidx.compose.animation.core.tween(periodMs, delayMs, LoopEasing)
    fun <T> linearLoop(periodMs: Int): androidx.compose.animation.core.DurationBasedAnimationSpec<T> =
        androidx.compose.animation.core.tween(periodMs, easing = androidx.compose.animation.core.LinearEasing)

    // ---- 2.2 「档案模式」 (archive deck) --------------------------------------------------------
    /** Deck settling on the focused card after a drag / fling: firm, a hint of settle. */
    val ArchiveSnap = SpringToken(response = 0.40f, damping = 0.88f)
    /** Selection wave rippling through the neighbours of the newly focused card. */
    const val ARCHIVE_WAVE_MS = 560
    /** Cards falling back when a folder is opened. */
    const val ARCHIVE_FALL_MS = 260
    /** The next folder's cards rising into place. */
    const val ARCHIVE_RISE_MS = 480
    /** Diagonal "decrypt" sweep of the file sheet, frosted → clear. */
    const val ARCHIVE_DECRYPT_MS = 640
    /** 2.1.9 档案: the white intro (title only) before the slab field fades in, first open per session. */
    const val ARCHIVE_INTRO_MS = 700
    /** 2.1.9 档案: the slab field fading in / settling after the intro. */
    const val ARCHIVE_SETTLE_MS = 620
    /** 2.1.9 档案: the file detail — decrypt line, frosted → clear glass, redaction bars retracting. */
    const val ARCHIVE_REVEAL_MS = 1150
    /** Emphasized ease for one-shot 0 → 1 progress values (a wave or sweep has no value to retarget). */
    val ProgressEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Ease-in for things leaving (the falling cards). */
    val ExitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** A one-shot 0 → 1 progress (archive wave, rise, decrypt sweep). */
    fun <T> progress(periodMs: Int): androidx.compose.animation.core.DurationBasedAnimationSpec<T> =
        androidx.compose.animation.core.tween(periodMs, easing = ProgressEasing)
    /** A one-shot progress for content leaving. */
    fun <T> progressOut(periodMs: Int): androidx.compose.animation.core.DurationBasedAnimationSpec<T> =
        androidx.compose.animation.core.tween(periodMs, easing = ExitEasing)

    // ---- Generic specs -----------------------------------------------------------------------
    fun <T> enter(): FiniteAnimationSpec<T> = Gentle.spec()
    fun <T> exit(): FiniteAnimationSpec<T> = FadeOut.spec()
    fun <T> standard(): FiniteAnimationSpec<T> = Smooth.spec()
    fun <T> snappy(): FiniteAnimationSpec<T> = Snappy.spec()

    // ---- Enter / exit building blocks -------------------------------------------------------
    fun fadeIn(): EnterTransition = fadeIn(FadeIn.float())
    fun fadeOut(): ExitTransition = fadeOut(FadeOut.float())
    fun expand(): EnterTransition = expandVertically(Gentle.size(), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(FadeIn.float())
    fun collapse(): ExitTransition = shrinkVertically(Smooth.size(), shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut(FadeOut.float())
    fun popIn(initialScale: Float = 0.6f): EnterTransition = scaleIn(Bouncy.float(), initialScale) + fadeIn(FadeIn.float())
    fun popOut(targetScale: Float = 0.6f): ExitTransition = scaleOut(Smooth.float(), targetScale) + fadeOut(FadeOut.float())
    /** Rises a little while fading in (banners, toasts, empty states). [fraction] of own height. */
    fun riseIn(fraction: Float = 0.35f): EnterTransition =
        slideInVertically(Gentle.offset()) { (it * fraction).toInt() } + fadeIn(FadeIn.float())
    fun sinkOut(fraction: Float = 0.35f): ExitTransition =
        slideOutVertically(Smooth.offset()) { (it * fraction).toInt() } + fadeOut(FadeOut.float())

    // ---- Content transforms ------------------------------------------------------------------
    /**
     * iOS navigation push / pop. The new page slides in over the old one from the trailing edge;
     * the old one drifts back a third of the way and dims. Pops reverse it, and the page being
     * popped stays on top. Driven by a seekable transition, so the predictive back gesture scrubs
     * this exact animation with the finger.
     */
    fun push(forward: Boolean): ContentTransform {
        val enter = if (forward) slideInHorizontally(Smooth.offset()) { it }
        else slideInHorizontally(Smooth.offset()) { -it / 3 } + fadeIn(Smooth.float(), initialAlpha = PAGE_DIM)
        val exit = if (forward) slideOutHorizontally(Smooth.offset()) { -it / 3 } + fadeOut(Smooth.float(), targetAlpha = PAGE_DIM)
        else slideOutHorizontally(Smooth.offset()) { it }
        return ContentTransform(enter, exit, targetContentZIndex = if (forward) 1f else -1f, sizeTransform = null)
    }
    private const val PAGE_DIM = 0.55f

    /** enter/exit plus a spring-driven, unclipped size change (built directly, no `using`). */
    private fun sized(enter: EnterTransition, exit: ExitTransition, size: SpringToken): ContentTransform =
        ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false) { _, _ -> size.size() })

    /** In-place swap of small content (titles, status text). */
    fun crossfade(): ContentTransform =
        sized(fadeIn(FadeIn.float()), fadeOut(FadeOut.float()), Gentle)

    /** Icon / action morph (send ↔ stop ↔ voice, copy ↔ check): springy scale-through. */
    fun pop(): ContentTransform =
        sized(scaleIn(Bouncy.float(), initialScale = 0.55f) + fadeIn(FadeIn.float()),
            scaleOut(Smooth.float(), targetScale = 0.55f) + fadeOut(FadeOut.float()), Snappy)

    /** Rolling text for counters and changing numbers: up when the value grows, down when it shrinks. */
    fun roll(up: Boolean): ContentTransform {
        val dir = if (up) 1 else -1
        return sized(slideInVertically(Snappy.offset()) { dir * it / 2 } + fadeIn(FadeIn.float()),
            slideOutVertically(Snappy.offset()) { -dir * it / 2 } + fadeOut(FadeOut.float()), Snappy)
    }

    /** Fade-through with a slight zoom, for whole-section swaps. */
    fun fadeThrough(): ContentTransform =
        (fadeIn(FadeIn.float()) + scaleIn(Gentle.float(), initialScale = 0.96f)) togetherWith fadeOut(FadeOut.float())

    val none: ContentTransform = EnterTransition.None togetherWith ExitTransition.None
}

/** Picks [transform], or no animation when motion is reduced. Use inside AnimatedContent.transitionSpec. */
fun <S> AnimatedContentTransitionScope<S>.motion(reduce: Boolean, transform: () -> ContentTransform): ContentTransform =
    if (reduce) IrisMotion.none else transform()

/** [token]'s spring, or an instant snap when the system "remove animations" switch is on. */
@Composable
fun <T> motionSpec(token: SpringToken, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else token.spec(visibilityThreshold)

/** Same as [motionSpec] for callers outside composition that already know the reduce flag. */
fun <T> motionSpec(token: SpringToken, reduce: Boolean, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    if (reduce) snap() else token.spec(visibilityThreshold)

/**
 * Whether purely decorative, repeating animation may run right now (pulsing labels, breathing
 * dots, moving glass highlights). False while a reply is streaming (the typewriter and the follow
 * scroll own the frame budget then), when the system removes animations, and when the app is not
 * in the foreground. Compose already stops the frame clock when the activity stops; this flag also
 * stops the work from being scheduled at all.
 */
val LocalDecorativeMotion = compositionLocalOf { true }

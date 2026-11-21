package com.iris.localai.ui.feedback

import com.iris.localai.IrisApp
import com.iris.localai.util.diagnostics.SystemStatus
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.iris.localai.R

/**
 * one place for haptics + sound feedback. Call sites name *what happened*
 * ([FeedbackEvent]); [IrisFeedbackPolicy] (pure, JVM-tested) decides which haptic and which
 * sound that means and whether either may play right now.
 */
enum class FeedbackEvent { SEND, REPLY_DONE, LISTEN_START, LISTEN_STOP, BARGE_IN, STEP_DONE, ERROR, TOGGLE, TICK, LONG_PRESS }

/** Haptic kinds, mapped to [HapticFeedbackConstants] in [IrisFeedback.hapticConstant] (minSdk 31). */
enum class HapticKind { LIGHT, TICK, CONFIRM, REJECT, LONG_PRESS, GESTURE_START, GESTURE_END }

/** The tiny soft sounds in res/raw (each < 150 ms). */
enum class FeedbackSound { SEND, DONE, TICK, ERROR }

data class FeedbackSpec(val haptic: HapticKind, val sound: FeedbackSound?)

/** Everything the gate looks at, captured at the moment of the event. */
data class FeedbackEnv(
    val hapticsOn: Boolean = true,
    val soundOn: Boolean = true,
    /** Ringer mode is normal (not silent / vibrate). */
    val ringerNormal: Boolean = true,
    /** Do-not-disturb is off (interruption filter ALL or unknown). */
    val dndOff: Boolean = true,
    /** A voice session is active: TTS speaking or the mic listening. */
    val voiceActive: Boolean = false,
)

data class FeedbackPlan(val haptic: HapticKind?, val sound: FeedbackSound?) {
    val isEmpty: Boolean get() = haptic == null && sound == null
}

object IrisFeedbackPolicy {
    /** Same sounding event again within this window is dropped (plan step + agent step finishing together). */
    const val THROTTLE_MS = 250L
    const val VOLUME = 0.35f

    fun spec(e: FeedbackEvent): FeedbackSpec = when (e) {
        FeedbackEvent.SEND -> FeedbackSpec(HapticKind.LIGHT, FeedbackSound.SEND)
        FeedbackEvent.REPLY_DONE -> FeedbackSpec(HapticKind.CONFIRM, FeedbackSound.DONE)
        FeedbackEvent.LISTEN_START -> FeedbackSpec(HapticKind.GESTURE_START, null)
        FeedbackEvent.LISTEN_STOP -> FeedbackSpec(HapticKind.GESTURE_END, null)
        FeedbackEvent.BARGE_IN -> FeedbackSpec(HapticKind.TICK, null)
        FeedbackEvent.STEP_DONE -> FeedbackSpec(HapticKind.LIGHT, FeedbackSound.TICK)
        FeedbackEvent.ERROR -> FeedbackSpec(HapticKind.REJECT, FeedbackSound.ERROR)
        FeedbackEvent.TOGGLE -> FeedbackSpec(HapticKind.LIGHT, null)
        FeedbackEvent.TICK -> FeedbackSpec(HapticKind.TICK, null)
        FeedbackEvent.LONG_PRESS -> FeedbackSpec(HapticKind.LONG_PRESS, null)
    }

    /** Sound only when the user wants it, the phone is not silenced, and no voice session owns the audio. */
    fun soundAllowed(env: FeedbackEnv): Boolean = env.soundOn && env.ringerNormal && env.dndOff && !env.voiceActive

    fun plan(e: FeedbackEvent, env: FeedbackEnv): FeedbackPlan {
        val s = spec(e)
        return FeedbackPlan(
            haptic = s.haptic.takeIf { env.hapticsOn },
            sound = s.sound.takeIf { soundAllowed(env) },
        )
    }

    fun ringerNormal(ringerMode: Int): Boolean = ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun dndOff(interruptionFilter: Int): Boolean =
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            interruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    /** True when [e] was already played [THROTTLE_MS] ago or less (sounding events only). */
    fun throttled(e: FeedbackEvent, lastAtMs: Long?, nowMs: Long): Boolean =
        spec(e).sound != null && lastAtMs != null && nowMs - lastAtMs in 0 until THROTTLE_MS
}

/** Voice state the UI knows about (read-aloud is tracked in SystemStatus.voiceActive). */
object FeedbackVoice {
    /** The voice-chat overlay is open (mic listening for the whole session). */
    @Volatile var overlayOpen = false
    /** Hold-to-talk dictation in the composer. */
    @Volatile var dictating = false
}

class IrisFeedback internal constructor(private val view: View) {
    private val app: Context = view.context.applicationContext

    fun perform(e: FeedbackEvent) {
        val now = SystemClock.uptimeMillis()
        if (IrisFeedbackPolicy.throttled(e, lastAt[e], now)) return
        val plan = IrisFeedbackPolicy.plan(e, env())
        if (plan.isEmpty) return
        if (plan.sound != null) lastAt[e] = now
        plan.haptic?.let { if (!Haptics.compose(app, it)) view.performHapticFeedback(hapticConstant(it)) }
        plan.sound?.let { Sounds.play(app, it) }
    }

    private fun env(): FeedbackEnv {
        val s = runCatching { (app as IrisApp).container.settings.current }.getOrNull()
        val audio = app.getSystemService(AudioManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        return FeedbackEnv(
            hapticsOn = s?.haptics ?: true,
            soundOn = s?.sound ?: true,
            ringerNormal = audio?.let { IrisFeedbackPolicy.ringerNormal(it.ringerMode) } ?: false,
            dndOff = nm?.let { IrisFeedbackPolicy.dndOff(it.currentInterruptionFilter) } ?: false,
            voiceActive = FeedbackVoice.overlayOpen || FeedbackVoice.dictating ||
                SystemStatus.live.value.voiceActive,
        )
    }

    companion object {
        private val lastAt = java.util.concurrent.ConcurrentHashMap<FeedbackEvent, Long>()

        fun hapticConstant(k: HapticKind): Int = when (k) {
            HapticKind.LIGHT -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
            HapticKind.TICK -> if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HapticKind.CONFIRM -> HapticFeedbackConstants.CONFIRM
            HapticKind.REJECT -> HapticFeedbackConstants.REJECT
            HapticKind.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            HapticKind.GESTURE_START -> HapticFeedbackConstants.GESTURE_START
            HapticKind.GESTURE_END -> HapticFeedbackConstants.GESTURE_END
        }
    }

    /** SoundPool created on the first sound, all four clips loaded then; a clip still loading plays once ready. */
    private object Sounds {
        private var pool: SoundPool? = null
        private val ids = HashMap<FeedbackSound, Int>()
        private val ready = HashSet<Int>()
        private var pending: Int? = null

        private fun res(s: FeedbackSound) = when (s) {
            FeedbackSound.SEND -> R.raw.iris_send
            FeedbackSound.DONE -> R.raw.iris_done
            FeedbackSound.TICK -> R.raw.iris_tick
            FeedbackSound.ERROR -> R.raw.iris_error
        }

        @Synchronized fun play(ctx: Context, s: FeedbackSound) {
            val p = pool ?: SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
                .also { sp ->
                    pool = sp
                    sp.setOnLoadCompleteListener { pl, id, status ->
                        synchronized(this) {
                            if (status != 0) return@synchronized
                            ready += id
                            if (pending == id) { pending = null; pl.play(id, IrisFeedbackPolicy.VOLUME, IrisFeedbackPolicy.VOLUME, 1, 0, 1f) }
                        }
                    }
                    for (f in FeedbackSound.entries) ids[f] = sp.load(ctx, res(f), 1)
                }
            val id = ids[s] ?: return
            if (id in ready) p.play(id, IrisFeedbackPolicy.VOLUME, IrisFeedbackPolicy.VOLUME, 1, 0, 1f) else pending = id
        }
    }
}

/** `val feedback = rememberIrisFeedback(); feedback.perform(FeedbackEvent.SEND)` */
@Composable
fun rememberIrisFeedback(): IrisFeedback {
    val view = LocalView.current
    return remember(view) { IrisFeedback(view) }
}

/**
 * 3.0 振动重做：机器支持合成原语（Android 11+ 的线性马达，VibrationEffect.Composition）时用原语拼出更细腻的手感，
 * 不支持时退回系统的 HapticFeedbackConstants。只读不改「触感反馈」开关（由 [IrisFeedbackPolicy.plan] 把关）。
 */
object HapticRecipe {
    /** (原语, 强度 0..1, 前延迟 ms)。纯数据（单测）。 */
    data class Step(val primitive: Int, val scale: Float, val delayMs: Int = 0)

    fun of(k: HapticKind): List<Step> = when (k) {
        HapticKind.LIGHT -> listOf(Step(android.os.VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f))
        HapticKind.TICK -> listOf(Step(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.6f))
        HapticKind.CONFIRM -> listOf(
            Step(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f),
            Step(android.os.VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 70),
        )
        HapticKind.REJECT -> listOf(
            Step(android.os.VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f),
            Step(android.os.VibrationEffect.Composition.PRIMITIVE_THUD, 0.4f, 90),
        )
        HapticKind.LONG_PRESS -> listOf(Step(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f))
        HapticKind.GESTURE_START -> listOf(Step(android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.5f))
        HapticKind.GESTURE_END -> listOf(Step(android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f))
    }
}

internal object Haptics {
    @Volatile private var supported: Map<Int, Boolean>? = null

    /** true = played with composition primitives. */
    fun compose(ctx: Context, k: HapticKind): Boolean = runCatching {
        val vm = ctx.getSystemService(android.os.VibratorManager::class.java) ?: return false
        val v = vm.defaultVibrator
        if (!v.hasVibrator()) return false
        val steps = HapticRecipe.of(k)
        val sup = supported ?: run {
            val all = HapticKind.entries.flatMap { HapticRecipe.of(it) }.map { it.primitive }.distinct().toIntArray()
            val r = v.arePrimitivesSupported(*all)
            all.indices.associate { all[it] to r[it] }.also { supported = it }
        }
        if (steps.any { sup[it.primitive] != true }) return false
        val c = android.os.VibrationEffect.startComposition()
        steps.forEach { c.addPrimitive(it.primitive, it.scale, it.delayMs) }
        v.vibrate(c.compose(), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
        true
    }.getOrDefault(false)
}

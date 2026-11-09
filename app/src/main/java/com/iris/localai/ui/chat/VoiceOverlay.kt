package com.iris.localai.ui.chat

import com.iris.localai.ui.theme.IrisDimens
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import android.media.AudioManager
import android.app.Activity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motionSpec
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.motion.motion
import com.iris.localai.ui.components.StatusDot
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import com.iris.localai.voice.VoicePhase
import kotlin.math.PI
import kotlin.math.cos

/** Full-screen hands-free voice conversation, ChatGPT-voice style. */
@Composable
fun VoiceOverlay(vm: ChatViewModel, onClose: () -> Unit) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val state by vm.voice.state.collectAsStateWithLifecycle()
    var muted by rememberSaveable { mutableStateOf(false) }

    // The microphone only runs while this overlay is visible and the app is in the foreground.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        vm.voice.start()
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) onClose() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.voice.stop()
        }
    }
    BackHandler(onBack = onClose)
    // A light tick when the end of what you said was caught, so you know you were heard
    // without looking at the screen.
    val haptic = com.iris.localai.ui.feedback.rememberIrisFeedback()
    DisposableEffect(Unit) {
        com.iris.localai.ui.feedback.FeedbackVoice.overlayOpen = true
        onDispose { com.iris.localai.ui.feedback.FeedbackVoice.overlayOpen = false }
    }
    val bargeIns0 = remember { state.bargeIns }
    LaunchedEffect(state.bargeIns) { if (state.bargeIns > bargeIns0) haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.BARGE_IN) }
    var lastPhase by remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase) {
        val p = state.phase
        if (p == VoicePhase.RECOGNIZING && lastPhase == VoicePhase.LISTENING) {
            haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.LISTEN_STOP)
        }
        if (p == VoicePhase.LISTENING && lastPhase == VoicePhase.PREPARING) haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.LISTEN_START)
        if (p == VoicePhase.ERROR && lastPhase != VoicePhase.ERROR) haptic.perform(com.iris.localai.ui.feedback.FeedbackEvent.ERROR)
        lastPhase = p
    }
    // Volume keys adjust media volume (where the reply plays) while the voice screen is open.
    val activity = LocalContext.current as? Activity
    val voiceComm = remember { vm.settings.value.bargeInVoiceComm }
    DisposableEffect(activity, voiceComm) {
        val prev = activity?.volumeControlStream ?: AudioManager.USE_DEFAULT_STREAM_TYPE
        activity?.volumeControlStream = if (voiceComm) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        // 「更易打断」: replies play as a call. Put the phone in communication mode and keep the
        // loudspeaker (not the earpiece) unless a headset / Bluetooth device is connected.
        val am = activity?.getSystemService(AudioManager::class.java)
        if (voiceComm && am != null) runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val devices = am.availableCommunicationDevices
            val external = devices.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (!external) devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        }
        onDispose {
            activity?.volumeControlStream = prev
            if (voiceComm && am != null) runCatching { am.clearCommunicationDevice(); am.mode = AudioManager.MODE_NORMAL }
        }
    }

    val accent = c.accent // 1.8.0: HyperOS blue (was indigo)
    val phaseColor = when (state.phase) {
        VoicePhase.LISTENING -> if (muted) c.textTertiary else c.success
        VoicePhase.RECOGNIZING, VoicePhase.THINKING -> c.warning
        VoicePhase.SPEAKING -> accent
        VoicePhase.ERROR -> c.danger
        VoicePhase.PREPARING -> c.textTertiary
    }
    val phaseText = when (state.phase) {
        VoicePhase.PREPARING -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f601cb)
        VoicePhase.LISTENING -> if (muted) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_557582) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5cca6a)
        VoicePhase.RECOGNIZING -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_b77edf)
        VoicePhase.THINKING -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1b0a9d)
        VoicePhase.SPEAKING -> if (state.awaitingAudio) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_97058e) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_9b174d)
        VoicePhase.ERROR -> com.iris.localai.I18n.s(com.iris.localai.R.string.chat_985c39)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.chatBackground)
            .background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = if (c.isDark) 0.18f else 0.10f),
                    0.55f to Color.Transparent,
                    1f to accent.copy(alpha = if (c.isDark) 0.08f else 0.05f),
                ),
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Status pill
            Spacer(Modifier.height(IrisDimens.space20))
            Row(
                Modifier
                    .background(c.surfaceVariant.copy(alpha = 0.7f), IrisRadius.pillShape)
                    .padding(horizontal = com.iris.localai.ui.theme.IrisSpace.M, vertical = IrisDimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dotColor by animateColorAsState(phaseColor, IrisMotion.standard(), label = "phaseColor")
                StatusDot(dotColor, active = state.phase != VoicePhase.PREPARING && !muted)
                Spacer(Modifier.size(IrisDimens.space8))
                AnimatedContent(
                    targetState = phaseText,
                    transitionSpec = { motion(reduce) { IrisMotion.crossfade() } },
                    label = "phaseText",
                ) { t -> Text(t, style = IrisType.labelSmall, color = c.text) }
            }
            if (state.phase == VoicePhase.ERROR && !state.error.isNullOrBlank()) {
                Text(
                    state.error.orEmpty(), style = IrisType.caption, color = c.danger, textAlign = TextAlign.Center,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = IrisDimens.space32, vertical = com.iris.localai.ui.theme.IrisSpace.S),
                )
            }

            Spacer(Modifier.weight(1f))
            VoiceOrb(
                modifier = Modifier.size(220.dp).clickable(
                    remember { MutableInteractionSource() }, indication = null,
                    enabled = state.phase == VoicePhase.ERROR || state.phase == VoicePhase.SPEAKING,
                ) {
                    // Tap the disc: retry after an error, or cut a reply short.
                    if (state.phase == VoicePhase.ERROR) vm.voice.start() else vm.voice.interrupt()
                },
                phase = state.phase,
                waiting = state.waiting,
                color = if (state.phase == VoicePhase.ERROR) c.danger else if (muted && state.phase == VoicePhase.LISTENING) c.textTertiary else accent,
                level = if (state.phase == VoicePhase.SPEAKING) state.outputLevel else if (muted) 0f else state.micLevel,
            )
            Spacer(Modifier.weight(1f))

            // Transcript
            Column(
                Modifier.fillMaxWidth().padding(horizontal = com.iris.localai.ui.theme.IrisSpace.XL).height(190.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (state.lastHeard.isNotBlank()) {
                    Text(
                        "“${state.lastHeard}”", style = IrisType.secondary, color = c.textSecondary,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(IrisDimens.space12))
                }
                val reply = state.reply.trim()
                if (reply.isNotEmpty()) {
                    // Full reply in a scrolling box that follows the end. The old "last 120 chars,
                    // 3 lines, centred" view re-wrapped every token, so the subtitle jumped around.
                    val scroll = rememberScrollState()
                    LaunchedEffect(reply.length) { scroll.animateScrollTo(scroll.maxValue) }
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll)) {
                        Text(
                            reply,
                            style = IrisType.voiceReply,
                            color = c.text,
                            textAlign = if (reply.length <= 24) TextAlign.Center else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (state.lastHeard.isBlank()) {
                    Text(
                        if (state.phase == VoicePhase.LISTENING && !muted) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_0c2a12) else "",
                        style = IrisType.secondary, color = c.textTertiary, textAlign = TextAlign.Center,
                    )
                }
            }

            // Controls
            Row(
                Modifier.fillMaxWidth().padding(top = IrisDimens.space20, bottom = com.iris.localai.ui.theme.IrisSpace.XL, start = IrisDimens.space24, end = IrisDimens.space24),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                VoiceControl(if (muted) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6e1019) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_351cb1)) {
                    PressableIcon(
                        if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (muted) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6e1019) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_351cb1),
                        size = 64.dp, iconSize = 26.dp,
                        background = if (muted) c.text else c.surfaceVariant,
                        tint = if (muted) c.chatBackground else c.text,
                    ) { muted = !muted; vm.voice.setMuted(muted) }
                }
                VoiceControl(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7c1e51)) {
                    PressableIcon(
                        Icons.Outlined.PanTool, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_7c1e51), size = 64.dp, iconSize = IrisDimens.space24, background = c.surfaceVariant,
                        enabled = state.phase == VoicePhase.SPEAKING || state.phase == VoicePhase.THINKING,
                    ) { vm.voice.interrupt() }
                }
                VoiceControl(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_12f1d7)) {
                    PressableIcon(
                        Icons.Outlined.Close, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_137760), size = 64.dp, iconSize = 26.dp,
                        background = c.danger, tint = Color.White, haptic = true, onClick = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceControl(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(IrisDimens.space8))
        Text(label, style = IrisType.caption, color = Iris.colors.textSecondary)
    }
}

/**
 * A plain disc: one solid circle and one faint halo. It breathes slowly while listening, swells
 * with the voice level, and — while the answer is being prepared ([waiting]: thinking until the
 * first audio, or a gap between sentences) — clearly floats up and down while breathing.
 * With reduce-motion the disc stays still and only the halo widens to show the waiting state.
 */
@Composable
private fun VoiceOrb(phase: VoicePhase, waiting: Boolean, level: Float, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "orb")
    val busy = phase == VoicePhase.RECOGNIZING || phase == VoicePhase.PREPARING
    // Read only inside the draw lambda: redraws every frame, never recomposes.
    val breath = if (reduce) null else t.animateFloat(
        0f, 1f,
        infiniteRepeatable(IrisMotion.linearLoop(if (busy) IrisMotion.ORB_BREATH_BUSY_MS else IrisMotion.ORB_BREATH_MS)),
        label = "breath",
    )
    val float = if (reduce) null else t.animateFloat(
        0f, 1f, infiniteRepeatable(IrisMotion.linearLoop(OrbMotion.FLOAT_PERIOD_MS)), label = "float",
    )
    // Eases the float in and out instead of snapping when waiting starts / audio resumes.
    val amount by animateFloatAsState(if (waiting && !reduce) 1f else 0f, motionSpec(IrisMotion.Gentle, reduce, 0.001f), label = "waitAmount")
    val waitHalo by animateFloatAsState(if (waiting) 1f else 0f, motionSpec(IrisMotion.FadeIn, reduce, 0.001f), label = "waitHalo")
    val energy = animateFloatAsState(
        when (phase) {
            VoicePhase.SPEAKING, VoicePhase.LISTENING -> if (level.isFinite()) (level * 10f).coerceIn(0f, 1f) else 0f
            else -> 0f
        },
        motionSpec(IrisMotion.Bouncy, reduce, 0.001f),
        label = "energy",
    )
    val tint by animateColorAsState(color, IrisMotion.standard(), label = "orbColor")

    Canvas(modifier) {
        val b = (breath?.value ?: 0f).let { if (it.isFinite()) it else 0f }
        val f = (float?.value ?: 0f).let { if (it.isFinite()) it else 0f }
        val en = energy.value.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        val wave = 0.5f - 0.5f * cos(b * 2f * PI.toFloat())
        val r0 = size.minDimension * 0.30f
        if (!r0.isFinite() || r0 < 1f) return@Canvas
        val pulse = if (busy) 0.035f * wave else 0.015f * wave
        val m = OrbMotion.at(f, amount)
        val r = r0 * (1f + pulse + m.scale + 0.12f * en)
        val center = Offset(size.width / 2f, size.height / 2f + m.dy * size.minDimension)
        drawCircle(tint.copy(alpha = 0.10f + 0.10f * en + 0.06f * waitHalo), radius = r * (1.18f + 0.10f * en + 0.10f * waitHalo), center = center)
        drawCircle(tint, radius = r, center = center)
    }
}

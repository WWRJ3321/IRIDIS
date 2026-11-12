package com.iris.localai.ui.chat

import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import com.iris.localai.ui.components.floatingSurface
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.ToggleChip
import com.iris.localai.ui.components.pulse
import com.iris.localai.ui.glass.GlassBackdrop
import com.iris.localai.ui.glass.glass
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.motion.motion

data class ComposerState(
    val text: String,
    val editing: Boolean,
    val generating: Boolean,
    val deepThinking: Boolean,
    val thinkingSupported: Boolean,
    val listening: Boolean,
    val micLevel: Float,
    val transcribing: Boolean,
    /** 1.7.1: resource tier ordinal (低/中/高/全部) — how much of the phone inference may use. */
    val resourceTier: Int = com.iris.localai.engine.ResourceTier.DEFAULT.ordinal,
    /** 1.7.5: one short status of the last tier change, from what the engine really did (null = none). */
    val resourceNote: String? = null,
    /** 1.9.0: 智能体 mode (tools on); false = 对话. */
    val agentMode: Boolean = false,
    /** 1.10.12: files / pictures waiting to be sent with the next message. */
    val attachments: List<com.iris.localai.data.Attachment> = emptyList(),
    /** 2.2 「技能」: skills / tools picked for the next message (chips above the text). */
    val refs: List<com.iris.localai.data.TurnRef> = emptyList(),
    /** 2.3.0 ③: the draft is being translated (streams into [text]). */
    val translating: Boolean = false,
    /** 2.3.0 ③: the draft before 「翻译」 (non-null = 撤销翻译 shows). */
    val draftOriginal: String? = null,
)

/** 1.10.2: the round action (empty input = 语音对话). 2.2: the face of the one voice capsule ([VoiceCapsule]). */
enum class ComposerAction { STOP, SEND, VOICE }

/** 1.10.2: which round action shows (pure, unit-tested). */
object ComposerActions {
    fun of(generating: Boolean, hasText: Boolean, hasAttachments: Boolean = false): ComposerAction = when {
        // 2.1.7: typing while a reply runs sends (a 补充, or 「停/等等…」 interrupts); empty = 停止.
        hasText || hasAttachments -> ComposerAction.SEND
        generating -> ComposerAction.STOP
        else -> ComposerAction.VOICE
    }
}

/** 1.9.0: composer texts (unit-tested). */
object ComposerText {
    val CHAT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_859362)
    val AGENT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_72fbb9)
    val PLACEHOLDER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_6f6822)
    val AGENT_PLACEHOLDER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_db0873)
    // 1.10.1: minimal composer. 1.10.2: the 「+」 menu is gone — voice input is the press-and-hold
    // mic again (right of the pills) and 语音对话 is the round button when the input is empty.
    val THINK: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_21d68b)
    val THINK_UNSUPPORTED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_e3d835)
    val LISTENING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c8bcad)
    val RELEASE_TO_CANCEL: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1f31c5)
    val TRANSCRIBING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_0e89f9)
    val SPEED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_03f385)
    val ATTACH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_ca0b5e)
    val ATTACH_PHOTOS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d2fb1e)
    val ATTACH_FILES: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2a0c47)
    // 2.2 composer: one voice capsule + 「技能」.
    val CALL_HOLD: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_83c923)
    val SEND: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1535fc)
    val STOP: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_dda4c0)
    val SKILLS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_699143)
    val SKILL_SEARCH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_74fa39)
    val SECTION_SKILLS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_46c945)
    val SECTION_TOOLS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_fb695c)
    val NO_SKILLS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2ce87d)
    val NO_TOOLS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_05ae1f)
    val NO_MATCH: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_fb08d1)
    val SKILL_TOOL_OFF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_568f9a)
    val SKILLS_EMPTY: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d9abfd)
    val AGENT_FOR_REF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_338074)
    fun removeRef(name: String) = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_8506eb, name)
    fun agentNotice(on: Boolean): String =
        if (on) com.iris.localai.I18n.s(com.iris.localai.R.string.chat_fbf95c) else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_e9e4d8)
}

@Composable
fun Composer(
    focusRequester: FocusRequester? = null,
    state: ComposerState,
    backdrop: GlassBackdrop,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelEdit: () -> Unit,
    onToggleThinking: () -> Unit,
    onHoldToTalkStart: () -> Unit,
    onHoldToTalkEnd: () -> Unit,
    onVoiceMode: () -> Unit,
    onResourceTier: (Int) -> Unit = {},
    /** 2.1.7: long-press 发送 while generating → 「打断并发送」. */
    onInterruptSend: () -> Unit = {},
    /** 1.10.1: tap on the greyed 思考 pill (the model has no thinking) — show a hint. */
    onThinkingUnsupported: () -> Unit = {},
    /** 1.9.0: 对话 ⇄ 智能体. */
    onToggleAgent: () -> Unit = {},
    /** 1.10.13: 「附件」 menu — 照片 (system photo picker) / 文件 (document picker). */
    onPickPhotos: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    onRemoveAttachment: (com.iris.localai.data.Attachment) -> Unit = {},
    onMoveAttachment: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** 2.2: slide up while holding the capsule → the dictation is thrown away. */
    onHoldToTalkCancel: () -> Unit = {},
    /** 2.2 「技能」: what the sheet lists (read when it opens), and adding / removing a reference. */
    refCatalog: () -> RefCatalog = { RefCatalog.EMPTY },
    onAddRef: (com.iris.localai.data.TurnRef) -> Unit = {},
    onRemoveRef: (com.iris.localai.data.TurnRef) -> Unit = {},
    /** 2.3.0 ③ 「翻译」 the draft: target setting ("auto" / "zh" / "en"), and 撤销翻译. */
    onTranslateDraft: (String) -> Unit = {},
    onUndoTranslate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = Iris.colors
    val shape = IrisRadius.composerShape
    val reduce = LocalReduceMotion.current
    var composerTop by remember { mutableStateOf(Int.MAX_VALUE) }
    Column(
        modifier
            .fillMaxWidth()
            // 1.7.3: clip = false — the frosted glass clips itself to the outline path; a clipping
            // shadow layer could cut the blurred slice and leave a light fringe at the edge.
            .onGloballyPositioned { composerTop = it.boundsInWindow().top.toInt() }
            .glass(backdrop, shape)
            .border(IrisDimens.hairline + 0.1.dp, c.outline.copy(alpha = 0.55f), shape)
            .then(if (reduce) Modifier else Modifier.animateContentSize(IrisMotion.Snappy.size()))
            .padding(start = com.iris.localai.ui.theme.IrisSpace.S, end = com.iris.localai.ui.theme.IrisSpace.S, top = com.iris.localai.ui.theme.IrisSpace.XS, bottom = com.iris.localai.ui.theme.IrisSpace.S),
    ) {
        AnimatedVisibility(
            visible = state.editing,
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = com.iris.localai.ui.theme.IrisSpace.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.XS))
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2fb441), style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                PressableIcon(Icons.Outlined.Close, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_cbb465), size = 32.dp, iconSize = 16.dp, tint = c.textSecondary, onClick = onCancelEdit)
            }
        }

        AnimatedVisibility(
            visible = state.translating || state.draftOriginal != null,
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = com.iris.localai.ui.theme.IrisSpace.S), verticalAlignment = Alignment.CenterVertically) {
                Icon(com.iris.localai.ui.icons.IrisIcons.Translate, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.XS))
                Text(if (state.translating) TranslateText.TRANSLATING else com.iris.localai.I18n.s(com.iris.localai.R.string.chat_9aa52a), style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                if (state.translating) com.iris.localai.ui.components.TypingDots()
                PressableIcon(Icons.Outlined.Close, TranslateText.UNDO, size = 32.dp, iconSize = 16.dp, tint = c.textSecondary, onClick = onUndoTranslate)
            }
        }

        // 1.10.2: 「智能体」 is a small pill in the card's top-left corner, above the text field
        // (user: 「智能体放左上」). Same colours as the 1.10.1 pills, just smaller.
        Row(
            Modifier.fillMaxWidth().padding(start = com.iris.localai.ui.theme.IrisSpace.XS, top = com.iris.localai.ui.theme.IrisSpace.XS, end = com.iris.localai.ui.theme.IrisSpace.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerPill(
                ComposerText.AGENT,
                selected = state.agentMode,
                onClick = onToggleAgent,
                icon = com.iris.localai.ui.icons.IrisIcons.Agent,
                compact = true,
                modifier = Modifier.tourTarget(TourId.AGENT),
            )
            // 2.2 「技能」: pick an installed skill / agent tool for this message.
            var skillSheet by remember { mutableStateOf(false) }
            var catalog by remember { mutableStateOf(RefCatalog.EMPTY) }
            Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.XS))
            ComposerPill(
                ComposerText.SKILLS,
                selected = state.refs.isNotEmpty(),
                toggle = false,
                onClick = { catalog = refCatalog(); skillSheet = true },
                icon = com.iris.localai.ui.icons.IrisIcons.Skill,
                compact = true,
                modifier = Modifier.tourTarget(TourId.SKILLS),
            )
            if (skillSheet) SkillPickerSheet(catalog, onPick = { onAddRef(it); skillSheet = false }, onDismiss = { skillSheet = false })
            Spacer(Modifier.weight(1f))
            // 1.10.12: attach pictures / files — top-right, opposite 智能体, so the bottom row keeps its layout.
            // 1.10.13: a small IRIS menu first (user: not straight into the file manager).
            // 2.2: a 「+」 as tall as the chips, its right edge on the capsule's right edge.
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                PressableIcon(com.iris.localai.ui.icons.IrisIcons.Plus, ComposerText.ATTACH, modifier = Modifier.tourTarget(TourId.ATTACH), size = ComposerPillStyle.COMPACT_HEIGHT_DP.dp, iconSize = 22.dp, tint = c.textSecondary, onClick = { attachMenu = true })
                com.iris.localai.ui.components.IrisMenu(attachMenu, { attachMenu = false }) {
                    com.iris.localai.ui.components.IrisMenuItem(ComposerText.ATTACH_PHOTOS, com.iris.localai.ui.icons.IrisIcons.Image) { attachMenu = false; onPickPhotos() }
                    com.iris.localai.ui.components.IrisMenuItem(ComposerText.ATTACH_FILES, com.iris.localai.ui.icons.IrisIcons.Folder) { attachMenu = false; onPickFiles() }
                    // 2.3.0 ③: translate the draft before sending (default 中↔英 by detection).
                    if (state.text.isNotBlank() && !state.translating) {
                        for ((target, label) in listOf("auto" to TranslateText.DRAFT_AUTO, "zh" to TranslateText.DRAFT_ZH, "en" to TranslateText.DRAFT_EN)) {
                            com.iris.localai.ui.components.IrisMenuItem(label, com.iris.localai.ui.icons.IrisIcons.Translate) { attachMenu = false; onTranslateDraft(target) }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.attachments.isNotEmpty(),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            AttachmentStrip(state.attachments, onRemove = onRemoveAttachment, onMove = onMoveAttachment, modifier = Modifier.padding(start = com.iris.localai.ui.theme.IrisSpace.XS, top = com.iris.localai.ui.theme.IrisSpace.XS, end = com.iris.localai.ui.theme.IrisSpace.XS))
        }
        AnimatedVisibility(
            visible = state.refs.isNotEmpty(),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            RefChips(state.refs, onRemove = onRemoveRef, modifier = Modifier.padding(start = com.iris.localai.ui.theme.IrisSpace.XS, top = com.iris.localai.ui.theme.IrisSpace.XS, end = com.iris.localai.ui.theme.IrisSpace.XS))
        }

        // 1.14.1: the text field stays in composition while dictating. Swapping it out (1.10–1.14.0)
        // dropped its focus, the keyboard closed, the composer jumped down by the IME height and the
        // finger ended up outside the mic button — which cancelled the hold and cut the dictation off.
        val recording = state.listening || state.transcribing
        var cancelArmed by remember { mutableStateOf(false) }
        val fieldAlpha by animateFloatAsState(if (recording) 0f else 1f, IrisMotion.Color.float(), label = "fieldAlpha")
        Box(Modifier.tourTarget(TourId.INPUT)) {
                BasicTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    textStyle = IrisType.message.copy(color = c.text, lineHeight = IrisType.row.lineHeight),
                    cursorBrush = SolidColor(c.text),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    readOnly = recording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .graphicsLayer { alpha = fieldAlpha }
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = com.iris.localai.ui.theme.IrisSpace.S, vertical = com.iris.localai.ui.theme.IrisSpace.M)) {
                            if (state.text.isEmpty()) {
                                Text(if (state.agentMode) ComposerText.AGENT_PLACEHOLDER else ComposerText.PLACEHOLDER, style = IrisType.message.copy(lineHeight = IrisType.row.lineHeight), color = c.textTertiary)
                            }
                            inner()
                        }
                    },
                )
            androidx.compose.animation.AnimatedVisibility(
                visible = recording,
                enter = fadeIn(IrisMotion.enter()),
                exit = fadeOut(IrisMotion.exit()),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    ListeningIndicator(state.micLevel, state.transcribing, cancelArmed)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 1.10.2 bottom row: 思考 + speed on the left (they scroll on a very narrow screen instead
            // of squeezing the buttons), then — as before 1.10.1 — the press-and-hold mic and the
            // round action button (停止 / 发送 / 语音对话) on the right. No 「+」 menu any more.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(com.iris.localai.ui.theme.IrisSpace.XS),
            ) {
                Spacer(Modifier.width(0.dp))
                ComposerPill(
                    ComposerText.THINK,
                    selected = state.deepThinking,
                    enabled = state.thinkingSupported,
                    onClick = onToggleThinking,
                    onDisabledClick = onThinkingUnsupported,
                    icon = com.iris.localai.ui.icons.IrisIcons.Think,
                    modifier = Modifier.tourTarget(TourId.THINK),
                )
                SpeedChip(state.resourceTier, state.resourceNote, { composerTop }, onResourceTier)
                Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.XS))
            }
            // 2.2: one long accent capsule (users: the voice button was too small). Tap = 语音通话,
            // long-press = 语音输入 (slide up to cancel); with text / attachments 发送, while generating 停止.
            val action = ComposerActions.of(generating = state.generating, hasText = state.text.isNotBlank(), hasAttachments = state.attachments.isNotEmpty())
            Box(Modifier.tourTarget(TourId.VOICE)) { VoiceCapsuleButton(
                action = action,
                holding = state.listening,
                micLevel = state.micLevel,
                sendEnabled = !state.transcribing,
                cancelArmed = cancelArmed,
                onSend = onSend,
                onStop = onStop,
                onCall = onVoiceMode,
                generating = state.generating,
                onInterruptSend = onInterruptSend,
                onHoldStart = onHoldToTalkStart,
                onHoldEnd = onHoldToTalkEnd,
                onHoldCancel = onHoldToTalkCancel,
                onCancelArmed = { cancelArmed = it },
            ) }
        }
    }
}

@Composable
private fun ListeningIndicator(level: Float, transcribing: Boolean, cancelArmed: Boolean = false) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = com.iris.localai.ui.theme.IrisSpace.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(com.iris.localai.ui.theme.IrisSpace.S),
    ) {
        LevelBars(if (transcribing) 0f else level)
        Text(
            if (transcribing) ComposerText.TRANSCRIBING else if (cancelArmed) ComposerText.RELEASE_TO_CANCEL else ComposerText.LISTENING,
            style = IrisType.body, color = if (cancelArmed) c.danger else c.textSecondary,
            modifier = Modifier.pulse(transcribing),
        )
    }
}

/** Microphone level meter. One animated value, drawn in a Canvas: no recomposition per level change. */
@Composable
fun LevelBars(level: Float, bars: Int = 5, color: Color = Iris.colors.onAccentContainer) {
    val norm = (level * 12f).coerceIn(0f, 1f)
    val lv = animateFloatAsState(norm, com.iris.localai.ui.motion.motionSpec(IrisMotion.Meter), label = "level")
    Canvas(Modifier.size(width = (bars * 6 - 3).dp, height = 24.dp)) {
        val w = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val minH = 6.dp.toPx()
        val maxExtra = size.height - minH
        for (i in 0 until bars) {
            val shape = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / bars
            val h = minH + maxExtra * lv.value * shape
            drawRoundRect(
                color,
                topLeft = Offset(i * (w + gap), (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}

private val TIERS = com.iris.localai.engine.ResourceTier.entries

/**
 * 1.7.1: the composer sets how much of the phone inference may use (threads, which cores,
 * priority, batch size, whether 自动 may use the GPU); the model then runs as fast as that allows.
 * 1.10.1: shown as a speed — 低速 / 高速 / 极速 = the 省电 / 中 / 高 tier ([SpeedLevel]); same
 * setting, same behaviour. A grey pill with the current label opens the 1.7.6 slider card.
 */
@Composable
private fun SpeedChip(tier: Int, note: String?, composerTop: () -> Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val speed = SpeedLevel.ofTier(tier)
    Box(Modifier.tourTarget(TourId.SPEED)) {
        ComposerPill(
            speed.label, selected = false, toggle = false, onClick = { open = true },
            icon = Icons.Rounded.Bolt, trailingIcon = Icons.Rounded.KeyboardArrowDown,
            modifier = Modifier.semantics { contentDescription = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_b9fef6, ComposerText.SPEED, speed.label) },
        )
        if (open) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember(density) {
                    AbovePopupPosition(
                        gap = with(density) { 8.dp.roundToPx() },
                        margin = with(density) { 12.dp.roundToPx() },
                        avoidTop = composerTop,
                    )
                },
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                ResourceCard(tier, note, onChange)
            }
        }
    }
}

/**
 * 1.7.3: the resource card sits [gap] above the chip — and above the top of the composer, so it
 * never covers the text field — or [gap] below the chip when it does not fit above. Horizontally
 * it starts at the chip's left edge, clamped to [margin, window width − card width − margin].
 */
internal class AbovePopupPosition(
    private val gap: Int,
    private val margin: Int,
    private val avoidTop: () -> Int,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset = ResourcePopupMath.position(
        anchorLeft = anchorBounds.left, anchorTop = anchorBounds.top, anchorBottom = anchorBounds.bottom,
        avoidTop = avoidTop(), windowW = windowSize.width, windowH = windowSize.height,
        popupW = popupContentSize.width, popupH = popupContentSize.height, gap = gap, margin = margin,
    ).let { (x, y) -> androidx.compose.ui.unit.IntOffset(x, y) }
}

/** Pure placement maths for [AbovePopupPosition] (unit-tested). Returns (x, y) in window px. */
object ResourcePopupMath {
    fun position(
        anchorLeft: Int, anchorTop: Int, anchorBottom: Int, avoidTop: Int,
        windowW: Int, windowH: Int, popupW: Int, popupH: Int, gap: Int, margin: Int,
    ): Pair<Int, Int> {
        val maxX = (windowW - popupW - margin).coerceAtLeast(margin)
        val x = anchorLeft.coerceIn(margin, maxX)
        val top = minOf(anchorTop, avoidTop)
        val above = top - gap - popupH
        val y = if (above >= 0) above else (anchorBottom + gap).coerceAtMost((windowH - popupH).coerceAtLeast(0))
        return x to y
    }
}

/**
 * 1.7.6: the 1.7.4 slider card again, flatter: one line (bolt + tier name, short real status on the
 * right) over the original track. No thread / core / batch details, no reset button.
 */
@Composable
private fun ResourceCard(tier: Int, note: String?, onChange: (Int) -> Unit) {
    val c = Iris.colors
    val shape = com.iris.localai.ui.theme.smoothShape(IrisRadius.large)
    Column(
        Modifier
            .widthIn(max = minOf(300, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(160).dp)
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(1.dp, c.outline.copy(alpha = 0.6f), shape)
            .padding(start = com.iris.localai.ui.theme.IrisSpace.L, end = com.iris.localai.ui.theme.IrisSpace.L, top = com.iris.localai.ui.theme.IrisSpace.M, bottom = com.iris.localai.ui.theme.IrisSpace.S),
    ) {
        com.iris.localai.ui.components.TierSlider(
            labels = SpeedLevel.labels,
            index = tier.coerceIn(0, TIERS.lastIndex),
            onChange = onChange,
            status = note,
        )
    }
}

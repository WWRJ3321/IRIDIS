package com.iris.localai.ui.chat

import com.iris.localai.data.HomeItem
import com.iris.localai.data.HomeLayout
import androidx.compose.runtime.key

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.Attachment
import com.iris.localai.data.TurnRef
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.ui.components.IrisMenu
import com.iris.localai.ui.components.IrisMenuItem
import com.iris.localai.ui.components.TierSlider
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.IrisSpace
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
import androidx.compose.ui.draw.clip
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
import com.iris.localai.ui.theme.chatScaled
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.motion.motion

data class ComposerState(
    val text: String,
    val editing: Boolean,
    val generating: Boolean,
    val deepThinking: Boolean,
    val thinkingSupported: Boolean,
    /** 思考档位 (0 none chosen, 1 低, 2 中, 3 高). */
    val thinkLevel: Int = 0,
    val listening: Boolean,
    val micLevel: Float,
    val transcribing: Boolean,
    /** resource tier ordinal (低/中/高/全部) — how much of the phone inference may use. */
    val resourceTier: Int = ResourceTier.DEFAULT.ordinal,
    /** one short status of the last tier change, from what the engine really did (null = none). */
    val resourceNote: String? = null,
    /** 智能体 mode (tools on); false = 对话. */
    val agentMode: Boolean = false,
    /** files / pictures waiting to be sent with the next message. */
    val attachments: List<Attachment> = emptyList(),
    /** 2.2 「技能」: skills / tools picked for the next message (chips above the text). */
    val refs: List<TurnRef> = emptyList(),
    /** the draft is being translated (streams into [text]). */
    val translating: Boolean = false,
    /** the draft before 「翻译」 (non-null = 撤销翻译 shows). */
    val draftOriginal: String? = null,
)

/** the round action (empty input = 语音对话). 2.2: the face of the one voice capsule ([VoiceCapsule]). */
enum class ComposerAction { STOP, SEND, VOICE }

/** which round action shows (pure, unit-tested). */
/** 2.7.3/2.7.4：按住说话时输入框与「正在听」提示的关系（纯函数，单测覆盖）。 */
object DictationOverlay {
    /**
     * NONE = 没在录；FULL = 输入框为空，提示盖住输入框（原样）；
     * TEXT = 有文字：提示原地淡出（不位移、不占高度），输入框可见且不跳。
     */
    enum class Mode { NONE, FULL, TEXT }
    fun of(recording: Boolean, text: String): Mode = when {
        !recording -> Mode.NONE
        text.isEmpty() -> Mode.FULL
        else -> Mode.TEXT
    }
    /** 有文字时上滑到取消区：「松开取消」以叠层盖在输入框上（不改布局）。 */
    fun cancelOverlay(mode: Mode, cancelArmed: Boolean): Boolean = mode == Mode.TEXT && cancelArmed
}

object ComposerActions {
    /** 2.7.4：[recording]（按住说话 / 识别中）时保持语音样式，识别文字写进输入框也不变成「发送」，松手定稿后才按文字切换。 */
    fun of(generating: Boolean, hasText: Boolean, hasAttachments: Boolean = false, recording: Boolean = false): ComposerAction = when {
        recording && !generating -> ComposerAction.VOICE
        // typing while a reply runs sends (a 补充, or 「停/等等…」 interrupts); empty = 停止.
        hasText || hasAttachments -> ComposerAction.SEND
        generating -> ComposerAction.STOP
        else -> ComposerAction.VOICE
    }
}

/** composer texts (unit-tested). */
object ComposerText {
    val CHAT: String get() = I18n.s(R.string.chat_859362)
    val AGENT: String get() = I18n.s(R.string.chat_72fbb9)
    val PLACEHOLDER: String get() = I18n.s(R.string.chat_6f6822)
    val AGENT_PLACEHOLDER: String get() = I18n.s(R.string.chat_db0873)
    // minimal composer. 1.10.2: the 「+」 menu is gone — voice input is the press-and-hold
    // mic again (right of the pills) and 语音对话 is the round button when the input is empty.
    val THINK: String get() = I18n.s(R.string.chat_21d68b)
    val THINK_UNSUPPORTED: String get() = I18n.s(R.string.chat_e3d835)
    val LISTENING: String get() = I18n.s(R.string.chat_c8bcad)
    val RELEASE_TO_CANCEL: String get() = I18n.s(R.string.chat_1f31c5)
    val TRANSCRIBING: String get() = I18n.s(R.string.chat_0e89f9)
    val SPEED: String get() = I18n.s(R.string.chat_03f385)
    val ATTACH: String get() = I18n.s(R.string.chat_ca0b5e)
    val ATTACH_PHOTOS: String get() = I18n.s(R.string.chat_d2fb1e)
    val TAKE_PHOTO: String get() = I18n.s(R.string.b5_take_photo)
    val ATTACH_FILES: String get() = I18n.s(R.string.chat_2a0c47)
    // 2.2 composer: one voice capsule + 「技能」.
    val CALL_HOLD: String get() = I18n.s(R.string.chat_83c923)
    val SEND: String get() = I18n.s(R.string.chat_1535fc)
    val STOP: String get() = I18n.s(R.string.chat_dda4c0)
    val SKILLS: String get() = I18n.s(R.string.chat_699143)
    val SKILL_SEARCH: String get() = I18n.s(R.string.chat_74fa39)
    val SECTION_SKILLS: String get() = I18n.s(R.string.chat_46c945)
    val SECTION_TOOLS: String get() = I18n.s(R.string.chat_fb695c)
    val NO_SKILLS: String get() = I18n.s(R.string.chat_2ce87d)
    val NO_TOOLS: String get() = I18n.s(R.string.chat_05ae1f)
    val NO_MATCH: String get() = I18n.s(R.string.chat_fb08d1)
    val SKILL_TOOL_OFF: String get() = I18n.s(R.string.chat_568f9a)
    val SKILLS_EMPTY: String get() = I18n.s(R.string.chat_d9abfd)
    val AGENT_FOR_REF: String get() = I18n.s(R.string.chat_338074)
    fun removeRef(name: String) = I18n.s(R.string.chat_8506eb, name)
    fun agentNotice(on: Boolean): String =
        if (on) I18n.s(R.string.chat_fbf95c) else I18n.s(R.string.chat_e9e4d8)
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
    /** long-press 思考 → 0 关, 1 低, 2 中, 3 高. */
    onThinkLevel: (Int) -> Unit = {},
    onHoldToTalkStart: () -> Unit,
    onHoldToTalkEnd: () -> Unit,
    onVoiceMode: () -> Unit,
    onResourceTier: (Int) -> Unit = {},
    /** long-press 发送 while generating → 「打断并发送」. */
    onInterruptSend: () -> Unit = {},
    /** tap on the greyed 思考 pill (the model has no thinking) — show a hint. */
    onThinkingUnsupported: () -> Unit = {},
    /** 对话 ⇄ 智能体. */
    onToggleAgent: () -> Unit = {},
    /** 「附件」 menu — 照片 (system photo picker) / 文件 (document picker). */
    onPickPhotos: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    onRemoveAttachment: (Attachment) -> Unit = {},
    onMoveAttachment: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** a picture edited in the viewer (crop / rotate / doodle) replaces the attachment. */
    onReplaceAttachment: ((old: Attachment, new: Attachment) -> Unit)? = null,
    /** slide up while holding the capsule → the dictation is thrown away. */
    onHoldToTalkCancel: () -> Unit = {},
    /** 2.2 「技能」: what the sheet lists (read when it opens), and adding / removing a reference. */
    refCatalog: () -> RefCatalog = { RefCatalog.EMPTY },
    onAddRef: (TurnRef) -> Unit = {},
    onRemoveRef: (TurnRef) -> Unit = {},
    /** 「翻译」 the draft: target setting ("auto" / "zh" / "en"), and 撤销翻译. */
    onTranslateDraft: (String) -> Unit = {},
    onUndoTranslate: () -> Unit = {},
    /** 3.0.0 Beta6 自定布局. */
    layout: HomeLayout = HomeLayout.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val c = Iris.colors
    val shape = IrisRadius.composerShape
    val reduce = LocalReduceMotion.current
    var composerTop by remember { mutableStateOf(Int.MAX_VALUE) }
    Column(
        modifier
            .fillMaxWidth()
            // clip = false — the frosted glass clips itself to the outline path; a clipping
            // shadow layer could cut the blurred slice and leave a light fringe at the edge.
            .onGloballyPositioned { composerTop = it.boundsInWindow().top.toInt() }
            .glass(backdrop, shape)
            .border(IrisDimens.hairline + 0.1.dp, c.outline.copy(alpha = 0.55f), shape)
            .then(if (reduce) Modifier else Modifier.animateContentSize(IrisMotion.Snappy.size()))
            .padding(start = IrisSpace.S, end = IrisSpace.S, top = IrisSpace.XS, bottom = IrisSpace.S),
    ) {
        AnimatedVisibility(
            visible = state.editing,
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = IrisSpace.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(IrisSpace.XS))
                Text(I18n.s(R.string.chat_2fb441), style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                PressableIcon(Icons.Outlined.Close, I18n.s(R.string.chat_cbb465), size = 32.dp, iconSize = 16.dp, tint = c.textSecondary, onClick = onCancelEdit)
            }
        }

        AnimatedVisibility(
            visible = state.translating || state.draftOriginal != null,
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = IrisSpace.S), verticalAlignment = Alignment.CenterVertically) {
                Icon(IrisIcons.Translate, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(IrisSpace.XS))
                AnimatedContent(
                    state.translating, Modifier.weight(1f),
                    transitionSpec = { motion(reduce) { IrisMotion.crossfade() } }, label = "trLabel",
                ) { on -> Text(if (on) TranslateText.TRANSLATING else I18n.s(R.string.chat_9aa52a), style = IrisType.labelSmall, color = c.textSecondary) }
                if (state.translating) TypingDots()
                PressableIcon(Icons.Outlined.Close, TranslateText.UNDO, size = 32.dp, iconSize = 16.dp, tint = c.textSecondary, onClick = onUndoTranslate)
            }
        }

        // 3.0.0 Beta6 自定布局：上排 / 下排的按钮按 [layout] 摆在格子里（默认＝Beta5 的样子）。
        val recording = state.listening || state.transcribing
        var cancelArmed by remember { mutableStateOf(false) }
        var skillSheet by remember { mutableStateOf(false) }
        var catalog by remember { mutableStateOf(RefCatalog.EMPTY) }
        var attachMenu by remember { mutableStateOf(false) }
        if (skillSheet) SkillPickerSheet(catalog, onPick = { onAddRef(it); skillSheet = false }, onDismiss = { skillSheet = false })
        val renderItem: @Composable (HomeItem) -> Unit = { item ->
            when (item) {
                HomeItem.AGENT -> ComposerPill(
                    ComposerText.AGENT,
                    selected = state.agentMode,
                    onClick = onToggleAgent,
                    icon = IrisIcons.Agent,
                    compact = true,
                    modifier = Modifier.tourTarget(TourId.AGENT),
                )
                // 2.2 「技能」: pick an installed skill / agent tool for this message.
                HomeItem.SKILLS -> ComposerPill(
                    ComposerText.SKILLS,
                    selected = state.refs.isNotEmpty(),
                    toggle = false,
                    onClick = { catalog = refCatalog(); skillSheet = true },
                    icon = IrisIcons.Skill,
                    compact = true,
                    modifier = Modifier.tourTarget(TourId.SKILLS),
                )
                // attach pictures / files: a small IRIS menu first (user: not straight into the file manager).
                HomeItem.ATTACH -> Box {
                    PressableIcon(IrisIcons.Plus, ComposerText.ATTACH, modifier = Modifier.tourTarget(TourId.ATTACH), size = ComposerPillStyle.COMPACT_HEIGHT_DP.dp, iconSize = 22.dp, tint = c.textSecondary, onClick = { attachMenu = true })
                    IrisMenu(attachMenu, { attachMenu = false }) {
                        IrisMenuItem(ComposerText.ATTACH_PHOTOS, IrisIcons.Image) { attachMenu = false; onPickPhotos() }
                        IrisMenuItem(ComposerText.TAKE_PHOTO, IrisIcons.Camera) { attachMenu = false; onTakePhoto() }
                        IrisMenuItem(ComposerText.ATTACH_FILES, IrisIcons.Folder) { attachMenu = false; onPickFiles() }
                        // translate the draft before sending (default 中↔英 by detection).
                        if (state.text.isNotBlank() && !state.translating) {
                            for ((target, label) in listOf("auto" to TranslateText.DRAFT_AUTO, "zh" to TranslateText.DRAFT_ZH, "en" to TranslateText.DRAFT_EN)) {
                                IrisMenuItem(label, IrisIcons.Translate) { attachMenu = false; onTranslateDraft(target) }
                            }
                        }
                    }
                }
                HomeItem.THINK -> ThinkPill(
                    ComposerText.THINK,
                    thinking = state.deepThinking,
                    level = state.thinkLevel,
                    enabled = state.thinkingSupported,
                    onClick = onToggleThinking,
                    onDisabledClick = onThinkingUnsupported,
                    onPick = onThinkLevel,
                    icon = IrisIcons.Think,
                    modifier = Modifier.tourTarget(TourId.THINK),
                )
                HomeItem.SPEED -> SpeedChip(state.resourceTier, state.resourceNote, { composerTop }, onResourceTier)
                // one long accent capsule. Tap = 语音通话, long-press = 语音输入 (slide up to cancel);
                // with text / attachments 发送, while generating 停止.
                HomeItem.VOICE -> {
                    val action = ComposerActions.of(generating = state.generating, hasText = state.text.isNotBlank(), hasAttachments = state.attachments.isNotEmpty(), recording = recording)
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
                else -> {}
            }
        }
        if (!layout.isEmpty(HomeLayout.Row.UPPER)) {
            LayoutRow(layout, HomeLayout.Row.UPPER, Modifier.padding(start = IrisSpace.XS, top = IrisSpace.XS, end = IrisSpace.XS), renderItem)
        }
        AnimatedVisibility(
            visible = state.attachments.isNotEmpty(),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            AttachmentStrip(state.attachments, onRemove = onRemoveAttachment, onMove = onMoveAttachment, onReplace = onReplaceAttachment, modifier = Modifier.padding(start = IrisSpace.XS, top = IrisSpace.XS, end = IrisSpace.XS))
        }
        AnimatedVisibility(
            visible = state.refs.isNotEmpty(),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            RefChips(state.refs, onRemove = onRemoveRef, modifier = Modifier.padding(start = IrisSpace.XS, top = IrisSpace.XS, end = IrisSpace.XS))
        }

        // the text field stays in composition while dictating. Swapping it out (1.10–1.14.0)
        // dropped its focus, the keyboard closed, the composer jumped down by the IME height and the
        // finger ended up outside the mic button — which cancelled the hold and cut the dictation off.
        // 2.7.4: with dictated (or existing) text the field stays visible while recording and the
        // indicator just fades out in place (an overlay, no height of its own), so the field never jumps.
        val dictMode = DictationOverlay.of(recording, state.text)
        val fieldAlpha by animateFloatAsState(if (dictMode == DictationOverlay.Mode.FULL) 0f else 1f, IrisMotion.Color.float(), label = "fieldAlpha")
        Box(Modifier.tourTarget(TourId.INPUT)) {
                // Beta2: hold a TextFieldValue so text set from outside (dictation, translate, draft)
                // puts the cursor after the last character instead of leaving it at the start.
                var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(state.text, androidx.compose.ui.text.TextRange(state.text.length))) }
                val fieldValue = if (tfv.text == state.text) tfv
                    else androidx.compose.ui.text.input.TextFieldValue(state.text, androidx.compose.ui.text.TextRange(state.text.length))
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { v -> tfv = v; if (v.text != state.text) onTextChange(v.text) },
                    textStyle = IrisType.message.copy(color = c.text, lineHeight = IrisType.row.lineHeight).chatScaled(),
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
                        Box(Modifier.padding(horizontal = IrisSpace.S, vertical = IrisSpace.M)) {
                            if (state.text.isEmpty()) {
                                Text(if (state.agentMode) ComposerText.AGENT_PLACEHOLDER else ComposerText.PLACEHOLDER, style = IrisType.message.copy(lineHeight = IrisType.row.lineHeight).chatScaled(), color = c.textTertiary)
                            }
                            inner()
                        }
                    },
                )
            androidx.compose.animation.AnimatedVisibility(
                visible = dictMode == DictationOverlay.Mode.FULL,
                enter = fadeIn(IrisMotion.enter()),
                exit = fadeOut(IrisMotion.exit()),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    ListeningIndicator(state.micLevel, state.transcribing, cancelArmed)
                }
            }
            // 有文字时上滑取消：「松开取消」叠在输入框上（不占高度）。
            androidx.compose.animation.AnimatedVisibility(
                visible = DictationOverlay.cancelOverlay(dictMode, cancelArmed),
                enter = fadeIn(IrisMotion.enter()),
                exit = fadeOut(IrisMotion.exit()),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().clip(IrisRadius.mediumShape).background(c.surface.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ComposerText.RELEASE_TO_CANCEL, style = IrisType.body, color = c.danger)
                }
            }
        }

        if (!layout.isEmpty(HomeLayout.Row.LOWER)) {
            // the left side scrolls on a very narrow screen instead of squeezing the buttons.
            LayoutRow(layout, HomeLayout.Row.LOWER, Modifier, renderItem)
        }
    }
}

/**
 * 3.0.0 Beta6：一排格子。左边几格从左排起（窄屏时横向滚动），右边几格贴右。
 */
@Composable
private fun LayoutRow(layout: HomeLayout, row: HomeLayout.Row, modifier: Modifier, render: @Composable (HomeItem) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IrisSpace.XS),
        ) {
            layout.start(row).forEach { key(it) { render(it) } }
        }
        val end = layout.end(row)
        if (end.isNotEmpty()) {
            Spacer(Modifier.width(IrisSpace.XS))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IrisSpace.XS)) {
                end.forEach { key(it) { render(it) } }
            }
        }
    }
}

@Composable
private fun ListeningIndicator(level: Float, transcribing: Boolean, cancelArmed: Boolean = false) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = IrisSpace.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IrisSpace.S),
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

private val TIERS = ResourceTier.entries

/**
 * the composer sets how much of the phone inference may use (threads, which cores,
 * priority, batch size, whether 自动 may use the GPU); the model then runs as fast as that allows.
 * shown as a speed — 低速 / 高速 / 极速 = the 省电 / 中 / 高 tier ([SpeedLevel]); same
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
            modifier = Modifier.semantics { contentDescription = I18n.s(R.string.chat_b9fef6, ComposerText.SPEED, speed.label) },
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
 * the resource card sits [gap] above the chip — and above the top of the composer, so it
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
 * the 1.7.4 slider card again, flatter: one line (bolt + tier name, short real status on the
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
            .padding(start = IrisSpace.L, end = IrisSpace.L, top = IrisSpace.M, bottom = IrisSpace.S),
    ) {
        TierSlider(
            labels = SpeedLevel.labels,
            index = tier.coerceIn(0, TIERS.lastIndex),
            onChange = onChange,
            status = note,
        )
    }
}

package com.iris.localai.ui.chat

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
    const val CHAT = "对话"
    const val AGENT = "智能体"
    const val PLACEHOLDER = "给 IRIDIS 发送消息"
    const val AGENT_PLACEHOLDER = "交给 IRIDIS 智能体（可读写工作区文件）"
    // 1.10.1: minimal composer. 1.10.2: the 「+」 menu is gone — voice input is the press-and-hold
    // mic again (right of the pills) and 语音对话 is the round button when the input is empty.
    const val THINK = "思考"
    const val THINK_UNSUPPORTED = "当前模型不支持思考，换一个支持思考的模型后可开启"
    const val VOICE_INPUT = "语音输入"
    const val VOICE_MODE = "语音对话"
    const val HOLD_TO_TALK = "按住说话"
    const val LISTENING = "正在聆听，松开结束，上滑取消"
    const val RELEASE_TO_CANCEL = "松开取消"
    const val TRANSCRIBING = "正在识别…"
    const val SPEED = "速度"
    const val ATTACH = "添加图片或文件"
    const val ATTACH_PHOTOS = "照片"
    const val ATTACH_FILES = "文件"
    // 2.2 composer: one voice capsule + 「技能」.
    const val CALL_HOLD = "通话，长按语音输入"
    const val SEND = "发送"
    const val STOP = "停止生成"
    const val SKILLS = "技能"
    const val SKILL_SEARCH = "搜索技能或工具"
    const val SECTION_SKILLS = "已安装的技能"
    const val SECTION_TOOLS = "智能体工具"
    const val NO_SKILLS = "暂无技能"
    const val NO_TOOLS = "没有已开启的工具"
    const val NO_MATCH = "没有匹配项"
    const val SKILL_TOOL_OFF = "技能工具已关闭（设置 › 智能体）"
    const val SKILLS_EMPTY = "暂无可用的技能或工具"
    const val AGENT_FOR_REF = "已切换到智能体模式：本条消息会使用所选技能/工具"
    fun removeRef(name: String) = "移除 $name"
    fun agentNotice(on: Boolean): String =
        if (on) "已切换到智能体模式：可调用工具读写工作区文件（设置 › 智能体）" else "已切换到对话模式：不调用工具"
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
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
    ) {
        AnimatedVisibility(
            visible = state.editing,
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("编辑消息", style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                PressableIcon(Icons.Outlined.Close, "取消编辑", size = 32.dp, iconSize = 16.dp, tint = c.textSecondary, onClick = onCancelEdit)
            }
        }

        // 1.10.2: 「智能体」 is a small pill in the card's top-left corner, above the text field
        // (user: 「智能体放左上」). Same colours as the 1.10.1 pills, just smaller.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerPill(
                ComposerText.AGENT,
                selected = state.agentMode,
                onClick = onToggleAgent,
                icon = com.iris.localai.ui.icons.IrisIcons.Agent,
                compact = true,
            )
            // 2.2 「技能」: pick an installed skill / agent tool for this message.
            var skillSheet by remember { mutableStateOf(false) }
            var catalog by remember { mutableStateOf(RefCatalog.EMPTY) }
            Spacer(Modifier.width(6.dp))
            ComposerPill(
                ComposerText.SKILLS,
                selected = state.refs.isNotEmpty(),
                toggle = false,
                onClick = { catalog = refCatalog(); skillSheet = true },
                icon = com.iris.localai.ui.icons.IrisIcons.Skill,
                compact = true,
            )
            if (skillSheet) SkillPickerSheet(catalog, onPick = { onAddRef(it); skillSheet = false }, onDismiss = { skillSheet = false })
            Spacer(Modifier.weight(1f))
            // 1.10.12: attach pictures / files — top-right, opposite 智能体, so the bottom row keeps its layout.
            // 1.10.13: a small IRIS menu first (user: not straight into the file manager).
            // 2.2: a 「+」 as tall as the chips, its right edge on the capsule's right edge.
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                PressableIcon(com.iris.localai.ui.icons.IrisIcons.Plus, ComposerText.ATTACH, size = ComposerPillStyle.COMPACT_HEIGHT_DP.dp, iconSize = 22.dp, tint = c.textSecondary, onClick = { attachMenu = true })
                com.iris.localai.ui.components.IrisMenu(attachMenu, { attachMenu = false }) {
                    com.iris.localai.ui.components.IrisMenuItem(ComposerText.ATTACH_PHOTOS, com.iris.localai.ui.icons.IrisIcons.Image) { attachMenu = false; onPickPhotos() }
                    com.iris.localai.ui.components.IrisMenuItem(ComposerText.ATTACH_FILES, com.iris.localai.ui.icons.IrisIcons.Folder) { attachMenu = false; onPickFiles() }
                }
            }
        }
        AnimatedVisibility(
            visible = state.attachments.isNotEmpty(),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            AttachmentStrip(state.attachments, onRemove = onRemoveAttachment, onMove = onMoveAttachment, modifier = Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp))
        }
        AnimatedVisibility(
            visible = state.refs.isNotEmpty(),
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            RefChips(state.refs, onRemove = onRemoveRef, modifier = Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp))
        }

        // 1.14.1: the text field stays in composition while dictating. Swapping it out (1.10–1.14.0)
        // dropped its focus, the keyboard closed, the composer jumped down by the IME height and the
        // finger ended up outside the mic button — which cancelled the hold and cut the dictation off.
        val recording = state.listening || state.transcribing
        var cancelArmed by remember { mutableStateOf(false) }
        val fieldAlpha by animateFloatAsState(if (recording) 0f else 1f, IrisMotion.Color.float(), label = "fieldAlpha")
        Box {
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
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 11.dp)) {
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Spacer(Modifier.width(0.dp))
                ComposerPill(
                    ComposerText.THINK,
                    selected = state.deepThinking,
                    enabled = state.thinkingSupported,
                    onClick = onToggleThinking,
                    onDisabledClick = onThinkingUnsupported,
                    icon = com.iris.localai.ui.icons.IrisIcons.Think,
                )
                SpeedChip(state.resourceTier, state.resourceNote, { composerTop }, onResourceTier)
                Spacer(Modifier.width(4.dp))
            }
            // 2.2: one long accent capsule (users: the voice button was too small). Tap = 语音通话,
            // long-press = 语音输入 (slide up to cancel); with text / attachments 发送, while generating 停止.
            val action = ComposerActions.of(generating = state.generating, hasText = state.text.isNotBlank(), hasAttachments = state.attachments.isNotEmpty())
            VoiceCapsuleButton(
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
            )
        }
    }
}

@Composable
private fun ListeningIndicator(level: Float, transcribing: Boolean, cancelArmed: Boolean = false) {
    val c = Iris.colors
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
    Box {
        ComposerPill(
            speed.label, selected = false, toggle = false, onClick = { open = true },
            icon = Icons.Rounded.Bolt, trailingIcon = Icons.Rounded.KeyboardArrowDown,
            modifier = Modifier.semantics { contentDescription = "${ComposerText.SPEED}：${speed.label}" },
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
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
    ) {
        com.iris.localai.ui.components.TierSlider(
            labels = SpeedLevel.labels,
            index = tier.coerceIn(0, TIERS.lastIndex),
            onChange = onChange,
            status = note,
        )
    }
}

package com.iris.localai.ui.chat

import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Check
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

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenImageGen: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit,
    onOpenApi: () -> Unit,
) {
    val c = Iris.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    // 2.1.0: feedback when a reply finishes, only while the app is in the foreground.
    val feedback = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            vm.replyFinished.collect { ok -> feedback.perform(if (ok) com.iris.localai.ui.feedback.FeedbackEvent.REPLY_DONE else com.iris.localai.ui.feedback.FeedbackEvent.ERROR) }
        }
    }
    val currentPlan by vm.plan.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val guiSetup by vm.guiSetup.collectAsStateWithLifecycle()
    /** 1.10.2: last load per model / backend (model sheet: 「上次加载 N 秒 · 已用缓存」). */
    val loadRecords by vm.loadStats.collectAsStateWithLifecycle()
    val tierApply by vm.tierApply.collectAsStateWithLifecycle()
    val speakingId by vm.speakingMessageId.collectAsStateWithLifecycle()
    // Read only inside the streaming message (and the follow loop), never here.
    val streamState = vm.stream.collectAsStateWithLifecycle()
    // 1.7.7: 「正在重载模型…」 in the waiting reply's bubble while a tier reload finishes.
    val replyStatus by vm.replyStatus.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val warming by vm.warming.collectAsStateWithLifecycle()
    val micLevel by vm.dictation.level.collectAsStateWithLifecycle()
    // 1.9.0: 对话 / 智能体 and a tool waiting for the user's permission.
    val agentSettings by vm.agentSettings.collectAsStateWithLifecycle()
    val permissionAsk by vm.permissionAsk.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    val backgroundRunId by vm.backgroundRunId.collectAsStateWithLifecycle()
    val apiStatus by ApiService.status.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val backdrop = rememberGlassBackdrop()
    // 2.2.4: Haze backdrop of the floating glass (top bar, model menu, drawer, sheets, dialogs).
    val haze = remember { dev.chrisbanes.haze.HazeState() }

    var input by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    // 2.0.x: 「记住这条」 dialog for this message (null = closed).
    var remembering by remember { mutableStateOf<com.iris.localai.data.ChatMessage?>(null) }
    // 1.10.12: attachments waiting for the next send (already copied into app storage).
    var pending by remember { mutableStateOf<List<com.iris.localai.data.Attachment>>(emptyList()) }
    /** 2.2 「技能」: references picked for the next message. */
    var refs by remember { mutableStateOf<List<com.iris.localai.data.TurnRef>>(emptyList()) }
    fun addPicked(uris: List<android.net.Uri>) {
        if (uris.isNotEmpty()) scope.launch {
            val room = com.iris.localai.util.Attachments.MAX_PER_MESSAGE - pending.size
            if (room <= 0) { vm.showNotice("每条消息最多 ${com.iris.localai.util.Attachments.MAX_PER_MESSAGE} 个附件"); return@launch }
            pending = pending + vm.importAttachments(uris.take(room))
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addPicked(it) }
    // 2.1.7 生图 「发到聊天」: the generated PNG joins the composer's attachments.
    val incomingImage by vm.incomingImage.collectAsStateWithLifecycle()
    LaunchedEffect(incomingImage) {
        val path = incomingImage ?: return@LaunchedEffect
        vm.consumeIncomingImage()
        if (pending.size >= com.iris.localai.util.Attachments.MAX_PER_MESSAGE) { vm.showNotice("每条消息最多 ${com.iris.localai.util.Attachments.MAX_PER_MESSAGE} 个附件"); return@LaunchedEffect }
        vm.importLocalImage(path)?.let { pending = pending + it }
    }
    // 1.10.13: 照片 opens the system photo picker (no storage permission needed).
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.iris.localai.util.Attachments.MAX_PER_MESSAGE),
    ) { addPicked(it) }
    /** 1.10.6: 「查看提示内容」 text (null = closed). */
    var promptView by remember { mutableStateOf<String?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    // 2.2.7: the model pill morphs into the model panel (container transform).
    var modelMenu by remember { mutableStateOf(false) }
    var pillBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val modelMorph = remember { Animatable(0f) }
    var configureModel by remember { mutableStateOf<com.iris.localai.data.LocalModel?>(null) }
    var showContext by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    val reduce = LocalReduceMotion.current
    /** One place that drops the keyboard, so overlays and gestures never fight the IME animation. */
    fun hideKeyboard() = focusManager.clearFocus(force = true)
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var topBarHeight by remember { mutableIntStateOf(0) }
    var composerHeight by remember { mutableIntStateOf(0) }

    // Keep the screen awake only while text is actually being generated.
    val view = LocalView.current
    DisposableEffect(ui.isGenerating, settings.keepScreenOn) {
        view.keepScreenOn = ui.isGenerating && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val voiceStates by vm.voiceStore.states.collectAsStateWithLifecycle()
    val asrReady = remember(voiceStates, settings.asrPack) { vm.voiceStore.active(com.iris.localai.voice.VoiceKind.ASR, settings.asrPack) != null }
    var pendingVoiceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingVoiceAction?.invoke()
        pendingVoiceAction = null
    }
    // Background generation shows its progress in a notification: ask for that permission once.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // 1.10.13: once, on a later send (never stacked on the notification prompt): 允许后台运行.
    fun maybeAskBattery() {
        if (!settings.backgroundGeneration || settings.askedBatteryExemption) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && !settings.askedNotificationPermission) return
        if (com.iris.localai.util.BackgroundGuard.isExempt(context)) return
        vm.markBatteryAsked()
        runCatching { context.startActivity(com.iris.localai.util.BackgroundGuard.requestIntent(context)) }
    }
    fun maybeAskNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || !settings.backgroundGeneration || settings.askedNotificationPermission) return
        vm.markNotificationAsked()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun withMic(action: () -> Unit) {
        if (!asrReady) { onOpenVoiceSettings(); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingVoiceAction = action; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    androidx.compose.runtime.CompositionLocalProvider(com.iris.localai.ui.components.LocalGlassHaze provides haze) {
    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = com.iris.localai.ui.components.LARGE_SCRIM,
        drawerContent = {
            HistoryDrawer(
                summaries = summaries,
                currentId = ui.conversation.id,
                apiRunning = apiStatus.running,
                onNewChat = { vm.newChat(); scope.launch { drawer.close() } },
                onOpen = { vm.open(it); scope.launch { drawer.close() } },
                onRename = vm::rename,
                onPin = vm::togglePin,
                onDelete = vm::delete,
                onModels = { scope.launch { drawer.close() }; onOpenModels() },
                onSettings = { scope.launch { drawer.close() }; onOpenSettings() },
                onApi = { scope.launch { drawer.close() }; onOpenApi() },
                onFiles = { scope.launch { drawer.close() }; onOpenFiles() },
                onImageGen = { scope.launch { drawer.close() }; onOpenImageGen() },
                runningId = backgroundRunId,
                open = drawer.targetValue == DrawerValue.Open,
            )
        },
    ) {
        LaunchedEffect(drawer.targetValue) { if (drawer.targetValue == DrawerValue.Open) hideKeyboard() }
        Box(Modifier.fillMaxSize().background(c.chatBackground)) {
            val messages = ui.conversation.messages
            val listState = rememberLazyListState()
            val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
            // Follow the newest text while the user hasn't scrolled away from the bottom.
            var follow by rememberSaveable { mutableStateOf(true) }
            // What the list has already been scrolled for, saved so that coming back from another
            // screen restores the reading position instead of jumping to the bottom again.
            var landedOn by rememberSaveable { mutableStateOf<String?>(null) }
            var seenCount by rememberSaveable { mutableIntStateOf(-1) }
            LaunchedEffect(listState) {
                listState.interactionSource.interactions.collect { i ->
                    when (i) {
                        is DragInteraction.Start -> { follow = false; hideKeyboard() }
                        is DragInteraction.Stop,
                        is DragInteraction.Cancel -> follow = !listState.canScrollForward
                    }
                }
            }
            LaunchedEffect(listState, atBottom) { if (atBottom && !listState.isScrollInProgress) follow = true }
            suspend fun scrollToEnd(animated: Boolean) {
                val last = listState.layoutInfo.totalItemsCount - 1
                if (last < 0) return
                if (animated) listState.animateScrollToItem(last) else listState.scrollToItem(last)
                listState.scroll { scrollBy(1_000_000f) } // clamp to the very end of a tall last item
            }
            // 1.13.1: one smooth spring to the end. animateScrollToItem lands on the item's TOP and
            // the clamp then jumped down — the "auto scroll + bounce" the user saw on every send.
            suspend fun glideToEnd() {
                androidx.compose.runtime.withFrameNanos { } // let the new items lay out first
                val last = listState.layoutInfo.totalItemsCount - 1
                if (last < 0) return
                val d = FollowScroll.distanceToEnd(listState.layoutInfo)
                if (d == null) { scrollToEnd(false); return }
                if (d > 0.5f) {
                    if (reduce) listState.scroll { scrollBy(d) }
                    else listState.animateScrollBy(d, com.iris.localai.ui.motion.IrisMotion.Smooth.float())
                }
            }
            // Opening a conversation lands on its newest message.
            LaunchedEffect(ui.conversation.id) {
                if (landedOn != ui.conversation.id) {
                    landedOn = ui.conversation.id
                    seenCount = messages.size
                    follow = true
                    scrollToEnd(false)
                }
            }
            // A new message (sent by the user or a new reply) always brings the bottom into view.
            LaunchedEffect(messages.size) {
                val grew = messages.size > seenCount && seenCount >= 0
                seenCount = messages.size
                if (grew && messages.isNotEmpty()) { follow = true; glideToEnd() }
            }
            // Streaming text and keyboard/composer resizes keep the bottom pinned while following.
            val lastLen = messages.lastOrNull()?.content?.length ?: 0
            // Only nudge the offset while the last item is on screen: re-anchoring with scrollToItem
            // on every token makes the list jump to the item's top for a frame, which flickers.
            LaunchedEffect(lastLen, composerHeight) {
                // 1.13.1: while a reply streams, the glide loop below owns the scroll; two writers
                // (this jump + that glide) fighting each other made the list bounce.
                if (ui.streamingId != null) return@LaunchedEffect
                if (!follow || messages.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
                val lastIdx = listState.layoutInfo.totalItemsCount - 1
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == lastIdx
                if (lastVisible) listState.scroll { scrollBy(1_000_000f) } else scrollToEnd(false)
            }

            // While a reply streams: glide to the bottom a fraction of the remaining distance per
            // frame instead of jumping a line at a time. Stops following as soon as the user drags.
            val streamingNow = ui.streamingId != null
            LaunchedEffect(streamingNow, reduce) {
                if (!streamingNow) return@LaunchedEffect
                // 1.13.0: frames are requested only while there is distance to glide; when idle the
                // loop sleeps (a frame request every vsync kept a 120 Hz panel at full rate all reply).
                while (true) {
                    if (!follow || listState.isScrollInProgress || messages.isEmpty()) { kotlinx.coroutines.delay(48); continue }
                    val d = FollowScroll.distanceToEnd(listState.layoutInfo)
                    when {
                        d == null -> { scrollToEnd(false); kotlinx.coroutines.delay(16) } // last item off screen: re-anchor once
                        d > 0.5f -> androidx.compose.runtime.withFrameNanos { listState.dispatchRawDelta(FollowScroll.step(d, reduce)) }
                        else -> kotlinx.coroutines.delay(48)
                    }
                }
            }

            // ---- Message list: top-down, newest at the bottom ----
            Box(Modifier.fillMaxSize().glassBackdrop(haze).glassSource(backdrop, Iris.colors.chatBackground)) {
                // 2.1.4 外观 › 自定义背景 (inside the glass source so the composer glass blurs it too).
                com.iris.localai.ui.theme.ChatBackdropImage(settings.backgroundImage, settings.backgroundBlur, settings.backgroundScrim)
                if (messages.isEmpty()) {
                    if (settings.backgroundImage.isEmpty()) com.iris.localai.ui.components.HomeAmbientGlow()
                    ChatEmptyState(
                        modelName = ui.readyModel?.displayName,
                        engine = ui.engine,
                        topPadding = with(density) { topBarHeight.toDp() },
                        bottomPadding = with(density) { composerHeight.toDp() },
                    ) { prompt -> input = prompt; runCatching { inputFocus.requestFocus() } }
                } else {
                    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 1.12.0: centred readable column on tablets / foldables / landscape.
                        contentPadding = PaddingValues(
                            top = with(density) { topBarHeight.toDp() } + 8.dp,
                            bottom = with(density) { composerHeight.toDp() } + 16.dp,
                            start = com.iris.localai.ui.components.readableSidePadding(),
                            end = com.iris.localai.ui.components.readableSidePadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(messages, key = { it.id }, contentType = { it.role }) { m ->
                            // Appear is handled by MessageRow (new messages only); animateItem only moves / removes.
                            MessageRow(
                                m,
                                if (reduce) Modifier else Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = IrisMotion.Gentle.offset(),
                                    fadeOutSpec = IrisMotion.standard(),
                                ),
                            ) {
                                Column {
                                if (m.role == Role.USER) {
                                    UserMessage(
                                        m,
                                        onEdit = { editingId = m.id; input = m.content; scope.launch { delay(50); runCatching { inputFocus.requestFocus() } } },
                                        onRemember = { remembering = m },
                                        onSwitchVersion = { v -> vm.switchVersion(m.id, v) },
                                    )
                                } else {
                                    AssistantMessage(
                                        message = m,
                                        streaming = m.id == ui.streamingId,
                                        isLast = m.id == lastAssistantId,
                                        expectThinking = ui.supportsThinking && ui.deepThinking,
                                        speaking = speakingId == m.id,
                                        onRegenerate = vm::regenerate,
                                        onSpeak = { vm.speak(m.id) },
                                        onStopSpeaking = vm::stopSpeaking,
                                        streamText = { streamState.value?.takeIf { it.id == m.id }?.text },
                                        status = if (m.id == ui.streamingId) replyStatus else null,
                                        onShowPrompt = { promptView = vm.promptText(m.id) },
                                        // 2.0.x: edit the reply in place (same composer edit mode as a user message).
                                        onEdit = {
                                            editingId = m.id
                                            input = com.iris.localai.util.ThinkParser.parse(m.content).answer
                                            scope.launch { delay(50); runCatching { inputFocus.requestFocus() } }
                                        },
                                        onRemember = { remembering = m },
                                        onSwitchVersion = { v -> vm.switchVersion(m.id, v) },
                                    )
                                }
                                if (ui.context.compactedMessages > 0 && m.id == ui.conversation.compaction?.upToId) {
                                    Spacer(Modifier.height(16.dp))
                                    CompactionDivider(ui.context.compactedMessages, onClear = vm::clearCompaction)
                                }
                                if (m.id == ui.conversation.contextStartAfterId) {
                                    Spacer(Modifier.height(16.dp))
                                    ContextBreakDivider(onClear = vm::clearContextBreak)
                                }
                                }
                            }
                        }
                        // 2.1.7: messages sent while a reply runs (补充), answered / read next; tap = cancel.
                        items(queued.filter { it.convId == ui.conversation.id }, key = { "queued-" + it.id }) { q ->
                            QueuedBubble(q, if (reduce) Modifier else Modifier.animateItem(fadeInSpec = IrisMotion.standard(), placementSpec = IrisMotion.Gentle.offset(), fadeOutSpec = IrisMotion.standard())) {
                                vm.cancelQueued(q.id)
                            }
                        }
                    }
                }
            }

            // ---- Top bar: solid, no translucent scrim over the messages (user request, 1.5.0) ----
            // 2.1.5: with a custom background the bar (status-bar area included) is see-through like
            // the cards, so the picture runs full-bleed to the top edge instead of being cut off.
            val hasBgImage = settings.backgroundImage.isNotEmpty()
            // 2.2.4: Kimi-style floating top bar — no flat full-width bar; two round buttons and a
            // model pill float on a transparent background so the chat scrolls under them.
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeight = it.height }
                    .statusBarsPadding(),
            ) {
                // 2.2.8 (HIG): strict symmetry — identical 42 dp side buttons at the same 16 dp edge
                // inset, the model pill centred on the screen like a nav title, never closer than
                // 8 dp to either button.
                Box(
                    Modifier.fillMaxWidth().height(IrisDimens.topBarHeight + 8.dp).padding(horizontal = IrisDimens.screenMargin),
                    contentAlignment = Alignment.Center,
                ) {
                    com.iris.localai.ui.components.FloatingIconButton(Icons.Outlined.Menu, "历史对话", Modifier.align(Alignment.CenterStart), size = IrisDimens.topBarControl) { hideKeyboard(); scope.launch { drawer.open() } }
                    ModelTitle(
                        ui.engine,
                        Modifier
                            .padding(horizontal = IrisDimens.topBarControl + IrisDimens.space8)
                            .onGloballyPositioned { pillBounds = it.boundsInRoot() }
                            // hidden while the morphing surface stands in for it
                            .graphicsLayer { alpha = if (modelMenu || modelMorph.value > 0f) 0f else 1f },
                    ) { hideKeyboard(); modelMenu = true }
                    com.iris.localai.ui.components.FloatingIconButton(Icons.Outlined.EditNote, "新对话", Modifier.align(Alignment.CenterEnd), size = IrisDimens.topBarControl, enabled = messages.isNotEmpty()) { vm.newChat() }
                }
                // 2.1.3: the hairline progress bar under the top bar is gone — the pill above the
                // composer (with % and 「取消」) is the one place that shows a model load.
            }

            // ---- Performance overlay (opt-in, only while generating) ----
            if (settings.perfOverlay) {
                PerfOverlay(
                    vm, generating = ui.isGenerating,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = with(density) { topBarHeight.toDp() } + 6.dp, end = 10.dp),
                )
            }

            // ---- Scroll-to-bottom ----
            AnimatedVisibility(
                visible = !atBottom && messages.isNotEmpty(),
                enter = fadeIn(IrisMotion.enter()) + scaleIn(IrisMotion.enter(), initialScale = 0.8f),
                exit = fadeOut(IrisMotion.standard()) + scaleOut(IrisMotion.standard(), targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { composerHeight.toDp() } + 8.dp),
            ) {
                PressableIcon(
                    Icons.Outlined.KeyboardArrowDown, "回到底部", size = 40.dp, iconSize = 22.dp,
                    modifier = Modifier.glass(backdrop, CircleShape, GlassStyle(tint = c.glassTintOpaque)),
                ) { follow = true; scope.launch { scrollToEnd(true) } }
            }

            // 1.12.0: the gesture-bar strip under the composer is opaque, so the end of a reply never
            // shows through below the composer card. 2.1.5: it is the page colour (no different-coloured
            // strip), and with a custom background it is not drawn at all — the picture runs under the
            // home pill; the composer still keeps navigationBarsPadding so it sits above the pill.
            if (!hasBgImage) Spacer(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsBottomHeight(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                    .background(c.chatBackground),
            )
            // ---- Composer (frosted glass) ----
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { composerHeight = it.height }
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = com.iris.localai.ui.components.readableSidePadding(com.iris.localai.ui.components.Adaptive.chatMaxWidth + 24.dp))
                    .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space8),
            ) {
                SlowLoadBanner(ui.engine, backdrop = backdrop, onUseCpu = vm::switchLoadingModelToCpu)
                NoticeBanner(ui.notice, backdrop = backdrop, onDismiss = vm::dismissNotice)
                // 1.14.0 计划卡片 (kept until the plan is done or cleared).
                com.iris.localai.ui.agent.PlanCard(currentPlan, backdrop, onClear = vm::clearPlan)
                // 1.10.10: the activity indicator shares the context meter's row right above the
                // composer (no extra height, so nothing above it moves when it appears).
                val showMeter = messages.isNotEmpty() && ui.context.limit > 0
                val act = if (ui.isGenerating) activity else warming
                if (showMeter || ui.engine is EngineState.Loading || act != null) {
                    Row(
                        // 1.12.0: the 1.11.1 fade band behind this row is gone (user: too abrupt); only
                        // the frosted capsules themselves cover what scrolls underneath.
                        Modifier.fillMaxWidth().heightIn(min = IrisDimens.activityRowHeight).padding(bottom = 2.dp), // 1.12.0: grows with large font
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActivityIndicator(ui.engine, act, backdrop, onCancelLoad = vm::cancelLoad, modifier = Modifier.weight(1f))
                        // 1.10.10b: frosted capsule so messages scrolling underneath never show through the text.
                        if (showMeter) ContextMeter(ui.context, onClick = { showContext = true }, modifier = Modifier.glass(backdrop, IrisRadius.pillShape, GlassStyle(tint = Iris.colors.glassTintOpaque)))
                    }
                }
                Composer(
                    focusRequester = inputFocus,
                    state = ComposerState(
                        text = input,
                        editing = editingId != null,
                        generating = ui.isGenerating,
                        deepThinking = ui.deepThinking,
                        thinkingSupported = ui.supportsThinking,
                        listening = listening,
                        micLevel = micLevel,
                        transcribing = transcribing,
                        resourceTier = settings.resourceTier,
                        resourceNote = vm.resourceStatus(tierApply, ui.engine),
                        agentMode = agentSettings.agentMode,
                        attachments = pending,
                        refs = refs,
                    ),
                    refCatalog = vm::refCatalog,
                    onAddRef = { r -> refs = com.iris.localai.data.TurnRefs.add(refs, r) },
                    onRemoveRef = { r -> refs = refs - r },
                    onHoldToTalkCancel = { if (listening) { listening = false; vm.dictation.cancel() } },
                    onPickPhotos = { pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onRemoveAttachment = { a -> pending = pending - a; vm.discardAttachments(listOf(a)) },
                    onMoveAttachment = { from, to -> pending = AttachmentOrder.move(pending, from, to) },
                    onToggleAgent = {
                        val on = !agentSettings.agentMode
                        vm.setAgentMode(on)
                        vm.showNotice(ComposerText.agentNotice(on))
                    },
                    onThinkingUnsupported = { vm.showNotice(ComposerText.THINK_UNSUPPORTED) },
                    backdrop = backdrop,
                    onResourceTier = vm::setResourceTier,
                    onTextChange = { input = it; vm.onUserActivity() },
                    onSend = {
                        val askedBefore = settings.askedNotificationPermission || android.os.Build.VERSION.SDK_INT < 33
                        maybeAskNotifications()
                        if (askedBefore) maybeAskBattery()
                        val id = editingId
                        // 2.0.x: editing a reply saves an edited version; editing a user message re-sends it.
                        val editingReply = id != null && ui.conversation.messages.firstOrNull { it.id == id }?.role == Role.ASSISTANT
                        if (id != null) { if (editingReply) vm.editAssistant(id, input) else vm.editAndResend(id, input) }
                        else { vm.send(input, pending, refs); pending = emptyList(); refs = emptyList() }
                        feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.SEND)
                        hideKeyboard()
                        input = ""
                        editingId = null
                    },
                    onStop = vm::stop,
                    // 2.1.7: long-press 发送 while generating → stop the reply, this is a new turn.
                    onInterruptSend = {
                        vm.send(input, pending, refs, interrupt = true); pending = emptyList(); refs = emptyList()
                        feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.SEND)
                        hideKeyboard(); input = ""; editingId = null
                    },
                    onCancelEdit = { editingId = null; input = "" },
                    onToggleThinking = { vm.setDeepThinking(!ui.deepThinking) },
                    onHoldToTalkStart = { /* 1.13.1: keep the keyboard (user: 语音输入会中断键盘) */ withMic { listening = true; vm.dictation.warmLanguage = settings.asrLanguage; vm.dictation.start() } },
                    onHoldToTalkEnd = {
                        if (listening) {
                            listening = false
                            transcribing = true
                            scope.launch {
                                val text = try {
                                    vm.dictation.finish(settings.asrLanguage)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    android.util.Log.w("ChatScreen", "dictation failed", t)
                                    vm.showNotice("语音识别出错：${t.message ?: t.javaClass.simpleName}")
                                    ""
                                } finally {
                                    transcribing = false
                                }
                                if (text.isNotBlank()) input = if (input.isBlank()) text else "$input $text"
                            }
                        }
                    },
                    onVoiceMode = { hideKeyboard(); withMic { voiceMode = true } },
                )
            }

            AnimatedVisibility(
                visible = voiceMode,
                enter = if (reduce) fadeIn(snap()) else fadeIn(IrisMotion.enter()) + scaleIn(IrisMotion.enter(), initialScale = 0.96f),
                exit = if (reduce) fadeOut(snap()) else fadeOut(IrisMotion.exit()) + scaleOut(IrisMotion.exit(), targetScale = 0.97f),
            ) {
                VoiceOverlay(vm = vm, onClose = { voiceMode = false })
            }
        }

            // ---- 2.2.7: model pill → panel morph, above everything in this screen ----
            val reduceMorph = LocalReduceMotion.current
            LaunchedEffect(modelMenu) {
                modelMorph.animateTo(
                    if (modelMenu) 1f else 0f,
                    if (reduceMorph) tween(120) else tween(300, easing = IrisMotion.ProgressEasing),
                )
            }
            androidx.activity.compose.BackHandler(enabled = modelMenu) { modelMenu = false }
            val pb = pillBounds
            if (pb != null && (modelMenu || modelMorph.value > 0f)) {
                val current = (ui.engine as? EngineState.Ready)?.model?.path ?: (ui.engine as? EngineState.Loading)?.model?.path
                ModelMorphPanel(
                    progress = { modelMorph.value },
                    expanded = modelMenu,
                    pillInRoot = pb,
                    engine = ui.engine,
                    models = models,
                    currentPath = current,
                    onDismiss = { modelMenu = false },
                    onPick = { m ->
                        modelMenu = false
                        val locked = ui.isGenerating || backgroundRunId != null
                        if (!locked && m.path != current) vm.loadModel(m)
                    },
                    onTiers = { modelMenu = false; showPicker = true },
                    onManage = { modelMenu = false; onOpenModels() },
                    // 2.2.9: free the model's memory; disabled while a reply / background run is going.
                    unloadEnabled = !(ui.isGenerating || backgroundRunId != null),
                    onUnload = { modelMenu = false; vm.unloadModel() },
                )
            }
    }
    }

    if (showPicker) {
        ModelPickerSheet(
            models = models,
            engine = ui.engine,
            busy = ui.isGenerating || backgroundRunId != null,
            onPick = { vm.loadModel(it) },
            onUnload = { vm.unloadModel(); showPicker = false },
            onManage = { showPicker = false; onOpenModels() },
            onDismiss = { showPicker = false },
            resourceTier = settings.resourceTier,
            onResourceTier = vm::setResourceTier,
            preferredBackend = { m -> vm.preferredBackendLabel(m) },
            onConfigure = { m -> configureModel = m },
            lastLoad = { m -> com.iris.localai.engine.LoadTelemetry.latest(loadRecords.values, m.id)?.let(com.iris.localai.engine.LoadTelemetry::summary) },
            // 1.10.3: 「建议：这个模型在 CPU 上加载更快（可选）」 from the load records.
            cpuFaster = { m -> com.iris.localai.engine.CpuFasterHint.shouldShow(loadRecords.values, m.id, vm.preferredBackendLabel(m)) },
            onSwitchToCpu = vm::switchModelToCpu,
            engineTag = { m -> vm.engineTag(m) },
            onCancelLoad = vm::cancelLoad,
        )
    }
    configureModel?.let { m -> com.iris.localai.ui.models.ModelConfigSheet(m, onDismiss = { configureModel = null }) }
    remembering?.let { m ->
        RememberDialog(
            initial = vm.memoryEntryFor(m),
            suggested = vm.suggestMemoryTarget(vm.memoryEntryFor(m)),
            onSave = { text, target -> vm.rememberText(text, target) },
            onDismiss = { remembering = null },
        )
    }
    promptView?.let { text ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { promptView = null }, title = "发送给模型的提示内容",
            confirmText = "关闭", onConfirm = { promptView = null },
            dismissText = "复制", onDismissClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)) },
        ) {
            Box(
                Modifier.fillMaxWidth().clip(com.iris.localai.ui.theme.IrisRadius.smallShape).background(com.iris.localai.ui.theme.Iris.colors.codeBackground)
                    .heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(10.dp),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = com.iris.localai.ui.theme.IrisType.monoSmall, color = com.iris.localai.ui.theme.Iris.colors.text)
                }
            }
        }
    }
    // 2.1.9: `screen` was blocked → the matching guide (无障碍 / 受限设置 / 识图模型).
    guiSetup?.let { b -> com.iris.localai.ui.agent.GuiSetupDialog(b, onDismiss = vm::dismissGuiSetup, onPickModel = { vm.dismissGuiSetup(); showPicker = true }) }
    // 1.13.0: a tool of the chat running in the background asks only once that chat is on screen.
    LaunchedEffect(permissionAsk != null && backgroundRunId != null) {
        if (permissionAsk != null && backgroundRunId != null) vm.showNotice("后台对话在等你确认权限，去侧边栏查看")
    }
    permissionAsk?.takeIf { backgroundRunId == null }?.let { ask ->
        com.iris.localai.ui.agent.AgentPermissionDialog(ask.toolLabel, ask.toolName, ask.preview, alwaysPrefix = ask.alwaysPrefix, onAnswer = vm::answerPermission)
    }
    if (showContext) {
        ContextSheet(
            usage = ui.context,
            globalStrategy = com.iris.localai.data.ContextStrategy.of(settings.contextStrategy),
            keepTurns = settings.contextKeepTurns,
            summary = ui.conversation.summary,
            hasMessages = ui.conversation.messages.isNotEmpty(),
            onStrategy = vm::setConversationStrategy,
            onBreakHere = vm::startNewContextHere,
            onClearBreak = vm::clearContextBreak,
            onClearSummary = vm::clearSummary,
            onDismiss = { showContext = false },
            compactionText = ui.conversation.compaction?.let { com.iris.localai.context.Compactor.render(it) }.orEmpty(),
            onCompact = vm::compactNow,
            onClearCompaction = vm::clearCompaction,
        )
    }
}

private fun engineTag(m: LocalModel): String = when (m.format) {
    ModelFormat.GGUF -> "GGUF"
    ModelFormat.MNN -> "MNN"
    ModelFormat.QAIRT -> "GenieX"
    ModelFormat.LITERT -> "LiteRT"
}

@Composable
private fun ModelTitle(engine: EngineState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    com.iris.localai.ui.components.IrisPill(
        modifier,
        height = IrisDimens.topBarControl,
        onClick = onClick, onClickLabel = "选择模型",
    ) { ModelPillContent(engine, chevronTurn = 0f) }
}

/** 2.2.7: the pill's row content, shared by the pill and the header of the expanded panel. */
@Composable
private fun RowScope.ModelPillContent(engine: EngineState, chevronTurn: Float, hPad: androidx.compose.ui.unit.Dp = IrisDimens.space12) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val title = when (engine) {
        is EngineState.Ready -> engine.model.displayName
        is EngineState.Loading -> engine.model.displayName
        else -> "IRIDIS"
    }
    val sub = when (engine) {
        // 1.7.8: the backend really in use; a fallback says so right under the title (never silent).
        is EngineState.Ready -> engine.details.backend + if (engine.details.fallbackNote != null) " · 已回退" else ""
        is EngineState.Loading -> com.iris.localai.engine.SlowLoadHint.subtitle(null, 0)
        is EngineState.Failed -> "加载失败，点按查看"
        EngineState.Idle -> "未加载"
    }
    Spacer(Modifier.width(hPad))
    Column(Modifier.weight(1f, fill = chevronTurn > 0f), verticalArrangement = Arrangement.Center) {
        AnimatedContent(
            targetState = title,
            transitionSpec = { motion(reduce) { IrisMotion.crossfade() } },
            label = "modelTitle",
        ) { t ->
            Text(t, style = IrisType.pillTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            sub,
            style = IrisType.pillCaption,
            color = if (engine is EngineState.Failed) c.danger else c.textTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.pulse(engine is EngineState.Loading, min = 0.5f),
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
private fun ModelMorphPanel(
    progress: () -> Float,
    expanded: Boolean,
    pillInRoot: androidx.compose.ui.geometry.Rect,
    engine: EngineState,
    models: List<LocalModel>,
    currentPath: String?,
    onDismiss: () -> Unit,
    onPick: (LocalModel) -> Unit,
    onTiers: () -> Unit,
    onManage: () -> Unit,
    unloadEnabled: Boolean = true,
    onUnload: () -> Unit = {},
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
            // 2.2.8: symmetric — the panel is centred on screen and grows from the pill's centre downward.
            val pillLeft = pillInRoot.left - origin.x
            val top = pillInRoot.top - origin.y
            val pillW = pillInRoot.width
            val pillH = pillInRoot.height
            val areaWidth = if (areaW > 0) areaW.toFloat() else conf.screenWidthDp.dp.toPx()
            val panelW = minOf(maxOf(pillW, 248.dp.toPx()), 280.dp.toPx(), areaWidth - 32.dp.toPx()).coerceAtLeast(pillW)
            val panelLeft = (areaWidth - panelW) / 2f
            val avail = if (areaH > 0) areaH - top - 16.dp.toPx() else Float.MAX_VALUE
            val maxH = minOf(conf.screenHeightDp.dp.toPx() * 0.5f, avail).coerceAtLeast(pillH)
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
                        .wrapContentSize(Alignment.TopCenter, unbounded = true)
                        .width(panelW.toDp())
                        .heightIn(max = maxH.toDp())
                        .onSizeChanged { contentH = it.height + (52.dp.toPx() - headerH).toInt() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier
                            .width(w.toDp()).height(headerH.toDp())
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null, onClickLabel = "收起",
                            ) { onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) { ModelPillContent(engine, chevronTurn = p, hPad = hPad) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .graphicsLayer { alpha = ((progress() - 0.3f) / 0.7f).coerceIn(0f, 1f) }
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(bottom = 4.dp),
                    ) {
                        MorphHairline()
                        models.forEach { m ->
                            val selected = m.path == currentPath
                            Row(
                                Modifier.fillMaxWidth().height(44.dp).clickable(enabled = expanded) { onPick(m) }.padding(horizontal = 16.dp),
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
                        if (models.isNotEmpty()) MorphHairline()
                        CompactMenuRow("档位与加载…", Icons.Outlined.Tune) { if (expanded) onTiers() }
                        CompactMenuRow("管理模型", Icons.Outlined.Settings) { if (expanded) onManage() }
                        if (engine is EngineState.Ready) {
                            CompactMenuRow("卸载模型", Icons.Outlined.Eject, enabled = unloadEnabled) { if (expanded) onUnload() }
                        }
                    }
                }
            }
        }
    }
}

/** 2.2.8: section hairline on the panel grid — inset 16 dp both sides, 8 dp section gap. */
@Composable
private fun MorphHairline() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(IrisDimens.hairline).background(Iris.colors.outline))
}

@Composable
private fun CompactMenuRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Iris.colors
    val a = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.textSecondary.copy(alpha = c.textSecondary.alpha * a), modifier = Modifier.size(18.dp))
        Text(text, style = IrisType.menuItem.copy(fontSize = 15.sp, lineHeight = 20.sp), color = c.text.copy(alpha = c.text.alpha * a), maxLines = 1, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * 1.9.0: a GPU load past [com.iris.localai.engine.SlowLoadHint.NOTICE_SECS] explains the wait and
 * offers 「改用 CPU」 (applied by reloading as soon as the running load returns).
 */
@Composable
private fun SlowLoadBanner(engine: EngineState, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onUseCpu: () -> Unit) {
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
            com.iris.localai.ui.components.PressableIcon(Icons.Outlined.Close, "关闭", size = IrisDimens.space32, iconSize = IrisDimens.iconSmall, tint = c.textTertiary) { dismissedFor = loading?.since }
        }
    }
}

/**
 * 2.0.x: the one look of the cards that float above the composer (slow-load hint, notices):
 * frosted, continuous corners, hairline border, info icon, the same padding.
 */
@Composable
private fun BannerCard(
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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, onClickLabel = "关闭提示") else Modifier)
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
private fun ActivityIndicator(engine: EngineState, activity: com.iris.localai.util.Activity?, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onCancelLoad: () -> Unit, modifier: Modifier = Modifier) {
    val c = Iris.colors
    val loading = engine as? EngineState.Loading
    val target: String? = when {
        loading != null -> if (loading.applyingTier != null) "正在重载模型" else "正在加载 ${loading.model.displayName}"
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
                val tail = pct + if (secs >= 2) " · ${secs} 秒" else ""
                if (tail.isNotEmpty()) com.iris.localai.ui.motion.RollingText(tail, style = tnum, color = c.textTertiary)
                if (loading != null && loading.applyingTier == null) {
                    Spacer(Modifier.size(4.dp))
                    // Fits the 22 dp capsule (a 36 dp IrisButton overflowed the fixed-height row).
                    Text(
                        "取消", style = IrisType.caption, color = c.accent,
                        modifier = Modifier.clip(IrisRadius.pillShape).clickable(onClickLabel = "取消加载", onClick = onCancelLoad)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Dismissible notice above the composer; keeps showing the last text while it animates out. */
@Composable
private fun NoticeBanner(notice: String?, backdrop: com.iris.localai.ui.glass.GlassBackdrop, onDismiss: () -> Unit) {
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

@Composable
private fun ChatEmptyState(
    modelName: String?,
    engine: EngineState,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPrompt: (String) -> Unit,
) {
    val c = Iris.colors
    val reduce = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) appear.animateTo(1f, IrisMotion.enter()) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            .padding(horizontal = IrisDimens.space32)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx()
                val s = 0.97f + 0.03f * appear.value
                scaleX = s; scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 2.2.5: the mark is decorative again (tap-to-load removed); it still breathes while a model loads.
        Icon(
            painterResource(R.drawable.ic_iris_mark), null,
            tint = c.text,
            modifier = Modifier
                .padding(6.dp)
                .size(44.dp)
                .pulse(engine is EngineState.Loading, min = 0.35f, periodMs = 1100),
        )
        Spacer(Modifier.height(IrisDimens.space20))
        Text("有什么可以帮你？", style = IrisType.display, color = c.text, textAlign = TextAlign.Center)
    }
}

/** Seconds since the current load started, ticking once a second while loading. */
@Composable
fun loadingSeconds(engine: EngineState): Long {
    val since = (engine as? EngineState.Loading)?.since ?: return 0
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    return ((now - since) / 1000).coerceAtLeast(0)
}

/** 2.0.x: 「记住这条」 — the line to save (editable) and where it goes: 关于你 (USER.md) or 记住的事 (MEMORY.md). */
@Composable
private fun RememberDialog(
    initial: String,
    suggested: com.iris.localai.memory.MemoryTarget,
    onSave: (String, com.iris.localai.memory.MemoryTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var target by remember { mutableStateOf(suggested) }
    com.iris.localai.ui.components.IrisDialog(
        onDismiss = onDismiss, title = VersionText.REMEMBER, confirmText = "记住",
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onSave(text, target); onDismiss() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(
                text, { text = it },
                minLines = 2, maxLines = 6,
                textStyle = com.iris.localai.ui.theme.IrisType.body.copy(color = com.iris.localai.ui.theme.Iris.colors.text),
                shape = com.iris.localai.ui.theme.IrisRadius.mediumShape,
                colors = com.iris.localai.ui.components.irisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            com.iris.localai.memory.MemoryTarget.entries.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(com.iris.localai.ui.theme.IrisRadius.smallShape)
                        .clickable { target = t }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.iris.localai.ui.components.IrisRadio(selected = target == t)
                    Spacer(Modifier.width(10.dp))
                    Text("${t.label}（${t.fileName}）", style = com.iris.localai.ui.theme.IrisType.body, color = com.iris.localai.ui.theme.Iris.colors.text)
                }
            }
        }
    }
}

/** 2.1.5: chat top bar fill — opaque page colour, or card-like translucency over a custom background. */
internal fun chatTopBarColor(page: androidx.compose.ui.graphics.Color, backgroundImage: Boolean): androidx.compose.ui.graphics.Color =
    if (backgroundImage) page.copy(alpha = CHAT_TOP_BAR_BACKDROP_ALPHA) else page

/** Same see-through level as cards over a custom background ([com.iris.localai.ui.theme.overBackdrop] surfaceElevated). */
internal const val CHAT_TOP_BAR_BACKDROP_ALPHA = 0.88f

/** 2.1.7: a message waiting to be answered (or read by the 智能体); tap cancels it. */
@Composable
private fun QueuedBubble(q: QueuedMessage, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    val c = com.iris.localai.ui.theme.Iris.colors
    Box(modifier.fillMaxWidth().padding(start = 52.dp), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .clip(com.iris.localai.ui.theme.IrisRadius.bubbleShape)
                    .background(c.surfaceVariant.copy(alpha = 0.55f))
                    .clickable(onClickLabel = "取消这条") { onCancel() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(q.text, style = com.iris.localai.ui.theme.IrisType.message, color = c.textSecondary)
            }
            Row(Modifier.padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(com.iris.localai.ui.icons.IrisIcons.Clock, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    (if (q.forAgent) SendIntent.QUEUED_AGENT_LABEL else SendIntent.QUEUED_LABEL) + " · 点按取消",
                    style = com.iris.localai.ui.theme.IrisType.labelSmall, color = c.textTertiary,
                )
            }
        }
    }
}

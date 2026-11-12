package com.iris.localai.ui.chat

import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
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

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenImageGen: () -> Unit = {},
    onOpenCharacters: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit,
    onOpenApi: () -> Unit,
) {
    val c = Iris.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val character by vm.character.collectAsStateWithLifecycle() // 2.3.2 角色卡
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
    // 2.3.0 ③ 「翻译」 the draft: the text before it (撤销翻译) and the running job.
    var draftOriginal by remember { mutableStateOf<String?>(null) }
    var draftJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val translator = rememberTranslator()
    val draft by vm.draft.collectAsStateWithLifecycle()
    LaunchedEffect(draft) { draft?.let { input = it; vm.setDraft(null) } }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    // 2.0.x: 「记住这条」 dialog for this message (null = closed).
    var remembering by remember { mutableStateOf<com.iris.localai.data.ChatMessage?>(null) }
    var deletingMsg by remember { mutableStateOf<com.iris.localai.data.ChatMessage?>(null) }
    // 1.10.12: attachments waiting for the next send (already copied into app storage).
    var pending by remember { mutableStateOf<List<com.iris.localai.data.Attachment>>(emptyList()) }
    /** 2.2 「技能」: references picked for the next message. */
    var refs by remember { mutableStateOf<List<com.iris.localai.data.TurnRef>>(emptyList()) }
    fun addPicked(uris: List<android.net.Uri>) {
        if (uris.isNotEmpty()) scope.launch {
            val room = com.iris.localai.util.Attachments.MAX_PER_MESSAGE - pending.size
            if (room <= 0) { vm.showNotice(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_926679, com.iris.localai.util.Attachments.MAX_PER_MESSAGE)); return@launch }
            pending = pending + vm.importAttachments(uris.take(room))
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addPicked(it) }
    // 2.1.7 生图 「发到聊天」: the generated PNG joins the composer's attachments.
    val incomingImage by vm.incomingImage.collectAsStateWithLifecycle()
    LaunchedEffect(incomingImage) {
        val path = incomingImage ?: return@LaunchedEffect
        vm.consumeIncomingImage()
        if (pending.size >= com.iris.localai.util.Attachments.MAX_PER_MESSAGE) { vm.showNotice(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_926679, com.iris.localai.util.Attachments.MAX_PER_MESSAGE)); return@LaunchedEffect }
        vm.importLocalImage(path)?.let { pending = pending + it }
    }
    // 1.10.13: 照片 opens the system photo picker (no storage permission needed).
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.iris.localai.util.Attachments.MAX_PER_MESSAGE),
    ) { addPicked(it) }
    /** 1.10.6: 「查看提示内容」 text (null = closed). */
    var promptView by remember { mutableStateOf<String?>(null) }
    // 2.2.7: the model pill morphs into the model panel (container transform).
    var modelMenu by remember { mutableStateOf(false) }
    var pillBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var charPillBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) } // 2.3.3
    var charMenu by remember { mutableStateOf(false) }
    val charMorph = remember { androidx.compose.animation.core.Animatable(0f) }
    val modelMorph = remember { Animatable(0f) }
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
    val trash by vm.trash.collectAsStateWithLifecycle()
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
                onCharacters = { scope.launch { drawer.close() }; onOpenCharacters() },
                runningId = backgroundRunId,
                open = drawer.targetValue == DrawerValue.Open,
                trash = trash,
                onRestore = vm::restoreChat,
                onPurge = vm::purgeChat,
                onEmptyTrash = vm::emptyTrash,
            )
        },
    ) {
        LaunchedEffect(drawer.targetValue) { if (drawer.targetValue == DrawerValue.Open) hideKeyboard() }
        // 2.5.3 新手引导: the drawer step opens the drawer; every other step keeps it closed.
        val tour = com.iris.localai.ui.tour.LocalTour.current
        val tourActive = tour?.active == true
        val tourDrawer = tourActive && tour?.currentStep?.screen == com.iris.localai.ui.tour.TourScreen.DRAWER
        LaunchedEffect(tourActive, tourDrawer) {
            if (!tourActive) return@LaunchedEffect
            if (tourDrawer) drawer.open() else if (drawer.currentValue == DrawerValue.Open) drawer.close()
        }
        // 2.5.0: shared page skeleton (柔光: faint accent tint at the top; 经典: plain colour). Layout unchanged.
        com.iris.localai.ui.theme.IrisPageSurface(com.iris.localai.ui.theme.PageKind.CHAT) {
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
                // 2.2.14 纯色背景: no picture, blend or glow.
                val solidBg = settings.solidBackground
                if (!solidBg) com.iris.localai.ui.theme.ChatBackdropImage(settings.backgroundImage, settings.backgroundBlur, settings.backgroundScrim)
                // 2.2.10: 动态混色背景 (off = the 2.2.9 look exactly: static glow on the empty home only).
                if (!solidBg && settings.dynamicBlend && settings.backgroundImage.isEmpty()) com.iris.localai.ui.components.HomeDynamicBlend()
                // 2.3.5: the soft top glow stays once a conversation starts (it used to show on the empty home only).
                if (!solidBg && settings.backgroundImage.isEmpty() && !settings.dynamicBlend) com.iris.localai.ui.components.HomeAmbientGlow()
                if (messages.isEmpty()) {
                    ChatEmptyState(
                        modelName = ui.readyModel?.displayName,
                        engine = ui.engine,
                        topPadding = with(density) { topBarHeight.toDp() },
                        bottomPadding = with(density) { composerHeight.toDp() },
                    )
                } else {
                    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id
                    // 2.4.5 (#10): expand / collapse inside the list snaps to its end while the list moves.
                    val listScrolling = remember(listState) { derivedStateOf { listState.isScrollInProgress } }
                    androidx.compose.runtime.CompositionLocalProvider(com.iris.localai.ui.components.LocalListScrolling provides listScrolling) {
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
                        verticalArrangement = Arrangement.spacedBy(com.iris.localai.ui.theme.IrisSpace.L),
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
                                        onDelete = { deletingMsg = m },
                                    )
                                } else {
                                    // 2.3.2 角色卡: the card's avatar + name above each of its replies.
                                    character?.let { ch ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = com.iris.localai.ui.theme.IrisSpace.XS)) {
                                            com.iris.localai.ui.character.CharacterAvatar(ch.avatar, ch.card.name, 26.dp)
                                            Spacer(Modifier.width(com.iris.localai.ui.theme.IrisSpace.S))
                                            Text(ch.card.name, style = com.iris.localai.ui.theme.IrisType.secondary, color = com.iris.localai.ui.theme.Iris.colors.textSecondary, maxLines = 1)
                                        }
                                    }
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
                                        onAcceptMemory = { vm.acceptMemorySuggestion(m.id) },
                                        onDismissMemory = { vm.dismissMemorySuggestion(m.id) },
                                        onDelete = if (m.id == ui.streamingId) null else ({ deletingMsg = m }),
                                    )
                                }
                                if (ui.context.compactedMessages > 0 && m.id == ui.conversation.compaction?.upToId) {
                                    Spacer(Modifier.height(com.iris.localai.ui.theme.IrisSpace.L))
                                    CompactionDivider(ui.context.compactedMessages, onClear = vm::clearCompaction)
                                }
                                if (m.id == ui.conversation.contextStartAfterId) {
                                    Spacer(Modifier.height(com.iris.localai.ui.theme.IrisSpace.L))
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
            }

            // ---- Top bar: solid, no translucent scrim over the messages (user request, 1.5.0) ----
            // 2.1.5: with a custom background the bar (status-bar area included) is see-through like
            // the cards, so the picture runs full-bleed to the top edge instead of being cut off.
            val hasBgImage = settings.backgroundImage.isNotEmpty() && !settings.solidBackground
            // 2.2.4: Kimi-style floating top bar — no flat full-width bar; two round buttons and a
            // model pill float on a transparent background so the chat scrolls under them.
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeight = it.height }
                    .statusBarsPadding(),
            ) {
                // Identical 42 dp side buttons at the same 16 dp edge inset; the model pill follows the
                // menu button on the left (2.2.13), never closer than 8 dp to either button.
                Box(
                    Modifier.fillMaxWidth().height(IrisDimens.topBarHeight + 8.dp).padding(horizontal = IrisDimens.screenMargin),
                    contentAlignment = Alignment.Center,
                ) {
                    com.iris.localai.ui.components.FloatingIconButton(Icons.Outlined.Menu, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f8dce1), Modifier.align(Alignment.CenterStart).tourTarget(TourId.MENU), size = IrisDimens.topBarControl) { hideKeyboard(); scope.launch { drawer.open() } }
                    // 2.3.3: 模型 and 角色 pills side by side after the menu button; on a narrow row the
                    // 角色 pill shrinks to the card's avatar.
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier.align(Alignment.CenterStart)
                            .padding(start = IrisDimens.topBarControl + IrisDimens.space8, end = IrisDimens.topBarControl + IrisDimens.space8),
                    ) {
                        val compactChar = com.iris.localai.character.CharacterPick.compact(maxWidth.value.toInt(), character != null)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ModelTitle(
                                ui.engine,
                                Modifier
                                    .weight(1f, fill = false)
                                    .onGloballyPositioned { pillBounds = it.boundsInRoot() }
                                    .tourTarget(TourId.MODEL)
                                    // hidden while the morphing surface stands in for it
                                    .graphicsLayer { alpha = if (modelMenu || modelMorph.value > 0f) 0f else 1f },
                            ) { hideKeyboard(); charMenu = false; modelMenu = true }
                            Spacer(Modifier.width(IrisDimens.space8))
                            CharacterTitle(
                                character, compactChar,
                                Modifier
                                    .then(if (compactChar) Modifier else Modifier.weight(1f, fill = false))
                                    .onGloballyPositioned { charPillBounds = it.boundsInRoot() }
                                    .tourTarget(TourId.CHARACTER)
                                    // 2.3.5: hidden while its panel (which morphs from it) is showing — same as 模型
                                    .graphicsLayer { alpha = if (charMenu || charMorph.value > 0f) 0f else 1f },
                                expanded = { charMorph.value },
                            ) { hideKeyboard(); modelMenu = false; charMenu = !charMenu }
                        }
                    }
                    com.iris.localai.ui.components.FloatingIconButton(Icons.Outlined.EditNote, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_1ac07a), Modifier.align(Alignment.CenterEnd).tourTarget(TourId.NEW_CHAT), size = IrisDimens.topBarControl, enabled = messages.isNotEmpty()) { vm.newChat() }
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
                        .padding(top = with(density) { topBarHeight.toDp() } + com.iris.localai.ui.theme.IrisSpace.XS, end = com.iris.localai.ui.theme.IrisSpace.S),
                )
            }

            // ---- Scroll-to-bottom ----
            AnimatedVisibility(
                visible = !atBottom && messages.isNotEmpty(),
                enter = fadeIn(IrisMotion.enter()) + scaleIn(IrisMotion.enter(), initialScale = 0.8f),
                exit = fadeOut(IrisMotion.standard()) + scaleOut(IrisMotion.standard(), targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { composerHeight.toDp() } + com.iris.localai.ui.theme.IrisSpace.S),
            ) {
                PressableIcon(
                    Icons.Outlined.KeyboardArrowDown, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_5d94f1), size = 40.dp, iconSize = 22.dp,
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
                    .padding(horizontal = com.iris.localai.ui.components.readableSidePadding(com.iris.localai.ui.components.Adaptive.chatMaxWidth + com.iris.localai.ui.theme.IrisSpace.XL))
                    .padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space8),
            ) {
                SlowLoadBanner(ui.engine, backdrop = backdrop, onUseCpu = vm::switchLoadingModelToCpu)
                NoticeBanner(ui.notice, backdrop = backdrop, onDismiss = vm::dismissNotice)
                ModelRestoreBanner(vm.modelRestore.collectAsState().value, backdrop, onBack = vm::acceptModelRestore, onDismiss = vm::dismissModelRestore)
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
                        Modifier.fillMaxWidth().heightIn(min = IrisDimens.activityRowHeight).padding(bottom = com.iris.localai.ui.theme.IrisSpace.XS), // 1.12.0: grows with large font
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
                        translating = draftJob != null,
                        draftOriginal = draftOriginal,
                    ),
                    refCatalog = vm::refCatalog,
                    onAddRef = { r ->
                        refs = com.iris.localai.data.TurnRefs.add(refs, r)
                        // 2.3.5: a referenced skill / tool runs through tools → 智能体 on right away (not only at send).
                        if (com.iris.localai.data.TurnRefs.needsAgent(refs, agentSettings.agentMode)) { vm.setAgentMode(true); vm.showNotice(ComposerText.AGENT_FOR_REF) }
                    },
                    onRemoveRef = { r -> refs = refs - r },
                    onHoldToTalkCancel = { if (listening) { listening = false; vm.dictation.cancel() } },
                    onPickPhotos = { pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onRemoveAttachment = { a -> pending = pending - a; vm.discardAttachments(listOf(a)) },
                    onMoveAttachment = { from, to -> pending = AttachmentOrder.move(pending, from, to) },
                    onToggleAgent = {
                        val on = !agentSettings.agentMode
                        // 2.3.5: skills / tools need the agent — turning it off drops the references.
                        if (!on) refs = emptyList()
                        vm.setAgentMode(on)
                        vm.showNotice(ComposerText.agentNotice(on))
                    },
                    onThinkingUnsupported = { vm.showNotice(ComposerText.THINK_UNSUPPORTED) },
                    backdrop = backdrop,
                    onResourceTier = vm::setResourceTier,
                    onTextChange = { input = it; vm.onUserActivity(); if (draftJob == null) draftOriginal = null },
                    onTranslateDraft = { target ->
                        val original = input
                        draftOriginal = original
                        draftJob = scope.launch {
                            try { translator.translate(original, target).collect { if (it.isNotEmpty()) input = it } }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Throwable) { input = original; draftOriginal = null; vm.showNotice(e.message ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_032ee1)) }
                            finally { draftJob = null }
                        }
                    },
                    onUndoTranslate = { draftJob?.cancel(); draftJob = null; draftOriginal?.let { input = it }; draftOriginal = null },
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
                        draftJob?.cancel(); draftJob = null; draftOriginal = null
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
                                    vm.showNotice(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_fcf82a, t.message ?: t.javaClass.simpleName))
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
                        if (m.path != current) { if (locked) vm.noteSwitchBlocked() else vm.loadModel(m) }
                    },
                    onManage = { modelMenu = false; onOpenModels() },
                    // 2.2.9: free the model's memory; disabled while a reply / background run is going.
                    unloadEnabled = !(ui.isGenerating || backgroundRunId != null),
                    onUnload = { modelMenu = false; vm.unloadModel() },
                )
            }
            // ---- 2.3.3: 角色 pill → panel morph (same pattern as the model panel) ----
            LaunchedEffect(charMenu) {
                charMorph.animateTo(
                    if (charMenu) 1f else 0f,
                    if (reduceMorph) tween(120) else tween(300, easing = IrisMotion.ProgressEasing),
                )
            }
            androidx.activity.compose.BackHandler(enabled = charMenu) { charMenu = false }
            val cb = charPillBounds
            if (cb != null && (charMenu || charMorph.value > 0f)) {
                val cards by androidx.compose.runtime.produceState(emptyList<com.iris.localai.character.CharacterStore.Entry>(), charMenu) {
                    if (charMenu) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.characterEntries() }
                }
                val compactChar = cb.width < 60f * androidx.compose.ui.platform.LocalDensity.current.density && character != null
                CharacterMorphPanel(
                    progress = { charMorph.value },
                    expanded = charMenu,
                    pillInRoot = cb,
                    current = character,
                    compact = compactChar,
                    cards = cards,
                    onDismiss = { charMenu = false },
                    onPick = { id -> charMenu = false; vm.pickCharacter(id) },
                    onManage = { charMenu = false; onOpenCharacters() },
                )
            }
    }
    }

    // 2.5.0 Beta6: 加载前内存预检 (仍要加载 / 取消).
    val memWarn by vm.memWarn.collectAsStateWithLifecycle()
    memWarn?.let { (m, msg) -> com.iris.localai.ui.models.MemoryLoadDialog(m, msg, onConfirm = { vm.confirmMemLoad() }, onDismiss = vm::dismissMemWarn) }

    deletingMsg?.let { m ->
        com.iris.localai.ui.components.ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.del244_title),
            message = com.iris.localai.I18n.s(if (com.iris.localai.data.MessageVersions.count(m) > 1) com.iris.localai.R.string.del244_message_versions else com.iris.localai.R.string.del244_message),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.del244_delete),
            onConfirm = { vm.deleteMessage(m.id); deletingMsg = null },
            onDismiss = { deletingMsg = null },
        )
    }
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
            onDismiss = { promptView = null }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_c7c46c),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91), onConfirm = { promptView = null },
            dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_79d3ab), onDismissClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)) },
        ) {
            Box(
                Modifier.fillMaxWidth().clip(com.iris.localai.ui.theme.IrisRadius.smallShape).background(com.iris.localai.ui.theme.Iris.colors.codeBackground)
                    .heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(com.iris.localai.ui.theme.IrisSpace.S),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = com.iris.localai.ui.theme.IrisType.monoSmall, color = com.iris.localai.ui.theme.Iris.colors.text)
                }
            }
        }
    }
    // 2.1.9: `screen` was blocked → the matching guide (无障碍 / 受限设置 / 识图模型).
    guiSetup?.let { b -> com.iris.localai.ui.agent.GuiSetupDialog(b, onDismiss = vm::dismissGuiSetup, onPickModel = { vm.dismissGuiSetup(); onOpenModels() }) }
    // 1.13.0: a tool of the chat running in the background asks only once that chat is on screen.
    LaunchedEffect(permissionAsk != null && backgroundRunId != null) {
        if (permissionAsk != null && backgroundRunId != null) vm.showNotice(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_3b8d7a))
    }
    permissionAsk?.takeIf { backgroundRunId == null }?.let { ask ->
        // 2.4.5 (#9): tapping outside folds the question into a corner pill; only the buttons answer it.
        var collapsed by remember(ask.answer) { mutableStateOf(false) }
        if (collapsed) com.iris.localai.ui.agent.AgentPermissionPill(ask.toolLabel, with(density) { composerHeight.toDp() } + 12.dp) { collapsed = false }
        else com.iris.localai.ui.agent.AgentPermissionDialog(ask.toolLabel, ask.toolName, ask.preview, alwaysPrefix = ask.alwaysPrefix, onCollapse = { collapsed = true }, onAnswer = vm::answerPermission)
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
            showStrategy = settings.developerMode,
        )
    }
}

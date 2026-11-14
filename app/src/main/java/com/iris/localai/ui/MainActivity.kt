package com.iris.localai.ui

import com.iris.localai.ui.theme.IrisDimens
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.IrisApp
import com.iris.localai.server.ApiService
import com.iris.localai.ui.chat.ChatScreen
import com.iris.localai.ui.chat.ChatViewModel
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.models.ModelsScreen
import com.iris.localai.ui.settings.AboutPage
import com.iris.localai.ui.settings.ApiPage
import com.iris.localai.ui.settings.GenerationPage
import com.iris.localai.ui.settings.SettingsHome
import com.iris.localai.ui.settings.SettingsPage
import com.iris.localai.ui.settings.SettingsViewModel
import com.iris.localai.ui.settings.VoicePage
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motion
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.ui.theme.IrisTheme
import com.iris.localai.ui.theme.LocalReduceMotion
import com.iris.localai.ui.theme.systemAnimationsDisabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** Top-level destinations. A tiny back stack is enough for this app's shallow navigation. */
private enum class Route { APPEARANCE, LANGUAGE, CHAT, MODELS, SETTINGS, GENERATION, VOICE, API, ABOUT, DIAGNOSTICS, CONTEXT, PERFORMANCE, TIER_PROFILES, AGENT, MEMORY, BROWSER, FILES, STORAGE, DEVELOPER, IMAGEGEN, APP_FILES, DEV_CONTEXT, DOWNLOAD_MODELS, CHARACTERS,
    /** 2.5.0 Beta5: 上下文、记忆和知识库 › 记忆列表 / 知识库 as real routes (push / pop animation like every subpage). */
    MEMORY_LIST, KNOWLEDGE,
    /** 2.5.0 Beta6: 智能体 › MCP 服务 and 知识库 › 新建/编辑文字 as real routes (the file to edit rides in KbEditorNav). */
    MCP, KB_EDITOR,
    /** 2.5.4: 设置 › 下载源 (one setting for every download). */
    DOWNLOAD_SOURCE,
    /** 2.5.0 Beta6: 关于 › 许可证 and 单个许可证全文 as real routes (the file rides in LicenseNav). */
    LICENSES, LICENSE_TEXT }

class MainActivity : ComponentActivity() {

    private val chat: ChatViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(com.iris.localai.I18n.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        // 2.1.5: fully transparent system bars (no auto scrim) and no navigation-bar contrast scrim,
        // so the chat background / page colour runs under the gesture pill. Icon light/dark is set
        // per app theme in IrisTheme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        // 2.3.1: some labels (enums, object texts) are read once per process; after a language switch restart it.
        if (com.iris.localai.I18n.langChangedSinceStart()) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            finishAffinity()
            Runtime.getRuntime().exit(0)
            return
        }
        val container = (application as IrisApp).container
        if (container.settings.current.apiEnabled && !ApiService.status.value.running) {
            ApiService.start(this)
        }
        val crash = if (savedInstanceState == null) com.iris.localai.util.CrashReporter.takeReport(this) else null
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            var crashText by rememberSaveable { mutableStateOf(crash) }
            val powerSave = rememberPowerSaveMode()
            val reduceMotion = rememberReduceMotion()
            val thermal = rememberThermalStatus()
            val resumed = rememberResumed()
            // Decorative loops pause while a reply streams (typewriter + follow-scroll own the
            // frames), while the app is not in the foreground, and in battery saver.
            val generating by chat.engineBusy.collectAsStateWithLifecycle()
            val streaming = generating != null
            val quality = com.iris.localai.ui.glass.GlassPolicy.resolve(
                level = settings.visualEffects.coerceAtLeast(0),
                glassSwitch = !settings.solidBackground,
                powerSave = powerSave,
                thermalStatus = thermal,
                generating = streaming,
            )
            IrisTheme(
                mode = settings.themeMode, glassQuality = quality, reduceMotion = reduceMotion,
                decorativeMotion = resumed && !streaming && !powerSave && settings.visualEffects != 0,
                palette = com.iris.localai.ui.theme.IrisPalette.of(settings.themePalette),
                backdrop = settings.backgroundImage.isNotEmpty() && !settings.solidBackground,
                solid = settings.solidBackground,
                style = com.iris.localai.ui.theme.IrisStyle.of(settings.uiStyle),
            ) {
                // 1.14.7: first-launch welcome (carries the disclaimer) gates the app until accepted;
                // fresh installs and upgrades see it once (welcomeAccepted defaults to false).
                AnimatedContent(
                    targetState = settings.welcomeAccepted,
                    transitionSpec = { motion(reduceMotion) { IrisMotion.fadeThrough() } },
                    label = "welcome",
                ) { accepted ->
                    if (accepted) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        // 2.5.3 新手引导 (spotlight tour): once after the welcome; replayable from 设置 › 关于与调试 › 新手引导.
                        AppNavigation(
                            chat,
                            showTour = com.iris.localai.ui.tour.TourLogic.shouldShow(settings.welcomeAccepted, settings.tour253Done),
                            // 2.5.5: 全新安装引导结束时记为已看过本版「此次更新」，不紧接着再弹
                            onTourDone = { container.settings.update { it.copy(tour253Done = true).let { n -> n.copy(notesSeen = n.notesSeen.ifEmpty { com.iris.localai.BuildConfig.VERSION_NAME }) } } },
                        )
                        // 2.5.5: 升级后首次打开弹一次「此次更新」
                        if (com.iris.localai.data.UpdateNotes.shouldShow(settings.welcomeAccepted, settings.tour253Done, settings.notesSeen, com.iris.localai.BuildConfig.VERSION_NAME)) {
                            com.iris.localai.ui.whatsnew.UpdateNotesSheet(com.iris.localai.BuildConfig.VERSION_NAME, history = false) {
                                container.settings.update { it.copy(notesSeen = com.iris.localai.BuildConfig.VERSION_NAME) }
                            }
                        }
                    } else com.iris.localai.ui.welcome.WelcomeScreen(
                        onStart = { container.settings.update { it.copy(welcomeAccepted = true) } },
                    )
                }
                crashText?.let { report -> CrashDialog(report) { crashText = null } }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A "回复已完成 / 正在生成" notification opens its conversation. */
    private fun handleIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(com.iris.localai.service.GenerationService.EXTRA_CONVERSATION) ?: return
        intent.removeExtra(com.iris.localai.service.GenerationService.EXTRA_CONVERSATION)
        chat.openFromNotification(id)
    }
}

@Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val c = Iris.colors
    IrisDialog(
        onDismiss = onClose,
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_271348),
        confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_79d3ab),
        onConfirm = { clipboard.setText(AnnotatedString(report)); onClose() },
        dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91),
    ) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text(com.iris.localai.I18n.s(com.iris.localai.R.string.ui_406ea7), style = IrisType.secondary, color = c.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            Text(report, style = IrisType.monoSmall.copy(fontSize = IrisType.micro.fontSize, lineHeight = IrisType.micro.lineHeight), color = c.text)
        }
    }
}

@Composable
private fun AppNavigation(chat: ChatViewModel, showTour: Boolean = false, onTourDone: () -> Unit = {}) {
    var stack by rememberSaveable { mutableStateOf(listOf(Route.CHAT.name)) }
    fun push(r: Route) { if (stack.last() != r.name) stack = stack + r.name }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    val settingsVm: SettingsViewModel = viewModel()
    // Keeps each screen's rememberSaveable state (typed input, scroll positions) while it is off screen.
    val saveable = rememberSaveableStateHolder()
    val showChat by chat.showChat.collectAsStateWithLifecycle()
    LaunchedEffect(showChat) { if (showChat > 0) stack = listOf(Route.CHAT.name) }
    val reduce = LocalReduceMotion.current

    // Predictive back: the page shrinks toward the swipe edge while the gesture is in progress,
    // then the regular backward transition finishes the job. Only graphicsLayer work.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    var backRoute by remember { mutableStateOf<String?>(null) }
    PredictiveBackHandler(enabled = stack.size > 1) { events: Flow<BackEventCompat> ->
        backRoute = stack.last()
        try {
            events.collect { e ->
                backFromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress = e.progress
            }
            pop()
        } catch (e: CancellationException) {
            backProgress = 0f
            backRoute = null
            throw e
        }
    }
    LaunchedEffect(stack.size) {
        // Clear the gesture transform once the exit transition is over.
        if (backRoute != null) { delay(600); backProgress = 0f; backRoute = null }
    }

    // 2.5.3 新手引导: the tour drives this stack (normal push / pop transitions) and overlays everything.
    val tour = remember { com.iris.localai.ui.tour.TourController() }
    tour.onFinish = { stack = listOf(Route.CHAT.name); onTourDone() }
    LaunchedEffect(showTour) { if (showTour) { if (!tour.active) tour.start() } else { tour.active = false } }
    fun tourNavigate(screen: com.iris.localai.ui.tour.TourScreen) {
        val target = tourStack(screen).map { it.name }
        if (stack != target) stack = target
    }

    val density = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.runtime.CompositionLocalProvider(com.iris.localai.ui.tour.LocalTour provides tour) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = stack,
        contentKey = { it.last() },
        transitionSpec = {
            // Material shared-axis X: forward pushes slide in from the end, pops slide back.
            if (reduce) IrisMotion.none
            else IrisMotion.push(forward = targetState.size >= initialState.size)
        },
        label = "nav",
    ) { s ->
        val name = s.last()
        val r = Route.valueOf(name)
        saveable.SaveableStateProvider(name) {
            val gestureModifier = if (name == backRoute) Modifier.graphicsLayer {
                val p = backProgress
                val scale = 1f - 0.08f * p
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(if (backFromLeft) 1f else 0f, 0.5f)
                translationX = (if (backFromLeft) 1f else -1f) * IrisDimens.space12.toPx() * p
                shape = com.iris.localai.ui.theme.smoothShape((com.iris.localai.ui.theme.IrisRadius.sheet.value * p).dp)  // 1.10.10: continuous corners like every other surface
                clip = p > 0f
            } else Modifier
            androidx.compose.foundation.layout.Box(gestureModifier) {
                Screen(r, chat, settingsVm, push = ::push, pop = ::pop, home = { stack = listOf(Route.CHAT.name) })
            }
        }
    }
    if (tour.active) com.iris.localai.ui.tour.TourOverlay(tour, navigate = ::tourNavigate)
    }
    }
}

/** 2.5.3 新手引导: the route stack that shows a tour step's screen (DRAWER = chat, its drawer opened by ChatScreen). */
private fun tourStack(screen: com.iris.localai.ui.tour.TourScreen): List<Route> = when (screen) {
    com.iris.localai.ui.tour.TourScreen.CHAT, com.iris.localai.ui.tour.TourScreen.DRAWER -> listOf(Route.CHAT)
    com.iris.localai.ui.tour.TourScreen.MODELS -> listOf(Route.CHAT, Route.MODELS)
    com.iris.localai.ui.tour.TourScreen.SETTINGS -> listOf(Route.CHAT, Route.SETTINGS)
    com.iris.localai.ui.tour.TourScreen.GENERATION -> listOf(Route.CHAT, Route.SETTINGS, Route.GENERATION)
    com.iris.localai.ui.tour.TourScreen.PERFORMANCE -> listOf(Route.CHAT, Route.SETTINGS, Route.PERFORMANCE)
    com.iris.localai.ui.tour.TourScreen.VOICE -> listOf(Route.CHAT, Route.SETTINGS, Route.VOICE)
    com.iris.localai.ui.tour.TourScreen.CONTEXT -> listOf(Route.CHAT, Route.SETTINGS, Route.MEMORY)
    com.iris.localai.ui.tour.TourScreen.AGENT -> listOf(Route.CHAT, Route.SETTINGS, Route.AGENT)
}

@Composable
private fun Screen(r: Route, chat: ChatViewModel, settingsVm: SettingsViewModel, push: (Route) -> Unit, pop: () -> Unit, home: () -> Unit = {}) {
    when (r) {
        Route.CHAT -> ChatScreen(
            vm = chat,
            onOpenModels = { push(Route.MODELS) },
            onOpenSettings = { push(Route.SETTINGS) },
            onOpenVoiceSettings = { push(Route.VOICE) },
            onOpenApi = { push(Route.API) },
            onOpenFiles = { push(Route.APP_FILES) }, // 2.2: sidebar 文件 = the whole Download/IRIDIS
            onOpenImageGen = { push(Route.IMAGEGEN) },
            onOpenCharacters = { push(Route.CHARACTERS) },
        )
        // 2.3.2 角色卡: tap a card = new chat with it, back on the chat screen
        Route.CHARACTERS -> com.iris.localai.ui.character.CharactersPage(onBack = pop, onStartChat = { id -> chat.startCharacterChat(id); home() })
        Route.IMAGEGEN -> com.iris.localai.ui.imagegen.ImageGenScreen(onBack = pop, onSendToChat = { path -> chat.attachGeneratedImage(path); pop() })
        // 2.2.16: one 「模型与加载」 page (models + 档位 + load settings); 设置 › 模型与加载 opens it too
        Route.MODELS -> ModelsScreen(onBack = pop, settingsVm = settingsVm, onOpenDownload = { push(Route.DOWNLOAD_MODELS) })
        // 2.2.17 C: full 下载模型 page; 「试一试」 puts the demo prompt into the chat input
        Route.DOWNLOAD_MODELS -> com.iris.localai.ui.models.DownloadModelsScreen(onBack = pop, onTryPrompt = { p -> chat.setDraft(p); home() })
        Route.APPEARANCE -> com.iris.localai.ui.settings.AppearancePage(settingsVm, onBack = pop)
        Route.LANGUAGE -> com.iris.localai.ui.settings.LanguagePage(onBack = pop)
        Route.DOWNLOAD_SOURCE -> com.iris.localai.ui.settings.DownloadSourcePage(settingsVm, onBack = pop)
        Route.SETTINGS -> SettingsHome(
            settingsVm, onBack = pop,
            onOpen = { p -> push(routeOf(p)) },
        )
        Route.GENERATION -> GenerationPage(settingsVm, onBack = pop)
        Route.CONTEXT, Route.MEMORY -> {
            // 2.2.9 F: one 上下文 · 记忆 · 知识库 page; the old controls are under 开发者选项.
            val ui by chat.state.collectAsStateWithLifecycle()
            com.iris.localai.ui.settings.ContextMemoryPage(
                settingsVm, ui.context, onBack = pop, onDeveloper = { push(Route.DEVELOPER) },
                onOpenList = { push(Route.MEMORY_LIST) }, onOpenKnowledge = { push(Route.KNOWLEDGE) },
            )
        }
        Route.MEMORY_LIST -> com.iris.localai.ui.agent.MemoryListPage(onBack = pop)
        Route.KNOWLEDGE -> com.iris.localai.ui.agent.KnowledgePage(onBack = pop, onOpenEditor = { push(Route.KB_EDITOR) })
        Route.KB_EDITOR -> com.iris.localai.ui.agent.KbEditorRoute(onBack = pop)
        Route.DEV_CONTEXT -> com.iris.localai.ui.settings.DevContextMemoryPage(
            settingsVm, onBack = pop, onOpenList = { push(Route.MEMORY_LIST) }, onOpenKnowledge = { push(Route.KNOWLEDGE) },
        )
        Route.PERFORMANCE -> com.iris.localai.ui.settings.PerformancePage(settingsVm, onBack = pop, onOpen = { p -> push(routeOf(p)) })
        Route.TIER_PROFILES -> com.iris.localai.ui.settings.TierProfilesPage(settingsVm, onBack = pop)
        Route.VOICE -> VoicePage(settingsVm, onBack = pop)
        Route.API -> ApiPage(settingsVm, onBack = pop)
        Route.ABOUT -> AboutPage(onBack = pop, onDeveloper = { push(Route.DEVELOPER) }, onOpenLicenses = { push(Route.LICENSES) })
        Route.LICENSES -> com.iris.localai.ui.settings.LicensesPage(onBack = pop, onOpenFile = { push(Route.LICENSE_TEXT) })
        Route.LICENSE_TEXT -> com.iris.localai.ui.settings.LicenseTextRoute(onBack = pop)
        Route.DEVELOPER -> com.iris.localai.ui.settings.DeveloperPage(settingsVm, onBack = pop, onOpen = { p -> push(routeOf(p)) })
        Route.DIAGNOSTICS -> com.iris.localai.ui.settings.ResourceDiagnosticsPage(settingsVm, onBack = pop)
        Route.AGENT -> com.iris.localai.ui.agent.AgentSettingsPage(
            onBack = pop, onOpenBrowser = { push(Route.BROWSER) }, onOpenFiles = { push(Route.FILES) }, onOpenMcp = { push(Route.MCP) },
        )
        Route.MCP -> com.iris.localai.ui.agent.McpPage(onBack = pop)
        Route.FILES -> com.iris.localai.ui.agent.FilesPage(onBack = pop)
        Route.BROWSER -> com.iris.localai.ui.agent.BrowserPage(onBack = pop)
        Route.APP_FILES -> com.iris.localai.ui.files.AppFilesPage(onBack = pop)
        Route.STORAGE -> com.iris.localai.ui.settings.StoragePage(
            settingsVm, onBack = pop,
            onOpen = { p -> push(routeOf(p)) },
            onModels = { push(Route.MODELS) },
            onOpenFiles = { push(Route.FILES) },
            onImageModels = { push(Route.IMAGEGEN) },
        )
    }
}

private fun routeOf(p: SettingsPage): Route = when (p) {
    SettingsPage.APPEARANCE -> Route.APPEARANCE
    SettingsPage.LANGUAGE -> Route.LANGUAGE
    SettingsPage.HOME -> Route.SETTINGS
    SettingsPage.MODEL_BACKEND -> Route.MODELS
    SettingsPage.GENERATION -> Route.GENERATION
    SettingsPage.CONTEXT -> Route.CONTEXT
    SettingsPage.PERFORMANCE -> Route.PERFORMANCE
    SettingsPage.TIER_PROFILES -> Route.TIER_PROFILES
    SettingsPage.VOICE -> Route.VOICE
    SettingsPage.API -> Route.API
    SettingsPage.ABOUT -> Route.ABOUT
    SettingsPage.DIAGNOSTICS -> Route.DIAGNOSTICS
    SettingsPage.AGENT -> Route.AGENT
    SettingsPage.MEMORY -> Route.MEMORY
    SettingsPage.STORAGE -> Route.STORAGE
    SettingsPage.DEVELOPER -> Route.DEVELOPER
    SettingsPage.DEV_CONTEXT -> Route.DEV_CONTEXT
    SettingsPage.DOWNLOAD_SOURCE -> Route.DOWNLOAD_SOURCE
}

/** System "remove animations" switch, re-read whenever the app comes back to the foreground. */
@Composable
private fun rememberReduceMotion(): Boolean {
    var reduce by remember { mutableStateOf(systemAnimationsDisabled()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reduce = systemAnimationsDisabled() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return reduce
}

/** Tracks the system battery saver so glass effects switch off while it is on. */
@Composable
private fun rememberThermalStatus(): Int {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var status by remember { mutableStateOf(pm.currentThermalStatus) }
    DisposableEffect(pm) {
        val l = PowerManager.OnThermalStatusChangedListener { status = it }
        runCatching { pm.addThermalStatusListener(context.mainExecutor, l) }
        onDispose { runCatching { pm.removeThermalStatusListener(l) } }
    }
    return status
}

/** True while the activity is resumed (visible and in front); false in the background / screen off. */
@Composable
private fun rememberResumed(): Boolean {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val state by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    return state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
}

@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var on by remember { mutableStateOf(pm.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { on = pm.isPowerSaveMode }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}

package com.iris.localai.ui
import androidx.compose.foundation.text.selection.SelectionContainer

import com.iris.localai.BuildConfig
import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.UpdateNotes
import com.iris.localai.service.GenerationService
import com.iris.localai.ui.agent.AgentSettingsPage
import com.iris.localai.ui.agent.BrowserPage
import com.iris.localai.ui.agent.FilesPage
import com.iris.localai.ui.agent.KbEditorRoute
import com.iris.localai.ui.agent.KnowledgePage
import com.iris.localai.ui.agent.McpPage
import com.iris.localai.ui.agent.MemoryListPage
import com.iris.localai.ui.character.CharactersPage
import com.iris.localai.ui.files.AppFilesPage
import com.iris.localai.ui.glass.GlassPolicy
import com.iris.localai.ui.imagegen.ImageGenScreen
import com.iris.localai.ui.models.DownloadModelsScreen
import com.iris.localai.ui.models.DownloadNav
import com.iris.localai.ui.models.ModelGuideNav
import com.iris.localai.ui.models.ModelGuidePage
import com.iris.localai.ui.settings.AppearancePage
import com.iris.localai.ui.settings.ContextMemoryPage
import com.iris.localai.ui.settings.DevContextMemoryPage
import com.iris.localai.ui.settings.DeveloperPage
import com.iris.localai.ui.settings.DownloadSourcePage
import com.iris.localai.ui.settings.LanguagePage
import com.iris.localai.ui.settings.LicenseTextRoute
import com.iris.localai.ui.settings.LicensesPage
import com.iris.localai.ui.settings.PerformancePage
import com.iris.localai.ui.settings.ResourceDiagnosticsPage
import com.iris.localai.ui.settings.StoragePage
import com.iris.localai.ui.settings.TierProfilesPage
import com.iris.localai.ui.theme.IrisPalette
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisStyle
import com.iris.localai.ui.tour.LocalTour
import com.iris.localai.ui.tour.TourController
import com.iris.localai.ui.tour.TourLogic
import com.iris.localai.ui.tour.TourOverlay
import com.iris.localai.ui.tour.TourScreen
import com.iris.localai.ui.welcome.WelcomeScreen
import com.iris.localai.ui.whatsnew.UpdateNotesSheet
import com.iris.localai.util.diagnostics.CrashReporter
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
    /** 上下文、记忆和知识库 › 记忆列表 / 知识库 as real routes (push / pop animation like every subpage). */
    MEMORY_LIST, KNOWLEDGE,
    /** 智能体 › MCP 服务 and 知识库 › 新建/编辑文字 as real routes (the file to edit rides in KbEditorNav). */
    MCP, KB_EDITOR,
    /** 设置 › 下载源 (one setting for every download). */
    DOWNLOAD_SOURCE,
    /** 模型与加载 › 看懂模型。 */
    MODEL_GUIDE,
    /** 关于 › 许可证 and 单个许可证全文 as real routes (the file rides in LicenseNav). */
    LICENSES, LICENSE_TEXT,
    /** 3.0：AI 工具箱、自定义提示词。 */
    TOOLBOX, PROMPTS,
    /** 3.0.0 Beta6 主页布局. */
    LAYOUT }

class MainActivity : ComponentActivity() {

    private val chat: ChatViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(I18n.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        // fully transparent system bars (no auto scrim) and no navigation-bar contrast scrim,
        // so the chat background / page colour runs under the gesture pill. Icon light/dark is set
        // per app theme in IrisTheme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        // some labels (enums, object texts) are read once per process; after a language switch restart it.
        if (I18n.langChangedSinceStart()) {
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
        val crash = if (savedInstanceState == null) CrashReporter.takeReport(this, container.settings.current.crashReportPopup) else null
        if (savedInstanceState == null) handleIntent(intent)
        if (savedInstanceState == null) com.iris.localai.widget.QuickLaunch.publishShortcuts(this) // 3.0
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
            val quality = GlassPolicy.resolve(
                level = settings.visualEffects.coerceAtLeast(0),
                glassSwitch = !settings.solidBackground,
                powerSave = powerSave,
                thermalStatus = thermal,
                generating = streaming,
            )
            IrisTheme(
                mode = settings.themeMode, glassQuality = quality, reduceMotion = reduceMotion,
                decorativeMotion = resumed && !streaming && !powerSave && settings.visualEffects != 0,
                palette = IrisPalette.of(settings.themePalette),
                backdrop = settings.backgroundImage.isNotEmpty() && !settings.solidBackground,
                solid = settings.solidBackground,
                style = IrisStyle.of(settings.uiStyle),
            ) {
                // first-launch welcome (carries the disclaimer) gates the app until accepted;
                // fresh installs and upgrades see it once (welcomeAccepted defaults to false).
                AnimatedContent(
                    targetState = settings.welcomeAccepted,
                    transitionSpec = { motion(reduceMotion) { IrisMotion.fadeThrough() } },
                    label = "welcome",
                ) { accepted ->
                    if (accepted) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        // 新手引导 (spotlight tour): once after the welcome; replayable from 设置 › 关于与调试 › 新手引导.
                        AppNavigation(
                            chat,
                            showTour = TourLogic.shouldShow(settings.welcomeAccepted, settings.tour253Done),
                            // 全新安装引导结束时记为已看过本版「此次更新」，不紧接着再弹
                            onTourDone = { container.settings.update { it.copy(tour253Done = true).let { n -> n.copy(notesSeen = n.notesSeen.ifEmpty { BuildConfig.VERSION_NAME }) } } },
                        )
                        // 升级后首次打开弹一次「此次更新」
                        if (UpdateNotes.shouldShow(settings.welcomeAccepted, settings.tour253Done, settings.notesSeen, BuildConfig.VERSION_NAME)) {
                            UpdateNotesSheet(BuildConfig.VERSION_NAME, history = false) {
                                container.settings.update { it.copy(notesSeen = BuildConfig.VERSION_NAME) }
                            }
                        }
                    } else WelcomeScreen(
                        onStart = { container.settings.update { it.copy(welcomeAccepted = true) } },
                    )
                }
                crashText?.let { report -> CrashDialog(report, onNeverAgain = { container.settings.update { it.copy(crashReportPopup = false) } }) { crashText = null } }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A "回复已完成 / 正在生成" notification opens its conversation. */
    private fun handleIntent(intent: android.content.Intent?) {
        // 3.0：桌面快捷方式 / 小组件 / 悬浮窗
        intent?.getStringExtra(QuickNav.EXTRA)?.let { a -> intent.removeExtra(QuickNav.EXTRA); QuickNav.open(a); return }
        if (intent?.getStringExtra(com.iris.localai.service.ImageGenJobService.EXTRA_OPEN) == com.iris.localai.service.ImageGenJobService.OPEN_IMAGEGEN) {
            intent.removeExtra(com.iris.localai.service.ImageGenJobService.EXTRA_OPEN)
            com.iris.localai.service.ImageGenMonitor.requestOpen()
            return
        }
        val id = intent?.getStringExtra(GenerationService.EXTRA_CONVERSATION) ?: return
        intent.removeExtra(GenerationService.EXTRA_CONVERSATION)
        chat.openFromNotification(id)
    }
}

@Composable
private fun CrashDialog(report: String, onNeverAgain: () -> Unit, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val c = Iris.colors
    // 2.7.2：「不再提示」= 关掉 设置 › 崩溃后显示报告（关闭弹窗时生效）
    var never by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val close = { if (never) onNeverAgain(); onClose() }
    IrisDialog(
        onDismiss = close,
        title = I18n.s(R.string.ui_271348),
        confirmText = I18n.s(R.string.ui_79d3ab),
        onConfirm = { clipboard.setText(AnnotatedString(report)); close() },
        dismissText = I18n.s(R.string.ui_b15d91),
    ) {
        Column {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                Text(I18n.s(R.string.ui_406ea7), style = IrisType.secondary, color = c.textSecondary)
                Spacer(Modifier.height(IrisDimens.space8))
                SelectionContainer { Text(report, style = IrisType.monoSmall.copy(fontSize = IrisType.micro.fontSize, lineHeight = IrisType.micro.lineHeight), color = c.text) }
            }
            com.iris.localai.ui.components.SwitchRow(I18n.s(R.string.v272_crash_never), checked = never, subtitle = I18n.s(R.string.v272_crash_never_sub)) { never = it }
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
    // 生图通知点开 → 生图页
    val openImageGen by com.iris.localai.service.ImageGenMonitor.openRequests.collectAsStateWithLifecycle()
    LaunchedEffect(openImageGen) { if (openImageGen) { com.iris.localai.service.ImageGenMonitor.consumeOpen(); push(Route.IMAGEGEN) } }
    val reduce = LocalReduceMotion.current
    // 3.0：快捷方式 / 小组件 / 悬浮窗 / 灵动岛 的打开请求
    val quick by QuickNav.pending.collectAsStateWithLifecycle()
    LaunchedEffect(quick) {
        val q = quick ?: return@LaunchedEffect
        QuickNav.consume()
        when (q) {
            QuickNav.NEW_CHAT -> { chat.newChat(); stack = listOf(Route.CHAT.name) }
            QuickNav.VOICE -> stack = listOf(Route.CHAT.name)
            QuickNav.IMAGEGEN -> push(Route.IMAGEGEN)
            QuickNav.TOOLBOX, QuickNav.FLOATING -> push(Route.TOOLBOX)
            QuickNav.GUIDE -> push(Route.MODEL_GUIDE)
            QuickNav.DOWNLOADS -> push(Route.DOWNLOAD_MODELS)
        }
    }
    val appSettings by chat.settings.collectAsStateWithLifecycle()

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

    // 新手引导: the tour drives this stack (normal push / pop transitions) and overlays everything.
    val tour = remember { TourController() }
    tour.onFinish = { stack = listOf(Route.CHAT.name); onTourDone() }
    LaunchedEffect(showTour) { if (showTour) { if (!tour.active) tour.start() } else { tour.active = false } }
    fun tourNavigate(screen: TourScreen) {
        val target = tourStack(screen).map { it.name }
        if (stack != target) stack = target
    }

    val density = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.runtime.CompositionLocalProvider(LocalTour provides tour) {
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
                shape = com.iris.localai.ui.theme.smoothShape((IrisRadius.sheet.value * p).dp)  // continuous corners like every other surface
                clip = p > 0f
            } else Modifier
            androidx.compose.foundation.layout.Box(gestureModifier) {
                Screen(r, chat, settingsVm, push = ::push, pop = ::pop, home = { stack = listOf(Route.CHAT.name) })
            }
        }
    }
    // 3.0 应用内灵动岛：所在页面自己显示进度的任务种类不上岛
    val top = stack.last()
    com.iris.localai.ui.island.DynamicIsland(
        enabled = appSettings.dynamicIsland && !tour.active,
        bottomPad = if (top == Route.CHAT.name) 146f else 16f, // 对话页放在输入框卡片上方
        hidden = buildSet {
            if (top == Route.IMAGEGEN.name) { add(com.iris.localai.task.TaskProgress.Kind.IMAGE_GEN); add(com.iris.localai.task.TaskProgress.Kind.DOWNLOAD) } // 生图页的模型卡片自己显示下载进度
            if (top == Route.DOWNLOAD_MODELS.name) add(com.iris.localai.task.TaskProgress.Kind.DOWNLOAD)
            if (top == Route.CHAT.name) add(com.iris.localai.task.TaskProgress.Kind.OTHER) // 对话页底部已有加载行
        },
        onOpen = { t ->
            when (t.kind) {
                com.iris.localai.task.TaskProgress.Kind.IMAGE_GEN -> push(Route.IMAGEGEN)
                com.iris.localai.task.TaskProgress.Kind.DOWNLOAD -> push(Route.DOWNLOAD_MODELS)
                com.iris.localai.task.TaskProgress.Kind.OTHER -> if (t.id == com.iris.localai.task.LoadIsland.ID) stack = listOf(Route.CHAT.name)
                else -> {}
            }
        },
    )
    if (tour.active) TourOverlay(tour, navigate = ::tourNavigate)
    }
    }
}

/** 新手引导: the route stack that shows a tour step's screen (DRAWER = chat, its drawer opened by ChatScreen). */
private fun tourStack(screen: TourScreen): List<Route> = when (screen) {
    TourScreen.CHAT, TourScreen.DRAWER -> listOf(Route.CHAT)
    TourScreen.MODELS -> listOf(Route.CHAT, Route.MODELS)
    TourScreen.SETTINGS -> listOf(Route.CHAT, Route.SETTINGS)
    TourScreen.GENERATION -> listOf(Route.CHAT, Route.SETTINGS, Route.GENERATION)
    TourScreen.PERFORMANCE -> listOf(Route.CHAT, Route.SETTINGS, Route.PERFORMANCE)
    TourScreen.VOICE -> listOf(Route.CHAT, Route.SETTINGS, Route.VOICE)
    TourScreen.CONTEXT -> listOf(Route.CHAT, Route.SETTINGS, Route.MEMORY)
    TourScreen.AGENT -> listOf(Route.CHAT, Route.SETTINGS, Route.AGENT)
}

@Composable
private fun Screen(r: Route, chat: ChatViewModel, settingsVm: SettingsViewModel, push: (Route) -> Unit, pop: () -> Unit, home: () -> Unit = {}) {
    DownloadNav.opener = { push(Route.DOWNLOAD_MODELS) } // 2.6.2
    ModelGuideNav.opener = { push(Route.MODEL_GUIDE) } // 2.6.4
    when (r) {
        Route.CHAT -> ChatScreen(
            vm = chat,
            onOpenModels = { push(Route.MODELS) },
            onOpenSettings = { push(Route.SETTINGS) },
            onOpenVoiceSettings = { push(Route.VOICE) },
            onOpenApi = { push(Route.API) },
            onOpenFiles = { push(Route.APP_FILES) }, // sidebar 文件 = the whole Download/IRIDIS
            onOpenImageGen = { push(Route.IMAGEGEN) },
            onOpenCharacters = { push(Route.CHARACTERS) },
            onOpenToolbox = { push(Route.TOOLBOX) },
        )
        // 角色卡: tap a card = new chat with it, back on the chat screen
        Route.CHARACTERS -> CharactersPage(onBack = pop, onStartChat = { id -> chat.startCharacterChat(id); home() })
        Route.IMAGEGEN -> ImageGenScreen(onBack = pop, onSendToChat = { path -> chat.attachGeneratedImage(path); pop() })
        // one 「模型与加载」 page (models + 档位 + load settings); 设置 › 模型与加载 opens it too
        Route.MODELS -> ModelsScreen(onBack = pop, settingsVm = settingsVm, onOpenDownload = { push(Route.DOWNLOAD_MODELS) }, onOpenGuide = { push(Route.MODEL_GUIDE) })
        Route.MODEL_GUIDE -> ModelGuidePage(onBack = pop)
        // C: full 下载模型 page; 「试一试」 puts the demo prompt into the chat input
        Route.DOWNLOAD_MODELS -> DownloadModelsScreen(onBack = pop, onTryPrompt = { p -> chat.setDraft(p); home() })
        Route.APPEARANCE -> AppearancePage(settingsVm, onBack = pop)
        Route.LANGUAGE -> LanguagePage(onBack = pop)
        Route.DOWNLOAD_SOURCE -> DownloadSourcePage(settingsVm, onBack = pop)
        Route.SETTINGS -> SettingsHome(
            settingsVm, onBack = pop,
            onOpen = { p -> push(routeOf(p)) },
        )
        Route.GENERATION -> GenerationPage(settingsVm, onBack = pop)
        Route.CONTEXT, Route.MEMORY -> {
            // F: one 上下文 · 记忆 · 知识库 page; the old controls are under 开发者选项.
            val ui by chat.state.collectAsStateWithLifecycle()
            ContextMemoryPage(
                settingsVm, ui.context, onBack = pop, onDeveloper = { push(Route.DEVELOPER) },
                onOpenList = { push(Route.MEMORY_LIST) }, onOpenKnowledge = { push(Route.KNOWLEDGE) },
            )
        }
        Route.MEMORY_LIST -> MemoryListPage(onBack = pop)
        Route.KNOWLEDGE -> KnowledgePage(onBack = pop, onOpenEditor = { push(Route.KB_EDITOR) })
        Route.KB_EDITOR -> KbEditorRoute(onBack = pop)
        Route.DEV_CONTEXT -> DevContextMemoryPage(
            settingsVm, onBack = pop, onOpenList = { push(Route.MEMORY_LIST) }, onOpenKnowledge = { push(Route.KNOWLEDGE) },
        )
        Route.PERFORMANCE -> PerformancePage(settingsVm, onBack = pop, onOpen = { p -> push(routeOf(p)) })
        Route.TIER_PROFILES -> TierProfilesPage(settingsVm, onBack = pop)
        Route.VOICE -> VoicePage(settingsVm, onBack = pop)
        Route.API -> ApiPage(settingsVm, onBack = pop)
        Route.ABOUT -> AboutPage(onBack = pop, onDeveloper = { push(Route.DEVELOPER) }, onOpenLicenses = { push(Route.LICENSES) })
        Route.LICENSES -> LicensesPage(onBack = pop, onOpenFile = { push(Route.LICENSE_TEXT) })
        Route.LICENSE_TEXT -> LicenseTextRoute(onBack = pop)
        Route.DEVELOPER -> DeveloperPage(settingsVm, onBack = pop, onOpen = { p -> push(routeOf(p)) })
        Route.DIAGNOSTICS -> ResourceDiagnosticsPage(settingsVm, onBack = pop)
        Route.AGENT -> AgentSettingsPage(
            onBack = pop, onOpenBrowser = { push(Route.BROWSER) }, onOpenFiles = { push(Route.FILES) }, onOpenMcp = { push(Route.MCP) },
        )
        Route.MCP -> McpPage(onBack = pop)
        Route.TOOLBOX -> com.iris.localai.ui.toolbox.ToolboxPage(chat, onBack = pop, onOpenImageGen = { push(Route.IMAGEGEN) })
        Route.PROMPTS -> com.iris.localai.ui.prompts.PromptsPage(settingsVm, onBack = pop)
        Route.LAYOUT -> com.iris.localai.ui.settings.LayoutPage(settingsVm, onBack = pop)
        Route.FILES -> FilesPage(onBack = pop)
        Route.BROWSER -> BrowserPage(onBack = pop)
        Route.APP_FILES -> AppFilesPage(onBack = pop)
        Route.STORAGE -> StoragePage(
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
    SettingsPage.PROMPTS -> Route.PROMPTS
    SettingsPage.LAYOUT -> Route.LAYOUT
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

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
private enum class Route { APPEARANCE, CHAT, MODELS, SETTINGS, GENERATION, VOICE, API, ABOUT, DIAGNOSTICS, MODEL_BACKEND, CONTEXT, PERFORMANCE, TIER_PROFILES, AGENT, MEMORY, BROWSER, FILES, STORAGE, DEVELOPER, IMAGEGEN, APP_FILES }

class MainActivity : ComponentActivity() {

    private val chat: ChatViewModel by viewModels()

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
                glassSwitch = true,
                powerSave = powerSave,
                thermalStatus = thermal,
                generating = streaming,
            )
            IrisTheme(
                mode = settings.themeMode, glassQuality = quality, reduceMotion = reduceMotion,
                decorativeMotion = resumed && !streaming && !powerSave && settings.visualEffects != 0,
                palette = com.iris.localai.ui.theme.IrisPalette.of(settings.themePalette),
                backdrop = settings.backgroundImage.isNotEmpty(),
            ) {
                // 1.14.7: first-launch welcome (carries the disclaimer) gates the app until accepted;
                // fresh installs and upgrades see it once (welcomeAccepted defaults to false).
                AnimatedContent(
                    targetState = settings.welcomeAccepted,
                    transitionSpec = { motion(reduceMotion) { IrisMotion.fadeThrough() } },
                    label = "welcome",
                ) { accepted ->
                    if (accepted) AppNavigation(chat)
                    else com.iris.localai.ui.welcome.WelcomeScreen(
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
        title = "上次 IRIDIS 意外退出了",
        confirmText = "复制",
        onConfirm = { clipboard.setText(AnnotatedString(report)); onClose() },
        dismissText = "关闭",
    ) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("复制下面的信息发给开发者，可以更快定位问题。", style = IrisType.secondary, color = c.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            Text(report, style = IrisType.monoSmall.copy(fontSize = IrisType.micro.fontSize, lineHeight = IrisType.micro.lineHeight), color = c.text)
        }
    }
}

@Composable
private fun AppNavigation(chat: ChatViewModel) {
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

    val density = androidx.compose.ui.platform.LocalDensity.current.density
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
                Screen(r, chat, settingsVm, push = ::push, pop = ::pop)
            }
        }
    }
}

@Composable
private fun Screen(r: Route, chat: ChatViewModel, settingsVm: SettingsViewModel, push: (Route) -> Unit, pop: () -> Unit) {
    when (r) {
        Route.CHAT -> ChatScreen(
            vm = chat,
            onOpenModels = { push(Route.MODELS) },
            onOpenSettings = { push(Route.SETTINGS) },
            onOpenVoiceSettings = { push(Route.VOICE) },
            onOpenApi = { push(Route.API) },
            onOpenFiles = { push(Route.APP_FILES) }, // 2.2: sidebar 文件 = the whole Download/IRIDIS
            onOpenImageGen = { push(Route.IMAGEGEN) },
        )
        Route.IMAGEGEN -> com.iris.localai.ui.imagegen.ImageGenScreen(onBack = pop, onSendToChat = { path -> chat.attachGeneratedImage(path); pop() })
        Route.MODELS -> ModelsScreen(onBack = pop)
        Route.APPEARANCE -> com.iris.localai.ui.settings.AppearancePage(settingsVm, onBack = pop)
        Route.SETTINGS -> SettingsHome(
            settingsVm, onBack = pop,
            onOpen = { p -> push(routeOf(p)) },
            onModels = { push(Route.MODELS) },
        )
        Route.MODEL_BACKEND -> com.iris.localai.ui.settings.ModelBackendPage(settingsVm, onBack = pop, onModels = { push(Route.MODELS) })
        Route.GENERATION -> GenerationPage(settingsVm, onBack = pop)
        Route.CONTEXT -> com.iris.localai.ui.settings.ContextPage(settingsVm, onBack = pop)
        Route.PERFORMANCE -> com.iris.localai.ui.settings.PerformancePage(settingsVm, onBack = pop, onOpen = { p -> push(routeOf(p)) })
        Route.TIER_PROFILES -> com.iris.localai.ui.settings.TierProfilesPage(settingsVm, onBack = pop)
        Route.VOICE -> VoicePage(settingsVm, onBack = pop)
        Route.API -> ApiPage(settingsVm, onBack = pop)
        Route.ABOUT -> AboutPage(onBack = pop, onDeveloper = { push(Route.DEVELOPER) })
        Route.DEVELOPER -> com.iris.localai.ui.settings.DeveloperPage(settingsVm, onBack = pop, onOpen = { p -> push(routeOf(p)) })
        Route.DIAGNOSTICS -> com.iris.localai.ui.settings.ResourceDiagnosticsPage(settingsVm, onBack = pop)
        Route.AGENT -> com.iris.localai.ui.agent.AgentSettingsPage(onBack = pop, onOpenBrowser = { push(Route.BROWSER) }, onOpenFiles = { push(Route.FILES) })
        Route.FILES -> com.iris.localai.ui.agent.FilesPage(onBack = pop)
        Route.BROWSER -> com.iris.localai.ui.agent.BrowserPage(onBack = pop)
        Route.APP_FILES -> com.iris.localai.ui.files.AppFilesPage(onBack = pop)
        Route.MEMORY -> com.iris.localai.ui.settings.ContextPage(settingsVm, onBack = pop) // 2.1.3: one 记忆与上下文 page
        Route.STORAGE -> com.iris.localai.ui.settings.StoragePage(
            settingsVm, onBack = pop,
            onOpen = { p -> push(routeOf(p)) },
            onModels = { push(Route.MODELS) },
            onOpenFiles = { push(Route.FILES) },
        )
    }
}

private fun routeOf(p: SettingsPage): Route = when (p) {
    SettingsPage.APPEARANCE -> Route.APPEARANCE
    SettingsPage.HOME -> Route.SETTINGS
    SettingsPage.MODEL_BACKEND -> Route.MODEL_BACKEND
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

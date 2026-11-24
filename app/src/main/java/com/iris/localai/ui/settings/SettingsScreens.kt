package com.iris.localai.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.aspectRatio
import com.iris.localai.I18n
import com.iris.localai.IrisApp
import com.iris.localai.agent.tools.ScreenTool
import com.iris.localai.agent.tools.WebFetchTool
import com.iris.localai.agent.web.WebSearchTool
import com.iris.localai.context.ContextAssembly
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.CtxScale
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.TokenLimit
import com.iris.localai.engine.EngineFactory
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.NpuState
import com.iris.localai.engine.NpuSupport
import com.iris.localai.engine.NpuVendor
import com.iris.localai.engine.llama.LlamaHtpRuntime
import com.iris.localai.engine.resources.CoolDown
import com.iris.localai.engine.resources.LiveResourcePolicy
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.engine.resources.ThermalCutoff
import com.iris.localai.engine.resources.ThermalGovernor
import com.iris.localai.ui.agent.AgentText
import com.iris.localai.ui.agent.MemorySettingsPage
import com.iris.localai.ui.chat.SpeedLevel
import com.iris.localai.ui.components.Fold
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.components.TierSlider
import com.iris.localai.ui.components.ValueText
import com.iris.localai.ui.glass.GlassPolicy
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.models.SourceText
import com.iris.localai.ui.theme.BackgroundImage
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.theme.IrisStyle
import com.iris.localai.ui.whatsnew.UpdateNotesSheet
import com.iris.localai.util.VisionQuality
import com.iris.localai.util.diagnostics.CrashDump
import com.iris.localai.util.diagnostics.SystemStatus
import com.iris.localai.util.io.BackgroundGuard
import com.iris.localai.voice.PackState
import com.iris.localai.ui.tour.TourId
import com.iris.localai.ui.tour.tourTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.BuildConfig
import com.iris.localai.R
import com.iris.localai.data.ThemeMode
import com.iris.localai.ui.components.Chevron
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.AdvancedFold
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedRow
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.CheckRow
import androidx.compose.foundation.verticalScroll
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.components.TextInputDialog
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.components.VersionFooter
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.chatScaled
import com.iris.localai.ui.theme.IrisType
import java.util.Locale
import kotlin.math.roundToInt


/**
 * 设置整理: the settings are grouped by what they do. HOME only links to the groups
 * (plus the few one-tap items: theme, visual effects, delete all); each group is its own page,
 * with rarely used items folded under 「高级」. No storage key or default value changed.
 */
/** DEVELOPER = 开发者选项 (reached from 关于 IRIDIS); CONTEXT and MEMORY open the same 「记忆与上下文」 page. */
enum class SettingsPage { APPEARANCE, LANGUAGE, HOME, MODEL_BACKEND, GENERATION, CONTEXT, PERFORMANCE, TIER_PROFILES, VOICE, API, ABOUT, DIAGNOSTICS, AGENT, MEMORY, STORAGE, DEVELOPER, DEV_CONTEXT, DOWNLOAD_SOURCE, PROMPTS, LAYOUT }

private val THEME_OPTIONS get() = listOf(ThemeMode.SYSTEM to I18n.s(R.string.settings_71bbc7), ThemeMode.LIGHT to I18n.s(R.string.settings_48d0a0), ThemeMode.DARK to I18n.s(R.string.settings_41e8e8))

/** One-line descriptions, kept in one place so the wording stays consistent (unit-tested). */
object SettingsText {
    val MODEL_BACKEND: String get() = I18n.s(R.string.settings_90aa74)
    val GENERATION: String get() = I18n.s(R.string.settings_28a1cc)
    val CONTEXT: String get() = I18n.s(R.string.settings_0f3195)
    /** 关于 IRIDIS › 开发者选项. */
    val DEVELOPER: String get() = I18n.s(R.string.settings_d6e5b3)
    val PERFORMANCE: String get() = I18n.s(R.string.settings_3969dc)
    val VOICE: String get() = I18n.s(R.string.settings_078b04)
    val API_OFF: String get() = I18n.s(R.string.settings_21d98a)
    val THEME: String get() = I18n.s(R.string.settings_37200b)
    val APPEARANCE: String get() = I18n.s(R.string.settings_b4c216)
    val DELETE_ALL: String get() = I18n.s(R.string.settings_4566dc)
    /** 设置 › 存储空间. */
    val STORAGE: String get() = I18n.s(R.string.settings_7c3347)
    val DIAGNOSTICS: String get() = I18n.s(R.string.settings_2f5693)
    val TIER_PROFILES: String get() = I18n.s(R.string.settings_cbe1b9)
    val THINKING_BUDGET: String get() = I18n.s(R.string.settings_4e15bc)
    val MAX_NEW: String get() = I18n.s(R.string.settings_53a86d)
    val ADVANCED: String get() = I18n.s(R.string.components_dfac15)
    /** Second line of the centred version footer on the settings home page (1.8.0). */
    val TAGLINE: String get() = I18n.s(R.string.settings_5b702b)

    /** First line of the version footer: "IRIDIS 1.8.0-full" (1.14.5 rename). */
    fun versionLine(versionName: String) = "IRIDIS $versionName"

    /** The pages HOME links to, with their titles: the structure the 1.7.8 tests check. */
    val HOME_GROUPS: List<Pair<String, List<String>>> get() = listOf(
        I18n.s(R.string.settings_3dc3df) to listOf(I18n.s(R.string.chat_035516), I18n.s(R.string.settings_4dfe70), ContextMemoryText.NAV, I18n.s(R.string.settings_2b7c2b)),
        I18n.s(R.string.settings_6f492c) to listOf(I18n.s(R.string.settings_b9a753), I18n.s(R.string.settings_2a59e7)),
        I18n.s(R.string.settings_379b88) to listOf(I18n.s(R.string.settings_afcde2)),
        I18n.s(R.string.settings_0d8307) to listOf(I18n.s(R.string.settings_5b7998), I18n.s(R.string.settings_12ecbe)),
        I18n.s(R.string.settings_78944f) to listOf(I18n.s(R.string.settings_699379)), // 资源诊断 moved to 关于 IRIDIS › 开发者选项
    )
}

@Composable
private fun NavItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SettingsRow(title, icon = icon, subtitle = subtitle, onClick = onClick, trailing = { Chevron() })
}

/**
 * crash diagnostics: exports the last native crash (tombstone + PSS/RSS/时间), the MNN
 * load-stage log and a filtered logcat snapshot to the Download folder
 * (iris_tombstone.bin / iris_crash.txt / iris_mnn_load.log / iris_logcat.txt).
 */
@Composable
internal fun CrashDumpRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CrashDump.Result?>(null) }
    SettingsRow(
        I18n.s(R.string.settings_e45c28), icon = Icons.Outlined.BugReport,
        subtitle = if (busy) I18n.s(R.string.settings_0b6cea) else result?.let { r ->
            r.note + I18n.s(R.string.settings_dec892) + r.items.joinToString(I18n.s(R.string.settings_c4530d)) { it.name + if (it.bytes > 0) " (${it.bytes / 1024} KB)" else I18n.s(R.string.settings_ac85da) + (it.note?.let { n -> I18n.s(R.string.settings_67f810, n) } ?: "") }
        } ?: I18n.s(R.string.settings_1ca943),
        subtitleMaxLines = 6,
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.IO) { CrashDump.export(context) }
                busy = false
            }
        },
    )
}

@Composable
fun SettingsHome(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val api by vm.apiStatus.collectAsStateWithLifecycle()
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    var showNotes by rememberSaveable { mutableStateOf(false) } // 2.5.5

    SubpageScaffold(I18n.s(R.string.chat_e366cc), onBack) {
        ScrollBody {
            // the 运行状态 rack moved to 关于 IRIDIS › 开发者选项 (DeveloperPage).
            SectionTitle(I18n.s(R.string.settings_3dc3df))
            SettingsGroup {
                NavItem(I18n.s(R.string.chat_035516), SettingsText.MODEL_BACKEND, IrisIcons.Chip) { onOpen(SettingsPage.MODEL_BACKEND) }
                Divider()
                // one 下载源 for every download (models, 生图, 翻译包, 语音); the per-page pickers are gone.
                NavItem(SourceText.TITLE, s.downloadSource.label, IrisIcons.Download) { onOpen(SettingsPage.DOWNLOAD_SOURCE) }
                Divider()
                NavItem(I18n.s(R.string.settings_4dfe70), SettingsText.GENERATION, IrisIcons.Spark) { onOpen(SettingsPage.GENERATION) }
                Divider()
                NavItem(
                    I18n.s(R.string.settings_2b7c2b), I18n.s(R.string.settings_40a98a, ResourceTier.of(s.resourceTier).label),
                    IrisIcons.Gauge,
                ) { onOpen(SettingsPage.PERFORMANCE) }
                Divider()
                // 智能体 (tools, permissions, workspace). Not part of the 1.7.8 HOME_GROUPS structure test.
                NavItem(I18n.s(R.string.chat_72fbb9), AgentText.NAV, IrisIcons.Agent) { onOpen(SettingsPage.AGENT) }
                Divider()
                // 「上下文与记忆」 + 「记忆」 → one entry 「记忆与上下文」 (one page, context sections first).
                NavItem(ContextMemoryText.NAV, ContextMemoryText.NAV_SUB, IrisIcons.Memory) { onOpen(SettingsPage.MEMORY) }
                Divider()
                // 3.0 可自定全部提示词
                NavItem(I18n.s(R.string.v30_prompts), I18n.s(R.string.v30_prompts_nav), IrisIcons.Prompt) { onOpen(SettingsPage.PROMPTS) }
            }

            SectionTitle(I18n.s(R.string.settings_6f492c))
            SettingsGroup {
                NavItem(I18n.s(R.string.settings_b9a753), SettingsText.VOICE, IrisIcons.Voice) { onOpen(SettingsPage.VOICE) }
                Divider()
                NavItem(I18n.s(R.string.settings_2a59e7), if (api.running) I18n.s(R.string.settings_27de1c, api.port) else SettingsText.API_OFF, IrisIcons.Plug) { onOpen(SettingsPage.API) }
            }

            SectionTitle(I18n.s(R.string.settings_379b88))
            SettingsGroup {
                NavItem(I18n.s(R.string.settings_afcde2), SettingsText.APPEARANCE, IrisIcons.Contrast) { onOpen(SettingsPage.APPEARANCE) }
                Divider()
                // 3.0.0 Beta6 自定布局
                NavItem(I18n.s(R.string.b6_layout), I18n.s(R.string.b6_layout_sub), IrisIcons.Layers) { onOpen(SettingsPage.LAYOUT) }
                Divider()
                // 语言 is its own entry (moved out of 外观).
                val ctx = LocalContext.current
                val tag = remember { I18n.current(ctx) }
                NavItem(I18n.s(R.string.set234_lang),
                    I18n.LANGS.firstOrNull { it.first == tag }?.second ?: I18n.s(R.string.lang_system),
                    IrisIcons.Globe) { onOpen(SettingsPage.LANGUAGE) }
            }

            SectionTitle(I18n.s(R.string.settings_0d8307))
            SettingsGroup {
                // 存储空间 (sizes by category, clear / delete per item).
                NavItem(I18n.s(R.string.settings_5b7998), SettingsText.STORAGE, IrisIcons.Storage) { onOpen(SettingsPage.STORAGE) }
                Divider()
                // HyperOS destructive row: red text, no icon.
                SettingsRow(
                    I18n.s(R.string.settings_12ecbe), titleColor = Iris.colors.danger,
                    subtitle = SettingsText.DELETE_ALL, onClick = { confirmWipe = true },
                )
            }

            SectionTitle(I18n.s(R.string.settings_78944f))
            SettingsGroup {
                // 资源诊断 (with 故障排查) moved to 关于 IRIDIS › 开发者选项.
                NavItem(I18n.s(R.string.settings_699379), I18n.s(R.string.settings_45020f, BuildConfig.VERSION_NAME), IrisIcons.Info) { onOpen(SettingsPage.ABOUT) }
                Divider()
                // 新手引导: replay the spotlight tour (MainActivity runs it while tour253Done is false).
                Box(Modifier.tourTarget(TourId.REPLAY)) {
                    NavItem(I18n.s(R.string.tour253_replay), I18n.s(R.string.tour253_replay_sub), IrisIcons.Spark) { vm.update { it.copy(tour253Done = false) } }
                }
                Divider()
                // 更新历史
                NavItem(I18n.s(R.string.v255_notes_history), I18n.s(R.string.v255_notes_history_sub), IrisIcons.Info) { showNotes = true }
            }
            VersionFooter(SettingsText.versionLine(BuildConfig.VERSION_NAME), SettingsText.TAGLINE)
        }
    }

    if (showNotes) UpdateNotesSheet(BuildConfig.VERSION_NAME, history = true) { showNotes = false }
    if (confirmWipe) {
        ConfirmDialog(
            title = I18n.s(R.string.settings_1db0aa),
            message = I18n.s(R.string.settings_c63448),
            confirmText = I18n.s(R.string.chat_2f4aad),
            onConfirm = { vm.deleteAllConversations() },
            onDismiss = { confirmWipe = false },
        )
    }
}

/**
 * the load settings (formerly the page 设置 › 模型与加载), shown at the bottom of the merged
 * 「模型与加载」 page ([com.iris.localai.ui.models.ModelsScreen]): one page for models, 档位 and loading.
 */
@Composable
fun ColumnScope.ModelLoadSettings(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().padding(end = IrisDimens.groupGutter), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(I18n.s(R.string.models_4793ea)) }
                IrisButton(I18n.s(R.string.settings_7468f3), vm::resetLoadOptions, style = IrisButtonStyle.Text, compact = true)
            }
            SettingsGroup {
                SwitchRow(
                    I18n.s(R.string.settings_1c8600), s.autoLoadLastModel, icon = IrisIcons.Power,
                    subtitle = I18n.s(R.string.settings_b6c8f1),
                ) { on -> vm.update { it.copy(autoLoadLastModel = on) } }
                Divider()
                DefaultModelRow(vm, s.defaultModelFile)
                Divider()
                VisionQualityRow(vm, s.visionSide)
                Divider()
                SettingsRow(I18n.s(R.string.settings_41ba97), icon = IrisIcons.Reload, subtitle = I18n.s(R.string.settings_1a7844), onClick = { vm.reloadModel() })
            }


            SectionTitle(I18n.s(R.string.settings_494ef0))
            SettingsGroup {
                // QAIRT bundles only run on the NPU — nothing to choose.
                val formats = EngineFactory.supportedFormats.filter { it != ModelFormat.QAIRT }
                // GGUF on the NPU runs on GenieX; the NPU choice is offered once its runtime is installed.
                val gxReady = vm.geniex?.state?.collectAsState()?.value is NpuState.Installed
                formats.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val cur = s.defaultBackends[f.name]
                    // GGUF can pick NPU (= GenieX) in the full build; the backend decides the engine.
                    val htpOk = f == ModelFormat.GGUF && LlamaHtpRuntime.available(androidx.compose.ui.platform.LocalContext.current)
                    val withNpu = f == ModelFormat.GGUF && (htpOk || vm.geniex != null && (gxReady || cur == "NPU"))
                    SegmentedRow(
                        f.label, if (withNpu) listOf(I18n.s(R.string.settings_3aed2c), "CPU", "GPU", "NPU") else listOf(I18n.s(R.string.settings_3aed2c), "CPU", "GPU"),
                        when (cur) { "CPU" -> 1; "GPU" -> 2; "NPU" -> if (withNpu) 3 else 0; else -> 0 },
                        { idx -> vm.setDefaultBackend(f, when (idx) { 1 -> "CPU"; 2 -> "GPU"; 3 -> "NPU"; else -> null }) },
                        // no standing per-format notes (the model sheet shows backend details).
                    )
                }
            }

            // one NPU section: the QNN runtime and (full flavour) GenieX in one card, one short note.
            if (vm.npu != null || vm.geniex != null) {
                val mtk = vm.npu?.vendor == NpuVendor.MEDIATEK
                SectionTitle(if (mtk) "NPU" else I18n.s(R.string.npu27_section))
                SettingsGroup {
                    vm.npu?.let { npu -> NpuRuntimeRow(npu, onInstall = vm::installNpu, onCancel = vm::cancelNpu, onDelete = vm::deleteNpu) }
                    vm.geniex?.let { gx -> GenieXRuntimeRow(gx, onInstall = vm::installGeniex, onCancel = vm::cancelGeniex, onDelete = vm::deleteGeniex) }
                }
                if (vm.geniex != null) GroupFootnote(I18n.s(R.string.settings_9a3ae4))
            }

            // 识图（mmproj）：GGUF 视觉投影器跑 CPU 还是 GPU；GPU = 原「识图用 GPU」开关打开（沿用 visionGpu，不迁移）。
            SettingsGroup {
                SegmentedRow(
                    I18n.s(R.string.vg245_title), listOf("CPU", "GPU"), if (s.visionGpu) 1 else 0,
                    { idx -> vm.update { it.copy(visionGpu = idx == 1) } },
                    subtitle = I18n.s(R.string.vg27b_note),
                )
            }

            // 加载选项 moved to 设置 › 生成 (模型与加载 manages models only).
}

/** 设置 › 生成. */
@Composable
fun GenerationPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var editPrompt by rememberSaveable { mutableStateOf(false) }
    fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
    SubpageScaffold(
        I18n.s(R.string.settings_4dfe70), onBack,
        actions = { IrisButton(I18n.s(R.string.settings_7468f3), vm::resetGeneration, style = IrisButtonStyle.Text, compact = true) },
    ) {
        ScrollBody {
            SectionTitle(I18n.s(R.string.settings_4579b7))
            SettingsGroup {
                SettingsRow(
                    I18n.s(R.string.settings_fba64a), icon = IrisIcons.Prompt,
                    subtitle = s.systemPrompt.ifBlank { I18n.s(R.string.settings_55a43c) }, subtitleMaxLines = 2, onClick = { editPrompt = true },
                )
                Divider()
                SwitchRow(
                    I18n.s(R.string.models_1d878d), s.deepThinking, icon = IrisIcons.Think,
                    subtitle = I18n.s(R.string.settings_18c5b3),
                ) { on -> vm.update { it.copy(deepThinking = on) } }
                Divider()
                SliderRow(
                    I18n.s(R.string.settings_1727e1), s.thinkingBudget.toFloat(), 0f..8192f,
                    if (s.thinkingBudget == 0) I18n.s(R.string.settings_7ce421) else "${s.thinkingBudget} tokens",
                    subtitle = SettingsText.THINKING_BUDGET + I18n.s(R.string.settings_d852f9),
                    steps = 31,
                ) { v -> vm.update { it.copy(thinkingBudget = (v / 256).toInt() * 256) } }
            }

            SectionTitle(I18n.s(R.string.settings_74de60))
            SettingsGroup {
                SliderRow(I18n.s(R.string.settings_fa7b28), TokenLimit.toSlider(s.maxNewTokens, 8192, 256), 256f..8448f, TokenLimit.label(s.maxNewTokens, " tokens"), subtitle = SettingsText.MAX_NEW) { v -> vm.update { it.copy(maxNewTokens = TokenLimit.fromSlider(v, 8192, 256)) } }
                Divider()
                Box(Modifier.tourTarget(TourId.TEMPERATURE)) {
                SliderRow(I18n.s(R.string.settings_c9bf0b), s.temperature, 0f..2f, f2(s.temperature), subtitle = I18n.s(R.string.settings_752316)) { v -> vm.update { it.copy(temperature = (v * 20).toInt() / 20f) } }
                }
                Divider()
                AdvancedFold("sampling", SettingsText.ADVANCED) {
                    Divider()
                    SliderRow("Top-P", s.topP, 0.05f..1f, f2(s.topP), subtitle = I18n.s(R.string.settings_0f59ca)) { v -> vm.update { it.copy(topP = (v * 20).toInt() / 20f) } }
                    Divider()
                    SliderRow("Top-K", s.topK.toFloat(), 0f..100f, if (s.topK == 0) I18n.s(R.string.ui_b15d91) else s.topK.toString(), subtitle = I18n.s(R.string.settings_b7795b)) { v -> vm.update { it.copy(topK = v.toInt()) } }
                    Divider()
                    SliderRow("Min-P", s.minP, 0f..0.3f, f2(s.minP), subtitle = I18n.s(R.string.settings_a915d4)) { v -> vm.update { it.copy(minP = (v * 100).toInt() / 100f) } }
                    Divider()
                    SliderRow(I18n.s(R.string.settings_43a45e), s.repeatPenalty, 1f..1.5f, f2(s.repeatPenalty), subtitle = I18n.s(R.string.settings_09ea9f)) { v -> vm.update { it.copy(repeatPenalty = (v * 100).toInt() / 100f) } }
                    Divider()
                    SliderRow(I18n.s(R.string.settings_e4596e), s.repeatLastN.toFloat(), 0f..512f, "${s.repeatLastN} tokens", subtitle = I18n.s(R.string.settings_818868)) { v -> vm.update { it.copy(repeatLastN = (v / 16).toInt() * 16) } }
                    Divider()
                    SliderRow(I18n.s(R.string.settings_8f9adc), s.presencePenalty, -2f..2f, f2(s.presencePenalty), subtitle = I18n.s(R.string.settings_69e836)) { v -> vm.update { it.copy(presencePenalty = (v * 20).toInt() / 20f) } }
                    Divider()
                    SliderRow(I18n.s(R.string.settings_4de5f1), s.frequencyPenalty, -2f..2f, f2(s.frequencyPenalty), subtitle = I18n.s(R.string.settings_531e21)) { v -> vm.update { it.copy(frequencyPenalty = (v * 20).toInt() / 20f) } }
                    Divider()
                    SwitchRow(I18n.s(R.string.settings_8d2639), s.seed >= 0, subtitle = if (s.seed >= 0) I18n.s(R.string.settings_bc4200, s.seed) else I18n.s(R.string.settings_2beef4)) { on ->
                        vm.update { it.copy(seed = if (on) 42 else -1) }
                    }
                }
            }
            GroupFootnote(I18n.s(R.string.settings_3b9f4b))

            // every load option sits behind 「高级」 (defaults unchanged).
            Row(Modifier.fillMaxWidth().padding(end = IrisDimens.groupGutter), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(I18n.s(R.string.settings_04e3d1)) }
                IrisButton(I18n.s(R.string.settings_7468f3), vm::resetLoadTuning, style = IrisButtonStyle.Text, compact = true)
            }
            SettingsGroup {
                AdvancedFold("load", SettingsText.ADVANCED) {
                    SwitchRow("Flash Attention", s.flashAttention, subtitle = I18n.s(R.string.settings_c67060)) { on -> vm.update { it.copy(flashAttention = on) } }
                    Divider()
                    SegmentedRow(
                        I18n.s(R.string.settings_1747d7), listOf("F16", "Q8_0", "Q4_0"), s.kvCacheType.coerceIn(0, 2),
                        { i -> vm.update { it.copy(kvCacheType = i) } },
                        subtitle = if (s.flashAttention) I18n.s(R.string.settings_b9637e) else I18n.s(R.string.settings_1cf9cd),
                    )
                    Divider()
                    SliderRow(
                        I18n.s(R.string.settings_b65a0d), s.batchSize.toFloat(), 0f..2048f,
                        if (s.batchSize == 0) I18n.s(R.string.settings_eb0286, vm.tierBatch(s.resourceTier)) else "${s.batchSize}",
                        subtitle = I18n.s(R.string.settings_6935d2),
                    ) { v -> vm.update { it.copy(batchSize = (v / 64).toInt() * 64) } }
                    Divider()
                    SwitchRow(I18n.s(R.string.settings_475473), s.useMmap, subtitle = I18n.s(R.string.settings_300fac)) { on -> vm.update { it.copy(useMmap = on) } }
                    Divider()
                    SwitchRow(I18n.s(R.string.settings_810ed9), s.warmup, subtitle = I18n.s(R.string.settings_004e27)) { on -> vm.update { it.copy(warmup = on) } }
                }
            }
            GroupFootnote(I18n.s(R.string.settings_2a309b))
        }
    }

    if (editPrompt) {
        TextInputDialog(
            title = I18n.s(R.string.settings_4dde09),
            initial = s.systemPrompt,
            onConfirm = { text -> vm.update { it.copy(systemPrompt = text.trim()) } },
            onDismiss = { editPrompt = false },
            confirmText = I18n.s(R.string.settings_be5fbb),
            placeholder = I18n.s(R.string.settings_efaae4),
            singleLine = false,
            allowEmpty = true,
        )
    }
}

/**
 * 设置 › 记忆与上下文. 2.1.3: the old 「上下文与记忆」 (context window) and 「记忆」 pages are one
 * page: the context sections on top, then the memory settings ([com.iris.localai.ui.agent.MemorySettingsPage]).
 * Both SettingsPage.CONTEXT and SettingsPage.MEMORY open it. 「恢复默认」 still only resets the context part.
 */
@Composable
fun DevContextMemoryPage(vm: SettingsViewModel, onBack: () -> Unit, onOpenList: (() -> Unit)? = null, onOpenKnowledge: (() -> Unit)? = null) {
    val s by vm.settings.collectAsStateWithLifecycle()
    // F: 开发者选项 › 上下文与记忆 — every control of the old 记忆与上下文 page, unchanged.
    MemorySettingsPage(
        onBack = onBack,
        onOpenList = onOpenList, onOpenKnowledge = onOpenKnowledge,
        title = I18n.s(R.string.settings_9f7070),
        actions = { IrisButton(I18n.s(R.string.settings_7468f3), vm::resetContext, style = IrisButtonStyle.Text, compact = true) },
    ) {
        run {
            SectionTitle(I18n.s(R.string.settings_0ebfd1))
            SettingsGroup {
                SliderRow(I18n.s(R.string.models_3b5663), CtxScale.toPos(s.contextLength), CtxScale.RANGE, "${s.contextLength} tokens", subtitle = I18n.s(R.string.settings_474626)) { v ->
                    vm.update { it.copy(contextLength = CtxScale.fromPos(v, 1024)) }
                }
            }

            SectionTitle(I18n.s(R.string.settings_3f1fee))
            SettingsGroup {
                ContextStrategy.entries.forEach { st ->
                    if (st.ordinal > 0) Divider()
                    CheckRow(st.label, s.contextStrategy == st.ordinal, subtitle = st.hint) {
                        vm.update { it.copy(contextStrategy = st.ordinal) }
                    }
                }
                Divider()
                SliderRow(I18n.s(R.string.settings_dc8bfe), s.contextKeepTurns.toFloat(), 1f..32f, I18n.s(R.string.settings_14f1d0, s.contextKeepTurns), subtitle = I18n.s(R.string.settings_f2104d), steps = 30) { v -> vm.update { it.copy(contextKeepTurns = v.toInt()) } }
                Divider()
                SliderRow(I18n.s(R.string.settings_0e766b), s.summaryTriggerPercent.toFloat(), 40f..95f, "${s.summaryTriggerPercent}%", subtitle = I18n.s(R.string.settings_4bc8ad)) { v -> vm.update { it.copy(summaryTriggerPercent = v.toInt()) } }
                Divider()
                // E: 本对话的策略 (chat context sheet) only with this on.
                SwitchRow(I18n.s(R.string.settings_e471c9), s.developerMode, subtitle = I18n.s(R.string.settings_99314d)) { on -> vm.update { it.copy(developerMode = on) } }
            }
            if (ContextAssembly.devTakeover(ContextStrategy.of(s.contextStrategy))) {
                GroupFootnote(I18n.s(R.string.settings_97fdbb, ContextAssembly.TAKEOVER))
            }
            GroupFootnote(I18n.s(R.string.settings_9a4613))
        }
    }
}

/** 设置 › 性能与资源. */
@Composable
fun PerformancePage(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val tierApply by vm.tierApply.collectAsStateWithLifecycle()
    SubpageScaffold(I18n.s(R.string.settings_2b7c2b), onBack) {
        ScrollBody {
            SectionTitle(I18n.s(R.string.settings_81791e))
            SettingsGroup {
                // the same slider as the composer card (1.7.4 form, muted blue fill, one line of text).
                TierSlider(
                    labels = SpeedLevel.labels, // 低速 / 高速 / 极速 (= 省电 / 中 / 高)
                    index = s.resourceTier.coerceIn(0, ResourceTier.entries.lastIndex),
                    onChange = { m -> vm.update { it.copy(resourceTier = m) } },
                    status = LiveResourcePolicy.statusText(tierApply),
                    modifier = Modifier.tourTarget(TourId.PERF_TIER).padding(start = IrisDimens.space16, end = IrisDimens.space16, top = IrisDimens.space12, bottom = IrisSpace.S),
                )
                // 资源档配置 moved to 关于 IRIDIS › 开发者选项.
            }
            GroupFootnote(I18n.s(R.string.settings_25a91d) + LiveResourcePolicy.GPU_SCOPE_NOTE + I18n.s(R.string.settings_81197c))

            SectionTitle(I18n.s(R.string.settings_ed5278))
            SettingsGroup {
                SliderRow(
                    I18n.s(R.string.settings_ddb982),
                    s.idleUnloadMinutes.toFloat(), 0f..60f,
                    if (s.idleUnloadMinutes == 0) I18n.s(R.string.settings_487496) else I18n.s(R.string.settings_c29004, s.idleUnloadMinutes),
                    subtitle = I18n.s(R.string.settings_d52729),
                    steps = 11,
                ) { v -> vm.update { it.copy(idleUnloadMinutes = (v / 5).toInt() * 5) } }
                Divider()
                SwitchRow(
                    I18n.s(R.string.settings_ab603e), s.backgroundGeneration, icon = Icons.Outlined.Notifications,
                    subtitle = I18n.s(R.string.settings_539f31),
                ) { on -> vm.update { it.copy(backgroundGeneration = on) } }
                // HyperOS freezes IRIS in the background unless it may run there.
                // the row folds in / out instead of popping.
                Fold(s.backgroundGeneration) { androidx.compose.foundation.layout.Column {
                    Divider()
                    var exempt by remember { mutableStateOf(BackgroundGuard.isExempt(context)) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                        exempt = BackgroundGuard.isExempt(context)
                        onPauseOrDispose { }
                    }
                    SettingsRow(
                        I18n.s(R.string.settings_956fee),
                        subtitle = if (exempt) I18n.s(R.string.settings_db477e)
                        else I18n.s(R.string.settings_29ee72),
                        icon = IrisIcons.Bolt,
                        onClick = { BackgroundGuard.open(context) },
                        trailing = { ValueText(if (exempt) I18n.s(R.string.settings_70c3a1) else I18n.s(R.string.settings_94754e)) },
                    )
                } }
                Divider()
                val thermal by ThermalGovernor.status.collectAsStateWithLifecycle()
                val nowText = if (thermal.tempC.isNaN()) I18n.s(R.string.settings_80ef0f) else I18n.s(R.string.settings_137d5c, Math.round(thermal.tempC * 10) / 10.0)
                SwitchRow(
                    I18n.s(R.string.settings_3bee9a), s.thermalLimitC > 0, icon = IrisIcons.Gauge,
                    subtitle = if (s.thermalLimitC == 0) I18n.s(R.string.settings_9f3765, nowText)
                    else if (thermal.throttling) I18n.s(R.string.settings_b8b90f, nowText, thermal.capTps)
                    else I18n.s(R.string.settings_f1bd75, nowText),
                ) { on -> vm.update { it.copy(thermalLimitC = if (on) 42 else 0) } }
                Fold(s.thermalLimitC > 0) {
                    SliderRow(
                        I18n.s(R.string.settings_d31153), s.thermalLimitC.toFloat(), 35f..50f, "${s.thermalLimitC}°C",
                        subtitle = I18n.s(R.string.settings_d3af4c),
                        steps = 14,
                    ) { v -> vm.update { it.copy(thermalLimitC = v.toInt().coerceIn(35, 50)) } }
                }
                Divider()
                // 过热暂停：到上限说完这条再暂停，降 3°C 自动继续；接近上限时少算。不降速。
                SwitchRow(
                    I18n.s(R.string.cool267_title), s.coolPauseC > 0, icon = IrisIcons.Gauge,
                    subtitle = if (s.coolPauseC == 0) I18n.s(R.string.cool267_off)
                    else I18n.s(R.string.cool267_on, s.coolPauseC, s.coolPauseC - CoolDown.HYST_C.toInt()),
                ) { on -> vm.update { it.copy(coolPauseC = if (on) CoolDown.DEFAULT_C else 0) } }
                Fold(s.coolPauseC > 0) {
                    Column {
                        SliderRow(
                            I18n.s(R.string.cool267_limit), s.coolPauseC.toFloat(),
                            CoolDown.MIN_C.toFloat()..com.iris.localai.engine.resources.CoolDown.MAX_C.toFloat(),
                            "${s.coolPauseC}°C",
                            subtitle = I18n.s(R.string.cool267_note),
                            steps = CoolDown.MAX_C - CoolDown.MIN_C - 1,
                        ) { v -> vm.update { it.copy(coolPauseC = CoolDown.sanitize(kotlin.math.round(v).toInt())) } }
                        SwitchRow(I18n.s(R.string.cool267_stop), s.coolStopNow, subtitle = I18n.s(R.string.cool267_stop_sub)) { on -> vm.update { it.copy(coolStopNow = on) } }
                    }
                }
                Divider()
                // 终止温度 — hard stop + unload everything (no auto reload).
                SwitchRow(
                    I18n.s(R.string.settings_6f5c6c), s.thermalCutoffC > 0, icon = IrisIcons.Power,
                    subtitle = if (s.thermalCutoffC == 0) I18n.s(R.string.settings_e5ffc0, nowText)
                    else I18n.s(R.string.settings_a5ae10, s.thermalCutoffC),
                ) { on -> vm.update { it.copy(thermalCutoffC = if (on) ThermalCutoff.DEFAULT_C else 0) } }
                Fold(s.thermalCutoffC > 0) {
                    SliderRow(
                        I18n.s(R.string.settings_82a989), s.thermalCutoffC.toFloat(),
                        ThermalCutoff.MIN_C.toFloat()..com.iris.localai.engine.resources.ThermalCutoff.MAX_C.toFloat(),
                        "${s.thermalCutoffC}°C",
                        subtitle = I18n.s(R.string.settings_153dc8),
                        steps = ThermalCutoff.MAX_C - ThermalCutoff.MIN_C - 1,
                    ) { v -> vm.update { it.copy(thermalCutoffC = ThermalCutoff.sanitize(kotlin.math.round(v).toInt())) } }
                }
                Divider()
                SwitchRow(I18n.s(R.string.settings_3ffddc), s.keepScreenOn, icon = Icons.Outlined.ScreenLockPortrait) { on -> vm.update { it.copy(keepScreenOn = on) } }
                Divider()
                AdvancedFold("perf", SettingsText.ADVANCED) {
                    Divider()
                    SliderRow(
                        I18n.s(R.string.settings_539cb2), s.threads.toFloat(), 0f..8f,
                        if (s.threads == 0) I18n.s(R.string.settings_eb0286, vm.tierThreads(s.resourceTier)) else "${s.threads}",
                        subtitle = I18n.s(R.string.settings_3a985e),
                        steps = 7,
                    ) { v -> vm.update { it.copy(threads = v.toInt()) } }
                    Divider()
                    SwitchRow(
                        I18n.s(R.string.settings_a99d5a), s.perfOverlay, icon = IrisIcons.Gauge,
                        subtitle = I18n.s(R.string.settings_69a45f),
                    ) { on -> vm.update { it.copy(perfOverlay = on) } }
                    Divider()
                    SettingsRow(
                        I18n.s(R.string.settings_c43f5c), icon = IrisIcons.Gauge, subtitle = I18n.s(R.string.settings_2a0f92),
                        onClick = { vm.runBenchmark() },
                    )
                }
            }
            BenchmarkDialog(vm)
        }
    }
}

@Composable
fun AboutPage(onBack: () -> Unit, onDeveloper: () -> Unit = {}, onOpenLicenses: (() -> Unit)? = null) {
    val c = Iris.colors
    // 许可证全文 in place; 2.5.0 Beta6: MainActivity passes onOpenLicenses → Route.LICENSES (push/pop + back gesture).
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    if (showLicenses && onOpenLicenses == null) {
        androidx.activity.compose.BackHandler { showLicenses = false }
        LicensesPage(onBack = { showLicenses = false })
        return
    }
    SubpageScaffold(I18n.s(R.string.settings_81d9f5), onBack) {
        ScrollBody {
            // 3.0：关于卡片——背景是放大并模糊的标志，上层居中字标、下面版本号（参考 ColorOS / HyperOS 关于本机）。
            AboutHeroCard()
            SectionTitle(I18n.s(R.string.models_20a284))
            SettingsGroup {
                SettingsRow(I18n.s(R.string.settings_50322d), subtitle = I18n.s(R.string.settings_521717))
                Divider()
                SettingsRow(
                    I18n.s(R.string.settings_4236d8), subtitle = I18n.s(R.string.settings_48b9fe),
                    onClick = { if (onOpenLicenses != null) onOpenLicenses() else showLicenses = true }, trailing = { Chevron() },
                )
            }
            SectionTitle(I18n.s(R.string.settings_843d86))
            SettingsGroup {
                OPEN_SOURCE.forEachIndexed { i, (name, desc) ->
                    if (i > 0) Divider()
                    SettingsRow(name, subtitle = desc)
                }
            }
            // 开发者选项 at the bottom of 关于 IRIDIS.
            Spacer(Modifier.height(IrisDimens.space24))
            SettingsGroup {
                NavItem(I18n.s(R.string.settings_1b6949), SettingsText.DEVELOPER, IrisIcons.Pulse, onDeveloper)
            }
        }
    }
}

// one short paragraph (what stays on the phone, the three cases that go online).
internal val ABOUT_PRIVACY: String get() = I18n.s(R.string.about262_privacy)

@Composable
private fun AboutHeroCard() {
    val c = Iris.colors
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val shape = com.iris.localai.ui.theme.smoothShape(28.dp)
    // 3.0 彩蛋：连点卡片 7 下，标志转一圈、光斑变色，并记下「找到的彩蛋」
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    val spin = remember { androidx.compose.animation.core.Animatable(0f) }
    val hue = remember { androidx.compose.animation.core.Animatable(0f) }
    var egg by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val feedback = com.iris.localai.ui.feedback.rememberIrisFeedback()
    val reduce = com.iris.localai.ui.theme.LocalReduceMotion.current
    Box(
        Modifier
            .padding(horizontal = IrisDimens.groupGutter, vertical = IrisDimens.space8)
            .fillMaxWidth()
            .aspectRatio(1.18f)
            // Beta2：卡片边界跟页面底色分开——轻阴影 + 发丝描边，混色也铺满到边
            .shadow(6.dp, shape, ambientColor = Color(0xFF3A4BD8).copy(alpha = 0.18f), spotColor = Color(0xFF3A4BD8).copy(alpha = 0.22f))
            .clip(shape)
            .background(c.surface)
            .border(0.6.dp, if (c.isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF3A4BD8).copy(alpha = 0.10f), shape)
            .then(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                val now = System.currentTimeMillis()
                taps = if (now - lastTap < 600) taps + 1 else 1
                lastTap = now
                feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.TICK)
                if (taps >= 7) {
                    taps = 0; egg = true
                    feedback.perform(com.iris.localai.ui.feedback.FeedbackEvent.REPLY_DONE)
                    runCatching { (appCtx as com.iris.localai.IrisApp).container.settings }.getOrNull()?.update { st -> if ("about-spin" in st.eggsFound) st else st.copy(eggsFound = st.eggsFound + "about-spin") }
                    scope.launch { spin.snapTo(0f); spin.animateTo(360f, com.iris.localai.ui.motion.motionSpec(com.iris.localai.ui.motion.IrisMotion.Rebound, reduce)) }
                    scope.launch { hue.animateTo(hue.value + 180f, com.iris.localai.ui.motion.IrisMotion.progress(1600)) }
                }
            }),
        contentAlignment = Alignment.Center,
    ) {
        // Beta2：HyperOS / ColorOS 式动态流动混色——几团柔光沿各自的李萨如轨迹慢慢游走、互相混合
        AboutFlowBackground(hueShift = hue.value, spin = spin.value, dark = c.isDark, base = c.surface)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to c.surface.copy(alpha = if (c.isDark) 0.04f else 0.10f), 0.6f to Color.Transparent, 1f to c.surface.copy(alpha = if (c.isDark) 0.18f else 0.16f),
        )))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(36.dp).graphicsLayer { rotationY = spin.value; cameraDistance = 24f * density }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.iridis_wordmark_ink), "IRIDIS", tint = c.text, modifier = Modifier.height(36.dp).width(207.dp))
                androidx.compose.foundation.Image(painterResource(R.drawable.iridis_wordmark_accent), null, modifier = Modifier.height(36.dp).width(207.dp))
            }
            Spacer(Modifier.height(IrisDimens.space12))
            if (egg) Text(I18n.s(R.string.v30_egg_about), style = IrisType.secondary, color = c.textSecondary)
            else AboutVersion(BuildConfig.VERSION_NAME)
        }
    }
}

/** Beta2：关于页版本号——去掉「版本」，数字用细字宽字距，阶段（Beta 1）做成半透明小标签。 */
@Composable
private fun AboutVersion(versionName: String) {
    val c = Iris.colors
    val (num, stage) = AboutVersionText.parts(versionName)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            num,
            style = IrisType.secondary.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                letterSpacing = androidx.compose.ui.unit.TextUnit(2.4f, androidx.compose.ui.unit.TextUnitType.Sp),
                fontFeatureSettings = "tnum",
            ),
            color = c.text.copy(alpha = 0.78f),
        )
        if (stage != null) {
            Spacer(Modifier.width(IrisDimens.space8))
            Box(
                Modifier.clip(IrisRadius.pillShape)
                    .background(c.text.copy(alpha = if (c.isDark) 0.12f else 0.08f))
                    .then(Modifier.border(0.5.dp, c.text.copy(alpha = 0.14f), IrisRadius.pillShape))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    stage.uppercase(),
                    style = IrisType.micro.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(1.6f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    color = c.text.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/** 版本名拆成「数字」和「阶段」：「3.0.0 Beta1-full」→「3.0.0」+「Beta 1」（纯函数，单测）。 */
object AboutVersionText {
    fun parts(versionName: String): Pair<String, String?> {
        val v = versionName.trim().replace(Regex("-(full|lite)$", RegexOption.IGNORE_CASE), "").trim()
        val i = v.indexOf(' ')
        if (i < 0) return v to null
        val num = v.substring(0, i)
        val stage = v.substring(i + 1).trim().replace(Regex("([A-Za-z])(\\d)"), "$1 $2")
        return num to stage.ifEmpty { null }
    }
}

/**
 * Beta2：关于页卡片的流动混色背景。四团径向渐变柔光各走一条慢速李萨如轨迹（周期 14–26 秒，互不同步），
 * 叠在卡片底色上自然混色；减弱动态时静止。[hueShift] 给彩蛋变色，[spin] 让彩蛋时整片转动。
 */
@Composable
private fun AboutFlowBackground(hueShift: Float, spin: Float, dark: Boolean, base: Color) {
    val reduce = com.iris.localai.ui.theme.LocalReduceMotion.current
    val t = if (reduce) 0f else {
        val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "aboutFlow")
        inf.animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(com.iris.localai.ui.motion.IrisMotion.linearLoop(78_000)),
            label = "aboutFlowT",
        ).value
    }
    val blobs = AboutFlow.BLOBS
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin / 3f; scaleX = 1.25f; scaleY = 1.25f }) {
        drawRect(base)
        val w = size.width; val h = size.height
        for (b in blobs) {
            val (fx, fy) = AboutFlow.position(b, t)
            val col = AboutFlow.shift(if (dark) b.dark else b.light, hueShift)
            val r = b.radius * maxOf(w, h)
            val center = androidx.compose.ui.geometry.Offset(fx * w, fy * h)
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to col.copy(alpha = if (dark) 0.42f else 0.30f),
                    0.55f to col.copy(alpha = if (dark) 0.16f else 0.11f),
                    1f to col.copy(alpha = 0f),
                    center = center, radius = r,
                ),
                radius = r, center = center,
            )
        }
    }
}

/** 流动混色的柔光参数与轨迹（纯函数，单测）。 */
object AboutFlow {
    class Blob(val light: Color, val dark: Color, val cx: Float, val cy: Float, val ax: Float, val ay: Float, val kx: Int, val ky: Int, val phase: Float, val radius: Float)

    val BLOBS = listOf(
        Blob(Color(0xFF7C83FF), Color(0xFF5660F0), 0.30f, 0.32f, 0.22f, 0.18f, 3, 2, 0.0f, 0.62f),
        Blob(Color(0xFF5FD6EA), Color(0xFF18B4D2), 0.72f, 0.40f, 0.20f, 0.24f, 2, 3, 1.7f, 0.58f),
        Blob(Color(0xFFD69CFF), Color(0xFF9B5CF0), 0.55f, 0.78f, 0.26f, 0.16f, 4, 3, 3.1f, 0.55f),
        Blob(Color(0xFFFFB3A6), Color(0xFFE0708A), 0.22f, 0.80f, 0.18f, 0.20f, 3, 5, 4.4f, 0.48f),
    )

    /** [t] ∈ [0,1) 一整圈；每团的整数倍频保证 t=1 与 t=0 首尾相接、不跳。 */
    fun position(b: Blob, t: Float): Pair<Float, Float> {
        val a = (2.0 * Math.PI * t).toFloat()
        return (b.cx + b.ax * kotlin.math.sin(a * b.kx + b.phase)) to (b.cy + b.ay * kotlin.math.cos(a * b.ky + b.phase * 0.7f))
    }

    fun shift(c: Color, deg: Float): Color {
        if (deg == 0f) return c
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(c.toArgb(), hsl)
        hsl[0] = ((hsl[0] + deg) % 360f + 360f) % 360f
        return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
    }
}

/** 色相旋转矩阵（[deg] 度），给彩蛋的光斑变色。 */
private fun hueMatrix(deg: Float): androidx.compose.ui.graphics.ColorMatrix {
    val r = Math.toRadians(deg.toDouble()); val cos = Math.cos(r).toFloat(); val sin = Math.sin(r).toFloat()
    val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
    return androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        lr + cos * (1 - lr) + sin * (-lr), lg + cos * (-lg) + sin * (-lg), lb + cos * (-lb) + sin * (1 - lb), 0f, 0f,
        lr + cos * (-lr) + sin * 0.143f, lg + cos * (1 - lg) + sin * 0.140f, lb + cos * (-lb) + sin * (-0.283f), 0f, 0f,
        lr + cos * (-lr) + sin * (-(1 - lr)), lg + cos * (-lg) + sin * lg, lb + cos * (1 - lb) + sin * lb, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
}

/** every bundled / downloadable third-party component with its license. */
internal val OPEN_SOURCE = listOf(
    "llama.cpp" to I18n.s(R.string.settings_bdd372),
    "MNN" to I18n.s(R.string.settings_fd49fd),
    "Google LiteRT-LM" to I18n.s(R.string.settings_934216),
    "LiteRT Dispatch (Qualcomm)" to I18n.s(R.string.settings_ac8dbd),
    I18n.s(R.string.settings_4e0e80) to I18n.s(R.string.settings_887484),
    "sherpa-onnx" to I18n.s(R.string.settings_53cc9e),
    "ONNX Runtime" to I18n.s(R.string.settings_c46311),
    "Bergamot Translator" to I18n.s(R.string.about262_bergamot),
    I18n.s(R.string.settings_0bc708) to I18n.s(R.string.settings_14990b),
    "AndroidX / Jetpack Compose" to I18n.s(R.string.settings_dc1c39),
    "Kotlin / kotlinx" to I18n.s(R.string.settings_77baf4),
    "Apache Commons Compress" to I18n.s(R.string.settings_4369be),
    "Silero VAD" to I18n.s(R.string.settings_84b6ac),
    "SenseVoice" to I18n.s(R.string.settings_cecfaa),
    "Kokoro" to I18n.s(R.string.settings_d45d0c),
    "Zipformer CTC" to I18n.s(R.string.settings_a358bf),
    "ZipVoice" to I18n.s(R.string.settings_f6afeb),
    "Matcha baker" to I18n.s(R.string.settings_8e93fd),
    "Piper xiao_ya" to I18n.s(R.string.settings_392cd5),
    "three.js" to I18n.s(R.string.settings_152596),
    "RhineLabUI" to I18n.s(R.string.settings_f0f2f2),
)


@Composable
private fun NpuRuntimeRow(
    npu: NpuSupport,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val st by npu.state.collectAsState()
    val chip = npu.chipLabel
    val mtk = npu.vendor == NpuVendor.MEDIATEK
    // the QNN runtime is Qualcomm's proprietary software; ask once per session before downloading.
    var askConsent by remember { mutableStateOf(false) }
    val install: () -> Unit = {
        // MediaTek's dispatch library is Apache-2.0 and already in the APK — no Qualcomm terms to accept.
        if (NpuConsent.accepted || mtk) onInstall() else { askConsent = true }
    }
    if (askConsent) {
        NpuLicenseDialog(
            onAccept = { NpuConsent.accepted = true; askConsent = false; onInstall() },
            onDismiss = { askConsent = false },
        )
    }
    val (subtitle, action) = when (val s = st) {
        is NpuState.Unsupported -> s.reason to null
        NpuState.NotInstalled ->
            (if (mtk) I18n.s(R.string.mtk244_not_installed, chip) else I18n.s(R.string.settings_001963, chip)) to (I18n.s(R.string.settings_f26ef9) to install)
        is NpuState.Downloading ->
            I18n.s(R.string.settings_2b5c7e, s.downloaded shr 20, s.total shr 20) to (I18n.s(R.string.chat_625fb2) to onCancel)
        NpuState.Installed ->
            (if (mtk) I18n.s(R.string.mtk244_installed, chip) else I18n.s(R.string.npu27_qnn_installed)) to (I18n.s(R.string.chat_2f4aad) to onDelete)
        is NpuState.Failed -> I18n.s(R.string.settings_ce1a3a, s.message) to (I18n.s(R.string.settings_132c5c) to install)
        is NpuState.NeedsRepair ->
            I18n.s(R.string.settings_501bdb, chip, s.reason) to (I18n.s(R.string.settings_f82661) to install)
    }
    Column {
        SettingsRow(
            if (mtk) I18n.s(R.string.mtk244_title) else I18n.s(R.string.settings_42e701),
            subtitle = subtitle,
            subtitleColor = if (st is NpuState.Failed || st is NpuState.NeedsRepair) Iris.colors.danger else Iris.colors.textTertiary,
            trailing = action?.let { (label, fn) ->
                {
                    IrisButton(
                        label, fn, compact = true,
                        style = if (label == I18n.s(R.string.settings_f26ef9) || label == I18n.s(R.string.settings_132c5c) || label == I18n.s(R.string.settings_f82661)) IrisButtonStyle.Outline else IrisButtonStyle.Text,
                        destructive = label == I18n.s(R.string.chat_2f4aad),
                    )
                }
            },
        )
        (st as? NpuState.Downloading)?.let { d ->
            IrisProgressBar(
                progress = if (d.total > 0) d.downloaded.toFloat() / d.total else null,
                modifier = Modifier.padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, bottom = IrisDimens.space12),
            )
        }
    }
}

/** In-memory (per process) acceptance of the Qualcomm runtime terms; nothing is persisted. */
internal object NpuConsent {
    @Volatile var accepted: Boolean = false
}

internal val NPU_LICENSE_TITLE: String get() = I18n.s(R.string.settings_0ccfb8)
internal val NPU_LICENSE_BODY: String get() =
    I18n.s(R.string.settings_513cdf)
internal const val NPU_LICENSE_URL = "https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk"

@Composable
private fun NpuLicenseDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    IrisDialog(
        onDismiss = onDismiss,
        title = NPU_LICENSE_TITLE,
        confirmText = I18n.s(R.string.settings_ad7951),
        onConfirm = onAccept,
        dismissText = I18n.s(R.string.chat_625fb2),
    ) {
        Column {
            Text(NPU_LICENSE_BODY, style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                I18n.s(R.string.settings_81d193), {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(NPU_LICENSE_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                style = IrisButtonStyle.Text, compact = true,
            )
        }
    }
}

@Composable
private fun BenchmarkDialog(vm: SettingsViewModel) {
    val c = Iris.colors
    val b by vm.bench.collectAsStateWithLifecycle()
    if (b is SettingsViewModel.Bench.Idle) return
    fun f1(v: Float) = String.format(Locale.US, "%.1f", v)
    val st = b
    // Decode speed is what the user feels most; pick the fewest threads within 3% of the best.
    val best = (st as? SettingsViewModel.Bench.Done)?.rows?.filter { it.threads > 0 }?.let { rows ->
        val top = rows.maxOfOrNull { it.decodeTps } ?: 0f
        rows.filter { it.decodeTps >= top * 0.97f }.minByOrNull { it.threads }
    }
    val confirmText: String
    val onConfirm: () -> Unit
    var dismissText: String? = null
    when {
        st is SettingsViewModel.Bench.Running -> { confirmText = I18n.s(R.string.chat_625fb2); onConfirm = { vm.cancelBenchmark() } }
        best != null -> { confirmText = I18n.s(R.string.settings_0964c9, best.threads); onConfirm = { vm.applyThreads(best.threads) }; dismissText = I18n.s(R.string.ui_b15d91) }
        else -> { confirmText = I18n.s(R.string.settings_ac2c8f); onConfirm = { vm.dismissBenchmark() } }
    }
    IrisDialog(
        onDismiss = { vm.dismissBenchmark() },
        title = I18n.s(R.string.settings_c43f5c),
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismissClick = { vm.dismissBenchmark() },
    ) {
        when (st) {
            is SettingsViewModel.Bench.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st.done == 0) I18n.s(R.string.settings_7e19ab) else I18n.s(R.string.settings_292c29, st.done, st.total),
                    style = IrisType.secondary, color = c.textSecondary,
                )
                Spacer(Modifier.size(IrisDimens.space6))
                TypingDots()
            }
            is SettingsViewModel.Bench.Failed -> Text(I18n.s(R.string.settings_4141f7, st.message), style = IrisType.secondary, color = c.textSecondary)
            is SettingsViewModel.Bench.Done -> Column {
                Text(st.backend, style = IrisType.caption, color = c.textTertiary)
                Spacer(Modifier.height(IrisDimens.space12))
                Row(Modifier.fillMaxWidth()) {
                    for (h in listOf(I18n.s(R.string.settings_d27612), I18n.s(R.string.settings_ff8d52), I18n.s(R.string.settings_4dfe70), I18n.s(R.string.settings_4a0fe1))) Text(h, Modifier.weight(1f), style = IrisType.caption, color = c.textTertiary)
                }
                for (r in st.rows) {
                    val highlight = best != null && r.threads == best.threads
                    val color = if (highlight) c.text else c.textSecondary
                    val style = if (highlight) IrisType.label else IrisType.secondary
                    Row(Modifier.fillMaxWidth().padding(top = IrisDimens.space6)) {
                        Text(if (r.threads == 0) "—" else "${r.threads}", Modifier.weight(1f), style = style, color = color)
                        Text(f1(r.prefillTps), Modifier.weight(1f), style = style, color = color)
                        Text(f1(r.decodeTps), Modifier.weight(1f), style = style, color = color)
                        Text("${r.firstTokenMs} ms", Modifier.weight(1f), style = style, color = color)
                    }
                }
                Spacer(Modifier.height(IrisDimens.space12))
                Text(I18n.s(R.string.settings_fa4fe7, st.rows.firstOrNull()?.promptTokens ?: 0), style = IrisType.caption, color = c.textTertiary)
            }
            else -> {}
        }
    }
}


/** the settings page each rack slot opens (same order as [RackRules.modules]). */
internal val RACK_PAGES = listOf(
    SettingsPage.MODEL_BACKEND, SettingsPage.AGENT, SettingsPage.AGENT, SettingsPage.AGENT,
    SettingsPage.VOICE, SettingsPage.API, SettingsPage.AGENT, SettingsPage.MEMORY,
)

/** gathers the live state of every part of IRIS for [StatusRack]. */
@Composable
private fun RuntimeRack(vm: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val engine by vm.engine.collectAsStateWithLifecycle()
    val api by vm.apiStatus.collectAsStateWithLifecycle()
    val packs by vm.voicePacks.collectAsStateWithLifecycle()
    val ag by agent.settings.settings.collectAsStateWithLifecycle()
    val mem by agent.memorySettings.settings.collectAsStateWithLifecycle()
    val plans by agent.plans.plans.collectAsStateWithLifecycle()
    val live by SystemStatus.live.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val input = RackInput(
        modelName = when (val e = engine) {
            is EngineState.Ready -> e.model.displayName
            is EngineState.Loading -> e.model.displayName
            is EngineState.Failed -> e.model?.displayName
            else -> null
        },
        modelState = when (val e = engine) {
            is EngineState.Ready -> if (live.generating) RackState.RUN else RackState.ON
            is EngineState.Loading -> RackState.LOAD
            is EngineState.Failed -> RackState.ERROR
            else -> RackState.STANDBY
        },
        modelDetail = when (val e = engine) {
            is EngineState.Ready -> e.details.activeBackend.label
            is EngineState.Loading -> I18n.s(R.string.settings_3a5cec, (e.progress * 100).toInt())
            is EngineState.Failed -> e.message.take(40)
            else -> ""
        },
        modelChips = (engine as? EngineState.Ready)?.let { e ->
            listOf(e.details.activeBackend.label, "${(e.details.contextLength / 1024).coerceAtLeast(1)}K", ResourceTier.of(s.resourceTier).label)
        }.orEmpty(),
        agentMode = ag.agentMode, agentRunning = live.agentRunning, tool = live.tool, toolGroup = live.toolGroup, stepsDone = live.stepsDone,
        webOn = ag.isEnabled(WebSearchTool) || ag.isEnabled(WebFetchTool),
        screenOn = ag.isEnabled(ScreenTool),
        voicePacks = packs.values.count { it == PackState.Installed }, voiceActive = live.voiceActive,
        apiRunning = api.running, apiPort = api.port, apiError = api.error,
        memoryOn = mem.enabled,
        planLeft = plans.values.filter { !it.finished }.sumOf { it.steps.size - it.done },
    )
    val modules = RackRules.modules(input)
    SettingsGroup {
        StatusRack(modules, generating = live.generating, modelReady = engine is EngineState.Ready,
            modifier = Modifier.fillMaxWidth().padding(vertical = IrisDimens.space8),
            onOpen = { i -> onOpen(RACK_PAGES.getOrElse(i) { SettingsPage.AGENT }) })
    }
}


/** 外观: theme, visual effects, feedback. */
/** 设置 › 语言 — the 2.3.1 picker on its own page (follow system + the 7 UI languages). */
/** 设置 › 下载源 — the only place to pick it; models, 生图, 翻译包 and 语音 all follow [AppSettings.downloadSource]. */
@Composable
fun DownloadSourcePage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SubpageScaffold(SourceText.TITLE, onBack) {
        ScrollBody {
            SettingsGroup {
                DownloadSource.entries.forEachIndexed { i, src ->
                    if (i > 0) Divider()
                    CheckRow(src.label, selected = s.downloadSource == src) { vm.update { it.copy(downloadSource = src) } }
                }
            }
            GroupFootnote(SourceText.SHARED)
        }
    }
}

@Composable
fun LanguagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val langTag = remember { I18n.current(context) }
    SubpageScaffold(I18n.s(R.string.set234_lang), onBack) {
        ScrollBody {
            SettingsGroup {
                (listOf("" to I18n.s(R.string.lang_system)) + I18n.LANGS).forEachIndexed { i, (tag, name) ->
                    if (i > 0) Divider()
                    CheckRow(name, selected = tag == langTag) {
                        if (tag != langTag && activity != null) I18n.set(activity, tag)
                    }
                }
            }
        }
    }
}

@Composable
fun AppearancePage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bgFailed by remember { mutableStateOf(false) }
    // 自定义背景: system photo picker (no storage permission); the picture is copied in, the URI dropped.
    val pickBackground = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) scope.launch {
            val name = BackgroundImage.import(context, uri)
            bgFailed = name == null
            if (name != null) vm.update { it.copy(backgroundImage = name) }
        }
    }
    SubpageScaffold(I18n.s(R.string.settings_afcde2), onBack) {
        ScrollBody {
            SectionTitle(I18n.s(R.string.settings_9970ad))
            SettingsGroup {
                // 界面风格: 柔光 (default) / 经典 (the ≤ 2.4.6 look). Beta4: single-choice rows (2.4.6 CheckRow), no segmented control.
                val curStyle = IrisStyle.of(s.uiStyle)
                val classicStyle = curStyle == IrisStyle.CLASSIC
                CheckRow(
                    I18n.s(R.string.v250_style) + " · " + I18n.s(R.string.v250_style_soft),
                    selected = curStyle == IrisStyle.SOFT,
                ) { vm.update { it.copy(uiStyle = IrisStyle.SOFT.id) } }
                Divider()
                CheckRow(
                    I18n.s(R.string.v250_style) + " · " + I18n.s(R.string.v250_style_classic),
                    selected = classicStyle,
                ) { vm.update { it.copy(uiStyle = IrisStyle.CLASSIC.id) } }
                Divider()
                // 3.0 液态玻璃
                CheckRow(
                    I18n.s(R.string.v250_style) + " · " + I18n.s(R.string.v30_style_liquid),
                    selected = curStyle == IrisStyle.LIQUID,
                ) { vm.update { it.copy(uiStyle = IrisStyle.LIQUID.id, liquidGlass = true) } }
                Divider()
                SwitchRow(I18n.s(R.string.v30_island), s.dynamicIsland, icon = IrisIcons.Download) { on -> vm.update { it.copy(dynamicIsland = on) } }
                Divider()
                SwitchRow(I18n.s(R.string.v30_quips), s.idleQuips, icon = IrisIcons.Spark) { on -> vm.update { it.copy(idleQuips = on) } }
                Divider()
                SegmentedRow(
                    I18n.s(R.string.settings_9970ad), icon = IrisIcons.Contrast,
                    options = THEME_OPTIONS.map { it.second },
                    selectedIndex = THEME_OPTIONS.indexOfFirst { it.first == s.themeMode },
                    onSelect = { i -> vm.update { it.copy(themeMode = THEME_OPTIONS[i].first) } },
                )
                Divider()
                PaletteSwatches(s.themePalette) { p -> vm.update { it.copy(themePalette = p.id) } }
                Divider()
                SegmentedRow(
                    I18n.s(R.string.settings_917205), GlassPolicy.LEVEL_LABELS, s.visualEffects.coerceIn(0, 2),
                    { i -> vm.update { it.copy(visualEffects = i, glassEffects = i > 0) } },
                    icon = IrisIcons.Eye,
                    subtitle = when (s.visualEffects) {
                        0 -> I18n.s(R.string.settings_f24ad9)
                        2 -> I18n.s(R.string.settings_94b767)
                        else -> I18n.s(R.string.settings_41d7e0)
                    },
                )
            }
            // 对话文字大小（vc283）：只缩放对话正文，下方预览即时跟着变。
            SectionTitle(I18n.s(R.string.v277_chat_text))
            SettingsGroup {
                val pct = com.iris.localai.data.ChatTextScale.sanitize(s.chatTextScale)
                SliderRow(
                    I18n.s(R.string.v277_chat_text), pct.toFloat(),
                    com.iris.localai.data.ChatTextScale.MIN.toFloat()..com.iris.localai.data.ChatTextScale.MAX.toFloat(),
                    display = com.iris.localai.data.ChatTextScale.label(pct),
                    subtitle = I18n.s(R.string.v277_chat_text_sub),
                    steps = com.iris.localai.data.ChatTextScale.sliderSteps,
                ) { v ->
                    val next = com.iris.localai.data.ChatTextScale.fromSlider(v)
                    if (next != s.chatTextScale) vm.update { it.copy(chatTextScale = next) }
                }
                Text(
                    I18n.s(R.string.v277_chat_text_preview),
                    style = com.iris.localai.ui.theme.IrisType.message.copy(color = Iris.colors.text)
                        .chatScaled(com.iris.localai.data.ChatTextScale.factor(pct)),
                    modifier = Modifier.fillMaxWidth().padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, bottom = IrisDimens.rowVerticalPadding),
                )
            }
            SectionTitle(I18n.s(R.string.settings_8e1b94))
            SettingsGroup {
                val hasBg = s.backgroundImage.isNotEmpty()
                // 2.2.14
                SwitchRow(
                    I18n.s(R.string.settings_c847fe), s.solidBackground,
                    subtitle = I18n.s(R.string.settings_1f71ce),
                    icon = IrisIcons.Image,
                ) { on -> vm.update { it.copy(solidBackground = on) } }
                Divider()
                SwitchRow(
                    I18n.s(R.string.settings_e2bc30), s.dynamicBlend, enabled = !s.solidBackground,
                    subtitle = if (hasBg) I18n.s(R.string.settings_d864cc) else I18n.s(R.string.settings_01c110),
                    icon = IrisIcons.Image,
                ) { on -> vm.update { it.copy(dynamicBlend = on) } }
                Divider()
                SettingsRow(
                    I18n.s(R.string.settings_ac429c),
                    subtitle = when { bgFailed -> AppearanceText.BACKGROUND_FAILED; hasBg -> AppearanceText.BACKGROUND_SET; else -> AppearanceText.BACKGROUND_NONE },
                    icon = IrisIcons.Image,
                    onClick = {
                        pickBackground.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    trailing = { Chevron() },
                )
                Fold(hasBg) { androidx.compose.foundation.layout.Column {
                    Divider()
                    SliderRow(
                        I18n.s(R.string.settings_613ee9), s.backgroundBlur.toFloat(), 0f..com.iris.localai.ui.theme.BackgroundImage.MAX_BLUR_DP.toFloat(),
                        display = "${s.backgroundBlur} dp", subtitle = AppearanceText.BLUR,
                        steps = BackgroundImage.MAX_BLUR_DP - 1,
                    ) { v -> vm.update { it.copy(backgroundBlur = kotlin.math.round(v).toInt()) } }
                    Divider()
                    SliderRow(
                        I18n.s(R.string.settings_5f8050), s.backgroundScrim.toFloat(), 0f..com.iris.localai.ui.theme.BackgroundImage.MAX_SCRIM.toFloat(),
                        display = "${s.backgroundScrim}%", subtitle = AppearanceText.SCRIM,
                    ) { v -> vm.update { it.copy(backgroundScrim = kotlin.math.round(v).toInt()) } }
                    Divider()
                    SettingsRow(
                        I18n.s(R.string.settings_9a17b1), subtitle = AppearanceText.REMOVE, icon = IrisIcons.Trash,
                        titleColor = Iris.colors.danger,
                        onClick = {
                            bgFailed = false
                            vm.update { it.copy(backgroundImage = "") }
                            scope.launch { BackgroundImage.clear(context) }
                        },
                    )
                } }
            }
            // 顶栏按钮底色 / 运行状态机架 / 性能浮窗 moved to 关于 IRIDIS › 开发者选项 (DeveloperPage).
            SectionTitle(I18n.s(R.string.settings_1d50fa))
            SettingsGroup {
                SwitchRow(I18n.s(R.string.settings_487676), s.haptics, subtitle = I18n.s(R.string.settings_c5c770), icon = IrisIcons.Haptic) { on -> vm.update { it.copy(haptics = on) } }
                Divider()
                SwitchRow(I18n.s(R.string.settings_95ffd3), s.sound, subtitle = I18n.s(R.string.settings_3666d0), icon = IrisIcons.Sound) { on -> vm.update { it.copy(sound = on) } }
            }
            RenderSettingsSection(s, vm::update) // 2.2 渲染网页代码 / 加载网络图片
        }
    }
}


/**
 * 关于 IRIDIS › 开发者选项: the rarely used tuning / diagnostics pages and display switches,
 * moved off the main settings list and 外观. Default values unchanged.
 */
@Composable
fun DeveloperPage(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SubpageScaffold(I18n.s(R.string.settings_1b6949), onBack) {
        ScrollBody {
            // F: every old context / memory / index control moved here.
            SectionTitle(I18n.s(R.string.settings_9f7070))
            SettingsGroup {
                NavItem(I18n.s(R.string.settings_9f7070), I18n.s(R.string.settings_2e9be5), IrisIcons.Memory) { onOpen(SettingsPage.DEV_CONTEXT) }
            }
            SectionTitle(I18n.s(R.string.settings_eee83a))
            SettingsGroup {
                NavItem(I18n.s(R.string.settings_6eba78), SettingsText.TIER_PROFILES, IrisIcons.Bolt) { onOpen(SettingsPage.TIER_PROFILES) }
                Divider()
                NavItem(I18n.s(R.string.settings_7ea069), SettingsText.DIAGNOSTICS, IrisIcons.Pulse) { onOpen(SettingsPage.DIAGNOSTICS) }
            }
            SectionTitle(I18n.s(R.string.settings_4d775d))
            SettingsGroup {
                SwitchRow(I18n.s(R.string.settings_2bb191), s.showStatusRack, subtitle = I18n.s(R.string.settings_9e41af)) { on -> vm.update { it.copy(showStatusRack = on) } }
                Divider()
                SwitchRow(I18n.s(R.string.settings_a99d5a), s.perfOverlay, subtitle = I18n.s(R.string.settings_8a2e4e)) { on -> vm.update { it.copy(perfOverlay = on) } }
                Divider()
                // 风险提示弹窗开关
                SwitchRow(I18n.s(R.string.v256_warn_popups), s.warnPopups, subtitle = I18n.s(R.string.v256_warn_popups_sub)) { on -> vm.update { it.copy(warnPopups = on) } }
                Divider()
                // 2.7.2：崩溃后显示报告（与意外退出弹窗里的「不再提示」是同一个设置）
                SwitchRow(I18n.s(R.string.v272_crash_popup), s.crashReportPopup, subtitle = I18n.s(R.string.v272_crash_popup_sub)) { on -> vm.update { it.copy(crashReportPopup = on) } }
                Divider()
                SwitchRow(I18n.s(R.string.settings_2c1040), s.topBarCapsules, subtitle = I18n.s(R.string.settings_65d1b5)) { on -> vm.update { it.copy(topBarCapsules = on) } }
            }
            // 运行状态 rack (2.1.3: moved here from the settings home).
            if (s.showStatusRack) {
                SectionTitle(I18n.s(R.string.settings_e4b51d))
                RuntimeRack(vm, onOpen)
            }
        }
    }
}

/**
 * 「默认模型」: what tapping the chat logo loads (also used when a message needs a model and
 * none is loaded). 「上次使用的模型」 = the default ("" in settings).
 */
@Composable
private fun DefaultModelRow(vm: SettingsViewModel, current: String) {
    val models by vm.installedModels.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val name = models.firstOrNull { current.isNotBlank() && it.fileName == current }?.displayName
    SettingsRow(
        I18n.s(R.string.settings_b11de2), icon = IrisIcons.Cube,
        subtitle = when {
            current.isBlank() -> I18n.s(R.string.settings_dfac96)
            name != null -> I18n.s(R.string.settings_80717f, name)
            else -> I18n.s(R.string.settings_65b42a)
        },
        onClick = { vm.refreshModels(); open = true },
    )
    if (open) {
        IrisSheet(onDismiss = { open = false }) {
            SheetHeader(I18n.s(R.string.settings_b11de2))
            CheckRow(I18n.s(R.string.settings_098aca), current.isBlank(), subtitle = I18n.s(R.string.settings_3243cd)) {
                vm.setDefaultModel(""); open = false
            }
            models.forEach { m ->
                CheckRow(m.displayName, current == m.fileName, subtitle = m.fileName) {
                    vm.setDefaultModel(m.fileName); open = false
                }
            }
        }
    }
}

/** 「识图清晰度」: long side of the picture a vision model reads (快速 384 / 标准 512 / 清晰 1024). */
@Composable
private fun VisionQualityRow(vm: SettingsViewModel, current: Int) {
    val options = VisionQuality.entries
    SegmentedRow(
        I18n.s(R.string.settings_524e9e), options.map { it.label }, VisionQuality.of(current).ordinal,
        { i -> vm.setVisionSide(options[i].side) },
        subtitle = I18n.s(R.string.settings_f81386),
    )
}

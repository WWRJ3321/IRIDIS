package com.iris.localai.ui.settings

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import com.iris.localai.ui.theme.IrisType
import java.util.Locale
import kotlin.math.roundToInt


/**
 * 1.7.8 (设置整理): the settings are grouped by what they do. HOME only links to the groups
 * (plus the few one-tap items: theme, visual effects, delete all); each group is its own page,
 * with rarely used items folded under 「高级」. No storage key or default value changed.
 */
/** 2.1.3: DEVELOPER = 开发者选项 (reached from 关于 IRIDIS); CONTEXT and MEMORY open the same 「记忆与上下文」 page. */
enum class SettingsPage { APPEARANCE, LANGUAGE, HOME, MODEL_BACKEND, GENERATION, CONTEXT, PERFORMANCE, TIER_PROFILES, VOICE, API, ABOUT, DIAGNOSTICS, AGENT, MEMORY, STORAGE, DEVELOPER, DEV_CONTEXT }

private val THEME_OPTIONS get() = listOf(ThemeMode.SYSTEM to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_71bbc7), ThemeMode.LIGHT to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_48d0a0), ThemeMode.DARK to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_41e8e8))

/** One-line descriptions, kept in one place so the wording stays consistent (unit-tested). */
object SettingsText {
    val MODEL_BACKEND: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_90aa74)
    val GENERATION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_28a1cc)
    val CONTEXT: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0f3195)
    /** 2.1.3: 关于 IRIDIS › 开发者选项. */
    val DEVELOPER: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d6e5b3)
    val PERFORMANCE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3969dc)
    val VOICE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_078b04)
    val API_OFF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_21d98a)
    val THEME: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_37200b)
    val APPEARANCE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b4c216)
    val DELETE_ALL: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4566dc)
    /** 1.14.3 设置 › 存储空间. */
    val STORAGE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7c3347)
    val DIAGNOSTICS: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2f5693)
    val TIER_PROFILES: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cbe1b9)
    val THINKING_BUDGET: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4e15bc)
    val MAX_NEW: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_53a86d)
    val ADVANCED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.components_dfac15)
    /** Second line of the centred version footer on the settings home page (1.8.0). */
    val TAGLINE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5b702b)

    /** First line of the version footer: "IRIDIS 1.8.0-full" (1.14.5 rename). */
    fun versionLine(versionName: String) = "IRIDIS $versionName"

    /** The pages HOME links to, with their titles: the structure the 1.7.8 tests check. */
    val HOME_GROUPS: List<Pair<String, List<String>>> get() = listOf(
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3dc3df) to listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_035516), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4dfe70), ContextMemoryText.NAV, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2b7c2b)),
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6f492c) to listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b9a753), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2a59e7)),
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_379b88) to listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_afcde2)),
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0d8307) to listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5b7998), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_12ecbe)),
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_78944f) to listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_699379)), // 2.1.3: 资源诊断 moved to 关于 IRIDIS › 开发者选项
    )
}

@Composable
private fun NavItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SettingsRow(title, icon = icon, subtitle = subtitle, onClick = onClick, trailing = { Chevron() })
}

/**
 * 1.8.0 crash diagnostics: exports the last native crash (tombstone + PSS/RSS/时间), the MNN
 * load-stage log and a filtered logcat snapshot to the Download folder
 * (iris_tombstone.bin / iris_crash.txt / iris_mnn_load.log / iris_logcat.txt).
 */
@Composable
internal fun CrashDumpRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.iris.localai.util.CrashDump.Result?>(null) }
    SettingsRow(
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e45c28), icon = Icons.Outlined.BugReport,
        subtitle = if (busy) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0b6cea) else result?.let { r ->
            r.note + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dec892) + r.items.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c4530d)) { it.name + if (it.bytes > 0) " (${it.bytes / 1024} KB)" else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ac85da) + (it.note?.let { n -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_67f810, n) } ?: "") }
        } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1ca943),
        subtitleMaxLines = 6,
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.IO) { com.iris.localai.util.CrashDump.export(context) }
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

    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_e366cc), onBack) {
        ScrollBody {
            // 2.1.3: the 运行状态 rack moved to 关于 IRIDIS › 开发者选项 (DeveloperPage).
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3dc3df))
            SettingsGroup {
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_035516), SettingsText.MODEL_BACKEND, com.iris.localai.ui.icons.IrisIcons.Chip) { onOpen(SettingsPage.MODEL_BACKEND) }
                Divider()
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4dfe70), SettingsText.GENERATION, com.iris.localai.ui.icons.IrisIcons.Spark) { onOpen(SettingsPage.GENERATION) }
                Divider()
                NavItem(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2b7c2b), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_40a98a, com.iris.localai.engine.ResourceTier.of(s.resourceTier).label),
                    com.iris.localai.ui.icons.IrisIcons.Gauge,
                ) { onOpen(SettingsPage.PERFORMANCE) }
                Divider()
                // 1.9.0: 智能体 (tools, permissions, workspace). Not part of the 1.7.8 HOME_GROUPS structure test.
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_72fbb9), com.iris.localai.ui.agent.AgentText.NAV, com.iris.localai.ui.icons.IrisIcons.Agent) { onOpen(SettingsPage.AGENT) }
                Divider()
                // 2.1.3: 「上下文与记忆」 + 「记忆」 → one entry 「记忆与上下文」 (one page, context sections first).
                NavItem(ContextMemoryText.NAV, ContextMemoryText.NAV_SUB, com.iris.localai.ui.icons.IrisIcons.Memory) { onOpen(SettingsPage.MEMORY) }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6f492c))
            SettingsGroup {
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b9a753), SettingsText.VOICE, com.iris.localai.ui.icons.IrisIcons.Voice) { onOpen(SettingsPage.VOICE) }
                Divider()
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2a59e7), if (api.running) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_27de1c, api.port) else SettingsText.API_OFF, com.iris.localai.ui.icons.IrisIcons.Plug) { onOpen(SettingsPage.API) }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_379b88))
            SettingsGroup {
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_afcde2), SettingsText.APPEARANCE, com.iris.localai.ui.icons.IrisIcons.Contrast) { onOpen(SettingsPage.APPEARANCE) }
                Divider()
                // 2.3.4: 语言 is its own entry (moved out of 外观).
                val ctx = LocalContext.current
                val tag = remember { com.iris.localai.I18n.current(ctx) }
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.set234_lang),
                    com.iris.localai.I18n.LANGS.firstOrNull { it.first == tag }?.second ?: com.iris.localai.I18n.s(com.iris.localai.R.string.lang_system),
                    com.iris.localai.ui.icons.IrisIcons.Globe) { onOpen(SettingsPage.LANGUAGE) }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0d8307))
            SettingsGroup {
                // 1.14.3: 存储空间 (sizes by category, clear / delete per item).
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5b7998), SettingsText.STORAGE, com.iris.localai.ui.icons.IrisIcons.Storage) { onOpen(SettingsPage.STORAGE) }
                Divider()
                // 1.8.0: HyperOS destructive row: red text, no icon.
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_12ecbe), titleColor = Iris.colors.danger,
                    subtitle = SettingsText.DELETE_ALL, onClick = { confirmWipe = true },
                )
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_78944f))
            SettingsGroup {
                // 2.1.3: 资源诊断 (with 故障排查) moved to 关于 IRIDIS › 开发者选项.
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_699379), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_45020f, BuildConfig.VERSION_NAME), com.iris.localai.ui.icons.IrisIcons.Info) { onOpen(SettingsPage.ABOUT) }
            }
            VersionFooter(SettingsText.versionLine(BuildConfig.VERSION_NAME), SettingsText.TAGLINE)
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1db0aa),
            message = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c63448),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
            onConfirm = { vm.deleteAllConversations() },
            onDismiss = { confirmWipe = false },
        )
    }
}

/**
 * 2.2.16: the load settings (formerly the page 设置 › 模型与加载), shown at the bottom of the merged
 * 「模型与加载」 page ([com.iris.localai.ui.models.ModelsScreen]): one page for models, 档位 and loading.
 */
@Composable
fun ColumnScope.ModelLoadSettings(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().padding(end = IrisDimens.groupGutter), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_4793ea)) }
                IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7468f3), vm::resetLoadOptions, style = IrisButtonStyle.Text, compact = true)
            }
            SettingsGroup {
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1c8600), s.autoLoadLastModel, icon = com.iris.localai.ui.icons.IrisIcons.Power,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b6c8f1),
                ) { on -> vm.update { it.copy(autoLoadLastModel = on) } }
                Divider()
                DefaultModelRow(vm, s.defaultModelFile)
                Divider()
                VisionQualityRow(vm, s.visionSide)
                Divider()
                SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_41ba97), icon = com.iris.localai.ui.icons.IrisIcons.Reload, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1a7844), onClick = { vm.reloadModel() })
            }

            // 2.2.16: model / voice / 生图 downloads; 自动 picks the fastest reachable source and falls through.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_d98cb5))
            SettingsGroup {
                com.iris.localai.data.DownloadSource.entries.forEachIndexed { i, src ->
                    if (i > 0) Divider()
                    CheckRow(src.label, selected = s.downloadSource == src) { vm.update { it.copy(downloadSource = src) } }
                }
            }
            GroupFootnote(com.iris.localai.ui.models.SourceText.FOOTNOTE)

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_494ef0))
            SettingsGroup {
                // 2.2.0: QAIRT bundles only run on the NPU — nothing to choose.
                val formats = com.iris.localai.engine.EngineFactory.supportedFormats.filter { it != com.iris.localai.data.ModelFormat.QAIRT }
                // 2.1.3: GGUF on the NPU runs on GenieX; the NPU choice is offered once its runtime is installed.
                val gxReady = vm.geniex?.state?.collectAsState()?.value is com.iris.localai.engine.NpuState.Installed
                formats.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val cur = s.defaultBackends[f.name]
                    // 2.1.3: GGUF can pick NPU (= GenieX) in the full build; the backend decides the engine.
                    val htpOk = f == com.iris.localai.data.ModelFormat.GGUF && com.iris.localai.engine.llama.LlamaHtpRuntime.available(androidx.compose.ui.platform.LocalContext.current)
                    val withNpu = f == com.iris.localai.data.ModelFormat.GGUF && (htpOk || vm.geniex != null && (gxReady || cur == "NPU"))
                    SegmentedRow(
                        f.label, if (withNpu) listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3aed2c), "CPU", "GPU", "NPU") else listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3aed2c), "CPU", "GPU"),
                        when (cur) { "CPU" -> 1; "GPU" -> 2; "NPU" -> if (withNpu) 3 else 0; else -> 0 },
                        { idx -> vm.setDefaultBackend(f, when (idx) { 1 -> "CPU"; 2 -> "GPU"; 3 -> "NPU"; else -> null }) },
                        subtitle = when (f) {
                            com.iris.localai.data.ModelFormat.GGUF -> if (withNpu) (if (gxReady) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c3e012) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_681358)) else if (vm.geniex != null) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_92b631, com.iris.localai.engine.geniex.GgufEngineRule.NEED_GENIEX) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3d65ab)
                            com.iris.localai.data.ModelFormat.LITERT -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b329a5, com.iris.localai.engine.BackendCapabilities.LITERT_GPU_WARNING)
                            com.iris.localai.data.ModelFormat.MNN -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a3e9ec)
                            com.iris.localai.data.ModelFormat.QAIRT -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7bf960)
                        },
                    )
                }
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7dcdd9))

            vm.npu?.let { npu ->
                SectionTitle("NPU")
                SettingsGroup { NpuRuntimeRow(npu, onInstall = vm::installNpu, onCancel = vm::cancelNpu, onDelete = vm::deleteNpu) }
            }

            vm.geniex?.let { gx -> GenieXSection(vm, gx) }

            // 2.1.3: every load option sits behind 「高级」 (defaults unchanged).
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_04e3d1))
            SettingsGroup {
                AdvancedFold("load", SettingsText.ADVANCED) {
                    SwitchRow("Flash Attention", s.flashAttention, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c67060)) { on -> vm.update { it.copy(flashAttention = on) } }
                    Divider()
                    SegmentedRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1747d7), listOf("F16", "Q8_0", "Q4_0"), s.kvCacheType.coerceIn(0, 2),
                        { i -> vm.update { it.copy(kvCacheType = i) } },
                        subtitle = if (s.flashAttention) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b9637e) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1cf9cd),
                    )
                    Divider()
                    SliderRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b65a0d), s.batchSize.toFloat(), 0f..2048f,
                        if (s.batchSize == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_eb0286, vm.tierBatch(s.resourceTier)) else "${s.batchSize}",
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6935d2),
                    ) { v -> vm.update { it.copy(batchSize = (v / 64).toInt() * 64) } }
                    Divider()
                    SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_475473), s.useMmap, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_300fac)) { on -> vm.update { it.copy(useMmap = on) } }
                    Divider()
                    SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_810ed9), s.warmup, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_004e27)) { on -> vm.update { it.copy(warmup = on) } }
                }
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2a309b))
}

/** 设置 › 生成. */
@Composable
fun GenerationPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var editPrompt by rememberSaveable { mutableStateOf(false) }
    fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
    SubpageScaffold(
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4dfe70), onBack,
        actions = { IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7468f3), vm::resetGeneration, style = IrisButtonStyle.Text, compact = true) },
    ) {
        ScrollBody {
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4579b7))
            SettingsGroup {
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fba64a), icon = com.iris.localai.ui.icons.IrisIcons.Prompt,
                    subtitle = s.systemPrompt.ifBlank { com.iris.localai.I18n.s(com.iris.localai.R.string.settings_55a43c) }, subtitleMaxLines = 2, onClick = { editPrompt = true },
                )
                Divider()
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_1d878d), s.deepThinking, icon = com.iris.localai.ui.icons.IrisIcons.Think,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_18c5b3),
                ) { on -> vm.update { it.copy(deepThinking = on) } }
                Divider()
                SliderRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1727e1), s.thinkingBudget.toFloat(), 0f..8192f,
                    if (s.thinkingBudget == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7ce421) else "${s.thinkingBudget} tokens",
                    subtitle = SettingsText.THINKING_BUDGET + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d852f9),
                    steps = 31,
                ) { v -> vm.update { it.copy(thinkingBudget = (v / 256).toInt() * 256) } }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_74de60))
            SettingsGroup {
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fa7b28), com.iris.localai.data.TokenLimit.toSlider(s.maxNewTokens, 8192, 256), 256f..8448f, com.iris.localai.data.TokenLimit.label(s.maxNewTokens, " tokens"), subtitle = SettingsText.MAX_NEW) { v -> vm.update { it.copy(maxNewTokens = com.iris.localai.data.TokenLimit.fromSlider(v, 8192, 256)) } }
                Divider()
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c9bf0b), s.temperature, 0f..2f, f2(s.temperature), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_752316)) { v -> vm.update { it.copy(temperature = (v * 20).toInt() / 20f) } }
                Divider()
                AdvancedFold("sampling", SettingsText.ADVANCED) {
                    Divider()
                    SliderRow("Top-P", s.topP, 0.05f..1f, f2(s.topP), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0f59ca)) { v -> vm.update { it.copy(topP = (v * 20).toInt() / 20f) } }
                    Divider()
                    SliderRow("Top-K", s.topK.toFloat(), 0f..100f, if (s.topK == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91) else s.topK.toString(), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b7795b)) { v -> vm.update { it.copy(topK = v.toInt()) } }
                    Divider()
                    SliderRow("Min-P", s.minP, 0f..0.3f, f2(s.minP), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a915d4)) { v -> vm.update { it.copy(minP = (v * 100).toInt() / 100f) } }
                    Divider()
                    SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_43a45e), s.repeatPenalty, 1f..1.5f, f2(s.repeatPenalty), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_09ea9f)) { v -> vm.update { it.copy(repeatPenalty = (v * 100).toInt() / 100f) } }
                    Divider()
                    SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e4596e), s.repeatLastN.toFloat(), 0f..512f, "${s.repeatLastN} tokens", subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_818868)) { v -> vm.update { it.copy(repeatLastN = (v / 16).toInt() * 16) } }
                    Divider()
                    SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8f9adc), s.presencePenalty, -2f..2f, f2(s.presencePenalty), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_69e836)) { v -> vm.update { it.copy(presencePenalty = (v * 20).toInt() / 20f) } }
                    Divider()
                    SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4de5f1), s.frequencyPenalty, -2f..2f, f2(s.frequencyPenalty), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_531e21)) { v -> vm.update { it.copy(frequencyPenalty = (v * 20).toInt() / 20f) } }
                    Divider()
                    SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8d2639), s.seed >= 0, subtitle = if (s.seed >= 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_bc4200, s.seed) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2beef4)) { on ->
                        vm.update { it.copy(seed = if (on) 42 else -1) }
                    }
                }
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3b9f4b))
        }
    }

    if (editPrompt) {
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4dde09),
            initial = s.systemPrompt,
            onConfirm = { text -> vm.update { it.copy(systemPrompt = text.trim()) } },
            onDismiss = { editPrompt = false },
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb),
            placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_efaae4),
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
fun DevContextMemoryPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    // 2.2.9 F: 开发者选项 › 上下文与记忆 — every control of the old 记忆与上下文 page, unchanged.
    com.iris.localai.ui.agent.MemorySettingsPage(
        onBack = onBack,
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9f7070),
        actions = { IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7468f3), vm::resetContext, style = IrisButtonStyle.Text, compact = true) },
    ) {
        run {
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0ebfd1))
            SettingsGroup {
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b5663), s.contextLength.toFloat(), 1024f..32768f, "${s.contextLength} tokens", subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_474626)) { v ->
                    vm.update { it.copy(contextLength = (v / 1024).toInt() * 1024) }
                }
            }

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3f1fee))
            SettingsGroup {
                com.iris.localai.data.ContextStrategy.entries.forEach { st ->
                    if (st.ordinal > 0) Divider()
                    com.iris.localai.ui.components.CheckRow(st.label, s.contextStrategy == st.ordinal, subtitle = st.hint) {
                        vm.update { it.copy(contextStrategy = st.ordinal) }
                    }
                }
                Divider()
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dc8bfe), s.contextKeepTurns.toFloat(), 1f..32f, com.iris.localai.I18n.s(com.iris.localai.R.string.settings_14f1d0, s.contextKeepTurns), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f2104d), steps = 30) { v -> vm.update { it.copy(contextKeepTurns = v.toInt()) } }
                Divider()
                SliderRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0e766b), s.summaryTriggerPercent.toFloat(), 40f..95f, "${s.summaryTriggerPercent}%", subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4bc8ad)) { v -> vm.update { it.copy(summaryTriggerPercent = v.toInt()) } }
                Divider()
                // 2.2.9 E: 本对话的策略 (chat context sheet) only with this on.
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e471c9), s.developerMode, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_99314d)) { on -> vm.update { it.copy(developerMode = on) } }
            }
            if (com.iris.localai.context.ContextAssembly.devTakeover(com.iris.localai.data.ContextStrategy.of(s.contextStrategy))) {
                GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_97fdbb, com.iris.localai.context.ContextAssembly.TAKEOVER))
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9a4613))
        }
    }
}

/** 设置 › 性能与资源. */
@Composable
fun PerformancePage(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val tierApply by vm.tierApply.collectAsStateWithLifecycle()
    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2b7c2b), onBack) {
        ScrollBody {
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_81791e))
            SettingsGroup {
                // 1.7.6: the same slider as the composer card (1.7.4 form, muted blue fill, one line of text).
                com.iris.localai.ui.components.TierSlider(
                    labels = com.iris.localai.ui.chat.SpeedLevel.labels, // 1.10.1: 低速 / 高速 / 极速 (= 省电 / 中 / 高)
                    index = s.resourceTier.coerceIn(0, com.iris.localai.engine.ResourceTier.entries.lastIndex),
                    onChange = { m -> vm.update { it.copy(resourceTier = m) } },
                    status = com.iris.localai.engine.LiveResourcePolicy.statusText(tierApply),
                    modifier = Modifier.padding(start = IrisDimens.space16, end = IrisDimens.space16, top = IrisDimens.space12, bottom = 10.dp),
                )
                // 2.1.3: 资源档配置 moved to 关于 IRIDIS › 开发者选项.
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_25a91d) + com.iris.localai.engine.LiveResourcePolicy.GPU_SCOPE_NOTE + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_81197c))

            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ed5278))
            SettingsGroup {
                SliderRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ddb982),
                    s.idleUnloadMinutes.toFloat(), 0f..60f,
                    if (s.idleUnloadMinutes == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_487496) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c29004, s.idleUnloadMinutes),
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d52729),
                    steps = 11,
                ) { v -> vm.update { it.copy(idleUnloadMinutes = (v / 5).toInt() * 5) } }
                Divider()
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ab603e), s.backgroundGeneration, icon = Icons.Outlined.Notifications,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_539f31),
                ) { on -> vm.update { it.copy(backgroundGeneration = on) } }
                // 1.10.13: HyperOS freezes IRIS in the background unless it may run there.
                if (s.backgroundGeneration) {
                    Divider()
                    var exempt by remember { mutableStateOf(com.iris.localai.util.BackgroundGuard.isExempt(context)) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                        exempt = com.iris.localai.util.BackgroundGuard.isExempt(context)
                        onPauseOrDispose { }
                    }
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_956fee),
                        subtitle = if (exempt) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_db477e)
                        else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_29ee72),
                        icon = com.iris.localai.ui.icons.IrisIcons.Bolt,
                        onClick = { com.iris.localai.util.BackgroundGuard.open(context) },
                        trailing = { com.iris.localai.ui.components.ValueText(if (exempt) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_70c3a1) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_94754e)) },
                    )
                }
                Divider()
                val thermal by com.iris.localai.engine.ThermalGovernor.status.collectAsStateWithLifecycle()
                val nowText = if (thermal.tempC.isNaN()) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_80ef0f) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_137d5c, Math.round(thermal.tempC * 10) / 10.0)
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3bee9a), s.thermalLimitC > 0, icon = com.iris.localai.ui.icons.IrisIcons.Gauge,
                    subtitle = if (s.thermalLimitC == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9f3765, nowText)
                    else if (thermal.throttling) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b8b90f, nowText, thermal.capTps)
                    else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f1bd75, nowText),
                ) { on -> vm.update { it.copy(thermalLimitC = if (on) 42 else 0) } }
                if (s.thermalLimitC > 0) {
                    SliderRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d31153), s.thermalLimitC.toFloat(), 35f..50f, "${s.thermalLimitC}°C",
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d3af4c),
                        steps = 14,
                    ) { v -> vm.update { it.copy(thermalLimitC = v.toInt().coerceIn(35, 50)) } }
                }
                Divider()
                // 2.2.10: 终止温度 — hard stop + unload everything (no auto reload).
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6f5c6c), s.thermalCutoffC > 0, icon = com.iris.localai.ui.icons.IrisIcons.Power,
                    subtitle = if (s.thermalCutoffC == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e5ffc0, nowText)
                    else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a5ae10, s.thermalCutoffC),
                ) { on -> vm.update { it.copy(thermalCutoffC = if (on) com.iris.localai.engine.ThermalCutoff.DEFAULT_C else 0) } }
                if (s.thermalCutoffC > 0) {
                    SliderRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_82a989), s.thermalCutoffC.toFloat(),
                        com.iris.localai.engine.ThermalCutoff.MIN_C.toFloat()..com.iris.localai.engine.ThermalCutoff.MAX_C.toFloat(),
                        "${s.thermalCutoffC}°C",
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_153dc8),
                        steps = com.iris.localai.engine.ThermalCutoff.MAX_C - com.iris.localai.engine.ThermalCutoff.MIN_C - 1,
                    ) { v -> vm.update { it.copy(thermalCutoffC = com.iris.localai.engine.ThermalCutoff.sanitize(kotlin.math.round(v).toInt())) } }
                }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3ffddc), s.keepScreenOn, icon = Icons.Outlined.ScreenLockPortrait, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_65fb6d)) { on -> vm.update { it.copy(keepScreenOn = on) } }
                Divider()
                AdvancedFold("perf", SettingsText.ADVANCED) {
                    Divider()
                    SliderRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_539cb2), s.threads.toFloat(), 0f..8f,
                        if (s.threads == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_eb0286, vm.tierThreads(s.resourceTier)) else "${s.threads}",
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3a985e),
                        steps = 7,
                    ) { v -> vm.update { it.copy(threads = v.toInt()) } }
                    Divider()
                    SwitchRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a99d5a), s.perfOverlay, icon = com.iris.localai.ui.icons.IrisIcons.Gauge,
                        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_69a45f),
                    ) { on -> vm.update { it.copy(perfOverlay = on) } }
                    Divider()
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c43f5c), icon = com.iris.localai.ui.icons.IrisIcons.Gauge, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2a0f92),
                        onClick = { vm.runBenchmark() },
                    )
                }
            }
            BenchmarkDialog(vm)
        }
    }
}

@Composable
fun AboutPage(onBack: () -> Unit, onDeveloper: () -> Unit = {}) {
    val c = Iris.colors
    // 1.14.7: 许可证全文 is routed inside the About page (no new top-level route).
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    if (showLicenses) {
        LicensesPage(onBack = { showLicenses = false })
        return
    }
    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_81d9f5), onBack) {
        ScrollBody {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter, vertical = IrisDimens.space16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(72.dp).background(c.surfaceElevated, com.iris.localai.ui.theme.IrisRadius.appIconShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_iris_mark), null, tint = c.text, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(IrisDimens.space16))
                // 1.14.5: D9 wordmark — letters follow the theme ink, the three I's keep the brand gradient.
                Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.iridis_wordmark_ink), "IRIDIS", tint = c.text, modifier = Modifier.height(26.dp).width(134.dp))
                    androidx.compose.foundation.Image(painterResource(R.drawable.iridis_wordmark_accent), null, modifier = Modifier.height(26.dp).width(134.dp))
                }
                Spacer(Modifier.height(IrisDimens.space2))
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_45020f, BuildConfig.VERSION_NAME), style = IrisType.caption, color = c.textTertiary)
                Spacer(Modifier.height(IrisDimens.space16))
                Text(
                    ABOUT_PRIVACY,
                    style = IrisType.secondary, color = c.textSecondary, textAlign = TextAlign.Center,
                )
            }
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_20a284))
            SettingsGroup {
                SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_50322d), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_521717))
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4236d8), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_48b9fe),
                    onClick = { showLicenses = true }, trailing = { Chevron() },
                )
            }
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_843d86))
            SettingsGroup {
                OPEN_SOURCE.forEachIndexed { i, (name, desc) ->
                    if (i > 0) Divider()
                    SettingsRow(name, subtitle = desc)
                }
            }
            // 2.1.3: 开发者选项 at the bottom of 关于 IRIDIS.
            Spacer(Modifier.height(IrisDimens.space24))
            SettingsGroup {
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1b6949), SettingsText.DEVELOPER, com.iris.localai.ui.icons.IrisIcons.Pulse, onDeveloper)
            }
        }
    }
}

internal val ABOUT_PRIVACY: String get() =
    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1586eb) +
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2ae972)

/** 1.14.7: every bundled / downloadable third-party component with its license. */
internal val OPEN_SOURCE = listOf(
    "llama.cpp" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_bdd372),
    "MNN" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fd49fd),
    "Google LiteRT-LM" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_934216),
    "LiteRT Dispatch (Qualcomm)" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ac8dbd),
    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4e0e80) to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_887484),
    "sherpa-onnx" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_53cc9e),
    "ONNX Runtime" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c46311),
    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0bc708) to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_14990b),
    "AndroidX / Jetpack Compose" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dc1c39),
    "Kotlin / kotlinx" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_77baf4),
    "Apache Commons Compress" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4369be),
    "Silero VAD" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_84b6ac),
    "SenseVoice" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_cecfaa),
    "Kokoro" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d45d0c),
    "Zipformer CTC" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a358bf),
    "ZipVoice" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f6afeb),
    "Matcha baker" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8e93fd),
    "Piper xiao_ya" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_392cd5),
    "three.js" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_152596),
    "RhineLabUI" to com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f0f2f2),
)


@Composable
private fun NpuRuntimeRow(
    npu: com.iris.localai.engine.NpuSupport,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val st by npu.state.collectAsState()
    val chip = npu.chipLabel
    val mtk = npu.vendor == com.iris.localai.engine.NpuVendor.MEDIATEK
    // 1.14.7: the QNN runtime is Qualcomm's proprietary software; ask once per session before downloading.
    var askConsent by remember { mutableStateOf(false) }
    val install: () -> Unit = {
        // 2.4.4: MediaTek's dispatch library is Apache-2.0 and already in the APK — no Qualcomm terms to accept.
        if (NpuConsent.accepted || mtk) onInstall() else { askConsent = true }
    }
    if (askConsent) {
        NpuLicenseDialog(
            onAccept = { NpuConsent.accepted = true; askConsent = false; onInstall() },
            onDismiss = { askConsent = false },
        )
    }
    val (subtitle, action) = when (val s = st) {
        is com.iris.localai.engine.NpuState.Unsupported -> s.reason to null
        com.iris.localai.engine.NpuState.NotInstalled ->
            (if (mtk) com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_not_installed, chip) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_001963, chip)) to (com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f26ef9) to install)
        is com.iris.localai.engine.NpuState.Downloading ->
            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2b5c7e, s.downloaded shr 20, s.total shr 20) to (com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2) to onCancel)
        com.iris.localai.engine.NpuState.Installed ->
            (if (mtk) com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_installed, chip) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d9c3a7, chip)) to (com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad) to onDelete)
        is com.iris.localai.engine.NpuState.Failed -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ce1a3a, s.message) to (com.iris.localai.I18n.s(com.iris.localai.R.string.settings_132c5c) to install)
        is com.iris.localai.engine.NpuState.NeedsRepair ->
            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_501bdb, chip, s.reason) to (com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f82661) to install)
    }
    Column {
        SettingsRow(
            if (mtk) com.iris.localai.I18n.s(com.iris.localai.R.string.mtk244_title) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_42e701),
            subtitle = subtitle,
            subtitleColor = if (st is com.iris.localai.engine.NpuState.Failed || st is com.iris.localai.engine.NpuState.NeedsRepair) Iris.colors.danger else Iris.colors.textTertiary,
            trailing = action?.let { (label, fn) ->
                {
                    IrisButton(
                        label, fn, compact = true,
                        style = if (label == com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f26ef9) || label == com.iris.localai.I18n.s(com.iris.localai.R.string.settings_132c5c) || label == com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f82661)) IrisButtonStyle.Outline else IrisButtonStyle.Text,
                        destructive = label == com.iris.localai.I18n.s(com.iris.localai.R.string.chat_2f4aad),
                    )
                }
            },
        )
        (st as? com.iris.localai.engine.NpuState.Downloading)?.let { d ->
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

internal val NPU_LICENSE_TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0ccfb8)
internal val NPU_LICENSE_BODY: String get() =
    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_513cdf)
internal const val NPU_LICENSE_URL = "https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk"

@Composable
private fun NpuLicenseDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    IrisDialog(
        onDismiss = onDismiss,
        title = NPU_LICENSE_TITLE,
        confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ad7951),
        onConfirm = onAccept,
        dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2),
    ) {
        Column {
            Text(NPU_LICENSE_BODY, style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                com.iris.localai.I18n.s(com.iris.localai.R.string.settings_81d193), {
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
        st is SettingsViewModel.Bench.Running -> { confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_625fb2); onConfirm = { vm.cancelBenchmark() } }
        best != null -> { confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_0964c9, best.threads); onConfirm = { vm.applyThreads(best.threads) }; dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91) }
        else -> { confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ac2c8f); onConfirm = { vm.dismissBenchmark() } }
    }
    IrisDialog(
        onDismiss = { vm.dismissBenchmark() },
        title = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c43f5c),
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismissClick = { vm.dismissBenchmark() },
    ) {
        when (st) {
            is SettingsViewModel.Bench.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st.done == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7e19ab) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_292c29, st.done, st.total),
                    style = IrisType.secondary, color = c.textSecondary,
                )
                Spacer(Modifier.size(IrisDimens.space6))
                TypingDots()
            }
            is SettingsViewModel.Bench.Failed -> Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4141f7, st.message), style = IrisType.secondary, color = c.textSecondary)
            is SettingsViewModel.Bench.Done -> Column {
                Text(st.backend, style = IrisType.caption, color = c.textTertiary)
                Spacer(Modifier.height(IrisDimens.space12))
                Row(Modifier.fillMaxWidth()) {
                    for (h in listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d27612), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ff8d52), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4dfe70), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4a0fe1))) Text(h, Modifier.weight(1f), style = IrisType.caption, color = c.textTertiary)
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
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_fa4fe7, st.rows.firstOrNull()?.promptTokens ?: 0), style = IrisType.caption, color = c.textTertiary)
            }
            else -> {}
        }
    }
}


/** 1.14.1: the settings page each rack slot opens (same order as [RackRules.modules]). */
internal val RACK_PAGES = listOf(
    SettingsPage.MODEL_BACKEND, SettingsPage.AGENT, SettingsPage.AGENT, SettingsPage.AGENT,
    SettingsPage.VOICE, SettingsPage.API, SettingsPage.AGENT, SettingsPage.MEMORY,
)

/** 1.14.0: gathers the live state of every part of IRIS for [StatusRack]. */
@Composable
private fun RuntimeRack(vm: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as com.iris.localai.IrisApp).container.agent }
    val engine by vm.engine.collectAsStateWithLifecycle()
    val api by vm.apiStatus.collectAsStateWithLifecycle()
    val packs by vm.voicePacks.collectAsStateWithLifecycle()
    val ag by agent.settings.settings.collectAsStateWithLifecycle()
    val mem by agent.memorySettings.settings.collectAsStateWithLifecycle()
    val plans by agent.plans.plans.collectAsStateWithLifecycle()
    val live by com.iris.localai.util.SystemStatus.live.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val input = com.iris.localai.ui.settings.RackInput(
        modelName = when (val e = engine) {
            is com.iris.localai.engine.EngineState.Ready -> e.model.displayName
            is com.iris.localai.engine.EngineState.Loading -> e.model.displayName
            is com.iris.localai.engine.EngineState.Failed -> e.model?.displayName
            else -> null
        },
        modelState = when (val e = engine) {
            is com.iris.localai.engine.EngineState.Ready -> if (live.generating) RackState.RUN else RackState.ON
            is com.iris.localai.engine.EngineState.Loading -> RackState.LOAD
            is com.iris.localai.engine.EngineState.Failed -> RackState.ERROR
            else -> RackState.STANDBY
        },
        modelDetail = when (val e = engine) {
            is com.iris.localai.engine.EngineState.Ready -> e.details.activeBackend.label
            is com.iris.localai.engine.EngineState.Loading -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3a5cec, (e.progress * 100).toInt())
            is com.iris.localai.engine.EngineState.Failed -> e.message.take(40)
            else -> ""
        },
        modelChips = (engine as? com.iris.localai.engine.EngineState.Ready)?.let { e ->
            listOf(e.details.activeBackend.label, "${(e.details.contextLength / 1024).coerceAtLeast(1)}K", com.iris.localai.engine.ResourceTier.of(s.resourceTier).label)
        }.orEmpty(),
        agentMode = ag.agentMode, agentRunning = live.agentRunning, tool = live.tool, toolGroup = live.toolGroup, stepsDone = live.stepsDone,
        webOn = ag.isEnabled(com.iris.localai.agent.WebSearchTool) || ag.isEnabled(com.iris.localai.agent.WebFetchTool),
        screenOn = ag.isEnabled(com.iris.localai.agent.ScreenTool),
        voicePacks = packs.values.count { it == com.iris.localai.voice.PackState.Installed }, voiceActive = live.voiceActive,
        apiRunning = api.running, apiPort = api.port, apiError = api.error,
        memoryOn = mem.enabled,
        planLeft = plans.values.filter { !it.finished }.sumOf { it.steps.size - it.done },
    )
    val modules = RackRules.modules(input)
    com.iris.localai.ui.components.SettingsGroup {
        StatusRack(modules, generating = live.generating, modelReady = engine is com.iris.localai.engine.EngineState.Ready,
            modifier = Modifier.fillMaxWidth().padding(vertical = IrisDimens.space8),
            onOpen = { i -> onOpen(RACK_PAGES.getOrElse(i) { SettingsPage.AGENT }) })
    }
}


/** 1.14.1 外观: theme, visual effects, feedback. */
/** 2.3.4: 设置 › 语言 — the 2.3.1 picker on its own page (follow system + the 7 UI languages). */
@Composable
fun LanguagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val langTag = remember { com.iris.localai.I18n.current(context) }
    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.set234_lang), onBack) {
        ScrollBody {
            SettingsGroup {
                (listOf("" to com.iris.localai.I18n.s(com.iris.localai.R.string.lang_system)) + com.iris.localai.I18n.LANGS).forEachIndexed { i, (tag, name) ->
                    if (i > 0) Divider()
                    CheckRow(name, selected = tag == langTag) {
                        if (tag != langTag && activity != null) com.iris.localai.I18n.set(activity, tag)
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
    // 2.1.4 自定义背景: system photo picker (no storage permission); the picture is copied in, the URI dropped.
    val pickBackground = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) scope.launch {
            val name = com.iris.localai.ui.theme.BackgroundImage.import(context, uri)
            bgFailed = name == null
            if (name != null) vm.update { it.copy(backgroundImage = name) }
        }
    }
    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_afcde2), onBack) {
        ScrollBody {
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9970ad))
            SettingsGroup {
                SegmentedRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9970ad), icon = com.iris.localai.ui.icons.IrisIcons.Contrast,
                    options = THEME_OPTIONS.map { it.second },
                    selectedIndex = THEME_OPTIONS.indexOfFirst { it.first == s.themeMode },
                    onSelect = { i -> vm.update { it.copy(themeMode = THEME_OPTIONS[i].first) } },
                    subtitle = SettingsText.THEME,
                )
                Divider()
                PaletteSwatches(s.themePalette) { p -> vm.update { it.copy(themePalette = p.id) } }
                Divider()
                SegmentedRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_917205), com.iris.localai.ui.glass.GlassPolicy.LEVEL_LABELS, s.visualEffects.coerceIn(0, 2),
                    { i -> vm.update { it.copy(visualEffects = i, glassEffects = i > 0) } },
                    icon = com.iris.localai.ui.icons.IrisIcons.Eye,
                    subtitle = when (s.visualEffects) {
                        0 -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f24ad9)
                        2 -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_94b767)
                        else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_41d7e0)
                    } + com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2232ee),
                )
            }
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8e1b94))
            SettingsGroup {
                val hasBg = s.backgroundImage.isNotEmpty()
                // 2.2.14
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c847fe), s.solidBackground,
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1f71ce),
                    icon = com.iris.localai.ui.icons.IrisIcons.Image,
                ) { on -> vm.update { it.copy(solidBackground = on) } }
                Divider()
                SwitchRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e2bc30), s.dynamicBlend, enabled = !s.solidBackground,
                    subtitle = if (hasBg) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_d864cc) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_01c110),
                    icon = com.iris.localai.ui.icons.IrisIcons.Image,
                ) { on -> vm.update { it.copy(dynamicBlend = on) } }
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_ac429c),
                    subtitle = when { bgFailed -> AppearanceText.BACKGROUND_FAILED; hasBg -> AppearanceText.BACKGROUND_SET; else -> AppearanceText.BACKGROUND_NONE },
                    icon = com.iris.localai.ui.icons.IrisIcons.Image,
                    onClick = {
                        pickBackground.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    trailing = { Chevron() },
                )
                if (hasBg) {
                    Divider()
                    SliderRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_613ee9), s.backgroundBlur.toFloat(), 0f..com.iris.localai.ui.theme.BackgroundImage.MAX_BLUR_DP.toFloat(),
                        display = "${s.backgroundBlur} dp", subtitle = AppearanceText.BLUR,
                        steps = com.iris.localai.ui.theme.BackgroundImage.MAX_BLUR_DP - 1,
                    ) { v -> vm.update { it.copy(backgroundBlur = kotlin.math.round(v).toInt()) } }
                    Divider()
                    SliderRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_5f8050), s.backgroundScrim.toFloat(), 0f..com.iris.localai.ui.theme.BackgroundImage.MAX_SCRIM.toFloat(),
                        display = "${s.backgroundScrim}%", subtitle = AppearanceText.SCRIM,
                    ) { v -> vm.update { it.copy(backgroundScrim = kotlin.math.round(v).toInt()) } }
                    Divider()
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9a17b1), subtitle = AppearanceText.REMOVE, icon = com.iris.localai.ui.icons.IrisIcons.Trash,
                        titleColor = com.iris.localai.ui.theme.Iris.colors.danger,
                        onClick = {
                            bgFailed = false
                            vm.update { it.copy(backgroundImage = "") }
                            scope.launch { com.iris.localai.ui.theme.BackgroundImage.clear(context) }
                        },
                    )
                }
            }
            // 2.1.3: 顶栏按钮底色 / 运行状态机架 / 性能浮窗 moved to 关于 IRIDIS › 开发者选项 (DeveloperPage).
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1d50fa))
            SettingsGroup {
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_487676), s.haptics, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c5c770), icon = com.iris.localai.ui.icons.IrisIcons.Haptic) { on -> vm.update { it.copy(haptics = on) } }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_95ffd3), s.sound, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3666d0), icon = com.iris.localai.ui.icons.IrisIcons.Sound) { on -> vm.update { it.copy(sound = on) } }
            }
            RenderSettingsSection(s, vm::update) // 2.2 渲染网页代码 / 加载网络图片
        }
    }
}


/**
 * 2.1.3 关于 IRIDIS › 开发者选项: the rarely used tuning / diagnostics pages and display switches,
 * moved off the main settings list and 外观. Default values unchanged.
 */
@Composable
fun DeveloperPage(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SubpageScaffold(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1b6949), onBack) {
        ScrollBody {
            // 2.2.9 F: every old context / memory / index control moved here.
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9f7070))
            SettingsGroup {
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9f7070), com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2e9be5), com.iris.localai.ui.icons.IrisIcons.Memory) { onOpen(SettingsPage.DEV_CONTEXT) }
            }
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_eee83a))
            SettingsGroup {
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_6eba78), SettingsText.TIER_PROFILES, com.iris.localai.ui.icons.IrisIcons.Bolt) { onOpen(SettingsPage.TIER_PROFILES) }
                Divider()
                NavItem(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7ea069), SettingsText.DIAGNOSTICS, com.iris.localai.ui.icons.IrisIcons.Pulse) { onOpen(SettingsPage.DIAGNOSTICS) }
            }
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4d775d))
            SettingsGroup {
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2bb191), s.showStatusRack, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_9e41af)) { on -> vm.update { it.copy(showStatusRack = on) } }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_a99d5a), s.perfOverlay, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8a2e4e)) { on -> vm.update { it.copy(perfOverlay = on) } }
                Divider()
                SwitchRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2c1040), s.topBarCapsules, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_65d1b5)) { on -> vm.update { it.copy(topBarCapsules = on) } }
            }
            // 1.14.0 运行状态 rack (2.1.3: moved here from the settings home).
            if (s.showStatusRack) {
                SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e4b51d))
                RuntimeRack(vm, onOpen)
            }
        }
    }
}

/**
 * 2.1.7 「默认模型」: what tapping the chat logo loads (also used when a message needs a model and
 * none is loaded). 「上次使用的模型」 = the default ("" in settings).
 */
@Composable
private fun DefaultModelRow(vm: SettingsViewModel, current: String) {
    val models by vm.installedModels.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val name = models.firstOrNull { current.isNotBlank() && it.fileName == current }?.displayName
    SettingsRow(
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b11de2), icon = com.iris.localai.ui.icons.IrisIcons.Cube,
        subtitle = when {
            current.isBlank() -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_dfac96)
            name != null -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_80717f, name)
            else -> com.iris.localai.I18n.s(com.iris.localai.R.string.settings_65b42a)
        },
        onClick = { vm.refreshModels(); open = true },
    )
    if (open) {
        com.iris.localai.ui.components.IrisSheet(onDismiss = { open = false }) {
            com.iris.localai.ui.components.SheetHeader(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b11de2))
            com.iris.localai.ui.components.CheckRow(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_098aca), current.isBlank(), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3243cd)) {
                vm.setDefaultModel(""); open = false
            }
            models.forEach { m ->
                com.iris.localai.ui.components.CheckRow(m.displayName, current == m.fileName, subtitle = m.fileName) {
                    vm.setDefaultModel(m.fileName); open = false
                }
            }
        }
    }
}

/** 2.1.7 「识图清晰度」: long side of the picture a vision model reads (快速 384 / 标准 512 / 清晰 1024). */
@Composable
private fun VisionQualityRow(vm: SettingsViewModel, current: Int) {
    val options = com.iris.localai.util.VisionQuality.entries
    SegmentedRow(
        com.iris.localai.I18n.s(com.iris.localai.R.string.settings_524e9e), options.map { it.label }, com.iris.localai.util.VisionQuality.of(current).ordinal,
        { i -> vm.setVisionSide(options[i].side) },
        subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_f81386),
    )
}

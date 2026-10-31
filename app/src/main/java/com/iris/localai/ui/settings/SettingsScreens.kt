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
enum class SettingsPage { APPEARANCE, HOME, MODEL_BACKEND, GENERATION, CONTEXT, PERFORMANCE, TIER_PROFILES, VOICE, API, ABOUT, DIAGNOSTICS, AGENT, MEMORY, STORAGE, DEVELOPER }

private val THEME_OPTIONS = listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色")

/** One-line descriptions, kept in one place so the wording stays consistent (unit-tested). */
object SettingsText {
    const val MODEL_BACKEND = "模型与默认后端"
    const val GENERATION = "指令、思考与采样"
    const val CONTEXT = "上下文长度与超出处理"
    /** 2.1.3: one entry for the merged 记忆与上下文 page. */
    const val MEMORY_CONTEXT = "上下文长度、长期记忆与压缩"
    /** 2.1.3: 关于 IRIDIS › 开发者选项. */
    const val DEVELOPER = "资源档配置、资源诊断与调试显示"
    const val PERFORMANCE = "资源档与功耗"
    const val VOICE = "识别与朗读"
    const val API_OFF = "供其他 App 调用（未开启）"
    const val THEME = "浅色、深色或跟随系统"
    const val APPEARANCE = "主题、视觉效果、触感与音效"
    const val DELETE_ALL = "永久删除所有历史对话，无法恢复"
    /** 1.14.3 设置 › 存储空间. */
    const val STORAGE = "各类数据占用、备份与恢复"
    const val DIAGNOSTICS = "速度、功耗与故障排查"
    const val TIER_PROFILES = "分别设置 CPU 与 GPU 后端三档的线程、核心和优先级"
    const val THINKING_BUDGET = "思考最多用多少 token，不占用正文的最大回复长度；用完后直接写正文"
    const val MAX_NEW = "正文最多生成多少 token；思考另算"
    const val ADVANCED = "高级"
    /** Second line of the centred version footer on the settings home page (1.8.0). */
    const val TAGLINE = "本地大模型，对话不离开你的手机"

    /** First line of the version footer: "IRIDIS 1.8.0-full" (1.14.5 rename). */
    fun versionLine(versionName: String) = "IRIDIS $versionName"

    /** The pages HOME links to, with their titles: the structure the 1.7.8 tests check. */
    val HOME_GROUPS: List<Pair<String, List<String>>> = listOf(
        "模型与推理" to listOf("模型与后端", "生成", "记忆与上下文", "性能与资源"),
        "语音与服务" to listOf("语音", "API 服务"),
        "界面与外观" to listOf("外观"),
        "数据" to listOf("存储空间", "删除全部对话"),
        "关于与调试" to listOf("关于 IRIDIS"), // 2.1.3: 资源诊断 moved to 关于 IRIDIS › 开发者选项
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
        "导出上次崩溃日志", icon = Icons.Outlined.BugReport,
        subtitle = if (busy) "正在收集…" else result?.let { r ->
            r.note + "：" + r.items.joinToString("；") { it.name + if (it.bytes > 0) " (${it.bytes / 1024} KB)" else " (无)" + (it.note?.let { n -> "（$n）" } ?: "") }
        } ?: "闪退记录与日志片段 → 下载文件夹",
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
fun SettingsHome(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit, onModels: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val api by vm.apiStatus.collectAsStateWithLifecycle()
    var confirmWipe by rememberSaveable { mutableStateOf(false) }

    SubpageScaffold("设置", onBack) {
        ScrollBody {
            // 2.1.3: the 运行状态 rack moved to 关于 IRIDIS › 开发者选项 (DeveloperPage).
            SectionTitle("模型与推理")
            SettingsGroup {
                NavItem("模型与后端", SettingsText.MODEL_BACKEND, com.iris.localai.ui.icons.IrisIcons.Chip) { onOpen(SettingsPage.MODEL_BACKEND) }
                Divider()
                NavItem("生成", SettingsText.GENERATION, com.iris.localai.ui.icons.IrisIcons.Spark) { onOpen(SettingsPage.GENERATION) }
                Divider()
                NavItem(
                    "性能与资源", "当前：${com.iris.localai.engine.ResourceTier.of(s.resourceTier).label}",
                    com.iris.localai.ui.icons.IrisIcons.Gauge,
                ) { onOpen(SettingsPage.PERFORMANCE) }
                Divider()
                // 1.9.0: 智能体 (tools, permissions, workspace). Not part of the 1.7.8 HOME_GROUPS structure test.
                NavItem("智能体", com.iris.localai.ui.agent.AgentText.NAV, com.iris.localai.ui.icons.IrisIcons.Agent) { onOpen(SettingsPage.AGENT) }
                Divider()
                // 2.1.3: 「上下文与记忆」 + 「记忆」 → one entry 「记忆与上下文」 (one page, context sections first).
                NavItem("记忆与上下文", SettingsText.MEMORY_CONTEXT, com.iris.localai.ui.icons.IrisIcons.Memory) { onOpen(SettingsPage.MEMORY) }
            }

            SectionTitle("语音与服务")
            SettingsGroup {
                NavItem("语音", SettingsText.VOICE, com.iris.localai.ui.icons.IrisIcons.Voice) { onOpen(SettingsPage.VOICE) }
                Divider()
                NavItem("API 服务", if (api.running) "运行中 · 端口 ${api.port}" else SettingsText.API_OFF, com.iris.localai.ui.icons.IrisIcons.Plug) { onOpen(SettingsPage.API) }
            }

            SectionTitle("界面与外观")
            SettingsGroup {
                NavItem("外观", SettingsText.APPEARANCE, com.iris.localai.ui.icons.IrisIcons.Contrast) { onOpen(SettingsPage.APPEARANCE) }
            }

            SectionTitle("数据")
            SettingsGroup {
                // 1.14.3: 存储空间 (sizes by category, clear / delete per item).
                NavItem("存储空间", SettingsText.STORAGE, com.iris.localai.ui.icons.IrisIcons.Storage) { onOpen(SettingsPage.STORAGE) }
                Divider()
                // 1.8.0: HyperOS destructive row: red text, no icon.
                SettingsRow(
                    "删除全部对话", titleColor = Iris.colors.danger,
                    subtitle = SettingsText.DELETE_ALL, onClick = { confirmWipe = true },
                )
            }

            SectionTitle("关于与调试")
            SettingsGroup {
                // 2.1.3: 资源诊断 (with 故障排查) moved to 关于 IRIDIS › 开发者选项.
                NavItem("关于 IRIDIS", "版本 ${BuildConfig.VERSION_NAME}", com.iris.localai.ui.icons.IrisIcons.Info) { onOpen(SettingsPage.ABOUT) }
            }
            VersionFooter(SettingsText.versionLine(BuildConfig.VERSION_NAME), SettingsText.TAGLINE)
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "删除全部对话？",
            message = "所有历史对话将被永久删除，无法恢复。",
            confirmText = "删除",
            onConfirm = { vm.deleteAllConversations() },
            onDismiss = { confirmWipe = false },
        )
    }
}

/** 设置 › 模型与后端. */
@Composable
fun ModelBackendPage(vm: SettingsViewModel, onBack: () -> Unit, onModels: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SubpageScaffold(
        "模型与后端", onBack,
        actions = { IrisButton("恢复默认", vm::resetLoadOptions, style = IrisButtonStyle.Text, compact = true) },
    ) {
        ScrollBody {
            SectionTitle("模型")
            SettingsGroup {
                NavItem("模型管理", "下载、导入、删除模型；每个模型可单独设置后端和参数", com.iris.localai.ui.icons.IrisIcons.Cube, onModels)
                Divider()
                SwitchRow(
                    "启动时预加载上次的模型", s.autoLoadLastModel, icon = com.iris.localai.ui.icons.IrisIcons.Power,
                    subtitle = "打开 App 时就在后台加载上次用的模型，进入对话即可使用；关闭可省一点电和内存",
                ) { on -> vm.update { it.copy(autoLoadLastModel = on) } }
                Divider()
                DefaultModelRow(vm, s.defaultModelFile)
                Divider()
                VisionQualityRow(vm, s.visionSide)
                Divider()
                SettingsRow("立即重新加载模型", icon = com.iris.localai.ui.icons.IrisIcons.Reload, subtitle = "让需要重新加载才生效的修改马上生效", onClick = { vm.reloadModel() })
            }

            SectionTitle("默认后端")
            SettingsGroup {
                // 2.2.0: QAIRT bundles only run on the NPU — nothing to choose.
                val formats = com.iris.localai.engine.EngineFactory.supportedFormats.filter { it != com.iris.localai.data.ModelFormat.QAIRT }
                // 2.1.3: GGUF on the NPU runs on GenieX; the NPU choice is offered once its runtime is installed.
                val gxReady = vm.geniex?.state?.collectAsState()?.value is com.iris.localai.engine.NpuState.Installed
                formats.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val cur = s.defaultBackends[f.name]
                    // 2.1.3: GGUF can pick NPU (= GenieX) in the full build; the backend decides the engine.
                    val withNpu = f == com.iris.localai.data.ModelFormat.GGUF && vm.geniex != null && (gxReady || cur == "NPU")
                    SegmentedRow(
                        f.label, if (withNpu) listOf("自动", "CPU", "GPU", "NPU") else listOf("自动", "CPU", "GPU"),
                        when (cur) { "CPU" -> 1; "GPU" -> 2; "NPU" -> if (withNpu) 3 else 0; else -> 0 },
                        { idx -> vm.setDefaultBackend(f, when (idx) { 1 -> "CPU"; 2 -> "GPU"; 3 -> "NPU"; else -> null }) },
                        subtitle = when (f) {
                            com.iris.localai.data.ModelFormat.GGUF -> if (withNpu) "自动 = CPU。GPU 首次加载要编译；NPU 用 GenieX" else if (vm.geniex != null) "自动 = CPU。GPU 首次加载要编译；NPU ${com.iris.localai.engine.geniex.GgufEngineRule.NEED_GENIEX}" else "自动 = CPU。GPU 走 Adreno OpenCL，Q4_0 / Q8_0 最快，首次加载要编译内核"
                            com.iris.localai.data.ModelFormat.LITERT -> "自动 = GPU（Qwen3.5、Agents-A1 用 CPU）。GPU ${com.iris.localai.engine.BackendCapabilities.LITERT_GPU_WARNING}，之后会用缓存。文件名带 _sm8750 的 NPU 编译模型始终用 NPU"
                            com.iris.localai.data.ModelFormat.MNN -> "自动 = CPU。GPU 走 OpenCL"
                            com.iris.localai.data.ModelFormat.QAIRT -> "只能用 NPU"
                        },
                    )
                }
            }
            GroupFootnote("资源档不会改变后端。加载失败按 NPU → GPU → CPU 回退，并在聊天页提示；某个后端曾导致崩溃会被记住并跳过。聊天页标题下方显示实际在用的后端。")

            vm.npu?.let { npu ->
                SectionTitle("NPU")
                SettingsGroup { NpuRuntimeRow(npu, onInstall = vm::installNpu, onCancel = vm::cancelNpu, onDelete = vm::deleteNpu) }
            }

            vm.geniex?.let { gx -> GenieXSection(vm, gx) }

            // 2.1.3: every load option sits behind 「高级」 (defaults unchanged).
            SectionTitle("加载选项")
            SettingsGroup {
                AdvancedFold("load", SettingsText.ADVANCED) {
                    SwitchRow("Flash Attention", s.flashAttention, subtitle = "降低长上下文的内存与功耗（GGUF）") { on -> vm.update { it.copy(flashAttention = on) } }
                    Divider()
                    SegmentedRow(
                        "KV 缓存量化", listOf("F16", "Q8_0", "Q4_0"), s.kvCacheType.coerceIn(0, 2),
                        { i -> vm.update { it.copy(kvCacheType = i) } },
                        subtitle = if (s.flashAttention) "GGUF 专用。Q8_0 内存减半、质量几乎不变；Q4_0 更省但可能变差" else "需开启 Flash Attention",
                    )
                    Divider()
                    SliderRow(
                        "批大小", s.batchSize.toFloat(), 0f..2048f,
                        if (s.batchSize == 0) "跟随资源档（${vm.tierBatch(s.resourceTier)}）" else "${s.batchSize}",
                        subtitle = "GGUF 预填充一次处理的 token 数",
                    ) { v -> vm.update { it.copy(batchSize = (v / 64).toInt() * 64) } }
                    Divider()
                    SwitchRow("mmap 加载", s.useMmap, subtitle = "按需分页读取权重（GGUF / MNN）") { on -> vm.update { it.copy(useMmap = on) } }
                    Divider()
                    SwitchRow("加载后预热", s.warmup, subtitle = "加载完先跑 1 个 token，首条回复更快") { on -> vm.update { it.copy(warmup = on) } }
                }
            }
            GroupFootnote("修改后需重新加载模型。")
        }
    }
}

/** 设置 › 生成. */
@Composable
fun GenerationPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var editPrompt by rememberSaveable { mutableStateOf(false) }
    fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
    SubpageScaffold(
        "生成", onBack,
        actions = { IrisButton("恢复默认", vm::resetGeneration, style = IrisButtonStyle.Text, compact = true) },
    ) {
        ScrollBody {
            SectionTitle("指令与思考")
            SettingsGroup {
                SettingsRow(
                    "自定义指令（System Prompt）", icon = com.iris.localai.ui.icons.IrisIcons.Prompt,
                    subtitle = s.systemPrompt.ifBlank { "未设置；每次对话开头发给模型的说明" }, subtitleMaxLines = 2, onClick = { editPrompt = true },
                )
                Divider()
                SwitchRow(
                    "深度思考", s.deepThinking, icon = com.iris.localai.ui.icons.IrisIcons.Think,
                    subtitle = "支持思考的模型先推理再回答；与输入框上的「深度思考」按钮是同一个开关",
                ) { on -> vm.update { it.copy(deepThinking = on) } }
                Divider()
                SliderRow(
                    "思考预算", s.thinkingBudget.toFloat(), 0f..8192f,
                    if (s.thinkingBudget == 0) "与正文共用" else "${s.thinkingBudget} tokens",
                    subtitle = SettingsText.THINKING_BUDGET + "。设为 0 则与正文共用最大回复长度",
                    steps = 31,
                ) { v -> vm.update { it.copy(thinkingBudget = (v / 256).toInt() * 256) } }
            }

            SectionTitle("长度与采样")
            SettingsGroup {
                SliderRow("最大回复长度", s.maxNewTokens.toFloat(), 256f..8192f, "${s.maxNewTokens} tokens", subtitle = SettingsText.MAX_NEW) { v -> vm.update { it.copy(maxNewTokens = (v / 256).toInt() * 256) } }
                Divider()
                SliderRow("温度", s.temperature, 0f..2f, f2(s.temperature), subtitle = "越高越有创意，越低越稳定；0 为确定性输出") { v -> vm.update { it.copy(temperature = (v * 20).toInt() / 20f) } }
                Divider()
                AdvancedFold("sampling", SettingsText.ADVANCED) {
                    Divider()
                    SliderRow("Top-P", s.topP, 0.05f..1f, f2(s.topP), subtitle = "只在累计概率前 P 的候选词里取词") { v -> vm.update { it.copy(topP = (v * 20).toInt() / 20f) } }
                    Divider()
                    SliderRow("Top-K", s.topK.toFloat(), 0f..100f, if (s.topK == 0) "关闭" else s.topK.toString(), subtitle = "只在概率最高的 K 个候选词里取词；0 为关闭") { v -> vm.update { it.copy(topK = v.toInt()) } }
                    Divider()
                    SliderRow("Min-P", s.minP, 0f..0.3f, f2(s.minP), subtitle = "去掉概率低于最高者 P 倍的候选词") { v -> vm.update { it.copy(minP = (v * 100).toInt() / 100f) } }
                    Divider()
                    SliderRow("重复惩罚", s.repeatPenalty, 1f..1.5f, f2(s.repeatPenalty), subtitle = "降低重复用词；1.00 为不惩罚") { v -> vm.update { it.copy(repeatPenalty = (v * 100).toInt() / 100f) } }
                    Divider()
                    SliderRow("惩罚窗口", s.repeatLastN.toFloat(), 0f..512f, "${s.repeatLastN} tokens", subtitle = "重复、Presence、Frequency 惩罚统计最近多少个 token（GGUF）") { v -> vm.update { it.copy(repeatLastN = (v / 16).toInt() * 16) } }
                    Divider()
                    SliderRow("Presence 惩罚", s.presencePenalty, -2f..2f, f2(s.presencePenalty), subtitle = "正值鼓励换话题（GGUF）") { v -> vm.update { it.copy(presencePenalty = (v * 20).toInt() / 20f) } }
                    Divider()
                    SliderRow("Frequency 惩罚", s.frequencyPenalty, -2f..2f, f2(s.frequencyPenalty), subtitle = "正值减少重复用词（GGUF）") { v -> vm.update { it.copy(frequencyPenalty = (v * 20).toInt() / 20f) } }
                    Divider()
                    SwitchRow("固定随机种子", s.seed >= 0, subtitle = if (s.seed >= 0) "种子 ${s.seed}：相同输入得到相同输出（GGUF）" else "每次随机") { on ->
                        vm.update { it.copy(seed = if (on) 42 else -1) }
                    }
                }
            }
            GroupFootnote("这里是全局默认。每个模型可在模型选择或模型管理里点 ⚙ 单独设置，未设置的项跟随这里。「恢复默认」只恢复本页。")
        }
    }

    if (editPrompt) {
        TextInputDialog(
            title = "自定义指令",
            initial = s.systemPrompt,
            onConfirm = { text -> vm.update { it.copy(systemPrompt = text.trim()) } },
            onDismiss = { editPrompt = false },
            confirmText = "保存",
            placeholder = "例如：你是一位简洁、专业的中文助手。回答尽量使用要点。",
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
fun ContextPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    com.iris.localai.ui.agent.MemorySettingsPage(
        onBack = onBack,
        title = "记忆与上下文",
        actions = { IrisButton("恢复默认", vm::resetContext, style = IrisButtonStyle.Text, compact = true) },
    ) {
        run {
            SectionTitle("上下文窗口")
            SettingsGroup {
                SliderRow("上下文长度", s.contextLength.toFloat(), 1024f..32768f, "${s.contextLength} tokens", subtitle = "模型一次能看到的对话长度；越大越占内存，修改后重新加载模型生效") { v ->
                    vm.update { it.copy(contextLength = (v / 1024).toInt() * 1024) }
                }
            }

            SectionTitle("超出上下文时")
            SettingsGroup {
                com.iris.localai.data.ContextStrategy.entries.forEach { st ->
                    if (st.ordinal > 0) Divider()
                    com.iris.localai.ui.components.CheckRow(st.label, s.contextStrategy == st.ordinal, subtitle = st.hint) {
                        vm.update { it.copy(contextStrategy = st.ordinal) }
                    }
                }
                Divider()
                SliderRow("滑动窗口保留轮数", s.contextKeepTurns.toFloat(), 1f..32f, "${s.contextKeepTurns} 轮", subtitle = "「滑动窗口」保留最近多少轮对话", steps = 30) { v -> vm.update { it.copy(contextKeepTurns = v.toInt()) } }
                Divider()
                SliderRow("摘要触发阈值", s.summaryTriggerPercent.toFloat(), 40f..95f, "${s.summaryTriggerPercent}%", subtitle = "「自动摘要」在历史超过上下文预算的这个比例时，先总结早期对话") { v -> vm.update { it.copy(summaryTriggerPercent = v.toInt()) } }
            }
            GroupFootnote("每个对话可在输入框上方的上下文用量处单独改策略，或「从这里开始新上下文」。KV 上下文平移只有 GGUF（llama.cpp）支持，其他引擎按 token 预算截断。")
        }
    }
}

/** 设置 › 性能与资源. */
@Composable
fun PerformancePage(vm: SettingsViewModel, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val tierApply by vm.tierApply.collectAsStateWithLifecycle()
    SubpageScaffold("性能与资源", onBack) {
        ScrollBody {
            SectionTitle("资源档")
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
            GroupFootnote("越低越省电，越高越快；对话、语音和 API 都按这个档位。档位只调整 CPU 资源，不会改变后端。" + com.iris.localai.engine.LiveResourcePolicy.GPU_SCOPE_NOTE + "。")

            SectionTitle("功耗")
            SettingsGroup {
                SliderRow(
                    "空闲自动卸载模型",
                    s.idleUnloadMinutes.toFloat(), 0f..60f,
                    if (s.idleUnloadMinutes == 0) "从不" else "${s.idleUnloadMinutes} 分钟",
                    subtitle = "一段时间不用就释放内存，让芯片进入低功耗状态",
                    steps = 11,
                ) { v -> vm.update { it.copy(idleUnloadMinutes = (v / 5).toInt() * 5) } }
                Divider()
                SwitchRow(
                    "切到后台继续生成", s.backgroundGeneration, icon = Icons.Outlined.Notifications,
                    subtitle = "生成时显示进度通知（可停止），完成后通知你；语音对话不受影响",
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
                        "后台运行",
                        subtitle = if (exempt) "已允许忽略电池优化。小米/HyperOS 另需在应用详情把「省电策略」设为无限制并开启「自启动」"
                        else "未允许：切到后台后系统可能暂停生成。点按允许后台运行",
                        icon = com.iris.localai.ui.icons.IrisIcons.Bolt,
                        onClick = { com.iris.localai.util.BackgroundGuard.open(context) },
                        trailing = { com.iris.localai.ui.components.ValueText(if (exempt) "已允许" else "去允许") },
                    )
                }
                Divider()
                val thermal by com.iris.localai.engine.ThermalGovernor.status.collectAsStateWithLifecycle()
                val nowText = if (thermal.tempC.isNaN()) "读不到温度" else "当前 ${"%.1f".format(thermal.tempC)}°C"
                SwitchRow(
                    "温度限速", s.thermalLimitC > 0, icon = com.iris.localai.ui.icons.IrisIcons.Gauge,
                    subtitle = if (s.thermalLimitC == 0) "手机达到设定温度时放慢生成，减少发热（$nowText）"
                    else if (thermal.throttling) "$nowText · 正在限速（约 ${thermal.capTps} 字/秒）"
                    else "$nowText · 未限速",
                ) { on -> vm.update { it.copy(thermalLimitC = if (on) 42 else 0) } }
                if (s.thermalLimitC > 0) {
                    SliderRow(
                        "限速温度", s.thermalLimitC.toFloat(), 35f..50f, "${s.thermalLimitC}°C",
                        subtitle = "按电池温度判断；超过越多越慢（10 → 2 字/秒），降到设定以下 1°C 恢复",
                        steps = 14,
                    ) { v -> vm.update { it.copy(thermalLimitC = v.toInt().coerceIn(35, 50)) } }
                }
                Divider()
                SwitchRow("生成时保持屏幕常亮", s.keepScreenOn, icon = Icons.Outlined.ScreenLockPortrait, subtitle = "回复没写完时屏幕不熄灭") { on -> vm.update { it.copy(keepScreenOn = on) } }
                Divider()
                AdvancedFold("perf", SettingsText.ADVANCED) {
                    Divider()
                    SliderRow(
                        "推理线程", s.threads.toFloat(), 0f..8f,
                        if (s.threads == 0) "跟随资源档（${vm.tierThreads(s.resourceTier)}）" else "${s.threads}",
                        subtitle = "手动设置会替代所有档位的线程数（核心与优先级仍按档位）；一般保持「跟随资源档」",
                        steps = 7,
                    ) { v -> vm.update { it.copy(threads = v.toInt()) } }
                    Divider()
                    SwitchRow(
                        "性能浮窗", s.perfOverlay, icon = com.iris.localai.ui.icons.IrisIcons.Gauge,
                        subtitle = "生成时在聊天界面右上角显示速度、CPU、内存（GPU 能读到时才显示）",
                    ) { on -> vm.update { it.copy(perfOverlay = on) } }
                    Divider()
                    SettingsRow(
                        "速度测试", icon = com.iris.localai.ui.icons.IrisIcons.Gauge, subtitle = "用当前模型测预填充和生成速度；CPU 下会对比 4–8 个线程，约 1 分钟",
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
    SubpageScaffold("关于", onBack) {
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
                Text("版本 ${BuildConfig.VERSION_NAME}", style = IrisType.caption, color = c.textTertiary)
                Spacer(Modifier.height(IrisDimens.space16))
                Text(
                    ABOUT_PRIVACY,
                    style = IrisType.secondary, color = c.textSecondary, textAlign = TextAlign.Center,
                )
            }
            SectionTitle("许可证")
            SettingsGroup {
                SettingsRow("本应用许可证 · Apache-2.0", subtitle = "IRIDIS 源码以 Apache License 2.0 开源")
                Divider()
                SettingsRow(
                    "许可证全文", subtitle = "查看各第三方组件的许可证原文",
                    onClick = { showLicenses = true }, trailing = { Chevron() },
                )
            }
            SectionTitle("开源组件")
            SettingsGroup {
                OPEN_SOURCE.forEachIndexed { i, (name, desc) ->
                    if (i > 0) Divider()
                    SettingsRow(name, subtitle = desc)
                }
            }
            // 2.1.3: 开发者选项 at the bottom of 关于 IRIDIS.
            Spacer(Modifier.height(IrisDimens.space24))
            SettingsGroup {
                NavItem("开发者选项", SettingsText.DEVELOPER, com.iris.localai.ui.icons.IrisIcons.Pulse, onDeveloper)
            }
        }
    }
}

internal const val ABOUT_PRIVACY =
    "对话、推理、语音识别与合成都在本机完成，对话内容不会上传。联网仅用于：你发起的模型/语音包/NPU 运行库/GenieX 运行库/Linux 环境下载，" +
        "智能体开启联网工具时的搜索与网页读取，以及你开启的本地 API。"

/** 1.14.7: every bundled / downloadable third-party component with its license. */
internal val OPEN_SOURCE = listOf(
    "llama.cpp" to "MIT · GGUF 推理",
    "MNN" to "Apache-2.0（已修改）· MNN 推理",
    "Google LiteRT-LM" to "Apache-2.0 · .litertlm 推理",
    "LiteRT Dispatch (Qualcomm)" to "Apache-2.0 · NPU 调度层",
    "Qualcomm GenieX（Kotlin 层）" to "BSD-3-Clause · full 版 GenieX 引擎；原生运行库为高通专有软件，用户自行下载",
    "sherpa-onnx" to "Apache-2.0 · 语音识别 / 合成 / VAD",
    "ONNX Runtime" to "MIT · 语音模型运行时",
    "Khronos OpenCL 头文件" to "Apache-2.0 · GPU 加速",
    "AndroidX / Jetpack Compose" to "Apache-2.0 · 界面",
    "Kotlin / kotlinx" to "Apache-2.0 · 语言与基础库",
    "Apache Commons Compress" to "Apache-2.0 · 模型包解压",
    "Silero VAD" to "MIT · 人声检测",
    "SenseVoice" to "FunASR Model License · 语音识别模型",
    "Kokoro" to "Apache-2.0 · 语音合成模型",
    "Zipformer CTC" to "Apache-2.0 · 中文语音识别模型",
    "ZipVoice" to "代码 Apache-2.0 · 模型基于 Emilia（CC BY-NC 4.0，仅限非商业）· 音色克隆",
    "Matcha baker" to "标贝数据，仅限非商业 · 语音合成模型",
    "Piper xiao_ya" to "见模型许可 · 语音合成模型",
    "RhineLabUI" to "MIT · Copyright (c) 2026 LBEILC · 设计灵感：「文件」档案模式（未使用其代码）",
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
    // 1.14.7: the QNN runtime is Qualcomm's proprietary software; ask once per session before downloading.
    var askConsent by remember { mutableStateOf(false) }
    val install: () -> Unit = {
        if (NpuConsent.accepted) onInstall() else { askConsent = true }
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
            "$chip。运行 NPU 专用模型（文件名带芯片型号，如 _sm8750）所需；约 71 MB 下载，安装后占用约 113 MB" to ("下载" to install)
        is com.iris.localai.engine.NpuState.Downloading ->
            "正在下载 ${s.downloaded shr 20} / ${s.total shr 20} MB" to ("取消" to onCancel)
        com.iris.localai.engine.NpuState.Installed ->
            "$chip · 已安装（QNN 2.50）。加载 NPU 专用模型时自动使用" to ("删除" to onDelete)
        is com.iris.localai.engine.NpuState.Failed -> "下载失败：${s.message}" to ("重试" to install)
        is com.iris.localai.engine.NpuState.NeedsRepair ->
            "$chip · 未安装完整，需要修复（${s.reason}）。重新下载即可修复" to ("修复" to install)
    }
    Column {
        SettingsRow(
            "NPU 运行库（高通 Hexagon）",
            subtitle = subtitle,
            subtitleColor = if (st is com.iris.localai.engine.NpuState.Failed || st is com.iris.localai.engine.NpuState.NeedsRepair) Iris.colors.danger else Iris.colors.textTertiary,
            trailing = action?.let { (label, fn) ->
                {
                    IrisButton(
                        label, fn, compact = true,
                        style = if (label == "下载" || label == "重试" || label == "修复") IrisButtonStyle.Outline else IrisButtonStyle.Text,
                        destructive = label == "删除",
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

internal const val NPU_LICENSE_TITLE = "高通 NPU 运行库许可"
internal const val NPU_LICENSE_BODY =
    "该运行库（Qualcomm AI Engine Direct / QNN 2.50）为高通专有软件，不属于本项目开源部分，将从 Maven Central 下载。下载即表示你同意高通的许可条款。"
internal const val NPU_LICENSE_URL = "https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk"

@Composable
private fun NpuLicenseDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    IrisDialog(
        onDismiss = onDismiss,
        title = NPU_LICENSE_TITLE,
        confirmText = "同意并下载",
        onConfirm = onAccept,
        dismissText = "取消",
    ) {
        Column {
            Text(NPU_LICENSE_BODY, style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                "查看条款", {
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
        st is SettingsViewModel.Bench.Running -> { confirmText = "取消"; onConfirm = { vm.cancelBenchmark() } }
        best != null -> { confirmText = "使用 ${best.threads} 线程"; onConfirm = { vm.applyThreads(best.threads) }; dismissText = "关闭" }
        else -> { confirmText = "好"; onConfirm = { vm.dismissBenchmark() } }
    }
    IrisDialog(
        onDismiss = { vm.dismissBenchmark() },
        title = "速度测试",
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismissClick = { vm.dismissBenchmark() },
    ) {
        when (st) {
            is SettingsViewModel.Bench.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st.done == 0) "预热中" else "测试中 ${st.done}/${st.total}（请保持在本页面）",
                    style = IrisType.secondary, color = c.textSecondary,
                )
                Spacer(Modifier.size(IrisDimens.space6))
                TypingDots()
            }
            is SettingsViewModel.Bench.Failed -> Text("测试失败：${st.message}", style = IrisType.secondary, color = c.textSecondary)
            is SettingsViewModel.Bench.Done -> Column {
                Text(st.backend, style = IrisType.caption, color = c.textTertiary)
                Spacer(Modifier.height(IrisDimens.space12))
                Row(Modifier.fillMaxWidth()) {
                    for (h in listOf("线程", "预填充", "生成", "首字")) Text(h, Modifier.weight(1f), style = IrisType.caption, color = c.textTertiary)
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
                Text("单位 tok/s。提示词 ${st.rows.firstOrNull()?.promptTokens ?: 0} tokens，生成 64 tokens。", style = IrisType.caption, color = c.textTertiary)
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
            is com.iris.localai.engine.EngineState.Loading -> "加载 ${(e.progress * 100).toInt()}%"
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
    SubpageScaffold("外观", onBack) {
        ScrollBody {
            SectionTitle("主题")
            SettingsGroup {
                SegmentedRow(
                    "主题", icon = com.iris.localai.ui.icons.IrisIcons.Contrast,
                    options = THEME_OPTIONS.map { it.second },
                    selectedIndex = THEME_OPTIONS.indexOfFirst { it.first == s.themeMode },
                    onSelect = { i -> vm.update { it.copy(themeMode = THEME_OPTIONS[i].first) } },
                    subtitle = SettingsText.THEME,
                )
                Divider()
                PaletteSwatches(s.themePalette) { p -> vm.update { it.copy(themePalette = p.id) } }
                Divider()
                SegmentedRow(
                    "视觉效果", com.iris.localai.ui.glass.GlassPolicy.LEVEL_LABELS, s.visualEffects.coerceIn(0, 2),
                    { i -> vm.update { it.copy(visualEffects = i, glassEffects = i > 0) } },
                    icon = com.iris.localai.ui.icons.IrisIcons.Eye,
                    subtitle = when (s.visualEffects) {
                        0 -> "不做模糊、停用装饰动画；最省电"
                        2 -> "全分辨率毛玻璃，对话框背后也模糊"
                        else -> "降采样毛玻璃；日常推荐"
                    } + "。系统省电或发热时自动降级",
                )
            }
            SectionTitle("背景")
            SettingsGroup {
                val hasBg = s.backgroundImage.isNotEmpty()
                SettingsRow(
                    "自定义背景",
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
                        "模糊程度", s.backgroundBlur.toFloat(), 0f..com.iris.localai.ui.theme.BackgroundImage.MAX_BLUR_DP.toFloat(),
                        display = "${s.backgroundBlur} dp", subtitle = AppearanceText.BLUR,
                        steps = com.iris.localai.ui.theme.BackgroundImage.MAX_BLUR_DP - 1,
                    ) { v -> vm.update { it.copy(backgroundBlur = kotlin.math.round(v).toInt()) } }
                    Divider()
                    SliderRow(
                        "遮罩深浅", s.backgroundScrim.toFloat(), 0f..com.iris.localai.ui.theme.BackgroundImage.MAX_SCRIM.toFloat(),
                        display = "${s.backgroundScrim}%", subtitle = AppearanceText.SCRIM,
                    ) { v -> vm.update { it.copy(backgroundScrim = kotlin.math.round(v).toInt()) } }
                    Divider()
                    SettingsRow(
                        "移除背景", subtitle = AppearanceText.REMOVE, icon = com.iris.localai.ui.icons.IrisIcons.Trash,
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
            SectionTitle("反馈")
            SettingsGroup {
                SwitchRow("触感反馈", s.haptics, subtitle = "发送、步骤完成、长按和拖动时轻微振动", icon = com.iris.localai.ui.icons.IrisIcons.Haptic) { on -> vm.update { it.copy(haptics = on) } }
                Divider()
                SwitchRow("提示音", s.sound, subtitle = "发送和回复完成时的轻柔提示音；静音、勿扰或语音对话时不响", icon = com.iris.localai.ui.icons.IrisIcons.Sound) { on -> vm.update { it.copy(sound = on) } }
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
    SubpageScaffold("开发者选项", onBack) {
        ScrollBody {
            SectionTitle("资源")
            SettingsGroup {
                NavItem("资源档配置", SettingsText.TIER_PROFILES, com.iris.localai.ui.icons.IrisIcons.Bolt) { onOpen(SettingsPage.TIER_PROFILES) }
                Divider()
                NavItem("资源诊断", SettingsText.DIAGNOSTICS, com.iris.localai.ui.icons.IrisIcons.Pulse) { onOpen(SettingsPage.DIAGNOSTICS) }
            }
            SectionTitle("显示")
            SettingsGroup {
                SwitchRow("运行状态机架", s.showStatusRack, subtitle = "在本页显示各组件的运行状态") { on -> vm.update { it.copy(showStatusRack = on) } }
                Divider()
                SwitchRow("性能浮窗", s.perfOverlay, subtitle = "生成时在聊天页右上角显示速度、CPU 与内存") { on -> vm.update { it.copy(perfOverlay = on) } }
                Divider()
                SwitchRow("顶栏按钮底色", s.topBarCapsules, subtitle = "聊天页顶部的按钮显示灰色底") { on -> vm.update { it.copy(topBarCapsules = on) } }
            }
            // 1.14.0 运行状态 rack (2.1.3: moved here from the settings home).
            if (s.showStatusRack) {
                SectionTitle("运行状态")
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
        "默认模型", icon = com.iris.localai.ui.icons.IrisIcons.Cube,
        subtitle = when {
            current.isBlank() -> "上次使用的模型 · 点按对话页中间的图标即可加载"
            name != null -> "$name · 点按对话页中间的图标即可加载"
            else -> "已设置的模型不在了，将使用上次使用的模型"
        },
        onClick = { vm.refreshModels(); open = true },
    )
    if (open) {
        com.iris.localai.ui.components.IrisSheet(onDismiss = { open = false }) {
            com.iris.localai.ui.components.SheetHeader("默认模型")
            com.iris.localai.ui.components.CheckRow("上次使用的模型", current.isBlank(), subtitle = "不指定，沿用上次加载的模型") {
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
        "识图清晰度", options.map { it.label }, com.iris.localai.util.VisionQuality.of(current).ordinal,
        { i -> vm.setVisionSide(options[i].side) },
        subtitle = "发图片时模型看到的尺寸：快速 384 px、标准 512 px、清晰 1024 px。越清晰预填充越慢；MNN 最多 512 px",
    )
}

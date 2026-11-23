package com.iris.localai.ui.models
import androidx.compose.animation.AnimatedContent
import com.iris.localai.ui.components.Fold
import com.iris.localai.ui.components.InlineError
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.motion.motion
import com.iris.localai.ui.motion.SelectCheck
import com.iris.localai.ui.theme.LocalReduceMotion

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.data.CtxScale
import com.iris.localai.data.ModelAliases
import com.iris.localai.data.ModelFormat
import com.iris.localai.data.TokenLimit
import com.iris.localai.engine.EngineFeatures
import com.iris.localai.engine.GgufQuant
import com.iris.localai.engine.MoeLoad
import com.iris.localai.engine.MtpTag
import com.iris.localai.engine.llama.MmprojPairing
import com.iris.localai.engine.load.CpuFasterHint
import com.iris.localai.engine.load.ModelCache
import com.iris.localai.ui.components.SectionFold
import com.iris.localai.ui.components.TypingDots
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.IrisSpace
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.data.EffectiveModelConfig
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelOverrides
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.EngineState
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedRow
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.CheckRow
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.components.TextInputDialog
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * one model's backend and parameters. Every value starts out following the global default
 * (shown as "默认"); touching it stores an override for this model only. Opened from the model
 * picker and from 模型管理.
 */
@Composable
fun ModelConfigSheet(model: LocalModel, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as IrisApp).container
    val engine = container.engine
    val repo = container.modelConfigs
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val configs by repo.configs.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val o = configs[model.id] ?: ModelOverrides()
    val eff = EffectiveModelConfig.resolve(settings, model.format, o)
    var crashTick by remember { mutableIntStateOf(0) }
    val options = remember(model.id, configs, settings.defaultBackends, crashTick) { engine.backendOptions(model) }
    val gpuOff = remember(crashTick) { engine.environment().openClOff }
    var gpuRecheckNote by remember { mutableStateOf<String?>(null) }
    val crashed = remember(model.id, crashTick) { engine.crashMemory.crashed(model.id) }
    // GGUF can run on llama.cpp or Qualcomm GenieX (full flavour); 2.1.3: picked by the backend.
    val engineId = engine.engineFor(model, eff, options = options)
    val features = BackendCapabilities.features(model.format, model.fileName, engine = engineId)
    val auto = BackendCapabilities.autoBackend(model.format, model.fileName, options, engine.threadPolicy.nominal(engine.tier).allowGpu)
    val loaded = (state as? EngineState.Ready)?.takeIf { it.model.path == model.path }
    fun set(block: (ModelOverrides) -> ModelOverrides) = repo.update(model.id, block)
    fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
    var editPrompt by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) } // 2.5.5
    var editSeed by remember { mutableStateOf(false) }
    var pickMmproj by remember { mutableStateOf(false) }
    var mmprojTick by remember { mutableIntStateOf(0) }
    // copying a projector takes a while and could fail; both used to be invisible.
    var mmprojBusy by remember { mutableStateOf(false) }
    var mmprojError by remember { mutableStateOf<String?>(null) }
    val mmprojFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.scope.launch {
            mmprojBusy = true; mmprojError = null
            runCatching { container.models.importMmproj(model, uri) }
                .onSuccess { n -> set { it.copy(mmproj = n) }; mmprojTick++ }
                .onFailure { t -> mmprojError = I18n.s(R.string.mm245_attach_failed, t.message ?: t.javaClass.simpleName) }
            mmprojBusy = false
        }
    }
    // projectors in the model's folder (GGUF vision through llama.cpp mtmd).
    val mmprojSiblings = remember(model.path, pickMmproj, mmprojTick) {
        if (model.format != ModelFormat.GGUF) emptyList()
        else java.io.File(model.path).parentFile?.list()?.toList().orEmpty()
    }
    val mmprojChoices = remember(mmprojSiblings) { MmprojPairing.candidates(java.io.File(model.path).name, mmprojSiblings) }
    val mmprojAuto = remember(mmprojSiblings) { MmprojPairing.pick(java.io.File(model.path).name, mmprojSiblings) }
    // MTP layers read from the GGUF header on IO; the switch only appears when present.
    val hasMtp by androidx.compose.runtime.produceState(false, model.path) {
        // every LiteRT-LM model gets the switch too (its drafter lives inside the file).
        value = model.format == ModelFormat.LITERT ||
            model.format == ModelFormat.GGUF && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            GgufQuant.of(java.io.File(model.path))?.mtp == true
        }
    }
    // MoE 信息（专家数 / 默认激活数）从 GGUF 头读取；只有 MoE 才显示「专家按需加载」「激活专家数」。
    val moeInfo by androidx.compose.runtime.produceState<GgufQuant.Info?>(null, model.path) {
        value = if (model.format != ModelFormat.GGUF) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            GgufQuant.of(java.io.File(model.path))?.takeIf { it.moe }
        }
    }
    val c = Iris.colors

    // 改名后表头立即跟着变（model 参数是打开时的快照）
    val liveModels by container.models.models.collectAsStateWithLifecycle()
    val shownName = liveModels.firstOrNull { it.fileName == model.fileName }?.displayName ?: model.displayName
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(shownName) {
            IrisButton(I18n.s(R.string.settings_7468f3), { repo.reset(model.id) }, style = IrisButtonStyle.Text, compact = true, enabled = !o.isEmpty)
        }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
            Text(
                I18n.s(R.string.models_135fd8),
                style = IrisType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space4),
            )

            // no 「推理引擎」 switch any more — for GGUF the backend decides (NPU = GenieX, GPU / CPU = 内置 llama.cpp).

            // ---- Backend ----
            SectionTitle(I18n.s(R.string.models_e778d6))
            SettingsGroup {
                BackendRow(
                    I18n.s(R.string.settings_3aed2c), I18n.s(R.string.models_e367b9, auto.label),
                    selected = o.backend == null, enabled = true,
                ) { set { it.copy(backend = null) } }
                options.forEach { opt ->
                    Divider()
                    // an OpenCL hang on this device moves GGUF to CPU; retry clears it.
                    val hung = opt.backend == Backend.GPU && model.format == ModelFormat.GGUF &&
                        remember(crashTick) { engine.crashMemory.openClHung }
                    val crash = opt.backend in crashed || hung
                    BackendRow(
                        opt.backend.label,
                        when {
                            !opt.available -> opt.reason ?: I18n.s(R.string.chat_d1e4a7)
                            hung -> I18n.s(R.string.models_7bc0c1)
                            crash -> I18n.s(R.string.models_429d7e)
                            else -> opt.detail
                        },
                        selected = o.backend == opt.backend.name,
                        enabled = opt.available,
                        trailingAction = if (crash) ({ engine.crashMemory.forget(model.id); crashTick++ }) else null,
                    ) { set { it.copy(backend = opt.backend.name) } }
                }
            }
            // 上次加载崩溃后 GPU 被安全关闭：可手动重新检测。
            Fold(gpuOff || gpuRecheckNote != null) {
                val reduce = LocalReduceMotion.current
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        gpuRecheckNote ?: I18n.s(R.string.engine_cl_off), Modifier.weight(1f),
                        transitionSpec = { motion(reduce) { IrisMotion.crossfade() } }, label = "gpuNote",
                    ) { t ->
                        Text(t, style = IrisType.caption, color = c.textTertiary, maxLines = 3)
                    }
                    if (gpuOff) IrisButton(
                        I18n.s(R.string.models_gpu_recheck), {
                            val restart = engine.crashMemory.retryGpu()
                            crashTick++
                            val env = engine.environment()
                            gpuRecheckNote = when {
                                !env.hasOpenCl && !env.hasVulkan -> I18n.s(R.string.models_gpu_recheck_none)
                                restart -> I18n.s(R.string.models_gpu_recheck_restart)
                                else -> I18n.s(R.string.models_gpu_recheck_ok)
                            }
                        },
                        style = IrisButtonStyle.Text, compact = true,
                    )
                }
            }
            loaded?.let { r ->
                GroupFootnote(
                    I18n.s(R.string.models_7e17b1, r.details.backend) + (r.details.fallbackNote?.let { "\n$it" } ?: ""),
                )
            }
            // 「建议：这个模型在 CPU 上加载更快（可选）」 when its last GPU load was > 60 s and a CPU load was faster.
            val loadRecords by engine.loadStats.records.collectAsStateWithLifecycle()
            val nextLabel = remember(model.id, configs, settings.defaultBackends) { runCatching { engine.preferredBackend(model).label }.getOrDefault("") }
            if (CpuFasterHint.shouldShow(loadRecords.values, model.id, nextLabel)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        CpuFasterHint.TEXT, style = IrisType.caption, color = c.accent,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IrisButton(
                        CpuFasterHint.ACTION, { set { it.copy(backend = Backend.CPU.name) } },
                        style = IrisButtonStyle.Text, compact = true,
                    )
                }
            }
            GroupFootnote(I18n.s(R.string.models_32578b))

            // ---- Behaviour ----
            SectionTitle(I18n.s(R.string.models_a04961))
            SettingsGroup {
                // 自定义显示名（不改文件名）
                SettingsRow(
                    I18n.s(R.string.v255_rename),
                    subtitle = ModelAliases.of(model.fileName)?.let { I18n.s(R.string.v255_rename_file, model.fileName) }
                        ?: I18n.s(R.string.v255_rename_sub),
                    onClick = { editName = true },
                )
                Divider()
                SegmentedRow(
                    I18n.s(R.string.models_1d878d), listOf(I18n.s(R.string.models_112be9), I18n.s(R.string.components_849320), I18n.s(R.string.components_d58a55)),
                    when (o.thinking) { null -> 0; true -> 1; false -> 2 },
                    { i -> set { it.copy(thinking = when (i) { 1 -> true; 2 -> false; else -> null }) } },
                    subtitle = I18n.s(R.string.models_4ebee0),
                )
                Divider()
                SettingsRow(
                    I18n.s(R.string.models_24c18d),
                    subtitle = o.systemPrompt?.takeIf { it.isNotBlank() } ?: I18n.s(R.string.models_df255e),
                    subtitleMaxLines = 2,
                    onClick = { editPrompt = true },
                )
            }

            // ---- Context & length ----
            SectionTitle(I18n.s(R.string.models_dfd0cd))
            SettingsGroup {
                val fixed = features.fixedContext
                if (fixed != null) {
                    SettingsRow(I18n.s(R.string.models_3b5663), subtitle = I18n.s(R.string.models_2c1185, fixed), enabled = false)
                } else {
                    OvSlider(
                        I18n.s(R.string.models_3b5663), CtxScale.toPos(eff.contextLength), o.contextLength == null, CtxScale.RANGE, "${eff.contextLength}",
                        onChange = { v -> set { it.copy(contextLength = CtxScale.fromPos(v, 512)) } },
                        onReset = { set { it.copy(contextLength = null) } },
                    )
                }
                Divider()
                OvSlider(
                    I18n.s(R.string.models_422a72), TokenLimit.toSlider(eff.maxNewTokens, 8192, 128), o.maxNewTokens == null, 128f..8320f, TokenLimit.label(eff.maxNewTokens),
                    onChange = { v -> set { it.copy(maxNewTokens = TokenLimit.fromSlider(v, 8192, 128)) } },
                    onReset = { set { it.copy(maxNewTokens = null) } },
                )
            }

            moeInfo?.let { mi ->
                // 流式按需加载从「高级」折叠里移出，单独成组。
                SectionTitle(I18n.s(R.string.v255_moe_section))
                SettingsGroup {
                    // 专家按需加载（MoE）：默认 = 自动（内存不足时开），开关 + 「恢复默认」，不新增分段控件。
                    OvSwitch(I18n.s(R.string.v251_moe_cpu), eff.moeCpu ?: false, o.moeCpu == null,
                        subtitle = I18n.s(if (o.moeCpu == null) R.string.v251_moe_cpu_auto else R.string.v251_moe_cpu_sub),
                        onChange = { on -> set { it.copy(moeCpu = on) } }, onReset = { set { it.copy(moeCpu = null) } })
                    // LES 前瞻专家调度（只 qwen35moe）
                    if (MoeLoad.lesSupported(mi)) {
                        Divider()
                        // 手动选了 GPU 时，自动不开 LES
                        val gpuPicked = o.backend == Backend.GPU.name
                        OvSwitch(I18n.s(R.string.v254_les), eff.les ?: ((eff.moeCpu ?: false) && !gpuPicked), o.les == null,
                            subtitle = I18n.s(if (o.les == null) R.string.v254_les_auto else R.string.v254_les_sub),
                            onChange = { on -> set { it.copy(les = on) } }, onReset = { set { it.copy(les = null) } })
                    }
                    Divider()
                    // 激活专家数 0 = 模型默认，1..min(32, 专家数)
                    val maxExp = minOf(MoeLoad.MAX_EXPERTS_UI, mi.expertCount).coerceAtLeast(1)
                    OvSlider(
                        I18n.s(R.string.v251_experts), eff.expertUsed.coerceAtMost(maxExp).toFloat(), o.expertUsed == null, 0f..maxExp.toFloat(),
                        if (eff.expertUsed == 0) I18n.s(R.string.v251_experts_default, mi.expertUsed) else "${eff.expertUsed.coerceAtMost(maxExp)}",
                        subtitle = I18n.s(R.string.v251_experts_sub), steps = (maxExp - 1).coerceAtLeast(0),
                        onChange = { v -> set { it.copy(expertUsed = v.toInt()) } }, onReset = { set { it.copy(expertUsed = null) } },
                    )
                }
            }

            // 2.0.x: what is rarely touched folds away; unsupported options are not drawn one by
            // one as grey rows any more but named once under their group.
            val unsupported = UnsupportedOptions.names(features, model.format)
            SectionFold("model-adv-" + model.id, I18n.s(R.string.models_528fa3)) {
                // ---- Sampling ----
                SectionTitle(I18n.s(R.string.models_42ac9b))
                SettingsGroup {
                    OvSlider(I18n.s(R.string.settings_c9bf0b), eff.temperature, o.temperature == null, 0f..2f, f2(eff.temperature),
                        onChange = { v -> set { it.copy(temperature = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(temperature = null) } })
                    Divider()
                    OvSlider("Top-P", eff.topP, o.topP == null, 0.05f..1f, f2(eff.topP),
                        onChange = { v -> set { it.copy(topP = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(topP = null) } })
                    Divider()
                    OvSlider("Top-K", eff.topK.toFloat(), o.topK == null, 0f..100f, if (eff.topK == 0) I18n.s(R.string.ui_b15d91) else "${eff.topK}",
                        onChange = { v -> set { it.copy(topK = v.toInt()) } }, onReset = { set { it.copy(topK = null) } })
                    Divider()
                    if (features.minP) {
                        OvSlider("Min-P", eff.minP, o.minP == null, 0f..0.3f, f2(eff.minP),
                            onChange = { v -> set { it.copy(minP = (v * 100).toInt() / 100f) } }, onReset = { set { it.copy(minP = null) } })
                    }
                    Divider()
                    if (features.repeatPenalty) {
                        OvSlider(I18n.s(R.string.settings_43a45e), eff.repeatPenalty, o.repeatPenalty == null, 1f..1.5f, f2(eff.repeatPenalty),
                            onChange = { v -> set { it.copy(repeatPenalty = (v * 100).toInt() / 100f) } }, onReset = { set { it.copy(repeatPenalty = null) } })
                    }
                    Divider()
                    if (model.format == ModelFormat.GGUF) {
                        OvSlider(I18n.s(R.string.settings_e4596e), eff.repeatLastN.toFloat(), o.repeatLastN == null, 0f..512f, "${eff.repeatLastN} tokens",
                            subtitle = I18n.s(R.string.models_5be881),
                            onChange = { v -> set { it.copy(repeatLastN = (v / 16).toInt() * 16) } }, onReset = { set { it.copy(repeatLastN = null) } })
                    }
                    Divider()
                    if (features.presenceFrequency) {
                        OvSlider(I18n.s(R.string.settings_8f9adc), eff.presencePenalty, o.presencePenalty == null, -2f..2f, f2(eff.presencePenalty),
                            onChange = { v -> set { it.copy(presencePenalty = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(presencePenalty = null) } })
                        Divider()
                        OvSlider(I18n.s(R.string.settings_4de5f1), eff.frequencyPenalty, o.frequencyPenalty == null, -2f..2f, f2(eff.frequencyPenalty),
                            onChange = { v -> set { it.copy(frequencyPenalty = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(frequencyPenalty = null) } })
                    }
                    Divider()
                    if (features.seed) {
                        SettingsRow(
                            I18n.s(R.string.models_b0db48), subtitle = if (o.seed == null) I18n.s(R.string.models_bf8c1c, seedText(eff.seed)) else seedText(eff.seed),
                            onClick = { editSeed = true },
                        )
                    }
                }

                // ---- Performance ----
                SectionTitle(I18n.s(R.string.models_c3318e))
                SettingsGroup {
                    val tierPlan = engine.threadPolicy.nominal(engine.tier)
                    OvSlider(
                        I18n.s(R.string.models_aadb83), eff.threads.toFloat(), o.threads == null, 0f..12f,
                        if (eff.threads == 0) I18n.s(R.string.settings_eb0286, tierPlan.threads) else "${eff.threads}", steps = 11,
                        onChange = { v -> set { it.copy(threads = v.toInt()) } }, onReset = { set { it.copy(threads = null) } },
                    )
                    Divider()
                    if (features.batchSize) {
                        OvSlider(
                            I18n.s(R.string.settings_b65a0d), eff.batchSize.toFloat(), o.batchSize == null, 0f..2048f,
                            if (eff.batchSize == 0) I18n.s(R.string.settings_eb0286, tierPlan.batch) else "${eff.batchSize}",
                            subtitle = I18n.s(R.string.models_92e1b7),
                            onChange = { v -> set { it.copy(batchSize = (v / 64).toInt() * 64) } }, onReset = { set { it.copy(batchSize = null) } },
                        )
                    }
                    Divider()
                    if (features.flashAttention) {
                        OvSwitch("Flash Attention", eff.flashAttention, o.flashAttention == null, onChange = { on -> set { it.copy(flashAttention = on) } }, onReset = { set { it.copy(flashAttention = null) } })
                    }
                    Divider()
                    if (features.kvQuant) {
                        SegmentedRow(
                            I18n.s(R.string.settings_1747d7) + if (o.kvCacheType == null) I18n.s(R.string.models_c63342) else "",
                            listOf("F16", "Q8_0", "Q4_0"), eff.kvCacheType,
                            { i -> set { it.copy(kvCacheType = i) } },
                            subtitle = if (eff.flashAttention) I18n.s(R.string.models_f3d41d) else I18n.s(R.string.settings_1cf9cd),
                        )
                    }
                    Divider()
                    if (features.mmap) {
                        OvSwitch(I18n.s(R.string.settings_475473), eff.useMmap, o.useMmap == null, subtitle = I18n.s(R.string.models_f1f0d5),
                            onChange = { on -> set { it.copy(useMmap = on) } }, onReset = { set { it.copy(useMmap = null) } })
                    }
                    if (model.format == ModelFormat.GGUF) {
                        Divider()
                        SettingsRow(
                            // always offered for GGUF — 挂载视觉投影 (mmproj)
                            I18n.s(R.string.mm235_attach),
                            subtitle = when (o.mmproj) {
                                null -> I18n.s(R.string.models_a73152) + (mmprojAuto ?: I18n.s(R.string.models_a21729))
                                MmprojPairing.OFF -> I18n.s(R.string.models_8bfbde)
                                else -> o.mmproj
                            } + I18n.s(R.string.models_875dd5),
                            onClick = { pickMmproj = true },
                        )
                    }
                    if (hasMtp) {
                        Divider()
                        OvSwitch(I18n.s(R.string.v259_mtp), eff.mtp, o.mtp == null, subtitle = I18n.s(R.string.v259_mtp_sub) +
                            (if (MtpTag.gpuNote(eff.backend, loaded?.details?.backend)) I18n.s(R.string.v2510_mtp_gpu) else ""),
                            onChange = { on -> set { it.copy(mtp = on) } }, onReset = { set { it.copy(mtp = null) } })
                    }
                    Divider()
                    OvSwitch(I18n.s(R.string.settings_810ed9), eff.warmup, o.warmup == null, subtitle = I18n.s(R.string.settings_004e27),
                        onChange = { on -> set { it.copy(warmup = on) } }, onReset = { set { it.copy(warmup = null) } })
                }

                if (unsupported.isNotEmpty()) GroupFootnote(I18n.s(R.string.models_773920) + unsupported.joinToString(I18n.s(R.string.agent_b50566)))
                // ---- 1.13.1: this model's on-disk caches ----
                val ctx = LocalContext.current
                var cacheTick by remember { mutableIntStateOf(0) }
                var cacheBytes by remember { mutableStateOf<Long?>(null) }
                androidx.compose.runtime.LaunchedEffect(model.fileName, cacheTick) {
                    cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ModelCache.bytes(ModelCache.files(ctx, model.fileName))
                    }
                }
                var cacheNote by remember { mutableStateOf<String?>(null) }
                SectionTitle(I18n.s(R.string.models_e80c31))
                SettingsGroup {
                    val b = cacheBytes
                    SettingsRow(
                        I18n.s(R.string.models_8ded69),
                        subtitle = cacheNote ?: when {
                            b == null -> I18n.s(R.string.models_dc020b)
                            b == 0L -> I18n.s(R.string.models_373e22) + if (model.format == ModelFormat.GGUF) I18n.s(R.string.models_67478c) else ""
                            else -> I18n.s(R.string.models_7c5be1, cacheSize(b)) + if (loaded != null) I18n.s(R.string.models_1bb7db) else ""
                        },
                        enabled = (b ?: 0L) > 0L,
                        onClick = {
                            scope.launch {
                                val (n, freed) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelCache.clear(ctx, model.fileName) }
                                cacheNote = I18n.s(R.string.models_cfd753, n, cacheSize(freed))
                                cacheTick++
                            }
                        },
                    )
                }

            }

            if (loaded != null) {
                Spacer(Modifier.height(IrisDimens.space12))
                IrisButton(
                    I18n.s(R.string.models_466ae2), { scope.launch { engine.reload() } }, icon = IrisIcons.Reload,
                    style = IrisButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
                )
            }
            Spacer(Modifier.height(IrisDimens.space12))
        }
    }

    if (editName) {
        TextInputDialog(
            title = I18n.s(R.string.v255_rename),
            initial = ModelAliases.of(model.fileName) ?: model.fileDisplayName,
            onConfirm = { t -> container.models.rename(model.fileName, t.takeIf { it.trim() != model.fileDisplayName }) },
            onDismiss = { editName = false },
            confirmText = I18n.s(R.string.settings_be5fbb),
            placeholder = model.fileDisplayName,
            allowEmpty = true,
        )
    }
    if (editPrompt) {
        TextInputDialog(
            title = I18n.s(R.string.models_24c18d),
            initial = o.systemPrompt.orEmpty(),
            onConfirm = { t -> set { it.copy(systemPrompt = t.trim().ifEmpty { null }) } },
            onDismiss = { editPrompt = false },
            confirmText = I18n.s(R.string.settings_be5fbb),
            placeholder = I18n.s(R.string.models_cfe653),
            singleLine = false,
            allowEmpty = true,
        )
    }
    if (pickMmproj) {
        IrisDialog(onDismiss = { pickMmproj = false }, title = I18n.s(R.string.models_f08420), confirmText = I18n.s(R.string.agent_769d88), onConfirm = { pickMmproj = false }, dismissText = null) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CheckRow(I18n.s(R.string.models_fd346f), o.mmproj == null, subtitle = mmprojAuto ?: I18n.s(R.string.models_d0a02b)) { set { it.copy(mmproj = null) } }
                CheckRow(I18n.s(R.string.models_57ed11), o.mmproj == MmprojPairing.OFF) { set { it.copy(mmproj = MmprojPairing.OFF) } }
                mmprojChoices.forEach { f -> CheckRow(f, o.mmproj == f) { set { it.copy(mmproj = f) } } }
                // pick / replace from a file; 移除 = never load one
                if (mmprojBusy) SettingsRow(I18n.s(R.string.mm245_copying), trailing = { TypingDots() })
                else CheckRow(I18n.s(R.string.mm235_pick), false) { mmprojFilePicker.launch(arrayOf("*/*")) }
                InlineError(mmprojError, Modifier.padding(horizontal = IrisSpace.L, vertical = IrisSpace.XS))
                if (o.mmproj != MmprojPairing.OFF && (o.mmproj != null || mmprojAuto != null))
                    CheckRow(I18n.s(R.string.mm235_remove), false) { set { it.copy(mmproj = MmprojPairing.OFF) } }
            }
        }
    }
    if (editSeed) {
        TextInputDialog(
            title = I18n.s(R.string.models_b0db48),
            initial = if (eff.seed < 0) "" else eff.seed.toString(),
            onConfirm = { t -> set { it.copy(seed = t.trim().toIntOrNull()?.coerceAtLeast(0) ?: -1) } },
            onDismiss = { editSeed = false },
            confirmText = I18n.s(R.string.settings_be5fbb),
            placeholder = I18n.s(R.string.models_3e1d97),
            allowEmpty = true,
        )
    }
}

private fun seedText(seed: Int) = if (seed < 0) I18n.s(R.string.settings_2beef4) else I18n.s(R.string.models_23cc34, seed)

@Composable
private fun BackendRow(
    title: String, subtitle: String, selected: Boolean, enabled: Boolean,
    trailingAction: (() -> Unit)? = null, onClick: () -> Unit,
) {
    SettingsRow(title, subtitle = subtitle, enabled = enabled, role = Role.RadioButton, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trailingAction != null) {
                Text(I18n.s(R.string.chat_4403fc), style = IrisType.labelSmall, color = Iris.colors.accent, modifier = Modifier.clickable(onClick = trailingAction).padding(IrisDimens.space8))
            }
            Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
                SelectCheck(selected) { Icon(Icons.Outlined.Check, I18n.s(R.string.chat_f08afd), tint = Iris.colors.accent, modifier = Modifier.size(IrisDimens.iconMedium)) }
            }
        }
    }
}

/** 2.0.x: options this engine does not have, named once instead of one grey row each (unit-tested). */
object UnsupportedOptions {
    fun names(f: EngineFeatures, format: ModelFormat): List<String> = buildList {
        if (!f.minP) add("Min-P")
        if (!f.repeatPenalty) add(I18n.s(R.string.settings_43a45e))
        if (format != ModelFormat.GGUF) add(I18n.s(R.string.settings_e4596e))
        if (!f.presenceFrequency) add(I18n.s(R.string.models_68cc4a))
        if (!f.seed) add(I18n.s(R.string.models_b0db48))
        if (!f.batchSize) add(I18n.s(R.string.settings_b65a0d))
        if (!f.flashAttention) add("Flash Attention")
        if (!f.kvQuant) add(I18n.s(R.string.settings_1747d7))
        if (!f.mmap) add("mmap")
    }
}

@Composable
private fun OvSlider(
    title: String, value: Float, inherited: Boolean, range: ClosedFloatingPointRange<Float>, display: String,
    subtitle: String? = null, steps: Int = 0, onChange: (Float) -> Unit, onReset: () -> Unit,
) {
    SliderRow(title, value, range, if (inherited) I18n.s(R.string.models_82a38b, display) else display, subtitle = subtitle, steps = steps, onChange = onChange)
    Fold(!inherited) { ResetLink(onReset) }
}

@Composable
private fun OvSwitch(title: String, checked: Boolean, inherited: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit, onReset: () -> Unit) {
    SwitchRow(title + if (inherited) I18n.s(R.string.models_c63342) else "", checked, subtitle = subtitle, onChange = onChange)
    Fold(!inherited) { ResetLink(onReset) }
}

@Composable
private fun ResetLink(onReset: () -> Unit) {
    Text(
        I18n.s(R.string.models_24eeac), style = IrisType.labelSmall, color = Iris.colors.accent,
        modifier = Modifier.padding(start = IrisDimens.rowPadding, bottom = IrisDimens.space8).clickable(onClick = onReset).padding(vertical = IrisDimens.space4),
    )
}

/** bytes as KB / MB / GB for the cache row. */
internal fun cacheSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> "${(b + 1023) / 1024} KB"
}

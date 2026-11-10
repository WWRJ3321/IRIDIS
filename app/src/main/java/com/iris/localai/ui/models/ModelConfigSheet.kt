package com.iris.localai.ui.models

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
 * 1.7.1: one model's backend and parameters. Every value starts out following the global default
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
    val options = remember(model.id, configs, settings.defaultBackends) { engine.backendOptions(model) }
    var crashTick by remember { mutableIntStateOf(0) }
    val crashed = remember(model.id, crashTick) { engine.crashMemory.crashed(model.id) }
    // 2.2.0: GGUF can run on llama.cpp or Qualcomm GenieX (full flavour); 2.1.3: picked by the backend.
    val engineId = engine.engineFor(model, eff, options = options)
    val features = BackendCapabilities.features(model.format, model.fileName, engine = engineId)
    val auto = BackendCapabilities.autoBackend(model.format, model.fileName, options, engine.threadPolicy.nominal(engine.tier).allowGpu)
    val loaded = (state as? EngineState.Ready)?.takeIf { it.model.path == model.path }
    fun set(block: (ModelOverrides) -> ModelOverrides) = repo.update(model.id, block)
    fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
    var editPrompt by remember { mutableStateOf(false) }
    var editSeed by remember { mutableStateOf(false) }
    var pickMmproj by remember { mutableStateOf(false) }
    var mmprojTick by remember { mutableIntStateOf(0) }
    // 2.4.5 (#12): copying a projector takes a while and could fail; both used to be invisible.
    var mmprojBusy by remember { mutableStateOf(false) }
    var mmprojError by remember { mutableStateOf<String?>(null) }
    val mmprojFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.scope.launch {
            mmprojBusy = true; mmprojError = null
            runCatching { container.models.importMmproj(model, uri) }
                .onSuccess { n -> set { it.copy(mmproj = n) }; mmprojTick++ }
                .onFailure { t -> mmprojError = com.iris.localai.I18n.s(com.iris.localai.R.string.mm245_attach_failed, t.message ?: t.javaClass.simpleName) }
            mmprojBusy = false
        }
    }
    // 2.2.17: projectors in the model's folder (GGUF vision through llama.cpp mtmd).
    val mmprojSiblings = remember(model.path, pickMmproj, mmprojTick) {
        if (model.format != com.iris.localai.data.ModelFormat.GGUF) emptyList()
        else java.io.File(model.path).parentFile?.list()?.toList().orEmpty()
    }
    val mmprojChoices = remember(mmprojSiblings) { com.iris.localai.engine.llama.MmprojPairing.candidates(java.io.File(model.path).name, mmprojSiblings) }
    val mmprojAuto = remember(mmprojSiblings) { com.iris.localai.engine.llama.MmprojPairing.pick(java.io.File(model.path).name, mmprojSiblings) }
    // 2.5.0-beta9: MTP layers read from the GGUF header on IO; the switch only appears when present.
    val hasMtp by androidx.compose.runtime.produceState(false, model.path) {
        value = model.format == com.iris.localai.data.ModelFormat.GGUF && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.iris.localai.engine.GgufQuant.of(java.io.File(model.path))?.mtp == true
        }
    }
    val c = Iris.colors

    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(model.displayName) {
            IrisButton(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_7468f3), { repo.reset(model.id) }, style = IrisButtonStyle.Text, compact = true, enabled = !o.isEmpty)
        }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
            Text(
                com.iris.localai.I18n.s(com.iris.localai.R.string.models_135fd8),
                style = IrisType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space4),
            )

            // 2.1.3: no 「推理引擎」 switch any more — for GGUF the backend decides (NPU = GenieX, GPU / CPU = 内置 llama.cpp).

            // ---- Backend ----
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_e778d6))
            SettingsGroup {
                BackendRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.settings_3aed2c), com.iris.localai.I18n.s(com.iris.localai.R.string.models_e367b9, auto.label),
                    selected = o.backend == null, enabled = true,
                ) { set { it.copy(backend = null) } }
                options.forEach { opt ->
                    Divider()
                    // 2.2.14: an OpenCL hang on this device moves GGUF to CPU; retry clears it.
                    val hung = opt.backend == Backend.GPU && model.format == com.iris.localai.data.ModelFormat.GGUF &&
                        remember(crashTick) { engine.crashMemory.openClHung }
                    val crash = opt.backend in crashed || hung
                    BackendRow(
                        opt.backend.label,
                        when {
                            !opt.available -> opt.reason ?: com.iris.localai.I18n.s(com.iris.localai.R.string.chat_d1e4a7)
                            hung -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_7bc0c1)
                            crash -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_429d7e)
                            else -> opt.detail
                        },
                        selected = o.backend == opt.backend.name,
                        enabled = opt.available,
                        trailingAction = if (crash) ({ engine.crashMemory.forget(model.id); crashTick++ }) else null,
                    ) { set { it.copy(backend = opt.backend.name) } }
                }
            }
            loaded?.let { r ->
                GroupFootnote(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_7e17b1, r.details.backend) + (r.details.fallbackNote?.let { "\n$it" } ?: ""),
                )
            }
            // 1.10.3: 「建议：这个模型在 CPU 上加载更快（可选）」 when its last GPU load was > 60 s and a CPU load was faster.
            val loadRecords by engine.loadStats.records.collectAsStateWithLifecycle()
            val nextLabel = remember(model.id, configs, settings.defaultBackends) { runCatching { engine.preferredBackend(model).label }.getOrDefault("") }
            if (com.iris.localai.engine.CpuFasterHint.shouldShow(loadRecords.values, model.id, nextLabel)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        com.iris.localai.engine.CpuFasterHint.TEXT, style = IrisType.caption, color = c.accent,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IrisButton(
                        com.iris.localai.engine.CpuFasterHint.ACTION, { set { it.copy(backend = Backend.CPU.name) } },
                        style = IrisButtonStyle.Text, compact = true,
                    )
                }
            }
            GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.models_32578b))

            // ---- Behaviour ----
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_a04961))
            SettingsGroup {
                SegmentedRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_1d878d), listOf(com.iris.localai.I18n.s(com.iris.localai.R.string.models_112be9), com.iris.localai.I18n.s(com.iris.localai.R.string.components_849320), com.iris.localai.I18n.s(com.iris.localai.R.string.components_d58a55)),
                    when (o.thinking) { null -> 0; true -> 1; false -> 2 },
                    { i -> set { it.copy(thinking = when (i) { 1 -> true; 2 -> false; else -> null }) } },
                    subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.models_4ebee0),
                )
                Divider()
                SettingsRow(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_24c18d),
                    subtitle = o.systemPrompt?.takeIf { it.isNotBlank() } ?: com.iris.localai.I18n.s(com.iris.localai.R.string.models_df255e),
                    subtitleMaxLines = 2,
                    onClick = { editPrompt = true },
                )
            }

            // ---- Context & length ----
            SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_dfd0cd))
            SettingsGroup {
                val fixed = features.fixedContext
                if (fixed != null) {
                    SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b5663), subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.models_2c1185, fixed), enabled = false)
                } else {
                    OvSlider(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.models_3b5663), eff.contextLength.toFloat(), o.contextLength == null, 512f..32768f, "${eff.contextLength}",
                        onChange = { v -> set { it.copy(contextLength = ((v / 512).toInt() * 512).coerceAtLeast(512)) } },
                        onReset = { set { it.copy(contextLength = null) } },
                    )
                }
                Divider()
                OvSlider(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_422a72), com.iris.localai.data.TokenLimit.toSlider(eff.maxNewTokens, 8192, 128), o.maxNewTokens == null, 128f..8320f, com.iris.localai.data.TokenLimit.label(eff.maxNewTokens),
                    onChange = { v -> set { it.copy(maxNewTokens = com.iris.localai.data.TokenLimit.fromSlider(v, 8192, 128)) } },
                    onReset = { set { it.copy(maxNewTokens = null) } },
                )
            }

            // 2.0.x: what is rarely touched folds away; unsupported options are not drawn one by
            // one as grey rows any more but named once under their group.
            val unsupported = UnsupportedOptions.names(features, model.format)
            com.iris.localai.ui.components.SectionFold("model-adv-" + model.id, com.iris.localai.I18n.s(com.iris.localai.R.string.models_528fa3)) {
                // ---- Sampling ----
                SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_42ac9b))
                SettingsGroup {
                    OvSlider(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_c9bf0b), eff.temperature, o.temperature == null, 0f..2f, f2(eff.temperature),
                        onChange = { v -> set { it.copy(temperature = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(temperature = null) } })
                    Divider()
                    OvSlider("Top-P", eff.topP, o.topP == null, 0.05f..1f, f2(eff.topP),
                        onChange = { v -> set { it.copy(topP = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(topP = null) } })
                    Divider()
                    OvSlider("Top-K", eff.topK.toFloat(), o.topK == null, 0f..100f, if (eff.topK == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.ui_b15d91) else "${eff.topK}",
                        onChange = { v -> set { it.copy(topK = v.toInt()) } }, onReset = { set { it.copy(topK = null) } })
                    Divider()
                    if (features.minP) {
                        OvSlider("Min-P", eff.minP, o.minP == null, 0f..0.3f, f2(eff.minP),
                            onChange = { v -> set { it.copy(minP = (v * 100).toInt() / 100f) } }, onReset = { set { it.copy(minP = null) } })
                    }
                    Divider()
                    if (features.repeatPenalty) {
                        OvSlider(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_43a45e), eff.repeatPenalty, o.repeatPenalty == null, 1f..1.5f, f2(eff.repeatPenalty),
                            onChange = { v -> set { it.copy(repeatPenalty = (v * 100).toInt() / 100f) } }, onReset = { set { it.copy(repeatPenalty = null) } })
                    }
                    Divider()
                    if (model.format == com.iris.localai.data.ModelFormat.GGUF) {
                        OvSlider(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e4596e), eff.repeatLastN.toFloat(), o.repeatLastN == null, 0f..512f, "${eff.repeatLastN} tokens",
                            subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.models_5be881),
                            onChange = { v -> set { it.copy(repeatLastN = (v / 16).toInt() * 16) } }, onReset = { set { it.copy(repeatLastN = null) } })
                    }
                    Divider()
                    if (features.presenceFrequency) {
                        OvSlider(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_8f9adc), eff.presencePenalty, o.presencePenalty == null, -2f..2f, f2(eff.presencePenalty),
                            onChange = { v -> set { it.copy(presencePenalty = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(presencePenalty = null) } })
                        Divider()
                        OvSlider(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_4de5f1), eff.frequencyPenalty, o.frequencyPenalty == null, -2f..2f, f2(eff.frequencyPenalty),
                            onChange = { v -> set { it.copy(frequencyPenalty = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(frequencyPenalty = null) } })
                    }
                    Divider()
                    if (features.seed) {
                        SettingsRow(
                            com.iris.localai.I18n.s(com.iris.localai.R.string.models_b0db48), subtitle = if (o.seed == null) com.iris.localai.I18n.s(com.iris.localai.R.string.models_bf8c1c, seedText(eff.seed)) else seedText(eff.seed),
                            onClick = { editSeed = true },
                        )
                    }
                }

                // ---- Performance ----
                SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_c3318e))
                SettingsGroup {
                    val tierPlan = engine.threadPolicy.nominal(engine.tier)
                    OvSlider(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.models_aadb83), eff.threads.toFloat(), o.threads == null, 0f..12f,
                        if (eff.threads == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_eb0286, tierPlan.threads) else "${eff.threads}", steps = 11,
                        onChange = { v -> set { it.copy(threads = v.toInt()) } }, onReset = { set { it.copy(threads = null) } },
                    )
                    Divider()
                    if (features.batchSize) {
                        OvSlider(
                            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b65a0d), eff.batchSize.toFloat(), o.batchSize == null, 0f..2048f,
                            if (eff.batchSize == 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_eb0286, tierPlan.batch) else "${eff.batchSize}",
                            subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.models_92e1b7),
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
                            com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1747d7) + if (o.kvCacheType == null) com.iris.localai.I18n.s(com.iris.localai.R.string.models_c63342) else "",
                            listOf("F16", "Q8_0", "Q4_0"), eff.kvCacheType,
                            { i -> set { it.copy(kvCacheType = i) } },
                            subtitle = if (eff.flashAttention) com.iris.localai.I18n.s(com.iris.localai.R.string.models_f3d41d) else com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1cf9cd),
                        )
                    }
                    Divider()
                    if (features.mmap) {
                        OvSwitch(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_475473), eff.useMmap, o.useMmap == null, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.models_f1f0d5),
                            onChange = { on -> set { it.copy(useMmap = on) } }, onReset = { set { it.copy(useMmap = null) } })
                    }
                    if (model.format == com.iris.localai.data.ModelFormat.GGUF) {
                        Divider()
                        SettingsRow(
                            // 2.3.5 (#8): always offered for GGUF — 挂载视觉投影 (mmproj)
                            com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_attach),
                            subtitle = when (o.mmproj) {
                                null -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_a73152) + (mmprojAuto ?: com.iris.localai.I18n.s(com.iris.localai.R.string.models_a21729))
                                com.iris.localai.engine.llama.MmprojPairing.OFF -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_8bfbde)
                                else -> o.mmproj
                            } + com.iris.localai.I18n.s(com.iris.localai.R.string.models_875dd5),
                            onClick = { pickMmproj = true },
                        )
                    }
                    if (hasMtp) {
                        Divider()
                        OvSwitch(com.iris.localai.I18n.s(com.iris.localai.R.string.v259_mtp), eff.mtp, o.mtp == null, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.v259_mtp_sub),
                            onChange = { on -> set { it.copy(mtp = on) } }, onReset = { set { it.copy(mtp = null) } })
                    }
                    Divider()
                    OvSwitch(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_810ed9), eff.warmup, o.warmup == null, subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_004e27),
                        onChange = { on -> set { it.copy(warmup = on) } }, onReset = { set { it.copy(warmup = null) } })
                }

                if (unsupported.isNotEmpty()) GroupFootnote(com.iris.localai.I18n.s(com.iris.localai.R.string.models_773920) + unsupported.joinToString(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b50566)))
                // ---- 1.13.1: this model's on-disk caches ----
                val ctx = LocalContext.current
                var cacheTick by remember { mutableIntStateOf(0) }
                var cacheBytes by remember { mutableStateOf<Long?>(null) }
                androidx.compose.runtime.LaunchedEffect(model.fileName, cacheTick) {
                    cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.iris.localai.engine.ModelCache.bytes(com.iris.localai.engine.ModelCache.files(ctx, model.fileName))
                    }
                }
                var cacheNote by remember { mutableStateOf<String?>(null) }
                SectionTitle(com.iris.localai.I18n.s(com.iris.localai.R.string.models_e80c31))
                SettingsGroup {
                    val b = cacheBytes
                    SettingsRow(
                        com.iris.localai.I18n.s(com.iris.localai.R.string.models_8ded69),
                        subtitle = cacheNote ?: when {
                            b == null -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_dc020b)
                            b == 0L -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_373e22) + if (model.format == com.iris.localai.data.ModelFormat.GGUF) com.iris.localai.I18n.s(com.iris.localai.R.string.models_67478c) else ""
                            else -> com.iris.localai.I18n.s(com.iris.localai.R.string.models_7c5be1, cacheSize(b)) + if (loaded != null) com.iris.localai.I18n.s(com.iris.localai.R.string.models_1bb7db) else ""
                        },
                        enabled = (b ?: 0L) > 0L,
                        onClick = {
                            scope.launch {
                                val (n, freed) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.engine.ModelCache.clear(ctx, model.fileName) }
                                cacheNote = com.iris.localai.I18n.s(com.iris.localai.R.string.models_cfd753, n, cacheSize(freed))
                                cacheTick++
                            }
                        },
                    )
                }

            }

            if (loaded != null) {
                Spacer(Modifier.height(IrisDimens.space12))
                IrisButton(
                    com.iris.localai.I18n.s(com.iris.localai.R.string.models_466ae2), { scope.launch { engine.reload() } }, icon = com.iris.localai.ui.icons.IrisIcons.Reload,
                    style = IrisButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
                )
            }
            Spacer(Modifier.height(IrisDimens.space12))
        }
    }

    if (editPrompt) {
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.models_24c18d),
            initial = o.systemPrompt.orEmpty(),
            onConfirm = { t -> set { it.copy(systemPrompt = t.trim().ifEmpty { null }) } },
            onDismiss = { editPrompt = false },
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb),
            placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.models_cfe653),
            singleLine = false,
            allowEmpty = true,
        )
    }
    if (pickMmproj) {
        IrisDialog(onDismiss = { pickMmproj = false }, title = com.iris.localai.I18n.s(com.iris.localai.R.string.models_f08420), confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_769d88), onConfirm = { pickMmproj = false }, dismissText = null) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CheckRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_fd346f), o.mmproj == null, subtitle = mmprojAuto ?: com.iris.localai.I18n.s(com.iris.localai.R.string.models_d0a02b)) { set { it.copy(mmproj = null) } }
                CheckRow(com.iris.localai.I18n.s(com.iris.localai.R.string.models_57ed11), o.mmproj == com.iris.localai.engine.llama.MmprojPairing.OFF) { set { it.copy(mmproj = com.iris.localai.engine.llama.MmprojPairing.OFF) } }
                mmprojChoices.forEach { f -> CheckRow(f, o.mmproj == f) { set { it.copy(mmproj = f) } } }
                // 2.3.5 (#8): pick / replace from a file; 移除 = never load one
                if (mmprojBusy) SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.mm245_copying), trailing = { com.iris.localai.ui.components.TypingDots() })
                else CheckRow(com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_pick), false) { mmprojFilePicker.launch(arrayOf("*/*")) }
                mmprojError?.let { err -> Text(err, style = com.iris.localai.ui.theme.IrisType.caption, color = c.danger, modifier = Modifier.padding(horizontal = com.iris.localai.ui.theme.IrisSpace.L, vertical = com.iris.localai.ui.theme.IrisSpace.XS)) }
                if (o.mmproj != com.iris.localai.engine.llama.MmprojPairing.OFF && (o.mmproj != null || mmprojAuto != null))
                    CheckRow(com.iris.localai.I18n.s(com.iris.localai.R.string.mm235_remove), false) { set { it.copy(mmproj = com.iris.localai.engine.llama.MmprojPairing.OFF) } }
            }
        }
    }
    if (editSeed) {
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.models_b0db48),
            initial = if (eff.seed < 0) "" else eff.seed.toString(),
            onConfirm = { t -> set { it.copy(seed = t.trim().toIntOrNull()?.coerceAtLeast(0) ?: -1) } },
            onDismiss = { editSeed = false },
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.settings_be5fbb),
            placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.models_3e1d97),
            allowEmpty = true,
        )
    }
}

private fun seedText(seed: Int) = if (seed < 0) com.iris.localai.I18n.s(com.iris.localai.R.string.settings_2beef4) else com.iris.localai.I18n.s(com.iris.localai.R.string.models_23cc34, seed)

@Composable
private fun BackendRow(
    title: String, subtitle: String, selected: Boolean, enabled: Boolean,
    trailingAction: (() -> Unit)? = null, onClick: () -> Unit,
) {
    SettingsRow(title, subtitle = subtitle, enabled = enabled, role = Role.RadioButton, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trailingAction != null) {
                Text(com.iris.localai.I18n.s(com.iris.localai.R.string.chat_4403fc), style = IrisType.labelSmall, color = Iris.colors.accent, modifier = Modifier.clickable(onClick = trailingAction).padding(IrisDimens.space8))
            }
            Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Outlined.Check, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f08afd), tint = Iris.colors.text, modifier = Modifier.size(IrisDimens.iconMedium))
            }
        }
    }
}

/** 2.0.x: options this engine does not have, named once instead of one grey row each (unit-tested). */
object UnsupportedOptions {
    fun names(f: com.iris.localai.engine.EngineFeatures, format: com.iris.localai.data.ModelFormat): List<String> = buildList {
        if (!f.minP) add("Min-P")
        if (!f.repeatPenalty) add(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_43a45e))
        if (format != com.iris.localai.data.ModelFormat.GGUF) add(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_e4596e))
        if (!f.presenceFrequency) add(com.iris.localai.I18n.s(com.iris.localai.R.string.models_68cc4a))
        if (!f.seed) add(com.iris.localai.I18n.s(com.iris.localai.R.string.models_b0db48))
        if (!f.batchSize) add(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_b65a0d))
        if (!f.flashAttention) add("Flash Attention")
        if (!f.kvQuant) add(com.iris.localai.I18n.s(com.iris.localai.R.string.settings_1747d7))
        if (!f.mmap) add("mmap")
    }
}

@Composable
private fun OvSlider(
    title: String, value: Float, inherited: Boolean, range: ClosedFloatingPointRange<Float>, display: String,
    subtitle: String? = null, steps: Int = 0, onChange: (Float) -> Unit, onReset: () -> Unit,
) {
    SliderRow(title, value, range, if (inherited) com.iris.localai.I18n.s(com.iris.localai.R.string.models_82a38b, display) else display, subtitle = subtitle, steps = steps, onChange = onChange)
    if (!inherited) ResetLink(onReset)
}

@Composable
private fun OvSwitch(title: String, checked: Boolean, inherited: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit, onReset: () -> Unit) {
    SwitchRow(title + if (inherited) com.iris.localai.I18n.s(com.iris.localai.R.string.models_c63342) else "", checked, subtitle = subtitle, onChange = onChange)
    if (!inherited) ResetLink(onReset)
}

@Composable
private fun ResetLink(onReset: () -> Unit) {
    Text(
        com.iris.localai.I18n.s(com.iris.localai.R.string.models_24eeac), style = IrisType.labelSmall, color = Iris.colors.accent,
        modifier = Modifier.padding(start = IrisDimens.rowPadding, bottom = IrisDimens.space8).clickable(onClick = onReset).padding(vertical = IrisDimens.space4),
    )
}

/** 1.13.1: bytes as KB / MB / GB for the cache row. */
internal fun cacheSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> "${(b + 1023) / 1024} KB"
}

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
    val c = Iris.colors

    IrisSheet(onDismiss = onDismiss) {
        SheetHeader(model.displayName) {
            IrisButton("恢复默认", { repo.reset(model.id) }, style = IrisButtonStyle.Text, compact = true, enabled = !o.isEmpty)
        }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
            Text(
                "未单独设置的项跟随全局（标「默认」）",
                style = IrisType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space4),
            )

            // 2.1.3: no 「推理引擎」 switch any more — for GGUF the backend decides (NPU = GenieX, GPU / CPU = 内置 llama.cpp).

            // ---- Backend ----
            SectionTitle("后端")
            SettingsGroup {
                BackendRow(
                    "自动", "会用 ${auto.label}",
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
                            !opt.available -> opt.reason ?: "不可用"
                            hung -> "上次 GPU 加载卡住，已默认用 CPU · 点右侧重试"
                            crash -> "上次在此后端崩溃，自动加载时会跳过 · 点右侧清除"
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
                    "正在用：${r.details.backend}" + (r.details.fallbackNote?.let { "\n$it" } ?: ""),
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
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IrisButton(
                        com.iris.localai.engine.CpuFasterHint.ACTION, { set { it.copy(backend = Backend.CPU.name) } },
                        style = IrisButtonStyle.Text, compact = true,
                    )
                }
            }
            GroupFootnote("加载失败会自动换 GPU、CPU 再试。改动重新加载后生效。")

            // ---- Behaviour ----
            SectionTitle("行为")
            SettingsGroup {
                SegmentedRow(
                    "深度思考", listOf("跟随全局", "开", "关"),
                    when (o.thinking) { null -> 0; true -> 1; false -> 2 },
                    { i -> set { it.copy(thinking = when (i) { 1 -> true; 2 -> false; else -> null }) } },
                    subtitle = "单独设置后，输入框「思考」按钮改的是这个模型的值",
                )
                Divider()
                SettingsRow(
                    "模型专属系统提示",
                    subtitle = o.systemPrompt?.takeIf { it.isNotBlank() } ?: "未设置（使用全局自定义指令）",
                    subtitleMaxLines = 2,
                    onClick = { editPrompt = true },
                )
            }

            // ---- Context & length ----
            SectionTitle("长度")
            SettingsGroup {
                val fixed = features.fixedContext
                if (fixed != null) {
                    SettingsRow("上下文长度", subtitle = "NPU 模型编译时固定为 $fixed tokens", enabled = false)
                } else {
                    OvSlider(
                        "上下文长度", eff.contextLength.toFloat(), o.contextLength == null, 512f..32768f, "${eff.contextLength}",
                        onChange = { v -> set { it.copy(contextLength = ((v / 512).toInt() * 512).coerceAtLeast(512)) } },
                        onReset = { set { it.copy(contextLength = null) } },
                    )
                }
                Divider()
                OvSlider(
                    "最大生成长度", com.iris.localai.data.TokenLimit.toSlider(eff.maxNewTokens, 8192, 128), o.maxNewTokens == null, 128f..8320f, com.iris.localai.data.TokenLimit.label(eff.maxNewTokens),
                    onChange = { v -> set { it.copy(maxNewTokens = com.iris.localai.data.TokenLimit.fromSlider(v, 8192, 128)) } },
                    onReset = { set { it.copy(maxNewTokens = null) } },
                )
            }

            // 2.0.x: what is rarely touched folds away; unsupported options are not drawn one by
            // one as grey rows any more but named once under their group.
            val unsupported = UnsupportedOptions.names(features, model.format)
            com.iris.localai.ui.components.SectionFold("model-adv-" + model.id, "采样、性能与缓存") {
                // ---- Sampling ----
                SectionTitle("采样")
                SettingsGroup {
                    OvSlider("温度", eff.temperature, o.temperature == null, 0f..2f, f2(eff.temperature),
                        onChange = { v -> set { it.copy(temperature = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(temperature = null) } })
                    Divider()
                    OvSlider("Top-P", eff.topP, o.topP == null, 0.05f..1f, f2(eff.topP),
                        onChange = { v -> set { it.copy(topP = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(topP = null) } })
                    Divider()
                    OvSlider("Top-K", eff.topK.toFloat(), o.topK == null, 0f..100f, if (eff.topK == 0) "关闭" else "${eff.topK}",
                        onChange = { v -> set { it.copy(topK = v.toInt()) } }, onReset = { set { it.copy(topK = null) } })
                    Divider()
                    if (features.minP) {
                        OvSlider("Min-P", eff.minP, o.minP == null, 0f..0.3f, f2(eff.minP),
                            onChange = { v -> set { it.copy(minP = (v * 100).toInt() / 100f) } }, onReset = { set { it.copy(minP = null) } })
                    }
                    Divider()
                    if (features.repeatPenalty) {
                        OvSlider("重复惩罚", eff.repeatPenalty, o.repeatPenalty == null, 1f..1.5f, f2(eff.repeatPenalty),
                            onChange = { v -> set { it.copy(repeatPenalty = (v * 100).toInt() / 100f) } }, onReset = { set { it.copy(repeatPenalty = null) } })
                    }
                    Divider()
                    if (model.format == com.iris.localai.data.ModelFormat.GGUF) {
                        OvSlider("惩罚窗口", eff.repeatLastN.toFloat(), o.repeatLastN == null, 0f..512f, "${eff.repeatLastN} tokens",
                            subtitle = "重复 / presence / frequency 惩罚统计最近多少个 token",
                            onChange = { v -> set { it.copy(repeatLastN = (v / 16).toInt() * 16) } }, onReset = { set { it.copy(repeatLastN = null) } })
                    }
                    Divider()
                    if (features.presenceFrequency) {
                        OvSlider("Presence 惩罚", eff.presencePenalty, o.presencePenalty == null, -2f..2f, f2(eff.presencePenalty),
                            onChange = { v -> set { it.copy(presencePenalty = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(presencePenalty = null) } })
                        Divider()
                        OvSlider("Frequency 惩罚", eff.frequencyPenalty, o.frequencyPenalty == null, -2f..2f, f2(eff.frequencyPenalty),
                            onChange = { v -> set { it.copy(frequencyPenalty = (v * 20).toInt() / 20f) } }, onReset = { set { it.copy(frequencyPenalty = null) } })
                    }
                    Divider()
                    if (features.seed) {
                        SettingsRow(
                            "随机种子", subtitle = if (o.seed == null) "默认（${seedText(eff.seed)}）" else seedText(eff.seed),
                            onClick = { editSeed = true },
                        )
                    }
                }

                // ---- Performance ----
                SectionTitle("性能")
                SettingsGroup {
                    val tierPlan = engine.threadPolicy.nominal(engine.tier)
                    OvSlider(
                        "线程数", eff.threads.toFloat(), o.threads == null, 0f..12f,
                        if (eff.threads == 0) "跟随资源档（${tierPlan.threads}）" else "${eff.threads}", steps = 11,
                        onChange = { v -> set { it.copy(threads = v.toInt()) } }, onReset = { set { it.copy(threads = null) } },
                    )
                    Divider()
                    if (features.batchSize) {
                        OvSlider(
                            "批大小", eff.batchSize.toFloat(), o.batchSize == null, 0f..2048f,
                            if (eff.batchSize == 0) "跟随资源档（${tierPlan.batch}）" else "${eff.batchSize}",
                            subtitle = "预填充一次处理的 token 数；越大长提示越快，也越占内存",
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
                            "KV 缓存量化" + if (o.kvCacheType == null) " · 默认" else "",
                            listOf("F16", "Q8_0", "Q4_0"), eff.kvCacheType,
                            { i -> set { it.copy(kvCacheType = i) } },
                            subtitle = if (eff.flashAttention) "Q8_0 内存减半、质量几乎不变；Q4_0 更省但可能变差" else "需开启 Flash Attention",
                        )
                    }
                    Divider()
                    if (features.mmap) {
                        OvSwitch("mmap 加载", eff.useMmap, o.useMmap == null, subtitle = "按需分页读取权重，加载快、占用少",
                            onChange = { on -> set { it.copy(useMmap = on) } }, onReset = { set { it.copy(useMmap = null) } })
                    }
                    Divider()
                    OvSwitch("加载后预热", eff.warmup, o.warmup == null, subtitle = "加载完先跑 1 个 token，首条回复更快",
                        onChange = { on -> set { it.copy(warmup = on) } }, onReset = { set { it.copy(warmup = null) } })
                }

                if (unsupported.isNotEmpty()) GroupFootnote("当前引擎不支持：" + unsupported.joinToString("、"))
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
                SectionTitle("缓存")
                SettingsGroup {
                    val b = cacheBytes
                    SettingsRow(
                        "清除此模型的缓存",
                        subtitle = cacheNote ?: when {
                            b == null -> "计算中…"
                            b == 0L -> "没有缓存" + if (model.format == com.iris.localai.data.ModelFormat.GGUF) "（GGUF 不生成缓存）" else ""
                            else -> "已用 ${cacheSize(b)} · GPU 编译结果 / 权重缓存；清除后下次加载会重新编译" + if (loaded != null) "（当前已加载，不影响本次运行）" else ""
                        },
                        enabled = (b ?: 0L) > 0L,
                        onClick = {
                            scope.launch {
                                val (n, freed) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.iris.localai.engine.ModelCache.clear(ctx, model.fileName) }
                                cacheNote = "已清除 $n 个文件，释放 ${cacheSize(freed)}"
                                cacheTick++
                            }
                        },
                    )
                }

            }

            if (loaded != null) {
                Spacer(Modifier.height(IrisDimens.space12))
                IrisButton(
                    "重新加载以应用", { scope.launch { engine.reload() } }, icon = com.iris.localai.ui.icons.IrisIcons.Reload,
                    style = IrisButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
                )
            }
            Spacer(Modifier.height(IrisDimens.space12))
        }
    }

    if (editPrompt) {
        TextInputDialog(
            title = "模型专属系统提示",
            initial = o.systemPrompt.orEmpty(),
            onConfirm = { t -> set { it.copy(systemPrompt = t.trim().ifEmpty { null }) } },
            onDismiss = { editPrompt = false },
            confirmText = "保存",
            placeholder = "留空 = 使用全局自定义指令",
            singleLine = false,
            allowEmpty = true,
        )
    }
    if (editSeed) {
        TextInputDialog(
            title = "随机种子",
            initial = if (eff.seed < 0) "" else eff.seed.toString(),
            onConfirm = { t -> set { it.copy(seed = t.trim().toIntOrNull()?.coerceAtLeast(0) ?: -1) } },
            onDismiss = { editSeed = false },
            confirmText = "保存",
            placeholder = "留空 = 每次随机；填数字 = 可复现",
            allowEmpty = true,
        )
    }
}

private fun seedText(seed: Int) = if (seed < 0) "每次随机" else "固定 $seed"

@Composable
private fun BackendRow(
    title: String, subtitle: String, selected: Boolean, enabled: Boolean,
    trailingAction: (() -> Unit)? = null, onClick: () -> Unit,
) {
    SettingsRow(title, subtitle = subtitle, enabled = enabled, role = Role.RadioButton, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (trailingAction != null) {
                Text("清除", style = IrisType.labelSmall, color = Iris.colors.accent, modifier = Modifier.clickable(onClick = trailingAction).padding(IrisDimens.space8))
            }
            Box(Modifier.size(IrisDimens.iconLarge), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Outlined.Check, "已选择", tint = Iris.colors.text, modifier = Modifier.size(IrisDimens.iconMedium))
            }
        }
    }
}

/** 2.0.x: options this engine does not have, named once instead of one grey row each (unit-tested). */
object UnsupportedOptions {
    fun names(f: com.iris.localai.engine.EngineFeatures, format: com.iris.localai.data.ModelFormat): List<String> = buildList {
        if (!f.minP) add("Min-P")
        if (!f.repeatPenalty) add("重复惩罚")
        if (format != com.iris.localai.data.ModelFormat.GGUF) add("惩罚窗口")
        if (!f.presenceFrequency) add("Presence / Frequency 惩罚")
        if (!f.seed) add("随机种子")
        if (!f.batchSize) add("批大小")
        if (!f.flashAttention) add("Flash Attention")
        if (!f.kvQuant) add("KV 缓存量化")
        if (!f.mmap) add("mmap")
    }
}

@Composable
private fun OvSlider(
    title: String, value: Float, inherited: Boolean, range: ClosedFloatingPointRange<Float>, display: String,
    subtitle: String? = null, steps: Int = 0, onChange: (Float) -> Unit, onReset: () -> Unit,
) {
    SliderRow(title, value, range, if (inherited) "$display · 默认" else display, subtitle = subtitle, steps = steps, onChange = onChange)
    if (!inherited) ResetLink(onReset)
}

@Composable
private fun OvSwitch(title: String, checked: Boolean, inherited: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit, onReset: () -> Unit) {
    SwitchRow(title + if (inherited) " · 默认" else "", checked, subtitle = subtitle, onChange = onChange)
    if (!inherited) ResetLink(onReset)
}

@Composable
private fun ResetLink(onReset: () -> Unit) {
    Text(
        "改回默认", style = IrisType.labelSmall, color = Iris.colors.accent,
        modifier = Modifier.padding(start = IrisDimens.rowPadding, bottom = IrisDimens.space8).clickable(onClick = onReset).padding(vertical = IrisDimens.space4),
    )
}

/** 1.13.1: bytes as KB / MB / GB for the cache row. */
internal fun cacheSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> "${(b + 1023) / 1024} KB"
}

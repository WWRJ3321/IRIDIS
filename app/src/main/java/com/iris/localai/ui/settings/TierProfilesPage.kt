package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.TierProfile
import com.iris.localai.engine.LiveResourcePolicy
import com.iris.localai.engine.ResourceBackendKind
import com.iris.localai.engine.ResourceTier
import com.iris.localai.engine.TierProfiles
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.PageDescription
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SegmentedRow
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.ToggleChip
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType

/**
 * 1.7.8: 设置 › 性能与资源 › 资源档配置. Per backend kind (CPU / GPU·NPU) and per tier: threads,
 * cores (by cpu id or a whole cluster), nice. Invalid drafts cannot be saved
 * ([TierProfiles.validate]); saving the tier in use is applied by EngineManager (live where it can,
 * otherwise the debounced, ReloadGate-guarded reload).
 */
@Composable
fun TierProfilesPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var kindIndex by rememberSaveable { mutableStateOf(vm.activeKind.ordinal) }
    val kind = ResourceBackendKind.entries[kindIndex]
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    SubpageScaffold(
        "资源档配置", onBack,
        actions = { IrisButton("恢复默认", { confirmReset = true }, style = IrisButtonStyle.Text, compact = true) },
    ) {
        ScrollBody {
            PageDescription("每个档位用几个线程、跑在哪些核心上、优先级多高。CPU 后端和 GPU / NPU 后端各有一套；默认值按本机的核心簇生成。")
            SettingsGroup {
                SegmentedRow(
                    "后端", listOf("CPU", "GPU / NPU"), kindIndex, { kindIndex = it },
                    icon = Icons.Outlined.DeveloperBoard,
                    subtitle = if (kind == ResourceBackendKind.CPU) "CPU 后端：档位决定推理线程数、核心与优先级"
                    else LiveResourcePolicy.GPU_SCOPE_NOTE,
                )
            }
            val current = ResourceTier.of(s.resourceTier)
            ResourceTier.entries.forEach { tier ->
                val stored = TierProfiles.resolve(s.tierProfiles, kind, tier, vm.topology)
                val custom = stored != vm.defaultProfile(kind, tier)
                SectionTitle(tier.label + (if (tier == current) "（当前）" else "") + (if (custom) " · 已修改" else ""))
                TierProfileEditor(vm, kind, tier, stored, custom)
            }
            GroupFootnote(
                "线程数要在 1 到所选核心数之间，nice 在 ${TierProfiles.NICE_MIN} 到 ${TierProfiles.NICE_MAX} 之间（越小优先级越高），至少选 1 个核心。" +
                    "修改正在使用的档位，空闲时自动应用（必要时重新加载模型）。",
            )
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "恢复默认？",
            message = "${kind.label}的三个档位都恢复为本机默认值。",
            confirmText = "恢复",
            onConfirm = { vm.resetProfiles(kind) },
            onDismiss = { confirmReset = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierProfileEditor(vm: SettingsViewModel, kind: ResourceBackendKind, tier: ResourceTier, stored: TierProfile, custom: Boolean) {
    val c = Iris.colors
    // The draft restarts whenever the saved value (or the page's backend kind) changes.
    var threads by remember(kind, tier, stored) { mutableStateOf(stored.threads) }
    var cpus by remember(kind, tier, stored) { mutableStateOf(stored.cpus.toSet()) }
    var nice by remember(kind, tier, stored) { mutableStateOf(stored.nice) }
    // 1.7.9: GPU 性能提示 (GPU set only; null on the CPU set).
    var gpuHint by remember(kind, tier, stored) { mutableStateOf(stored.gpuHint) }
    val draft = TierProfile(threads, cpus.sorted(), nice, gpuHint)
    val problems = vm.validate(draft)
    val dirty = draft != stored
    val all = TierProfiles.allCores(vm.topology)

    SettingsGroup {
        SettingsRow("线程数", subtitle = "1 到所选核心数（${cpus.size}）", trailing = {
            Stepper(threads, onMinus = { threads-- }, onPlus = { threads++ }, minusEnabled = threads > 1, plusEnabled = threads < maxOf(1, cpus.size))
        })
        Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
            Text("核心", style = IrisType.row, color = c.text)
            Text("按核心簇快速选择，或逐个勾选 cpu 编号：${TierProfiles.coreLabel(cpus.sorted(), vm.topology)} · ${TierProfiles.cpuRange(cpus.sorted())}", style = IrisType.caption, color = c.textTertiary)
            Spacer(Modifier.width(0.dp).padding(top = IrisDimens.space8))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8), modifier = Modifier.padding(top = IrisDimens.space8)) {
                TierProfiles.clusterChoices(vm.topology).forEach { (name, cl) ->
                    ToggleChip("$name ${TierProfiles.cpuRange(cl)}", Icons.Outlined.DeveloperBoard, selected = cpus.containsAll(cl) && cpus.size == cl.size) {
                        cpus = cl.toSet()
                    }
                }
                ToggleChip("全部核心", Icons.Outlined.DeveloperBoard, selected = cpus.size == all.size) { cpus = all.toSet() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8), verticalArrangement = Arrangement.spacedBy(IrisDimens.space8), modifier = Modifier.padding(top = IrisDimens.space8)) {
                all.forEach { id ->
                    ToggleChip("cpu$id", Icons.Outlined.Memory, selected = id in cpus) {
                        cpus = if (id in cpus) cpus - id else cpus + id
                    }
                }
            }
        }
        Divider()
        SettingsRow("优先级（nice）", subtitle = "${TierProfiles.NICE_MIN} 到 ${TierProfiles.NICE_MAX}；越小越优先，太小会和界面抢 CPU", trailing = {
            Stepper(nice, onMinus = { nice-- }, onPlus = { nice++ }, minusEnabled = nice > TierProfiles.NICE_MIN, plusEnabled = nice < TierProfiles.NICE_MAX)
        })
        if (kind == ResourceBackendKind.GPU) {
            Divider()
            val hints = com.iris.localai.data.GpuPerfHint.entries
            val sel = gpuHint ?: TierProfiles.defaultGpuHint(tier)
            SegmentedRow(
                "GPU 性能提示", hints.map { it.label }, hints.indexOf(sel), { gpuHint = hints[it] },
                icon = Icons.Outlined.DeveloperBoard,
                subtitle = "Adreno 的 OpenCL 性能提示（默认 省电档→省电、中→普通、高→高）。MNN 走 GPU 时加载模型时生效；LiteRT 没有这个选项，不起作用",
            )
        }
        if (problems.isNotEmpty()) {
            Text(
                problems.joinToString("；"), style = IrisType.caption, color = c.danger,
                modifier = Modifier.padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, bottom = IrisDimens.space8),
            )
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8),
            horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (custom || dirty) IrisButton("恢复默认", {
                val d = vm.defaultProfile(kind, tier)
                threads = d.threads; cpus = d.cpus.toSet(); nice = d.nice; gpuHint = d.gpuHint
                vm.resetProfile(kind, tier)
            }, style = IrisButtonStyle.Text, compact = true)
            IrisButton("保存", { vm.saveProfile(kind, tier, draft) }, style = IrisButtonStyle.Tonal, compact = true, enabled = dirty && problems.isEmpty())
        }
    }
}

@Composable
private fun Stepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean, plusEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IrisButton("−", onMinus, style = IrisButtonStyle.Outline, compact = true, enabled = minusEnabled)
        Text("$value", style = IrisType.label, color = Iris.colors.text, modifier = Modifier.padding(horizontal = 10.dp))
        IrisButton("+", onPlus, style = IrisButtonStyle.Outline, compact = true, enabled = plusEnabled)
    }
}

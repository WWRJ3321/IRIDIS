package com.iris.localai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.iris.localai.data.LocalModel
import com.iris.localai.engine.EngineState
import com.iris.localai.ui.components.EmptyState
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import androidx.compose.foundation.layout.Arrangement
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.IrisSheet
import com.iris.localai.ui.components.SheetHeader
import com.iris.localai.ui.models.ModelListItem
import com.iris.localai.ui.models.pickerMeta
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.motion.IrisMotion
import com.iris.localai.ui.theme.IrisType

@Composable
fun ModelPickerSheet(
    models: List<LocalModel>,
    engine: EngineState,
    busy: Boolean,
    onPick: (LocalModel) -> Unit,
    onUnload: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    resourceTier: Int = com.iris.localai.engine.ResourceTier.DEFAULT.ordinal,
    onResourceTier: (Int) -> Unit = {},
    /** Backend a load would start with, per model ("CPU" / "GPU" / "NPU"). */
    preferredBackend: (LocalModel) -> String = { "" },
    onConfigure: (LocalModel) -> Unit = {},
    /** 1.10.2: 「上次加载 N 秒 · 已用缓存/首次编译」 per model (null = never loaded since 1.10.2). */
    lastLoad: (LocalModel) -> String? = { null },
    /** 1.10.3: show 「建议：这个模型在 CPU 上加载更快（可选）」 + 改用 CPU ([com.iris.localai.engine.CpuFasterHint]). */
    cpuFaster: (LocalModel) -> Boolean = { false },
    onSwitchToCpu: (LocalModel) -> Unit = {},
    /** 2.2.0: 「GenieX · NPU」 for models GenieX runs (null = the format label + backend). */
    engineTag: (LocalModel) -> String? = { null },
    /** 2.1.7 弹窗可取消: 「取消」 on the loading block (abandons the load; a tier reload is never cancelled). */
    onCancelLoad: () -> Unit = {},
) {
    val c = Iris.colors
    IrisSheet(onDismiss = onDismiss) {
        SheetHeader("选择模型") {
            IrisButton("管理", onManage, style = IrisButtonStyle.Text, icon = Icons.Outlined.Tune, compact = true)
        }

        val loading = engine as? EngineState.Loading
        AnimatedVisibility(
            visible = loading != null,
            enter = fadeIn(IrisMotion.enter()),
            exit = fadeOut(IrisMotion.standard()),
        ) {
            val l = loading ?: return@AnimatedVisibility
            Column(Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space8)) {
                val secs = loadingSeconds(l)
                Text("正在加载 ${l.model.displayName} · $secs 秒", style = IrisType.labelSmall, color = c.textSecondary)
                // 1.10.2: what the previous load of this model really took (telemetry), instead of
                // always promising that only the first load is slow.
                val previous = lastLoad(l.model)
                if (previous != null) {
                    Spacer(Modifier.height(IrisDimens.space2))
                    Text(previous, style = IrisType.caption, color = c.textTertiary)
                } else if (secs >= 8) {
                    Spacer(Modifier.height(IrisDimens.space2))
                    Text("首次加载会把模型优化并缓存到本机，只需一次；之后通常几秒就好。", style = IrisType.caption, color = c.textTertiary)
                }
                Spacer(Modifier.height(IrisDimens.space8))
                IrisProgressBar(progress = l.progress.takeIf { it > 0.01f && it <= 1f })
                if (l.applyingTier == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IrisButton("取消", onCancelLoad, style = IrisButtonStyle.Text, compact = true)
                    }
                }
            }
        }
        com.iris.localai.ui.components.SegmentedRow(
            // 1.10.1: 低速 / 高速 / 极速 = 省电 / 中 / 高 (display only; same setting and hints).
            "资源档位", SpeedLevel.labels, resourceTier, onResourceTier,
            subtitle = com.iris.localai.engine.ResourceTier.of(resourceTier).hint,
        )
        if (engine is EngineState.Failed) {
            Text(
                engine.message, style = IrisType.caption, color = c.danger,
                modifier = Modifier.padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4, vertical = IrisDimens.space6),
            )
        }
        if (models.isEmpty()) {
            EmptyState(
                title = "还没有模型",
                message = "在「管理」中导入模型文件（${com.iris.localai.engine.EngineFactory.supportedFormats.flatMap { it.extensions }.joinToString(" / ") { ".$it" }}）" +
                    if (com.iris.localai.data.ModelFormat.MNN in com.iris.localai.engine.EngineFactory.supportedFormats) "，或下载 / 导入 MNN 模型文件夹" else "",
                icon = com.iris.localai.ui.icons.IrisIcons.Folder,
                action = { IrisButton("导入模型", onManage, compact = true) },
            )
        } else {
            val loadedPath = (engine as? EngineState.Ready)?.model?.path
            val loadingPath = loading?.model?.path
            val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
            Column(Modifier.heightIn(max = maxListHeight).verticalScroll(rememberScrollState())) {
                models.forEach { m ->
                    ModelListItem(
                        model = m,
                        loaded = m.path == loadedPath,
                        loading = m.path == loadingPath,
                        enabled = !busy && loading == null,
                        onClick = { onPick(m) },
                        meta = m.pickerMeta((engine as? EngineState.Ready)?.takeIf { it.model.path == m.path }?.details?.backend, preferredBackend(m), engineTag(m)),
                        onSettings = { onConfigure(m) },
                        extra = lastLoad(m),
                    )
                    if (cpuFaster(m)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter + IrisDimens.space4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                com.iris.localai.engine.CpuFasterHint.TEXT, style = IrisType.caption, color = c.accent,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            IrisButton(
                                com.iris.localai.engine.CpuFasterHint.ACTION, { onSwitchToCpu(m) },
                                style = IrisButtonStyle.Text, compact = true, enabled = !busy,
                            )
                        }
                    }
                }
            }
            if (loadedPath != null) {
                Spacer(Modifier.height(IrisDimens.space8))
                IrisButton(
                    "卸载模型并释放内存", onUnload, enabled = !busy, icon = Icons.Outlined.Eject,
                    style = IrisButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter),
                )
            }
        }
    }
}

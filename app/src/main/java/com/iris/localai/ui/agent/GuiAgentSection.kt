package com.iris.localai.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.gui.android.IrisAccessibilityService
import com.iris.localai.ui.components.AdvancedFold
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.TextInputDialog
import com.iris.localai.ui.icons.IrisIcons
import kotlinx.coroutines.launch

internal object GuiAgentText {
    const val TITLE = "GUI 智能体（实验）"
    const val ON = "已开启 · 可以查看屏幕并代你点击、滑动、输入"
    const val CONNECTING = "已在系统设置里开启，正在连接…"
    const val OFF = "未开启 · 点按去 系统设置 › 无障碍 › 已下载的应用 › IRIDIS GUI 智能体 开启"
    const val RESTRICTED = "系统提示「受限设置」时：点按这里打开应用信息，右上角 ⋮ › 允许受限制的设置，再回去开启"
    const val NOTE = "看屏幕 → 思考 → 操作。需要能识图的本地模型（如 Qwen3-VL、MAI-UI、AutoGLM-Phone）。" +
        "运行时顶部有悬浮条可随时停止；支付、发送、删除等操作先问你，密码和支付类应用由你自己操作；最多 30 步。"
}

/** 2.2 设置 › 智能体 › GUI 智能体: 无障碍 status and guide, 自检, and a test run (第一阶段入口). */
@Composable
fun GuiAgentSection() {
    val context = LocalContext.current
    val runner = remember { (context.applicationContext as IrisApp).container.gui }
    val run by runner.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(IrisAccessibilityService.enabledInSettings(context)) }
    var connected by remember { mutableStateOf(IrisAccessibilityService.connected) }
    var check by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = IrisAccessibilityService.enabledInSettings(context)
                connected = IrisAccessibilityService.connected
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    SectionTitle(GuiAgentText.TITLE)
    SettingsGroup {
        SettingsRow(
            "无障碍服务", icon = IrisIcons.Eye,
            subtitle = when { connected -> GuiAgentText.ON; enabled -> GuiAgentText.CONNECTING; else -> GuiAgentText.OFF },
            onClick = { IrisAccessibilityService.openSettings(context) },
            trailing = { com.iris.localai.ui.components.Chevron() },
        )
        if (!connected) {
            Divider()
            SettingsRow("允许受限制的设置", icon = IrisIcons.Info, subtitle = GuiAgentText.RESTRICTED,
                onClick = { IrisAccessibilityService.openAppInfo(context) })
        }
        Divider()
        AdvancedFold("gui-agent", "自检与试运行") {
            SettingsRow(
                "自检", icon = IrisIcons.Check,
                subtitle = check ?: "截一张图、读一次界面元素，不做任何操作",
                enabled = connected,
                onClick = { scope.launch { check = "检查中…"; check = runner.selfTest() } },
            )
            Divider()
            SettingsRow(
                if (run.running) "停止试运行" else "试运行",
                icon = if (run.running) IrisIcons.Stop else IrisIcons.Agent,
                subtitle = when {
                    run.running -> run.lines.lastOrNull() ?: "运行中…"
                    run.result != null -> run.result
                    else -> runner.blockerText() ?: "输入一个任务，用当前模型在本机屏幕上执行"
                },
                enabled = run.running || connected,
                onClick = { if (run.running) runner.stop() else asking = true },
            )
        }
    }
    if (run.lines.isNotEmpty()) GroupFootnote(run.lines.takeLast(12).joinToString("\n"), monospace = true)
    GroupFootnote(GuiAgentText.NOTE)

    if (asking) {
        TextInputDialog(
            title = "GUI 任务", initial = "", placeholder = "例如：打开设置，把屏幕亮度调到最高",
            confirmText = "开始", singleLine = false,
            onConfirm = { runner.start(it) }, onDismiss = { asking = false },
        )
    }
}

/**
 * 2.1.9: the chat's guide when 「操控手机」 cannot start — 无障碍 off (去开启 / 受限设置, the same
 * guide as the settings section) or no vision model (选择模型 opens the model picker).
 */
@Composable
fun GuiSetupDialog(blocker: com.iris.localai.agent.GuiBlocker, onDismiss: () -> Unit, onPickModel: () -> Unit) {
    val context = LocalContext.current
    when (blocker) {
        com.iris.localai.agent.GuiBlocker.A11Y_OFF -> com.iris.localai.ui.components.IrisDialog(
            onDismiss = onDismiss, title = "开启无障碍服务",
            confirmText = "去开启", onConfirm = { onDismiss(); IrisAccessibilityService.openSettings(context) },
            dismissText = "受限设置", onDismissClick = { onDismiss(); IrisAccessibilityService.openAppInfo(context) },
        ) {
            androidx.compose.material3.Text(GuiSetupText.A11Y)
        }
        com.iris.localai.agent.GuiBlocker.NO_MODEL, com.iris.localai.agent.GuiBlocker.NO_VISION -> com.iris.localai.ui.components.IrisDialog(
            onDismiss = onDismiss, title = "需要识图模型",
            confirmText = "选择模型", onConfirm = onPickModel,
        ) {
            androidx.compose.material3.Text(GuiSetupText.VISION)
        }
        com.iris.localai.agent.GuiBlocker.BUSY -> Unit
    }
}

internal object GuiSetupText {
    const val A11Y = "操控手机要用到「IRIDIS GUI 智能体」无障碍服务。\n" +
        "点「去开启」：系统设置 › 无障碍 › 已下载的应用 › IRIDIS GUI 智能体 › 开启。\n" +
        "如果提示「受限设置」，点「受限设置」打开应用信息，右上角 ⋮ › 允许受限制的设置，再回去开启。开好后在聊天里再说一次。"
    const val VISION = "操控手机需要能读图的模型看屏幕。请加载一个识图模型（如 Qwen3-VL MNN），再说一次。"
}

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
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b2740e)
    val ON: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0413be)
    val CONNECTING: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_96cd4a)
    val OFF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2696c0)
    val RESTRICTED: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_fd59e1)
    /** 2.1.9: 清后台 turns the service off on HyperOS (2.5.0 Beta5: lock / battery tips only, 保活 removed). */
    val KEEP: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_76907b)
    val NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_b1aa64)
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
            com.iris.localai.I18n.s(com.iris.localai.R.string.agent_6279dc), icon = IrisIcons.Eye,
            subtitle = when { connected -> GuiAgentText.ON; enabled -> GuiAgentText.CONNECTING; else -> GuiAgentText.OFF },
            onClick = { IrisAccessibilityService.openSettings(context) },
            trailing = { com.iris.localai.ui.components.Chevron() },
        )
        if (!connected) {
            Divider()
            SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_281ed1), icon = IrisIcons.Info, subtitle = GuiAgentText.RESTRICTED,
                onClick = { IrisAccessibilityService.openAppInfo(context) })
        }
        Divider()
        AdvancedFold("gui-agent", com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f3183f)) {
            SettingsRow(
                com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3b7d06), icon = IrisIcons.Check,
                subtitle = check ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7e9e37),
                enabled = connected,
                onClick = { scope.launch { check = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d6a223); check = runner.selfTest() } },
            )
            Divider()
            SettingsRow(
                if (run.running) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_31bb97) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3e5caf),
                icon = if (run.running) IrisIcons.Stop else IrisIcons.Agent,
                subtitle = when {
                    run.running -> run.lines.lastOrNull() ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_8d11c5)
                    run.result != null -> run.result
                    else -> runner.blockerText() ?: com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0a2fb8)
                },
                enabled = run.running || connected,
                onClick = { if (run.running) runner.stop() else asking = true },
            )
        }
    }
    if (run.lines.isNotEmpty()) GroupFootnote(run.lines.takeLast(12).joinToString("\n"), monospace = true)
    GroupFootnote(GuiAgentText.NOTE)
    // 2.5.0 Beta5: 无障碍保活 card removed.

    if (asking) {
        TextInputDialog(
            title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_c43ee1), initial = "", placeholder = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_52b807),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_a3e3b8), singleLine = false,
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
            onDismiss = onDismiss, title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_e7dfa0),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_5e213d), onConfirm = { onDismiss(); IrisAccessibilityService.openSettings(context) },
            dismissText = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0415c4), onDismissClick = { onDismiss(); IrisAccessibilityService.openAppInfo(context) },
        ) {
            androidx.compose.material3.Text(GuiSetupText.A11Y)
        }
        com.iris.localai.agent.GuiBlocker.NO_MODEL, com.iris.localai.agent.GuiBlocker.NO_VISION -> com.iris.localai.ui.components.IrisDialog(
            onDismiss = onDismiss, title = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_946fdd),
            confirmText = com.iris.localai.I18n.s(com.iris.localai.R.string.chat_f2d373), onConfirm = onPickModel,
        ) {
            androidx.compose.material3.Text(GuiSetupText.VISION)
        }
        com.iris.localai.agent.GuiBlocker.BUSY -> Unit
    }
}

internal object GuiSetupText {
    val A11Y: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_ca727f) + GuiAgentText.KEEP
    val VISION: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_88f86e)
}

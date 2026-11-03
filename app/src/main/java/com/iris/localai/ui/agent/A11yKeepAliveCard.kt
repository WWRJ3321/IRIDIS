package com.iris.localai.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.iris.localai.agent.gui.android.A11yKeepAlive
import com.iris.localai.agent.gui.android.KeepAliveStatus
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object KeepAliveText {
    val TITLE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_abc12e)
    val NOTE: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_cf7099)
    val SHIZUKU_ON: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_614956)
    val SHIZUKU_OFF: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_852ed5)
    val ADB: String get() = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_f8858d)
}

/** 2.2.14 设置 › 智能体 › 无障碍保活. */
@Composable
fun A11yKeepAliveCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val status = remember(tick) { A11yKeepAlive.status(context) }
    val granted = status != KeepAliveStatus.NOT_GRANTED
    val shizuku = remember(tick) { A11yKeepAlive.shizukuRunning() }
    val battery = remember(tick) { A11yKeepAlive.batteryUnrestricted(context) }
    val cmd = A11yKeepAlive.adbCommand(context.packageName)

    SectionTitle(KeepAliveText.TITLE)
    SettingsGroup {
        SettingsRow(
            com.iris.localai.I18n.s(com.iris.localai.R.string.agent_3fea7c), icon = IrisIcons.Power,
            subtitle = status.label,
            subtitleColor = if (status == KeepAliveStatus.ACTIVE) Iris.colors.success else Iris.colors.textTertiary,
            onClick = { if (granted) { A11yKeepAlive.ensure(context); tick++ } },
        )
        if (!granted) {
            Divider()
            SettingsRow(
                com.iris.localai.I18n.s(com.iris.localai.R.string.agent_84a580), icon = IrisIcons.Bolt,
                subtitle = msg ?: if (shizuku) KeepAliveText.SHIZUKU_ON else KeepAliveText.SHIZUKU_OFF,
                onClick = {
                    if (!A11yKeepAlive.shizukuRunning()) { msg = KeepAliveText.SHIZUKU_OFF; tick++; return@SettingsRow }
                    A11yKeepAlive.requestShizuku { ok ->
                        if (!ok) { msg = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_2c0bbf); return@requestShizuku }
                        scope.launch {
                            msg = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d81eec)
                            val r = withContext(Dispatchers.IO) { A11yKeepAlive.grantViaShizuku(context) }
                            msg = r.fold({ com.iris.localai.I18n.s(com.iris.localai.R.string.agent_1324e4) }, { com.iris.localai.I18n.s(com.iris.localai.R.string.agent_d8f208, it.message) })
                            tick++
                        }
                    }
                },
            )
            Divider()
            SettingsRow(
                com.iris.localai.I18n.s(com.iris.localai.R.string.agent_735df4), icon = IrisIcons.Copy,
                subtitle = KeepAliveText.ADB + "\n" + cmd,
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                    Toast.makeText(context, com.iris.localai.I18n.s(com.iris.localai.R.string.chat_52e6ab), Toast.LENGTH_SHORT).show()
                },
            )
            Divider()
            SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_013dd6), icon = IrisIcons.Settings,
                subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_da791a),
                onClick = { A11yKeepAlive.openDeveloperOptions(context) },
                trailing = { com.iris.localai.ui.components.Chevron() })
        }
        Divider()
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7397e9), icon = IrisIcons.Settings,
            subtitle = com.iris.localai.I18n.s(com.iris.localai.R.string.agent_94ccef),
            onClick = { A11yKeepAlive.openAutostart(context) },
            trailing = { com.iris.localai.ui.components.Chevron() })
        Divider()
        SettingsRow(com.iris.localai.I18n.s(com.iris.localai.R.string.agent_7c33fd), icon = IrisIcons.Bolt,
            subtitle = if (battery) com.iris.localai.I18n.s(com.iris.localai.R.string.agent_dc3883) else com.iris.localai.I18n.s(com.iris.localai.R.string.agent_0843eb),
            onClick = { A11yKeepAlive.openBatterySettings(context) },
            trailing = { com.iris.localai.ui.components.Chevron() })
    }
    GroupFootnote(KeepAliveText.NOTE)
}

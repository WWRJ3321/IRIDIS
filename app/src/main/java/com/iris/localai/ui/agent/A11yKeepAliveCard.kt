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
    const val TITLE = "无障碍保活"
    const val NOTE = "授权后，App 启动、开机和无障碍被系统关掉时，IRIDIS 会自动把「IRIDIS GUI 智能体」重新打开。" +
        "只写入无障碍开关两项设置，不做其他事。"
    const val SHIZUKU_ON = "一键授权（Shizuku 已运行）"
    const val SHIZUKU_OFF = "未检测到 Shizuku：安装并启动 Shizuku 后点这里；或用下方 adb 命令"
    const val ADB = "电脑连接（USB 调试）或开发者选项 › 无线调试后，在电脑上执行："
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
            "状态", icon = IrisIcons.Power,
            subtitle = status.label,
            subtitleColor = if (status == KeepAliveStatus.ACTIVE) Iris.colors.success else Iris.colors.textTertiary,
            onClick = { if (granted) { A11yKeepAlive.ensure(context); tick++ } },
        )
        if (!granted) {
            Divider()
            SettingsRow(
                "Shizuku 授权", icon = IrisIcons.Bolt,
                subtitle = msg ?: if (shizuku) KeepAliveText.SHIZUKU_ON else KeepAliveText.SHIZUKU_OFF,
                onClick = {
                    if (!A11yKeepAlive.shizukuRunning()) { msg = KeepAliveText.SHIZUKU_OFF; tick++; return@SettingsRow }
                    A11yKeepAlive.requestShizuku { ok ->
                        if (!ok) { msg = "Shizuku 拒绝了授权"; return@requestShizuku }
                        scope.launch {
                            msg = "授权中…"
                            val r = withContext(Dispatchers.IO) { A11yKeepAlive.grantViaShizuku(context) }
                            msg = r.fold({ "已授权" }, { "失败：${it.message}" })
                            tick++
                        }
                    }
                },
            )
            Divider()
            SettingsRow(
                "复制 adb 命令", icon = IrisIcons.Copy,
                subtitle = KeepAliveText.ADB + "\n" + cmd,
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
            )
            Divider()
            SettingsRow("打开开发者选项", icon = IrisIcons.Settings,
                subtitle = "开启 USB 调试或无线调试（未开启开发者选项：关于手机 › 连点版本号 7 次）",
                onClick = { A11yKeepAlive.openDeveloperOptions(context) },
                trailing = { com.iris.localai.ui.components.Chevron() })
        }
        Divider()
        SettingsRow("允许自启动", icon = IrisIcons.Settings,
            subtitle = "开机后才能自动恢复无障碍",
            onClick = { A11yKeepAlive.openAutostart(context) },
            trailing = { com.iris.localai.ui.components.Chevron() })
        Divider()
        SettingsRow("电池不限制", icon = IrisIcons.Bolt,
            subtitle = if (battery) "已设为不限制" else "点按设为不限制（无限制 / 不优化）",
            onClick = { A11yKeepAlive.openBatterySettings(context) },
            trailing = { com.iris.localai.ui.components.Chevron() })
    }
    GroupFootnote(KeepAliveText.NOTE)
}

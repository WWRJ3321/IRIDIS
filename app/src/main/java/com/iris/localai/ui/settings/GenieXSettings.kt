package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.iris.localai.engine.NpuState
import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GenieXSupport
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.IrisProgressBar
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType

/**
 * 2.2.0, 设置 › 模型与后端 › GenieX (full flavour): the downloadable runtime.
 * 2.1.3: the global GGUF engine switch is gone — a GGUF model on the NPU runs on GenieX, on GPU / CPU on llama.cpp.
 */
@Composable
internal fun GenieXSection(vm: SettingsViewModel, gx: GenieXSupport) {
    SectionTitle("GenieX（高通）")
    SettingsGroup {
        GenieXRuntimeRow(gx, onInstall = vm::installGeniex, onCancel = vm::cancelGeniex, onDelete = vm::deleteGeniex)
    }
    GroupFootnote("GGUF 模型选 NPU 时用 GenieX 运行。AI Hub QAIRT 模型文件夹可直接导入，仅在 NPU 上运行。")
}

@Composable
private fun GenieXRuntimeRow(gx: GenieXSupport, onInstall: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    LaunchedEffect(gx) { gx.refreshState() }
    val st by gx.state.collectAsState()
    val chip = gx.chipLabel
    var askConsent by remember { mutableStateOf(false) }
    val install: () -> Unit = { if (GenieXConsent.accepted) onInstall() else askConsent = true }
    if (askConsent) {
        GenieXLicenseDialog(
            onAccept = { GenieXConsent.accepted = true; askConsent = false; onInstall() },
            onDismiss = { askConsent = false },
        )
    }
    val mb = { b: Long -> b shr 20 }
    val (subtitle, action) = when (val s = st) {
        is NpuState.Unsupported -> s.reason to null
        NpuState.NotInstalled ->
            "$chip。GenieX 引擎所需；约 ${mb(gx.downloadBytes)} MB 下载，只保留本机用到的库，安装后约 ${mb(gx.installedBytes)} MB" +
                (if (gx.qairtSupported) "" else "（本机不支持 QAIRT 包，仅 GGUF）") to ("下载" to install)
        is NpuState.Downloading -> "正在下载 ${mb(s.downloaded)} / ${mb(s.total)} MB" to ("取消" to onCancel)
        NpuState.Installed -> "$chip · 已安装（GenieX ${GenieX.VERSION}）" to ("删除" to onDelete)
        is NpuState.Failed -> s.message to ("重试" to install)
        is NpuState.NeedsRepair -> "$chip · 需要修复（${s.reason}）。重新下载即可修复" to ("修复" to install)
    }
    Column {
        SettingsRow(
            "GenieX 运行库",
            subtitle = subtitle,
            subtitleColor = if (st is NpuState.Failed || st is NpuState.NeedsRepair) Iris.colors.danger else Iris.colors.textTertiary,
            trailing = action?.let { (label, fn) ->
                {
                    IrisButton(
                        label, fn, compact = true,
                        style = if (label == "删除" || label == "取消") IrisButtonStyle.Text else IrisButtonStyle.Outline,
                        destructive = label == "删除",
                    )
                }
            },
        )
        (st as? NpuState.Downloading)?.let { d ->
            IrisProgressBar(
                progress = if (d.total > 0) d.downloaded.toFloat() / d.total else null,
                modifier = Modifier.padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, bottom = IrisDimens.space12),
            )
        }
    }
}

/** In-memory (per process) acceptance of Qualcomm's terms for the GenieX runtime; nothing is persisted. */
internal object GenieXConsent {
    @Volatile var accepted: Boolean = false
}

internal const val GENIEX_LICENSE_TITLE = "高通 GenieX 运行库许可"
internal const val GENIEX_LICENSE_BODY =
    "GenieX 的 Kotlin 接口层按 BSD-3-Clause 开源；它的原生运行库（含 Qualcomm AI Engine Direct / QNN、Hexagon 内核）为高通专有软件，" +
        "不属于本项目开源部分，将从 Maven Central（com.qualcomm.qti:geniex-android:${GenieX.VERSION}，失败时用阿里云镜像）下载，校验 SHA-256 后只保留本机需要的库。" +
        "下载即表示你同意高通的使用条款（Qualcomm Terms of Use）。"
internal const val GENIEX_LICENSE_URL = "https://www.qualcomm.com/site/terms-of-use"

@Composable
private fun GenieXLicenseDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    IrisDialog(
        onDismiss = onDismiss,
        title = GENIEX_LICENSE_TITLE,
        confirmText = "同意并下载",
        onConfirm = onAccept,
        dismissText = "取消",
    ) {
        Column {
            Text(GENIEX_LICENSE_BODY, style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                "查看条款", {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GENIEX_LICENSE_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                style = IrisButtonStyle.Text, compact = true,
            )
        }
    }
}

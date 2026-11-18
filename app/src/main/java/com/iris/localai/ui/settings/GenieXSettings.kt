package com.iris.localai.ui.settings

import com.iris.localai.I18n
import com.iris.localai.R
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
 * 2.2.0, 设置 › 模型与加载 › GenieX (full flavour): the downloadable runtime.
 * the global GGUF engine switch is gone — a GGUF model on the NPU runs on GenieX, on GPU / CPU on llama.cpp.
 */
@Composable
internal fun GenieXSection(vm: SettingsViewModel, gx: GenieXSupport) {
    SectionTitle(I18n.s(R.string.settings_0ab786))
    SettingsGroup {
        GenieXRuntimeRow(gx, onInstall = vm::installGeniex, onCancel = vm::cancelGeniex, onDelete = vm::deleteGeniex)
    }
    GroupFootnote(I18n.s(R.string.settings_9a3ae4))
}

@Composable
internal fun GenieXRuntimeRow(gx: GenieXSupport, onInstall: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
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
            I18n.s(R.string.settings_dc39b3, chip, mb(gx.downloadBytes), mb(gx.installedBytes)) +
                (if (gx.qairtSupported) "" else I18n.s(R.string.settings_cd84be)) to (I18n.s(R.string.settings_f26ef9) to install)
        is NpuState.Downloading -> I18n.s(R.string.settings_2b5c7e, mb(s.downloaded), mb(s.total)) to (I18n.s(R.string.chat_625fb2) to onCancel)
        NpuState.Installed -> I18n.s(R.string.npu27_geniex_installed, GenieX.VERSION) to (I18n.s(R.string.chat_2f4aad) to onDelete)
        is NpuState.Failed -> s.message to (I18n.s(R.string.settings_132c5c) to install)
        is NpuState.NeedsRepair -> I18n.s(R.string.settings_5a6769, chip, s.reason) to (I18n.s(R.string.settings_f82661) to install)
    }
    Column {
        SettingsRow(
            I18n.s(R.string.settings_5bff1c),
            subtitle = subtitle,
            subtitleColor = if (st is NpuState.Failed || st is NpuState.NeedsRepair) Iris.colors.danger else Iris.colors.textTertiary,
            trailing = action?.let { (label, fn) ->
                {
                    IrisButton(
                        label, fn, compact = true,
                        style = if (label == I18n.s(R.string.chat_2f4aad) || label == I18n.s(R.string.chat_625fb2)) IrisButtonStyle.Text else IrisButtonStyle.Outline,
                        destructive = label == I18n.s(R.string.chat_2f4aad),
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

internal val GENIEX_LICENSE_TITLE: String get() = I18n.s(R.string.settings_6b9f80)
internal val GENIEX_LICENSE_BODY: String get() =
    I18n.s(R.string.settings_9f4b3f) +
        I18n.s(R.string.settings_c97ff7, GenieX.VERSION) +
        I18n.s(R.string.settings_358252)
internal const val GENIEX_LICENSE_URL = "https://www.qualcomm.com/site/terms-of-use"

@Composable
private fun GenieXLicenseDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    IrisDialog(
        onDismiss = onDismiss,
        title = GENIEX_LICENSE_TITLE,
        confirmText = I18n.s(R.string.settings_ad7951),
        onConfirm = onAccept,
        dismissText = I18n.s(R.string.chat_625fb2),
    ) {
        Column {
            Text(GENIEX_LICENSE_BODY, style = IrisType.secondary, color = Iris.colors.textSecondary)
            Spacer(Modifier.height(IrisDimens.space8))
            IrisButton(
                I18n.s(R.string.settings_81d193), {
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

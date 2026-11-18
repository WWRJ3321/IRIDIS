package com.iris.localai.ui.settings

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.resources.ResourceBackendKind
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.engine.resources.ReplyReport
import com.iris.localai.engine.resources.ResourcePlan
import com.iris.localai.engine.resources.ResourceTier
import com.iris.localai.engine.resources.SegmentReport
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisType

/** Pure text of the diagnostics page (unit-tested). */
object DiagnosticsText {
    fun tokPerSec(v: Float?): String = v?.let { "%.1f tok/s".format(it) } ?: "—"
    fun cpuPerToken(v: Float?): String = v?.let { "%.1f ms/token".format(it) } ?: "—"
    fun current(ma: Float?, samples: Int, charging: Boolean): String = when {
        ma == null -> I18n.s(R.string.settings_b8daf9)
        charging -> I18n.s(R.string.settings_38fba7).format(ma)
        else -> I18n.s(R.string.settings_200006).format(ma, samples)
    }

    /** "cpu5 100%" … sorted by CPU id; "—" when no running sample was taken. */
    fun cores(share: Map<Int, Float>): String =
        if (share.isEmpty()) "—" else share.toSortedMap().entries.joinToString("  ") { "cpu${it.key} ${kotlin.math.round(it.value).toInt()}%" }

    /** "1 线程 · cpu5 · nice 10" for one tier on this phone. */
    fun plan(p: ResourcePlan): String {
        val cores = when {
            p.cpus.isEmpty() -> I18n.s(R.string.settings_9ee927)
            p.cpus.size == 1 -> "cpu${p.cpus[0]}"
            p.cpus == (p.cpus.first()..p.cpus.last()).toList() -> "cpu${p.cpus.first()}-${p.cpus.last()}"
            else -> p.cpus.joinToString(",") { "cpu$it" }
        }
        return I18n.s(R.string.settings_308707, p.threads, cores, p.nice)
    }
}

/**
 * 设置 › 资源诊断: the last reply split by resource tier, from ResourceTelemetry (the same
 * numbers it logs under IrisResource), so the tiers can be compared without adb.
 */
@Composable
fun ResourceDiagnosticsPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val report by vm.telemetryReport.collectAsStateWithLifecycle()
    SubpageScaffold(I18n.s(R.string.settings_7ea069), onBack) {
        ScrollBody {
            val r = report
            if (r == null) {
                SectionTitle(I18n.s(R.string.settings_ef2768))
                SettingsGroup { SettingsRow(I18n.s(R.string.settings_c3cc2a), subtitle = I18n.s(R.string.settings_c55f8f)) }
            } else {
                ReplySection(r)
            }
            SectionTitle(I18n.s(R.string.settings_7f6fa4))
            SettingsGroup {
                // CPU and GPU/NPU profile sets (资源档配置) side by side.
                ResourceTier.entries.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    SettingsRow(
                        t.label,
                        subtitle = I18n.s(R.string.settings_83575b) + DiagnosticsText.plan(vm.tierPlan(t, ResourceBackendKind.CPU)) +
                            I18n.s(R.string.settings_78f6de) + DiagnosticsText.plan(vm.tierPlan(t, ResourceBackendKind.GPU)),
                    )
                }
            }
            GroupFootnote(I18n.s(R.string.settings_bcecaf))

            // 2.0.x: moved here from the settings home page (diagnostics, rarely needed).
            SectionTitle(I18n.s(R.string.settings_97dbb1))
            val st by vm.settings.collectAsStateWithLifecycle()
            SettingsGroup {
                SwitchRow(I18n.s(R.string.settings_b100d8), st.mnnNoVisual, subtitle = I18n.s(R.string.settings_38669a)) { on -> vm.update { it.copy(mnnNoVisual = on) } }
                Divider()
                SwitchRow(I18n.s(R.string.settings_c75698), st.mnnForceNoMmap, subtitle = I18n.s(R.string.settings_d0cdb4)) { on -> vm.update { it.copy(mnnForceNoMmap = on) } }
                Divider()
                CrashDumpRow()
            }
        }
    }
}

@Composable
private fun ReplySection(r: ReplyReport) {
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(r.atEpochMs))
    SectionTitle(I18n.s(R.string.settings_39ef97, time))
    SettingsGroup {
        SettingsRow(
            I18n.s(R.string.settings_92e3af),
            subtitle = I18n.s(R.string.settings_b2b81b, r.tokens ?: '?', DiagnosticsText.tokPerSec(r.tokPerSec), DiagnosticsText.cpuPerToken(r.cpuMsPerToken)),
        )
    }
    r.segments.forEachIndexed { i, s ->
        SectionTitle(if (r.segments.size > 1) I18n.s(R.string.settings_ef75f8, i + 1, s.tier) else I18n.s(R.string.settings_a3fa50, s.tier))
        SettingsGroup { SegmentBody(s, r.charging) }
    }
}

@Composable
private fun SegmentBody(s: SegmentReport, charging: Boolean) {
    val c = Iris.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space16, vertical = IrisDimens.space12)) {
        Metric(I18n.s(R.string.chat_03f385), DiagnosticsText.tokPerSec(s.tokPerSec))
        Metric("CPU", I18n.s(R.string.settings_76cc26, DiagnosticsText.cpuPerToken(s.cpuMsPerToken)).format(s.busyCores))
        Metric(I18n.s(R.string.settings_5ecd05), DiagnosticsText.current(s.avgMa, s.maSamples, charging))
        Metric(I18n.s(R.string.settings_338c76), DiagnosticsText.cores(s.coreShare))
        if (s.coreShare.isNotEmpty()) {
            Spacer(Modifier.height(IrisDimens.space8))
            CoreBars(s.coreShare)
        }
        Spacer(Modifier.height(IrisDimens.space2))
        Text("${s.tokens} tokens · ${s.wallMs} ms", style = IrisType.labelSmall, color = c.textTertiary)
    }
}

@Composable
private fun Metric(name: String, value: String) {
    val c = Iris.colors
    Row(Modifier.fillMaxWidth().padding(vertical = IrisSpace.XS), verticalAlignment = Alignment.Top) {
        Text(name, style = IrisType.labelSmall, color = c.textSecondary, modifier = Modifier.width(112.dp))
        Text(value, style = IrisType.labelSmall.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
    }
}

/** One quiet bar per CPU (0 … highest seen, at least 8) with the share of running samples. */
@Composable
private fun CoreBars(share: Map<Int, Float>) {
    val c = Iris.colors
    val n = maxOf(8, (share.keys.maxOrNull() ?: 0) + 1)
    Column {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val gap = IrisDimens.space6.toPx()
            val w = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val x = i * (w + gap)
                drawRoundRect(c.outline.copy(alpha = 0.35f), Offset(x, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
                val f = ((share[i] ?: 0f) / 100f).coerceIn(0f, 1f)
                if (f > 0f) {
                    val h = (size.height * f).coerceAtLeast(IrisDimens.space2.toPx())
                    drawRoundRect(c.textSecondary.copy(alpha = 0.8f), Offset(x, size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                Text("$i", style = IrisType.labelSmall, color = c.textTertiary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

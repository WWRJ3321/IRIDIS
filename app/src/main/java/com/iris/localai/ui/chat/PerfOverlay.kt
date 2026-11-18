package com.iris.localai.ui.chat

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.resources.CoolDown
import com.iris.localai.engine.resources.ThermalGovernor
import com.iris.localai.ui.theme.IrisSpace
import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.Format
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.util.diagnostics.PerfSampler
import com.iris.localai.util.diagnostics.RateWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Small in-app overlay (no system overlay permission) shown while a reply is generating:
 * output speed, this app's CPU and memory, and GPU load only where the kernel lets apps read it.
 * Sampling runs only while [generating]; otherwise nothing is polled.
 */
@Composable
fun PerfOverlay(vm: ChatViewModel, generating: Boolean, modifier: Modifier = Modifier) {
    if (!generating) return
    val c = Iris.colors
    val sampler = remember { PerfSampler() }
    val rate = remember { RateWindow(windowMs = 3_000) }
    var reading by remember { mutableStateOf<PerfSampler.Reading?>(null) }
    var speed by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        rate.clear()
        withContext(Dispatchers.IO) { sampler.sample() } // primes the CPU delta
        // counts visible characters from the reply's own timer (whitespace, <think> tags and
        // agent tool-call text were counted before, and the per-step stream reset made it drop to 0),
        // sampled every 250 ms over a 3 s window; system stats stay at once a second.
        var tick = 0
        while (true) {
            delay(250)
            rate.add(android.os.SystemClock.elapsedRealtime(), vm.liveTimer?.chars ?: 0)
            speed = rate.perSecond()
            if (++tick % 4 == 0) reading = withContext(Dispatchers.IO) { sampler.sample() }
        }
    }
    val r = reading
    Column(
        modifier
            // near-opaque card with a hairline edge: it floats over the messages, so the text
            // under it must not show through (it used to blend with the user's bubble).
            .shadow(IrisDimens.space4, IrisRadius.smallShape, clip = false)
            .background(c.surface.copy(alpha = 0.97f), IrisRadius.smallShape)
            .border(IrisDimens.hairline, c.outline, IrisRadius.smallShape)
            .padding(horizontal = IrisSpace.S, vertical = IrisDimens.space6),
    ) {
        val style = IrisType.monoSmall.copy(fontSize = IrisType.micro.fontSize, lineHeight = IrisType.micro.lineHeight)
        Text(I18n.s(R.string.chat_4485a1) + (speed?.let { String.format(Locale.US, I18n.s(R.string.chat_292451), it) } ?: "—"), style = style, color = c.text)
        Text("CPU " + (r?.cpuPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"), style = style, color = c.textSecondary)
        Text(I18n.s(R.string.chat_bcf4c8) + (r?.rssBytes?.let { Format.bytes(it) } ?: "—"), style = style, color = c.textSecondary)
        // 电池温度（系统广播，约每次变化 0.1 °C 时更新）
        val temp = ThermalGovernor.tempC
        // 按「过热暂停」上限标色：接近（少算）橙、到上限红；降温中加标记
        if (!temp.isNaN()) {
            val lim = CoolDown.limitC
            val tone = PerfTone.of(temp, lim)
            Text(I18n.s(R.string.pf259_temp, String.format(Locale.US, "%.1f", temp)) +
                (if (CoolDown.cooling) " · " + I18n.s(R.string.cool267_short) else ""),
                style = style, color = when (tone) { 2 -> c.danger; 1 -> c.warning; else -> c.textSecondary })
        }
        r?.gpuPercent?.let { Text(I18n.s(R.string.chat_4cb9bb, it), style = style, color = c.textSecondary) }
    }
}

/** 温度标色 0 正常 / 1 接近上限（已在少算）/ 2 到上限。上限关时沿用 42 °C 标红。纯函数。 */
internal object PerfTone {
    fun of(tempC: Float, limitC: Int): Int = when {
        tempC.isNaN() -> 0
        limitC <= 0 -> if (tempC >= 42f) 2 else 0
        tempC >= limitC -> 2
        CoolDown.hot(tempC, limitC) -> 1
        else -> 0
    }
}

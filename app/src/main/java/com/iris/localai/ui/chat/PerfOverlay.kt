package com.iris.localai.ui.chat

import com.iris.localai.ui.theme.IrisDimens
import androidx.compose.foundation.background
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
import com.iris.localai.util.PerfSampler
import com.iris.localai.util.RateWindow
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
        // 1.13.1: counts visible characters from the reply's own timer (whitespace, <think> tags and
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
            .background(c.surfaceVariant.copy(alpha = 0.92f), IrisRadius.smallShape)
            .padding(horizontal = 10.dp, vertical = IrisDimens.space6),
    ) {
        val style = IrisType.monoSmall.copy(fontSize = IrisType.micro.fontSize, lineHeight = IrisType.micro.lineHeight)
        Text("速度 " + (speed?.let { String.format(Locale.US, "%.1f 字/秒", it) } ?: "—"), style = style, color = c.text)
        Text("CPU " + (r?.cpuPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"), style = style, color = c.textSecondary)
        Text("内存 " + (r?.rssBytes?.let { Format.bytes(it) } ?: "—"), style = style, color = c.textSecondary)
        r?.gpuPercent?.let { Text("GPU $it%（整机）", style = style, color = c.textSecondary) }
    }
}

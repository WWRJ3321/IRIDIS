package com.iris.localai.v242

import com.iris.localai.engine.CpuTopology
import com.iris.localai.engine.ThermalCutoff
import com.iris.localai.imagegen.SdProgress
import com.iris.localai.imagegen.SdRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V242Test {
    @After fun off() { ThermalCutoff.setCutoff(0) }

    // ── 生图进度 ──
    private fun fold(steps: Int, vararg ev: Pair<Int, Int>): SdProgress.State =
        ev.fold(SdProgress.State()) { s, (d, t) -> SdProgress.next(s, d, t, steps) }

    @Test fun weightLoadIsNotAStep() {
        // user's run: text encoder 386 tensors, step 1 starts (0/8), diffusion model 452 tensors loaded lazily
        val loading = fold(8, 100 to 386)
        assertEquals(SdProgress.Phase.LOADING, loading.phase)
        assertTrue(SdProgress.label(loading, 8, 5, gpu = false).startsWith("加载模型权重 100/386"))
        val after = fold(8, 386 to 386, 0 to 8, 200 to 452, 452 to 452)
        assertEquals(SdProgress.Phase.FIRST_STEP, after.phase)
        val l = SdProgress.label(after, 8, 125, gpu = true)
        assertTrue(l, l.contains("第 1/8 步计算中") && l.contains("GPU") && l.contains("2:05"))
        assertNull(SdProgress.fraction(after, 8))
    }

    @Test fun stepsThenDecode() {
        val s3 = fold(8, 452 to 452, 0 to 8, 1 to 8, 2 to 8, 3 to 8)
        assertEquals(SdProgress.Phase.STEP, s3.phase)
        assertEquals(3f / 8, SdProgress.fraction(s3, 8)!!, 1e-6f)
        assertTrue(SdProgress.label(s3, 8, 0, false).contains("已完成 3/8 步，第 4 步"))
        // a weight reload between steps keeps the step count
        val reload = fold(8, 0 to 8, 1 to 8, 10 to 452, 452 to 452)
        assertEquals(SdProgress.Phase.STEP, reload.phase); assertEquals(1, reload.done)
        val dec = fold(8, 0 to 8, 8 to 8, 2 to 4)
        assertEquals(SdProgress.Phase.DECODING, dec.phase)
        assertTrue(SdProgress.label(dec, 8, 0, false).startsWith("解码图片 2/4"))
    }

    @Test fun sdThreadsSkipLittleCores() {
        // 1 + 5 + 2 (little cluster first) → 6 ; 6 + 2 → all 8 ; unknown → 0 (sd.cpp default)
        assertEquals(6, SdRules.threads(CpuTopology(listOf(listOf(0, 1), listOf(2, 3, 4, 5, 6), listOf(7)))))
        assertEquals(8, SdRules.threads(CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))))
        assertEquals(0, SdRules.threads(CpuTopology(emptyList())))
    }

    // ── 终止温度 ──
    @Test fun singleHeadroomSpikeOrSevereDoesNotTrip() {
        ThermalCutoff.setCutoff(52)
        assertFalse(ThermalCutoff.check(43.1f, 3, 1.05f, active = true, nowMs = 0))       // SEVERE + one spike
        assertFalse(ThermalCutoff.check(43.1f, 2, 1.02f, active = true, nowMs = 8_000))
        assertFalse(ThermalCutoff.check(43.1f, 2, 0.90f, active = true, nowMs = 16_000)) // streak broken
        assertFalse(ThermalCutoff.check(43.1f, 2, 1.01f, active = true, nowMs = 24_000))
        assertFalse(ThermalCutoff.check(43.1f, 2, 1.01f, active = true, nowMs = 32_000))
        assertTrue(ThermalCutoff.check(43.1f, 2, 1.01f, active = true, nowMs = 44_000))   // held 20 s, 3 readings
        assertEquals(ThermalCutoff.Reason.SYSTEM, ThermalCutoff.lastReason)
        assertTrue(ThermalCutoff.notice(43.1f).contains("系统报告严重过热"))
    }

    @Test fun criticalTripsAndBatteryNoticeNamesBattery() {
        ThermalCutoff.setCutoff(52)
        assertTrue(ThermalCutoff.check(45f, 4, Float.NaN, active = true))
        ThermalCutoff.rearm()
        assertTrue(ThermalCutoff.check(52.3f, 0, Float.NaN, active = true))
        assertEquals(ThermalCutoff.Reason.BATTERY, ThermalCutoff.lastReason)
        assertTrue(ThermalCutoff.notice(52.3f).contains("电池 52.3°C"))
    }

    @Test fun reloadAfterTripIsProtectedAgain() {
        ThermalCutoff.setCutoff(52)
        assertTrue(ThermalCutoff.check(52.5f, 0, Float.NaN, active = true))
        ThermalCutoff.check(51.0f, 0, Float.NaN, active = false)   // still latched (not cooled 2°C)
        assertNull(ThermalCutoff.startOrRefuse())                    // 51.0 < 52: load allowed, re-armed
        assertTrue(ThermalCutoff.check(52.1f, 0, Float.NaN, active = true)) // 2.4.1 stayed silent here
    }

    @Test fun refusesWhileStillOver() {
        ThermalCutoff.setCutoff(48)
        ThermalCutoff.check(49.2f, 0, Float.NaN, active = false)
        val msg = ThermalCutoff.startOrRefuse()
        assertNotNull(msg); assertTrue(msg!!.contains("49.2°C") && msg.contains("48°C"))
        assertNull(ThermalCutoff.refusal(47f, 48))
        assertNull(ThermalCutoff.refusal(60f, 0))
    }

    @Test fun cutoffReleasesTranslatorAndNamesWait() {
        val app = java.io.File("app/src/main/java/com/iris/localai/IrisApp.kt").readText()
        assertTrue(app.contains("runCatching { container.translator.release() }   // 2.4.2"))
        assertTrue(app.contains("当前计算结束后完成卸载"))
        val em = java.io.File("app/src/main/java/com/iris/localai/engine/EngineManager.kt").readText()
        assertEquals(2, Regex("ThermalCutoff.startOrRefuse\\(\\)").findAll(em).count())
        val repo = java.io.File("app/src/main/java/com/iris/localai/imagegen/ImageGenRepository.kt").readText()
        assertTrue(repo.contains("ThermalCutoff.startOrRefuse()"))
    }
}

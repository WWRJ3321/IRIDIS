package com.iris.localai.imagegen

import com.iris.localai.imagegen.EulerScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EulerSchedulerTest {
    @Test fun twentySteps() {
        val s = EulerScheduler()
        s.setTimesteps(20)
        assertEquals(20, s.timesteps.size)
        assertEquals(21, s.sigmas.size)
        assertEquals(999f, s.timesteps[0], 1e-4f)
        assertEquals(0f, s.timesteps[19], 1e-4f)
        assertEquals(14.6146f, s.sigmas[0], 0.01f)
        assertEquals(0f, s.sigmas[20], 0f)
        for (i in 0 until 20) assertTrue(s.sigmas[i] > s.sigmas[i + 1])
        assertEquals(sqrt(14.6146f * 14.6146f + 1f), s.initNoiseSigma, 0.01f)
        // timestep 0 sigma ≈ sqrt(β0/(1−β0))
        assertEquals(0.0292f, s.sigmas[19], 0.001f)
    }

    @Test fun scaleAndStep() {
        val s = EulerScheduler(); s.setTimesteps(10)
        val x = floatArrayOf(2f, -4f)
        val sc = s.scaleModelInput(x, 0)
        val d = sqrt(s.sigmas[0] * s.sigmas[0] + 1f)
        assertEquals(2f / d, sc[0], 1e-5f)
        val out = s.step(floatArrayOf(1f, 1f), 0, x)
        val dt = s.sigmas[1] - s.sigmas[0]
        assertEquals(2f + dt, out[0], 1e-5f)
        assertEquals(-4f + dt, out[1], 1e-5f)
    }
}

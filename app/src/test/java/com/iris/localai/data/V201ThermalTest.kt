package com.iris.localai.data

import com.iris.localai.data.AppSettings
import com.iris.localai.engine.resources.ThermalGovernor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class V201ThermalTest {
    @Test fun defaultOffAndSanitized() {
        assertEquals(0, AppSettings().thermalLimitC)
        assertEquals(0, ThermalGovernor.sanitize(0))
        assertEquals(35, ThermalGovernor.sanitize(20))
        assertEquals(50, ThermalGovernor.sanitize(70))
        assertEquals(42, ThermalGovernor.sanitize(42))
    }

    @Test fun pacingSteps() {
        assertEquals(0L, ThermalGovernor.intervalMs(41.9f, 42, false))
        assertEquals(0L, ThermalGovernor.intervalMs(45f, 0, false))
        assertEquals(0L, ThermalGovernor.intervalMs(Float.NaN, 42, false))
        assertEquals(100L, ThermalGovernor.intervalMs(42f, 42, false))
        assertEquals(167L, ThermalGovernor.intervalMs(43.5f, 42, false))
        assertEquals(250L, ThermalGovernor.intervalMs(44.2f, 42, false))
        assertEquals(500L, ThermalGovernor.intervalMs(48f, 42, false))
        // hysteresis: stays engaged until 1 °C below the limit
        assertEquals(100L, ThermalGovernor.intervalMs(41.2f, 42, true))
        assertEquals(0L, ThermalGovernor.intervalMs(40.9f, 42, true))
    }

}

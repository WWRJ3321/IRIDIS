package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.geniex.GgufEngineRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 崩溃后为安全关闭 OpenCL 时，GPU 不可用的原因要说清楚，而不是「没有驱动」。 */
class GpuSafetyOffTest {
    private fun env(off: Boolean) = BackendEnvironment(hasOpenCl = false, npuSoc = null, npuRuntimeInstalled = false, fullFlavor = true, openClOff = off)

    @Test fun reasonSaysSafetyOffNotMissingDriver() {
        val off = env(true).noGpuReason()
        val none = env(false).noGpuReason()
        assertTrue(off, off.contains("崩溃") && off.contains("GPU"))
        assertEquals("本机没有可用的 OpenCL 驱动", none)
    }

    @Test fun optionsCarryTheReason() {
        val gguf = GgufEngineRule.options(env(true)).first { it.backend == Backend.GPU }
        assertFalse(gguf.available)
        assertEquals(env(true).noGpuReason(), gguf.reason)
        val mnn = BackendCapabilities.options(ModelFormat.MNN, "x.mnn", env(false)).first { it.backend == Backend.GPU }
        assertEquals("本机没有可用的 OpenCL 驱动", mnn.reason)
    }
}

package com.iris.localai.engine

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V236Test {
    @Test fun ggufGpuIsVulkanOffQualcomm() {
        val mali = BackendEnvironment(hasOpenCl = false, npuSoc = null, npuRuntimeInstalled = false, fullFlavor = true, hasVulkan = true)
        assertTrue(mali.ggufGpu)
        assertTrue(BackendCapabilities.options(ModelFormat.GGUF, "a.gguf", mali).first { it.backend == Backend.GPU }.available)
        val none = BackendEnvironment(hasOpenCl = false, npuSoc = null, npuRuntimeInstalled = false, fullFlavor = true)
        assertFalse(none.ggufGpu)
        val adreno = BackendEnvironment(hasOpenCl = true, npuSoc = "sm8750", npuRuntimeInstalled = false, fullFlavor = true)
        assertTrue(adreno.ggufGpu)
    }

}

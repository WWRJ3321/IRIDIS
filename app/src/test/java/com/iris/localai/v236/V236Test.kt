package com.iris.localai.v236

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

    @Test fun nativeVulkanGateBeforeBackendInit() {
        val src = File("app/src/main/cpp/iris_engine.cpp").readText()
        val gate = src.indexOf("GGML_DISABLE_VULKAN")
        val init = src.indexOf("llama_backend_init();")
        assertTrue(gate in 0 until init)
        assertTrue(File("scripts/fetch-deps.sh").readText().contains("bee23322f1baa3d2dd16b8339bed6aff4a8eb663"))
    }
}

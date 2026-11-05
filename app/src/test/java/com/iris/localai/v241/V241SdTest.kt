package com.iris.localai.v241

import com.iris.localai.imagegen.SdCppEngine
import com.iris.localai.imagegen.SdRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2.4.1 G2: Z-Image / FLUX on the GPU without the text encoder in Vulkan memory, a RAM check, 重试 GPU / 改用 CPU. */
class V241SdTest {
    @Test fun textEncoderStaysOnTheCpu() {
        assertEquals("diffusion=vulkan0,te=cpu,vae=vulkan0", SdRules.GPU)
        assertEquals(listOf(SdRules.GPU, SdRules.CPU), SdRules.backends(gpuCrashedBefore = false, preferCpu = false))
        assertEquals(listOf(SdRules.CPU), SdRules.backends(gpuCrashedBefore = false, preferCpu = true))
    }

    @Test fun memoryCheck() {
        val gb = 1024L * 1024L * 1024L
        val gpu1024 = SdRules.neededMb(true, 3 * gb, gb / 4, 1024, 1024)
        val gpu768 = SdRules.neededMb(true, 3 * gb, gb / 4, 768, 768)
        val cpu1024 = SdRules.neededMb(false, 3 * gb, gb / 4, 1024, 1024)
        assertTrue(gpu1024 > gpu768 && gpu768 > cpu1024)
        assertTrue("$gpu1024", gpu1024 in 4500L..5500L)
        assertNull(SdRules.memoryProblem(8000, gpu1024, true, 1024, 1024))
        val p = SdRules.memoryProblem(3000, gpu1024, true, 1024, 1024)!!
        assertTrue(p, p.contains("1024×1024") && p.contains("改用 CPU") && p.contains("2.9"))
        val c = SdRules.memoryProblem(1000, cpu1024, false, 1024, 1024)!!
        assertFalse(c, c.contains("改用 CPU"))
    }

    @Test fun retryGpuAndUseCpu() {
        val dir: File = Files.createTempDirectory("sd241").toFile()
        val e = SdCppEngine(rootOf = { null }, stateDir = dir)
        assertFalse(e.usesCpu)
        e.setPreferCpu(true); assertTrue(e.usesCpu)
        e.clearGpuCrash(); assertFalse(e.usesCpu)
        File(dir, SdRules.CRASH_MARKER).writeText("1")   // a run that died on the GPU
        assertTrue(e.gpuCrashedBefore && e.usesCpu)
        e.clearGpuCrash()                                   // 「重试 GPU」
        assertFalse(e.gpuCrashedBefore || e.usesCpu)
        dir.deleteRecursively()
    }
}

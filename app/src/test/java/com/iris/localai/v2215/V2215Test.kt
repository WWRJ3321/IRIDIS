package com.iris.localai.v2215

import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.geniex.GenieX
import com.iris.localai.engine.geniex.GgufEngineRule
import com.iris.localai.engine.llama.LlamaHtpRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2215Test {
    private fun env(gx: String?, htp: Boolean) = BackendEnvironment(
        hasOpenCl = true, npuSoc = null, npuRuntimeInstalled = false, fullFlavor = true, geniexBlock = gx, htpReady = htp,
    )

    @Test fun npuEnginesOrder() {
        assertEquals(listOf(GenieX.ENGINE_GENIEX, GenieX.ENGINE_LLAMA), GgufEngineRule.npuEngines(true, true))
        assertEquals(listOf(GenieX.ENGINE_LLAMA), GgufEngineRule.npuEngines(false, true))
        assertEquals(listOf(GenieX.ENGINE_GENIEX), GgufEngineRule.npuEngines(true, false))
        assertTrue(GgufEngineRule.npuEngines(false, false).isEmpty())
    }

    @Test fun npuAvailableWithoutGenieXWhenHtpReady() {
        val npu = GgufEngineRule.options(env(GenieX.NEED_RUNTIME, true)).first { it.backend == Backend.NPU }
        assertTrue(npu.available)
        assertNull(GgufEngineRule.npuReason(env(GenieX.NEED_RUNTIME, true)))
        assertEquals(GgufEngineRule.NEED_GENIEX, GgufEngineRule.npuReason(env(GenieX.NEED_RUNTIME, false)))
        assertTrue(GgufEngineRule.options(env(null, false)).first { it.backend == Backend.NPU }.available)
    }

    @Test fun htpFilesAndAdspPath() {
        assertEquals(listOf("libiris_lhtp.so", "libiris-hexagon.so", "libiris-htp-v79.so"), LlamaHtpRules.files(79))
        assertTrue(73 in LlamaHtpRules.ARCHS && 81 in LlamaHtpRules.ARCHS && 69 !in LlamaHtpRules.ARCHS)
        assertEquals("/a;/g", LlamaHtpRules.adspPath("/a", "/g;/a"))
        assertEquals("/a", LlamaHtpRules.adspPath("/a", null))
        // renamed names keep the original byte lengths (in-place binary patch)
        assertEquals("libggml-htp-v79.so".length, LlamaHtpRules.skel(79).length)
        assertEquals("libggml-base.so".length, LlamaHtpRules.JNI_LIB.length)
        assertEquals("libggml-hexagon.so".length, LlamaHtpRules.HOST_LIB.length)
    }
}

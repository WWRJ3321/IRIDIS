package com.iris.localai.v217.imagegen

import com.iris.localai.imagegen.ProbeFacts
import com.iris.localai.imagegen.SelfCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckTest {
    private val good = ProbeFacts("SM8750", 79, true, true, true, true, 6000, setOf("sd15-qnn"))
    private fun v(f: ProbeFacts) = SelfCheck.verdicts(f).associateBy { it.modelId }

    @Test fun capableDevice() {
        val m = v(good)
        assertTrue(m["sd15-qnn"]!!.ok); assertFalse(m["sd15-qnn"]!!.reasonZh.contains("未下载"))
        assertTrue(m["sd15-mnn"]!!.ok); assertTrue(m["sd15-mnn"]!!.reasonZh.contains("未下载"))
        assertTrue(m["flux2-klein-4b"]!!.ok)
        assertTrue(m["z-image-turbo"]!!.ok)
    }

    @Test fun qnnRules() {
        assertFalse(v(good.copy(htpArch = 69))["sd15-qnn"]!!.ok)
        assertFalse(v(good.copy(htpArch = null))["sd15-qnn"]!!.ok)
        assertTrue(v(good.copy(htpArch = 73))["sd15-qnn"]!!.ok)
        assertFalse(v(good.copy(qnnRuntimePresent = false))["sd15-qnn"]!!.ok)
        assertFalse(v(good.copy(qnnBackendOk = false))["sd15-qnn"]!!.ok)
        assertFalse(v(good.copy(unsignedPd = false))["sd15-qnn"]!!.ok)
        assertFalse(v(good.copy(freeRamMb = 2499))["sd15-qnn"]!!.ok)
        assertTrue(v(good.copy(freeRamMb = 2500))["sd15-qnn"]!!.ok)
    }

    @Test fun mnnRam() {
        val low = v(good.copy(freeRamMb = 2999))["sd15-mnn"]!!
        assertFalse(low.ok); assertTrue(low.reasonZh.contains("内存"))
        assertTrue(v(good.copy(freeRamMb = 3000))["sd15-mnn"]!!.ok)
    }

    @Test fun reportIsChineseAndListsAll() {
        val r = SelfCheck.report(good, SelfCheck.verdicts(good))
        assertTrue(r.contains("SM8750")); assertTrue(r.contains("可用内存"))
        for (id in listOf("sd15-qnn", "sd15-mnn", "flux2-klein-4b", "z-image-turbo")) assertTrue(r.contains(id))
    }
}

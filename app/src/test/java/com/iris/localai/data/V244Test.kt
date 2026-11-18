package com.iris.localai.data

import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.Backend
import com.iris.localai.engine.BackendCapabilities
import com.iris.localai.engine.BackendEnvironment
import com.iris.localai.engine.NpuIds
import com.iris.localai.data.RecommendedCatalog as R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.4.4 (Issue #6): MediaTek NeuroPilot NPU through LiteRT-LM. */
class V244Test {
    private val mt = "Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm"

    @Test fun mediatekFileNamesAndChips() {
        assertEquals("mt6991", NpuIds.targetSoc(mt))
        assertEquals("mt6989", NpuIds.targetSoc("Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm"))
        assertEquals(1280, NpuIds.kvCapacity(mt))
        assertEquals("sm8750", NpuIds.targetSoc("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertNull(NpuIds.targetSoc("gemma3-1b-it-int4.litertlm"))
        assertEquals("mt6991", NpuIds.baseSocId("MT6991"))
        assertEquals("mt6989", NpuIds.baseSocId("MT6989W"))
        assertEquals("mt6991", NpuIds.mediatekNpu("Mediatek", "MT6991"))
        assertEquals("mt6993", NpuIds.mediatekNpu("MediaTek", "MT6993Z"))
        assertNull(NpuIds.mediatekNpu("Mediatek", "MT6878")) // Dimensity 7300: no official LLM NPU build
        assertNull(NpuIds.mediatekNpu("QTI", "SM8750"))
        assertTrue(NpuIds.socMatches("mt6991", "mt6991"))
        assertFalse(NpuIds.socMatches("mt6989", "mt6991"))
        assertFalse(NpuIds.socMatches("mt6991", "sm8750"))
        assertTrue(NpuIds.isMediatekTarget("mt6991")); assertFalse(NpuIds.isMediatekTarget("sm8750")); assertFalse(NpuIds.isMediatekTarget(null))
        assertEquals(null, NpuIds.hexagonArch("Mediatek", "MT6991"))
    }

    @Test fun backendOptionsForMediatekFile() {
        val env = BackendEnvironment(hasOpenCl = false, npuSoc = "mt6991", npuRuntimeInstalled = true, fullFlavor = true)
        val opts = BackendCapabilities.options(ModelFormat.LITERT, mt, env)
        val npu = opts.first { it.backend == Backend.NPU }
        assertTrue(npu.available); assertEquals("MediaTek · NeuroPilot", npu.detail)
        assertFalse(opts.first { it.backend == Backend.CPU }.available)
        val other = BackendCapabilities.options(ModelFormat.LITERT, "Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm", env)
        assertFalse(other.first { it.backend == Backend.NPU }.available)
        assertEquals(1280, BackendCapabilities.features(ModelFormat.LITERT, mt).fixedContext)
    }

    @Test fun catalogShowsOnlyThisChipsBuild() {
        val before = R.deviceSoc
        try {
            R.deviceSoc = null
            assertTrue(R.section(R.Section.CHAT).none { it.onlySoc != null })
            R.deviceSoc = "sm8750"
            assertTrue(R.section(R.Section.CHAT).none { it.onlySoc != null })
            R.deviceSoc = "mt6991"
            val mtk = R.section(R.Section.CHAT).filter { it.onlySoc != null }
            assertEquals(1, mtk.size)
            val k = mtk[0].kind as R.Kind.Hub
            assertEquals(R.GEMMA3_1B, k.repoId); assertEquals(mt, k.file)
            assertEquals(1_033_814_016L, mtk[0].sizeBytes)
            assertTrue(mtk[0].top)
            assertTrue(R.matchesLocal(R.GEMMA3_1B, mt))
        } finally { R.deviceSoc = before }
    }

    @Test fun downloadsFromModelScopeAndKeepsGenericPick() {
        assertTrue("litert-community/Gemma3-1B-IT" in com.iris.localai.data.DownloadSources.MODELSCOPE)
        val files = listOf(mt to 1L, "gemma3-1b-it-int4.litertlm" to 2L, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm" to 3L)
        assertEquals("gemma3-1b-it-int4.litertlm", com.iris.localai.data.MnnHub.pickLiteRtFile(files)?.first)
    }

    @Test fun npuLogKeepsNeuronLines() {
        val lines = listOf("E/NeuronAdapter( 123): compile failed", "I/Other( 1): nothing", "E/apusys: power on fail")
        val got = com.iris.localai.engine.NpuLog.relevant(lines, 10)
        assertTrue("NeuronAdapter" in got && "apusys" in got && "Other" !in got)
    }


    // ---- 删除消息 ----
    private fun msg(id: String, role: com.iris.localai.data.Role) = com.iris.localai.data.ChatMessage(id = id, role = role, content = id)






    private fun src(p: String) = java.io.File(p).readText()
}
